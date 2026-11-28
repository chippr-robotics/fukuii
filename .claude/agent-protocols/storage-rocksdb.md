# Storage & RocksDB Protocol

Patterns, constraints, and safety rules for the fukuii storage layer (`db/`).
Applied when writing new storage code, diagnosing corruption, changing RocksDB
configuration, or touching the DataSource abstraction.

Used by: VAULT (primary), FORGE (for consensus state reads/writes), all agents touching `db/`
Referenced by: vault.md, forge.md, `fukuii/CLAUDE.md`
Reference repo: `repo-references/rocksdb` (`java/src/main/java/org/rocksdb/`)

---

## The DataSource contract (do not bypass)

All storage access goes through `DataSource` — never call `RocksDB` directly from
outside `db/`. This abstraction allows `EphemDataSource` (in-memory) in tests and
`RocksDbDataSource` in production without changing callers.

```scala
// ✅ Correct — through DataSource
dataSource.getOptimized(namespace, key)
dataSource.update(Seq(DataUpdate.Put(namespace, key, value)))

// ❌ Never — bypasses abstraction and test/prod swap
db.get(handle, key)   // direct RocksDB call outside db/
```

---

## Column families = namespaces

Each logical data collection (blocks, headers, receipts, state trie nodes, …) is stored
in its own column family, identified by the `Namespace` type (a byte prefix).

**Rule:** Never reuse a namespace for two distinct data types. Namespaces are immutable
once data exists — changing a namespace ID corrupts the database silently (reads return
empty; the old data is still there under the old key).

**Column family operations require a DB restart** — you cannot add or remove a column
family from a live `RocksDB` instance without closing and reopening. Plan namespace
additions in migration steps.

---

## Read path

### Point reads (hot data — block cache active)

```scala
// DataSource.getOptimized — uses default ReadOptions with fillCache=true
val maybeValue = dataSource.getOptimized(namespace, key)

// Multi-get (batched round-trip, efficient for ≤16 sibling keys)
val values = dataSource.multiGetOptimized(namespace, Seq(key1, key2, key3))
```

- Block cache is populated on every point read (default `ReadOptions.fillCache=true`)
- Use for random access of hot data (headers, account state)

### Range scans (cold / sequential data — cache bypass)

```scala
// DataSource.scanRange — uses scanReadOptions with fillCache=false
// This prevents scan data from evicting hot block cache entries
dataSource.scanRange(namespace, fromKey, toKeyExclusive) { (key, value) =>
  process(key, value)
}
```

**Rule:** Always use `scanRange` or `seekFrom` for sequential/range access.
Never use `get` in a loop over contiguous keys — it populates the cache with
data that will never be re-read, evicting hot entries.

### Iterator lifecycle

Iterators are native resources — they MUST be closed.

```scala
// ✅ withResources ensures close even on exception
withResources(db.newIterator(handle, scanReadOptions)) { it =>
  it.seekToFirst()
  while (it.isValid) {
    process(it.key(), it.value())
    it.next()
  }
}

// ❌ Never — iterator leak on exception
val it = db.newIterator(handle, scanReadOptions)
it.seekToFirst()
// ... if this throws, iterator is never closed
```

An iterator leak holds a snapshot of the DB open, preventing SST file deletion.
The DB grows unboundedly until the JVM exits.

**Iterator leak sweep:** When fixing any iterator leak, run a project-wide grep
across the entire storage layer before committing:

```bash
grep -rn "db\.newIterator\|newIterator(" \
  src/main/scala/com/chipprbots/ethereum/db/ --include="*.scala" \
  | grep -v "withResources"
```

Any `newIterator` not inside `withResources` is a leak. Fix ALL occurrences in the
same commit — partial fixes leave other code paths growing the DB unboundedly, and
the issue only becomes visible under load when a different path happens to be hot.

---

## Write path

### Atomic batch writes (always use WriteBatch)

```scala
// DataSource.update — wraps writes in a WriteBatch internally
dataSource.update(Seq(
  DataUpdate.Put(namespace, key1, value1),
  DataUpdate.Put(namespace, key2, value2),
  DataUpdate.Delete(namespace, keyToRemove)
))
```

**Rule:** All multi-key writes use `WriteBatch`. Never issue multiple single `put` calls
for a logically atomic update — a crash between puts corrupts state.

The `DataSource.update` method uses `WriteOptions.setSync(false)` (async, WAL-buffered).
The `DataSource.updateSync` method forces a WAL flush before returning — use only when
durability is required before proceeding (e.g., post-SNAP pivot commit).

