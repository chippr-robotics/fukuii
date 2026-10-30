> Closed 2026-10-30 as Superseded by 022; boxes below are as they were at closure.

---
description: "Task list for Post-SNAP BFS State-Healing Walk — Performance, Redundancy-Avoidance, and Observability"
---

# Tasks: Post-SNAP BFS State-Healing Walk — Performance, Redundancy-Avoidance, and Observability

**Input**: Design documents from `specs/002-bfs-heal-performance/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/internal-interfaces.md, quickstart.md

**Tests**: INCLUDED — FR-025 mandates a shared-ancestor completeness regression test and the constitution
(Principle III) requires deterministic tests for behavioral changes. Tests are deterministic (no
`Thread.sleep`); use Pekko TestKit + same-thread `ExecutionContext` injection.

**Organization**: Tasks grouped by user story (priority order). US1 is the MVP and the live-node unblocker.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: parallelizable (different file, no dependency on an incomplete task)
- **[Story]**: US1–US7 (setup/foundational/polish carry no story label)
- Paths are repository-relative.

**Module path roots** (single-project):
- Snap actors: `src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/`
- DB storage: `src/main/scala/com/chipprbots/ethereum/db/storage/`
- DB dataSource: `src/main/scala/com/chipprbots/ethereum/db/dataSource/`
- Config: `src/main/resources/conf/base/`
- Tests: `src/test/scala/com/chipprbots/ethereum/...`

> **CONSENSUS GUARDRAIL (Constitution I)**: only US1 (marker set/clear) and the US2/US6 inflation counters
> touch consensus-adjacent code. US1 MUST go through the `forge` protocol (T_FORGE) and the FR-025
> regression test (T015) before merge. No change may alter which nodes the walk enqueues/visits/declares
> missing.

> **OPERATIONAL GUARDRAIL**: do NOT run `sbt testEssential`/`testStandard` while barad-dûr nodes are active
> (freezes host) — run targeted suites. Do NOT restart the live production node to apply config without
> operator sign-off (FR-026).

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Branch and baseline.

- [x] T001 Create work branch `002-bfs-heal-performance` from `staging` (`git switch -c 002-bfs-heal-performance staging`)
- [x] T002 Confirm a clean baseline: `sbt compile-all` is green on the new branch (no changes yet)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Deterministic-test scaffolding reused by US1 (FR-025) and US3 (concurrency equivalence). MUST
complete before those stories' tests.

**⚠️ CRITICAL**: US1 and US3 test tasks depend on these.

- [x] T003 Add a shared synthetic-trie test fixture (a branch/extension node referenced by two parents, with an injectable missing grandchild) usable by `rebuildFrontierBFS` tests, in `src/test/scala/com/chipprbots/ethereum/blockchain/sync/snap/actors/HealingTrieFixtures.scala`. Provides `sharedAncestor` (branch + extension variants), `wideSharedAncestor` (parallel-config concurrency), `multiNodeWithSharedAncestor` (serial-equivalence), `wideFrontierLevel` (a >50K-entry level so the parallel-split branch genuinely fires for T029), and `childlessLeafRoot`. All fixed-byte/deterministic.
- [x] T004 Thread a test-injectable `ExecutionContext` (same-thread) and the new healing config (min-parallelism, reserved-cores, optional reader-EC) through `TrieNodeHealingCoordinator` `props`/constructor so walk tests run deterministically, in `src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/actors/TrieNodeHealingCoordinator.scala` (companion `props` + constructor params only; no behavior change yet)

**Checkpoint**: deterministic walk/marker tests can now be written.

---

## Phase 3: User Story 1 — Skip a completed heal (Priority: P1) 🎯 MVP

**Goal**: A genuinely-healed trie skips the full-state walk on restart and reaches regular sync in minutes
(SC-001), without ever skipping when a node could be missing (FR-004).

**Independent Test**: After a verification-complete run over a fully-present trie, `store.isComplete == true`;
restart skips the walk; an incomplete trie still walks; a missing node behind a shared ancestor is still found.

### Tests for User Story 1 (write first, expect FAIL)

- [x] T005 [P] [US1] Test: marker is SET on the verification-complete path (fully-present trie ⇒ `store.isComplete == true` after `VerificationDFSComplete`), in `src/test/scala/com/chipprbots/ethereum/blockchain/sync/snap/actors/HealingFrontierResumeSpec.scala`
- [x] T006 [P] [US1] Test: same-root `HealingPivotRefreshed` keeps the marker; different-root clears it, in `HealingFrontierResumeSpec.scala`
- [x] T007 [P] [US1] Test: complete+marked store on restart logs the skip path and reaches `StateHealingComplete` WITHOUT the full-state DFS, in `HealingFrontierResumeSpec.scala`
- [x] T008 [P] [US1] Negative test: the `totalNodesHealed == 0` idle arm does NOT mark complete; an incomplete trie (one injected missing node) leaves `isComplete == false` and still walks on restart (FR-004), in `HealingFrontierResumeSpec.scala`
- [x] T009 [P] [US1] FR-025 regression: a missing node behind a SHARED branch/extension ancestor is still discovered after the visited set reports that ancestor seen (uses T003 fixture). Implemented in `TrieNodeHealingCoordinatorSpec.scala` (the TestKit home that already drives the coordinator over synthetic tries — `FrontierRebuildSpec` is a pure non-TestKit `boundedVisitedSet` suite, so retrofitting an actor system onto it was avoided): two cases (shared BranchNode + shared ExtensionNode ancestor), each asserting `pendingTasks == 1` (discovered exactly once, not 0/skipped, not 2/double-counted).

### Implementation for User Story 1

- [x] T010 [US1] Set `healingFrontierStorage.foreach(_.markComplete())` in the `VerificationDFSComplete` handler (after `verificationPassComplete = true`, before `StateHealingComplete`) with a log line mirroring the existing marker-set log, in `TrieNodeHealingCoordinator.scala`
- [x] T011 [US1] Mirror `markComplete()` into the `HealingCheckCompletion` handler ONLY on the `verificationPassComplete == true` arm — NOT the `totalNodesHealed == 0` idle arm, in `TrieNodeHealingCoordinator.scala`
- [x] T012 [US1] Guard `HealingPivotRefreshed`: when `newStateRoot == stateRoot` (full 32-byte compare, not the 4-byte log prefix), log a no-op and return early without clearing the marker/frontier/pending state; only the differing-root path reaches `clearPersistedFrontier()`, in `TrieNodeHealingCoordinator.scala`
- [x] T013 [US1] Add a clarifying comment at `clearPersistedFrontier` that same-root refresh no longer reaches it, in `TrieNodeHealingCoordinator.scala`
- [x] T_FORGE [US1] `forge` consensus-adjacent review of T010–T013: confirm the marker invariant (set ⟺ walk covered trie AND `isComplete`) and that no enqueue/skip/detection semantics changed
- [x] T014 [US1] Record an ADR for the completeness-marker set/clear semantics + the Bloom-filter rejection, in `docs/adr/NNNN-healing-completeness-marker.md`

**Checkpoint**: US1 fully functional — a healed node skips the walk on restart; MVP and the live-node unblocker. Validate via `sbt "testOnly *HealingFrontierResumeSpec *FrontierRebuildSpec"`.

---

## Phase 4: User Story 2 — See why the walk is slow (Priority: P1)

**Goal**: Cache hit/miss, per-phase timing, GC pressure, and enqueued-vs-distinct inflation are observable
from metrics/logs (SC-003, SC-004), so US3/US4/US6 tune from numbers, not guesses.

**Independent Test**: each new signal is emitted and reflects reality on a synthetic walk; cache gauges read
`None`/absent when statistics disabled.

### Tests for User Story 2 (write first, expect FAIL)

- [x] T015 [P] [US2] `GcPressureSampler` unit test with injected clock + bean-sum supplier, incl. zero-wall-time guard, in `src/test/scala/com/chipprbots/ethereum/blockchain/sync/snap/actors/GcPressureSamplerSpec.scala`
- [x] T016 [P] [US2] `RocksDbDataSource` statistics test: N hits + M misses move `cacheStats` tickers; `cacheStats == None` when flag off; `close()` releases the handle, in `src/test/scala/com/chipprbots/ethereum/db/dataSource/RocksDbDataSourceSpec.scala`
- [ ] T017 [P] [US2] Per-phase timing + inflation counter test on a synthetic walk: three phase-nanos > 0 (once per chunk); `childRefsSeen ≥ distinctEnqueued`; emitted ratio == quotient, in `FrontierRebuildSpec.scala` — SKIPPED (needs a metrics-scrape hook, not faked). The US2 counters (`queueReadNanos`/`trieReadNanos`/`queueWriteNanos`, `childRefsSeen`/`distinctEnqueued`) are LOCAL to `rebuildFrontierBFS`; they surface only as JVM-global, per-level, last-write-wins `SNAPSyncMetrics` gauges that (a) reset at each level boundary so the final value is the deepest level's, not a walk aggregate; (b) round sub-millisecond phase timings to 0 on a tiny deterministic fixture (so "ms > 0" is not reliably assertable); (c) expose only the inflation *ratio*, never `childRefsSeen`/`distinctEnqueued` individually; and (d) are contaminated by every other walk in the same JVM/spec (the busy `TrieNodeHealingCoordinatorSpec` runs many). A faithful, deterministic assertion needs a dedicated test seam — a per-walk *aggregate* gauge (max/last inflation observed) or a hook exposing the raw counters — which does not exist yet. The setter→registry round-trip is already covered by `SNAPSyncMetricsSpec` (T019); the instrumentation's observation-only nature (counters increment beside unchanged `markIfNew`/enqueue logic) is verified by review (T025) and by T009/T027 showing the frontier is unchanged.
- [ ] T018 [P] [US2] Observation-only regression: counters/timing do NOT change the frontier/enqueued-children set for a fixed trie (FR-023/SC-006), in `FrontierRebuildSpec.scala` — SKIPPED for the same reason as T017 (no metrics-scrape hook exposing per-walk aggregate counters). The observation-only guarantee is established indirectly: T009 (shared-ancestor discovered exactly once) and T027 (cfg=1 frontier identical with/without a reader EC, equal to the fixture's known missing count) both pass with the counters present, demonstrating the instrumentation does not perturb the frontier/enqueued set.
- [x] T019 [P] [US2] `SNAPSyncMetrics` setter round-trip via `SimpleMeterRegistry` for the new gauges, in `src/test/scala/com/chipprbots/ethereum/blockchain/sync/snap/SNAPSyncMetricsSpec.scala`

### Implementation for User Story 2

- [x] T020 [P] [US2] New `GcPressureSampler` (baseline `getCollectionTime` sum + wall clock; `sample(): (deltaPauseMs, fraction)`; injectable clock/bean supplier), in `src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/actors/GcPressureSampler.scala`
- [x] T021 [P] [US2] Add `enableStatistics: Boolean = false` to the `RocksDbConfig` trait; read `enable-statistics` (hasPath-guarded) in `InstanceConfig` `object RocksDb`, in `RocksDbDataSource.scala` + `src/main/scala/com/chipprbots/ethereum/utils/InstanceConfig.scala`
- [x] T022 [US2] In `RocksDbDataSource.createDB`, attach a `Statistics` (`StatsLevel.EXCEPT_DETAILED_TIMERS`) when enabled, retain as an `Option` field, add `cacheStats: Option[(hit,miss,idxHit,idxMiss)]`, close in `close()`, in `RocksDbDataSource.scala` (depends on T021). NOTE: `cacheStats` returns the four block-cache tickers `(BLOCK_CACHE_HIT, BLOCK_CACHE_MISS, BLOCK_CACHE_INDEX_HIT+FILTER_HIT, BLOCK_CACHE_INDEX_MISS+FILTER_MISS)`; the optional `estimate-table-readers-mem` long-property was not bundled into the tuple (it is a memory gauge, not a hit/miss ticker, and is independently available via `getLongProperty` if a future poll gauge needs it).
- [x] T023 [US2] Add `enable-statistics = false` under `db.rocksdb` with a ~1-2% overhead comment, in `src/main/resources/conf/base/db.conf`
- [x] T024 [P] [US2] Add SNAPSyncMetrics gauges + setters: `setHealingPhaseQueueReadMs/TrieReadMs/QueueWriteMs`, `setHealingGcPauseMs`, `setHealingGcFraction`, `setHealingInflationRatio`, in `src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/SNAPSyncMetrics.scala`. NOTE: the poll-closure RocksDB cache gauges were NOT registered in this static object — they require a live `RocksDbDataSource` reference (the state datasource) to sample at scrape time, which the static `SNAPSyncMetrics` object does not hold. `cacheStats` (T022) is the data source; wiring it to a poll gauge needs datasource self-registration plumbing out of scope for T024. Deferred to the dashboard/polish phase.
- [x] T025 [US2] In `rebuildFrontierBFS`/`processSubRange`: add per-walk `queueReadNanos/trieReadNanos/queueWriteNanos` + per-level `childRefsSeen/distinctEnqueued`; time the three ops per chunk; increment `childRefsSeen` before each `markIfNew` and `distinctEnqueued` in its true branch; emit all five to T024 setters + the GC sampler at the level boundary and append to the `[HEAL-BFS] Level complete` log; reset per-level counters, in `TrieNodeHealingCoordinator.scala` (depends on T020, T024; same file as US1 → after T010–T013). Observation-only: the `markIfNew`/enqueue logic is byte-for-byte unchanged.

**Checkpoint**: an operator can read off whether a slow walk is cache-, GC-, or disk-bound, and the inflation ratio is a real number. Validate via `sbt "testOnly *GcPressureSamplerSpec *SNAPSyncMetricsSpec *RocksDbDataSourceSpec *FrontierRebuildSpec"`.

---

## Phase 5: User Story 3 — Use available CPU/IO headroom (Priority: P2)

**Goal**: On hosts with spare cores/disk, the walk scales past 2 serial readers (SC-002), without regressing a
CPU-bound host below the serial baseline (FR-012) and without breaking the single-walk/non-overlap invariants.

**Independent Test**: parallelism formula clamps correctly; `cfg=1` is byte-identical to the serial path;
pipelined output equals serial; no Await deadlock at pool size; shared-ancestor completeness holds concurrently.

### Tests for User Story 3 (write first, expect FAIL)

- [x] T026 [P] [US3] Parallelism-formula unit test: `min(cfg, min(nproc, max(min, nproc − reserved)))` across tuples (4-core ⇒ 2; 16-core ⇒ cfg; `cfg=1` ⇒ 1; min above nproc clamped to nproc; reserved ≥ nproc ⇒ min floor), in `TrieNodeHealingCoordinatorSpec.scala`. The inline formula in `startFrontierBFS` was extracted to a PURE companion method `computeEffectiveParallelism(traversalParallelism, availableProcessors, minParallelism, reservedCores)` (byte-identical; `forge`-reviewed as a non-consensus testability refactor) and the call site rewired to it; 5 tuple cases.
- [x] T027 [P] [US3] Serial-equivalence: with `cfg=1` the emitted frontier (the pendingTasks set) over a fixed multi-node trie is identical to a reference run — and identical whether or not a reader EC is supplied (FR-012). Implemented in `TrieNodeHealingCoordinatorSpec.scala` (the TestKit home; drives the coordinator and reads `pendingTasks` via `HealingGetProgress`, the externally-observable frontier). Asserts both runs equal the fixture's known missing-node count (2).
- [ ] T028 [P] [US3] Pipeline = serial equivalence: pipelined `processSubRange` returns the same frontier set and enqueued-children set as the serial loop (stub mptStorage records call order), in `FrontierRebuildSpec.scala` — deferred with T035 (pipeline). T035 (the bounded 2-stage prefetch pipeline) was DEFERRED, so there is no pipeline path to assert equivalence against; the current `processSubRange` is the serial chunk loop, covered by T027.
- [x] T029 [P] [US3] Starvation/deadlock guard: a level spawning multiple sub-ranges completes within a bounded TestKit timeout (no Await deadlock — forge's reader-EC-distinct-from-writer-EC fix). Implemented in `TrieNodeHealingCoordinatorSpec.scala`: drives the `wideFrontierLevel` fixture (level-4 frontier 53,248 > `BfsChunkSize`=50,000) with `traversalParallelism=2` + a real reader EC distinct from the writer EC so the parallel-split branch is GENUINELY taken; asserts the full frontier lands within 60s (a deadlock would never reach it). Note `BfsChunkSize` is a hardcoded companion val (not props-tunable), so this is the minimum trie that actually crosses the split threshold — a sub-50K synthetic trie never exercises the parallel-Await path.
- [ ] T030 [P] [US3] Bounded-heap + backpressure: peak buffered chunks ≤ handoff-queue capacity and `awaitFrontierDrain` (#1338) still invoked per batch, in `FrontierRebuildSpec.scala` — deferred with T035 (pipeline). The "handoff-queue capacity / peak buffered chunks" bound is a property of the deferred 2-stage pipeline (T035); without that pipeline there is no chunk handoff buffer to bound. `awaitFrontierDrain`-per-batch is exercised by the existing backpressure deadlock test in `TrieNodeHealingCoordinatorSpec.scala`.
- [x] T031 [P] [US3] Shared-ancestor completeness under concurrency: the missing node behind a shared ancestor is still discovered with parallel readers configured (FR-025). Implemented in `TrieNodeHealingCoordinatorSpec.scala`: drives the `wideSharedAncestor` fixture with `traversalParallelism=2` + a real reader EC and asserts `pendingTasks == 1`. (The original "non-overlap key invariant property test" framing assumes the deferred pipeline/parallel-enqueue path; with a tiny synthetic trie the physical split does not fire — the genuine parallel-split path is proven by T029, and the FR-025 invariant under the concurrency *configuration* is asserted here, per the task's documented fold.)

### Implementation for User Story 3

- [x] T032 [US3] Add a dedicated `healing-reader-dispatcher` (fixed pool sized to the parallelism ceiling, default 4–6), in `src/main/resources/conf/base/pekko.conf`
- [x] T033 [US3] Run the sub-range Futures on the new reader EC (or wrap the parent `Await.result` in `scala.concurrent.blocking { }`) to remove the Await-on-same-pool starvation hazard, in `TrieNodeHealingCoordinator.scala` (do FIRST — blocking prerequisite for raising parallelism)
- [x] T034 [US3] Replace `effectiveParallelism` with `min(cfg, max(healingMinParallelism, nproc − healingReservedCores))`; never exceed `cfg`/`nproc`; `cfg=1` ⇒ serial, in `TrieNodeHealingCoordinator.scala` (depends on T033)
- [ ] T035 [US3] Convert `processSubRange`'s serial chunk loop into a bounded 2-stage pipeline (prefetch chunk k+1's `multiGetNodes` while decoding/expanding chunk k; bounded 1–2-chunk handoff); leave `markIfNew`/frontier accounting and the non-overlap key invariant unchanged, in `TrieNodeHealingCoordinator.scala` (depends on T033; same file as T025 → sequence after it)
- [x] T036 [US3] Add `healing-min-parallelism = 2` and `healing-reserved-cores = 2` with the effective-parallelism + pipeline-depth doc note (4-core ref host stays at 2), in `src/main/resources/conf/base/sync.conf`

**Checkpoint**: on a ≥4-free-core host throughput ≥ 2× the serial baseline; on the 4-core host the pipeline raises SSD queue depth; serial baseline never regresses.

---

## Phase 6: User Story 4 — Tunable resource limits (Priority: P2)

**Goal**: Operators raise the open-file limit and block-cache size via config (already wired), guided by US2
metrics, without exceeding the memory budget (SC-005).

**Independent Test**: a RocksDbDataSource opens at raised values without error; defaults keep the reference host within its limit.

### Tests for User Story 4 (write first, expect FAIL)

- [x] T037 [P] [US4] Test: open a `RocksDbDataSource` with raised `maxOpenFiles`/`blockCacheSize` (inline `RocksDbConfig`) without error (FR-013/FR-014), in `src/test/scala/com/chipprbots/ethereum/db/storage/BfsQueueStorageSpec.scala` (reuse the `withRocksDb` harness)

### Implementation for User Story 4

- [x] T038 [US4] Document in `db.conf`: raise `max-open-files` to ≥ the on-disk SST count (or `-1` unlimited); raise `block-cache-size` ONLY on low US2 hit rate; warn the block cache is OFF-HEAP and counts against the container memory cgroup (FR-015/SC-005). No default value change, in `src/main/resources/conf/base/db.conf`

**Checkpoint**: knobs documented with safe ranges and the memory guardrail; no code plumbing needed (fields already wired).

---

## Phase 7: User Story 5 — Forward-scan queue read / H1 (Priority: P3)

**Goal**: The dense, sequential BFS queue is read via a forward scan, with identical results (AS5.1) and no
iterator leak on abort (FR-018).

**Independent Test**: `scanRange` round-trip equals `multiGetOptimized` over the same window; half-open
boundaries + unsigned ordering; Ephem parity (sorted); no open-iterator error on abort.

### Tests for User Story 5 (write first, expect FAIL)

- [x] T039 [P] [US5] `scanRange` round-trip on RocksDb equals `multiGetOptimized` over the same window, in `BfsQueueStorageSpec.scala`
- [x] T040 [P] [US5] `scanRange` half-open boundaries (`[k,k)` empty; `toExclusive` excluded) + unsigned ordering for high-byte (≥0x80) keys, in `BfsQueueStorageSpec.scala`
- [x] T041 [P] [US5] `EphemDataSource.scanRange` parity: namespace isolation, range bounds, sorted output (FR-017), in `BfsQueueStorageSpec.scala`
- [x] T042 [P] [US5] `iterateRange` via `scanRange` equals the old path across chunk sizes incl. chunk-splitting (AS5.1/AS5.2), in `BfsQueueStorageSpec.scala`
- [x] T043 [P] [US5] No-leak on abort: consume only the first chunk then drop the outer iterator; DB closes cleanly (FR-018), in `BfsQueueStorageSpec.scala`

### Implementation for User Story 5

- [x] T044 [US5] Add abstract `def scanRange(namespace, fromKey, toKeyExclusive): Iterator[(Array[Byte],Array[Byte])]` (forward, half-open, unsigned-lexicographic) with scaladoc on ordering/boundary/close contract, in `src/main/scala/com/chipprbots/ethereum/db/dataSource/DataSource.scala`
- [x] T045 [P] [US5] Implement `scanRange` in `RocksDbDataSource` via `db.newIterator(handle, scanReadOptions)` + `seek` + drain `[from,to)` with `Arrays.compareUnsigned`, closing the iterator + releasing the read lock in `try/finally` (no `return`/`finalize`), in `RocksDbDataSource.scala` (depends on T044)
- [x] T046 [P] [US5] Implement `scanRange` in `EphemDataSource` by filtering the in-memory map on namespace + `[from,to)` and emitting SORTED `(suffix-key, value)` pairs (reuse the `deleteRange` comparator), in `src/main/scala/com/chipprbots/ethereum/db/dataSource/EphemDataSource.scala` (depends on T044)
- [x] T047 [US5] Rewrite `RocksDbBfsQueueStorage.iterateRange` to chunk `dataSource.scanRange` (decode each value to `BfsEntry`); keep the outer `Iterator[Seq[BfsEntry]]` and O(chunkSize) memory, in `src/main/scala/com/chipprbots/ethereum/db/storage/BfsQueueStorage.scala` (depends on T045, T046)

**Checkpoint**: queue reads use the forward scan with identical results; banked efficiency win.

---

## Phase 8: User Story 6 — Honest visited-set accounting (Priority: P3)

**Goal**: The visited set is named/documented accurately (FIFO, ~120–150B/entry), and any cap change is
justified by the US2 inflation metric, never raised to a value that risks OOM at 6g.

**Independent Test**: code review confirms accurate naming/cost; with the US2 metric, a cap change moves the
ratio as expected and stays within the heap budget.

### Implementation for User Story 6

- [x] T048 [US6] Correct the "LRU" naming → FIFO/insertion-order and the per-entry cost (~120–150B with ByteString, not 80B) in the visited-set comments + `DefaultVisitedCap` docstring; note eviction protects no recently-touched node, in `TrieNodeHealingCoordinator.scala` (same file as US1/US2/US3 → sequence after them)
- [x] T049 [US6] Document the cap-tuning procedure (raise only on a measured `inflation_ratio`, within the heap budget; do NOT set 20M) next to the `healing-visited-cap` key, in `src/main/resources/conf/base/sync.conf`

**Checkpoint**: naming/docs honest; cap is evidence-tuned via US2's metric.

---

## Phase 9: User Story 7 — Basic-pruning batched read (Priority: P3)

**Goal**: A basic-pruning node batches trie-node reads during the walk (FR-021), byte-identical to per-key
(FR-022/SC-006). Defensive — does not affect the archive production node.

**Independent Test**: `multiGet == keys.map(get)` element-for-element incl. `None`; one batched DB call; archive vs basic identical bytes.

### Tests for User Story 7 (write first, expect FAIL)

- [x] T050 [P] [US7] `ReferenceCountNodeStorage.multiGet(keys) == keys.map(get)` (incl. `None` for misses), resolving to one batched datasource call, in `src/test/scala/com/chipprbots/ethereum/db/storage/ReferenceCountNodeStorageSpec.scala`
- [x] T051 [P] [US7] `FastSyncNodeStorage` inherits the batched `multiGet` with an identical unwrap; archive vs basic return identical decoded bytes for the same node set (SC-006), in `ReferenceCountNodeStorageSpec.scala`

### Implementation for User Story 7

- [x] T052 [P] [US7] Add `override def multiGet` to `ReferenceCountNodeStorage` (`nodeStorage.multiGet` → same `storedNodeFromBytes(_).nodeEncoded.toArray` unwrap as per-key `get`), in `src/main/scala/com/chipprbots/ethereum/db/storage/ReferenceCountNodeStorage.scala`
- [x] T053 [P] [US7] (optional) Add `override def multiGet` to `ReadOnlyNodeStorage` honoring the in-memory buffer first then batching misses, preserving order/buffer-shadows semantics, in `src/main/scala/com/chipprbots/ethereum/db/storage/ReadOnlyNodeStorage.scala`

**Checkpoint**: basic-pruning nodes no longer degrade to 50K serial gets; results byte-identical.

---

## Phase 10: Polish & Cross-Cutting Concerns

- [x] T054 [P] Update the operator metrics reference with the new `app_snapsync.healing.*` and `app_db.rocksdb.block_cache.*` series, in `docs/operations/metrics-reference.md`
- [x] T055 [P] Add the new gauges to the SNAP-sync Grafana dashboard (phase timing, GC, inflation ratio, cache hit/miss), in `ops/grafana/Sync/fukuii-snap-sync.json`
- [ ] T056 `eye` validation: run the targeted test suites for each story and confirm pass/fail with evidence; on a large Mordor/disposable trie, capture before/after walk timing + the new per-phase/inflation numbers (node STOPPED per the freeze warning)
- [ ] T057 Run `sbt scalafmtAll` then `sbt pp` (compile-all → scalafmt → fast + integration tests); resolve all findings
- [ ] T058 Run `specs/002-bfs-heal-performance/quickstart.md` validation end-to-end and record results

---

## Dependencies & Execution Order

### Phase dependencies

- **Setup (P1)** → no deps.
- **Foundational (P2)** → after Setup; blocks US1 & US3 test tasks (T005–T009, T026–T031).
- **User Stories (P3+)** → after Foundational. US1 is the MVP and should land first (it unblocks the live node). US2 should land before US3/US4/US6 so tuning uses measured numbers. US4/US5/US6/US7 are independent of each other.
- **Polish (P10)** → after the desired stories.

### Story dependencies

- **US1 (P1)**: independent. Ships the live-node unblocker. `forge` review (T_FORGE) + FR-025 (T009) gate merge.
- **US2 (P1)**: independent; its inflation metric feeds US6's tuning decision.
- **US3 (P2)**: independent; **T033 (starvation fix) MUST precede T034/T035** (raising parallelism on the shared pool would deadlock).
- **US4 (P2)**: independent (doc + one test).
- **US5 (P3)**: independent; T044 (trait) before T045/T046 (impls) before T047 (consumer).
- **US6 (P3)**: depends on US2's metric existing for the tuning rationale (the naming/doc edits do not).
- **US7 (P3)**: independent.

### Same-file sequencing (NOT parallel)

`TrieNodeHealingCoordinator.scala` is touched by T004, T010–T013, T025, T033–T035, T048 — these MUST be
sequential (one file). Order: T004 → T010–T013 (US1) → T025 (US2) → T033 → T034 → T035 (US3) → T048 (US6).
`db.conf` is touched by T023 (US2) and T038 (US4) — sequence them. `sync.conf` by T036 (US3) and T049 (US6).

### Parallel opportunities

- US1 tests T005–T009 are all `[P]` (distinct test methods/files); same for US2 T015–T019, US3 T026–T031, US5 T039–T043, US7 T050–T051.
- Cross-story implementation in DIFFERENT files can parallelize once Foundational is done: e.g. T020 (GcPressureSampler), T044–T046 (scanRange in DataSource/RocksDb/Ephem), T052 (ReferenceCountNodeStorage), T024 (SNAPSyncMetrics) — different files, no shared-file conflict.

---

## Parallel Example: User Story 1 tests

```bash
sbt "testOnly *HealingFrontierResumeSpec *FrontierRebuildSpec"
# T005 marker-set-on-verification, T006 same-root-refresh, T007 skip-on-restart,
# T008 idle-arm-negative + incomplete-no-skip, T009 shared-ancestor discovery
```

---

## Implementation Strategy

### MVP first (US1 only)

1. Phase 1 Setup → Phase 2 Foundational → Phase 3 US1 (T005–T014 + T_FORGE).
2. **STOP and VALIDATE**: healed trie skips the walk on restart; incomplete trie still walks; shared-ancestor
   node still found. This is the change that, on the live node's next controlled restart, avoids the ~17h walk
   entirely — the highest-leverage deliverable.

### Incremental delivery

US1 (MVP, live-node unblocker) → US2 (observability; enables evidence-based tuning) → US3 (throughput on
capable hosts) → US4 (config) → US5 (forward-scan) → US6 (visited-set accounting, tuned from US2) → US7
(basic-pruning defensive). Each story is independently testable and adds value without breaking the prior.

### Notes

- `[P]` = different file, no incomplete dependency. Same-file tasks are sequential (see Same-file sequencing).
- Consensus-adjacent US1 requires `forge` review + the FR-025 regression test before merge; the Bloom filter
  stays rejected (FR-024).
- Commit per task or logical group; conventional prefixes; link the spec/ADR.
- Validate each story at its checkpoint with the targeted `sbt testOnly` suites (node STOPPED).
