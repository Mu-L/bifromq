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

import static com.baidu.bifromq.basekv.localengine.IKVEngine.DEFAULT_NS;
import static com.baidu.bifromq.basekv.localengine.rocksdb.Keys.META_SECTION_END;
import static com.baidu.bifromq.basekv.localengine.rocksdb.Keys.META_SECTION_START;
import static com.baidu.bifromq.basekv.localengine.rocksdb.Keys.fromMetaKey;
import static com.google.protobuf.UnsafeByteOperations.unsafeWrap;
import static java.util.Collections.singletonList;

import com.baidu.bifromq.baseenv.EnvProvider;
import com.baidu.bifromq.basekv.localengine.IKVSpace;
import com.baidu.bifromq.basekv.localengine.IKVSpaceWriter;
import com.baidu.bifromq.basekv.localengine.ISyncContext;
import com.baidu.bifromq.basekv.localengine.KVEngineException;
import com.baidu.bifromq.basekv.localengine.KVSpaceDescriptor;
import com.baidu.bifromq.basekv.localengine.SyncContext;
import com.baidu.bifromq.basekv.localengine.metrics.KVSpaceMeters;
import com.baidu.bifromq.basekv.localengine.metrics.KVSpaceMetric;
import com.google.common.collect.Maps;
import com.google.protobuf.ByteString;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.subjects.BehaviorSubject;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import lombok.SneakyThrows;
import org.rocksdb.BlockBasedTableConfig;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.CompactRangeOptions;
import org.rocksdb.DBOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.WriteOptions;

