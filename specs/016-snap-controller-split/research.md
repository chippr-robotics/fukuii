# Phase 0 Research: SNAPSyncController and Coordinator Characterization (spec 016, #1401)

**Status**: Complete for the controller; preliminary for the four coordinators (finished per coordinator in task
T060, before the first coordinator slice).

**Source**: the static characterization report `/home/dontpanic/work/snap-characterization.md` (written 2026-10-08
against `origin/staging`). This file copies the matrices and verdicts that the spec, plan and tasks depend on, so the
spec still reads correctly if that working file goes away. Additions made while writing the spec are labelled
**[016]**.

## R0. Baseline, line anchors and method

- **Baseline commit**: `staging@9a6681cc0` (`chore: bump version to 0.9.7`). All line numbers below (`L####`) refer to
  `src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/SNAPSyncController.scala` at that commit, unless a path
  is given.
- **Line numbers will drift.** PR #1501 (spec 014) changes the controller by +229/−96. The file grows from 6,646 to
  6,779 lines, and every line after L83 moves. Slices, pin tests and review checklists identify code by **symbol and
  grep pattern**, never by line number alone. The line numbers here are only for reading this document against the
  baseline commit.
- **Method** (from the report): every result is from static inspection only. No test was run, no sbt or java was
  started, and no source was edited. The matrices are derived with regular expressions: each occurrence is attributed
  to its enclosing top-level `def` or behaviour, and closures inside a behaviour count towards that behaviour. `xN`
  means N occurrences. High-traffic vars were spot-checked by hand.

## R1. Measured size (the issue's "~2k lines" is stale)

| File | staging@9a6681cc0 | after PR #1501 (`fix/snap-memory-bounds`) |
|---|---|---|
| `snap/SNAPSyncController.scala` | **6,646** (impl class L53–5669 ≈ 5,600; companion and config ≈ 980) | 6,779 |
| `snap/actors/TrieNodeHealingCoordinator.scala` | 2,900 | 2,900 |
| `snap/actors/StorageRangeCoordinator.scala` | 2,273 | 2,288 |
| `snap/actors/AccountRangeCoordinator.scala` | 2,193 | 2,195 |
| `snap/actors/ByteCodeCoordinator.scala` | 842 | 870 |
| **Total in scope** | **14,854** | **15,032** |

PR #1501 also adds `snap/SnapIntakeBudget.scala` (161), `snap/SnapHeapWatchdog.scala` (156) and
`snap/GatedTaskFileReplay.scala` (59). All three are already separate files; this spec places them and does not split
them (R11).

## R2. Headline findings

- **`syncing` (L953–2102, 1,150 lines) is one behaviour that serves five phases.** It touches 56 of the 82 mutable
  items (68%) and writes 44 of them. It reads `currentPhase` 37 times, with 28 `currentPhase ==` tests and 17
  `case … if currentPhase == X` guards. The phases are AccountRangeSync, ByteCodeAndStorageSync, StateHealing,
  StateValidation and ChainDownloadCompletion. **[016]** The whole file has 38 `currentPhase ==` tests; the 28 cited
  in this spec are the ones inside `syncing`.
- **The real coupling hubs are the reset methods**:
  - `restartSnapSync` (L4866, 85 lines) touches 29 vars and writes 28.
  - `wakeFromDormant` (L3387) touches 18 and writes 17.
  - `enterDormantMode` (L3339) touches 14 and writes 13.
  - `startSnapSync` writes 17.

  Each one hand-rolls a flag/cursor/validation/probe reset. They also repeat the same four `ctx.stop(...)` plus
  `= None` lines (L3358–3361, L4897–4900, and `stopStateSyncChildren` L782–790).
- **`startSnapSync` (L2585–3293, 709 lines) is the largest method.** `handleCLPivotHint` is only 23 lines
  (L2562–2584). `startSnapSync` mixes several concerns:
  - accounts-complete recovery (~305 lines);
  - the CL-wait gate;
  - CL, genesis, local and network pivot selection;
  - bootstrap resume;
  - an inline copy of the bytecode and storage coordinator spawns (L2719, L2743), which duplicates
    `launchAccountRangeWorkers` (L3767, L3795).
- **Shared state: 18 of the 82 mutable items belong to one module only, 19 more belong to one module plus `syncing`
  arms, and 45 are shared across 2 or more modules.** If the reset trio is ignored, only 34 are shared. The shared
  hubs are:
  - `pivotBlock` (10 modules), `stateRoot` (9), `currentPhase` (8);
  - `progressMonitor` (7), `requestTracker` (7);
  - the five child refs.
- **None of the five fixes restored by #1385 has a test that fails if the fix is dropped.** Fix #5 has only a weak,
  partial test (R7).
- **Two defects found while reading, now filed**: #1502 (`prunedHealVerification` is never forwarded to
  `TrieNodeHealingCoordinator`) and #1503 (accounts-complete recovery reuses the saved pivot unchecked when
  `networkBest == 0`). Both are **placed** by this spec (R10), not fixed.

Per-method write fan-out (distinct vars touched / written): syncing 56/44, restartSnapSync 29/28,
completePivotRefreshWithStateRoot 21/12, startSnapSync 20/17, launchAccountRangeWorkers 19/16, wakeFromDormant 18/17,
enterDormantMode 14/13, startStateHealing 10/6, maybeRestartIfAccountStagnant 9/5, maybeRestartIfStorageStagnant 8/5,
refreshPivotInPlace 8/4, finalizeSnapSync 8/3, startStateHealingWithInterleave 7/4, maybeRequestHealingServeRoot 7/2,
bootstrapping 7/4, handleCLPivotHint 3/2. Everything else touches at most 6.

## R3. Var × module matrix (verdicts)

There are 82 items: 76 `private var`s and 6 mutable-holder `val`s (`requestTracker`, `progressMonitor`,
`healedCodeHashes`, `failedPivotBlocks`, `healingWalkLocalOnly`, `snapServerPeerLastConnectAttemptMs`).
**[016]** After #1501 there is an 83rd: `private var heapWatchdog: Option[SnapHeapWatchdog.Handle]`. It is written only
by `ensureHeapWatchdog` (at the two coordinator-spawn sites) and `stopHeapWatchdog` (in `onStop` and
`stopSnapOnlySchedules`), so it is exclusive to Lifecycle (§R12).

Each var below is assigned to the modules that touch it. The module abbreviations mean:

| Module | Code it covers |
|---|---|
| PEER | peer helpers: L139–240, 557–689, 716, 3471–3527, 4137, 4251–4301 |
| PIVOT-SEL | `idle`, `bootstrapping`, `handleCLPivotHint`, `startSnapSync`, bootstrap-retry, `calibratePivotTD`/`updateBestBlockForPivot` |
| PIVOT-REFRESH | `refreshPivotInPlace` + `completePivotRefreshWithStateRoot` |
| DOWNLOAD | `launchAccountRangeWorkers`, `startAccountRangeSync`, `request*` ticks, `stopStateSyncChildren`, `checkAllDownloadsComplete`, `restartSnapSync`, `currentSyncStatus` |
| STAGNATION | `scheduleStagnationChecks` + `maybeRestart*` / `maybeForce*` |
| HEALING | L3915–4250 + `triggerHealing`, the `queueHealedCode` family, `anchorPivotBeforeLazyHandoff` |
| VALIDATION | `validateState` + `spawn*Validation` |
| FINALIZE | `completeSnapSync` … `launchChainDownloader`, `completed`, `completedWithBackfill` |
| PERSIST | `clearStorageDoneMarkers`, `sweepSupersededTaskFiles`, `deserializeSnapProgress` |
| DORMANT | `recordCriticalFailure`, `enterDormantMode`, `wakeFromDormant`, `dormantRetry` |

This table counts the reset trio as part of DOWNLOAD and DORMANT, which is why 45 items show as shared.

