/*
 * Copyright (c) 2023. Baidu, Inc. All Rights Reserved.
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

package com.baidu.bifromq.retain.client;

import static com.google.protobuf.UnsafeByteOperations.unsafeWrap;

import com.baidu.bifromq.baserpc.IRPCClient;
import com.baidu.bifromq.retain.RPCBluePrint;
import com.baidu.bifromq.retain.rpc.proto.MatchReply;
import com.baidu.bifromq.retain.rpc.proto.MatchRequest;
import com.baidu.bifromq.retain.rpc.proto.RetainReply;
import com.baidu.bifromq.retain.rpc.proto.RetainRequest;
import com.baidu.bifromq.retain.rpc.proto.RetainServiceGrpc;
import com.baidu.bifromq.type.ClientInfo;
import com.baidu.bifromq.type.Message;
import com.baidu.bifromq.type.QoS;
import io.reactivex.rxjava3.core.Observable;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;

@Slf4j
final class RetainClient implements IRetainClient {
    private final IRPCClient rpcClient;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    RetainClient(RetainClientBuilder builder) {
        this.rpcClient = IRPCClient.newBuilder()
            .bluePrint(RPCBluePrint.INSTANCE)
            .executor(builder.executor)
            .eventLoopGroup(builder.eventLoopGroup)
            .sslContext(builder.sslContext)
            .crdtService(builder.crdtService)
            .build();
    }

    @Override
    public void stop() {
        if (closed.compareAndSet(false, true)) {
            log.info("Stopping retain client");
            log.debug("Stopping rpc client");
            rpcClient.stop();
            log.debug("Retain client stopped");
        }
    }

    @Override
    public Observable<IRPCClient.ConnState> connState() {
        return rpcClient.connState();
    }

    @Override
    public CompletableFuture<MatchReply> match(long reqId,
                                               String tenantId,
                                               String topicFilter,
                                               int limit,
                                               ClientInfo subscriber) {
        log.trace("Handling match request: reqId={}, topicFilter={}", reqId, topicFilter);
        return rpcClient.invoke(tenantId, null, MatchRequest.newBuilder()
            .setReqId(reqId)
            .setTenantId(tenantId)
            .setTopicFilter(topicFilter)
            .setLimit(limit)
            .setSubscriber(subscriber)
            .build(), RetainServiceGrpc.getMatchMethod());
    }

    @Override
    public CompletableFuture<RetainReply> retain(long reqId, String tenantId,
                                                 String topic, QoS qos, ByteBuffer payload,
                                                 int expirySeconds, ClientInfo publisher) {
        long now = System.currentTimeMillis();
        long expiry = expirySeconds == Integer.MAX_VALUE ? Long.MAX_VALUE : now +
            TimeUnit.MILLISECONDS.convert(expirySeconds, TimeUnit.SECONDS);
        return rpcClient.invoke(tenantId, null, RetainRequest.newBuilder()
            .setReqId(reqId)
            .setTenantId(tenantId)
            .setTopic(topic)
            .setMessage(Message.newBuilder()
                .setMessageId(reqId)
                .setPubQoS(qos)
                .setPayload(unsafeWrap(payload))
                .setTimestamp(now)
                .setExpireTimestamp(expiry)
                .build())
            .setPublisher(publisher)
            .build(), RetainServiceGrpc.getRetainMethod());
    }
}