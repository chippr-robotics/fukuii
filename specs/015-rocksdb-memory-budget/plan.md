# Implementation Plan: RocksDB Memory Budget (spec 015)

**Spec**: [spec.md](./spec.md) | **Scope**: `db/dataSource/`, `utils/InstanceConfig.scala`, `conf/base/db.conf`

## Design

1. `RocksDbConfig` gains defaulted members: `memoryBudget: Option[Long]`, `writeBufferAllowStall`,
   `partitionIndexAndFilters`, `metadataBlockSize`. They are `def`s, so a default that reads other members sees the
   subclass's initialised values, and the ~25 anonymous test configs compile unchanged.
2. `RocksDbMemoryBudget.resolve(config)` is a pure function implementing the formula in spec R1, and it is unit-tested.
3. `RocksDbDataSource.memoryResources` builds `LRUCache(capacity, -1, strict = false, highPri = 0.5)`, a
   `WriteBufferManager(memTableBudget, cache, allowStall)` and the `BlockBasedTableConfig` (cached index/filter at high
   priority, L0 pinned, `optimizeFiltersForMemory`, and when enabled a two-level index, partitioned filters and pinned
   top-level blocks). `DBOptions.setWriteBufferManager` replaces `setDbWriteBufferSize`.
4. The native handles are kept in `MemoryResources` and closed after `db.close()` in `close()`, recreated in `clear()`,
   and closed if `RocksDB.open` fails. `createDB` returns an `OpenedDb` case class instead of a 6-tuple.
5. `RocksDbDataSource.memoryStats` reads the cache handle (`getUsage`, `getPinnedUsage`) and
   `getAggregatedLongProperty` for memtables and table readers, under the DB read lock.
6. `RocksDbCacheMetrics` adds `MemorySampler`, a 30 s memo with no scheduler thread that never throws, plus the six
   memory gauges and `statistics_enabled`. They are registered from the existing `StdNode` call site.
7. `db.conf` documents the formula, the 16 GiB guidance and each new key. `memory-budget` stays commented out, so
   existing overrides keep their meaning.

## Risks

- **Write stall.** When memtables reach the budget, writes wait for a flush, and a flush took 30 s on the soak SSD.
  `doWrite` holds the DataSource write lock, so readers wait too. Flushes start at 7/8 of the budget, so a stall means
  flushing has fallen behind, which is when the cap matters. It can be turned off with
  `write-buffer-allow-stall = false`.
- **Blocks see less cache while memtables are full.** Capacity is the sum of the two old caps, so blocks never get
  less than the old 512 MiB.
- **Format of new SSTs.** A partitioned index and filters are standard BlockBased table options, and older readers of
  the same rocksdbjni line read them. Rolling back the binary keeps the data readable (tested by reopening with the
  option off).

## Verification

No local JVM builds (the live node shares the host). CI runs `scalafmtCheckAll`, `compile-all` and `testEssential`,
which includes `DatabaseTest`. After deploy: the startup log line `RocksDB memory budget at ...`, the
`app_db_rocksdb_*_bytes` series, and RSS through a SNAP storage phase.