| var (decl line) | modules touching (W=writes) | verdict |
|---|---|---|
| lastSnapExclusions (146) | PEER(W) | exclusive: PEER |
| lastSnapExclusionLogMs (147) | PEER(W) | exclusive: PEER |
| mptStorage (209) | DORMANT(W), DOWNLOAD(W), HEALING(W), PIVOT-SEL(W) | SHARED(4) |
| accountRangeCoordinator (260) | DORMANT(W), DOWNLOAD(W), PEER, PIVOT-REFRESH, STAGNATION, SYNCING | SHARED(6) |
| bytecodeCoordinator (263) | DORMANT(W), DOWNLOAD(W), HEALING(W), PEER, PIVOT-REFRESH, PIVOT-SEL(W), STAGNATION, SYNCING | SHARED(8) |
| storageRangeCoordinator (265) | DORMANT(W), DOWNLOAD(W), PEER, PIVOT-REFRESH, PIVOT-SEL(W), STAGNATION, SYNCING | SHARED(7) |
| trieNodeHealingCoordinator (268) | DORMANT(W), DOWNLOAD(W), HEALING(W), PEER, PIVOT-REFRESH, SYNCING | SHARED(6) |
| chainDownloader (272) | FINALIZE(W), PEER, PIVOT-REFRESH, SYNCING | SHARED(4) |
| chainDownloadComplete (273) | FINALIZE(W), SYNCING(W) | FINALIZE + syncing-arms |
| headerHold (276) | FINALIZE(W), SYNCING | FINALIZE + syncing-arms |
| lastHeaderHoldWarnMs (277) | FINALIZE(W) | exclusive: FINALIZE |
| holdLastCursor (279) | FINALIZE(W) | exclusive: FINALIZE |
| holdLastAdvanceMs (280) | FINALIZE(W) | exclusive: FINALIZE |
| coordinatorGeneration (284) | DORMANT(W), DOWNLOAD(W), FINALIZE(W), HEALING(W), PIVOT-SEL(W) | SHARED(5) |
| clPivotHint (289) | FINALIZE, HEALING, PIVOT-REFRESH, PIVOT-SEL(W) | SHARED(4) |
| minPivotHint (293) | PIVOT-SEL(W) | exclusive: PIVOT-SEL |
| clHintArrivedAtMs (294) | PIVOT-SEL(W) | exclusive: PIVOT-SEL |
| currentPhase (305) | DORMANT(W), DOWNLOAD(W), FINALIZE(W), HEALING(W), PIVOT-REFRESH, PIVOT-SEL(W), STAGNATION, SYNCING(W) | SHARED(8) |
| pathPublish (308) | FINALIZE(W), SYNCING(W) | FINALIZE + syncing-arms |
| pivotBlock (309) | DORMANT(W), DOWNLOAD(W), FINALIZE, HEALING, PERSIST, PIVOT-REFRESH(W), PIVOT-SEL(W), STAGNATION, SYNCING, VALIDATION | SHARED(10) |
| stateRoot (310) | DORMANT(W), DOWNLOAD(W), FINALIZE(W), HEALING(W), PIVOT-REFRESH(W), PIVOT-SEL(W), STAGNATION, SYNCING(W), VALIDATION | SHARED(9) |
| preservedRangeProgress (317) | DOWNLOAD(W), PIVOT-REFRESH, SYNCING(W) | SHARED(3) |
| preservedAtPivotBlock (318) | DOWNLOAD(W), PIVOT-REFRESH(W), SYNCING(W) | SHARED(3) |
| preservedTaskFiles (322) | DOWNLOAD(W), SYNCING(W) | DOWNLOAD + syncing-arms |
| launchedAccountGeneration (324) | DOWNLOAD(W), SYNCING | DOWNLOAD + syncing-arms |
| currentCarrySource (326) | DOWNLOAD(W), SYNCING | DOWNLOAD + syncing-arms |
| lastSweptForRecord (328) | SYNCING(W) | exclusive: SYNCING |
| resumedStaleCursors (354) | DOWNLOAD(W) | exclusive: DOWNLOAD |
| accountsComplete (358) | DORMANT(W), DOWNLOAD(W), PIVOT-SEL(W), SYNCING(W) | SHARED(4) |
| bytecodePhaseComplete (361) | DORMANT(W), DOWNLOAD(W), PIVOT-SEL(W), STAGNATION, SYNCING(W) | SHARED(5) |
| storagePhaseComplete (362) | DORMANT(W), DOWNLOAD(W), PIVOT-SEL(W), STAGNATION, SYNCING(W) | SHARED(5) |
| storagePhaseForceCompleted (363) | DORMANT(W), DOWNLOAD(W), PIVOT-SEL(W), SYNCING(W) | SHARED(4) |
| awaitingHealedCode (372) | FINALIZE(W), HEALING(W), SYNCING(W) | SHARED(3) |
| healedCodeWaitExhausted (373) | FINALIZE, HEALING(W), SYNCING(W) | SHARED(3) |
| bytecodeForceCompleted (375) | FINALIZE, HEALING(W), STAGNATION(W) | SHARED(3) |
| criticalFailureCount (380) | DORMANT(W), SYNCING(W) | DORMANT + syncing-arms |
| accountsAtLastCriticalFailure (381) | DORMANT(W), SYNCING(W) | DORMANT + syncing-arms |
| dormantRetryCount (386) | DORMANT(W), SYNCING(W) | DORMANT + syncing-arms |
| validationRetryCount (391) | SYNCING(W) | exclusive: SYNCING |
| validationInProgress (403) | DORMANT(W), DOWNLOAD(W), SYNCING(W), VALIDATION(W) | SHARED(4) |
| validationGeneration (404) | DORMANT(W), DOWNLOAD(W), PIVOT-REFRESH(W), SYNCING(W), VALIDATION(W) | SHARED(5) |
| healingValidatedRoot (414) | DORMANT(W), DOWNLOAD(W), HEALING(W), SYNCING(W), VALIDATION(W) | SHARED(5) |
| bytecodesEstimatedTotal (421) | DORMANT(W), DOWNLOAD(W), SYNCING(W) | SHARED(3) |
| bootstrapRetryCount (424) | DORMANT(W), PIVOT-SEL(W) | SHARED(2) |
| lastPivotRestartMs (430) | STAGNATION(W), SYNCING(W) | STAGNATION + syncing-arms |
| lastProactivePivotBlock (437) | PIVOT-REFRESH(W), SYNCING | PIVOT-REFRESH + syncing-arms |
| pivotProbeRequestId (453) | DORMANT(W), DOWNLOAD(W), PIVOT-REFRESH(W), SYNCING(W) | SHARED(4) |
| proactiveRollNeedsProbe (454) | DORMANT(W), DOWNLOAD(W), PIVOT-REFRESH(W), SYNCING(W) | SHARED(4) |
| pendingProbeCommit (455) | DORMANT(W), DOWNLOAD(W), PIVOT-REFRESH(W), SYNCING(W) | SHARED(4) |
| lastProbeAttemptMs (456) | DOWNLOAD(W), SYNCING(W) | DOWNLOAD + syncing-arms |
| probeAttemptCount (458) | DORMANT(W), DOWNLOAD(W), SYNCING(W) | SHARED(3) |
| consecutivePivotRefreshes (468) | DORMANT(W), PIVOT-REFRESH(W), SYNCING(W) | SHARED(3) |
| pendingPivotRefresh (481) | DORMANT(W), DOWNLOAD(W), HEALING, PIVOT-REFRESH(W), SYNCING(W) | SHARED(5) |
| pendingDisconnectedPeers (486) | PEER(W) | exclusive: PEER |
| storageStagnationRefreshAttempted (488) | STAGNATION(W), SYNCING(W) | STAGNATION + syncing-arms |
| forceCompleteStorageSent (493) | DORMANT(W), DOWNLOAD(W), PIVOT-SEL(W), STAGNATION(W) | SHARED(4) |
| trieWalkInProgress (494) | HEALING(W), SYNCING(W) | HEALING + syncing-arms |
| healingRoundCount (495) | HEALING(W), SYNCING(W) | HEALING + syncing-arms |
| healingServeRootRequestInFlight (501) | DOWNLOAD(W), HEALING(W), SYNCING(W) | SHARED(3) |
| lastHealingServeRootBlock (505) | DOWNLOAD(W), HEALING(W), SYNCING(W) | SHARED(3) |
| healRepegNoRootAttempts (516) | HEALING(W), PIVOT-REFRESH(W) | SHARED(2) |
| retryRefreshCounts (520) | PIVOT-REFRESH(W), SYNCING(W) | PIVOT-REFRESH + syncing-arms |
| snapPeerEvictionStarted (528) | PEER(W) | exclusive: PEER |
| snapServerPeersSchedulerStarted (531) | PEER(W) | exclusive: PEER |
| fruitlessEvictionCycles (536) | PEER(W) | exclusive: PEER |
| lastEvictionSnapCount (537) | PEER(W) | exclusive: PEER |
| bestEth68PeerForCalibration (545) | PEER(W), PIVOT-SEL | SHARED(2) |
| storageTailBaseline (674) | PIVOT-SEL(W), STAGNATION(W), SYNCING(W) | SHARED(3) |
| lastStorageProgressMs (677) | PIVOT-SEL(W), STAGNATION(W), SYNCING(W) | SHARED(3) |
| lastBytecodeProgressMs (678) | STAGNATION(W), SYNCING(W) | STAGNATION + syncing-arms |
| lastBytecodeProgressCount (679) | STAGNATION(W), SYNCING(W) | STAGNATION + syncing-arms |
| lastAccountProgressMs (680) | DOWNLOAD(W), PIVOT-REFRESH(W), STAGNATION(W), SYNCING(W) | SHARED(4) |
| lastAccountTasksCompleted (681) | DOWNLOAD(W), STAGNATION(W) | SHARED(2) |
| lastAccountsDownloaded (682) | DOWNLOAD(W), STAGNATION(W) | SHARED(2) |
| storageContractProgressPct (685) | SYNCING(W) | exclusive: SYNCING |
| consecutiveAccountStallRefreshes (5038) | STAGNATION(W) | exclusive: STAGNATION |
| requestTracker (303) | DORMANT(W), DOWNLOAD(W), HEALING(W), PEER, PIVOT-REFRESH, PIVOT-SEL(W), SYNCING | SHARED(7) |
| healedCodeHashes (371) | FINALIZE, HEALING(W), SYNCING | SHARED(3) |
| progressMonitor (377) | DORMANT, DOWNLOAD, FINALIZE(W), HEALING, PEER, PIVOT-SEL, SYNCING(W) | SHARED(7) |
| failedPivotBlocks (476) | PIVOT-REFRESH, SYNCING(W) | PIVOT-REFRESH + syncing-arms |
| healingWalkLocalOnly (504) | DORMANT(W), DOWNLOAD(W), HEALING | SHARED(3) |
| snapServerPeerLastConnectAttemptMs (525) | PEER(W) | exclusive: PEER |

