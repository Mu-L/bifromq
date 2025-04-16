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

package com.baidu.bifromq.inbox.server.scheduler;

import com.baidu.bifromq.basekv.client.IBaseKVStoreClient;
import com.baidu.bifromq.basekv.client.exception.BadVersionException;
import com.baidu.bifromq.basekv.client.exception.TryLaterException;
import com.baidu.bifromq.basekv.client.scheduler.BatchMutationCall;
import com.baidu.bifromq.basekv.client.scheduler.MutationCallBatcherKey;
import com.baidu.bifromq.basekv.store.proto.RWCoProcInput;
import com.baidu.bifromq.basekv.store.proto.RWCoProcOutput;
import com.baidu.bifromq.baserpc.client.exception.ServerNotFoundException;
import com.baidu.bifromq.basescheduler.ICallTask;
import com.baidu.bifromq.inbox.record.InboxInstance;
import com.baidu.bifromq.inbox.record.TenantInboxInstance;
import com.baidu.bifromq.inbox.rpc.proto.CreateReply;
import com.baidu.bifromq.inbox.rpc.proto.CreateRequest;
import com.baidu.bifromq.inbox.storage.proto.BatchCreateRequest;
import com.baidu.bifromq.inbox.storage.proto.InboxServiceRWCoProcInput;
import com.baidu.bifromq.inbox.storage.proto.Replica;
import com.baidu.bifromq.type.ClientInfo;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;

class BatchCreateCall extends BatchMutationCall<CreateRequest, CreateReply> {

    protected BatchCreateCall(IBaseKVStoreClient distWorkerClient, MutationCallBatcherKey batcherKey) {
        super(distWorkerClient, batcherKey);
    }

    @Override
    protected MutationCallTaskBatch<CreateRequest, CreateReply> newBatch(long ver) {
        return new BatchCreateCallTask(ver);
    }

    @Override
    protected RWCoProcInput makeBatch(
        Iterable<ICallTask<CreateRequest, CreateReply, MutationCallBatcherKey>> callTasks) {
        BatchCreateRequest.Builder reqBuilder = BatchCreateRequest.newBuilder()
            .setLeader(Replica.newBuilder()
                .setRangeId(batcherKey.id)
                .setStoreId(batcherKey.leaderStoreId)
                .build());
        callTasks.forEach(call -> {
            CreateRequest request = call.call();
            ClientInfo client = request.getClient();
            String tenantId = client.getTenantId();
            BatchCreateRequest.Params.Builder paramsBuilder = BatchCreateRequest.Params.newBuilder()
                .setInboxId(request.getInboxId())
                .setIncarnation(request.getIncarnation()) // new incarnation
                .setExpirySeconds(request.getExpirySeconds())
                .setLimit(request.getLimit())
                .setDropOldest(request.getDropOldest())
                .setClient(client)
                .setNow(request.getNow());
            if (request.hasLwt()) {
                paramsBuilder.setLwt(request.getLwt());
            }
            reqBuilder.addParams(paramsBuilder.build());
        });
        long reqId = System.nanoTime();
        return RWCoProcInput.newBuilder()
            .setInboxService(InboxServiceRWCoProcInput.newBuilder()
                .setReqId(reqId)
                .setBatchCreate(reqBuilder.build())
                .build())
            .build();
    }

    @Override
    protected void handleOutput(Queue<ICallTask<CreateRequest, CreateReply, MutationCallBatcherKey>> batchedTasks,
                                RWCoProcOutput output) {
        ICallTask<CreateRequest, CreateReply, MutationCallBatcherKey> callTask;
        assert batchedTasks.size() == output.getInboxService().getBatchCreate().getSucceedCount();

        int i = 0;
        while ((callTask = batchedTasks.poll()) != null) {
            boolean succeed = output.getInboxService().getBatchCreate().getSucceed(i++);
            callTask.resultPromise().complete(CreateReply.newBuilder()
                .setReqId(callTask.call().getReqId())
                .setCode(succeed ? CreateReply.Code.OK : CreateReply.Code.CONFLICT)
                .build());
        }
    }

    @Override
    protected void handleException(ICallTask<CreateRequest, CreateReply, MutationCallBatcherKey> callTask,
                                   Throwable e) {
        if (e instanceof ServerNotFoundException || e.getCause() instanceof ServerNotFoundException) {
            callTask.resultPromise().complete(CreateReply.newBuilder()
                .setReqId(callTask.call().getReqId())
                .setCode(CreateReply.Code.TRY_LATER)
                .build());
            return;
        }
        if (e instanceof BadVersionException || e.getCause() instanceof BadVersionException) {
            callTask.resultPromise().complete(CreateReply.newBuilder()
                .setReqId(callTask.call().getReqId())
                .setCode(CreateReply.Code.TRY_LATER)
                .build());
            return;
        }
        if (e instanceof TryLaterException || e.getCause() instanceof TryLaterException) {
            callTask.resultPromise().complete(CreateReply.newBuilder()
                .setReqId(callTask.call().getReqId())
                .setCode(CreateReply.Code.TRY_LATER)
                .build());
            return;
        }
        callTask.resultPromise().completeExceptionally(e);

    }

    private static class BatchCreateCallTask extends MutationCallTaskBatch<CreateRequest, CreateReply> {
        private final Set<TenantInboxInstance> inboxes = new HashSet<>();

        private BatchCreateCallTask(long ver) {
            super(ver);
        }

        @Override
        protected void add(ICallTask<CreateRequest, CreateReply, MutationCallBatcherKey> callTask) {
            super.add(callTask);
            inboxes.add(new TenantInboxInstance(
                callTask.call().getClient().getTenantId(),
                new InboxInstance(callTask.call().getInboxId(), callTask.call().getIncarnation()))
            );
        }

        @Override
        protected boolean isBatchable(ICallTask<CreateRequest, CreateReply, MutationCallBatcherKey> callTask) {
            return !inboxes.contains(new TenantInboxInstance(
                callTask.call().getClient().getTenantId(),
                new InboxInstance(callTask.call().getInboxId(), callTask.call().getIncarnation()))
            );
        }
    }
}
