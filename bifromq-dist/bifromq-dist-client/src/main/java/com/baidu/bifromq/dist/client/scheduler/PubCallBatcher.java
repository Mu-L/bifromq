/*
 * Copyright (c) 2024. The BifroMQ Authors. All Rights Reserved.
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

package com.baidu.bifromq.dist.client.scheduler;

import com.baidu.bifromq.baserpc.client.IRPCClient;
import com.baidu.bifromq.basescheduler.Batcher;
import com.baidu.bifromq.basescheduler.IBatchCall;
import com.baidu.bifromq.dist.client.PubResult;
import com.baidu.bifromq.dist.rpc.proto.DistReply;
import com.baidu.bifromq.dist.rpc.proto.DistRequest;

class PubCallBatcher extends Batcher<PubCall, PubResult, PubCallBatcherKey> {
    private final IRPCClient.IRequestPipeline<DistRequest, DistReply> ppln;


    PubCallBatcher(PubCallBatcherKey batcherKey, String name,
                   long tolerableLatencyNanos,
                   long burstLatencyNanos,
                   IRPCClient.IRequestPipeline<DistRequest, DistReply> ppln) {
        super(batcherKey, name, tolerableLatencyNanos, burstLatencyNanos);
        this.ppln = ppln;
    }

    @Override
    protected IBatchCall<PubCall, PubResult, PubCallBatcherKey> newBatch() {
        return new BatchPubCall(ppln);
    }

    @Override
    public void close() {
        super.close();
        ppln.close();
    }
}