**Exclusive to one module (18)**:
- PEER (8): `lastSnapExclusions`, `lastSnapExclusionLogMs`, `pendingDisconnectedPeers`, `snapPeerEvictionStarted`,
  `snapServerPeersSchedulerStarted`, `fruitlessEvictionCycles`, `lastEvictionSnapCount`,
  `snapServerPeerLastConnectAttemptMs`.
- FINALIZE (3): `lastHeaderHoldWarnMs`, `holdLastCursor`, `holdLastAdvanceMs`.
- PIVOT-SEL (2): `minPivotHint`, `clHintArrivedAtMs`.
- DOWNLOAD (1): `resumedStaleCursors`.
- STAGNATION (1): `consecutiveAccountStallRefreshes`.
- `syncing` arms only (3): `lastSweptForRecord`, `validationRetryCount`, `storageContractProgressPct`.

**One module plus `syncing` arms (19)**:
- FINALIZE: `chainDownloadComplete`, `headerHold`, `pathPublish`.
- DOWNLOAD: `preservedTaskFiles`, `launchedAccountGeneration`, `currentCarrySource`, `lastProbeAttemptMs`.
- DORMANT: `criticalFailureCount`, `accountsAtLastCriticalFailure`, `dormantRetryCount`.
- STAGNATION: `lastPivotRestartMs`, `storageStagnationRefreshAttempted`, `lastBytecodeProgressMs`,
  `lastBytecodeProgressCount`.
- PIVOT-REFRESH: `lastProactivePivotBlock`, `retryRefreshCounts`, `failedPivotBlocks`.
- HEALING: `trieWalkInProgress`, `healingRoundCount`.

**How the state is handed between modules.** The behaviour methods are cheap to separate, because their private state
is clean. The state hand-off happens in three places:

1. The reset trio.
2. `syncing` arms that mutate cross-module state inline (44 writes).
3. Pivot refresh. It resets the stagnation clocks (`lastAccountProgressMs`, `healRepegNoRootAttempts`,
   `retryRefreshCounts`), the validation generation, `preserved*`, `pendingPivotRefresh` and the probe state. It also
   messages all four coordinators and the ChainDownloader.

### R3a. [016] Write sets of the reset paths (input to slice P2)

The table lists every item written by at least one reset path, derived from the R3 writer lists. "W" means the method
assigns the item somewhere in its body. Some of those writes reset to an initial value; others set a new value (for
example `pivotBlock` in `wakeFromDormant`). P2 starts by classifying each W as **reset** or **set**, and only the
**reset** writes go into `PhaseFlags.reset(kind)`.

| item | startSnapSync | restartSnapSync | wakeFromDormant | enterDormantMode | stopStateSyncChildren | completePivotRefresh… | launchAccountRangeWorkers |
|---|---|---|---|---|---|---|---|
| mptStorage | W | W | W |  |  |  | W |
| accountRangeCoordinator |  | W |  | W | W |  | W |
| bytecodeCoordinator | W | W |  | W | W |  | W |
| storageRangeCoordinator | W | W |  | W | W |  | W |
| trieNodeHealingCoordinator |  | W |  | W | W |  |  |
| coordinatorGeneration | W | W | W |  |  |  |  |
| clHintArrivedAtMs | W |  |  |  |  |  |  |
| currentPhase | W | W | W | W |  |  |  |
| pivotBlock | W | W | W |  |  | W |  |
| stateRoot | W | W | W |  |  | W | W |
| accountsComplete | W | W | W |  |  |  |  |
| bytecodePhaseComplete | W | W | W |  |  |  |  |
| storagePhaseComplete | W | W | W |  |  |  |  |
| storagePhaseForceCompleted | W | W | W |  |  |  |  |
| criticalFailureCount |  |  | W |  |  |  |  |
| dormantRetryCount |  |  |  | W |  |  |  |
| validationInProgress |  | W | W |  |  |  |  |
| validationGeneration |  | W | W |  |  | W |  |
| healingValidatedRoot |  | W | W |  |  |  |  |
| bytecodesEstimatedTotal |  | W | W |  |  |  |  |
| bootstrapRetryCount | W |  | W |  |  |  |  |
| pivotProbeRequestId |  | W |  | W |  | W |  |
| proactiveRollNeedsProbe |  | W |  | W |  | W |  |
| pendingProbeCommit |  | W |  | W |  | W |  |
| lastProbeAttemptMs |  | W |  |  |  |  |  |
| probeAttemptCount |  | W |  | W |  |  |  |
| consecutivePivotRefreshes |  |  | W |  |  | W |  |
| pendingPivotRefresh |  | W |  | W |  |  |  |
| forceCompleteStorageSent | W | W | W |  | W |  | W |
| healingServeRootRequestInFlight |  | W |  |  |  |  |  |
| lastHealingServeRootBlock |  | W |  |  |  |  |  |
| storageTailBaseline | W |  |  |  |  |  |  |
| lastStorageProgressMs | W |  |  |  |  |  |  |
| requestTracker | W | W |  | W |  |  | W |
| healingWalkLocalOnly |  | W |  | W | W |  |  |

**The paths do not reset the same sets.** For example:
- `wakeFromDormant` does not touch the probe state or `pendingPivotRefresh`; `enterDormantMode` and `restartSnapSync`
  do.
- Only `restartSnapSync` clears `lastHealingServeRootBlock` and `healingServeRootRequestInFlight`.
- `enterDormantMode` leaves the phase-complete flags alone.

Some of these differences may be bugs, but **this spec preserves them**. `reset(kind)` takes a per-kind field set
equal to today's. Any difference a reviewer thinks is a bug goes into CHASE-QUEUE as a follow-up and is not fixed in
P2 (FR-012).

The timer cancel lists are hand-copied in three places: `stopSnapOnlySchedules` L759–770 (12 cancels),
`enterDormantMode` L3347–3356 and `restartSnapSync` L4887–4894. They differ in length (8 to 12 keys), and P2 must keep
each list exactly as it is.

## R4. Command × behaviour matrix

The six behaviours are `idle`, `syncing`, `bootstrapping`, `completed`, `dormantRetry` and `completedWithBackfill`.
The cell values mean:

| Mark | Meaning |
|---|---|
| H | handled (real work) |
| D | explicit stale-drop (log and ignore) |
| `*` | guarded, e.g. `if currentPhase == …`, `if pendingPivotRefresh.isDefined`, `if !storagePhaseComplete`, generation mismatch |
| `-` | falls to the catch-all |

The catch-all policy differs per behaviour:
- `idle` has about 45 explicit drop arms and **no** catch-all.
- `syncing` and `bootstrapping` log "Unhandled message in … state".
- `completed`, `dormantRetry` and `completedWithBackfill` drop the message silently.

The table covers 76 distinct command names: the 63-case ADT plus the Progress*, Backpressure and chain-downloader
commands imported from Messages.scala.

