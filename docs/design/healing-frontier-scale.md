# Post-SNAP healing: frontier-rebuild scalability

## Problem

After SNAP sync, `TrieNodeHealingCoordinator` heals the residual missing trie nodes. On
restart (`[HEAL-RESTART]`), when the state root is already in local storage, it rediscovers
the missing-node *frontier* by walking the **entire state trie** (accounts + every storage
trie). The original implementation did this depth-first in `rebuildFrontierDFS`, reading each
node one at a time via `mptStorage.get`. That walk has since been replaced by a **level-order
BFS** (`rebuildFrontierBFS`): nodes are processed level by level out of a RocksDB-backed
level queue, with `multiGetNodes` batch reads instead of per-node point reads, behind a
single-flight gate so only one walk can run at a time (DFS → BFS evolution landed via
PR #1323/#1325; log prefixes are now `[HEAL-BFS]` / `[HEAL-RESTART]` / `[HEAL-PULSE]`).

On ETC mainnet the original DFS did not scale. Live diagnosis of `fukuii-primary`
(block ~24.68M, 77 GB RocksDB) found:

- The DFS had visited **36.6M+ nodes and was still climbing** (the full account + storage
  state, far more than the ~12.9M accounts-only walk older builds completed).
- Throughput **~142 nodes/s** — `mptStorage.get` is a random point-read over the 77 GB DB and
  is I/O-bound. One full pass would take **days**.
- The in-memory `visited: mutable.Set[ByteString]` had grown to ~36.6M hashes
  ≈ **2.9 GB**, pushing the heap to **5.18 / 5.37 GB (96%)**.

With `-XX:+ExitOnOutOfMemoryError`, the terminal behaviour was: heap fills → **OOM → restart →
walk from zero → OOM** — an unrecoverable OOM-restart loop. The node never reaches regular sync.
This is a *performance/scalability* wedge, distinct from the older recovery-download abandon
loop ([[project_recovery_recent_root_roll]]).

## Fix (layered)

### 1. Bounded `visited` set — this PR (the OOM-stopper)

Replace the unbounded `mutable.Set[ByteString]` with a fixed-capacity LRU set
(insertion-order eviction via `LinkedHashMap.removeEldestEntry`). Default cap **4M entries
≈ 320 MB** (configurable).

**Why it's correct (never misses a missing node):** the frontier is de-duplicated downstream
by `pendingHashSet` when entries are queued, so re-discovering a missing node is
harmless. Bounding `visited` only means a node evicted from the cache may be *re-walked* if it
is reached again via another reference. In a Merkle trie the walk is tree-shaped except for
content-addressed sharing; for ETC state the dominant shared node is the empty-storage-root
(no children → a cheap re-read), so re-walk overhead is small. The walk still terminates (the
work queue drains; re-walks add bounded work, not cycles).

**Effect:** converts the OOM-restart loop into a walk that **completes without crashing** —
recoverable. It does not by itself make the first walk fast (see 3).

### 2. Persisted frontier + resume — follow-on

The frontier already lives in `pendingTasks` / `pendingHashSet`, but only in memory, so a
restart must rebuild it from scratch. Persist it so restart resumes instead of re-walking:

- Add a dedicated RocksDB column family (`Namespaces`) for the healing frontier:
  `hash → pathset`. Write on `queueNodes`; delete when a node is healed
  (`rawNodeBuffer` flush / heal-complete path).
- Plumb a small `HealingFrontierStorage` into the `TrieNodeHealingCoordinator` constructor
  from `SNAPSyncController` (mirrors how `FlatSlotStorage` was wired).
- On `[HEAL-RESTART]`: if the persisted frontier is non-empty, load it via `queueNodes` and
  **skip the full-state walk**; only fall back to the full walk when no persisted frontier
  exists (first post-SNAP run or a corrupt/missing frontier CF).

Makes restart **O(frontier)** instead of **O(full state)** — the days-long re-walk happens at
most once.

### 3. First-walk throughput — delivered via the BFS rewrite

The initial full walk was I/O-bound (~142 nodes/s) under the serial depth-first
implementation because of serial random point-reads. The originally sketched options here
(batch/parallelise reads across the healing-writer EC; RocksDB tuning) were superseded by the
**level-order BFS rewrite** (`rebuildFrontierBFS`, PR #1323): each level is read in
`multiGetNodes` batches (50K-hash chunks) instead of one `get` per node, and the level queue
lives in a RocksDB column family so queue memory stays bounded regardless of state size.

## Rollout

1 shipped first (small, localized, immediately made the node recoverable). 2 followed as a
separate change (new CF + constructor wiring + tests). 3 was originally gated on measuring
the first walk after 1+2, and ultimately shipped as the BFS + `multiGet` rewrite (see Status).
Operationally, checkpoint import remains the fast unblock for an already-wedged node.

## Status (as built — updated 2026-06)

Spec/plan/tasks: `specs/001-healing-frontier-scale/`.

**Layer 1 — DONE.** Bounded `visited` LRU in `TrieNodeHealingCoordinator` (commit `d04b24703`),
with the cap extracted to the testable companion seam `TrieNodeHealingCoordinator.boundedVisitedSet(cap)`
and made operator-tunable via `sync.snap-sync.healing-visited-cap` (default
`TrieNodeHealingCoordinator.DefaultVisitedCap = 4_000_000`), threaded through `SNAPSyncConfig`.
Now used by `rebuildFrontierBFS`. Unit-tested in `FrontierRebuildSpec` (eviction bound,
insertion-order eviction, set semantics).

**Layer 2 — DONE, default-on.** Persisted frontier in a dedicated column family
(`Namespaces.HealingFrontierNamespace = 'g'`, `hash → RLP(pathset)`) via `HealingFrontierStorage`,
wired from `SNAPSyncController` (`flatSlotStorage.dataSource`, gated by
`sync.snap-sync.healing-frontier-persistence` — **default `true`** in base `sync.conf`
(`src/main/resources/conf/base/sync.conf`)). Write-on-enqueue at all three
growth sites (`queueNodes`, inline child discovery, pivot reseed); delete-on-heal-flush (never on
dispatch); clear on force-complete / pivot-refresh; resume on `[HEAL-RESTART]` loads the frontier
and skips the full-state walk, with a fail-safe fallback to the full walk on empty/absent/corrupt.
No datadir migration (`setCreateMissingColumnFamilies(true)`). Tested in `HealingFrontierStorageSpec`
(storage round-trip) and `HealingFrontierResumeSpec` (resume / fallback / write-on-queue).
Enabled by default after operational validation.

**Layer 3 — DELIVERED (superseded plan).** First-walk throughput landed via the DFS → BFS
rewrite (PR #1323, with follow-up #1325) rather than the originally planned parallel
point-reads: `rebuildFrontierBFS` walks the state level-order with one `multiGetNodes` batch
per 50K-hash chunk, a RocksDB CF-backed level queue, the Layer-1 LRU-bounded visited set, and
a single-flight gate (one walk at a time). Progress is visible under the `[HEAL-BFS]` /
`[HEAL-PULSE]` log prefixes (the old `rebuildFrontierDFS` and its log lines are gone).

All three layers have shipped; the remaining-before-merge items (`sbt pp`, live-node validation
of L1/L2) were completed prior to release.
