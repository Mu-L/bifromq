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

package com.baidu.bifromq.dist.entity;


import static com.baidu.bifromq.dist.entity.EntityUtil.toQInboxId;
import static org.testng.Assert.assertEquals;

import com.baidu.bifromq.dist.rpc.proto.GroupMatchRecord;
import com.google.protobuf.ByteString;
import java.util.Set;
import org.testng.annotations.Test;

public class GroupMatchingTest {

    @Test
    public void addAllReceivers() {
        String tenantId = "tenant1";
        String topicFilter = "$oshare/group1/home/sensor/temperature";

        ByteString key = EntityUtil.toGroupMatchRecordKey(tenantId, topicFilter);
        GroupMatchRecord matchRecord = GroupMatchRecord.newBuilder()
            .addQReceiverId(toQInboxId(1, "inbox1", "deliverer1"))
            .build();

        GroupMatching matching = (GroupMatching) EntityUtil.parseMatchRecord(key, matchRecord.toByteString());

        Set<String> receiverIds =
            Set.of(toQInboxId(1, "inbox2", "deliverer2"), toQInboxId(1, "inbox3", "deliverer3"));

        matching.addAll(receiverIds);

        assertEquals(3, matching.receiverIds.size());
        assertEquals(3, matching.receiverList.size());
    }

    @Test
    public void removeAllReceivers() {
        String tenantId = "tenant1";
        String topicFilter = "$oshare/group1/home/sensor/temperature";

        ByteString key = EntityUtil.toGroupMatchRecordKey(tenantId, topicFilter);
        GroupMatchRecord matchRecord = GroupMatchRecord.newBuilder()
            .addQReceiverId(toQInboxId(1, "inbox1", "deliverer1"))
            .addQReceiverId(toQInboxId(1, "inbox2", "deliverer2"))
            .addQReceiverId(toQInboxId(1, "inbox3", "deliverer3"))
            .build();

        GroupMatching matching = (GroupMatching) EntityUtil.parseMatchRecord(key, matchRecord.toByteString());

        Set<String> receiverIds =
            Set.of(toQInboxId(1, "inbox2", "deliverer2"), toQInboxId(1, "inbox3", "deliverer3"));

        matching.removeAll(receiverIds);

        assertEquals(1, matching.receiverIds.size());
        assertEquals(1, matching.receiverList.size());
        assertEquals("inbox1", matching.receiverList.get(0).matchInfo.getReceiverId());
    }
}