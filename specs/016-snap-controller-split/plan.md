# Implementation Plan: Split SNAPSyncController and the SNAP Coordinators into Phase Modules

**Branch**: `docs/spec-016-snap-split` (this plan). Each slice has its own branch, listed in `tasks.md`.

**Date**: 2026-10-08. **Amended**: 2026-10-11 (state capability boundary; see the amendment section at the end)

**Spec**: [spec.md](./spec.md) · **Phase 0**: [research.md](./research.md) · **Routing doc (draft)**:
[`.claude/agent-protocols/snap-sync.md`](../../.claude/agent-protocols/snap-sync.md)

## Summary

Five Pekko Typed actor classes (14,854 lines on `staging@9a6681cc0`) are split into phase modules, with no change in
behaviour. The work runs in this order:

1. **Pins and characterization (S0).** Pin tests for the five #1385 fixes, golden-bytes tests for every on-disk
   format, and characterization tests for the stagnation watchdog, the resets and pivot refresh. These land before
   any code moves.
   S0 also adds the missing CI coverage: a SNAP `SyncTest` job (S0e), the scripted verification job (S0f), and a fix
   for the `StaleVerificationWalkSpec` flake (S0g).
2. **Prerequisites (P1–P4)** remove the coupling the Phase 0 matrix found:
   - P1 `CoordinatorHandles`;
   - P2 `PhaseFlags.reset(kind)`;
   - P3 a single peer-event handler;
   - P4 per-phase arms in `syncing`.
3. **Controller modules (M1–M11)**, one per PR, lowest coupling first. Each PR has two commits: a byte-identical
   move into a trait, then a **required** narrowing of the self-type to a per-module state interface plus capability
   traits (D1, ADR CON-013).
4. **Amendment 2026-10-11 slices**: the env freeze ratchet (T054) and the `StateIngest` capability (T055), after
   the controller modules and before the coordinators.
5. **Coordinators**: the same method, after a short per-coordinator characterization. Each narrowing takes
   `StateIngest`, never `StateAccess`.
6. **R1**: DFS → BFS wording, after the moves.

PR #1501 (spec 014) **merged on 2026-10-08** (`dfa4a8836`), so the gate is met. P1 starts as soon as S0a–S0g have
merged, without waiting for Sepolia to reach head (D6). The live Sepolia node is not used to validate any slice.

## Technical Context

**Language/Version**: Scala 3.x LTS, JDK 25

**Primary Dependencies**: Apache Pekko Typed (actors, `TimerScheduler`, `ManualTime` in tests), RocksDB via `DataSource`

**Storage**: unchanged. `AppStateStorage`, `SnapSyncProgressStorage`, `SnapStorageDoneStorage`,
`HealingFrontierStorage` (CF `g`), `BfsQueueStorage`, and the task files in the datadir. All of them are frozen
(FR-030).

**Testing**: ScalaTest + Pekko `ActorTestKit`/`ManualTime`, run by CI only (FR-035). The PR gate on staging is Tier 1
(`testEssential`, which excludes `SyncTest`), plus the S0e `SyncTest` job and the S0f `snap-split-verify` job. Tier 2
does not run on staging PRs (research.md R13a).

**Target Platform**: the fukuii node on Linux, both chain families (ETC/Mordor: Hash scheme, PoW; ETH: Path scheme,
PoS + CL pivot)

**Project Type**: a single sbt project. Only the root `main` module is touched.

**Performance Goals**: no measurable change. Mixing in traits adds no dispatch cost beyond the existing virtual calls.

**Constraints**:
- no local sbt or JVM;
- no on-disk format change;
- no assertion change in existing suites;
- one module per PR.

**Scale/Scope**: about 47 PRs; about 11,500 lines moved (see Effort)

## Constitution Check

*Gate: checked before Phase 0 and again after the design below.*

| Principle | Status | Notes |
|---|---|---|
| I. Consensus determinism | **Pass** | SNAP sync is not consensus code: no EVM, gas, state-root computation, RLP or fork config is moved. `MerkleProofVerifier`, `StateValidator` and the trie classes are out of scope. As a policy, `forge` signs off that every controller slice leaves ETC unchanged, and `beacon` signs off on slices that move CL-pivot or Path-scheme code (FR-036). ETC and ETH code paths are not mixed: the split is by phase, not by chain. |
| II. Spec-driven | **Pass** | This spec. Each slice PR links it and names its task ID. |
| III. Test discipline | **Pass, with a note** | Pin and characterization tests come first (S0). They are deterministic (`ManualTime`, probes, no `Thread.sleep`). Extraction PRs change no behaviour, so they add no new assertions, and coverage does not fall because the tested code is only moved. Constitution III says "behavioural changes ship with tests"; there are no behavioural changes, and the pins carry the burden instead. |
| IV. Scala 3 style | **Pass** | Self-types and `export` clauses are idiomatic Scala 3. scalafmt and scalafix run in CI. Widened members get explicit result types where scalafix asks for them. |
| V. Quality gates | **Deviation** | `sbt pp` cannot run locally (the soak host is shared with the live node). See Complexity Tracking. |
| VI. Security | **Pass** | No network-exposed surface or key handling changes. |
| VII. Versioning / ADR | **Pass** | Each PR uses a conventional prefix (`refactor(snap):`, `test(snap):`) and references #1401. The module form is recorded in ADR `docs/adr/consensus/CON-013-snap-controller-module-form.md`. |

Re-check after design: no new violations. The two-commit trait form with required narrowing (D1) is logged
below.

## Design decisions

### D1 — Module form: move into a trait, then narrow (required). ADR CON-013

Every module PR has two commits.

**Commit 1, the move.** The module becomes a trait, mixed into `SNAPSyncControllerImpl`. Its self-type temporarily
names the impl class, so bodies move byte-identical:

```scala
// snap/controller/HealingOrchestrator.scala — commit 1 (move)
package com.chipprbots.ethereum.blockchain.sync.snap.controller
private[snap] trait HealingOrchestrator:
  self: SNAPSyncControllerImpl =>
  // exclusive vars and defs, bodies byte-identical to the baseline
```

**Commit 2, the narrowing (required, never skipped).** The self-type is replaced by the module's own state interface
plus capability traits:

```scala
// snap/controller/HealingOrchestrator.scala — commit 2 (narrowing)
private[snap] trait HealingState:                       // exactly the shared fields this module reads/writes
  def healRepegNoRootAttempts: Int
  def healRepegNoRootAttempts_=(v: Int): Unit
  def pendingPivotRefresh: Option[PendingPivotRefresh]
  // … one getter (+ setter if written) per field in research.md R14, confirmed by the compiler

private[snap] trait HealingOrchestrator extends HealingApi:
  self: HealingState & SnapSharedState & CoordinatorHandles & PhaseFlags & SnapControllerEnv & PivotRefreshApi =>
  private var trieWalkInProgress = false                // exclusive state is private to the trait
  private var healingRoundCount = 0
  …
```

The pieces of the narrowed form:

- **`SnapSharedState`** is the one explicit interface for the hubs: `pivotBlock`, `stateRoot`, `currentPhase`,
  `progressMonitor`, `requestTracker` (getters, plus setters where written). The controller core implements it.
- **`CoordinatorHandles`** (P1) holds the child refs, and **`PhaseFlags`** (P2) the phase flags.
- **`SnapControllerEnv`** carries `ctx`, `timers`, `snapSyncConfig`, `log`/`asyncLog`, the storages and the metrics.
  It is a capability trait, and it is counted separately from state. **Amended 2026-10-11:** the storages leave it.
  T055 moves the 8 storage handles into **`StateIngest`**, and `SnapControllerEnv` drops from 18 members to about 11
  (18 − 8 + 1 `stateIngest`). The count is a ratchet that only goes down (T054).
- **`StateIngest`** (T055, amendment 2026-10-11) is the SNAP data-plane capability: bulk trie-range and trie-node
  writes with no execution, plus the verification reads SNAP needs (proof checks, healing presence checks, state
  validation, publish verification). It carries a per-capability durability policy, and its commit to the access
  store (`PathToHashExporter` on Path scheme, a durable barrier on Hash scheme) is always fully durable (spec
  FR-044). Its execution counterpart, `StateAccess`, belongs to spec 017, and SNAP code never holds it.
- **`<Module>State`** declares only the shared fields this module touches that are not hubs. Abstract `var`
  declarations, or getter/setter pairs, keep each body unchanged: `x = y` still compiles against a setter.
- **`<Other>Api`**: when one module calls another, it names the callee's small **API trait** (for example
  `PivotRefreshApi`, `StagnationResetApi`), never the callee's implementation trait.

The core class implements every `<Module>State` by keeping the shared fields as plain vars. Scala 3 lets a concrete
`var` implement an abstract getter/setter pair.

**Why traits first**: FR-016 requires the move commit to be mechanically verifiable (identical bodies). A
`SNAPSyncControllerImpl` self-type lets every body compile unchanged.

**Why narrowing is required**: a trait that keeps the impl-class self-type is a file split, not a module. It can
still touch all 83 items, so coupling is neither visible nor bounded. Narrowing makes the compiler enumerate each
module's real dependencies (`<Module>State` + capabilities). It makes each module unit-testable against a stub (b),
and it gives a number that can be ratcheted (c).

**Rejected alternative**: separate classes with a context object from the start. Every body would change in the
same commit as the move, so the move could not be verified mechanically. The ADR records this choice. A narrowed trait
can later become a class without touching its bodies again.

**Acceptance per module PR** (spec FR-016 a–d). All four are scripted in `scripts/snap-split/` (S0f, FR-038) and run
by the required CI job `snap-split-verify` on **every commit** of the PR:

