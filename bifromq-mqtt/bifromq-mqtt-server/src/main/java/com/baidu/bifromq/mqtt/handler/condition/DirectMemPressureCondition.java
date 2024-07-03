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

package com.baidu.bifromq.mqtt.handler.condition;

import static com.baidu.bifromq.sysprops.BifroMQSysProp.INGRESS_SLOWDOWN_DIRECT_MEMORY_USAGE;

import com.baidu.bifromq.baseenv.MemUsage;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class DirectMemPressureCondition implements Condition {
    public static final DirectMemPressureCondition INSTANCE = new DirectMemPressureCondition();
    private static final double MAX_DIRECT_MEMORY_USAGE = INGRESS_SLOWDOWN_DIRECT_MEMORY_USAGE.get();

    @Override
    public boolean meet() {
        return MemUsage.local().nettyDirectMemoryUsage() > MAX_DIRECT_MEMORY_USAGE;
    }

    @Override
    public String toString() {
        return "HighDirectMemoryUsage";
    }
}
