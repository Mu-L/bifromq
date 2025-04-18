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
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;

import com.baidu.bifromq.basehlc.HLC;
import com.baidu.bifromq.inbox.storage.proto.BatchAttachRequest;
import com.baidu.bifromq.inbox.storage.proto.BatchDetachReply;
import com.baidu.bifromq.inbox.storage.proto.BatchDetachRequest;
import com.baidu.bifromq.inbox.storage.proto.InboxVersion;
import com.baidu.bifromq.plugin.eventcollector.EventType;
import com.baidu.bifromq.sessiondict.client.type.ExistResult;
import com.baidu.bifromq.type.ClientInfo;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.testng.annotations.Test;

public class InboxSessionEventTest extends InboxStoreTest {
    @Test(groups = "integration")
    public void sessionStart() {
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
        verify(eventCollector).report(argThat(e -> e.type() == EventType.MQTT_SESSION_START));
    }

    @Test(groups = "integration")
    public void noSessionStopBeforeExpire() {
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

        reset(eventCollector);
        BatchDetachRequest.Params detachedParams = BatchDetachRequest.Params.newBuilder()
            .setTenantId(tenantId)
            .setInboxId(inboxId)
            .setExpirySeconds(5)
            .setVersion(versionList.get(0))
            .setNow(now)
            .build();
        assertEquals(requestDetach(detachedParams).get(0), BatchDetachReply.Code.OK);

        verify(eventCollector, timeout(4000).times(0))
            .report(argThat(e -> e.type() == EventType.MQTT_SESSION_STOP));
    }

    @Test(groups = "integration")
    public void sessionStopAfterExpire() {
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
        InboxVersion inboxVersion = requestAttach(attachParams).get(0);

        BatchDetachRequest.Params detachedParams = BatchDetachRequest.Params.newBuilder()
            .setTenantId(tenantId)
            .setInboxId(inboxId)
            .setExpirySeconds(2)
            .setVersion(inboxVersion)
            .setNow(now)
            .build();
        assertEquals(requestDetach(detachedParams).get(0), BatchDetachReply.Code.OK);

        verify(inboxClient, timeout(4000).times(1))
            .delete(argThat(deleteRequest -> deleteRequest.getTenantId().equals(tenantId)
                && deleteRequest.getInboxId().equals(inboxId)
                && deleteRequest.getVersion().getIncarnation() == inboxVersion.getIncarnation()
                && deleteRequest.getVersion().getMod() == inboxVersion.getMod() + 1));
    }
}
