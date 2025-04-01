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

package com.baidu.bifromq.retain.store.gc;

import java.util.concurrent.CompletableFuture;
import javax.annotation.Nullable;

public interface IRetainStoreGCProcessor {
    CompletableFuture<Result> gc(long reqId,
                                 @Nullable String tenantId,
                                 @Nullable Integer expirySeconds,
                                 long now);

    enum Result {
        OK, TRY_LATER, ERROR
    }
}
