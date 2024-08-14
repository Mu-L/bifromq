/*
 * Copyright (c) 2023. The BifroMQ Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *    http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */

package com.baidu.bifromq.basekv.store.range;

import com.baidu.bifromq.basekv.proto.KVPair;
import com.baidu.bifromq.basekv.proto.KVRangeMessage;
import com.baidu.bifromq.basekv.proto.SaveSnapshotDataReply;
import com.baidu.bifromq.basekv.proto.SaveSnapshotDataRequest;
import com.baidu.bifromq.basekv.proto.SnapshotSyncRequest;
import com.baidu.bifromq.basekv.store.util.AsyncRunner;
import com.baidu.bifromq.logger.SiftLogger;
import com.google.common.util.concurrent.RateLimiter;
import io.reactivex.rxjava3.disposables.Disposable;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;

class KVRangeDumpSession {
    enum Result {
        OK, NoCheckpoint, Canceled, Abort, Error
    }

    private final Logger log;

    interface DumpBytesRecorder {
        void record(int bytes);
    }

    private final String follower;
    private final SnapshotSyncRequest request;
    private final IKVRangeMessenger messenger;
    private final AsyncRunner runner;
    private final AtomicInteger reqId = new AtomicInteger();
    private final AtomicBoolean canceled = new AtomicBoolean();
    private final Duration maxIdleDuration;
    private final CompletableFuture<Result> doneSignal = new CompletableFuture<>();
    private final DumpBytesRecorder recorder;
    private final RateLimiter rateLimiter;
    private IKVCheckpointIterator snapshotDataItr;
    private volatile KVRangeMessage currentRequest;
    private volatile long lastReplyTS;

    KVRangeDumpSession(String follower,
                       SnapshotSyncRequest request,
                       IKVRange accessor,
                       IKVRangeMessenger messenger,
                       Executor executor,
                       Duration maxIdleDuration,
                       long bandwidth,
                       DumpBytesRecorder recorder,
                       String... tags) {
        this.follower = follower;
        this.request = request;
        this.messenger = messenger;
        this.runner = new AsyncRunner("basekv.runner.sessiondump", executor);
        this.maxIdleDuration = maxIdleDuration;
        this.recorder = recorder;
        rateLimiter = RateLimiter.create(bandwidth);
        this.log = SiftLogger.getLogger(KVRangeDumpSession.class, tags);
        if (!request.getSnapshot().hasCheckpointId()) {
            messenger.send(KVRangeMessage.newBuilder()
                .setRangeId(request.getSnapshot().getId())
                .setHostStoreId(follower)
                .setSaveSnapshotDataRequest(SaveSnapshotDataRequest.newBuilder()
                    .setSessionId(request.getSessionId())
                    .setFlag(SaveSnapshotDataRequest.Flag.End)
                    .build())
                .build());
            executor.execute(() -> doneSignal.complete(Result.OK));
        } else if (!accessor.hasCheckpoint(request.getSnapshot())) {
            log.warn("No checkpoint found for snapshot: {}", request.getSnapshot());
            messenger.send(KVRangeMessage.newBuilder()
                .setRangeId(request.getSnapshot().getId())
                .setHostStoreId(follower)
                .setSaveSnapshotDataRequest(SaveSnapshotDataRequest.newBuilder()
                    .setSessionId(request.getSessionId())
                    .setFlag(SaveSnapshotDataRequest.Flag.Error)
                    .build())
                .build());
            executor.execute(() -> doneSignal.complete(Result.NoCheckpoint));
        } else {
            snapshotDataItr = accessor.open(request.getSnapshot()).newDataReader().iterator();
            snapshotDataItr.seekToFirst();
            Disposable disposable = messenger.receive()
                .mapOptional(m -> {
                    if (m.hasSaveSnapshotDataReply()) {
                        SaveSnapshotDataReply reply = m.getSaveSnapshotDataReply();
                        if (reply.getSessionId().equals(request.getSessionId())) {
                            return Optional.of(reply);
                        }
                    }
                    return Optional.empty();
                })
                .subscribe(this::handleReply);
            doneSignal.whenComplete((v, e) -> {
                snapshotDataItr.close();
                disposable.dispose();
            });
            nextSaveRequest();
        }
    }

