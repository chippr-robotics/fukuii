---
description: "Task list for Post-SNAP Healing Frontier-Rebuild Scalability"
---

# Tasks: Post-SNAP Healing Frontier-Rebuild Scalability

**Input**: Design documents from `/specs/001-healing-frontier-scale/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md

**Tests**: INCLUDED — behavioral changes require deterministic tests (Constitution Principle III);
quickstart.md enumerates the test recipes.

**Organization**: Tasks are grouped by user story (= the three layers) for independent
implementation and delivery. **Layer 1 (US1) is the MVP and its bounded-set core is already
implemented** in commit `d04b24703`; US1's remaining work is config-tunability + tests + validation.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: US1 / US2 / US3 (Setup, Foundational, Polish have no story label)
- Exact file paths are in each task.

## ⚠️ Shared-file sequencing (read before parallelizing)

US1 and US2 both edit three shared files — tasks touching them are **sequential**, never `[P]`:

- `src/main/resources/conf/base/sync.conf` — T005 (US1) then T013 (US2)
- `src/main/scala/com/chipprbots/ethereum/utils/Config.scala` — T006 (US1) then T014 (US2)
- `src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/actors/TrieNodeHealingCoordinator.scala`
  — T007 (US1) then T015/T017/T018/T019 (US2) then T022 (US3)

⚠️ **Do NOT run `sbt testEssential`/`testStandard`/`pp` while Barad-dûr nodes are active** (freezes
the host; project memory). Prefer targeted `sbt "testOnly *Spec"` during the loop.

---

## Phase 1: Setup & Baseline

**Purpose**: Confirm the starting point is green before changing anything.

- [X] T001 Establish baseline on `fix/healing-frontier-scale`: ran `sbt "testOnly *TrieNodeHealingCoordinatorSpec"` (21/21 PASS) — baseline green; the Layer-1 draft compiles.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Shared groundwork. Minimal here — US1's core is already implemented and US2 is additive.

- [X] T002 Confirmed the shared extension points. **Reconciliation:** the SNAP/healing config does **not** live in `Config.scala` — it is the `SNAPSyncConfig` case class + `fromConfig` in `src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/SNAPSyncController.scala` (`:4438`/`:4526`), alongside the `snap-sync { }` block in `src/main/resources/conf/base/sync.conf`. US1/US2 both edit `SNAPSyncController.scala` + `sync.conf` (sequential). Confirmed `RocksDbDataSource` opens with `setCreateMissingColumnFamilies(true)` (`RocksDbDataSource.scala:389`) → US2's new CF needs **no migration** (research R6). NOTE: T006/T014 target `SNAPSyncController.SNAPSyncConfig`, not `Config.scala`.

**Checkpoint**: Foundation confirmed — user stories can begin (US1 first as MVP).

---

## Phase 3: User Story 1 — Recover from healing without OOM-looping (Priority: P1) 🎯 MVP

**Goal**: The frontier-rebuild walk completes on 35M+ node state without exiting on OOM; the
bookkeeping cap is operator-tunable.

**Independent Test**: Force a post-SNAP healing restart on large-state (or synthetic >cap) state;
the walk completes, the process does not OOM-exit, and bookkeeping memory stays ≤ the cap, with
every missing node found exactly once.

### Tests for User Story 1 ⚠️ (write first; T003 runs against the already-implemented LRU)

- [X] T003 [P] [US1] Added bounded-walk tests in NEW `src/test/scala/com/chipprbots/ethereum/blockchain/sync/snap/actors/FrontierRebuildSpec.scala` (4 tests, PASS). To keep the eviction contract unit-testable, the LRU factory was extracted to the companion seam `TrieNodeHealingCoordinator.boundedVisitedSet(cap)`. Tests cover: size never exceeds cap across 4×cap inserts (INV-3); insertion-order eviction drops eldest first; set idempotency on re-add; membership. NOTE: the full `rebuildFrontierDFS` completeness-under-eviction **property** (INV-1) needs a synthetic-trie fixture — deferred to the coordinator-level suite; INV-1 is provided by `pendingHashSet` de-dup (independent of the bounded set) and argued in research R1.

### Implementation for User Story 1

- [X] T004 [US1] Bounded `visited` LRU implemented in `TrieNodeHealingCoordinator.scala` (commit `d04b24703`); the LRU construction was refactored into the companion `boundedVisitedSet(cap)` seam and `HealingVisitedCap` now reads the constructor `visitedCap` param.
- [X] T005 [US1] Added `healing-visited-cap = 4000000` (with rationale comment) to the `snap-sync { }` block in `src/main/resources/conf/base/sync.conf`.
- [X] T006 [US1] Added `healingVisitedCap` to `SNAPSyncConfig` (default `actors.TrieNodeHealingCoordinator.DefaultVisitedCap`) + `fromConfig` `hasPath` parse in `src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/SNAPSyncController.scala`. (Plan said `Config.scala`; actual home is `SNAPSyncConfig` — see T002.)
- [X] T007 [US1] Threaded the cap: added `visitedCap: Int = DefaultVisitedCap` to the `TrieNodeHealingCoordinator` constructor + `props`, replaced the hard-coded constant, and passed `visitedCap = snapSyncConfig.healingVisitedCap` at **both** construction sites in `SNAPSyncController.scala` (`:3145`, `:3198`). Compile clean; `*FrontierRebuildSpec` + `*TrieNodeHealingCoordinatorSpec` green (4 + 21).
- [ ] T008 [US1] Operational validation (delegate to `eye` / live node): on a large-state node, force a healing restart and confirm **zero** OOM restarts and walk-bookkeeping heap ≤ ~350 MB (SC-001, SC-002). ⏸ **Requires a live large-state node — not runnable in this session.**

**Checkpoint**: US1 code complete — node is recoverable; cap is operator-tunable; targeted tests green. **MVP shippable** as a `fix:` PR (pending `sbt pp` + T008 operational validation).

---

## Phase 4: User Story 2 — Restart resumes instead of re-walking (Priority: P2)

**Goal**: A restart loads the persisted outstanding frontier and skips the full-state walk
(O(frontier), not O(full state)), with a fail-safe fallback to the full walk.

**Independent Test**: Populate a persisted frontier, restart → resumes without the DFS; corrupt /
remove the frontier → safely falls back to the full walk; disabled flag → Layer-1 parity.

### Tests for User Story 2 ⚠️ (write first, ensure they FAIL before implementation)

- [X] T009 [P] [US2] NEW `src/test/scala/com/chipprbots/ethereum/db/storage/HealingFrontierStorageSpec.scala` (7 tests, PASS). RocksDB-backed (temp dir) because `loadAll` needs bare-key namespace iteration, which `EphemDataSource.iterate` does not provide (it returns namespace-prefixed keys). Covers put/get round-trip, single/multi-segment pathset serialization, remove (+absent no-op), `loadAll` with 32-byte bare keys, empty `loadAll`, and atomic batched upsert+remove.
- [X] T010 [P] [US2] NEW `src/test/scala/com/chipprbots/ethereum/blockchain/sync/snap/actors/HealingFrontierResumeSpec.scala` (4 tests, PASS; Pekko TestKit, no `Thread.sleep`): resume-skips-DFS from a populated CF (FR-007), disabled-flag → DFS parity, empty-CF → DFS fallback (FR-009), and write-on-queue (C1). Fixture stops the actor + drains the resume EC **before** destroying RocksDB (avoids a `loadAll`/`destroy` use-after-free SIGSEGV). NOTE: delete-on-heal-flush (C2) and idempotent resume (FR-010) are covered by T009 storage round-trip + the wiring in T018 + operational T020, rather than simulating a full heal-response round-trip in the actor test.

### Implementation for User Story 2

- [X] T011 [US2] Added `HealingFrontierNamespace = 'g'` and to `nsSeq` in `Namespaces.scala`.
- [X] T012 [US2] Created `HealingFrontierStorage.scala` — `TransactionalKeyValueStorage[ByteString, Seq[ByteString]]`, key = node hash, value = RLP-encoded pathset (`rlp.encode`/`decode[Seq[ByteString]]` via `RLPImplicits.given`), namespace = `Namespaces.HealingFrontierNamespace`. Adds `loadAll()` (drains `storageContent` via `IORuntime.global`; throws on iteration/decode error so the caller falls back).
- [X] T013 [US2] Added `healing-frontier-persistence = false` to the `snap-sync { }` block in `sync.conf`.
- [X] T014 [US2] Added `healingFrontierPersistence: Boolean = false` to `SNAPSyncConfig` + `fromConfig` parse in `SNAPSyncController.scala` (actual config home — see T002).
- [X] T015 [US2] Added `healingFrontierStorage: Option[HealingFrontierStorage] = None` to the `TrieNodeHealingCoordinator` constructor + `props`; `None` ⇒ exact Layer-1 behaviour (existing tests unchanged). Added private `persistFrontier`/`unpersistFrontier`/`clearPersistedFrontier` helpers (all no-ops when `None`).
- [X] T016 [US2] In `SNAPSyncController.scala`, added a shared `private lazy val healingFrontierStorageOpt` (built from `flatSlotStorage.dataSource` when `healingFrontierPersistence`, else `None`) and passed it at **both** coordinator construction sites (`:3145`, `:3198` region).
- [X] T017 [US2] Wired **write-on-queue** at **all three** new-entry sites: `queueNodes` (`:571`), inline child discovery in `discoverMissingChildren` (`:1092`), and the pivot reseed (`:410`). (The re-queue paths at `:357`/`:736`/`:822` reuse already-persisted entries — no hook needed.)
- [X] T018 [US2] Wired **delete-on-heal** in `flushRawNodesSync` and `flushRawNodesAsync` (after the durable node write, never on dispatch — R5). The async delete runs on the healing-writer thread (immutable handle + thread-safe RocksDB).
- [X] T019 [US2] Wired **resume** in the `StartTrieNodeHealing` restart branch: load persisted frontier on the writer EC; non-empty+readable ⇒ `FrontierRebuilt(loaded)` and **skip** the DFS; empty/absent/`NonFatal` read error ⇒ log loudly and fall back to the full DFS (FR-007/FR-009). Also clears the persisted frontier on force-complete (`:378`) and pivot-refresh (`:392`) so a restart never resumes a stale frontier.
- [ ] T020 [US2] Operational validation (delegate to `eye` / live node): restart with a populated frontier → resume in minutes, no full `[HEAL-RESTART-DFS]` walk (SC-004); migration check — pre-Layer-2 datadir opens with the new CF auto-created. ⏸ **Requires a live large-state node — not runnable in this session.**

**Checkpoint**: US2 complete — restart is O(frontier); fail-safe fallback verified; `sbt pp` clean. Shippable as a `feat:` PR.

---

## Phase 5: User Story 3 — First-walk throughput (Priority: P3, deferred/optional)

**Goal**: When a full walk is unavoidable, finish in hours not days. Pursue only if the first walk
is still too slow after US1+US2.

**Independent Test**: Measure DFS nodes/s before vs after; assert material improvement over the
~142 nodes/s baseline and host memory within budget.

- [ ] T021 [US3] Measure baseline first-walk throughput (nodes/s) on a large-state node and decide whether to proceed — **gate**: if L2 makes the full walk a one-time event of acceptable duration, stop here and record that decision in `docs/design/healing-frontier-scale.md`.
- [ ] T022 [US3] (Conditional on T021) Batch sibling-child point-reads via RocksDB `multiGet` in the `rebuildFrontierDFS` read path (`TrieNodeHealingCoordinator.scala:877+`), turning serial point-reads into batched reads (research R8, option 1). ⚠️ shared file.
- [ ] T023 [US3] Throughput + memory-budget validation (delegate to `eye`): material improvement over ~142 nodes/s (SC-005) **and** host memory within budget — no regression into over-subscription (`snap_etc_host_freeze_oversubscription`).

**Checkpoint**: US3 complete (if pursued) — first walk is operationally acceptable.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [X] T024 [P] Updated `docs/design/healing-frontier-scale.md` with an as-built "Status (2026-06-06)" section (L1 done + cap config; L2 done, default-off; L3 gated).
- [X] T025 [P] Added a one-line pointer + topic file `project_healing_frontier_scale.md` to project memory `MEMORY.md`.
- [ ] T026 Run `sbt pp` (compile-all → scalafmt → quick + integration) and resolve all findings (⚠️ not while Barad-dûr node containers are active). Partial: targeted suites green (36 tests); `scalafmtCheckAll` run separately; full `sbt pp` + `scalafix` (needs `scalafixEnable`) still owed before PR.
- [ ] T027 [P] Confirm statement coverage ≥ 70% via `sbt testStandard` (⚠️ not while Barad-dûr node containers are active). Deferred — heavy full-suite run.
- [ ] T028 Run quickstart.md end-to-end validation; `eye` confirms the node transitions to regular sync and imports blocks after healing completes (SC-003). ⏸ Requires a live node.

---

## Dependencies & Execution Order

### Phase dependencies

- **Setup (Phase 1)** → no deps.
- **Foundational (Phase 2)** → after Setup; thin (US1 core already shipped).
- **US1 (Phase 3)** → after Foundational. **MVP.**
- **US2 (Phase 4)** → after Foundational; independent of US1 *functionally*, but shares 3 files with US1 (sequence the shared-file edits — see top).
- **US3 (Phase 5)** → after US1+US2; gated by T021 measurement.
- **Polish (Phase 6)** → after the desired stories.

### Key task dependencies

- T003 → only needs T004 (done) → can start immediately.
- T006 → T007 (cap config before wiring). T005 pairs with T006.
- T011 → T012 → {T015, T016}. T015 → {T016, T017, T018, T019}. T016 → T019.
- T009/T010 (tests) written before US2 impl; expected to FAIL until T011–T019 land.
- T020 after T011–T019. T022 after T021 (gate). T026/T028 after all chosen stories.
- Shared-file serialization: `sync.conf` (T005→T013), `Config.scala` (T006→T014),
  `TrieNodeHealingCoordinator.scala` (T007→T015→T017→T018→T019→T022).

### Parallel opportunities

- T003, T009, T010 are each a **distinct new test file** → `[P]` with each other and with impl.
- T024, T025 (docs) are `[P]`.
- Within US2, T011 and the test files can proceed while T012+ wiring is in flight, but the
  coordinator-edit chain (T015→T017→T018→T019) and the config files are **sequential** (same files).

---

## Parallel Example

```bash
# Test files are independent (different files) — author them together:
Task: "T003 FrontierRebuildSpec.scala (US1 bounded-walk tests)"
Task: "T009 HealingFrontierStorageSpec.scala (US2 storage round-trip)"
Task: "T010 HealingFrontierResumeSpec.scala (US2 resume/fallback/idempotency)"

# Polish docs are independent:
Task: "T024 update docs/design/healing-frontier-scale.md"
Task: "T025 add MEMORY.md pointer"
```

---

## Implementation Strategy

### MVP first (User Story 1)

1. Phase 1 Setup → Phase 2 Foundational.
2. Phase 3 US1: T003 (tests) + T005–T007 (config-tunable cap) on top of the already-shipped LRU (T004), then T008 validation.
3. **STOP and VALIDATE**: node recovers without OOM. Ship the `fix:` PR (Layer 1 + cap config).

### Incremental delivery

1. US1 → recoverable node (MVP, `fix:`).
2. US2 → restart resumes (O(frontier), `feat:`) — separate PR, default `healing-frontier-persistence=false` (ship dark), flip to `true` after T009/T010/T020 pass.
3. US3 → throughput (optional) — only if T021 shows the first walk is still too slow.

### Consensus / validation note

This is sync-mechanism work — `forge` is **not** triggered (no change to trie-node verification,
RLP, state roots, gas, hard forks). The correctness invariant is healing **completeness**; `eye`
validates after each layer (T008, T020, T028) that the node reaches regular sync and imports blocks.
