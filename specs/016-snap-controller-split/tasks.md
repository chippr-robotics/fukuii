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
