# Phase 0 Research: Healing Frontier-Rebuild Scalability

All Technical Context unknowns are resolved below. There are **no remaining NEEDS
CLARIFICATION** items.

---

## R1 — Why a bounded `visited` set is complete (never misses a missing node)

**Decision**: Bound the frontier-rebuild DFS `visited` set with a fixed-capacity,
insertion-order-eviction LRU (default 4M entries). This is correct: it never causes a missing
node to be skipped.

**Rationale**:
- The healing *result* is the frontier, de-duplicated by `pendingHashSet`
  (`TrieNodeHealingCoordinator.scala:150`) in `queueNodes` (`:567`) and on every child-discovery
  check (`:1039`, `:1052`, `:1067`). A missing node re-discovered after its `visited` record was
  evicted is still enqueued and de-duplicated — it is healed exactly once.
- `visited` exists only to avoid *re-walking present subtries*. Evicting a present node merely
  means that, if it is reached again via a shared reference, it is re-read and re-traversed —
  extra work, not incorrect work.
- A Merkle Patricia Trie walk is tree-shaped except for content-addressed sharing. The DFS uses
  an explicit stack that strictly drains; re-walks push a bounded amount of additional work and
  introduce no cycles, so the walk terminates.

**Cost model**: re-walk overhead is dominated by the most-shared present node. On ETC the
dominant shared node is the **childless empty-storage-root** (every EOA / empty-storage contract
references it) — re-reading it is a single point-read with no children to expand, so eviction
churn is cheap. Correctness does not depend on this; only the cost estimate does.

**Alternatives considered**:
- *Unbounded set* — the status quo; OOM-loops on ETC (~2.9 GB). Rejected.
- *Bloom filter / probabilistic set* — bounded memory but false positives would mark an
  *unvisited* node as visited and could skip a real subtree → completeness violation. Rejected.
- *On-disk visited set (RocksDB)* — bounded heap but adds a second random-I/O stream to an
  already I/O-bound walk; the LRU is simpler and the empty-storage-root churn makes it
  unnecessary. Rejected for L1; superseded entirely by L2 (persisted frontier removes the need
  to re-walk at all).

---

## R2 — LRU implementation: `LinkedHashMap.removeEldestEntry`

**Decision**: `java.util.LinkedHashMap` with `removeEldestEntry` returning `size() > cap`,
wrapped as a `mutable.Set[ByteString]` via `Collections.newSetFromMap(...).asScala`
(`:138-144`). Insertion-order eviction (access-order `false`).

**Rationale**: O(1) put/contains/evict with deterministic insertion-order eviction and a tight,
predictable footprint (~cap × ~80 B ≈ 320 MB at 4M). Insertion-order (not access-order) is
correct here: the earliest-inserted entries are the earliest-completed subtries — the safest to
forget. Java interop is the idiomatic way to get `removeEldestEntry`; Scala's collections have no
direct bounded-LRU equivalent. This is consistent with Principle IV (justified interop).

**Alternatives considered**: Caffeine/Guava cache (heavier dependency, async eviction less
predictable — rejected, no new dep needed); hand-rolled ring buffer + set (more code, same
result — rejected).

---

## R3 — Cap default and operator tunability (FR-012, SC-002)

**Decision**: Default cap **4,000,000** entries (≈ 320 MB). Expose as `sync.conf` key
`snap-sync.healing-visited-cap`, parsed into `SyncConfig`; the constant is the default when
unset.

**Rationale**: 320 MB fits comfortably inside the documented ETC-mainnet heap (≥ 5 GB) while
covering a large fraction of the working set, minimising re-walks. Operators on smaller heaps
can lower it (more re-walks, still complete) or raise it on big heaps. Making it config-driven is
a one-line change in `SyncConfig` plus the `sync.conf` entry; the current draft hard-codes
`HealingVisitedCap = 4_000_000`, which becomes the fallback default.

**Alternatives considered**: hard-code only (spec FR-012 asks for tunability; rejected); derive
from `-Xmx` at runtime (clever but opaque and hard to test — rejected in favour of an explicit
key with a sane default).

---

## R4 — Persisted frontier storage shape (Layer 2)

**Decision**: A dedicated RocksDB column family keyed `node hash (32 B) → encoded pathset`,
exposed through a new `HealingFrontierStorage extends TransactionalKeyValueStorage[ByteString,
Seq[ByteString]]`, mirroring `FlatSlotStorage`.

**Rationale**: The frontier entry is exactly `HealingEntry(pathset: Seq[ByteString], hash:
ByteString)` (`:46`). Keying by `hash` matches the existing `pendingHashSet` de-dup key and makes
delete-on-heal an O(1) point-delete by the same hash used at the heal site. Value =
the `pathset` (the HP-encoded path segments needed to re-issue `GetTrieNodes`). RLP-encode the
`Seq[ByteString]` for the value serializer (the codebase already uses RLP for byte-seq
encoding); the storage class follows the `TransactionalKeyValueStorage` idiom so batched
commits compose with existing write batches.

**Alternatives considered**:
- *Reuse `FastSyncStateNamespace`* — different lifecycle/semantics; risks colliding with fast-sync
  state. Rejected: a dedicated CF is cleaner and independently clearable.
- *Single blob (whole frontier as one key)* — simpler writes but rewriting the entire frontier on
  every queue/heal is O(frontier) per mutation. Rejected; per-hash keys give O(1) mutations.

---

## R5 — When to write / delete persisted-frontier entries (Layer 2)

