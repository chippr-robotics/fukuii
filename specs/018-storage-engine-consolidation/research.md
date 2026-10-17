# Research: How fukuii uses its database today (Phase 0 of spec 018)

**Spec**: [spec.md](./spec.md) · **Plan**: [plan.md](./plan.md)

**Read from**: `staging` at v0.9.97 (2026-10-17). Every figure below is a grep or a file read on that tree, or a quote
from a spec/ADR already in the repo. Where a figure is a measurement the repo does not have yet, it is marked
**[MEASURE]** and becomes a Phase 0 task in `plan.md`. Nothing here is a benchmark we ran.

## 1. The shape of the storage layer

| Layer | What | Size |
| --- | --- | --- |
| Engine | `org.rocksdb:rocksdbjni:10.10.1.1`, one instance, 20 column families (`db/storage/Namespaces.scala`) | `RocksDbDataSource.scala` 874 lines |
| Contract | `db/dataSource/DataSource.scala` (trait) + `EphemDataSource` (HashMap, tests) | 48 main files reference `DataSource` |
| Typed stores | `db/storage/*` — 36 classes over `DataSource` (`KeyValueStorage`, `TransactionalKeyValueStorage`) | `db/` total 5,616 lines |
| State | `MptStorage` → `NodesKeyValueStorage` → `NodeStorage` (hash-keyed CF `n`), with `ReferenceCountNodeStorage` (basic pruning) or `ArchiveNodeStorage`; `PathNodeStorage` (Path scheme, CFs `t`/`u`); `FlatAccountStorage` (`a`), `FlatSlotStorage` (`d`); `EvmCodeStorage` (`c`) | |
| Wiring | `Storages.DefaultStorages` builds every store over one `dataSource`; `NodeBuilder` picks the engine from `db.data-source` (only `"rocksdb"` exists) | |
| Tests | 84 test files use `EphemDataSource`; 35 reference `RocksDbDataSource`; 27 specs under `test/.../db` | |

**Column families** (`Namespaces.nsSeq`, 20): receipts `r`, headers `h`, bodies `b`, nodes `n`, code `c`, chain weight
`w`, app state `s`, known nodes `k`, heights `i`, fast-sync state `f`, tx mapping `l`, block-first-seen `m`, flat slots
`d`, flat accounts `a`, healing frontier `g`, BFS queue `q`, SNAP progress `p`, state-trie path `t`, storage-trie path
`u`, block access lists `j`. The comment on `j` records the convention: a new CF is created on next start, nothing is
migrated.

**Engine configuration** (`conf/base/db.conf`, `RocksDbDataSource.createDB`): LZ4 with ZSTD on the bottommost level,
`level_compaction_dynamic_level_bytes`, 10-bit bloom filters, 16 KiB blocks, one shared LRU cache (512 MiB blocks +
512 MiB memtables under a `WriteBufferManager`, spec 015), `max_total_wal_size` 512 MiB, `max_open_files` 512, 4
background threads, paranoid checks and checksum verification on, statistics off by default (1–2% read cost).

## 2. Finding F1 — fukuii is already a single-writer store, by its own lock

`RocksDbDataSource` wraps every call in a process-wide `ReentrantReadWriteLock` (`dbLock`). `doWrite` takes the
**write lock for the whole `db.write`**; every `get`, `multiGet`, `scanRange`, iterator refill and metrics read takes
the read lock. Spec 015 R2 states the consequence: a write stall inside RocksDB (memtable flush, L0 trigger, pending
compaction bytes) is held under that lock, so "block import, SNAP serving, the Engine API and RPC all wait, possibly for
tens of seconds". Spec 015's evidence has one flush at 30 s on the Sepolia SSD (`Flush lasted 30319062 microseconds`).

Two conclusions follow:

- **The latency spikes the user describes are real and recorded, but their immediate cause is the lock, and their
  systemic cause is the LSM's background work.** Removing the lock alone would let readers proceed during a stall; it
  would not remove the stall. Swapping the engine alone, with a lock kept for close-fencing, keeps readers safe only if
  the new engine has no background work to stall on. The spec must separate these two effects (plan Phase 0, R-1).
- **libmdbx's single-writer / multi-reader model is not a new constraint.** fukuii already serializes writers at the
  `DataSource` boundary. The SNAP coordinators' dedicated dispatchers (`storage-writer-dispatcher`, fixed-pool-size 1,
  "because RocksDB compaction is single-threaded anyway and we want to preserve write ordering";
  `healing-writer-dispatcher`; `account-trie-dispatcher`) exist to batch around that serialization. Those dispatchers
  are RocksDB-shaped architecture that the new engine inherits and may simplify, but they are not blockers.

## 3. Finding F2 — the application writes each state node several times

Above the LSM's own write amplification (RocksDB's compaction rewrites; order of magnitude 4–10× for random keys), the
application layer multiplies state writes:

