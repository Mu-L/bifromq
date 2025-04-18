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

package com.baidu.bifromq.inbox.store;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import com.baidu.bifromq.basehlc.HLC;
import com.baidu.bifromq.inbox.storage.proto.BatchAttachRequest;
import com.baidu.bifromq.inbox.storage.proto.BatchDetachReply;
import com.baidu.bifromq.inbox.storage.proto.BatchDetachRequest;
import com.baidu.bifromq.inbox.storage.proto.BatchExistRequest;
import com.baidu.bifromq.inbox.storage.proto.InboxVersion;
import com.baidu.bifromq.sessiondict.client.type.ExistResult;
import com.baidu.bifromq.type.ClientInfo;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.testng.annotations.Test;

public class InboxExistTest extends InboxStoreTest {
    @Test(groups = "integration")
    public void exist() {
        long now = 0;
        String tenantId = "tenantId-" + System.nanoTime();
        String inboxId1 = "inboxId1-" + System.nanoTime();
        String inboxId2 = "inboxId2-" + System.nanoTime();
        BatchExistRequest.Params getParams1 = BatchExistRequest.Params.newBuilder()
            .setTenantId(tenantId)
            .setInboxId(inboxId1)
            .setNow(now)
            .build();
        BatchExistRequest.Params getParams2 = BatchExistRequest.Params.newBuilder()
            .setTenantId(tenantId)
            .setInboxId(inboxId2)
            .setNow(now)
            .build();
        for (boolean result : requestExist(getParams1, getParams2)) {
            assertFalse(result);
        }
    }

    @Test(groups = "integration")
    public void existAfterAttach() {
        long now = HLC.INST.getPhysical();
        String tenantId = "tenantId-" + System.nanoTime();
        String inboxId = "inboxId-" + System.nanoTime();
        long incarnation = System.nanoTime();
        ClientInfo client = ClientInfo.newBuilder().setTenantId(tenantId).build();
        when(sessionDictClient.exist(any())).thenReturn(CompletableFuture.completedFuture(ExistResult.EXISTS));
        BatchAttachRequest.Params attachParams = BatchAttachRequest.Params.newBuilder()
            .setInboxId(inboxId)
            .setIncarnation(incarnation)
            .setExpirySeconds(5)
            .setClient(client)
            .setNow(now)
            .build();
        requestAttach(attachParams);
        BatchExistRequest.Params existParams = BatchExistRequest.Params.newBuilder()
            .setTenantId(tenantId)
            .setInboxId(inboxId)
            .setNow(now)
            .build();
        boolean result = requestExist(existParams).get(0);
        assertTrue(result);
    }

    @Test(groups = "integration")
    public void existBeforeExpire() {
        long now = HLC.INST.getPhysical();
        String tenantId = "tenantId-" + System.nanoTime();
        String inboxId = "inboxId-" + System.nanoTime();
        long incarnation = System.nanoTime();
        ClientInfo client = ClientInfo.newBuilder().setTenantId(tenantId).build();
        when(sessionDictClient.exist(any())).thenReturn(CompletableFuture.completedFuture(ExistResult.EXISTS));
        BatchAttachRequest.Params attachParams = BatchAttachRequest.Params.newBuilder()
            .setInboxId(inboxId)
            .setIncarnation(incarnation)
            .setExpirySeconds(5)
            .setClient(client)
            .setNow(now)
            .build();
        List<InboxVersion> versionList = requestAttach(attachParams);

        BatchExistRequest.Params existParams = BatchExistRequest.Params.newBuilder()
            .setTenantId(tenantId)
            .setInboxId(inboxId)
            .setNow(now)
            .build();
        assertTrue(requestExist(existParams).get(0));

        BatchDetachRequest.Params detachedParams = BatchDetachRequest.Params.newBuilder()
            .setTenantId(tenantId)
            .setInboxId(inboxId)
            .setExpirySeconds(10)
            .setVersion(versionList.get(0))
            .setNow(now)
            .build();
        assertEquals(requestDetach(detachedParams).get(0), BatchDetachReply.Code.OK);

        existParams = BatchExistRequest.Params.newBuilder()
            .setTenantId(tenantId)
            .setInboxId(inboxId)
            .setNow(Duration.ofMillis(now).plusSeconds(9).toMillis())
            .build();
        assertTrue(requestExist(existParams).get(0));
    }

    @Test(groups = "integration")
    public void existAfterExpire() {
        long now = HLC.INST.getPhysical();
        String tenantId = "tenantId-" + System.nanoTime();
        String inboxId = "inboxId-" + System.nanoTime();
        long incarnation = System.nanoTime();
        ClientInfo client = ClientInfo.newBuilder().setTenantId(tenantId).build();
        when(sessionDictClient.exist(any())).thenReturn(CompletableFuture.completedFuture(ExistResult.EXISTS));
        BatchAttachRequest.Params attachParams = BatchAttachRequest.Params.newBuilder()
            .setInboxId(inboxId)
            .setIncarnation(incarnation)
            .setExpirySeconds(5)
            .setClient(client)
            .setNow(now)
            .build();
        List<InboxVersion> versionList = requestAttach(attachParams);

        BatchExistRequest.Params existParams = BatchExistRequest.Params.newBuilder()
            .setTenantId(tenantId)
            .setInboxId(inboxId)
            .setNow(now)
            .build();
        assertTrue(requestExist(existParams).get(0));

        BatchDetachRequest.Params detachedParams = BatchDetachRequest.Params.newBuilder()
            .setTenantId(tenantId)
            .setInboxId(inboxId)
            .setExpirySeconds(10)
            .setVersion(versionList.get(0))
            .setNow(now)
            .build();
        assertEquals(requestDetach(detachedParams).get(0), BatchDetachReply.Code.OK);

        existParams = BatchExistRequest.Params.newBuilder()
            .setTenantId(tenantId)
            .setInboxId(inboxId)
            .setNow(Duration.ofMillis(now).plusSeconds(11).toMillis())
            .build();
        assertFalse(requestExist(existParams).get(0));
    }
}
