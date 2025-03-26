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

import static com.baidu.bifromq.basekv.proto.State.StateType.Merged;
import static com.baidu.bifromq.basekv.proto.State.StateType.Removed;
import static com.baidu.bifromq.basekv.proto.State.StateType.ToBePurged;
import static java.util.concurrent.CompletableFuture.completedFuture;

import com.baidu.bifromq.basekv.proto.State;
import com.baidu.bifromq.basekv.store.api.IKVLoadRecord;
import com.baidu.bifromq.basekv.store.api.IKVRangeCoProc;
import com.baidu.bifromq.basekv.store.api.IKVRangeSplitHinter;
import com.baidu.bifromq.basekv.store.api.IKVReader;
import com.baidu.bifromq.basekv.store.exception.KVRangeException;
import com.baidu.bifromq.basekv.store.proto.ROCoProcInput;
import com.baidu.bifromq.basekv.store.proto.ROCoProcOutput;
import com.baidu.bifromq.logger.SiftLogger;
import com.google.common.collect.Sets;
import com.google.protobuf.ByteString;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;

class KVRangeQueryRunner implements IKVRangeQueryRunner {
    private final Logger log;
    private final IKVRange kvRange;
    private final IKVRangeCoProc coProc;
    private final Executor executor;
    private final Set<CompletableFuture<?>> runningQueries = Sets.newConcurrentHashSet();
    private final IKVRangeQueryLinearizer linearizer;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final List<IKVRangeSplitHinter> splitHinters;

    KVRangeQueryRunner(IKVRange kvRange,
                       IKVRangeCoProc coProc,
                       Executor executor,
                       IKVRangeQueryLinearizer linearizer,
                       List<IKVRangeSplitHinter> splitHinters,
                       String... tags) {
        this.kvRange = kvRange;
        this.coProc = coProc;
        this.executor = executor;
        this.linearizer = linearizer;
        this.splitHinters = splitHinters;
        this.log = SiftLogger.getLogger(KVRangeQueryRunner.class, tags);
    }

    // Execute a ROCommand
    @Override
    public CompletableFuture<Boolean> exist(long ver, ByteString key, boolean linearized) {
        return submit(ver, rangeReader -> completedFuture(rangeReader.exist(key)), linearized);
    }

    @Override
    public CompletableFuture<Optional<ByteString>> get(long ver, ByteString key, boolean linearized) {
        return submit(ver, rangeReader -> completedFuture(rangeReader.get(key)), linearized);
    }

    @Override
    public CompletableFuture<ROCoProcOutput> queryCoProc(long ver, ROCoProcInput query, boolean linearized) {
        return submit(ver, rangeReader -> {
            IKVLoadRecorder loadRecorder = new KVLoadRecorder();
            IKVReader loadRecordableReader = new LoadRecordableKVReader(rangeReader, loadRecorder);
            return coProc.query(query, loadRecordableReader)
                .whenComplete((v, e) -> {
                    try {
                        IKVLoadRecord record = loadRecorder.stop();
                        splitHinters.forEach(hinter -> hinter.recordQuery(query, record));
                    } catch (Throwable t) {
                        log.error("Failed to reset hinter and coProc", t);
                    }
                });
        }, linearized);
    }

    // Close the executor, the returned future will be completed when running commands finished and pending tasks
    // will be canceled
    public void close() {
        if (closed.compareAndSet(false, true)) {
            runningQueries.forEach(f -> f.cancel(true));
        }
    }

    private <ReqT, ResultT> CompletableFuture<ResultT> submit(long ver, QueryFunction<ReqT, ResultT> queryFn,
                                                              boolean linearized) {
        CompletableFuture<ResultT> onDone = new CompletableFuture<>();
        runningQueries.add(onDone);
        Runnable queryTask = () -> {
            if (onDone.isDone()) {
                return;
            }
            if (closed.get()) {
                onDone.cancel(true);
                return;
            }
            if (linearized) {
                linearizer.linearize()
                    .thenComposeAsync(v -> doQuery(ver, queryFn), executor)
                    .whenCompleteAsync((r, e) -> {
                        if (e != null) {
                            onDone.completeExceptionally(e);
                        } else {
                            onDone.complete(r);
                        }
                    }, executor);
            } else {
                doQuery(ver, queryFn).whenCompleteAsync((v, e) -> {
                    if (e != null) {
                        onDone.completeExceptionally(e);
                    } else {
                        onDone.complete(v);
                    }
                }, executor);
            }
        };
        onDone.whenComplete((v, e) -> runningQueries.remove(onDone));
        executor.execute(queryTask);
        return onDone;
    }

    private <ReqT, ResultT> CompletableFuture<ResultT> doQuery(long ver,
                                                               QueryFunction<ReqT, ResultT> queryFn) {
        CompletableFuture<ResultT> onDone = new CompletableFuture<>();
        IKVReader dataReader = kvRange.borrowDataReader();
        // return the borrowed reader when future completed
        onDone.whenComplete((v, e) -> kvRange.returnDataReader(dataReader));
        State state = kvRange.state();
        if (ver != kvRange.version()) {
            onDone.completeExceptionally(
                new KVRangeException.BadVersion("Version Mismatch: expect=" + kvRange.version() + ", actual=" + ver));
            return onDone;
        }
        if (state.getType() == Merged || state.getType() == Removed || state.getType() == ToBePurged) {
            onDone.completeExceptionally(
                new KVRangeException.TryLater("Range has been in state: " + state.getType().name().toLowerCase()));
            return onDone;
        }
        try {
            return queryFn.apply(dataReader).whenCompleteAsync((v, e) -> {
                if (e != null) {
                    onDone.completeExceptionally(e);
                } else {
                    onDone.complete(v);
                }
            }, executor);
        } catch (Throwable e) {
            log.debug("Failed to query range", e);
            return CompletableFuture.failedFuture(new KVRangeException.InternalException(e.getMessage()));
        }
    }

    private interface QueryFunction<Req, Resp> {
        CompletableFuture<Resp> apply(IKVReader dataReader);
    }
}
