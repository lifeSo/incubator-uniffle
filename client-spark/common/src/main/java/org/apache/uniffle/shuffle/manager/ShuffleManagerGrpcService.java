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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

import io.grpc.stub.StreamObserver;
import org.apache.spark.shuffle.ShuffleHandleInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.uniffle.common.RemoteStorageInfo;
import org.apache.uniffle.common.ShuffleServerInfo;
import org.apache.uniffle.common.util.JavaUtils;
import org.apache.uniffle.proto.RssProtos;
import org.apache.uniffle.proto.ShuffleManagerGrpc.ShuffleManagerImplBase;

public class ShuffleManagerGrpcService extends ShuffleManagerImplBase {
  private static final Logger LOG = LoggerFactory.getLogger(ShuffleManagerGrpcService.class);
  private final Map<Integer, RssShuffleStatus> shuffleStatus = JavaUtils.newConcurrentMap();
  // The shuffleId mapping records the number of ShuffleServer write failures
  private final Map<Integer, ShuffleServerFailureRecord> shuffleWrtieStatus =
      JavaUtils.newConcurrentMap();
  private final RssShuffleManagerInterface shuffleManager;

  public ShuffleManagerGrpcService(RssShuffleManagerInterface shuffleManager) {
    this.shuffleManager = shuffleManager;
  }

  @Override
  public void reportShuffleWriteFailure(
      RssProtos.ReportShuffleWriteFailureRequest request,
      StreamObserver<RssProtos.ReportShuffleWriteFailureResponse> responseObserver) {
    String appId = request.getAppId();
    int shuffleId = request.getShuffleId();
    int stageAttemptNumber = request.getStageAttemptNumber();
    List<RssProtos.ShuffleServerId> shuffleServerIdsList = request.getShuffleServerIdsList();
    RssProtos.StatusCode code;
    boolean reSubmitWholeStage;
    String msg;
    if (!appId.equals(shuffleManager.getAppId())) {
      msg =
          String.format(
              "got a wrong shuffle write failure report from appId: %s, expected appId: %s",
              appId, shuffleManager.getAppId());
      LOG.warn(msg);
      code = RssProtos.StatusCode.INVALID_REQUEST;
      reSubmitWholeStage = false;
    } else {
      Map<String, AtomicInteger> shuffleServerInfoIntegerMap = JavaUtils.newConcurrentMap();
      List<ShuffleServerInfo> shuffleServerInfos =
          ShuffleServerInfo.fromProto(shuffleServerIdsList);
      shuffleServerInfos.forEach(
          shuffleServerInfo -> {
            shuffleServerInfoIntegerMap.put(shuffleServerInfo.getId(), new AtomicInteger(0));
          });
      ShuffleServerFailureRecord shuffleServerFailureRecord =
          shuffleWrtieStatus.computeIfAbsent(
              shuffleId,
              key ->
                  new ShuffleServerFailureRecord(shuffleServerInfoIntegerMap, stageAttemptNumber));
      boolean resetflag =
          shuffleServerFailureRecord.resetStageAttemptIfNecessary(stageAttemptNumber);
      if (resetflag) {
        msg =
            String.format(
                "got an old stage(%d vs %d) shuffle write failure report, which should be impossible.",
                shuffleServerFailureRecord.getStageAttempt(), stageAttemptNumber);
        LOG.warn(msg);
        code = RssProtos.StatusCode.INVALID_REQUEST;
        reSubmitWholeStage = false;
      } else {
        code = RssProtos.StatusCode.SUCCESS;
        // update the stage shuffleServer write failed count
        boolean fetchFailureflag =
            shuffleServerFailureRecord.incPartitionWriteFailure(
                stageAttemptNumber, shuffleServerInfos, shuffleManager);
        if (fetchFailureflag) {
          reSubmitWholeStage = true;
          msg =
              String.format(
                  "report shuffle write failure as maximum number(%d) of shuffle write is occurred",
                  shuffleManager.getMaxFetchFailures());
        } else {
          reSubmitWholeStage = false;
          msg = "don't report shuffle write failure";
        }
      }
    }

    RssProtos.ReportShuffleWriteFailureResponse reply =
        RssProtos.ReportShuffleWriteFailureResponse.newBuilder()
            .setStatus(code)
            .setReSubmitWholeStage(reSubmitWholeStage)
            .setMsg(msg)
            .build();
    responseObserver.onNext(reply);
    responseObserver.onCompleted();
  }

