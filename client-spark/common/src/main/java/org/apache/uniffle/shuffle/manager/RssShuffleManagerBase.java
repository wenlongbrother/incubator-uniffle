/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.uniffle.shuffle.manager;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Maps;
import org.apache.hadoop.conf.Configuration;
import org.apache.spark.MapOutputTracker;
import org.apache.spark.MapOutputTrackerMaster;
import org.apache.spark.SparkConf;
import org.apache.spark.SparkEnv;
import org.apache.spark.SparkException;
import org.apache.spark.shuffle.RssSparkConfig;
import org.apache.spark.shuffle.RssSparkShuffleUtils;
import org.apache.spark.shuffle.ShuffleManager;
import org.apache.spark.shuffle.SparkVersionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.uniffle.common.RemoteStorageInfo;
import org.apache.uniffle.common.config.RssConf;
import org.apache.uniffle.common.exception.RssException;

import static org.apache.uniffle.common.config.RssClientConf.HADOOP_CONFIG_KEY_PREFIX;
import static org.apache.uniffle.common.config.RssClientConf.RSS_CLIENT_REMOTE_STORAGE_USE_LOCAL_CONF_ENABLED;

public abstract class RssShuffleManagerBase implements RssShuffleManagerInterface, ShuffleManager {
  private static final Logger LOG = LoggerFactory.getLogger(RssShuffleManagerBase.class);
  private AtomicBoolean isInitialized = new AtomicBoolean(false);
  private Method unregisterAllMapOutputMethod;
  private Method registerShuffleMethod;

  /**
   * Provides a task attempt id that is unique for a shuffle stage.
   *
   * <p>We are not using context.taskAttemptId() here as this is a monotonically increasing number
   * that is unique across the entire Spark app which can reach very large numbers, which can
   * practically reach LONG.MAX_VALUE. That would overflow the bits in the block id.
   *
   * <p>Here we use the map index or task id, appended by the attempt number per task. The map index
   * is limited by the number of partitions of a stage. The attempt number per task is limited /
   * configured by spark.task.maxFailures (default: 4).
   *
   * @return a task attempt id unique for a shuffle stage
   */
  @VisibleForTesting
  protected static long getTaskAttemptId(
      int mapIndex, int attemptNo, int maxFailures, boolean speculation, int maxTaskAttemptIdBits) {
    // attempt number is zero based: 0, 1, …, maxFailures-1
    // max maxFailures < 1 is not allowed but for safety, we interpret that as maxFailures == 1
    int maxAttemptNo = maxFailures < 1 ? 0 : maxFailures - 1;

    // with speculative execution enabled we could observe +1 attempts
    if (speculation) {
      maxAttemptNo++;
    }

    if (attemptNo > maxAttemptNo) {
      // this should never happen, if it does, our assumptions are wrong,
      // and we risk overflowing the attempt number bits
      throw new RssException(
          "Observing attempt number "
              + attemptNo
              + " while maxFailures is set to "
              + maxFailures
              + (speculation ? " with speculation enabled" : "")
              + ".");
    }

    int attemptBits = 32 - Integer.numberOfLeadingZeros(maxAttemptNo);
    int mapIndexBits = 32 - Integer.numberOfLeadingZeros(mapIndex);
    if (mapIndexBits + attemptBits > maxTaskAttemptIdBits) {
      throw new RssException(
          "Observing mapIndex["
              + mapIndex
              + "] that would produce a taskAttemptId with "
              + (mapIndexBits + attemptBits)
              + " bits which is larger than the allowed "
              + maxTaskAttemptIdBits
              + " bits (maxFailures["
              + maxFailures
              + "], speculation["
              + speculation
              + "]). Please consider providing more bits for taskAttemptIds.");
    }

    return (long) mapIndex << attemptBits | attemptNo;
  }

  @Override
  public void unregisterAllMapOutput(int shuffleId) throws SparkException {
    if (!RssSparkShuffleUtils.isStageResubmitSupported()) {
      return;
    }
    MapOutputTrackerMaster tracker = getMapOutputTrackerMaster();
    if (isInitialized.compareAndSet(false, true)) {
      unregisterAllMapOutputMethod = getUnregisterAllMapOutputMethod(tracker);
      registerShuffleMethod = getRegisterShuffleMethod(tracker);
    }
    if (unregisterAllMapOutputMethod != null) {
      try {
        unregisterAllMapOutputMethod.invoke(tracker, shuffleId);
      } catch (InvocationTargetException | IllegalAccessException e) {
        throw new RssException("Invoke unregisterAllMapOutput method failed", e);
      }
    } else {
      int numMaps = getNumMaps(shuffleId);
      int numReduces = getPartitionNum(shuffleId);
      defaultUnregisterAllMapOutput(tracker, registerShuffleMethod, shuffleId, numMaps, numReduces);
    }
  }

