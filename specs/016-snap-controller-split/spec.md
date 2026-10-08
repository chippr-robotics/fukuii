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
expression makes it fail; this is checked once with a local edit that is never committed (or in a throwaway CI run),
and the result is recorded in the S0a/S0b PR.

**Acceptance Scenarios**: one per pin, FR-020 to FR-024.

### User Story 4 — The live Sepolia sync is never the test bed (Priority: P2)

The Sepolia node is mid-SNAP, about two weeks from head, and stays on fix-only builds until it reaches head. Each
extraction slice is validated on Mordor (ETC, Hash scheme) and on the Platåberget devnet (ETH, PoS, Path scheme).

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
  remaining slices, and a move PR that conflicts with it is redone, not hand-merged (FR-033).

## Requirements *(mandatory)*

### Scope

- **FR-001** The scope is `SNAPSyncController.scala` and the four coordinators: `AccountRangeCoordinator`,
  `StorageRangeCoordinator`, `TrieNodeHealingCoordinator` and `ByteCodeCoordinator`. Workers, `ChainDownloader`,
  `StateValidator`, the storage classes and the three #1501 files (`SnapIntakeBudget`, `SnapHeapWatchdog`,
  `GatedTaskFileReplay`) are placed in the routing doc but not split.
- **FR-002** Every SNAP phase is a separately targetable module, meaning:
  - **Controller**: one file per module in the map (plan.md §Module map).
  - **Coordinators**: one file per concern inside each coordinator (plan.md §Coordinator modules).
- **FR-003** **Sequencing gate**: no slice except S0a–S0d and the routing doc may merge before PR #1501 (spec 014)
  merges. The module map already places #1501's new types (research.md R11). T001 re-checks that placement against
  #1501 as it merged.

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
  `ctx.watch` and no change to supervision.
- **FR-011 `PhaseFlags` + `reset(kind)` (P2).** One holder for the phase-complete and force-complete flags:
  `accountsComplete`, `bytecodePhaseComplete`, `storagePhaseComplete`, `storagePhaseForceCompleted`,
  `bytecodeForceCompleted`, `forceCompleteStorageSent`, `awaitingHealedCode`, `healedCodeWaitExhausted` and
  `resumedStaleCursors`. A single `reset(kind: Start | Restart | Wake | Dormant)` replaces the reset writes in
  `startSnapSync`, `restartSnapSync`, `wakeFromDormant` and `enterDormantMode`. A single `cancelSyncTimers(keys)`
  replaces the three hand-copied cancel lists, and each call site passes its own list, unchanged.
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
  phase into its own Pekko `Behavior` value is **not** part of P4; it is a later option (plan.md D3).

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
- **FR-016 Moves keep bodies identical.** A module is moved into a self-typed trait in its own file (plan.md D1).
  Method bodies and the declarations of vars the module owns exclusively are moved **unchanged**. The only allowed
  edits in a move commit are: the enclosing `trait`/file header, imports, and visibility widening (`private` →
  `private[snap]`) where another module calls the member. Each move commit carries a grep/diff recipe that shows the
  moved bodies are byte-identical (plan.md §Move verification).
- **FR-017 Each slice ships on its own.** After any slice, staging compiles, every regression suite is green, and the
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
  `frontierPersistenceEnabled = true`, and passes `false` when the key is false.
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
  precedent. It adds no logic. S0b is "tests + seams", **not** test-only (plan.md Complexity Tracking).

### Characterization tests (S0d, before the moves that need them)