### Sync vs async writes

| Method | WAL flushed | Use for |
|--------|------------|---------|
| `update` | No (OS buffer) | Normal block/state writes during sync |
| `updateSync` | Yes (before return) | Pivot block commit, finality checkpoints |

Sync writes are 10-100× slower than async. Do not use `updateSync` in a hot loop.

### Write batch size limits

Large batches consume proportional memory before commit. Limits:

| Operation | Max batch size |
|-----------|---------------|
| Normal block/receipt writes | No explicit limit (bounded by block size) |
| State trie bulk writes (SNAP) | 5,000–10,000 keys per batch |
| Delete range (account cleanup) | Use `deleteRange` — never batch individual deletes |

For SNAP state downloads, commit every 5K–10K nodes to bound memory. Log each commit:

```scala
if (pendingWrites.size >= batchSize) {
  dataSource.update(pendingWrites.toSeq)
  ctx.log.debug(
    "SNAP write batch committed: size={} total={} elapsed={}ms",
    pendingWrites.size, totalWritten, elapsed.toMillis
  )
  pendingWrites.clear()
}
```

---

## EphemDataSource (in-memory, tests only)

`EphemDataSource` is a `HashMap`-backed implementation for unit tests. Rules:

- **Use in tests** to avoid disk I/O and test isolation
- **Never use in production** for data that must survive restarts
- `EphemDataSource` is not thread-safe — all access must be synchronized or single-threaded
- `getAll` returns all entries (useful for asserting test state)
- Does not support WAL, bloom filters, or column families — behavior diverges from RocksDB
  for edge cases (key ordering, empty reads)

**Test isolation:** Each test should construct a fresh `EphemDataSource(Map.empty)`.
Sharing instances between tests causes state leak and flakiness.

---

## RocksDB configuration patterns

### Block cache sizing

Block cache holds frequently accessed SST data. Too small = thrash. Too large = OOM.

```hocon
# application.conf — fukuii RocksDB config
storage {
  rocksdb {
    # Block cache: tune to available RAM minus JVM heap and OS needs
    # Rule of thumb: 25% of available RAM
    block-cache-size = 512MB
    enable-statistics = true   # enables cacheStats metric — always on in prod
  }
}
```

### Statistics and metrics

When `enable-statistics = true`, `RocksDbDataSource.cacheStats` returns
`(hits, misses, indexHits, filterHits)`. Log periodically:

```scala
dataSource.cacheStats.foreach { case (hits, misses, idxHits, filterHits) =>
  val total = hits + misses
  val hitRate = if (total > 0) hits * 100 / total else 0
  ctx.log.info(
    "RocksDB cache: hits={} misses={} hitRate={}% indexHits={} filterHits={}",
    hits, misses, hitRate, idxHits, filterHits
  )
  // Dashboard
  cacheHitRateGauge.set(hitRate)
  cacheMissCounter.increment(misses)
}
```

A hit rate below 80% during sync suggests the block cache is undersized.

### WAL behavior

- **Recovery mode is `kPointInTimeRecovery`, set explicitly** (`RocksDbDataSource.WalRecoveryMode`, B-078). A crash
  recovers a prefix of the writes in the order they were made, never a later write without an earlier one, and a batch
  (one log record, across column families) whole or not at all. Children-before-parent (spec 022), "state that must
  survive a restart is in the batch of what it describes" and a synced write making the log before it durable all rest
  on it. Do not switch to `kTolerateCorruptedTailRecords` or `kSkipAnyCorruptedRecords` (they keep records past a bad
  one) or `kAbsoluteConsistency` (it refuses to open after the torn tail a power loss leaves). Test:
  `RocksDbDataSourceSpec`.
- WAL is enabled by default — provides crash recovery
- Do NOT disable WAL permanently in production
- WAL can be temporarily disabled for bulk import operations (snapshot restore), then re-enabled
- After an interrupted bulk import, check for WAL files (`*.log`) and run integrity check:

```bash
# Check for WAL files (sign of interrupted write)
ls -la /path/to/db/*.log

# Verify integrity
sbt "run --check-db-integrity"
# or direct:
# sqlite3 / rocksdb verify tool
```

---

## DataSource abstraction layers

```
Callers (sync actors, state machines)
    ↓
DataSource interface (db/dataSource/DataSource.scala)
    ↓
RocksDbDataSource — production (wraps RocksDB Java)
EphemDataSource   — tests (wraps HashMap)
    ↓
Namespace (byte prefix) → Column Family handle
```