**Decision**: **Write** on `queueNodes` (`:567`) — alongside `pendingHashSet += hash`.
**Delete** on heal-flush, i.e. when buffered raw nodes are actually written to storage
(`flushRawNodesSync`/`flushRawNodesAsync`, `:230`/`:242`) for the hashes in that flush batch.

**Rationale**: The persisted frontier must represent *missing-and-not-yet-healed* nodes. Note
`requestNextBatch` removes a hash from `pendingHashSet` on **dispatch** (`:648`) — but a
dispatched node is in-flight, not healed, and may fail and be re-queued. Therefore the persisted
frontier must **not** delete on dispatch; it deletes only when the node is durably stored. This
makes the persisted set a *superset* of `pendingTasks` (it also retains in-flight entries), which
is exactly what we want for crash-safety: on restart, in-flight-but-unhealed nodes are reloaded
and re-dispatched.

**Idempotency (FR-010)**: On restart, load every persisted entry via `queueNodes`. If an entry
was in fact already healed before the crash (write-after-delete races are avoided by deleting
only post-flush, but a persisted entry could still correspond to a now-present node), the normal
`isNodeInStorage`/heal path treats a present node as a no-op. Re-loading is therefore safe.

**Alternatives considered**: delete-on-dispatch (loses in-flight nodes on crash → incomplete
heal; rejected); write-on-discovery-in-DFS instead of `queueNodes` (would also persist nodes the
de-dup drops; `queueNodes` is the single choke point and the right place — chosen).

---

## R6 — New column family on an existing database (migration)

**Decision**: Add `HealingFrontierNamespace` to `Namespaces.nsSeq`; **no migration code needed**.

**Rationale**: `RocksDbDataSource` opens with `setCreateMissingColumnFamilies(true)`
(`RocksDbDataSource.scala:389`), so an existing on-disk DB that lacks the new CF gets it created
automatically on next open. The `nsSeq` uniqueness assert (`:458`) guards against a duplicate
byte. Pick an unused single byte — current bytes are `r h b n c w s a d k i f l m`; e.g. `'g'`
(`HealingFrontierNamespace`). Final byte chosen at implementation time provided it is unique.

**Alternatives considered**: explicit migration/version bump (unnecessary given
create-missing-CF; rejected).

---

## R7 — Resume vs. full-DFS decision and fail-safe fallback (Layer 2, FR-007/FR-009)

**Decision**: On `[HEAL-RESTART]` (`:276`), after confirming the state root is already local:
if `HealingFrontierStorage` is enabled **and** the persisted frontier is non-empty and readable,
load it via `queueNodes` and **skip** `rebuildFrontierDFS`. Otherwise (disabled, empty, missing,
or read error) run the full DFS exactly as today.

**Rationale**: Fail-safe — any doubt about the persisted frontier falls back to the
provably-complete full walk (now bounded by L1, so it cannot OOM). Gate persistence behind
`snap-sync.healing-frontier-persistence` (default decided at implementation; safe to default
`true` once L2 tests pass, or `false` to ship dark first). A read error must be caught and logged
loudly (Principle: fail loudly, then fall back) — never silently skip healing.

**Alternatives considered**: trust the persisted frontier unconditionally (a corrupt CF would
wedge healing; rejected — must fall back).

---

## R8 — First-walk throughput options (Layer 3, deferred)

**Decision**: Defer. Document options; do not implement until L1+L2 are shipped and the first
walk is measured to still be too slow.

**Options (ranked)**:
1. **Batch reads via RocksDB `multiGet`** for sibling children at each branch node — turns N
   serial point-reads into one batched read; biggest win for the I/O-bound walk, lowest risk.
2. **Parallelise the DFS** across the existing `healing-writer-dispatcher` EC (bounded worker
   pool) — more speedup, more complexity (shared `visited`/stack must be made concurrency-safe).
3. **RocksDB tuning** — larger block cache / `max-open-files`. Must respect the open-files vs.
   heap trade-off documented in `snap_etc_host_freeze_oversubscription`; raising block cache
   trades heap for read speed and can reintroduce host memory over-subscription. Tune within the
   host memory budget only.

**Rationale**: L2 makes the full walk happen at most once, which may make L3 unnecessary;
measuring first is cheaper than building. Start with `multiGet` if needed (smallest, safest).

**Alternatives considered**: rewriting the walk as a streaming RocksDB prefix-iteration (the
state CF is keyed by node hash, not by trie order, so an ordered scan does not correspond to a
trie walk — rejected).

---

## Summary of resolved decisions

| # | Topic | Decision |
|---|-------|----------|
| R1 | Bounded-set completeness | Safe: `pendingHashSet` de-dup + tree-shaped DFS terminates |
| R2 | LRU impl | `LinkedHashMap.removeEldestEntry`, insertion-order, Set-wrapped |
| R3 | Cap default/tunable | 4M (≈320 MB) default, `sync.conf` `healing-visited-cap` |
| R4 | Persisted shape (L2) | New CF `hash → RLP(pathset)`, `HealingFrontierStorage` |
| R5 | Write/delete points (L2) | Write on `queueNodes`; delete on heal-flush (not dispatch) |
| R6 | New CF migration | None — `setCreateMissingColumnFamilies(true)` |
| R7 | Resume vs DFS (L2) | Load if present+readable, else fail-safe full DFS |
| R8 | Throughput (L3) | Deferred; prefer `multiGet`, then bounded parallelism, then tuning within memory budget |
