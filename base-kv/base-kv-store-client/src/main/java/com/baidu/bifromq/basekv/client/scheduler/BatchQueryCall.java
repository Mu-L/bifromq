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

package com.baidu.bifromq.basekv.client.scheduler;

import com.baidu.bifromq.basekv.client.IBaseKVStoreClient;
import com.baidu.bifromq.basekv.client.IQueryPipeline;
import com.baidu.bifromq.basekv.client.exception.BadRequestException;
import com.baidu.bifromq.basekv.client.exception.BadVersionException;
import com.baidu.bifromq.basekv.client.exception.InternalErrorException;
import com.baidu.bifromq.basekv.client.exception.TryLaterException;
import com.baidu.bifromq.basekv.store.proto.KVRangeRORequest;
import com.baidu.bifromq.basekv.store.proto.ROCoProcInput;
import com.baidu.bifromq.basekv.store.proto.ROCoProcOutput;
import com.baidu.bifromq.basescheduler.IBatchCall;
import com.baidu.bifromq.basescheduler.ICallTask;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public abstract class BatchQueryCall<ReqT, RespT> implements IBatchCall<ReqT, RespT, QueryCallBatcherKey> {
    private static final int MAX_RECURSION_DEPTH = 100;
    private final QueryCallBatcherKey batcherKey;
    private final IQueryPipeline storePipeline;
    private final Deque<BatchQueryCall.BatchCallTask<ReqT, RespT>> batchCallTasks = new ArrayDeque<>();

    protected BatchQueryCall(IBaseKVStoreClient storeClient, boolean linearizable, QueryCallBatcherKey batcherKey) {
        this.batcherKey = batcherKey;
        if (linearizable) {
            storePipeline = storeClient.createLinearizedQueryPipeline(batcherKey.storeId);
        } else {
            storePipeline = storeClient.createQueryPipeline(batcherKey.storeId);
        }
    }

    @Override
    public void add(ICallTask<ReqT, RespT, QueryCallBatcherKey> callTask) {
        BatchQueryCall.BatchCallTask<ReqT, RespT> lastBatchCallTask;
        QueryCallBatcherKey batcherKey = callTask.batcherKey();
        if ((lastBatchCallTask = batchCallTasks.peekLast()) != null) {
            lastBatchCallTask.batchedTasks.add(callTask);
            batchCallTasks.add(lastBatchCallTask);
        } else {
            lastBatchCallTask = new BatchQueryCall.BatchCallTask<>(batcherKey.storeId, batcherKey.ver);
            lastBatchCallTask.batchedTasks.add(callTask);
            batchCallTasks.add(lastBatchCallTask);
        }
    }

    protected abstract ROCoProcInput makeBatch(Iterator<ReqT> reqIterator);

    protected abstract void handleOutput(Queue<ICallTask<ReqT, RespT, QueryCallBatcherKey>> batchedTasks,
                                         ROCoProcOutput output);

    protected abstract void handleException(ICallTask<ReqT, RespT, QueryCallBatcherKey> callTask, Throwable e);

    @Override
    public void reset() {

    }

    @Override
    public CompletableFuture<Void> execute() {
        return fireBatchCall(1);
    }

    @Override
    public void destroy() {
        storePipeline.close();
    }

    private CompletableFuture<Void> fireBatchCall(int recursionDepth) {
        BatchCallTask<ReqT, RespT> batchCallTask = batchCallTasks.poll();
        if (batchCallTask == null) {
            return CompletableFuture.completedFuture(null);
        }
        return fireBatchCall(batchCallTask, recursionDepth);
    }

    private CompletableFuture<Void> fireBatchCall(BatchCallTask<ReqT, RespT> batchCallTask, int recursionDepth) {
        ROCoProcInput input = makeBatch(batchCallTask.batchedTasks.stream().map(ICallTask::call).iterator());
        long reqId = System.nanoTime();
        return storePipeline.query(
                KVRangeRORequest.newBuilder()
                    .setReqId(reqId)
                    .setVer(batchCallTask.ver)
                    .setKvRangeId(batcherKey.id)
                    .setRoCoProc(input)
                    .build())
            .thenApply(reply -> switch (reply.getCode()) {
                case Ok -> reply.getRoCoProcResult();
                case TryLater -> throw new TryLaterException();
                case BadVersion -> throw new BadVersionException();
                case BadRequest -> throw new BadRequestException();
                default -> throw new InternalErrorException();
            })
            .handle((v, e) -> {
                if (e != null) {
                    ICallTask<ReqT, RespT, QueryCallBatcherKey> callTask;
                    while ((callTask = batchCallTask.batchedTasks.poll()) != null) {
                        handleException(callTask, e);
                    }
                } else {
                    handleOutput(batchCallTask.batchedTasks, v);
                }
                return null;
            })
            .thenCompose(v -> {
                if (recursionDepth < MAX_RECURSION_DEPTH) {
                    return fireBatchCall(recursionDepth + 1);
                } else {
                    return CompletableFuture.supplyAsync(() -> fireBatchCall(1))
                        .thenCompose(inner -> inner);
                }
            });
    }

    private static class BatchCallTask<ReqT, RespT> {
        final String storeId;
        final long ver;
        final LinkedList<ICallTask<ReqT, RespT, QueryCallBatcherKey>> batchedTasks = new LinkedList<>();

        private BatchCallTask(String storeId, long ver) {
            this.storeId = storeId;
            this.ver = ver;
        }
    }
}
