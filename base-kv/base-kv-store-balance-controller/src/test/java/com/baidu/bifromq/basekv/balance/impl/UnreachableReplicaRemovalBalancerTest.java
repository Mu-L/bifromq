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

package com.baidu.bifromq.basekv.balance.impl;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import com.baidu.bifromq.basekv.balance.command.BalanceCommand;
import com.baidu.bifromq.basekv.balance.command.ChangeConfigCommand;
import com.baidu.bifromq.basekv.proto.KVRangeDescriptor;
import com.baidu.bifromq.basekv.proto.KVRangeId;
import com.baidu.bifromq.basekv.proto.KVRangeStoreDescriptor;
import com.baidu.bifromq.basekv.proto.State;
import com.baidu.bifromq.basekv.raft.proto.ClusterConfig;
import com.baidu.bifromq.basekv.raft.proto.RaftNodeStatus;
import com.baidu.bifromq.basekv.raft.proto.RaftNodeSyncState;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

public class UnreachableReplicaRemovalBalancerTest {

    private UnreachableReplicaRemovalBalancer balancer;
    private Supplier<Long> mockTimeSource;
    private final String localStoreId = "localStore";
    private final String peerStoreId = "peerStore";
    private final KVRangeId rangeId = KVRangeId.newBuilder().setEpoch(1).setId(1).build();

    @BeforeMethod
    public void setUp() {
        mockTimeSource = mock(Supplier.class);
        when(mockTimeSource.get()).thenReturn(System.currentTimeMillis());
        balancer =
            new UnreachableReplicaRemovalBalancer("clusterId", localStoreId, Duration.ofSeconds(15), mockTimeSource);
    }

    @Test
    public void noChangeWhenAllReplicasAreReachable() {
        KVRangeStoreDescriptor storeDescriptor = createStoreDescriptor(
            localStoreId,
            createRangeDescriptor(rangeId,
                RaftNodeStatus.Leader,
                Map.of(localStoreId, RaftNodeSyncState.Replicating),
                Set.of(localStoreId), Set.of())
        );

        balancer.update(Set.of(storeDescriptor));

        Optional<BalanceCommand> commandOpt = balancer.balance();
        assertTrue(commandOpt.isEmpty());
    }

    @Test
    public void removesUnreachableVoterReplicaAfterTimeout() {
        KVRangeStoreDescriptor localStoreDescriptor = createStoreDescriptor(
            localStoreId,
            createRangeDescriptor(rangeId, RaftNodeStatus.Leader,
                Map.of(localStoreId, RaftNodeSyncState.Replicating, peerStoreId, RaftNodeSyncState.Probing),
                Set.of(localStoreId, peerStoreId), Set.of())
        );
        KVRangeStoreDescriptor peerStoreDescriptor = createStoreDescriptor(peerStoreId);

        // Simulate initial time setting
        when(mockTimeSource.get()).thenReturn(System.currentTimeMillis());

        balancer.update(Set.of(localStoreDescriptor, peerStoreDescriptor));

        // Simulate time passing to make the replica unhealthy
        when(mockTimeSource.get()).thenReturn(System.currentTimeMillis() + 16000);

        Optional<BalanceCommand> commandOpt = balancer.balance();
        assertTrue(commandOpt.isPresent());

        BalanceCommand command = commandOpt.get();
        assertTrue(command instanceof ChangeConfigCommand);
        ChangeConfigCommand changeConfigCommand = (ChangeConfigCommand) command;

        // Verify that the unhealthy replica is scheduled for removal
        assertEquals(localStoreId, changeConfigCommand.getToStore());
        assertEquals(rangeId, changeConfigCommand.getKvRangeId());
        assertEquals(5, changeConfigCommand.getExpectedVer());
        assertFalse(changeConfigCommand.getVoters().contains(peerStoreId));
    }

    @Test
    public void removesUnreachableLearnerReplicaAfterTimeout() {
        KVRangeStoreDescriptor localStoreDescriptor = createStoreDescriptor(
            localStoreId,
            createRangeDescriptor(rangeId, RaftNodeStatus.Leader,
                Map.of(localStoreId, RaftNodeSyncState.Replicating, peerStoreId, RaftNodeSyncState.Probing),
                Set.of(localStoreId), Set.of(peerStoreId))
        );
        // peerStore exists but no range hosted
        KVRangeStoreDescriptor peerStoreDescriptor = createStoreDescriptor(peerStoreId);
        // Simulate initial time setting
        when(mockTimeSource.get()).thenReturn(System.currentTimeMillis());

        balancer.update(Set.of(localStoreDescriptor, peerStoreDescriptor));

        // Simulate time passing to make the replica unhealthy
        when(mockTimeSource.get()).thenReturn(System.currentTimeMillis() + 16000);

        Optional<BalanceCommand> commandOpt = balancer.balance();
        assertTrue(commandOpt.isPresent());

        BalanceCommand command = commandOpt.get();
        assertTrue(command instanceof ChangeConfigCommand);
        ChangeConfigCommand changeConfigCommand = (ChangeConfigCommand) command;

        // Verify that the unhealthy replica is scheduled for removal
        assertEquals(localStoreId, changeConfigCommand.getToStore());
        assertEquals(rangeId, changeConfigCommand.getKvRangeId());
        assertEquals(5, changeConfigCommand.getExpectedVer());
        assertFalse(changeConfigCommand.getLearners().contains(peerStoreId));
    }


