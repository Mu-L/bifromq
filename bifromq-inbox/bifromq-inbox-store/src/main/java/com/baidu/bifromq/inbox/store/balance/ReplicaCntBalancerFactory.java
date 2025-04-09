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

package com.baidu.bifromq.inbox.store.balance;

import com.baidu.bifromq.basekv.balance.StoreBalancer;
import com.baidu.bifromq.basekv.balance.impl.ReplicaCntBalancer;
import com.baidu.bifromq.inbox.store.spi.IInboxStoreBalancerFactory;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ReplicaCntBalancerFactory implements IInboxStoreBalancerFactory {
    private static final String VOTERS_PER_RANGE = "votersPerRange";
    private static final int DEFAULT_VOTERS_PER_RANGE = 3;
    private int votersPerRange;

    @Override
    public void init(Struct config) {
        votersPerRange = (int) config.getFieldsOrDefault(VOTERS_PER_RANGE,
            Value.newBuilder().setNumberValue(DEFAULT_VOTERS_PER_RANGE).build()).getNumberValue();
        if (votersPerRange < 1 || votersPerRange % 2 == 0) {
            votersPerRange = 3;
            log.warn("Invalid voters per range config {}, use default {}", votersPerRange, DEFAULT_VOTERS_PER_RANGE);
        }
    }

    @Override
    public StoreBalancer newBalancer(String clusterId, String localStoreId) {
        return new ReplicaCntBalancer(clusterId, localStoreId, votersPerRange, 0);
    }
}
