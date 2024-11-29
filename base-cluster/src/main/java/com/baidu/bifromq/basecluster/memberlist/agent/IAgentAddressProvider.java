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

package com.baidu.bifromq.basecluster.memberlist.agent;

import com.baidu.bifromq.basecluster.agent.proto.AgentEndpoint;
import io.reactivex.rxjava3.core.Observable;
import java.util.Set;

/**
 * The provider of agent address in the host cluster.
 */
public interface IAgentAddressProvider {
    /**
     * The observable of agent address.
     *
     * @return an observable of agent address
     */
    Observable<Set<AgentEndpoint>> agentAddress();
}