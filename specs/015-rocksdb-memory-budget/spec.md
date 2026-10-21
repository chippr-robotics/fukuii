# Feature Specification: One Bounded RocksDB Memory Budget, With Memory Gauges

**Feature Branch**: `fix/rocksdb-memory-budget`

**Created**: 2026-10-08

**Status**: Implemented (PR against staging)

## Problem

RocksDB's native memory had two separate caps and no gauges. The block cache (`block-cache-size`, 512 MiB) and the
memtables (`db-write-buffer-size`, 512 MiB) were sized on their own. `db_write_buffer_size` only triggers flushes, so
memtables waiting on a slow flush could exceed it. Index and filter blocks for every SST file compete for the same
512 MiB, and the L0 ones are pinned. `/metrics` had no memory series, so native usage could only be attributed with
`pmap` arithmetic. ETH mainnet must sync on a 16 GiB host, where an unbounded component means an OOM kill.

## Evidence

Sepolia, v0.9.6, `-Xmx6g -XX:MaxDirectMemorySize=512M`, SNAP storage phase, 2026-10-08:

- RSS 7.6 to 9.2 GB. Java heap about 5.1 to 5.6 GB. Native about 2.3 to 2.4 GB: about 30 glibc-arena-sized mappings
  holding 1.3 GB, plus about 1 GB of other JVM native memory (metaspace 118 MB, code cache, GC structures, direct
  buffers). Thread stacks are not the cause: 90 threads, 78 stack mappings, 12 MB resident.
- RocksDB LOG: `Block cache LRUCache@... capacity: 512.00 MB usage: 510.34 MB`. The cache is full. Entries:
  DataBlock 373 MB, FilterBlock 119 MB (23%), IndexBlock 17 MB. `ClockCache` in rocksdbjni 10.10 is a deprecated
  shim that returns an `LRUCache`.
- A 20 GB DB with 422 SST files already uses 119 MB of filters. SST `table_properties` put `filter_size` at about 2.7%
  of file size. An ETH-mainnet state DB with 1,500+ SST files has far more filter than any cache that fits the host.
- `app_db_rocksdb_block_cache_hit/miss` read 0.0 because `enable-statistics = false` (default, and not set on the live
  node). This is not a bug: `RocksDbDataSourceSpec` shows the tickers advance once statistics are on.
- RocksDB LOG shows a single flush taking 30 s (`Flush lasted 30319062 microseconds`) on this SSD.

## Requirements

- **R1. One budget.** A single `LRUCache`, shared by all column families, holds data, index and filter blocks. A
  `WriteBufferManager` charges memtable memory to the same cache. Formula:
  - cache capacity = `memory-budget` if set, otherwise `block-cache-size + db-write-buffer-size` (default 1 GiB).
  - memtable budget = `db-write-buffer-size`, capped at capacity / 2 when `memory-budget` is set.
  - blocks keep at least capacity minus the memtable budget.
  Both parts are floored at 1 MiB.
- **R2. Circuit breaker (opt-in).** By default the WriteBufferManager flushes when memtables reach their budget, and
  memtables can overshoot briefly while a flush catches up. `write-buffer-allow-stall = true` makes it a hard cap by
  having writers wait for the flush. **A stall freezes all reads:** `doWrite` holds the process-wide `dbLock`
  exclusively around `db.write`, so block import, SNAP serving, the Engine API and RPC all wait, possibly for tens of
  seconds. So it is off by default and meant only for memory-constrained hosts. The cache is not strict-capacity:
  with a strict limit a full cache fails the insert and RocksDB returns `MemoryLimit` to the read or memtable
  reservation that needed it.
- **R3. Per-CF memtables.** About 21 column families × `write_buffer_size` 64 MiB × `max_write_buffer_number` 2 is
  about 2.7 GiB in theory. The WriteBufferManager caps the sum, so per-CF options stay unchanged.
- **R4. Partitioned index and filters (opt-in for this release).** `partition-index-and-filters = true` applies to
  newly written SSTs: `kTwoLevelIndexSearch`, partitioned filters, `optimize_filters_for_memory`, and
  `metadata-block-size` 4 KiB partitions. Each file's top-level index/filter block is pinned; it grows with file size
  and file count, and L0 blocks stay pinned as before. Existing SSTs stay readable and are rewritten by normal
  compaction. Turning it off again is also readable. No resync. With the flag off (the default), the table format is
  exactly what nodes write today.
- **R5. Gauges.** Read per DB, at most once per 30 s, and served from that snapshot in between:
  `app_db_rocksdb_block_cache_usage_bytes` and `_pinned_usage_bytes` (from the shared `Cache` handle; the per-CF
  property would count the shared cache once per CF), `_capacity_bytes`,
  `app_db_rocksdb_memtables_size_bytes` (`rocksdb.cur-size-all-mem-tables`, summed over CFs), `_budget_bytes`,
  `app_db_rocksdb_table_readers_mem_bytes` (`rocksdb.estimate-table-readers-mem`, summed over CFs), and
  `app_db_rocksdb_statistics_enabled` (0/1, so a 0.0 hit rate can be told apart from "off").
  Block-cache usage includes the memtable reservation; do not add it to the memtable size.
- **R6. Native safety.** Every gauge read and `cacheStats` tries the DB read lock for at most 100 ms and returns
  nothing if it times out or the DB is closed, so the sampler keeps its last value and scrapes never queue behind a
  long write. `close()` releases the options, statistics, cache, WriteBufferManager and filter in a `finally`, even
  if `db.close()` throws. A failed open closes every native object created so far. `destroyDB` no longer leaks a
  full-size cache.
- **R7. Compatibility.** Existing `block-cache-size` / `db-write-buffer-size` overrides keep their meaning: blocks keep
  at least `block-cache-size`, as before. All new `RocksDbConfig` members have defaults. No column family, key or
  value format changes.

## Expected effect on the live Sepolia node (defaults, no config change)

RocksDB cache + memtables: about 1 GiB, the same envelope as before (512 + 512 MiB). It is now one accounted budget
that the gauges can measure. It is a hard limit only with the opt-in stall; with both opt-ins off, the on-disk table
format is unchanged. Total RocksDB native is about 1.0 to 1.1 GiB plus allocator overhead. The rest of the 2.3 GiB native is JVM
memory (about 1 GiB) and glibc arena fragmentation, which are outside `db/`. `MALLOC_ARENA_MAX` is handled in the
service unit, not here. Expected RSS ceiling: 6 GiB heap + about 1 GiB JVM native + at most 0.5 GiB direct + about
1.1 GiB RocksDB = about 8.6 GiB, provided arena fragmentation is capped.

## Acceptance

- `RocksDbMemoryBudgetSpec`:
  - the shipped config keeps both opt-ins off and resolves to 1 GiB / 512 MiB, and the overrides parse;
  - the budget formula (default, explicit, tight, zero);
  - `memoryStats` reports capacity and budget, sees memtable growth, and is `None` after close;
  - SSTs written with and without partitioning (explicitly flushed, with `.sst` presence checked after each phase)
    are readable in both directions;
  - the sampler reads at most once per interval and keeps the last value on `None` or an exception.
- Existing `RocksDbDataSourceSpec` (statistics tickers, iterator lifecycle) stays green.
- On the node: the startup log prints the resolved budget, and `/metrics` shows the new series.

## Out of scope

JVM native memory (GC structures, metaspace, code cache), glibc arena tuning, `-Xmx` sizing, HyperClockCache
(its dummy-entry charging with a WriteBufferManager is less proven; LRU is what runs today), strict-capacity mode,
and per-CF write-buffer tuning.
