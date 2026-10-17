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
- Bug-fix PRs that touch a module file use `fix/*` or `hotfix/*`; verify.sh treats them as non-move branches (plan.md §Move verification, "Branch rule")
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
- [ ] **Ratchet (amendment 2026-10-11, from T054 onward).** `verify.py` step 8 is green against
  `scripts/snap-split/storage-baseline.json`: the `SnapControllerEnv` member count is unchanged or lower, and the
  storage-reference **total** over `snap/controller/`, `snap/actors/` and the core `SNAPSyncController.scala` (T054c)
  is unchanged or lower (per-file counts may move between files, including from the core into a module). A PR that lowers a count records it in the baseline file in the same PR and pastes the
  before/after numbers. Calls on `StateIngest` are not storage references (FR-043).
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

- [x] T035 [P3] Add `peerEventArms` (quartet + `GetProgress` + `CLPivotHint`) and use it in `idle`, `syncing`,
  `bootstrapping` and `dormantRetry`. `bootstrapping`'s reactivity `PollHandshakedPeers` arm stays ahead of it, and
  the `GetStatus` bodies stay per behaviour.
  - As built (research.md R4, P3 correction): `bootstrapping`'s reactivity arm is `WrappedHandshakedPeers`, not
    `PollHandshakedPeers`, and its `GetProgress` arm (bootstrap progress) also differs. Both stay inline ahead of
    `peerEventArms`.

## P4 — per-phase arms in `syncing` (five PRs)

- [x] T036 [P4a] Arm-order table for **all** of `syncing`: every Command, its arms in source order, and their guards.
  This goes into research.md in P4a and is reused by P4b–e.
  - As built: research.md R4b (75 arms, dispatch guard vs body-level phase test, planned target per arm, notes for
    P4b–e).
- [x] T037 [P4a] Introduce the dispatcher (plan.md D3), `commonSyncingArms` and `accountRangeArms`.
  - As built: `syncing` dispatches `syncingGuardArms` (arms #1–#7: the path-publish and header-hold arms, their two
    guarded wildcards and the `peerEventArms` delegation) → `phaseArms(currentPhase)` → `commonSyncingArms` → the
    unchanged catch-all. The guard-arm prefix is needed because #4 and #6 match any message (R4b). `accountRangeArms`
    holds the one arm guarded on `AccountRangeSync` (`AccountCoordinatorProgress`); arms that test the phase inside
    their body stay in `commonSyncingArms` (R4b note 2).
