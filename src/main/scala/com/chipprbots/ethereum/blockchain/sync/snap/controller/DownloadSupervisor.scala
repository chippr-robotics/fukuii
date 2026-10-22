package com.chipprbots.ethereum.blockchain.sync.snap.controller

import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.SupervisorStrategy
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.util.ByteString

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*
import scala.util.Failure
import scala.util.Success

import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
import com.chipprbots.ethereum.blockchain.sync.snap.ByteCodeTask
import com.chipprbots.ethereum.blockchain.sync.snap.ContractTaskFiles
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.*
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.SyncPhase.*
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncMetrics
import com.chipprbots.ethereum.blockchain.sync.snap.actors
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.SnapStorageDoneStorage
import com.chipprbots.ethereum.db.storage.SnapSyncProgressStorage
import com.chipprbots.ethereum.domain.TrieRoot

private[snap] trait AccountRangeStartApi:
  def startAccountRangeSync(rootHash: TrieRoot): Unit

private[snap] trait ByteCodeRequestApi:
  def requestByteCodes(): Unit

private[snap] trait DownloadRecoveryApi:
  def spawnRecoveryCoordinators(
      rootBs: ByteString,
      storage: MptStorage,
      bytecodeAlreadyDone: Boolean,
      storageAlreadyDone: Boolean
  ): Unit
  def checkAllDownloadsComplete(): Unit

private[snap] trait DownloadSupervisorState:
  def coordinatorGeneration: Long
  def snapProgressStorage: SnapSyncProgressStorage
  def recordStorageDone: Boolean
  def SnapCapabilityCheckKey: String
  var pivotProbeRequestId: Option[BigInt]
  var consecutivePivotRefreshes: Int
  var criticalFailureCount: Int
  var accountsAtLastCriticalFailure: Long
  var dormantRetryCount: Int
  var lastAccountProgressMs: Long
  var lastBytecodeProgressMs: Long
  var lastStorageProgressMs: Long
  var storageStagnationRefreshAttempted: Boolean
  var storageContractProgressPct: Double
  var bytecodesEstimatedTotal: Long
  var preservedRangeProgress: Map[ByteString, ByteString]
  var preservedAtPivotBlock: Option[BigInt]
  var preservedTaskFiles: Option[ContractTaskFiles]
  var launchedAccountGeneration: Long
  var currentCarrySource: Option[ContractTaskFiles]
  def storageDoneStorage: SnapStorageDoneStorage
  implicit def ec: ExecutionContext

/** SNAP download supervision (spec 016 M11, research.md R6 "Download supervision"): the account phase's start with its
  * snap-capability grace check (`startAccountRangeSync`), the accounts-complete recovery's bytecode and storage
  * coordinator spawns (`spawnRecoveryCoordinators`), the 1 s peer-availability ticks (`requestAccountRanges`,
  * `requestByteCodes`, `requestStorageRanges`), the hand-off from the three download phases to healing or completion
  * (`checkAllDownloadsComplete`) and the `eth_syncing` status (`currentSyncStatus`). The core keeps
  * `launchAccountRangeWorkers` (its resume selection is resume planning, still inline) and the dispatch: the request
  * ticks, the response routing, the progress and backpressure arms, `IncrementalContractData` and the phase-completion
  * arms are inline in `commonSyncingArms`, and they call these members.
  */
