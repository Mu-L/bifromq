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

package com.baidu.bifromq.inbox.server;

import static com.baidu.bifromq.plugin.subbroker.TypeUtil.toResult;

import com.baidu.bifromq.basekv.client.exception.BadVersionException;
import com.baidu.bifromq.basekv.client.exception.TryLaterException;
import com.baidu.bifromq.inbox.record.TenantInboxInstance;
import com.baidu.bifromq.inbox.rpc.proto.SendReply;
import com.baidu.bifromq.inbox.rpc.proto.SendRequest;
import com.baidu.bifromq.inbox.server.scheduler.IInboxInsertScheduler;
import com.baidu.bifromq.inbox.storage.proto.InboxInsertResult;
import com.baidu.bifromq.inbox.storage.proto.InboxSubMessagePack;
import com.baidu.bifromq.inbox.storage.proto.SubMessagePack;
import com.baidu.bifromq.plugin.subbroker.DeliveryPack;
import com.baidu.bifromq.plugin.subbroker.DeliveryReply;
import com.baidu.bifromq.plugin.subbroker.DeliveryResult;
import com.baidu.bifromq.type.MatchInfo;
import com.baidu.bifromq.type.TopicMessagePack;
import com.baidu.bifromq.util.TopicUtil;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;

@Slf4j
class InboxWriter implements InboxWriterPipeline.ISendRequestHandler {
    private final IInboxInsertScheduler insertScheduler;

    InboxWriter(IInboxInsertScheduler insertScheduler) {
        this.insertScheduler = insertScheduler;
    }

    @Override
    public CompletableFuture<SendReply> handle(SendRequest request) {
        Map<TenantInboxInstance, List<MatchInfo>> matchInfosByInbox = new HashMap<>();
        Map<TenantInboxInstance, List<SubMessagePack>> subMsgPacksByInbox = new HashMap<>();
        // break DeliveryPack into SubMessagePack by each TenantInboxInstance
        for (String tenantId : request.getRequest().getPackageMap().keySet()) {
            for (DeliveryPack pack : request.getRequest().getPackageMap().get(tenantId).getPackList()) {
                TopicMessagePack topicMessagePack = pack.getMessagePack();
                Map<TenantInboxInstance, SubMessagePack.Builder> subMsgPackByInbox = new HashMap<>();
                for (MatchInfo matchInfo : pack.getMatchInfoList()) {
                    TenantInboxInstance tenantInboxInstance = TenantInboxInstance.from(tenantId, matchInfo);
                    matchInfosByInbox.computeIfAbsent(tenantInboxInstance, k -> new LinkedList<>()).add(matchInfo);
                    subMsgPackByInbox.computeIfAbsent(tenantInboxInstance,
                            k -> SubMessagePack.newBuilder().setMessages(topicMessagePack))
                        .putMatchedTopicFilters(matchInfo.getMatcher().getMqttTopicFilter(),
                            matchInfo.getIncarnation());
                }
                for (TenantInboxInstance tenantInboxInstance : subMsgPackByInbox.keySet()) {
                    subMsgPacksByInbox.computeIfAbsent(tenantInboxInstance, k -> new LinkedList<>())
                        .add(subMsgPackByInbox.get(tenantInboxInstance).build());
                }
            }
        }
        List<CompletableFuture<InboxInsertResult>> replyFutures = subMsgPacksByInbox.entrySet().stream().map(
            entry -> insertScheduler.schedule(InboxSubMessagePack.newBuilder().setTenantId(entry.getKey().tenantId())
                .setInboxId(entry.getKey().instance().inboxId()).setIncarnation(entry.getKey().instance().incarnation())
                .addAllMessagePack(entry.getValue()).build())).toList();
        return CompletableFuture.allOf(replyFutures.toArray(new CompletableFuture[0])).handle((v, e) -> {
            if (e != null) {
                if (e instanceof TryLaterException || e.getCause() instanceof TryLaterException ||
                    e instanceof BadVersionException || e.getCause() instanceof BadVersionException) {
                    return SendReply.newBuilder().setReqId(request.getReqId())
                        .setReply(DeliveryReply.newBuilder().setCode(DeliveryReply.Code.TRY_LATER).build()).build();
                }
                log.debug("Failed to insert", e);
                return SendReply.newBuilder().setReqId(request.getReqId())
                    .setReply(DeliveryReply.newBuilder().setCode(DeliveryReply.Code.ERROR).build()).build();
            }
            assert replyFutures.size() == subMsgPacksByInbox.size();
            SendReply.Builder replyBuilder = SendReply.newBuilder().setReqId(request.getReqId());
            Map<String, Map<MatchInfo, DeliveryResult.Code>> tenantMatchResultMap = new HashMap<>();
            int i = 0;
            for (TenantInboxInstance tenantInboxInstance : subMsgPacksByInbox.keySet()) {
                String receiverId = tenantInboxInstance.receiverId();
                InboxInsertResult result = replyFutures.get(i++).join();
                Map<MatchInfo, DeliveryResult.Code> matchResultMap =
                    tenantMatchResultMap.computeIfAbsent(tenantInboxInstance.tenantId(), k -> new HashMap<>());
                switch (result.getCode()) {
                    case OK -> result.getResultList().forEach(insertionResult -> {
                        DeliveryResult.Code code =
                            insertionResult.getRejected() ? DeliveryResult.Code.NO_SUB : DeliveryResult.Code.OK;
                        matchResultMap.putIfAbsent(MatchInfo.newBuilder().setReceiverId(receiverId)
                            .setMatcher(TopicUtil.from(insertionResult.getTopicFilter()))
                            .setIncarnation(insertionResult.getIncarnation()).build(), code);
                    });
                    case NO_INBOX -> {
                        for (MatchInfo matchInfo : matchInfosByInbox.get(tenantInboxInstance)) {
                            matchResultMap.putIfAbsent(matchInfo, DeliveryResult.Code.NO_RECEIVER);
                        }
                    }
                    default -> {
                        // never happen
                    }
                }
            }
            return replyBuilder.setReqId(request.getReqId()).setReply(
                DeliveryReply.newBuilder().setCode(DeliveryReply.Code.OK).putAllResult(toResult(tenantMatchResultMap))
                    .build()).build();

        });
    }
}
