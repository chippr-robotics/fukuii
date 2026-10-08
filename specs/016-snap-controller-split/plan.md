# Implementation Plan: Split SNAPSyncController and the SNAP Coordinators into Phase Modules

**Branch**: `docs/spec-016-snap-split` (this plan). Each slice has its own branch, listed in `tasks.md`.

**Date**: 2026-10-08

**Spec**: [spec.md](./spec.md) · **Phase 0**: [research.md](./research.md) · **Routing doc (draft)**:
[`.claude/agent-protocols/snap-sync.md`](../../.claude/agent-protocols/snap-sync.md)

## Summary

Five Pekko Typed actor classes (14,854 lines on `staging@9a6681cc0`) are split into phase modules, with no change in
behaviour. The work runs in this order:

1. **Pins and characterization (S0).** Pin tests for the five #1385 fixes, golden-bytes tests for every on-disk
   format, and characterization tests for the stagnation watchdog, the resets and pivot refresh. These land before
   any code moves.
2. **Prerequisites (P1–P4)** remove the coupling the Phase 0 matrix found:
   - P1 `CoordinatorHandles`;
   - P2 `PhaseFlags.reset(kind)`;
   - P3 a single peer-event handler;
   - P4 per-phase arms in `syncing`.
3. **Controller modules (M1–M11)**, moved one per PR, lowest coupling first, into self-typed traits with identical
   bodies.
4. **Coordinators**: the same method, after a short per-coordinator characterization.
5. **R1**: DFS → BFS wording, after the moves.

No extraction PR merges before PR #1501 (spec 014). The live Sepolia node is not used to validate any slice.

## Technical Context

**Language/Version**: Scala 3.x LTS, JDK 25

**Primary Dependencies**: Apache Pekko Typed (actors, `TimerScheduler`, `ManualTime` in tests), RocksDB via `DataSource`

**Storage**: unchanged. `AppStateStorage`, `SnapSyncProgressStorage`, `SnapStorageDoneStorage`,
`HealingFrontierStorage` (CF `g`), `BfsQueueStorage`, and the task files in the datadir. All of them are frozen
(FR-030).

**Testing**: ScalaTest + Pekko `ActorTestKit`/`ManualTime`, run by CI only (FR-035)

**Target Platform**: the fukuii node on Linux, both chain families (ETC/Mordor: Hash scheme, PoW; ETH: Path scheme,
PoS + CL pivot)

**Project Type**: a single sbt project. Only the root `main` module is touched.

**Performance Goals**: no measurable change. Mixing in traits adds no dispatch cost beyond the existing virtual calls.

**Constraints**:
- no local sbt or JVM;
- no on-disk format change;
- no assertion change in existing suites;
- one module per PR.

**Scale/Scope**: about 42 PRs; about 11,500 lines moved (see Effort)

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
| VII. Versioning / ADR | **Pass, pending** | Each PR uses a conventional prefix (`refactor(snap):`, `test(snap):`) and references #1401. Whether an ADR is needed is an open question (spec Open Questions 3). |

Re-check after design: no new violations. The trait-mixin form (D1) is a deliberate intermediate step and is logged
below.

## Design decisions

### D1 — Module form: self-typed traits, with identical bodies

Each controller module becomes a trait that is mixed into `SNAPSyncControllerImpl`:

```scala
// snap/controller/SnapPeerPool.scala
package com.chipprbots.ethereum.blockchain.sync.snap.controller
private[snap] trait SnapPeerPool:
  self: SNAPSyncControllerImpl =>
  // the module's exclusive vars and its defs, bodies byte-identical to the baseline
```

**Why**: FR-016 requires grep-verifiable identical bodies. A trait whose self-type is the impl class can refer to
every member it referred to before, so bodies need no `state.` prefixes or extra parameters.

**Rejected alternative**: separate classes with an explicit context object. Every body would change, so a reviewer
could not check the move mechanically, and that is exactly the risk this series has to avoid. The traits can be
narrowed later (see "narrowing commit" below) and turned into classes when the explicit dependencies are small, but
that is outside this spec.

**Rules for a move commit**:

- **What may change:**
  - the `trait` header and file;
  - imports;
  - `private` → `private[snap]` on any member that another module or the core calls;
  - an explicit result type added where scalafix demands one on a widened member.
- **What must not change:** any body.
- **What moves with the module:**
  - vars it owns exclusively (research.md R3, "exclusive" and "one module + syncing arms" once P4 has moved the arms);
  - the `syncing` arms for its Commands;
  - its timer keys.