| Command | idle | syncing | bootstrapping | completed | dormantRetry | completedWithBackfill |
|---|---|---|---|---|---|---|
| AccountCoordinatorProgress | D | H* | - | - | - | - |
| AccountRangeProgressCmd | D | H | - | - | - | - |
| AccountRangeResponse | D | H | - | - | - | - |
| AccountRangeSyncComplete | H | H | - | - | - | - |
| AccountTrieFinalizationFailed | H | H | - | - | - | - |
| AccountTrieFinalized | D | H | - | - | - | - |
| BootstrapComplete | H | H* | H | - | - | - |
| ByteCodeBackpressureChanged | D | H | - | - | - | - |
| ByteCodeCoordinatorProgress | D | H* | - | - | - | - |
| ByteCodeSyncComplete | H | H*+H* | - | - | - | - |
| ByteCodesResponse | D | H | - | - | - | - |
| CLPivotHint | H | H | H | - | H | - |
| ChainDownloaderDone | D | H | - | - | - | H |
| ChainDownloaderProgress | D | H | - | - | - | H |
| CheckDownloadStagnation | D | H | - | - | - | - |
| CheckSnapCapability | D | H | - | - | - | - |
| DelayedRestart | D | H | - | - | - | - |
| DormantWakeUp | D | - | - | - | H | - |
| EnsureSnapServerPeersConnected | D | H | - | - | - | - |
| EvictNonSnapPeers | D | H | H | - | - | - |
| FlushPeerDisconnects | H | H | H | - | H | - |
| GetProgress | H | H | H | H | H | H |
| GetStatus | H | H | H | H | H | H |
| HeaderHoldTick | - | H | - | - | - | - |
| HealedCodeHashes | D | H | - | - | - | - |
| HealedCodeWaitTimeout | D | H* | - | - | - | - |
| HealingAllPeersStateless | D | H* | - | - | - | - |
| HealingRootUnservable | D | H* | - | - | - | - |
| HealingServeRoot | D | H | - | - | - | - |
| HealingStagnated | D | H* | - | - | - | - |
| IncrementalContractData | D | H | - | - | - | - |
| MinPivotBlock | H | - | - | - | - | - |
| PathPublishDone | - | H | - | - | - | - |
| PathPublishFailed | - | H | - | - | - | - |
| PathPublishProgress | - | H | - | - | - | - |
| PivotBootstrapFailed | H | H* | H | - | - | - |
| PivotProbeTimeout | D | H | - | - | - | - |
| PivotStateUnservable | D | H | - | - | - | - |
| PollHandshakedPeers | H | H | H | - | H | - |
| ProgressAccountEstimate | D | H | - | - | - | - |
| ProgressAccountsFinalizingTrie | D | H | - | - | - | - |
| ProgressAccountsSynced | D | H | - | - | - | - |
| ProgressAccountsTrieFinalized | D | H | - | - | - | - |
| ProgressBytecodesDownloaded | D | H | - | - | - | - |
| ProgressNodesHealed | D | H | - | - | - | - |
| ProgressStorageContracts | D | H | - | - | - | - |
| ProgressStorageSlotsSynced | D | H | - | - | - | - |
| RequestAccountRanges | D | H | - | - | - | - |
| RequestByteCodes | D | H | - | - | - | - |
| RequestStorageRanges | D | H | - | - | - | - |
| RequestTrieNodeHealing | D | H | - | - | - | - |
| RetryBootstrapAtBlock | D | H | - | - | - | - |
| RetryPivotRefresh | D | H | - | - | - | - |
| RetrySnapSyncStart | D | - | H | - | - | - |
| ScheduledTrieWalk | D | H* | - | - | - | - |
| Start | H | - | - | - | - | - |
| StateHealingAbandoned | D | H* | - | - | - | - |
| StateHealingComplete | H | H | - | - | - | - |
| StateValidationComplete | H | H | - | - | - | - |
| StorageBackpressureChanged | D | H | - | - | - | - |
| StorageCoordinatorProgress | D | H* | - | - | - | - |
| StorageRangeSyncComplete | H | H* | - | - | - | - |
| StorageRangeSyncForceCompleted | H | H* | - | - | - | - |
| StorageRangesResponse | D | H | - | - | - | - |
| TrieNodesResponse | D | H | - | - | - | - |
| TrieWalkBatch | D | H* | - | - | - | - |
| TrieWalkComplete | D | H* | - | - | - | - |
| TrieWalkFailed | D | H* | - | - | - | - |
| TrieWalkResult | D | H* | - | - | - | - |
| TuneRateTracker | D | H | - | - | - | - |
| ValidateAccountTrieResult | D | D*+H*+H* | - | - | - | - |
| ValidateStorageTriesResult | D | D*+H*+H* | - | - | - | - |
| ValidationRetry | D | D*+H* | - | - | - | - |
| WrappedHandshakedPeers | H | H | H | - | H | - |
| WrappedPeerDisconnected | H | H | H | - | H | - |

