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

package com.baidu.bifromq.dist.worker.cache;

import com.baidu.bifromq.basekv.proto.Boundary;
import com.baidu.bifromq.dist.entity.Matching;
import com.baidu.bifromq.dist.worker.TopicIndex;
import com.baidu.bifromq.metrics.ITenantMeter;
import com.baidu.bifromq.metrics.TenantMetric;
import com.baidu.bifromq.sysprops.props.DistMaxCachedRoutesPerTenant;
import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.CacheLoader;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Scheduler;
import com.github.benmanes.caffeine.cache.Ticker;
import com.github.benmanes.caffeine.cache.Weigher;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.checkerframework.checker.index.qual.NonNegative;
import org.checkerframework.checker.nullness.qual.Nullable;

class TenantRouteCache implements ITenantRouteCache {
    private record RouteCacheKey(String topic, Boundary matchRecordBoundary) {
    }

    private final String tenantId;
    private final AsyncLoadingCache<RouteCacheKey, Set<Matching>> routesCache;
    private final TopicIndex<RouteCacheKey> index;

    TenantRouteCache(String tenantId,
                     ITenantRouteMatcher matcher,
                     Duration expiryAfterAccess,
                     Executor matchExecutor) {
        this(tenantId, matcher, expiryAfterAccess, Ticker.systemTicker(), matchExecutor);
    }

    TenantRouteCache(String tenantId,
                     ITenantRouteMatcher matcher,
                     Duration expiryAfterAccess,
                     Ticker ticker,
                     Executor matchExecutor) {
        this.tenantId = tenantId;
        index = new TopicIndex<>();
        routesCache = Caffeine.newBuilder()
            .scheduler(Scheduler.systemScheduler())
            .ticker(ticker)
            .executor(matchExecutor)
            .maximumWeight(DistMaxCachedRoutesPerTenant.INSTANCE.get())
            .weigher(new Weigher<RouteCacheKey, Set<Matching>>() {
                @Override
                public @NonNegative int weigh(RouteCacheKey key, Set<Matching> value) {
                    return value.size();
                }
            })
            .expireAfterAccess(expiryAfterAccess)
            .evictionListener((key, value, cause) -> {
                if (key != null) {
                    index.remove(key.topic, key);
                }
            })
            .buildAsync(new CacheLoader<>() {
                @Override
                public @Nullable Set<Matching> load(RouteCacheKey key) {
                    ITenantMeter.get(tenantId).recordCount(TenantMetric.MqttRouteCacheMissCount);
                    Map<String, Set<Matching>> results = matcher.matchAll(Collections.singleton(key.topic));
                    index.add(key.topic, key);
                    return results.get(key.topic);
                }

                @Override
                public Map<RouteCacheKey, Set<Matching>> loadAll(Set<? extends RouteCacheKey> keys) {
                    ITenantMeter.get(tenantId).recordCount(TenantMetric.MqttRouteCacheMissCount, keys.size());
                    Map<String, RouteCacheKey> topicToKeyMap = new HashMap<>();
                    keys.forEach(k -> topicToKeyMap.put(k.topic(), k));
                    Map<String, Set<Matching>> resultMap = matcher.matchAll(topicToKeyMap.keySet());
                    Map<RouteCacheKey, Set<Matching>> result = new HashMap<>();
                    for (Map.Entry<String, Set<Matching>> entry : resultMap.entrySet()) {
                        RouteCacheKey key = topicToKeyMap.get(entry.getKey());
                        result.put(key, entry.getValue());
                        index.add(key.topic, key);
                    }
                    return result;
                }

                @Override
                public @Nullable Set<Matching> reload(RouteCacheKey key, Set<Matching> oldValue) {
                    Map<String, Set<Matching>> results = matcher.matchAll(Collections.singleton(key.topic));
                    return results.get(key.topic);
                }
            });
        ITenantMeter.gauging(tenantId, TenantMetric.MqttRouteCacheSize, routesCache.synchronous()::estimatedSize);
    }

    @Override
    public void refresh(Set<String> topicFilters) {
        topicFilters.forEach(topicFilter -> {
            for (RouteCacheKey cacheKey : index.match(topicFilter)) {
                routesCache.synchronous().refresh(cacheKey);
            }
        });
    }

    @Override
    public CompletableFuture<Set<Matching>> getMatch(String topic, Boundary currentTenantRange) {
        return routesCache.get(new RouteCacheKey(topic, currentTenantRange));
    }

    @Override
    public void destroy() {
        ITenantMeter.stopGauging(tenantId, TenantMetric.MqttRouteCacheSize);
    }
}
