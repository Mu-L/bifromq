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

package com.baidu.bifromq.basekv.client.scheduler;

import com.baidu.bifromq.basekv.client.IBaseKVStoreClient;
import com.baidu.bifromq.basekv.client.IMutationPipeline;
import com.baidu.bifromq.basescheduler.IBatchCall;
import com.baidu.bifromq.basescheduler.IBatchCallBuilder;
import com.baidu.bifromq.basescheduler.IBatchCallBuilderFactory;

final class BatchMutationCallBuilderFactory<ReqT, RespT, BatchCallT extends BatchMutationCall<ReqT, RespT>>
    implements IBatchCallBuilderFactory<ReqT, RespT, MutationCallBatcherKey> {
    private final IBaseKVStoreClient storeClient;
    private final IBatchMutationCallBuilder<ReqT, RespT, BatchCallT> batchCallBuilder;

    BatchMutationCallBuilderFactory(IBaseKVStoreClient storeClient,
                                    IBatchMutationCallBuilder<ReqT, RespT, BatchCallT> batchBuilder) {
        this.storeClient = storeClient;
        this.batchCallBuilder = batchBuilder;
    }

    @Override
    public IBatchCallBuilder<ReqT, RespT, MutationCallBatcherKey> newBuilder(String name,
                                                                             MutationCallBatcherKey batcherKey) {
        IMutationPipeline storePipeline = storeClient.createMutationPipeline(batcherKey.leaderStoreId);
        return new IBatchCallBuilder<>() {
            @Override
            public IBatchCall<ReqT, RespT, MutationCallBatcherKey> newBatchCall() {
                return batchCallBuilder.newBatchCall(storePipeline, batcherKey);
            }

            @Override
            public void close() {
                storePipeline.close();
            }
        };
    }
}