  private static void defaultUnregisterAllMapOutput(
      MapOutputTrackerMaster tracker,
      Method registerShuffle,
      int shuffleId,
      int numMaps,
      int numReduces)
      throws SparkException {
    if (tracker != null && registerShuffle != null) {
      tracker.unregisterShuffle(shuffleId);
      // re-register this shuffle id into map output tracker
      try {
        if (SparkVersionUtils.MAJOR_VERSION > 3
            || (SparkVersionUtils.isSpark3() && SparkVersionUtils.MINOR_VERSION >= 2)) {
          registerShuffle.invoke(tracker, shuffleId, numMaps, numReduces);
        } else {
          registerShuffle.invoke(tracker, shuffleId, numMaps);
        }
      } catch (InvocationTargetException | IllegalAccessException e) {
        throw new RssException("Invoke registerShuffle method failed", e);
      }
      tracker.incrementEpoch();
    } else {
      throw new SparkException(
          "default unregisterAllMapOutput should only be called on the driver side");
    }
  }

  private static Method getUnregisterAllMapOutputMethod(MapOutputTrackerMaster tracker) {
    if (tracker != null) {
      Class<? extends MapOutputTrackerMaster> klass = tracker.getClass();
      Method m = null;
      try {
        if (SparkVersionUtils.isSpark2() && SparkVersionUtils.MINOR_VERSION <= 3) {
          // for spark version less than 2.3, there's no unregisterAllMapOutput support
          LOG.warn("Spark version <= 2.3, fallback to default method");
        } else if (SparkVersionUtils.isSpark2()) {
          // this method is added in Spark 2.4+
          m = klass.getDeclaredMethod("unregisterAllMapOutput", int.class);
        } else if (SparkVersionUtils.isSpark3() && SparkVersionUtils.MINOR_VERSION <= 1) {
          // spark 3.1 will have unregisterAllMapOutput method
          m = klass.getDeclaredMethod("unregisterAllMapOutput", int.class);
        } else if (SparkVersionUtils.isSpark3()) {
          m = klass.getDeclaredMethod("unregisterAllMapAndMergeOutput", int.class);
        } else {
          LOG.warn(
              "Unknown spark version({}), fallback to default method",
              SparkVersionUtils.SPARK_VERSION);
        }
      } catch (NoSuchMethodException e) {
        LOG.warn(
            "Got no such method error when get unregisterAllMapOutput method for spark version({})",
            SparkVersionUtils.SPARK_VERSION);
      }
      return m;
    } else {
      return null;
    }
  }

  private static Method getRegisterShuffleMethod(MapOutputTrackerMaster tracker) {
    if (tracker != null) {
      Class<? extends MapOutputTrackerMaster> klass = tracker.getClass();
      Method m = null;
      try {
        if (SparkVersionUtils.MAJOR_VERSION > 3
            || (SparkVersionUtils.isSpark3() && SparkVersionUtils.MINOR_VERSION >= 2)) {
          // for spark >= 3.2, the register shuffle method is changed to signature:
          //   registerShuffle(shuffleId, numMapTasks, numReduceTasks);
          m = klass.getDeclaredMethod("registerShuffle", int.class, int.class, int.class);
        } else {
          m = klass.getDeclaredMethod("registerShuffle", int.class, int.class);
        }
      } catch (NoSuchMethodException e) {
        LOG.warn(
            "Got no such method error when get registerShuffle method for spark version({})",
            SparkVersionUtils.SPARK_VERSION);
      }
      return m;
    } else {
      return null;
    }
  }

  private static MapOutputTrackerMaster getMapOutputTrackerMaster() {
    MapOutputTracker tracker =
        Optional.ofNullable(SparkEnv.get()).map(SparkEnv::mapOutputTracker).orElse(null);
    return tracker instanceof MapOutputTrackerMaster ? (MapOutputTrackerMaster) tracker : null;
  }

  private static Map<String, String> parseRemoteStorageConf(Configuration conf) {
    Map<String, String> confItems = Maps.newHashMap();
    for (Map.Entry<String, String> entry : conf) {
      confItems.put(entry.getKey(), entry.getValue());
    }
    return confItems;
  }

  protected static RemoteStorageInfo getDefaultRemoteStorageInfo(SparkConf sparkConf) {
    Map<String, String> confItems = Maps.newHashMap();
    RssConf rssConf = RssSparkConfig.toRssConf(sparkConf);
    if (rssConf.getBoolean(RSS_CLIENT_REMOTE_STORAGE_USE_LOCAL_CONF_ENABLED)) {
      confItems = parseRemoteStorageConf(new Configuration(true));
    }

    for (String key : rssConf.getKeySet()) {
      if (key.startsWith(HADOOP_CONFIG_KEY_PREFIX)) {
        String val = rssConf.getString(key, null);
        if (val != null) {
          String extractedKey = key.replaceFirst(HADOOP_CONFIG_KEY_PREFIX, "");
          confItems.put(extractedKey, val);
        }
      }
    }

    return new RemoteStorageInfo(
        sparkConf.get(RssSparkConfig.RSS_REMOTE_STORAGE_PATH.key(), ""), confItems);
  }
}