**Basic pruning (default on every chain; `pruning.conf` `mode = "basic"`, `history = 64`).** `ReferenceCountNodeStorage`
stores every node as `STORED_NODE(value, references, lastUsedByBlock)`, so **any reference-count change rewrites the
full node value**. For each block it also writes a **snapshot** (undo record) per changed node and a **death row** per
block; `update` appends to `currentDeathRow` and rewrites the whole row on every call within the block
(`currentDeathRow ++ key`, then `deathRowKey -> currentDeathRow.toArray`), which is quadratic in bytes across a block's
`update` calls. The abandon/readopt records (`abandonRecordWrites`) add a third family of per-block writes. This is the
**data-model** amplification: it is independent of the engine and survives an engine swap unchanged.

**Path scheme (ETH, Sepolia, Platåberget: `storage-scheme = "path"`).** During SNAP a state node is written **three
times**: path-keyed (`t`/`u`), its leaf data as flat slots (`d`) and flat accounts (`a`), and then copied by
`PathToHashExporter.publish` into the hash-keyed CF `n` that execution reads. After publish, **nothing updates `t`, `u`,
`a` or `d`**: no file under `ledger/` or `domain/` references `PathNodeStorage`, and the only callers of
`putAccountsBatch` / `putSlotsBatch` are `StorageRangeCoordinator` and `StorageDoneMarkers` (SNAP). So on ETH the Path
and flat CFs are **frozen at the pivot** once SNAP completes. They serve SNAP peers from a stale view and otherwise cost
disk and compaction.

**Hash scheme (ETC/Mordor).** SNAP writes `n` directly (8 MiB batches); there is no copy. ETC has no flat state at all.

## 4. Finding F3 — execution reads the trie, never the flat state

`InMemoryWorldStateProxy.getAccount` is `accountsStateTrie.get(address)`: a `MerklePatriciaTrie` walk of hash-keyed
nodes, one `MptStorage.get(hash)` per level (about 7–9 levels on ETH mainnet accounts; storage tries add their own).
`getStorage` walks the storage trie unless a `flatSlotStorage` is passed, and `BlockExecution.buildInitialWorld` does
**not** pass one (spec 017 DC-8 relies on this). `FlatAccountStorage.getAccount` has **no caller in execution** (the
`getAccount` hits in `jsonrpc/` are `BlockchainReader.getAccount`, which is a trie read).

