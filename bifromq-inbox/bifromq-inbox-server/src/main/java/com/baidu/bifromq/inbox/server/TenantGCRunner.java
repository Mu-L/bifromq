/*
 * Copyright (c) 2025. The BifroMQ Authors. All Rights Reserved.
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

import static com.baidu.bifromq.base.util.CompletableFutureUtil.unwrap;
import static com.baidu.bifromq.basekv.client.KVRangeRouterUtil.findByBoundary;
import static com.baidu.bifromq.basekv.utils.BoundaryUtil.toBoundary;
import static com.baidu.bifromq.basekv.utils.BoundaryUtil.upperBound;
import static com.baidu.bifromq.inbox.store.schema.KVSchemaUtil.tenantBeginKeyPrefix;
import static org.reflections.Reflections.log;

import com.baidu.bifromq.basekv.client.IBaseKVStoreClient;
import com.baidu.bifromq.basekv.client.KVRangeSetting;
import com.baidu.bifromq.basekv.client.exception.BadRequestException;
import com.baidu.bifromq.basekv.client.exception.BadVersionException;
import com.baidu.bifromq.basekv.client.exception.InternalErrorException;
import com.baidu.bifromq.basekv.client.exception.TryLaterException;
import com.baidu.bifromq.basekv.store.proto.KVRangeRORequest;
import com.baidu.bifromq.basekv.store.proto.ROCoProcInput;
import com.baidu.bifromq.baserpc.client.exception.ServerNotFoundException;
import com.baidu.bifromq.inbox.rpc.proto.ExpireAllReply;
import com.baidu.bifromq.inbox.rpc.proto.ExpireAllRequest;
import com.baidu.bifromq.inbox.storage.proto.ExpireTenantReply;
import com.baidu.bifromq.inbox.storage.proto.ExpireTenantRequest;
import com.baidu.bifromq.inbox.storage.proto.InboxServiceROCoProcInput;
import com.google.protobuf.ByteString;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;

public class TenantGCRunner implements ITenantGCRunner {
    private final IBaseKVStoreClient storeClient;

    public TenantGCRunner(IBaseKVStoreClient storeClient) {
        this.storeClient = storeClient;
    }

    public CompletableFuture<ExpireAllReply> expire(ExpireAllRequest request) {
        ByteString tenantBeginKey = tenantBeginKeyPrefix(request.getTenantId());
        Collection<KVRangeSetting> rangeSettingList = findByBoundary(
            toBoundary(tenantBeginKey, upperBound(tenantBeginKey)), storeClient.latestEffectiveRouter());
        if (rangeSettingList.isEmpty()) {
            return CompletableFuture.completedFuture(ExpireAllReply.newBuilder()
                .setReqId(request.getReqId())
                .setCode(ExpireAllReply.Code.OK)
                .build());
        }
        CompletableFuture<?>[] gcResults = rangeSettingList.stream()
            .map(setting -> expire(setting, request.getTenantId(), request.getExpirySeconds(), request.getNow()))
            .toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(gcResults)
            .handle(unwrap((v, e) -> {
                if (e != null) {
                    if (e instanceof BadVersionException
                        || e instanceof TryLaterException
                        || e instanceof ServerNotFoundException) {
                        return ExpireAllReply.newBuilder()
                            .setReqId(request.getReqId())
                            .setCode(ExpireAllReply.Code.TRY_LATER)
                            .build();
                    } else {
                        log.debug("[InboxGC] Failed to do gc: reqId={}", request.getReqId(), e);
                        return ExpireAllReply.newBuilder()
                            .setReqId(request.getReqId())
                            .setCode(ExpireAllReply.Code.ERROR)
                            .build();
                    }
                }
                return ExpireAllReply.newBuilder()
                    .setReqId(request.getReqId())
                    .setCode(ExpireAllReply.Code.OK)
                    .build();
            }));
    }

    private CompletableFuture<ExpireTenantReply> expire(KVRangeSetting rangeSetting,
                                                        String tenantId,
                                                        Integer expirySeconds,
                                                        long now) {
        long reqId = System.nanoTime();
        return storeClient.query(rangeSetting.leader, KVRangeRORequest.newBuilder()
                .setReqId(reqId)
                .setKvRangeId(rangeSetting.id)
                .setVer(rangeSetting.ver)
                .setRoCoProc(ROCoProcInput.newBuilder()
                    .setInboxService(buildRequest(reqId, tenantId, expirySeconds, now))
                    .build())
                .build())
            .thenApply(v -> {
                switch (v.getCode()) {
                    case Ok -> {
                        return v.getRoCoProcResult().getInboxService().getExpireTenant();
                    }
                    case BadRequest -> throw new BadRequestException();
                    case BadVersion -> throw new BadVersionException();
                    case TryLater -> throw new TryLaterException();
                    default -> throw new InternalErrorException();
                }
            });
    }

    private InboxServiceROCoProcInput buildRequest(long reqId, String tenantId, Integer expirySeconds, long now) {
        return InboxServiceROCoProcInput.newBuilder()
            .setReqId(reqId)
            .setExpireTenant(ExpireTenantRequest.newBuilder()
                .setNow(now)
                .setTenantId(tenantId)
                .setExpirySeconds(expirySeconds)
                .build())
            .build();
    }
}
