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
import com.baidu.bifromq.basekv.balance.impl.RangeSplitBalancer;
import com.baidu.bifromq.inbox.store.spi.IInboxStoreBalancerFactory;
import com.baidu.bifromq.sysprops.props.InboxStoreSplitMaxRangeNum;
import com.baidu.bifromq.sysprops.props.InboxStoreSplitUnderCPUUsage;
import com.baidu.bifromq.sysprops.props.InboxStoreSplitUnderIODensity;
import com.baidu.bifromq.sysprops.props.InboxStoreSplitUnderIONanos;

public class RangeSplitBalancerFactory implements IInboxStoreBalancerFactory {
    @Override
    public StoreBalancer newBalancer(String clusterId, String localStoreId) {
        return new RangeSplitBalancer(clusterId, localStoreId,
            "kv_io_mutation",
            InboxStoreSplitMaxRangeNum.INSTANCE.get(),
            InboxStoreSplitUnderCPUUsage.INSTANCE.get(),
            InboxStoreSplitUnderIODensity.INSTANCE.get(),
            InboxStoreSplitUnderIONanos.INSTANCE.get());
    }
}