    String id() {
        return request.getSessionId();
    }

    String checkpointId() {
        return request.getSnapshot().getCheckpointId();
    }

    void tick() {
        if (lastReplyTS == 0) {
            return;
        }
        long elapseNanos = Duration.ofNanos(System.nanoTime() - lastReplyTS).toNanos();
        if (maxIdleDuration.toNanos() < elapseNanos) {
            log.debug("DumpSession idle: session={}, follower={}", request.getSessionId(), follower);
            cancel();
        } else if (maxIdleDuration.toNanos() / 2 < elapseNanos && currentRequest != null) {
            runner.add(() -> {
                if (maxIdleDuration.toNanos() / 2 < Duration.ofNanos(System.nanoTime() - lastReplyTS).toNanos()) {
                    messenger.send(currentRequest);
                }
            });
        }
    }

    void cancel() {
        if (canceled.compareAndSet(false, true)) {
            runner.add(() -> doneSignal.complete(Result.Canceled));
        }
    }

    CompletableFuture<Result> awaitDone() {
        return doneSignal;
    }

    private void handleReply(SaveSnapshotDataReply reply) {
        KVRangeMessage currReq = currentRequest;
        if (currReq == null) {
            return;
        }
        SaveSnapshotDataRequest req = currentRequest.getSaveSnapshotDataRequest();
        lastReplyTS = System.nanoTime();
        if (req.getReqId() == reply.getReqId()) {
            currentRequest = null;
            switch (reply.getResult()) {
                case OK -> {
                    switch (req.getFlag()) {
                        case More -> nextSaveRequest();
                        case End -> runner.add(() -> doneSignal.complete(Result.OK));
                    }
                }
                case NoSessionFound, Error -> runner.add(() -> doneSignal.complete(Result.Abort));
            }
        }
    }

    private void nextSaveRequest() {
        runner.add(() -> {
            SaveSnapshotDataRequest.Builder reqBuilder = SaveSnapshotDataRequest.newBuilder()
                .setSessionId(request.getSessionId())
                .setReqId(reqId.getAndIncrement());
            int dumpBytes = 0;
            while (true) {
                if (!canceled.get()) {
                    try {
                        if (snapshotDataItr.isValid()) {
                            KVPair kvPair = KVPair.newBuilder()
                                .setKey(snapshotDataItr.key())
                                .setValue(snapshotDataItr.value())
                                .build();
                            reqBuilder.addKv(kvPair);
                            int bytes = snapshotDataItr.key().size() + snapshotDataItr.value().size();
                            snapshotDataItr.next();
                            if (!rateLimiter.tryAcquire(bytes)) {
                                if (snapshotDataItr.isValid()) {
                                    reqBuilder.setFlag(SaveSnapshotDataRequest.Flag.More);
                                } else {
                                    reqBuilder.setFlag(SaveSnapshotDataRequest.Flag.End);
                                }
                                break;
                            }
                            dumpBytes += bytes;
                        } else {
                            // current iterator finished
                            reqBuilder.setFlag(SaveSnapshotDataRequest.Flag.End);
                            break;
                        }
                    } catch (Throwable e) {
                        log.error("DumpSession error: session={}, follower={}",
                            request.getSessionId(), follower, e);
                        reqBuilder.clearKv();
                        reqBuilder.setFlag(SaveSnapshotDataRequest.Flag.Error);
                        break;
                    }
                } else {
                    log.debug("DumpSession has been canceled: session={}, follower={}",
                        request.getSessionId(), follower);
                    reqBuilder.clearKv();
                    reqBuilder.setFlag(SaveSnapshotDataRequest.Flag.Error);
                    break;
                }
            }
            currentRequest = KVRangeMessage.newBuilder()
                .setRangeId(request.getSnapshot().getId())
                .setHostStoreId(follower)
                .setSaveSnapshotDataRequest(reqBuilder.build())
                .build();
            lastReplyTS = System.nanoTime();
            recorder.record(dumpBytes);
            messenger.send(currentRequest);
            if (currentRequest.getSaveSnapshotDataRequest().getFlag() == SaveSnapshotDataRequest.Flag.Error) {
                doneSignal.complete(Result.Error);
            }
        });
    }
}
