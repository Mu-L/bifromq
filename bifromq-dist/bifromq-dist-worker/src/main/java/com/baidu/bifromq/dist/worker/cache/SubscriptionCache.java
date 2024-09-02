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

package com.baidu.bifromq.dist.worker.cache;

import static com.baidu.bifromq.basekv.utils.BoundaryUtil.intersect;
import static com.baidu.bifromq.dist.entity.EntityUtil.matchRecordKeyPrefix;
import static com.baidu.bifromq.dist.entity.EntityUtil.tenantUpperBound;
import static com.baidu.bifromq.dist.worker.cache.SubscriptionCache.TenantKey.noRefreshExpiry;
import static com.baidu.bifromq.dist.worker.cache.SubscriptionCache.TenantKey.refreshExpiry;

import com.baidu.bifromq.basekv.proto.Boundary;
import com.baidu.bifromq.basekv.proto.KVRangeId;
import com.baidu.bifromq.basekv.store.api.IKVCloseableReader;
import com.baidu.bifromq.basekv.utils.KVRangeIdUtil;
import com.baidu.bifromq.dist.entity.Matching;
import com.baidu.bifromq.sysprops.props.DistTopicMatchExpirySeconds;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.github.benmanes.caffeine.cache.Scheduler;
import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;

/**
 * Cache for subscription matching.
 */
@Slf4j
public class SubscriptionCache {
    @EqualsAndHashCode
    static class TenantKey {
        final String tenantId;
        @EqualsAndHashCode.Exclude
        final boolean refreshExpiry;

        private TenantKey(String tenantId, boolean refreshExpiry) {
            this.tenantId = tenantId;
            this.refreshExpiry = refreshExpiry;
        }

        static TenantKey refreshExpiry(String tenantId) {
            return new TenantKey(tenantId, true);
        }

        static TenantKey noRefreshExpiry(String tenantId) {
            return new TenantKey(tenantId, false);
        }
    }

    private final Executor matchExecutor;
    private final ITenantRouteCacheFactory tenantRouteCacheFactory;
    private final LoadingCache<TenantKey, ITenantRouteCache> tenantCache;
    private volatile Boundary boundary;

    public SubscriptionCache(KVRangeId id, Supplier<IKVCloseableReader> rangeReaderProvider, Executor matchExecutor) {
        this(id, new TenantRouteCacheFactory(rangeReaderProvider,
                Duration.ofSeconds(DistTopicMatchExpirySeconds.INSTANCE.get()), "id", KVRangeIdUtil.toString(id)),
            matchExecutor, Ticker.systemTicker());
    }

    public SubscriptionCache(KVRangeId id,
                             ITenantRouteCacheFactory tenantRouteCacheFactory,
                             Executor matchExecutor,
                             Ticker ticker) {
        this.matchExecutor = matchExecutor;
        this.tenantRouteCacheFactory = tenantRouteCacheFactory;
        long expiryNanos = tenantRouteCacheFactory.expiry().multipliedBy(2).toNanos();
        tenantCache = Caffeine.newBuilder()
            .ticker(ticker)
            .expireAfter(new Expiry<TenantKey, ITenantRouteCache>() {
                @Override
                public long expireAfterCreate(TenantKey key, ITenantRouteCache value, long currentTime) {
                    return expiryNanos;
                }

                @Override
                public long expireAfterUpdate(TenantKey key, ITenantRouteCache value, long currentTime,
                                              long currentDuration) {
                    return expiryNanos;
                }

                @Override
                public long expireAfterRead(TenantKey key, ITenantRouteCache value, long currentTime,
                                            long currentDuration) {
                    return key.refreshExpiry ? expiryNanos : currentDuration;
                }
            })
            .scheduler(Scheduler.systemScheduler())
            .evictionListener((key, cachedTenantRoutes, cause) -> {
                if (cachedTenantRoutes != null) {
                    cachedTenantRoutes.destroy();
                }
            })
            .build(k -> tenantRouteCacheFactory.create(k.tenantId));
    }

    public CompletableFuture<Set<Matching>> get(String tenantId, String topic) {
        ITenantRouteCache routesCache = tenantCache.get(refreshExpiry(tenantId));
        Boundary tenantBoundary = intersect(Boundary.newBuilder()
            .setStartKey(matchRecordKeyPrefix(tenantId))
            .setEndKey(tenantUpperBound(tenantId))
            .build(), boundary);
        Set<Matching> matches = routesCache.getIfPresent(topic, tenantBoundary);
        if (matches != null) {
            // cache hit: fast path
            return CompletableFuture.completedFuture(matches);
        }
        // cache miss: slow path
        return CompletableFuture.supplyAsync(() -> routesCache.get(topic, tenantBoundary), matchExecutor);
    }

    public void removeAllMatch(Map<String, Map<String, Matching>> matchesByTenant) {
        matchesByTenant.forEach(
            (tenantId, matches) -> {
                ITenantRouteCache cache = tenantCache.getIfPresent(noRefreshExpiry(tenantId));
                if (cache != null) {
                    cache.removeAllMatch(matches);
                }
            });
    }

    public void addAllMatch(Map<String, Map<String, Matching>> matchesByTenant) {
        matchesByTenant.forEach((tenantId, matches) -> {
            ITenantRouteCache cache = tenantCache.getIfPresent(noRefreshExpiry(tenantId));
            if (cache != null) {
                cache.addAllMatch(matches);
            }
        });
    }

    public void reset(Boundary boundary) {
        this.boundary = boundary;
    }

    public void close() {
        tenantCache.invalidateAll();
        tenantRouteCacheFactory.close();
    }
}