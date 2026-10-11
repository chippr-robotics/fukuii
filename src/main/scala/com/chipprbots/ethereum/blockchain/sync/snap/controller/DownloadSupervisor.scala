package com.chipprbots.ethereum.blockchain.sync.snap.controller

import org.apache.pekko.actor.typed.SupervisorStrategy
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.util.ByteString

import scala.concurrent.duration.*

import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
import com.chipprbots.ethereum.blockchain.sync.snap.ByteCodeTask
import com.chipprbots.ethereum.blockchain.sync.snap.ContractTaskFiles
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.*
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.SyncPhase.*
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncMetrics
import com.chipprbots.ethereum.blockchain.sync.snap.actors
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.SnapSyncProgressStorage
import com.chipprbots.ethereum.domain.TrieRoot

private[snap] trait AccountRangeStartApi:
  def startAccountRangeSync(rootHash: TrieRoot): Unit

private[snap] trait ByteCodeRequestApi:
  def requestByteCodes(): Unit

private[snap] trait AccountRangeLaunchApi:
  def launchAccountRangeWorkers(rootHash: TrieRoot, concurrency: Int): Unit

private[snap] trait AccountResumeSelectionApi:
  def selectAccountResume(
      rootHash: TrieRoot,
      effectiveConcurrency: Int,
      currentPivot: BigInt
  ): (Map[ByteString, ByteString], Option[ContractTaskFiles])

private[snap] trait DownloadSupervisorState:
  def coordinatorGeneration: Long
  def snapProgressStorage: SnapSyncProgressStorage
  def recordStorageDone: Boolean
  def SnapCapabilityCheckKey: String

/** SNAP download supervision (spec 016 M11, research.md R6 "Download supervision"): the account phase's start with its
  * snap-capability grace check (`startAccountRangeSync`), the accounts-complete recovery's bytecode and storage
  * coordinator spawns (`spawnRecoveryCoordinators`), the 1 s peer-availability ticks (`requestAccountRanges`,
  * `requestByteCodes`, `requestStorageRanges`), the hand-off from the three download phases to healing or completion
  * (`checkAllDownloadsComplete`) and the `eth_syncing` status (`currentSyncStatus`). The core keeps
  * `launchAccountRangeWorkers` (its resume selection is resume planning, still inline) and the dispatch: the request
  * ticks, the response routing, the progress and backpressure arms, `IncrementalContractData` and the phase-completion
  * arms are inline in `commonSyncingArms`, and they call these members.
  */
private[snap] trait DownloadSupervisor extends AccountRangeStartApi with ByteCodeRequestApi:
  self: DownloadSupervisorState & SnapSharedState & SnapControllerEnv & CoordinatorHandles & PhaseFlags & PeerPoolApi &
    SnapServerPeersApi & SnapPeerEvictionApi & ChainDownloadStartApi & FinalizationApi & HealingApi &
    AccountRangeLaunchApi =>

  /** Geth-aligned: check if all 3 concurrent download phases are complete. Only transitions to healing when accounts +
    * bytecodes + storage are ALL done. The sentinel pattern (NoMoreByteCodeTasks/NoMoreStorageTasks) ensures bytecodes
    * and storage cannot complete before accounts.
    */
  private[snap] def checkAllDownloadsComplete(): Unit =
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
  private[snap] def spawnRecoveryCoordinators(
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
                evmCodeStorage = evmCodeStorage,
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
                flatSlotStorage = flatSlotStorage,
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
                pathNodeStorage = pathNodeStorageOpt,
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
    val startingBlock = appStateStorage.getSyncStartingBlock()
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