Storages (db/storage/) are typed wrappers over DataSource that add serialization
(`RocksDbStorage[K, V]`). Always use the typed wrapper, not raw DataSource, when
the type is known.

---

## Consensus state — FORGE routing

Any read or write touching:
- State trie nodes (`MptStorage`, `NodeStorage`)
- Account state (`AccountStorage`)
- Block/header/receipt stores
- World state root

Requires **FORGE review** before changing the access pattern. These are consensus-critical
paths — wrong reads return wrong state; wrong writes corrupt the chain.

```
# Safe without FORGE:
- Read cache stats
- Change block cache size
- Add log statements to DataSource methods
- Fix iterator leak

# Requires FORGE:
- Change namespace IDs
- Change column family configuration
- Change WAL settings on consensus state writes
- Change batch commit strategy for state writes
- Any change to NodeStorage read/write path
```

---

## Common pitfalls

| Pitfall | Symptom | Fix |
|---------|---------|-----|
| Iterator leak | DB grows unboundedly, SST files accumulate | `withResources` on every iterator |
| Point reads in scan loop | Block cache eviction, degraded sync perf | Use `scanRange` / `seekFrom` |
| Shared `EphemDataSource` in tests | Test state pollution, flaky tests | Fresh instance per test |
| `updateSync` in hot path | Write throughput collapses | Use `update` (async) unless durability required |
| Large batch without intermediate commits | OOM during SNAP state download | Commit every 5K–10K keys |
| Changing namespace ID without migration | Silent data loss (reads empty) | New namespace + migration step |
| Direct `RocksDB` calls outside `db/` | Bypasses EphemDataSource swap | All access through `DataSource` |
| Missing `close()` on `ReadOptions`/`WriteOptions` | Native memory leak | `withResources` on all RocksDB objects |

---

## DataSource close protocol (M4 — by-design)

**Verdict:** `RocksDbDataSource.close()` correctly does NOT call `cache.invalidateAll()`.
This is by design — not a missing invalidation.

**Why no invalidation in `close()`:**
The overlay caches (`LruCache[NodeHash, HeapEntry]` in `CachedReferenceCountedStateStorage`,
`MapCache` in `CachedNodeStorage`) live in `db/storage/` and are owned by `DefaultStorages`.
`RocksDbDataSource` has no reference to any `Cache` object — the two layers are intentionally
decoupled. Calling `cache.invalidateAll()` from inside `close()` would invert the abstraction
(DataSource layer knowing about storage layer above it) and would also be incorrect: the
`Cache` trait is not part of the `DataSource` contract.

**Where the actual concern lives:**
The stale-cache scenario only manifests when `dataSource.clear()` is called while a
`CachedNodeStorage` or `CachedReferenceCountedStateStorage` over the same source remains
alive — a test-isolation pattern, not a runtime node path (production nodes never re-open a
closed DB within the same JVM). The fix belongs at the component boundary that owns both:

```scala
// In a test fixture that holds both a cache and a DataSource:
dataSource.clear()
nodeCache.clear()   // <-- the caller's responsibility, not DataSource's
```

**Test suite rule:** Any test that calls `dataSource.clear()` on a source backing a cached
storage MUST also call `cache.clear()` on that storage's cache (or discard the cached storage
instance and construct a fresh one). Add `afterEach { cache.clear(); dataSource.clear() }` to
any spec that uses `CachedNodeStorage` or `CachedReferenceCountedStateStorage` with a shared
`RocksDbDataSource` or `EphemDataSource`.

**No code change made to `close()`.** A clarifying comment was added to
`RocksDbDataSource.close()` documenting this rationale inline.

---

## Grep patterns for storage code review

```bash
# Iterator without withResources (potential leak)
grep -rn "newIterator\b" src/main/ --include="*.scala" | grep -v "withResources\|resource"

# Direct RocksDB.get outside db/ package
grep -rn "\.get(handle\|\.get(cf" src/main/ --include="*.scala" | grep -v "db/dataSource"

# Sync write in potential hot path
grep -rn "updateSync" src/main/ --include="*.scala"

# EphemDataSource in main sources (should only be in test)
grep -rn "EphemDataSource" src/main/ --include="*.scala"

# Missing cacheStats logging (find DataSource impls without metrics call)
grep -rn "class.*DataSource" src/main/ --include="*.scala" | grep -v "Ephem\|Component"
```