  @Override
  public void reportShuffleFetchFailure(
      RssProtos.ReportShuffleFetchFailureRequest request,
      StreamObserver<RssProtos.ReportShuffleFetchFailureResponse> responseObserver) {
    String appId = request.getAppId();
    int stageAttempt = request.getStageAttemptId();
    int partitionId = request.getPartitionId();
    RssProtos.StatusCode code;
    boolean reSubmitWholeStage;
    String msg;
    if (!appId.equals(shuffleManager.getAppId())) {
      msg =
          String.format(
              "got a wrong shuffle fetch failure report from appId: %s, expected appId: %s",
              appId, shuffleManager.getAppId());
      LOG.warn(msg);
      code = RssProtos.StatusCode.INVALID_REQUEST;
      reSubmitWholeStage = false;
    } else {
      RssShuffleStatus status =
          shuffleStatus.computeIfAbsent(
              request.getShuffleId(),
              key -> {
                int partitionNum = shuffleManager.getPartitionNum(key);
                return new RssShuffleStatus(partitionNum, stageAttempt);
              });
      int c = status.resetStageAttemptIfNecessary(stageAttempt);
      if (c < 0) {
        msg =
            String.format(
                "got an old stage(%d vs %d) shuffle fetch failure report, which should be impossible.",
                status.getStageAttempt(), stageAttempt);
        LOG.warn(msg);
        code = RssProtos.StatusCode.INVALID_REQUEST;
        reSubmitWholeStage = false;
      } else { // update the stage partition fetch failure count
        code = RssProtos.StatusCode.SUCCESS;
        status.incPartitionFetchFailure(stageAttempt, partitionId);
        int fetchFailureNum = status.getPartitionFetchFailureNum(stageAttempt, partitionId);
        if (fetchFailureNum >= shuffleManager.getMaxFetchFailures()) {
          reSubmitWholeStage = true;
          msg =
              String.format(
                  "report shuffle fetch failure as maximum number(%d) of shuffle fetch is occurred",
                  shuffleManager.getMaxFetchFailures());
        } else {
          reSubmitWholeStage = false;
          msg = "don't report shuffle fetch failure";
        }
      }
    }

    RssProtos.ReportShuffleFetchFailureResponse reply =
        RssProtos.ReportShuffleFetchFailureResponse.newBuilder()
            .setStatus(code)
            .setReSubmitWholeStage(reSubmitWholeStage)
            .setMsg(msg)
            .build();
    responseObserver.onNext(reply);
    responseObserver.onCompleted();
  }

