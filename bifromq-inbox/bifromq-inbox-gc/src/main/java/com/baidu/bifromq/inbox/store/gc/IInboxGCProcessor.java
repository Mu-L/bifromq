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

package com.baidu.bifromq.inbox.store.gc;

import com.baidu.bifromq.basekv.proto.KVRangeId;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nullable;

public interface IInboxGCProcessor {
    enum Result {
        OK, ERROR;
    }

    CompletableFuture<Result> gcRange(KVRangeId rangeId,
                                      @Nullable String tenantId,
                                      @Nullable Integer expirySeconds,
                                      long now,
                                      int limit);
}