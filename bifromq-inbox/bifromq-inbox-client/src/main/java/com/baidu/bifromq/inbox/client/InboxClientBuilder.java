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

package com.baidu.bifromq.inbox.client;

import com.baidu.bifromq.basecrdt.service.ICRDTService;
import com.baidu.bifromq.baserpc.IRPCClient;
import com.baidu.bifromq.baserpc.trafficgovernor.IRPCServiceTrafficGovernor;
import com.baidu.bifromq.inbox.RPCBluePrint;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.ssl.SslContext;
import java.util.concurrent.Executor;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;

@NoArgsConstructor(access = AccessLevel.PACKAGE)
@Accessors(fluent = true)
@Setter
public final class InboxClientBuilder implements IInboxClientBuilder {
    ICRDTService crdtService;
    EventLoopGroup eventLoopGroup;
    SslContext sslContext;
    Executor executor;

    public IInboxClient build() {
        return new InboxClient(IRPCClient.newBuilder()
            .bluePrint(RPCBluePrint.INSTANCE)
            .executor(executor)
            .eventLoopGroup(eventLoopGroup)
            .crdtService(crdtService)
            .sslContext(sslContext)
            .build(),
            IRPCServiceTrafficGovernor.newInstance(RPCBluePrint.INSTANCE.serviceDescriptor().getName(), crdtService));
    }
}
