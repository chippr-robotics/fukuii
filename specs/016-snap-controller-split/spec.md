# Feature Specification: Split SNAPSyncController and the SNAP Coordinators into Phase Modules

**Feature Branch**: `docs/spec-016-snap-split` (spec only). Each slice gets its own branch, named in `tasks.md`.

**Created**: 2026-10-08

**Status**: Draft. Phase 0 (characterization) is complete for the controller.

**Issue**: #1401 "Split SNAPSyncController"

**Input**: Split the SNAP controller and the AccountRange, StorageRange, TrieNodeHealing and ByteCode coordinators so
that every SNAP phase is a module that an agent or a reviewer can be pointed at on its own. There is no behaviour
change. The design input is the static characterization in [research.md](./research.md) (Phase 0).

## Problem

The issue describes `SNAPSyncController.scala` as about 2k lines. On `staging@9a6681cc0` it is **6,646 lines**, and
PR #1501 takes it to 6,779. The coordinators add 2,900 (TrieNodeHealing), 2,273 (StorageRange), 2,193
(AccountRange) and 842 (ByteCode) lines. In total, **14,854 lines** decide how a node gets from genesis to a healed
state trie, and five large classes hold all of it.

The size is a symptom. The cost comes from how the code is shaped:

- **One behaviour serves five phases.** `syncing` is 1,150 lines. It serves account, bytecode/storage, healing,
  validation and chain-download completion, and it picks between them with 28 `currentPhase ==` tests. A fix to one
  phase is made inside code that every other phase also runs through.
- **Three hand-rolled resets.** `restartSnapSync`, `wakeFromDormant` and `enterDormantMode` each write 13 to 28 vars,
  and each picks a slightly different set. When someone adds a var, they must remember to add it to all three.
- **Fixes that are not pinned.** None of the five fixes restored by #1385 has a test that fails when the fix is
  removed. They were lost once already, and a refactor of this size could lose them again without anyone noticing.
- **No phase can be handed to a specialist.** Today an agent asked to fix healing must read the whole controller to
  find the healing code.

## User Scenarios & Testing *(mandatory)*

### User Story 1 — An operator restarts a node mid-SNAP across any slice (Priority: P1)

An operator runs a node that is part-way through a SNAP sync. They upgrade from a build before slice N to a build after
slice N and restart. The node resumes where it stopped: the account cursor, carried task files, done-markers, healing
frontier and good-peer list are all still used.

**Why this priority**: a SNAP sync on ETH mainnet takes weeks. A refactor that forces a resync fails the reason the
refactor exists. This is the one property no slice may break.

**Independent Test**: the golden-bytes tests from slice S0c pass unchanged after the slice. On the validation node
(Mordor or Platåberget), stop it mid-phase on the pre-slice jar, start the post-slice jar, and check that the resume
log lines and counters continue rather than restart.

**Acceptance Scenarios**:

1. **Given** a node mid-account-phase with an `AccountResumeCheckpoint` v1 and carried task files, **When** it
   restarts on the post-slice build, **Then** it logs a resume at the saved cursor and replays only unfinished
   carried tasks (the spec 010 done-markers are honoured).
2. **Given** a node in storage, healing or validation with persisted phase flags, **When** it restarts on the
   post-slice build, **Then** the same phase resumes and no phase flag is cleared.
3. **Given** any slice, **When** the S0c golden-bytes suite runs, **Then** every encoded format is byte-identical to
   the baseline vectors.

### User Story 2 — A maintainer or agent works on one SNAP phase (Priority: P1)

A maintainer, or an agent such as `vault`, `herald` or `prism`, is asked to fix "heal convergence" or "storage pivot
refresh". The routing doc `.claude/agent-protocols/snap-sync.md` names the files, config keys, metrics, log tags and
reviewing specialist for that phase. The code for that phase is in those files, and the files are a size a reviewer
can read in one sitting.

**Why this priority**: this is what the issue asks for, and what the user wants: to point an agent at one phase.

**Independent Test**: for each phase row in the routing doc, the named files exist after the slice that targets them.
Each named log tag and metric is defined in, or emitted from, one of those files (a grep check per row, T090).

**Acceptance Scenarios**:

