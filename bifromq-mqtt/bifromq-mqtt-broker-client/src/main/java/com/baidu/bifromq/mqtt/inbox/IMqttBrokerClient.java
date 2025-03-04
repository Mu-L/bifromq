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

package com.baidu.bifromq.mqtt.inbox;

import com.baidu.bifromq.baserpc.client.IConnectable;
import com.baidu.bifromq.mqtt.inbox.rpc.proto.SubReply;
import com.baidu.bifromq.mqtt.inbox.rpc.proto.UnsubReply;
import com.baidu.bifromq.plugin.subbroker.ISubBroker;
import com.baidu.bifromq.type.QoS;
import java.util.concurrent.CompletableFuture;

public interface IMqttBrokerClient extends ISubBroker, IConnectable, AutoCloseable {
    static MqttBrokerClientBuilder newBuilder() {
        return new MqttBrokerClientBuilder();
    }

    CompletableFuture<SubReply> sub(long reqId,
                                    String tenantId,
                                    String sessionId,
                                    String topicFilter,
                                    QoS qos,
                                    String brokerServerId);

    CompletableFuture<UnsubReply> unsub(long reqId,
                                        String tenantId,
                                        String sessionId,
                                        String topicFilter,
                                        String brokerServerId);

    @Override
    default int id() {
        return 0;
    }

    void close();
}
