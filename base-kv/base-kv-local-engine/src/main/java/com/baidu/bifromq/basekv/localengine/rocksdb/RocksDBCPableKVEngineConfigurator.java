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

import com.baidu.bifromq.basekv.localengine.ICPableKVEngineConfigurator;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import lombok.experimental.SuperBuilder;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.DBOptions;
import org.rocksdb.DBOptionsInterface;
import org.rocksdb.PrepopulateBlobCache;

@Accessors(chain = true, fluent = true)
@Getter
@Setter
@SuperBuilder(toBuilder = true)
public final class RocksDBCPableKVEngineConfigurator
    extends RocksDBKVEngineConfigurator<RocksDBCPableKVEngineConfigurator> implements ICPableKVEngineConfigurator {
    private String dbCheckpointRootDir;

    @Override
    protected void configDBOptions(DBOptionsInterface<DBOptions> targetOption) {
        super.configDBOptions(targetOption);
        targetOption.setRecycleLogFileNum(0);
    }

    @Override
    public ColumnFamilyOptions cfOptions(String name) {
        ColumnFamilyOptions cfOptions = super.cfOptions(name);
        cfOptions.setEnableBlobFiles(true);
        cfOptions.setMinBlobSize(2048); // 2kb
        cfOptions.enableBlobGarbageCollection();
        cfOptions.setEnableBlobFiles(true);
        cfOptions.setPrepopulateBlobCache(PrepopulateBlobCache.PREPOPULATE_BLOB_FLUSH_ONLY);
        return cfOptions;
    }
}