- **What stays in the core:** the shared hubs (`pivotBlock`, `stateRoot`, `currentPhase`, `PhaseFlags`,
  `CoordinatorHandles`, `progressMonitor`, `requestTracker`, the storages).

**Initialization order (hazard).** Trait bodies run **before** the class body, but after the class's constructor
parameters are assigned. A var or val moved into a trait whose initializer reads a **class-body** member would see
`null` or `0`. Constructor parameters are fine.

**Rule**: a declaration moves only if its initializer is a literal, a constructor parameter or a companion constant.
Anything else stays in the core and is listed in the PR. Every controller spec constructs the controller, so a
mistake shows up as a construction-time NPE across the whole suite, not as a silent fault. Turning on
`-Wsafe-init` for the snap package is an option for the first M slice (T020 decides).

**Optional narrowing commit, in the same PR, after the move**: replace `self: SNAPSyncControllerImpl =>` with the
narrowest intersection of capability traits that compiles, for example
`self: SnapControllerCore & CoordinatorHandlesAccess =>`. The compiler then documents the module's real dependencies.
If narrowing needs more than trivial capability traits, skip it and list the dependencies in the PR body.

**Coordinators** follow the same pattern. The traits live in sub-packages:
- `snap/actors/account/`
- `snap/actors/storage/`
- `snap/actors/healing/`
- `snap/actors/bytecode/`

Each is mixed into its `*CoordinatorImpl`. The coordinator files and their public Command ADTs stay where they are,
so no import outside the snap package changes.

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

**Why not separate `Behavior`s now**: `currentPhase` has 11 writers outside `syncing`. Each writer would also have to
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

**Proposed** (spec Open Questions 2): tag `snap-split-base` at the staging commit just before P1 merges. Sepolia
hotfixes branch from that tag, ship as fix-only builds, and are then forward-ported to staging. Pin and
characterization tests are anchored by symbol, so a forward-port finds the moved code through the routing doc.

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

Commands that sit on a boundary (for example `BootstrapComplete`, which has three different implementations) go to
the module that owns the implementation for that behaviour. The table lists each case.

## Coordinator modules

Boundaries are preliminary (research.md R12) and are confirmed by T060 before C-B1.