abstract class RocksDBKVSpace<
    E extends RocksDBKVEngine<E, T, C>,
    T extends RocksDBKVSpace<E, T, C>,
    C extends RocksDBKVEngineConfigurator<C>
    >
    extends RocksDBKVSpaceReader implements IKVSpace {

    protected enum State {
        Init, Opening, Destroying, Closing, Terminated
    }

    private final AtomicReference<State> state = new AtomicReference<>(State.Init);
    private final File keySpaceDBDir;
    private final DBOptions dbOptions;
    private final C configurator;
    private final ColumnFamilyDescriptor cfDesc;
    private final IWriteStatsRecorder writeStats;
    private final ExecutorService compactionExecutor;
    private final E engine;
    private final Runnable onDestroy;
    private final AtomicBoolean compacting = new AtomicBoolean(false);
    private final BehaviorSubject<Map<ByteString, ByteString>> metadataSubject = BehaviorSubject.create();
    private final ISyncContext syncContext = new SyncContext();
    private final ISyncContext.IRefresher metadataRefresher = syncContext.refresher();
    private final MetricManager metricMgr;
    protected final RocksDB db;
    protected final ColumnFamilyHandle cfHandle;
    private volatile long lastCompactAt;
    private volatile long nextCompactAt;

    @SneakyThrows
    public RocksDBKVSpace(String id,
                          C configurator,
                          E engine,
                          Runnable onDestroy,
                          String... tags) {
        super(id, tags);
        this.onDestroy = onDestroy;
        this.configurator = configurator;
        this.writeStats = configurator.heuristicCompaction() ? new RocksDBKVSpaceCompactionTrigger(id,
            configurator.compactMinTombstoneKeys(),
            configurator.compactMinTombstoneRanges(),
            configurator.compactTombstoneKeysRatio(),
            this::scheduleCompact) : NoopWriteStatsRecorder.INSTANCE;
        this.engine = engine;
        compactionExecutor = ExecutorServiceMetrics.monitor(Metrics.globalRegistry, new ThreadPoolExecutor(1, 1,
                0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(),
                EnvProvider.INSTANCE.newThreadFactory("kvspace-compactor-" + id)),
            "compactor", "kvspace", Tags.of(metricTags));
        dbOptions = configurator.dbOptions();
        keySpaceDBDir = new File(configurator.dbRootDir(), id);
        try {
            Files.createDirectories(keySpaceDBDir.getAbsoluteFile().toPath());
            cfDesc = new ColumnFamilyDescriptor(DEFAULT_NS.getBytes(), configurator.cfOptions(DEFAULT_NS));
            List<ColumnFamilyDescriptor> cfDescs = singletonList(cfDesc);
            List<ColumnFamilyHandle> cfHandles = new ArrayList<>();
            db = RocksDB.open(dbOptions, keySpaceDBDir.getAbsolutePath(), cfDescs, cfHandles);
            assert cfHandles.size() == 1;
            cfHandle = cfHandles.get(0);
        } catch (Throwable e) {
            throw new KVEngineException("Failed to initialize RocksDBKVSpace", e);
        }
        metricMgr = new MetricManager(tags);
    }

    public T open() {
        if (state.compareAndSet(State.Init, State.Opening)) {
            doLoad();
        }
        return (T) this;
    }

    public Observable<Map<ByteString, ByteString>> metadata() {
        return metadataSubject;
    }

    protected abstract WriteOptions writeOptions();

    protected Optional<ByteString> doMetadata(ByteString metaKey) {
        return metadataRefresher.call(() -> {
            Map<ByteString, ByteString> metaMap = metadataSubject.getValue();
            if (metaMap != null) {
                return Optional.ofNullable(metaMap.get(metaKey));
            }
            return Optional.empty();
        });
    }

    @Override
    public KVSpaceDescriptor describe() {
        return new KVSpaceDescriptor(id, collectStats());
    }

    private Map<String, Double> collectStats() {
        Map<String, Double> stats = new HashMap<>();
        stats.put("size", (double) size());
        // TODO: more stats
        return stats;
    }

    protected void doLoad() {
        metadataRefresher.runIfNeeded(() -> {
            try (RocksDBKVEngineIterator metaItr =
                     new RocksDBKVEngineIterator(db, cfHandle, null, META_SECTION_START, META_SECTION_END)) {
                Map<ByteString, ByteString> metaMap = new HashMap<>();
                for (metaItr.seekToFirst(); metaItr.isValid(); metaItr.next()) {
                    metaMap.put(fromMetaKey(metaItr.key()), unsafeWrap(metaItr.value()));
                }
                metadataSubject.onNext(Collections.unmodifiableMap(metaMap));
            }
        });
    }

    private void updateMetadata(Map<ByteString, ByteString> metadataUpdates) {
        metadataRefresher.runIfNeeded(() -> {
            if (metadataUpdates.isEmpty()) {
                return;
            }
            Map<ByteString, ByteString> metaMap = Maps.newHashMap(metadataSubject.getValue());
            metaMap.putAll(metadataUpdates);
            metadataSubject.onNext(Collections.unmodifiableMap(metaMap));
        });
    }

    @Override
    public void destroy() {
        if (state.compareAndSet(State.Opening, State.Destroying)) {
            try {
                doDestroy();
            } catch (Throwable e) {
                throw new KVEngineException("Destroy KVRange error", e);
            } finally {
                onDestroy.run();
                state.set(State.Terminated);
            }
        }
    }

    @Override
    public IKVSpaceWriter toWriter() {
        return new RocksDBKVSpaceWriter<>(id, db, cfHandle, engine, writeOptions(), syncContext,
            writeStats.newRecorder(), this::updateMetadata, metricTags);
    }

    void close() {
        if (state.compareAndSet(State.Opening, State.Closing)) {
            try {
                doClose();
            } finally {
                state.set(State.Terminated);
            }
        }
    }

    protected State state() {
        return state.get();
    }

    protected void doClose() {
        log.debug("Close key range[{}]", id);
        metricMgr.close();
        synchronized (compacting) {
            db.destroyColumnFamilyHandle(cfHandle);
        }
        cfDesc.getOptions().close();
        try {
            db.syncWal();
        } catch (RocksDBException e) {
            log.error("SyncWAL RocksDBKVSpace[{}] error", id, e);
        }
        db.close();
        dbOptions.close();
        metadataSubject.onComplete();
    }

    protected void doDestroy() {
        doClose();
        try {
            deleteDir(keySpaceDBDir.toPath());
        } catch (IOException e) {
            log.error("Failed to delete key range dir: {}", keySpaceDBDir, e);
        }
    }

    @Override
    protected RocksDB db() {
        return db;
    }

    @Override
    protected ColumnFamilyHandle cfHandle() {
        return cfHandle;
    }

    @Override
    protected ISyncContext.IRefresher newRefresher() {
        return syncContext.refresher();
    }

    protected static void deleteDir(Path path) throws IOException {
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void scheduleCompact() {
        if (state.get() != State.Opening) {
            return;
        }
        metricMgr.compactionSchedCounter.increment();
        if (compacting.compareAndSet(false, true)) {
            compactionExecutor.execute(metricMgr.compactionTimer.wrap(() -> {
                log.debug("KeyRange[{}] compaction start", id);
                lastCompactAt = System.nanoTime();
                writeStats.reset();
                try (CompactRangeOptions options = new CompactRangeOptions()
                    .setBottommostLevelCompaction(CompactRangeOptions.BottommostLevelCompaction.kSkip)
                    .setExclusiveManualCompaction(false)) {
                    synchronized (compacting) {
                        if (state.get() == State.Opening) {
                            db.compactRange(cfHandle, null, null, options);
                        }
                    }
                    log.debug("KeyRange[{}] compacted", id);
                } catch (Throwable e) {
                    log.error("KeyRange[{}] compaction error", id, e);
                } finally {
                    compacting.set(false);
                    if (nextCompactAt > lastCompactAt) {
                        scheduleCompact();
                    }
                }
            }));
        } else {
            nextCompactAt = System.nanoTime();
        }
    }

    private class MetricManager {
        private final Gauge blockCacheSizeGauge;
        private final Gauge tableReaderSizeGauge;
        private final Gauge memtableSizeGauges;
        private final Gauge pinedMemorySizeGauges;
        private final Counter compactionSchedCounter;
        private final Timer compactionTimer;

        MetricManager(String... tags) {
            Tags metricTags = Tags.of(tags);
            compactionSchedCounter = KVSpaceMeters.getCounter(id, KVSpaceMetric.CompactionCounter, metricTags);
            compactionTimer = KVSpaceMeters.getTimer(id, KVSpaceMetric.CompactionTimer, metricTags);

            blockCacheSizeGauge = KVSpaceMeters.getGauge(id, KVSpaceMetric.BlockCache, () -> {
                try {
                    if (!((BlockBasedTableConfig) cfDesc.getOptions().tableFormatConfig()).noBlockCache()) {
                        return db.getLongProperty(cfHandle, "rocksdb.block-cache-usage");
                    }
                    return 0;
                } catch (RocksDBException e) {
                    log.warn("Unable to get long property {}", "rocksdb.block-cache-usage", e);
                    return 0;
                }
            }, metricTags);

            tableReaderSizeGauge = KVSpaceMeters.getGauge(id, KVSpaceMetric.TableReader, () -> {
                try {
                    return db.getLongProperty(cfHandle, "rocksdb.estimate-table-readers-mem");
                } catch (RocksDBException e) {
                    log.warn("Unable to get long property {}", "rocksdb.estimate-table-readers-mem", e);
                    return 0;
                }
            }, metricTags);

            memtableSizeGauges = KVSpaceMeters.getGauge(id, KVSpaceMetric.MemTable, () -> {
                try {
                    return db.getLongProperty(cfHandle, "rocksdb.cur-size-all-mem-tables");
                } catch (RocksDBException e) {
                    log.warn("Unable to get long property {}", "rocksdb.cur-size-all-mem-tables", e);
                    return 0;
                }
            }, metricTags);

            pinedMemorySizeGauges = KVSpaceMeters.getGauge(id, KVSpaceMetric.PinnedMem, () -> {
                try {
                    if (!((BlockBasedTableConfig) cfDesc.getOptions().tableFormatConfig()).noBlockCache()) {
                        return db.getLongProperty(cfHandle, "rocksdb.block-cache-pinned-usage");
                    }
                    return 0;
                } catch (RocksDBException e) {
                    log.warn("Unable to get long property {}", "rocksdb.block-cache-pinned-usage", e);
                    return 0;
                }
            }, metricTags);
        }

        void close() {
            blockCacheSizeGauge.close();
            memtableSizeGauges.close();
            tableReaderSizeGauge.close();
            pinedMemorySizeGauges.close();
            compactionSchedCounter.close();
            compactionTimer.close();
        }
    }
}
