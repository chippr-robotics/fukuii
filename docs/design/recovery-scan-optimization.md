# Post-SNAP Recovery Scan — Optimization Plan

**Status:** proposed · **Context:** ETC mainnet checkpoint generation · **Authored:** 2026-06-03

## TL;DR

The post-SNAP recovery scan (`BytecodeRecoveryActor` + `StorageRecoveryActor`) walks the
full ~70M-account ETC state trie to find storage/bytecode gaps before a checkpoint export. It
is **CPU-bound on a 4-core box** (SSD ~60% util, 0% iowait) because it runs **two redundant
single-threaded DFS walks**, each RLP-decoding the entire trie, with a per-node ref-count
unwrap — and it has **no resumability** (a crash/OOM re-scans from account 0).

This plan combines the two walks into one, parallelizes it by trie-prefix shard, skips the
ref-count unwrap on the read path, and persists per-shard progress so restarts resume.
**Realistic speedup: ~2.5–3× on this box (≈24h → ≈8–10h); ~6–10× on a 16-core/64GB host.**

**Decisive call: do _not_ abort and restart the in-flight ETC scan to use this.** It feeds an
all-or-nothing checkpoint export, so a single missed gap = a silently-incomplete checkpoint.
The parallel walk can't be trusted until a validation suite passes (~2 days), which far exceeds
the ~10h remaining. Let the current (known-correct) scan finish this cycle; ship this as a
tested PR for the **next** regeneration.

## Bottleneck (empirically measured, 2026-06-03)

| signal | reading | meaning |
|---|---|---|
| in-container iowait | 0.0% | not disk-bound |
| user CPU | 89.6% | pinned on compute |
| cores / load avg | 4 / 10.5 | massively oversubscribed |
| data disk | SATA SSD (BX500), LUKS, ~60% util, ~1ms reads | spare I/O capacity |
| competitors | other containers <1%, 0 peer requests served | node has the box to itself |

Cost drivers, in order: (1) the **redundant second full-trie decode**; (2) per-node
`ReferenceCountNodeStorage.get` ref-count unwrap (`ReferenceCountNodeStorage.scala:37-38`);
(3) GC pressure from millions of short-lived `MptNode`/`Account` allocations; (4) the hard
4-core ceiling. Parallelizing reads does **not** help — there are no spare cores; the fix is
*less CPU work*.

## Design

### Phase 0 — Resumable checkpointing (ship FIRST, standalone) · Effort M (~1.5d)
Highest value-to-risk, independent of the parallel walk. Today `AppStateStorage` holds only
boolean done-flags, so a restart loses everything.
- Add keys `{Bytecode,Storage}RecoveryShardsComplete` (a 16-bit completed-shard set) and
  `{Bytecode,Storage}RecoveryMissingList` (serialized accumulated gaps), following the existing
  string-serialized `SnapSyncProgress` pattern in `AppStateStorage.scala`. Tag the key with the
  shard-count and the pivot `stateRoot` so a config change or pivot refresh invalidates stale
  checkpoints.
- Each shard commits its completion + found-gaps atomically; `startRecovery`
  (`SyncController.scala:1109`) reads progress and skips done shards; `{bytecode,storage}RecoveryDone()`
  clears the checkpoint.
- **This alone removes the "never restart the node during recovery" landmine** on this
  OOM-prone host — worth landing even if the parallel walk never ships.

### Phase 1 — `CombinedRecoveryVisitor`: single walk, dual check · Effort M (~1d)
New `mpt/MptVisitors/CombinedRecoveryVisitor.scala`, copying `PathTrackingLeafWalkVisitor`
(it already tracks the full nibble path → 32-byte accountHash). The `onLeaf(accountHash, leaf)`
callback checks **both** per leaf: `codeHash != EmptyCodeHash → evmCodeStorage.get(...).isEmpty`,
and `storageRoot != EmptyStorageRootHash → try mptStorage.get(storageRoot) catch MPTException`.
Per-shard local `HashSet`s for seen-codeHash/seen-storageRoot dedup (mirrors today). **Eliminates
the redundant second full-trie RLP decode — the single biggest win, ~halves decode work.**

### Phase 2 — Parallel-by-shard driver + `CombinedRecoveryActor` · Effort M (~1.5d)
New `blockchain/sync/CombinedRecoveryActor.scala`. `ShardEnumerator.enumShards` resolves the
state root into disjoint, exhaustive shards: **Branch → its 16 children**; **Extension → recurse,
mapping all subtree leaves to the extension's first nibble** (the subtle case); Leaf → single
pseudo-shard; Hash → resolve; Null → empty. Each shard gets its **own** `getBackingStorage(pivot)`
handle so a real missing-node `MPTException` is isolated per subtree (preserving today's
semantics) and threads don't share a storage handle. Drive shards on a **bounded pool sized
`min(nproc, cap)`, default 3 not 4** (reserve a core + memory for the live node/GC; only ~5GB
free with swap active). Merge per-shard missing-sets in the single-threaded driver (commutative
union). Behind `sync.conf` flag `parallel-recovery-scan` (default false).