The mitigations in place are all caches over the trie walk: `DecodedNodeCache` (`decoded-node-cache-bytes`),
`CachingMptStorage.cachedForExecution`, `TrieReadMemo` (per world, per root), the code and code-size caches in
`EvmCodeStorage`, the BAL-driven `PrefetchNodeReader` (#79), and `multiGetNodes` (one JNI `multiGetAsList`). They
reduce the cost of read amplification; they do not remove it. **A flat read path for execution does not exist today and
is the substantive change this spec makes to the data model.**

## 5. Finding F4 — where the engine leaks past the contract

The `DataSource` trait is mostly engine-neutral, but not entirely:

| Leak | Where | Why it matters for a swap |
| --- | --- | --- |
| `import RocksDbDataSource.IterationError` in the trait's own signature (`iterate(): Stream[IO, Either[IterationError, …]]`) | `DataSource.scala` | the error type must move to the trait |
| `compactRange` on the trait (LSM concept; added so callers can reclaim `deleteRange` space) | `DataSource.scala`, callers in SNAP | becomes a no-op or is removed |
| `case rdb: RocksDbDataSource =>` pattern matches for `seekFrom`, `approximateKeyCount`, `iterateSyncRange` | `FlatAccountStorage.scala:62,73`, `FlatSlotStorage.scala:65`, `BfsQueueStorage.scala:105` | three typed stores depend on the concrete class; `EphemDataSource` takes a different branch, so tests already diverge from production here |
| `RocksDbCacheMetrics.register(storagesInstance.dataSource)` | `nodebuilder/StdNode.scala:133` | metrics are engine-specific; need an engine-neutral `StorageMetrics` seam |
| `RocksDbConfig` built in `utils/InstanceConfig.scala`; `db.data-source` string match in `NodeBuilder.scala:149` | config | the only engine selector; `"rocksdb"` is its only value |
| `import org.rocksdb` and `deleteRocksDBFiles()` | `Fukuii.scala` | datadir lifecycle (`--delete-db`) is engine-specific |
| Class names `BlockFirstSeenRocksDbStorage`, `RocksDbBfsQueueStorage` | `db/storage/` | naming only |
| Comments naming RocksDB in 25 files outside `db/` (SNAP coordinators, `BlockExecution`, `BlockchainWriter`, `StackTrie`) | | documentation debt, not code coupling |

The checkpoint archive (`CheckpointArchive`, format v1; spec 013 §2) is engine-neutral (a tagged node/bytecode stream)
but its importer uses `storeRawNodes`, the Hash-scheme writer. The operator skills (`fukuii-backup-restore`,
`fukuii-disk-management`) and `multi-instance.conf` name the `rocksdb/` directory.

## 6. Finding F5 — durability and the two capabilities

`update` writes with `WriteOptions.setSync(false)` (WAL appended, not fsynced); `updateSync` fsyncs and is used for the
SNAP finalization flags only. INF-006 §4 records the property spec 017 DC-1 depends on: a recovered `snapSyncDone`
implies every earlier write reached the WAL, so the publish is durable before execution reads it. INF-006 also records
the exception: under `pruning.mode = "inmemory"` the property does **not** hold, because `CachedReferenceCountedStorage`
keeps nodes in its LRU and its `persist()` is a no-op.

For the engine swap this means: the **durability contract to preserve is "commit-ordered, with one synced barrier at
finalization"**, not "WAL semantics". A copy-on-write B+tree with a durable commit per transaction gives the same
property with no WAL at all. The ingest side's relaxed policy (spec 016 T056, `StateIngest.Durability`) maps to
`MDBX_SAFE_NOSYNC` / `MDBX_UTTERLY_NOSYNC` on the ingest transactions only, and DC-1's barrier maps to a synced commit
before `completeSnapSync`.

## 7. Finding F6 — operational pressures already recorded in the repo

- Spec 013 §1: "the 2026-10-05 Sepolia sync and the Platåberget soak both showed I/O stalls and OOM kills on this host
  class" (4 cores / 16 GiB).
- Spec 015: RSS 7.6–9.2 GB with `-Xmx6g`; RocksDB native about 1 GiB plus glibc arena fragmentation; filters alone 119 MB
  on a 20 GB DB with 422 SST files; an ETH-mainnet state DB has 1,500+ SST files.
- `DataSource.deleteRange` scaladoc: per-key deletes of about 140M keys "ground for ~30 minutes at full CPU and drove the
  container to the edge of its memory cgroup".
- `db.conf` `max-open-files`: a healing walk over more SST files than open handles causes index/filter re-reads
  ("table-handle churn").
- `docs/runbooks/known-issues.md` §"Compaction backlog" / "Write amplification": operator guidance that already names
  the problem.

These are the baseline numbers Phase 0 must reproduce and the new engine must beat (spec SC-002..SC-005).

## 8. What libmdbx gives and what it costs (desk evaluation; every row is a Phase 0 measurement)

| Property | RocksDB today | libmdbx | Consequence for fukuii |
| --- | --- | --- | --- |
| Write path | memtable → WAL → L0 → compaction | copy-on-write B+tree, one writer txn, no WAL, no background threads | commit cost is dirty pages + meta page; no stall source outside the commit itself (F1) |
| Readers vs writer | share `dbLock`; stalls block reads | MVCC snapshots; readers never block the writer, writer never blocks readers | `dbLock` can shrink to close-fencing; Engine API and RPC reads stay responsive during import |
| Long readers | iterator holds a snapshot, prevents SST deletion (storage-rocksdb.md) | a long read txn pins old pages; **DB file grows until it ends** | fukuii's `iterate()` `Stream`s, the heal walk and `scanRange` windows must be bounded read txns (DC-5). Same failure class, same discipline, harsher penalty |
| Random-key inserts | compaction rewrites | page splits on keccak-hashed keys, fill factor ~50–70%, COW along the path | bulk ingest must be **sorted and batched** (Erigon's collate-then-load); SNAP ranges arrive sorted already |
| Compression | LZ4 / ZSTD | none | on-disk state grows; **[MEASURE]** Sepolia and Mordor footprints; mitigations: shorter keys in flat CFs, no stored reference counts |
| Memory | shared LRU + memtables, native malloc, cgroup-visible as anon | mmap'd file pages in the OS page cache, cgroup-visible as file memory | JVM `-Xmx` plus page cache must fit 16 GiB; reclaimable but counted; **[MEASURE]** RSS and cgroup `memory.current` under import |
| Durability knobs | `sync` per write; WAL | per-commit `MDBX_SYNC_DURABLE` / `SAFE_NOSYNC` / `UTTERLY_NOSYNC` | maps onto the two capabilities' policies (F5) |
| Value / key limits | none practical | key ≤ ~1 KiB (`mdbx_env_get_maxkeysize`), values ≤ 4 GiB | fine: keys are 32–64 bytes, nodes ≤ 532 bytes, code ≤ 24 KiB |
| JVM binding | `rocksdbjni` from Maven Central, actively released | **no stable Maven artifact found.** `mdbxjni` (Castor Technologies, the binding libmdbx's README names) publishes `99-master-SNAPSHOT` from an old FuseSource repo; the last confirmed tag bundles mdbx 0.11.2 while upstream is 0.13.x and ships amalgamated source only since 2025-12. `mdbx4s` is Scala Native, unreleased | **the load-bearing risk.** Recommended answer: bind libmdbx's amalgamated C with **JDK 25's Foreign Function & Memory API** (`java.lang.foreign`, final since JDK 22; `jextract` generates the bindings) and vendor the amalgamation. No JNI, no third-party binding to keep alive. Needs a constitution note (new native dependency, build step) |
| Platforms | Linux/macOS/Windows jars | Linux and macOS (mmap); Windows supported upstream | fukuii's supported targets are covered |
| Precedent | Besu (Bonsai on RocksDB), geth (Pebble) | Erigon (cgo), reth (`reth-libmdbx`), Akula | two production ELs on the same engine with flat state |

**Alternatives that must be weighed in Phase 0, not assumed away:**

1. **Flat storage on RocksDB** (Besu Bonsai's choice). Removes the trie-walk read amplification (F3) and the
   reference-count write amplification (F2) without a new engine or a native build. Keeps the LSM stalls (F1). This is
   the **control arm** for measuring how much of the problem is the data model and how much is the engine.
2. **Remove the process-wide lock on RocksDB.** RocksDB is internally thread-safe; `dbLock` exists to fence `close()`.
   A cheaper close-fence (an `AtomicBoolean` plus a reader count) would let reads proceed during a write stall. Cheap,
   measurable, and it isolates F1's immediate cause. It is a candidate for a standalone fix PR regardless of this spec.
3. **LMDB via `lmdbjava`** (stable Maven artifact, same mmap/COW model). Lacks libmdbx's automatic DB-size management,
   dirty-page spilling for large txns and `MDBX_SAFE_NOSYNC` semantics; the same long-reader growth problem. Only if the
   FFM route to libmdbx fails.

## 9. Interaction with spec 017 (witnesses) — a design conflict to resolve here, not there

Spec 017 records witnesses with `RecordingMptStorage`, an `MptStorage` decorator at `buildInitialWorld` (DC-3), and
makes a capturing world with flat slot storage **unconstructible** (DC-8), because a SLOAD that skips the trie records
no proof. A flat execution read path (F3's change) therefore **cannot** be installed under 017's recorder as specified.

The resolution the other ELs use, and the one this spec adopts (spec DC-7): record the **access set** (hashed account
keys, hashed slot keys, code hashes, ancestor headers) during flat execution, then **generate the proof nodes from the
trie for that access set** at witness finalization. reth's `debug_executionWitness` is built this way (hashed
post-state → trie witness); geth's `stateless` package likewise. The witness on the wire is unchanged (017 DC-11), the
hash-addressed nodes are produced by a trie walk over the recorded keys, and the 017 differential test becomes the
oracle that the two recorders agree (spec SC-007). 017's DC-3 rejected *node* recording at the storage-key level for
Path scheme; key-set recording plus a proof walk is a different mechanism and does not fall under that rejection.

Sequencing consequence: 017 ships the node-level recorder first; 018 adds the key-level recorder and must show
byte-identical witnesses before the flat read path is enabled by default. 017 DC-8 is amended by this spec (plan Phase 2).

## 10. Interaction with spec 019 (zkVM guest)

The guest executes a block from a witness over an in-memory `WitnessMptStorage` (017 FR-005). It never opens a
database. This spec's only obligation to it is negative: the stateless closure (017 DC-15) gains **no** dependency on the
engine, the flat stores, the FFM bindings or the key-level recorder's host-side plumbing (spec DC-8). The engine swap is
host-only, which is the "clean ZK sandboxing" property the user asked for.

## 11. Summary of the evaluation

- The pain is real and recorded in the repo: write stalls under a process-wide lock (F1), three-fold state writes
  during SNAP on ETH plus reference-count rewrites on every chain (F2), trie-walk reads for every account and slot
  (F3), and OOM pressure on the 16 GiB target (F6).
- The problem has two layers, **engine** and **data model**, and today's code couples them only loosely (F4). The
  engine swap is a `DataSource` implementation plus the removal of a handful of leaks. The data model change (flat reads
  for execution, changesets instead of reference counts, one trie as a commitment structure) is the larger and
  consensus-adjacent part, and it is where the read and write amplification actually goes away.
- libmdbx fits fukuii's existing single-writer shape, and its two sharp edges (long readers, no compression) are
  measurable and manageable. The real risk is the **JVM binding**, and the answer is JDK 25 FFM over the amalgamated
  source rather than a third-party JNI.
- The spec must gate every step on measurement against today's baseline, keep state roots byte-identical on both chains,
  and resolve the 017 DC-8 conflict with a key-set recorder before flat reads are turned on.