private[snap] trait DownloadSupervisor extends AccountRangeStartApi with ByteCodeRequestApi with DownloadRecoveryApi:
  self: DownloadSupervisorState & SnapSharedState & SnapControllerEnv & CoordinatorHandles & PhaseFlags & PeerPoolApi &
    SnapServerPeersApi & SnapPeerEvictionApi & ChainDownloadStartApi & FinalizationApi & HealingApi & LifecycleApi &
    PivotProbeResponseApi & StagnationResetApi & StagnationScheduleApi & StorageDoneMarkersApi & ResumeApi &
    HeapWatchdogApi & AccountResumeSelectionApi =>

  private lazy val AccountProgressResetThreshold: Long = 100_000L

  /** Geth-aligned: check if all 3 concurrent download phases are complete. Only transitions to healing when accounts +
    * bytecodes + storage are ALL done. The sentinel pattern (NoMoreByteCodeTasks/NoMoreStorageTasks) ensures bytecodes
    * and storage cannot complete before accounts.
    */
  def checkAllDownloadsComplete(): Unit =
    if accountsComplete && bytecodePhaseComplete && storagePhaseComplete &&
      currentPhase != StateHealing && currentPhase != ChainDownloadCompletion && currentPhase != Completed
    then
      if SNAPSyncController.shouldSkipHealingAfterDownloads(
          snapSyncConfig,
          resumedStaleCursors
        )
      then
        // With deferred merkleization, trie nodes were never constructed during download —
        // only flat storage was written. A trie walk would find the entire internal trie "missing",
        // taking hours to scan and failing to heal (peers can't serve the full trie via GetTrieNodes).
        //
        // Skip healing/validation entirely. Regular sync's BlockImporter will fetch missing trie
        // nodes on-demand via GetTrieNodes (SNAP protocol) when block execution encounters them.
        // This is the "lazy healing" pattern used by geth's path-based storage.
        ctx.log.info(
          "All state downloads complete (accounts + bytecodes + storage). " +
            "Deferred merkleization enabled — skipping healing/validation phase. " +
            "Missing trie nodes will be fetched on-demand during block execution."
        )
        completeSnapSync()
      else
        if snapSyncConfig.deferredMerkleization && storagePhaseForceCompleted then
          ctx.log.warn(
            "All state downloads reached terminal state, but storage was force-completed with deferred " +
              "merkleization enabled. Starting healing instead of handing off a state with known holes."
          )
        else ctx.log.info("All state downloads complete (accounts + bytecodes + storage). Starting healing...")
        currentPhase = StateHealing
        startStateHealing()

  /** The recovery branch's copy of the bytecode and storage coordinator spawns (spec 016 restructure, CQ-SNAP-016-22),
    * with the recovery per-peer budget (bytecode 2, storage 3). Extracted verbatim from `recoverAccountsComplete`.
    */
  def spawnRecoveryCoordinators(
      rootBs: ByteString,
      storage: MptStorage,
      bytecodeAlreadyDone: Boolean,
      storageAlreadyDone: Boolean
  ): Unit =
    if !bytecodeAlreadyDone then
      bytecodeCoordinator = Some(
        ctx.spawn(
          Behaviors
            .supervise(
              childFactories.byteCodeCoordinator(
                stateIngest = stateIngest,
                networkPeerManager = networkPeerManager,
                requestTracker = requestTracker,
                batchSize = ByteCodeTask.DEFAULT_BATCH_SIZE,
                snapSyncController = ctx.self,
                intakeBudget = Some(intakeBudget)
              )
            )
            .onFailure[Throwable](
              SupervisorStrategy.restartWithBackoff(1.second, 10.seconds, 0.2).withMaxRestarts(3)
            ),
          s"bytecode-coordinator-$coordinatorGeneration",
          org.apache.pekko.actor.typed.DispatcherSelector.fromConfig("sync-dispatcher")
        )
      )
      bytecodeCoordinator.foreach(_ ! actors.ByteCodeCoordinator.StartByteCodeSync(Seq.empty))
      timers.startTimerWithFixedDelay(RequestByteCodes, RequestByteCodes, 1.second)

    if !storageAlreadyDone then
      forceCompleteStorageSent = false
      storageRangeCoordinator = Some(
        ctx.spawn(
          Behaviors
            .supervise(
              childFactories.storageRangeCoordinator(
                stateRoot = rootBs,
                networkPeerManager = networkPeerManager,
                requestTracker = requestTracker,
                mptStorage = storage,
                maxAccountsPerBatch = snapSyncConfig.storageBatchSize,
                maxInFlightRequests = snapSyncConfig.storageConcurrency,
                requestTimeout = snapSyncConfig.timeout,
                snapSyncController = ctx.self,
                initialMaxInFlightPerPeer = 3, // Recovery: accounts done, storage gets 3 of 5 per-peer budget
                initialResponseBytes = snapSyncConfig.storageInitialResponseBytes,
                minResponseBytes = snapSyncConfig.storageMinResponseBytes,
                deferredMerkleization = snapSyncConfig.deferredMerkleization,
                maxConcurrentStorageAccounts = snapSyncConfig.maxConcurrentStorageAccounts,
                snapProgressStorage = Some(snapProgressStorage),
                storageScheme = snapSyncConfig.storageScheme,
                stateIngest = stateIngest,
                recordStorageDone = recordStorageDone,
                intakeBudget = Some(intakeBudget)
              )
            )
            .onFailure[Throwable](
              SupervisorStrategy.restartWithBackoff(1.second, 10.seconds, 0.2).withMaxRestarts(3)
            ),
          s"storage-range-coordinator-$coordinatorGeneration",
          org.apache.pekko.actor.typed.DispatcherSelector.fromConfig("sync-dispatcher")
        )
      )
      storageRangeCoordinator.foreach(_ ! actors.StorageRangeCoordinator.StartStorageRangeSync(rootBs))
      timers.startTimerWithFixedDelay(RequestStorageRanges, RequestStorageRanges, 1.second)

    // Recovery budget: accounts done, bytecode=2, storage=3 (total 5 per peer)
    bytecodeCoordinator.foreach(_ ! actors.ByteCodeCoordinator.UpdateMaxInFlightPerPeer(2))

  def startAccountRangeSync(rootHash: TrieRoot): Unit =
    // Before starting workers, check if any connected peer supports the snap/1 protocol.
    // If no peers support snap, the workers will send requests that are silently ignored,
    // stalling sync until the 3-minute stagnation watchdog fires. Instead, check upfront
    // and schedule a grace period for peers to connect before going dormant.
    val snapPeerCount = peersToDownloadFrom.count { case (_, p) =>
      p.peerInfo.remoteStatus.supportsSnap
    }

    if snapPeerCount == 0 then
      val gracePeriod = snapSyncConfig.snapCapabilityGracePeriod
      ctx.log.warn(
        s"No peers with snap/1 capability found (${peersToDownloadFrom.size} peers connected, ${peersToDownloadFrom.size - snapPeerCount} without snap)"
      )
      ctx.log.warn(s"Scheduling snap capability check in ${gracePeriod.toSeconds}s before entering dormant mode")
      timers.startSingleTimer(SnapCapabilityCheckKey, CheckSnapCapability, gracePeriod)
    else
      // Use the full configured account concurrency regardless of startup peer count.
      // AccountRangeCoordinator's dispatch loop is asymmetric: peers serve ranges, not the
      // other way around, so 16 ranges with 4 peers just means each peer gets 4 ranges queued
      // and worker count scales up as more peers connect (see `maxWorkers` in
      // AccountRangeCoordinator). The previous `min(configured, peers@start)` froze the
      // task split at the initial-peer count for the whole sync — observed in production as
      // `4 workers/10 peers, 0/4 ranges done` permanently, leaving 6 peers' worth of
      // throughput on the table after the pool grew. PR #1278's ByteCode-worker-leak and
      // phase-guard fixes removed the original stagnation risk that motivated this cap.
      val effectiveConcurrency = snapSyncConfig.accountConcurrency.max(1)
      ctx.log.info(
        s"Starting account range sync with concurrency $effectiveConcurrency " +
          s"($snapPeerCount snap-capable peers at start, configured max ${snapSyncConfig.accountConcurrency})"
      )
      ctx.log.info("Using actor-based concurrency for account range sync")
      launchAccountRangeWorkers(rootHash, effectiveConcurrency)

      // Start periodic SNAP peer eviction to ensure we maintain enough SNAP-capable peers.
      // Non-SNAP peers filling all slots is the #1 cause of SNAP sync starvation.
      startSnapPeerEviction()

      // Start parallel chain download (headers, bodies, receipts from genesis to pivot)
      // Follows the Geth/Nethermind pattern of overlapping chain + state download.
      startChainDownloader()

      // Start proactive outbound dials to snap-server-peers so they are connected throughout ALL
      // phases (not only at StateHealing). core-geth's StaticNode dial to fukuii fails silently;
      // fukuii must initiate the connection itself.
      startSnapServerPeersScheduler()
    // end else (snapPeerCount > 0)

  private[snap] def requestAccountRanges(): Unit =
    // Notify coordinator of available peers. Filter does NOT require maxBlockNumber >= pivot
    // for the same reason as requestStorageRanges — stale-tracked-head false negatives starve
    // the coordinator. Stateless detection on the coordinator (strike-counted) handles peers
    // that actually can't serve the current pivot's state.
    accountRangeCoordinator.foreach { coordinator =>
      val snapPeers = snapServingPeers()

      SNAPSyncMetrics.setSnapCapablePeers(snapPeers.size)

      if snapPeers.isEmpty then ctx.log.debug("No SNAP-capable peers available for account range requests")
      else
        snapPeers.foreach { peer =>
          coordinator ! actors.AccountRangeCoordinator.PeerAvailable(peer)
        }
    }

  def requestByteCodes(): Unit =
    // Notify coordinator of available peers
    bytecodeCoordinator.foreach { coordinator =>
      val snapPeers = snapServingPeers()

      if snapPeers.isEmpty then ctx.log.debug("No SNAP-capable peers available for bytecode requests")
      else
        snapPeers.foreach { peer =>
          coordinator ! actors.ByteCodeCoordinator.ByteCodePeerAvailable(peer)
        }
    }

  private[snap] def requestStorageRanges(): Unit =
    // Notify coordinator of available peers.
    //
    // Filter intentionally does NOT require maxBlockNumber >= pivot — that filter was the
    // cause of the sepolia 2026-05-14 storage stall. Sepolia's pivot advances every ~3 min
    // (CL-driven) while peer maxBlockNumber only refreshes every 5 min (PR #1238 best-block
    // re-probe). After ~1 pivot cycle, all peers' tracked block was below the new pivot →
    // every broadcast filtered them all out → coordinator received no StoragePeerAvailable
    // → knownAvailablePeers stayed empty → 100K storage tasks pinned forever.
    //
    // Stale maxBlockNumber is a *tracking* artifact, not a *capability* statement. The peer
    // has the same SNAP state regardless of what we believe their head is. If they actually
    // can't serve our pivot's state, the coordinator's strike-counted stateless detection
    // catches it (5 strikes → flagged stateless). Same applies to bytecode/account.
    storageRangeCoordinator.foreach { coordinator =>
      val snapPeers = snapServingPeers()

      SNAPSyncMetrics.setSnapCapablePeers(snapPeers.size)

      if snapPeers.isEmpty then ctx.log.info("No SNAP-capable peers available for storage range requests")
      else
        snapPeers.foreach { peer =>
          coordinator ! actors.StorageRangeCoordinator.StoragePeerAvailable(peer)
        }
    }

  /** Convert SNAP sync progress to SyncProtocol.Status for eth_syncing RPC endpoint.
    *
    * Returns syncing status with:
    *   - startingBlock: The block number we started syncing from
    *   - currentBlock: The pivot block we're syncing state for
    *   - highestBlock: The pivot block (same as current for SNAP sync)
    *   - knownStates: Estimated total state nodes based on current phase
    *   - pulledStates: Number of state nodes synced so far
    */
  private[snap] def currentSyncStatus: SyncProtocol.Status =
    val progress = progressMonitor.currentProgress
    val startingBlock = stateIngest.appState.getSyncStartingBlock()
    val currentBlock = pivotBlock.getOrElse(startingBlock)

    /** Helper to get estimate or actual count, whichever is larger. Used as a defensive fallback when estimates are not
      * yet available (0).
      */
    def estimateOrActual(estimate: Long, actual: Long): Long = estimate.max(actual)

    // Calculate state progress based on current phase
    // SNAP sync involves multiple phases: accounts, bytecode, storage, healing
    val (pulledStates, knownStates) = currentPhase match
      case AccountRangeSync =>
        // In account range sync, we track accounts synced
        (progress.accountsSynced, estimateOrActual(progress.estimatedTotalAccounts, progress.accountsSynced))

      case ByteCodeAndStorageSync =>
        // Concurrent bytecode + storage sync
        val pulled = progress.accountsSynced + progress.bytecodesDownloaded + progress.storageSlotsSynced
        val known = progress.estimatedTotalAccounts + estimateOrActual(
          progress.estimatedTotalBytecodes,
          progress.bytecodesDownloaded
        ) +
          estimateOrActual(progress.estimatedTotalSlots, progress.storageSlotsSynced)
        (pulled, known)

      case StateHealing =>
        // In healing, we add nodes healed to the pulled count
        // For the known total, use the sum of all previous phases since we don't know
        // how many nodes need healing until validation discovers them
        val pulled = progress.accountsSynced + progress.bytecodesDownloaded +
          progress.storageSlotsSynced + progress.nodesHealed
        // Known is the sum of estimates from previous phases (healing adds no new estimate)
        val known = progress.estimatedTotalAccounts + progress.estimatedTotalBytecodes +
          progress.estimatedTotalSlots
        // Ensure known is at least as much as pulled (consistent with defensive fallback pattern)
        (pulled, estimateOrActual(known, pulled))

      case StateValidation =>
        // During validation, show total state synced
        val total = progress.accountsSynced + progress.bytecodesDownloaded +
          progress.storageSlotsSynced + progress.nodesHealed
        (total, total)

      case ChainDownloadCompletion =>
        // State sync done, just waiting for chain download
        val total = progress.accountsSynced + progress.bytecodesDownloaded +
          progress.storageSlotsSynced + progress.nodesHealed
        (total, total)

      case Idle | Completed | Dormant =>
        (0L, 0L)

    SyncProtocol.Status.Syncing(
      startingBlockNumber = startingBlock,
      blocksProgress = SyncProtocol.Status.Progress(currentBlock, currentBlock),
      stateNodesProgress = Some(SyncProtocol.Status.Progress(BigInt(pulledStates), BigInt(knownStates)))
    )

  /** A `commonSyncingArms` arm lifted into a class member (spec 016 lift before M11b, CQ-SNAP-016-32), body unchanged:
    * `CheckSnapCapability`, the end of the snap-capability grace period: launch the account phase if a snap peer is now
    * present, otherwise go dormant. `commonSyncingArms` delegates to it at the arm's original position, so the arm
    * order is unchanged.
    */
  private[snap] lazy val checkSnapCapabilityArms: PartialFunction[Command, Behavior[Command]] = {
    // Snap capability grace period check: if still no snap/1 peers, go dormant and retry on a fresh pivot
    case CheckSnapCapability =>
      val snapPeerCount = peersToDownloadFrom.count { case (_, p) =>
        p.peerInfo.remoteStatus.supportsSnap
      }
      if snapPeerCount > 0 && accountRangeCoordinator.isDefined then
        // #1516: the account phase is already running (a peer arrived inside the grace period and launched it), and
        // spawning a second coordinator under the same name throws InvalidActorNameException. Nothing to do.
        ctx.log.debug("Capability check: account range coordinator already running, not launching another")
        Behaviors.same
      else if snapPeerCount > 0 then
        val effectiveConcurrency = snapSyncConfig.accountConcurrency.max(1)
        ctx.log.info(
          s"Found $snapPeerCount snap-capable peer(s) during grace period, starting account range sync (concurrency=$effectiveConcurrency, peers=$snapPeerCount)"
        )
        stateRoot.foreach(launchAccountRangeWorkers(_, effectiveConcurrency))
        Behaviors.same
      else enterDormantMode("no snap-capable peers after the capability grace period")
  }

  /** A run of `commonSyncingArms` arms lifted into a class member (spec 016 lift before M11b, CQ-SNAP-016-32), bodies
    * unchanged: the three peer-availability ticks, adjacent, in their original order: `RequestAccountRanges`,
    * `RequestByteCodes` and `RequestStorageRanges`. `commonSyncingArms` delegates to it at the run's original position,
    * so the arm order is unchanged.
    */
  private[snap] lazy val downloadTickArms: PartialFunction[Command, Behavior[Command]] = {
    // Periodic request triggers
    case RequestAccountRanges =>
      requestAccountRanges()
      Behaviors.same

    case RequestByteCodes =>
      requestByteCodes()
      Behaviors.same

    case RequestStorageRanges =>
      requestStorageRanges()
      Behaviors.same
  }

  /** A run of `commonSyncingArms` arms lifted into a class member (spec 016 lift before M11b, CQ-SNAP-016-32), bodies
    * unchanged: the four SNAP response routing arms, adjacent, in their original order: `AccountRangeResponse` (with
    * the readiness-probe match that calls `handlePivotProbeResponse`), `ByteCodesResponse`, `StorageRangesResponse` and
    * `TrieNodesResponse` (its `StateValidation` test stays in the body). `commonSyncingArms` delegates to it at the
    * run's original position, so the arm order is unchanged.
    */
  private[snap] lazy val snapResponseArms: PartialFunction[Command, Behavior[Command]] = {
    // Handle SNAP protocol responses (wrapped in Command ADT by NetworkPeerManagerActor)
    case AccountRangeResponse(msg) =>
      // Intercept pivot readiness probe responses before forwarding to coordinator.
      pivotProbeRequestId match
        case Some(probeId) if msg.requestId == probeId =>
          handlePivotProbeResponse(msg)
        // lastProbeAttemptMs stays set; next stagnation tick after cooldown will re-probe
        case _ =>
          ctx.log.debug(s"Received AccountRange response: requestId=${msg.requestId}, accounts=${msg.accounts.size}")
          // Forward to the account range coordinator (it owns the workers).
          forwardResponse(msg)
      Behaviors.same

    case ByteCodesResponse(msg) =>
      ctx.log.debug(s"Received ByteCodes response: requestId=${msg.requestId}, codes=${msg.codes.size}")

      // Forward to the bytecode coordinator (it owns the workers).
      forwardResponse(msg)
      Behaviors.same

    case StorageRangesResponse(msg) =>
      ctx.log.debug(s"Received StorageRanges response: requestId=${msg.requestId}, slots=${msg.slots.size}")

      // Forward to the storage range coordinator (it owns the workers).
      forwardResponse(msg)
      Behaviors.same

    case TrieNodesResponse(msg) =>
      ctx.log.debug(s"Received TrieNodes response: requestId=${msg.requestId}, nodes=${msg.nodes.size}")

      // Forward to the trie node healing coordinator (it owns the workers).
      // Don't forward during validation — the healing coordinator has already
      // signalled complete and any responses still arriving from peers are
      // late chatter that must not race with the validation walk.
      if currentPhase != StateValidation then forwardResponse(msg)
      Behaviors.same
  }

  /** A `commonSyncingArms` arm lifted into a class member (spec 016 lift before M11b, CQ-SNAP-016-32), body unchanged:
    * `ProgressAccountsSynced`: the account counter, and on real progress the `consecutivePivotRefreshes` and
    * critical-failure resets. It is separated from the other progress arms by `AccountRangeProgressCmd` (M5's
    * checkpoint arm, not lifted). `commonSyncingArms` delegates to it at the arm's original position, so the arm order
    * is unchanged.
    */
  private[snap] lazy val progressAccountsSyncedArms: PartialFunction[Command, Behavior[Command]] = {
    case ProgressAccountsSynced(count) =>
      progressMonitor.incrementAccountsSynced(count)
      // Real account downloads mean SNAP is making progress — reset the pivot refresh counter.
      if count > 0 then
        consecutivePivotRefreshes = 0
        if criticalFailureCount > 0 then
          val currentTotal = progressMonitor.currentProgress.accountsSynced
          if currentTotal - accountsAtLastCriticalFailure >= AccountProgressResetThreshold then
            ctx.log.info(
              s"Resetting criticalFailureCount ($criticalFailureCount -> 0): " +
                s"${currentTotal - accountsAtLastCriticalFailure} accounts since last critical failure"
            )
            criticalFailureCount = 0
            accountsAtLastCriticalFailure = currentTotal
            dormantRetryCount = 0
      Behaviors.same
  }

  /** A run of `commonSyncingArms` arms lifted into a class member (spec 016 lift before M11b, CQ-SNAP-016-32), bodies
    * unchanged: the account-trie finalization arms, adjacent, in their original order:
    * `ProgressAccountsFinalizingTrie`, `AccountTrieFinalized`, `ProgressAccountsTrieFinalized` and
    * `AccountTrieFinalizationFailed` (a restart). `commonSyncingArms` delegates to it at the run's original position,
    * so the arm order is unchanged.
    */
  private[snap] lazy val accountTrieFinalizationArms: PartialFunction[Command, Behavior[Command]] = {
    case ProgressAccountsFinalizingTrie =>
      progressMonitor.setFinalizingTrie(true)
      // AccountRange can legitimately produce empty segments (count=0) while still progressing.
      // Treat any progress update as a liveness signal.
      lastAccountProgressMs = System.currentTimeMillis()
      Behaviors.same

    case AccountTrieFinalized(finalizedRoot) =>
      // Persist the finalized trie root hash so we can recover after restart.
      // With pivot refreshes, the finalized root differs from the pivot block header's stateRoot.
      // Diagnostic only: startup logs it against the pivot header, which is never rewritten (its hash covers stateRoot).
      ctx.log.info(
        "Persisting finalized account trie root: {}",
        finalizedRoot.take(8).toArray.map("%02x".format(_)).mkString
      )
      stateIngest.appState.putSnapSyncFinalizedRoot(finalizedRoot).commit()
      Behaviors.same

    case ProgressAccountsTrieFinalized =>
      progressMonitor.setFinalizingTrie(false)
      ctx.log.info("Account range trie finalization complete")
      Behaviors.same

    case AccountTrieFinalizationFailed(error) =>
      // Root mismatch: the trie we built doesn't hash to the pivot's state root.
      // This means peers returned empty/wrong data (e.g., snapshot not ready).
      // Restart with a fresh pivot rather than entering healing with corrupt state.
      ctx.log.error(s"Account trie finalization failed ($error) — restarting SNAP sync with fresh pivot")
      progressMonitor.setFinalizingTrie(false)
      restartSnapSync(s"account trie finalization failed: $error")
  }

  /** A run of `commonSyncingArms` arms lifted into a class member (spec 016 lift before M11b, CQ-SNAP-016-32), bodies
    * unchanged: the progress reports, adjacent, in their original order: `ProgressBytecodesDownloaded`,
    * `ProgressStorageSlotsSynced` (both write a stagnation clock), `ProgressNodesHealed`, `ProgressAccountEstimate` and
    * `ProgressStorageContracts` (writes `storageContractProgressPct`). `commonSyncingArms` delegates to it at the run's
    * original position, so the arm order is unchanged.
    */
  private[snap] lazy val downloadProgressArms: PartialFunction[Command, Behavior[Command]] = {
    case ProgressBytecodesDownloaded(count) =>
      progressMonitor.incrementBytecodesDownloaded(count)
      lastBytecodeProgressMs = System.currentTimeMillis()
      Behaviors.same

    case ProgressStorageSlotsSynced(count) =>
      progressMonitor.incrementStorageSlotsSynced(count)
      // Only reset stagnation timer on meaningful progress (>10 slots).
      // Trickle progress from stale in-flight responses or pivot refresh cycles
      // shouldn't keep the stagnation timer from eventually firing.
      if count > 10 then
        lastStorageProgressMs = System.currentTimeMillis()
        if count > 100 then storageStagnationRefreshAttempted = false
      Behaviors.same

    case ProgressNodesHealed(count) =>
      progressMonitor.incrementNodesHealed(count)
      Behaviors.same

    case ProgressAccountEstimate(estimatedTotal) =>
      progressMonitor.updateEstimates(accounts = estimatedTotal)
      Behaviors.same

    case ProgressStorageContracts(completed, total) =>
      if total > 0 then storageContractProgressPct = completed.toDouble / total
      progressMonitor.updateStorageContracts(completed, total)
      if completed > 0 && total > 0 then
        val currentSlots = progressMonitor.getStorageSlotsSynced
        val estimatedTotalSlots = (currentSlots.toDouble / completed * total).toLong
        progressMonitor.updateEstimates(slots = estimatedTotalSlots)
      Behaviors.same
  }

  /** A run of `commonSyncingArms` arms lifted into a class member (spec 016 lift before M11b, CQ-SNAP-016-32), bodies
    * unchanged: `StorageBackpressureChanged` and `ByteCodeBackpressureChanged`, adjacent, in their original order: both
    * forwarded to the account coordinator. `commonSyncingArms` delegates to it at the run's original position, so the
    * arm order is unchanged.
    */
  private[snap] lazy val downloadBackpressureArms: PartialFunction[Command, Behavior[Command]] = {
    case StorageBackpressureChanged(paused) =>
      // Forward the storage coordinator's pause/resume signal to the account coordinator so it
      // stops dispatching new account-range requests when storage is over its high-water mark.
      accountRangeCoordinator.foreach(_ ! actors.AccountRangeCoordinator.StorageQueuePressure(paused))
      Behaviors.same

    case ByteCodeBackpressureChanged(paused) =>
      // Same pattern for bytecodes — account-range completions enqueue bytecode tasks (in addition
      // to storage tasks), so the account coordinator must also pause when the bytecode queue is
      // over its high-water mark.
      accountRangeCoordinator.foreach(_ ! actors.AccountRangeCoordinator.ByteCodeQueuePressure(paused))
      Behaviors.same
  }

  /** A `commonSyncingArms` arm lifted into a class member (spec 016 lift before M11b, CQ-SNAP-016-32), body unchanged:
    * `IncrementalContractData`: the account coordinator's contract work, forwarded to the bytecode and storage
    * coordinators (the #1501 credit release when one is missing; writes `bytecodesEstimatedTotal`). `commonSyncingArms`
    * delegates to it at the arm's original position, so the arm order is unchanged.
    */
  private[snap] lazy val incrementalContractDataArms: PartialFunction[Command, Behavior[Command]] = {
    // Geth-aligned: bytecodes and storage are dispatched inline from each account batch.
    // IncrementalContractData arrives from AccountRangeCoordinator after every identifyContractAccounts() call.
    case IncrementalContractData(codeHashes, storageTasks, replayed) =>
      // Replayed (carried) codeHashes were identified in an earlier attempt and many were already fetched; the
      // bytecode coordinator drops the ones present locally (`skipPresent`). That lookup used to run here, on this
      // actor's thread: 2.5M RocksDB reads per Sepolia resume, minutes of mailbox lag (spec 014).
      // launchAccountRangeWorkers spawns both downstream coordinators in the same handler as the account coordinator,
      // so a missing coordinator cannot happen today; make it loud if a future change breaks that ordering.
      val noBytecodeCoordinator = codeHashes.nonEmpty && bytecodeCoordinator.isEmpty
      val noStorageCoordinator = storageTasks.nonEmpty && storageRangeCoordinator.isEmpty
      if noBytecodeCoordinator || noStorageCoordinator then
        ctx.log.error(
          s"IncrementalContractData (replayed=$replayed) with no downstream coordinator: dropping " +
            s"${codeHashes.size} codeHashes / ${storageTasks.size} storage tasks"
        )
        // The producer reserved this work in the intake gate; nothing will acknowledge it.
        intakeBudget.release(
          if noStorageCoordinator then storageTasks.size else 0,
          if noBytecodeCoordinator then codeHashes.size else 0
        )
      if codeHashes.nonEmpty then
        bytecodeCoordinator.foreach(
          _ ! actors.ByteCodeCoordinator.AddByteCodeTasks(codeHashes, skipPresent = replayed)
        )
        // Accumulate the running total of unique codeHashes for the dashboard. `codeHashes` is
        // already deduplicated upstream (Bloom filter in AccountRangeCoordinator), so summing
        // batch sizes gives the unique total. Replayed hashes already present locally are reported back by the
        // bytecode coordinator as downloaded, so the estimate and the progress count stay consistent.
        bytecodesEstimatedTotal += codeHashes.size
        progressMonitor.updateEstimates(bytecodes = bytecodesEstimatedTotal)
      if storageTasks.nonEmpty then
        storageRangeCoordinator.foreach(_ ! actors.StorageRangeCoordinator.AddStorageTasks(storageTasks))
      Behaviors.same
  }

  /** A `commonSyncingArms` arm lifted into a class member (spec 016 lift before M11b, CQ-SNAP-016-32):
    * `AccountRangeSyncComplete`: the storage-phase entry, with the handoff-file persistence and the `preserved*` clear.
    * `commonSyncingArms` delegates to it at the arm's original position, so the arm order is unchanged. Since #1603 it
    * also takes the two task-file infos (`StorageFileInfoReply`, `CodeHashesFileInfoReply`) the account coordinator
    * sends right after `AccountRangeSyncComplete`, so the handoff paths are persisted, and logged, on the actor thread.
    */
  private[snap] lazy val accountRangeSyncCompleteArms: PartialFunction[Command, Behavior[Command]] = {
    case AccountRangeSyncComplete =>
      if accountsComplete then ctx.log.info("Ignoring duplicate AccountRangeSyncComplete")
      else
        accountsComplete = true
        progressMonitor.startPhase(AccountRangeSync)
        ctx.log.info("Account range sync complete. Signaling NoMore to bytecode/storage coordinators.")

        // Persist accounts-complete flag for crash recovery (Step 7)
        stateIngest.appState.putSnapSyncAccountsComplete(true).commit()

        // The temp file paths for crash recovery are not asked for here (#1603). The account coordinator sends them
        // itself, as StorageFileInfoReply and CodeHashesFileInfoReply, right after this message and from the same
        // handler (so they arrive after it, and before the coordinator can stop after trie finalization). They are
        // persisted on the actor thread by the arms below. If a file info is missing, recovery restarts the accounts
        // phase, which is acceptable since account trie data survives.

        // Clear persisted range progress — account phase is done, no need to resume it. The resume checkpoint is
        // removed here unconditionally: if the storage file info is never persisted, a lingering all-complete record
        // could otherwise be loaded by a later fresh SNAP cycle (e.g. a re-snap after clearSnapSyncDone) and skip
        // accounts.
        stateRoot.foreach(root => snapProgressStorage.clearProgress(root.value))
        stateIngest.appState.removeSnapAccountResumeCheckpoint().commit()
        releaseHeldResumeCheckpoint()
        preservedRangeProgress = Map.empty
        preservedAtPivotBlock = None
        preservedTaskFiles = None

        // Reset consecutive pivot refreshes — account completion IS progress
        consecutivePivotRefreshes = 0

        // Signal that no more work will arrive (sentinel pattern — prevents premature completion)
        bytecodeCoordinator.foreach(_ ! actors.ByteCodeCoordinator.NoMoreByteCodeTasks)
        storageRangeCoordinator.foreach(_ ! actors.StorageRangeCoordinator.NoMoreStorageTasks)

        // Transition to ByteCodeAndStorageSync for status reporting and stagnation checks
        currentPhase = ByteCodeAndStorageSync
        progressMonitor.startPhase(ByteCodeAndStorageSync)

        // Redistribute per-peer budget: accounts done, give storage+bytecode more bandwidth.
        // Global budget remains 5 per peer: storage=3, bytecode=2.
        storageRangeCoordinator.foreach(_ ! actors.StorageRangeCoordinator.UpdateMaxInFlightPerPeer(3))
        // ByteCode budget 8 per peer: with 2 local ETC-capable peers (Besu + core-geth) that gives
        // 16 concurrent requests vs 4 at budget=2. External ETH-mainnet peers respond with empty quickly
        // and cool down; local peers handle the full load at <1ms RTT.
        bytecodeCoordinator.foreach(_ ! actors.ByteCodeCoordinator.UpdateMaxInFlightPerPeer(8))
        // Clear accumulated peer cooldowns and seed initial dispatch — without this, peers on
        // 2-min backoff from AccountRange skip ByteCode dispatch indefinitely (Layer 2 stall).
        bytecodeCoordinator.foreach(_ ! actors.ByteCodeCoordinator.ByteCodePivotRefreshed)
        requestByteCodes()

        // Cancel account-phase schedulers (no longer relevant)
        timers.cancel(CheckDownloadStagnation)
        timers.cancel(RequestAccountRanges)

        // Start storage + bytecode stagnation watchdogs now that accounts are done
        resetStagnationClocks(StagnationWatchdog.ResetReason.StoragePhaseEntry)
        scheduleStagnationChecks()

        checkAllDownloadsComplete()
      Behaviors.same

    // The two task-file infos the account coordinator sends after AccountRangeSyncComplete (#1603), persisted on the
    // actor thread with the same batches as before. A failure (the coordinator could not flush the file) is reported,
    // naming the path that was not persisted.
    case StorageFileInfoReply(Success(info)) =>
      // Retire the account resume checkpoint with the storage-file handoff too (idempotent; it is also removed
      // unconditionally on AccountRangeSyncComplete, so a missing file info cannot leave a stale all-complete record).
      stateIngest.appState
        .putSnapSyncStorageFilePath(info.filePath.toString)
        .and(stateIngest.appState.putSnapSyncStorageFileCount(Some(info.count)))
        .and(stateIngest.appState.removeSnapAccountResumeCheckpoint())
        .commit()
      ctx.log.info(s"Persisted storage file path for recovery: ${info.filePath} (${info.count} entries)")
      Behaviors.same

    case StorageFileInfoReply(Failure(e)) =>
      ctx.log.warn(
        "Storage file path NOT persisted for recovery: the account coordinator could not report it " +
          s"(${e.getClass.getSimpleName}: ${e.getMessage}). A restart before storage completes restarts the " +
          "accounts phase."
      )
      Behaviors.same

    case CodeHashesFileInfoReply(Success(info)) =>
      stateIngest.appState
        .putSnapSyncCodeHashesPath(info.filePath.toString)
        .and(stateIngest.appState.putSnapSyncCodeHashesCount(Some(info.count)))
        .commit()
      ctx.log.info(s"Persisted codeHashes file path for recovery: ${info.filePath} (${info.count} entries)")
      Behaviors.same

    case CodeHashesFileInfoReply(Failure(e)) =>
      ctx.log.warn(
        "CodeHashes file path NOT persisted for recovery: the account coordinator could not report it " +
          s"(${e.getClass.getSimpleName}: ${e.getMessage}). A restart before bytecode completes restarts the " +
          "accounts phase."
      )
      Behaviors.same
  }

  /** A run of `commonSyncingArms` arms lifted into a class member (spec 016 lift before M11b, CQ-SNAP-016-32), bodies
    * unchanged: the download phase-completion arms, adjacent, in their original order: `ByteCodeSyncComplete if
    * !bytecodePhaseComplete`, `StorageRangeSyncComplete if !storagePhaseComplete` and `StorageRangeSyncForceCompleted
    * if !storagePhaseComplete` (its phase test stays in the body). The guards are pure flag reads; a message whose
    * guard is false still falls through to the next arm of `commonSyncingArms`, as before. `commonSyncingArms`
    * delegates to it at the run's original position, so the arm order is unchanged.
    */
  private[snap] lazy val downloadPhaseCompleteArms: PartialFunction[Command, Behavior[Command]] = {
    case ByteCodeSyncComplete if !bytecodePhaseComplete =>
      bytecodePhaseComplete = true
      stateIngest.appState.putSnapSyncBytecodeComplete(true).commit()
      progressMonitor.setBytecodeComplete()
      val downloaded = progressMonitor.currentProgress.bytecodesDownloaded
      ctx.log.info(
        s"ByteCode sync complete ($downloaded bytecodes). Storage: $storagePhaseComplete, Accounts: $accountsComplete"
      )
      // Bytecode done — give storage the full per-peer budget (was 3/5, now 5/5).
      // On peer-limited networks (Mordor: ~10 peers), this nearly doubles storage throughput.
      if !storagePhaseComplete then
        storageRangeCoordinator.foreach { coord =>
          coord ! actors.StorageRangeCoordinator.UpdateMaxInFlightPerPeer(snapSyncConfig.maxInFlightPerPeer)
          ctx.log.info(
            s"Storage per-peer budget boosted to ${snapSyncConfig.maxInFlightPerPeer} (bytecode complete, full budget)"
          )
        }
      checkAllDownloadsComplete()
      Behaviors.same

    case StorageRangeSyncComplete if !storagePhaseComplete =>
      storagePhaseComplete = true
      storagePhaseForceCompleted = false
      stateIngest.appState.putSnapSyncStorageComplete(true).commit()
      // Storage is durably complete, so no resume replays storage tasks any more: the markers are garbage now.
      // (Not on force-complete — storage-complete is not persisted there and the recovery stream still reads them.)
      clearStorageDoneMarkers("storage phase complete")
      ctx.log.info(s"Storage range sync complete. ByteCode: $bytecodePhaseComplete, Accounts: $accountsComplete")
      checkAllDownloadsComplete()
      Behaviors.same

    case StorageRangeSyncForceCompleted if !storagePhaseComplete =>
      if currentPhase == ByteCodeAndStorageSync || currentPhase == StateHealing then
        storagePhaseComplete = true
        storagePhaseForceCompleted = true
        ctx.log.warn(
          s"Storage range sync was force-completed. ByteCode: $bytecodePhaseComplete, " +
            s"Accounts: $accountsComplete. SNAP will run healing/validation before handoff."
        )
        checkAllDownloadsComplete()
      else
        ctx.log.warn(
          s"StorageRangeSyncForceCompleted received during phase $currentPhase " +
            s"(consecutive-failures transient path) — ignoring storagePhaseComplete flag. " +
            s"Storage stagnation recovery remains active for ByteCodeAndStorageSync phase."
        )
      Behaviors.same
  }

  def launchAccountRangeWorkers(rootHash: TrieRoot, concurrency: Int): Unit =
    val effectiveConcurrency = if concurrency > 0 then concurrency else snapSyncConfig.accountConcurrency
    // Reset stagnation tracking for this phase.
    resetStagnationClocks(StagnationWatchdog.ResetReason.AccountLaunch)

    // Safety valve: only preserve range progress if pivot hasn't drifted too far.
    // Content-addressed MPT data is valid across adjacent pivots (~256 blocks apart),
    // but large drift means the state may have changed significantly.
    val currentPivot = pivotBlock.getOrElse(BigInt(0))

    val (resumeProgress, carriedTaskFiles) = selectAccountResume(rootHash, effectiveConcurrency, currentPivot)

    val storage = getOrCreateMptStorage(currentPivot)
    launchedAccountGeneration = coordinatorGeneration
    ensureHeapWatchdog()
    currentCarrySource = carriedTaskFiles

    accountRangeCoordinator = Some(
      ctx.spawn(
        Behaviors
          .supervise(
            childFactories.accountRangeCoordinator(
              stateRoot = rootHash.value,
              networkPeerManager = networkPeerManager,
              requestTracker = requestTracker,
              mptStorage = storage,
              concurrency = effectiveConcurrency,
              snapSyncController = ctx.self,
              resumeProgress = resumeProgress,
              initialMaxInFlightPerPeer =
                5, // Full per-peer budget during AccountRangeSync (storage+bytecode deferred to 0)
              initialResponseBytes = snapSyncConfig.accountInitialResponseBytes,
              minResponseBytes = snapSyncConfig.accountMinResponseBytes,
              storageScheme = snapSyncConfig.storageScheme,
              stateIngest = stateIngest,
              taskFileDir = snapSyncConfig.taskFileDir,
              carriedTaskFiles = carriedTaskFiles,
              progressGeneration = coordinatorGeneration,
              storageDone = Option.when(recordStorageDone)(storageDoneStorage),
              intakeBudget = Some(intakeBudget)
            )
          )
          .onFailure[Throwable](
            SupervisorStrategy.restartWithBackoff(1.second, 10.seconds, 0.2).withMaxRestarts(3)
          ),
        s"account-range-coordinator-$coordinatorGeneration",
        org.apache.pekko.actor.typed.DispatcherSelector.fromConfig("sync-dispatcher")
      )
    )

    // Start the coordinator
    accountRangeCoordinator.foreach(_ ! actors.AccountRangeCoordinator.StartAccountRangeSync(rootHash.value))

    // Periodically send peer availability notifications
    timers.startTimerWithFixedDelay(RequestAccountRanges, RequestAccountRanges, 1.second)

    scheduleStagnationChecks()

    // Schedule periodic rate tracker tuning (geth msgrate alignment: recalculate median RTT every 5s)
    if !timers.isTimerActive(TuneRateTracker) then
      timers.startTimerWithFixedDelay(TuneRateTracker, TuneRateTracker, 5.seconds)

    // Geth-aligned: create bytecode + storage coordinators upfront so they can receive
    // IncrementalContractData from the first account batch response. No phase gap.
    if bytecodeCoordinator.isEmpty then
      bytecodeCoordinator = Some(
        ctx.spawn(
          Behaviors
            .supervise(
              childFactories.byteCodeCoordinator(
                stateIngest = stateIngest,
                networkPeerManager = networkPeerManager,
                requestTracker = requestTracker,
                batchSize = ByteCodeTask.DEFAULT_BATCH_SIZE,
                snapSyncController = ctx.self,
                intakeBudget = Some(intakeBudget)
              )
            )
            .onFailure[Throwable](
              SupervisorStrategy.restartWithBackoff(1.second, 10.seconds, 0.2).withMaxRestarts(3)
            ),
          s"bytecode-coordinator-$coordinatorGeneration",
          org.apache.pekko.actor.typed.DispatcherSelector.fromConfig("sync-dispatcher")
        )
      )
      // Start with empty codeHashes — tasks arrive incrementally via AddByteCodeTasks
      bytecodeCoordinator.foreach(_ ! actors.ByteCodeCoordinator.StartByteCodeSync(Seq.empty))

      timers.startTimerWithFixedDelay(RequestByteCodes, RequestByteCodes, 1.second)

    if storageRangeCoordinator.isEmpty then
      val storage = getOrCreateMptStorage(currentPivot)

      forceCompleteStorageSent = false
      storageRangeCoordinator = Some(
        ctx.spawn(
          Behaviors
            .supervise(
              childFactories.storageRangeCoordinator(
                stateRoot = rootHash.value,
                networkPeerManager = networkPeerManager,
                requestTracker = requestTracker,
                mptStorage = storage,
                maxAccountsPerBatch = snapSyncConfig.storageBatchSize,
                maxInFlightRequests = snapSyncConfig.storageConcurrency,
                requestTimeout = snapSyncConfig.timeout,
                snapSyncController = ctx.self,
                // 2-per-peer during AccountRangeSync. Original design used 0 here to defer storage
                // dispatch until accounts completed (prevents stale-root timeouts triggering false
                // pivot refreshes). That assumption breaks on huge chains like sepolia: account
                // ranges never complete within a pivot serve window, so storage never gets a
                // non-zero budget and the queue grows unbounded until OOM. PR #1237's strike-counted
                // stateless detection + PR #1241's backpressure-release-on-pivot now make stale-root
                // timeouts a recoverable event rather than a failure cascade. Bump default ensures
                // storage can drain concurrently with account.
                initialMaxInFlightPerPeer = 2,
                initialResponseBytes = snapSyncConfig.storageInitialResponseBytes,
                minResponseBytes = snapSyncConfig.storageMinResponseBytes,
                deferredMerkleization = snapSyncConfig.deferredMerkleization,
                maxConcurrentStorageAccounts = snapSyncConfig.maxConcurrentStorageAccounts,
                snapProgressStorage = Some(snapProgressStorage),
                storageScheme = snapSyncConfig.storageScheme,
                stateIngest = stateIngest,
                recordStorageDone = recordStorageDone,
                intakeBudget = Some(intakeBudget)
              )
            )
            .onFailure[Throwable](
              SupervisorStrategy.restartWithBackoff(1.second, 10.seconds, 0.2).withMaxRestarts(3)
            ),
          s"storage-range-coordinator-$coordinatorGeneration",
          org.apache.pekko.actor.typed.DispatcherSelector.fromConfig("sync-dispatcher")
        )
      )
      // Start with empty tasks — tasks arrive incrementally via AddStorageTasks
      storageRangeCoordinator.foreach(_ ! actors.StorageRangeCoordinator.StartStorageRangeSync(rootHash.value))

      timers.startTimerWithFixedDelay(RequestStorageRanges, RequestStorageRanges, 1.second)

    // ByteCode and storage start with a reduced budget during account phase (per-peer concurrent
    // limit). On huge chains (sepolia, ETH mainnet) account ranges never fully complete within
    // a pivot serve window, so the old budget=0 path left storage/bytecode queues to grow until
    // OOM. PR #1237's strike-counted demotion + PR #1241's backpressure-release-on-pivot make
    // stale-root timeouts recoverable rather than failure cascades. See PR #1252.
    // During AccountRangeSync: accounts=5, storage=3 (configurable), bytecode=2 per peer.
    // Storage was 2: on Sepolia 2026-10-07 two peers carried ~80% of storage requests at 2 slots each while the pool
    // was otherwise idle. 3 matches the post-account storage budget (see the AccountRangeSync completion handler).
    bytecodeCoordinator.foreach(_ ! actors.ByteCodeCoordinator.UpdateMaxInFlightPerPeer(2))
    storageRangeCoordinator.foreach(
      _ ! actors.StorageRangeCoordinator.UpdateMaxInFlightPerPeer(
        SNAPSyncController.storageInFlightDuringAccounts(snapSyncConfig)
      )
    )

    progressMonitor.startPhase(AccountRangeSync)