| # | Coordinator → trait / file | Contents | Notes | Est. lines |
|---|---|---|---|---|
| C-B1 | ByteCode → `actors/bytecode/ByteCodeDispatch.scala`, `ByteCodeResponseHandling.scala` | cooldown, response bytes, assign/dispatch/redispatch; `handleByteCodesResponse`, `validateReturnedCodes`, `storeBytecodesWithHashes`, `filterAndDedupeCodeHashes`, the #1501 `skipPresent` check | one PR (coordinator is 870 lines) | ~350 |
| C-A1 | Account → `actors/account/AccountPeerDispatch.scala` | stateless/snapless marking, cooldown, response bytes, `inFlightForPeer`, `dispatchIfPossible`, backpressure + #1501 intake gate | `[ACCOUNT-COOLDOWN]`, `[ACCOUNT-IDLE]` | ~350 |
| C-A2 | Account → `actors/account/AccountTaskLifecycle.scala` | `handleTaskComplete`/`Failed`, `requeueOrEscalate`, redispatch, 32-byte hash math, `completeEmptyTaskRange`, `classifyIdleStall` use | site of "stale-pivot empty ranges as scarcity" | ~350 |
| C-A3 | Account → `actors/account/AccountTrieAssembly.scala` | `getOrCreateTaskStackTrie`, `handleStoreAccountChunk`, `finalizeTrie`, `finalizing` behaviour | StackTrie resume (#1487) | ~300 |
| C-A4 | Account → `actors/account/ContractWorkCarry.scala` | `identifyContractAccounts`, task-file writing, `syncTaskFiles`, `replayCarriedChunk`, `readContractFile`, `readUniqueCodeHashes`, `copyPrefix` | **vault**; #1453/#1487/#1498 and #1501 invariants | ~350 |
| C-S1 | Storage → `actors/storage/StoragePeerDispatch.scala` | stateless, `SnapPeerHealth`, cooldown, batch sizing, response bytes, `selectFloorPeer` use, `redispatchEligible` | `[STORAGE-PEER-HEALTH]`, `[STORAGE-FLOOR]` | ~350 |
| C-S2 | Storage → `actors/storage/StorageOrderedApply.scala` | `ReadyStorageChunk` ordering, `drainOrderedStorageChunks`, `applyReadyStorageChunk`, per-account trie get/commit/**reset** | **vault**; site of the "pivot refresh discards in-flight tries" task | ~400 |
| C-S3 | Storage → `actors/storage/StorageDoneMarkers.scala` | `recordFlatBatchDone`, `stageDoneMarker`, `takeDoneMarkersFor`, `stageFlatSlotChunk`, `flushPendingFlatBatch`, `recordCompletedTask` | **vault**; spec 010 R2a is kept word for word (a record is never durable before its flat slots) | ~170 |
| C-S4 | Storage → `actors/storage/StorageResponseProcessing.scala` | `handleResponse`, `processStorageRanges`, `processServedTasks`, `createStorageSubTasks`, `recordSubtaskCompletion`, stale-root give-up | `[STORAGE-FORCE-COMPLETE]` | ~450 |
| C-H0 | Healing: split `activeBehavior` (821 lines) into per-concern partial functions inside the file (the D3 pattern) | arm-order table as in P4 | prerequisite for C-H1…H5 | ~820 restructured |
| C-H1 | Healing → `actors/healing/FrontierPersistence.scala` | `persistFrontier`, `unpersistFrontier`, `clearPersistedFrontier`, `awaitFrontierDrain`, `writeDurableSubtreeRecords`, `flushRawNodes*`, gauges | **vault**; CF `g` format frozen | ~250 |
| C-H2 | Healing → `actors/healing/FrontierRebuildBfs.scala` | `rebuildFrontierBFS`, `startFrontierBFS`×2, visit keys, `boundedVisitedSet`, `computeEffectiveParallelism` | site of #1350 and #1349 | ~500 |
| C-H3 | Healing → `actors/healing/HealVerification.scala` | `reverifyCurrentRoot`, `startVerificationBFS`, `startScopedVerification`, pruned verification | `[HEAL-VERIFY*]`; spec 003/005 | ~200 |
| C-H4 | Healing → `actors/healing/HealDispatch.scala` | cooldown, response bytes, `dispatchIfPossible`, `requestNextBatch`, `handleTimeout`, redispatch, `noteUnservableAttempt`, `updateHealThrottle` | `[HEAL-FLOOR]`, `[HEAL-WATCHDOG]` | ~350 |
| C-H5 | Healing → `actors/healing/HealResponseProcessing.scala` | `processActiveResponse`, `discoverMissingChildren`, `queueNodes`, healed code hashes, `isComplete` | site of the heal-convergence latch | ~400 |
| C-X | `snap/AdaptiveResponseBytes.scala` shared by ARC, SRC, TNHC | the three identical windows, with the log label as a parameter (R12) | ByteCode excluded (NG-5) | −60 net |

## Slice list, order and estimates

| Order | Slice | Kind | Est. lines (main / test) | Reviewers | Validation beyond CI |
|---|---|---|---|---|---|
| 1 | **S0a** #1367 pin (NPMA) | test | 0 / ~80 | herald | — |
| 2 | **S0b** controller pins #1378, #1319×2, spec-005 (+#1502 doc), #1371 + `ChildFactories` seam | tests + seam | ~60 / ~350 | prism, forge | — |
| 3 | **S0c** golden-bytes for the frozen formats | test | 0 / ~300 | vault | — |
| 4 | **S0d** `SnapControllerFixture` + characterization (stagnation, reset, pivot refresh) | test | 0 / ~900 | prism, forge, beacon (CL pivot cases) | — |
| — | **gate: #1501 merged** (T001 re-checks R11) | | | | |
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
| 21 | **M8** Pivot selection (+ separate `isStarting` cleanup commit) | move | ~700 | prism, beacon, forge | Platåberget start from scratch |
| 22 | **M9** Lifecycle/reset + dormant | move | ~390 | prism, vault, forge | Mordor dormant/wake |
| 23 | **M10** Pivot refresher | move | ~570 | prism, beacon, forge | Platåberget across ≥ 3 refreshes; Mordor |
| 24 | **M11** Download supervisor | move | ~850 | prism, forge | full Mordor SNAP |
| 25 | **T060** coordinator characterization (docs) | docs | — | — | — |
| 26 | **C-B1** | move | ~350 | prism | — |
| 27–30 | **C-A1…A4** | move | ~1,350 | prism; **vault** on A3 and A4 | Mordor restart mid-account after A4 |
| 31–34 | **C-S1…S4** | move | ~1,370 | prism; **vault** on S2 and S3 | Mordor + Platåberget restart mid-storage after S3 |
| 35–40 | **C-H0…H5** | restructure + move | ~2,520 | prism; **vault** on H1 and H3 | Mordor heal restart after H1 |
| 41 | **C-X** `AdaptiveResponseBytes` | dedupe | −60 | prism | — |
| 42 | **R1** DFS→BFS wording + routing-doc final check (T090) | rename | ~40 | prism | — |

## Move verification (every move commit)

1. **Moved blocks only.** Run
   `git diff --color-moved=plain --color-moved-ws=allow-indentation-change HEAD~1 HEAD -- <snap paths>`. Every
   removed line in the old file must show as moved. The only non-moved lines allowed are trait headers, imports,
   `private`→`private[snap]` and scalafix result types.
2. **Identical bodies.** For each `def` in the PR's symbol list, extract its body from `HEAD~1:old-file` and from
   `HEAD:new-file`, strip the visibility modifier, and `diff` them. The diff must be empty. The PR body lists the
   symbols and the command used.
3. **No stray assertions.** Run `git diff HEAD~1 HEAD -- src/test | grep -E '^[+-].*(should|must|shouldBe|assert|expect)'`.
   It must be empty, apart from lines whose only change is an import or a fixture constructor.
4. **No format or config drift.** `git diff HEAD~1 HEAD -- src/main/resources` must be empty. The S0c goldens and the
   pin tests must be untouched.

A small script under `scripts/snap-split/` may automate steps 2 and 3. It is added in the first M PR, and
`prism` reviews it.

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

**R-2: initialization order in traits.** See D1. This is caught at construction by every controller spec, and the
rule keeps non-trivial initializers in the core.

**R-3: arm order in P4 and C-H0.** A reordered arm silently changes which handler wins. Mitigations: the arm-order
table in each PR, S0d characterization tests, and keeping P4 as five small PRs.

**R-4: #1501 churn.** #1501 is still open. If it changes the controller again, T001 re-maps R11 before P1. The S0
slices do not depend on #1501.

**R-5: hotfix forward-ports** while Sepolia is on fix-only builds (D6). Mitigations: symbol-anchored pins and the
routing doc. A move PR that conflicts with a hotfix is redone from the new base, not hand-merged.

**R-6: reviewer fatigue on large moves** (M5, M11, C-H0 are 800+ lines each). Mitigation: the verification recipe
makes the review mechanical, and the reviewer reads only the non-moved lines.

**R-7: log-scraping consumers** (dashboards, the `fukuii-log-triage` skill) depend on log text. FR-031 keeps the text
unchanged, so only the source file a line comes from changes.

## Effort estimate

- **PRs**: 42. That is 4 S0, 8 prerequisites (P1, P2, P3, P4a–e), 12 controller (M1–M11 with M6 split in two),
  1 coordinator characterization, 15 coordinator, 1 C-X and 1 R1.
- **Lines**: about 1,630 test lines added in S0, about 6,000 controller lines moved (`syncing` arms move twice: P4 and
  then M), and about 5,500 coordinator lines moved. Net `src/main` growth is small: trait headers, handles and
  `PhaseFlags`, less the deduplication.
- **Time**: about 0.5–1 agent-day per move PR, plus review and CI. P2, P4 and M10 need 2–3 days each. With two
  specialist reviews per PR and Mordor/Platåberget runs on about 14 slices, a realistic pace is 4–6 merges a week:
  **about 8–11 weeks** for the whole series. The controller (slices 1–24) is roughly the first 5–6 weeks.

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
| Constitution V: no local `sbt pp` before a PR | The only host is shared with the live Sepolia node; sbt next to the soak swaps it and breaks SNAP (memory: no builds during soak). | Running locally risks the live node. CI runs the same gates (`compile-all`, `scalafmtCheckAll`, Tier 1/2), and a PR is opened only after CI is green (FR-035). This is the precedent from spec 014. |
| Constitution III: extraction PRs ship no new assertions | They change no behaviour, and FR-032 forbids assertion edits so the old suites stay a valid oracle. | Adding tests inside move PRs would mix oracle changes with code moves. The tests land in S0 instead. |
| `src/main` change in a "test" slice (S0b `ChildFactories`) | Pins #1378, #1319 and #1371 cannot be observed without a spawn seam, because the impl class is private. | A test-only reflection hack is brittle and not idiomatic. A constructor seam with a production default has precedent (`validatorFactory`). |
| Trait mixins (D1) as an intermediate form, rather than classes with explicit interfaces | Body-identical, mechanically checkable moves. | Classes change every body, so moves could not be verified. |
| Five P4 PRs instead of one | Arm-order mistakes are silent. Smaller diffs keep the arm-order table reviewable. | One 1,150-line restructure is too large to review for arm order. |
