/*
 * Copyright (c) 2025. The BifroMQ Authors. All Rights Reserved.
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

package com.baidu.bifromq.sessiondict.client.scheduler;

import com.baidu.bifromq.baserpc.client.IRPCClient;
import com.baidu.bifromq.basescheduler.IBatchCall;
import com.baidu.bifromq.basescheduler.ICallTask;
import com.baidu.bifromq.sessiondict.client.type.ExistResult;
import com.baidu.bifromq.sessiondict.client.type.TenantClientId;
import com.baidu.bifromq.sessiondict.rpc.proto.ExistReply;
import com.baidu.bifromq.sessiondict.rpc.proto.ExistRequest;
import java.util.LinkedList;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;

@Slf4j
class BatchSessionExistCall implements IBatchCall<TenantClientId, ExistResult, String> {
    private final IRPCClient.IRequestPipeline<ExistRequest, ExistReply> ppln;
    private final LinkedList<ICallTask<TenantClientId, ExistResult, String>> batchedTasks = new LinkedList<>();

    public BatchSessionExistCall(IRPCClient.IRequestPipeline<ExistRequest, ExistReply> ppln) {
        this.ppln = ppln;
    }

    @Override
    public void add(ICallTask<TenantClientId, ExistResult, String> task) {
        batchedTasks.add(task);
    }

    @Override
    public void reset() {

    }

    @Override
    public CompletableFuture<Void> execute() {
        ExistRequest.Builder reqBuilder = ExistRequest.newBuilder().setReqId(System.nanoTime());
        batchedTasks.forEach(task ->
            reqBuilder.addClient(ExistRequest.Client.newBuilder()
                .setUserId(task.call().userId())
                .setClientId(task.call().clientId())
                .build()));
        return ppln.invoke(reqBuilder.build())
            .handle((reply, e) -> {
                if (e != null) {
                    log.debug("Session exist call failed", e);
                    ICallTask<TenantClientId, ExistResult, String> task;
                    while ((task = batchedTasks.poll()) != null) {
                        task.resultPromise().complete(ExistResult.ERROR);
                    }
                } else {
                    switch (reply.getCode()) {
                        case OK -> {
                            ICallTask<TenantClientId, ExistResult, String> task;
                            assert reply.getExistCount() == batchedTasks.size();
                            int i = 0;
                            while ((task = batchedTasks.poll()) != null) {
                                task.resultPromise().complete(reply.getExist(i++)
                                    ? ExistResult.EXISTS : ExistResult.NOT_EXISTS);
                            }
                        }
                        default -> {
                            ICallTask<TenantClientId, ExistResult, String> task;
                            while ((task = batchedTasks.poll()) != null) {
                                task.resultPromise().complete(ExistResult.ERROR);
                            }
                        }
                    }
                }
                return null;
            });
    }
}
