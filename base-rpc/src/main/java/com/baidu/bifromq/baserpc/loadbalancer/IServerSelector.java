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

package com.baidu.bifromq.baserpc.loadbalancer;

/**
 * The interface for choosing server for load balancing.
 */
public interface IServerSelector {
    /**
     * If the server exists.
     *
     * @param serverId the server id
     * @return true if can be selected
     */
    boolean exists(String serverId);

    /**
     * Get the server group router for the tenant.
     *
     * @param tenantId the tenant id
     * @return server group router
     */
    IServerGroupRouter get(String tenantId);
}
