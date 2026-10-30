> Closed 2026-10-30 as Superseded by 022; boxes below are as they were at closure.

# Tasks: Scoped Post-Heal Verification

**Input**: Design documents from `/specs/003-scoped-heal-verification/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/internal-interfaces.md, quickstart.md

**Tests**: INCLUDED. Constitution Principle III requires deterministic tests for behavioral changes, and this is consensus-*adjacent* (the completion gate that lets the node trust its state) — the byte-parity invariant (FR-007) and scope-capture completeness (V1) MUST be test-pinned. Tests use Pekko TestKit, no `Thread.sleep`.

**Organization**: Tasks grouped by user story (US1/US2 are both P1 and together form the MVP — a scoped path is only shippable with its fallback; US3 is P2). All code is in the `node` (main) module.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: parallelizable (different files, no dependency on incomplete tasks)
- **[Story]**: US1/US2/US3 (story-phase tasks only)
- File paths are relative to repo root.

## Path Conventions

- Coordinator: `src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/actors/TrieNodeHealingCoordinator.scala` (`TNHC`)
- Controller/config: `src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/SNAPSyncController.scala`
- Metrics: `src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/SNAPSyncMetrics.scala`
- Config: `src/main/resources/conf/base/sync.conf`
- Tests: `src/test/scala/com/chipprbots/ethereum/blockchain/sync/snap/...`

---

## Phase 1: Setup (config scaffolding)

- [X] T001 Add `scoped-heal-verification = true` and `scoped-heal-max-paths = 200000` keys (with explanatory comments mirroring `heal-hold-pivot-on-stagnation`) to the `snap-sync` block in `src/main/resources/conf/base/sync.conf` (per contract C5).
- [X] T002 Add `scopedHealVerification: Boolean = true` and `scopedHealMaxPaths: Int = 200000` to the `SNAPSyncConfig` case class, parse them with the `hasPath`-guarded idiom in `SNAPSyncConfig.fromConfig`, and thread both into `TrieNodeHealingCoordinator.props`/constructor beside `healingTraversalParallelism`, in `src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/SNAPSyncController.scala` (contract C5).

---

## Phase 2: Foundational (blocking prerequisites for US1 + US2)

**⚠️ MUST complete before any user-story phase.**

- [X] T003 Add the healed-paths accumulator fields to `TNHC` — `healedPathsThisRound: mutable.LinkedHashMap[ByteString, HealingEntry]`, `healedPathsRoot: ByteString`, `healedPathsOverflowed: Boolean` — mirroring `pendingTasks`/`pendingHashSet`, in `TrieNodeHealingCoordinator.scala` (contract C1; data-model.md §Healed-paths set).
- [X] T004 Capture each healed node's `HealingEntry` into `healedPathsThisRound` at the single heal site in `TNHC.handleResponse` (immediately after `totalNodesHealed += 1`, ~`:1081`): tag `healedPathsRoot = stateRoot` on first capture of the round, dedup by hash, and set `healedPathsOverflowed` when size would exceed `scopedHealMaxPaths`, in `TrieNodeHealingCoordinator.scala` (contract C1).
- [X] T005 Clear the healed-paths set (`empty`, `healedPathsOverflowed = false`) at the three reset sites — differing-root `HealingPivotRefreshed` (~`:599`), `HealingForceComplete` (~`:576`), and after a verified `StateHealingComplete` (~`:731`); do NOT clear on same-root `HealingPivotRefreshed` — in `TrieNodeHealingCoordinator.scala` (contract C1).
- [X] T006 Add the multi-seed `rebuildFrontierBFS(seeds: Seq[(ByteString, Seq[ByteString], Boolean)], ...)` overload as the kernel and reduce the existing single-seed signature to a thin wrapper that calls it with one seed; the ONLY kernel deltas are seeding `markIfNew` and `enqueueBatch` over `seeds` (contract C2) — in `TrieNodeHealingCoordinator.scala`. Must be byte-identical for a single seed.

---

## Phase 3: User Story 1 — Heal completes in seconds, not a second full-day walk (Priority: P1)

**Goal**: When full-trie coverage is durably proven and a small set of nodes was healed, the post-heal verification re-walks only the healed subtrees and reaches `StateHealingComplete` in seconds.

**Independent test**: With the CF `g` completeness marker set and a small clean healed set, the completion gate seeds the BFS from the healed paths (not the root) and completes far faster than a full-root walk, with identical resulting state.

- [X] T007 [US1] Add `startScopedVerification(seeds: Seq[HealingEntry])` — sibling of `startVerificationBFS` — mapping each entry to `(hash, pathset, pathset.size > 1)`, launching the multi-seed walk on `healingWriterEc` via `startFrontierBFS` plumbing, reusing `verificationBFSRunning`, the shared `bfsQueue`, and routing completion to the existing `VerificationBFSComplete` handler and exceptions to `FrontierWalkFailed`, in `TrieNodeHealingCoordinator.scala` (contract C3).
- [X] T008 [US1] In `TNHC.HealingCheckCompletion` (~`:732-742`), add the `useScoped` decision and call `startScopedVerification(healedPathsThisRound.values.toSeq)` on the scoped branch (full predicate completed in T013); leave the completion declaration (`:715-731`) untouched so completion flows through the single `verificationPassComplete` chokepoint, in `TrieNodeHealingCoordinator.scala` (contract C4).
- [X] T009 [P] [US1] Test: scope-capture completeness (V1) — drive N heals, assert `healedPathsThisRound` equals exactly those N `(hash, pathset)` pairs (dedup, no skip), in a new `TrieNodeHealingScopeCaptureSpec.scala`.
- [X] T010 [P] [US1] Test: scoped completion (V2) — marker set + small clean healed set → asserts BFS seeded from healed paths, only those subtrees visited, reaches `StateHealingComplete`, in `TrieNodeHealingScopedVerificationSpec.scala`.
- [X] T011 [P] [US1] Test: gap below a healed node (V3 / FR-006) — healed node with a deeper missing descendant → scoped walk discovers it, emits `FrontierRebuilt`, does NOT complete until clean, in `TrieNodeHealingScopedVerificationSpec.scala`.
- [X] T012 [P] [US1] Test: multi-seed single-element byte-parity (C2) — assert the single-seed wrapper and a one-element multi-seed call produce identical visited set / frontier emission, in `RebuildFrontierBfsMultiSeedSpec.scala`.

**Checkpoint**: US1 demonstrable — scoped verification engages and completes on a covered, small-gap node.

---

## Phase 4: User Story 2 — Never declare complete with an unverified gap (Priority: P1)

**Goal**: The scoped path is provably as safe as the full walk — it falls back to full-root verification in every case where full-trie coverage isn't durably proven, and reaches a byte-identical completion.

**Independent test**: For each fallback clause (disabled / no-marker / empty-or-restart / over-bound / pivot-changed / fresh), the gate takes the full-root path; and scoped-vs-full-root completion yields an identical state root + marker.

- [X] T013 [US2] Implement the full 5-condition `useScoped` predicate in `TNHC.HealingCheckCompletion` — `scopedHealVerification` (F1) ∧ `healingFrontierStorage.exists(_.isComplete)` (F2/F6) ∧ `healedPathsThisRound.nonEmpty` (F3) ∧ `!healedPathsOverflowed` (F4) ∧ `healedPathsRoot == stateRoot` (F5); `else` keeps the unchanged `startVerificationBFS(stateRoot, emptyPath)` full-root fallback (contract C4, FR-004/005/009/011).
- [X] T014 [P] [US2] Test: byte-parity (V4 / FR-007 / SC-004) — same healed state reaches completion via scoped and via full-root (config flip); assert identical final state root AND identical CF `g` completeness-marker bytes, in `ScopedVerificationParitySpec.scala`.
- [X] T015 [P] [US2] Test: fallback clauses F1-F6 (V5 / SC-003) — each unsafe condition asserts the full-root branch is taken and `startScopedVerification` is NOT called, in `ScopedVerificationFallbackSpec.scala`.

**Checkpoint**: US1+US2 = the safe MVP — scoped when proven, full-root otherwise, identical outcomes.

---

## Phase 5: User Story 3 — Operators can see and control the scoped path (Priority: P2)

**Goal**: Operators can confirm the scoped path engaged and how much it saved, and force full-root via config.

**Independent test**: A scoped run emits the engagement log + `app_snapsync.healing.scoped_*` metrics; disabling the config flag yields the full-root path with a "scoping disabled" log.

- [X] T016 [US3] Add the `[HEAL-VERIFY-SCOPED]` engagement + completion logs and the additive `SNAPSyncMetrics` gauges (`scoped_verification` 0/1, `scoped_subtrees`, `scoped_duration_ms`, `app_`-prefixed) on scoped entry and on `VerificationBFSComplete` for a scoped run; set the gauge to 0 on the full-root path — in `TrieNodeHealingCoordinator.scala` and `SNAPSyncMetrics.scala` (contract C6, FR-010).
- [X] T017 [US3] Emit the once-per-round "scoped verification disabled by config — using full-root verification" log when the fallback engages specifically because `scopedHealVerification == false`, in `TrieNodeHealingCoordinator.scala` (contract C5, US3 AS2).
- [X] T018 [P] [US3] Test: observability (V6) — scoped path emits the engagement log + moves the gauges; disabled path emits the "scoping disabled" log and the full-root walk, in `ScopedVerificationObservabilitySpec.scala`.

**Checkpoint**: full feature — fast, safe, observable, controllable.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [ ] T019 [P] Validate `scoped-heal-max-paths = 200000` against the 6 GB reference-host heap with a deliberately over-bound round (N2); adjust the default if the bound risks heap pressure, documenting the result in research.md.
- [ ] T020 `forge` review of the consensus-adjacent surfaces (C2 multi-seed parity, C3 launcher, C4 gate/no-new-marker-site) — byte-parity sign-off against the full-root path before merge.
- [ ] T021 Run `sbt pp` (compile-all → scalafmt → scalafix → Tier-1 + Tier-2) green on CI/idle host (NOT while the barad-dûr node is active); `eye` validation; confirm ≥70% statement coverage on the new code.
- [ ] T022 [P] Update code comments + spec.md `Status` to reflect implementation; record N1 (persist the healed-paths set so a restart-mid-verification can resume scoped rather than fall to full-root) as a deferred follow-up in research.md.

---

## Dependencies & Execution Order

- **Setup (T001-T002)** → **Foundational (T003-T006)** → **US1 (T007-T012)** → **US2 (T013-T015)** → **US3 (T016-T018)** → **Polish (T019-T022)**.
- US2 depends on US1 (shares the `HealingCheckCompletion` predicate + `startScopedVerification`). US3 depends on US1/US2 (it instruments both paths).
- Foundational T003→T004→T005 are sequential (same fields/file); T006 is independent of T003-T005 (different method) and may proceed in parallel with them.
- Within a phase, `[P]` tasks are test files (distinct files, no shared edits) and may run in parallel; non-`[P]` implementation tasks edit the shared `TrieNodeHealingCoordinator.scala` and are sequential.

## Parallel Execution Examples

- US1 tests together once T007-T008 land: `T009`, `T010`, `T011`, `T012` (four separate spec files).
- US2 tests together once T013 lands: `T014`, `T015`.
- Polish: `T019` and `T022` in parallel; `T020`/`T021` gate the merge.

## Implementation Strategy

- **MVP = US1 + US2** (both P1). A scoped fast-path is only shippable with its total full-root fallback and the byte-parity guarantee; ship them together, behind `scoped-heal-verification` (default on, instantly revertable to full-root).
- **Increment = US3** (observability/control) — adds confidence and operability; not required for correctness.
- Land Foundational + US1 + US2, get `forge` byte-parity sign-off (T020) and green `sbt pp` (T021) BEFORE merge; deploy to the barad-dûr node only at a clean boundary (after the current heal completes) per the live-deploy discipline.