- [x] T038 [P4b] `byteCodeAndStorageArms`.
  - As built: `phaseArms(ByteCodeAndStorageSync)` returns `byteCodeAndStorageArms` (class member), holding the two
    arms guarded on `ByteCodeAndStorageSync`: `StorageCoordinatorProgress` (#70) and `ByteCodeCoordinatorProgress`
    (#71), guards dropped, bodies unchanged. Arms with a body-level `ByteCodeAndStorageSync` test (#10, #35, #39,
    #40, #48, #68) stay in `commonSyncingArms` (R4b note 2).
- [x] T039 [P4c] `stateHealingArms` (beacon reviews the CL/serve-root arms).
  - As built: `phaseArms(StateHealing)` returns `stateHealingArms` (class member), holding the nine arms guarded on
    `StateHealing`: #49 `HealingAllPeersStateless`, #50 `HealingStagnated`, #52 `StateHealingAbandoned`, #53
    `HealingRootUnservable`, #54 `TrieWalkBatch`, #55 `TrieWalkComplete`, #56 `TrieWalkResult`, #57
    `ScheduledTrieWalk`, #58 `TrieWalkFailed`, guards dropped, bodies unchanged. Arms with a body-level `StateHealing`
    test (#15, #39, #40, #48, #51) stay in `commonSyncingArms` (R4b note 2). The CL/serve-root arms (#15
    `RequestTrieNodeHealing`, #16 `HealingServeRoot`) carry no phase guard, so none of them moved.
- [x] T040 [P4d] `stateValidationArms`.
  - As built: `phaseArms(StateValidation)` returns `stateValidationArms` (class member) =
    `staleValidationDropArms.orElse(stateValidationResultArms)`. `stateValidationResultArms` holds the five arms
    guarded on `StateValidation`: #63/#64 `ValidateAccountTrieResult` (Right, Left), #65/#66
    `ValidateStorageTriesResult` (Right, Left), #67 `ValidationRetry`, guards dropped, bodies unchanged. The
    stale-generation drops #60–#62 (no phase guard) moved verbatim into `staleValidationDropArms`, which runs first in
    `stateValidationArms` and from a delegation arm at their old place in `commonSyncingArms` for every other phase
    (R4b note 1, second option). #21 `TrieNodesResponse` (body-level `!= StateValidation`) and #59
    `StateValidationComplete` (no phase test) stay in `commonSyncingArms`.
- [x] T041 [P4e] `chainDownloadCompletionArms`. Check that no `currentPhase ==` remains inside `syncing`. Run a full
  SNAP on Platåberget.
  - As built: `phaseArms(ChainDownloadCompletion)` returns `chainDownloadCompletionArms` (class member), which is
    empty: no arm was ever guarded on that phase and nothing assigns it (R4b note 3, CQ-SNAP-016-9). No arm moved.
    The nine arms that test the phase inside their body (#10, #15, #21, #35, #39, #40, #48, #51, #68) stay in
    `commonSyncingArms`, each with its reason in R4b (note 2, P4e decision), so the check is met as "no dispatch guard
    on `currentPhase` remains in `syncing`"; the body-level tests stay until their M module moves the Command. The
    P4d review's two doc nits on `stateValidationResultArms` are fixed.
  - Platåberget full SNAP: **pending** (to be run by the user on the slice jar after merge; no node run here, and the
    soak host takes no builds).

## M1–M11 — controller modules (one PR each, in this order)

- [x] T042 [M1] Move the companion pure helpers to `controller/{Resume,Stagnation,Pivot,Heal}Policy.scala`. Add
  `export` forwarders in `object SNAPSyncController`, so no test call site changes. M1 is pure objects, not traits,
  so FR-016's narrowing does not apply. The S0f script still runs.
  - As built: 14 symbols, byte-identical, into four `private[snap] object`s, re-exported by six one-line `export`
    clauses: `StagnationPolicy` (`StorageTailBaseline` class + companion, `MinStaleFailuresForTailLivelock`,
    `evaluateStorageTail`), `PivotPolicy` (`pivotPassesFreshnessFloor`, `unservablePivotTarget`, `MaxPeerTipLead`,
    `clPivotNotYetAdvanced`), `HealPolicy` (`bytecodeRecoveryComplete`, `shouldSkipHealingAfterDownloads`,
    `healRepegSuppressedByLocalWalk`, `staleReferenceHead`, `lastHealingServeRootBlockToRecord`), `ResumePolicy`
    (`taskFilePaths`, `checkResumable`; the orphaned `checkResumable` scaladoc moved with it, verbatim).
  - Criterion: a stateless, side-effect-free decision that belongs to one of the four policies. Not moved:
    `deserializeSnapProgress` (an impl-class method, M5; the S0c golden finds it by reflection), `sweepTaskFiles` and
    `SweepableTaskFilePrefixes` (file I/O), `countsTowardRestart` (tied to the `UnservableCause` ADT enum; M9),
    `servesSnapState`/`snapExclusionReason`/`SnapExclusion*` (peer pool, M3), `chainBackfillDeferredToFinalization`
    and `storageInFlightDuringAccounts` (config derivations, M4/M11), and the timer and wait constants.
  - `snap-split-verify` step 1 gained the D2 export allowance (one-line clause, `# moved:` names only), with
    self-tests; plan.md §Move verification step 1 and D1 record it.
- [x] T041a [M2: the first trait-based module PR, after M1] Add `controller/SnapSharedState.scala` (the hub
  interface: `pivotBlock`, `stateRoot`, `currentPhase`, `progressMonitor`, `requestTracker`) and `controller/SnapControllerEnv.scala` (`ctx`,
  `timers`, config, logs, storages, metrics). The core implements both. All later narrowing commits reuse them.
  - As built: its own commit ahead of the move (a self-typed trait cannot see the impl's constructor parameters, and
    the move commit may not turn parameters into members). `SnapSharedState` = 5 (three abstract `var`s, two `def`s);
    `SnapControllerEnv` = 11 (`ctx`, `timers`, `snapSyncConfig`, `syncConfig`, `asyncLog`, the four constructor
    storages, `validatorFactory`, `snapValidationEc`; metrics are a global object). The core's constructor parameters
    became `val`s and its hub fields lost `private`; every initializer stays strict in the core.
- [x] T043 [M2] `StateValidationModule`: move, then narrow to
  `StateValidationState & SnapSharedState & SnapControllerEnv & ResumeApi`. Stub test (Tier 1): a stale-generation
  validation result is dropped, and a current one clears `validationInProgress`. That body reads and writes the
  state.
  - As built: moved `validationRetryCount`, the P4d arms (`staleValidationDropArms`, `stateValidationResultArms`,
    `stateValidationArms`), `spawnAccountValidation`, `spawnStorageValidation`, `validateState`. Narrowed self-type:
    `StateValidationState & SnapSharedState & SnapControllerEnv & ResumeApi & HealingApi & LifecycleApi`; state 3
    (= R14), Api 6 (R14a: 1; the moved arms call M6 and M9). `MaxValidationRetries`/`ValidationRetryDelay` stayed in the
    core in the move (rule (d)) and became pure private lazy vals of the trait in the narrowing. The dispatch, the
    `StateValidationComplete` arm and the `idle` drops stay in the core. Stub test: `StateValidationModuleSpec`
    (four cases). verify.py step 6 now finds the core's `extends` clause past its 30-line constructor (first commit).
  **Every M task below (T044–T053) also has the narrowing commit and at least one stub test, per the per-slice
  checklist.**
- [x] T044 [M3] `SnapPeerPool` (herald second review).
  - As built: a prerequisite commit adds `networkPeerManager` and `blockchainReader` to `SnapControllerEnv` (11 -> 13;
    constructor parameters become `val`s, as in T041a). The move commit moves the peer-list view (`handshakedPeers`,
    `peersToDownloadFrom`, `snapServingPeers`, `getSnapPeerWithHighestBlock`), the refresh handlers, the disconnect
    debounce, `pollHandshakedPeers`, eviction, snap-server dialling, `bestSnapProbeTarget` and `calibratePivotTD` into
    `controller/SnapPeerPool.scala`, and the companion helpers M1 left (`servesSnapState`, `snapExclusionReason`,
    `SnapExclusion*`) into `controller/SnapPeerPolicy.scala`, re-exported. Narrowed self-type: `SnapPeerPoolState &
    SnapSharedState & SnapControllerEnv & CoordinatorHandles`; state 6 (R14: 2; `peerListHelper`,
    `handshakedPeersAdapter`, `bestEth68PeerForCalibration` and `BootstrapCheckKey` stay in the core), Api 0 (R14a: 3).
    `DisconnectFlushKey`, `MaxFruitlessEvictionCycles` and `snapServerPeerLastConnectAttemptMs` stayed in the core in the
    move (rule (d)) and became pure private lazy vals of the trait in the narrowing. The dispatch (`peerEventArms`, the
    `bootstrapping` reactivity arm, the peer-tick arms) and `currentNetworkBestFromSnapPeers` (M8) stay in the core. Stub
    test: `SnapPeerPoolSpec` (five cases).
- [x] T045 [M4] `SnapFinalization` + `ChainDownloaderHandle` use. Run Platåberget to head.
  - As built: a prerequisite commit adds `blockchainWriter`, `peerEventBus`, `syncController`, `childFactories` (constructor
    parameters, now `val`s) and `pathNodeStorageOpt` (the first derived store a module needs) to `SnapControllerEnv`
    (13 -> 18), and rewords the one comment in `completeSnapSync` that named the impl class (FR-016 (a) covers comments).
    The move commit moves `completeSnapSync`, `enterHeaderHold`, `leaveHeaderHold` (with the hold's exclusive vars
    `lastHeaderHoldWarnMs`, `holdLastCursor`, `holdLastAdvanceMs`), `finalizeSnapSync`, `startPathPublish`,
    `onPathPublishDone`, `completedWithBackfill`, `completed`, `startChainDownloader`, `releaseDeferredBodiesAndReceipts`
    and `launchChainDownloader` into `controller/SnapFinalization.scala`, and `chainBackfillDeferredToFinalization` (the
    config derivation M1 left for M4) with the header-hold constants and `EmptyHeaderBackoff` into
    `controller/FinalizationPolicy.scala`, re-exported. Narrowed self-type: `SnapFinalizationState & SnapSharedState &
    SnapControllerEnv & CoordinatorHandles & PhaseFlags & ResumeApi & TaskFileSweepApi & HealedCodeApi & PeerPoolApi &
    ShutdownApi`; state 6 (R14: 3; `chainDownloadComplete`, `headerHold` and `pathPublish` stay in the core because the
    `syncing` guard arms #1–#7 and the ChainDownloader arms that touch them stay there, and `chainDownloaderReplyAdapter`
    is a strict core val), Api 10 (R14a: 11). `PeerPoolApi` is declared in `SnapPeerPool.scala` and `SnapPeerPool`
    extends it; the other three new Api traits are declared in `SnapFinalization.scala` and implemented by the core. No
    `val` -> `lazy val` conversion: the only vals the bodies reach have impure initializers and stay in the core. The
    dispatch, the healed-code helpers and `anchorPivotBeforeLazyHandoff` (M6b) stay in the core. Stub test:
    `SnapFinalizationSpec` (seven cases). Platåberget to head: **pending** (to be run by the user on the slice jar after
    merge; no node run here).
- [x] T046 [M5] `SnapResumePlanner`, including #1501 `runGatedReplay`. vault reviews. Run Mordor and Platåberget
  restarts mid-account and mid-storage.
  - As built: a prerequisite commit makes the core's `scheduler` constructor parameter a `val` (`runGatedReplay` re-arms
    a paused replay on it). The move commit moves the whole-member resume helpers into
    `controller/SnapResumePlanner.scala`: `checkStorageSchemeMismatch`, `getOrCreateMptStorage`,
    `clearStorageDoneMarkers`, `accountsCompleteTaskFilePaths`, `sweepSupersededTaskFiles`, `runGatedReplay` and
    `deserializeSnapProgress` (`SnapFrozenFormatsGoldenSpec` finds it through the impl's hierarchy, unchanged). Narrowed
    self-type: `SnapResumePlannerState & SnapControllerEnv`; state 3 (R14: 1; `storageDoneStorage` and `scheduler` are
    core values reached through abstract defs, kept out of `SnapControllerEnv`, which stays at 18), Api 0 (R14a: 14).
    `ResumeApi` and `TaskFileSweepApi` move next to their implementer (`SnapResumePlanner extends` both;
    CQ-SNAP-016-16). **Not moved**, because each is part of the body of a member that stays: the accounts-complete
    recovery branch of `startSnapSync` (inside its `boundary`, ending in `break(syncing())`), the resume selection in
    `launchAccountRangeWorkers`, the `AccountRangeProgressCmd` checkpoint arm (a local of `syncing()`), and with them the
    vars they own (`preserved*`, `launchedAccountGeneration`, `currentCarrySource`, `lastSweptForRecord`). Moving them
    needs a carve-out of those bodies first (CQ-SNAP-016-17), like P4 for the arms. #1434 and #1503 therefore still sit
    in `startSnapSync`. No `val` -> `lazy val` conversion. Stub test: `SnapResumePlannerSpec` (six cases). Mordor and
    Platåberget restarts: **pending** (to be run by the user on the slice jar after merge; no node run here).
- [x] T047 [M6a] Dedupe the two healing spawn blocks into `healingCoordinatorArgs`. The pins from T014 and T015 must
  stay green unchanged.
  - **As built:** one private core member, `spawnHealingCoordinator(root: TrieRoot): Unit`, named for what it does
    rather than `healingCoordinatorArgs`. The factory seam takes named parameters, so there is no args value to
    extract without a new tuple or case class. It holds the span the two routes shared verbatim: open the pivot's trie
    store, the supervised spawn (25 forwarded arguments, 4 factory defaults incl. `prunedHealVerification`, #1502
    unchanged), the three sends (`StartTrieNodeHealing`, `UpdateMaxInFlightPerPeer`, the snap-peer flush) and
    `startHealingRequestScheduler()`. No route-specific parameter was needed: every difference (guards, resets, logs,
    `startSnapServerPeersScheduler` + `startPhase` on the plain route, the `[HEAL-INTERLEAVE]` log + `startTrieWalk` on
    the interleave route) sits before or after that span and stays at its call site, in the same order. The code stays
    in the core until M6b. No test file changed.
- [x] T048 [M6b] `HealingOrchestrator`. Run a Mordor heal restart.
  - As built: a prerequisite commit makes the core's implicit `ec` constructor parameter a `val` (the two trie walks
    run their Futures on it) and declares `PivotRefreshApi` (`refreshPivotInPlace` with its three defaulted
    parameters; scalafmt wraps the declaration, and step 6 admits only one-line abstract declarations, so it cannot go
    into the narrowing commit), implemented by the core. The move commit moves into `controller/HealingOrchestrator.scala`
    the `StateHealing` phase function `stateHealingArms` (as M2 moved `stateValidationArms`) with its exclusive
    `healingRoundCount`, `completeHealingWalkClean`, `startTrieWalk`, `healingFrontierStorageOpt`, `bfsQueueStorage`,
    `spawnHealingCoordinator`, `startStateHealing`, `startStateHealingWithInterleave`, `startHealingRequestScheduler`,
    `requestTrieNodeHealing`, `maybeRequestHealingServeRoot`, `triggerHealingForMissingNodes`,
    `anchorPivotBeforeLazyHandoff`, `resetHealedCodeHold`, `dropHealedCodeNowPresent` and `queueHealedCode` (17
    symbols). Narrowed self-type: `HealingOrchestratorState & SnapSharedState & SnapControllerEnv & CoordinatorHandles &
    PhaseFlags & ResumeApi & PeerPoolApi & SnapServerPeersApi & ValidationApi & FinalizationApi & PivotRefreshApi &
    PivotSelectionApi & ByteCodeRequestApi`; state 12 (R14: 10; + `trieWalkInProgress`, `isPoSChain`, `ec`, −
    `mptStorage`), Api 10 (R14a: 11). `HealingApi` and `HealedCodeApi` move next to their implementer
    (`HealingOrchestrator extends` both; CQ-SNAP-016-16). `ScheduledTrieWalkKey` and `HealingServeRootMarginBlocks`
    stayed in the core in the move (rule (d)) and became pure private lazy vals of the trait in the narrowing.
    `SnapControllerEnv` stays at 18. **Not moved**: the healing arms of `commonSyncingArms` (a local of `syncing()`):
    #15 `RequestTrieNodeHealing` (P4e earmarked it for M6b, but lifting it needs a delegation line in `syncing()`, a
    body change), #16 `HealingServeRoot`, `StateHealingComplete`, `HealedCodeHashes` and the healed-code hold arms.
    #1502 is unchanged (pinned). Stub test: `HealingOrchestratorSpec` (nine cases). Mordor heal restart: **pending**
    (to be run by the user on the slice jar after merge; no node run here).
- [x] T049 [M7] `StagnationWatchdog`, with the `reset(reason)` hook used by launch, refresh and `startSnapSync`.
  - The hook was built in the restructure slice (T050c) as `resetStagnationClocks(reason)`; that closes T049.
  - **As built (partial: the `reset(reason)` hook is open, CQ-SNAP-016-20):** no prerequisite commit. The move commit
    moves into `controller/StagnationWatchdog.scala` the `AccountRangeSync` and `ByteCodeAndStorageSync` phase functions
    `accountRangeArms` and `byteCodeAndStorageArms` (P4a/P4b; they hold only the three `*CoordinatorProgress` arms,
    the answers to the progress asks, research.md R6 "progress forwarding", so they move whole as M2 and M6b moved
    their phase functions; the plan's M11 row and the routing doc had listed these arms under M11),
    `scheduleStagnationChecks`, `maybeRestartIfStorageStagnant`, `maybeForceCompleteIfBytecodeStagnant`,
    `maybeRestartIfAccountStagnant` and the exclusive `consecutiveAccountStallRefreshes` (7 symbols). Narrowed
    self-type: `StagnationWatchdogState & SnapSharedState & SnapControllerEnv & CoordinatorHandles & PhaseFlags &
    HandshakedPeersApi & LifecycleApi & PivotRefreshApi`; state 11 (R14: 5; + `lastBytecodeProgressMs`,
    `lastBytecodeProgressCount`, `storageStagnationRefreshAttempted`, `lastPivotRestartMs`, `MinPivotRestartInterval`,
    `StorageRestartedEmptyThreshold`), Api 5 (R14a: 7). `HandshakedPeersApi` is new, declared in `SnapPeerPool.scala`
    (`SnapPeerPool extends` it). Five constants used only by the moved bodies stayed in the core in the move (rule (d))
    and became pure private lazy vals of the trait in the narrowing; a sixth, `StorageRestartedEmptyThreshold`, stays
    in the core behind the state interface because its comment names `StorageRangeCoordinatorImpl` (FR-016 (a)). `SnapControllerEnv` stays at 18. **Not moved**: the `CheckDownloadStagnation` arm of
    `commonSyncingArms` (a local of `syncing()`; lifting it needs a delegation line, CQ-SNAP-016-19), and the clock
    vars, whose initializers are impure (`System.currentTimeMillis()`) or which code that stays also writes. **Not
    built**: the `watchdog.reset(reason)` hook. Replacing the inline resets at launch, refresh and `startSnapSync` is a
    body change in members that do not move, which neither the move nor the narrowing commit admits, and the sites
    reset different clock sets; it needs a decision on where it lands and its per-reason sets (CQ-SNAP-016-20). The
    resets are unchanged. Stub test: `StagnationWatchdogSpec` (eight cases).
- [x] T050 [M8] `PivotSelector`. Run Platåberget from scratch.
  - Closed by M8c (T050d): `startSnapSync`'s pivot-selection steps moved; Platåberget from scratch is still pending.
  - As built: no prerequisite commit. The move commit moves into `controller/PivotSelector.scala` the whole members
    `bootstrapping` (the behaviour: `BootstrapComplete` with the freshness check and the post-merge header gate,
    `RetrySnapSyncStart`, the initial `PivotBootstrapFailed` backoff), `handleCLPivotHint`, `bootstrapRetryDelay`,
    `checkBootstrapRetryTimeout`, `currentNetworkBestFromSnapPeers` and `updateBestBlockForPivot` (6 symbols); no var
    moves (each pivot-selection var is also reached by code that stays). Narrowed self-type: `PivotSelectorState &
    SnapSharedState & SnapControllerEnv & PhaseFlags & PeerPoolApi & HandshakedPeersApi & BootstrapPeersApi & HealingApi
    & LifecycleApi & ShutdownApi & SnapStartApi & SyncingApi & AccountRangeStartApi`; state 7 (R14: 6; `clPivotHint`,
    `clHintArrivedAtMs`, `bootstrapRetryCount`, `isPoSChain`, `bigIntReverseOrdering`, `BootstrapCheckKey`,
    `peerEventArms`), Api 16 (R14a: 20). `PivotSelector extends PivotSelectionApi`, whose declaration moves next to it
    from the healing file (CQ-SNAP-016-16); `BootstrapPeersApi` is new in `SnapPeerPool.scala` (`SnapPeerPool extends`
    it); `SnapStartApi`, `SyncingApi` and `AccountRangeStartApi` are declared in the module file and implemented by the
    core. `BootstrapRetryBaseDelay`, `BootstrapRetryMaxDelay` and `MaxBootstrapRetries` stayed in the core in the move
    (rule (d)) and became pure private lazy vals of the trait in the narrowing. `SnapControllerEnv` stays at 18.
    **Not moved**: `startSnapSync`. plan.md gives M8 only its pivot-selection parts (research.md R6: ~270 of ~710
    lines); the rest is the accounts-complete recovery branch (M5, CQ-SNAP-016-17) and a copy of the coordinator spawns
    (M11), so the byte-identical move cannot take it without carrying them into this module; it waits for a carve-out
    (CQ-SNAP-016-22). With it stay `minPivotHint` and the `isStarting` parameter (M8b). The dispatch stays in the core:
    the `Start` and `MinPivotBlock` arms of `idle`, the `CLPivotHint` arm of `peerEventArms`, the
    `RetryBootstrapAtBlock` arm of `commonSyncingArms`. Stub test: `PivotSelectorSpec` (eight cases). Platåberget from
    scratch: **pending** (to be run by the user on the slice jar after merge; no node run here). T050 stays open until
    `startSnapSync`'s pivot-selection part moves.
- [x] T050b [M8b] Remove the dead `isStarting` parameter in its own PR (FR-034). The PR has no other change.
    As built: the parameter was on `handleCLPivotHint` (not `startSnapSync`), only ever read by `val _ = isStarting`; removed with its one main and three test call-site arguments.
- [x] T050c [RESTRUCTURE] (approved by the user 2026-10-10; one behaviour-preserving PR, no module move) Carve the
  bodies that M5, M6b, M7 and M8 could not byte-move into named core members, so the remaining moves (M5b, M6c, M7b,
  M8c) are whole-member moves: split `startSnapSync` (CQ-SNAP-016-22, CQ-SNAP-016-17's recovery branch), lift the
  healing, stagnation and bootstrap-retry arms out of `commonSyncingArms` (CQ-SNAP-016-18/19), and add the
  per-reason stagnation-clock reset table (CQ-SNAP-016-20) with a pin. Characterization first.
  - As built: four commits. (1) `SNAPStartSnapSyncCharacterizationSpec` (new, Tier 1) pins `startSnapSync`'s main
    paths that the S0d/fixture suites left unasserted: fresh start (network pivot requested and persisted as the
    bootstrap target; no peer; genesis; CL-hint pivot), all phases complete → healing, the accounts-complete recovery
    (spawn order and arguments, the bytecode child's start → budget 2 → budget 8 → cooldown reset, the task-file
    streams, the escalation-hint and missing-anchor wipes), a stale pivot (flags wiped when a phase is incomplete,
    kept when all are complete, then healing on the bootstrapped pivot) and bootstrap retry (interrupted bootstrap
    resumed, reached target cleared, retry → network pivot). (2) `startSnapSync` = `startSnapPeerEviction()` →
    `recoverAccountsComplete()` → `resumeInterruptedBootstrap()` → `selectPivotAndStart()` → `bootstrapping()`, plus
    `spawnRecoveryCoordinators(rootBs, storage, bytecodeAlreadyDone, storageAlreadyDone)` called from the recovery
    step; all `private[snap]`, bodies verbatim. The three boundary steps take `startSnapSync`'s
    `boundary.Label[Behavior[Command]]` as a `using` parameter, so every `break(...)` is unchanged. (3) Seven
    `commonSyncingArms` arms become `private[snap] lazy val` class members, each delegated from `commonSyncingArms` at
    its original position (the P4d `staleValidationDropArms` form): `requestTrieNodeHealingArms`,
    `healingServeRootArms`, `retryBootstrapAtBlockArms`, `healedCodeHashesArms`, `healedCodeHoldArms`,
    `stateHealingCompleteArms`, `checkDownloadStagnationArms`; the two guards that moved are pure flag reads. (4)
    `StagnationWatchdog extends StagnationResetApi` (`resetStagnationClocks(reason)`; not `reset`, which would overload
    `PhaseFlags.reset(kind)` in the core) with `StagnationWatchdog.ResetReason` = `AccountLaunch`,
    `AccountsCompleteRecovery`, `StoragePhaseEntry`, `PivotRefreshHeaderFetch`, `PivotRefreshCommit`,
    `BootstrapRetryAtBlock`, one per call site, each writing exactly the site's former inline writes in order (one
    `System.currentTimeMillis()` per write). The hook is a member of the `StagnationWatchdog` trait: it writes only
    `StagnationWatchdogState` fields the module already declares, so no state moves. `StagnationResetTablePinSpec`
    (new, Tier 1) pins, per reason, the fields reset and the fields kept, including the #1551 gaps (bytecode clocks
    on `AccountsCompleteRecovery`; `storageStagnationRefreshAttempted` and `consecutiveAccountStallRefreshes`, now
    `private[snap]` for the pin, on every reason). #1551 is not fixed. No existing test changed; counts unchanged
    (StagnationWatchdog 11 / 5). Not done here: the other two CQ-SNAP-016-17 sites (the resume selection inside
    `launchAccountRangeWorkers` and the `AccountRangeProgressCmd` checkpoint arm), and no `Restart`/`Wake` reason
    (those paths reset no stagnation clock today, so #1551 items 2 and 3 need a new call site as well as a table row).
    Next: M5b, M6c, M7b, M8c byte-moves of the named members.
- [x] T050d [M8c] Byte-move `startSnapSync`'s pivot-selection steps (named by T050c) into `PivotSelector`.
  - As built: three commits. (1) Prerequisite: verify.py learns the `# extended-tests: <exact paths>` trailer. M8c is
    the first slice to move more code into an already-narrowed module, and its narrowing extends M8's stub suite. On a
    narrowing commit, a named suite must be a module stub suite (`new Stub<Module>State`). It may gain assertions only in
    cases the commit adds (an added `... in {` opener earlier in the same hunk), never a skip, retagging or scheduling
    token (`ignore`, `pending`, `cancel`, `assume`, a `taggedAs` other than `UnitTest`, `Thread.sleep`, `eventually`,
    `within`, `timeout`, `interval`), and never loses one. It counts for FR-016 (b) if it gains at least one. Known
    limit: the case-opener check is per -U0 hunk, so an added line that git aligns into an existing case's hunk is
    attributed by position, not parsed. Nine new self-tests (73, OK). (2) The move commit moves `resumeInterruptedBootstrap`,
    `selectPivotAndStart` and `retryBootstrapAtBlockArms` (3 symbols; plan.md's M8 row lists `RetryBootstrapAtBlock`)
    into a temporary trait `PivotSelectorStart` appended to `controller/PivotSelector.scala` with the temporary
    self-type `SNAPSyncControllerImpl`. `minPivotHint` and `completePivotRefreshWithStateRoot` go `private` →
    `private[snap]`, and the core gains `with PivotSelectorStart`. (3) The narrowing commit drops that trait's two
    header lines, so its members fold into `PivotSelector` unchanged. Narrowed self-type: `PivotSelectorState &
    SnapSharedState & SnapControllerEnv & CoordinatorHandles & PhaseFlags & PeerPoolApi & HandshakedPeersApi &
    BootstrapPeersApi & HealingApi & LifecycleApi & ShutdownApi & SnapStartApi & SyncingApi & AccountRangeStartApi &
    StagnationResetApi & PivotRefreshCommitApi`.
    - State 9 against M8's 7: + `minPivotHint` and `pendingPivotRefresh`.
    - Api 18 against M8's 16: + `StagnationResetApi` (reused whole) and `PivotRefreshCommitApi`
      (`completePivotRefreshWithStateRoot`). The latter is new, declared in the module file and implemented by the
      core; a new trait, rather than growing `PivotRefreshApi`, keeps M6b's and M7's counts unchanged.
    - `CoordinatorHandles` (the ChainDownloader pause) joins the self-type. `SnapControllerEnv` stays at 18.

    The two steps keep their `using scala.util.boundary.Label[Behavior[Command]]` parameter. `startSnapSync` (still in
    the core) opens the boundary and calls them, and the Label resolves there as a context argument, so every `break`
    is unchanged and still exits `startSnapSync`. No `try`/`Try`/`NonFatal` sits between a `break` and the boundary.
    **Not moved**: `startSnapSync` (the boundary and the step sequence), `recoverAccountsComplete` and
    `spawnRecoveryCoordinators` (M5b / M11), `minPivotHint` (written by `idle`), and the dispatch (`idle`'s `Start` and
    `MinPivotBlock` arms, the `CLPivotHint` arm of `peerEventArms`, the delegation line in `commonSyncingArms`). Stub
    test: `PivotSelectorSpec` extended with nine cases (17 in all): interrupted bootstrap resumed; reached target
    cleared, then the no-peer backoff; network pivot; `MinPivotBlock` raise; genesis pivot; CL-head pivot; CL wait;
    the backtracked retry by header fetch and by in-place commit; the retry skipped outside the three phases.
    Platåberget from scratch: **pending** (to be run by the user on the slice jar after merge; no node run here).
- [x] T051 [M9] `SyncLifecycle`, including the #1501 heap watchdog (call sites unchanged). Run Mordor dormant/wake.
  - As built: no prerequisite commit. The move commit moves into `controller/SyncLifecycle.scala` the #1501 heap
    watchdog (its handle `heapWatchdog`, the one var the module owns, `ensureHeapWatchdog`, `onHeapWatchdogEscape`,
    `stopHeapWatchdog`, `onHeapPressureChange`), `recordCriticalFailure`, `enterDormantMode`, `wakeFromDormant` (with
    #1556's `resetStagnationClocks(ResetReason.Wake)` in its place, before `startSnapSync()`), the `dormantRetry`
    behaviour and `restartSnapSync` (10 symbols). The heap-watchdog call sites are unchanged: start in
    `spawnRecoveryCoordinators` and `launchAccountRangeWorkers`, stop in `onStop` and `stopSnapOnlySchedules`. The
    watchdog's start seam reaches the module as the trait's parameter, `trait SyncLifecycle(val heapWatchdogStart:
    HeapWatchdogStart)`, mixed in as `with SyncLifecycle(heapWatchdogStart)`; making the constructor parameter a `val`
    (the T041a/M5/M6b route) would edit the line next to the one #1559 (#1555) changes and conflict with it. Narrowed
    self-type: `SyncLifecycleState & SnapSharedState & SnapControllerEnv & CoordinatorHandles & PhaseFlags &
    HandshakedPeersApi & HealedCodeHoldApi & ShutdownApi & SnapStartApi & StagnationResetApi`; state 25 (R14: 16; the
    routing doc's counts row has the breakdown), Api 7 (R14a: 12). `SyncLifecycle extends LifecycleApi`, whose
    declaration moves next to it from `StateValidationModule.scala` (CQ-SNAP-016-16); `HealedCodeHoldApi`
    (`resetHealedCodeHold`) is new, declared in `HealingOrchestrator.scala` (`HealingOrchestrator extends` it).
    `DormantBaseDelay`, `DormantMaxDelay` and `DormantWakeUpKey` stayed in the core in the move (rule (d)) and became
    pure private lazy vals of the trait in the narrowing. `SnapControllerEnv` stays at 18. **Not moved**: the
    `DelayedRestart` and `PivotStateUnservable` arms (inline in `commonSyncingArms`, a local of `syncing()`; lifting
    them needs a delegation line, CQ-SNAP-016-27), the critical-failure and dormant counters (the
    `ProgressAccountsSynced` arm resets them), and `stopSnapOnlySchedules` / `stopStateSyncChildren` (plan.md's M9 row
    does not list them; they sit in `ShutdownApi` with the core's `onStop`; CQ-SNAP-016-26). #1555 (PR #1559) touches
    no M9 symbol. Stub test: `SyncLifecycleSpec` (eight cases). Mordor dormant/wake: **pending** (to be run by the user
    on the slice jar after merge; no node run here).
- [x] T052a [LIFT] (before M10; one behaviour-preserving PR, no module move; the second restructure slice after
  T050c) Lift the pivot-refresh, probe, unservable and restart arms out of `commonSyncingArms` (a local of `syncing()`)
  into named core members, so M10 (and an M9b, if the user wants `DelayedRestart` in `SyncLifecycle`) are whole-member
  moves. Characterization first. Resolves CQ-SNAP-016-27.
  - As built: three commits after the characterization commit. (1) `SNAPPivotRefreshArmsCharacterizationSpec` (new,
    Tier 1, 11 cases) pins what the existing suites left unasserted: the refresh-side `BootstrapComplete(None)` → full
    restart; `PivotBootstrapFailed` → backtrack by `pivotBlockOffset` and `RetryBootstrapAtBlock` after 5 s, or
    `RetryPivotRefresh` after 60 s when the backtracked block is not positive; an empty probe answer deferred and not
    forwarded, a non-probe `AccountRange` forwarded while the probe is pending, a late probe timeout ignored; on PoS in
    `StateHealing`, a counted `RetryPivotRefresh` keeps the #1555 unservable fallback (`retryRefreshUnservable`) and
    counts toward the re-peg budget; `PivotStateUnservable` counted refresh, the debounce re-arm (no count, no refresh),
    ignored outside the download phases; `DelayedRestart` restarts in `ByteCodeAndStorageSync` and is ignored in
    `StateHealing`. Not pinned: the forced roll after `MaxProbeAttempts` and the `MaxConsecutivePivotRefreshes`
    threshold (both need wall-clock time: `ProbeCooldownMs`, `MinPivotRestartInterval`). (2) Six arms become
    `private[snap] lazy val` class members, each delegated from `commonSyncingArms` at its original position (the
    T050c/P4d form), bodies verbatim: `delayedRestartArms`, `pivotStateUnservableArms`,
    `pivotRefreshBootstrapCompleteArms` (`BootstrapComplete if pendingPivotRefresh.isDefined`),
    `pivotRefreshBootstrapFailedArms` (`PivotBootstrapFailed if pendingPivotRefresh.isDefined`),
    `pivotProbeTimeoutArms`, `retryPivotRefreshArms`. The two moved guards are pure reads of `pendingPivotRefresh`; a
    message whose guard is false still falls through to the next arm. (3) Extract-method: the probe branch of the
    `AccountRangeResponse` arm becomes `handlePivotProbeResponse(msg)`, called from the same case; the arm keeps the
    probe-id match and the forwarding. No existing test changed; the `syncing()` composition is unchanged (T027 is the
    order oracle). Not done: no fix (#1561's gaps stay); the `AccountRangeResponse` arm itself stays inline (M11's
    response routing). Next: M10 (T052) moves the refresh members whole; `delayedRestartArms` and
    `pivotStateUnservableArms` go with M9b or M10, as the user decides (CQ-SNAP-016-27's note).
- [x] T052 [M10] `PivotRefresher`. Run Platåberget across at least 3 refreshes, and Mordor.
  - As built: no prerequisite commit. The move commit moves into `controller/PivotRefresher.scala`
    `refreshPivotInPlace`, `completePivotRefreshWithStateRoot`, the arms T052a lifted (`pivotRefreshBootstrapCompleteArms`,
    `pivotRefreshBootstrapFailedArms`, `pivotProbeTimeoutArms`, `retryPivotRefreshArms`, and `pivotStateUnservableArms`,
    which triggers a refresh), `handlePivotProbeResponse`, and the two vars only these bodies touch, `retryRefreshCounts`
    and `retryRefreshUnservable` (10 symbols). `commonSyncingArms` still delegates to the five arms at their positions
    and its `AccountRangeResponse` arm still calls `handlePivotProbeResponse`; only the definitions moved. The core loses
    three imports only the moved bodies used (`SNAP.*`, `PoSBlockHeaderValidator`, `BlockchainConfig`). Narrowed
    self-type: `PivotRefresherState & SnapSharedState & SnapControllerEnv & CoordinatorHandles & PhaseFlags &
    LifecycleApi & PeerPoolApi & PivotSelectionApi & PivotBestBlockApi & ProbeTargetApi & FinalizationApi &
    ChainDownloadStartApi & LazyHandoffApi & StagnationResetApi`; state 20 (R14: 11), Api 13 (R14a: 12); the routing
    doc's counts row has the breakdown. `PivotRefresher extends PivotRefreshApi with PivotRefreshCommitApi`, whose
    declarations move next to it from `controller/HealingOrchestrator.scala` and `controller/PivotSelector.scala`
    (CQ-SNAP-016-16); the core no longer lists them. Four new narrow Api traits, each declared next to and
    implemented by its module (members lose `private[snap]`), rather than growing an Api other modules already count:
    `PivotBestBlockApi` (`updateBestBlockForPivot`, `PivotSelector`), `ProbeTargetApi` (`bestSnapProbeTarget`,
    `SnapPeerPool`), `ChainDownloadStartApi` (`startChainDownloader`, `SnapFinalization`), `LazyHandoffApi`
    (`lazyHandoffBlockedByAbsentPathRoot`, `anchorPivotBeforeLazyHandoff`, `HealingOrchestrator`). `MaxProbeAttempts`,
    `MaxConsecutivePivotRefreshes`, `failedPivotBlocks` and `MaxHealRepegNoRootAttempts` stayed in the core in the move
    (rule (d)) and became pure private lazy vals of the trait in the narrowing. `SnapControllerEnv` stays at 18; no
    other module's count moves. **Not moved**: `delayedRestartArms` (a restart: SyncLifecycle, M9b),
    `retryBootstrapAtBlockArms` (PivotSelector, M8c, CQ-SNAP-016-25), `healAtNewerPivot` (the #1555 branch of
    `recoverAccountsComplete`, resume planning, M5b; CQ-SNAP-016-29), the proactive roll's trigger (in
    `checkDownloadStagnationArms`, M7's), the dispatch (the five delegation lines and the probe-id match), and the
    refresh and probe state the core, `SyncLifecycle`, `StagnationWatchdog`, `HealingOrchestrator` or `PivotSelector`
    also read or write. No fix: #1561's gaps stay. Stub test: `PivotRefresherSpec` (ten cases). Platåberget across at
    least 3 refreshes, and Mordor: **pending** (to be run by the user on the slice jar after merge; no node run here).
- [x] T053 [M11] `DownloadSupervisor`. Check SC-001 (core ≤ about 1,000 lines). Run a full Mordor SNAP.
  - As built (partial by design: whole members only, no lift; CQ-SNAP-016-32 and -33 hold the rest): no prerequisite
    commit. The move commit moves into `controller/DownloadSupervisor.scala` `startAccountRangeSync`,
    `spawnRecoveryCoordinators` (named by T050c), the three peer-availability ticks `requestAccountRanges`,
    `requestByteCodes`, `requestStorageRanges`, `checkAllDownloadsComplete` and `currentSyncStatus` (7 symbols).
    `launchAccountRangeWorkers` and `recordStorageDone` go `private` -> `private[snap]` (the moved bodies reach them),
    and four moved members go `private` -> `private[snap]` (the core still calls them). Narrowed self-type:
    `DownloadSupervisorState & SnapSharedState & SnapControllerEnv & CoordinatorHandles & PhaseFlags & PeerPoolApi &
    SnapServerPeersApi & SnapPeerEvictionApi & ChainDownloadStartApi & FinalizationApi & HealingApi &
    AccountRangeLaunchApi`; state 4 (R14: 8; `coordinatorGeneration`, `snapProgressStorage`, `recordStorageDone`,
    `SnapCapabilityCheckKey`, all read-only here), Api 10 (R14a: 13). `DownloadSupervisor extends AccountRangeStartApi
    with ByteCodeRequestApi`, whose declarations move next to it from `controller/PivotSelector.scala` and
    `controller/HealingOrchestrator.scala` (CQ-SNAP-016-16); the core no longer lists them. Two new narrow Api traits:
    `SnapPeerEvictionApi` (`startSnapPeerEviction`, declared in and implemented by `SnapPeerPool`, the member loses
    `private[snap]`) and `AccountRangeLaunchApi` (`launchAccountRangeWorkers`, declared in the module file and
    implemented by the core until its carve-out). The capability traits are unchanged (5 / 18 / 12 / 10); no other
    module's count moves. The trait has no member `val` or `var`, so rule (d) held nothing back and no `val` became a
    `lazy val`. **Not moved**: every M11 arm of `syncing` (the request ticks, the four `*Response` routing arms, the
    `Progress*` arms, `AccountTrieFinalized`/`AccountTrieFinalizationFailed`, the two `*BackpressureChanged` arms,
    `IncrementalContractData` and the four phase-completion arms), all inline in `commonSyncingArms` (a local of
    `syncing()`), so neither commit can take them (CQ-SNAP-016-32: an M11 lift slice, then an M11b move);
    `launchAccountRangeWorkers`, whose first ~130 lines are M5's inline resume selection (CQ-SNAP-016-33, the
    CQ-SNAP-016-17 residual); `bytecodesEstimatedTotal` and `storageContractProgressPct` (written by those core arms;
    no moved body reads them); `recoverAccountsComplete` and `healAtNewerPivot` (M5b, CQ-SNAP-016-29);
    `delayedRestartArms` (M9b); `stopSnapOnlySchedules` / `stopStateSyncChildren` (CQ-SNAP-016-26);
    `storageInFlightDuringAccounts` (a companion helper the plan does not list). No fix. Stub test:
    `DownloadSupervisorSpec` (eight cases). SC-001: **not met** at this slice. `SNAPSyncController.scala` is 2,947 lines
    (the class 2,176, the companion 771) against about 1,000; the biggest remaining members are `commonSyncingArms`
    (~485 lines), `recoverAccountsComplete` (~290) and `launchAccountRangeWorkers` (~280), so SC-001 needs
    CQ-SNAP-016-32/33 and M5b. A full Mordor SNAP: **pending** (to be run by the user on the slice jar after merge; no
    node run here).
- [x] T053a [LIFT] (after M11, before M11b and M5b; one behaviour-preserving PR, no module move; the third lift slice
  after T050c and T052a) Lift M11's arms (and the `CheckSnapCapability` arm) out of `commonSyncingArms` (a local of
  `syncing()`) into named core members, and extract the resume selection out of `launchAccountRangeWorkers`, so M11b
  and M5b are whole-member moves. Characterization first. Resolves CQ-SNAP-016-32 and CQ-SNAP-016-33.
  - As built: three commits after the characterization commit. (1) `SNAPDownloadArmsCharacterizationSpec` (new, Tier 1,
    12 cases, `SnapControllerFixture`, ManualTime) pins what the existing suites left unasserted: `CheckSnapCapability`
    with a snap peer launches the account phase at `accountConcurrency.max(1)` on the current root (reached from
    `ByteCodeAndStorageSync`); each `Request*` tick offers the snap peer to its own coordinator only; the progress
    counters, account estimate, storage-contract figures and the extrapolated slot estimate (none with no completed
    contract); the trie-finalizing flag, the persisted finalized root and the restart on `AccountTrieFinalizationFailed`;
    the backpressure forwards in order; `IncrementalContractData` forwarding (`replayed` -> `skipPresent`), the
    accumulated bytecode estimate and no credit release; `AccountRangeSyncComplete` (accounts-complete persisted, resume
    checkpoint retired, the two file-info asks, NoMore and the budget rebalance in order, `ByteCodeAndStorageSync`,
    duplicate ignored); in `AccountRangeSync`, bytecode completion persists and boosts storage to `maxInFlightPerPeer`
    only while storage is incomplete, storage completion persists, a second completion falls through to the catch-all,
    and a storage force-complete is ignored without setting the flag. Not pinned: the wall-clock stagnation clocks the
    progress arms write, the `consecutivePivotRefreshes` / critical-failure resets of `ProgressAccountsSynced` (behind
    the wall-clock `MinPivotRestartInterval` debounce), the `TrieNodesResponse` drop in `StateValidation`. (2) Twenty-five
    arms become ten `private[snap] lazy val` class members, bodies verbatim, each delegated from `commonSyncingArms` at
    its original position (the T050c/T052a/P4d form); adjacent arms of one concern share a member, in their original
    order: `checkSnapCapabilityArms`, `downloadTickArms` (the three ticks), `snapResponseArms` (the four `*Response`
    arms; `AccountRangeResponse` still calls `handlePivotProbeResponse`), `progressAccountsSyncedArms`,
    `accountTrieFinalizationArms` (`ProgressAccountsFinalizingTrie`, `AccountTrieFinalized`,
    `ProgressAccountsTrieFinalized`, `AccountTrieFinalizationFailed`), `downloadProgressArms` (the other five
    `Progress*` arms), `downloadBackpressureArms`, `incrementalContractDataArms`, `accountRangeSyncCompleteArms`,
    `downloadPhaseCompleteArms` (the three guarded completion arms; the guards are pure flag reads). Replacing each
    delegation line with its member's body gives back the old `commonSyncingArms` line for line; the 47 expanded case
    headers are unchanged in content and order. (3) Extract-method: `launchAccountRangeWorkers`'s resume selection (126
    lines, from the checkpoint read to the marker clear) becomes `selectAccountResume(rootHash, effectiveConcurrency,
    currentPivot): (Map[ByteString, ByteString], Option[ContractTaskFiles])`, verbatim, called at the same point and
    bound to the old names; `effectiveConcurrency`, the `AccountLaunch` stagnation reset and the single `currentPivot`
    read stay in the caller (the spawns read them too). No `boundary`/`break` or `try` is involved. No existing test
    changed; the `syncing()` composition is unchanged (T027 is the order oracle). Not lifted: `AccountRangeProgressCmd`
    (M5's checkpoint arm, CQ-SNAP-016-17's residual; not in CQ-SNAP-016-32's list), and the non-M11 inline arms
    (`TuneRateTracker`, `EvictNonSnapPeers`, `EnsureSnapServerPeersConnected`, `StateValidationComplete`,
    `ChainDownloaderProgress`/`Done`, `GetStatus`). No fix. Next: M11b moves the ten members and the rest of
    `launchAccountRangeWorkers` into `DownloadSupervisor` (dropping `AccountRangeLaunchApi`); M5b moves
    `selectAccountResume` with `recoverAccountsComplete` into `SnapResumePlanner`.
- [x] T053b [M11b] Byte-move the members T053a lifted, and the non-resume remainder of `launchAccountRangeWorkers`, into
  `DownloadSupervisor`. Check SC-001 again.
  - As built: three commits. (1) Prerequisite: `AccountResumeSelectionApi` (`selectAccountResume`) is declared in
    `controller/DownloadSupervisor.scala` and implemented by the core, whose member loses `private[snap]`; scalafmt
    wraps the declaration, so it cannot go into the narrowing commit (the M6b precedent). (2) The move commit moves 11
    symbols, byte-identical, into a temporary trait `DownloadSupervisorArms` appended to the module file with the
    temporary self-type `SNAPSyncControllerImpl` (the M8c pattern): `checkSnapCapabilityArms`, `downloadTickArms`,
    `snapResponseArms`, `progressAccountsSyncedArms`, `accountTrieFinalizationArms`, `downloadProgressArms`,
    `downloadBackpressureArms`, `incrementalContractDataArms`, `accountRangeSyncCompleteArms` (the routing doc lists
    it for the M11b move with the other arms; its M5 persistence comes along unchanged), `downloadPhaseCompleteArms`
    and `launchAccountRangeWorkers` (the stagnation reset, the call to `selectAccountResume`, the spawns, timers and
    budgets). Five core fields go `private` -> `private[snap]` (`storageContractProgressPct`, `preservedTaskFiles`,
    `launchedAccountGeneration`, `currentCarrySource`, `AccountProgressResetThreshold`), and the core drops the
    `SupervisorStrategy` import only the launch used. `AccountProgressResetThreshold` stayed in the core in the move
    (rule (d)). (3) The narrowing commit drops the temporary trait's two header lines, so its members fold into
    `DownloadSupervisor`; `AccountProgressResetThreshold` becomes a pure private lazy val of the trait (pure: yes, a
    literal); `AccountRangeLaunchApi` is deleted (the module now implements `launchAccountRangeWorkers`, and nothing
    else named it). Narrowed self-type: `DownloadSupervisorState & SnapSharedState & SnapControllerEnv &
    CoordinatorHandles & PhaseFlags & PeerPoolApi & SnapServerPeersApi & SnapPeerEvictionApi & ChainDownloadStartApi &
    FinalizationApi & HealingApi & LifecycleApi & PivotProbeResponseApi & StagnationResetApi & StagnationScheduleApi &
    StorageDoneMarkersApi & ResumeApi & HeapWatchdogApi & AccountResumeSelectionApi`.
    - State 22 against M11's 4: + 18, each kept in the core because code that stays also reads or writes it (the M7
      clocks, the M9 counters, the M10 probe and refresh state, the M5 resume state, `storageContractProgressPct`,
      `bytecodesEstimatedTotal`, `storageDoneStorage`) and `ec`, the core's implicit constructor `val`, which the file-info
      asks of `accountRangeSyncCompleteArms` run their callbacks on; it is declared `implicit def ec` so the body is
      unchanged. The routing doc's counts row lists them.
    - Api 19 against M11's 10: − `AccountRangeLaunchApi` (1); + `LifecycleApi` (3), `StagnationResetApi` (1) and
      `ResumeApi` (1), reused whole; + five new narrow traits, each declared next to its implementer, whose member loses
      `private[snap]`: `PivotProbeResponseApi` (`handlePivotProbeResponse`, `PivotRefresher`), `StagnationScheduleApi`
      (`scheduleStagnationChecks`, `StagnationWatchdog`), `StorageDoneMarkersApi` (`clearStorageDoneMarkers`,
      `SnapResumePlanner`), `HeapWatchdogApi` (`ensureHeapWatchdog`, `SyncLifecycle`), and `AccountResumeSelectionApi`
      (`SSC` until M5b). No other module's count moves; the capability traits are unchanged (5 / 18 / 12 / 10).
    - Stub test: `DownloadSupervisorSpec` extended (`# extended-tests:`) with eleven cases (19 in all): the capability
      check's launch and dormant paths; a tick arm; the probe answer vs coordinator routing and the `StateValidation`
      `TrieNodes` drop; the account-progress and failure-counter resets; trie finalization (flag, persisted root,
      restart); the progress clocks, contract share and slot estimate; the backpressure forwards; the contract-data
      forwards, estimate and credit release; the storage-phase entry and its duplicate; the phase completions and the
      force-complete skip; and the real `launchAccountRangeWorkers` (resume selection, carry source, generation,
      spawns, timers, budgets). The stub drops `AccountRangeLaunchApi`; the harness keeps M11's stubbed launch for the
      existing cases by overriding `launchAccountRangeWorkers` in its anonymous module instance (fixture construction;
      no existing assertion changes), and the launch case runs the module's own.
  - **Not moved**: the dispatch (the ten delegation lines in `commonSyncingArms`), `selectAccountResume` and
    `AccountRangeProgressCmd` (M5b), and the 18 fields above. No fix. The scaladocs of `DownloadSupervisor`,
    `SnapResumePlanner`, `SyncLifecycle` and `PivotRefresher` still describe the arms and the launch as core code: a
    move or narrowing commit cannot edit comments (CQ-SNAP-016-34). SC-001: **not met**. `SNAPSyncController.scala`
    is 2,518 lines (the class 1,747, the companion 771) against about 1,000; the biggest remaining members are
    `commonSyncingArms`, `recoverAccountsComplete` (~290) and `selectAccountResume` (~130), so SC-001 needs M5b and the
    remaining small slices. A full Mordor SNAP: **pending** (to be run by the user on the slice jar after merge; no node
    run here).
- [x] T046b [M5b] Byte-move the resume code M5 could not take (CQ-SNAP-016-17), now named core members, into
  `SnapResumePlanner`: `recoverAccountsComplete` (T050c), its #1555 branch `healAtNewerPivot` (CQ-SNAP-016-29) and
  `selectAccountResume` (T053a). Check SC-001 again.
  - As built: three commits. (1) Prerequisite: the core's `failedFinalizationPivot` constructor parameter (#1555)
    becomes a `val` (the recovery reads it; the M5 `scheduler` route); `AccountResumeSelectionApi` moves unchanged from
    `controller/DownloadSupervisor.scala` to `controller/SnapResumePlanner.scala` (CQ-SNAP-016-16); and
    `DownloadRecoveryApi` (`spawnRecoveryCoordinators`, `checkAllDownloadsComplete`) is declared in
    `controller/DownloadSupervisor.scala`, because scalafmt wraps it (the M6b / M11b precedent); and
    `SnapControllerEnv.ctx` becomes an abstract `val` (was a `def`; count unchanged), because the recovery's
    `import ctx.executionContext` needs a stable import prefix through the narrowed self-type. Every implementer already
    used a constructor `val`. verify.py's rule (d) now admits an abstract member `val` (no initializer, nothing runs at
    construction), with two self-tests, and plan.md D1's (d) list says so. (2) The move commit
    moves the 3 symbols, byte-identical, into a temporary trait `SnapResumePlannerRecovery` appended to the module file
    with the temporary self-type `SNAPSyncControllerImpl` (the M8c pattern): `healAtNewerPivot`,
    `recoverAccountsComplete`, `selectAccountResume`. `MaxPreservedPivotDistance` goes `private` -> `private[snap]` and
    stays in the core (rule (d)); the core drops the `scala.util.Try` and `utils.Hex` imports only the moved code used.
    (3) The narrowing commit drops the temporary trait's two header lines, so its members fold into
    `SnapResumePlanner`; `MaxPreservedPivotDistance` becomes a pure private lazy val of the trait (pure: yes, the literal
    50_000). Narrowed self-type: `SnapResumePlannerState & SnapSharedState & SnapControllerEnv & CoordinatorHandles &
    PhaseFlags & PivotSelectionApi & BootstrappingApi & SyncingApi & HeapWatchdogApi & DownloadRecoveryApi &
    ByteCodeRequestApi & StagnationResetApi & StagnationScheduleApi & ChainDownloadStartApi & SnapServerPeersApi`;
    `SnapResumePlanner` also `extends AccountResumeSelectionApi`, and the core no longer lists it.
    - State 15 against M5's 3: + `minPivotHint`, `isPoSChain`, `clPivotHint`, `failedFinalizationPivot`,
      `coordinatorGeneration`, `recordStorageDone`, `SnapServeWindowMargin`, `BootstrapCheckKey`,
      `preservedRangeProgress`, `preservedAtPivotBlock`, `preservedTaskFiles`, `snapProgressStorage`, each kept in the
      core because code that stays also reads or writes it. The routing doc's counts row has the breakdown.
    - Api 12 against M5's 0 (R14a: 14): eight reused whole (`PivotSelectionApi`, `SyncingApi`, `HeapWatchdogApi`,
      `ByteCodeRequestApi`, `StagnationResetApi`, `StagnationScheduleApi`, `ChainDownloadStartApi`,
      `SnapServerPeersApi`) and two new narrow traits, each declared next to its implementer: `BootstrappingApi`
      (`bootstrapping`, `PivotSelector`) and `DownloadRecoveryApi` (`DownloadSupervisor`, whose two members lose
      `private[snap]`). No other module's count moves; the capability traits are unchanged (5 / 18 / 12 / 10).
    - How the boundary Label crosses: `recoverAccountsComplete` and `healAtNewerPivot` keep their `using
      scala.util.boundary.Label[Behavior[Command]]` parameter. `startSnapSync` (still in the core) opens the boundary and
      calls `recoverAccountsComplete`, which passes the Label on to `healAtNewerPivot`, so every `break` is unchanged and
      still exits `startSnapSync`. No `try`/`Try`/`NonFatal` sits between a `break` and the boundary, and no `break`
      runs inside a deferred callback (the three are in match arms and if branches, outside the `runGatedReplay` and
      `onComplete` closures). The #1555 failed-pivot gating and the CL drift branch moved byte-identical.
    - Stub test: `SnapResumePlannerSpec` extended (`# extended-tests:`) with eight cases (14 in all): the recovery falls
      through with nothing saved and clears the flags of a recovery with no saved pivot; a fresh saved pivot resumes
      bytecodes and storage and breaks out with `syncing()` (pivot, root, flags, generation, heap watchdog, recovery
      spawns, phase, `AccountsCompleteRecovery` reset, the schedules and the completion check); a saved pivot below the
      escalation hint clears the recovery and falls through; on PoS a failed-finalization pivot with no newer target
      retries the start on `BootstrapCheckKey` in 30 s and breaks out with `bootstrapping()`, and with a CL head past it
      bootstraps `clHead - pivotBlockOffset`; the resume selection keeps in-cap preserved cursors (latch, marker
      clear), drops out-of-cap ones with their progress record and checkpoint, and recovers cursors from the progress
      store. The two steps run under a `boundary` of the core's type, as `startSnapSync` runs them. Nothing waits on a
      clock: the retry is a recorded timer.
  - **Not moved**: `startSnapSync` (the boundary and the step sequence), the `AccountRangeProgressCmd` checkpoint arm
    (still inline in `commonSyncingArms`, a local of `syncing()`; not lifted here, CQ-SNAP-016-36), and the fields
    above. No fix. The scaladocs of `SnapResumePlanner` ("the core keeps the resume decisions that sit inside
    `startSnapSync` and `launchAccountRangeWorkers`"), of `DownloadSupervisor` and of the moved members, and the core's
    comment above the former `MaxPreservedPivotDistance`, still describe the old layout: a move or narrowing commit
    cannot edit comments (CQ-SNAP-016-37, with CQ-SNAP-016-34). SC-001: **not met**. `SNAPSyncController.scala` is
    2,033 lines (the class 1,262, the companion 771) against about 1,000, down from 2,518; the biggest remaining member
    is `commonSyncingArms`. Mordor and Platåberget restarts mid-account and mid-storage: **pending** (to be run by the
    user on the slice jar after merge; no node run here).
- [x] T053c [LIFT] (after M5b, before T054/T055; one behaviour-preserving PR, no module move; the fourth and last lift
  slice after T050c, T052a and T053a) Lift the `AccountRangeProgressCmd` checkpoint arm out of `commonSyncingArms` (a
  local of `syncing()`) into a named core member, and extract the proactive pivot-roll trigger out of
  `checkDownloadStagnationArms` into a named helper, so M5c and M7b are whole-member moves; record the user's
  CQ-SNAP-016-26 decision for M9b. Characterization first. Resolves CQ-SNAP-016-31 and CQ-SNAP-016-36.
  - As built: three commits after the characterization commit (pushed alone first as the pre-refactor Tier 1
    baseline). (1) `SNAPFinalLiftCharacterizationSpec` (new, Tier 1, 12 cases, `SnapControllerFixture`, ManualTime,
    synchronous log capture) pins what the existing suites left unasserted: the checkpoint arm drops a superseded
    generation's snapshot (nothing persisted, swept or kept for the next launch) and a snapshot after accounts
    complete; persists a durable snapshot with task files as the versioned checkpoint at the current root and pivot,
    logs its range counts, and sweeps superseded task files once per record; keeps a durable snapshot without task
    files and a non-durable one in memory only (the next launch resumes from them); and records the pivot the cursors
    were last advanced against after an in-place refresh. The roll trigger: 100 blocks of age does not roll, 101 does;
    50 blocks past the last committed refresh does not roll, 51 does; in `ByteCodeAndStorageSync` 80 % of contracts
    done does not roll, 79 % does; `StateHealing` does not roll; no second roll while the probe is in flight. At SNAP
    finalisation the state-sync child is stopped and the ChainDownloader is not. Already pinned and not repeated: the
    roll firing and its probe commit and timeout (T023), `delayedRestartArms` in `AccountRangeSync` (T022) and in
    `ByteCodeAndStorageSync` / `StateHealing` (T052a), the heap-watchdog stop in `onStop` and `stopSnapOnlySchedules`
    (T025). Not pinned (no deterministic observation point): the wall-clock 30-s probe cooldown, the timers and the two
    scheduler flags `stopSnapOnlySchedules` clears (`completedWithBackfill` drops every leaked tick),
    `stopPeriodicLogging`, and the two flags `stopStateSyncChildren` clears. (2) CQ-SNAP-016-36: the arm becomes
    `private[snap] lazy val accountProgressCheckpointArms`, body verbatim (re-indented), delegated from
    `commonSyncingArms` at its original position (between the `progressAccountsSyncedArms` and
    `accountTrieFinalizationArms` delegations) with `case msg if X.isDefinedAt(msg) => X(msg)`. Its header has no
    guard, so `isDefinedAt` is a pure pattern match. Replacing the delegation line with the body gives back the old
    `commonSyncingArms` line for line (checked mechanically); the expanded case headers are unchanged in content and
    order. (3) CQ-SNAP-016-31: extract-method. The `[PIVOT-ROLL]` block becomes `private[snap] def
    maybeProactivePivotRoll(): Unit`, 23 lines verbatim, called by `checkDownloadStagnationArms` at the same point
    (after the debug line, before the zero-peer check and the asks). Nothing flows in or out (the block reads class
    state only; the arm's local `Timeout` given is unused by it), so the statement order and every read and write are
    unchanged; inlining the call gives back the old arm line for line. **Owner: `PivotRefresher`.** plan.md's M10 row
    gives the pivot refresher the vars the trigger writes (`proactiveRollNeedsProbe`, the probe state) and the
    `[PIVOT-ROLL]` tag; M7 gets only the `CheckDownloadStagnation` arm. CQ-SNAP-016-26: the user decided the stop
    helpers belong to `SyncLifecycle`; both are already whole `def` members of the core, so no code change. No existing
    test changed; the `syncing()` composition is unchanged (T027 is the order oracle). No fix. `verify.sh` OK on every
    commit; the storage-reference baseline is unchanged (the core is outside `snap/controller/` and `snap/actors/`).
    `SNAPSyncController.scala` is 2,055 lines (the class 1,216, the companion 771; +22 for two scaladocs and a
    delegation). Next: M5c (T046c), M7b (T049b), M9b (T051b).
- [x] T046c [M5c] Byte-move `accountProgressCheckpointArms` (T053c) into `SnapResumePlanner`; the delegation line stays
  in `commonSyncingArms` at its position. The arm reads and writes `launchedAccountGeneration`, `currentCarrySource`,
  `lastSweptForRecord` (today `private var`) and `preserved*`, and calls `sweepSupersededTaskFiles` and
  `accountsCompleteTaskFilePaths` (already the module's), so the narrowing commit adds the fields that stay in the core
  (shared with the account launch and the refresher) to `SnapResumePlannerState`, with a justification line per
  member. `lastSweptForRecord` is written by this arm only and may move with it. `SnapResumePlannerSpec` is extended
  (`# extended-tests:`). Review: **vault** (the checkpoint format is frozen) + prism + forge.
  - As built: two commits, after T054c (the core is in the storage ratchet's measured set). (1) The move commit moves
    `lastSweptForRecord` (with its comment) and `accountProgressCheckpointArms` (with its scaladoc), byte-identical,
    into a temporary trait `SnapResumePlannerCheckpoint` appended to the module file with the temporary self-type
    `SNAPSyncControllerImpl` (the M8c pattern); the core gains `with SnapResumePlannerCheckpoint`, and the module file
    imports `blockchain.sync.snap.*` (the self-type) and pekko's `Behaviors`. No `private` -> `private[snap]` change:
    every core field the arm reads is already public or reached through a hub. The storage-reference rows move with
    the code (`SNAPSyncController.scala` 64 -> 62, `controller/SnapResumePlanner.scala` 93 -> 95; total 692,
    unchanged, no justification). (2) The narrowing commit drops the temporary trait's two header lines, so the members
    fold into `SnapResumePlanner`; `lastSweptForRecord` stays a `private var` (initializer `None`, rule (d)), now of
    the trait. Self-type unchanged.
    - State 17 against M5b's 15: + `launchedAccountGeneration` and `currentCarrySource`, read by the arm and written by
      the account launch (`DownloadSupervisor`), so they stay in the core; both are read-only `def`s here
      (`DownloadSupervisorState` keeps them as `var`s).
    - Api 12 against M5b's 12: the arm calls only the module's own members, and `accountsComplete` comes through
      `PhaseFlags`, already in the self-type. No other module's count moves; the capability traits are unchanged
      (5 / 18 / 12 / 10).
    - Stub test: `SnapResumePlannerSpec` extended (`# extended-tests:`) with four cases (18 in all): the arm is defined
      for `AccountRangeProgressCmd` only; a superseded generation's snapshot and one after accounts complete are
      dropped (nothing preserved, persisted or swept); a durable snapshot with task files at a root and pivot is
      persisted as the versioned checkpoint, sweeps superseded task files (keeping its own files and the carry
      source), does not sweep again for the same record, and sweeps again for a record over new files; a durable
      snapshot without task files, a non-durable one and one with no state root are kept in memory only, with the
      tracked pivot following the current pivot or staying put without one.
  - **Not moved**: the delegation line in `commonSyncingArms` and the two fields above. No fix. The scaladocs of
    `SnapResumePlanner` ("the core keeps ... the checkpoint arm of `syncing`") and of the moved arm ("lifted into a
    class member") still describe the old layout: a move or narrowing commit cannot edit comments (CQ-SNAP-016-37,
    with CQ-SNAP-016-34). SC-001: **not met**. `SNAPSyncController.scala` is 1,989 lines (the class 1,150, the
    companion 771) against about 1,000, down from 2,055. Mordor and Platåberget restarts mid-account: **pending** (to
    be run by the user on the slice jar after merge; no node run here).
- [x] T049b [M7b] Byte-move the stagnation tick and its roll trigger (T053c) to their owners:
  `checkDownloadStagnationArms` into `StagnationWatchdog` (plan.md M7's `CheckDownloadStagnation` arm, with the
  progress `ask`s and `ZeroPeerStagnationMs`), and `maybeProactivePivotRoll` into `PivotRefresher` (plan.md M10's
  `[PIVOT-ROLL]` state), added to `PivotRefreshApi`, which the watchdog already names. `SnapServeWindowBlocks` moves with
  the trigger or joins `PivotRefresherState`; `lastProactivePivotBlock` and `ProbeCooldownMs` then need not stay in
  the core for the trigger (CQ-SNAP-016-31). The arm keeps its delegation line in `commonSyncingArms`. One PR or two
  (one per module), each with its move and narrowing commits; the stub suites of both modules are extended.
  - As built: one PR, four commits, two per module, PivotRefresher first so the watchdog's narrowing can name the
    new `PivotRollTriggerApi` (plan.md "one module per PR" is read against T049b's "one PR or two (one per module), each
    with its move and narrowing commits"; `verify.py` checks every commit, and each move commit adds exactly one
    temporary self-type to one module file). (1) Move: `maybeProactivePivotRoll` (with its scaladoc), byte-identical,
    into a temporary trait `PivotRefresherRoll` appended to `controller/PivotRefresher.scala` with the temporary
    self-type `SNAPSyncControllerImpl`; the core gains `with PivotRefresherRoll`, `SnapServeWindowBlocks` goes `private`
    -> `private[snap]`. (2) Narrowing: the trait's two header lines go, so the member folds into `PivotRefresher`;
    a new narrow trait `PivotRollTriggerApi` (`def maybeProactivePivotRoll(): Unit`), declared in the module file, is
    extended by `PivotRefresher`, and the member loses `private[snap]` to implement it (review of #1576: the M8c
    precedent, a new narrow trait rather than growing `PivotRefreshApi` as T053c first said, so healing's count stays);
    `SnapServeWindowBlocks` becomes a pure private lazy val of the trait. (3) Move:
    `checkDownloadStagnationArms` (with its scaladoc), byte-identical, into a temporary trait `StagnationWatchdogTick`
    placed after `StagnationWatchdog` and before its companion object; the core gains `with StagnationWatchdogTick`,
    `ZeroPeerStagnationMs` goes `private` -> `private[snap]`. (4) Narrowing: the header lines go, the member folds into
    `StagnationWatchdog`, `PeerPoolApi` and `PivotRollTriggerApi` join its self-type, and `ZeroPeerStagnationMs` becomes a pure private lazy val
    of the trait. Neither body carries a storage reference: every `storage-baseline.json` row and the total (692) are
    unchanged, so the baseline file is untouched. The core needs no import change (the arm's `Timeout`,
    `Success`/`Failure` and `AskPattern` imports are local to its body).
    - PivotRefresher 22 / 13 against M10's 20 / 13: State + `lastProbeAttemptMs` (the probe cooldown clock; the
      lifecycle's `restartSnapSync` also clears it) and `storageContractProgressPct` (written by the
      `ProgressStorageContracts` arm; a read-only `def` here). `lastProactivePivotBlock` and `ProbeCooldownMs` stay State
      members: `PivotRefresherSpec`'s stub implements both and an existing case asserts `lastProactivePivotBlock`
      through it, so moving them into the trait would change an existing test.
    - StagnationWatchdog 11 / 8 against M7's 11 / 5: Api + `PeerPoolApi` (2; the tick counts SNAP peers among
      `peersToDownloadFrom`) and + `PivotRollTriggerApi` (1, `maybeProactivePivotRoll`; the watchdog's stub implements
      it with a counting member). `PivotRefreshApi` is unchanged, so HealingOrchestrator stays 12 / 10 and no other
      module's count moves. Capability traits unchanged (5 / 18 / 12 / 10).
    - Stub tests (`# extended-tests:`): `PivotRefresherSpec` + 5 cases (15 in all): the 100/101-block age boundary
      (probe flag, cooldown stamp, header request for best - 64, the `PivotRefreshHeaderFetch` reset); 50/51 blocks
      past the last roll and no SNAP best block; a probe in flight or a deferred probed commit; 80 % / 79 % of contracts
      in `ByteCodeAndStorageSync`; `StateHealing`, no pivot, and the 30-s cooldown (a stamp an hour ahead; T053c could
      not pin the cooldown through the controller). `StagnationWatchdogSpec` + 5 cases (18 in all): the arm is defined
      for `CheckDownloadStagnation` only; every tick runs the roll trigger and outside the download phases asks
      nothing; the zero-SNAP-peer log after 3 minutes in account sync only, not with a SNAP peer or fresh progress; in a
      live actor under ManualTime, the account ask's answer is piped back and an unanswered ask pipes zero stats after
      2 s (the `given Timeout`); in the storage phase both storage and bytecode are asked, the unanswered bytecode ask
      pipes zero progress, and a completed bytecode phase asks storage only.
  - **Not moved**: the `CheckDownloadStagnation` delegation line in `commonSyncingArms`, and the State members above.
    No fix. The scaladocs of `PivotRefresher` ("It also keeps the proactive roll's trigger (the
    `CheckDownloadStagnation` arm)"), of `StagnationWatchdog` ("The core keeps ... the `CheckDownloadStagnation` arm"),
    of the moved arm ("lifted into a class member") and of the moved trigger ("M7b moves it to `PivotRefresher`") still
    describe the old layout: a move or narrowing commit cannot edit comments (CQ-SNAP-016-37, with CQ-SNAP-016-34).
    SC-001: **not met**. `SNAPSyncController.scala` is 1,882 lines (the class 1,043, the companion 771) against about
    1,000, down from 1,989. Platåberget across refreshes and a Mordor stall: **pending** (to be run by the user on the
    slice jar after merge; no node run here).
- [x] T051b [M9b] Byte-move `delayedRestartArms` (T052a) and the stop helpers `stopSnapOnlySchedules` and
  `stopStateSyncChildren` into `SyncLifecycle` (CQ-SNAP-016-26, user decision 2026-10-11). Prerequisite: split
  `ShutdownApi` (`controller/SnapFinalization.scala`) so the core's `onStop` keeps its own declaration and the two
  helpers get a narrow Api declared next to `SyncLifecycle`, which `SnapFinalization` then names. The narrowing commit
  adds what the helpers write that stays in the core (`snapPeerEvictionStarted`, `snapServerPeersSchedulerStarted`,
  `forceCompleteStorageSent`, `healingWalkLocalOnly`, the timer keys they cancel, `progressMonitor` if not already
  reachable) to `SyncLifecycleState`, with a justification line per member. The `DelayedRestart` delegation line stays
  in `commonSyncingArms`. `SyncLifecycleSpec` is extended; its two `notUsed` stop-helper stubs become real.
  - As built: three commits. (1) Prerequisite (neither a move nor a narrowing; no body changes): `ShutdownApi` keeps
    `def onStop(): Unit`, still implemented by the core and named by `SnapFinalization`, `PivotSelector` and
    `SyncLifecycle` (each calls it from a PostStop handler). A new narrow trait, `SnapStopApi` (`stopSnapOnlySchedules`,
    `stopStateSyncChildren`), is declared in `controller/SyncLifecycle.scala` and implemented by the core until the
    move (`with SnapStopApi`). `SnapFinalization`'s self-type adds `& SnapStopApi` (`finalizeSnapSync` calls both
    helpers). Test stubs: `SnapFinalizationSpec`'s stub adds `with SnapStopApi`, and `PivotSelectorSpec`'s stub drops
    its two `notUsed` helper stubs, which implemented nothing once `ShutdownApi` lost the helpers. No assertion
    changed. (2) The move commit moves `stopSnapOnlySchedules`, `stopStateSyncChildren` and `delayedRestartArms`, each
    with its scaladoc and byte-identical, into a temporary trait `SyncLifecycleStop` appended to the module file. The
    trait has the temporary self-type `SNAPSyncControllerImpl` (the M8c pattern). The core gains `with
    SyncLifecycleStop`, and the module file imports `blockchain.sync.snap.*`. No `private` -> `private[snap]` change:
    every core member the bodies reach is already public or reached through a trait, and `HealedCodeWaitTimerKey` is
    the companion's. (3) The narrowing commit drops the temporary trait's two header lines, so its members fold into
    `SyncLifecycle`, which now `extends LifecycleApi with HeapWatchdogApi with SnapStopApi`. The core drops `with
    SnapStopApi`, `with SyncLifecycleStop` and their imports. Self-type unchanged.
    - State 27 against M9's 25: + `snapPeerEvictionStarted` and `snapServerPeersSchedulerStarted`. The moved
      `stopSnapOnlySchedules` clears them, and they stay in the core because the peer pool (`startSnapPeerEviction`,
      `startSnapServerPeersScheduler`; `SnapPeerPoolState`) sets them. Nothing else from the task's list is added.
      `forceCompleteStorageSent` is a `PhaseFlags` member, and `healingWalkLocalOnly` and the three core timer keys
      (`BootstrapCheckKey`, `PivotBootstrapRetryKey`, `SnapCapabilityCheckKey`) were already `SyncLifecycleState`
      members. The other keys (`HealedCodeWaitTimerKey` and the Command objects) are the companion's, `progressMonitor`
      and `currentPhase` are `SnapSharedState`, and `stopAll` is `CoordinatorHandles`. `stopHeapWatchdog` and
      `restartSnapSync` are the module's own.
    - Api 5 against M9's 7 (M7b left it at 7): `ShutdownApi` 3 -> 1. The module now implements `SnapStopApi` rather
      than naming it. Other modules: `SnapFinalization` 6 / 10, unchanged (`ShutdownApi` 1 + `SnapStopApi` 2);
      `PivotSelector` 9 / 18 -> 9 / 16 (`ShutdownApi` 1; it never called the helpers). The capability traits are
      unchanged (5 / 18 / 12 / 10).
    - Storage ratchet: the moved bodies carry no storage reference. Every `storage-baseline.json` row and the total
      (692) are unchanged, so the baseline file is untouched.
    - Stub test: `SyncLifecycleSpec` extended (`# extended-tests:`). The stub loses its two `notUsed` helper stubs, so
      the module's real members run, and gains the two flags as vars. Five new cases (13 in all): the arm is defined
      for `DelayedRestart` only; `DelayedRestart` in `AccountRangeSync` and `ByteCodeAndStorageSync` runs the real
      restart (healed-code hold reset, `startSnapSync`, `Idle`, pivot and refresh cleared, four children stopped, the
      generations bumped); in every other phase (`Idle`, `StateHealing`, `StateValidation`, `ChainDownloadCompletion`,
      `Completed`, `Dormant`) it changes nothing; `stopSnapOnlySchedules` clears both scheduler flags and stops the heap
      watchdog with the intake pressure released, stops no child, and a second call stops nothing more;
      `stopStateSyncChildren` stops the four coordinators and not an attached ChainDownloader, clears
      `forceCompleteStorageSent` and the local-walk flag, and leaves the phase flags, the pivot and the refresh state.
      Not pinned: the twelve timer keys `stopSnapOnlySchedules` cancels. The stub records them, but verify.py's
      extended-suite rule rejects every added line that matches `\bcancel\w*`, so no added case can name the stub's
      recorder. `stopPeriodicLogging` is not pinned either: the stub's progress monitor has no observation point.
  - **Not moved**: the `DelayedRestart` delegation line in `commonSyncingArms`, the core's `onStop`, and the two flags.
    No fix. Two scaladocs still describe the old layout: `SyncLifecycle`'s ("The core keeps the call sites: ... stops
    in `onStop` and `stopSnapOnlySchedules`. It also keeps the dispatch: the `DelayedRestart` and
    `PivotStateUnservable` arms") and the moved arm's ("lifted into a class member"). A move or narrowing commit cannot
    edit comments (CQ-SNAP-016-37, with CQ-SNAP-016-34). SC-001: **not met**. `SNAPSyncController.scala` is 1,834 lines (the class 995, the companion 771)
    against about 1,000, down from 1,882. A Mordor SNAP to completion (finalisation runs both helpers) and a dormant /
    critical-failure restart: **pending** (to be run by the user on the slice jar after merge; no node run here).

## Amendment 2026-10-11 — state capability boundary (after M11, before the coordinators)

User decision of 2026-10-11 (spec.md "Amendment 2026-10-11"; plan.md, same section). These slices land after the
controller modules and before T060. Spec 017 (`specs/017-state-capabilities-witnesses/`) depends on T055.

- [ ] T054 [FREEZE] **Env freeze ratchet: the coupling we can measure.** Implemented by another agent in PR #1570
  (branch `test/snap-016-env-freeze`). It is referenced here, not implemented by this spec PR.
  - What counts is set by that tooling: `scripts/snap-split/verify.py` **step 8**, against the committed
    `scripts/snap-split/storage-baseline.json` (FR-043):
    - `[env-freeze]`: the `SnapControllerEnv` member count (18 today) and its storage-typed members only go down;
    - `[storage-refs]`: storage-type identifiers and storage member accesses in code under `snap/controller/` and
      `snap/actors/`, comments excluded. The **total** across both directories only goes down; per-file counts may
      move between files;
    - `[raw-import]`: no new raw RocksDB / `DataSource` / `WriteBatch` import there.
  - Calls on `StateIngest` are **not** storage references. `StateIngest` gets its own member-cap row in the routing
    doc.
  - A PR that lowers a count records it in the baseline file in the same PR.
  - The coordinator stage (T061–T075) pays the table down; each C-* task's done-criteria include lowering its
    coordinator's share of the total.
- [x] T054c [FREEZE] **Ratchet scope: count the controller core** (branch `test/snap-016-ratchet-core`, before M5c).
  Step 8 counted only `snap/controller/` and `snap/actors/`, so a byte-identical move out of `SNAPSyncController.scala`
  (outside both) raised the total: M5c's `accountProgressCheckpointArms` carries 2 references (`appStateStorage`,
  `.commit()`), 628 → 630, though no coupling was added. `verify.py` gains `RATCHET_FILES`
  (`snap/SNAPSyncController.scala`, class and companion), counted for `[storage-refs]` and `[raw-import]` with its own
  per-file row. No other top-level `snap/` file is a move source of the remaining slices (M5c, M7b and M9b move core
  members; T055 and the C-* slices change `controller/` and `actors/` code in place), so none is listed. Re-baselined
  in the same PR: the core counts 64 and has no raw import, so the total goes 628 → 692, recorded with
  `justifications["storage_refs.total"]` ("scope extension: core added to the measured set; no code change"). This is
  a scope extension, not a coupling increase. From here a move from the core into a module is a per-file trade, and
  the total only goes down. Two self-tests: a byte-identical move of 2 references from the core into a module passes
  with no justification; a core-only increase fails. plan.md step 8, FR-043 and the routing doc name the measured set.
- [x] T054b [FREEZE] **Verify mode for a capability-introduction PR** (owned by the T054 tooling line; needed before
  T055). Add a `verify.py` mode, with self-tests, for a behaviour-neutral PR that introduces a capability and moves call
  sites onto it, which is neither a move nor a narrowing (plan.md amendment, "Why T055 is not a move or a
  narrowing"): every changed line in a module file replaces a storage handle with the matching capability member,
  while (a), (d), `[counts]`, the test-hunk guard, pin and golden protection and step 8 still apply.
  - **As built** (branch `test/snap-016-capability-verify`). The rules are in the routing doc ("Capability mode") and
    in `verify.py` ("capability mode").
    - *Selection.* The commit trailer `# capability: <Trait>`, on `refactor/snap-016-*` only; there is no new branch
      kind, so T055 keeps `refactor/snap-016-state-ingest`. With the trailer on one commit, the PR is a capability PR:
      every commit that touches `snap/` code needs the trailer, and no commit may be a move or a narrowing. The trailer
      is rejected next to `# moved:` and on `test/`, `fix/` and other branches. A PR that removes a pinned
      `SnapControllerEnv` storage handle must be a capability PR. This one existing self-test changed on purpose:
      such a removal on a plain refactor branch used to pass with a WARN, and now fails `[capability]`.
    - *Mapping.* The capability file declares the map in comment lines (`// capability-map: handle[.member] ->
      member`). Each mapped member's production implementation is in that file and is checked as a pure forwarder:
      the same call, with its own parameters in order, and no default parameters. Call sites are checked by
      whole-file token alignment, not line pairs, so multi-line chains, interpolated `${...}` and named-argument
      values work. A statement skeleton check rejects a reordered, extra, merged or re-indented statement. Comments,
      whitespace and named imports may change.
    - *Wiring.* The env may only drop pinned handles and add the accessor `: <Trait>`. The core may add one accessor
      declaration, whose initializer is a single constructor call over handles. A `<X>State` may drop a storage-typed
      member. Nothing outside `snap/` may change, and no file may be added except the capability file.
      `ChildFactories` signatures stay as they are: `ChildFactoriesSpec` (protected) compares default positions.
    - *Step 8.* The new baseline section `capabilities` holds the file, the member count (it only goes down unless
      justified) and the raw imports (fixed at introduction). The capability file is not a `storage_refs` row, but
      `[raw-import]` still measures it. `<accessor>.<call>` is not a storage reference, while `<accessor>.<handle>`
      (a parameterless storage-typed member) counts once.
    - *Review hardening (prism on f43aa3309).* B1: the capability file has an allow-listed shape, rechecked at every
      PR head once the capability exists: the trait with abstract members; a companion holding only plain enums, type
      aliases, one `apply` and one production class whose body holds only mapped forwarders. An unforwarded member
      such as `durability` is a `val` constructor parameter. B2: `apply` is exactly `new <Prod>(<its params in
      order>)`, and the class's params are apply's. B3: the core's accessor passes, position by position, the handle
      bare-mapped to each `apply` parameter, or a case of a declared enum; named arguments must name the parameter.
      Dropped or added env, State or accessor declarations must stand alone on their lines. B4: the test-hunk guard
      admits non-assertion lines by design (routing doc). F1 (prism on c4d99e3f6): the capability file imports only
      at the top of the file, named members only (no wildcard, `given` or nested import). T055's draft must move `durability` from the `Stores` body
      into the constructor and `apply`, and the core passes `StateIngest.Durability.Default`. The remaining caveats
      are in the routing doc.
    - *Self-tests.* 59 new (184 in all), plus the real-baseline test retargeted from literals (18, the 8 names) to consistency with the env on the tree (count, pins declared, every storage-typed member pinned), so T055 can lower both: 34 git-fixture cases (T055-shaped positive, plus negatives for a changed argument, an
      extra, reordered, merged or re-indented statement, a new storage reference, the wrong member, a lying or
      impure forwarder, a mode trailer with `# moved:`, a move commit or plain commit beside a capability commit, the
      trailer on test/fix/other branches, a raw `DataSource` member, and the baseline rows) and 6 unit cases for the
      token matcher.
    - *Replay.* The 25 merged spec-016 PR ranges (M2–M11b, M5b/c, M7b, M9b, lifts, T050c, T054, T054c, #1558) give
      byte-identical verifier output before and after. A dry run on the real tree forwards all 8 handles plus one
      method: 154 substitutions in 9 modules, env 18 → 11, total 692 → 674. It passes, and two injected changes
      (`.commit()` → `.commitSync()`, `pivot` → `pivot - 1`) fail.
- [ ] T055 [SI] **`StateIngest` capability** (one behaviour-neutral PR, branch `refactor/snap-016-state-ingest`).
  - Introduce `controller/StateIngest.scala`, a capability trait over:
    - the 8 storage handles now in `SnapControllerEnv`: `appStateStorage`, `stateStorage`, `evmCodeStorage`,
      `flatSlotStorage`, `pathNodeStorageOpt`, `blockchainReader`, `blockchainWriter`,
      `validatorFactory: MptStorage => StateValidator`;
    - the Path-scheme batched storage-trie node-write path (`SnapPathTrie` → `PathNodeStorage.writeStorageNode`,
      buffered in `StorageRangeCoordinator`'s `trieNodeWrites` and committed by `flushTrieNodeWrites`; introduced by
      #1548). Hash scheme already batches at 8 MiB.
  - It includes the **verification reads**: proof checks, healing presence checks, state validation and publish
    verification. It exposes no EVM execution read or write (`StateAccess` is spec 017's).
  - Move the controller modules onto it: the resume planner (checkpoints, done-markers, progress storage),
    finalization (`appStateStorage`, `blockchainWriter`, the Path→hash publish), pivot selection (pivot persistence and
    headers), healing (frontier and BFS stores, healed code), validation, and the peer pool's `blockchainReader` use.
    ~~Change the `ChildFactories` spawn arguments to pass a `StateIngest`~~ **Deferred to the C-* slices (user
    decision 2026-10-11)**: each C-* narrowing changes its factory method and its child's `apply` together (see
    "Coordinators").
  - `SnapControllerEnv` shrinks from 18 members to about 11 (18 − 8 + `stateIngest`). Record the lower count in the
    T054 baseline (`[env-freeze]`), and update the cap row in the routing doc.
  - **Durability policy.** `StateIngest` carries a per-capability durability policy whose default is today's
    behaviour. No relaxed policy is enabled in this PR (that is T056).
  - **Exporter durability property (FR-044), explicit on the trait.** `PathToHashExporter`'s publish is the
    `StateIngest` **commit to the access store**. Its output must be durable (flushed and synced) before SNAP
    finalization marks SNAP complete and before execution reads from it, even when ingest writes ran with relaxed WAL.
    Otherwise a crash during finalization leaves execution reading a store that was never durably written. On Hash
    scheme (ETC/Mordor), where SNAP writes the execution store directly and there is no export step, an explicit
    durable flush/sync barrier runs before `completeSnapSync`. The scaladoc of `StateIngest` states the property, and
    a new Tier 1 test checks the ordering on both schemes (flush/sync before the completion flag).
    **Amended (user decision 2026-10-11):** T055 documents the property and stays behaviour-neutral; it pins today's
    implicit guarantee (below) and adds no barrier. The explicit barrier is T056's, as a hard precondition.
  - **Design note: chain-data handles.** `blockchainReader` and `blockchainWriter` are chain-data handles (headers,
    pivot, best block), not trie state. T055 decides whether to split a header/chain sub-interface out of
    `StateIngest`, and records the decision in the ADR.
  - Runs `snap-split-verify` in the T054b mode; T055 does not start until T054b has merged.
  - ADR: record the capability boundary and the durability property under `docs/adr/infrastructure/` (next free
    INF number), linked from plan.md.
  - **Merge criteria (all required):**
    - forge sign-off ("no ETC behaviour change"; the Hash-scheme barrier is T056's, see the as-built note) and vault
      sign-off (DataSource contract, WriteBatch, flush/sync); beacon reviews the Path-scheme publish;
    - a full Mordor SNAP run on the slice jar;
    - ~~storage-rate parity (SC-010)~~ **moved to C-S2 (T067), user decision 2026-10-11**: T055 moves no
      coordinator call site, so the per-node write path does not change here;
    - the per-slice checklist, with the ratchet item showing `SnapControllerEnv` at about 11.
  - **As built (PR #1579, branch `refactor/snap-016-state-ingest`).**
    - `controller/StateIngest.scala`: one member per former env handle (`appState`, `stateTries`, `flatSlots`,
      `pathNodes`, `bytecode`, `chainReader`, `chainWriter`, `validatorFactory`) plus `durability` (one value,
      `Default` = today's behaviour); cap 9. Production implementation: a `final class` of plain vals built once by
      the core. `SnapControllerEnv` 18 → 11. Storage-reference total **692 → 678** under T054b's counting (recorded
      in the baseline at the rebase onto T054b, with a `capabilities["StateIngest"]` entry of 9 members). It is not
      556 (the pre-T054b counter's figure): `stateIngest.<handle>` is a parameterless storage-typed member, so it
      still counts once per use, as the handle did. That is honest, because the modules still reach the stores
      through those handles. The real reduction comes in the C-* slices (user decision 2026-10-11: keep handle-level
      members, ADR INF-006).
    - Chain data: `chainReader`/`chainWriter` stay on `StateIngest`, no header/chain sub-interface for now
      (ADR INF-006, decision 2).
    - **FR-044 (user decision 2026-10-11: document now, barrier in T056).** Finding: no separate durable flush/sync
      barrier exists today on either scheme. The WAL is on for every write; ingest and publish writes are plain
      commits; the three completion flags are one `commitSync()` batch, the only synced write on the finalization
      path. Its WAL sync persists every earlier WAL record, so, under archive and basic pruning, a recovered
      `snapSyncDone` implies the ingest and publish writes were recovered. Under `inmemory` pruning it does not:
      trie nodes stay in `CachedReferenceCountedStorage`'s cache and never reach the WAL before the flags, on either
      scheme (vault review; shipped configs use basic or archive). That implicit guarantee is what T055 pins: the `StateIngest` scaladoc, ADR
      INF-006 and `StateIngestDurabilitySpec` (both schemes: the flags are the only synced write, after every
      ingest/publish write, and every replayed prefix holding `snapSyncDone` holds the pivot root). No barrier is
      added; ETC behaviour is unchanged. The property is about execution reads: SNAP's own read-only checks of the
      hash-keyed store before the flags (the #1555 pivot-root guard, the validation walks, the healing walks, the
      post-publish root check) are ingest verification reads and are not covered (beacon note N1). **Open (vault):** confirm that the synced flags batch also covers WAL files
      rolled before it (stated in the ADR).
    - **`ChildFactories` (user decision 2026-10-11: deferred to the C-* slices).** The signatures are unchanged:
      `ChildFactoriesSpec` checks each factory's defaults against its child's `apply` by position, so the change
      belongs with the `apply` change. Spawn sites pass `stateIngest` members as the existing arguments.
    - **SC-010 (user decision 2026-10-11: moved to C-S2, T067).**
- [ ] T056 [SI-WAL] **Lighter-WAL experiment, recorded as a `StateIngest` durability policy** (optional, after T055;
  its own PR).
  - A relaxed-WAL policy is a setting of `StateIngest`'s durability policy, never of `StateAccess` and never of the
    execution store, on either scheme (FR-045).
  - FR-044 holds unchanged: the exporter publish (Path) and the pre-`completeSnapSync` barrier (Hash) run at full
    durability whatever the ingest policy.
  - **Hard precondition (user decision 2026-10-11).** T055 found no explicit barrier today: the guarantee is implicit
    (the completion flags' `commitSync()` WAL sync covers every earlier WAL record), and it breaks as soon as ingest
    writes skip the WAL. So T056 first adds an explicit durable flush/sync barrier before `completeSnapSync`, covering
    the publish output (Path) and the ingest writes (Hash), at full durability, with forge and vault sign-off (it
    changes the ETC path). **No relaxed WAL policy may be enabled, or even selectable, without it.** The vault
    question from T055 (does the synced flags batch cover earlier rolled WAL files?) is answered before T056 merges.
    `StateIngestDurabilitySpec` is extended to expect the barrier before the flags on both schemes.
  - **Barrier requirements (vault review of T055, 2026-10-11):**
    - **What:** a memtable flush (`FlushOptions` with wait) across the node, path-node, flat-slot and code namespaces,
      plus a WAL sync. A synced empty batch is not enough.
    - **Inmemory pruning:** under `pruning.mode = "inmemory"`, flush the node cache (`CachedReferenceCountedStorage`)
      first. Today those nodes never reach the WAL before the flags, on either scheme (ADR INF-006 §4).
    - **Where:** Path scheme, after the publish and before the flags; Hash scheme, after the last ingest write and
      before the flags.
    - **Owner:** the barrier lives on `StateIngest`.
    - **Tests:** extend `StateIngestDurabilitySpec`, and add a real-RocksDB kill -9 recovery test. It also confirms
      vault's rolled-WAL answer (a synced write fsyncs every live, unsynced WAL), which is from RocksDB semantics, not
      a read of the rocksdbjni 10.10.1.1 source.
  - Reviews: vault and forge. Validation: Mordor and Platåberget SNAP runs, including a crash during finalization that
    must resume without reading undurable state. The default stays today's policy until the user decides otherwise.

## Coordinators

**`ChildFactories` (user decision 2026-10-11, deferred from T055; applies to T061–T075).** Each C-* narrowing that
moves its coordinator onto `StateIngest` changes that coordinator's `ChildFactories` method and the coordinator's
`apply` together, in the same commit, so `ChildFactoriesSpec`'s default-equality check holds by construction. Done
criterion: the factory method and the `apply` take the same parameter list, and `ChildFactoriesSpec` is unchanged and
green.

**Amendment 2026-10-11 (applies to T061–T075).** Every C-* narrowing self-type takes `StateIngest` for storage,
**never `StateAccess`** and never a raw storage type (FR-042). Each C-* task is done only when it **lowers the T054
baseline total** (the storage-reference total over `snap/controller/`, `snap/actors/` and the core, FR-043), and the PR records
the new total in the baseline file. Its own coordinator file's figure normally falls too, but the gate is the total:
per-file counts may move between files. The one exception is a slice whose moved code holds no storage reference at
all: the PR says so, with the grep, and the total stays unchanged. It never rises, except by a session-value Env
declaration ("Amendment 2026-10-13", below). The "no `StateAccess` under `snap/`" dependency check is delivered by spec 017 (DC-4); it extends the T054 ratchet tooling and is vacuous
until `StateAccess` exists (FR-042).

**Amendment 2026-10-13: session values stay explicit parameters (named FR-042 exception; user decision 2026-10-13,
found at T064; applies to C-A3, C-A4, C-S2, C-H1 and every later C-* slice that takes these stores).** A coordinator's
pivot-bound trie store (`mptStorage: MptStorage`, the core's per-pivot cached instance from `getOrCreateMptStorage`)
and its per-spawn `storageDone: Option[SnapStorageDoneStorage]` (`Option.when(recordStorageDone)(storageDoneStorage)`
at the spawn site), and SRC's per-spawn `snapProgressStorage: Option[SnapSyncProgressStorage]` (`Some` from the
controller's spawn, the `None` default from recovery's; added by the user decision of 2026-10-13, found at T067), are
**session values, not capabilities**. They stay explicit `apply` parameters, in the Impl
constructor, `ChildFactories` and the spawn sites, and the module traits reach them through the coordinator's
`<Coordinator>Env`. Everything else adopts `StateIngest`. Why:
- `StateIngest` has no parameterless member that hands out the cached `MptStorage`. `trieStore(pivot)` returns a new
  `MptStorage` per call on the Hash scheme (ETC), and its doc requires the core's cache to be its only caller. A
  coordinator calling it would flush the StackTrie (or apply storage, or heal) through a different instance, a
  behaviour change. `verify.py`'s adoption mode also maps a dropped parameter only to a parameterless member;
- `stateIngest.storageDone` is the core's store, not the spawn's `Option`: whether markers are read is decided per
  spawn (`recordStorageDone`);
- `stateIngest.snapProgress` is the core's store, not the spawn's `Option`: whether the storage cursor is persisted is
  decided per spawn (recovery persists none). The types also differ, so the adoption gate cannot map it, and a plain
  store would start cursor writes in recovery.

The ratchet keeps counting all three (they are still storage references), so a slice that keeps them lowers the total only by
what it adopts; the PR says so. SC-009 ("storage only through `StateIngest`") reads with this exception. Recorded in
plan.md ("Amendment 2026-10-13") and ADR INF-006 (decision 1, note).

**The Env declaration cost (user decision 2026-10-13, found at T065; tightened after the #1591 reviews).** Declaring a
session value on the coordinator's `<Coordinator>Env` is itself counted (the declaration and its import). A slice whose
moved code reads a session value the Env does not yet declare may raise the total only under all of these:
- **Closed list.** Only the session values above qualify: the pivot-bound `mptStorage: MptStorage`, the per-spawn
  `storageDone: Option[SnapStorageDoneStorage]` and SRC's per-spawn `snapProgressStorage:
  Option[SnapSyncProgressStorage]` (added by the user decision of 2026-10-13, found at T067). Any other store is not a
  session value unless a new user decision names it.
- **Net of adoptions.** The slice first applies every adoption it can (each store that maps to a `StateIngest`
  member). The rise is the total after those adoptions, net of them; a slice whose adoptions cover the declaration
  does not rise at all.
- **Bounded amount.** The rise equals, exactly, the increase in the Env file's own count from that declaration and its
  import (+N). Nothing else in the slice may raise the total.
- **Fresh justification.** `storage-baseline.json` carries a fresh `justifications["storage_refs.total"]` line that
  names the declaration as the session-value Env declaration cost under this exception and states +N. The previous
  line is kept as its own history entry (`justifications["storage_refs.total.history"]`), not overwritten.
Any other rise is still forbidden. Instances:
- C-A4 (T065): `AccountRangeEnv` declares `storageDone: Option[SnapStorageDoneStorage]` (+3: the name, the type and
  its import; `AccountRangeEnv.scala` 3 → 6); total 609 → 612. C-A4 adopts nothing to offset it (none of its stores
  maps to a parameterless `StateIngest` member), so the net rise is the whole +3.
- C-S2 (T067): `StorageRangeEnv` declares `mptStorage: MptStorage` and `snapProgressStorage:
  Option[SnapSyncProgressStorage]` (+6: each name, its type and its import; `StorageRangeEnv.scala` 0 → 6). C-S2's
  adoption (`pathNodeStorage` → `pathNodes`) gives −15 first, so the total falls, 612 → 597 → 603 (rebased onto
  C-A4), and does not rise.
  The fresh justification line states both steps; the T060b counter-v2 line it replaced is kept as
  `justifications["storage_refs.total.history"]`.

- [x] T060 Coordinator characterization (docs PR): a var × method matrix and Command × behaviour table for ARC, SRC,
  TNHC and BCC, using the research.md method. Confirm or amend the C-* boundaries in plan.md, and derive each
  coordinator module's state-interface baseline (the R14 equivalent). Every C-* task below has the narrowing commit
  and a stub test.
  - **As built** (`staging@7d2edf157`; static only, no JVM). New file
    [coordinator-characterization.md](./coordinator-characterization.md) holds:
    - the matrices for 164 mutable fields against every method and behaviour arm;
    - the Command × behaviour tables;
    - per-slice storage references, measured with `verify.py`'s own counter, which reproduces the baseline (10 / 28 /
      88 / 84, `ChildFactories` 72);
    - the tooling gap analysis and the order.

    research.md R12 is completed, and R14b holds the State/Api baselines for the 15 coordinator modules. plan.md's
    rows carry the amendments, marked "(T060)".
  - **Boundaries confirmed, with these amendments:**
    - #1501's `skipPresent` is a line of BCC's `AddByteCodeTasks` arm. It moves only after a lift commit carves it
      into a method;
    - #1533's off-actor pipeline goes to C-S4, and `writePrebuiltTrie` to C-S2;
    - `handleTimeout` goes to C-S1 (SRC) and C-H4 (TNHC);
    - `maybeRequestPivotRefresh` goes to C-A1, `drainActiveTasks` to C-A2, `isNodeInStorage` to C-H5, and
      `readPathVerified*` to C-H3;
    - C-S4 reads no store, because proof verification is pure;
    - the Impl-nested message and helper types move to the companion before narrowing. TNHC declares 8 `Command`s
      inside its Impl.
  - **Zero-reference slices:** C-A1, C-A2, C-H4 and BCC's dispatch half. C-S1 and C-S4 are zero-reference in
    substance: the only matches are `ForceCompleteStorage`, metric setters and `validateStorageRanges`. Each
    coordinator's storage parameters therefore change in the first slice whose code uses them. That slice is named
    per task below.
  - **Projection for C-B1:** total 678 → 662, checked on a scratch tree with step 8's counter.
  - **Armless messages** (local CHASE-QUEUE CQ-SNAP-016-38/39/40; no fix):
    - seven ARC `Command` cases have no arm and no sender;
    - TNHC drops the worker-sent `HealingTaskFailed` in its catch-all;
    - the ratchet has false-positive floors (ARC 7, SRC 25, TNHC 14).
  - **User decisions (open at T060, decided 2026-10-12; see T060c, T060d and plan.md "Amendment 2026-10-12"):**
    - **D-T060-1** (before C-A3): `StateIngest` exposes no per-pivot `MptStorage`, `SnapStorageDoneStorage`,
      `SnapSyncProgressStorage`, `HealingFrontierStorage` or `BfsQueueStorage`. The options were to keep them as
      parameters, to add capability members, or to derive them in the coordinator (a behaviour change). See
      characterization §4.3. **Decided: add capability members** (T060d);
    - **D-T060-2** (before C-B1): `BytecodeRecoveryActor` and `StorageRecoveryActor` (`blockchain/sync`, outside
      `snap/`) call BCC's and SRC's `apply` with raw stores, and cannot see `private[snap] StateIngest`. See §4.4.
      **Decided: option (a)**, `SyncController` builds one `StateIngest` for them (T060c).
  - **Tooling:** today's `verify.py` rejects the specified narrowing (step 6 rejects the `apply`, `ChildFactories`,
    constructor, spawn-site and call-site lines). Capability mode cannot carry it. A plain prerequisite commit would
    pass but is unchecked. → T060b.
- [x] T060b [TOOL] **`verify.py`: StateIngest adoption allowance for C-* narrowings** (owned by the T054 tooling line;
  before T061; branch `test/snap-016-coordinator-verify`). This is the change proposed in coordinator-characterization.md
  §6.3; it is not implemented by T060.
  - A narrowing commit with the trailer `# adopts: StateIngest <Coordinator>` may:
    - remove storage-typed parameters from that coordinator's Impl constructor, its companion `apply`, the
      `new <Coordinator>Impl(...)` call and the matching `ChildFactories` method, and add one
      `stateIngest: StateIngest` (or `Option[StateIngest]`) in their place. The remaining parameters keep their
      names, defaults and order, and the forwarding lines stay `name = name`;
    - substitute call sites (`<removed param>` → `stateIngest.<member>`) under `substitution_check`, with a map
      declared in the coordinator file (`// adopt-map: ...`);
    - replace the spawn-site arguments of removed parameters with one `stateIngest = stateIngest`.
  - `STORAGE_NAME_ALLOW += ForceCompleteStorage`, with SRC re-baselined in the same PR. Optionally, exempt the
    metric setters and the `isNodeInStorage` / `.isStorage` names.
  - Optionally:
    - treat `<Coordinator>Env` traits under `snap/actors/` as capability traits;
    - add a type-level SC-009 check;
    - if D-T060-2 chooses (a), admit the recovery actors under the same substitution rule.
  - Self-tests: a C-B1-shaped positive case, plus the negatives listed in §6.3 item 8. Replay the merged spec-016
    ranges and show identical output, as T054b did.
  - **As built** (branch `test/snap-016-coordinator-verify`; the rules are in the routing doc, "Adoption mode (T060b)",
    and in `verify.py`, "adoption mode"). No sbt or JVM was run.
    - *Adoption mode (G1–G7).* The trailer `# adopts: StateIngest <Unit>`, on `refactor/snap-016-*` only, not with
      `# moved:` or `# capability:`. `<Unit>` is one of the four coordinators, or `RecoveryActors` (D-T060-2). The
      commit is the slice's narrowing commit, where the check replaces step 6 (the lines step 6 admits are dropped
      first, and visibility modifiers may change), or a standalone commit. Every changed `src/main` file must belong to
      the unit: the coordinator file and its `actors/<dir>/` modules, `ChildFactories`, the `DownloadSupervisor` and
      `HealingOrchestrator` spawn sites, the recovery actors' coordinator calls; or, for `RecoveryActors`, the two
      recovery actors, `SyncController` and the `StateIngest` visibility. The check is token level, like capability
      mode's: declarations drop storage-typed parameters and take one `stateIngest: StateIngest` at the first dropped
      position (kept parameters unchanged, each dropped one mapped to a parameterless member of the same type by a
      `// adopt-map:` line or a bare `capability-map:` entry); calls drop those arguments, all or none, for one
      `stateIngest`, and each dropped value must be `stateIngest.<member>` or a name equal to it in that file; dropped
      parameters may stand as `stateIngest.<member>` in the coordinator and its modules. The `ChildFactories` method
      must keep `apply`'s parameter list (names, defaults, the `stateIngest` type), so `ChildFactoriesSpec` holds by
      construction and stays protected.
    - *D-T060-2 support.* `SyncController` may add one `stateIngest` whose initializer is token-identical to
      `SNAPSyncController`'s, plus the handles that initializer names which it lacks, each derived token-identically
      (`pathNodeStorageOpt` from its `val`, `validatorFactory` from `SNAPSyncController.apply`'s default); its own `def`s
      may gain a `stateIngest` parameter. `StateIngest.scala` may only widen the trait's and object's `private[snap]`
      to `private[sync]`. No other logic may change.
    - *D-T060-1 support (capability extension).* A capability-mode PR for a capability already in the merge-base
      baseline may add members, map lines, forwarders and `apply` parameters; existing members and parameters keep
      their declarations; map entries the file had at the merge-base are not re-checked against the empty pinned list.
      The core's accessor gains only the new arguments (each checked like an introduction's), and `SyncController`'s
      mirror follows in the same commit. `def trieStore(pivot: BigInt): MptStorage = stateTries.getBackingStorage(pivot)`
      mapped from `stateStorage.getBackingStorage` passes as a pure forwarder, and `stateIngest.stateTries.getBackingStorage(n)`
      may become `stateIngest.trieStore(n)` (the accessor-alias form). Member growth needs a fresh
      `justifications["capabilities.StateIngest.members"]` line.
    - *G8.* Without the trailer, dropping a storage-typed parameter from (or adding `StateIngest` to) a coordinator's
      Impl constructor or `apply`, a `ChildFactories` coordinator method or a recovery actor `def`, or changing
      `StateIngest`'s visibility, fails `[adopts]`.
    - *G9.* A `<Coordinator>Env` trait (`ByteCodeEnv`, ...) is declaration-only for step 6 and reported on the
      `[counts] capability traits` line. Baselined capabilities are also reported there (before, `StateIngest` was
      listed as a module after T055).
    - *G10, counter version 2.* The baseline's `counter` field selects the counter (absent = 1, so every old range
      replays unchanged). Version 2 drops 21 reviewed false-positive names (`ForceCompleteStorage`, `isNodeInStorage`,
      `.isStorage`, `replayQueuedStorage`, `.createStorageTask`, the `SNAPSyncMetrics.setStorage*` setters, the
      `SyncProgressMonitor` and `SnapIntakeBudget` counters, `maxConcurrentStorageAccounts`/`maxPendingStorageTasks`,
      `evaluateStorageTail`, `sampledStorageRoots`, `validateStorageRanges`, `verifyStorageRange`,
      `attachStorageConsumer`: −53) and counts `storageDone` (+10). **Re-baselined 678 (v1) = 635 (v2)**, with a
      `justifications["storage_refs.total"]` line. The counter only goes up, and a change needs a fresh justification.
      Per-file: SRC 88 → 66, TNHC 84 → 70, ARC 28 → 29, `ChildFactories` 72 → 75, `DownloadSupervisor` 60 → 56,
      `StagnationWatchdog` 3 → 0, and four −1s. The floors (characterization §4.2) fall to ARC 2 (`.commit()`), SRC 3
      (`.commit()`), TNHC 0.
    - *Extra gap found (step 1).* A coordinator's first move commit turns the Impl's multi-line constructor closer `):`
      into `) extends <Module>`; step 1 now admits exactly those two header shapes.
    - *Not covered:* `Option[StateIngest]` (T075) fails closed until T075 decides between `Option` and a flag; calls in
      unchanged files are left to the compiler; SC-009's type-level check (characterization §6.3 item 6) is not built.
    - *Self-tests.* 38 new (222 in all; 236 after the two prism rounds): 26 git-fixture adoption cases (C-B1, D-T060-2 and narrowing-combined positives;
      negatives for a logic change in `apply`, in a spawn site and beside a substitution, a dropped non-storage
      argument, another capability passed, the wrong member, a wrong-typed adopt-map, reordered/retyped/extra
      parameters, `ChildFactories` out of step, a `ChildFactoriesSpec` edit, a file outside the unit, the wrong unit,
      no trailer, the trailer on a move commit or a test branch, a narrowing body change, and for D-T060-2 an
      accessor that does not mirror the core, a handle derived differently, logic changes, a stray `stateIngest` use,
      a capability change beyond visibility, and visibility or signature changes without the trailer), 1 unit case for
      the call rule (named, positional, partial, pure addition), 6 capability-extension cases (D-T060-1 positive, no
      justification, impure `trieStore`, a changed core argument, `SyncController` out of step, a changed member) and
      5 counter/G9 cases (v1 vs v2 counts, upgrade needs a justification, no downgrade, the real baseline matches
      this tree, `ByteCodeEnv`).
    - *Prism review of 4470da23c (changes requested, fixed in a second commit).* (1) An adopted file may add only the
      capability's import or a once-qualified name: no wildcard, `given`, rename or rebinding import. (2) Step 1's
      closer allowance is scoped to the coordinator Impl files, plain parent names that are traits of the
      coordinator's `actors/<dir>/`, and only where the file gains such a closer; the adoption check's header lines
      need plain parents too. Steps 1 and 6 elsewhere keep admitting parent arguments: tightening them changed the
      replay of #1560 (M9's `with SyncLifecycle(heapWatchdogStart)`), so they stay, as a documented reviewer item.
      (3) A counter change is measured against the merge-base re-measured under the new counter, with no
      justification escape; the commit that changes it touches no `src/main`; and its justification names
      `counter v<N>` in 40+ characters. (4) A rewrite of a dropped name under a shadowing declaration (a helper
      parameter, a local `val`, a lambda binding) fails. (5) The mirrored `stateIngest` and handles are plain `val`s,
      and the trait and object widen together. (6) An `adopt-map` line may not override a bare `capability-map:`
      entry. Info: `STORAGE_NAME_ALLOW_V2` matches by name everywhere (routing doc). Prism round 2 (on 4af2ce0f6):
      (A) a mirrored type's import in SyncController must be the core's own fully-qualified entry (or, for a type the core uses from its own package without an import, that package's path), and no adopted file
      may import a Scala/Predef name; (B) the shadow check counts every binding form (`case`/pattern/tuple positions,
      `<-` without parentheses, `using`, class `val` parameters) and is file-wide in the coordinator and its modules,
      member-wide in a recovery actor; optional, taken: steps 1 and 6 admit header parent arguments only as bare
      identifiers (the 29-range replay stays byte-identical, #1560 included). 11 new fixtures; 233 self-tests. SyncController may also add named imports of the types its mirrored declarations name (not already used bare): the D-T060-2 dry run re-done with those imports passes (635), and C-B1 on top passes (635 → 619).
    - *Replay.* Old and new `verify.py` over the 29 merged spec-016 ranges (#1541–#1582, with #1543, #1558, #1570,
      #1574, #1579, #1580, #1582): byte-identical output for all 29.
    - *Dry run* (scratch clone of staging, not committed). A D-T060-2-shaped commit (`StateIngest` → `private[sync]`;
      `SyncController` builds one `StateIngest`; both recovery actors' `apply`/`applyPreloaded`/`testApply`/
      `scanning`/`downloading` take it) passes, total 678 unchanged. A C-B1-shaped commit on top (`ByteCodeCoordinator`
      Impl/`apply`/`new`, `ChildFactories`, three spawn sites and `BytecodeRecoveryActor` take `stateIngest`;
      `evmCodeStorage` → `bytecode`) passes with the total **678 → 662** (BCC 10 → 4, `ChildFactories` 72 → 68,
      `DownloadSupervisor` 60 → 56, `HealingOrchestrator` 45 → 43), the characterization's projection exactly. Four
      injected changes fail `[adopts]`: `batchSize * 2` in `apply`'s `new` call, a spawn site's `intakeBudget = None`,
      `isEmpty` → `nonEmpty` beside the `skipPresent` substitution, and a changed `ChildFactories` default. Re-run on
      top of the T060b commit (counter v2), the same two commits pass with **635 → 619** (BCC 10 → 4,
      `ChildFactories` 75 → 71, `DownloadSupervisor` 56 → 52, `HealingOrchestrator` 45 → 43).
- [x] T060c [SI] **D-T060-2: recovery actors take `StateIngest`** (user decision 2026-10-12; before T061; its own PR,
  `# adopts: StateIngest RecoveryActors`). `StateIngest` widens from `private[snap]` to `private[sync]`.
  `SyncController` already holds 6 of the 8 stores; it builds one `StateIngest`, deriving `pathNodes` exactly as
  `SNAPSyncController` does (and `validatorFactory` as its `apply` default), and passes it to `BytecodeRecoveryActor`
  and `StorageRecoveryActor`, which pass it on to the coordinators once C-B1 and the storage slices adopt it. No other
  logic changes. Reviews: forge (both actors run on ETC at startup), vault. Validation: a bytecode- or storage-recovery
  start on Mordor.
  - **As built** (branch `refactor/snap-016-recovery-stateingest`, one commit with `# adopts: StateIngest RecoveryActors`;
    no sbt or JVM run, CI compiles).
    - `StateIngest.scala`: only the trait's and the object's `private[snap]` become `private[sync]`.
    - `SyncController` has no `snapSyncConfig` field (it loads one per call), so the handle cannot be built at actor
      construction with the core's derivation. `startRecovery` builds it, in its `(Some, Some)` arm right after
      `loadSnapSyncConfig()`: `pathNodeStorageOpt` (the core's `val`, token for token), `validatorFactory`
      (`SNAPSyncController.apply`'s default) and `stateIngest` (the core's initializer). So it is one `StateIngest`
      per recovery start, passed to the four recovery spawn sites and, on the combined-scan path, to
      `runningCombinedScan` as a new last parameter.
    - **Construction timing.** On the Hash scheme (ETC/Mordor) `pathNodeStorageOpt` is `None`, so nothing new is
      constructed: only the references are stored, plus the `new StateValidator(_)` function value, which builds no
      validator. On the Path scheme (ETH/Sepolia) each recovery start now builds one `PathNodeStorage` over
      `flatSlotStorage.dataSource` where none was built before. That class holds two namespace constants and the data
      source reference, does no I/O, and nothing in recovery reads `stateIngest.pathNodes`.
    - `BytecodeRecoveryActor` (`apply`, `applyPreloaded`, `testApply`, `scanning`, `downloading`) and
      `StorageRecoveryActor` (the same five) take `stateIngest` in place of their raw stores, at the first dropped
      position. Their bodies read the same instances as `stateIngest.appState` / `.stateTries` / `.bytecode` /
      `.flatSlots`. The coordinators' `apply` signatures do not change: BCC still gets
      `evmCodeStorage = stateIngest.bytecode`, SRC `flatSlotStorage = stateIngest.flatSlots` and the same
      `getBackingStorage(pivotBlockNumber)` store. The scan helpers (`scanForMissingBytecodes`, `scanForMissingStorage`,
      `logResidualGaps`) keep their raw parameters. `CombinedRecoveryScanActor` is unchanged.
    - Tests: `BytecodeRecoveryActorSpec` (5 calls) and `StorageRecoveryActorSpec` (1 call) pass
      `stateIngest = recoveryIngest(...)`, a local `StateIngest` over the spec's stores whose other members throw. No
      assertion changed.
    - `verify.py` (#1583's): OK. `[adopts]` BytecodeRecoveryActor 9 lists / 5 substitutions, StorageRecoveryActor 9 / 6,
      SyncController 6 / 0, StateIngest visibility widened on 2 lines. Storage total 635, unchanged (the recovery actors
      are outside `snap/`), so no baseline change.
    - Owed: the forge and vault reviews, and the Mordor recovery-start validation run. The live recovery-start check is
      deferred to the first natural trigger and to the C-B1/C-S* Mordor runs (user decision 2026-10-12).
- [x] T060d [SI] **D-T060-1: `StateIngest` members for the derived stores** (user decision 2026-10-12; before T064;
  a capability-mode PR, `# capability: StateIngest`, an extension). Members for the per-pivot `MptStorage` (a method),
  `SnapStorageDoneStorage`, `SnapSyncProgressStorage`, `HealingFrontierStorage` and `BfsQueueStorage`, handing out the
  same instances, built where they are today, so the change is behaviour-neutral. The member cap grows with a
  `justifications["capabilities.StateIngest.members"]` line; ADR INF-006 decision 1 gets a note. Open for vault: the
  healing stores are lazy vals of `HealingOrchestrator` today (a strict accessor would build them at construction), and
  a `getBackingStorage` forwarder returns a new `MptStorage` per call on the Hash scheme, so the core's per-pivot cache
  stays the only caller (forge).
  - **Narrowed (user decision 2026-10-12, after a first attempt stopped):** `trieStore`, `snapProgress` and
    `storageDone` only. The healing stores are deferred to **C-H1**. Two things block them today:
    - the mirror rule cannot carry a lazy handle. SyncController must declare each handle in the core's initializer as a
      plain `val` derived token for token as SNAPSyncController derives it. The healing stores are `HealingOrchestrator`'s
      `private lazy val`s, so there is no such derivation. Copying one into SyncController would build a
      `RocksDbBfsQueueStorage` (which holds a write counter) on every recovery start, and its initializer reads
      `stateIngest`;
    - the core cannot name them. They are `private` to the trait, and capability mode's token check rejects widening
      them.
    A small verify change at C-H1 admits by-name/lazy members (#1584, the mirror/lazy-handle limit).
  - **As built** (branch `refactor/snap-016-stateingest-derived`, one commit with `# capability: StateIngest`, an
    extension; no sbt or JVM run, CI compiles and tests).
    - `StateIngest`: three members, 9 → 12, with a `justifications["capabilities.StateIngest.members"]` line.
      `def trieStore(pivot: BigInt): MptStorage = stateTries.getBackingStorage(pivot)` (map
      `stateStorage.getBackingStorage -> trieStore`, a pure forwarder); `snapProgress` (map
      `snapProgressStorage -> snapProgress`, a `val` constructor parameter); `storageDone` (map
      `storageDoneStorage -> storageDone`, a by-name parameter `storageDoneStorage: => SnapStorageDoneStorage` with a
      forwarder `def`). `apply` and `Stores` take the two new parameters after `durability`.
    - **Construction timing.** Unchanged in the core (line numbers at the base). `snapProgressStorage` is built at L206, before the capability
      (L225), so a strict parameter hands out the built instance. `storageDoneStorage` is built at L241, after the
      capability: a strict argument would capture `null`, so the parameter is by-name, and each `storageDone` read
      returns the core's one field. The core's accessor appends `snapProgressStorage, storageDoneStorage`.
    - **Caching.** `trieStore` returns a new `MptStorage` per call on the Hash scheme (ETC). Its only caller is the
      core's per-pivot cache: `SnapResumePlanner.getOrCreateMptStorage` reads `stateIngest.trieStore(pivotBlockNumber)`
      where it read `stateIngest.stateTries.getBackingStorage(pivotBlockNumber)` (the accessor-alias form). The cache
      and its resets are unchanged. `StorageRecoveryActor` (outside `snap/`) keeps its own `getBackingStorage` call.
    - **Recovery path.** SyncController's mirror (`startRecovery`, `(Some, Some)` arm) adds
      `val snapProgressStorage = new SnapSyncProgressStorage(appStateStorage.dataSource)` and
      `val storageDoneStorage: SnapStorageDoneStorage = new SnapStorageDoneStorage(flatSlotStorage.dataSource)`, the
      core's derivations, and passes them. So each recovery start builds two more wrappers, as T060c did for
      `PathNodeStorage`. Both are stateless: a data-source reference and a constant namespace (`SnapSyncProgressStorage`
      extends `TransactionalKeyValueStorage`, which declares only abstract members). Neither does I/O at construction,
      and recovery reads neither.
    - Tests: the five test implementations of the trait (`ForwardingStateIngest` and the stubs in
      `BytecodeRecoveryActorSpec`, `StorageRecoveryActorSpec`, `ByteCodeCoordinatorSpec`,
      `ByteCodeResponseHandlingSpec`) implement the three members. `ForwardingStateIngest.trieStore` forwards to
      `tries.getBackingStorage` as production does (`SnapResumePlannerSpec` reaches it). Every other new member throws
      "not reached". No assertion changed.
    - `verify.py`: OK. `[capability]` extension +3 members, new `apply` parameters `snapProgress`,
      `storageDoneStorage`; SyncController mirrors the core; SnapResumePlanner has 1 substitution. Storage total 619,
      unchanged: `SNAPSyncController.scala` 68 → 70 (the two accessor arguments), `SnapResumePlanner.scala`
      94 → 92 (`stateIngest.stateTries` and `.getBackingStorage` leave; `stateIngest.trieStore(...)` is a call).
    - Owed: forge (ETC Hash path: `trieStore` and the cache) and vault (store lifecycle) reviews.
- [x] T061 [C-B1] ByteCode: `ByteCodeDispatch`, `ByteCodeResponseHandling` (the #1501 `skipPresent` check moves
  unchanged). Narrowing takes `StateIngest` (code writes, presence check), never `StateAccess`; lowers the T054
  baseline total.
  - T060: needs T060b (done) and D-T060-2 (T060c merged first). Runs in adoption mode,
    `# adopts: StateIngest ByteCodeCoordinator`, on the narrowing commit.
    - `skipPresent` is a line of the `AddByteCodeTasks` arm. Lift it into a method in a plain commit, or leave it in
      the core, where it is the core's one remaining reference.
    - Move `ActiveByteCodeRequest` and `WorkerRef` to the companion first.
    - Adopts `evmCodeStorage` → `bytecode`. Projected total 678 → 662 under counter v1, 635 → 619 under counter v2
      (the baseline since T060b); the T060b dry run measured it.
    - Baselines: State 9 + 7, Api 1 + 9 (R14b). Add vault.
  - **As built** (branch `refactor/snap-016-cb1-bytecode`, six commits; no sbt or JVM run, CI compiles and tests).
    (1) `# moved:` `WorkerRef`, `ActiveByteCodeRequest` and `ValidatedByteCodes` from the Impl into the companion
    (`private[actors]`; a case class is not a member a module trait may hold, FR-016 (d), so `ValidatedByteCodes`
    goes there too). (2) Lift: the `skipPresent` line becomes `skipPresentCodeHashes(received, skipPresent)`, verbatim,
    bound at the same point of the `AddByteCodeTasks` arm. (3) Prerequisite: `ByteCodeEnv`, the two `<Module>State`
    traits and the three Api traits are declared in `actors/bytecode/` (the adoption commit edits existing files only
    and adds no import but the capability's); the constructor values the modules read become `val`s; the Impl goes
    `private` → `private[actors]`; the six module-only constants become `private lazy val`s (pure: yes, literal
    initializers: `maxFailuresPerHash`, `min`/`max`/`initialResponseBytes`, `increaseFactor`, `decreaseFactor`).
    (4) The move commit takes 25 members byte-identical into `ByteCodeDispatch` (19, with `workerSeq`) and
    `ByteCodeResponseHandling` (6, with the lifted check), the Impl closing `) extends ByteCodeDispatch` /
    `with ByteCodeResponseHandling:`. (5) The narrowing commit, `# adopts: StateIngest ByteCodeCoordinator`: the
    self-types name `ByteCodeDispatchState & ByteCodeEnv & ByteCodeQueueApi` and
    `ByteCodeResponseHandlingState & ByteCodeEnv & ByteCodeDispatchApi & ByteCodeCompletionApi`; `evmCodeStorage`
    → `stateIngest` in the Impl constructor, `apply`, `new`, `ChildFactories.byteCodeCoordinator`, the three spawn
    sites and `BytecodeRecoveryActor`; three call sites read `stateIngest.bytecode`. `ChildFactoriesSpec` unchanged.
    (6) Docs.
    - Counts against R14b: `ByteCodeDispatch` State 9 (= 9), Api 1 (= 1, `pendingHashCount`);
      `ByteCodeResponseHandling` State 7 (= 7), Api 8 (R14b 9: the seven `ByteCodeDispatchApi` calls and
      `checkCompletion`; R14b's `active` was a name match on the `case Some(active)` binding). `ByteCodeEnv` 9,
      reported as a capability trait (G9): `context`, `log`, `networkPeerManager`, `requestTracker`,
      `cooldownConfig`, `snapSyncController`, `intakeBudget`, `stateIngest`, `maxWorkers`.
    - Storage total **635 → 619** (counter v2), the projection: `ByteCodeCoordinator.scala` 10 → 0,
      `actors/bytecode/ByteCodeResponseHandling.scala` 0 → 4 (3 `stateIngest.bytecode`, the batch `.commit()`),
      `ChildFactories` 75 → 71, `DownloadSupervisor` 56 → 52, `HealingOrchestrator` 45 → 43.
    - Stub tests (`# new-tests:`): `ByteCodeDispatchSpec` (5 cases) and `ByteCodeResponseHandlingSpec` (7 cases).
      `ByteCodeCoordinatorSpec`'s spawn helper and `RecordingChildFactories`' override take `stateIngest`; no assertion
      changed.
    - Owed: the vault review (the `skipPresent` presence check and the code-write path now read through
      `StateIngest.bytecode`), and a Mordor SNAP through the bytecode phase or a bytecode-recovery start.
- [x] T062 [C-A1] `AccountPeerDispatch` (includes the #1501 intake gate; T060: plus `maybeRequestPivotRefresh`; a
  zero-reference slice, so the PR records the grep and the total is unchanged). Narrowing takes `StateIngest` where it touches
  storage, never `StateAccess`; lowers the baseline total.
  - **As built** (branch `refactor/snap-016-ca1-account-dispatch`, four code commits and this docs commit; no sbt or
    JVM run, CI compiles and tests). (1) `# moved:` `WorkerRef` from the Impl into the companion (`private[actors]`).
    No lift: every A1 member was already an indent-2 member, and the intake-gate lines of the arms are calls.
    (2) Prerequisite: `AccountRangeEnv`, `AccountPeerDispatchState` and `AccountTaskLifecycleApi` are declared in
    `actors/account/`; the nine constructor parameters the module reads become `val`s (`ctx`, `timers`,
    `networkPeerManager`, `requestTracker`, `concurrency`, `snapSyncController`, `initialResponseBytesConfig`,
    `minResponseBytesConfig`, `intakeBudget`); the Impl goes `private` → `private[actors]`; the eleven module-only
    constants become `private lazy val`s (pure: yes; literals, or `BigInt(<constructor val>)` for `minResponseBytes` /
    `initialResponseBytes`). The storage parameters (`mptStorage`, `pathNodeStorage`, `storageDone`) are untouched.
    (3) The move commit takes 37 members byte-identical into `AccountPeerDispatch` (24 methods, `workerSeq`,
    `lastPivotRefreshTimeMs` and the eleven constants), the Impl closing `) extends AccountPeerDispatch` /
    `with AccountRangeEnv:`. (4) The narrowing commit: the self-type names
    `AccountPeerDispatchState & AccountRangeEnv & AccountTaskLifecycleApi`, the Impl adds the State and Api parents.
    No `# adopts:`, and `apply`, `ChildFactories` and `ChildFactoriesSpec` are unchanged (the tasks.md exception).
    - Counts against R14b: State 17 (= 17), Api 1 (= 1, `tryRedispatchPendingTasks`). `AccountRangeEnv` 10, reported
      as a capability trait (G9), with no store.
    - Storage total **619, unchanged** (zero-reference slice). The characterization §5 grep over
      `actors/account/AccountPeerDispatch.scala` (and `AccountRangeEnv.scala`) prints `[]`; ARC stays at 29.
      `storage-baseline.json` is unchanged.
    - Stub test (`# new-tests:`): `AccountPeerDispatchSpec` (8 cases, in a `BehaviorTestKit`). No existing test changed.
    - The fields the module reaches are now public; their `private[actors] so AccountRangeCoordinatorSpec ...` comments
      are left as they were (FR-034, no cleanups in a move PR).
    - Owed: the second-specialist review (prism) and forge's "no ETC behaviour change" sign-off. No vault review and
      no node run (characterization §7: none for C-A1); the Mordor restart mid-account comes after C-A4.
- [x] T063 [C-A2] `AccountTaskLifecycle`. Narrowing takes `StateIngest`, never `StateAccess`; lowers the baseline total.
  (T060: plus `drainActiveTasks`. A zero-reference slice: grep recorded, total unchanged.)
  - **As built** (branch `refactor/snap-016-ca2-account-lifecycle`, three code commits and this docs commit; no sbt or
    JVM run, CI compiles and tests). No `# moved:` type commit and no lift: A2's code names no Impl-nested type
    (`WorkerRef` moved in C-A1), and every A2 member was already an indent-2 member. (1) Prerequisite:
    `AccountTaskLifecycleState`, `AccountProgressApi` (`sendProgressSnapshot`, the core's) and `ContractWorkCarryApi`
    (`identifyContractAccounts`, the core's until C-A4, named for that owner) are declared in the new
    `actors/account/AccountTaskLifecycle.scala`, and `AccountPeerDispatchApi` (the 13 A1 members A2 calls) in
    `AccountPeerDispatch.scala`, the file of its implementer (the C-B1 `ByteCodeDispatchApi` precedent); the two
    module-only constants become `private lazy val`s (pure: yes, literals: `StateLogIntervalMs`, `peerCooldownNoProof`).
    `sendProgressSnapshot` keeps its default in the Api (the `BfsQueueStorage.iterateRange` precedent). No constructor
    parameter changes: the A2 bodies read only `ctx`, `log`, `snapSyncController` and `concurrency` from the
    environment, all `AccountRangeEnv` members since C-A1. (2) The move commit takes 13 members byte-identical into
    `AccountTaskLifecycle`: `drainActiveTasks`, `lastStateLogMs` (a private var, rule (d)), `StateLogIntervalMs`,
    `peerCooldownNoProof`, `handleTaskComplete`, `updateTaskProgress`, `compareUnsigned32`, `incrementHash32`,
    `isMaxHash`, `completeEmptyTaskRange`, `handleTaskFailed`, `requeueOrEscalate`, `tryRedispatchPendingTasks`. The
    Impl gains `with AccountTaskLifecycle`; the core fields and methods the module reaches become public
    (`completedTasks`, `accountsDownloaded`, `bytesDownloaded`, `consumedKeyspace`, `storingTasks`,
    `sendProgressSnapshot`, `identifyContractAccounts`). (3) The narrowing commit: the self-type names
    `AccountTaskLifecycleState & AccountRangeEnv & AccountPeerDispatchApi & AccountProgressApi & ContractWorkCarryApi`;
    `AccountTaskLifecycle extends AccountTaskLifecycleApi`, so A1's Api member (`tryRedispatchPendingTasks`) is now
    satisfied by the A2 module and the Impl drops its own `with AccountTaskLifecycleApi`; `AccountPeerDispatch extends
    AccountPeerDispatchApi`; the Impl adds the State and the two core Api parents. No `# adopts:`, and `apply`,
    `ChildFactories` and `ChildFactoriesSpec` are unchanged (the tasks.md exception).
    - Counts against R14b: State 18 (= 18), Api 15 (= 15: `AccountPeerDispatchApi` 13, `AccountProgressApi` 1,
      `ContractWorkCarryApi` 1). `AccountPeerDispatch` unchanged (17 / 1); `AccountRangeEnv` unchanged (10, no store).
    - Storage total **619, unchanged** (zero-reference slice). The characterization §5 grep over
      `actors/account/AccountTaskLifecycle.scala` prints `[]`; ARC stays at 29. `storage-baseline.json` is unchanged.
    - Stub test (`# new-tests:`): `AccountTaskLifecycleSpec` (7 cases, in a `BehaviorTestKit`). No existing test changed.
    - Left as they were (FR-034, no cleanups in a move PR): `AccountTaskLifecycleApi`'s scaladoc, in
      `AccountPeerDispatch.scala`, still says the core provides it until C-A2; the fields the module reaches are now
      public.
    - Owed: the second-specialist review (prism) and forge's "no ETC behaviour change" sign-off. No vault review and
      no node run (characterization §7: none for C-A2); the Mordor restart mid-account comes after C-A4.
- [x] T064 [C-A3] `AccountTrieAssembly` + `finalizing` (vault; T060: the first ARC slice that touches storage. It
  adopts `pathNodeStorage` → `pathNodes`, and `mptStorage` per D-T060-1, which must be decided first). Narrowing takes `StateIngest` (trie-node writes,
  StackTrie commit), never `StateAccess`; lowers the baseline total.
  - **Stop and decision.** The first attempt stopped before any code: `mptStorage` maps to no parameterless
    `StateIngest` member (`trieStore(pivot)` takes a parameter, and on the Hash scheme returns a new `MptStorage` per
    call, while the spawn site passes the core's cached instance), so the adoption gate rejects it and a substitution
    would change the StackTrie's store. User decision 2026-10-13: the pivot-bound `MptStorage` and the per-spawn
    `storageDone` are session values and stay explicit parameters, the named FR-042 exception (Coordinators,
    "Amendment 2026-10-13"). A3 adopts `pathNodeStorage` → `pathNodes` only.
  - **As built** (branch `refactor/snap-016-ca3-account-trie`, three code commits and this docs commit; no sbt or JVM
    run, CI compiles and tests). No `# moved:` type commit and no lift: A3's code names no Impl-nested type, and every
    A3 member was already an indent-2 member. (1) Prerequisite: `AccountTrieAssemblyState`, `AccountRangeCoreApi`
    (`calculateProgress`, `onStop`, the core's) and `ContractTaskFilesApi` (`readContractFile`,
    `readUniqueCodeHashes`, the core's until C-A4) are declared in the new `actors/account/AccountTrieAssembly.scala`,
    and `AccountActivePeersApi` (`activePeerCount`) in `AccountPeerDispatch.scala`, a separate trait so C-A2's
    `AccountPeerDispatchApi` does not grow. `AccountRangeEnv` gains `futureLog`, `resumeProgress`, `storageScheme` and
    `mptStorage`; those constructor parameters (and `pathNodeStorage`, for the temporary self-type) become `val`s and
    `futureLog` loses `private`. The three module-only constants become `private lazy val`s (pure: yes:
    `ProgressLogInterval`, `totalKeyspace = BigInt(2).pow(256)`, `storeChunkSize`). The C-A1/C-A2 stub states gain the
    four env members. (2) The move commit takes 9 members byte-identical into `AccountTrieAssembly`: `lastProgressLogAt`,
    `ProgressLogInterval`, `lastFlatMilestonePct`, `totalKeyspace` (the two vars are private vars, rule (d)),
    `finalizing` (a whole behaviour), `storeChunkSize`, `handleStoreAccountChunk`, `getOrCreateTaskStackTrie`,
    `finalizeTrie`. The Impl gains `with AccountTrieAssembly`; the core members the module reaches become public
    (`startTime`, `trieFlushGeneration`, `taskStackTries`, the six task-file handles and three counts,
    `calculateProgress`, `readContractFile`, `readUniqueCodeHashes`); the core drops its `ProgressMilestones` import.
    (3) The narrowing commit, `# adopts: StateIngest AccountRangeCoordinator`: the self-type names
    `AccountTrieAssemblyState & AccountRangeEnv & AccountActivePeersApi & AccountTaskLifecycleApi & AccountProgressApi &
    AccountRangeCoreApi & ContractTaskFilesApi`; `AccountPeerDispatch` also extends `AccountActivePeersApi`; the Impl
    adds the State and the two core Api parents. `pathNodeStorage: Option[PathNodeStorage] = None` → `stateIngest:
    StateIngest` in the Impl constructor, `apply`, `new`, `ChildFactories.accountRangeCoordinator` and the
    `DownloadSupervisor` spawn site (`stateIngest = stateIngest` where it passed `stateIngest.pathNodes`); the one call
    site in `getOrCreateTaskStackTrie` reads `stateIngest.pathNodes` (`// adopt-map: pathNodeStorage -> pathNodes`);
    `AccountRangeEnv` gains `stateIngest`. `mptStorage` and `storageDone` are unchanged. `ChildFactoriesSpec` unchanged.
    - Trie-node write path (for vault): the Hash-scheme `SnapHashTrie` flushes to the same `mptStorage` instance (the
      spawn site's `getOrCreateMptStorage(currentPivot)`), the Path-scheme `SnapPathTrie` writes to the same
      `PathNodeStorage` (production's `Stores.pathNodes` is a constructor `val`; it is now read when a range's trie is
      first built instead of at spawn), and the range-complete `trie.commit()` and `finalizeTrie`'s straggler commits
      run where and in the order they ran.
    - Counts: `AccountTrieAssembly` State 20 (R14b 12, which counted mutable fields only; plus `trieFlushGeneration`,
      `startTime` and the six task-file handles the `finalizing` arms read), Api 7 (R14b 3, an upper bound by name:
      `activePeerCount`, `tryRedispatchPendingTasks`, `sendProgressSnapshot`, `calculateProgress`, `onStop`,
      `readContractFile`, `readUniqueCodeHashes`). `AccountPeerDispatch` 17 / 1 and `AccountTaskLifecycle` 18 / 15
      unchanged. `AccountRangeEnv` 10 → 15 (G9): `futureLog`, `resumeProgress`, `storageScheme`, `mptStorage`,
      `stateIngest`.
    - Storage total **619 → 609** (counter v2): `AccountRangeCoordinator.scala` 29 → 17, `actors/account/
      AccountTrieAssembly.scala` 0 → 5 (`mptStorage`, `stateIngest.pathNodes`, the `pathNodeStorage` in the exception
      message, the two StackTrie `.commit()`), `actors/account/AccountRangeEnv.scala` 0 → 3 (the explicit
      `mptStorage` and its import), `ChildFactories` 71 → 67, `DownloadSupervisor` 52 → 50. The 606 projected for this
      route plus those 3. `storage-baseline.json` records it.
    - Tests (`# new-tests:`): `AccountTrieAssemblySpec` (8 cases, in a `BehaviorTestKit`; Hash and Path schemes, the
      chunk yield, the missing Path store, `finalizeTrie`, `finalizing`'s stale drops, query, teardown, success and
      failure). `AccountRangeCoordinatorSpec`'s two spawn helpers pass a local `StateIngest` whose `pathNodes` is
      `None`; `RecordingChildFactories`' override takes `stateIngest` and records `stateIngest.pathNodes` in the
      unchanged `ChildSpawn.AccountRange.pathNodeStorage`; the C-A1/C-A2 stubs gain the env members. No assertion
      changed.
    - Owed: the vault review (the trie-node write path and the StackTrie commit above), the second-specialist review
      (prism) and forge's "no ETC behaviour change" sign-off (the Hash-scheme path is ETC's). Node run: the Mordor
      restart mid-account comes after C-A4 (T065).
- [x] T065 [C-A4] `ContractWorkCarry` (vault; T060: adopts `storageDone` per D-T060-1, superseded by the Coordinators
  "Amendment 2026-10-13": `storageDone` and `mptStorage` stay explicit parameters. The task-file stream vals stay in
  the core under rule (d)). Narrowing takes `StateIngest`, never `StateAccess`; lowers the baseline
  total. Run a Mordor restart mid-account. The #1487/#1498/#1501 replay invariants are unchanged.
  - **Stop and decision.** The first attempt stopped before code: under the 2026-10-13 exception the replay reaches
    `storageDone` through `AccountRangeEnv`, whose declaration raises the total by 3, and C-A4 adopts nothing to offset
    it. User decision 2026-10-13: the Env route (the replay moves intact into the module), the rise justified, and the
    Amendment gains "The Env declaration cost" (above). The alternative, lifting the `storageDone` uses into core
    methods, would have left the marker skip outside the module vault reviews.
  - **As built** (branch `refactor/snap-016-ca4-contract-carry`, four code commits and this docs commit; no sbt or
    JVM run, CI compiles and tests). (1) `# moved:` `ContractEntrySize` from the Impl into the companion
    (`private[actors]`): the core initializer `carriedStorageCount` reads it, so it cannot be a trait `lazy val`.
    No lift: every A4 member was already an indent-2 member. (2) Prerequisite: `ContractWorkCarryState` (13) is
    declared in the new `actors/account/ContractWorkCarry.scala`, and `AccountIntakeGateApi`
    (`downstreamBackpressureActive`, `intakeGateClosed`) in `AccountPeerDispatch.scala`, a separate trait (the
    `AccountActivePeersApi` precedent); `AccountRangeEnv` gains `storageDone`, so that constructor parameter becomes a
    `val`; the C-A1/C-A2/C-A3 stub states gain it; `storage-baseline.json` records 612 with the justification.
    (3) The move commit takes 10 members byte-identical into `ContractWorkCarry`: `replayStorageOffset`,
    `replayCodeHashesOffset`, `replaySkippedFinished`, `replayQueuedStorage` (private vars, rule (d)), `replayDone`,
    `syncTaskFiles`, `replayCarriedChunk`, `identifyContractAccounts`, `readContractFile`, `readUniqueCodeHashes`. The
    Impl gains `with ContractWorkCarry`; the moved members the core calls lose `private` (`replayDone`,
    `syncTaskFiles`, `replayCarriedChunk`); the core members the module reaches become public (`carriedStorageCount`,
    `carriedCodeHashesCount`, `contractStorageFos`, `uniqueCodeHashesFos`, `codeHashBloom`); the core drops the
    `RandomAccessFile`, `Account` and `MerklePatriciaTrie` imports. (4) The narrowing commit: the self-type names
    `ContractWorkCarryState & AccountRangeEnv & AccountIntakeGateApi`; `ContractWorkCarry extends ContractWorkCarryApi
    with ContractTaskFilesApi`, so the Impl drops its own `with` for each and adds `with ContractWorkCarryState`;
    `AccountPeerDispatch` also extends `AccountIntakeGateApi`. No `# adopts:`; `apply`, `ChildFactories`, the spawn
    site and `ChildFactoriesSpec` are unchanged.
    - Replay path (for vault): the same `storageDone` and `mptStorage` instances (the constructor's), the same order
      (empty roots dropped, the marker skip on both schemes, then the Hash-scheme root-presence check), the same
      chunking, pause and pacing timers, intake reservation, `replayed = true` message and closing `CheckCompletion`;
      the task-file writes, flushes and fsyncs are unchanged. The #1487/#1498/#1501 replay invariants are unchanged.
    - Counts: `ContractWorkCarry` State 13 (R14b 4, "about 15 with the task-file I/O vals": the three counts and
      `codeHashBloom`, plus the two carried counts and the seven task-file handles and streams), Api 2 (= R14b).
      `AccountPeerDispatch` 17 / 1, `AccountTaskLifecycle` 18 / 15 and `AccountTrieAssembly` 20 / 7 unchanged.
      `AccountRangeEnv` 15 → 16 (G9): `storageDone`.
    - No adoptable store (verify.py's counter v2 over the moved bodies): `storageDone.fold(...)`,
      `mptStorage.multiGetNodes(...)` and `storageDone.isEmpty` in `replayCarriedChunk`; `identifyContractAccounts`,
      `syncTaskFiles` and the two readers hold none (`.createStorageTask` and `replayQueuedStorage` are allow-listed).
      Both stores are session values under the exception.
    - Storage total **609 → 612** (counter v2), justified: `AccountRangeCoordinator.scala` 17 → 14,
      `actors/account/ContractWorkCarry.scala` 0 → 3 (the three references above),
      `actors/account/AccountRangeEnv.scala` 3 → 6 (`storageDone: Option[SnapStorageDoneStorage]` and its import).
    - Tests (`# new-tests:`): `ContractWorkCarrySpec` (7 cases, in a `BehaviorTestKit` with real temporary task files:
      identification and the codeHash dedup, a response without contracts, the replay's marker skip and Hash-scheme
      presence check, the Path scheme without a trie-store read, the intake-gate hold and resume, two-chunk pacing,
      `syncTaskFiles`). The C-A1/C-A2/C-A3 stubs gain `storageDone`. No assertion changed.
    - Owed: the vault review (the carried replay above), the second-specialist review (prism), forge's "no ETC
      behaviour change" sign-off (the Hash-scheme presence check is ETC's path), and the **Mordor restart
      mid-account** (from the CI artifact jar, user).
- [x] T066 [C-S1] `StoragePeerDispatch` (T060: plus `handleTimeout`. Zero-reference in substance: its 2 matches are
  `ForceCompleteStorage`, which T060b allow-lists. Grep recorded, total unchanged). Narrowing takes `StateIngest` where it touches storage, never `StateAccess`;
  lowers the baseline total.
  - **As built** (branch `refactor/snap-016-cs1-storage-dispatch`, four code commits and this docs commit; no sbt or
    JVM run, CI compiles and tests). The C-A1 (T062) shape. (1) `# moved: PendingResponse`: the Impl-nested #1533
    pipeline class goes to the companion (visibility unchanged, `final private[actors]`), so the State trait can name
    `pendingResponses`' element type; no test names it. No lift: every S1 member was already an indent-2 member, and
    `handleTimeout` is called from the `StorageRequestTimedOut` arm. (2) Prerequisite: `StorageRangeEnv` (new
    `actors/storage/StorageRangeEnv.scala`), `StoragePeerDispatchState` and `StorageRangeCoreApi` (`progress`, the
    core's) are declared in the new `actors/storage/StoragePeerDispatch.scala`; the thirteen constructor parameters the
    module reads become `val`s (`context`, `networkPeerManager`, `requestTracker`, `maxAccountsPerBatch`,
    `maxInFlightRequests`, `requestTimeout`, `snapSyncController`, `configInitialResponseBytes`,
    `configMinResponseBytes`, `deferredMerkleization`, `maxConcurrentStorageAccounts`, `intakeBudget`, `processing`);
    the sixteen module-only constants become `private lazy val`s (pure: yes, literals or a constructor `val`;
    `minResponseBytes` wraps after `=` to stay within 120 columns). The storage parameters are untouched. (3) The move
    commit takes 42 members byte-identical into `StoragePeerDispatch`: 21 methods (`isPostRefreshCooldownActive`,
    `isPeerStateless`, `markPeerStateless`, `recordPeerSuccess`, `recordPeerAnswered`, `maybeRequestPivotRefresh`,
    `batchSizeFor`, `reduceBatchSize`, `maybeIncreaseBatchSize`, `inFlightForPeer`, the three response-budget methods,
    `requestNextRanges`, `handleTimeout`, `dispatchIfPossible`, `tryRedispatchPendingTasks`, `redispatchEligible`,
    `isPeerCoolingDown`, `perPeerInFlightLimit`, `recordPeerCooldown`), the five private vars (rule (d):
    `lastPenalisedFloorLogMs`, `lastPivotRefreshTimeMs`, `storageIdleChecks`, `storageMilestonePct`,
    `lastStateLogMs`) and the sixteen constants. The Impl closes `) extends StoragePeerDispatch` /
    `with StorageRangeEnv:`; the core members the module reaches become public (`log`, `self`, the 24 State fields,
    `progress`); the core imports `actors.storage.*` and drops `MessageSerializable`. The module's self-type is
    `this:`, not `self:`: three moved bodies send to the coordinator's `self` (`context.self`, an Env member), which a
    `self` alias would shadow. (4) The narrowing commit: the self-type names
    `StoragePeerDispatchState & StorageRangeEnv & StorageRangeCoreApi`, the Impl adds the State and Api parents, the
    module swaps the `actors.*` wildcard for a named import. No `# adopts:`, and `apply`, `ChildFactories` and
    `ChildFactoriesSpec` are unchanged (the tasks.md exception).
    - Counts against R14b: State 24 (R14b 23, plus `peerHealth`, the #1491 `SnapPeerHealth` score: a concrete `val` of a
      mutable object that the core's arms also read; R14b's matrix lists mutable fields and collections and missed
      it), Api 1 (= 1, `progress`). `StorageRangeEnv` 15, reported as a capability trait (G9), with no store.
    - Storage total **609, unchanged** (zero-reference slice). The characterization §5 grep over
      `actors/storage/StoragePeerDispatch.scala` prints three `ForceCompleteStorage` under `STORAGE_NAME_ALLOW` (v1:
      `handleTimeout`'s two, as T060 predicted, plus the module's named import of the Command object) and `[]` under
      `STORAGE_NAME_ALLOW_V2`, the baseline's counter; `StorageRangeEnv.scala` prints `[]` under both. SRC stays at 66;
      `storage-baseline.json` is unchanged.
    - Stub test (`# new-tests:`): `StoragePeerDispatchSpec` (8 cases, in a `BehaviorTestKit`). No existing test changed.
    - Left as they were (FR-034, no cleanups in a move PR): the fields the module reaches are now public, and the moved
      constants sit in the trait without the comments of the fields they were declared beside (those stay with the
      fields).
    - Owed: the second-specialist review (prism) and forge's "no ETC behaviour change" sign-off (SRC runs on ETC). No
      vault review and no node run (characterization §7: none for C-S1; no storage path changes).
- [x] T067 [C-S2] `StorageOrderedApply` (vault; T060: plus `writePrebuiltTrie`. It adopts `pathNodeStorage`, and
  `mptStorage`/`snapProgressStorage` per D-T060-1. `flushTrieNodeWrites` writes via `pns.dataSource.update`, a raw
  call that a per-batch `StateIngest` member could replace; decide at T067). Narrowing takes `StateIngest` (the batched storage-trie node-write
  path, `flushTrieNodeWrites`), never `StateAccess`; lowers the baseline total.
  - **Storage-rate parity (SC-010), moved here from T055 (user decision 2026-10-11)**, because this is where the
    per-node write path moves onto `StateIngest`. The batched storage-trie node-write path is the hottest one, and a
    wrapper that adds a virtual call or an allocation per node would show up there first. Merge gate:
    - `[STORAGE-PERF]` parity on **the same physical host** that produced the ~1.7M entries/h figure (the 4-core /
      16 GB / SATA BX500 soak host), with the host quiescent: no other node and no sbt running;
    - on the same workload as the baseline, the Sepolia Path-scheme storage phase, against the ~1.7M entries/h
      figure. How the wrapped build runs against that workload (on a copy of the Sepolia datadir, or on the live
      node) is decided by the user at T067, because Sepolia otherwise stays on fix-only builds;
    - in addition, an A/B on that same host (pre-wrap build vs post-wrap build, same chain, same starting state)
      shows no regression. If the pre-wrap build cannot reproduce ~1.7M/h, the fresh same-host pre-wrap measurement
      becomes the baseline, and the PR says so.
  - **Stop and decisions (2026-10-13).** The first attempt stopped before any code, on two findings. (1) The storage
    recovery spawn (`StorageRecoveryActor`) relied on `pathNodeStorage`'s `None` default, and the adoption gate rejects
    a call that relied on a dropped default and starts passing `stateIngest` (confirmed on a scratch commit). (2)
    `snapProgressStorage` is an `Option[SnapSyncProgressStorage]` chosen per spawn; `StateIngest.snapProgress` is a plain
    store (the gate's type check fails, and adopting it would start cursor writes in recovery), and it was not on the
    session-value list. User decisions: (1) option A, a plain prerequisite commit in which the recovery spawn passes
    `pathNodeStorage = stateIngest.pathNodes` explicitly (its `storageScheme` and `snapProgressStorage` stay at their
    defaults; whether recovery's coordinator should run the node's scheme is a separate question, filed for beacon);
    (2) `snapProgressStorage` joins the closed session-value list (Coordinators, "Amendment 2026-10-13" and "The Env
    declaration cost"). The raw `pns.dataSource.update` stays (amendment A-S2); `flatSlotStorage` stays for C-S3.
  - **As built** (branch `refactor/snap-016-cs2-storage-apply`, five code commits and this docs commit; no sbt or JVM
    run, CI compiles and tests). (1) `# moved: ReadyStorageChunk, ByteStringOrdering`: the two Impl-nested types go to
    the companion (`ByteStringOrdering` widens from `private` to `private[actors]`: the core, C-S2 and C-S3 use it).
    (2) Plain prerequisite: `StorageRecoveryActor` passes `pathNodeStorage = stateIngest.pathNodes` (Hash scheme: the
    same `None`; Path scheme: recovery's coordinator holds a `Some` it never reads, because its `storageScheme` is the
    `Hash` default). (3) Prerequisite: `StorageOrderedApplyState` and `StorageApplyCoreApi` are declared in the new
    `actors/storage/StorageOrderedApply.scala`, `StoragePeerFeedbackApi` (`recordPeerCooldown`,
    `adjustResponseBytesOnFailure`) in `StoragePeerDispatch.scala`, a separate trait so C-S1's interfaces do not change;
    `StorageRangeEnv` gains `mptStorage`, `snapProgressStorage` and `storageScheme`, those parameters (and
    `pathNodeStorage`, for the temporary self-type) become `val`s; the companion gains `type TrieNodeWrite =
    DataUpdate`, so the State's `trieNodeWrites` needs no raw `DataSource` import in the module file (verify rule 2);
    the C-S1 stub state gains the three env members. (4) The move commit takes 10 members byte-identical into
    `StorageOrderedApply`: `clearStorageOrderingState`, `getOrCreateAccountTrie`, `commitAccountTrie`,
    `commitPrebuiltRoot`, `resetAccountTrie` (loses `private`: the core calls it), `flushTrieNodeWrites`,
    `writePrebuiltTrie`, `applyOrderedStorageChunk`, `drainOrderedStorageChunks`, `applyReadyStorageChunk`. No field
    moves (every one is shared with the core, C-S1, C-S3 or C-S4, or is a concrete `val` kept by rule (d)); the core
    members the module reaches become public. (5) The narrowing commit, `# adopts: StateIngest
    StorageRangeCoordinator`: the self-type names `StorageOrderedApplyState & StorageRangeEnv & StorageApplyCoreApi &
    StoragePeerFeedbackApi`; the Impl adds the State and core Api parents; `StoragePeerDispatch` extends
    `StoragePeerFeedbackApi`. `pathNodeStorage: Option[PathNodeStorage] = None` → `stateIngest: StateIngest` in the
    Impl constructor, `apply`, `new`, `ChildFactories.storageRangeCoordinator`, both `DownloadSupervisor` spawn sites
    and the `StorageRecoveryActor` spawn (all `stateIngest = stateIngest`); the three reads use `stateIngest.pathNodes`
    (`// adopt-map: pathNodeStorage -> pathNodes`); `StorageRangeEnv` gains `stateIngest`. `ChildFactoriesSpec`
    unchanged.
    - Node-write hot path (for vault and SC-010): the instances, the `trieNodeWrites` buffer and the flush points are
      unchanged (one batch per applied response, before a flat batch or a persisted cursor, at the end of every
      message, at stop). `stateIngest.pathNodes` is read where `pathNodeStorage` was: once per trie creation (the
      `SnapPathTrie` callbacks capture `pns`), once per flush, once per prebuilt write; never per node, and no new
      allocation. The per-node callback now reads `trieNodeWrites` through the State accessor instead of the Impl's
      private field: one interface getter call per node, monomorphic in production, which the JIT inlines. A hoist into
      a captured local would remove it at bytecode level but is a body change in a move PR; SC-010 parity decides.
    - Counts: `StorageOrderedApply` State 13 (R14b 11, plus `tasks`, written as `this.tasks.enqueue`, which the
      matrix's name match missed, and `zeroSlotHash`, an immutable `val` kept by rule (d)), Api 7 (= R14b: 5 core, 2
      `StoragePeerDispatch`). `StoragePeerDispatch` 24 / 1 unchanged. `StorageRangeEnv` 15 → 19 (G9): `mptStorage`,
      `snapProgressStorage`, `storageScheme`, `stateIngest`.
    - Storage total **612 → 603** (rebased onto C-A4, whose +3 took the total from 609 to 612) (counter v2): the adoption −15, then the session-value Env declarations +6 exactly
      (Coordinators, "The Env declaration cost"). `StorageRangeCoordinator.scala` 66 → 46, `actors/storage/
      StorageOrderedApply.scala` 0 → 13, `actors/storage/StorageRangeEnv.scala` 0 → 6, `ChildFactories` 67 → 63,
      `DownloadSupervisor` 50 → 46. `storage-baseline.json` records it with a fresh justification line (the T060b line
      kept as history).
    - Tests (`# new-tests:`): `StorageOrderedApplySpec` (9 cases, a plain `AnyFlatSpec`: the bodies need no actor
      context): a whole chunk, the out-of-order buffer and its drain, the boundary-repeat rejection, the ordering-state
      clear, the trie reset, an empty chunk against its claimed root, a Hash-scheme trie reaching `mptStorage`, Path
      node writes buffered and committed in one flush, an empty flush. `StorageRangeCoordinatorSpec` and
      `StorageRangeCoordinatorOffActorSpec` pass a local `StateIngest` (`pathNodes` `None`, and the fixture's store,
      respectively); `RecordingChildFactories` records `stateIngest.pathNodes` in the unchanged
      `ChildSpawn.StorageRange.pathNodeStorage`; the C-S1 stub state gains the four env members. No assertion changed.
    - Owed: **SC-010 `[STORAGE-PERF]` parity (the merge gate above, not run)**; vault (the node-write path, the alias,
      the recovery prerequisite), forge ("no ETC behaviour change": the Hash-scheme path and the recovery spawn), beacon
      (the Path-scheme writes), and the second-specialist review (prism).
- [x] T068 [C-S3] `StorageDoneMarkers` (vault; T060: adopts `flatSlotStorage` → `flatSlots`. `storageDoneStorage` is a
  core val under rule (d)). Narrowing takes `StateIngest` (flat slots, done markers), never
  `StateAccess`; lowers the baseline total. The PR quotes spec 010 R2a and shows each clause still holds. Run Mordor and
  Platåberget restarts mid-storage.
  - **No stop.** None of the C-S2 stop conditions arose: `flatSlotStorage` has no default anywhere (Impl, `apply`,
    `ChildFactories`), every spawn site already passed `flatSlotStorage = stateIngest.flatSlots` and `stateIngest =
    stateIngest`, `FlatSlotStorage` is exactly `StateIngest.flatSlots`'s type (the capability's own map entry), and
    `storageDoneStorage` is a derived val, not a parameter, so no store joins the session-value list.
  - **As built** (branch `refactor/snap-016-cs3-storage-done`, three code commits and this docs commit; no sbt or JVM
    run, CI compiles and tests). (1) Prerequisite: `StorageDoneMarkersState` is declared in the new
    `actors/storage/StorageDoneMarkers.scala`, `StorageTrieFlushApi` (`flushTrieNodeWrites`) in
    `StorageOrderedApply.scala`, a separate trait so C-S2's interfaces do not change (the `StoragePeerFeedbackApi`
    precedent); `StorageRangeEnv` gains `flatBatchEntryThreshold`; that parameter and `flatSlotStorage` (for the
    temporary self-type) become `val`s; the companion gains `type FlatSlotBatch = DataSourceBatchUpdate`; the C-S1 and
    C-S2 stub states gain the env member. (2) The move commit takes 7 members byte-identical into `StorageDoneMarkers`:
    `flatBatchesDoneThrough` (a literal-initialised var only this module touches, so a var of the trait),
    `recordFlatBatchDone`, `stageDoneMarker`, `takeDoneMarkersFor`, `stageFlatSlotChunk`, `flushPendingFlatBatch`,
    `recordCompletedTask`. `recordFlatBatchDone`, `takeDoneMarkersFor` and `flushPendingFlatBatch` lose `private` (the
    core's arms and `onPostStop` call them); the core fields the module reaches become public. `flushPendingFlatBatch`
    names `DataSourceBatchUpdate`, and a new module file may not import a raw type (verify rule 2), so the move commit
    imported the alias under that name (`import ...StorageRangeCoordinator.FlatSlotBatch as DataSourceBatchUpdate`)
    and the body stayed byte-identical (the alternative, a prerequisite commit retyping the body's annotation, was a
    body change before a move). The prism review found the rename misleading (it reads as the raw type), so a
    post-move commit imports `FlatSlotBatch` plainly and types the one annotation `FlatSlotBatch`. (3) The narrowing commit, `# adopts: StateIngest StorageRangeCoordinator`: the self-type
    names `StorageDoneMarkersState & StorageRangeEnv & StorageTrieFlushApi`; the Impl adds the State parent;
    `StorageOrderedApply` extends `StorageTrieFlushApi`, and its `flushTrieNodeWrites` loses `private[actors]` (a
    public abstract member cannot be implemented with weaker access). `flatSlotStorage: FlatSlotStorage` is dropped
    from the Impl constructor, `apply`, `new`, `ChildFactories.storageRangeCoordinator` and its forwarding, both
    `DownloadSupervisor` spawn sites and the `StorageRecoveryActor` spawn (`stateIngest` was already a parameter since
    C-S2, so nothing is added); the four reads use `stateIngest.flatSlots` (the `storageDoneStorage` initializer,
    `onPostStop`'s final flush twice, `flushPendingFlatBatch`'s capture for the Future). `storageDoneStorage` stays a
    core val (rule (d)), `Option.when(recordStorageDone)(new SnapStorageDoneStorage(stateIngest.flatSlots.dataSource))`,
    and the module reaches it through State; it is not replaced by `stateIngest.storageDone` (the controller's store,
    not gated by this spawn's `recordStorageDone`). `StorageApplyCoreApi` still declares `stageFlatSlotChunk`,
    `stageDoneMarker` and `recordCompletedTask`; the Impl now satisfies them through the module's concrete members, so
    C-S2's recorded interface is left as it is. `ChildFactoriesSpec` unchanged.
    - Flat-slot and marker write path (for vault): unchanged. The accumulator, the threshold, the batch sequence, the
      marker rule, one `WriteBatch` per flat batch (slots and markers joined with `and`, committed on `flatBatchEc`, the
      storage-writer dispatcher), the trie-node flush before it, and `onPostStop`'s synchronous tail. How often:
      `stageFlatSlotChunk` once per applied chunk (no store access); a flat batch per `flatBatchEntryThreshold` entries
      (1000 by default), plus the 10 s `FlushStorageDoneMarkers` pulse (with markers on), completion, pivot refresh and
      force-complete; one marker write per fully downloaded account, inside a flat batch. `stateIngest.flatSlots` is
      read once per submitted batch, on the actor thread (`val storage = ...`, captured by the Future as before, never
      read inside it), once at stop and once at construction (with markers on); never per slot or per marker. No new allocation: `flatSlots` is a field read on
      the capability, and the module's State and Env reads are interface getter calls (monomorphic in production) per
      chunk or per batch, not per slot.
    - Spec 010 R2a, quoted, clause by clause: "**R2a — crash consistency.** A record never becomes durable before that
      data:"
      - "It is written in the same RocksDB WriteBatch as the account's last flat slots, or in a later batch once every
        numbered flat batch up to the one carrying those slots has committed. It never waits on batches submitted after
        it." Holds: `stageDoneMarker` (byte-identical) records `flatBatchSeq + 1` as the marker's last slot batch;
        `takeDoneMarkersFor` (byte-identical) lets it ride only when `flatBatchesDoneThrough >= lastSlotBatch`, or in
        batch `lastSlotBatch` itself once every earlier batch has committed, and compares with nothing later;
        `flushPendingFlatBatch` (byte-identical) joins the riding markers to the slots with `and` and commits once;
        `recordFlatBatchDone` (byte-identical) advances the watermark only over contiguous batches, called from the
        core's unchanged `FlatBatchFlushComplete` arm. The markers and slots still share one data source, so one
        `WriteBatch`: `storageDoneStorage` is built over `stateIngest.flatSlots.dataSource`, and the slots are written
        through `stateIngest.flatSlots`, the same instance every spawn site passed before. `onPostStop`'s synchronous
        tail (core, unchanged but for the store read) applies the same rule with the next sequence number.
      - "A failed batch turns records off for that coordinator." Holds: the core's `FlatBatchFlushFailed` arm
        (unchanged) sets `doneMarkersDisabled` and clears `pendingDoneMarkers`; the moved `stageDoneMarker` and
        `takeDoneMarkersFor` read that same field through State.
      - "Give-up, max-empty skip, force-complete and root-mismatch completions are never recorded." Holds:
        `stageDoneMarker` has one caller, `applyReadyStorageChunk` (C-S2, unchanged), at `if rootVerified then
        stageDoneMarker(...)` on the every-subtask-applied path; `giveUpOnAccountForHealing`, the max-empty skip, the
        abandoned and force-complete paths never call it (at most `recordCompletedTask`, a counter).
      - "A crash before the record lands costs one re-download, which is what happens today." Holds: a staged marker
        lives only in `pendingDoneMarkers` until its batch commits; `onPostStop` still drops the ones that may not ride.
    - Counts: `StorageDoneMarkers` State 11 (R14b 9, plus `storageDoneStorage` and `flatBatchEc`, immutable vals that
      rule (d) keeps in the core and R14b's matrix of mutable fields does not list), Api 1 (= R14b,
      `StorageTrieFlushApi`). `StorageOrderedApply` 13 / 7 and `StoragePeerDispatch` 24 / 1 unchanged.
      `StorageRangeEnv` 19 → 20 (G9): `flatBatchEntryThreshold`; no store joins it.
    - Storage total **603 → 590** (counter v2), a fall with no exception: the adoption −16 (the parameter and its type
      at the Impl, `apply` and the factory, the `new` call's and the factory's forwarding, the two `DownloadSupervisor`
      arguments, the core's and `ChildFactories`' `FlatSlotStorage` imports; the four reads count once each, as
      before); kept +4: State's `storageDoneStorage` declaration and its import (+3, the first storage-typed State
      member among the coordinator modules, a rule (d) core val, not a session value) and the `FlatSlotBatch` alias
      (+1); the module's plain `FlatSlotBatch` import and retyped annotation name no storage type (−1 against the
      moved reference). `StorageRangeCoordinator.scala` 46 → 32,
      `actors/storage/StorageDoneMarkers.scala` 0 → 10, `ChildFactories` 63 → 58, `DownloadSupervisor` 46 → 42.
      `storage-baseline.json` records it with a fresh justification line (the C-S2 line kept at the head of the history
      entry).
    - Tests (`# new-tests:`): `StorageDoneMarkersSpec` (9 cases, a plain `AnyFlatSpec`; the flat batch runs on
      `ExecutionContext.parasitic` and reports to a `TestInbox`): a chunk kept sorted below the threshold, a batch at
      the threshold (trie flush first, slots in the store, the completion message), an empty flush, a marker in its
      own slots' batch, a marker held while an earlier batch is uncommitted and written alone once both are, the
      contiguous watermark, the per-batch marker selection, no markers when off or disabled, the completed-task count.
      `StorageDoneMarkersFlushSpec` (2 cases, post-review, `# new-tests:`): the trie nodes are flushed while the slots
      are still uncommitted (the stub's `onTrieFlush` hook reads the store at that instant), and a commit that throws
      reaches `self` as `FlatBatchFlushFailed`, not as a completion (a store whose writes fail on demand).
      `StorageRangeCoordinatorSpec` and `StorageRangeCoordinatorOffActorSpec` hand their flat-slot store to their local
      `StateIngest` (whose `flatSlots` threw before); `RecordingChildFactories` records `stateIngest.flatSlots` in the
      unchanged `ChildSpawn.StorageRange.flatSlotStorage`; the C-S1 and C-S2 stub states gain the env member. No
      assertion changed.
    - Left as they were (FR-034, no cleanups in a move PR): the moved `takeDoneMarkersFor` scaladoc's "the rule above"
      now points at the crash-consistency comment in the core (at `storageDoneStorage`), and the newly public fields
      keep their "Package-private for unit tests" scaladoc.
    - Owed: vault (the marker and flat-slot write path, the alias, the State member), forge ("no ETC behaviour change":
      the Hash-scheme path and the recovery spawn), beacon (Path scheme), the second-specialist review (prism), and the
      **Mordor and Platåberget restarts mid-storage** (characterization §7), run by the orchestrator.
- [x] T069 [C-S4] `StorageResponseProcessing` (T060: plus #1533's off-actor pipeline. Proof verification is pure,
  with no store read, so this is zero-reference in substance: grep recorded, total unchanged). ~~Narrowing takes
  `StateIngest` (proof verification reads), never `StateAccess`; lowers the baseline total.~~ Amended by the
  characterization (A-S4b) and confirmed at T069: the moved code reads no store, so the narrowing takes nothing and the
  total is unchanged (the C-S1 shape).
  - **No stop.** None of the stop conditions arose: no parameter, default or spawn site changes (nothing is adopted, so
    `apply`, `ChildFactories`, both `DownloadSupervisor` spawns and the `StorageRecoveryActor` spawn are untouched), no
    `Option` store is involved, no store joins the session-value list (the module reaches `mptStorage`,
    `snapProgressStorage` and `stateIngest` not at all), and the #1533 threading is unchanged (below).
  - **The wording conflict, decided.** T069 said both "zero-reference, total unchanged" and "narrowing takes
    `StateIngest` (proof verification reads); lowers the total". The code settles it for the first: `verifyStorageChunk`
    and `verifyServedSlots` (companion, unchanged) read only the response's slots and proof nodes, and SRC has no storage
    parameter that only C-S4 reads, so the adoption gate would have no dropped parameter to map. No `# adopts:`
    trailer, `storage-baseline.json` unchanged (the C-S1 precedent, not C-S3's: C-S3 had a store to adopt).
  - **As built** (branch `refactor/snap-016-cs4-storage-response`, four code commits and this docs commit; `sbt compile`,
    `scalafmtAll` and targeted `testOnly` run locally, see the PR). (1) `# moved: StorageTaskKey, StaleRootFailure`: the
    two Impl-nested case classes go to the companion so the State trait can name `emptyResponsesByTask`'s key and
    `staleRootFailuresByAccount`'s element (`StorageTaskKey` widens from `private` to `private[actors]`, the
    `ByteStringOrdering` precedent; no test names either). (2) Prerequisite: `StorageResponseProcessingState` is declared in
    the new `actors/storage/StorageResponseProcessing.scala`, and three narrow Api traits next to their implementers so the
    recorded C-S1..S3 interfaces do not change (the `StoragePeerFeedbackApi` precedent): `StorageResponsePeerApi` (8) in
    `StoragePeerDispatch.scala`, `StorageChunkApplyApi` (`applyOrderedStorageChunk` with its `prebuilt = None` default,
    `resetAccountTrie`) in `StorageOrderedApply.scala`, `StorageCompletedTaskApi` (`recordCompletedTask`) in
    `StorageDoneMarkers.scala`; `storageConcurrency`, `maxEmptyResponsesPerTask` and `maxStaleRootFailuresPerAccount`
    become `private lazy val`s (pure: integer literals). `StorageRangeEnv` needs nothing: every constructor value the
    bodies read is already a member. (3) The move commit takes 19 members byte-identical into `StorageResponseProcessing`:
    the three constants, `isStaleLocalRootSignature`, `giveUpOnAccountForHealing`, `nextResponseSeq` and
    `inlineVerifyNanos` (literal-initialised vars only this module touches, so private vars of the trait),
    `enqueueResponse`, `submitProcessingJobs`, `drainProcessedResponses`, `logStoragePerf`, `abandonPendingResponses`,
    `processingPoolShutDown`, `shutdownProcessingPool`, `handleResponse`, `processStorageRanges` (with its nested
    `handleProofOfAbsence` / `handleEmptyResponse`), `processServedTasks`, `createStorageSubTasks`,
    `recordSubtaskCompletion`. `handleResponse`, `submitProcessingJobs`, `drainProcessedResponses`,
    `abandonPendingResponses` and `shutdownProcessingPool` lose `private` (core arms, `onPostStop`, `PreRestart`); the
    core fields the module reaches become public (`emptyResponsesByTask`, `staleRootFailuresByAccount`,
    `abandonedAccounts`, `staleRootFailureEvents`, `processingGeneration`, `processingPool`, `processingEc`); the core
    drops its `scala.concurrent.Future` import. `processingPool` and `processingEc` stay core vals (rule (d)): A-S4a
    assigned them to C-S4, but a module trait cannot hold a concrete non-lazy val, and a lazy pool would be created by
    the stop-time `shutdownProcessingPool` (a behaviour change); the module reaches them through State, the C-S3
    `flatBatchEc` precedent. (4) The narrowing commit (no `# adopts:`): the self-type names
    `StorageResponseProcessingState & StorageRangeEnv & StorageResponsePeerApi & StoragePeerFeedbackApi &
    StorageChunkApplyApi & StorageTrieFlushApi & StorageCompletedTaskApi`; the Impl adds the State parent; the three
    siblings extend their new Api, and `applyOrderedStorageChunk` loses `private[actors]` (the C-S3
    `flushTrieNodeWrites` precedent). `StorageApplyCoreApi` still declares `createStorageSubTasks` and
    `recordSubtaskCompletion`; the Impl now satisfies them through this module, so C-S2's recorded interface is left as
    it is. `apply`, `ChildFactories` and `ChildFactoriesSpec` unchanged.
    - **The companion wildcard (a judgement call).** The module imports the companion as `StorageRangeCoordinator.*`,
      not member by member as C-S1..S3 do. A named `import ...StorageRangeCoordinator.verifyStorageChunk` matches the
      ratchet's `.xStorageY` member-access pattern and counted as one storage reference (591 > the 590 ceiling, measured
      on the first version of the move commit), a false positive of the kind counter v2 allow-lists
      (`verifyStorageRange`, the call it wraps, is on `STORAGE_NAME_ALLOW_V2`). The wildcard reproduces exactly the scope
      the bodies were written in (the Impl's own `import StorageRangeCoordinator.*`); the members the bodies reach are
      listed in the move commit. Allow-listing `verifyStorageChunk` is a counter change, left for the next tooling pass
      (follow-up below), the C-S3 `FlatSlotBatch` alias being the precedent for a local import workaround.
    - **Off-actor pipeline hot path (#1533, for vault).** Threads: unchanged. On the actor thread: `handleResponse`
      (index the response once, `validateStorageRanges`, `completeRequest`, the `activeTasks` removal), `enqueueResponse`
      (the `PendingResponse`, its sequence number and generation), `submitProcessingJobs` (captures the job's tasks and
      response, the skip set from `abandonedAccounts`, the prebuild scheme and `self` before the `Future`), the core's
      `StorageResponseProcessed` arm (the running-job count, the generation check that drops a superseded result, the
      job's `verified` and `ready`), `drainProcessedResponses` and everything it applies, and every RocksDB write (the
      trie-node flush after each applied response). On `processingEc` (the owned daemon pool, the test override, or
      `ExecutionContext.parasitic` in inline mode): only `verifyServedSlots`, a pure function of its captured arguments.
      The completion callback runs on `parasitic` and only posts `StorageResponseProcessed` to the mailbox. Ordering:
      unchanged; jobs are submitted oldest first up to `processing.maxInFlightJobs`, and `drainProcessedResponses`
      applies strictly from the head of `pendingResponses`, so a job that finishes early waits for the responses before
      it and the ordering gate sees chunks in arrival order. Generation drop: unchanged; `abandonPendingResponses` (pivot
      refresh, force-complete, stop) bumps `processingGeneration`, and the core arm ignores a result of an older one.
      Allocation: none added. The one bytecode-level change is that the module now reads the queue, the abandoned set,
      `processingEc`, `processingGeneration`, the job counters and `trieWriteNanos` through State getters (interface
      calls, monomorphic in production), once per enqueued, submitted or drained response, never per slot or per node;
      `inlineVerifyNanos` and `nextResponseSeq` are now fields of the trait instead of the Impl (same object, same
      thread). The pool is still built at construction and shut down by `onPostStop` and `PreRestart`.
    - Counts: `StorageResponseProcessing` State 21 (R14b 19, plus `processingPool` and `processingEc`, immutable vals
      that rule (d) keeps in the core and R14b's matrix of mutable fields does not list), Api 14 (= R14b:
      `StorageResponsePeerApi` 8 and `StoragePeerFeedbackApi` 2 by `StoragePeerDispatch`, `StorageChunkApplyApi` 2 and
      `StorageTrieFlushApi` 1 by `StorageOrderedApply`, `StorageCompletedTaskApi` 1 by `StorageDoneMarkers`).
      `StoragePeerDispatch` 24 / 1, `StorageOrderedApply` 13 / 7 and `StorageDoneMarkers` 11 / 1 unchanged.
      `StorageRangeEnv` 20, unchanged (G9). Lines: `StorageRangeCoordinator.scala` 1,914 → 1,471,
      `StorageResponseProcessing.scala` 507 (characterization estimate 476).
    - Storage total **590, unchanged** (zero-reference slice). The characterization §5 grep over the moved members prints
      `.setStoragePendingTries` ×2 and `requestTracker.validateStorageRanges`, all on `STORAGE_NAME_ALLOW_V2`, the
      baseline's counter; `StorageResponseProcessing.scala` measures 0 (no row), `StorageRangeCoordinator.scala` stays 32.
      `storage-baseline.json` is unchanged.
    - Tests (`# new-tests:`): `StorageResponseProcessingSpec` (11 cases, a plain `AnyFlatSpec`; the jobs run on
      `ExecutionContext.parasitic` and report to a `TestInbox`): the job cap and its result message, the worker's skip
      set and the prebuild when merkleization is not deferred, the head-only drain in arrival order with one trie flush
      per response, no redispatch to a peer gone after answering, the generation bump, the worker's result vs inline
      verification, the stale-local-root give-up (3 failures, 2 peers, queued siblings dropped, no peer penalty), empty
      strikes and the skip at 5, proof-of-absence through the ordering gate, the 16-way subtask split and the
      per-account subtask count, the pool shutdown. No existing test changed: `StorageRangeCoordinatorSpec` and
      `StorageRangeCoordinatorOffActorSpec` reach the moved members through the Impl as before, and the sibling stub
      states implement what their modules require, which did not grow.
    - Left as they were (FR-034, no cleanups in a move PR): `giveUpOnAccountForHealing`'s scaladoc points at
      `staleRootFailuresByAccount`'s doc, which stays in the core with the field; the #1533 rationale block stays above
      the queue fields in the core; the moved `maxEmptyResponsesPerTask` and `maxStaleRootFailuresPerAccount` sit in the
      trait without the field-block comments that explain them.
    - Follow-up, done (tooling PR before C-H3; no measured count moved, the wildcard import counting 0 already) (tooling, not this slice): add `verifyStorageChunk` (the pure companion wrapper of
      `MerkleProofVerifier.verifyStorageRange`) to `STORAGE_NAME_ALLOW_V2`, so a module may import it by name; C-H3 and
      C-H5 may meet the same import shape.
    - Owed: the second-specialist review (prism), forge's "no ETC behaviour change" sign-off (SRC runs on ETC), and vault
      on the hot-path note above (#1533). No node run is required by characterization §7 for C-S4 (no storage path
      changes).
- [x] T070 [C-H0] Split TNHC `activeBehavior` into per-concern partial functions, with an arm-order table as in T036.
  (T060: a plain restructure commit with no `# moved:` trailer, like P4 and the lift PRs. 25 arms + catch-all, listed
  in characterization Appendix A. Then a `# moved:` commit of the 8 Impl-nested messages and the helper classes to the
  companion.)
  (Restructure only, no narrowing; the baseline total must not rise.)
  - **As built** (branch `refactor/snap-016-ch0-heal-arms`, two code commits, one test commit and this docs commit; no
    sbt or JVM run, CI compiles and tests). (1) Restructure, no trailer: `activeBehavior` becomes
    `Behaviors.receiveMessage[Command]` over an `orElse` chain of seven `private[actors] lazy val` partial functions on
    the Impl, in the order of each group's first arm: `healLifecycleArms` (core: #1 `StartTrieNodeHealing`, #9
    `HealingForceComplete`, #10 `HealingPivotRefreshed`, #21 `HealingStagnationCheck`, #22 `HealingGetProgress`),
    `frontierRebuildArms` (C-H2: #2, #3, #4, #25), `healResponseArms` (C-H5: #5, #13, #15), `healDispatchArms` (C-H4:
    #6, #7, #8, #11, #12, #23), `frontierPersistenceArms` (C-H1: #14), `healVerificationArms` (C-H3: #16–#20, #24) and
    `unhandledCommandArms` (#26, the catch-all, last). Counts 5 + 4 + 3 + 6 + 1 + 6 + 1 = 26. Every arm body is
    byte-identical at the same indentation (`--color-moved` shows each as moved); inside a group the arms keep their
    source order. The arm-order table is research.md **R4c** (arm, staging line, pattern, guard, main calls by module,
    group, C-H0 line). Why first-match is unchanged although the groups interleave in the source: the only Command with
    more than one arm is `VerificationBFSComplete` (#17 `walkRoot != stateRoot`, #18 `missingEmitted > 0`, #19
    unguarded), and the only wildcard is the catch-all; every other arm matches its own case class or object. The three
    `VerificationBFSComplete` arms sit together, in order, in `healVerificationArms`, and the catch-all is last. The
    guards are pure comparisons. (2) `# moved: HealingEntry, ActiveRequest, HealingStagnationCheck, FlushComplete,
    FrontierRebuilt, VerificationBFSComplete, FrontierRebuildComplete, FrontierWalkFailed, RestartResumeVerification,
    RestartFullRebuild`: the seven internal messages (with their comment block, verbatim), the stagnation pulse's
    message and the two helper classes go to the companion, byte-identical. Ten symbols, not the "8" above: the 8 are
    the seven messages plus `HealingStagnationCheck`, and `HealingEntry`/`ActiveRequest` are the helper classes
    (characterization §3.4 lists all ten); step 2 rejects a removed member that the trailer does not list. Visibility
    widens from `private` to `private[actors]` (the Impl is not the object's companion class, so a `private` object
    member would be invisible to it; the C-S2 `ByteStringOrdering` precedent). The Impl's `import
    TrieNodeHealingCoordinator.*` covers every use site; no `isInstanceOf`/`getClass` in the file. The comments above
    `pendingTasks`, the stagnation vars and `activeRequests` also describe those fields and stay. (3) Test
    (`# new-tests:`): `TrieNodeHealingDispatchTableSpec` (3 cases, `BehaviorTestKit` on the real Impl, an execution
    context that never runs a task so the walk and flush Futures never start): per sample of all 24 Commands (four of
    `VerificationBFSComplete`, one per guard outcome), exactly the expected group is defined (none for
    `HealingTaskFailed`) and the catch-all is defined everywhere; every sample but `HealingTaskFailed` is handled
    (`HealingTaskFailed` returns `Behaviors.unhandled`); each `VerificationBFSComplete` sample runs the arm its guards
    select (#17, #17, #18, #19), read from the arm's own log line. It follows the move commit because the internal
    messages were Impl-private before it. No existing test changed.
    - Judgement calls (R4c "Placements"): `HealingStagnationCheck` is lifecycle (the liveness pulse touches H1, H3 and
      H4); `HealingServeRootRefresh` is dispatch (fetch root only) and `HealingPivotRefreshed` lifecycle (walk root);
      `FrontierRebuilt` goes with the rebuild (it is the BFS's output) though it calls H5's `queueNodes`. The groups
      name a concern, not a destination: the arms stay in the core through C-H5 (Appendix A), and a slice that wants a
      group in its module moves the whole `lazy val`.
    - Left as it was (FR-034): the catch-all's comment still calls `Command` non-sealed; it is `sealed` in the
      companion. Logged here, not fixed in a restructure.
    - Storage total **603, unchanged** (no code left the file; comments are not counted). `storage-baseline.json`
      unchanged. No narrowing, so no counts.
    - Owed: the second-specialist review (prism) and forge's "no ETC behaviour change" sign-off (TNHC runs on ETC);
      `scalafmtCheckAll` was not run locally (no JVM on the soak host), so the autofix job may reformat. No vault review
      and no node run (characterization §7: none for C-H0; no storage path changes).
- [x] T071 [C-H1] `FrontierPersistence` (vault; T060: the first TNHC slice that touches storage. It adopts `mptStorage`
  and `healingFrontierStorage` per D-T060-1). Narrowing takes `StateIngest` (frontier and BFS stores), never
  `StateAccess`; lowers the baseline total. Run a Mordor heal restart.
  - **Stop and decision.** The first attempt stopped before any code. `mptStorage` was already settled: the Coordinators
    "Amendment 2026-10-13" names C-H1, so it stays an explicit session value (reached through the Env at the declaration
    cost). `healingFrontierStorage` had no `StateIngest` member (T060d deferred the healing stores): adopting it needed
    the #1584 verify change for a by-name member fed by `HealingOrchestrator`'s lazy val, a separate `# capability:` PR
    (verify forbids `# capability:` with `# adopts:` or `# moved:`, and every snap/ commit of a capability range carries
    it), and a decision on recovery's mirror. Orchestrator decision 2026-10-15 under the user's standing D-T060-1 (route
    (a)): both healing stores become members in one extension (`healingFrontier: Option[HealingFrontierStorage]`,
    `bfsQueue: Option[BfsQueueStorage]`, by-name from the lazy vals), recovery's capability hands out `None` for both,
    and C-H1 adopts `healingFrontier`. Three PRs, stacked.
  - **As built, PR 1 (tooling)** (branch `test/snap-016-verify-lazy-members`, two commits, `# test-files: none`; no
    JVM). (1) Lazy healing handles in a capability extension (#1584): a core accessor argument that is a `lazy val` of
    the core or a snap/controller file feeds a by-name `apply` parameter of exactly its type (a strict one would build
    the store with the capability); its declaration only widens `private` → `private[snap]`, or is the **Option lift**
    of a removed `private lazy val g: T = e` into `private[snap] lazy val h: Option[T] = Some(e)`, after which each
    `Some(g)` in that file may stand as `stateIngest.<member>`; SyncController's mirror declares such a handle `val h:
    <its type> = None` and may not read the member. (2) Found on the C-H1 narrowing: the adoption check compared the
    `ChildFactories` defaults with `apply`'s token by token, and TNHC's `apply` (in its companion) writes
    `DefaultVisitedCap` where `ChildFactories` writes `TrieNodeHealingCoordinator.DefaultVisitedCap` (nine such
    defaults; the same constants, so `ChildFactoriesSpec` passes). A `<Coordinator>.X` default now drops its qualifier
    when `X` is a member of the companion; nothing else is normalized. Self-tests: 10 new (7 `LazyHandleTests`, 3
    adoption cases), 246 in all, OK. Replay of the 56 merged refactor/test snap-016 ranges: byte-identical.
  - **As built, PR 2 (capability)** (branch `refactor/snap-016-ch1a-stateingest-healing`, one `# capability:
    StateIngest` commit and a docs commit). `StateIngest` 12 → 14: `healingFrontier` and `bfsQueue`, by-name `apply`
    parameters `healingFrontierStorageOpt` and `bfsQueueStorageOpt` with pure forwarders (map lines
    `healingFrontierStorageOpt -> healingFrontier`, `bfsQueueStorageOpt -> bfsQueue`). `HealingOrchestrator`:
    `healingFrontierStorageOpt` widens to `private[snap]`; `bfsQueueStorage: BfsQueueStorage = new
    RocksDbBfsQueueStorage(...)` lifts into `bfsQueueStorageOpt: Option[BfsQueueStorage] = Some(new ...)` (its only use
    was the spawn's `Some(bfsQueueStorage)`); the healing spawn passes `stateIngest.healingFrontier` and
    `stateIngest.bfsQueue`. The core's accessor appends the two handles; SyncController's mirror declares both `None`.
    Same instances, built at the same first read (the spawn). Tests: the anonymous test `StateIngest`s and
    `ForwardingStateIngest` gain the two members (failing loudly; `ForwardingStateIngest` takes them by-name with a
    failing default), and `HealingOrchestratorSpec`'s stub hands in a frontier store and an in-memory queue. Storage
    total 590 → 592 (+2, justified: the core's accessor names the two handles; `HealingOrchestrator` stays 43).
  - **As built, PR 3 (C-H1)** (branch `refactor/snap-016-ch1-frontier-persistence`, three code commits and this docs
    commit). No `# moved:` type commit: C-H0 moved the Impl-nested types, and every C-H1 member was already an indent-2
    member. (1) Prerequisite: `FrontierPersistenceState` (11) in the new `actors/healing/FrontierPersistence.scala` and
    `TrieNodeHealingEnv` (9: `log`, `self`, `mptStorage`, `frontierPersistenceEnabled`, `decoupledHealServeRoot`,
    `decoupledHealMaxAttemptsNoRefresh`, `frontierHighWater`, `frontierLowWater`, `frontierBackpressureMaxWaitMs`) in
    the new `actors/healing/TrieNodeHealingEnv.scala`; the eight constructor parameters the module reads (and
    `healingFrontierStorage`, for the temporary self-type) become `val`s. No Api trait: the module calls nothing in the
    core. (2) The move commit takes 8 members byte-identical into `FrontierPersistence`: `persistFrontier`,
    `unpersistFrontier`, `clearPersistedFrontier`, `emitHealingFrontierGauges`, `awaitFrontierDrain`,
    `writeDurableSubtreeRecords`, `flushRawNodesSync`, `flushRawNodesAsync` (with the Layer-2 section comment). No field
    moves (every one is shared, or a rule (d) core val: `pendingBackpressure`, `prunedEnabled`, `healingWriterEc`). Six
    lose `private` (the core calls them); `unpersistFrontier` and `writeDurableSubtreeRecords` stay `private`. The core
    members the module reaches become public; the core drops `scala.concurrent.blocking`. The self-type is `this:`
    (`flushRawNodesAsync` reads `self`). (3) The narrowing commit, `# adopts: StateIngest TrieNodeHealingCoordinator`:
    self-type `FrontierPersistenceState & TrieNodeHealingEnv`; the Impl adds `with FrontierPersistenceState`;
    `TrieNodeHealingEnv` gains `stateIngest`. `healingFrontierStorage: Option[HealingFrontierStorage] = None` →
    `stateIngest: StateIngest` in the Impl constructor (a `val`), `apply`, `new`,
    `ChildFactories.trieNodeHealingCoordinator` and the `HealingOrchestrator` spawn; eight core reads and four module
    reads use `stateIngest.healingFrontier` (`// adopt-map: healingFrontierStorage -> healingFrontier`). `mptStorage`,
    `bfsQueueStorageOpt` (C-H2), `pathNodeStorageOpt` (C-H3) and `evmCodeStorage` (C-H5) are unchanged.
    `ChildFactoriesSpec` unchanged.
    - **For T075.** TNHC now takes a plain `stateIngest: StateIngest`, not `Option[StateIngest]` (the adoption gate
      supports only the plain form). C-H5's `evmCodeStorage` adoption, whose `None` test default turns the healed-code
      check off (characterization §3.4), therefore needs the `healedCodeCheck: Boolean` route, or keeps
      `evmCodeStorage` explicit; an `Option[StateIngest]` is no longer open.
    - **Verify range.** Each PR is verified against its parent branch. From staging, this branch fails until PR 2
      merges: the range then mixes PR 2's `# capability:` commit with move and adopts commits, which verify forbids in
      one range.
    - **Frontier write path (for vault).** *When:* `persistFrontier` on every new enqueue (`queueNodes`, inline child
      discovery, the pivot reseed), one `update(Nil, entries).commit()` per call; `unpersistFrontier` once per
      healed-node flush; `clearPersistedFrontier` on a differing-root refresh or an abandonment, one batch delete then
      `clearComplete()`; all three gated on `frontierPersistenceEnabled` (default off), so with persistence off CF `g`
      holds only the spec 005 subtree records and the completeness marker. *Batching:* one RocksDB batch per call, as
      before; healed nodes are buffered (`rawNodeBuffer`, 1000) and flushed as one `storeRawNodes` + `persist()`.
      *Durability ordering relative to node writes:* each flush (sync at completion, async on
      `healing-writer-dispatcher` otherwise) runs `mptStorage.storeRawNodes`, `mptStorage.persist()`, then the staged
      subtree-complete records (`markSubtreeComplete`, WAL-ordered `update`), then the frontier unpersist. A healed node
      leaves the frontier only after its bytes are persisted, and a subtree record is written only after the subtree's
      bytes are (spec 005 D3, record-after-persist); a crash in between re-heals or re-descends, never skips. The
      completeness marker stays a `commitSync()` write. Byte-identical bodies, same threads, same instances.
    - **Instance and cost.** `stateIngest.healingFrontier` forwards, by-name, to `HealingOrchestrator`'s lazy
      `healingFrontierStorageOpt`, the instance the spawn passed before. Its first read moves off the spawn (the
      controller's thread). With `prunedHealVerification` on and the Hash scheme it is the coordinator's construction
      (`prunedEnabled`, on the coordinator's thread at setup; the `&&` short-circuits otherwise). Otherwise it is the
      coordinator's first read on its own thread (the `[HEAL-RESTART]` arm, a `persistFrontier` or another core read),
      or a flush's unpersist on the healing-writer thread. Building the store does no I/O, and the lazy val is
      thread-safe. Each read is an interface call, a thunk and a
      lazy-val read where it was a field read: per call of the mirror methods, never per node in the module; the core's
      per-child `exists(_.isSubtreeComplete(c))` in the inline child discovery (C-H5's code) pays it per child, beside a
      RocksDB get, and the frontier walk captures the store once (`prunedStore`). A hoist is a body change, left to
      C-H5.
    - Counts: `FrontierPersistence` State 11 (R14b 9, plus `prunedEnabled` and `healingWriterEc`, immutable vals rule
      (d) keeps in the core), Api 0 (= R14b). `TrieNodeHealingEnv` 10 (G9), one store (`mptStorage`).
    - Storage total **592 → 582** (counter v2): the adoption −13 (TNHC −6 net of the move, `ChildFactories` 58 → 53,
      `HealingOrchestrator` 43 → 41), then `TrieNodeHealingEnv`'s `mptStorage` declaration and import, +3 exactly (the
      session-value Env declaration cost; covered by the adoption). `TrieNodeHealingCoordinator.scala` 70 → 53,
      `actors/healing/FrontierPersistence.scala` 0 → 11 (the characterization's 11),
      `actors/healing/TrieNodeHealingEnv.scala` 0 → 3. Across the three PRs, 590 → 582. `storage-baseline.json` records
      it with a fresh justification line.
    - Tests (`# new-tests:`): `FrontierPersistenceSpec` (6 cases, a plain `AnyFlatSpec`; an `EphemDataSource` frontier
      store, a recording trie store, the async flush on `ExecutionContext.parasitic`): the mirror gate; the sync flush's
      record-after-persist and the unpersist; no records with pruning off, no write on an empty buffer; the async
      flush's `FlushComplete`, single flight and `flushing` left for the core; the clear of pending and in-flight
      entries and the marker; the backpressure mirror (its last case only calls the drain wait). Review follow-up
      (`# new-tests:`, a new suite because the test-hunk guard forbids assertion edits to a suite that exists at the
      parent commit, the C-S3 `StorageDoneMarkersFlushSpec` precedent): `FrontierPersistenceOrderSpec` (4 cases): on
      both flushes, at `persist()` no subtree record yet and every flushed node still a frontier entry, then the records
      and the unpersist (a mutation that unpersists before `persist()` fails it); the drain wait, no wait or log below
      the high-water mark, the INFO pause and WARN safety-timeout lines above it (0 ms timeout).
      `HealingTrieFixtures.healingIngest` builds the coordinator's `StateIngest` (`healingFrontier` the spec's store,
      every other member throws); `TrieNodeHealingDispatchTableSpec` passes `healingIngest(None)`;
      `RecordingChildFactories` records `stateIngest.healingFrontier` in the unchanged
      `ChildSpawn.Healing.healingFrontierStorage`. No assertion changed.
    - Build: `sbt compile` and `Test/compile` green; scalafmtAll applied. `testOnly` over the healing suites
      (`FrontierPersistenceSpec`, the dispatch table, `TrieNodeHealingCoordinatorSpec`, the resume, rebuild, pruned,
      scoped, decoupled, moving-root, seeding and path-presence specs), `HealingOrchestratorSpec`,
      `SNAPSyncControllerPinSpec`, `ChildFactoriesSpec`, `StateIngestDurabilitySpec`, `DownloadSupervisorSpec`: green.
      Not run: `testEssential`.
    - Owed: vault (the frontier write path above, the lazy healing members), forge ("no ETC behaviour change": the
      Hash-scheme subtree records and the healed-node flush are ETC's path), beacon (Path scheme), the second-specialist
      review (prism), and the **Mordor heal restart** (characterization §7), run by the orchestrator.
- [x] T072 [C-H2] `FrontierRebuildBfs` (T060: adopts `bfsQueueStorageOpt` per D-T060-1; add vault). Narrowing takes `StateIngest` (presence reads), never `StateAccess`; lowers the
  baseline total.
  - **No stop.** The one candidate was the BFS queue's construction thread (below); the T071 precedent settles it (the
    same move, recorded and merged for `healingFrontier`). No dropped default at a spawn site (the production spawn
    passed `stateIngest.bfsQueue`; the test shim keeps its own `bfsQueueStorageOpt` and hands it to its `StateIngest`),
    no store joins the session-value list (`bfsQueue` is a rule (d) core val reached through State, the C-S3
    `storageDoneStorage` precedent), no durability change (the queue is scratch).
  - **As built** (branch `refactor/snap-016-ch2-frontier-rebuild`, three code commits and this docs commit). (1)
    Prerequisite: `FrontierRebuildBfsState` and `PathVerifiedReadApi` (`readPathVerifiedNode`, C-H3's, still in the
    core) are declared in the new `actors/healing/FrontierRebuildBfs.scala`; `FrontierDrainApi` (`awaitFrontierDrain`)
    in `FrontierPersistence.scala`, which `FrontierPersistence` now extends (a separate trait so C-H1's interfaces stay
    as recorded, the C-S3 `StorageTrieFlushApi` precedent); `TrieNodeHealingEnv` gains `storageScheme` (the parameter
    becomes a `val`) and `asyncLog` (loses `private`); the two `FrontierPersistence` spec stubs gain both. (2) The move
    commit (`# moved:` 8 names, nine definitions) takes `rebuildFrontierBFS` (with the older single-seed scaladoc before
    it), both `startFrontierBFS` overloads, and the six visit-key helpers with their section comment, byte-identical.
    No field moves. Both launchers lose `private` (the core calls them); the walk and the helpers stay `private`. The
    core members the module reaches become public; the core drops `Await`, `Duration`, `BfsEntry` and
    `HealingFrontierStorage` imports. `boundedVisitedSet`, `computeEffectiveParallelism` and `BfsChunkSize` stay on the
    companion (the bodies name them qualified; no `export`). (3) The narrowing commit, `# adopts: StateIngest
    TrieNodeHealingCoordinator`: self-type `FrontierRebuildBfsState & TrieNodeHealingEnv & PathVerifiedReadApi &
    FrontierDrainApi`; the Impl adds `with FrontierRebuildBfsState with PathVerifiedReadApi`.
    `bfsQueueStorageOpt: Option[BfsQueueStorage] = None` is dropped from the Impl constructor, `apply`, `new`,
    `ChildFactories.trieNodeHealingCoordinator` (and its `BfsQueueStorage` import) and the `HealingOrchestrator` spawn;
    the core's `bfsQueue` reads `stateIngest.bfsQueue.getOrElse(new InMemoryBfsQueueStorage())`. The capability's bare
    `capability-map: bfsQueueStorageOpt -> bfsQueue` maps it (no `adopt-map` line). A second adoption on a coordinator
    that already takes `stateIngest`: a pure drop, verify's `[adopts]` reports "drops bfsQueueStorageOpt ->
    stateIngest.bfsQueue".
    - **Judgement calls.** (a) The queue's first read moves (the T071 precedent, below). (b) `asyncLog` goes on the Env
      (a logger, beside `log`), not State; `storageScheme` on the Env (a constructor setting). (c) The nine immutable
      core vals the walk reads (`bfsQueue`, both dispatchers, `prunedEnabled`, `FrontierBatchSize`, the four walk caps)
      are State members under rule (d), as C-H1 did for `prunedEnabled` and `healingWriterEc`. (d)
      `awaitFrontierDrain` is reached through a new `FrontierDrainApi` rather than a self-type on the module trait.
    - **BFS queue path (for vault).** *Instance:* `stateIngest.bfsQueue` forwards, by-name, to `HealingOrchestrator`'s
      lazy `bfsQueueStorageOpt` (`Some(new RocksDbBfsQueueStorage(flatSlots.dataSource, BfsQueueNamespace))`), the
      instance the spawn passed before. *First read:* it moves from the spawn (the controller's thread, evaluating the
      factory's arguments) to the coordinator's construction (the core's `bfsQueue` val, inside `Behaviors.setup` on the
      coordinator's `sync-dispatcher` thread), unconditionally (unlike `healingFrontier`, whose read at construction is
      behind `prunedEnabled`). Building the store allocates an `AtomicLong` and does no I/O; the namespace is opened with
      the DB; the lazy val is thread-safe. A supervisor restart re-runs setup and reads the same instance (before, the
      behaviour reused the spawn's `Option`); `None` (tests) still builds a fresh `InMemoryBfsQueueStorage` per Impl.
      *Queue I/O, unchanged:* `clear()` and the seed `enqueueBatch` at walk entry, the level `deleteRange` and the final
      `clear()` on the healing writer thread; chunked `iterateRange` reads and child `enqueueBatch` on the writer thread
      (serial) or the healing reader threads (a split level). *Durability:* none relied on; the queue is scratch, cleared
      on entry and exit (whole keyspace, so a crash's leftovers go), levels tombstoned as consumed. The walk's node reads
      (`mptStorage.multiGetNodes` under Hash, `readPathVerifiedNode` under Path) and the spec 005 oracle's
      `isSubtreeComplete` on `stateIngest.healingFrontier` (captured once per walk, `prunedStore`) are unchanged.
    - Counts: `FrontierRebuildBfs` State 11 (R14b 2: `verificationBFSRunning`, `prunedSubtreeCount`; plus nine rule (d)
      vals), Api 2 (= R14b). `TrieNodeHealingEnv` 12 (G9: + `storageScheme`, `asyncLog`), one store (`mptStorage`).
    - Storage total **582 → 571** (counter v2), a fall with no exception: the adoption −13 (TNHC −6, `ChildFactories`
      53 → 48, `HealingOrchestrator` 41 → 39); kept +2: State's `bfsQueue` declaration and its import, and the module's
      `HealingFrontierStorage` import (+3), less the core's `HealingFrontierStorage` import (−1).
      `TrieNodeHealingCoordinator.scala` 53 → 42, `actors/healing/FrontierRebuildBfs.scala` 0 → 7 (the characterization's
      4 genuine refs, the six `.isStorage` uncounted under v2, plus the three declarations). `storage-baseline.json`
      records it with a fresh justification line (C-H1's moved to the history).
    - Tests (`# new-tests:`): `FrontierRebuildBfsSpec` (7 cases, a plain `AnyFlatSpec`; the stub State, Env, Path read
      and drain wait over the `HealingTrieFixtures` tries, both dispatchers `ExecutionContext.parasitic`): the launcher
      flag and the `onComplete` count, one `FrontierRebuilt` per missing node with its pathset on the branch and the
      extension de-dup arms, one drain wait per batch and an empty queue after; a complete trie; a throwing queue
      (`FrontierWalkFailed`, no `onComplete`); the descend-and-stop oracle on and off; two seeds sharing one visited set;
      the Path scheme's path-verified reads (no hash batch read) and per-location de-dup (the shared node walked at both
      locations). `HealingTrieFixtures.healingIngest` gains `bfsQueueStore` (default `None`); the shim passes its
      `bfsQueueStorageOpt` there; `RecordingChildFactories` records `stateIngest.bfsQueue` in the unchanged
      `ChildSpawn.Healing.bfsQueueStorageOpt`. No assertion changed.
    - Build: `sbt Test/compile` green (an unused `scala.concurrent.Future` import in the core, found by the first compile, is dropped in the move commit); scalafmtAll applied. `testOnly` over 28 suites, 165 tests, all green: `FrontierRebuildBfsSpec` (7), `FrontierPersistenceSpec`, `FrontierPersistenceOrderSpec`, `TrieNodeHealingCoordinatorSpec`, `TrieNodeHealingDispatchTableSpec`,
      `TrieNodeHealingWorkerSpec`, the scope-capture and scoped-verification specs, `FrontierRebuildSpec`, `RebuildFrontierBfsMultiSeedSpec`, `CleanRebuildEarlyCompletionSpec`, `HealingFrontierResumeSpec`, the four pruned-heal
      specs, the three decoupled-heal specs, the three scoped-verification specs, `PathHealPresenceSpec`, `SubtreeCompleteSeedingSpec`, `HealedAccountCodeSpec`, `ChildFactoriesSpec`, `SNAPSyncControllerPinSpec`,
      `HealingOrchestratorSpec`. Not run: `testEssential`.
    - #1350 and #1349 (placed at this module) are not touched: a move PR fixes nothing (FR-034).
    - Owed: vault (the queue path above), forge ("no ETC behaviour change": the Hash walk and the pruned oracle are
      ETC's path), beacon (the Path reads and location keys), the second-specialist review (prism). Characterization §7 (step 13) lists no node run for
      C-H2; a Mordor heal restart (the walk's crash-recovery path) is the orchestrator's call.
- [x] T073 [C-H3] `HealVerification` (vault; T060: plus `readPathVerifiedRlp`/`Node`. Adopts `pathNodeStorageOpt` →
  `pathNodes`). Narrowing takes `StateIngest` (verification reads), never `StateAccess`;
  lowers the baseline total.
  - **No stop.** The adopted store is read where it was (the spawn passed `stateIngest.pathNodes`, SNAPSyncController's
    strict `pathNodeStorageOpt`), so no construction or first read moves; the coordinator now reads the capability's
    `val` per use, the same instance. No dropped default at a spawn site (the production spawn passed the store; the
    test shim keeps its own `pathNodeStorageOpt` and hands it to its `StateIngest`), no store joins the session-value
    list, no durability change (the Path write is the same `writeAccountNode`/`writeStorageNode` on the same store).
  - **As built** (branch `refactor/snap-016-ch3-heal-verification`, three code commits and this docs commit). (1)
    Prerequisite: `HealVerificationState`, `PathVerifiedRlpApi` (`readPathVerifiedRlp`) and `HealCompletionApi`
    (`isComplete`, C-H5's, still in the core) are declared in the new `actors/healing/HealVerification.scala`;
    `PathVerifiedReadApi` moves there unchanged from `FrontierRebuildBfs.scala` (which drops its `MptNode` import);
    `FrontierRebuildApi` (both `startFrontierBFS` overloads) in `FrontierRebuildBfs.scala`, which `FrontierRebuildBfs`
    now extends (the `FrontierDrainApi` precedent); `TrieNodeHealingEnv` gains `prunedHealVerification` (the parameter
    becomes a `val`); `pathNodeStorageOpt` becomes a `val` for the move's temporary self-type (the C-H1
    `healingFrontierStorage` precedent); the three healing spec stubs gain the Env member. (2) The move commit
    (`# moved:` 5 names) takes `reverifyCurrentRoot`, `startVerificationBFS`, `startScopedVerification`,
    `readPathVerifiedRlp` and `readPathVerifiedNode`, byte-identical, with their scaladocs. No field moves. The four
    `private` entry points and the read lose `private` (the core calls them); the core members the module reaches become
    public (`stateRoot`, `trieWalkInProgress`, `verificationPassComplete`, the five scoped/pruned observability vars,
    `isComplete`); the core drops its `kec256` import. The module trait extends `PathVerifiedReadApi` and
    `PathVerifiedRlpApi`. (3) The narrowing commit, `# adopts: StateIngest TrieNodeHealingCoordinator`: self-type
    `HealVerificationState & TrieNodeHealingEnv & FrontierRebuildApi & HealCompletionApi`; the Impl adds `with
    HealVerificationState with HealCompletionApi` and drops its own `with PathVerifiedReadApi` (the module provides it).
    `pathNodeStorageOpt: Option[PathNodeStorage] = None` is dropped from the Impl constructor, `apply`, `new`,
    `ChildFactories.trieNodeHealingCoordinator` (and its `PathNodeStorage` import) and the `HealingOrchestrator` spawn;
    the module's read in `readPathVerifiedRlp` and the core's Path-scheme healed-node write in `processActiveResponse`
    (C-H5's code; characterization §4.2: a parameter the core also uses is substituted in the core in the same commit)
    read `stateIngest.pathNodes`; the core drops its `PathNodeStorage` import. The capability's bare `capability-map:
    pathNodeStorageOpt -> pathNodes` maps it (no `adopt-map` line); verify's `[adopts]` reports "drops
    pathNodeStorageOpt -> stateIngest.pathNodes".
    - **Judgement calls.** (a) `PathVerifiedReadApi` moves to the file of the module that provides it, as C-H2's note
      asked; `HealVerification` extends it, so `FrontierRebuildBfs`'s self-type is satisfied through the module, and
      the trait's text is unchanged except its scaladoc's "stays in the core until C-H3". (b) `readPathVerifiedRlp`
      gets its own `PathVerifiedRlpApi` rather than joining `PathVerifiedReadApi`, so C-H2's recorded interface (and
      its stub) stays as it is; C-H5's module will take it in its self-type for `isNodeInStorage`. (c) The launchers
      are reached through a new `FrontierRebuildApi` that `FrontierRebuildBfs` extends, not a self-type on the module
      (the C-H2 `FrontierDrainApi` precedent). (d) `isComplete` is reached through `HealCompletionApi`, declared in
      the consumer's file (as C-H2 declared `PathVerifiedReadApi` in its own); C-H5 may move it when it moves
      `isComplete`. (e) `prunedHealVerification` goes on the Env (a constructor setting, the `storageScheme`
      precedent), not State. (f) `prunedEnabled` is a State member under rule (d), as in C-H1 and C-H2.
    - **Path node store (for vault and beacon).** *Instance:* `stateIngest.pathNodes` is SNAPSyncController's strict
      `pathNodeStorageOpt` (`Some(new PathNodeStorage(flatSlotStorage.dataSource))` under Path, `None` under Hash), the
      value the spawn passed before; the coordinator reads the capability's `val` on each use (a field read, no
      construction, no I/O). *Option semantics, both schemes:* under Hash (ETC/Mordor) it is `None`, so
      `readPathVerifiedRlp` returns `None` and the healed-node write is skipped, exactly as with the `None` parameter,
      and neither is reached under Hash (`isNodeInStorage`, the frontier walk and `processActiveResponse` dispatch on
      `storageScheme` first). Under Path it is the one store: reads at the entry's own path, present only when the
      bytes hash to the entry's hash (unchanged), writes by path on the actor thread (unchanged). *Threads:* the reads
      run on the actor thread (presence check) and on the healing writer and reader dispatchers (the walk), as before.
    - Counts: `HealVerification` State 12 (R14b 11: the ten vars and `prunedSubtreeCount`; plus `prunedEnabled`), Api
      2 traits = R14b (`FrontierRebuildApi`, `HealCompletionApi`; verify counts 3 members, the two overloads and
      `isComplete`). `TrieNodeHealingEnv` 13 (G9: + `prunedHealVerification`), one store (`mptStorage`).
    - Storage total **571 → 558** (counter v2), a fall with no exception: the adoption −14 (TNHC −7: constructor,
      `apply`, `new` argument and import; `ChildFactories` 48 → 43; `HealingOrchestrator` 39 → 37); kept +1: the
      module's `MptStorage` import for the moved `MptStorage.decodeNode`. `TrieNodeHealingCoordinator.scala` 42 → 31,
      `actors/healing/HealVerification.scala` 0 → 5 (the characterization's 4, `stateIngest.healingFrontier`,
      `stateIngest.pathNodes`, `.readStorageNode`, `MptStorage`, plus the import). `storage-baseline.json` records it
      with a fresh justification line (C-H2's moved to the history).
    - Tests (`# new-tests:`): `HealVerificationSpec` (8 cases, a plain `AnyFlatSpec`; the stub State, Env, launchers
      and completion flag, a `PathNodeStorage` over `EphemDataSource`): account and storage verified reads; stale
      bytes, an empty slot, an empty and a three-element pathset and a missing store all absent; decode, and bytes that
      hash right but do not decode reported absent; `reverifyCurrentRoot`'s direct full-root walk and its four
      deferring conditions (`HealingCheckCompletion` to `self`, the verdict reset either way); the full-root entry's
      pruned engagement on and off with the counter reset; the scoped entry's seed mapping, observability and
      completion against its launch root. `HealingTrieFixtures.healingIngest` gains `pathNodeStore` (default `None`);
      the shim passes its `pathNodeStorageOpt` there; `RecordingChildFactories` records `stateIngest.pathNodes` in the
      unchanged `ChildSpawn.Healing.pathNodeStorageOpt`. No assertion changed.
    - Build: `sbt compile-all` green on each code commit (prerequisite, move, narrowing); scalafmtAll applied (scaladoc reflow only, folded into the commit that wrote each line; the moved bodies unchanged). `testOnly` over 30 suites, 175 tests, all green: `HealVerificationSpec` (8), `FrontierRebuildBfsSpec`, `FrontierRebuildSpec`, `FrontierPersistenceSpec`, `FrontierPersistenceOrderSpec`, `TrieNodeHealingCoordinatorSpec`, `TrieNodeHealingDispatchTableSpec`, `TrieNodeHealingWorkerSpec`, the scope-capture and scoped-verification specs, `StaleVerificationWalkSpec`, `RebuildFrontierBfsMultiSeedSpec`, `CleanRebuildEarlyCompletionSpec`, `HealingFrontierResumeSpec`, the four pruned-heal specs, the three decoupled-heal specs, the three scoped-verification specs, `PathHealPresenceSpec`, `SubtreeCompleteSeedingSpec`, `HealedAccountCodeSpec`, `ChildFactoriesSpec`, `SNAPSyncControllerPinSpec`, `HealingOrchestratorSpec`. Not run: `testEssential`.
    - Owed: vault (the store path above), forge ("no ETC behaviour change": the Hash scheme's `None` store and the
      pruned-verification entry are ETC's path), beacon (the Path reads and the healed-node write), the
      second-specialist review (prism). Characterization §7 (step 14) lists no node run for C-H3.
- [x] T074 [C-H4] `HealDispatch` (T060: a zero-reference slice; grep recorded, total unchanged). Narrowing takes `StateIngest` where it touches storage, never `StateAccess`; lowers
  the baseline total.
  - **No stop.** A zero-reference slice (characterization §5): the moved code holds no storage reference, so nothing is
    adopted, no store's construction or first read moves, no spawn site or default changes, no session value is
    declared, and `apply`, `ChildFactories`, the `HealingOrchestrator` spawn and `ChildFactoriesSpec` are unchanged.
  - **As built** (branch `refactor/snap-016-ch4-heal-dispatch`, three code commits and this docs commit). (1)
    Prerequisite: `HealDispatchState` declared in the new `actors/healing/HealDispatch.scala`; `FrontierGaugesApi`
    (`emitHealingFrontierGauges`) in `FrontierPersistence.scala`, which `FrontierPersistence` now extends (the
    `FrontierDrainApi` precedent); `TrieNodeHealingEnv` gains `networkPeerManager`, `requestTracker`,
    `snapSyncController`, `batchSize` and `movingRootDeltaHeal` (the parameters become `val`s; the four healing spec
    stubs gain the members); the twelve constants only the dispatch reads become `private lazy val` (the C-S1
    precedent). (2) The move commit (`# moved:` 28 names) takes, byte-identical with their comments and scaladocs, the
    throttle's `healThrottle`/`healPending` (literal-initialised private vars of the trait) and six constants,
    `shortRootLabel`, `noteUnservableAttempt`, the response budget (five constants, `responseBytesTargetFor`,
    `adjustResponseBytesOnSuccess`/`OnFailure`), `peerCooldownDefault`, `isPeerCoolingDown`, `recordPeerCooldown`,
    `inFlightForPeer`, `dispatchIfPossible`, `updateHealThrottle`, `effectiveBatchSize`, `requestNextBatch`,
    `handleTimeout` and `tryRedispatchPendingTasks`. The ten the core calls lose `private`; the fifteen core fields the
    module reaches become public; the core drops its `GetTrieNodes` import. (3) The narrowing commit: self-type
    `HealDispatchState & TrieNodeHealingEnv & FrontierGaugesApi`; the Impl adds `with HealDispatchState`. No
    `# adopts:`.
    - **Judgement calls.** (a) The response-bytes trio moves here, as the characterization row and plan.md's C-H4 row
      place it and as C-A1/C-S1 moved ARC's and SRC's; C-X (T076) dedupes the three module copies (T075b's finding:
      TNHC's hard-coded 524288/51200 become parameters there). (b) `lastThrottleAdjustMs` is the dispatch's alone, but
      its `System.currentTimeMillis()` initializer is not a literal, so rule (d) keeps it in the core as a State `var`;
      `healRate` stays because the `[HEAL-PULSE]` arm logs it. (c) `emitHealingFrontierGauges` is reached through a new
      `FrontierGaugesApi` in the provider's file, not a self-type on the other module. (d) No provider Api for C-H5:
      the core calls the moved members through the Impl; C-H5 declares what its module needs (the C-S2/C-S4
      precedent). (e) The five constructor values go on the Env (settings and actor refs, the C-S1 `StorageRangeEnv`
      precedent), not State. (f) Timers: nothing timer-related moves. The `TimerScheduler` is read only by `start()`
      (the stagnation pulse, core); the dispatch's one deferred action is the request tracker's timeout callback,
      `self ! HealingRequestTimeout(requestId)`, which reaches `handleTimeout` through the core's arm on the mailbox.
      plan.md's C-H4 row lists `[HEAL-WATCHDOG]`; that log line is the stagnation pulse's and stays in the core.
    - Counts: `HealDispatch` State 21 (R14b 18, plus `maxConcurrentRequests`, `rawFlushThreshold`,
      `healingStagnationTimeoutMs`, immutable vals rule (d) keeps in the core), Api 1 (= R14b, `FrontierGaugesApi`).
      `TrieNodeHealingEnv` 18 (G9: + the five constructor values; no store).
    - Storage total **558, unchanged** (the tasks.md exception): the §5 grep over
      `actors/healing/HealDispatch.scala` prints `[]` under `STORAGE_NAME_ALLOW_V2`; `TrieNodeHealingCoordinator.scala`
      stays at 31, `TrieNodeHealingEnv.scala` at 3. `storage-baseline.json` is unchanged.
    - Tests (`# new-tests:`): `HealDispatchSpec` (10 cases, `ScalaTestWithActorTestKit`; the stub State, Env and
      gauges counter, test inboxes for `self`, the peer manager and the controller, a real `SNAPRequestTracker` whose
      in-flight timers each case cancels): the throttle up to the 8-path cap, its one-second hold and recovery; the
      one-path floor; the `GetTrieNodes` root (walk, serve when decoupled, walk under the moving root) with the queued
      paths and the initial budget; the per-peer and global caps and no dispatch to a stateless or cooling peer or
      under the refresh latch; `handleTimeout`'s cooldown, halved budget, re-queue and completion check; the decoupled
      attempt count and the once-only stagnation escalation; the `[HEAL-FLOOR]` revive on the periodic path only; the
      response budget; `noteUnservableAttempt`; `shortRootLabel`. No existing assertion changed.
    - Build: `sbt compile-all` green on each code commit (prerequisite, move, narrowing); scalafmtAll applied
      (scaladoc reflow and three stub lines, folded into the commit that wrote each line; the moved bodies unchanged).
      `testOnly` over 25 suites, 171 tests, all green: `HealDispatchSpec` (10), `HealVerificationSpec`,
      `FrontierRebuildBfsSpec`, `FrontierPersistenceSpec`, `FrontierPersistenceOrderSpec`,
      `TrieNodeHealingCoordinatorSpec`, `TrieNodeHealingDispatchTableSpec`, `TrieNodeHealingWorkerSpec`, the scope-capture
      and scoped-verification specs, `StaleVerificationWalkSpec`, `ResponseWindowGoldenSpec`, the four pruned-heal specs,
      the three decoupled-heal specs, the three scoped-verification specs, `PathHealPresenceSpec`, `ChildFactoriesSpec`,
      `HealingOrchestratorSpec`. Not run: `testEssential`.
    - Owed: forge ("no ETC behaviour change": the Hash scheme's dispatch is ETC's path), the second-specialist review
      (prism). No vault review (no storage) and no node run (characterization §7) for this slice.
- [x] T075 [C-H5] `HealResponseProcessing` (T060: plus `isNodeInStorage`. It adopts `evmCodeStorage`, whose `None` test
  default turns the healed-code check off, so the narrowing needs `Option[StateIngest]` or a flag. `RecordingChildFactories`
  keeps `ChildSpawn.Healing`'s fields for the protected pin. Add vault, and a Mordor heal restart). Narrowing takes `StateIngest` (healed nodes and code), never `StateAccess`;
  lowers the baseline total. Check SC-002.
  - **No stop.** The healed-code check takes the `healedCodeCheck: Boolean` route that characterization §3.4 and T071's
    "For T075" note name (`Option[StateIngest]` is closed: TNHC takes a plain `stateIngest`, and verify rejects the
    `Option` form). Keeping `evmCodeStorage` explicit would put it on the Env as a session value, which is not on the
    closed list, and would not lower the total. The adopted store is the instance the spawn already passed
    (`stateIngest.bytecode`), so no construction or first read moves; no dropped default at a spawn site (the
    production spawn passed the store, the test shim keeps its `Option` and hands it to its `StateIngest`); no store
    joins the session-value list; no durability change (the check only reads).
  - **As built** (branch `refactor/snap-016-ch5-heal-response`, three code commits and this docs commit). (1)
    Prerequisite: `HealResponseProcessingState` (23) declared in the new `actors/healing/HealResponseProcessing.scala`;
    `HealCompletionApi` moves there unchanged from `HealVerification.scala` (an Api lives with its provider, the C-H3
    `PathVerifiedReadApi` / `FrontierDrainApi` precedent; prism's C-H3 note); `HealDispatchApi` (7) in
    `HealDispatch.scala`, which `HealDispatch` now extends; `FrontierWriteApi` (`persistFrontier`, `flushRawNodesAsync`)
    in `FrontierPersistence.scala`, which `FrontierPersistence` now extends; `TrieNodeHealingEnv` gains
    `scopedHealMaxPaths` (the parameter becomes a `val`) and `healedCodeCheck` (the five healing spec stubs gain both);
    `EmptyResponseStrikeThreshold` and `healedCodeHashesThisResponse` become `private lazy val` (pure: a literal and an
    empty collection). And the flag split, the slice's one forced plain-commit logic edit: `evmCodeStorage:
    Option[EvmCodeStorage] = None` becomes `val healedCodeCheck: Boolean = false` plus a plain `val evmCodeStorage:
    EvmCodeStorage` (Impl, `apply`, `ChildFactories`; the spawn passes `healedCodeCheck = true` and
    `stateIngest.bytecode`), and `recordHealedCodeHash`'s `evmCodeStorage.foreach { codes => ... }` becomes `if
    healedCodeCheck then ... evmCodeStorage.get(...)`. The adoption check cannot carry either edit (an `# adopts:` commit
    adds only `stateIngest`, and a dropped parameter's type must equal its member's), so the prerequisite does, verified
    on its own (`verify.py` OK at that commit). The shim maps its `Option` to the flag and an unread ephemeral store;
    the dispatch-table spec's direct construction passes that store until the narrowing; `RecordingChildFactories`
    records `Option.when(healedCodeCheck)(evmCodeStorage)`. (2) The move commit (`# moved:` 12 names) takes
    `healedCodeHashesThisResponse`, `childrenDiscoveredTotal`, `clearHealedPathsSet`, `EmptyResponseStrikeThreshold`,
    `queueNodes`, `handleResponse`, `processActiveResponse`, `isComplete`, `recordHealedCodeHash`,
    `flushHealedCodeHashes`, `discoverMissingChildren` and `isNodeInStorage`, byte-identical with their comments. Four
    lose `private` (the core calls them; `isComplete` was public); the seven core fields the module reaches become
    public; the core drops its `domain.Account` import. (3) The narrowing commit, `# adopts: StateIngest
    TrieNodeHealingCoordinator`: self-type `HealResponseProcessingState & TrieNodeHealingEnv & FrontierGaugesApi &
    FrontierWriteApi & HealDispatchApi & PathVerifiedRlpApi`; the module extends `HealCompletionApi`; the Impl adds
    `with HealResponseProcessingState` and drops `with HealCompletionApi`. `evmCodeStorage` is dropped from the Impl
    constructor, `apply`, `new`, `ChildFactories.trieNodeHealingCoordinator` (and its import) and the
    `HealingOrchestrator` spawn; the module's one read becomes `stateIngest.bytecode.get` (the bare `capability-map:
    evmCodeStorage -> bytecode`; verify's `[adopts]` reports "drops evmCodeStorage -> stateIngest.bytecode").
    - **Judgement calls.** (a) The flag route and its placement in the prerequisite (above); `healedCodeCheck`'s
      default is `false`, the old `None`'s meaning, and only the production spawn turns it on. A tooling follow-up
      could let an adoption commit pair a `<flag>: Boolean = false` add with an `Option` drop, so the logic edit is
      checked; not needed now. (b) `HealCompletionApi` moves to its provider; `isNodeInStorage` gets no Api (its other
      callers are core arms, through the Impl, C-H4 judgement (d)). (c) The dispatch members get one `HealDispatchApi`
      and the two frontier writes one `FrontierWriteApi`, each in the provider's file, rather than module self-types
      (the `FrontierDrainApi` precedent). (d) `EmptyResponseStrikeThreshold` and `healedCodeHashesThisResponse` move as
      `private lazy val`s (the C-H4 constants precedent) rather than staying as State members; `childrenDiscoveredTotal`
      moves as a private `var` (literal initializer). (e) `scopedHealMaxPaths` and `healedCodeCheck` go on the Env
      (constructor settings), not State. (f) `calculateProgress`, `calculateNodesPerSecond`, `calculateKilobytesPerSecond`
      stay in the core (the progress arm's), so `completedTaskCount` and `totalBytesReceived` are State `var`s.
    - **Bytecode store path (for vault, forge and beacon).** *Instance:* `stateIngest.bytecode`, SNAPSyncController's
      env `evmCodeStorage`, the value the spawn wrapped in `Some` before; the coordinator reads the capability's member
      per healed account leaf (a field read). *Read:* the same `get` (never the execution cache), on the actor thread,
      only with the check on; no write. *Both schemes:* the check is scheme-independent, so Hash (ETC/Mordor) and Path
      run the same code; the `HealedCodeHashes` report is still one message per response, before that response's
      `ProgressNodesHealed`. With the flag off (bare construction, tests) the store is never read.
    - Counts: `HealResponseProcessing` State 23 (= R14b), Api 11 in 4 traits (= R14b: `FrontierGaugesApi` 1,
      `FrontierWriteApi` 2, `HealDispatchApi` 7, `PathVerifiedRlpApi` 1). `TrieNodeHealingEnv` 20 (G9: +
      `scopedHealMaxPaths`, `healedCodeCheck`), one store (`mptStorage`).
    - Storage total **558 → 544** (counter v2), a fall with no exception: the adoption −14 (TNHC −7: constructor,
      `apply`, `new` argument and import; `ChildFactories` 43 → 38; `HealingOrchestrator` 37 → 35).
      `TrieNodeHealingCoordinator.scala` 31 → 17, `actors/healing/HealResponseProcessing.scala` 0 → 7 (the
      characterization's 12 less the five `isNodeInStorage` false positives: `mptStorage` ×3, `stateIngest.pathNodes`,
      `.writeStorageNode`, `stateIngest.healingFrontier`, `stateIngest.bytecode`). `storage-baseline.json` records it
      with a fresh justification line (C-H3's moved to the history).
    - **SC-002 (checked, not met yet).** "After the last coordinator slice, no coordinator file exceeds about 1,000
      lines." At this head: `TrieNodeHealingCoordinator.scala` 1,532 (1,941 before C-H5),
      `StorageRangeCoordinator.scala` 1,471, `AccountRangeCoordinator.scala` 1,147, `ByteCodeCoordinator.scala` 548.
      C-X (T076) removes only the three response-window copies (tens of lines), so SC-002 will not hold after it
      either: three cores keep their arms, lifecycle and fields above the bound. A user decision (amend SC-002, or plan
      further C-* slices, e.g. moving arm groups) before R1. **Resolved 2026-10-17:** SC-002 amended to these sizes
      (spec.md, "Amendment 2026-10-17: SC-002 coordinator file size").
    - Tests (`# new-tests:`): `HealResponseProcessingSpec` (10 cases, `ScalaTestWithActorTestKit`; the stub State,
      Env and recording interfaces, inboxes for `self` and the controller, a `TestMptStorage`, an ephemeral frontier
      store and `PathNodeStorage`): the hash-matched accept and dropped mismatch, the re-queue and its
      `noteUnservableAttempt`, the feedback order; an untracked response; the spec 003 capture, overflow latch and
      cross-root count, then `clearHealedPathsSet`; the strikes and the fifth-strike stateless mark, eviction and
      `HealingAllPeersStateless`; the Path write at the task path and both alias kinds; a branch's absent child queued
      and persisted; the storage-root seed and the healed-code report on (absent reported first, held not) and off
      (store never read); the spec 005 seed; `queueNodes`' de-dup and `isComplete`; presence by hash and by verified
      path. `HealingTrieFixtures.healingIngest` gains `codeStore` (default `None`, `bytecode` throws);
      `RecordingChildFactories` records `Option.when(healedCodeCheck)(stateIngest.bytecode)` in the unchanged
      `ChildSpawn.Healing.evmCodeStorage`. No assertion changed.
    - Build: `sbt compile-all` green on each code commit (prerequisite, move, narrowing); scalafmtAll applied (scaladoc
      reflow and one blank line, folded into the commit that wrote each line; the moved bodies unchanged). `testOnly`
      over 30 suites, 189 tests, all green (`HealResponseProcessingSpec` 10, the healing module specs, the
      `TrieNodeHealing*` specs, the pruned, decoupled, scoped, path-presence, seeding, resume and early-completion specs,
      `HealedAccountCodeSpec`, `ChildFactoriesSpec`, `SNAPSyncControllerPinSpec`, `HealingOrchestratorSpec`). Not run:
      `testEssential`.
    - Owed: vault (the healed-code read), forge ("no ETC behaviour change": the Hash scheme's response processing, the
      spec 005 seed and the healed-code check are ETC's path), beacon (the Path-scheme healed-node write and presence
      reads), the second-specialist review (prism), and the **Mordor heal restart** (characterization §7), run by the
      orchestrator.
- [x] T075b [C-X0] Test-only golden of the response-size window for ARC, SRC and TNHC: initial target, grow on a ≥90%
  fill, shrink on failure, min/max clamps, and the exact DEBUG log text with its per-coordinator label. It lands
  before C-X. Built as `ResponseWindowGoldenSpec`. Finding for C-X: the three bodies are identical (factors 1.25 and
  0.5, the 90% rule, the 2 MiB ceiling, rounding); they differ only in the log label (`account`, `storage`, `healing`)
  and in where initial/min come from (ARC and SRC: configuration, defaults 524288/102400 and 1048576/131072; TNHC:
  hard-coded 524288/51200), so the extraction takes initial and min as parameters too.
- [x] T076 [C-X] `AdaptiveResponseBytes` for ARC, SRC and TNHC (identical bodies, log label as a parameter).
  ByteCode is untouched.
  - **As built** (branch `refactor/snap-016-cx-adaptive-response-bytes`, two code commits and this docs commit, on
    staging after C-H5's squash). (1) `snap/AdaptiveResponseBytes.scala` (72 lines, where plan.md's source tree puts
    it, next to `SnapIntakeBudget`, outside the module directories): `final class AdaptiveResponseBytes(label,
    initial, min, targets, log)` with `targetFor`, `onSuccess`, `onFailure`; the 2 MiB ceiling and the 1.25/0.5
    factors are companion constants. The bodies are the module bodies verbatim. Plus its unit spec. (2) Adoption: each
    module (`AccountPeerDispatch`, `StoragePeerDispatch`, `HealDispatch`) drops its five lazy-val constants and the
    three bodies for one `private lazy val responseWindow` and three one-line forwarders with the old names and
    visibility. Parameters: ARC `"account"`, `BigInt(initialResponseBytesConfig)`, `BigInt(minResponseBytesConfig)`;
    SRC `"storage"`, `BigInt(configInitialResponseBytes)`, `BigInt(configMinResponseBytes)`; TNHC `"healing"`, 512 KiB,
    50 KiB.
  - **Commit shape.** Plain refactor commits, no `# moved:` / `# adopts:` / `# capability:` (the C-H0, T070
    precedent): three copies collapse to one, so a move's identical-body check cannot apply, and no module file
    loses its last impl mention, so there is no narrowing. verify runs FR-016 (a)/(d), the test-hunk guard (the new
    suite named in `# new-tests:`), the protected paths and the storage ratchet on them.
  - **Judgement calls.** (a) A parameterised class composed in, not a mixin with abstract label/initial/min members:
    the module traits gain no abstract members, so no stub, State, Api or Env changes. (b) The window owns no state:
    `targets` is the coordinator's own `peerResponseBytesTarget` (still a core val, still cleared by the core on a
    pivot change) and `log` is the coordinator's Env logger, so the DEBUG line keeps its logger name as well as its
    text (a class logger would have passed the golden, which filters by package, but changed the logger in
    production). (c) `private lazy val`, not `val`: FR-016 (d) forbids a concrete member `val`, and `lazy` keeps the
    first read of the Env values at first use, as the old lazy constants did. (d) SRC's `responseBytesTargetFor` stays
    `private`; ARC's and TNHC's stay public (their Api traits declare them). (e) SRC's implicit `Int -> BigInt` is
    made explicit; same value.
  - Counts: unchanged (no State, Api or Env member added or removed). Storage total unchanged at **544** (no storage
    reference involved).
  - Lines: the three module files 392 -> 384 (ARC `AccountPeerDispatch`), 562 -> 553 (SRC `StoragePeerDispatch`) and
    361 -> 348 (`HealDispatch`): -73 / +43, net **-30**; with the new 72-line file
    (half of it scaladoc) the slice is **+42 net** in `src/main`, against plan.md's estimate of -60.
  - **SC-002 after C-X (unchanged by this slice).** C-X touches the module files, not the coordinator cores, so the
    four coordinator files are exactly as after C-H5: `TrieNodeHealingCoordinator.scala` 1,532,
    `StorageRangeCoordinator.scala` 1,471, `AccountRangeCoordinator.scala` 1,147, `ByteCodeCoordinator.scala` 548.
    Three exceed the ~1,000 bound; the decision (amend SC-002 or plan further C-* slices) is the user's, before R1.
    **Resolved 2026-10-17:** the user accepted these sizes and SC-002 is amended (spec.md, "Amendment 2026-10-17:
    SC-002 coordinator file size").
  - Tests (`# new-tests:`): `AdaptiveResponseBytesSpec` (9 cases, Tier 1): seeding, read-side clamps without rewrite,
    the exact 90% boundary, no growth below it / for a zero request / at the ceiling, rounding and the growth cap,
    halving with rounding and the floor, the seed-before-halve of an unseen peer, the DEBUG line's level, logger and
    text, and no line on success. `ResponseWindowGoldenSpec` unchanged (21 cases) and green; no stub suite edited.
  - Build: `sbt compile-all` green; `scalafmtAll` changed nothing. `testOnly` over 15 suites, 234 tests, all green
    (`ResponseWindowGoldenSpec`, `AdaptiveResponseBytesSpec`, `AccountPeerDispatchSpec`, `StoragePeerDispatchSpec`,
    `HealDispatchSpec`, the `AccountRange*`, `StorageRange*` and `TrieNodeHealing*` specs). Not run: `testEssential`.
  - Owed: the second-specialist review (prism); forge's "no ETC behaviour change" note (the window runs on ETC's
    Hash-scheme path too). No vault review and no node run (no storage, characterization row 17).

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
