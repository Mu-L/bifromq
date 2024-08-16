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

package com.baidu.bifromq.basekv.localengine.rocksdb;

import com.baidu.bifromq.basekv.localengine.AbstractKVEngine;
import com.baidu.bifromq.basekv.localengine.KVEngineException;
import com.google.common.base.Strings;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Tags;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Stream;

public abstract class RocksDBKVEngine<
    E extends RocksDBKVEngine<E, T, C>,
    T extends RocksDBKVSpace<E, T, C>,
    C extends RocksDBKVEngineConfigurator<C>
    > extends AbstractKVEngine<T> {
    private final File dbRootDir;
    private final C configurator;
    private final ConcurrentMap<String, T> kvSpaceMap = new ConcurrentHashMap<>();
    private final String identity;
    private final boolean isCreate;
    private MetricManager metricManager;

    public RocksDBKVEngine(String overrideIdentity, C configurator) {
        super(overrideIdentity);
        this.configurator = configurator;
        dbRootDir = new File(configurator.dbRootDir());
        try {
            Files.createDirectories(dbRootDir.getAbsoluteFile().toPath());
            isCreate = isEmpty(dbRootDir.toPath());
            identity = loadIdentity(isCreate);
        } catch (Throwable e) {
            throw new KVEngineException("Failed to initialize RocksDBKVEngine", e);
        }
    }

    @Override
    public T createIfMissing(String spaceId) {
        assertStarted();
        return kvSpaceMap.computeIfAbsent(spaceId,
            k -> buildKVSpace(spaceId, configurator, () -> kvSpaceMap.remove(spaceId), metricTags).open());
    }

    protected abstract T buildKVSpace(String spaceId, C configurator, Runnable onDestroy, String... tags);

    @Override
    protected void doStart(String... tags) {
        log.info("RocksDBKVEngine[{}] {} at path[{}]",
            identity, isCreate ? "initialized" : "loaded", dbRootDir.getName());
        loadExisting(tags);
        metricManager = new MetricManager(tags);
    }

    @Override
    protected void afterStart() {
        super.afterStart();
    }

    @Override
    public Map<String, T> spaces() {
        assertStarted();
        return Collections.unmodifiableMap(kvSpaceMap);
    }

    @Override
    protected void doStop() {
        log.info("Stopping RocksDBKVEngine[{}]", identity);
        metricManager.close();
        kvSpaceMap.values().forEach(RocksDBKVSpace::close);
    }

    @Override
    public String id() {
        return identity;
    }

    private void loadExisting(String... metricTags) {
        try (Stream<Path> paths = Files.list(Paths.get(dbRootDir.getAbsolutePath()))) {
            paths.filter(Files::isDirectory)
                .map(Path::getFileName)
                .map(Path::toString)
                .forEach(keySpaceId -> kvSpaceMap.put(keySpaceId,
                    buildKVSpace(keySpaceId, configurator, () -> kvSpaceMap.remove(keySpaceId), metricTags).open()));
        } catch (Throwable e) {
            log.error("Failed to load existing key spaces", e);
        }
    }

    private String loadIdentity(boolean create) {
        try {
            Path identityFilePath = Paths.get(dbRootDir.getAbsolutePath(), "IDENTITY");
            if (create) {
                String identity =
                    Strings.isNullOrEmpty(overrideIdentity) ? UUID.randomUUID().toString() : overrideIdentity.trim();
                Files.writeString(identityFilePath, identity, StandardOpenOption.CREATE);
            }
            return Files.readAllLines(identityFilePath).get(0);
        } catch (IndexOutOfBoundsException | IOException e) {
            throw new KVEngineException("Failed to read IDENTITY file", e);
        }
    }

    private static boolean isEmpty(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (Stream<Path> entries = Files.list(path)) {
                return entries.findFirst().isEmpty();
            }
        }
        return false;
    }

    private class MetricManager {
        private final Gauge dataTotalSpaceGauge;
        private final Gauge dataUsableSpaceGauge;

        MetricManager(String... metricTags) {
            Tags tags = Tags.of(metricTags);
            dataTotalSpaceGauge = Gauge.builder("basekv.le.rocksdb.total.data", dbRootDir::getTotalSpace)
                .tags(tags)
                .register(Metrics.globalRegistry);
            dataUsableSpaceGauge = Gauge.builder("basekv.le.rocksdb.usable.data", dbRootDir::getUsableSpace)
                .tags(tags)
                .register(Metrics.globalRegistry);
        }

        void close() {
            Metrics.globalRegistry.remove(dataTotalSpaceGauge);
            Metrics.globalRegistry.remove(dataUsableSpaceGauge);
        }
    }
}
