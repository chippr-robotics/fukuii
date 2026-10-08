# Tasks: RocksDB Memory Budget (spec 015)

**Spec**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md)

- [X] T001 Measure on the live node: RocksDB LOG cache stats (512 MiB LRU, 510 MiB used, filters 23%), pmap breakdown
  (arenas 1.3 GB, thread stacks 12 MB), OPTIONS (21 CFs × 64 MiB × 2), and statistics off in the config.
- [X] T002 `RocksDbConfig`: add the defaulted `memoryBudget`, `writeBufferAllowStall`, `partitionIndexAndFilters` and
  `metadataBlockSize`; wire them in `InstanceConfig.Db.RocksDb` with `hasPath` fallbacks.
- [X] T003 `RocksDbMemoryBudget.resolve`, the budget formula (R1).
- [X] T004 `createDB`: shared `LRUCache`, a `WriteBufferManager` charged to it with the stall, and partitioned
  index/filters; drop `setDbWriteBufferSize`; log the resolved budget.
- [X] T005 Lifecycle: `MemoryResources` closed after the DB in `close()`, recreated in `clear()`, closed when open fails;
  `destroyDB` closes its throwaway cache and filter.
- [X] T006 `memoryStats` and a locked `cacheStats` (R6).
- [X] T007 `RocksDbCacheMetrics`: the 30 s `MemorySampler`, six memory gauges and `statistics_enabled`.
- [X] T008 `db.conf`: the formula, 16 GiB guidance and the new keys.
- [X] T009 `RocksDbMemoryBudgetSpec`: formula, memoryStats lifecycle, partitioned/legacy SST compatibility, sampler.
- [ ] T010 CI green: "Test and Build (JDK 25, Scala 3.3.8)".
- [ ] T011 After the soak pause: deploy, confirm the budget log line and the `_bytes` gauges, and watch RSS through a
  SNAP storage phase.
