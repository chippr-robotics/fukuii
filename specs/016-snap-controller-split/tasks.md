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
- Line numbers are given as `staging@9a6681cc0 L####` only. Locate code by symbol (research.md R0).

## Per-slice checklist (copy it into every PR body)

- [ ] Links spec 016 and the task IDs. Commit prefix is `test(snap):` or `refactor(snap):`, and the message
  references #1401.
- [ ] No sbt or JVM was run on the soak host. The PR was opened only after CI was green (`compile-all`,
  `scalafmtCheckAll`, Tier 1/2).
- [ ] Every regression suite in research.md R13 is green with **zero assertion changes** (plan.md, Move verification
  step 3). Any `SyncControllerSpec` failure equals the T002 baseline by test name.
- [ ] S0c golden vectors and S0a/S0b pin tests are untouched. `src/main/resources` is unchanged.
- [ ] Move commits carry the moved-symbol list and the identical-body check (plan.md, Move verification steps 1–2).
  Renames are not in this PR.
- [ ] **Module PRs (M2–M11, C-*)**: the required narrowing commit follows the move commit (plan.md D1). The PR body
  shows:
  - **(a)** the grep proving no self-type names `SNAPSyncControllerImpl` / `*CoordinatorImpl`;
  - **(b)** the new stub-based unit test;
  - **(c)** the `<Module>State` member count and capability traits, against the research.md R14 baseline (a larger
    count needs a justification line);
  - **(d)** the `val`/`var` grep output.

  The same count goes into the module's row of the routing doc.
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

- [ ] T001 Once PR #1501 has merged, re-check research.md R11 against the merged diff (`gh pr diff 1501`):
  - new types, call sites of `ensureHeapWatchdog`/`stopHeapWatchdog`, `intakeBudget` spawn arguments, config keys,
    metrics and the `[SNAP-HEAP]` tag;
  - re-measure the five file sizes in R1.

  Amend plan.md if anything moved. **P1 does not start until this is done.**
- [ ] T002 Confirm from the S0a PR's CI run that the only failure is task #68: `SyncControllerSpec` "leave SNAP's
  resume alone once SNAP has taken over the best block of an upgraded node" (`:762`). Record the pass counts of every
  R13 suite in research.md R13.
- [ ] T003 Cut the tag `snap-split-base` on staging immediately before P1 merges (decided: spec FR-037, plan.md D6).
  Announce it as the Sepolia hotfix base.

## Done in the spec PR (#1505)

- [x] T004 ADR `docs/adr/consensus/CON-013-snap-controller-module-form.md` (traits, then required narrowing).
- [x] T005 Routing doc row added to CLAUDE.md's "Shared agent protocols" table.
- [x] T006 CHASE-QUEUE entries CQ-SNAP-016-1…4: the stale `healing-frontier-persistence` comment and three reset-path
  asymmetries. CHASE-QUEUE is a local, gitignored working doc, so these entries are not in the PR diff.

## S0a — Pin #1367 (NetworkPeerManagerActor)

- [ ] T010 [S0a] In `NetworkPeerManagerSpec`, add "#1367 TD-PROXY-GAP: no disconnect while our best block is 0" and
  the converse case (best > 0 and ratio > 10,000 disconnects). Drive the handshake TD-ratio check in
  `NetworkPeerManagerActor` (the guard `ourBest.header.number.value > 0`). Reviewer: herald.
- [ ] T011 [S0a] Show once that T010 fails with the guard removed, using a throwaway CI run on a scratch branch that is
  never merged. Record the run link in the PR.

## S0b — Controller pins + `ChildFactories` seam

- [ ] T012 [S0b] Add `ChildFactories` (four coordinators + ChainDownloader, defaulting to today's `apply`s) as a
  constructor parameter of the private impl and of `SNAPSyncController.apply`. There is no other `src/main` change
  (plan.md D4).
- [ ] T013 [P] [S0b] Pin #1378: a `ManualTime` test that the spawned controller sends `GetHandshakedPeersCmd`
  immediately and again after each 5 s advance, with no hand-sent `PollHandshakedPeers`.
- [ ] T014 [P] [S0b] Pin #1319/spec-002: through the factory seam, healing spawned via `startStateHealing` and via
  `startStateHealingWithInterleave` gets `frontierPersistenceEnabled == healing-frontier-persistence`, both true and
  false.
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
- [ ] T024 [S0d] Add the draft routing doc `.claude/agent-protocols/snap-sync.md` (already in the spec PR) and the
  pin-test index.

---

## P1 — `CoordinatorHandles` (after T001)

- [ ] T030 [P1] Introduce `CoordinatorHandles` (four refs; `stopAll()` with the same set and order as
  `stopStateSyncChildren`; `broadcastPeerUnavailable`; `pivotRefreshed(root)`; response fan-out; `intakeBudget`
  carried into the spawn helpers) and `ChainDownloaderHandle`. Replace the copied stop/clear lines in
  `enterDormantMode`, `restartSnapSync` and `stopStateSyncChildren`, and the per-site `foreach` fan-outs. There is no
  `ctx.watch`. The heap watchdog is **not** stopped here (spec Edge Cases).
- [ ] T031 [P1] Before/after table in the PR: every send site, its recipients and message, and their order.

## P2 — `PhaseFlags` + `reset(kind)` + `cancelSyncTimers`

- [ ] T032 [P2] Classify every W in research.md R3a as **reset** (to initial value) or **set** (to a computed value).
  Commit the table to research.md first, in its own docs commit.
- [ ] T033 [P2] Introduce `PhaseFlags` (the nine flags of FR-011) and `reset(kind: Start | Restart | Wake | Dormant)`,
  with per-kind field sets equal to the T032 reset set. Replace the reset writes at the four sites. Introduce
  `cancelSyncTimers(keys)`, with each call site passing its own existing list.
- [ ] T034 [P2] Log every per-kind asymmetry that looks like a bug to CHASE-QUEUE. **Do not unify them.**

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

- [ ] T041a [M2, first module PR] Add `controller/SnapSharedState.scala` (the hub interface: `pivotBlock`,
  `stateRoot`, `currentPhase`, `progressMonitor`, `requestTracker`) and `controller/SnapControllerEnv.scala` (`ctx`,
  `timers`, config, logs, storages, metrics). The core implements both. All later narrowing commits reuse them.
- [ ] T042 [M1] Move the companion pure helpers to `controller/{Resume,Stagnation,Pivot,Heal}Policy.scala`. Add
  `export` forwarders in `object SNAPSyncController`, so no test call site changes. Add `scripts/snap-split/` with the
  move-verification helper (prism reviews it).
- [ ] T043 [M2] `StateValidationModule`: move, then narrow to
  `StateValidationState & SnapSharedState & SnapControllerEnv`. Stub test: a stale-generation result is dropped.
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
- [ ] T050 [M8] `PivotSelector`. Removing the dead `isStarting` parameter is a separate commit **after** the move.
  Run Platåberget from scratch.
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
