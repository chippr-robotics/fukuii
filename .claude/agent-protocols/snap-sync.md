# SNAP Sync Routing Protocol (DRAFT — spec 016)

Where each SNAP phase lives, what configures and measures it, how it logs, and who reviews changes to it. Use this
doc to point an agent at **one** phase without having it read the 6,646-line controller.

Used by: all agents touching `blockchain/sync/snap/`. The primary reviewers are named per row.

Spec: `specs/016-snap-controller-split/` (#1401). Phase 0 data: `specs/016-snap-controller-split/research.md`.

**Status of this doc**: **draft**. Rows marked **current** point at today's code (`staging@9a6681cc0`; line numbers
moved when PR #1501 merged on 2026-10-08, so search by symbol; post-#1501 anchors are in research.md R1). Each spec-016 slice flips its rows to **target (post-split)**, with the
real file names, in the same PR. Slice R1 (T090) checks every row with grep.

Paths are relative to `src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/` unless they start with
`network/` or `src/`. `SSC` = `SNAPSyncController.scala`. Config keys are under `sync.snap-sync.` (`base/sync.conf`)
unless stated.

---

## Rules that apply to every row

- **On-disk formats are frozen during the split**: the progress string, `SnapSyncProgressStorage` (ns `p`),
  `AccountResumeCheckpoint` v1, the storage/codeHash task files, `SnapStorageDone/` markers, `HealingFrontierStorage`
  (CF `g`), `BfsQueueStorage`, the AppState phase flags and `snap-good-peers.v1`. Any change to them needs **vault**
  plus its own spec.
- **No bug fixes in move PRs.** Fixes are their own PRs against the current layout (spec 016 NG-4).
- **ETC and ETH share this code.** The split is by phase, not by chain. Hash scheme = ETC/Mordor; Path scheme and the
  CL pivot hint = ETH/Sepolia. ETC-reachable behaviour changes need **forge**; CL-pivot and Path-scheme changes need
  **beacon**.
- **Do not route SNAP work to `loom`.** The SNAP actors are already Pekko Typed.
- **No sbt on the soak host.** CI is the compiler.
- **Module form (ADR CON-013).** Every module is a trait whose self-type is
  `<Module>State & <capabilities> & <Callee>Api…`. A module file never mentions `SNAPSyncControllerImpl` or a
  `*CoordinatorImpl`. To change what a module touches, change its `<Module>State` or Api traits, and update the
  counts below in the same PR. A count must not grow without a justification line. `scripts/snap-split/verify.sh`
  (S0f) checks this, and so does the required CI job `snap-split-verify`.
- **CI reality.** Staging PRs run Tier 1 only (`testEssential`, which excludes `SyncTest`). `SyncControllerSpec`,
  `PivotHeaderBootstrapSpec` and `SnapServingActorSpec` run only in the `snap-synctest` job (S0e). That job runs on
  every PR to staging, exits early when nothing under sync changed, and checks the JUnit reports against a one-entry
  allow-list: task #68, `SyncControllerSpec:762`. It fails if #68 passes or is missing. One re-run of a
  non-allow-listed failure is allowed (FR-039).

---

## Phase / module routing

| Phase / module | Status | Current location | Target (post-split) | Config keys | Metrics | Log tags | Reviewer |
|---|---|---|---|---|---|---|---|
| **Controller core** (constructor, hubs, `start`, `idle`, `syncing` dispatcher, `GetStatus`/`GetProgress`, `onStop`, `SNAPSyncConfig`) | current; child refs **target (post-split)** since P1, phase flags since P2, shared peer-event arms since P3 | `SSC` (whole file); the four coordinator refs, their stop and fan-outs in `controller/CoordinatorHandles.scala`, the ChainDownloader ref in `controller/ChainDownloaderHandle.scala` (P1); the nine phase flags and `reset(kind)` in `controller/PhaseFlags.scala`, `cancelSyncTimers` in `SSC` (P2); `peerEventArms` (the quartet, `CLPivotHint`, `GetProgress` for `idle`, `syncing`, `bootstrapping`, `dormantRetry`) in `SSC` (P3) | `SSC` (≤ ~1,000 lines) + `controller/CoordinatorHandles.scala` and `controller/ChainDownloaderHandle.scala` (P1, done), `controller/PhaseFlags.scala` (P2, done), `controller/ChildFactories.scala` (S0b) | `enabled`, `timeout`, `max-retries` | `snapsync.phase.current.gauge`, `snapsync.phase.time.seconds.gauge` | `[SNAP]` | prism |
| **Peer pool**: SNAP-capable peer selection, eviction, snap-server dialling, disconnect debounce, TD calibration | current | `SSC`: `peerListHelper`, `snapServingPeers`, `snapExclusionReason`, `getSnapPeerWithHighestBlock`, `handleHandshakedPeers*`, `handlePeerDisconnectedDebounced`, `flushPeerDisconnects`, `pollHandshakedPeers`, `evictNonSnapPeers`, `startSnapPeerEviction`, `startSnapServerPeersScheduler`, `ensureSnapServerPeersConnected`, `calibratePivotTD` | `controller/SnapPeerPool.scala` (M3) | `min-snap-peers`, `snap-peer-eviction-interval`, `max-evictions-per-cycle`, `snap-server-peers`, `snap-capability-grace-period`, `max-inflight-per-peer` | `snapsync.peers.capable.gauge`, `snapsync.peers.blacklisted.total`, `snapsync.peers.snapless.confirmed.total`, `snapsync.peers.stateless.confirmed.total` | `[SNAP-PEERS]`, `[STORAGE-STATE]` (in `flushPeerDisconnects`) | prism; herald for wire/capability (#1431) |
| ↳ peer layer outside snap: handshake TD-ratio gap check (**pin #1367**) | current (not moved) | `network/NetworkPeerManagerActor.scala`: guard `ourBest.header.number.value > 0` | unchanged | — | — | `TD-PROXY-GAP` | herald |
| ↳ genesis-head crawler eviction (spec 011) | current (not moved) | `network/PeerManagerActor.scala` | unchanged | `network.*` (spec 011) | — | `GENESIS_HEAD_EVICT` | herald |
| ↳ remembered good SNAP peers (spec 012) | current (not moved) | `network/SnapGoodPeers.scala`, `PeerManagerActor`, `NetworkPeerManagerActor` (`FlushSnapServedTick`) | unchanged | `network.snap-good-peers.*` | — | — | herald; vault for the file format |
| **Pivot selection**: initial pivot (local, network, CL hint, genesis), bootstrap, bootstrap retry | current | `SSC`: `idle` (`Start`, `MinPivotBlock`), `bootstrapping`, `handleCLPivotHint`, pivot parts of `startSnapSync`, `currentNetworkBestFromSnapPeers`, `updateBestBlockForPivot`; `PivotHeaderBootstrap` (sync pkg) | `controller/PivotSelector.scala` (M8) | `pivot-block-offset`, `max-pivot-staleness-blocks` (code default 4096, not in base conf) | `snapsync.pivot.block.number.gauge` | `[CL-PIVOT]`, `[SNAP]` | prism + beacon (CL pivot) + forge |
| **Pivot refresh**: in-place re-peg, probe, unservable root, retry | current | `SSC`: `refreshPivotInPlace`, `completePivotRefreshWithStateRoot`, `PivotProbeTimeout`/`PivotStateUnservable`/`RetryPivotRefresh` arms, refresh-side `BootstrapComplete`/`PivotBootstrapFailed` | `controller/PivotRefresher.scala` (M10) | `pivot-block-offset`, `max-pivot-staleness-blocks`, `moving-root-delta-heal` | `snapsync.pivot.refreshed.total` | `[PIVOT-PROBE]`, `[PIVOT-ROLL]`, `[HEAL-REPEG]` | prism + beacon + forge |
| **Resume / persistence**: checkpoint, task files, done-markers, accounts-complete recovery, gated recovery replay | current | `SSC`: `clearStorageDoneMarkers`, `recordStorageDone`, `sweepSupersededTaskFiles`, `deserializeSnapProgress`, resume selection in `launchAccountRangeWorkers`, accounts-complete recovery in `startSnapSync`, `AccountRangeProgressCmd` checkpoint arm, `runGatedReplay` (#1501); `AccountResumeCheckpoint.scala`, `StorageTaskFile.scala`, `GatedTaskFileReplay.scala` (#1501); `src/.../db/storage/{SnapSyncProgressStorage,SnapStorageDoneStorage,AppStateStorage}.scala` | `controller/SnapResumePlanner.scala` (M5); formats unchanged | `deferred-merkleization`, `storage-recovery-max-root-rolls`, `parallel-recovery-scan`, `recovery-scan-concurrency`, `recovery-scan-shard-depth` | — | `Recovery:` prefix (untagged) | **vault** + prism + forge |
| **Download supervision**: account → bytecode + storage phases, completion, progress, response routing, intake budget release | current | `SSC`: `launchAccountRangeWorkers`, `startAccountRangeSync`, `request*` ticks, `checkAllDownloadsComplete`, `currentSyncStatus`, the `syncing` phase-completion, response and progress arms, the `IncrementalContractData` arm | `controller/DownloadSupervisor.scala` (M11); arms first split by phase in P4a/P4b | `account-concurrency`, `storage-concurrency`, `storage-batch-size`, `max-concurrent-storage-accounts`, `account-initial-response-bytes`, `account-min-response-bytes`, `storage-initial-response-bytes`, `storage-min-response-bytes`, `storage-max-inflight-per-peer-during-accounts`, `max-pending-storage-tasks`, `max-pending-bytecode-hashes` (#1501) | `snapsync.accounts.*`, `snapsync.bytecodes.*`, `snapsync.storage.*`, `snapsync.memory.{storage,bytecode}.pending.gauge`, `snapsync.memory.intake.paused.gauge` (#1501) | — | prism + forge |
| ↳ intake gate (#1501) | target (post-split) | `SnapIntakeBudget.scala`; the `intakeBudget` val in `SSC` implements the abstract `CoordinatorHandles.intakeBudget` (P1) and is passed to every coordinator spawn | unchanged; carried by `CoordinatorHandles` (P1) | `max-pending-storage-tasks`, `max-pending-bytecode-hashes` | `snapsync.memory.*.pending.gauge`, `snapsync.memory.intake.paused.gauge` | gate state in `[ACCOUNT-IDLE]` | prism; vault for replay interplay |
| **Stagnation watchdog**: account, storage and bytecode stall detection → refresh, restart or force-complete | current | `SSC`: `scheduleStagnationChecks`, `maybeRestartIfAccountStagnant`, `maybeRestartIfStorageStagnant`, `maybeForceCompleteIfBytecodeStagnant`, the `CheckDownloadStagnation` arm (progress `ask`s), companion `evaluateStorageTail` | `controller/StagnationWatchdog.scala` (M7) + `controller/StagnationPolicy.scala` (M1) | `account-stagnation-timeout` | — | `[STAGNATION]` | prism |
| **Healing orchestration**: trie walk, healing spawn, serve-root, healed-code hold, lazy anchor | current | `SSC`: `startTrieWalk`, `startStateHealing`, `startStateHealingWithInterleave` (**pin #1319** at both), `healingFrontierStorageOpt` (**pin spec-005** gate), `maybeRequestHealingServeRoot`, `triggerHealingForMissingNodes`, `queueHealedCode` family, `anchorPivotBeforeLazyHandoff`, `completeHealingWalkClean`, healing arms | `controller/HealingOrchestrator.scala` (M6b); spawn args deduped in M6a | `healing-*` (incl. `healing-visited-cap`, `healing-frontier-persistence`), `heal-hold-pivot-on-stagnation`, `scoped-heal-verification`, `scoped-heal-max-paths`, `decoupled-heal-serve-root`, `decoupled-heal-max-attempts-no-refresh`, `moving-root-delta-heal`, `pruned-heal-verification` (#1502: not forwarded to TNHC) | `snapsync.healing.nodes.healed.gauge` (controller side) | `[HEAL]`, `[HEAL-ABANDONED]`, `[HEAL-CODE]`, `[HEAL-INTERLEAVE]`, `[HEAL-LAZY-ANCHOR]`, `[HEAL-ROOT-UNSERVABLE]`, `[HEAL-SERVE-ROOT]`, `[HEAL-STAGNATED]`, `[HEAL-REPEG]` | prism + vault + forge; beacon for serve-root/CL |
| **State validation**: post-heal account/storage trie validation | current | `SSC`: `validateState`, `spawnAccountValidation`, `spawnStorageValidation`, validation arms; `StateValidator.scala` (real DFS, never rename) | `controller/StateValidationModule.scala` (M2) | `state-validation-enabled` | `snapsync.validation.failures.total`, `snapsync.validation.missing.nodes.gauge` | (untagged) | prism + forge |
| **Finalization**: completion, header hold, PathPublish, chain download / deferred backfill | current | `SSC`: `completeSnapSync`, `enterHeaderHold`, `leaveHeaderHold`, `finalizeSnapSync`, `startPathPublish`, `onPathPublishDone`, `startChainDownloader`, `launchChainDownloader`, `completed`, `completedWithBackfill`; `ChainDownloader.scala`; `PathToHashExporter.scala` | `controller/SnapFinalization.scala` (M4) | `chain-download-enabled`, `chain-download-max-concurrent-requests`, `chain-download-boosted-concurrent-requests`, `chain-backfill-concurrent-requests`, `defer-chain-backfill-until-state-complete`, `header-hold-stall-timeout`, `chain-download-timeout` | `snapsync.totaltime.minutes.gauge` | `[SNAP-COMPLETE]`, `[PATH-PUBLISH]`, `[HEAL-CODE]` | prism + beacon (PathPublish/backfill) + forge |
| **Lifecycle / reset / dormant**: critical failures, dormant mode, wake, restart, heap watchdog | current | `SSC`: `recordCriticalFailure`, `enterDormantMode`, `wakeFromDormant`, `dormantRetry`, `restartSnapSync`, `stopStateSyncChildren`, `stopSnapOnlySchedules`, `ensureHeapWatchdog`/`stopHeapWatchdog`/`onHeapPressureChange`/`onHeapWatchdogEscape` (#1501); `SnapHeapWatchdog.scala` (#1501) | `controller/SyncLifecycle.scala` (M9) using `PhaseFlags.reset(kind)` (`controller/PhaseFlags.scala`, P2, done), `cancelSyncTimers` (P2) and `CoordinatorHandles.stopAll()` (P1) | `max-snap-sync-failures`, `heap-watchdog-enabled`, `heap-watchdog-pause-fraction`, `heap-watchdog-resume-fraction`, `heap-watchdog-poll-interval`, `heap-watchdog-ineffective-after`, `heap-watchdog-max-pause` | `snapsync.errors.total`, `snapsync.memory.heap.pressure.gauge`, `snapsync.memory.oldgen.postgc.{bytes,ratio}.gauge` | `[SNAP-HEAP]` (pause / resume / ineffective / force-release) | prism + vault + forge |
| **AccountRange phase (coordinator)** | current | `actors/AccountRangeCoordinator.scala` (2,193), `actors/AccountRangeWorker.scala`, `AccountTask.scala` | core + `actors/account/{AccountPeerDispatch,AccountTaskLifecycle,AccountTrieAssembly,ContractWorkCarry}.scala` (C-A1…A4) | `account-concurrency`, `account-initial-response-bytes`, `account-min-response-bytes`, `max-inflight-per-peer` | `snapsync.accounts.*` | `[ACCOUNT-COORD]`, `[ACCOUNT-IDLE]`, `[ACCOUNT-STALL]`, `[ACCOUNT-STATE]`, `[ACCOUNT-COOLDOWN]`, `[ACCOUNT-FLOOR]`, `[ACCOUNT-REQUEUE]`, `[ACCOUNT-REDISPATCH]`, `[SNAP-PROGRESS]`, `[STORAGE-STATE]`, `[WORKER]` | prism; **vault** for C-A3/C-A4 |
| **ByteCode phase (coordinator)** | current | `actors/ByteCodeCoordinator.scala` (842), `actors/ByteCodeWorker.scala`, `ByteCodeTask.scala` | core + `actors/bytecode/{ByteCodeDispatch,ByteCodeResponseHandling}.scala` (C-B1) | `max-pending-bytecode-hashes` (#1501) | `snapsync.bytecodes.*`, `snapsync.bytecode.{backpressure,queue.depth,active_peers}.gauge`, `snapsync.bytecode.inflight.requests.gauge` (#1501) | `[SNAP-PROGRESS]` | prism |
| **StorageRange phase (coordinator)** | current | `actors/StorageRangeCoordinator.scala` (2,273), `actors/StorageRangeWorker.scala`, `StorageTask.scala`, `SnapPeerHealth.scala` | core + `actors/storage/{StoragePeerDispatch,StorageOrderedApply,StorageDoneMarkers,StorageResponseProcessing}.scala` (C-S1…S4) | `storage-concurrency`, `storage-batch-size`, `storage-initial-response-bytes`, `storage-min-response-bytes`, `max-concurrent-storage-accounts`, `deferred-merkleization`, `storage-max-inflight-per-peer-during-accounts`, `max-pending-storage-tasks` | `snapsync.storage.*`, `snapsync.storage.inflight.requests.gauge` (#1501) | `[STORAGE]`, `[STORAGE-STATE]`, `[STORAGE-FLOOR]`, `[STORAGE-FORCE-COMPLETE]`, `[STORAGE-PEER-HEALTH]`, `[STORAGE-REDISPATCH]`, `[STORAGE-WORKER]`, `[SNAP-PROGRESS]` | prism; **vault** for C-S2/C-S3 (spec 010 R2a) |
| **TrieNodeHealing phase (coordinator)** | current | `actors/TrieNodeHealingCoordinator.scala` (2,900), `actors/TrieNodeHealingWorker.scala`, `actors/GcPressureSampler.scala`, `HealingTask.scala`; `src/.../db/storage/{HealingFrontierStorage,BfsQueueStorage}.scala` | core + `actors/healing/{FrontierPersistence,FrontierRebuildBfs,HealVerification,HealDispatch,HealResponseProcessing}.scala` (C-H0…H5) | `healing-batch-size`, `healing-concurrency`, `healing-max-inflight-per-peer`, `healing-visited-cap`, `healing-frontier-persistence`, `healing-traversal-parallelism`, `healing-min-parallelism`, `healing-reserved-cores`, `healing-frontier-high-water`, `healing-frontier-low-water`, `scoped-heal-*`, `decoupled-heal-*`, `pruned-heal-verification` | `snapsync.healing.*` | `[HEAL]`, `[HEAL-BFS]`, `[HEAL-RESTART]`, `[HEAL-SERVE-ROOT]`, `[HEAL-VERIFY]`, `[HEAL-VERIFY-PRUNED]`, `[HEAL-VERIFY-SCOPED]`, `[HEAL-CODE]`, `[HEAL-FRONTIER]`, `[HEAL-FORCE-COMPLETE]`, `[HEAL-STAGNATION]`, `[HEAL-WATCHDOG]`, `[HEAL-FLOOR]`, `[HEAL-DISCOVER]`, `[HEAL-LEAF]`, `[HEAL-MILESTONE]`, `[HEAL-PULSE]`, `[HEALING-WORKER]` | prism; **vault** for C-H1/C-H3 |
| ↳ shared response-size window | current | duplicated in ARC, SRC and TNHC (`responseBytesTargetFor`, `adjustResponseBytesOn*`) | `AdaptiveResponseBytes.scala` (C-X); ByteCode keeps its own | `*-initial-response-bytes`, `*-min-response-bytes` | — | — | prism |
| **Requests, proofs, metrics plumbing** | current (not split) | `SNAPRequestTracker.scala`, `MerkleProofVerifier.scala`, `SNAPSyncMetrics.scala`, `SyncProgressMonitor.scala` | unchanged | — | `snapsync.requests.*`, `snapsync.proofs.invalid.total`, `snapsync.responses.malformed.total` | `[PROOF]` | prism; forge/beacon for proof verification |

---

## Module coupling: interface sizes (spec 016 FR-016 (c))

The baselines come from research.md R14 (state; method-attributed, `syncing` arms excluded) and R14a (Api calls, an
upper bound). "Recorded" holds the compiler-confirmed counts from the module's PR, written as `state / Api`. Each
slice fills in its own row, and no value may grow afterwards without a justification line.

| Module | `<Module>State` baseline (pre-P1/P2 → post) | Api members baseline (R14a, upper bound) | Capability traits | Recorded `state / Api` (PR) |
|---|---|---|---|---|
| M2 StateValidationModule | 5 → 3 | 1 | SnapSharedState, SnapControllerEnv | — |
| M3 SnapPeerPool | 8 → 2 | 3 | SnapSharedState, CoordinatorHandles, SnapControllerEnv | — |
| M4 SnapFinalization | 11 → 3 | 11 | SnapSharedState, CoordinatorHandles (ChainDownloaderHandle), PhaseFlags, SnapControllerEnv | — |
| M5 SnapResumePlanner | 2 → 1 (set at M5; resume code is inline today) | 14 | SnapSharedState, PhaseFlags, CoordinatorHandles (`intakeBudget`), SnapControllerEnv | — |
| M6 HealingOrchestrator | 20 → 10 | 11 | SnapSharedState, CoordinatorHandles, PhaseFlags, SnapControllerEnv | — |
| M7 StagnationWatchdog | 15 → 5 | 7 | SnapSharedState, CoordinatorHandles, PhaseFlags, SnapControllerEnv | — |
| M8 PivotSelector | 18 → 6 | 20 | SnapSharedState, CoordinatorHandles, PhaseFlags, SnapControllerEnv | — |
| M9 SyncLifecycle | 31 → 16 | 12 | SnapSharedState, CoordinatorHandles, PhaseFlags, SnapControllerEnv | — |
| M10 PivotRefresher | 20 → 11 | 12 | SnapSharedState, CoordinatorHandles, SnapControllerEnv | — |
| M11 DownloadSupervisor | 22 → 8 | 13 | SnapSharedState, CoordinatorHandles, PhaseFlags, SnapControllerEnv | — |
| Coordinator modules (C-*) | derived in T060 | derived in T060 | — | — |

**Caps on the shared capability traits.** These are member counts. Growth needs a justification line in the PR that
causes it.

| Trait | Cap | Set by |
|---|---|---|
| `SnapSharedState` | 5 hubs (getters, plus setters where written) | T041a |
| `CoordinatorHandles` | **12**: the 4 coordinator refs, `chainDownloader` (`ChainDownloaderHandle`), `def intakeBudget`, `stopChild`, `stopAll`, `broadcastPeerUnavailable`, `pivotRefreshed`, `reArmRangeCoordinators`, `forwardResponse`. Two more than the 10 the plan enumerated: `stopChild` (abstract, the core's `ctx.stop`; the trait cannot reach `ctx`, and `stopAll`/`ChainDownloaderHandle.stop` need it) and `reArmRangeCoordinators` (the two account + storage re-arm sites are a different fan-out from the four-way `pivotRefreshed`) | P1 |
| `PhaseFlags` | **10**: the 9 flags of FR-011 + `reset(kind)`. Recorded at P2: 10 (`verify.sh` `[counts] PhaseFlags=10`), equal to the cap. The `ResetKind` enum lives in `object PhaseFlags` and is not a member. `cancelSyncTimers` stays in the core (it needs `timers`) and is not a member | P2 |
| `SnapControllerEnv` | set at T041a | M2 |

## Pin tests (S0a/S0b): never edited by a move PR

Each pin test names its fix in the test name (FR-025). A move PR must leave these files byte-unchanged and green; a fix
PR that changes pinned behaviour on purpose (for example #1502) edits the one assertion it flips and says so.

| Pin | Fix | Where the pinned code lives | Test (suite: test name, abbreviated) |
|---|---|---|---|
| #1367 | TD-PROXY-GAP guard `ourBest > 0` | `network/NetworkPeerManagerActor.scala` | `NetworkPeerManagerSpec`: "#1367 TD-PROXY-GAP …" (S0a, T010) |
| #1378 | `apply()` calls `.start()`, arming the 5 s `PollHandshakedPeers` | `SSC` companion `apply` | `SNAPSyncControllerPinSpec`: "#1378: arm the 5 s handshaked-peer poll …" (T013) |
| #1319 / spec-002 | `frontierPersistenceEnabled` at both TNHC spawns; full spawn tuple on both routes | Healing orchestration (`startStateHealing`, `startStateHealingWithInterleave`) | `SNAPSyncControllerPinSpec`: four "#1319/spec-002: pass frontierPersistenceEnabled = true/false via …" tests and "#1319/spec-002: build the same healing coordinator via both routes" (T014) |
| spec-005 | `prunedHealVerification` default (case class, absent key), parse, store gate | `SNAPSyncConfig`; `healingFrontierStorageOpt` | `SNAPSyncControllerSpec`: three "spec-005: …" config tests; `SNAPSyncControllerPinSpec`: three "spec-005: create … healing frontier store …" tests (T015) |
| spec-005 / #1502 | the forwarded `prunedHealVerification` is asserted **as today** (always `true`) | Healing orchestration spawn args | `SNAPSyncControllerPinSpec`: "spec-005: forward pruned-heal-verification … as today (not forwarded, #1502)"; the value lives in one place, `todayForwardedPrunedHealVerification` (`// #1502`), which the #1502 fix flips |
| #1371 | no `storagePhaseForceCompleted` term in the skip-healing decision, at the call site | `checkAllDownloadsComplete` + companion `shouldSkipHealingAfterDownloads` (→ `HealPolicy`, M1) | `SNAPSyncControllerPinSpec`: "#1371: skip healing … storage force-completed" (+ control case) (T016); the helper-level tests in `SNAPSyncControllerSpec` stay |
| T012 seam | `ChildFactories` defaults equal each child `apply`'s defaults | `controller/ChildFactories.scala` | `ChildFactoriesSpec` (five tests) |

Each S0b pin was shown to fail when its pinned expression is reverted (T017; run links in the S0b PR, #1512).

The S0c golden vectors for every frozen on-disk format (`SnapFrozenFormatsGoldenSpec`) follow the same rule: never
edited by a move PR.

## Characterization tests (S0d): today's behaviour, including what looks wrong

They use the shared `SnapControllerFixture` (stubbed peer manager, `ManualTime`, recording `ChildFactories`, injected
intake budget and heap-watchdog seam, ephemeral stores) and assert **today's** behaviour. A move PR keeps them green
unchanged; a fix PR updates the assertion it changes on purpose.

| Area | Suite | Pins | Used by |
|---|---|---|---|
| Stagnation watchdog | `SNAPStagnationCharacterizationSpec` (T021) | which coordinator each phase asks; account stall → `RecoverStalledAccountTasks` + in-place refresh (+ 30 s debounce, retry, no-peer wait, paused/progress/no-work non-stalls); storage and bytecode below their wall-clock thresholds | M7, P4 |
| Reset completeness | `SNAPResetCharacterizationSpec` (T022) | `restartSnapSync`, `enterDormantMode` (critical failure), `wakeFromDormant`: children stopped/re-spawned (never the ChainDownloader), next spawn args, persisted flags, `GetStatus`, heap watchdog not stopped | P1, P2, M9 |
| Pivot refresh | `SNAPPivotRefreshCharacterizationSpec` (T023) | ETC/Hash: header bootstrap, child notifications, ChainDownloader `Pause`/`UpdateTarget`/`Resume`, persisted pivot/root, probe commit and probe timeout; ETH/Path/CL: re-peg on the CL head, no anchor write while healing | M10 (beacon reviews the ETH case) |
| #1501 budget and watchdog | `SNAPBudgetWatchdogPinSpec` (T025) | `IncrementalContractData` credit release (idle; `syncing` without coordinators), `RecoveryReplayPausedRetry` = 1 s and the gated recovery stream, watchdog start / stop in `onStop` and `stopSnapOnlySchedules` | P1, M5, M9, M11 |
| P1 fan-outs | `SNAPFanOutCharacterizationSpec` (T026) | peer-unavailable to every existing coordinator; each SNAP response to exactly its coordinator | P1 |
| `syncing` dispatch | `SNAPSyncingDispatchTableSpec` (T027) | every Command × each `SyncPhase` reachable in `syncing` (AccountRangeSync, ByteCodeAndStorageSync, StateHealing, StateValidation, Completed, Dormant): handled, catch-all ("Unhandled message in syncing state") or crash | P4a–e (arm-order oracle) |

**T020 decision: `-Wsafe-init` is not enabled.** scalac options are set per sbt module, not per package, so it cannot be
scoped to `snap`; on the whole root module it would report on code outside the split. The trait-initialization hazard
is covered by FR-016 (d) (`scripts/snap-split/verify.sh`, S0f) and by every controller suite constructing the controller.

**Known gap (needs a seam):** the storage-tail refresh/force-complete and the bytecode force-complete compare
hard-coded thresholds (10 min, 60 s, 2 min, 10 min) with `System.currentTimeMillis()`, which `ManualTime` does not
move. T021 pins only their below-threshold behaviour. Pinning the firing paths needs a `nowMs` clock seam on the
controller (proposed amendment to T012, its own PR before P4a/M7).

## Open items: where they belong (fix PRs, not move PRs)

| Item | Module |
|---|---|
| #1434 restart mid-heal wipes SNAP progress | Resume planner + Pivot selection (+ `SyncController` startup) |
| #1435 empty pivot body from older finalization | Finalization |
| #1502 `prunedHealVerification` not forwarded | Healing orchestration |
| #1503 unchecked pivot reuse when `networkBest == 0` | Resume planner |
| #1350 / #1349 heal-walk RSS / read-decode pipeline | TNHC FrontierRebuildBfs |
| #1431 negotiated snap version | Peer pool + network layer (herald) |
| off-thread `ctx.log`; status `ask` timeouts | controller core + Stagnation watchdog |
| heal convergence (latch; keep tasks across re-peg) | Healing orchestration × TNHC HealResponseProcessing |
| stale-pivot empty ranges classified as scarcity | ARC AccountTaskLifecycle × AccountPeerDispatch |
| refresh every ~10–13 min discards in-flight storage tries | Pivot refresh × SRC StorageOrderedApply |