  @Override
  public void getPartitionToShufflerServer(
      RssProtos.PartitionToShuffleServerRequest request,
      StreamObserver<RssProtos.PartitionToShuffleServerResponse> responseObserver) {
    RssProtos.PartitionToShuffleServerResponse reply;
    RssProtos.StatusCode code;
    int shuffleId = request.getShuffleId();
    ShuffleHandleInfo shuffleHandleInfoByShuffleId =
        shuffleManager.getShuffleHandleInfoByShuffleId(shuffleId);
    if (shuffleHandleInfoByShuffleId != null) {
      code = RssProtos.StatusCode.SUCCESS;
      Map<Integer, List<ShuffleServerInfo>> partitionToServers =
          shuffleHandleInfoByShuffleId.getPartitionToServers();
      Map<Integer, RssProtos.GetShuffleServerListResponse> protopartitionToServers =
          JavaUtils.newConcurrentMap();
      for (Map.Entry<Integer, List<ShuffleServerInfo>> integerListEntry :
          partitionToServers.entrySet()) {
        List<RssProtos.ShuffleServerId> shuffleServerIds =
            ShuffleServerInfo.toProto(integerListEntry.getValue());
        RssProtos.GetShuffleServerListResponse getShuffleServerListResponse =
            RssProtos.GetShuffleServerListResponse.newBuilder()
                .addAllServers(shuffleServerIds)
                .build();
        protopartitionToServers.put(integerListEntry.getKey(), getShuffleServerListResponse);
      }
      RemoteStorageInfo remoteStorage = shuffleHandleInfoByShuffleId.getRemoteStorage();
      RssProtos.RemoteStorageInfo.Builder protosRemoteStage =
          RssProtos.RemoteStorageInfo.newBuilder()
              .setPath(remoteStorage.getPath())
              .putAllConfItems(remoteStorage.getConfItems());
      reply =
          RssProtos.PartitionToShuffleServerResponse.newBuilder()
              .setStatus(code)
              .putAllPartitionToShuffleServer(protopartitionToServers)
              .setRemoteStorageInfo(protosRemoteStage)
              .build();
    } else {
      code = RssProtos.StatusCode.INVALID_REQUEST;
      reply =
          RssProtos.PartitionToShuffleServerResponse.newBuilder()
              .setStatus(code)
              .putAllPartitionToShuffleServer(null)
              .build();
    }
    responseObserver.onNext(reply);
    responseObserver.onCompleted();
  }

  @Override
  public void reassignShuffleServers(
      RssProtos.ReassignServersRequest request,
      StreamObserver<RssProtos.ReassignServersReponse> responseObserver) {
    int stageId = request.getStageId();
    int stageAttemptNumber = request.getStageAttemptNumber();
    int shuffleId = request.getShuffleId();
    int numPartitions = request.getNumPartitions();
    boolean needReassign =
        shuffleManager.reassignShuffleServers(
            stageId, stageAttemptNumber, shuffleId, numPartitions);
    RssProtos.StatusCode code = RssProtos.StatusCode.SUCCESS;
    RssProtos.ReassignServersReponse reply =
        RssProtos.ReassignServersReponse.newBuilder()
            .setStatus(code)
            .setNeedReassign(needReassign)
            .build();
    responseObserver.onNext(reply);
    responseObserver.onCompleted();
  }

  /**
   * Remove the no longer used shuffle id's rss shuffle status. This is called when ShuffleManager
   * unregisters the corresponding shuffle id.
   *
   * @param shuffleId the shuffle id to unregister.
   */
  public void unregisterShuffle(int shuffleId) {
    shuffleStatus.remove(shuffleId);
  }

  private static class ShuffleServerFailureRecord {
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final ReentrantReadWriteLock.ReadLock readLock = lock.readLock();
    private final ReentrantReadWriteLock.WriteLock writeLock = lock.writeLock();
    private final Map<String, AtomicInteger> shuffleServerFailureRecordCount;
    private int stageAttemptNumber;

    private ShuffleServerFailureRecord(
        Map<String, AtomicInteger> shuffleServerFailureRecordCount, int stageAttemptNumber) {
      this.shuffleServerFailureRecordCount = shuffleServerFailureRecordCount;
      this.stageAttemptNumber = stageAttemptNumber;
    }

    private <T> T withReadLock(Supplier<T> fn) {
      readLock.lock();
      try {
        return fn.get();
      } finally {
        readLock.unlock();
      }
    }

    private <T> T withWriteLock(Supplier<T> fn) {
      writeLock.lock();
      try {
        return fn.get();
      } finally {
        writeLock.unlock();
      }
    }

    public int getStageAttempt() {
      return withReadLock(() -> this.stageAttemptNumber);
    }