    @Test
    public void noCommandIfReplicaReachableAgain() {
        KVRangeStoreDescriptor localStoreDescriptor = createStoreDescriptor(
            localStoreId,
            createRangeDescriptor(rangeId, RaftNodeStatus.Leader,
                Map.of(localStoreId, RaftNodeSyncState.Replicating, peerStoreId, RaftNodeSyncState.Probing),
                Set.of(localStoreId, peerStoreId), Set.of())
        );
        KVRangeStoreDescriptor peerStoreDescriptor = createStoreDescriptor(
            peerStoreId,
            createRangeDescriptor(rangeId, RaftNodeStatus.Follower, Collections.emptyMap(),
                Set.of(localStoreId), Set.of(peerStoreId))
        );

        balancer.update(Set.of(localStoreDescriptor, peerStoreDescriptor));

        // Simulate the replica recovering before timeout
        KVRangeStoreDescriptor updatedDescriptor = createStoreDescriptor(
            localStoreId,
            createRangeDescriptor(rangeId, RaftNodeStatus.Leader,
                Map.of(localStoreId, RaftNodeSyncState.Replicating, peerStoreId, RaftNodeSyncState.Replicating),
                Set.of(localStoreId, peerStoreId), Set.of())
        );

        balancer.update(Set.of(updatedDescriptor, peerStoreDescriptor));

        Optional<BalanceCommand> commandOpt = balancer.balance();
        assertTrue(commandOpt.isEmpty());
    }

    @Test
    public void removesReplicaIfLeaderChanged() {
        KVRangeStoreDescriptor storeDescriptor = createStoreDescriptor(
            localStoreId,
            createRangeDescriptor(rangeId, RaftNodeStatus.Leader,
                Map.of(localStoreId, RaftNodeSyncState.Replicating, peerStoreId, RaftNodeSyncState.Probing),
                Set.of(localStoreId, peerStoreId), Set.of())
        );
        KVRangeStoreDescriptor peerStoreDescriptor = createStoreDescriptor(
            peerStoreId,
            createRangeDescriptor(rangeId, RaftNodeStatus.Follower, Collections.emptyMap(),
                Set.of(localStoreId), Set.of(peerStoreId))
        );


        balancer.update(Set.of(storeDescriptor, peerStoreDescriptor));

        // Simulate a leader change
        KVRangeStoreDescriptor updatedDescriptor = createStoreDescriptor(
            localStoreId,
            createRangeDescriptor(rangeId, RaftNodeStatus.Follower, Collections.emptyMap(),
                Set.of(localStoreId, peerStoreId), Set.of())
        );

        balancer.update(Set.of(updatedDescriptor, peerStoreDescriptor));
        when(mockTimeSource.get()).thenReturn(System.currentTimeMillis() + 16000);

        Optional<BalanceCommand> commandOpt = balancer.balance();
        assertTrue(commandOpt.isEmpty());
    }

    @Test
    public void handlesReplicaRemoval() {
        KVRangeStoreDescriptor storeDescriptor = createStoreDescriptor(
            localStoreId,
            createRangeDescriptor(rangeId, RaftNodeStatus.Leader,
                Map.of(localStoreId, RaftNodeSyncState.Replicating, peerStoreId, RaftNodeSyncState.Probing),
                Set.of(localStoreId, peerStoreId), Set.of())
        );

        KVRangeStoreDescriptor peerStoreDescriptor = createStoreDescriptor(peerStoreId);

        balancer.update(Set.of(storeDescriptor, peerStoreDescriptor));
        when(mockTimeSource.get()).thenReturn(System.currentTimeMillis() + 16000);
        assertTrue(balancer.balance().isPresent());

        // Simulate replica removal
        KVRangeStoreDescriptor updatedStoreDescriptor = createStoreDescriptor(
            localStoreId,
            createRangeDescriptor(rangeId, RaftNodeStatus.Leader,
                Map.of(localStoreId, RaftNodeSyncState.Replicating, peerStoreId, RaftNodeSyncState.Replicating),
                Set.of(localStoreId, peerStoreId), Set.of())
        );

        balancer.update(Set.of(updatedStoreDescriptor));

        when(mockTimeSource.get()).thenReturn(System.currentTimeMillis() + 32000);

        Optional<BalanceCommand> commandOpt = balancer.balance();
        assertTrue(commandOpt.isEmpty());
    }

    private KVRangeStoreDescriptor createStoreDescriptor(String storeId, KVRangeDescriptor... rangeDescriptors) {
        return KVRangeStoreDescriptor.newBuilder()
            .setId(storeId)
            .addAllRanges(Arrays.asList(rangeDescriptors))
            .build();
    }

    private KVRangeDescriptor createRangeDescriptor(KVRangeId rangeId,
                                                    RaftNodeStatus status,
                                                    Map<String, RaftNodeSyncState> syncState,
                                                    Set<String> voters,
                                                    Set<String> learners) {
        return KVRangeDescriptor.newBuilder()
            .setId(rangeId)
            .setRole(status)
            .setVer(5)
            .setState(State.StateType.Normal)
            .setConfig(ClusterConfig.newBuilder().addAllVoters(voters).addAllLearners(learners).build())
            .putAllSyncState(syncState)
            .build();
    }
}