**Duplicated handlers (candidates for one shared `commonArms` partial function)**
* Peer-event quartet (`WrappedHandshakedPeers`->`handleHandshakedPeersRateTracking`, `WrappedPeerDisconnected`->`handlePeerDisconnectedDebounced`, `FlushPeerDisconnects`->`flushPeerDisconnects`, `PollHandshakedPeers`->`pollHandshakedPeers`): idle L802–809, syncing L979–986, bootstrapping L2273–2280 (Poll arm is longer, reactivity), dormantRetry L3420–3427. Four copies.
* `GetProgress`: identical in all 6 behaviours (L828, 2091, 2463, 2541, 3453, 5581). `GetStatus`: 6 copies, 4 distinct bodies (NotSyncing / currentSyncStatus / bootstrapping variant / SyncDone x2 / Syncing(pivot,pivot) in dormant).
* `CLPivotHint -> handleCLPivotHint(hint, isStarting = false)`: idle L824, syncing L987, bootstrapping L2282, dormantRetry L3438 (four identical calls; `isStarting` is never true).
* `EvictNonSnapPeers`: syncing L1001, bootstrapping L2522. `ChainDownloaderProgress`/`ChainDownloaderDone`: syncing L2078/2082 and completedWithBackfill L5565/5569 (two different Done semantics: syncing->finalize path vs backfill->`Done`+`completed()`).
* `BootstrapComplete`/`PivotBootstrapFailed`: three different implementations — idle (warn+drop L833/836), bootstrapping (initial pivot, L2290/2509), syncing (refresh, L1396/1412, guarded by `pendingPivotRefresh.isDefined`).
* Gaps: `MinPivotBlock` is handled ONLY in idle (L811) — dropped silently in syncing/bootstrapping/dormant (relevant to #1434 because `belowEscalationHint` reads `minPivotHint`, L2610). `Start` only in idle; `RetrySnapSyncStart` only in bootstrapping; `DormantWakeUp` only in dormantRetry. `completed` (L2534) ignores everything but status/progress and `PostStop`.
* Stale-generation guards duplicated in syncing for validation (L1871–1883) and coordinators (`AccountRangeProgressCmd.generation`, L1175).

## R5. Child-actor and worker ownership

Controller-owned typed refs (all `var Option[ActorRef]`): `accountRangeCoordinator` L260, `bytecodeCoordinator` L263, `storageRangeCoordinator` L265, `trieNodeHealingCoordinator` L268, `chainDownloader` L272. Workers (AccountRangeWorker, ByteCodeWorker, StorageRangeWorker, TrieNodeHealingWorker) are spawned by their coordinators, never by the controller. No `ctx.watch`; supervision via `Behaviors.supervise` at each spawn.

| Child | Spawn (method:line) | Stop / clear | Messages sent (method:line, grouped) |
|---|---|---|---|
| AccountRangeCoordinator | `launchAccountRangeWorkers` L3720 | `stopStateSyncChildren` L783 (+`= None`); `enterDormantMode` L3358; `restartSnapSync` L4897 (inline copies); never on success (stopped by itself / `onStop`) | Start L3752; PeerAvailable L3869 (`requestAccountRanges`); PeerUnavailable L627 (`flushPeerDisconnects`); AccountRangeResponseMsg L1130; Storage/ByteCodeQueuePressure L1297/1304 (`syncing`); PivotRefreshed L1325, L4712, L4825; RecoverStalledAccountTasks L5125 (`maybeRestartIfAccountStagnant`) |
| ByteCodeCoordinator | `startSnapSync` L2719 (recovery), `launchAccountRangeWorkers` L3767, `queueHealedCode` L5204 (re-spawn after healing, bumps `coordinatorGeneration`) | same three stop sites | StartByteCodeSync L2737/3786/5222; AddByteCodeTasks L1500/2853/5224; NoMoreByteCodeTasks L1567/2870/5226; UpdateMaxInFlightPerPeer L1580/2778/2883/3847; ByteCodePivotRefreshed L1583/2884/4831; ForceCompleteByteCodes L2231; ByteCodesResponseMsg L1137; ByteCodePeerAvailable L3881; ByteCodePeerUnavailable L629 |
| StorageRangeCoordinator | `startSnapSync` L2743, `launchAccountRangeWorkers` L3795 | same three | StartStorageRangeSync L2774/3835; AddStorageTasks L1507/2805; NoMoreStorageTasks L1568/2826; UpdateMaxInFlightPerPeer L1576/1637/3849; StoragePivotRefreshed L1326/4713/4827; ForceCompleteStorage L2147/2204; StorageRangesResponseMsg L1144; StoragePeerAvailable L3907; StoragePeerUnavailable L628 |
| TrieNodeHealingCoordinator | `startStateHealing` L3988, `startStateHealingWithInterleave` L4068 | same three; also `healingWalkLocalOnly.set(false)` | StartTrieNodeHealing L4031/4109; UpdateMaxInFlightPerPeer L4032/4110; HealingPeerAvailable L4039/4115/4150; QueueMissingNodes L1790/1846; WalkStateChanged L1797/1824/1857/3935; HealingResumeDispatch L1704; HealingServeRootRefresh L1076; HealingPivotRefreshed L4844; TrieNodesResponseMsg L1155; HealingPeerUnavailable L630 |
| ChainDownloader | `launchChainDownloader` L5605–5640 (`sync-dispatcher`, `coordinatorGeneration += 1`); called from `startChainDownloader` L5596 and `finalizeSnapSync`/`enterHeaderHold` | `enterHeaderHold` L5314 (restart on stalled cursor), `finalizeSnapSync` L5493, `completedWithBackfill` L5572 (NOT stopped by `stopStateSyncChildren`/`restartSnapSync`/`enterDormantMode`: it survives restarts) | Start L5639; Pause L1477, L4556; UpdateTarget L4848, L5295, L5303; Resume L4850; ReleaseBodiesAndReceipts L5603 |
| State validators (not actors) | `spawnAccountValidation` L4302, `spawnStorageValidation` L4318 — `Future` on `snapValidationEc` (single-thread, L99), result piped to `ValidateAccountTrieResult`/`ValidateStorageTriesResult` stamped with `validationGeneration` | cannot be cancelled; stale results dropped by generation (L1871–1883) | `validatorFactory(storage)` (test seam) |
| Other async | `PathToHashExporter` via `ctx.pipeToSelf` L5525 (`startPathPublish`); progress `ask` of the coordinators via `pipeToSelf` L2015–2042 (CheckDownloadStagnation arm); `clearStorageDoneMarkers` compaction Future on `snapValidationEc` L245 | n/a | `PathPublishProgress/Done/Failed` |
| Parent | `syncController ! Done / SnapSyncFinalized / StartRegularSyncBootstrap[ByHash] / RequestHealingServeRoot / HealingImpossible` (L965, 1479, 2918, 3001, 3194, 3210, 3270, 4241, 4558, 5474, 5495, 5574) | | |
| NPMA / peer bus | `networkPeerManager ! UpdateClHeadCmd` L2575; `peerListHelper` messageAdapter (L105–135), `handshakedPeersAdapter` L708 | | |

Observation: only 4 of 5 children are stopped by `stopStateSyncChildren`; stop/clear logic is copied into `enterDormantMode`/`restartSnapSync` (L3358–3361, L4897–4900) — drift risk if a 5th child is added.

**[016]** PR #1501 adds three things here:
- An `intakeBudget = Some(intakeBudget)` constructor argument at every coordinator spawn site: `startSnapSync` (bytecode
  and storage), `launchAccountRangeWorkers` (account, bytecode and storage) and `queueHealedCode` (bytecode).
- `ensureHeapWatchdog()` at the two spawn clusters.
- `stopHeapWatchdog()` in `onStop` and `stopSnapOnlySchedules`.

`stopStateSyncChildren`, `enterDormantMode` and `restartSnapSync` do **not** stop the watchdog. `CoordinatorHandles`
(P1) carries the budget into its spawn helpers. The watchdog lifecycle moves with Lifecycle (M9) and keeps exactly
these call sites.

## R6. Proposed module boundaries (the hypothesis test)

Line estimates are **before #1501** and come from the def spans. The class state and doc block (L146–690) is split
across modules in proportion to their size.

| Hypothesised module | Verdict | Contents (lines) | Est. lines | Notes from the matrix |
|---|---|---|---|---|
| Peer tracking / eviction | **Confirm** | `peerListHelper` L99–135, `snapServingPeers` L153, `getSnapPeerWithHighestBlock` L194, reactivity/rate-tracking/debounce L557–689, `pollHandshakedPeers`, `evictNonSnapPeers` L3471, `startSnapPeerEviction`, `startSnapServerPeersScheduler`, `bestSnapProbeTarget`, `ensureSnapServerPeersConnected` L4269, peer-tick arms (~45) | ~450–500 | 8 vars wholly private. Cross-links: `flushPeerDisconnects` fans out to all 4 child refs (needs `CoordinatorHandles`), `stopSnapOnlySchedules` resets 2 flags, `bestEth68PeerForCalibration` read by `calibratePivotTD`, `requestTracker` shared. Fed by `PollHandshakedPeers`; emits `PeerUnavailable`. |
| Pivot selection + refresh | **Split in two** | (a) `PivotSelector`: `startSnapSync` selection part (~270 of 709), `bootstrapping` L2269 (265), `handleCLPivotHint` (23), bootstrap-retry (29), `currentNetworkBestFromSnapPeers`, `calibratePivotTD`, `updateBestBlockForPivot` (~108): **~700**. (b) `PivotRefresher`: `refreshPivotInPlace` (185), `completePivotRefreshWithStateRoot` (205), probe/unservable/RetryPivotRefresh arms (~180): **~570** | 1,270 | (b) writes 21 vars incl. stagnation clocks, validation generation, `preserved*`, probe state, and messages all 5 children: highest coupling after the reset trio. (a) is mostly a pure function of (local best, peer set, CL hint, saved anchors) -> `PivotPlan`. `startSnapSync` also contains recovery/resume (module PERSIST below) and a copy of coordinator spawns (DOWNLOAD). |
| Download-phase supervision + stagnation | **Split in two** | `DownloadSupervisor`: `launchAccountRangeWorkers` non-resume (~150), `startAccountRangeSync` 48, `request*` ticks 78, `stopStateSyncChildren`, `checkAllDownloadsComplete` 32, `restartSnapSync` 85, phase-completion arms (~190), response routing arms (~70), progress arms (~150), `currentSyncStatus` 69: **~850**. `StagnationWatchdog`: `scheduleStagnationChecks`, `maybeRestartIfStorageStagnant` 101, `maybeForceCompleteIfBytecodeStagnant` 20, `maybeRestartIfAccountStagnant` 118, `CheckDownloadStagnation` arm 87, progress forwarding 23: **~370** | 1,220 | Watchdog clocks (`lastStorageProgressMs`, `lastBytecode*`, `lastAccount*`, `storageTailBaseline`, `consecutiveAccountStallRefreshes`, `storageStagnationRefreshAttempted`) are 9 of its 11 vars; only `lastPivotRestartMs`, `forceCompleteStorageSent` and the `last*` resets on pivot refresh/launch/startSnapSync cross over: replace by `watchdog.reset(reason)`. Pure state machine `evaluateStorageTail` already extracted (L6122). |
| Healing orchestration | **Confirm** | `completeHealingWalkClean`, `startTrieWalk`, `startStateHealing`, `...WithInterleave`, scheduler, `requestTrieNodeHealing`, `maybeRequestHealingServeRoot` (90), `triggerHealingForMissingNodes`, healed-code hold family (`queueHealedCode` 48, `reset…`, `dropHealed…`, `anchorPivotBeforeLazyHandoff`), healing arms L1673–1861 (~190) | ~640 | The two `startStateHealing*` are ~85% duplicate (spawn args copied; the frontier/prune flags are exactly the pins). Shared vars: `healRepegNoRootAttempts`, `retryRefreshCounts`, `pendingPivotRefresh` with PivotRefresher; healed-code vars with FINALIZE. |
| Validation | **Confirm** (lowest coupling) | `validateState`, `spawn{Account,Storage}Validation`, arms L1862–1967, `validatorFactory`/`snapValidationEc` | ~175 | Private: `validationRetryCount`, `validationInProgress`; `validationGeneration` bumped by refresh/restart/wake (3 outside writers) and `healingValidatedRoot` set by healing. Talks only via Commands + `getOrCreateMptStorage`. |
| Finalization / header-hold / path-publish / backfill handoff | **Confirm; make it the terminal behaviour(s)** | `completeSnapSync` 39, `enterHeaderHold`/`leaveHeaderHold` 51, `finalizeSnapSync` 172, `startPathPublish`/`onPathPublishDone` 58, `completedWithBackfill` 34, `completed` 28, chain-downloader launch/release 54, gating arms ~45 | ~480 | Already effectively a state machine (pathPublish/headerHold gate every other message). 6 private vars. Reads `healedCodeHashes`/`bytecodeForceCompleted`, `chainDownloader` (also used by pivot refresh Pause/UpdateTarget/Resume). Natural candidate for its own `Behavior` or child actor. |
| Persistence / resume | **Confirm, but merge a "StateStores" holder first** | `getOrCreateMptStorage` 80, `clearStorageDoneMarkers`, `sweepSupersededTaskFiles`/`accountsCompleteTaskFilePaths` (~25 effective), `deserializeSnapProgress` 20, resume selection in `launchAccountRangeWorkers` L3585–3715 (~130), accounts-complete recovery in `startSnapSync` L2595–2900 (~305), checkpoint/task-file arms L1175–1230, L1486–1599 (~170) | ~730 | Mostly decisions (inputs: AppStateStorage + progress stores + current pivot) => extract as a pure `ResumePlanner.plan(...)` returning an ADT (`FreshStart | ResumeAccounts(cursors, files) | ResumeBytecodeStorage(path,…) | DiscardAndRestart(reason)`); this is also where #1434 and the unchecked-reuse defect get fixed and unit-tested. `checkResumable` already exists in the companion (L6040). |
| Dormant / critical-failure | **Merge into a `SyncLifecycle` (reset) module** | `recordCriticalFailure` 16, `enterDormantMode` 48, `wakeFromDormant` 29, `dormantRetry` 55, `restartSnapSync` 85, `PivotStateUnservable` arm ~90 | ~320 | Its three vars are private, but its writes are the reset trio: it cannot be separated from `restartSnapSync` without a shared `reset()`. Best as: `SyncState.reset(kind: Restart | Wake | Dormant)` + this module owns the "when to give up" policy (`MaxConsecutivePivotRefreshes`, `countsTowardRestart` L5995 already pure). |

Shell that remains: constructor, `start`, `idle`, common-arms partial function (peer quartet, GetProgress, CLPivotHint), `onStop`, `CoordinatorHandles` — ~700 lines, vs a 5,600-line class today (L53–5669; companion + config another ~980).

### Cross-cutting prerequisites (do these before moving any behaviour)
1. `CoordinatorHandles` (4 coordinator refs + chainDownloader; `stopAll()`, `broadcastPeerUnavailable`, `pivotRefreshed(root)`, `fanOutResponse`) — removes 8 duplicated stop/clear lines (L3358–3361, L4897–4900) and the per-site `foreach` fan-outs, fixes the stop-set drift.
2. `PhaseFlags` case class (`accountsComplete`, `bytecodePhaseComplete`, `storagePhaseComplete`, `storagePhaseForceCompleted`, `bytecodeForceCompleted`, `forceCompleteStorageSent`, `awaitingHealedCode`, `healedCodeWaitExhausted`, `resumedStaleCursors`) with `reset()`; the reset trio and `startSnapSync` currently write these independently.
3. One `cancelSyncTimers(keys)` (3 hand-copied lists: L759, L3347, L4887).
4. Replace `syncing` + `currentPhase` guards with per-phase behaviours (`accountRangeSync`, `byteCodeAndStorage`, `stateHealing`, `stateValidation`, `chainDownloadCompletion`) sharing a `commonSyncingArms` PF — removes 28 phase tests/17 guards and turns the "Unhandled message in syncing state" into a typed-per-phase decision.

### Lowest-coupling-first extraction order
1. Pure/decision code with no state: more `ResumePlanner` / `PivotPlan` / `StagnationPolicy` into the companion (precedent: 15 already there, each with a unit test). Also delete `isStarting`, dead `else` log L2260, stale DFS names (zero risk).
2. **Validation** (~175; 3 external writers fixed by `PhaseFlags.reset`).
3. **Peer pool** (~480; 8 private vars; needs `CoordinatorHandles` only for `broadcastPeerUnavailable`).
4. **Finalization/backfill** (~480; terminal, state private; needs `chainDownloader` handle shared with refresher — make `ChainDownloaderHandle` a small class both use).
5. **Persistence/resume planner** (~730; decisions first, side effects remain in controller until step 7). Closes #1434 + unchecked-reuse with tests.
6. **Healing orchestration** (~640; merge `startStateHealing*` first, then move; add coordinator-factory seam to pin fixes #3/#4).
7. **Stagnation watchdog** (~370; needs `reset()` hook from refresh and launch).
8. **Pivot selection** (~700; after ResumePlanner since both live inside `startSnapSync`).
9. **Lifecycle (dormant + restart)** (~320; after PhaseFlags/Handles, because it is the reset hub).
10. **Pivot refresher** (~570; last, it touches everything); what remains is the Download supervisor/shell.

## R7. Fix-pin inventory (the five fixes restored by PR #1385, merge 9bdcf7955)

| # | Fix | Current location | Test that fails if dropped? |
|---|---|---|---|
| 1 | #1367 `&& ourBest.header.number.value > 0` on TD-PROXY-GAP eviction | **`network/NetworkPeerManagerActor.scala:798`** (`if ratio > BigInt(10_000) && ourBest.header.number.value > 0 then`), rationale comment L790–797. Not in the snap package or the controller. | **None / comment only.** Greps of `src/test` + `src/it` for `TD-PROXY`, `10_000`, `number.value > 0`, `ratio` find only comments in `CalibratePivotTDSpec.scala:162,168`. `ChainWeightCalibrationSpec` tests `SyncController.CalibrateChainWeightFromPeer`; `NetworkPeerManagerSpec.scala:709` is the unrelated best-block-probe assertion. No test drives the handshake gap check with best block = 0. |
| 2 | #1378 `apply()` calls `.start()` not `.startSnapSync()` (arms 5 s `PollHandshakedPeers`) | `SNAPSyncController.scala:6316` (`).start() // #1378 …`, comment L6316–6320); `start()` L690–715 starts the timer at L701 and self-sends an immediate poll L702. | **None (indirect at best).** Fixtures that use `SNAPSyncController(...)` (`SNAPDeferredBackfillSpec:111`, `SNAPHealedCodeFinalizeSpec:102`, `SNAPStorageRecoverySpec:240`, `SNAPDormantRecoverySpec:171`, `SNAPLazyHealAnchorSpec:792`) stub NPMA to answer `GetHandshakedPeersCmd` but none asserts a poll arrives or that the 5 s timer fires; the ones that need peers send `PollHandshakedPeers` by hand (`SNAPDormantRecoverySpec:195`, `SNAPLazyHealAnchorSpec:263,311,454,471,836`). By inspection (tests not run: no sbt on this host), replacing `.start()` with `.idle()` leaves all of them green. (The impl class is `private`, so a test cannot call `startSnapSync` directly.) |
| 3 | #1319/spec-002 `frontierPersistenceEnabled = healingFrontierPersistence` at BOTH `TrieNodeHealingCoordinator` sites | `SNAPSyncController.scala:4003` (`startStateHealing`) and `:4083` (`startStateHealingWithInterleave`); store gate `:3964`. | **None at controller level.** Coordinator-level specs set the parameter directly (`HealingFrontierResumeSpec:119`, `CleanRebuildEarlyCompletionSpec:131`, `ScopedVerificationFallbackSpec:139`, `HealingTrieFixtures:83`, `PrunedHealFallbackSpec` "write NO frontier-mirror entries … when frontierPersistenceEnabled = false") so they pin the coordinator contract, not the controller wiring. By inspection (not run), deleting L4003/L4083 compiles (coordinator default `false`) and passes. No spec references `healing-frontier-persistence` or `healingFrontierPersistence` outside coordinator fixtures. |
| 4 | spec-005 `prunedHealVerification` default true + parsing + `\|\| prunedHealVerification` term | default `:6346`; parse `:6503–6505` (`pruned-heal-verification`, default true; conf `sync.conf:201`); store term `:3964`. | **None.** No test touches `SNAPSyncConfig.prunedHealVerification` or the config key (`SNAPSyncControllerSpec` "have sensible defaults" does not assert it). `PrunedHealFallbackSpec/VerificationSpec` pass `prunedHealVerification` straight to the coordinator. `PrunedHealParitySpec:88,148` docstrings say the flag "was removed from the production API" (stale). **Plus a live gap**: the controller does NOT forward the flag to either coordinator spawn (see section 0). |
| 5 | #1371 no `!storagePhaseForceCompleted` term in `shouldSkipHealingAfterDownloads` | def `:6087–6101` (2-arg: `snapSyncConfig`, `resumedStaleCursors`; body `deferredMerkleization && !resumedStaleCursors`); sole call site `:2241`. Orphan branch `:2260` still logs a "force-completed with deferred merkleization → starting healing" message that is now reachable only when a stale-cursor resume is true. | **Weak partial.** `SNAPSyncControllerSpec.scala:178–228` (4 tests, e.g. "skip healing … regardless of force-complete" at ~L189) call the 2-arg function, so re-adding the term as a *required* parameter breaks compilation; re-adding it with a default, or adding `&& !storagePhaseForceCompleted` at the call site (L2241, untestable: private class), passes. |

Recommendation for #1401: gate with grep/ratchet tests (S11-style) or a controller-level behaviour test per pin: (1) NPMA handshake test with best block 0 and ratio >10k expects no disconnect; (2) `SNAPSyncController` spawn test asserting `GetHandshakedPeersCmd` repeats at 5 s (use `ManualTime`); (3)+(4) spawn controller with `healing-frontier-persistence=true` and assert the `TrieNodeHealingCoordinator` factory args (needs a coordinator-factory seam, like `validatorFactory`); (5) keep, add call-site test through `SNAPLazyHealAnchorSpec`-style fixture with force-completed storage.

**[016] Locations re-checked on staging@9a6681cc0**: `NetworkPeerManagerActor.scala:798`, controller `:6316`, `:4003`,
`:4083`, `:3964`, `:6087`, `:6346` and `:6503–6505` all still hold the pinned expressions.

## R8. Resume ledger (the on-disk formats this spec freezes)

| Mechanism | Where (file:line) | On-disk format owner | State |
|---|---|---|---|
| Legacy plain-text progress `AppStateStorage.getSnapSyncProgress` | read L3646–3668; parser `deserializeSnapProgress` L5650–5669; migrated into `SnapSyncProgressStorage.writeAccountCursors` L3656 | `db/storage/AppStateStorage.scala` (string), migration-only fallback | Closed (kept for upgrade). Tests: `SNAPSyncControllerResumeSpec` (3 tests, mostly helper-level) |
| `SnapSyncProgressStorage` (namespace `p`, JSON account + storage cursors) | field L202; read L3623, `readProgress`; clear L1557, L3685; passed to Account coord L3821 and Storage coord L2761 | `db/storage/SnapSyncProgressStorage.scala:49` | Cursor key is the state root => pivot change = new root => legacy loss; mitigated by #1487 (below) and `MaxPreservedPivotDistance = 50_000` (L348, documented as heuristic not correctness; correctness = `resumedStaleCursors` L354/3693 forcing the full heal walk) |
| `AccountResumeCheckpoint` (versioned, fixed AppState key; cursors + pivot + contract task files; written every 30 s, on range completion and stop, after trie flush + task-file fsync) | write L1200–1201 (`AccountRangeProgressCmd` arm, only `durable` snapshots, generation-filtered L1175); read L3597–3620; retire L1537, L1558, L3620, L3686 | `snap/AccountResumeCheckpoint.scala:64` (encode/decode, version); key owned by `AppStateStorage.putSnapAccountResumeCheckpoint` | **Closed by PR #1487** (classified `UnservableCause`, geth-style StackTrie resume at cursor, carried contract task-file prefix). Tests: `SnapResumeBoundarySpec` (6), `AccountRangeCoordinatorSpec` (34, carried replay) |
| Contract task files (storage tasks + unique codeHashes) | paths/counts persisted L1535–1548; usability check L2630–2645; sweep of superseded files L331–352, companion `sweepTaskFiles` L6013, `taskFilePaths` L6008; accounts-complete recovery stream L2832, L2801 | `snap/StorageTaskFile.scala:17` (fixed-size entries, `isUsable`, `CodeHashEntrySize`); paths in AppStateStorage | **Closed by #1453** (files in datadir, never complete a phase without them) and #1449 (ordering, coordinator restart). Test: `SNAPStorageRecoverySpec` (15) |
| Storage done-markers (skip finished tasks on replay/recovery) | field L221; `recordStorageDone` L227; filter L2801; clear+compact L241–250 (on account phase without carry L3713, on storage complete L1651); passed L2764, L3740 | `db/storage/SnapStorageDoneStorage.scala:38`: key `SnapStorageDone/ ++ accountHash ++ storageRoot` in the app-state CF; `DataSource.compactRange` | **Closed by PR #1498** (spec 010). Old checkpoints have no markers => first restart replays everything (documented). Tests: `SnapStorageDoneStorageSpec`, coordinator specs |
| Phase flags and anchors (`accountsComplete`, `bytecodeComplete`, `storageComplete`, pivot, root, bootstrap target, storage/codeHash path+count, finalized root, `snapSyncDone`/`bytecodeRecoveryDone`/`storageRecoveryDone`) | writes L1518–1648, L1801–1831, L2386, L2980, L3137, L3209, L3920–3921, L5247–5448 | `AppStateStorage` (`putSnapSync*`) | Open: **#1434** (flag wipe at L2611–2620 by `belowEscalationHint` on a later `RegularSyncStuck`; the issue cites ~L2234, now drifted; `minPivotHint` is only set in idle L811) and the `networkBest == 0` unchecked-reuse (section 0; the "#93-ish stale pivot reuse" item could not be matched by number in repo or issues) |
| Healing frontier + BFS queue | `healingFrontierStorageOpt` L3960 (lazy, store exists if persistence OR pruned verification), `bfsQueueStorage` L3968 (`Namespaces.BfsQueueNamespace`) | `db/storage/HealingFrontierStorage.scala:27` (CF `g`: frontier mirror + subtree-complete records), `db/storage/BfsQueueStorage.scala:25/87` | `healing-frontier-persistence` default OFF (`sync.conf:~115`) => after a restart heal resumes by a full BFS rebuild; pruned verification (spec 005) ON. Closed pieces: #1472 (never clean on dirty/superseded pass), #1448. Open: #1350, #1349 (RSS / pipeline, TNHC). |
| `snap-good-peers.v1` | **not in the controller**; `network/SnapGoodPeers.scala` (owned by `PeerManagerActor`, written from `NetworkPeerManagerActor` `FlushSnapServedTick` -> `SnapServedReportCmd`), config `network.conf:107–118` | `network/SnapGoodPeers.scala`: text `fukuii-snap-good-peers v1` + TSV | **Closed by #1497** (spec 012); `SnapGoodPeersSpec` (9). Controller/peer-pool module must not own it; only consumes via NPMA. |
| Finalization anchors | `finalizeSnapSync` L5332–5503: pivot header+best-block stored, `snapSyncDone` AFTER (D4, L5422–5448) | AppStateStorage / BlockchainWriter | Open: **#1435** (older finalizations stored `Block(pivotHeader, BlockBody.empty)`; startup repair of P-5..P bodies) |

Related merged PRs (`gh pr list --state merged --search snap`): #1487 resume + classified escalation, #1498 done-markers, #1453 task files, #1449 storage ordering/restart, #1497 good peers, #1491 peer balance, #1479 scarce peers, #1478 never keep unservable pivot, #1472 dirty-pass guard, #1466 deferred backfill, #1455/#1454/#1480 Path scheme, #1457 best-header at startup, #1456/#1459 healed-code/bytecode recovery, #1385/#1378/#1371/#1367 (the five pins), #1357 hold healing pivot on stagnation.

**[016] Correction to the ledger row "Healing frontier + BFS queue"**: the report says `healing-frontier-persistence`
defaults OFF. In fact:
- `base/sync.conf:118` ships `healing-frontier-persistence = true`.
- The `SNAPSyncConfig` case-class default (`:6345`) is `false`.
- The comment above the key in `sync.conf` ("Default false (ships dark)") is stale.

So production nodes persist the frontier, and directly-built test configs do not. This is a note only; no fix is in
scope here. It belongs in CHASE-QUEUE as a stale comment.

**[016] Golden-bytes coverage**: a grep of `src/test` found no test that asserts the exact encoded bytes of any of
these formats:
- `AccountResumeCheckpoint` v1
- `SnapSyncProgressStorage` namespace `p`
- `SnapStorageDone/` keys
- `HealingFrontierStorage` CF `g` (the frontier mirror and the subtree-complete records)
- `BfsQueueStorage`
- `StorageTaskFile` entries (64 B storage-task entries, 32 B codeHash entries)
- the legacy `AppStateStorage` progress string

The existing specs round-trip these formats through the same encoder they test, so a change made symmetrically to the
encoder and decoder would pass them. Slice S0c adds a golden test for each format.

`snap-good-peers.v1` is owned by the network layer (`network/SnapGoodPeers.scala`). No slice moves it, and it is
listed only as an inertness oracle.

## R9. Leftover DFS naming after the BFS heal change

(`grep -niE 'dfs|depth.?first'` over the snap package + conf)
* `SNAPSyncController.scala:407` comment "has just been DFS-walked end-to-end" (heal walk is BFS); `:3957` doc "always full DFS on restart"; `:4344` comment "visits every node … via DFS"; `:6339` "frontier-rebuild DFS `visited` LRU".
* Related vestige names: `healingVisitedCap` (`:6342`, `:6498–6500`, `:3999`, `:4079`), `healing-visited-cap = 4000000` (`sync.conf:112`), TNHC `visitedCap`/`HealingVisitedCap` (TNHC L55, L263), `sync.conf:115` "full DFS is skipped".
* `actors/TrieNodeHealingCoordinator.scala:2237` doc "Replaces startParallelFrontierDFS" (history, acceptable).
* **Legit DFS (do not rename)**: `StateValidator.scala:223,253,266,273,303,304,342` (`walkAccountTrieDFS`, `walkStorageTrieDFS`, explicit stack) — the validator really is DFS.
* Tests with stale wording: `SNAPSyncControllerSpec:482`, `FrontierRebuildSpec:12,15`, `HealingFrontierResumeSpec:32,52,53,101,141–166`, `HealingTrieFixtures:363` (all say "DFS" for the BFS rebuild; `RebuildFrontierBfsMultiSeedSpec` already uses BFS).

## R10. Open items placed on modules

The report's issue table, extended **[016]** with #1502, #1503 and the project tasks named in the 016 brief:

| Item | What | Module (target) | Also touches |
|---|---|---|---|
| #1434 | Regular-sync startup ignores an in-progress SNAP sync; a restart mid-heal can wipe SNAP progress (`belowEscalationHint` flag wipe at L2610–2620; `MinPivotBlock` handled only in idle, L811) | **ResumePlanner** (M5) | PivotSelector (M8); `SyncController` startup routing (outside snap) |
| #1435 | Repair the empty pivot body that older SNAP finalization stored | **Finalization** (M4) | startup repair outside the controller |
| #1502 | `prunedHealVerification` never forwarded to TNHC (spawn args L3996–4020, L4075–4100) | **HealingOrchestrator** (M6); the S0b factory seam makes it testable | TNHC constructor |
| #1503 | Accounts-complete recovery reuses the saved pivot unchecked when `networkBest == 0` (L2669, `else if` L2696) | **ResumePlanner** (M5) | PivotSelector (M8) |
| #1350 | heal-walk reader-dispatcher raises peak RSS | **TNHC FrontierRebuildBfs** (C-H2) | `visitedCap` spawn args move with M6 |
| #1349 | read/decode pipeline in `processSubRange` | **TNHC FrontierRebuildBfs** (C-H2) | — |
| #1431 | negotiated snap version (snap/2) | **SnapPeerPool** (M3): `snapServingPeers`/`servesSnapState` | network layer (herald) |
| task: off-thread `ctx.log` + status `ask` timeouts | logging from Future callbacks; `GetStatus`/progress `ask` timeouts | **controller shell** (common arms, `currentSyncStatus`) and the `CheckDownloadStagnation` progress `ask` (L2015–2042) → **StagnationWatchdog** (M7) | `asyncLog` users in ResumePlanner |
| task: heal convergence (latch bug; keep tasks across re-peg) | healing never converges; tasks dropped on re-peg | **HealingOrchestrator** (M6) × **TNHC HealResponseProcessing / `isComplete`** (C-H5) | PivotRefresher (M10) sends `HealingPivotRefreshed` |
| task: stale-pivot empty ranges classified as scarcity | an empty AccountRange answer to a stale root is treated as peer scarcity | **ARC AccountTaskLifecycle** (C-A2: `completeEmptyTaskRange`, `handleTaskFailed`) × ARC peer stateless marking (C-A1) | StagnationWatchdog (M7) |
| task: pivot refresh every ~10–13 min discards in-flight per-account storage tries | `StoragePivotRefreshed` → `resetAccountTrie` | **PivotRefresher** (M10) × **SRC StorageOrderedApply** (C-S2: `resetAccountTrie`, `clearStorageOrderingState`) | — |
| dead `isStarting` param (L2562) | always false | **PivotSelector** (M8), removed in its own cleanup commit | — |
| orphan "force-completed with deferred merkleization → starting healing" log (L2260) | wording is misleading; reachable only on a stale-cursor resume | **DownloadSupervisor** (M11), CHASE-QUEUE | — |
| `MinPivotBlock` handled only in idle | dropped in other behaviours | **PivotSelector** (M8), relates to #1434 | — |

## R11. [016] PR #1501 (spec 014) types in the module map

This spec is rebased on PR #1501 as it stands on `fix/snap-memory-bounds` (open, not yet merged on 2026-10-08). If
#1501 changes before it merges, T001 re-checks this table.

| #1501 element | Kind | Target module |
|---|---|---|
| `SnapIntakeBudget` (new file) | shared admission gate (atomics) | stays its own file; the controller-owned `intakeBudget` val is carried by **CoordinatorHandles** (P1) into every spawn |
| `SnapHeapWatchdog` (new file), `heapWatchdog` var, `ensureHeapWatchdog`, `stopHeapWatchdog`, `onHeapPressureChange` | JVM-wide JMX listener | **SyncLifecycle** (M9). Call sites unchanged: start with coordinators, stop in `onStop` and `stopSnapOnlySchedules` only |
| `GatedTaskFileReplay` (new file), `runGatedReplay`, `RecoveryReplayPausedRetry` | chunked, gated recovery reader | **ResumePlanner** (M5), which owns the accounts-complete recovery streams |
| `IncrementalContractData` release on drop (idle arm and `syncing` arm) | budget bookkeeping | the idle arm stays in the shell; the `syncing` arm moves with **DownloadSupervisor** (M11) |
| `AddByteCodeTasks(skipPresent)` | the presence check for replayed codeHashes moved into `ByteCodeCoordinator` | **ByteCodeCoordinator** (C-B1) |
| `StorageTaskFile.foreachEntry` (moved from ARC) | shared reader | stays in `StorageTaskFile` |
| `StorageTask` shared full-range boundaries | allocation | unchanged |
| config `max-pending-storage-tasks`, `max-pending-bytecode-hashes`, `heap-watchdog-{enabled,pause-fraction,resume-fraction,poll-interval}` | `sync.snap-sync` keys | routing doc: Lifecycle (watchdog) and CoordinatorHandles/intake (budget) |
| metrics `snapsync.memory.{storage,bytecode}.pending`, `snapsync.memory.intake.paused`, `snapsync.memory.heap.pressure`, `snapsync.memory.oldgen.postgc.{bytes,ratio}`, `snapsync.{storage,bytecode}.inflight.requests` | gauges | routing doc |
| log tag `[SNAP-HEAP]`; gate state in `[ACCOUNT-IDLE]` | logs | routing doc |

## R12. [016] Coordinators — preliminary characterization (completed in T060)

The report covered only the controller. The structure below comes from a top-level outline of each coordinator
(`grep` of `def`/`case class` spans) and is enough to set slice boundaries. Before the first coordinator slice, T060
builds the same var × method matrix for each coordinator and either confirms these boundaries or moves them.

| Coordinator | Vars | Command cases | Largest units (staging lines) |
|---|---|---|---|
| AccountRangeCoordinator | 34 | 34 | `receive` L731–1097 (366); `finalizing` L1097–1189; `handleStoreAccountChunk` L1582–1691; `replayCarriedChunk` L1691–1764; task-file/contract I/O L1788–1914 |
| StorageRangeCoordinator | 30 | 18 | `active` L947–1300 (353); `applyReadyStorageChunk` L1702–1860; done-markers + flat batches L718–869; `processServedTasks` L1529–1644 |
| TrieNodeHealingCoordinator | 45 | 18 | **`activeBehavior` L713–1534 (821)**; `rebuildFrontierBFS` L1939–2240 (301); `discoverMissingChildren` L2411–2575; frontier persistence L278–449; verification L2301–2411 |
| ByteCodeCoordinator | 16 | 19 | `active` L189–426; `handleByteCodesResponse` L498–614 |

**Duplicated peer-dispatch helpers.** The per-peer adaptive response-size window (`responseBytesTargetFor`,
`adjustResponseBytesOnSuccess`, `adjustResponseBytesOnFailure`) has the **same body in ARC, SRC and TNHC**; only the
label in the debug log differs ("account"/"storage"/"healing"). ByteCodeCoordinator's copy is different in two ways:
it is keyed by `PeerId` instead of `String`, and its success path adds an extra `.max(minResponseBytes)` clamp. The
cooldown helpers differ per coordinator:
- ARC takes a duration argument and logs at INFO under `[ACCOUNT-COOLDOWN]`.
- SRC and TNHC use a fixed default and log at DEBUG.
- ByteCode backs off exponentially.

Verdict: extracting a shared `AdaptiveResponseBytes` for ARC, SRC and TNHC does not change behaviour, and is slice
C-X. Unifying cooldowns, or pulling ByteCode into the shared window, would change behaviour, so both are **non-goals**
(CHASE-QUEUE). `SnapPeerHealth` (#1491) is used only by SRC today, and no slice changes that.

## R13. Test inventory relevant to the split (from the report §8, condensed)

The SNAP suites are chain-agnostic by design. They split ETC from ETH by storage scheme:
- Hash scheme = ETC/Mordor.
- Path scheme, `isPoSChainOverride` and `CLPivotHint` = ETH/Sepolia.

No `src/it` spec touches the snap package.

**Controller-level suites (the regression set every controller slice must keep green unchanged):**

| Suite | ~tests | Chain |
|---|---|---|
| `SNAPSyncControllerSpec` | 83 | both |
| `SNAPSyncControllerResumeSpec` | 3 | ETC/Hash |
| `SNAPDormantRecoverySpec` | 5 | ETC/Hash |
| `SNAPLazyHealAnchorSpec` | 26 | ETH-leaning, with explicit ETC cases |
| `SNAPDeferredBackfillSpec` | 6 | — |
| `SNAPHealedCodeFinalizeSpec` | 5 | — |
| `SNAPStorageRecoverySpec` | 15 | Hash + Path |
| `SnapResumeBoundarySpec` | 6 | ETH + ETC |
| `SNAPRangeBoundarySpec` | — | — |
| `SNAPFakePeerSpec` | 6 | — |
| `SnapStoragePeerEligibilitySpec` | 11 | — |
| `SnapPeerHealthSpec` | 11 | — |
| `SNAPRequestTrackerSpec` | 20 | — |
| `SNAPSyncMetricsSpec` | 2 | — |
| `SyncProgressMonitorSpec` | 5 | — |

**Coordinator-level suites:**

| Suite | ~tests | Notes |
|---|---|---|
| `AccountRangeCoordinatorSpec` | 34 | |
| `AccountRangeWorkerSpec` | 9 | |
| `ByteCodeCoordinatorSpec` | 22 | |
| `ByteCodeWorkerSpec` | 7 | |
| `StorageRangeCoordinatorSpec` | 55 | |
| `StorageRangeWorkerSpec` | 5 | |
| `StorageFloorSelectionSpec` | 4 | |
| `TrieNodeHealingCoordinatorSpec` | 44 | |
| `TrieNodeHealingWorkerSpec` | 5 | |
| `TrieNodeHealingScopeCaptureSpec` | 3 | |
| `TrieNodeHealingScopedVerificationSpec` | 2 | |
| `GcPressureSamplerSpec` | 4 | |
| heal-verification family | — | Pruned*, Scoped*, Decoupled*, FrontierRebuild, RebuildFrontierBfsMultiSeed, HealingFrontierResume, CleanRebuildEarlyCompletion, ForceCompletedStorageVerification, StaleVerificationWalk, SubtreeCompleteSeeding, HealedAccountCode, `PathHealPresenceSpec` (Path/ETH) |
| #1501 additions | — | `SnapIntakeBudgetSpec`, `SnapHeapWatchdogSpec`, `GatedTaskFileReplaySpec` |

**Adjacent suites:**
- `SyncControllerSpec`.
- `ChainDownloaderSpec` (26), `PivotHeaderBootstrapSpec` (16).
- the storage specs: `SnapSyncProgressStorageSpec`, `SnapStorageDoneStorageSpec`, `HealingFrontierStorageSpec`,
  `BfsQueueStorageSpec`.
- `network/SnapGoodPeersSpec`, `NetworkPeerManagerSpec`.

**Gaps the split moves code through without coverage:**
- No controller-level test of the stagnation watchdog. The `maybeRestartIf*` methods are private; only
  `evaluateStorageTail` is unit-tested.
- No test of how complete the resets in `restartSnapSync`, `wakeFromDormant` and `enterDormantMode` are.
- No test of pivot refresh (`refreshPivotInPlace`, `completePivotRefreshWithStateRoot`) beyond the
  `SNAPLazyHealAnchorSpec` anchors.
- No test of `handleHandshakedPeersBootstrapReactivity`.
- Each of the six `SNAP*Spec` controller fixtures builds its own stubbed NPMA. Fixture work comes first (S0d).

**[016] Test baseline, task #68**: the brief records a pre-existing `SyncControllerSpec` failure as task #68. The only
written trace found is in `.local/docs/continuations/Main-Glamsterdam-Soak.md`. It describes a flaky **fast-sync**
`SyncControllerSpec` timing test (two isolated re-runs: one failure, then 21/21). Fast sync was removed by #1436, which
is on staging, so that test may no longer exist. The baseline for this spec is therefore **the `SyncControllerSpec`
result in the CI run of the S0a PR, recorded by test name** (T002). The identity of #68 is an open question for the
user (spec.md, Open Questions).