- **(a) No impl-class mention.** Any occurrence of `SNAPSyncControllerImpl` or `CoordinatorImpl` in a module file fails
  after commit 2, whether in a self-type, a comment or across lines. The check is a plain substring search over the
  module directories:

  ```sh
  ! grep -rn -e SNAPSyncControllerImpl -e CoordinatorImpl \
      src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/controller \
      src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/actors/{account,storage,healing,bytecode}
  ```

  In commit 1 the script allows exactly one occurrence per file: the temporary self-type line.
- **(b) Stub test.** At least one new Tier 1 test (no `SyncTest`, `IntegrationTest`, `SlowTest` or `DisabledTest`
  tag) of the form `new Stub<Module>State with <capability/Api stubs> with <Module>`. It calls a module body that
  **reads and writes** `<Module>State`, and asserts on the stub's fields afterwards.
- **(c) Coupling counts.** The script prints three numbers:
  - the `<Module>State` member count;
  - the Api members required (the sum over `<Callee>Api` traits in the self-type);
  - the member count of each shared capability trait (`SnapSharedState`, `SnapControllerEnv`, `CoordinatorHandles`,
    `PhaseFlags`, and `StateIngest` from T055).

  The PR pastes them, and the routing doc records them. Growth over the previously recorded value, or over research.md
  R14/R14a for a first recording, needs a justification line. The capability-trait caps live in the routing doc.
- **(d) No init hazard, checked at member level.** The script reads each module file and checks only **member-level
  lines**: under scalafmt, a top-level trait's members sit at exactly two spaces of indent. Lines inside def bodies
  (deeper indent) are skipped, so local `val`/`var` never trip it. It fails on:
  - any member-level line that is not one of `def`, `lazy val`, `var`/`private var`, `type`, `given`, `import`,
    `end`, a modifier-prefixed form of those, or a comment, which means **no top-level statements**;
  - any member-level `val` that is not `lazy` (an abstract `val` declaration, with no initializer, is allowed: it runs
    nothing at construction, and it gives a capability a stable path, e.g. for `import ctx.executionContext`);
  - any member-level `var` whose initializer is not a literal (number, boolean, string), `None`, `Nil`, an empty
    collection (`…empty…`), or a companion constant (`[A-Z]\w*(\.\w+)*`).

  Its self-tests include a local `val` inside a def (must pass), a member `val` (must fail), a top-level `println`
  (must fail) and an impure `var` initializer (must fail).

**Rule (d) also applies to commit 1.** A trait initializer runs before the impl class body, so even a byte-identical
move can reorder initialization. Any declaration whose initializer fails (d) therefore stays in the core in commit 1,
and it is listed in the PR.

**`val` → `lazy val`/`def` in commit 2 only for pure initializers.** A `lazy val` runs its initializer at first use,
not at construction. For an impure initializer that is a behaviour change: spawning, timers, metric registration,
subscriptions, `intakeBudget` reservation and logs would all move in time. So:
- The PR lists each converted val with "pure: yes" and the grep of its initializer for
  `ctx\.|timers\.|spawn|actorOf|ActorRef|messageAdapter|scheduleOnce|Metrics|metrics|register|subscribe|intakeBudget|log\.|asyncLog|Future|IO|new |System\.|Random|Instant\.now|currentTimeMillis|nanoTime`.
- The reviewer re-runs that grep. **The grep is a floor, not the test.** The reviewer judges purity by reading the
  initializer and anything it calls. A clean grep does not make an initializer pure, and when in doubt the val stays
  in the core.
- An impure initializer stays a `val` in the core, and the module reaches it through an abstract `def` in
  `<Module>State` or `SnapControllerEnv`.

**Why (d) matters.** Trait bodies run **before** the class body. A `val` or `var` initializer in a trait that reads a
self-type member would see `null` or `0`. A `lazy val` or `def` is evaluated on first use, after construction. T020
decides whether `-Wsafe-init` is turned on for the snap package as a second guard.

**Rules for commit 1 (the move)**:

- **What may change:**
  - the `trait` header and file;
  - imports;
  - `private` → `private[snap]` on members another module or the core calls;
  - explicit result types demanded by scalafix on widened members;
  - one-line `export` clauses, in the old object, that re-export `# moved:` symbols by name (D2; no wildcard, no
    rename). The clause's object path must also resolve to the object that now holds each symbol (`Obj`, a package
    suffix such as `controller.Obj`, or the full path), so an export cannot forward to a different member.
- **What must not change:** any body.
- **What moves with the module:**
  - vars it owns exclusively (research.md R3; "one module + syncing arms" vars once P4 has moved those arms);
  - the module's `syncing` arms;
  - its timer keys.
- **What stays in the core:** the hubs (`SnapSharedState`), `CoordinatorHandles`, `PhaseFlags` and the shared
  non-hub fields, which the core implements for each `<Module>State`.

The Tier 1 job checks only the PR head (commit 2). `snap-split-verify` runs its checks on every commit. Commit 1 is
also compiled on its own: the job runs `sbt compile` on the move commit, the parent of the narrowing commit. The move
commit is the one that carries the `# moved:` trailer. This is the only extra compile. So both
commits are known to build, and bisects work.

**Coordinators** follow the same two-commit pattern, with state interfaces derived in T060. The traits live in
sub-packages:
- `snap/actors/account/`
- `snap/actors/storage/`
- `snap/actors/healing/`
- `snap/actors/bytecode/`

Each is mixed into its `*CoordinatorImpl`, and after narrowing no self-type names the `*CoordinatorImpl`. The
coordinator files and their public Command ADTs stay where they are.

### D2 — Companion helpers move behind `export`

Pure helpers in `object SNAPSyncController` move to per-module objects, for example `ResumePolicy` and
`StagnationPolicy`. `object SNAPSyncController` re-exports them with `export`, so every existing test call such as
`SNAPSyncController.shouldSkipHealingAfterDownloads(...)` compiles unchanged. This keeps FR-032 (no assertion or
call-site edits).

### D3 — `syncing` per-phase arms are partial functions, not separate Behaviors

P4 turns `syncing` into:

```scala
Behaviors.receiveMessage(msg => (phaseArms(currentPhase) orElse commonSyncingArms orElse catchAll)(msg))
```

Here `phaseArms` returns `accountRangeArms`, `byteCodeAndStorageArms`, `stateHealingArms`, `stateValidationArms` or
`chainDownloadCompletionArms`. Each arm drops its `if currentPhase == X` guard and moves into its phase's function.

**Arm-order invariant.** For every message type, list today's arms in source order with their guards. After P4, for
every value of `currentPhase`, the first arm that matches must be the same arm as before. The PR includes this table,
and the characterization tests from S0d exercise it.

**Why not separate `Behavior`s now**: `currentPhase` is written by nine methods outside `syncing`
(`bootstrapping`, `checkAllDownloadsComplete`, `completeHealingWalkClean`, `enterDormantMode`, `finalizeSnapSync`,
`restartSnapSync`, `startSnapSync`, `triggerHealingForMissingNodes`, `wakeFromDormant`). Each writer would also have to
return the next behaviour, which makes every phase transition a control-flow change. The partial-function form makes
no control-flow change, and it still gives the M-slices one function per phase to move.

### D4 — Seams are constructor parameters with production defaults

S0b adds `childFactories: ChildFactories = ChildFactories.production` to the private impl and to `apply`, following
the `validatorFactory` precedent. `ChildFactories` holds one function per child (the four coordinators and the
ChainDownloader), and each function takes the same arguments as today's `apply`. Tests pass recording factories. This
is the only change to `src/main` before P1.

### D5 — Pins before moves; fixes never inside moves

Each open item in research.md R10 is fixed in its own PR, against whichever layout is current. The fix PR updates its
pin or characterization test on purpose. #1502 has a pin assertion written to be flipped (FR-023).

### D6 — Deploy and hotfix line

Extraction PRs merge to `staging`. The Sepolia node stays on fix-only builds until it reaches head.

**Decided (user, 2026-10-08)**: extraction (P1 onward) starts as soon as #1501 merges; it does not wait for Sepolia to
reach head. S0a–S0g still come first.

**The hotfix line (spec FR-037):**
- Tag `snap-split-base` at the staging commit just before P1 merges.
- Sepolia hotfixes branch from that tag (or the latest fix-only tag after it), ship as fix-only builds, and are then
  forward-ported to staging.
- Pin and characterization tests are anchored by symbol, so a forward-port finds the moved code through the routing
  doc.
- A forward-port lands before the next slice that touches the same module. A conflicting move PR is redone from the
  new base.

**Cost**: every Sepolia hotfix is written twice when it touches moved code. The window is about two weeks (Sepolia's
remaining SNAP time), and P1–P4 can mostly land inside it, because they restructure code without moving it into new
files.

**Validation targets**:
- **Mordor** covers ETC, Hash scheme and PoW pivot selection.
- **Platåberget** covers ETH, Path scheme, the CL pivot hint, PathPublish and deferred backfill.

Between them they reach every path the split moves. The Hash/Path split in the test suite (research.md R13) matches
this pairing. Platåberget runs as a soak, so no sbt runs next to it (memory note). The slice jar comes from CI.

### D7 — Routing doc is updated in every PR

Each slice PR flips its rows in `.claude/agent-protocols/snap-sync.md` from "current" to "target (post-split)", using
the real file names. R1 runs the T090 grep check over the whole document.

## Module map — controller

Line counts are estimates **before #1501** (research.md R6), plus the #1501 additions where noted. "Owns" lists vars
that move with the module. Shared hubs stay in the core.
Each module's baseline state-interface size (FR-016 (c)) is in research.md R14 and in the routing doc.

