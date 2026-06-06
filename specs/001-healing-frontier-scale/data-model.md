# Phase 1 Data Model: Healing Frontier-Rebuild Scalability

This feature is sync-mechanism, not domain-schema, work. The "entities" are in-memory
structures in `TrieNodeHealingCoordinator` plus one new durable RocksDB column family (Layer 2).
No consensus/domain types change.

---

## Entity: HealingEntry (existing — unchanged)

The unit of the frontier. Defined at `TrieNodeHealingCoordinator.scala:46`.

| Field | Type | Meaning |
|-------|------|---------|
| `pathset` | `Seq[ByteString]` | HP-encoded trie path segments locating the node; sent in `GetTrieNodes` |
| `hash` | `ByteString` (32 B) | keccak256 of the node; the de-dup key and the verify/store key |

**Relationships**: queued into `pendingTasks: ArrayDeque[HealingEntry]` (`:47`); de-duplicated
by `pendingHashSet: mutable.Set[ByteString]` (`:150`). One `HealingEntry` ⇄ one persisted
frontier row (Layer 2), keyed by `hash`.

---

## Entity: Bounded visited set (Layer 1 — implemented)

Transient bookkeeping for the frontier-rebuild DFS. **Not** part of the healing result.

| Property | Value |
|----------|-------|
| Backing | `java.util.LinkedHashMap[ByteString, java.lang.Boolean]`, insertion-order |
| Exposed as | `mutable.Set[ByteString]` via `Collections.newSetFromMap(...).asScala` |
| Eviction | `removeEldestEntry` → `size() > HealingVisitedCap` |
| Capacity | `HealingVisitedCap` (default `4_000_000`; Layer-1 follow-up: from `sync.conf`) |
| Footprint | ≈ cap × ~80 B ≈ 320 MB at default |
| Lifetime | One frontier-rebuild walk; discarded when the walk completes |

**Invariants**:
- INV-1 (completeness): eviction MUST NOT cause a missing node to be omitted from the frontier.
  Guaranteed because the frontier is de-duplicated by `pendingHashSet`, independent of `visited`.
- INV-2 (termination): the DFS MUST terminate for any state; re-walks of evicted present nodes
  add bounded work (no cycles — explicit stack drains).
- INV-3 (bounded memory): live size ≤ `HealingVisitedCap` at all times.

**State transitions**: `empty → growing → (steady at cap, evicting eldest) → discarded`.

---

## Entity: Persisted frontier (Layer 2 — new durable CF)

A crash-durable copy of the outstanding frontier so restart resumes instead of re-walking.

| Property | Value |
|----------|-------|
| Storage | New RocksDB column family `Namespaces.HealingFrontierNamespace` (e.g. `'g'`) |
| Access class | `HealingFrontierStorage extends TransactionalKeyValueStorage[ByteString, Seq[ByteString]]` |
| Key | `hash` (32 B) — the node hash |
| Value | `pathset` (`Seq[ByteString]`), RLP-encoded |
| Wiring | constructed in `SNAPSyncController`, passed to `TrieNodeHealingCoordinator.props` |

**Lifecycle / write rules** (see research R5):

| Event | Site | Action on persisted frontier |
|-------|------|------------------------------|
| Node enqueued | `queueNodes` (`:567`) | `put(hash → pathset)` for each newly-queued entry |
| Node healed (durably stored) | heal-flush `flushRawNodes*` (`:230`/`:242`) | `remove(hash)` for each flushed hash |
| Node dispatched (in-flight) | `requestNextBatch` (`:648`) | **no change** (delete only on heal, not dispatch) |
| `[HEAL-RESTART]` | `:276` | read all → if non-empty+readable, `queueNodes(load)` and skip DFS |

**Invariants**:
- INV-4 (superset of in-flight): the persisted set ⊇ `pendingTasks` (also retains nodes
  currently in `activeRequests`), so a crash mid-request does not lose work.
- INV-5 (fail-safe): empty / missing / unreadable persisted frontier ⇒ fall back to full DFS;
  never skip healing silently (read errors logged loudly).
- INV-6 (idempotent resume): re-loading an entry whose node is now present is a no-op on the
  heal path.

**State transitions** (per entry): `absent → persisted (queued) → [persisted (in-flight)] →
removed (healed)`. A restart at any non-removed state reloads the entry.

---

## Configuration (Layers 1 & 2)

| Key (`snap-sync { … }`) | Type | Default | Layer | Purpose |
|-------------------------|------|---------|-------|---------|
| `healing-visited-cap` | Int | `4000000` | L1 | Bound on the DFS `visited` LRU (FR-002, FR-012) |
| `healing-frontier-persistence` | Boolean | (impl decision; safe to ship `false` dark, flip `true` after L2 tests) | L2 | Enable persisted frontier + resume |

Parsed into `SyncConfig` (`utils/Config.scala`); defaults apply when unset.

---

## What does NOT change

- Trie-node verification (hash equality before store), RLP node encoding, state-root semantics,
  gas, rewards, hard-fork config — untouched. This is the basis for the Constitution Check
  Principle-I PASS.
- The `GetTrieNodes`/`TrieNodes` wire messages and the dispatch/throttle logic.
