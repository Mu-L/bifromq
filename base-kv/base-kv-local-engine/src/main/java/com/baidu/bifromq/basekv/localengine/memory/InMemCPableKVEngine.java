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

package com.baidu.bifromq.basekv.localengine.memory;

import com.baidu.bifromq.basekv.localengine.metrics.KVSpaceOpMeters;
import org.slf4j.Logger;

public class InMemCPableKVEngine extends InMemKVEngine<InMemCPableKVEngine, InMemCPableKVSpace> {
    public InMemCPableKVEngine(String overrideIdentity, InMemKVEngineConfigurator c) {
        super(overrideIdentity, c);
    }

    @Override
    protected InMemCPableKVSpace doBuildKVSpace(String spaceId,
                                                InMemKVEngineConfigurator configurator,
                                                Runnable onDestroy,
                                                KVSpaceOpMeters opMeters,
                                                Logger logger,
                                                String... tags) {
        return new InMemCPableKVSpace(spaceId, configurator, this, onDestroy, opMeters, logger);
    }
}
