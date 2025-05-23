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

package com.baidu.bifromq.basekv.server;

import static com.baidu.bifromq.basekv.Constants.toBaseKVAgentId;

import com.baidu.bifromq.basecluster.IAgentHost;
import com.baidu.bifromq.basecluster.memberlist.agent.IAgent;
import com.baidu.bifromq.basecluster.memberlist.agent.IAgentMember;
import com.baidu.bifromq.baseenv.ZeroCopyParser;
import com.baidu.bifromq.basekv.proto.KVRangeMessage;
import com.baidu.bifromq.basekv.proto.StoreMessage;
import com.baidu.bifromq.basekv.store.IStoreMessenger;
import com.baidu.bifromq.logger.SiftLogger;
import com.google.protobuf.InvalidProtocolBufferException;
import io.reactivex.rxjava3.core.Observable;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;

class AgentHostStoreMessenger implements IStoreMessenger {
    private final Logger log;
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final IAgentHost agentHost;
    private final IAgent agent;
    private final IAgentMember agentMember;
    private final String clusterId;
    private final String storeId;

    AgentHostStoreMessenger(IAgentHost agentHost, String clusterId, String storeId) {
        this.agentHost = agentHost;
        this.clusterId = clusterId;
        this.storeId = storeId;
        this.agent = agentHost.host(toBaseKVAgentId(clusterId));
        this.agentMember = agent.register(storeId);
        log = SiftLogger.getLogger(AgentHostStoreMessenger.class, "clusterId", clusterId, "storeId", storeId);
    }

    static String agentId(String clusterId) {
        return "BaseKV:" + clusterId;
    }

    @Override
    public void send(StoreMessage message) {
        if (message.getPayload().hasHostStoreId()) {
            agentMember.multicast(message.getPayload().getHostStoreId(), message.toByteString(), true);
        } else {
            agentMember.broadcast(message.toByteString(), true);
        }
    }

    @Override
    public Observable<StoreMessage> receive() {
        return agentMember.receive()
            .mapOptional(agentMessage -> {
                try {
                    StoreMessage message = ZeroCopyParser.parse(agentMessage.getPayload(), StoreMessage.parser());
                    KVRangeMessage payload = message.getPayload();
                    if (!payload.hasHostStoreId()) {
                        // this is a broadcast message
                        message = message.toBuilder().setPayload(payload.toBuilder()
                            .setHostStoreId(storeId)
                            .build()).build();
                    }
                    return Optional.of(message);
                } catch (InvalidProtocolBufferException e) {
                    log.warn("Unable to parse store message", e);
                    return Optional.empty();
                }
            });
    }

    @Override
    public void close() {
        if (stopped.compareAndSet(false, true)) {
            agent.deregister(agentMember).join();
        }
    }
}
