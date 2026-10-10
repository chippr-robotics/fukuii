---
description: "Task list for spec 016: split SNAPSyncController and the SNAP coordinators into phase modules (#1401)"
---

# Tasks: Split SNAPSyncController and the SNAP Coordinators (spec 016)

**Input**: [spec.md](./spec.md), [plan.md](./plan.md), [research.md](./research.md)

**Tests**: **Required, and they come first.** S0 (pins, goldens and characterization) lands before any code moves.
Move PRs add no assertions and change none (FR-032).

**Organization**: one phase per **slice**, and each slice is one PR. Slices merge in the order shown. A slice is done
only when every item on the per-slice checklist below holds.

## Format: `[ID] [P?] [Slice] Description`

- **[P]**: can run in parallel with the other [P] tasks of the same slice (different files)
- Branch names: `refactor/snap-016-<slice>`, or `test/snap-016-<slice>` for S0
- Line numbers are given as `staging@9a6681cc0 L####`, with post-#1501 anchors in research.md R1. Locate code by
  symbol.

## Per-slice checklist (copy it into every PR body)

- [ ] Links spec 016 and the task IDs. Commit prefix is `test(snap):` or `refactor(snap):`, and the message
  references #1401.
- [ ] No sbt or JVM was run on the soak host. The PR was opened only after **all three** required jobs were green:
  - **"Test and Build (JDK 25, Scala 3.3.8)"** (Tier 1 `testEssential`, the only test tier that runs on staging
    PRs);
  - **`snap-synctest`** (S0e, the SNAP `SyncTest` suites);
  - **`snap-split-verify`** (S0f, run on every commit; required from S0f onward, and S0a–S0e are checked by hand).

  Tier 2 does not run on staging PRs, so no Tier 2 claim is made (research.md R13a).
- [ ] **Per-suite counts pasted.** Total / passed / ignored for every R13 suite, from the Tier 1 job and from
  `snap-synctest`, equal to the T002 baseline, apart from tests the PR adds, which are listed by name.
  - `snap-synctest`: the only failure is task #68 (`SyncControllerSpec:762`), and the job fails if #68 passes or is
    missing. A single re-run of a non-allow-listed failure is allowed under FR-039, with both run links.
  - Tier 1: zero failures, or a single flake re-run under FR-039, with both run links.
- [ ] **Zero assertion changes**: the `snap-split-verify` test-hunk guard is green (plan.md §Move verification
  step 3).
- [ ] S0c golden vectors and S0a/S0b pin tests are untouched. `src/main/resources` is unchanged (also checked by the
  script).
- [ ] Move commits carry a `# moved:` symbol trailer, and the identical-body check passes (§Move verification steps
  1–2). No renames or cleanups in this PR (FR-034).
- [ ] **Module PRs (M2–M11, C-*)**: commit 2 (narrowing) follows commit 1 (move), and both pass `snap-split-verify`.
  The PR body pastes:
  - **(a)** the impl-class mention check, which must be empty after commit 2;
  - **(b)** the name of the new Tier 1 stub test, and the module body it calls that reads **and** writes
    `<Module>State`;
  - **(c)** the script's counts (`<Module>State` members, Api members, capability-trait members) against the last
    recorded value or the research.md R14/R14a baseline, with a justification line for any growth;
  - **(d)** the member-level check output;
  - each `val` → `lazy val`/`def` conversion marked "pure: yes", with its initializer grep (plan.md D1).

  The counts are copied into the routing doc's coupling table.
- [ ] No bug fix. Anything found goes to `.claude/agent-protocols/working-docs/CHASE-QUEUE.md` with a link to the
  slice.
- [ ] Rows in `.claude/agent-protocols/snap-sync.md` are flipped to "target (post-split)" with the real file names.
- [ ] A second specialist has reviewed the PR (the reviewers named in the plan.md slice list). For an ETC-reachable
  slice, `forge` has signed off "no ETC behaviour change".
- [ ] If the slice list asks for it, the Mordor and/or Platåberget restart-across-slice result is recorded. Sepolia is
  never used.

---

## Phase 0 — Characterization (complete)