1. **Given** the routing doc after the final slice, **When** an agent looks up "StateHealing",
   **Then** it gets the healing module files, `healing-*`/`*-heal-*` keys, `snapsync.healing.*` metrics and `[HEAL*]`
   tags, and `vault` as the reviewer for frontier persistence.
2. **Given** the final controller, **When** its size is measured, **Then** the shell is no more than about 1,000
   lines, and no `currentPhase ==` test remains inside `syncing`.

### User Story 3 — A regression of a restored fix fails CI (Priority: P1)

A developer accidentally reverts one of the #1385 fixes, for example by replacing `.start()` with `.startSnapSync()`
in `apply`. CI fails with a test that names the fix.

**Why this priority**: the split moves the code these fixes live in. Pins must land **before** any code moves, so
that each move PR proves it kept them.

**Independent Test**: for each pin, the test is written against the baseline and passes there. Reverting the pinned
expression makes it fail; this is checked once in a throwaway CI run on a scratch branch that is never merged (no
local builds, FR-035), and the result is recorded in the S0a/S0b PR.

**Acceptance Scenarios**: one per pin, FR-020 to FR-024.

### User Story 4 — The live Sepolia sync is never the test bed (Priority: P2)

The Sepolia node is mid-SNAP, about two weeks from head, and stays on fix-only builds until it reaches head.
Extraction does **not** wait for that: P1 starts as soon as #1501 merges (user decision, 2026-10-08). Sepolia hotfixes
come from the `snap-split-base` tag and are forward-ported (FR-037). Each extraction slice is validated on Mordor (ETC,
Hash scheme) and on the Platåberget devnet (ETH, PoS, Path scheme).

**Why this priority**: debugging a structural refactor through a multi-week live sync is the wrong order of risk.

**Independent Test**: every slice PR states its Mordor and Platåberget validation result, or says it was deferred.
None names the Sepolia node.

### Edge Cases

- **A message arrives in a phase where today it falls through to the catch-all** (for example `MinPivotBlock` in
  `syncing`). After P4 it must still fall through to the same "Unhandled message in syncing state" log. The phase
  split must not quietly start handling it or dropping it.
- **A stale validator `Future` completes after a reset.** The `validationGeneration` check must still drop it. That
  check moves with Validation (M2), and the generation bump stays at all three external writers.
- **The ChainDownloader survives restart and dormancy.** `stopStateSyncChildren`, `restartSnapSync` and
  `enterDormantMode` do not stop it today. `CoordinatorHandles.stopAll()` must not stop it either.
- **`stopHeapWatchdog` runs at exactly two sites** (`onStop`, `stopSnapOnlySchedules`). The reset trio does not stop
  it, and P1/P2 must not add it there.
- **Partial resets are deliberate.** The three reset paths reset different sets (research.md R3a). `reset(kind)` keeps
  the differences.
- **Slices interleave with fix PRs.** A hotfix that lands on staging during the split must be rebased through the
  remaining slices, and a move PR that conflicts with it is redone, not hand-merged (FR-037).

## Requirements *(mandatory)*

### Scope

- **FR-001** The scope is `SNAPSyncController.scala` and the four coordinators: `AccountRangeCoordinator`,
  `StorageRangeCoordinator`, `TrieNodeHealingCoordinator` and `ByteCodeCoordinator`. Workers, `ChainDownloader`,
  `StateValidator`, the storage classes and the three #1501 files (`SnapIntakeBudget`, `SnapHeapWatchdog`,
  `GatedTaskFileReplay`) are placed in the routing doc but not split.
- **FR-002** Every SNAP phase is a separately targetable module, meaning:
  - **Controller**: one file per module in the map (plan.md §Module map).
  - **Coordinators**: one file per concern inside each coordinator (plan.md §Coordinator modules).