| # | Module (trait) → file | Owns (moves with it) | Reads/writes in core | Commands handled | Config keys (`sync.snap-sync.*`) | Metrics | Log tags | Est. lines |
|---|---|---|---|---|---|---|---|---|
| M1 | `ResumePolicy`, `StagnationPolicy`, `PivotPolicy`, `HealPolicy` objects → `snap/controller/*Policy.scala` (companion pure helpers, `export`ed) | — (pure) | — | — | — | — | — | ~300 moved |
| M2 | `StateValidationModule` → `snap/controller/StateValidationModule.scala` (`validateState`, `spawn{Account,Storage}Validation`, `snapValidationEc` use, validation arms) | `validationRetryCount`, `validationInProgress` | `validationGeneration` (bumped by P2 reset and the refresher), `healingValidatedRoot`, `pivotBlock`, `stateRoot` | `ValidateAccountTrieResult`, `ValidateStorageTriesResult`, `ValidationRetry`, `StateValidationComplete` | `state-validation-enabled`, `max-retries` | `snapsync.validation.failures.total`, `snapsync.validation.missing.nodes.gauge` | (untagged) | ~175 |
| M3 | `SnapPeerPool` → `snap/controller/SnapPeerPool.scala` (`peerListHelper`, `snapServingPeers`, `snapExclusionReason`, `getSnapPeerWithHighestBlock`, reactivity, rate tracking, debounce, `pollHandshakedPeers`, `evictNonSnapPeers`, `startSnapPeerEviction`, `startSnapServerPeersScheduler`, `bestSnapProbeTarget`, `ensureSnapServerPeersConnected`, `flushPeerDisconnects`, `calibratePivotTD`) | `lastSnapExclusions`, `lastSnapExclusionLogMs`, `pendingDisconnectedPeers`, `snapPeerEvictionStarted`, `snapServerPeersSchedulerStarted`, `fruitlessEvictionCycles`, `lastEvictionSnapCount`, `snapServerPeerLastConnectAttemptMs`, `bestEth68PeerForCalibration` | `CoordinatorHandles.broadcastPeerUnavailable`, `requestTracker` | `WrappedHandshakedPeers`, `WrappedPeerDisconnected`, `FlushPeerDisconnects`, `PollHandshakedPeers` (via P3), `EvictNonSnapPeers`, `EnsureSnapServerPeersConnected`, `CheckSnapCapability`, `TuneRateTracker` | `min-snap-peers`, `snap-peer-eviction-interval`, `max-evictions-per-cycle`, `snap-server-peers`, `snap-capability-grace-period`, `max-inflight-per-peer` | `snapsync.peers.*` | `[SNAP-PEERS]`, `[STORAGE-STATE]` (in `flushPeerDisconnects`) | ~480 |
| M4 | `SnapFinalization` → `snap/controller/SnapFinalization.scala` (`completeSnapSync`, `enterHeaderHold`/`leaveHeaderHold`, `finalizeSnapSync`, `startPathPublish`/`onPathPublishDone`, `startChainDownloader`/`launchChainDownloader`/`releaseDeferredBodiesAndReceipts`, behaviours `completed`, `completedWithBackfill`) | `lastHeaderHoldWarnMs`, `holdLastCursor`, `holdLastAdvanceMs`, `chainDownloadComplete`, `headerHold`, `pathPublish` | `ChainDownloaderHandle` (shared with the refresher), `healedCodeHashes`, `bytecodeForceCompleted` (in `PhaseFlags`), `pivotBlock`, `stateRoot` | `HeaderHoldTick`, `PathPublishProgress`, `PathPublishDone`, `PathPublishFailed`, `ChainDownloaderProgress`, `ChainDownloaderDone` | `chain-download-enabled`, `chain-download-max-concurrent-requests`, `chain-download-boosted-concurrent-requests`, `chain-backfill-concurrent-requests`, `defer-chain-backfill-until-state-complete`, `header-hold-stall-timeout`, `chain-download-timeout` | `snapsync.totaltime.minutes.gauge` | `[PATH-PUBLISH]`, `[SNAP-COMPLETE]`, `[HEAL-CODE]` (finalize side) | ~480 |
| M5 | `SnapResumePlanner` → `snap/controller/SnapResumePlanner.scala` (`clearStorageDoneMarkers`, `recordStorageDone`, `sweepSupersededTaskFiles`, `accountsCompleteTaskFilePaths`, `deserializeSnapProgress`, resume selection from `launchAccountRangeWorkers`, accounts-complete recovery from `startSnapSync`, checkpoint and task-file arms, **#1501** `runGatedReplay`) | `lastSweptForRecord`, `preservedTaskFiles`, `currentCarrySource`, `launchedAccountGeneration` | `preservedRangeProgress`/`preservedAtPivotBlock` (shared with the refresher), `PhaseFlags.resumedStaleCursors`, `intakeBudget` | `AccountRangeProgressCmd` (checkpoint write), task-file/contract arms | `deferred-merkleization`, `storage-recovery-max-root-rolls`, `parallel-recovery-scan`, `recovery-scan-concurrency`, `recovery-scan-shard-depth`, task-file dir | — | `Recovery:` (untagged) | ~730 + ~120 (#1501) |
| M6a | merge the duplicated `startStateHealing` / `startStateHealingWithInterleave` spawn blocks into one `healingCoordinatorArgs` (dedupe, separate PR) | — | — | — | — | — | — | −80 net |
| M6b | `HealingOrchestrator` → `snap/controller/HealingOrchestrator.scala` (`completeHealingWalkClean`, `startTrieWalk`, `startStateHealing*`, `startHealingRequestScheduler`, `requestTrieNodeHealing`, `maybeRequestHealingServeRoot`, `triggerHealingForMissingNodes`, `queueHealedCode`/`resetHealedCodeHold`/`dropHealedCodeNowPresent`, `anchorPivotBeforeLazyHandoff`, healing arms) | `trieWalkInProgress`, `healingRoundCount`, `healingServeRootRequestInFlight`, `lastHealingServeRootBlock`, `healingWalkLocalOnly` | `healRepegNoRootAttempts`, `retryRefreshCounts`, `pendingPivotRefresh` (shared with the refresher), `healedCodeHashes` (shared with finalization), `healingFrontierStorageOpt`, `bfsQueueStorage` | `HealingAllPeersStateless`, `HealingRootUnservable`, `HealingServeRoot`, `HealingStagnated`, `StateHealingAbandoned`, `StateHealingComplete`, `ScheduledTrieWalk`, `TrieWalkBatch`, `TrieWalkComplete`, `TrieWalkFailed`, `TrieWalkResult`, `HealedCodeHashes`, `HealedCodeWaitTimeout`, `RequestTrieNodeHealing` | `healing-*`, `heal-hold-pivot-on-stagnation`, `scoped-heal-*`, `decoupled-heal-*`, `moving-root-delta-heal`, `pruned-heal-verification`, `healing-frontier-persistence`, `healing-visited-cap` | `snapsync.healing.*` (controller side: `nodes.healed`) | `[HEAL]`, `[HEAL-ABANDONED]`, `[HEAL-CODE]`, `[HEAL-INTERLEAVE]`, `[HEAL-LAZY-ANCHOR]`, `[HEAL-ROOT-UNSERVABLE]`, `[HEAL-SERVE-ROOT]`, `[HEAL-STAGNATED]`, `[HEAL-REPEG]` (serve-root side) | ~640 |
| M7 | `StagnationWatchdog` → `snap/controller/StagnationWatchdog.scala` (`scheduleStagnationChecks`, `maybeRestartIfStorageStagnant`, `maybeForceCompleteIfBytecodeStagnant`, `maybeRestartIfAccountStagnant`, `CheckDownloadStagnation` arm incl. progress `ask`s, `watchdog.reset(reason)` hook) | `storageTailBaseline`, `lastStorageProgressMs`, `lastBytecodeProgressMs`, `lastBytecodeProgressCount`, `lastAccountProgressMs`, `lastAccountTasksCompleted`, `lastAccountsDownloaded`, `consecutiveAccountStallRefreshes`, `storageStagnationRefreshAttempted`, `lastPivotRestartMs` | `PhaseFlags.forceCompleteStorageSent`, `CoordinatorHandles` | `CheckDownloadStagnation`, `DelayedRestart` (trigger side), progress forwarding | `account-stagnation-timeout` | — | `[STAGNATION]` | ~370 |
| M8 | `PivotSelector` → `snap/controller/PivotSelector.scala` (`idle` pivot parts, `bootstrapping`, `handleCLPivotHint`, pivot-selection parts of `startSnapSync`, bootstrap-retry, `currentNetworkBestFromSnapPeers`, `updateBestBlockForPivot`) | `minPivotHint`, `clHintArrivedAtMs`, `clPivotHint`, `bootstrapRetryCount` | `pivotBlock`, `stateRoot` (written), `currentPhase` | `Start`, `MinPivotBlock`, `CLPivotHint` (via P3), `BootstrapComplete`/`PivotBootstrapFailed` (initial), `RetrySnapSyncStart`, `RetryBootstrapAtBlock` | `pivot-block-offset`, `max-pivot-staleness-blocks`, `enabled`, `timeout` | `snapsync.pivot.block.number.gauge` | `[CL-PIVOT]`, `[SNAP]` | ~700 |
| M9 | `SyncLifecycle` → `snap/controller/SyncLifecycle.scala` (`recordCriticalFailure`, `enterDormantMode`, `wakeFromDormant`, `dormantRetry`, `restartSnapSync`, `PivotStateUnservable` arm, **#1501** `heapWatchdog`, `ensureHeapWatchdog`, `stopHeapWatchdog`, `onHeapPressureChange`) | `criticalFailureCount`, `accountsAtLastCriticalFailure`, `dormantRetryCount`, `heapWatchdog` | `PhaseFlags.reset(kind)`, `CoordinatorHandles.stopAll()`, `cancelSyncTimers` | `DormantWakeUp`, `DelayedRestart`, `PivotStateUnservable` | `max-snap-sync-failures`, `heap-watchdog-*` | `snapsync.errors.total`, `snapsync.memory.heap.pressure`, `snapsync.memory.oldgen.postgc.*` | `[SNAP-HEAP]` | ~320 + ~70 (#1501) |
| M10 | `PivotRefresher` → `snap/controller/PivotRefresher.scala` (`refreshPivotInPlace`, `completePivotRefreshWithStateRoot`, probe, unservable and `RetryPivotRefresh` arms) | `lastProactivePivotBlock`, `pivotProbeRequestId`, `proactiveRollNeedsProbe`, `pendingProbeCommit`, `lastProbeAttemptMs`, `probeAttemptCount`, `consecutivePivotRefreshes`, `failedPivotBlocks` | **everything shared**: `pivotBlock`, `stateRoot`, `validationGeneration`, `preserved*`, `pendingPivotRefresh`, `healRepegNoRootAttempts`, `retryRefreshCounts`, stagnation `reset`, `CoordinatorHandles.pivotRefreshed`, `ChainDownloaderHandle` | `BootstrapComplete`/`PivotBootstrapFailed` (refresh, guarded by `pendingPivotRefresh`), `PivotProbeTimeout`, `RetryPivotRefresh` | `pivot-block-offset`, `max-pivot-staleness-blocks`, `moving-root-delta-heal` | `snapsync.pivot.refreshed.total` | `[PIVOT-PROBE]`, `[PIVOT-ROLL]`, `[HEAL-REPEG]` | ~570 |
| M11 | `DownloadSupervisor` → `snap/controller/DownloadSupervisor.scala` (`launchAccountRangeWorkers` non-resume part, `startAccountRangeSync`, `request*` ticks, `checkAllDownloadsComplete`, `currentSyncStatus`, phase-completion, response-routing and progress arms, `IncrementalContractData` arm (#1501 budget release)) | `bytecodesEstimatedTotal`, `storageContractProgressPct` (`pendingPivotRefresh` and `preserved*` stay in the core: shared with M5, M6, M10) | `PhaseFlags`, `CoordinatorHandles`, `intakeBudget`, `progressMonitor` | `AccountRangeSyncComplete`, `ByteCodeSyncComplete`, `StorageRangeSyncComplete`, `StorageRangeSyncForceCompleted`, `AccountTrieFinalized`/`Failed`, `*Response`, `*CoordinatorProgress`, `Progress*`, `*BackpressureChanged`, `Request{AccountRanges,ByteCodes,StorageRanges}`, `IncrementalContractData` | `account-concurrency`, `storage-*`, `max-concurrent-storage-accounts`, `account-*-response-bytes`, `storage-max-inflight-per-peer-during-accounts`, `max-pending-storage-tasks`, `max-pending-bytecode-hashes` | `snapsync.{accounts,bytecodes,storage}.*`, `snapsync.phase.*`, `snapsync.memory.{storage,bytecode}.pending`, `snapsync.memory.intake.paused` | (untagged) | ~850 |
| — | **Core** stays in `SNAPSyncController.scala`: constructor, hubs, `start`, `idle` shell, `peerEventArms`, `commonSyncingArms`, `onStop`, `syncing` dispatcher, companion `apply`, `SNAPSyncConfig` | hubs | — | `GetStatus`, `GetProgress` | (config parsing) | — | — | ~1,000 incl. config |

**[016] Reassignment from research.md R3**: `calibratePivotTD` is listed under PIVOT-SEL in the R3 cluster mapping,
but it moves with the Peer pool (M3), because the state it reads and writes (`bestEth68PeerForCalibration`) belongs
to PEER. `updateBestBlockForPivot` stays in M8.

Commands that sit on a boundary (for example `BootstrapComplete`, which has three different implementations) go to
the module that owns the implementation for that behaviour. The table lists each case.

## Coordinator modules

Boundaries were preliminary (research.md R12). **T060 confirmed them with amendments**
([coordinator-characterization.md](./coordinator-characterization.md) §3; baselines in research.md R14b). The rows
below carry the amendments, marked "(T060)". Two user decisions gate the storage slices (both decided 2026-10-12,
see "Amendment 2026-10-12" below):
- **D-T060-1** (before C-A3): `StateIngest` gains members for the per-pivot `MptStorage` (a method) and the derived
  stores (`SnapStorageDoneStorage`, `SnapSyncProgressStorage`, `HealingFrontierStorage`, `BfsQueueStorage`), through a
  capability-mode PR. The members hand out the same instances, built where they are today.
- **D-T060-2** (before C-B1): `SyncController` builds one `StateIngest` and passes it to `BytecodeRecoveryActor` and
  `StorageRecoveryActor`, which pass it to the coordinators. `StateIngest` widens from `private[snap]` to
  `private[sync]`.

The tooling task **T060b** (done) lets `verify.py` check the `apply` + `ChildFactories` + spawn-site + call-site change
(§6 of the characterization) under the `# adopts: StateIngest <Unit>` trailer. Each coordinator's storage parameters
change in the first slice whose code uses them, not in a zero-reference slice (C-A1, C-A2, C-S1, C-S4, C-H4).

**Amendment 2026-10-11.** Every C-* narrowing self-type names `StateIngest` for storage, never `StateAccess` and never a
raw storage type. Each C-* PR lowers the T054 storage-reference total (its coordinator file's figure normally falls too,
but the gate is the total). The coordinators get their handles through `ChildFactories` today. **Amended (user
decision 2026-10-11):** T055 does not change the spawn arguments; each C-* narrowing changes its coordinator's
`ChildFactories` method and the coordinator's `apply` together, so `ChildFactoriesSpec` holds by construction.

| # | Coordinator → trait / file | Contents | Notes | Est. lines |
|---|---|---|---|---|
| C-B1 | ByteCode → `actors/bytecode/ByteCodeDispatch.scala`, `ByteCodeResponseHandling.scala` | cooldown, response bytes, assign/dispatch/redispatch; `handleByteCodesResponse`, `validateReturnedCodes`, `storeBytecodesWithHashes`, `filterAndDedupeCodeHashes`, the #1501 `skipPresent` check (T060: it is a line of the `AddByteCodeTasks` arm, so it moves only after a lift commit carves it into a method; otherwise it stays in the core) | one PR (coordinator is 870 lines); adopts `bytecode` (gated by D-T060-2) | ~320 |
| C-A1 | Account → `actors/account/AccountPeerDispatch.scala` | stateless/snapless marking (with `maybeRequestPivotRefresh`, T060), cooldown, response bytes, `inFlightForPeer`, `dispatchIfPossible`, backpressure + #1501 intake gate | `[ACCOUNT-COOLDOWN]`, `[ACCOUNT-IDLE]`; zero-reference (T060) | ~300 |
| C-A2 | Account → `actors/account/AccountTaskLifecycle.scala` | `handleTaskComplete`/`Failed`, `requeueOrEscalate`, redispatch, `drainActiveTasks` (T060), 32-byte hash math, `completeEmptyTaskRange`; `classifyIdleStall` stays with the core's `CheckDispatchStalled` arm (T060) | site of "stale-pivot empty ranges as scarcity"; zero-reference (T060) | ~320 |
| C-A3 | Account → `actors/account/AccountTrieAssembly.scala` | `getOrCreateTaskStackTrie`, `handleStoreAccountChunk`, `finalizeTrie`, `finalizing` behaviour | StackTrie resume (#1487); adopts `pathNodes`, `mptStorage` stays explicit (Amendment 2026-10-13) | ~300 |
| C-A4 | Account → `actors/account/ContractWorkCarry.scala` | `identifyContractAccounts`, task-file writing, `syncTaskFiles`, `replayCarriedChunk`, `readContractFile`, `readUniqueCodeHashes`; `copyPrefix` stays in the companion: only core vals call it (T060) | **vault**; #1453/#1487/#1498 and #1501 invariants; `storageDone` and `mptStorage` stay explicit, total +3 for the Env declaration (Amendment 2026-10-13) | ~350 |
| C-S1 | Storage → `actors/storage/StoragePeerDispatch.scala` | stateless, `SnapPeerHealth` (with `handleTimeout`, T060), cooldown, batch sizing, response bytes, `selectFloorPeer` use, `redispatchEligible` | `[STORAGE-PEER-HEALTH]`, `[STORAGE-FLOOR]`; zero-reference in substance (T060) | ~480 |
| C-S2 | Storage → `actors/storage/StorageOrderedApply.scala` | `ReadyStorageChunk` ordering, `drainOrderedStorageChunks`, `applyReadyStorageChunk`, per-account trie get/commit/**reset**, `flushTrieNodeWrites`, `writePrebuiltTrie` (#1533, T060) | **vault**; site of the "pivot refresh discards in-flight tries" task; `flushTrieNodeWrites` writes through `pns.dataSource.update` (raw, T060) | ~360 |
| C-S3 | Storage → `actors/storage/StorageDoneMarkers.scala` | `recordFlatBatchDone`, `stageDoneMarker`, `takeDoneMarkersFor`, `stageFlatSlotChunk`, `flushPendingFlatBatch`, `recordCompletedTask` | **vault**; spec 010 R2a is kept word for word (a record is never durable before its flat slots) | ~170 |
| C-S4 | Storage → `actors/storage/StorageResponseProcessing.scala` | `handleResponse`, `processStorageRanges`, `processServedTasks`, `createStorageSubTasks`, `recordSubtaskCompletion`, stale-root give-up, and #1533's off-actor pipeline (`PendingResponse`, `enqueueResponse`, `submitProcessingJobs`, `drainProcessedResponses`, `logStoragePerf`, `abandonPendingResponses`, pool shutdown; T060) | `[STORAGE-FORCE-COMPLETE]`; reads no store: proof verification is pure (T060) | ~480 |
| C-H0 | Healing: split `activeBehavior` (821 lines) into per-concern partial functions inside the file (the D3 pattern) | arm-order table as in P4 | prerequisite for C-H1…H5 | ~820 restructured |
| C-H1 | Healing → `actors/healing/FrontierPersistence.scala` | `persistFrontier`, `unpersistFrontier`, `clearPersistedFrontier`, `awaitFrontierDrain`, `writeDurableSubtreeRecords`, `flushRawNodes*`, gauges | **vault**; CF `g` format frozen | ~250 |
| C-H2 | Healing → `actors/healing/FrontierRebuildBfs.scala` | `rebuildFrontierBFS`, `startFrontierBFS`×2, visit keys; `boundedVisitedSet` and `computeEffectiveParallelism` are companion helpers (follow behind `export` or stay, T060) | site of #1350 and #1349 | ~440 |
| C-H3 | Healing → `actors/healing/HealVerification.scala` | `reverifyCurrentRoot`, `startVerificationBFS`, `startScopedVerification`, pruned verification, `readPathVerifiedRlp`/`Node` (T060) | `[HEAL-VERIFY*]`; spec 003/005 | ~120 |
| C-H4 | Healing → `actors/healing/HealDispatch.scala` | cooldown, response bytes, `dispatchIfPossible`, `requestNextBatch`, `handleTimeout`, redispatch, `noteUnservableAttempt`, `updateHealThrottle` | `[HEAL-FLOOR]`, `[HEAL-WATCHDOG]` | ~350 |
| C-H5 | Healing → `actors/healing/HealResponseProcessing.scala` | `processActiveResponse`, `discoverMissingChildren`, `queueNodes`, healed code hashes, `isComplete`, `isNodeInStorage` (T060) | site of the heal-convergence latch; `evmCodeStorage` is `Option` with a `None` test default (T060) | ~440 |
| C-X | `snap/AdaptiveResponseBytes.scala` shared by ARC, SRC, TNHC | the three identical windows, with the log label as a parameter (R12) | ByteCode excluded (NG-5) | −60 net |

## Slice list, order and estimates

| Order | Slice | Kind | Est. lines (main / test) | Reviewers | Validation beyond CI |
|---|---|---|---|---|---|
| 0a | **S0e** CI job `snap-synctest`: `sbt "testOnly *SyncControllerSpec *PivotHeaderBootstrapSpec *SnapServingActorSpec -- -n SyncTest"` on **every** PR to staging (cheap early exit when no `blockchain/sync/**` change), report-parsed one-entry allow-list (#68) | CI | workflow + small report parser | prism | — |
| 0b | **S0f** `scripts/snap-split/` verification tooling + self-tests + required job `snap-split-verify` | tooling | ~400 script / ~200 self-test | prism | — |
| 0c | **S0g** stabilise `StaleVerificationWalkSpec` (deterministic sync, no wall-clock `eventually`; project task #85) | test | 0 / ~60 | prism, vault | — |
| 1 | **S0a** #1367 pin (NPMA) | test | 0 / ~80 | herald | — |
| 2 | **S0b** controller pins #1378, #1319×2, spec-005 (+#1502 doc), #1371 + `ChildFactories` seam | tests + seam | ~60 / ~350 | prism, forge | — |
| 3 | **S0c** golden-bytes for the frozen formats | test | 0 / ~300 | vault | — |
| 4 | **S0d** `SnapControllerFixture` + characterization (stagnation, reset incl. heap-watchdog non-stop, pivot refresh, #1501 budget pins, P1 fan-outs, P4 Command × phase table) | test | 0 / ~1,200 | prism, forge, beacon (CL pivot cases) | — |
| — | **gate: #1501 merged: MET** (`dfa4a8836`; T001 done). P1 waits only for S0a–S0g. | | | | |
| 5 | **P1** `CoordinatorHandles` + `ChainDownloaderHandle` | refactor | ~+150 / −120 | prism, forge | Mordor restart |
| 6 | **P2** `PhaseFlags` + `reset(kind)` + `cancelSyncTimers` | refactor | ~+200 / −250 | prism, vault, forge | Mordor + Platåberget restart and dormant |
| 7 | **P3** `peerEventArms` | refactor | ~+60 / −90 | prism | — |
| 8–12 | **P4a–e** per-phase arms (AccountRange, ByteCodeAndStorage, StateHealing, StateValidation, ChainDownloadCompletion) | restructure | ~230 each | prism, forge; beacon for P4c and P4e | Platåberget full SNAP after P4e |
| 13 | **M1** policy objects + `export` | move | ~300 | prism | — |
| 14 | **M2** Validation | move | ~175 | prism, forge | — |
| 15 | **M3** Peer pool | move | ~480 | prism, herald | — |
| 16 | **M4** Finalization | move | ~480 | prism, beacon, forge | Platåberget to head (PathPublish, backfill) |
| 17 | **M5** Resume planner | move | ~850 | **vault**, prism, forge | Mordor + Platåberget mid-account and mid-storage restart |
| 18 | **M6a** healing spawn dedupe | refactor | −80 | prism, vault | — |
| 19 | **M6b** Healing orchestration | move | ~640 | prism, vault, forge | Mordor heal restart |
| 20 | **M7** Stagnation watchdog | move | ~370 | prism | — |
| 21 | **M8** Pivot selection | move | ~700 | prism, beacon, forge | Platåberget start from scratch |
| 21b | **M8b** remove the dead `isStarting` parameter (cleanup, own PR; FR-034) | cleanup | ~−10 | prism | — |
| 22 | **M9** Lifecycle/reset + dormant | move | ~390 | prism, vault, forge | Mordor dormant/wake |
| 23 | **M10** Pivot refresher | move | ~570 | prism, beacon, forge | Platåberget across ≥ 3 refreshes; Mordor |
| 24 | **M11** Download supervisor | move | ~850 | prism, forge | full Mordor SNAP |
| 24a | **T054** env freeze ratchet: `verify.py` step 8 against `scripts/snap-split/storage-baseline.json` (`SnapControllerEnv` count 18; storage-reference total over `snap/controller/` + `snap/actors/`, down-only) (amendment 2026-10-11; PR #1570, `test/snap-016-env-freeze`) | tooling | script + baseline file | prism | — |
| 24a′ | **T054b** `verify.py` mode for a behaviour-neutral capability-introduction PR (neither a move nor a narrowing); needed before T055; owned by the T054 tooling line | tooling | script + self-tests | prism | — |
| 24b | **T055** `StateIngest` capability: 8 env storage handles + the batched storage-trie node-write path (`flushTrieNodeWrites`, from #1548); modules move to it; `SnapControllerEnv` 18 → ~11; exporter durability property (FR-044) | refactor | ~+250 / −150 (estimate) | **forge**, **vault**, prism; beacon for the Path-scheme publish | Mordor full SNAP (SC-010 perf parity moved to C-S2, user decision 2026-10-11) |
| 24c | **T056** lighter-WAL experiment as a `StateIngest` durability policy (optional; after T055). Hard precondition: the explicit FR-044 barrier before `completeSnapSync` (user decision 2026-10-11) | experiment | small | **vault**, forge | Mordor + Platåberget SNAP with a crash during finalization |
| 25 | **T060** coordinator characterization (docs) | docs | — | — | — |
| 25a | **T060b** `verify.py` StateIngest-adoption allowance for C-* narrowings (characterization §6.3); decisions D-T060-1/2 recorded (done; adoption mode, capability extension, counter v2) | tooling | script + self-tests | prism | — |
| 25b | **D-T060-2** recovery wiring: `SyncController` builds one `StateIngest` for the two recovery actors; `StateIngest` → `private[sync]` (`# adopts: StateIngest RecoveryActors`) | refactor | ~60 | prism; **forge** (both run on ETC at startup); vault | a bytecode- or storage-recovery start on Mordor |
| 25c | **D-T060-1** `StateIngest` members for the per-pivot `MptStorage`, `snapProgress` and `storageDone` (`# capability: StateIngest`, an extension; before C-A3; healing stores deferred to C-H1, Amendment 2026-10-12) | refactor | ~80 | prism; **vault** (lazy healing stores, see Amendment 2026-10-12) | — |
| 26 | **C-B1** | move | ~320 | prism; **vault** (T060: code-write path, recovery wiring) | Mordor bytecode phase or recovery start if D-T060-2 rewires `BytecodeRecoveryActor` |
| 27–30 | **C-A1…A4** | move | ~1,350 | prism; **vault** on A3 and A4 | Mordor restart mid-account after A4 |
| 31–34 | **C-S1…S4** | move | ~1,370 | prism; **vault** on S2 and S3 | Mordor + Platåberget restart mid-storage after S3; **C-S2: `[STORAGE-PERF]` parity on the same quiescent soak host against ~1.7M entries/h on the Sepolia Path-scheme storage phase, plus a same-host pre-wrap vs post-wrap A/B (SC-010, moved from T055 by user decision 2026-10-11)** |
| 35–40 | **C-H0…H5** | restructure + move | ~2,520 | prism; **vault** on H1 and H3 (T060: also H2 and H5) | Mordor heal restart after H1 (T060: recommended after H5 too) |
| 40b | **C-X0** golden test of the response-size window (grow/shrink math and clamp, per coordinator) and of its DEBUG log text | test | 0 / ~120 | prism | — |
| 41 | **C-X** `AdaptiveResponseBytes` | dedupe | −60 | prism | — |
| 42 | **R1** DFS→BFS wording + routing-doc final check (T090) | rename | ~40 | prism | — |

## Move verification (scripted, required CI job `snap-split-verify`)

`scripts/snap-split/` ships in **S0f, before P1**, with fixture-based self-tests. The CI job `snap-split-verify` runs
it on every commit of every spec-016 PR (`git rev-list origin/staging..HEAD`) and exits non-zero on any failure.
**It is not required on S0a–S0e**, which merge before the script exists. Those PRs are checked by hand against the
same rules, and the reviewer states so in the review. S0f checks itself (its self-tests), and every slice after S0f
must pass the job. A
reviewer re-runs it locally with `scripts/snap-split/verify.sh origin/staging`; it needs no JVM.

**For a move commit:**
1. **Moved blocks only.** Every removed line shows as moved
   (`git diff --color-moved=plain --color-moved-ws=allow-indentation-change`). The only non-moved lines allowed are
   trait headers, imports, `private`→`private[snap]`, scalafix result types and one-line `export` clauses whose
   selectors are all `# moved:` symbols and whose object path resolves to the object now holding them (D2; added in M1, when the first companion move needed them; the object check came after M1).
2. **Identical bodies.** For each symbol in the PR's list (read from a `# moved:` trailer in the commit message), the
   body extracted from the parent and from the commit, with the visibility modifier stripped, is identical.

**For every commit:**

- **Branch rule (CQ-SNAP-016-23).** A change to a module file needs a `refactor/snap-016-*` or `test/snap-016-*`
  branch, or a `fix/*` or `hotfix/*` branch whose name does not contain `snap-016` (a fix named like a split slice is
  kind "other" and fails `[branch]`). A fix commit is never a move or narrowing, and fails `[fix-branch]` if it:
  carries `# moved:`; removes the last `*Impl` mention; adds or deletes a module file; or nets more than 150 deleted
  lines in module files without a `# fix: #<issue>` trailer. Steps 1, 2, 3, 6 are skipped. Still enforced: FR-016 (a)
  with no one-mention allowance, (d), `[counts]`, the merge-commit ban, the final-state check, and step 4's
  `src/main/resources` rule. Protected pin files may change only if the commit lists each by exact path in a
  `# pins: <paths>` trailer, and every listed path must be modified (a fix PR updates its pin on purpose, NG-4/D5).

3. **Test-hunk guard.** It applies on `refactor/snap-016-*` branches (P*, M*, C-*, R1).
   - In existing suites, every changed `src/test` hunk may touch only `import` lines and fixture construction.
   - A hunk that adds or removes a line containing an assertion or scheduling token fails. The tokens are `should`,
     `must`, `shouldBe`, `===`, `==`, `assert`, `assume`, `expect`, `intercept`, `fishForMessage`, `within`,
     `eventually`, `verify`, `taggedAs`, `ignore`, `pending`, `cancel`, `timeout` and `interval`.
   - New test files are allowed only when the slice's tasks name them.

   **`test/snap-016-*` branches (S0b, S0c, S0d, S0g, C-X0) are exempt from the token check.** These slices edit
   existing suites on purpose: S0b adds pins to `SNAPSyncControllerSpec`, and S0g rewrites `StaleVerificationWalkSpec`.
   Instead, each commit on such a branch must carry a `# test-files:` trailer that lists every `src/test` file it may
   touch. The script fails if the commit touches a `src/test` file that is not listed, or any `src/main` file other
   than the S0b seams named in T012.
4. **No format or config drift.** No change under `src/main/resources`. The S0c golden files and the S0a/S0b pin
   tests are byte-unchanged.
5. **FR-016 (a) and (d)**, as described in D1.

**For a narrowing commit, additionally:**

6. **Signature-only diff.** The commit may touch only:
   - self-type lines;
   - `<Module>State` and Api trait declarations;
   - the core's `extends` list and shared-field declarations (the script finds the `extends`/`with` clause past a
     constructor of up to 80 lines, but admits only the clause's own lines there; added in M2, whose narrowing is the first
     to touch the core's 30-line constructor);
   - `private` on exclusive vars;
   - the listed pure `val` → `lazy val`/`def` conversions;
   - the new stub test.
7. **FR-016 (c) counts**, printed for the PR body.

**For the PR head, once (env freeze ratchet, 2026-10-11 decision):**

8. **Storage ratchet** against `scripts/snap-split/storage-baseline.json`. The copy at the merge-base with staging is
   the ceiling. HEAD's copy must equal the measured values, so a decrease is recorded in the same PR. The baseline
   bootstraps once: when staging has the file, a merge-base without it fails, and the PR must rebase.
   - `[env-freeze]`: `SnapControllerEnv` never grows. Its member count (18) and its header (parents and self-type) are
     pinned, and no justification can raise either. A member is storage-typed when an identifier in its text after the
     name matches `Storage|Store|DataSource|(?i:rocksdb)|^Blockchain(?:Reader|Writer)$|WriteBatch`, including inside
     function and `Option` types. Only the names in `STORAGE_NAME_ALLOW` (`verify.py`) are exempt. These are
     `StorageRangeCoordinator`, `StorageRangeWorker`, `StorageRangesResponseMsg`, `StorageCoordinatorProgress`,
     `GetStorageRanges` and `StorageRanges`, enumerated from staging. Any other `…Storage…Worker`/`Actor`/`Coordinator`
     name is flagged by this rule and counted below.
     - The 8 current storage-typed members are pinned by name: `appStateStorage`, `stateStorage`, `evmCodeStorage`,
       `flatSlotStorage`, `pathNodeStorageOpt`, `blockchainReader`, `blockchainWriter` and `validatorFactory`.
     - Removing a pinned member passes.
     - Retyping a pinned member to another storage type passes.
     - Retyping any other member into a storage type fails.
   - `[storage-refs]`: `STORAGE_REF_RE` (in `verify.py`) is counted in code under `snap/controller/` and `snap/actors/`,
     and in the controller core `snap/SNAPSyncController.scala` (`RATCHET_FILES`; T054c). The core is in the measured
     set because it is the split's move source: without it, a byte-identical move of a storage reference out of the
     core read as new coupling. No other top-level `snap/` file is a move source, so none is listed. A small tokenizer
     drops comments but keeps string literals. The rule is move-aware:
     - The total must not rise. Per-file counts may move between files, so a split slice that moves references into a
       new module file, or from the core into a module, needs no justification.
     - A higher total needs a new `justifications["storage_refs.total"]` line.
     - The table prints on every run.
     - The metric is a tripwire, not a proof. It matches identifiers and `.xStorage…` member accesses, so a determined
       alias can evade it, and reviewers still read the diff.
   - `[raw-import]`: no new import from `org.rocksdb` or `…db.dataSource`, and no new imported name containing
     `WriteBatch` or inline fully qualified reference, in those files. The baselined files keep only their
     listed imports. There is no escape.

The **per-suite counts** (FR-032) come from the Tier 1 job's and the S0e job's test reports. The PR pastes them, and
the reviewer compares them with the T002 baseline.

## Risks

**R-1 (largest): `syncing` and pivot refresh share most of the state.** `syncing` writes 44 items.
`completePivotRefreshWithStateRoot` writes 12 and messages every child. Together they touch every hub. Moving either
one first would mean moving code whose state other modules still write inline. The prerequisites reduce this risk
before either is touched:

- **P2** turns the reset writes (28 + 17 + 13 + 17 lines across four methods) into one reviewed per-kind table, and
  S0d's reset tests assert it. After P2, a module that needs "reset the stagnation clocks" or "reset the phase flags"
  calls a named function instead of writing vars inline. That removes most cross-module **writes** (45 shared items
  fall to about 34; research.md R2).
- **P1** removes the child-ref writes and fan-outs from every module. The refresher's five child messages become a
  single `handles.pivotRefreshed(root)` plus the ChainDownloader handle.
- **P4** splits the 1,150-line `syncing` into five phase functions. Each M slice then moves the arms of one Command
  family out of one phase function, instead of cutting through a behaviour every phase uses.
- **S0d** adds characterization tests for pivot refresh: the messages sent to each child and the ChainDownloader,
  persisted anchors, and probe commit/abort. They land before P1, so every later slice runs against them.
- **The refresher moves last (M10).** By then every other module has its own file, and the refresher's remaining
  writes all go through named hooks (`watchdog.reset`, `PhaseFlags`, handles, `validationGeneration` bump). Its PR is
  mostly calls, not writes.

**R-2: initialization order in traits.** See D1. FR-016 (d) makes it grep-checkable (no concrete `val` in a
module trait; `var` initializers on an allow-list). It is also caught at construction by every controller spec.

**R-8: narrowing exposes more coupling than the matrix shows.** The R14 baseline is method-attributed and excludes
`syncing` arms, so some interfaces will come out larger than R14 says. Mitigations:
- The interface is whatever the compiler demands, and it is recorded (c).
- A count above the R14 baseline needs a justification line in the PR.
- After P1/P2 the child refs and phase flags collapse into two capability traits, which shrinks most interfaces.

If narrowing a module needs a non-trivial body change, that is a signal of hidden coupling. Stop and raise it; do not
work around it in the narrowing commit.

**R-3: arm order in P4 and C-H0.** A reordered arm silently changes which handler wins. Mitigations: the arm-order
table in each PR, S0d characterization tests, and keeping P4 as five small PRs.

**R-4: #1501 churn: closed.** #1501 merged as `dfa4a8836` on 2026-10-08. The merged version added an escape policy
and two keys beyond the snapshot first read. T001 re-mapped them (research.md R11), and no module boundary moved.
S0b's seams and S0c's goldens are written against the merged code (T001b).

**R-9: CI does not run what we think it runs.** `SyncControllerSpec`, `PivotHeaderBootstrapSpec` and
`SnapServingActorSpec` are `SyncTest`-tagged, and Tier 2 is skipped on staging (research.md R13a). Mitigations: S0e
adds a SNAP-scoped `SyncTest` job, every PR pastes per-suite counts, and the flake policy (FR-039) covers the known
`StaleVerificationWalkSpec` flake until S0g fixes it.

**R-5: hotfix forward-ports** while Sepolia is on fix-only builds (D6). Mitigations: symbol-anchored pins and the
routing doc. A move PR that conflicts with a hotfix is redone from the new base, not hand-merged.

**R-6: reviewer fatigue on large moves** (M5, M11, C-H0 are 800+ lines each). Mitigation: the verification recipe
makes the review mechanical, and the reviewer reads only the non-moved lines.

**R-7: log-scraping consumers** (dashboards, the `fukuii-log-triage` skill) depend on log text. FR-031 keeps the text
unchanged, so only the source file a line comes from changes.

## Effort estimate

- **PRs**: 47. That is 7 S0 (S0a–S0g), 8 prerequisites (P1, P2, P3, P4a–e), 13 controller (M1–M11 with M6 split in
  two, plus M8b), 1 coordinator characterization, 15 coordinator, 2 C-X (C-X0 pin, C-X) and 1 R1.
- **Lines**: about 1,630 test lines added in S0, about 6,000 controller lines moved (`syncing` arms move twice: P4 and
  then M), and about 5,500 coordinator lines moved. Net `src/main` growth is small: trait headers, handles and
  `PhaseFlags`, less the deduplication.
- **Time**: about 0.5–1 agent-day per move, plus about 0.5 day for the required narrowing commit and its stub test
  (about 1,500 extra test lines across 26 module PRs), plus review and CI. P2, P4 and M10 need 2–3 days each. With two
  specialist reviews per PR and Mordor/Platåberget runs on about 14 slices, a realistic pace is 4–5 merges a week:
  **about 9–12 weeks** for the whole series. The controller (slices 1–24) is roughly the first 6–7 weeks.

## Project Structure

### Documentation (this feature)

```text
specs/016-snap-controller-split/
├── spec.md       # what and why, FRs, invariants, non-goals, open questions
├── plan.md       # this file
├── research.md   # Phase 0: characterization (matrices copied from the report)
└── tasks.md      # slice-ordered tasks
.claude/agent-protocols/snap-sync.md   # routing doc (draft; updated by every slice)
```

### Source code (target, after the series)

```text
src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/
├── SNAPSyncController.scala            # core + companion + config (~1,000)
├── controller/
│   ├── CoordinatorHandles.scala        # P1
│   ├── PhaseFlags.scala                # P2
│   ├── SnapSharedState.scala, SnapControllerEnv.scala   # hub interface + environment capability (M2, the first trait-based module PR)
│   ├── StateIngest.scala               # T055 (amendment 2026-10-11): SNAP data-plane capability + durability policy
│   ├── <Module>State / <Module>Api     # declared in each module's file (narrowing commit)
│   ├── ChildFactories.scala            # S0b seam
│   ├── ResumePolicy.scala, StagnationPolicy.scala, PivotPolicy.scala, HealPolicy.scala   # M1
│   ├── StateValidationModule.scala     # M2
│   ├── SnapPeerPool.scala              # M3
│   ├── SnapFinalization.scala          # M4
│   ├── SnapResumePlanner.scala         # M5
│   ├── HealingOrchestrator.scala       # M6
│   ├── StagnationWatchdog.scala        # M7
│   ├── PivotSelector.scala             # M8
│   ├── SyncLifecycle.scala             # M9
│   ├── PivotRefresher.scala            # M10
│   └── DownloadSupervisor.scala        # M11
├── AdaptiveResponseBytes.scala         # C-X
├── SnapIntakeBudget.scala, SnapHeapWatchdog.scala, GatedTaskFileReplay.scala   # #1501, unchanged
└── actors/
    ├── AccountRangeCoordinator.scala   # core + ADT
    ├── account/{AccountPeerDispatch,AccountTaskLifecycle,AccountTrieAssembly,ContractWorkCarry}.scala
    ├── StorageRangeCoordinator.scala
    ├── storage/{StoragePeerDispatch,StorageOrderedApply,StorageDoneMarkers,StorageResponseProcessing}.scala
    ├── TrieNodeHealingCoordinator.scala
    ├── healing/{FrontierPersistence,FrontierRebuildBfs,HealVerification,HealDispatch,HealResponseProcessing}.scala
    ├── ByteCodeCoordinator.scala
    └── bytecode/{ByteCodeDispatch,ByteCodeResponseHandling}.scala
```

**Structure decision**: the new files go in sub-packages of `snap`, so `private[snap]` reaches them and no import
outside `snap` changes. The public Command ADTs stay in their current objects.

## Complexity Tracking

| Deviation | Why needed | Simpler alternative rejected because |
|---|---|---|
| Constitution V: no local `sbt pp` before a PR | The only host is shared with the live Sepolia node; sbt next to the soak swaps it and breaks SNAP (memory: no builds during soak). | Running locally risks the live node. CI is the compiler and gate: Tier 1 (`compile-all`, `scalafmtCheckAll`, `testEssential`), the S0e `SyncTest` job and the S0f `snap-split-verify` job. Tier 2 is skipped on staging. A PR is opened only after all three are green (FR-035). This is the precedent from spec 014. |
| Constitution III: extraction PRs ship no new assertions | They change no behaviour, and FR-032 forbids assertion edits so the old suites stay a valid oracle. | Adding tests inside move PRs would mix oracle changes with code moves. The tests land in S0 instead. |
| `src/main` change in a "test" slice (S0b `ChildFactories`) | Pins #1378, #1319 and #1371 cannot be observed without a spawn seam, because the impl class is private. | A test-only reflection hack is brittle and not idiomatic. A constructor seam with a production default has precedent (`validatorFactory`). |
| Two commits per module: a byte-identical trait move, then **required** narrowing to state interfaces (D1, ADR CON-013), rather than classes from the start | The move stays mechanically checkable, and narrowing still gives real module boundaries: (a) no impl self-type, (b) stub-testable, (c) measured coupling, (d) no init hazard. | Classes from the start change every body in the move commit, so the move cannot be verified. Traits without narrowing are only a file split. |
| Five P4 PRs instead of one | Arm-order mistakes are silent. Smaller diffs keep the arm-order table reviewable. | One 1,150-line restructure is too large to review for arm order. |

## Amendment 2026-10-11: state capability boundary

User decision of 2026-10-11, quoted in spec.md ("Amendment 2026-10-11"). This section records how it changes the
plan. Nothing above that is not marked "amended" changes.

### What the amendment adds

| Item | Where | Owner / review |
|---|---|---|
| Env freeze ratchet: `verify.py` step 8 against `scripts/snap-split/storage-baseline.json`: the `SnapControllerEnv` member count (18) and the storage-reference total over `snap/controller/`, `snap/actors/` and the core `SNAPSyncController.scala` (comments excluded; files may trade; the core since T054c), which CI lets go **down only** (spec FR-043) | T054; PR #1570 on `test/snap-016-env-freeze`; T054c on `test/snap-016-ratchet-core` | prism |
| `verify.py` mode for a behaviour-neutral capability-introduction PR | T054b, owned by the T054 tooling line, before T055 | prism |
| `StateIngest` capability trait | T055, one behaviour-neutral PR between M11 and T060 | forge, vault, Mordor run, perf parity |
| Coordinator narrowings take `StateIngest`, never `StateAccess`, and each lowers the baseline total | T061–T075 (amended) | as in the slice list |
| Lighter-WAL experiment as a per-capability durability policy on `StateIngest` | T056 (optional) | vault, forge |
| `StateAccess`, execution witnesses, `SyncMode`, backfill, opaque `Address`/`TrieKey`/`Wei` | **spec 017** (`specs/017-state-capabilities-witnesses/`), which depends on T055 | beacon, forge, vault |

### `StateIngest`: shape and rules

- **Members.** It wraps the 8 storage handles now in `SnapControllerEnv`: `appStateStorage`, `stateStorage`,
  `evmCodeStorage`, `flatSlotStorage`, `pathNodeStorageOpt`, `blockchainReader`, `blockchainWriter` and
  `validatorFactory: MptStorage => StateValidator`. It also wraps the Path-scheme batched storage-trie node-write path
  (`SnapPathTrie` → `PathNodeStorage.writeStorageNode`, buffered in `StorageRangeCoordinator`'s `trieNodeWrites` and
  committed by `flushTrieNodeWrites`; introduced by #1548). Hash scheme already batches at 8 MiB. The exact method
  surface is derived in T055 from the modules' real uses, the way R14 derived the state interfaces.
- **Chain-data handles (design note, T055).** `blockchainReader` and `blockchainWriter` are chain-data handles
  (headers, pivot, best block), not trie state. T055 decides whether to split a header/chain sub-interface out of
  `StateIngest`, and records the decision in the ADR.
- **Verification reads are in.** Proof checks, healing presence checks, state validation and the publish
  verification are reads SNAP needs to decide what to write, so they belong to ingest, not to access.
- **No execution.** `StateIngest` exposes no EVM account/storage read or write. Those are `StateAccess` (spec 017).
- **Who uses it.** The controller modules (moved by T055), the coordinators (moved by their C-* narrowings), and,
  later, snapshot import (spec 013) and era imports.
- **Durability policy.** `StateIngest` carries a per-capability durability policy (default: today's behaviour). A
  relaxed policy (T056) is allowed only here.
- **The exporter is the durability boundary (FR-044).** `PathToHashExporter`'s publish is `StateIngest`'s commit to
  the access store. It runs under full durability, and its output is flushed and synced before finalization marks
  SNAP complete and before execution reads it, whatever policy the ingest writes used. On Hash scheme there is no
  export step, so an explicit durable flush/sync barrier runs before `completeSnapSync`. Without this, a crash during
  finalization leaves execution reading a store that was never durably written. Spec 017's DC-1 restates it as a
  design constraint. **Amended (user decision 2026-10-11):** T055 documents the property and pins today's implicit
  guarantee (the completion flags' synced commit covers every earlier WAL record; no separate barrier exists on
  either scheme). The explicit barrier lands in T056, as a hard precondition: no relaxed WAL policy without it.

### Why T055 is not a move or a narrowing (verify.sh)

Wrapping the handles changes call sites (`appStateStorage.x` becomes a `StateIngest` call), so T055 is neither a
byte-identical move (steps 1–2) nor a signature-only narrowing (step 6). It runs `snap-split-verify` in a mode that
**T054b** adds (owned by the T054 tooling line): the move and signature checks are replaced by
"call-site substitution only: every changed line in a module file replaces a storage handle with the matching
`StateIngest` member", while (a), (d), `[counts]`, the test-hunk guard, the pin and golden protection and the ratchet
still apply, and calls on `StateIngest` are not storage references. T055 does not start until T054b has merged.

### ADR

The capability boundary and the exporter durability property are an architectural decision (constitution VII). T055
records them in a new ADR under `docs/adr/infrastructure/` (next free INF number; this is storage and durability, not a
consensus rule), linked from this plan, in the T055 PR.

Recorded: [ADR INF-006](../../docs/adr/infrastructure/INF-006-snap-state-ingest-capability.md) (T055): the capability
boundary, the chain-data decision (no header/chain sub-interface for now), the durability policy, and the FR-044
finding (today the completion flags' synced commit is the only sync; no separate barrier exists on either scheme).
User decision 2026-10-11: document now; the barrier is added in T056 as a hard precondition of any relaxed WAL policy.
Open for vault: whether the synced flags batch covers WAL files rolled before it.

### Constitution Check (amendment)

- **I (consensus).** No consensus rule changes. Durability affects crash safety of state that execution reads, so
  forge (ETC, Hash scheme) and beacon (Path-scheme publish) review T055 and T056; vault reviews both.
- **III (tests).** T055 adds a Tier 1 test of the durability property on both schemes (a store fake that records
  flush/sync ordering) and keeps every existing assertion. It pins today's ordering (the synced completion flags
  after every ingest/publish write); T056 extends it to expect the explicit barrier.
- **V (gates).** Unchanged: CI is the compiler (Complexity Tracking, row 1).

## Amendment 2026-10-12: coordinator adoption (T060b, D-T060-1, D-T060-2)

User decisions of 2026-10-12, after T060 (coordinator-characterization.md §4.3, §4.4, §6). Nothing above that is not
marked changes.

- **D-T060-2 (before C-B1).** `BytecodeRecoveryActor` and `StorageRecoveryActor` (package `blockchain.sync`) get a
  `StateIngest` built by `SyncController`. `StateIngest` widens from `private[snap]` to `private[sync]`. `SyncController`
  already holds 6 of the 8 stores; it builds one `StateIngest`, deriving `pathNodes` exactly as `SNAPSyncController`
  does (`Some(new PathNodeStorage(flatSlotStorage.dataSource))` on the Path scheme, else `None`) and `validatorFactory`
  as `SNAPSyncController.apply`'s default (`new StateValidator(_)`). It passes it to the recovery actors, which pass it
  to the coordinators (after C-B1 / C-S*). Its own PR (slice 25b), `# adopts: StateIngest RecoveryActors`; forge signs
  off (both actors run on ETC at startup).
- **D-T060-1 (before C-A3).** `StateIngest` gains members for the derived stores through a capability-mode PR (slice
  25c), an *extension* of the existing capability: the per-pivot `MptStorage` as a method (a pure forwarder of
  `stateStorage.getBackingStorage`, or a member that hands out the core's cached instance), `SnapStorageDoneStorage`,
  `SnapSyncProgressStorage`, `HealingFrontierStorage` and `BfsQueueStorage`. They are the same instances, built where
  they are today, so the change is behaviour-neutral. The member cap (9) grows only with a
  `justifications["capabilities.StateIngest.members"]` line, and ADR INF-006 decision 1 ("members narrow with their
  callers") gets a note. Open for vault: `HealingOrchestrator` builds the healing stores lazily; handing them to the
  core's strict accessor would build them at construction, which changes when their column family / namespace is first
  touched. A per-pivot `getBackingStorage` forwarder returns a new `MptStorage` per call on the Hash scheme (ETC); the
  core's cache must stay the only caller per pivot (forge, vault).
  - **Narrowed (user decision 2026-10-12, T060d as built):** the members are `trieStore(pivot)` (the forwarder; its only
    caller is the core's per-pivot cache `getOrCreateMptStorage`), `snapProgress` and `storageDone`. `storageDone` is a
    by-name `apply` parameter, because the core builds `storageDoneStorage` after the capability. The cap goes 9 → 12
    and the storage total stays 619. The healing stores (`HealingFrontierStorage`, `BfsQueueStorage`) are deferred to
    **C-H1**:
    - the mirror rule needs each handle in the core's initializer as a plain `val` in SyncController, derived token
      for token as SNAPSyncController derives it. The healing stores are `HealingOrchestrator`'s `private lazy val`s;
    - the core cannot name them without a visibility change, which capability mode rejects.
    A small verify change at C-H1 admits by-name/lazy members (#1584).
- **T060b (verify.py).** Three additions, routing doc "Adoption mode (T060b)":
  - *Adoption mode.* `# adopts: StateIngest <Unit>` admits a C-* narrowing commit (or a standalone commit) that moves
    a coordinator onto `StateIngest`: its Impl constructor, `apply`, `new <X>Impl(...)`, `ChildFactories` method,
    spawn sites, recovery-actor calls and in-coordinator call sites, by parameter-list, argument-list and handle
    rewrites only. In a narrowing commit step 6 runs inside this check. `Unit = RecoveryActors` admits D-T060-2.
  - *Capability extension.* A capability-mode PR for an existing capability may add members, map lines, forwarders
    and `apply` parameters; the core's accessor gains only the new arguments, and SyncController's mirror follows.
  - *Counter version 2.* The ratchet no longer counts the coordinators' reviewed false positives and does count
    `storageDone`: 678 (v1) = 635 (v2), re-baselined with a justification line. C-B1's projection is 635 → 619
    (678 → 662 under v1).

## Amendment 2026-10-13: session values stay explicit parameters (FR-042 exception)

User decision of 2026-10-13, after T064 (C-A3) stopped before code. Nothing above that is not marked changes.

- **The finding.** `StateIngest` has no parameterless member for a coordinator's `mptStorage`. The spawn sites pass the
  core's per-pivot cached instance (`getOrCreateMptStorage`), while `trieStore(pivot)` returns a new `MptStorage` per
  call on the Hash scheme (ETC) and must keep the core's cache as its only caller (D-T060-1 as built). Substituting it
  would change the store a coordinator writes to, and the adoption gate maps a dropped parameter only to a
  parameterless member. ARC's `storageDone` is a per-spawn `Option` (`Option.when(recordStorageDone)(...)`), which
  `stateIngest.storageDone` cannot express.
- **The decision.** The pivot-bound `MptStorage` and the per-spawn `Option[SnapStorageDoneStorage]` are session values,
  not capabilities. They stay explicit `apply` parameters (Impl constructor, `ChildFactories`, spawn sites), reached by
  the module traits through the coordinator's `<Coordinator>Env`, and the ratchet keeps counting them. Everything else
  adopts `StateIngest`. It is a named FR-042 exception covering C-A3, C-A4, C-S2 and C-H1 (tasks.md, Coordinators,
  "Amendment 2026-10-13"); SC-009 reads with it. ADR INF-006 decision 1 has a note.
- **C-A3 as built (T064).** Adopts `pathNodeStorage` → `pathNodes`; `mptStorage` and `storageDone` unchanged. Total
  619 → 609 (the characterization's 606 for this route, plus the explicit `mptStorage` in `AccountRangeEnv`).
- **The Env declaration cost (user decision 2026-10-13, found at T065; tightened after the #1591 reviews).** Declaring
  a session value on a coordinator's Env counts (the declaration and its import). A slice may raise the total for it
  only if: the value is on the closed list (`mptStorage`, pivot-bound; `storageDone`, the per-spawn `Option`; any
  other needs a new user decision); the rise is computed net of the slice's own adoptions, applied first; the rise
  equals exactly the Env file's count increase for that declaration and its import; and a fresh
  `justifications["storage_refs.total"]` line names it and states the amount, the previous line kept as
  `justifications["storage_refs.total.history"]`. Nothing else may raise the total (tasks.md, Coordinators).
- **C-A4 as built (T065).** No adoptable store: the replay's `storageDone` and `mptStorage` are the two session
  values. `AccountRangeEnv` declares `storageDone` (+3); total 609 → 612, justified.
