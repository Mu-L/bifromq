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

package com.baidu.bifromq.sessiondict;

import com.baidu.bifromq.baserpc.BluePrint;
import com.baidu.bifromq.sessiondict.rpc.proto.GetRequest;
import com.baidu.bifromq.sessiondict.rpc.proto.KillRequest;
import com.baidu.bifromq.sessiondict.rpc.proto.SessionDictServiceGrpc;
import com.baidu.bifromq.sessiondict.rpc.proto.SubRequest;
import com.baidu.bifromq.sessiondict.rpc.proto.UnsubRequest;

public class RPCBluePrint {
    public static final BluePrint INSTANCE = BluePrint.builder()
        .serviceDescriptor(SessionDictServiceGrpc.getServiceDescriptor())
        .methodSemantic(SessionDictServiceGrpc.getDictMethod(), BluePrint.WCHStreamingMethod.getInstance())
        .methodSemantic(SessionDictServiceGrpc.getExistMethod(), BluePrint.WCHPipelineUnaryMethod.getInstance())
        .methodSemantic(SessionDictServiceGrpc.getKillMethod(),
            BluePrint.WCHUnaryMethod.<KillRequest>builder()
                .keyHashFunc(r -> SessionRegisterKeyUtil.toRegisterKey(r.getTenantId(), r.getUserId(), r.getClientId()))
                .build())
        .methodSemantic(SessionDictServiceGrpc.getKillAllMethod(), BluePrint.DDUnaryMethod.getInstance())
        .methodSemantic(SessionDictServiceGrpc.getGetMethod(), BluePrint.WCHUnaryMethod.<GetRequest>builder()
            .keyHashFunc(r -> SessionRegisterKeyUtil.toRegisterKey(r.getTenantId(), r.getUserId(), r.getClientId()))
            .build())
        .methodSemantic(SessionDictServiceGrpc.getSubMethod(), BluePrint.WCHUnaryMethod.<SubRequest>builder()
            .keyHashFunc(r -> SessionRegisterKeyUtil.toRegisterKey(r.getTenantId(), r.getUserId(), r.getClientId()))
            .build())
        .methodSemantic(SessionDictServiceGrpc.getUnsubMethod(), BluePrint.WCHUnaryMethod.<UnsubRequest>builder()
            .keyHashFunc(r -> SessionRegisterKeyUtil.toRegisterKey(r.getTenantId(), r.getUserId(), r.getClientId()))
            .build())
        .build();
}