- [x] T000 Static characterization of the controller on `staging@9a6681cc0`: the var × method and Command × behaviour
  matrices, child ownership, fix pins, the resume ledger, DFS leftovers, module hypotheses and the test inventory.
  Copied into research.md (R0–R13).

## Gate tasks

- [x] T001 Re-check research.md R11 against #1501 **as merged** (`dfa4a8836`, 2026-10-08), and re-measure the R1 sizes
  and the symbol anchors on post-#1501 staging (`d23932524`). Done in this spec PR.
  - The escape policy, `onHeapWatchdogEscape` and the keys `heap-watchdog-ineffective-after` and
    `heap-watchdog-max-pause` were added to R11. All of them map to M9.
  - Metric names were corrected to their `.gauge` suffix.
  - No module boundary moved.
- [ ] T001b Carry the merged #1501 into the S0 slices:
  - **S0b**: every `ChildFactories` function takes the merged `apply` argument lists, including
    `intakeBudget: Option[SnapIntakeBudget]`.
  - **S0c**: generate the goldens on post-#1501 staging, then run the same golden suite on a throwaway CI branch at
    `9a6681cc0` (pre-#1501). The two must agree byte for byte, and both run links are recorded in the S0c PR. That
    is the evidence that #1501 left the formats unchanged.
- [ ] T002 Record the baseline from the first `snap-synctest` (S0e) run and the S0a Tier 1 run:
  - the per-suite counts (total / passed / ignored) for every R13 suite;
  - that the only `snap-synctest` failure is task #68: `SyncControllerSpec` "leave SNAP's resume alone once SNAP has
    taken over the best block of an upgraded node" (`:762`).

  Write both into research.md R13/R13a.
- [x] T003 Cut the tag `snap-split-base` on staging immediately before P1 merges (decided: spec FR-037, plan.md D6).
  Announce it as the Sepolia hotfix base.

## Done in the spec PR (#1505)

- [x] T004 ADR `docs/adr/consensus/CON-013-snap-controller-module-form.md` (traits, then required narrowing).
- [x] T005 Routing doc row added to CLAUDE.md's "Shared agent protocols" table.
- [x] T006 CHASE-QUEUE entries CQ-SNAP-016-1…4: the stale `healing-frontier-persistence` comment and three reset-path
  asymmetries. CHASE-QUEUE is a local, gitignored working doc, so these entries are not in the PR diff.

## S0e — SNAP `SyncTest` CI job (first)

- [ ] T007 [S0e] Add the CI job `snap-synctest` (new workflow, or a job in `ci.yml`).
  - **Triggers.** It runs on **every** PR to `staging` and on `workflow_dispatch`, with **no** `paths:` filter on the
    trigger. A required check that never starts would block merges.
  - **Early exit.** Its first step checks `git diff --name-only origin/staging...HEAD`. If nothing under
    `src/main/scala/com/chipprbots/ethereum/blockchain/sync/**`, the matching `src/test` tree or `build.sbt` changed,
    it reports success with a "skipped: no sync change" summary and stops before sbt.
  - **Run.** Otherwise it runs
    `sbt "testOnly *SyncControllerSpec *PivotHeaderBootstrapSpec *SnapServingActorSpec -- -n SyncTest"` and
    publishes the reports. Per-suite counts come from those reports.
  - **Allow-list mechanics.**
    - A small script parses the JUnit XML reports (`target/test-reports/*.xml`).
    - It builds the set of failed and errored test names, each as `<suite> :: <test name>`.
    - It compares that set with a one-entry allow-list file, `scripts/snap-split/synctest-allowlist.txt`, which holds
      `SyncControllerSpec :: SyncController should leave SNAP's resume alone once SNAP has taken over the best block
      of an upgraded node` (task #68).
  - **Pass/fail rules.** The job fails if:
    - any failed name is not on the list;
    - the #68 test **passes** unexpectedly (then the list is stale: remove the entry in that PR, or in a fix PR, and
      say so);
    - the #68 test is **missing** from the reports, which means renamed, deleted or not run;
    - no report was produced at all.
  - **Branch protection.** Make it a required check for PRs to staging. Because the trigger is unfiltered and the
    early exit is cheap, requiring it never blocks unrelated PRs.

## S0f — Verification tooling (before P1)

- [ ] T008 [S0f] Add `scripts/snap-split/verify.sh` (plus any helpers), with fixture-based self-tests under
  `scripts/snap-split/tests/`. It implements plan.md §Move verification steps 1–7 and FR-016 (a), (c) and (d).
  - Step 3 is branch-scoped: the token check runs on `refactor/snap-016-*` branches, and the `# test-files:` trailer
    check on `test/snap-016-*` branches.
  - For module PRs it also runs `sbt compile` on the move commit (the `# moved:` trailer, which is the parent of the
    narrowing commit).
  - It also ships the `snap-synctest` report parser and allow-list from T007, if S0e did not already ship them.
- [ ] T009 [S0f] Add the required CI job `snap-split-verify`. It runs the self-tests, then `verify.sh` on every commit
  in `origin/staging..HEAD`, and exits non-zero on failure.

## S0g — Stabilise `StaleVerificationWalkSpec` (test-only, before C-H0)

- [ ] T009a [S0g] Replace the four wall-clock `eventually(timeout(5.seconds))` waits (`:89`, `:95`, `:122`, `:129`)
  with deterministic synchronisation: an executor barrier or `ManualTime`. No production change, and no assertion
  is weakened (project task #85). The FR-039 flake policy applies until this lands.

## S0a — Pin #1367 (NetworkPeerManagerActor)

- [ ] T010 [S0a] In `NetworkPeerManagerSpec`, add "#1367 TD-PROXY-GAP: no disconnect while our best block is 0" and
  the converse case (best > 0 and ratio > 10,000 disconnects). Drive the handshake TD-ratio check in
  `NetworkPeerManagerActor` (the guard `ourBest.header.number.value > 0`). Reviewer: herald.
- [ ] T011 [S0a] Show once that T010 fails with the guard removed, using a throwaway CI run on a scratch branch that is
  never merged. Record the run link in the PR.

## S0b — Controller pins + `ChildFactories` seam

- [ ] T012 [S0b] Add the test seams below as constructor parameters of the private impl and of
  `SNAPSyncController.apply`. There is no other `src/main` change (plan.md D4).
  - `ChildFactories`: four coordinators + ChainDownloader, defaulting to today's merged `apply`s, including
    `intakeBudget`.
  - `heapWatchdogStart`: default `SnapHeapWatchdog.start`.
  - an optional injected `SnapIntakeBudget`.
- [ ] T013 [P] [S0b] Pin #1378: a `ManualTime` test that the spawned controller sends `GetHandshakedPeersCmd`
  immediately and again after each 5 s advance, with no hand-sent `PollHandshakedPeers`.
- [ ] T014 [P] [S0b] Pin #1319/spec-002: through the factory seam, healing spawned via `startStateHealing` and via
  `startStateHealingWithInterleave` gets `frontierPersistenceEnabled == healing-frontier-persistence`, both true and
  false. Also assert the **full spawn-argument tuple** on both routes, field by field, so that M6a's dedupe is pinned.
- [ ] T015 [P] [S0b] Pin spec-005, in `SNAPSyncControllerSpec` and a seam test:
  - the `prunedHealVerification` default is true (case class and absent key);
  - `pruned-heal-verification = false` parses;
  - the frontier store gate covers `|| prunedHealVerification`;
  - the forwarded TNHC value is asserted **as today**, with `// #1502` on the assertion the fix will flip.
- [ ] T016 [P] [S0b] Pin #1371 at the call site: with deferred merkleization, a fresh cursor and storage
  force-completed, no healing coordinator is spawned. The existing helper tests stay.
- [ ] T017 [S0b] For each of T013–T016, show once (throwaway CI) that reverting the pinned expression fails the test.
  Record the result in the PR.

## S0c — Golden-bytes for the frozen formats (vault)

- [ ] T018 [P] [S0c] Commit golden hex vectors with encode == golden and decode(golden) == value for each of:
  - `AccountResumeCheckpoint` v1;
  - `SnapSyncProgressStorage` (namespace `p`, account and storage cursors);
  - the `SnapStorageDone/` key layout;
  - `StorageTaskFile` 64 B and 32 B entries;
  - `HealingFrontierStorage` (CF `g`: frontier mirror, subtree-complete records);
  - `BfsQueueStorage`;
  - the legacy `AppStateStorage` progress string (`deserializeSnapProgress`);
  - the `AppStateStorage` SNAP phase-flag and anchor keys.

  Vectors come from the baseline code. Each is generated once and committed as hex.
- [ ] T019 [S0c] vault review: confirm the list is complete against research.md R8 and that every vector exercises a
  non-trivial value (non-empty cursors, more than one task file, a non-empty frontier).

## S0d — Shared fixture + characterization (assert today's behaviour)

- [ ] T020 [S0d] Add `SnapControllerFixture`: stubbed NPMA, `ManualTime`, recording `ChildFactories`, and an
  ephemeral `AppStateStorage` and stores. Existing `SNAP*Spec` fixtures are **not** migrated in this slice. Decide
  whether `-Wsafe-init` is turned on for the snap package (plan.md D1); record the decision.
- [ ] T021 [P] [S0d] Characterize the stagnation watchdog: account stall → refresh/restart path; storage tail →
  refresh, then force-complete (`ForceCompleteStorage` sent once); bytecode stall → force-complete. All with
  `ManualTime`.
- [ ] T022 [P] [S0d] Characterize reset completeness. For each of `restartSnapSync` (via `DelayedRestart`),
  `enterDormantMode` (via critical failures) and `wakeFromDormant` (via `DormantWakeUp`), assert:
  - which children are stopped and re-spawned, and the ChainDownloader **not** stopped;
  - the arguments of the next `Start*`;
  - persisted flags;
  - `GetStatus`.
- [ ] T023 [P] [S0d] Characterize pivot refresh. Drive a refresh to `completePivotRefreshWithStateRoot` and assert:
  - the `PivotRefreshed`, `StoragePivotRefreshed`, `ByteCodePivotRefreshed` and `HealingPivotRefreshed` messages;
  - ChainDownloader `Pause`/`UpdateTarget`/`Resume`;
  - persisted pivot and root;
  - probe commit, and probe timeout → abort.

  Cover the Hash (ETC) and Path/CL-hint (ETH) variants. beacon reviews the ETH variant.
- [ ] T025 [P] [S0d] Pin the #1501 budget and watchdog behaviour:
  - `IncrementalContractData` dropped in `idle`, and dropped in `syncing` with no coordinator, releases its credit on
    the injected budget;
  - `RecoveryReplayPausedRetry == 1.second`, and a closed gate delays the recovery stream by one retry under
    `ManualTime`;
  - the reset trio does **not** stop the heap watchdog, and `onStop`/`stopSnapOnlySchedules` do (via
    `heapWatchdogStart`).
- [ ] T026 [P] [S0d] Pin the P1 fan-outs: a peer disconnect sends `*PeerUnavailable` to every existing coordinator,
  and each SNAP response type reaches exactly its coordinator.
- [ ] T027 [P] [S0d] Table-driven Command × `SyncPhase` test for `syncing`. For every Command and every phase it
  asserts which handler runs: an observable effect, or the "Unhandled message in syncing state" catch-all (for
  example `MinPivotBlock`). This is the arm-order oracle for P4a–e.
- [ ] T024 [S0d] Add the draft routing doc `.claude/agent-protocols/snap-sync.md` (already in the spec PR) and the
  pin-test index.

---

## P1 — `CoordinatorHandles` (after T001)

- [x] T030 [P1] Introduce `CoordinatorHandles` (four refs; `stopAll()` with the same set and order as
  `stopStateSyncChildren`; `broadcastPeerUnavailable`; `pivotRefreshed(root)`; response fan-out; `intakeBudget`
  carried into the spawn helpers) and `ChainDownloaderHandle`. Replace the copied stop/clear lines in
  `enterDormantMode`, `restartSnapSync` and `stopStateSyncChildren`, and the per-site `foreach` fan-outs. There is no
  `ctx.watch`. The heap watchdog is **not** stopped here (spec Edge Cases).
- [x] T031 [P1] Before/after table in the PR: every send site, its recipients and message, and their order.

## P2 — `PhaseFlags` + `reset(kind)` + `cancelSyncTimers`

- [x] T032 [P2] Classify every W in research.md R3a as **reset** (to initial value) or **set** (to a computed value).
  Commit the table to research.md first, in its own docs commit.
- [x] T033 [P2] Introduce `PhaseFlags` (the nine flags of FR-011) and `reset(kind: Start | Restart | Wake | Dormant)`,
  with per-kind field sets equal to the T032 reset set. Replace the reset writes at the four sites. Introduce
  `cancelSyncTimers(keys)`, with each call site passing its own existing list.
- [x] T034 [P2] Log every per-kind asymmetry that looks like a bug to CHASE-QUEUE. **Do not unify them.**

## P3 — single peer-event handler

- [ ] T035 [P3] Add `peerEventArms` (quartet + `GetProgress` + `CLPivotHint`) and use it in `idle`, `syncing`,
  `bootstrapping` and `dormantRetry`. `bootstrapping`'s reactivity `PollHandshakedPeers` arm stays ahead of it, and
  the `GetStatus` bodies stay per behaviour.

## P4 — per-phase arms in `syncing` (five PRs)

- [ ] T036 [P4a] Arm-order table for **all** of `syncing`: every Command, its arms in source order, and their guards.
  This goes into research.md in P4a and is reused by P4b–e.
- [ ] T037 [P4a] Introduce the dispatcher (plan.md D3), `commonSyncingArms` and `accountRangeArms`.
- [ ] T038 [P4b] `byteCodeAndStorageArms`.
- [ ] T039 [P4c] `stateHealingArms` (beacon reviews the CL/serve-root arms).
- [ ] T040 [P4d] `stateValidationArms`.
- [ ] T041 [P4e] `chainDownloadCompletionArms`. Check that no `currentPhase ==` remains inside `syncing`. Run a full
  SNAP on Platåberget.

## M1–M11 — controller modules (one PR each, in this order)

- [ ] T042 [M1] Move the companion pure helpers to `controller/{Resume,Stagnation,Pivot,Heal}Policy.scala`. Add
  `export` forwarders in `object SNAPSyncController`, so no test call site changes. M1 is pure objects, not traits,
  so FR-016's narrowing does not apply. The S0f script still runs.
- [ ] T041a [M2: the first trait-based module PR, after M1] Add `controller/SnapSharedState.scala` (the hub
  interface: `pivotBlock`, `stateRoot`, `currentPhase`, `progressMonitor`, `requestTracker`) and `controller/SnapControllerEnv.scala` (`ctx`,
  `timers`, config, logs, storages, metrics). The core implements both. All later narrowing commits reuse them.
- [ ] T043 [M2] `StateValidationModule`: move, then narrow to
  `StateValidationState & SnapSharedState & SnapControllerEnv & ResumeApi`. Stub test (Tier 1): a stale-generation
  validation result is dropped, and a current one clears `validationInProgress`. That body reads and writes the
  state.
  **Every M task below (T044–T053) also has the narrowing commit and at least one stub test, per the per-slice
  checklist.**
- [ ] T044 [M3] `SnapPeerPool` (herald second review).
- [ ] T045 [M4] `SnapFinalization` + `ChainDownloaderHandle` use. Run Platåberget to head.
- [ ] T046 [M5] `SnapResumePlanner`, including #1501 `runGatedReplay`. vault reviews. Run Mordor and Platåberget
  restarts mid-account and mid-storage.
- [ ] T047 [M6a] Dedupe the two healing spawn blocks into `healingCoordinatorArgs`. The pins from T014 and T015 must
  stay green unchanged.
- [ ] T048 [M6b] `HealingOrchestrator`. Run a Mordor heal restart.
- [ ] T049 [M7] `StagnationWatchdog`, with the `reset(reason)` hook used by launch, refresh and `startSnapSync`.
- [ ] T050 [M8] `PivotSelector`. Run Platåberget from scratch.
- [ ] T050b [M8b] Remove the dead `isStarting` parameter in its own PR (FR-034). The PR has no other change.
- [ ] T051 [M9] `SyncLifecycle`, including the #1501 heap watchdog (call sites unchanged). Run Mordor dormant/wake.
- [ ] T052 [M10] `PivotRefresher`. Run Platåberget across at least 3 refreshes, and Mordor.
- [ ] T053 [M11] `DownloadSupervisor`. Check SC-001 (core ≤ about 1,000 lines). Run a full Mordor SNAP.

## Coordinators

- [ ] T060 Coordinator characterization (docs PR): a var × method matrix and Command × behaviour table for ARC, SRC,
  TNHC and BCC, using the research.md method. Confirm or amend the C-* boundaries in plan.md, and derive each
  coordinator module's state-interface baseline (the R14 equivalent). Every C-* task below has the narrowing commit
  and a stub test.
- [ ] T061 [C-B1] ByteCode: `ByteCodeDispatch`, `ByteCodeResponseHandling` (the #1501 `skipPresent` check moves
  unchanged).
- [ ] T062 [C-A1] `AccountPeerDispatch` (includes the #1501 intake gate).
- [ ] T063 [C-A2] `AccountTaskLifecycle`.
- [ ] T064 [C-A3] `AccountTrieAssembly` + `finalizing` (vault).
- [ ] T065 [C-A4] `ContractWorkCarry` (vault). Run a Mordor restart mid-account. The #1487/#1498/#1501 replay
  invariants are unchanged.
- [ ] T066 [C-S1] `StoragePeerDispatch`.
- [ ] T067 [C-S2] `StorageOrderedApply` (vault).
- [ ] T068 [C-S3] `StorageDoneMarkers` (vault). The PR quotes spec 010 R2a and shows each clause still holds. Run
  Mordor and Platåberget restarts mid-storage.
- [ ] T069 [C-S4] `StorageResponseProcessing`.
- [ ] T070 [C-H0] Split TNHC `activeBehavior` into per-concern partial functions, with an arm-order table as in T036.
- [ ] T071 [C-H1] `FrontierPersistence` (vault). Run a Mordor heal restart.
- [ ] T072 [C-H2] `FrontierRebuildBfs`.
- [ ] T073 [C-H3] `HealVerification` (vault).
- [ ] T074 [C-H4] `HealDispatch`.
- [ ] T075 [C-H5] `HealResponseProcessing`. Check SC-002.
- [ ] T075b [C-X0] Test-only golden of the response-size window for ARC, SRC and TNHC: initial target, grow on a ≥90%
  fill, shrink on failure, min/max clamps, and the exact DEBUG log text with its per-coordinator label. It lands
  before C-X.
- [ ] T076 [C-X] `AdaptiveResponseBytes` for ARC, SRC and TNHC (identical bodies, log label as a parameter).
  ByteCode is untouched.

## R1 — Renames and final check

- [ ] T080 [R1] DFS → BFS in comments, scaladoc and test **descriptions** (research.md R9 list). `StateValidator`'s
  DFS names are **not** changed, and neither is the `healing-visited-cap` config key.
- [ ] T090 [R1] Routing-doc check. For every row, verify that:
  - the files exist;
  - each listed log tag is emitted in one of them (`grep`);
  - each metric is registered in `SNAPSyncMetrics` or in one of them;
  - each config key is parsed in `SNAPSyncConfig` and consumed in one of them.

  Every row is marked "target (post-split)".

## Placed open items (NOT in this series; each is a separate fix PR against the current layout)

| Item | Fix lands in | Pin or characterization test the fix updates on purpose |
|---|---|---|
| #1434 | ResumePlanner (M5) + PivotSelector (M8) + `SyncController` startup | T022 (reset) / new test |
| #1435 | SnapFinalization (M4) + startup repair | new test |
| #1502 | HealingOrchestrator (M6) | T015 (`// #1502` assertion flips) |
| #1503 | ResumePlanner (M5) | new test |
| #1350, #1349 | TNHC FrontierRebuildBfs (C-H2) | — |
| #1431 | SnapPeerPool (M3) + network layer | — |
| off-thread `ctx.log`, status `ask` timeouts | core common arms, StagnationWatchdog (M7) | — |
| heal convergence (latch; keep tasks across re-peg) | HealingOrchestrator (M6) × TNHC HealResponseProcessing (C-H5) | T023 |
| stale-pivot empty ranges as scarcity | ARC AccountTaskLifecycle (C-A2) × AccountPeerDispatch (C-A1) | — |
| refresh every ~10–13 min discards in-flight storage tries | PivotRefresher (M10) × SRC StorageOrderedApply (C-S2) | T023 |