    public boolean resetStageAttemptIfNecessary(int stageAttemptNumber) {
      return withWriteLock(
          () -> {
            if (this.stageAttemptNumber < stageAttemptNumber) {
              // a new stage attempt is issued. Record the shuffleServer status of the Map should be
              // clear and reset.
              shuffleServerFailureRecordCount.clear();
              this.stageAttemptNumber = stageAttemptNumber;
              return false;
            } else if (this.stageAttemptNumber > stageAttemptNumber) {
              return true;
            }
            return false;
          });
    }

    public boolean incPartitionWriteFailure(
        int stageAttemptNumber,
        List<ShuffleServerInfo> shuffleServerInfos,
        RssShuffleManagerInterface shuffleManager) {
      return withWriteLock(
          () -> {
            if (this.stageAttemptNumber != stageAttemptNumber) {
              // do nothing here
              return false;
            }
            shuffleServerInfos.forEach(
                shuffleServerInfo -> {
                  shuffleServerFailureRecordCount
                      .computeIfAbsent(shuffleServerInfo.getId(), k -> new AtomicInteger())
                      .incrementAndGet();
                });
            List<Map.Entry<String, AtomicInteger>> list =
                new ArrayList(shuffleServerFailureRecordCount.entrySet());
            if (!list.isEmpty()) {
              Collections.sort(list, (o1, o2) -> (o1.getValue().get() - o2.getValue().get()));
              Map.Entry<String, AtomicInteger> shuffleServerInfoIntegerEntry = list.get(0);
              if (shuffleServerInfoIntegerEntry.getValue().get()
                  > shuffleManager.getMaxFetchFailures()) {
                shuffleManager.addFailuresShuffleServerInfos(
                    shuffleServerInfoIntegerEntry.getKey());
                return true;
              }
            }
            return false;
          });
    }
  }

  private static class RssShuffleStatus {
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final ReentrantReadWriteLock.ReadLock readLock = lock.readLock();
    private final ReentrantReadWriteLock.WriteLock writeLock = lock.writeLock();
    private final int[] partitions;
    private int stageAttempt;

    private RssShuffleStatus(int partitionNum, int stageAttempt) {
      this.stageAttempt = stageAttempt;
      this.partitions = new int[partitionNum];
    }

    private <T> T withReadLock(Supplier<T> fn) {
      readLock.lock();
      try {
        return fn.get();
      } finally {
        readLock.unlock();
      }
    }

    private <T> T withWriteLock(Supplier<T> fn) {
      writeLock.lock();
      try {
        return fn.get();
      } finally {
        writeLock.unlock();
      }
    }

    // todo: maybe it's more performant to just use synchronized method here.
    public int getStageAttempt() {
      return withReadLock(() -> this.stageAttempt);
    }

    /**
     * Check whether the input stage attempt is a new stage or not. If a new stage attempt is
     * requested, reset partitions.
     *
     * @param stageAttempt the incoming stage attempt number
     * @return 0 if stageAttempt == this.stageAttempt 1 if stageAttempt > this.stageAttempt -1 if
     *     stateAttempt < this.stageAttempt which means nothing happens
     */
    public int resetStageAttemptIfNecessary(int stageAttempt) {
      return withWriteLock(
          () -> {
            if (this.stageAttempt < stageAttempt) {
              // a new stage attempt is issued. the partitions array should be clear and reset.
              Arrays.fill(this.partitions, 0);
              this.stageAttempt = stageAttempt;
              return 1;
            } else if (this.stageAttempt > stageAttempt) {
              return -1;
            }
            return 0;
          });
    }

    public void incPartitionFetchFailure(int stageAttempt, int partition) {
      withWriteLock(
          () -> {
            if (this.stageAttempt != stageAttempt) {
              // do nothing here
            } else {
              this.partitions[partition] = this.partitions[partition] + 1;
            }
            return null;
          });
    }

    public int getPartitionFetchFailureNum(int stageAttempt, int partition) {
      return withReadLock(
          () -> {
            if (this.stageAttempt != stageAttempt) {
              return 0;
            } else {
              return this.partitions[partition];
            }
          });
    }
  }
}
