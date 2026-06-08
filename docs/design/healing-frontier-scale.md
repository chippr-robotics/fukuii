# Post-SNAP healing: frontier-rebuild scalability

## Problem

After SNAP sync, `TrieNodeHealingCoordinator` heals the residual missing trie nodes. On
restart (`[HEAL-RESTART]`, `TrieNodeHealingCoordinator.scala:260`), when the state root is
already in local storage, it rediscovers the missing-node *frontier* by walking the **entire
state trie** (accounts + every storage trie) depth-first in `rebuildFrontierDFS`
(`TrieNodeHealingCoordinator.scala:877`), reading each node one at a time via
`mptStorage.get`.

On ETC mainnet this does not scale. Live diagnosis of `fukuii-primary` (block ~24.68M, 77 GB
RocksDB) found:

- The DFS had visited **36.6M+ nodes and was still climbing** (the full account + storage
  state, far more than the ~12.9M accounts-only walk older builds completed).
- Throughput **~142 nodes/s** — `mptStorage.get` is a random point-read over the 77 GB DB and
  is I/O-bound. One full pass would take **days**.
- The in-memory `visited: mutable.Set[ByteString]` (`:270`) had grown to ~36.6M hashes
  ≈ **2.9 GB**, pushing the heap to **5.18 / 5.37 GB (96%)**.

With `-XX:+ExitOnOutOfMemoryError`, the terminal behaviour is: heap fills → **OOM → restart →
DFS from zero → OOM** — an unrecoverable OOM-restart loop. The node never reaches regular sync.
This is a *performance/scalability* wedge, distinct from the older recovery-download abandon
loop ([[project_recovery_recent_root_roll]]).

## Fix (layered)

### 1. Bounded `visited` set — this PR (the OOM-stopper)

Replace the unbounded `mutable.Set[ByteString]` at `:270` with a fixed-capacity LRU set
(insertion-order eviction via `LinkedHashMap.removeEldestEntry`). Default cap **4M entries
≈ 320 MB** (configurable).

**Why it's correct (never misses a missing node):** the frontier is de-duplicated downstream
by `pendingHashSet` (`:130`) when entries are queued, so re-discovering a missing node is
harmless. Bounding `visited` only means a node evicted from the cache may be *re-walked* if it
is reached again via another reference. In a Merkle trie the walk is tree-shaped except for
content-addressed sharing; for ETC state the dominant shared node is the empty-storage-root
(no children → a cheap re-read), so re-walk overhead is small. The DFS still terminates (the
explicit stack drains; re-walks add bounded work, not cycles).

**Effect:** converts the OOM-restart loop into a walk that **completes without crashing** —
recoverable. It does not by itself make the first walk fast (see 3).

### 2. Persisted frontier + resume — follow-on

The frontier already lives in `pendingTasks` / `pendingHashSet`, but only in memory, so a
restart must rebuild it from scratch. Persist it so restart resumes instead of re-walking:

- Add a dedicated RocksDB column family (`Namespaces`) for the healing frontier:
  `hash → pathset`. Write on `queueNodes`; delete when a node is healed
  (`rawNodeBuffer` flush / heal-complete path).
- Plumb a small `HealingFrontierStorage` into the `TrieNodeHealingCoordinator` constructor
  (`:28`) from `SNAPSyncController` (mirrors how `FlatSlotStorage` was wired).
- On `[HEAL-RESTART]`: if the persisted frontier is non-empty, load it via `queueNodes` and
  **skip the full DFS**; only fall back to the DFS when no persisted frontier exists (first
  post-SNAP run or a corrupt/missing frontier CF).

Makes restart **O(frontier)** instead of **O(full state)** — the days-long re-walk happens at
most once.

### 3. First-walk throughput — optional, separate

The initial full DFS is I/O-bound (~142 nodes/s) because of serial random point-reads. Options
(out of scope here): batch/parallelise the reads across the healing-writer EC; RocksDB tuning
(`max-open-files`, larger block cache) — noting the open-files/block-cache memory trade-off
documented for the recovery scan ([[snap_etc_host_freeze_oversubscription]]).

## Rollout

1 ships first (small, localized, immediately makes the node recoverable). 2 follows as a
separate change (new CF + constructor wiring + tests). 3 is an optimization if the first walk
is still too slow after 1+2. Operationally, checkpoint import remains the fast unblock for an
already-wedged node.

## Status (as built — 2026-06-06)

Spec/plan/tasks: `specs/001-healing-frontier-scale/`.

**Layer 1 — DONE.** Bounded `visited` LRU in `TrieNodeHealingCoordinator` (commit `d04b24703`),
with the cap extracted to the testable companion seam `TrieNodeHealingCoordinator.boundedVisitedSet(cap)`
and made operator-tunable via `sync.snap-sync.healing-visited-cap` (default
`TrieNodeHealingCoordinator.DefaultVisitedCap = 4_000_000`), threaded through `SNAPSyncConfig`.
Unit-tested in `FrontierRebuildSpec` (eviction bound, insertion-order eviction, set semantics).

**Layer 2 — DONE, default-off.** Persisted frontier in a dedicated column family
(`Namespaces.HealingFrontierNamespace = 'g'`, `hash → RLP(pathset)`) via `HealingFrontierStorage`,
wired from `SNAPSyncController` (`flatSlotStorage.dataSource`, gated by
`sync.snap-sync.healing-frontier-persistence`, default `false`). Write-on-enqueue at all three
growth sites (`queueNodes`, inline child discovery, pivot reseed); delete-on-heal-flush (never on
dispatch); clear on force-complete / pivot-refresh; resume on `[HEAL-RESTART]` loads the frontier
and skips the DFS, with a fail-safe fallback to the full walk on empty/absent/corrupt. No datadir
migration (`setCreateMissingColumnFamilies(true)`). Tested in `HealingFrontierStorageSpec` (storage
round-trip) and `HealingFrontierResumeSpec` (resume / fallback / write-on-queue). Ships dark; flip
`healing-frontier-persistence = true` after operational validation on a large-state node.

**Layer 3 — NOT STARTED (gated).** Measure the first-walk throughput on a large-state node first
(`tasks.md` T021); pursue `multiGet` batching only if the one-time full walk is still too slow.

Remaining before merge: `sbt pp`, and operational validation of L1 (no OOM loop) and L2 (resume in
minutes, CF auto-creates) on a large-state node — both require a live node, out of the build session.