- **FR-003** **Sequencing gate: met.** PR #1501 (spec 014) merged on 2026-10-08 (`dfa4a8836`). The S0 slices
  (S0a–S0g) land first. P1 starts as soon as they have merged; it does not wait for the Sepolia node to reach head
  (user decision, 2026-10-08). T001 (re-check against #1501 as merged) is done; see research.md R1 and R11.

### Phase 0

- **FR-004** Phase 0 is the static characterization in research.md (R0–R13). Coordinator characterization is finished
  per coordinator in T060, before the first coordinator slice. If T060 contradicts the boundaries in plan.md, plan.md
  is amended before that slice.

### Prerequisite refactors (each is its own slice and PR)

- **FR-010 `CoordinatorHandles` (P1).** One holder for the four coordinator refs, plus a separate
  `ChainDownloaderHandle`. It provides `stopAll()` (exactly the four coordinators: the same set and order as
  `stopStateSyncChildren` today), `broadcastPeerUnavailable`, `pivotRefreshed(root)` and the response fan-out. It
  carries `intakeBudget` into the spawn helpers. It replaces the copied stop/clear lines at
  `enterDormantMode`/`restartSnapSync`/`stopStateSyncChildren` and the per-site `foreach` fan-outs. There is still no
  `ctx.watch` and no change to supervision. Modules see the #1501 budget only through an **abstract
  `def intakeBudget: SnapIntakeBudget`** declared in `CoordinatorHandles` and implemented by the core. Before P1, S0d
  pins the fan-outs it replaces (T026).
- **FR-011 `PhaseFlags` + `reset(kind)` (P2).** One holder for the phase-complete and force-complete flags:
  `accountsComplete`, `bytecodePhaseComplete`, `storagePhaseComplete`, `storagePhaseForceCompleted`,
  `bytecodeForceCompleted`, `forceCompleteStorageSent`, `awaitingHealedCode`, `healedCodeWaitExhausted` and
  `resumedStaleCursors`. A single `reset(kind: Start | Restart | Wake | Dormant)` replaces the reset writes in
  `startSnapSync`, `restartSnapSync`, `wakeFromDormant` and `enterDormantMode`. A single `cancelSyncTimers(keys)`
  replaces the three hand-copied cancel lists, and each call site passes its own list, unchanged. The stagnation
  clocks (`storageTailBaseline`, `lastStorageProgressMs`, `lastAccountProgressMs`, `lastAccountTasksCompleted`,
  `lastAccountsDownloaded`, `lastBytecodeProgress*`) are **not** added to `PhaseFlags`. P2 leaves their reset writes
  inline at today's sites. M7 replaces those writes with `watchdog.reset(reason)`, so `PhaseFlags` stays the nine
  flags.
- **FR-012 Reset sets are kept exactly.** P2's per-kind field sets equal today's write sets once each write is
  classified as reset or set (research.md R3a). A difference that looks like a bug is recorded in CHASE-QUEUE and not
  fixed in P2.
- **FR-013 Single peer-event handler (P3).** One `peerEventArms` partial function handles the quartet that is
  duplicated across four behaviours today: `WrappedHandshakedPeers`, `WrappedPeerDisconnected`,
  `FlushPeerDisconnects` and `PollHandshakedPeers`. The same function also handles `GetProgress` and the four
  identical `CLPivotHint` arms. `bootstrapping` keeps its longer `PollHandshakedPeers` arm, the reactivity variant,
  ahead of the shared function. The `GetStatus` bodies differ per behaviour and stay per behaviour.
- **FR-014 Per-phase arms (P4).** `syncing` is restructured into one partial function per `SyncPhase` plus a
  `commonSyncingArms` partial function and the existing catch-all, chosen by `currentPhase` at the moment the message
  is handled. That removes the 28 `currentPhase ==` tests inside `syncing`. The arms for a given message type keep
  their original relative order, so a message that matched guarded arm A before unguarded arm B still does.
  `currentPhase` stays a var with the same writers, because status, metrics and `GetStatus` read it. Turning each
  phase into its own Pekko `Behavior` value is **not** part of P4; it is a later option (plan.md D3). Before P4a, a
  table-driven Command × phase test from S0d (T027) pins today's dispatch for every Command in every `SyncPhase`,
  including catch-all fall-through (for example `MinPivotBlock` in `syncing` logs "Unhandled message in syncing
  state").

### Module extraction (one module per PR)

- **FR-015 Order.** Lowest coupling first:
  1. M1 pure decisions to the companion;
  2. M2 Validation;
  3. M3 Peer pool;
  4. M4 Finalization;
  5. M5 Persistence/Resume planner;
  6. M6 Healing orchestration;
  7. M7 Stagnation watchdog;
  8. M8 Pivot selection;
  9. M9 Lifecycle/reset (+dormant);
  10. M10 Pivot refresher;
  11. M11 Download supervisor.

  The coordinators follow (C-B1, C-A1…, C-S1…, C-H1…, C-X). The plan gives each module's target file, owned state,
  Commands, config keys, metrics and log tags.
- **FR-016 Move, then narrow (both required, in the same PR).** Decision recorded in ADR CON-013.
  - **Commit 1, the move.**
    - The module goes into a trait in its own file (plan.md D1). Its method bodies, and the declarations of vars it
      owns exclusively, move **unchanged**.
    - The self-type may temporarily name `SNAPSyncControllerImpl`.
    - The only allowed edits are the `trait`/file header, imports, and visibility widening (`private` →
      `private[snap]`) where another module calls the member.
    - Rule (d) applies **already in commit 1**: a declaration whose initializer is not allowed by (d) does not move
      and stays in the core.
  - **Commit 2, the narrowing (required).** The self-type is replaced by
    `self: <Module>State & <capability traits> & <Callee>Api… =>`:
    - `<Module>State` lists exactly the shared non-hub fields the module reads or writes (research.md R14).
    - The capability traits are `SnapSharedState` (the hubs), `CoordinatorHandles` (child refs and
      `def intakeBudget`), `PhaseFlags` and `SnapControllerEnv`.
    - There is one `<Callee>Api` trait for every other module, or not-yet-extracted code, that this module calls
      (research.md R14a). The core implements the Api traits of code that has not moved yet.
    - Exclusive vars become `private` state of the trait.
  - **The only body-adjacent edit in commit 2 is `val` → `lazy val`/`def`, and only for a *pure* initializer.**
    - Pure means no `ctx.`, `timers.`, `spawn`, `scheduleOnce`, metric registration, subscription, logging,
      `intakeBudget`, I/O or other side effect.
    - The PR lists each converted val with "pure: yes" and the reviewer's grep of its initializer.
    - An impure initializer stays in the core, behind an abstract `def` in `<Module>State` or `SnapControllerEnv`.
      Making it lazy would move its side effect in time.
    - The plan's initializer grep (plan.md D1) is a floor. The reviewer judges purity by reading the code.
  - **Acceptance, per module PR.** All four checks are scripted (FR-038) and run on **every commit** of the PR.
    - **(a)** No mention of the concrete impl class anywhere in the module's files, after commit 2: any occurrence of
      `SNAPSyncControllerImpl` or `CoordinatorImpl` fails the check, whether in a self-type, a comment or across
      lines. The same applies to coordinator modules.
    - **(b)** The PR adds **at least one** Tier 1 unit test (not tagged `SyncTest`, `IntegrationTest`, `SlowTest` or
      `DisabledTest`). The test mixes the module trait into a stub implementing its state interface, capabilities and
      Api traits, and exercises a module body that both **reads and writes** `<Module>State`. A `BehaviorTestKit` or
      `ActorTestKit` is used only where a capability needs one. No existing assertion changes.
    - **(c)** Coupling is measured and ratcheted. The PR body and the routing doc record three numbers:
      - the `<Module>State` member count;
      - the Api members the module requires, summed over its `<Callee>Api` traits;
      - the member count of each shared capability trait.

      None may grow in a later PR without a justification line in that PR. The baselines are research.md R14
      (state) and R14a (Api, upper bound).
    - **(d)** No trait-initialization hazard, checked at **member level**, outside def bodies:
      - A module trait has **no top-level statements**. Its member-level lines are only `def`, `lazy val`,
        `private var`/`var` with an allowed initializer, `type`, `given`, `import`, `end` or comments.
      - It has **no concrete `val`** at member level.
      - Every member-level `var` initializer is a literal, an empty collection or a companion constant.
      - Vals and vars local to a def body are ignored.

      The check is the FR-038 script, which works on member indentation under scalafmt (two-space members in a
      top-level trait), not a raw grep.- **FR-017 Each slice ships on its own.** After any slice, staging compiles, every regression suite is green, and the
  node can be released. No slice depends on a later slice to be correct.

### Pin tests (the acceptance criteria; they land first, S0a/S0b)

- **FR-020 #1367 TD-PROXY-GAP guard.** The guard is `&& ourBest.header.number.value > 0` in
  `NetworkPeerManagerActor` (the handshake TD-ratio check, `:798` at baseline). Test: a peer whose TD ratio is above
  10,000 is **not** disconnected while our best block is 0, and **is** disconnected once our best block is above 0.
  Suite: `NetworkPeerManagerSpec`. Reviewer: `herald`.
- **FR-021 #1378 `apply()` arms the poll.** `SNAPSyncController.apply` calls `.start()` (`:6316`). Test: with
  `ManualTime`, a freshly spawned controller sends `GetHandshakedPeersCmd` immediately, and again after each 5 s
  advance, with no hand-sent `PollHandshakedPeers`.
- **FR-022 `frontierPersistenceEnabled` wiring, both sites.** The flag is passed at the `TrieNodeHealingCoordinator`
  spawn in `startStateHealing` (`:4003`) and in `startStateHealingWithInterleave` (`:4083`). Test: through a
  coordinator-factory seam (FR-026), spawning healing by each route with `healing-frontier-persistence = true` passes
  `frontierPersistenceEnabled = true`, and passes `false` when the key is false. The test also records the **full
  spawn-argument tuple** on both routes and asserts it field by field. Every argument is covered, including
  `visitedCap`, the parallelism and water-mark settings, `scopedHealVerification`, the `decoupledHeal*` settings,
  `movingRootDeltaHeal` and `intakeBudget`. The two spawn blocks are about 85% identical, and the M6a dedupe must
  keep both tuples exactly (T014).
- **FR-023 `prunedHealVerification`.** Tests:
  - the default is `true`, both in the case class (`:6346`) and when the key is absent from config (`:6503–6505`);
  - `pruned-heal-verification = false` parses to `false`;
  - the frontier store is created when either `healingFrontierPersistence` or `prunedHealVerification` is set
    (`:3964`).

  The test also **documents #1502**: the flag is not forwarded to the coordinator. It asserts today's forwarded value
  and carries a comment linking #1502, so the #1502 fix PR flips one assertion on purpose.
- **FR-024 #1371 skip-healing predicate.** `shouldSkipHealingAfterDownloads` has no `storagePhaseForceCompleted` term
  (`:6087`). Test, at the call site (the private `syncing` arm, `:2241`): with deferred merkleization on, a fresh
  (non-stale) cursor and storage **force-completed**, the controller skips healing, which is observable as no
  `TrieNodeHealingCoordinator` spawn through the seam. The existing helper-level tests stay.
- **FR-025 Pin tests are anchored by symbol, not line.** Each pin test names its fix (`#1367`, `#1378`, `#1319`,
  `spec-005`, `#1371`) in the test name, and the routing doc lists it.
- **FR-026 Test seams.** S0b may add constructor-level test seams to `src/main`, defaulting to production behaviour:
  a `ChildFactories` parameter for the four coordinators and the ChainDownloader, following the `validatorFactory`
  precedent.
  - Each factory takes today's `apply` argument list as merged with #1501, including `intakeBudget`.
  - It also has a `heapWatchdogStart` seam (default `SnapHeapWatchdog.start`) and an optional injected
    `SnapIntakeBudget` (default: built from config, as today). Without them, the S0d watchdog and budget pins cannot
    be observed.

  The seams add no logic. S0b is "tests + seams", **not** test-only (plan.md Complexity Tracking).

### Characterization tests (S0d, before the moves that need them)

- **FR-027** Behaviour tests at controller level for areas with no coverage today:
  - **stagnation watchdog**: the account-, storage- and bytecode-stagnation outcomes under `ManualTime`;
  - **reset completeness**: what each of `restartSnapSync`, `wakeFromDormant` and `enterDormantMode` leaves visible,
    namely child spawns and stops, persisted `AppStateStorage` flags, `GetStatus`, and the next `Start*` arguments;
  - **pivot refresh**: the messages sent to each child and the ChainDownloader, persisted anchors, and the probe
    commit or abort.

  - **reset vs heap watchdog**: none of `restartSnapSync`, `wakeFromDormant` or `enterDormantMode` stops the heap
    watchdog. Only `onStop` and `stopSnapOnlySchedules` do, observed through the `heapWatchdogStart` seam.
  - **#1501 budget pins**:
    - an `IncrementalContractData` dropped in `idle`, and one dropped in `syncing` with no coordinator, both release
      their reserved credit (observed on the injected budget);
    - `RecoveryReplayPausedRetry` is 1 s, and a closed gate delays the recovery stream by one retry under
      `ManualTime`.
  - **P1 fan-outs**: a peer disconnect sends the peer-unavailable message to all four coordinators that exist, and
    each SNAP response type is routed to exactly its coordinator. This pins what `CoordinatorHandles` replaces.
  - **P4 dispatch table**: a table-driven test over every Command × every `SyncPhase` in `syncing` records which arm
    handles it, or that it falls through to the "Unhandled message in syncing state" catch-all (for example
    `MinPivotBlock`).

  These tests assert **today's** behaviour, including anything that looks wrong. A shared `SnapControllerFixture`
  replaces the six copied stubbed-NPMA fixtures. Existing suites move to the shared fixture only if their assertions
  are left untouched.

### Invariants (every slice)

- **FR-030 On-disk formats are byte-identical.** The frozen formats are:
  - the legacy progress string;
  - `SnapSyncProgressStorage` (namespace `p`);
  - `AccountResumeCheckpoint` v1;
  - storage-task and codeHash task files (`StorageTaskFile`, 64 B / 32 B entries);
  - `SnapStorageDone/` done-markers;
  - the healing frontier (`HealingFrontierStorage` CF `g`, plus subtree-complete records) and `BfsQueueStorage`;
  - `AppStateStorage` phase-flag keys.

  S0c pins them with golden-bytes tests. `snap-good-peers.v1` is not moved, and serves as an inertness oracle.
- **FR-031 Zero behaviour change.** The same messages are sent to the same recipients in the same order, and the same
  timers are armed. Log messages and tags may move between files but do not change text. Metrics names do not change.
  Config keys do not change.
- **FR-032 Regression suites are unchanged, and that is checked by a script and by counts.**
  - **No assertion edits.** The suites are those listed in research.md R13: controller, coordinators,
    heal-verification family, storage, `SyncControllerSpec`; ETC/Hash and ETH/Path. A slice may change only test
    imports and fixture construction. The FR-038 test-hunk check fails if any changed `src/test` hunk in an existing
    suite touches assertion-bearing or scheduling code. Watched tokens include `should`, `must`, `shouldBe`, `===`,
    `==`, `assert`, `assume`, `expect`, `intercept`, `fishForMessage`, `within`, `eventually`, `verify`, `taggedAs`,
    `ignore`, `pending`, `cancel` and `timeout`/`interval`.
  - **Per-suite counts.** Every PR body pastes the per-suite counts (total / passed / ignored) for every R13 suite,
    from the Tier 1 job and from the S0e `SyncTest` job. They must equal the T002 baseline, apart from tests the PR
    adds on purpose, which are listed by name.
- **FR-033 Baseline failures are recorded, not hidden.**
  - The one known pre-existing failure (task #68) is `SyncControllerSpec` "leave SNAP's resume alone once SNAP has
    taken over the best block of an upgraded node" (`SyncControllerSpec.scala:762`). It is `SyncTest`-tagged, so it
    runs only in the S0e job (research.md R13a).
  - In the S0e job, a slice is green only if its failures are exactly that test. The job parses the test reports
    against a one-entry allow-list, and it also fails if #68 unexpectedly passes or is missing (T007).
  - In the Tier 1 job, a slice is green with zero failures, subject to the flake policy (FR-039).

- **FR-034 Moves and renames are separate.** A slice PR has:
  1. a move commit, or commits;
  2. the narrowing commit (required, FR-016);
  3. **no** renames, and no other cleanup.

  A cleanup such as removing the dead `isStarting` parameter is its own small PR after the move (M8b).

  DFS → BFS wording is fixed afterwards in slice R1:
  - comments, scaladoc and test descriptions only;
  - `StateValidator`'s `walkAccountTrieDFS`/`walkStorageTrieDFS` are a real DFS and are **not** renamed;
  - the config key `healing-visited-cap` is **not** renamed. If the user later wants it renamed, the old key stays as
    a read alias.
- **FR-035 No local builds; the real CI gates.** No sbt or JVM runs on the soak host. CI is the compiler. On PRs to
  `staging` the gates are:
  - **"Test and Build (JDK 25, Scala 3.3.8)"**: `compile-all`, scalafmt and `testEssential` (Tier 1). Tier 1
    excludes `SlowTest`, `IntegrationTest`, `SyncTest` and `DisabledTest`.
  - **the S0e SNAP `SyncTest` job.**
  - **the FR-038 `snap-split-verify` job.**

  Tier 2 (`testStandard`) is **skipped** for PRs to `staging` (`ci.yml` ~L110), and it also excludes `SyncTest`. No
  slice may claim Tier 2 evidence. Each slice PR is opened only after all three jobs are green on the branch.
- **FR-036 Second-specialist review.** Every PR is reviewed by a specialist other than its author:
  - `prism` for structure;
  - `vault` for persistence and resume slices (S0c, M5, C-A4, C-S2, C-S3, C-H1);
  - `herald` for S0a;
  - `forge` signs off that nothing changed for any slice that touches ETC-reachable behaviour (every controller
    slice, since SNAP runs on Mordor);
  - `beacon` signs off on slices that move CL-pivot or Path-scheme code (M8, M10, M4 PathPublish).

  `loom` is not used: the actors are already Pekko Typed.
- **FR-038 Verification tooling is a required CI job (S0f, before P1).**
  - `scripts/snap-split/` ships with its own fixture-based self-tests. It runs as the CI job `snap-split-verify`,
    which exits non-zero on failure, on **every commit** of a PR (`git rev-list base..HEAD`). A reviewer can re-run
    it locally with no JVM.
  - It implements:
    - the moved-block and identical-body check for move commits (plan.md §Move verification);
    - FR-016 (a) and (d);
    - the FR-032 test-hunk check;
    - the "no change under `src/main/resources`" and "goldens and pins untouched" checks;
    - a print of the FR-016 (c) counts for the PR body.
  - It must exist and be green before the first non-test slice (P1). **It is not required on S0a–S0e**, which merge
    before it exists. Those PRs are checked against the same rules by hand, and the reviewer says so.
  - The test-hunk token check applies to `refactor/snap-016-*` branches only. `test/snap-016-*` branches (S0b, S0c,
    S0d, S0g, C-X0) edit existing suites on purpose. They are checked instead against a per-commit `# test-files:`
    trailer that lists the permitted files (plan.md §Move verification, step 3).
- **FR-039 Flake policy (Tier 1 and `snap-synctest`).**
  - A failure in a test outside the slice's scope may be re-run **once**, in either job. This matters most for
    `snap-synctest`, whose suites are excluded from Tier 1 precisely because they time out under CI load.
  - In `snap-synctest`, the #68 allow-list entry is not a flake and is never re-run for. Only a non-allow-listed
    failure qualifies for the one re-run.
  - The slice is green only if:
    - the re-run passes;
    - the failing test's name is recorded with both run links;
    - the per-suite counts of the passing run equal the baseline.
  - The same test failing twice blocks the slice.
  - `StaleVerificationWalkSpec` (4 wall-clock `eventually(5 s)`; project task #85) is the known case. S0g stabilises
    it, test-only, before C-H0.
- **FR-037 Sepolia hotfix line.**
  - The tag `snap-split-base` is cut on staging immediately before P1 merges.
  - While the Sepolia node is on fix-only builds, its hotfixes branch from that tag (or from the latest fix-only tag
    after it) and ship from there.
  - Each hotfix is then forward-ported to staging. The pins and the routing doc are anchored by symbol, so the
    forward-port finds the moved code there.
  - A move PR that conflicts with a forward-ported fix is redone from the new base, not hand-merged.
  - A forward-port lands before the next slice that touches the same module.

### Non-goals

- **NG-1** No redesign. No new phases, no new actors, no change to supervision, no new Commands. Test seams are
  constructor parameters with production defaults.
- **NG-2** No Pekko API change. The controller and coordinators are already Typed.
- **NG-3** No config-key renames. If one is ever made, the old key stays as an alias.
- **NG-4** No bug fixes inside move PRs. The open items in R10 (#1434, #1435, #1502, #1503, #1350, #1349, #1431, and
  the project tasks) are **placed**, not fixed. Each fix is its own PR against whichever layout is current, and it
  updates its pin or characterization test on purpose.
- **NG-5** No unification of per-coordinator cooldown policies, and no `SnapPeerHealth` roll-out. ByteCode does not
  adopt the shared response-bytes window (R12).
- **NG-6** No change to the #1501 gate or watchdog behaviour.

### Key Entities

- **Module (controller)**: a trait in `snap/controller/` whose self-type is `<Module>State & <capabilities>`, never
  the impl class. It owns a named set of private vars and handles a named set of Commands. It also exposes a small API
  trait for any other module that calls it.
- **`SnapSharedState`**: the one explicit shared-state interface for the hubs: `pivotBlock`, `stateRoot`,
  `currentPhase`, `progressMonitor` and `requestTracker`. The child refs sit behind `CoordinatorHandles`, and the phase
  flags behind `PhaseFlags`. The controller core implements all three.
- **`<Module>State`**: the per-module abstract interface of shared fields (FR-016). Its member count is the module's
  coupling measure.
- **Slice**: one PR. It is independently shippable and has one reviewer set.
- **Pin test**: a test that fails if a named past fix is reverted.
- **Golden vector**: a hex-encoded byte string committed in a test. The encoder must reproduce it, and the decoder
  must accept it.

## Success Criteria *(mandatory)*

- **SC-001** After the last controller slice (M11), `SNAPSyncController.scala` holds no more than about 1,000 lines:
  shell, constructor, companion `apply` and config. No `snap/controller/` module exceeds about 900 lines, and `syncing`
  contains no `currentPhase ==` test.
- **SC-002** After the last coordinator slice, no coordinator file exceeds about 1,000 lines.
- **SC-003** The five pin tests and the S0c golden vectors exist and pass from S0 onward, and are never edited by a
  move PR.
- **SC-004** Zero assertion changes across the regression suites over the whole series, checked per PR.
- **SC-005** A node mid-SNAP restarts across every slice without losing progress. This is checked on Mordor or
  Platåberget at least at M5, M9, M10, C-A4, C-S3 and C-H1, the slices that move persistence or resets.
- **SC-006** Every phase row in the routing doc is marked "target (post-split)" with files that exist and that pass the
  grep check.
- **SC-007** After each module PR, criteria (a)–(d) of FR-016 hold for every module so far. After M11 no file under
  `snap/controller/` names `SNAPSyncControllerImpl` in a self-type, and every module has at least one stub-based unit
  test.

## Assumptions

- PR #1501 has merged (`dfa4a8836`). The module map is re-checked against the merged code (T001, research.md R11).
- The staging PR gate is Tier 1 only (research.md R13a). The S0e `SyncTest` job and the S0f `snap-split-verify` job
  are added to cover what Tier 1 does not run, which is enough to act as the compiler and regression gate.
- Mordor (Hash scheme, PoW) and Platåberget (Path scheme, PoS with a CL pivot) between them run every code path the
  split moves. Sepolia adds scale, not paths.
- Traits with **required** narrowing are the module form (ADR CON-013). Turning a narrowed trait into a class is a
  later, optional step and is not required by this spec.

## Decisions (user, 2026-10-08)

1. **Module form.** Traits for the moves, then **required** narrowing to per-module state interfaces with criteria
   (a)–(d) (FR-016; ADR `docs/adr/consensus/CON-013-snap-controller-module-form.md`).
2. **Start time.** P1 starts as soon as #1501 merges (#1501 merged 2026-10-08). S0a–S0g come first. `snap-split-base` is cut just before P1
   and governs the Sepolia hotfix line (FR-037).
3. **Task #68.** It is `SyncControllerSpec` "leave SNAP's resume alone once SNAP has taken over the best block of an
   upgraded node" (`:762`), the baseline exception (FR-033). It is not the old fast-sync timing test.
4. **P4** is five PRs. The routing doc is listed in CLAUDE.md's "Shared agent protocols" table.
5. The stale `healing-frontier-persistence` comment and the reset-path asymmetries are logged in CHASE-QUEUE
   (CQ-SNAP-016-1…4).

## Open Questions

None blocking. T001 (re-check against #1501 as merged) is the first implementation task after S0.
