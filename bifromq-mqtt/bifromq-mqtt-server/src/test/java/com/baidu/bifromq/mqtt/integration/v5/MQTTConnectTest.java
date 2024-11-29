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

package com.baidu.bifromq.mqtt.integration.v5;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertFalse;

import com.baidu.bifromq.mqtt.integration.MQTTTest;
import com.baidu.bifromq.mqtt.integration.v5.client.MqttTestClient;
import com.baidu.bifromq.plugin.authprovider.type.MQTT5AuthData;
import com.baidu.bifromq.plugin.authprovider.type.MQTT5AuthResult;
import com.baidu.bifromq.plugin.authprovider.type.Success;
import com.google.common.base.Strings;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.mqttv5.client.IMqttToken;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.testng.annotations.Test;

@Slf4j
public class MQTTConnectTest extends MQTTTest {

    @Test(groups = "integration")
    public void brokerAssignClientId() {
        when(authProvider.auth(any(MQTT5AuthData.class)))
            .thenReturn(CompletableFuture.completedFuture(MQTT5AuthResult.newBuilder()
                .setSuccess(Success.newBuilder()
                    .setTenantId("tenant")
                    .setUserId("testUser")
                    .build()).build()));

        MqttConnectionOptions connOpts = new MqttConnectionOptions();
        connOpts.setCleanStart(true);
        connOpts.setSessionExpiryInterval(0L);
        connOpts.setUserName("tenant/testUser");

        MqttTestClient client = new MqttTestClient(BROKER_URI, "");
        IMqttToken token = client.connect(connOpts);
        assertFalse(Strings.isNullOrEmpty(token.getResponseProperties().getAssignedClientIdentifier()));
    }
}