### Phase 3 — Raw read-only path, skip ref-count unwrap · Effort M (~0.5d, OPTIONAL)
Add `getRaw` to the node-storage trait; in `ReferenceCountNodeStorage` extract only the
`nodeEncoded` RLP element without building the full `StoredNode(references, lastUsedByBlock)`.
`Try`-wrap with fallback to `get()`. Expose a scan-only `MptStorage` variant used **only** by the
combined scan (never the write path). ~10–20% extra CPU on the per-node frame. Ship only if
benchmarks show the unwrap is still hot after Phases 1–2. (Skip the compaction-pause idea — too
intrusive while the node is live.)

### Phase 4 — Validation suite (THE GATE) · Effort M (~2d)
The scan output feeds an all-or-nothing export, so this gates everything:
- **Unit equivalence:** synthetic gappy trie (incl. a forced root `ExtensionNode`) → assert
  `parallel.sorted == expected.sorted` (no over/under-count).
- **Resume:** kill at 25/50/75%, assert resumed final list == uninterrupted run.
- **Real equivalence:** run legacy two-actor scan vs new combined-parallel scan against the same
  on-disk **Mordor** state → assert identical sorted missing-lists; measure speedup.
- Reuse `{Storage,Bytecode}RecoveryActorSpec` patterns + `preloadedMissingForTesting` hooks.
- ⚠️ Never run `sbt testEssential/testStandard` while Barad-dûr is active — targeted specs only.

### Phase 5 — Cutover + first-cycle cross-check · Effort S (~0.5d + run)
`startRecovery` spawns `CombinedRecoveryActor` when the flag is on; keep legacy actors in-tree
(deprecated) one cycle. For the **first** ETC run, enable the flag but cross-check the missing-list
against a legacy scan (or the known-good checkpoint already produced) before trusting the export.
Flip default to true only after one clean cross-checked ETC cycle.

### Defense-in-depth (recommended regardless)
Add `CheckpointExporter --verify` pre-flight that walks the trie and **aborts with the named gap
if any node is missing**, before streaming the archive — turns a silent bad checkpoint into a
loud, actionable failure independent of which scan produced the state.

## Expected speedup & timing

- **Combine (Phase 1):** ~2× on the decode-bound portion (kills the redundant pass).
- **Parallel (Phase 2):** ~2.2–2.6× wall-clock on *this* box (3 workers; uneven shard sizes →
  slowest-shard bound; memory ceiling), not the theoretical 3×.
- **Raw read (Phase 3):** +10–20% per-node.
- **This 4-core/RAM-tight box:** ~2.5–3× end-to-end (≈24h → **≈8–10h**).
- **16-core/64GB host:** ~6–10× (a 10–20h scan → **~1.5–3h**) — the walk is embarrassingly
  parallel by trie prefix; the real lever is hardware.
- **Resumability changes the expected-value math most:** a crash at 80% today costs a full
  restart; with per-shard checkpointing it costs only the in-flight shard.

## Why NOT restart the current scan now
1. Output feeds an **all-or-nothing** export — one missed gap = silently-broken checkpoint.
2. Parallel correctness is **untrusted** until Phase 4 passes (~2 days), ≫ the ~10h remaining.
3. Restarting discards 55% of **known-correct** work to gamble on unvalidated code → negative EV.
4. The optimization's value lands on the **next** regeneration, by which point it's tested.

## Open questions (need a decision)
1. Is the current scan feeding an immediate checkpoint export this cycle (so ~19:50 CDT is the
   real deadline)? (We believe yes — checkpoint sync is the goal.)
2. Run the optimized scan on a **dedicated regeneration host** (more cores, idle) rather than
   alongside the live node? Strongly preferred given the 4-core/RAM-tight constraint.
3. Is a Mordor on-disk state snapshot available for the equivalence test, or does it need a fresh
   ~50min Mordor SNAP sync to build the fixture?
4. Ship Phase 0 (resumability) as its own immediate PR (recommended) vs bundled?
5. First ETC cutover risk posture: full ETC side-by-side cross-check (≈one extra scan) vs Mordor
   equivalence + export pre-verify?

---

## Addendum (2026-06-04): the aged-pivot download failure + the recent-root fix

**What happened on the live ETC run:** the 24h scan finished (86M accounts, **538 missing storage tries + 2 bytecodes**), but the *download* of those gaps returned empty from every peer (`StorageRanges slotSets=0`). Root cause: the recovery downloads against the **frozen pivot root** (block 24682570), which by scan-end was **~6,600 blocks / 24h** behind the tip — far outside peers' ~128-block (~27-min) snapshot serve window. The scan took ~50× longer than the pivot's serve window, so the download is structurally too late. (`StorageRecoveryActor` builds the coordinator with `stateRoot`=pivot + `getBackingStorage(pivotBlockNumber)`.)

**Why this is general, not checkpoint-specific:** `SyncController.scala:892` runs recovery on *every* post-SNAP startup and **gates regular sync** on it. So the aged-pivot flaw bites any node whose SNAP→recovery window outlives the serve window — and even the optimized ~8–10h scan exceeds the 27-min window. The scan-then-download-at-pivot shape cannot complete a checkpoint-grade state for a chain this size.

