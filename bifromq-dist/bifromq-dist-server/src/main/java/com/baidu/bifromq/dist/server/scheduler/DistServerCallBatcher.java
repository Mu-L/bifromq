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

package com.baidu.bifromq.dist.server.scheduler;

import com.baidu.bifromq.basekv.client.IBaseKVStoreClient;
import com.baidu.bifromq.basescheduler.Batcher;
import com.baidu.bifromq.basescheduler.IBatchCall;

class DistServerCallBatcher extends Batcher<DistServerCall, DistServerCallResult, DistServerCallBatcherKey> {
    private final IBaseKVStoreClient distWorkerClient;

    protected DistServerCallBatcher(DistServerCallBatcherKey batcherKey,
                                    String name,
                                    long tolerableLatencyNanos,
                                    long burstLatencyNanos,
                                    IBaseKVStoreClient distWorkerClient) {
        super(batcherKey, name, tolerableLatencyNanos, burstLatencyNanos);
        this.distWorkerClient = distWorkerClient;
    }

    @Override
    protected IBatchCall<DistServerCall, DistServerCallResult, DistServerCallBatcherKey> newBatch() {
        return new BatchDistServerCall(distWorkerClient, batcherKey);
    }
}