- **FR-027** Behaviour tests at controller level for areas with no coverage today:
  - **stagnation watchdog**: the account-, storage- and bytecode-stagnation outcomes under `ManualTime`;
  - **reset completeness**: what each of `restartSnapSync`, `wakeFromDormant` and `enterDormantMode` leaves visible,
    namely child spawns and stops, persisted `AppStateStorage` flags, `GetStatus`, and the next `Start*` arguments;
  - **pivot refresh**: the messages sent to each child and the ChainDownloader, persisted anchors, and the probe
    commit or abort.

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
- **FR-032 Regression suites are unchanged.** The suites are listed in research.md R13: controller, coordinators,
  heal-verification family, storage, `SyncControllerSpec`; ETC/Hash and ETH/Path. Every one is green after each slice
  with **no assertion changes**. A slice may change only test imports, fixture construction and visibility-driven
  access. The diff must show this (T-review checklist).
- **FR-033 Baseline failures are recorded, not hidden.** The pre-existing `SyncControllerSpec` failure tracked as task
  #68 (research.md R13) is recorded by test name from the S0a CI run. A slice is green if its failures equal that
  recorded baseline exactly.
- **FR-034 Moves and renames are separate.** A slice PR has:
  1. a move commit, or commits;
  2. optionally, a narrowing commit that reduces the self-type to declared capabilities;
  3. **no** renames.

  DFS → BFS wording is fixed afterwards in slice R1:
  - comments, scaladoc and test descriptions only;
  - `StateValidator`'s `walkAccountTrieDFS`/`walkStorageTrieDFS` are a real DFS and are **not** renamed;
  - the config key `healing-visited-cap` is **not** renamed. If the user later wants it renamed, the old key stays as
    a read alias.
- **FR-035 No local builds.** No sbt or JVM runs on the soak host. CI is the compiler. Each slice PR is opened only
  after CI is green on the branch.
- **FR-036 Second-specialist review.** Every PR is reviewed by a specialist other than its author:
  - `prism` for structure;
  - `vault` for persistence and resume slices (S0c, M5, C-A4, C-S2, C-S3, C-H1);
  - `herald` for S0a;
  - `forge` signs off that nothing changed for any slice that touches ETC-reachable behaviour (every controller
    slice, since SNAP runs on Mordor);
  - `beacon` signs off on slices that move CL-pivot or Path-scheme code (M8, M10, M4 PathPublish).

  `loom` is not used: the actors are already Pekko Typed.

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

- **Module (controller)**: a self-typed trait in `snap/controller/`. It owns a named set of vars, handles a named set
  of Commands, and reads the shared hubs.
- **Shared hubs**: `pivotBlock`, `stateRoot`, `currentPhase`, `PhaseFlags`, `CoordinatorHandles`, `progressMonitor`
  and `requestTracker`. They stay in the controller core.
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

## Assumptions

- PR #1501 merges in substantially its current form. T001 re-checks R11 if it changes.
- CI runs `compile-all`, scalafmt and Tier 1/2 on each PR, which is enough to act as the compiler and regression gate
  (the same assumption spec 014 makes).
- Mordor (Hash scheme, PoW) and Platåberget (Path scheme, PoS with a CL pivot) between them run every code path the
  split moves. Sepolia adds scale, not paths.
- Self-typed traits are an acceptable intermediate module form (plan.md D1). Turning them into classes with explicit
  interfaces is a possible later step and is not required by this spec.

## Open Questions (for the user)

1. **Which test is task #68?** The only trace found is a flaky *fast-sync* `SyncControllerSpec` timing test, and fast
   sync was removed in #1436. This spec uses the S0a CI result as the baseline.
2. **Hotfix line for Sepolia.** While Sepolia stays on fix-only builds, should fixes come from a branch cut at the
   last pre-split staging commit, and be forward-ported through the slices (plan.md D6)? Or should Sepolia take staging
   once a slice has soaked on Platåberget?
3. **ADR.** Should the module layout (D1 traits, D3 per-phase arms) be recorded as an ADR under `docs/adr/`
   (Principle VII)?
4. **CLAUDE.md.** Should the routing doc get a row in CLAUDE.md's shared-protocols table? This spec does not edit
   CLAUDE.md.
5. **P4 granularity.** Should P4 be one PR, or five (one phase's arms per PR)? The plan proposes five; see plan.md.