### Fix: recovery rolls its download root to a *recent* block (reuse existing machinery)
The coordinator already supports `StoragePivotRefreshed(newStateRoot)` (`StorageRangeCoordinator.scala:794`) — it swaps `stateRoot` and re-queues tasks. During SNAP, `PivotStateUnservable` → `SNAPSyncController.refreshPivotInPlace()` fetches a recent header and replies `StoragePivotRefreshed`. **The recovery path is the only place that doesn't do this** — `StorageRecoveryActor`'s `PivotStateUnservable` handler (line ~176-186) just counts events and abandons.

Change: in `StorageRecoveryActor`, on `PivotStateUnservable`, **fetch a recent canonical header (its `stateRoot`) and send `StoragePivotRefreshed(recentRoot)` to the coordinator** instead of abandoning. Then:
- **Cold contracts** (storage unchanged since the pivot — ≈all 538 random SNAP-skipped roots): the recent root resolves the same account → same storage root → the reconstructed nodes are content-identical → the expected pivot storage-root node is filled. Serve-window-proof.
- **Hot contracts** (storage changed since pivot): the recent root yields different nodes (different hashes) → the expected pivot node is *not* filled. Detect by re-checking each gap's expected root node on disk after the roll; log the residue as genuinely-unrecoverable-at-this-pivot (rare).
- **Bytecode** gaps need no change — `GetByteCodes` is hash-keyed (content-addressed), not root-gated.

Recent-root source: reuse the SNAP pivot-header-bootstrap path the controller already uses for `refreshPivotInPlace`; wire it so the recovery actor (spawned by `SyncController`) can request a recent header and thread its `stateRoot` into `StoragePivotRefreshed`.

**Correctness gate:** a unit test that a roll to a root where the account's storage is unchanged fills the expected node; an integration check that residual (hot) gaps are reported, not silently marked done. This is the load-bearing fix; combine+parallel (speed) and shard-resume (resilience) stack on top but are independent.

---

## Implementation log (2026-06-04)

Building the rework test-first, in the dependency order below. Each component is proven correct in
isolation before integration — a wrong partition or a missed gap = a corrupt all-or-nothing checkpoint.

| # | Component | Status | Tests |
|---|---|---|---|
| 1 | `ShardEnumerator` — disjoint+exhaustive trie partition | ✅ done | 8 (partition multiset equality, root-extension edge, prefix reconstruction, property) |
| 2 | `CombinedRecoveryScan` — single-pass bytecode+storage check | ✅ done | 2 (equivalence across 7 present/missing combos, no-gaps) |
| 3 | `RecoveryProgress` + `AppStateStorage` resumable progress | ✅ done | 16 (round-trip, tag invalidation, corruption→None, atomic `recoveryDone`) |
| 4 | `CombinedRecoveryActor` — parallel-by-shard driver + flag | pending | — |
| 5 | Recent-root roll (the correctness fix, addendum above) | pending | — |
| 6 | Wire `SyncController` + build + validate | pending | — |

### Phase 0 outcome — resumable progress (implemented + audit-hardened)

`RecoveryProgress` (a versioned line-delimited codec) + four `AppStateStorage` methods
(`putRecoveryProgress` / `getRecoveryProgress` / `getRecoveryProgressFor(scanRoot, shardCount)` /
`clearRecoveryProgress`) persist the completed-shard set together with the accumulated gaps as **one
atomic value under one key**, tagged with `(scanRoot, shardCount)`. A crash mid-scan resumes from the
last completed shard; a torn/corrupt value reads back as `None` → fresh scan, never a silently-incomplete
gap set.

A 4-lens adversarial audit (crash-atomicity / codec-corruption / stale-tag / ETC-scale) hardened it:
- `deserialize` rejects wrong-but-plausible blobs — hash fields must be exactly 64 hex chars, `shardCount
  >= 1` (a 0 would read as spuriously "complete"), completed indices ∈ `[0, shardCount)`.
- `recoveryDone()` sets both done-flags **and** clears the checkpoint atomically — no done-but-stale window.
- scale lens found nothing: the single-blob value is fine at ETC scale (hundreds of gaps, ≤256 rewrites).

### Task #4 driver obligations (carried forward from the audit)

The persistence layer is deliberately dumb — it round-trips exactly what it's handed. The **driver** must:
1. **Iterate `progress.remainingShards` only** — never re-scan a completed shard (would double-count gaps).
2. **Dedup storage roots across shards** — `CombinedRecoveryScan` dedups per-shard (local `HashSet`); the
   same `storageRoot` can recur across shards, so the driver keeps a driver-level `Set[ByteString]` when
   merging per-shard results into the accumulated progress.
3. **Persist per shard atomically** — one `putRecoveryProgress(updated).commit()` per shard completion
   (completion index + that shard's gaps together).
4. **Use a fixed partition depth** (default 1 → up to 16 shards). `shardCount` in the tag is the partition
   fingerprint; if depth is ever made configurable, fold it into the tag too.
5. **Call `recoveryDone()`** (the atomic primitive) once the scan + download finish.
