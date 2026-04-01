package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.actor.{Actor, ActorLogging, ActorRef, Props, Scheduler, Cancellable}
import org.apache.pekko.util.ByteString

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext
import scala.collection.mutable

import com.chipprbots.ethereum.blockchain.sync.{Blacklist, CacheBasedBlacklist, PeerListSupportNg, SyncProtocol}
import com.chipprbots.ethereum.db.storage.{AppStateStorage, EvmCodeStorage, FlatSlotStorage, MptStorage, StateStorage}
import com.chipprbots.ethereum.domain.{Block, BlockBody, BlockHeader, BlockchainReader, BlockchainWriter, ChainWeight}
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.p2p.messages.SNAP
import com.chipprbots.ethereum.network.p2p.messages.SNAP._
import com.chipprbots.ethereum.utils.{Config, Hex, Logger, MilestoneLog}
import com.chipprbots.ethereum.utils.Config.SyncConfig
import com.chipprbots.ethereum.utils.ByteStringUtils.ByteStringOps

class SNAPSyncController(
    blockchainReader: BlockchainReader,
    blockchainWriter: BlockchainWriter,
    appStateStorage: AppStateStorage,
    stateStorage: StateStorage,
    evmCodeStorage: EvmCodeStorage,
    flatSlotStorage: FlatSlotStorage,
    val networkPeerManager: ActorRef,
    val peerEventBus: ActorRef,
    val syncConfig: SyncConfig,
    snapSyncConfig: SNAPSyncConfig,
    val scheduler: Scheduler
)(implicit ec: ExecutionContext)
    extends Actor
    with ActorLogging
    with PeerListSupportNg {

  import SNAPSyncController._

  // Blacklist for PeerListSupportNg trait
  val blacklist: Blacklist = CacheBasedBlacklist.empty(1000)

  // Writable MptStorage, lazily created when pivot block number is known.
  // Uses getBackingStorage(pivotBlockNumber) to ensure nodes are tagged with the
  // correct block number for proper reference counting in pruning modes.
  private var mptStorage: Option[MptStorage] = None

  private def getOrCreateMptStorage(pivotBlockNumber: BigInt): MptStorage =
    mptStorage.getOrElse {
      val storage = stateStorage.getBackingStorage(pivotBlockNumber)
      mptStorage = Some(storage)
      log.info(s"Created writable MptStorage for pivot block $pivotBlockNumber")
      storage
    }

  // Error handler for retry logic and peer blacklisting
  private val errorHandler = new SNAPErrorHandler(
    maxRetries = snapSyncConfig.maxRetries,
    initialBackoff = 1.second,
    maxBackoff = 60.seconds,
    circuitBreakerThreshold = 10
  )

  // Actor-based coordinators
  private var accountRangeCoordinator: Option[ActorRef] = None
  private var bytecodeCoordinator: Option[ActorRef] = None
  private var storageRangeCoordinator: Option[ActorRef] = None
  private var trieNodeHealingCoordinator: Option[ActorRef] = None
  private var chainDownloader: Option[ActorRef] = None
  private var chainDownloadComplete: Boolean = false
  private var chainDownloadTimeoutTask: Option[Cancellable] = None

  // Monotonic counter appended to coordinator actor names so restarts don't collide
  // with still-stopping actors from the previous cycle.
  private var coordinatorGeneration: Long = 0

  private val requestTracker = new SNAPRequestTracker()(scheduler)

  private var currentPhase: SyncPhase = Idle
  private var pivotBlock: Option[BigInt] = None
  private var stateRoot: Option[ByteString] = None

  // Preserved account range progress across SNAP sync restarts (core-geth parity).
  // Maps range `last` hash → current `next` position for ALL ranges (not just completed ones).
  // Content-addressed MPT storage survives pivot changes (accounts keyed by keccak256 hash),
  // so already-traversed keyspace doesn't need re-downloading if the pivot
  // hasn't drifted too far (within MaxPreservedPivotDistance blocks).
  private var preservedRangeProgress: Map[ByteString, ByteString] = Map.empty
  private var preservedAtPivotBlock: Option[BigInt] = None
  private val MaxPreservedPivotDistance: BigInt = 256

  // Geth-aligned: all 3 coordinators run concurrently from first account response.
  // accountsComplete is set when AccountRangeSyncComplete arrives and NoMore sentinels are sent.
  private var accountsComplete: Boolean = false

  // Concurrent phase completion tracking (all 3 coordinators run in parallel)
  private var bytecodePhaseComplete: Boolean = false
  private var storagePhaseComplete: Boolean = false

  private val progressMonitor = new SyncProgressMonitor(scheduler)

  // Failure tracking for fallback to fast sync
  private var criticalFailureCount: Int = 0

  // Retry counter for validation failures to prevent infinite loops
  private var validationRetryCount: Int = 0
  private val MaxValidationRetries = 3
  private val ValidationRetryDelay = 500.millis

  // Retry counter for bootstrap-to-SNAP transition
  private var bootstrapRetryCount: Int = 0
  private val BootstrapRetryBaseDelay = 2.seconds
  private val BootstrapRetryMaxDelay = 60.seconds
  private val MaxBootstrapRetryDuration = 5.minutes
  private var bootstrapRetryStartMs: Long = 0L

  // Pivot restart guard (prevents noisy rapid restarts if peer head fluctuates)
  private var lastPivotRestartMs: Long = 0L
  private val MinPivotRestartInterval: FiniteDuration = 30.seconds

  // Consecutive pivot refresh counter: when all peers are repeatedly stateless after
  // pivot refreshes, it strongly indicates no peer has a snapshot database. Each
  // PivotStateUnservable increments this; any successful account download resets it.
  // After MaxConsecutivePivotRefreshes, we record a critical failure to accelerate
  // fallback to fast sync instead of cycling pivots for 75+ minutes.
  private var consecutivePivotRefreshes: Int = 0
  private val MaxConsecutivePivotRefreshes = 3

  // Pending pivot refresh: when refreshPivotInPlace() needs a header from a peer,
  // it requests a bootstrap and stores the pending pivot here. When BootstrapComplete
  // arrives in the syncing state, the refresh is completed.
  private var pendingPivotRefresh: Option[(BigInt, String)] = None

  // Scheduled tasks for periodic peer requests
  private var accountRangeRequestTask: Option[Cancellable] = None
  private var bytecodeRequestTask: Option[Cancellable] = None
  private var storageRangeRequestTask: Option[Cancellable] = None
  private var accountStagnationCheckTask: Option[Cancellable] = None
  private var storageStagnationCheckTask: Option[Cancellable] = None
  private var healingRequestTask: Option[Cancellable] = None
  private var storageStagnationRefreshAttempted: Boolean = false
  private var trieWalkInProgress: Boolean = false
  private var consecutiveUnproductiveHealingRounds: Int = 0
  private val maxUnproductiveHealingRounds: Int = 3 // After 3 rounds of finding same missing nodes with 0 healed, skip to validation
  private var bootstrapCheckTask: Option[Cancellable] = None
  private var pivotBootstrapRetryTask: Option[Cancellable] = None
  private var rateTrackerTuneTask: Option[Cancellable] = None

  private case object RetryPivotRefresh
  private case object CheckSnapCapability
  private case object TuneRateTracker
  private case object EvictNonSnapPeers
  private var snapCapabilityCheckTask: Option[Cancellable] = None
  private var snapPeerEvictionTask: Option[Cancellable] = None

  /** Like handlePeerListMessagesWithRateTracking, but also reactively triggers a bootstrap retry
    * when new SNAP-capable peers arrive during the bootstrapping state. Without this, the node
    * waits for the full exponential backoff timer (up to 60s) even though peers are already available.
    * Core-geth starts syncing within 200ms of first peer — we should too.
    */
  private def handlePeerListMessagesWithBootstrapReactivity: Receive = {
    case msg @ com.chipprbots.ethereum.network.NetworkPeerManagerActor.HandshakedPeers(peers) =>
      val hadSnapPeers = handshakedPeers.values.exists(_.peerInfo.remoteStatus.supportsSnap)
      val oldPeerIds = handshakedPeers.keySet
      handlePeerListMessages(msg)
      val newPeerIds = handshakedPeers.keySet
      (newPeerIds -- oldPeerIds).foreach { peerId =>
        requestTracker.rateTracker.addPeer(peerId.value)
      }
      (oldPeerIds -- newPeerIds).foreach { peerId =>
        requestTracker.rateTracker.removePeer(peerId.value)
      }
      // If we just gained our first SNAP-capable peer(s), cancel the backoff timer and retry immediately.
      val hasSnapPeers = handshakedPeers.values.exists(_.peerInfo.remoteStatus.supportsSnap)
      if (!hadSnapPeers && hasSnapPeers) {
        log.info(
          s"First SNAP-capable peer(s) detected during bootstrap (${handshakedPeers.size} total, " +
            s"${handshakedPeers.values.count(_.peerInfo.remoteStatus.supportsSnap)} snap). " +
            s"Cancelling backoff timer and retrying immediately."
        )
        bootstrapCheckTask.foreach(_.cancel())
        bootstrapCheckTask = None
        self ! RetrySnapSyncStart
      }

    case msg @ com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.PeerDisconnected(peerId) =>
      handlePeerListMessages(msg)
      requestTracker.rateTracker.removePeer(peerId.value)
  }

  /** Wrap handlePeerListMessages to also update the PeerRateTracker when peers connect/disconnect. Tracks previous peer
    * set to detect additions and removals.
    */
  private def handlePeerListMessagesWithRateTracking: Receive = {
    case msg @ com.chipprbots.ethereum.network.NetworkPeerManagerActor.HandshakedPeers(peers) =>
      val oldPeerIds = handshakedPeers.keySet
      handlePeerListMessages(msg) // updates handshakedPeers
      val newPeerIds = handshakedPeers.keySet
      // Add new peers to rate tracker
      (newPeerIds -- oldPeerIds).foreach { peerId =>
        requestTracker.rateTracker.addPeer(peerId.value)
      }
      // Remove departed peers from rate tracker
      (oldPeerIds -- newPeerIds).foreach { peerId =>
        requestTracker.rateTracker.removePeer(peerId.value)
      }

    case msg @ com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.PeerDisconnected(peerId) =>
      handlePeerListMessages(msg) // removes from handshakedPeers
      requestTracker.rateTracker.removePeer(peerId.value)
  }

  // Storage stagnation watchdog: if storage stops advancing while tasks remain, repivot/restart.
  // This addresses the common case where peers no longer serve the chosen pivot/state window.
  // Threshold must be generous enough to allow large chains to complete within the SNAP serve window.
  private val StorageStagnationThreshold: FiniteDuration = 20.minutes
  private val StorageStagnationCheckInterval: FiniteDuration = 30.seconds
  private var lastStorageProgressMs: Long = System.currentTimeMillis()

  // AccountRange stagnation watchdog: if no download progress occurs, repivot/restart (and possibly fallback).
  // Tracks both task completions AND accountsDownloaded as liveness signals. The sync is only
  // stalled if neither metric advances within the threshold window. This prevents false stagnation
  // triggers when accounts are being downloaded steadily but no single 1/16th chunk has finished yet
  // (common with many concurrent workers and few peers).
  private val AccountStagnationThreshold: FiniteDuration = snapSyncConfig.accountStagnationTimeout
  private val AccountStagnationCheckInterval: FiniteDuration = 30.seconds
  private var lastAccountProgressMs: Long = System.currentTimeMillis()
  private var lastAccountTasksCompleted: Int = 0
  private var lastAccountsDownloaded: Long = 0

  override def preStart(): Unit = {
    log.info("SNAP Sync Controller initialized")
    progressMonitor.startPeriodicLogging()
  }

  override def postStop(): Unit = {
    // Cancel all scheduled tasks
    accountRangeRequestTask.foreach(_.cancel())
    bytecodeRequestTask.foreach(_.cancel())
    storageRangeRequestTask.foreach(_.cancel())
    accountStagnationCheckTask.foreach(_.cancel())
    storageStagnationCheckTask.foreach(_.cancel())
    healingRequestTask.foreach(_.cancel())
    bootstrapCheckTask.foreach(_.cancel())
    pivotBootstrapRetryTask.foreach(_.cancel())
    snapCapabilityCheckTask.foreach(_.cancel())
    snapPeerEvictionTask.foreach(_.cancel())
    rateTrackerTuneTask.foreach(_.cancel())
    pivotFreshnessCheckTask.foreach(_.cancel())
    progressMonitor.stopPeriodicLogging()

    // Log final error handler statistics
    val retryStats = errorHandler.getRetryStatistics
    val peerStats = errorHandler.getPeerStatistics
    log.info(
      s"SNAP Sync error statistics: retries=${retryStats.totalRetryAttempts}, " +
        s"failed_tasks=${retryStats.tasksAtMaxRetries}, " +
        s"peer_failures=${peerStats.totalFailuresRecorded}, " +
        s"peers_blacklisted=${peerStats.peersRecommendedForBlacklist}"
    )

    log.info("SNAP Sync Controller stopped")
  }

  override def receive: Receive = idle

  def idle: Receive = {
    case Start =>
      log.info("Starting SNAP sync...")
      startSnapSync()

    case SyncProtocol.GetStatus =>
      sender() ! SyncProtocol.Status.NotSyncing
  }

  def syncing: Receive = handlePeerListMessagesWithRateTracking.orElse {
    // Periodic rate tracker tuning (geth msgrate alignment)
    case TuneRateTracker =>
      requestTracker.rateTracker.tune()

    // Periodic SNAP peer eviction: disconnect non-SNAP outgoing peers to free slots
    case EvictNonSnapPeers =>
      evictNonSnapPeers()

    // Snap capability grace period check: if still no snap/1 peers, fall back to fast sync
    case CheckSnapCapability =>
      val verifiedSnapPeerCount = peersToDownloadFrom.count { case (_, p) =>
        p.peerInfo.remoteStatus.supportsSnap && p.peerInfo.isServingSnap
      }
      val advertisedSnapPeerCount = peersToDownloadFrom.count { case (_, p) =>
        p.peerInfo.remoteStatus.supportsSnap
      }
      if (verifiedSnapPeerCount > 0) {
        val effectiveConcurrency = math.min(snapSyncConfig.accountConcurrency, verifiedSnapPeerCount).max(1)
        log.info(
          s"Found $verifiedSnapPeerCount verified snap-serving peer(s) ($advertisedSnapPeerCount advertised) during grace period, " +
            s"starting account range sync (concurrency=$effectiveConcurrency)"
        )
        stateRoot.foreach(launchAccountRangeWorkers(_, effectiveConcurrency))
      } else if (advertisedSnapPeerCount > 0) {
        log.warning(
          s"$advertisedSnapPeerCount peer(s) advertise snap/1 but none verified as serving. " +
            "Probes may still be pending."
        )
        requestSnapRetry("No snap/1 peers found within capability check timeout")
      } else {
        log.warning("No snap-capable peers found after grace period.")
        requestSnapRetry("No snap/1 peers found within capability check timeout")
      }

    // Periodic request triggers
    case RequestAccountRanges =>
      requestAccountRanges()

    case RequestByteCodes =>
      requestByteCodes()

    case RequestStorageRanges =>
      requestStorageRanges()

    case RequestTrieNodeHealing =>
      requestTrieNodeHealing()

    // Handle SNAP protocol responses
    case msg: AccountRange =>
      log.debug(s"Received AccountRange response: requestId=${msg.requestId}, accounts=${msg.accounts.size}")

      // Forward to the account range coordinator (it owns the workers).
      // Forwarding only to this actor's direct children is insufficient because workers are children of coordinators.
      accountRangeCoordinator.foreach(_ ! actors.Messages.AccountRangeResponseMsg(msg))

    case msg: ByteCodes =>
      log.debug(s"Received ByteCodes response: requestId=${msg.requestId}, codes=${msg.codes.size}")

      // Forward to the bytecode coordinator (it owns the workers).
      bytecodeCoordinator.foreach(_ ! actors.Messages.ByteCodesResponseMsg(msg))

    case msg: StorageRanges =>
      log.debug(s"Received StorageRanges response: requestId=${msg.requestId}, slots=${msg.slots.size}")

      // Forward to the storage range coordinator (it owns the workers).
      storageRangeCoordinator.foreach(_ ! actors.Messages.StorageRangesResponseMsg(msg))

    case msg: TrieNodes =>
      log.debug(s"Received TrieNodes response: requestId=${msg.requestId}, nodes=${msg.nodes.size}")

      // Forward to the trie node healing coordinator (it owns the workers).
      trieNodeHealingCoordinator.foreach(_ ! actors.Messages.TrieNodesResponseMsg(msg))

    case ProgressAccountsSynced(count) =>
      progressMonitor.incrementAccountsSynced(count)
      // Real account downloads mean SNAP is making progress — reset the pivot refresh counter.
      if (count > 0) consecutivePivotRefreshes = 0

    case actors.Messages.AccountRangeProgress(progress) =>
      preservedRangeProgress = progress
      if (preservedAtPivotBlock.isEmpty) {
        preservedAtPivotBlock = pivotBlock
      }
      val completedCount = progress.count { case (last, next) =>
        // A range is "complete" when next >= last (entire keyspace traversed)
        next == last || BigInt(1, next.toArray.padTo(32, 0.toByte)) >= BigInt(1, last.toArray.padTo(32, 0.toByte))
      }
      log.info(
        s"Preserved account range progress: ${progress.size} ranges ($completedCount fully complete)"
      )
      // Persist to disk for crash recovery
      val effectivePivot = preservedAtPivotBlock.getOrElse(BigInt(0))
      appStateStorage.putSnapSyncProgress(serializeSnapProgress(progress, effectivePivot)).commit()

    case ProgressAccountsFinalizingTrie =>
      progressMonitor.setFinalizingTrie(true)
      // AccountRange can legitimately produce empty segments (count=0) while still progressing.
      // Treat any progress update as a liveness signal.
      lastAccountProgressMs = System.currentTimeMillis()

    case AccountTrieFinalized(finalizedRoot) =>
      // Persist the finalized trie root hash so we can recover after restart.
      // With pivot refreshes, the finalized root differs from the pivot block header's stateRoot.
      // On startup, SyncController substitutes this root into the pivot block header.
      log.info("Persisting finalized account trie root: {}", finalizedRoot.take(8).toArray.map("%02x".format(_)).mkString)
      appStateStorage.putSnapSyncFinalizedRoot(finalizedRoot).commit()

    case ProgressAccountsTrieFinalized =>
      progressMonitor.setFinalizingTrie(false)
      log.info("Account range trie finalization complete")

    case ProgressBytecodesDownloaded(count) =>
      progressMonitor.incrementBytecodesDownloaded(count)

    case ProgressStorageSlotsSynced(count) =>
      progressMonitor.incrementStorageSlotsSynced(count)
      lastStorageProgressMs = System.currentTimeMillis()
      storageStagnationRefreshAttempted = false

    case ProgressNodesHealed(count) =>
      progressMonitor.incrementNodesHealed(count)
      consecutiveUnproductiveHealingRounds = 0 // Reset — healing made progress

    case ProgressAccountEstimate(estimatedTotal) =>
      progressMonitor.updateEstimates(accounts = estimatedTotal)

    case ProgressStorageContracts(completed, total) =>
      progressMonitor.updateStorageContracts(completed, total)
      if (completed > 0 && total > 0) {
        val currentSlots = progressMonitor.getStorageSlotsSynced
        val estimatedTotalSlots = (currentSlots.toDouble / completed * total).toLong
        progressMonitor.updateEstimates(slots = estimatedTotalSlots)
      }

    case PivotStateUnservable(rootHash, reason, emptyResponses) =>
      // When peers can no longer serve the current state root, refresh the pivot in-place
      // instead of restarting. This preserves downloaded trie data (content-addressed nodes
      // are ~99.9% valid across pivot changes) and avoids the download-stall-restart loop.
      val now = System.currentTimeMillis()
      if (now - lastPivotRestartMs < MinPivotRestartInterval.toMillis) {
        log.warning(
          s"Ignoring PivotStateUnservable due to restart guard " +
            s"(phase=$currentPhase, emptyResponses=$emptyResponses, reason=$reason)"
        )
      } else if (
        currentPhase == AccountRangeSync || currentPhase == StorageRangeSync || currentPhase == ByteCodeAndStorageSync
      ) {
        lastPivotRestartMs = now
        consecutivePivotRefreshes += 1
        log.info(s"Consecutive stateless pivot refreshes: $consecutivePivotRefreshes/$MaxConsecutivePivotRefreshes")
        if (consecutivePivotRefreshes >= MaxConsecutivePivotRefreshes) {
          if (accountsComplete) {
            // Geth/Besu aligned: NEVER restart after accounts complete.
            // Accounts are the most expensive phase (~85.9M on ETC). Bytecodes are
            // content-addressed and don't depend on state root. Storage tasks can be
            // refreshed in-place. Refresh pivot for storage coordinator only.
            log.warning(
              s"$consecutivePivotRefreshes consecutive unservable pivots during bytecode/storage. " +
                "Refreshing in-place (preserving accounts)."
            )
            consecutivePivotRefreshes = 0 // Reset — accounts completing IS progress
            refreshPivotInPlace(reason)
          } else {
            // Accounts still in progress — full restart is acceptable
            // but preserve range progress (existing preservedRangeProgress mechanism)
            log.warning(
              s"$consecutivePivotRefreshes consecutive pivot refreshes without progress. " +
                "Peers likely lack snapshot databases."
            )
            if (recordCriticalFailure(s"$consecutivePivotRefreshes consecutive stateless pivot refreshes")) {
              requestSnapRetry("Pivot state unservable after 3 consecutive refreshes")
            } else {
              restartSnapSync(s"consecutive stateless pivots ($consecutivePivotRefreshes): $reason")
            }
          }
        } else {
          refreshPivotInPlace(reason)
        }
      } else {
        log.info(s"Ignoring PivotStateUnservable in phase=$currentPhase (reason=$reason)")
      }

    // Handle pivot header bootstrap completion during active sync.
    // This arrives when refreshPivotInPlace() requested a header from a peer because
    // it wasn't available locally. The coordinator is still alive with all its state.
    case BootstrapComplete(pivotHeaderOpt) if pendingPivotRefresh.isDefined =>
      val (pendingPivot, reason) = pendingPivotRefresh.get
      pendingPivotRefresh = None
      pivotHeaderOpt match {
        case Some(header) =>
          log.info(s"Pivot header bootstrap complete for block ${header.number} (requested $pendingPivot)")
          completePivotRefreshWithStateRoot(pendingPivot, header, reason)
        case None =>
          log.warning(
            s"Pivot header bootstrap for block $pendingPivot returned no header. Falling back to full restart."
          )
          restartSnapSync(s"pivot refresh bootstrap returned no header for $pendingPivot: $reason")
      }

    // Handle pivot header bootstrap failure. The bootstrap exhausted all retries (with exponential
    // backoff) without fetching the header. Schedule a retry after 60s to give peers time to recover.
    case PivotBootstrapFailed(reason) if pendingPivotRefresh.isDefined =>
      val (pendingPivot, originalReason) = pendingPivotRefresh.get
      pendingPivotRefresh = None
      log.warning(
        s"Pivot header bootstrap failed for block $pendingPivot (reason: $reason, " +
          s"original: $originalReason). Scheduling retry in 60s."
      )
      pivotBootstrapRetryTask.foreach(_.cancel())
      pivotBootstrapRetryTask = Some(
        scheduler.scheduleOnce(60.seconds, self, RetryPivotRefresh)(context.dispatcher)
      )

    case RetryPivotRefresh =>
      pivotBootstrapRetryTask = None
      if (
        currentPhase == AccountRangeSync || currentPhase == StorageRangeSync || currentPhase == ByteCodeAndStorageSync
      ) {
        log.info("Retrying pivot refresh after bootstrap failure...")
        refreshPivotInPlace("retry after bootstrap failure")
      } else {
        log.info(s"Skipping pivot refresh retry — phase=$currentPhase no longer needs it")
      }

    // Geth-aligned: bytecodes and storage are dispatched inline from each account batch.
    // IncrementalContractData arrives from AccountRangeCoordinator after every identifyContractAccounts() call.
    case IncrementalContractData(codeHashes, storageTasks) =>
      if (codeHashes.nonEmpty) {
        bytecodeCoordinator.foreach(_ ! actors.Messages.AddByteCodeTasks(codeHashes))
      }
      if (storageTasks.nonEmpty) {
        storageRangeCoordinator.foreach(_ ! actors.Messages.AddStorageTasks(storageTasks))
      }

    case AccountRangeSyncComplete =>
      if (accountsComplete) {
        log.info("Ignoring duplicate AccountRangeSyncComplete")
      } else {
        accountsComplete = true
        val acctCount = progressMonitor.currentProgress.accountsSynced
        log.info("Account range sync complete. Signaling NoMore to bytecode/storage coordinators.")
        MilestoneLog.phase(s"Account download complete | ${acctCount} accounts")

        // Persist accounts-complete flag for crash recovery (Step 7)
        appStateStorage.putSnapSyncAccountsComplete(true).commit()

        // Persist temp file paths for crash recovery (non-blocking ask — if this fails,
        // recovery will do a full restart which is acceptable since account trie data survives)
        accountRangeCoordinator.foreach { coordinator =>
          import org.apache.pekko.pattern.ask
          import org.apache.pekko.util.Timeout
          implicit val timeout: Timeout = Timeout(5.seconds)
          (coordinator ? actors.Messages.GetStorageFileInfo)
            .mapTo[actors.Messages.StorageFileInfoResponse]
            .foreach { info =>
              if (info.filePath != null) {
                appStateStorage.putSnapSyncStorageFilePath(info.filePath.toString).commit()
                log.info(s"Persisted storage file path for recovery: ${info.filePath} (${info.count} entries)")
              }
            }
        }

        // Persist completed-storage-accounts file path for R6 crash recovery
        storageRangeCoordinator.foreach { coordinator =>
          import org.apache.pekko.pattern.ask
          import org.apache.pekko.util.Timeout
          implicit val timeout: Timeout = Timeout(5.seconds)
          (coordinator ? actors.Messages.GetCompletedStorageFilePath)
            .mapTo[actors.Messages.CompletedStorageFilePath]
            .foreach { info =>
              if (info.filePath != null) {
                appStateStorage.putSnapSyncCompletedStoragePath(info.filePath.toString).commit()
                log.info(s"Persisted completed-storage file path for recovery: ${info.filePath}")
              }
            }
        }

        // Clear persisted range progress — account phase is done, no need to resume it
        appStateStorage.putSnapSyncProgress("").commit()
        preservedRangeProgress = Map.empty
        preservedAtPivotBlock = None

        // Reset consecutive pivot refreshes — account completion IS progress
        consecutivePivotRefreshes = 0

        // Signal that no more work will arrive (sentinel pattern — prevents premature completion)
        bytecodeCoordinator.foreach(_ ! actors.Messages.NoMoreByteCodeTasks)
        storageRangeCoordinator.foreach(_ ! actors.Messages.NoMoreStorageTasks)

        // Transition to ByteCodeAndStorageSync for status reporting and stagnation checks
        currentPhase = ByteCodeAndStorageSync
        progressMonitor.startPhase(ByteCodeAndStorageSync)

        // All 3 coordinators already running concurrently. Bytecode and storage budgets
        // stay at 2 and 3 respectively — accounts freed up 5 per-peer slots that now
        // benefit storage/bytecode through reduced peer contention.

        // Cancel account stagnation checks (no longer relevant)
        accountStagnationCheckTask.foreach(_.cancel()); accountStagnationCheckTask = None

        // Start storage stagnation watchdog now that accounts are done
        lastStorageProgressMs = System.currentTimeMillis()
        scheduleStorageStagnationChecks()

        checkAllDownloadsComplete()
      }

    case ByteCodeSyncComplete =>
      bytecodePhaseComplete = true
      progressMonitor.setBytecodeComplete()
      val downloaded = progressMonitor.currentProgress.bytecodesDownloaded
      log.info(
        s"ByteCode sync complete ($downloaded bytecodes). Storage: $storagePhaseComplete, Accounts: $accountsComplete"
      )
      MilestoneLog.phase(s"Bytecode sync complete | $downloaded bytecodes")
      // Bytecode done — give storage the full per-peer budget (was 3/5, now 5/5).
      // On peer-limited networks (Mordor: ~10 peers), this nearly doubles storage throughput.
      if (!storagePhaseComplete) {
        storageRangeCoordinator.foreach { coord =>
          coord ! actors.Messages.UpdateMaxInFlightPerPeer(snapSyncConfig.maxInFlightPerPeer)
          log.info(s"Storage per-peer budget boosted to ${snapSyncConfig.maxInFlightPerPeer} (bytecode complete, full budget)")
        }
      }
      checkAllDownloadsComplete()

    case StorageRangeSyncComplete =>
      storagePhaseComplete = true
      log.info(s"Storage range sync complete. ByteCode: $bytecodePhaseComplete, Accounts: $accountsComplete")
      MilestoneLog.phase("Storage sync complete")
      checkAllDownloadsComplete()

    case HealingAllPeersStateless if currentPhase == StateHealing =>
      log.warning("All healing peers stateless — refreshing pivot in-place for healing")
      refreshPivotInPlace("all healing peers stateless")

    case StateHealingComplete =>
      log.info("Healing coordinator idle (no pending tasks, no active requests).")
      if (trieWalkInProgress) {
        // A trie walk is already running — its result will determine next step
        log.info("Trie walk in progress, waiting for result...")
      } else {
        // No walk in progress — start one to check for deeper missing nodes
        startTrieWalk()
      }

    case TrieWalkResult(missingNodes) if currentPhase == StateHealing =>
      trieWalkInProgress = false
      if (missingNodes.isEmpty) {
        log.info("Trie walk found no missing nodes — healing complete!")
        MilestoneLog.phase("Trie healing complete | 0 missing nodes")
        consecutiveUnproductiveHealingRounds = 0
        progressMonitor.startPhase(StateValidation)
        currentPhase = StateValidation
        validateState()
      } else {
        consecutiveUnproductiveHealingRounds += 1
        if (consecutiveUnproductiveHealingRounds >= maxUnproductiveHealingRounds) {
          log.warning(
            s"Healing stagnation: ${missingNodes.size} missing nodes persist after " +
              s"$consecutiveUnproductiveHealingRounds consecutive rounds with no progress. " +
              s"Proceeding to validation — regular sync will recover missing nodes on-demand."
          )
          MilestoneLog.error(
            s"Healing stagnation | ${missingNodes.size} missing nodes after $consecutiveUnproductiveHealingRounds rounds, proceeding to validation"
          )
          consecutiveUnproductiveHealingRounds = 0
          progressMonitor.startPhase(StateValidation)
          currentPhase = StateValidation
          validateState()
        } else {
          log.info(
            s"Trie walk found ${missingNodes.size} missing nodes — queuing for healing " +
              s"(round $consecutiveUnproductiveHealingRounds/$maxUnproductiveHealingRounds)"
          )
          trieNodeHealingCoordinator.foreach { coordinator =>
            coordinator ! actors.Messages.QueueMissingNodes(missingNodes)
          }
          // Schedule next overlapping trie walk — don't wait for healing to complete
          scheduler.scheduleOnce(2.minutes) {
            self ! ScheduledTrieWalk
          }(ec)
        }
      }

    case ScheduledTrieWalk if currentPhase == StateHealing =>
      startTrieWalk()

    case TrieWalkFailed(error) if currentPhase == StateHealing =>
      trieWalkInProgress = false
      log.error(s"Trie walk failed: $error. Retrying after delay...")
      scheduler.scheduleOnce(5.seconds) {
        self ! ScheduledTrieWalk
      }(ec)

    case StateValidationComplete =>
      log.info("State validation complete. SNAP sync finished!")
      MilestoneLog.phase(
        s"State validation complete | pivot=${pivotBlock.getOrElse("?")} root=${stateRoot.map(_.toHex.take(8)).getOrElse("?")}"
      )
      completeSnapSync()

    // Chain download runs in parallel — track progress and completion
    case ChainDownloader.Progress(h, b, r, t) =>
      progressMonitor.updateChainProgress(h, b, r, t)

    case ChainDownloader.Done =>
      log.info("Parallel chain download completed during SNAP state sync.")
      chainDownloadComplete = true

    case SyncProtocol.GetStatus =>
      sender() ! currentSyncStatus

    case GetProgress =>
      sender() ! progressMonitor.currentProgress

    case msg =>
      log.debug(s"Unhandled message in syncing state: $msg")
  }

  // Internal message for periodic storage stagnation checks
  private case object CheckStorageStagnation

  // Proactive pivot freshness check (Geth-aligned).
  // Geth refreshes the pivot when head > pivot + 2*fsMinFullBlocks - 8 = 120 blocks.
  // This ensures the pivot stays within the 128-block SNAP serve window with an 8-block
  // safety margin, rather than waiting for peers to start returning empty responses.
  private case object CheckPivotFreshness
  private val PivotFreshnessThreshold: Long = 120 // blocks (Geth: 2*64-8)
  private val PivotFreshnessCheckInterval: FiniteDuration = 30.seconds
  private var pivotFreshnessCheckTask: Option[Cancellable] = None

  private case class StorageCoordinatorProgress(stats: actors.StorageRangeCoordinator.SyncStatistics)

  private def scheduleStorageStagnationChecks(): Unit = {
    storageStagnationCheckTask.foreach(_.cancel())
    storageStagnationCheckTask = Some(
      scheduler.scheduleWithFixedDelay(
        StorageStagnationCheckInterval,
        StorageStagnationCheckInterval,
        self,
        CheckStorageStagnation
      )(ec)
    )
  }

  private def maybeRestartIfStorageStagnant(stats: actors.StorageRangeCoordinator.SyncStatistics): Unit = {
    if (currentPhase != StorageRangeSync && currentPhase != ByteCodeAndStorageSync) return

    // Only consider it a stall if there is still work to do.
    val workRemaining = stats.tasksPending > 0 || stats.tasksActive > 0
    if (!workRemaining) return

    val now = System.currentTimeMillis()
    val stalledForMs = now - lastStorageProgressMs
    if (stalledForMs < StorageStagnationThreshold.toMillis) return

    // Avoid noisy rapid restarts.
    if (now - lastPivotRestartMs < MinPivotRestartInterval.toMillis) return
    lastPivotRestartMs = now

    if (!storageStagnationRefreshAttempted) {
      // First stagnation detection: try a pivot refresh (cheaper than force-completing).
      // A fresh pivot may bring peers back to a serveable root, resuming storage download.
      storageStagnationRefreshAttempted = true
      log.warning(
        s"Storage sync stalled: no progress for ${stalledForMs / 1000}s " +
          s"(threshold=${StorageStagnationThreshold.toSeconds}s), tasksPending=${stats.tasksPending}, " +
          s"tasksActive=${stats.tasksActive}. Attempting pivot refresh before force-completing."
      )
      // Reset timer to give the pivot refresh time to work (re-fires in another 20 min if refresh doesn't help)
      lastStorageProgressMs = now
      refreshPivotInPlace(s"storage stagnation: no progress for ${stalledForMs / 1000}s")
    } else {
      // Second stagnation after a refresh attempt already failed — force-complete and promote to healing.
      // The healing phase walks the state trie via GetTrieNodes (content-addressed by hash), which works
      // even when peers can't serve StorageRanges at the current pivot block.
      log.warning(
        s"Storage sync stalled after pivot refresh attempt: no progress for ${stalledForMs / 1000}s " +
          s"(threshold=${StorageStagnationThreshold.toSeconds}s), tasksPending=${stats.tasksPending}, " +
          s"tasksActive=${stats.tasksActive}. Promoting to healing phase (preserving downloaded state)."
      )

      // Cancel storage-phase timers
      storageRangeRequestTask.foreach(_.cancel()); storageRangeRequestTask = None
      storageStagnationCheckTask.foreach(_.cancel()); storageStagnationCheckTask = None

      // Tell coordinator to flush downloaded data and report completion
      storageRangeCoordinator.foreach(_ ! actors.Messages.ForceCompleteStorage)
    }
  }

  /** Proactive pivot freshness check (Geth-aligned).
    *
    * Unlike the reactive PivotStateUnservable approach (which waits until peers return empty responses),
    * this proactively refreshes the pivot when the network head has advanced 120 blocks past it.
    * This matches Geth's behavior: `head > pivot + 2*fsMinFullBlocks - 8` (120 blocks),
    * leaving an 8-block safety margin before the 128-block SNAP serve window expires.
    *
    * Benefits:
    *   - No wasted requests on a soon-to-be-stale root
    *   - No false PivotStateUnservable triggers
    *   - Predictable refresh cadence (~26 min on ETC at 13s/block)
    */
  private def maybeProactivelyRefreshPivot(): Unit = {
    if (pendingPivotRefresh.isDefined) return // Already refreshing

    val now = System.currentTimeMillis()
    if (now - lastPivotRestartMs < MinPivotRestartInterval.toMillis) return

    (pivotBlock, currentNetworkBestFromSnapPeers()) match {
      case (Some(pivot), Some(networkBest)) if networkBest > pivot =>
        val drift = networkBest - pivot
        if (drift > PivotFreshnessThreshold) {
          log.info(
            s"Proactive pivot refresh: network head $networkBest is $drift blocks ahead of pivot $pivot " +
              s"(threshold=$PivotFreshnessThreshold). Refreshing in-place to stay within SNAP serve window."
          )
          lastPivotRestartMs = now
          refreshPivotInPlace(s"proactive: head drifted $drift blocks (>${PivotFreshnessThreshold})")
        }
      case _ => // No pivot or no peers — nothing to do
    }
  }

  private def schedulePivotFreshnessChecks(): Unit = {
    pivotFreshnessCheckTask.foreach(_.cancel())
    pivotFreshnessCheckTask = Some(
      scheduler.scheduleWithFixedDelay(
        PivotFreshnessCheckInterval,
        PivotFreshnessCheckInterval,
        self,
        CheckPivotFreshness
      )(ec)
    )
  }

  // Internal message for periodic account stagnation checks
  private case object CheckAccountStagnation

  private case class AccountCoordinatorProgress(progress: actors.AccountRangeStats)

  private def scheduleAccountStagnationChecks(): Unit = {
    accountStagnationCheckTask.foreach(_.cancel())
    accountStagnationCheckTask = Some(
      scheduler.scheduleWithFixedDelay(
        AccountStagnationCheckInterval,
        AccountStagnationCheckInterval,
        self,
        CheckAccountStagnation
      )(ec)
    )
  }

  /** Geth-aligned: check if all 3 concurrent download phases are complete. Only transitions to healing when accounts +
    * bytecodes + storage are ALL done. The sentinel pattern (NoMoreByteCodeTasks/NoMoreStorageTasks) ensures bytecodes
    * and storage cannot complete before accounts.
    */
  private def checkAllDownloadsComplete(): Unit =
    if (accountsComplete && bytecodePhaseComplete && storagePhaseComplete &&
        currentPhase != StateHealing && currentPhase != ChainDownloadCompletion && currentPhase != Completed) {
      if (snapSyncConfig.deferredMerkleization) {
        // With deferred merkleization, trie nodes were never constructed during download —
        // only flat storage was written. A trie walk would find the entire internal trie "missing",
        // taking hours to scan and failing to heal (peers can't serve the full trie via GetTrieNodes).
        //
        // Skip healing/validation entirely. Regular sync's BlockImporter will fetch missing trie
        // nodes on-demand via GetTrieNodes (SNAP protocol) when block execution encounters them.
        // This is the "lazy healing" pattern used by geth's path-based storage.
        log.info(
          "All state downloads complete (accounts + bytecodes + storage). " +
            "Deferred merkleization enabled — skipping healing/validation phase. " +
            "Missing trie nodes will be fetched on-demand during block execution."
        )
        completeSnapSync()
      } else {
        log.info("All state downloads complete (accounts + bytecodes + storage). Starting healing...")
        MilestoneLog.phase("Trie healing starting")
        currentPhase = StateHealing
        startStateHealing()
      }
    }

  def bootstrapping: Receive = handlePeerListMessagesWithBootstrapReactivity.orElse {
    case BootstrapComplete(pivotHeaderOpt) =>
      log.info("=" * 80)
      log.info("✅ Bootstrap phase complete - transitioning to SNAP sync")
      log.info("=" * 80)

      // Get the bootstrap target that we synced to (this is the pivot we wanted)
      val bootstrapTarget = appStateStorage.getSnapSyncBootstrapTarget()
      val localBestBlock = appStateStorage.getBestBlockNumber()

      log.info(s"Bootstrap target: ${bootstrapTarget.getOrElse("none")}, Local best block: $localBestBlock")

      // Clear bootstrap target from storage now that we've read it
      appStateStorage.clearSnapSyncBootstrapTarget().commit()

      // Reset retry counter
      bootstrapRetryCount = 0

      // Helper: compute current best height from SNAP-capable peers (subject to bootstrapPivot floor).
      def currentNetworkBestFromSnapPeers(bootstrapPivot: BigInt): Option[BigInt] = {
        val snapPeersForPivot =
          peersToDownloadFrom.values.toList
            .filter(_.peerInfo.remoteStatus.supportsSnap)
            .filter(p => bootstrapPivot == 0 || p.peerInfo.maxBlockNumber >= bootstrapPivot)

        snapPeersForPivot
          .sortBy(_.peerInfo.maxBlockNumber)(bigIntReverseOrdering)
          .headOption
          .map(_.peerInfo.maxBlockNumber)
      }

      def pivotTooStaleAgainstNetworkHead(pivot: BigInt): Boolean = {
        val bootstrapPivot = appStateStorage.getBootstrapPivotBlock()
        currentNetworkBestFromSnapPeers(bootstrapPivot) match {
          case Some(networkBest) if networkBest > 0 =>
            val delta = networkBest - pivot
            if (delta > snapSyncConfig.maxPivotStalenessBlocks) {
              log.warning(
                s"Bootstrapped pivot $pivot is now $delta blocks behind current network best $networkBest; " +
                  s"exceeds maxPivotStaleness=${snapSyncConfig.maxPivotStalenessBlocks}. Re-selecting a fresher pivot."
              )
              true
            } else {
              log.info(
                s"Bootstrapped pivot freshness: pivot=$pivot, networkBest=$networkBest, delta=$delta, " +
                  s"maxPivotStaleness=${snapSyncConfig.maxPivotStalenessBlocks}"
              )
              false
            }
          case _ =>
            // If we can't see any suitable SNAP peers right now, don't block on freshness.
            false
        }
      }

      pivotHeaderOpt match {
        case Some(header) =>
          val targetPivot = header.number

          if (pivotTooStaleAgainstNetworkHead(targetPivot)) {
            // Don't commit a pivot that peers are unlikely to serve.
            startSnapSync()
          } else {
            pivotBlock = Some(targetPivot)
            stateRoot = Some(header.stateRoot)
            appStateStorage.putSnapSyncPivotBlock(targetPivot).commit()
            appStateStorage.putSnapSyncStateRoot(header.stateRoot).commit()
            updateBestBlockForPivot(header, targetPivot)

            SNAPSyncMetrics.setPivotBlockNumber(targetPivot)

            log.info("=" * 80)
            log.info("🎯 SNAP Sync Ready (from bootstrap)")
            log.info("=" * 80)
            log.info(s"Local best block: $localBestBlock")
            log.info(s"Using bootstrapped pivot block: $targetPivot")
            log.info(s"State root: ${header.stateRoot.toHex.take(16)}...")
            log.info(s"Beginning fast state sync with ${snapSyncConfig.accountConcurrency} concurrent workers")
            log.info("=" * 80)
            MilestoneLog.phase(
              s"SNAP account download starting | pivot=$targetPivot root=${header.stateRoot.toHex.take(8)} workers=${snapSyncConfig.accountConcurrency}"
            )

            currentPhase = AccountRangeSync
            startAccountRangeSync(header.stateRoot)
            context.become(syncing)
          }

        case None =>
          // Backward-compat / fallback: use the stored bootstrap target and local header.
          bootstrapTarget match {
            case Some(targetPivot) =>
              if (pivotTooStaleAgainstNetworkHead(targetPivot)) {
                startSnapSync()
              } else {
                blockchainReader.getBlockHeaderByNumber(targetPivot) match {
                  case Some(header) =>
                    pivotBlock = Some(targetPivot)
                    stateRoot = Some(header.stateRoot)
                    appStateStorage.putSnapSyncPivotBlock(targetPivot).commit()
                    appStateStorage.putSnapSyncStateRoot(header.stateRoot).commit()
                    updateBestBlockForPivot(header, targetPivot)

                    SNAPSyncMetrics.setPivotBlockNumber(targetPivot)

                    log.info("=" * 80)
                    log.info("🎯 SNAP Sync Ready (from bootstrap, local header)")
                    log.info("=" * 80)
                    log.info(s"Local best block: $localBestBlock")
                    log.info(s"Using bootstrapped pivot block: $targetPivot")
                    log.info(s"State root: ${header.stateRoot.toHex.take(16)}...")
                    log.info(s"Beginning fast state sync with ${snapSyncConfig.accountConcurrency} concurrent workers")
                    log.info("=" * 80)

                    currentPhase = AccountRangeSync
                    startAccountRangeSync(header.stateRoot)
                    context.become(syncing)

                  case None =>
                    log.warning(s"Bootstrap complete but pivot header $targetPivot not available yet")
                    log.warning("Falling back to recalculating pivot from current network state")
                    startSnapSync()
                }
              }
            case None =>
              log.info("No bootstrap target stored - calculating pivot from network state")
              startSnapSync()
          }
      }

    case RetrySnapSyncStart =>
      log.info("🔄 Retrying SNAP sync start after bootstrap delay...")
      // Clear the bootstrap check task since it has fired
      bootstrapCheckTask = None
      startSnapSync()

    case GetProgress =>
      // During bootstrap, report that we're preparing for SNAP sync
      val currentBlock = appStateStorage.getBestBlockNumber()
      val targetBlock = appStateStorage.getSnapSyncBootstrapTarget().getOrElse(BigInt(0))

      log.info(s"Bootstrap progress: $currentBlock / $targetBlock blocks")

      // Send a simple progress indicator - we're in bootstrap mode
      sender() ! SyncProgress(
        phase = Idle,
        accountsSynced = 0,
        bytecodesDownloaded = 0,
        storageSlotsSynced = 0,
        nodesHealed = 0,
        elapsedSeconds = 0,
        phaseElapsedSeconds = 0,
        accountsPerSec = 0,
        bytecodesPerSec = 0,
        slotsPerSec = 0,
        nodesPerSec = 0,
        recentAccountsPerSec = 0,
        recentBytecodesPerSec = 0,
        recentSlotsPerSec = 0,
        recentNodesPerSec = 0,
        phaseProgress = if (targetBlock == 0) 0 else ((currentBlock.toDouble / targetBlock.toDouble) * 100).toInt,
        estimatedTotalAccounts = 0,
        estimatedTotalBytecodes = 0,
        estimatedTotalSlots = 0,
        startTime = System.currentTimeMillis(),
        phaseStartTime = System.currentTimeMillis()
      )

    case SyncProtocol.GetStatus =>
      // During bootstrap, we're syncing via regular sync
      // Return syncing status based on bootstrap progress
      val currentBlock = appStateStorage.getBestBlockNumber()
      val targetBlock = appStateStorage.getSnapSyncBootstrapTarget().getOrElse(BigInt(0))
      val startingBlock = appStateStorage.getSyncStartingBlock()

      sender() ! SyncProtocol.Status.Syncing(
        startingBlockNumber = startingBlock,
        blocksProgress = SyncProtocol.Status.Progress(currentBlock, targetBlock),
        stateNodesProgress = None
      )

    case PivotBootstrapFailed(reason) =>
      log.warning(s"Pivot header bootstrap failed during initial startup: $reason")
      bootstrapRetryCount += 1
      if (checkBootstrapRetryTimeout(s"bootstrap failed: $reason")) {
        // checkBootstrapRetryTimeout already called requestSnapRetry()
      } else {
        val delay = bootstrapRetryDelay
        log.info(s"Retrying SNAP sync start in $delay (attempt $bootstrapRetryCount)")
        bootstrapCheckTask.foreach(_.cancel())
        bootstrapCheckTask = Some(
          scheduler.scheduleOnce(delay) { self ! RetrySnapSyncStart }(ec)
        )
      }

    // SNAP peer eviction runs during bootstrap to free slots for SNAP-capable peers
    case EvictNonSnapPeers =>
      evictNonSnapPeers()

    case msg =>
      log.debug(s"Unhandled message in bootstrapping state: $msg")
  }

  def completed: Receive = {
    case SyncProtocol.GetStatus =>
      sender() ! SyncProtocol.Status.SyncDone

    case GetProgress =>
      sender() ! progressMonitor.currentProgress

    case _ =>
      log.debug("SNAP sync is complete, ignoring messages")
  }

  private def startSnapSync(): Unit = {
    // Start evicting non-SNAP peers immediately to make room for SNAP-capable peers.
    // This runs during pivot selection and bootstrap, not just after account sync starts.
    startSnapPeerEviction()

    // Step 7: Check for accounts-complete recovery (process crash during bytecode/storage phase).
    // If accounts were previously completed and the pivot is still fresh, skip account download
    // and only re-run bytecodes + storage from the persisted storage file.
    if (appStateStorage.isSnapSyncAccountsComplete()) {
      val savedPivot = appStateStorage.getSnapSyncPivotBlock()
      val savedRootOpt = appStateStorage.getSnapSyncStateRoot()
      val savedStoragePath = appStateStorage.getSnapSyncStorageFilePath()

      (savedPivot, savedRootOpt) match {
        case (Some(pivot), Some(rootBs)) if pivot > 0 =>
          log.info(s"Recovery: accounts previously completed at pivot $pivot. Checking freshness...")

          // Check if pivot is still fresh enough
          val networkBest = currentNetworkBestFromSnapPeers().getOrElse(BigInt(0))
          val drift = if (networkBest > 0) (networkBest - pivot).abs else BigInt(0)
          if (networkBest > 0 && drift > snapSyncConfig.maxPivotStalenessBlocks) {
            log.warning(
              s"Recovery: pivot $pivot drifted $drift blocks from network best $networkBest. " +
                "Clearing accounts-complete flag and restarting fresh."
            )
            appStateStorage.putSnapSyncAccountsComplete(false).commit()
            // Fall through to normal startup
          } else {
            // Pivot is fresh enough — recover bytecodes + storage only
            pivotBlock = Some(pivot)
            stateRoot = Some(rootBs)
            accountsComplete = true
            bytecodePhaseComplete = false
            storagePhaseComplete = false

            log.info(s"Recovery: resuming bytecodes + storage sync from pivot $pivot (drift=$drift blocks)")

            // Create bytecode coordinator (will receive NoMore immediately since we have no new accounts)
            val storage = getOrCreateMptStorage(pivot)
            coordinatorGeneration += 1

            bytecodeCoordinator = Some(
              context.actorOf(
                actors.ByteCodeCoordinator
                  .props(
                    evmCodeStorage = evmCodeStorage,
                    networkPeerManager = networkPeerManager,
                    requestTracker = requestTracker,
                    batchSize = ByteCodeTask.DEFAULT_BATCH_SIZE,
                    snapSyncController = self
                  )
                  .withDispatcher("sync-dispatcher"),
                s"bytecode-coordinator-$coordinatorGeneration"
              )
            )
            bytecodeCoordinator.foreach(_ ! actors.Messages.StartByteCodeSync(Seq.empty))
            bytecodeRequestTask = Some(
              scheduler.scheduleWithFixedDelay(0.seconds, 1.second, self, RequestByteCodes)(ec)
            )

            storageRangeCoordinator = Some(
              context.actorOf(
                actors.StorageRangeCoordinator
                  .props(
                    stateRoot = rootBs,
                    networkPeerManager = networkPeerManager,
                    requestTracker = requestTracker,
                    mptStorage = storage,
                    flatSlotStorage = flatSlotStorage,
                    maxAccountsPerBatch = snapSyncConfig.storageBatchSize,
                    maxInFlightRequests = snapSyncConfig.storageConcurrency,
                    requestTimeout = snapSyncConfig.timeout,
                    snapSyncController = self,
                    initialMaxInFlightPerPeer = 3, // Recovery: accounts done, storage gets 3 of 5 per-peer budget
                    initialResponseBytes = snapSyncConfig.storageInitialResponseBytes,
                    minResponseBytes = snapSyncConfig.storageMinResponseBytes,
                    deferredMerkleization = snapSyncConfig.deferredMerkleization
                  )
                  .withDispatcher("sync-dispatcher"),
                s"storage-range-coordinator-$coordinatorGeneration"
              )
            )
            storageRangeCoordinator.foreach(_ ! actors.Messages.StartStorageRangeSync(rootBs))
            storageRangeRequestTask = Some(
              scheduler.scheduleWithFixedDelay(0.seconds, 1.second, self, RequestStorageRanges)(ec)
            )

            // Recovery budget: accounts done, bytecode=2, storage=3 (total 5 per peer)
            bytecodeCoordinator.foreach(_ ! actors.Messages.UpdateMaxInFlightPerPeer(2))

            // R6 fix: Load completed storage accounts from persisted file to avoid re-downloading
            val completedStoragePath = appStateStorage.getSnapSyncCompletedStoragePath()
            completedStoragePath.foreach { pathStr =>
              val filePath = java.nio.file.Paths.get(pathStr)
              if (java.nio.file.Files.exists(filePath)) {
                val completedHashes = mutable.Set[ByteString]()
                val raf = new java.io.RandomAccessFile(filePath.toFile, "r")
                try {
                  val buf = new Array[Byte](32)
                  while (raf.getFilePointer < raf.length()) {
                    raf.readFully(buf)
                    completedHashes += ByteString(buf.clone())
                  }
                } finally raf.close()
                if (completedHashes.nonEmpty) {
                  storageRangeCoordinator.foreach(_ ! actors.Messages.InitCompletedStorageAccounts(completedHashes.toSet))
                  log.info(s"Recovery: loaded ${completedHashes.size} completed storage accounts from $filePath")
                }
              } else {
                log.info(s"Recovery: completed-storage file $filePath not found (no prior completions to restore)")
              }
            }

            // Stream storage tasks from persisted file if available
            savedStoragePath.foreach { pathStr =>
              val filePath = java.nio.file.Paths.get(pathStr)
              if (java.nio.file.Files.exists(filePath)) {
                val coordinator = storageRangeCoordinator.get
                import context.dispatcher
                scala.concurrent
                  .Future {
                    val emptyRoot = ByteString(com.chipprbots.ethereum.mpt.MerklePatriciaTrie.EmptyRootHash)
                    val raf = new java.io.RandomAccessFile(filePath.toFile, "r")
                    val buf = new Array[Byte](64)
                    val batch = new scala.collection.mutable.ArrayBuffer[StorageTask](10000)
                    var totalTasks = 0
                    try {
                      while (raf.getFilePointer < raf.length()) {
                        raf.readFully(buf)
                        val accountHash = ByteString(java.util.Arrays.copyOfRange(buf, 0, 32))
                        val storageRoot = ByteString(java.util.Arrays.copyOfRange(buf, 32, 64))
                        if (storageRoot.nonEmpty && storageRoot != emptyRoot) {
                          batch += StorageTask.createStorageTask(accountHash, storageRoot)
                        }
                        if (batch.size >= 10000) {
                          coordinator ! actors.Messages.AddStorageTasks(batch.toSeq)
                          totalTasks += batch.size
                          batch.clear()
                        }
                      }
                      if (batch.nonEmpty) {
                        coordinator ! actors.Messages.AddStorageTasks(batch.toSeq)
                        totalTasks += batch.size
                      }
                    } finally raf.close()
                    totalTasks
                  }
                  .foreach { count =>
                    log.info(s"Recovery: streamed $count storage tasks from ${filePath}")
                    // Signal no more tasks — sentinel allows completion
                    coordinator ! actors.Messages.NoMoreStorageTasks
                  }
              } else {
                log.warning(s"Recovery: storage file $filePath not found. Sending NoMore immediately.")
                storageRangeCoordinator.foreach(_ ! actors.Messages.NoMoreStorageTasks)
              }
            }
            if (savedStoragePath.isEmpty) {
              log.warning("Recovery: no storage file path persisted. Sending NoMore immediately.")
              storageRangeCoordinator.foreach(_ ! actors.Messages.NoMoreStorageTasks)
            }

            // Bytecodes: with inline dispatch, codeHashes were already sent to the old coordinator.
            // On recovery, we can't recover those. Send NoMore — the healing phase will catch any
            // missing bytecodes via trie walk.
            bytecodeCoordinator.foreach(_ ! actors.Messages.NoMoreByteCodeTasks)

            currentPhase = ByteCodeAndStorageSync
            lastStorageProgressMs = System.currentTimeMillis()
            scheduleStorageStagnationChecks()
            schedulePivotFreshnessChecks()
            progressMonitor.startPhase(ByteCodeAndStorageSync)

            // Start parallel chain download during recovery too
            startChainDownloader()

            context.become(syncing)
            return
          }
        case _ =>
          log.warning("Recovery: accounts-complete flag set but missing pivot/root. Clearing and restarting fresh.")
          appStateStorage.putSnapSyncAccountsComplete(false).commit()
      }
    }

    // Check if there's an interrupted bootstrap to resume
    appStateStorage.getSnapSyncBootstrapTarget() match {
      case Some(bootstrapTarget) =>
        val bestBlockNumber = appStateStorage.getBestBlockNumber()

        // Header-only bootstrap may not advance bestBlockNumber; allow resume if pivot header exists.
        val hasPivotHeader = blockchainReader.getBlockHeaderByNumber(bootstrapTarget).isDefined

        if (bestBlockNumber >= bootstrapTarget || hasPivotHeader) {
          // Bootstrap already complete - clear the target and proceed with SNAP sync
          log.info(s"Bootstrap target $bootstrapTarget already reached (current block: $bestBlockNumber)")
          appStateStorage.clearSnapSyncBootstrapTarget().commit()
          // Continue to normal SNAP sync logic below
        } else {
          // Resume interrupted bootstrap
          log.info(s"Resuming interrupted bootstrap: current block $bestBlockNumber / target $bootstrapTarget")
          context.parent ! StartRegularSyncBootstrap(bootstrapTarget)
          context.become(bootstrapping)
          return
        }
      case None =>
      // No bootstrap in progress - continue with normal logic
    }

    // Get local and network state for pivot selection
    val localBestBlock = appStateStorage.getBestBlockNumber()

    // If bootstrap checkpoints are configured, we should not start SNAP from a peer that is behind
    // the highest trusted checkpoint. SNAP servers only guarantee serving very recent state; picking
    // an ancient pivot (e.g. millions of blocks behind the network head) will cause peers to return
    // empty/no-proof AccountRange responses.
    val bootstrapPivotBlock = appStateStorage.getBootstrapPivotBlock()

    // Query SNAP-capable peers to find the highest block in the network.
    // SNAP must NOT start until we have a SNAP-capable peer that is at/above the bootstrap pivot
    // (when configured). Falling back to non-SNAP peers here would select an unreachable state root.
    val snapPeersForPivot =
      peersToDownloadFrom.values.toList
        .filter(_.peerInfo.remoteStatus.supportsSnap)
        .filter(p => bootstrapPivotBlock == 0 || p.peerInfo.maxBlockNumber >= bootstrapPivotBlock)

    val networkBestBlockOpt =
      snapPeersForPivot
        .sortBy(_.peerInfo.maxBlockNumber)(bigIntReverseOrdering)
        .headOption
        .map(_.peerInfo.maxBlockNumber)

    // Diagnostics: show what heights we can actually see from connected peers.
    // If this list tops out near the Core-Geth "start height", it means we simply don't have any peer
    // advertising the real network head yet (or those peers are excluded/blacklisted/disconnected).
    val topPeerHeights =
      peersToDownloadFrom.values.toList
        .sortBy(_.peerInfo.maxBlockNumber)(bigIntReverseOrdering)
        .take(5)
        .map(p => s"${p.peer.remoteAddress}=${p.peerInfo.maxBlockNumber}")
        .mkString(", ")
    log.info(
      s"SNAP pivot selection: bootstrapPivot=$bootstrapPivotBlock, visible peer heights (top 5) = [$topPeerHeights]"
    )

    // Core-geth approach: Calculate pivot from network height
    // pivot = networkHeight - pivotBlockOffset (e.g., 23M - 64 = ~23M)
    val (baseBlockForPivot, pivotSelectionSource) = networkBestBlockOpt match {
      case Some(networkBestBlock) =>
        if (networkBestBlock <= localBestBlock + snapSyncConfig.pivotBlockOffset) {
          log.warning(s"Network best block ($networkBestBlock) is not significantly ahead of local ($localBestBlock)")
          log.warning("This may indicate limited peer connectivity or already synced state")
        }
        // Always use network block when available, regardless of gap size
        // This ensures we sync to the actual chain tip, not just our local view
        // Reset bootstrap retry state since we have peers
        bootstrapRetryCount = 0
        bootstrapRetryStartMs = 0L
        (networkBestBlock, NetworkPivot)
      case None =>
        // No peers available yet - fall back to local best block
        log.warning("No peers available for pivot selection, using local best block")
        log.warning("SNAP sync may select a suboptimal pivot - will retry if peers become available")
        (localBestBlock, LocalPivot)
    }

    val pivotBlockNumber = baseBlockForPivot - snapSyncConfig.pivotBlockOffset

    log.info(
      s"SNAP pivot selection: localBest=$localBestBlock, networkBest=${networkBestBlockOpt.getOrElse("none")}, " +
        s"base=$baseBlockForPivot (source=${pivotSelectionSource.name}), offset=${snapSyncConfig.pivotBlockOffset}, " +
        s"pivot=$pivotBlockNumber"
    )

    // Core-geth behavior: If chain height <= pivot offset, use genesis as pivot
    // This allows SNAP sync to start immediately from any height
    if (baseBlockForPivot <= snapSyncConfig.pivotBlockOffset) {
      // IMPORTANT: Only apply the "start from genesis" behavior when we actually know
      // the network height (i.e., we have peers). If we have no peers, the network height
      // is unknown and treating it as 0 will cause us to request SNAP data for the genesis
      // state root, which most peers won't serve.
      pivotSelectionSource match {
        case LocalPivot =>
          bootstrapRetryCount += 1
          if (checkBootstrapRetryTimeout("no peers, network height unknown")) return
          val delay = bootstrapRetryDelay
          log.warning(
            s"No peers available yet; network height is unknown. Retrying in $delay (attempt $bootstrapRetryCount)"
          )

          bootstrapCheckTask.foreach(_.cancel())
          bootstrapCheckTask = Some(
            scheduler.scheduleOnce(delay) {
              self ! RetrySnapSyncStart
            }(ec)
          )
          context.become(bootstrapping)
          return

        case NetworkPivot =>
        // proceed with genesis pivot handling below
      }

      log.info("=" * 80)
      log.info("🚀 SNAP Sync Starting from Genesis")
      log.info("=" * 80)
      log.info(s"Network height: $baseBlockForPivot blocks")
      log.info(s"Height below minimum threshold (${snapSyncConfig.pivotBlockOffset} blocks)")
      log.info(s"Using genesis (block 0) as pivot for early chain bootstrap")
      log.info("SNAP sync will effectively perform full sync for initial blocks")
      log.info("=" * 80)

      // Use genesis block as pivot (like core-geth does)
      blockchainReader.getBlockHeaderByNumber(0) match {
        case Some(genesisHeader) =>
          pivotBlock = Some(BigInt(0))
          stateRoot = Some(genesisHeader.stateRoot)
          appStateStorage.putSnapSyncPivotBlock(0).commit()
          appStateStorage.putSnapSyncStateRoot(genesisHeader.stateRoot).commit()
          updateBestBlockForPivot(genesisHeader, BigInt(0))

          SNAPSyncMetrics.setPivotBlockNumber(0)

          // Reset bootstrap retry state
          bootstrapRetryCount = 0
          bootstrapRetryStartMs = 0L

          // Start account range sync with genesis state root
          currentPhase = AccountRangeSync
          startAccountRangeSync(genesisHeader.stateRoot)
          context.become(syncing)

        case None =>
          log.error("Genesis block header not available - cannot start SNAP sync")
          context.parent ! RetrySnapSync("Genesis block header not available")
      }
      return
    }

    // With network-based pivot and 64-block offset, pivotBlockNumber should always be > 0
    // The genesis special case (above) handles when baseBlockForPivot <= 64
    // This remaining code handles the normal case where we have a valid pivot > localBestBlock

    if (pivotBlockNumber <= localBestBlock) {
      // Sanity check: pivot must be ahead of local state
      log.warning("=" * 80)
      log.warning("⚠️  SNAP Sync Pivot Issue Detected")
      log.warning("=" * 80)
      log.warning(s"Calculated pivot ($pivotBlockNumber) is not ahead of local state ($localBestBlock)")
      log.warning(
        s"Pivot source: ${pivotSelectionSource.name}, base block: $baseBlockForPivot, offset: ${snapSyncConfig.pivotBlockOffset}"
      )

      // LocalPivot is only used when no peers are available (see match expression above)
      // This check determines whether to retry for peers or transition to regular sync
      pivotSelectionSource match {
        case LocalPivot =>
          // No peers available - schedule retry with backoff
          bootstrapRetryCount += 1
          if (checkBootstrapRetryTimeout("no peers for pivot selection")) return
          val delay = bootstrapRetryDelay
          log.warning(s"No peers available for pivot selection. Retrying in $delay (attempt $bootstrapRetryCount)")

          bootstrapCheckTask.foreach(_.cancel())
          bootstrapCheckTask = Some(
            scheduler.scheduleOnce(delay) {
              self ! RetrySnapSyncStart
            }(ec)
          )
          context.become(bootstrapping)
          return

        case NetworkPivot =>
          // Pivot is at or behind local state - this means we're already synced or very close
          // Fall back to regular sync to catch up the remaining blocks
          log.warning("Pivot block is not ahead of local state - likely already synced")
          log.warning("Transitioning to regular sync for final block catch-up")
          context.parent ! Done // Signal completion, which will transition to regular sync
          return
      }
    } else {
      // Pivot is valid and ahead of local state

      // Even if we have a local header for this block number, fetch the pivot header from the network
      // to avoid starting SNAP from a stale/reorged or otherwise non-canonical header.
      // This is a header-only bootstrap.
      pivotSelectionSource match {
        case NetworkPivot =>
          log.info(s"Fetching pivot header from network for block $pivotBlockNumber before starting SNAP")
          appStateStorage.putSnapSyncBootstrapTarget(pivotBlockNumber).commit()
          context.parent ! StartRegularSyncBootstrap(pivotBlockNumber)
          context.become(bootstrapping)
          return
        case LocalPivot =>
        // Fall through to local-header availability checks below
      }

      // Check if we have the pivot block header locally
      blockchainReader.getBlockHeaderByNumber(pivotBlockNumber) match {
        case Some(header) =>
          // Pivot header is available - proceed with SNAP sync
          pivotBlock = Some(pivotBlockNumber)
          stateRoot = Some(header.stateRoot)
          appStateStorage.putSnapSyncPivotBlock(pivotBlockNumber).commit()
          appStateStorage.putSnapSyncStateRoot(header.stateRoot).commit()
          updateBestBlockForPivot(header, pivotBlockNumber)

          // Update metrics - pivot block
          SNAPSyncMetrics.setPivotBlockNumber(pivotBlockNumber)

          // Reset bootstrap retry state
          bootstrapRetryCount = 0
          bootstrapRetryStartMs = 0L

          log.info(s"Local pivot header available for block $pivotBlockNumber, starting SNAP sync")

          // Start account range sync
          currentPhase = AccountRangeSync
          startAccountRangeSync(header.stateRoot)
          context.become(syncing)

        case None =>
          // Pivot block header not available locally
          // This happens when we select a pivot based on network best block
          // but haven't synced that far yet

          pivotSelectionSource match {
            case NetworkPivot =>
              // We selected a network-based pivot but don't have the header yet
              // Need to bootstrap/sync to get closer to the pivot
              val targetForBootstrap = pivotBlockNumber

              log.info("=" * 80)
              log.info("🔄 SNAP Sync Pivot Header Not Available")
              log.info("=" * 80)
              log.info(s"Selected pivot: $pivotBlockNumber (based on network best block)")
              log.info(s"Local best block: $localBestBlock")
              log.info(s"Gap: ${pivotBlockNumber - localBestBlock} blocks")
              log.info("Need to sync headers/blocks to reach pivot point")
              log.info(s"Continuing regular sync to block $targetForBootstrap")
              log.info("Will automatically transition to SNAP sync once pivot is reached")
              log.info("=" * 80)

              // Store the bootstrap target
              appStateStorage.putSnapSyncBootstrapTarget(targetForBootstrap).commit()

              // Request regular sync to continue to the pivot point
              context.parent ! StartRegularSyncBootstrap(targetForBootstrap)
              context.become(bootstrapping)

            case LocalPivot =>
              // Local pivot selected but header still not available
              bootstrapRetryCount += 1
              if (checkBootstrapRetryTimeout("pivot header not available")) return
              val delay = bootstrapRetryDelay
              if (bootstrapRetryCount == 1) {
                log.info("Waiting for pivot block header to become available...")
              }
              log.info(
                s"   Pivot block $pivotBlockNumber not ready yet (attempt $bootstrapRetryCount), retrying in $delay"
              )

              // Schedule a retry and store the cancellable for proper cleanup
              bootstrapCheckTask.foreach(_.cancel())
              bootstrapCheckTask = Some(
                scheduler.scheduleOnce(delay) {
                  self ! RetrySnapSyncStart
                }(ec)
              )

              // Transition to bootstrapping state to handle the retry
              context.become(bootstrapping)
          }
      }
    }
  }

  /** Calculate exponential backoff delay for bootstrap retries. Backoff: 2s → 4s → 8s → 16s → 32s → 60s cap.
    */
  private def bootstrapRetryDelay: FiniteDuration = {
    val exponent = math.min(bootstrapRetryCount, 5)
    val delaySeconds = BootstrapRetryBaseDelay.toSeconds * math.pow(2, exponent).toLong
    math.min(delaySeconds, BootstrapRetryMaxDelay.toSeconds).seconds
  }

  /** Check if bootstrap retry has exceeded the maximum duration. If so, falls back to fast sync. Returns true if
    * fallback was triggered.
    */
  private def checkBootstrapRetryTimeout(context: String): Boolean = {
    if (bootstrapRetryStartMs == 0L) bootstrapRetryStartMs = System.currentTimeMillis()
    val elapsed = System.currentTimeMillis() - bootstrapRetryStartMs
    if (elapsed > MaxBootstrapRetryDuration.toMillis) {
      log.warning(
        s"No peers found after ${elapsed / 1000}s of bootstrap retries ($context)."
      )
      requestSnapRetry(s"No peers found after bootstrap timeout (${elapsed / 1000}s)")
      true
    } else {
      // Periodic diagnostic logging (~every 30s based on accumulated delay)
      if (bootstrapRetryCount > 0 && bootstrapRetryCount % 5 == 0) {
        log.info(
          s"Bootstrap retry diagnostics ($context): " +
            s"attempt=$bootstrapRetryCount, " +
            s"handshakedPeers=${handshakedPeers.size}, " +
            s"snapCapable=${handshakedPeers.values.count(_.peerInfo.remoteStatus.supportsSnap)}, " +
            s"elapsed=${elapsed / 1000}s"
        )
      }
      false
    }
  }

  /** Record a critical failure and check if we should fallback to fast sync. Critical failures are those that indicate
    * SNAP sync cannot proceed.
    *
    * @param reason
    *   Description of the failure
    * @return
    *   true if we should fallback to fast sync
    */
  private def recordCriticalFailure(reason: String): Boolean = {
    criticalFailureCount += 1
    log.warning(s"Critical SNAP sync failure ($criticalFailureCount/${snapSyncConfig.maxSnapSyncFailures}): $reason")

    if (criticalFailureCount >= snapSyncConfig.maxSnapSyncFailures) {
      log.error(s"SNAP sync failed ${criticalFailureCount} times, falling back to fast sync")
      true
    } else {
      false
    }
  }

  /** Request SNAP sync retry from parent controller due to repeated failures */
  private def requestSnapRetry(reason: String): Unit = {
    // Set phase to Completed FIRST to prevent aroundReceive guards (which check currentPhase)
    // from re-triggering stagnation checks while we're tearing down.
    currentPhase = Completed

    log.warning(s"Requesting SNAP sync retry: $reason")

    // Cancel all scheduled tasks
    accountRangeRequestTask.foreach(_.cancel())
    bytecodeRequestTask.foreach(_.cancel())
    storageRangeRequestTask.foreach(_.cancel())
    healingRequestTask.foreach(_.cancel())
    snapCapabilityCheckTask.foreach(_.cancel())
    snapPeerEvictionTask.foreach(_.cancel())

    // Stop progress monitoring
    progressMonitor.stopPeriodicLogging()

    // Clear persisted SNAP progress — retry will start fresh
    appStateStorage.putSnapSyncProgress("").commit()
    appStateStorage.putSnapSyncAccountsComplete(false).commit()
    appStateStorage.putSnapSyncStorageFilePath("").commit()
    appStateStorage.putSnapSyncCompletedStoragePath("").commit()
    preservedRangeProgress = Map.empty
    preservedAtPivotBlock = None

    // Stop chain downloader
    chainDownloader.foreach(context.stop)
    chainDownloader = None

    // Notify parent controller to retry SNAP sync with backoff
    context.parent ! RetrySnapSync(reason)
    context.become(completed)
  }

  /** Evict non-SNAP outgoing peers when SNAP peer count is below threshold.
    *
    * Core-Geth completes full SNAP sync in ~5 minutes because it connects to SNAP-capable peers
    * rapidly. Fukuii's peer slots can fill with non-SNAP peers (ETH-only), leaving no room for
    * SNAP-capable peers to connect. This method actively disconnects the oldest non-SNAP outgoing
    * peers to free connection slots, allowing discovery to fill them with SNAP-capable peers.
    */
  private def evictNonSnapPeers(): Unit = {
    val allPeers = handshakedPeers.values.toSeq
    val verifiedSnapPeerCount = allPeers.count(p => p.peerInfo.remoteStatus.supportsSnap && p.peerInfo.isServingSnap)
    // Only evict peers that don't advertise snap at all.
    // Aligned with Besu: snap-advertising peers with pending/failed probes are NOT evicted —
    // they are simply excluded from snap sync selection until verified.
    // Static peers are exempt from eviction (matches geth/Besu behavior).
    // core-geth advertises snap/1 but doesn't serve SNAP state — keeping it
    // connected as eth/68 peer is still valuable for block header/body sync.
    val nonSnapOutgoing = allPeers
      .filter(p => !p.peerInfo.remoteStatus.supportsSnap && !p.peer.incomingConnection && !p.peer.isStatic)
      .sortBy(_.peer.createTimeMillis)
    val evictionCandidates = nonSnapOutgoing

    if (verifiedSnapPeerCount >= snapSyncConfig.minSnapPeers || evictionCandidates.isEmpty) {
      log.debug(
        s"SNAP peer eviction: $verifiedSnapPeerCount verified snap peers (need ${snapSyncConfig.minSnapPeers}), " +
          s"${evictionCandidates.size} eviction candidates — no eviction needed"
      )
      return
    }

    val numToEvict = math.min(
      snapSyncConfig.maxEvictionsPerCycle,
      math.min(evictionCandidates.size, snapSyncConfig.minSnapPeers - verifiedSnapPeerCount)
    )

    if (numToEvict > 0) {
      log.info(
        s"SNAP peer eviction: only $verifiedSnapPeerCount verified snap peers (need ${snapSyncConfig.minSnapPeers}), " +
          s"evicting $numToEvict non-snap peers (${nonSnapOutgoing.size} candidates)"
      )
      evictionCandidates.take(numToEvict).foreach { peerWithInfo =>
        log.info(
          s"Evicting non-snap peer ${peerWithInfo.peer.id} (${peerWithInfo.peer.remoteAddress}, " +
            s"cap=${peerWithInfo.peerInfo.remoteStatus.capability})"
        )
        peerWithInfo.peer.ref ! com.chipprbots.ethereum.network.PeerActor.DisconnectPeer(
          com.chipprbots.ethereum.network.p2p.messages.WireProtocol.Disconnect.Reasons.TooManyPeers
        )
      }
    }
  }

  /** Start the periodic SNAP peer eviction task if not already running. */
  private def startSnapPeerEviction(): Unit =
    if (snapPeerEvictionTask.isEmpty) {
      snapPeerEvictionTask = Some(
        scheduler.scheduleWithFixedDelay(
          snapSyncConfig.snapPeerEvictionInterval, // initial delay — give discovery time to connect
          snapSyncConfig.snapPeerEvictionInterval,
          self,
          EvictNonSnapPeers
        )(ec)
      )
      log.info(
        s"SNAP peer eviction started: checking every ${snapSyncConfig.snapPeerEvictionInterval.toSeconds}s, " +
          s"min ${snapSyncConfig.minSnapPeers} snap peers, max ${snapSyncConfig.maxEvictionsPerCycle} evictions/cycle"
      )
    }

  private def startAccountRangeSync(rootHash: ByteString): Unit = {
    // Before starting workers, check if any connected peer has been verified as serving SNAP data.
    // Peers that advertise snap/1 but fail the SnapServerChecker probe are not counted.
    // If no verified peers exist, schedule a grace period before falling back to fast sync.
    val snapPeerCount = peersToDownloadFrom.count { case (_, p) =>
      p.peerInfo.remoteStatus.supportsSnap && p.peerInfo.isServingSnap
    }

    if (snapPeerCount == 0) {
      val gracePeriod = snapSyncConfig.snapCapabilityGracePeriod
      log.warning(s"No peers with snap/1 capability found ($peersToDownloadFrom.size peers connected)")
      log.warning(s"Scheduling snap capability check in ${gracePeriod.toSeconds}s before falling back to fast sync")
      snapCapabilityCheckTask = Some(
        scheduler.scheduleOnce(gracePeriod, self, CheckSnapCapability)(ec)
      )
      return
    }

    // Dynamic concurrency: use min(configured, peerCount) so each worker maps 1:1 to a peer.
    // With 16 ranges but only 4 peers, ranges never complete before stagnation.
    // With 4 ranges and 4 peers, each range gets dedicated throughput and finishes faster.
    val effectiveConcurrency = math.min(snapSyncConfig.accountConcurrency, snapPeerCount).max(1)
    log.info(
      s"Starting account range sync with concurrency $effectiveConcurrency ($snapPeerCount snap-capable peers, configured max ${snapSyncConfig.accountConcurrency})"
    )
    log.info("Using actor-based concurrency for account range sync")
    launchAccountRangeWorkers(rootHash, effectiveConcurrency)

    // Start periodic SNAP peer eviction to ensure we maintain enough SNAP-capable peers.
    // Non-SNAP peers filling all slots is the #1 cause of SNAP sync starvation.
    startSnapPeerEviction()

    // Start parallel chain download (headers, bodies, receipts from genesis to pivot)
    // Follows the Geth/Nethermind pattern of overlapping chain + state download.
    startChainDownloader()
  }

  private def launchAccountRangeWorkers(rootHash: ByteString, concurrency: Int = -1): Unit = {
    val effectiveConcurrency = if (concurrency > 0) concurrency else snapSyncConfig.accountConcurrency
    // Reset stagnation tracking for this phase.
    lastAccountProgressMs = System.currentTimeMillis()
    lastAccountTasksCompleted = 0
    lastAccountsDownloaded = 0

    // Safety valve: only preserve range progress if pivot hasn't drifted too far.
    // Content-addressed MPT data is valid across adjacent pivots (~256 blocks apart),
    // but large drift means the state may have changed significantly.
    val currentPivot = pivotBlock.getOrElse(BigInt(0))

    // Try disk recovery first (cross-process restart), then fall back to in-memory
    if (preservedRangeProgress.isEmpty) {
      appStateStorage.getSnapSyncProgress().foreach { saved =>
        deserializeSnapProgress(saved).foreach { case (savedPivot, savedRanges) =>
          if (savedRanges.nonEmpty && (currentPivot - savedPivot).abs <= MaxPreservedPivotDistance) {
            log.info(
              s"Recovered ${savedRanges.size} account ranges from disk " +
                s"(saved pivot=$savedPivot, current=$currentPivot, drift=${(currentPivot - savedPivot).abs})"
            )
            preservedRangeProgress = savedRanges
            preservedAtPivotBlock = Some(savedPivot)
          } else if (savedRanges.nonEmpty) {
            log.info(
              s"Discarding stale disk progress: pivot drifted ${(currentPivot - savedPivot).abs} blocks " +
                s"(>${MaxPreservedPivotDistance})"
            )
          }
        }
      }
    }

    val resumeProgress: Map[ByteString, ByteString] = preservedAtPivotBlock match {
      case Some(prevPivot) if (currentPivot - prevPivot).abs <= MaxPreservedPivotDistance =>
        if (preservedRangeProgress.nonEmpty) {
          log.info(
            s"Resuming ${preservedRangeProgress.size} account ranges from pivot $prevPivot " +
              s"(current pivot=$currentPivot, drift=${(currentPivot - prevPivot).abs} blocks)"
          )
        }
        preservedRangeProgress
      case Some(prevPivot) =>
        log.info(
          s"Pivot drifted ${(currentPivot - prevPivot).abs} blocks (>${MaxPreservedPivotDistance}), " +
            s"clearing ${preservedRangeProgress.size} preserved ranges"
        )
        preservedRangeProgress = Map.empty
        preservedAtPivotBlock = None
        appStateStorage.putSnapSyncProgress("").commit()
        Map.empty
      case None =>
        Map.empty
    }

    val storage = getOrCreateMptStorage(currentPivot)

    val tempDir = java.nio.file.Paths.get(Config.config.getString("tmpdir"))

    accountRangeCoordinator = Some(
      context.actorOf(
        actors.AccountRangeCoordinator
          .props(
            stateRoot = rootHash,
            networkPeerManager = networkPeerManager,
            requestTracker = requestTracker,
            mptStorage = storage,
            concurrency = effectiveConcurrency,
            snapSyncController = self,
            resumeProgress = resumeProgress,
            initialMaxInFlightPerPeer = 5, // Full per-peer budget during AccountRangeSync (storage+bytecode deferred to 0)
            trieFlushThreshold = snapSyncConfig.accountTrieFlushThreshold,
            initialResponseBytes = snapSyncConfig.accountInitialResponseBytes,
            minResponseBytes = snapSyncConfig.accountMinResponseBytes,
            tempDir = tempDir
          )
          .withDispatcher("sync-dispatcher"),
        s"account-range-coordinator-$coordinatorGeneration"
      )
    )

    // Start the coordinator
    accountRangeCoordinator.foreach(_ ! actors.Messages.StartAccountRangeSync(rootHash))

    // Periodically send peer availability notifications
    accountRangeRequestTask = Some(
      scheduler.scheduleWithFixedDelay(
        0.seconds,
        1.second,
        self,
        RequestAccountRanges
      )(ec)
    )

    scheduleAccountStagnationChecks()
    schedulePivotFreshnessChecks()

    // Schedule periodic rate tracker tuning (geth msgrate alignment: recalculate median RTT every 5s)
    if (rateTrackerTuneTask.isEmpty) {
      rateTrackerTuneTask = Some(
        scheduler.scheduleWithFixedDelay(5.seconds, 5.seconds, self, TuneRateTracker)(ec)
      )
    }

    // Geth-aligned: create bytecode + storage coordinators upfront so they can receive
    // IncrementalContractData from the first account batch response. No phase gap.
    if (bytecodeCoordinator.isEmpty) {
      bytecodeCoordinator = Some(
        context.actorOf(
          actors.ByteCodeCoordinator
            .props(
              evmCodeStorage = evmCodeStorage,
              networkPeerManager = networkPeerManager,
              requestTracker = requestTracker,
              batchSize = ByteCodeTask.DEFAULT_BATCH_SIZE,
              snapSyncController = self
            )
            .withDispatcher("sync-dispatcher"),
          s"bytecode-coordinator-$coordinatorGeneration"
        )
      )
      // Start with empty codeHashes — tasks arrive incrementally via AddByteCodeTasks
      bytecodeCoordinator.foreach(_ ! actors.Messages.StartByteCodeSync(Seq.empty))

      bytecodeRequestTask = Some(
        scheduler.scheduleWithFixedDelay(0.seconds, 1.second, self, RequestByteCodes)(ec)
      )
    }

    if (storageRangeCoordinator.isEmpty) {
      val storage = getOrCreateMptStorage(currentPivot)

      storageRangeCoordinator = Some(
        context.actorOf(
          actors.StorageRangeCoordinator
            .props(
              stateRoot = rootHash,
              networkPeerManager = networkPeerManager,
              requestTracker = requestTracker,
              mptStorage = storage,
              flatSlotStorage = flatSlotStorage,
              maxAccountsPerBatch = snapSyncConfig.storageBatchSize,
              maxInFlightRequests = snapSyncConfig.storageConcurrency,
              requestTimeout = snapSyncConfig.timeout,
              snapSyncController = self,
              initialMaxInFlightPerPeer = 3, // Parallel with accounts — matches geth/Besu (both run storage concurrently from the start)
              initialResponseBytes = snapSyncConfig.storageInitialResponseBytes,
              minResponseBytes = snapSyncConfig.storageMinResponseBytes
            )
            .withDispatcher("sync-dispatcher"),
          s"storage-range-coordinator-$coordinatorGeneration"
        )
      )
      // Start with empty tasks — tasks arrive incrementally via AddStorageTasks
      storageRangeCoordinator.foreach(_ ! actors.Messages.StartStorageRangeSync(rootHash))

      storageRangeRequestTask = Some(
        scheduler.scheduleWithFixedDelay(0.seconds, 1.second, self, RequestStorageRanges)(ec)
      )
    }

    // Geth-aligned: all 3 coordinators run concurrently from the start.
    // Storage at budget=3, bytecode at budget=2 (tasks arrive incrementally as contracts are
    // discovered). Bytecodes are content-addressed so pivot changes don't invalidate them.
    // On ETC mainnet (~2M contracts), this eliminates the post-account bytecode download wait.
    bytecodeCoordinator.foreach(_ ! actors.Messages.UpdateMaxInFlightPerPeer(2))

    progressMonitor.startPhase(AccountRangeSync)
  }

  // Internal message for periodic account range requests
  private case object RequestAccountRanges

  private def requestAccountRanges(): Unit = {
    if (maybeRestartIfPivotTooStale("AccountRangeSync")) return
    // Notify coordinator of available peers
    accountRangeCoordinator.foreach { coordinator =>
      val pivot = pivotBlock.getOrElse(BigInt(0))

      val snapPeers = peersToDownloadFrom.collect {
        case (_, peerWithInfo)
            if peerWithInfo.peerInfo.remoteStatus.supportsSnap
              && peerWithInfo.peerInfo.isServingSnap
              && peerWithInfo.peerInfo.maxBlockNumber >= pivot =>
          peerWithInfo.peer
      }

      SNAPSyncMetrics.setSnapCapablePeers(snapPeers.size)

      if (snapPeers.isEmpty) {
        log.debug("No verified SNAP-serving peers available for account range requests")
      } else {
        snapPeers.foreach { peer =>
          coordinator ! actors.Messages.PeerAvailable(peer)
        }
      }
    }
  }

  // Internal message for periodic bytecode requests
  private case object RequestByteCodes

  private def requestByteCodes(): Unit = {
    if (maybeRestartIfPivotTooStale("ByteCodeSync")) return
    // Notify coordinator of available peers
    bytecodeCoordinator.foreach { coordinator =>
      val snapPeers = peersToDownloadFrom.collect {
        case (peerId, peerWithInfo)
            if peerWithInfo.peerInfo.remoteStatus.supportsSnap
              && peerWithInfo.peerInfo.isServingSnap =>
          peerWithInfo.peer
      }

      // Adaptive bytecode budget: scale with verified SNAP peer count.
      // 1 peer: budget=1 (avoid starving accounts/storage). 2+: budget=2.
      val bytecodeBudget = if (snapPeers.size <= 1) 1 else 2
      coordinator ! actors.Messages.UpdateMaxInFlightPerPeer(bytecodeBudget)

      if (snapPeers.isEmpty) {
        log.debug("No verified SNAP-serving peers available for bytecode requests")
      } else {
        snapPeers.foreach { peer =>
          coordinator ! actors.Messages.ByteCodePeerAvailable(peer)
        }
      }
    }
  }

  // Internal message for periodic storage range requests
  private case object RequestStorageRanges

  private def requestStorageRanges(): Unit = {
    if (maybeRestartIfPivotTooStale("StorageRangeSync")) return
    // Notify coordinator of available peers
    storageRangeCoordinator.foreach { coordinator =>
      val pivot = pivotBlock.getOrElse(BigInt(0))

      val snapPeers = peersToDownloadFrom.collect {
        case (peerId, peerWithInfo)
            if peerWithInfo.peerInfo.remoteStatus.supportsSnap
              && peerWithInfo.peerInfo.isServingSnap
              && peerWithInfo.peerInfo.maxBlockNumber >= pivot =>
          peerWithInfo.peer
      }

      SNAPSyncMetrics.setSnapCapablePeers(snapPeers.size)

      // Adaptive storage budget: scale with verified SNAP peer count.
      // 1 peer: budget=2 (share with accounts=5, bytecodes=1 → total ~8). 2+: budget=3.
      val storageBudget = if (snapPeers.size <= 1) 2 else 3
      coordinator ! actors.Messages.UpdateMaxInFlightPerPeer(storageBudget)

      if (snapPeers.isEmpty) {
        log.info(s"No SNAP-capable peers at or above pivot $pivot available for storage range requests")
      } else {
        snapPeers.foreach { peer =>
          coordinator ! actors.Messages.StoragePeerAvailable(peer)
        }
      }
    }
  }

  // Handle storage stagnation checks in the actor mailbox so we can safely ask the coordinator.
  override def aroundReceive(receive: Receive, msg: Any): Unit = msg match {
    case CheckAccountStagnation if currentPhase == AccountRangeSync =>
      accountRangeCoordinator match {
        case Some(coordinator) =>
          import org.apache.pekko.pattern.{ask, pipe}
          import org.apache.pekko.util.Timeout
          implicit val timeout: Timeout = Timeout(2.seconds)

          (coordinator ? actors.Messages.GetProgress)
            .mapTo[actors.AccountRangeStats]
            .map(AccountCoordinatorProgress.apply)
            .recover { case ex =>
              log.debug(s"Account stagnation check failed to query coordinator: ${ex.getMessage}")
              AccountCoordinatorProgress(
                actors.AccountRangeStats(
                  accountsDownloaded = 0L,
                  bytesDownloaded = 0L,
                  tasksCompleted = 0,
                  tasksActive = 0,
                  tasksPending = 0,
                  progress = 0.0,
                  elapsedTimeMs = 0L,
                  contractAccountsFound = 0
                )
              )
            }
            .pipeTo(self)
        case None =>
          ()
      }
      super.aroundReceive(receive, msg)

    case AccountCoordinatorProgress(progress) if currentPhase == AccountRangeSync =>
      // Ignore the dummy progress used on recover.
      if (
        progress.elapsedTimeMs > 0 || progress.tasksPending > 0 || progress.tasksActive > 0 || progress.tasksCompleted > 0
      )
        maybeRestartIfAccountStagnant(progress)
      super.aroundReceive(receive, msg)

    case CheckStorageStagnation if currentPhase == StorageRangeSync || currentPhase == ByteCodeAndStorageSync =>
      storageRangeCoordinator match {
        case Some(coordinator) =>
          import org.apache.pekko.pattern.{ask, pipe}
          import org.apache.pekko.util.Timeout
          implicit val timeout: Timeout = Timeout(2.seconds)

          (coordinator ? actors.Messages.StorageGetProgress)
            .mapTo[actors.StorageRangeCoordinator.SyncStatistics]
            .map(StorageCoordinatorProgress.apply)
            .recover { case ex =>
              log.debug(s"Storage stagnation check failed to query coordinator: ${ex.getMessage}")
              // No stats; do nothing.
              StorageCoordinatorProgress(
                actors.StorageRangeCoordinator.SyncStatistics(0, 0, 0, 0, 0, 0, 0.0)
              )
            }
            .pipeTo(self)
        case None =>
          ()
      }
      super.aroundReceive(receive, msg)

    case StorageCoordinatorProgress(stats)
        if (currentPhase == StorageRangeSync || currentPhase == ByteCodeAndStorageSync) =>
      // Ignore the dummy stats used on recover.
      if (stats.elapsedTimeMs > 0 || stats.tasksPending > 0 || stats.tasksActive > 0 || stats.tasksCompleted > 0)
        maybeRestartIfStorageStagnant(stats)
      super.aroundReceive(receive, msg)

    // Proactive pivot freshness (Geth-aligned): refresh before peers start returning empty responses.
    case CheckPivotFreshness
        if currentPhase == AccountRangeSync || currentPhase == StorageRangeSync || currentPhase == ByteCodeAndStorageSync =>
      maybeProactivelyRefreshPivot()
      super.aroundReceive(receive, msg)

    case _ =>
      super.aroundReceive(receive, msg)
  }

  // Internal message for async trie walk result
  private case class TrieWalkResult(missingNodes: Seq[(Seq[ByteString], ByteString)])
  private case class TrieWalkFailed(error: String)
  private case object ScheduledTrieWalk

  /** Start an async trie walk to discover missing nodes. Guards against concurrent walks. */
  private def startTrieWalk(): Unit = {
    if (trieWalkInProgress) return
    if (currentPhase != StateHealing) return
    trieWalkInProgress = true
    stateRoot.foreach { root =>
      log.info("Starting trie walk to discover missing nodes for healing...")
      val storage = getOrCreateMptStorage(pivotBlock.getOrElse(BigInt(0)))
      val selfRef = self
      scala.concurrent
        .Future {
          val validator = new StateValidator(storage)
          validator.findMissingNodesWithPaths(root)
        }(ec)
        .foreach {
          case Right(missingNodes) => selfRef ! TrieWalkResult(missingNodes)
          case Left(error)         => selfRef ! TrieWalkFailed(error)
        }(ec)
    }
  }

  private def startStateHealing(): Unit = {
    // Guard: prevent duplicate healing coordinator creation (Bug 27).
    // Can happen when ByteCodeSyncComplete and StorageRangeSyncComplete arrive in quick
    // succession — both call checkAllDownloadsComplete() which calls startStateHealing().
    if (trieNodeHealingCoordinator.isDefined) {
      log.warning("startStateHealing called but healing coordinator already exists — ignoring duplicate")
      return
    }

    trieWalkInProgress = false // Reset for fresh healing phase
    log.info(s"Starting state healing with batch size ${snapSyncConfig.healingBatchSize}")

    stateRoot.foreach { root =>
      log.info("Using actor-based concurrency for state healing")

      val storage = getOrCreateMptStorage(pivotBlock.getOrElse(BigInt(0)))

      trieNodeHealingCoordinator = Some(
        context.actorOf(
          actors.TrieNodeHealingCoordinator
            .props(
              stateRoot = root,
              networkPeerManager = networkPeerManager,
              requestTracker = requestTracker,
              mptStorage = storage,
              batchSize = snapSyncConfig.healingBatchSize,
              snapSyncController = self,
              concurrency = snapSyncConfig.healingConcurrency
            )
            .withDispatcher("sync-dispatcher"),
          s"trie-node-healing-coordinator-$coordinatorGeneration"
        )
      )

      // Start the coordinator — give healing full per-peer budget (accounts/storage/bytecode done)
      trieNodeHealingCoordinator.foreach { coordinator =>
        coordinator ! actors.Messages.StartTrieNodeHealing(root)
        coordinator ! actors.Messages.UpdateMaxInFlightPerPeer(5)
      }

      // Periodically send peer availability notifications
      healingRequestTask = Some(
        scheduler.scheduleWithFixedDelay(
          0.seconds,
          1.second,
          self,
          RequestTrieNodeHealing
        )(ec)
      )

      progressMonitor.startPhase(StateHealing)

      // Run initial trie walk asynchronously to discover missing nodes
      startTrieWalk()
    }
  }

  // Internal message for periodic healing requests
  private case object RequestTrieNodeHealing

  private def requestTrieNodeHealing(): Unit = {
    if (maybeRestartIfPivotTooStale("StateHealing")) return
    // Notify coordinator of available peers
    trieNodeHealingCoordinator.foreach { coordinator =>
      val snapPeers = peersToDownloadFrom.collect {
        case (peerId, peerWithInfo)
            if peerWithInfo.peerInfo.remoteStatus.supportsSnap
              && peerWithInfo.peerInfo.isServingSnap =>
          peerWithInfo.peer
      }

      // Adaptive healing budget: distribute global concurrency across available peers.
      // healingConcurrency=16: 1 peer→5, 2→5, 3→5, 4→4, 5+→16/N
      if (snapPeers.nonEmpty) {
        val healingBudget = math.max(1, math.min(5, snapSyncConfig.healingConcurrency / snapPeers.size))
        coordinator ! actors.Messages.UpdateMaxInFlightPerPeer(healingBudget)
      }

      if (snapPeers.isEmpty) {
        log.debug("No SNAP-capable peers available for healing requests")
      } else {
        snapPeers.foreach { peer =>
          coordinator ! actors.Messages.HealingPeerAvailable(peer)
        }
      }
    }
  }

  private def validateState(): Unit = {
    if (!snapSyncConfig.stateValidationEnabled) {
      log.info("State validation disabled, skipping...")
      self ! StateValidationComplete
      return
    }

    log.info("Validating state completeness...")

    (stateRoot, pivotBlock) match {
      case (Some(expectedRoot), Some(pivot)) =>
        log.info(s"Validating state against expected root: ${expectedRoot.take(8).toHex}...")

        // With actor-based coordination, state is persisted directly to MPT storage
        // No need for finalization - just validate what's in storage
        val storage = getOrCreateMptStorage(pivot)
        val validator = new StateValidator(storage)

        val validationStartMs = System.currentTimeMillis()

        // Validate account trie and collect missing nodes
        validator.validateAccountTrie(expectedRoot) match {
          case Right(missingAccountNodes) if missingAccountNodes.isEmpty =>
            val elapsedMs = System.currentTimeMillis() - validationStartMs
            log.info(s"Account trie validation successful - no missing nodes (${elapsedMs}ms)")

            // Reset validation retry counter on success
            validationRetryCount = 0

            // Now validate storage tries
            validator.validateAllStorageTries(expectedRoot) match {
              case Right(missingStorageNodes) if missingStorageNodes.isEmpty =>
                val totalMs = System.currentTimeMillis() - validationStartMs
                log.info(s"Storage trie validation successful - no missing nodes (${totalMs}ms)")
                log.info("✅ State validation COMPLETE - all tries are intact")
                self ! StateValidationComplete

              case Right(missingStorageNodes) =>
                log.warning(s"Storage trie validation found ${missingStorageNodes.size} missing nodes")
                log.info("Triggering additional healing iteration for missing storage nodes...")
                triggerHealingForMissingNodes(missingStorageNodes)

              case Left(error) =>
                log.error(s"Storage trie validation failed: $error")
                log.error("Attempting to recover through healing phase")
                // Transition back to healing to attempt recovery
                currentPhase = StateHealing
                startStateHealing()
            }

          case Right(missingAccountNodes) =>
            log.warning(s"Account trie validation found ${missingAccountNodes.size} missing nodes")
            log.info("Triggering additional healing iteration for missing account nodes...")
            triggerHealingForMissingNodes(missingAccountNodes)

          case Left(error) =>
            log.error(s"Account trie validation failed: $error")

            // Check if this is a root node missing error
            if (error.contains("Missing root node")) {
              validationRetryCount += 1

              if (validationRetryCount > MaxValidationRetries) {
                log.warning(
                  s"Root node missing after $validationRetryCount validation attempts. " +
                    s"Proceeding to regular sync — missing nodes will be fetched on-demand during block execution."
                )
                // Skip validation and proceed: mark SNAP done, start regular sync.
                // With deferred merkleization, the root node was never built from flat data.
                // Regular sync's StateNodeFetcher will retrieve it via GetTrieNodes when needed.
                appStateStorage.snapSyncDone().commit()
                context.parent ! Done
              } else {
                log.error(s"Root node is missing (retry attempt $validationRetryCount of $MaxValidationRetries)")
                log.info("Retrying validation after brief delay...")
                // Retry validation after a short delay — direct retry, don't loop through healing
                scheduler.scheduleOnce(ValidationRetryDelay) {
                  validateState()
                }(ec)
              }
            } else {
              log.error("Attempting to recover through healing phase")
              currentPhase = StateHealing
              startStateHealing()
            }
        }

      case _ =>
        log.error("Missing state root or pivot block for validation — cannot validate state")
        log.error(s"stateRoot=${stateRoot.map(_.toHex.take(16))}, pivotBlock=$pivotBlock")
        // Don't leave the controller stuck in StateValidation phase.
        // Mark SNAP done and hand off to regular sync, which will fetch any
        // missing state on-demand during block execution.
        log.warning("Proceeding to regular sync without state validation")
        appStateStorage.snapSyncDone().commit()
        context.parent ! Done
    }
  }

  private def currentNetworkBestFromSnapPeers(): Option[BigInt] = {
    val bootstrapPivotBlock = appStateStorage.getBootstrapPivotBlock()
    peersToDownloadFrom.values.toList
      .filter(_.peerInfo.remoteStatus.supportsSnap)
      .filter(p => bootstrapPivotBlock == 0 || p.peerInfo.maxBlockNumber >= bootstrapPivotBlock)
      .sortBy(_.peerInfo.maxBlockNumber)(bigIntReverseOrdering)
      .headOption
      .map(_.peerInfo.maxBlockNumber)
  }

  private def maybeRestartIfPivotTooStale(contextLabel: String): Boolean = {
    val now = System.currentTimeMillis()
    if (now - lastPivotRestartMs < MinPivotRestartInterval.toMillis) return false

    (pivotBlock, currentNetworkBestFromSnapPeers()) match {
      case (Some(pivot), Some(networkBest)) if networkBest > 0 && networkBest > pivot =>
        val delta = networkBest - pivot
        if (delta > snapSyncConfig.maxPivotStalenessBlocks) {
          lastPivotRestartMs = now
          restartSnapSync(
            s"pivot=$pivot is $delta blocks behind visible networkBest=$networkBest in $contextLabel " +
              s"(maxPivotStaleness=${snapSyncConfig.maxPivotStalenessBlocks})"
          )
          true
        } else {
          false
        }
      case _ =>
        false
    }
  }

  /** Refresh the pivot block and state root without destroying coordinators.
    *
    * Unlike restartSnapSync() which discards all progress, this method:
    *   1. Selects a fresher pivot from current network best 2. Updates internal pivot/stateRoot tracking 3. Sends
    *      PivotRefreshed to the active coordinator 4. Resets stagnation timer so the watchdog doesn't trigger
    *
    * Downloaded trie nodes are content-addressed (keyed by keccak256 hash), so ~99.9% remain valid across pivot
    * changes. Root mismatch (if any) is resolved during the healing phase.
    */
  private def refreshPivotInPlace(reason: String): Unit = {
    log.info(s"Refreshing pivot in-place: $reason")

    // Select a new pivot from the current network best
    val newPivotOpt = currentNetworkBestFromSnapPeers()
      .map { networkBest =>
        networkBest - snapSyncConfig.pivotBlockOffset
      }
      .filter(_ > 0)

    if (newPivotOpt.isEmpty) {
      log.warning(
        "Cannot refresh pivot: no suitable SNAP peers available. Scheduling retry in 30s."
      )
      // Don't restart — restarting can't help with no peers, and it destroys all in-memory trie data.
      // Schedule an explicit retry so we don't stall indefinitely waiting for coordinator backoff.
      pivotBootstrapRetryTask.foreach(_.cancel())
      pivotBootstrapRetryTask = Some(
        scheduler.scheduleOnce(30.seconds, self, RetryPivotRefresh)(context.dispatcher)
      )
      return
    }

    val newPivotBlock = newPivotOpt.get
    val newPivotHeaderOpt = blockchainReader.getBlockHeaderByNumber(newPivotBlock)

    if (newPivotHeaderOpt.isEmpty) {
      // Header not available locally — request it from a peer via the bootstrap mechanism.
      // The coordinator stays alive (all peers are stateless, so no dispatch will happen).
      // When BootstrapComplete arrives in the syncing state, the refresh is completed.
      log.info(s"Pivot header for block $newPivotBlock not available locally. Requesting header bootstrap...")
      // Pause chain download to free up peers for the pivot header bootstrap
      chainDownloader.foreach(_ ! ChainDownloader.Pause)
      pendingPivotRefresh = Some((newPivotBlock, reason))
      context.parent ! StartRegularSyncBootstrap(newPivotBlock)
      // Reset account stagnation timer while we wait for the header.
      // Note: do NOT reset lastStorageProgressMs here — if storage has been stalled with
      // no actual slot progress, the stagnation timer must continue counting so it can
      // eventually trigger a full restart rather than cycling pivots indefinitely.
      lastAccountProgressMs = System.currentTimeMillis()
      return
    }

    completePivotRefreshWithStateRoot(newPivotBlock, newPivotHeaderOpt.get, reason)
  }

  /** Update best block info and chain weight so ETH status handshake advertises the correct forkId.
    *
    * Without this, getBestBlockNumber() returns 0 (genesis) during SNAP sync, causing peers to reject us with
    * incompatible forkId (e.g. Frontier vs Spiral). This stores the pivot header, chain weight, and best block info so
    * that createStatusMsg() in EthNodeStatus64ExchangeState can build a valid status message referencing the pivot.
    */
  private def updateBestBlockForPivot(header: BlockHeader, pivotBlockNumber: BigInt): Unit = {
    val pivotHash = header.hash
    val estimatedTotalDifficulty =
      if (pivotBlockNumber == BigInt(0)) header.difficulty
      else header.difficulty * pivotBlockNumber
    // Store header so getBestBlockHeader() -> getBlockHeaderByNumber(pivot) finds it
    blockchainWriter.storeBlockHeader(header).commit()
    blockchainWriter
      .storeChainWeight(pivotHash, ChainWeight.totalDifficultyOnly(estimatedTotalDifficulty))
      .commit()
    appStateStorage
      .putBestBlockInfo(com.chipprbots.ethereum.domain.appstate.BlockInfo(pivotHash, pivotBlockNumber))
      .commit()
    log.info(
      s"Updated best block for ETH status: block=$pivotBlockNumber, hash=${pivotHash.toHex.take(16)}..., " +
        s"estimatedTD=$estimatedTotalDifficulty"
    )
  }

  /** Complete the pivot refresh once we have the header (either from local storage or bootstrapped from peer). */
  private def completePivotRefreshWithStateRoot(
      newPivotBlock: BigInt,
      newPivotHeader: BlockHeader,
      reason: String
  ): Unit = {
    val newStateRoot = newPivotHeader.stateRoot
    val oldPivot = pivotBlock.getOrElse(BigInt(0))
    val oldRoot = stateRoot.map(_.take(4).toHex).getOrElse("none")
    val newRoot = newStateRoot.take(4).toHex

    if (stateRoot.contains(newStateRoot)) {
      log.warning(s"Pivot refresh: new root $newRoot is same as old. Falling back to full restart.")
      restartSnapSync(s"pivot refresh produced same root ($newRoot): $reason")
      return
    }

    log.info(s"Pivot refreshed: block $oldPivot -> $newPivotBlock, root $oldRoot -> $newRoot")
    val drift = newPivotBlock - oldPivot
    if (drift > 500) {
      MilestoneLog.event(s"Pivot refreshed | old=$oldPivot new=$newPivotBlock drift=$drift phase=$currentPhase")
    }

    // Update internal state
    pivotBlock = Some(newPivotBlock)
    stateRoot = Some(newStateRoot)
    // Note: mptStorage stays the same — content-addressed nodes don't need re-tagging.
    // The backing storage was already created for the original pivot block number,
    // but since nodes are keyed by hash, they're valid for any root.

    // Persist new pivot
    appStateStorage.putSnapSyncPivotBlock(newPivotBlock).commit()
    appStateStorage.putSnapSyncStateRoot(newStateRoot).commit()
    updateBestBlockForPivot(newPivotHeader, newPivotBlock)
    SNAPSyncMetrics.setPivotBlockNumber(newPivotBlock)

    // Geth-aligned: send refresh signal to ALL active coordinators (all 3 run concurrently)
    accountRangeCoordinator.foreach(_ ! actors.Messages.PivotRefreshed(newStateRoot))
    storageRangeCoordinator.foreach(_ ! actors.Messages.StoragePivotRefreshed(newStateRoot))
    // Bytecodes are content-addressed (hash-keyed) so pivot changes don't invalidate them,
    // but the coordinator should clear stale peer tracking.
    bytecodeCoordinator.foreach(_ ! actors.Messages.ByteCodePivotRefreshed)
    // Healing coordinator: update root, clear pending tasks and stateless peers.
    // Then re-walk the trie with the new root to discover missing nodes.
    trieNodeHealingCoordinator.foreach { coordinator =>
      coordinator ! actors.Messages.HealingPivotRefreshed(newStateRoot)
      // Re-walk trie with new root to populate fresh healing tasks
      trieWalkInProgress = false // Reset so startTrieWalk() can proceed
      startTrieWalk()
    }
    // Chain download target extends to the new pivot (chain data is canonical, never invalidated)
    if (chainDownloader.isDefined) {
      chainDownloader.foreach(_ ! ChainDownloader.UpdateTarget(newPivotBlock))
      // Resume chain download if it was paused during pivot header bootstrap
      chainDownloader.foreach(_ ! ChainDownloader.Resume)
    } else {
      // Start chain downloader if not yet started (e.g. pivot was 0 at initial bootstrap)
      startChainDownloader()
    }

    // Reset account stagnation timer during pivot refresh recovery.
    // Note: do NOT reset lastStorageProgressMs — only actual slot downloads (via
    // ProgressStorageSlotsSynced) should reset the storage stagnation timer.
    // This ensures that repeated stateless-peer pivot cycles don't prevent the
    // stagnation watchdog from eventually triggering a full restart.
    lastAccountProgressMs = System.currentTimeMillis()
  }

  private def restartSnapSync(reason: String): Unit = {
    log.warning(s"Restarting SNAP sync with a fresher pivot: $reason")

    // Clear any pending pivot refresh (we're doing a full restart instead)
    pendingPivotRefresh = None

    // NOTE: do NOT reset consecutivePivotRefreshes here. restartSnapSync is often called
    // from refreshPivotInPlace when no new pivot is available, which means the counter would
    // reset on every failed cycle and never reach the threshold. The counter only resets on
    // actual account download progress (ProgressAccountsSynced) or at startSnapSync().

    // Cancel periodic phase request ticks
    accountRangeRequestTask.foreach(_.cancel()); accountRangeRequestTask = None
    bytecodeRequestTask.foreach(_.cancel()); bytecodeRequestTask = None
    storageRangeRequestTask.foreach(_.cancel()); storageRangeRequestTask = None
    accountStagnationCheckTask.foreach(_.cancel()); accountStagnationCheckTask = None
    storageStagnationCheckTask.foreach(_.cancel()); storageStagnationCheckTask = None
    pivotFreshnessCheckTask.foreach(_.cancel()); pivotFreshnessCheckTask = None
    healingRequestTask.foreach(_.cancel()); healingRequestTask = None
    bootstrapCheckTask.foreach(_.cancel()); bootstrapCheckTask = None
    pivotBootstrapRetryTask.foreach(_.cancel()); pivotBootstrapRetryTask = None
    rateTrackerTuneTask.foreach(_.cancel()); rateTrackerTuneTask = None

    // Stop coordinators so we don't double-run phases
    accountRangeCoordinator.foreach(context.stop); accountRangeCoordinator = None
    bytecodeCoordinator.foreach(context.stop); bytecodeCoordinator = None
    storageRangeCoordinator.foreach(context.stop); storageRangeCoordinator = None
    trieNodeHealingCoordinator.foreach(context.stop); trieNodeHealingCoordinator = None

    // Clear inflight request timeouts and internal phase state
    requestTracker.clear()

    // Clear concurrent download state and recovery data
    accountsComplete = false
    bytecodePhaseComplete = false
    storagePhaseComplete = false
    appStateStorage.putSnapSyncAccountsComplete(false).commit()
    appStateStorage.putSnapSyncStorageFilePath("").commit()
    appStateStorage.putSnapSyncCompletedStoragePath("").commit()

    // Reset pivot/state root and storage so a new selection is committed
    pivotBlock = None
    stateRoot = None
    mptStorage = None
    currentPhase = Idle
    coordinatorGeneration += 1

    // Reset progress counters so logs/ETA reflect the new attempt
    progressMonitor.reset()

    // Re-run pivot selection/bootstrap with the latest visible peer set
    context.become(idle)
    startSnapSync()
  }

  /** Trigger healing by re-running the trie walk with path tracking. Called from validateState() when missing nodes are
    * discovered. The passed hashes are just an indicator — we re-walk to get proper paths for GetTrieNodes.
    */
  private def triggerHealingForMissingNodes(missingNodes: Seq[ByteString]): Unit = {
    log.info(s"Validation found ${missingNodes.size} missing nodes — re-running trie walk with paths for healing")
    currentPhase = StateHealing
    stateRoot.foreach { root =>
      val storage = getOrCreateMptStorage(pivotBlock.getOrElse(BigInt(0)))
      scala.concurrent
        .Future {
          val validator = new StateValidator(storage)
          validator.findMissingNodesWithPaths(root)
        }(ec)
        .foreach {
          case Right(nodes) => self ! TrieWalkResult(nodes)
          case Left(error)  => self ! TrieWalkFailed(error)
        }(ec)
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
  private def currentSyncStatus: SyncProtocol.Status = {
    val progress = progressMonitor.currentProgress
    val startingBlock = appStateStorage.getSyncStartingBlock()
    val currentBlock = pivotBlock.getOrElse(startingBlock)

    /** Helper to get estimate or actual count, whichever is larger. Used as a defensive fallback when estimates are not
      * yet available (0).
      */
    def estimateOrActual(estimate: Long, actual: Long): Long = estimate.max(actual)

    // Calculate state progress based on current phase
    // SNAP sync involves multiple phases: accounts, bytecode, storage, healing
    val (pulledStates, knownStates) = currentPhase match {
      case AccountRangeSync =>
        // In account range sync, we track accounts synced
        (progress.accountsSynced, estimateOrActual(progress.estimatedTotalAccounts, progress.accountsSynced))

      case ByteCodeSync =>
        // In bytecode sync, we add accounts + bytecodes
        val pulled = progress.accountsSynced + progress.bytecodesDownloaded
        val known = progress.estimatedTotalAccounts + estimateOrActual(
          progress.estimatedTotalBytecodes,
          progress.bytecodesDownloaded
        )
        (pulled, known)

      case ByteCodeAndStorageSync =>
        // Concurrent bytecode + storage sync
        val pulled = progress.accountsSynced + progress.bytecodesDownloaded + progress.storageSlotsSynced
        val known = progress.estimatedTotalAccounts + estimateOrActual(
          progress.estimatedTotalBytecodes,
          progress.bytecodesDownloaded
        ) +
          estimateOrActual(progress.estimatedTotalSlots, progress.storageSlotsSynced)
        (pulled, known)

      case StorageRangeSync =>
        // In storage sync, we add accounts + bytecodes + storage slots
        val pulled = progress.accountsSynced + progress.bytecodesDownloaded + progress.storageSlotsSynced
        val known = progress.estimatedTotalAccounts + progress.estimatedTotalBytecodes +
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

      case Idle | Completed =>
        (0L, 0L)
    }

    SyncProtocol.Status.Syncing(
      startingBlockNumber = startingBlock,
      blocksProgress = SyncProtocol.Status.Progress(currentBlock, currentBlock),
      stateNodesProgress = Some(SyncProtocol.Status.Progress(BigInt(pulledStates), BigInt(knownStates)))
    )
  }

  // Track consecutive account stall pivot refreshes to detect truly unrecoverable situations.
  private var consecutiveAccountStallRefreshes: Int = 0
  private val maxAccountStallRefreshes: Int = 10 // 10 refreshes × 10min threshold = ~100 min before giving up

  private def maybeRestartIfAccountStagnant(progress: actors.AccountRangeStats): Unit = {
    if (currentPhase != AccountRangeSync) return

    // Only consider it a stall if there is still work to do.
    val workRemaining = progress.tasksPending > 0 || progress.tasksActive > 0
    if (!workRemaining) return

    // Update liveness based on task completions OR account download progress.
    // The sync is making progress if EITHER metric advances. This prevents false
    // stagnation when accounts are downloading steadily across all 16 chunks but
    // no single chunk has finished its full 1/16th range yet.
    val taskProgress = progress.tasksCompleted > lastAccountTasksCompleted
    val downloadProgress = progress.accountsDownloaded > lastAccountsDownloaded

    if (taskProgress || downloadProgress) {
      lastAccountTasksCompleted = progress.tasksCompleted
      lastAccountsDownloaded = progress.accountsDownloaded
      lastAccountProgressMs = System.currentTimeMillis()
      consecutiveAccountStallRefreshes = 0 // Reset on real progress
      return
    }

    val now = System.currentTimeMillis()
    val stalledForMs = now - lastAccountProgressMs
    if (stalledForMs < AccountStagnationThreshold.toMillis) return

    // Avoid noisy rapid refreshes.
    if (now - lastPivotRestartMs < MinPivotRestartInterval.toMillis) return
    lastPivotRestartMs = now

    consecutiveAccountStallRefreshes += 1

    val context =
      s"account range sync stalled: no download progress for ${stalledForMs / 1000}s " +
        s"(threshold=${AccountStagnationThreshold.toSeconds}s), accountsDownloaded=${progress.accountsDownloaded}, " +
        s"tasksPending=${progress.tasksPending}, tasksActive=${progress.tasksActive}"

    if (consecutiveAccountStallRefreshes > maxAccountStallRefreshes) {
      // Truly unrecoverable after many pivot refreshes — fall back
      log.error(
        s"Account sync stalled after $consecutiveAccountStallRefreshes consecutive pivot refreshes. " +
          s"Falling back to fast sync."
      )
      MilestoneLog.error(
        s"Account stagnation | $consecutiveAccountStallRefreshes consecutive stall refreshes, falling back to fast sync"
      )
      if (recordCriticalFailure(context)) {
        requestSnapRetry(s"Account stagnation: $context")
      } else {
        restartSnapSync(context)
      }
    } else {
      // Bug 29 fix: do an in-place pivot refresh instead of restartSnapSync().
      // restartSnapSync() destroys all in-memory account trie data (50M+ accounts lost).
      // In-place refresh updates the state root while preserving downloaded data.
      // Trie nodes are content-addressed, so ~99.9% remain valid across pivots.
      log.warning(
        s"Account stall detected ($context). " +
          s"Refreshing pivot in-place to recover (attempt $consecutiveAccountStallRefreshes/$maxAccountStallRefreshes). " +
          s"Account data preserved."
      )
      lastAccountProgressMs = System.currentTimeMillis() // Reset stall timer after refresh
      refreshPivotInPlace(s"account stall: $context")
    }
  }

  private def completeSnapSync(): Unit =
    pivotBlock.foreach { pivot =>
      if (snapSyncConfig.deferredMerkleization) {
        // With deferred merkleization, don't wait for chain download to finish.
        // Downloading 24M+ block headers/bodies/receipts from genesis takes days and
        // blocks the node from syncing new blocks. Regular sync will handle chain data
        // from the pivot forward. Historical chain data can be backfilled later.
        log.info(
          "Deferred merkleization enabled — skipping chain download wait. " +
            "Finalizing SNAP sync immediately to start regular sync from pivot."
        )
        // Stop the chain downloader — regular sync handles blocks from pivot onward
        chainDownloader.foreach(context.stop)
        chainDownloader = None
        finalizeSnapSync(pivot)
      } else {
        // If chain download is still running, boost its concurrency and wait
        if (!chainDownloadComplete && chainDownloader.isDefined) {
          log.info("SNAP state sync complete, boosting chain download concurrency and waiting for completion...")
          chainDownloader.foreach(_ ! ChainDownloader.BoostConcurrency(
            snapSyncConfig.chainDownloadBoostedConcurrentRequests
          ))
          currentPhase = ChainDownloadCompletion
          progressMonitor.startPhase(ChainDownloadCompletion)
          // Safety timeout: if the chain downloader never sends Done (crash, lost message),
          // finalize anyway after 2 hours rather than hanging indefinitely.
          chainDownloadTimeoutTask = Some(
            scheduler.scheduleOnce(2.hours) {
              self ! ChainDownloadTimeout
            }(ec)
          )
          context.become(waitingForChainDownload)
          return
        }

        finalizeSnapSync(pivot)
      }
    }

  /** Final SNAP sync completion — called when both state sync and chain download are done. */
  private def finalizeSnapSync(pivot: BigInt): Unit = {
    chainDownloadTimeoutTask.foreach(_.cancel())
    chainDownloadTimeoutTask = None

    // Look up the pivot header so we can store a complete "best block" anchor.
    // RegularSync's BranchResolution needs: header, body, number→hash mapping,
    // ChainWeight, and BestBlockInfo (hash + number) to accept blocks that chain
    // from the pivot.
    blockchainReader.getBlockHeaderByNumber(pivot) match {
      case Some(pivotHeader) =>
        val pivotHash = pivotHeader.hash
        val estimatedTotalDifficulty = pivotHeader.difficulty * pivot

        // H-013: Atomic finalization — commit SnapSyncDone marker together with
        // pivot block, chain weight, and best block info in a single batch.
        // If any of these are missing when SnapSyncDone=true, regular sync fails
        // on startup (branch resolution finds no chain weight, no best block hash).
        // go-ethereum writes pivot + state atomically; we do the same.
        appStateStorage.snapSyncDone()
          .and(blockchainWriter.storeBlock(Block(pivotHeader, BlockBody.empty)))
          .and(blockchainWriter.storeChainWeight(
            pivotHash,
            ChainWeight.totalDifficultyOnly(estimatedTotalDifficulty)
          ))
          .and(appStateStorage.putBestBlockInfo(
            com.chipprbots.ethereum.domain.appstate.BlockInfo(pivotHash, pivot)
          ))
          .commit()

        log.info(s"SNAP sync completed successfully at block $pivot (hash=${pivotHash.take(8).toHex})")

      case None =>
        // Fallback: shouldn't happen since PivotHeaderBootstrap stored the header
        log.warning(s"Pivot header for block $pivot not found in storage — setting best block number only")
        appStateStorage.snapSyncDone()
          .and(appStateStorage.putBestBlockNumber(pivot))
          .commit()
    }

    // Stop chain downloader if still running
    chainDownloader.foreach(context.stop)
    chainDownloader = None

    progressMonitor.complete()
    log.info(progressMonitor.currentProgress.toString)

    context.become(completed)
    context.parent ! Done
  }

  /** Waiting for parallel chain download to finish after SNAP state sync completed. */
  def waitingForChainDownload: Receive = handlePeerListMessagesWithRateTracking.orElse {
    case ChainDownloader.Done =>
      log.info("Parallel chain download complete. Finalizing SNAP sync.")
      chainDownloadTimeoutTask.foreach(_.cancel())
      chainDownloadTimeoutTask = None
      chainDownloadComplete = true
      pivotBlock.foreach(finalizeSnapSync)

    case ChainDownloadTimeout =>
      log.warning(
        "Chain download did not complete within timeout. " +
          "Finalizing SNAP sync anyway — regular sync will handle remaining chain data."
      )
      chainDownloadComplete = true
      pivotBlock.foreach(finalizeSnapSync)

    case ChainDownloader.Progress(h, b, r, t) =>
      progressMonitor.updateChainProgress(h, b, r, t)

    case SyncProtocol.GetStatus =>
      sender() ! currentSyncStatus

    case GetProgress =>
      sender() ! progressMonitor.currentProgress
  }

  private def startChainDownloader(): Unit = {
    if (!snapSyncConfig.chainDownloadEnabled) return
    pivotBlock.filter(_ > 0).foreach { pivot =>
      if (chainDownloader.isEmpty) {
        log.info("Starting parallel chain download from genesis to pivot block {}", pivot)
        coordinatorGeneration += 1
        val downloader = context.actorOf(
          ChainDownloader
            .props(
              blockchainReader,
              blockchainWriter,
              networkPeerManager,
              peerEventBus,
              syncConfig,
              scheduler,
              snapSyncConfig.chainDownloadMaxConcurrentRequests,
              snapSyncConfig.chainDownloadTimeout
            )
            .withDispatcher("sync-dispatcher"),
          s"chain-downloader-$coordinatorGeneration"
        )
        downloader ! ChainDownloader.Start(pivot)
        chainDownloader = Some(downloader)
        chainDownloadComplete = false
      }
    }
  }

  // --- SNAP progress persistence helpers ---

  /** Serialize range progress + pivot block to a simple key=value format for AppStateStorage. Format:
    * "pivotBlock=<N>\n<hexLast>=<hexNext>\n..." Deliberately simple — no JSON library dependency needed for 4-16
    * entries.
    */
  private def serializeSnapProgress(
      progress: Map[ByteString, ByteString],
      pivot: BigInt
  ): String = {
    val sb = new StringBuilder
    sb.append("pivotBlock=").append(pivot.toString).append('\n')
    progress.foreach { case (last, next) =>
      sb.append(last.toHex).append('=').append(next.toHex).append('\n')
    }
    sb.toString
  }

  /** Deserialize range progress from AppStateStorage format.
    * @return
    *   (pivotBlock, rangeProgress) or None if parsing fails
    */
  private def deserializeSnapProgress(data: String): Option[(BigInt, Map[ByteString, ByteString])] =
    try {
      val lines = data.split('\n').filter(_.nonEmpty)
      if (lines.isEmpty) return None

      var pivot: Option[BigInt] = None
      val ranges = scala.collection.mutable.Map.empty[ByteString, ByteString]

      lines.foreach { line =>
        val idx = line.indexOf('=')
        if (idx > 0) {
          val key = line.substring(0, idx)
          val value = line.substring(idx + 1)
          if (key == "pivotBlock") {
            pivot = Some(BigInt(value))
          } else {
            ranges += (ByteString(Hex.decode(key)) -> ByteString(Hex.decode(value)))
          }
        }
      }

      pivot.map(p => (p, ranges.toMap))
    } catch {
      case _: Exception => None
    }
}

object SNAPSyncController {

  sealed trait SyncPhase
  case object Idle extends SyncPhase
  case object AccountRangeSync extends SyncPhase
  case object ByteCodeSync extends SyncPhase
  case object ByteCodeAndStorageSync extends SyncPhase
  case object StorageRangeSync extends SyncPhase
  case object StateHealing extends SyncPhase
  case object StateValidation extends SyncPhase
  case object ChainDownloadCompletion extends SyncPhase
  case object Completed extends SyncPhase

  /** Source of pivot block selection */
  sealed trait PivotSelectionSource {
    def name: String
  }
  case object NetworkPivot extends PivotSelectionSource {
    val name = "network"
  }
  case object LocalPivot extends PivotSelectionSource {
    val name = "local"
  }

  case object Start
  case object Done
  case class RetrySnapSync(reason: String) // Signal to parent to retry SNAP sync with backoff
  case class StartRegularSyncBootstrap(targetBlock: BigInt) // Request bootstrap from SyncController
  final case class BootstrapComplete(
      pivotHeader: Option[BlockHeader] = None
  ) // Signal from SyncController that bootstrap is done
  final case class PivotBootstrapFailed(
      reason: String
  ) // Signal from SyncController that pivot header bootstrap exhausted retries
  private case object RetrySnapSyncStart // Internal message to retry SNAP sync start after bootstrap
  case object AccountRangeSyncComplete
  case object ByteCodeSyncComplete
  case object StorageRangeSyncComplete

  /** Inline contract data dispatched from AccountRangeCoordinator after each account batch. Geth-aligned: bytecodes and
    * storage tasks are populated inline from processAccountResponse(), not queried after account download completes.
    */
  final case class IncrementalContractData(
      codeHashes: Seq[ByteString],
      storageTasks: Seq[StorageTask]
  )
  case object StateHealingComplete
  case object HealingAllPeersStateless
  case object StateValidationComplete
  case object ChainDownloadTimeout
  case object GetProgress

  /** Signal from coordinators that the current pivot/stateRoot is likely not serveable by peers.
    *
    * This is analogous to Nethermind's ExpiredRootHash detection (empty payload + empty proofs).
    */
  final case class PivotStateUnservable(rootHash: ByteString, reason: String, consecutiveEmptyResponses: Int)

  /** Progress updates emitted by worker coordinators.
    *
    * These are deltas (increments), not absolute totals.
    */
  final case class ProgressAccountsSynced(count: Long)
  case object ProgressAccountsFinalizingTrie
  case object ProgressAccountsTrieFinalized
  final case class AccountTrieFinalized(finalizedRoot: ByteString)
  final case class ProgressBytecodesDownloaded(count: Long)
  final case class ProgressStorageSlotsSynced(count: Long)
  final case class ProgressNodesHealed(count: Long)
  final case class ProgressAccountEstimate(estimatedTotal: Long)
  final case class ProgressStorageContracts(completedContracts: Int, totalContracts: Int)

  def props(
      blockchainReader: BlockchainReader,
      blockchainWriter: BlockchainWriter,
      appStateStorage: AppStateStorage,
      stateStorage: StateStorage,
      evmCodeStorage: EvmCodeStorage,
      flatSlotStorage: FlatSlotStorage,
      networkPeerManager: ActorRef,
      peerEventBus: ActorRef,
      syncConfig: SyncConfig,
      snapSyncConfig: SNAPSyncConfig,
      scheduler: Scheduler
  )(implicit ec: ExecutionContext): Props =
    Props(
      new SNAPSyncController(
        blockchainReader,
        blockchainWriter,
        appStateStorage,
        stateStorage,
        evmCodeStorage,
        flatSlotStorage,
        networkPeerManager,
        peerEventBus,
        syncConfig,
        snapSyncConfig,
        scheduler
      )
    )
}

case class SNAPSyncConfig(
    enabled: Boolean = true,
    // How far behind the perceived head to place the pivot.
    // SNAP servers generally only guarantee serving *very recent* state; keeping this small improves storage serving.
    pivotBlockOffset: Long = 64,
    // If bootstrap takes too long and the selected pivot drifts too far behind the current network head,
    // abandon it and re-select a fresher pivot.
    maxPivotStalenessBlocks: Long = 4096,
    accountConcurrency: Int = 16,
    storageConcurrency: Int = 16,
    storageBatchSize: Int = 128,
    storageInitialResponseBytes: Int = 1048576,
    storageMinResponseBytes: Int = 131072,
    healingBatchSize: Int = 16,
    healingConcurrency: Int = 16,
    stateValidationEnabled: Boolean = true,
    maxRetries: Int = 3,
    timeout: FiniteDuration = 30.seconds,
    maxSnapSyncFailures: Int = 5, // Max failures before fallback to fast sync
    // Grace period after bootstrap to wait for snap/1-capable peers before falling back.
    // If no connected peer advertises snap/1 within this window, fall back to fast sync.
    snapCapabilityGracePeriod: FiniteDuration = 30.seconds,
    // Account stagnation timeout: if no account range tasks complete within this window,
    // record a critical failure (may trigger fallback). Reduced from 15 minutes to catch
    // non-snap peers faster.
    accountStagnationTimeout: FiniteDuration = 10.minutes,
    maxInFlightPerPeer: Int = 5,
    accountTrieFlushThreshold: Int = 50000,
    accountInitialResponseBytes: Int = 524288,
    accountMinResponseBytes: Int = 102400,
    chainDownloadEnabled: Boolean = true,
    chainDownloadMaxConcurrentRequests: Int = 2,
    chainDownloadBoostedConcurrentRequests: Int = 16,
    chainDownloadTimeout: FiniteDuration = 10.seconds,
    minSnapPeers: Int = 3,
    snapPeerEvictionInterval: FiniteDuration = 15.seconds,
    maxEvictionsPerCycle: Int = 3,
    deferredMerkleization: Boolean = true
)

object SNAPSyncConfig {
  def fromConfig(config: com.typesafe.config.Config): SNAPSyncConfig = {
    val snapConfig = config.getConfig("snap-sync")

    SNAPSyncConfig(
      enabled = snapConfig.getBoolean("enabled"),
      pivotBlockOffset = snapConfig.getLong("pivot-block-offset"),
      maxPivotStalenessBlocks =
        if (snapConfig.hasPath("max-pivot-staleness-blocks"))
          snapConfig.getLong("max-pivot-staleness-blocks")
        else 4096,
      accountConcurrency = snapConfig.getInt("account-concurrency"),
      storageConcurrency = snapConfig.getInt("storage-concurrency"),
      storageBatchSize = snapConfig.getInt("storage-batch-size"),
      storageInitialResponseBytes =
        if (snapConfig.hasPath("storage-initial-response-bytes"))
          snapConfig.getInt("storage-initial-response-bytes")
        else 1048576,
      storageMinResponseBytes =
        if (snapConfig.hasPath("storage-min-response-bytes"))
          snapConfig.getInt("storage-min-response-bytes")
        else 131072,
      healingBatchSize = snapConfig.getInt("healing-batch-size"),
      healingConcurrency =
        if (snapConfig.hasPath("healing-concurrency"))
          snapConfig.getInt("healing-concurrency")
        else 16,
      stateValidationEnabled = snapConfig.getBoolean("state-validation-enabled"),
      maxRetries = snapConfig.getInt("max-retries"),
      timeout = snapConfig.getDuration("timeout").toMillis.millis,
      maxSnapSyncFailures =
        if (snapConfig.hasPath("max-snap-sync-failures"))
          snapConfig.getInt("max-snap-sync-failures")
        else 5,
      snapCapabilityGracePeriod =
        if (snapConfig.hasPath("snap-capability-grace-period"))
          snapConfig.getDuration("snap-capability-grace-period").toMillis.millis
        else 30.seconds,
      accountStagnationTimeout =
        if (snapConfig.hasPath("account-stagnation-timeout"))
          snapConfig.getDuration("account-stagnation-timeout").toMillis.millis
        else 3.minutes,
      maxInFlightPerPeer =
        if (snapConfig.hasPath("max-inflight-per-peer"))
          snapConfig.getInt("max-inflight-per-peer")
        else 5,
      accountTrieFlushThreshold =
        if (snapConfig.hasPath("account-trie-flush-threshold"))
          snapConfig.getInt("account-trie-flush-threshold")
        else 50000,
      accountInitialResponseBytes =
        if (snapConfig.hasPath("account-initial-response-bytes"))
          snapConfig.getInt("account-initial-response-bytes")
        else 524288,
      accountMinResponseBytes =
        if (snapConfig.hasPath("account-min-response-bytes"))
          snapConfig.getInt("account-min-response-bytes")
        else 102400,
      chainDownloadEnabled =
        if (snapConfig.hasPath("chain-download-enabled"))
          snapConfig.getBoolean("chain-download-enabled")
        else true,
      chainDownloadMaxConcurrentRequests =
        if (snapConfig.hasPath("chain-download-max-concurrent-requests"))
          snapConfig.getInt("chain-download-max-concurrent-requests")
        else 2,
      chainDownloadBoostedConcurrentRequests =
        if (snapConfig.hasPath("chain-download-boosted-concurrent-requests"))
          snapConfig.getInt("chain-download-boosted-concurrent-requests")
        else 16,
      chainDownloadTimeout =
        if (snapConfig.hasPath("chain-download-timeout"))
          snapConfig.getDuration("chain-download-timeout").toMillis.millis
        else 10.seconds,
      minSnapPeers =
        if (snapConfig.hasPath("min-snap-peers"))
          snapConfig.getInt("min-snap-peers")
        else 3,
      snapPeerEvictionInterval =
        if (snapConfig.hasPath("snap-peer-eviction-interval"))
          snapConfig.getDuration("snap-peer-eviction-interval").toMillis.millis
        else 15.seconds,
      maxEvictionsPerCycle =
        if (snapConfig.hasPath("max-evictions-per-cycle"))
          snapConfig.getInt("max-evictions-per-cycle")
        else 3,
      deferredMerkleization =
        if (snapConfig.hasPath("deferred-merkleization"))
          snapConfig.getBoolean("deferred-merkleization")
        else true
    )
  }
}

class StateValidator(mptStorage: MptStorage) {

  import com.chipprbots.ethereum.mpt._
  import com.chipprbots.ethereum.domain.Account
  import scala.collection.mutable

  /** Validate the account trie by traversing it and detecting missing nodes.
    *
    * @param stateRoot
    *   The expected state root hash
    * @return
    *   Right with missing node hashes if any, or Left with error message
    */
  def validateAccountTrie(stateRoot: ByteString): Either[String, Seq[ByteString]] =
    try {
      val missingNodes = mutable.ArrayBuffer[ByteString]()

      // Try to load the root node from storage
      try {
        val rootNode = mptStorage.get(stateRoot.toArray)

        // Traverse the trie and collect missing nodes
        traverseForMissingNodes(rootNode, mptStorage, missingNodes)

        if (missingNodes.isEmpty) {
          Right(Seq.empty)
        } else {
          Right(missingNodes.toSeq)
        }

      } catch {
        case e: MerklePatriciaTrie.MissingNodeException =>
          // Root node itself is missing
          Left(s"Missing root node: ${stateRoot.take(8).toHex}")
        case e: Exception =>
          Left(s"Failed to load root node: ${e.getMessage}")
      }

    } catch {
      case e: Exception =>
        Left(s"Validation error: ${e.getMessage}")
    }

  /** Validate all storage tries by walking through all accounts and checking their storage.
    *
    * @param stateRoot
    *   The state root to validate from
    * @return
    *   Right with missing node hashes if any, or Left with error message
    */
  def validateAllStorageTries(stateRoot: ByteString): Either[String, Seq[ByteString]] =
    try {
      val missingStorageNodes = mutable.ArrayBuffer[ByteString]()
      val accounts = mutable.ArrayBuffer[Account]()

      // First, collect all accounts by traversing the account trie
      try {
        val rootNode = mptStorage.get(stateRoot.toArray)
        collectAccounts(rootNode, mptStorage, accounts)
      } catch {
        case _: Exception =>
          return Left("Cannot validate storage tries: failed to traverse account trie")
      }

      // Now validate each account's storage trie
      accounts.foreach { account =>
        if (account.storageRoot != Account.EmptyStorageRootHash) {
          try {
            val storageRootNode = mptStorage.get(account.storageRoot.toArray)
            traverseForMissingNodes(storageRootNode, mptStorage, missingStorageNodes)
          } catch {
            case e: MerklePatriciaTrie.MissingNodeException =>
              missingStorageNodes += e.hash
            case e: Exception =>
              // Continue with other accounts - log at caller level if needed
              ()
          }
        }
      }

      if (missingStorageNodes.isEmpty) {
        Right(Seq.empty)
      } else {
        Right(missingStorageNodes.toSeq)
      }

    } catch {
      case e: Exception =>
        Left(s"Storage validation error: ${e.getMessage}")
    }

  /** Recursively traverse a trie and collect missing node hashes.
    *
    * @param node
    *   The current node being traversed
    * @param storage
    *   The storage to lookup nodes from
    * @param missingNodes
    *   Buffer to collect missing node hashes
    * @param visited
    *   Set of visited node hashes to prevent infinite loops
    */
  private def traverseForMissingNodes(
      node: MptNode,
      storage: MptStorage,
      missingNodes: mutable.ArrayBuffer[ByteString],
      visited: mutable.Set[ByteString] = mutable.Set.empty
  ): Unit =
    // Note: visited check is per-case, NOT at the top.
    // HashNode and its resolved node share the same hash (decodeNode sets
    // cachedHash = lookupKey), so a top-level visited check would skip the
    // resolved node immediately after adding the HashNode — truncating the
    // traversal at depth 1.
    node match {
      case _: LeafNode | NullNode =>
        ()

      case ext: ExtensionNode =>
        val nodeHash = ByteString(ext.hash)
        if (!visited.contains(nodeHash)) {
          visited += nodeHash
          traverseForMissingNodes(ext.next, storage, missingNodes, visited)
        }

      case branch: BranchNode =>
        val nodeHash = ByteString(branch.hash)
        if (!visited.contains(nodeHash)) {
          visited += nodeHash
          branch.children.foreach { child =>
            traverseForMissingNodes(child, storage, missingNodes, visited)
          }
        }

      case hash: HashNode =>
        // Don't add to visited here — the resolved node will add its hash
        // (which is the same value) when it recurses as a non-HashNode.
        val hashKey = ByteString(hash.hash)
        if (!visited.contains(hashKey)) {
          try {
            val resolvedNode = storage.get(hash.hash)
            traverseForMissingNodes(resolvedNode, storage, missingNodes, visited)
          } catch {
            case _: MerklePatriciaTrie.MissingNodeException =>
              missingNodes += ByteString(hash.hash)
            case _: Exception =>
              // Unreachable node — treat as missing
              missingNodes += ByteString(hash.hash)
          }
        }
    }

  /** Recursively collect all accounts from a trie.
    *
    * @param node
    *   The current node being traversed
    * @param storage
    *   The storage to lookup nodes from
    * @param accounts
    *   Buffer to collect accounts
    * @param visited
    *   Set of visited node hashes to prevent infinite loops
    */
  private def collectAccounts(
      node: MptNode,
      storage: MptStorage,
      accounts: mutable.ArrayBuffer[Account],
      visited: mutable.Set[ByteString] = mutable.Set.empty
  ): Unit = {
    import com.chipprbots.ethereum.domain.Account.accountSerializer

    // Note: visited check is per-case (see traverseForMissingNodes comment)
    node match {
      case leaf: LeafNode =>
        try {
          val account = accountSerializer.fromBytes(leaf.value.toArray)
          accounts += account
        } catch {
          case _: Exception => ()
        }

      case ext: ExtensionNode =>
        val nodeHash = ByteString(ext.hash)
        if (!visited.contains(nodeHash)) {
          visited += nodeHash
          collectAccounts(ext.next, storage, accounts, visited)
        }

      case branch: BranchNode =>
        val nodeHash = ByteString(branch.hash)
        if (!visited.contains(nodeHash)) {
          visited += nodeHash
          branch.children.foreach { child =>
            collectAccounts(child, storage, accounts, visited)
          }
          branch.terminator.foreach { value =>
            try {
              val account = accountSerializer.fromBytes(value.toArray)
              accounts += account
            } catch {
              case _: Exception => ()
            }
          }
        }

      case hash: HashNode =>
        val hashKey = ByteString(hash.hash)
        if (!visited.contains(hashKey)) {
          try {
            val resolvedNode = storage.get(hash.hash)
            collectAccounts(resolvedNode, storage, accounts, visited)
          } catch {
            case _: MerklePatriciaTrie.MissingNodeException => ()
            case _: Exception                               => ()
          }
        }

      case NullNode => ()
    }
  }

  // ========================================
  // Path-tracking trie walk for GetTrieNodes healing
  // ========================================

  /** Find all missing trie nodes with GetTrieNodes-compatible pathsets. Walks the account trie and each account's
    * storage trie, tracking the hex nibble path to each missing node and encoding it as an HP-encoded compact path for
    * GetTrieNodes.
    *
    * @return
    *   (pathset, hash) pairs ready for TrieNodeHealingCoordinator.QueueMissingNodes
    */
  def findMissingNodesWithPaths(stateRoot: ByteString): Either[String, Seq[(Seq[ByteString], ByteString)]] = {
    val result = mutable.ArrayBuffer[(Seq[ByteString], ByteString)]()

    try {
      val rootNode = mptStorage.get(stateRoot.toArray)

      // Walk the account trie — missing account nodes get single-element pathsets
      traverseAccountTrieWithPaths(
        rootNode,
        mptStorage,
        Array.empty[Byte],
        result,
        mutable.Set.empty
      )

      Right(result.toSeq)
    } catch {
      case e: MerklePatriciaTrie.MissingNodeException =>
        // Root node itself is missing
        val compactPath = ByteString(HexPrefix.encode(Array.empty[Byte], isLeaf = false))
        result += ((Seq(compactPath), e.hash))
        Right(result.toSeq)
      case e: Exception =>
        Left(s"Trie walk failed: ${e.getMessage}")
    }
  }

  /** Walk the account trie, tracking nibble paths for missing nodes. Also checks storage tries for each account found.
    */
  private def traverseAccountTrieWithPaths(
      node: MptNode,
      storage: MptStorage,
      currentNibblePath: Array[Byte],
      result: mutable.ArrayBuffer[(Seq[ByteString], ByteString)],
      visited: mutable.Set[ByteString]
  ): Unit = {
    import com.chipprbots.ethereum.domain.Account.accountSerializer

    // Note: visited check is per-case (see traverseForMissingNodes comment)
    node match {
      case leaf: LeafNode =>
        try {
          val account = accountSerializer.fromBytes(leaf.value.toArray)
          if (account.storageRoot != com.chipprbots.ethereum.domain.Account.EmptyStorageRootHash) {
            val leafKeyNibbles = leaf.key.toArray
            val fullNibblePath = currentNibblePath ++ leafKeyNibbles
            val accountHashBytes = HexPrefix.nibblesToBytes(fullNibblePath)

            try {
              val storageRoot = storage.get(account.storageRoot.toArray)
              traverseStorageTrieWithPaths(
                storageRoot,
                storage,
                Array.empty[Byte],
                ByteString(accountHashBytes),
                result,
                mutable.Set.empty
              )
            } catch {
              case e: MerklePatriciaTrie.MissingNodeException =>
                val compactPath = ByteString(HexPrefix.encode(Array.empty[Byte], isLeaf = false))
                result += ((Seq(ByteString(accountHashBytes), compactPath), e.hash))
            }
          }
        } catch {
          case _: Exception => ()
        }

      case ext: ExtensionNode =>
        val nodeHash = ByteString(ext.hash)
        if (!visited.contains(nodeHash)) {
          visited += nodeHash
          val sharedKeyNibbles = ext.sharedKey.toArray
          val newPath = currentNibblePath ++ sharedKeyNibbles
          traverseAccountTrieWithPaths(ext.next, storage, newPath, result, visited)
        }

      case branch: BranchNode =>
        val nodeHash = ByteString(branch.hash)
        if (!visited.contains(nodeHash)) {
          visited += nodeHash
          for (i <- 0 until 16) {
            val child = branch.children(i)
            if (!child.isNull) {
              val newPath = currentNibblePath :+ i.toByte
              traverseAccountTrieWithPaths(child, storage, newPath, result, visited)
            }
          }
        }

      case hash: HashNode =>
        val hashKey = ByteString(hash.hash)
        if (!visited.contains(hashKey)) {
          try {
            val resolvedNode = storage.get(hash.hash)
            traverseAccountTrieWithPaths(resolvedNode, storage, currentNibblePath, result, visited)
          } catch {
            case _: MerklePatriciaTrie.MissingNodeException =>
              val compactPath = ByteString(HexPrefix.encode(currentNibblePath, isLeaf = false))
              result += ((Seq(compactPath), ByteString(hash.hash)))
          }
        }

      case NullNode => ()
    }
  }

  /** Walk a storage trie, tracking nibble paths for missing nodes. Missing nodes get two-element pathsets:
    * [account_hash, compact_storage_path].
    */
  private def traverseStorageTrieWithPaths(
      node: MptNode,
      storage: MptStorage,
      currentNibblePath: Array[Byte],
      accountHash: ByteString,
      result: mutable.ArrayBuffer[(Seq[ByteString], ByteString)],
      visited: mutable.Set[ByteString]
  ): Unit =
    // Note: visited check is per-case (see traverseForMissingNodes comment)
    node match {
      case _: LeafNode | NullNode => ()

      case ext: ExtensionNode =>
        val nodeHash = ByteString(ext.hash)
        if (!visited.contains(nodeHash)) {
          visited += nodeHash
          val sharedKeyNibbles = ext.sharedKey.toArray
          val newPath = currentNibblePath ++ sharedKeyNibbles
          traverseStorageTrieWithPaths(ext.next, storage, newPath, accountHash, result, visited)
        }

      case branch: BranchNode =>
        val nodeHash = ByteString(branch.hash)
        if (!visited.contains(nodeHash)) {
          visited += nodeHash
          for (i <- 0 until 16) {
            val child = branch.children(i)
            if (!child.isNull) {
              val newPath = currentNibblePath :+ i.toByte
              traverseStorageTrieWithPaths(child, storage, newPath, accountHash, result, visited)
            }
          }
        }

      case hash: HashNode =>
        val hashKey = ByteString(hash.hash)
        if (!visited.contains(hashKey)) {
          try {
            val resolvedNode = storage.get(hash.hash)
            traverseStorageTrieWithPaths(resolvedNode, storage, currentNibblePath, accountHash, result, visited)
          } catch {
            case _: MerklePatriciaTrie.MissingNodeException =>
              val compactPath = ByteString(HexPrefix.encode(currentNibblePath, isLeaf = false))
              result += ((Seq(accountHash, compactPath), ByteString(hash.hash)))
          }
        }
    }
}

class SyncProgressMonitor(_scheduler: Scheduler) extends Logger {

  import SNAPSyncController._
  import scala.concurrent.ExecutionContext.Implicits.global

  private var currentPhaseState: SyncPhase = Idle
  private var bytecodesDone: Boolean = false
  private var accountsSynced: Long = 0
  private var bytecodesDownloaded: Long = 0
  private var storageSlotsSynced: Long = 0
  private var nodesHealed: Long = 0
  private val startTime: Long = System.currentTimeMillis()
  private var phaseStartTime: Long = System.currentTimeMillis()

  // Whether the account range download is complete and the trie is being flushed to disk
  private var finalizingTrie: Boolean = false
  private var finalizeStartTimeMs: Long = 0

  // Estimated totals for ETA calculation (updated during sync)
  private var estimatedTotalAccounts: Long = 0
  private var estimatedTotalBytecodes: Long = 0
  private var estimatedTotalSlots: Long = 0

  // Storage contract completion tracking
  private var storageContractsCompleted: Int = 0
  private var storageContractsTotal: Int = 0

  // Chain download tracking (parallel header/body/receipt download)
  private var chainHeaders: BigInt = BigInt(0)
  private var chainBodies: BigInt = BigInt(0)
  private var chainReceipts: BigInt = BigInt(0)
  private var chainTarget: BigInt = BigInt(0)

  // Periodic logging
  private var lastLogTime: Long = System.currentTimeMillis()

  private var periodicLogTask: Option[Cancellable] = None

  // Metrics history for throughput averaging
  private val metricsWindow = 60 // 60 seconds window for averaging
  private val accountsHistory = mutable.Queue[(Long, Long)]() // (timestamp, count)
  private val bytecodesHistory = mutable.Queue[(Long, Long)]()
  private val slotsHistory = mutable.Queue[(Long, Long)]()
  private val nodesHistory = mutable.Queue[(Long, Long)]()

  /** Start periodic progress logging.
    * Uses java.util.Timer instead of Pekko Scheduler for reliability — the Pekko scheduler
    * silently fails in some configuration environments (observed on Barad-dûr mainnet).
    */
  def startPeriodicLogging(): Unit = {
    val timer = new java.util.Timer("sync-progress-monitor", true)
    timer.scheduleAtFixedRate(new java.util.TimerTask {
      def run(): Unit =
        try logProgress()
        catch { case e: Exception => log.error(s"Progress monitor error: ${e.getMessage}", e) }
    }, 30000L, 30000L)
    periodicLogTask = Some(new org.apache.pekko.actor.Cancellable {
      def cancel(): Boolean = { timer.cancel(); true }
      def isCancelled: Boolean = false
    })
  }

  /** Stop periodic progress logging */
  def stopPeriodicLogging(): Unit = {
    periodicLogTask.foreach(_.cancel())
    periodicLogTask = None
  }

  def setFinalizingTrie(value: Boolean): Unit = synchronized {
    finalizingTrie = value
    if (value) finalizeStartTimeMs = System.currentTimeMillis()
  }

  def setBytecodeComplete(): Unit = synchronized { bytecodesDone = true }

  /** Reset all counters/ETA state for a fresh SNAP attempt */
  def reset(): Unit = synchronized {
    currentPhaseState = Idle
    accountsSynced = 0
    bytecodesDownloaded = 0
    storageSlotsSynced = 0
    nodesHealed = 0
    finalizingTrie = false

    estimatedTotalAccounts = 0
    estimatedTotalBytecodes = 0
    estimatedTotalSlots = 0
    storageContractsCompleted = 0
    storageContractsTotal = 0
    chainHeaders = BigInt(0)
    chainBodies = BigInt(0)
    chainReceipts = BigInt(0)
    chainTarget = BigInt(0)

    phaseStartTime = System.currentTimeMillis()
    lastLogTime = System.currentTimeMillis()

    accountsHistory.clear()
    bytecodesHistory.clear()
    slotsHistory.clear()
    nodesHistory.clear()
  }

  def startPhase(phase: SyncPhase): Unit = {
    val previousPhase = currentPhaseState

    // Idempotency: callers may invoke startPhase(phase) more than once.
    // Only treat it as a transition when the phase actually changes.
    if (previousPhase != phase) {
      currentPhaseState = phase
      phaseStartTime = System.currentTimeMillis()
      log.info(s"📊 SNAP Sync phase transition: $previousPhase → $phase")
    }

    logProgress()
  }

  def complete(): Unit = {
    currentPhaseState = Completed
    stopPeriodicLogging()
    log.info("✅ SNAP Sync completed!")
    logProgress()
  }

  def incrementAccountsSynced(count: Long): Unit = synchronized {
    accountsSynced += count
    val now = System.currentTimeMillis()
    accountsHistory.enqueue((now, accountsSynced))
    cleanupHistory(accountsHistory, now)
  }

  def incrementBytecodesDownloaded(count: Long): Unit = synchronized {
    bytecodesDownloaded += count
    val now = System.currentTimeMillis()
    bytecodesHistory.enqueue((now, bytecodesDownloaded))
    cleanupHistory(bytecodesHistory, now)
  }

  def incrementStorageSlotsSynced(count: Long): Unit = synchronized {
    storageSlotsSynced += count
    val now = System.currentTimeMillis()
    slotsHistory.enqueue((now, storageSlotsSynced))
    cleanupHistory(slotsHistory, now)
  }

  def incrementNodesHealed(count: Long): Unit = synchronized {
    nodesHealed += count
    val now = System.currentTimeMillis()
    nodesHistory.enqueue((now, nodesHealed))
    cleanupHistory(nodesHistory, now)
  }

  /** Update estimated totals for ETA calculation */
  def updateEstimates(accounts: Long = 0, bytecodes: Long = 0, slots: Long = 0): Unit = synchronized {
    if (accounts > 0) estimatedTotalAccounts = accounts
    if (bytecodes > 0) estimatedTotalBytecodes = bytecodes
    if (slots > 0) estimatedTotalSlots = slots
  }

  /** Get current storage slots synced count */
  def getStorageSlotsSynced: Long = synchronized(storageSlotsSynced)

  /** Update storage contract completion counts for display */
  def updateStorageContracts(completed: Int, total: Int): Unit = synchronized {
    storageContractsCompleted = completed
    storageContractsTotal = total
  }

  /** Update chain download progress (headers/bodies/receipts vs target) */
  def updateChainProgress(headers: BigInt, bodies: BigInt, receipts: BigInt, target: BigInt): Unit = synchronized {
    chainHeaders = headers
    chainBodies = bodies
    chainReceipts = receipts
    chainTarget = target
  }

  /** Remove old entries from history (keep only last N seconds) */
  private def cleanupHistory(history: mutable.Queue[(Long, Long)], now: Long): Unit = {
    val cutoff = now - (metricsWindow * 1000)
    while (history.nonEmpty && history.head._1 < cutoff)
      history.dequeue()
  }

  /** Calculate recent throughput from history.
    *
    * NOTE: This method must only be called from synchronized contexts (calculateETA, currentProgress). It accesses
    * mutable collections that are modified by synchronized methods.
    */
  private def calculateRecentThroughput(history: mutable.Queue[(Long, Long)]): Double = {
    if (history.size < 2) return 0.0

    val oldest = history.head
    val newest = history.last
    val timeDiff = (newest._1 - oldest._1) / 1000.0
    val countDiff = newest._2 - oldest._2

    if (timeDiff > 0) countDiff / timeDiff else 0.0
  }

  /** Calculate ETA in seconds based on current progress and throughput */
  def calculateETA: Option[Long] = synchronized {
    currentPhaseState match {
      case AccountRangeSync if estimatedTotalAccounts > 0 =>
        val remaining = estimatedTotalAccounts - accountsSynced
        val throughput = calculateRecentThroughput(accountsHistory)
        if (throughput > 0 && remaining > 0) {
          Some((remaining / throughput).toLong)
        } else None

      case ByteCodeSync if estimatedTotalBytecodes > 0 =>
        val remaining = estimatedTotalBytecodes - bytecodesDownloaded
        val throughput = calculateRecentThroughput(bytecodesHistory)
        if (throughput > 0 && remaining > 0) {
          Some((remaining / throughput).toLong)
        } else None

      case ByteCodeAndStorageSync if estimatedTotalSlots > 0 =>
        // ETA based on storage (the longer phase)
        val remaining = estimatedTotalSlots - storageSlotsSynced
        val throughput = calculateRecentThroughput(slotsHistory)
        if (throughput > 0 && remaining > 0) {
          Some((remaining / throughput).toLong)
        } else None

      case StorageRangeSync if estimatedTotalSlots > 0 =>
        val remaining = estimatedTotalSlots - storageSlotsSynced
        val throughput = calculateRecentThroughput(slotsHistory)
        if (throughput > 0 && remaining > 0) {
          Some((remaining / throughput).toLong)
        } else None

      case _ => None
    }
  }

  /** Format ETA as human-readable string */
  private def formatETA(seconds: Long): String =
    if (seconds < 60) s"${seconds}s"
    else if (seconds < 3600) s"${seconds / 60}m ${seconds % 60}s"
    else s"${seconds / 3600}h ${(seconds % 3600) / 60}m"

  /** Log current progress.
    *
    * Synchronized to ensure consistent snapshot of state when logging.
    */
  def logProgress(): Unit = synchronized {
    val progress = currentProgress
    val etaStr = calculateETA.map(eta => s", ETA: ${formatETA(eta)}").getOrElse("")

    // Update Prometheus metrics
    SNAPSyncMetrics.measure(progress)

    log.info(s"📈 SNAP Sync Progress: ${progress.formattedString}$etaStr")
    lastLogTime = System.currentTimeMillis()
  }

  def currentProgress: SyncProgress = synchronized {
    val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
    val phaseElapsed = (System.currentTimeMillis() - phaseStartTime) / 1000.0

    // Calculate overall throughput (since start)
    val overallAccountsPerSec = if (elapsed > 0) accountsSynced / elapsed else 0
    val overallBytecodesPerSec = if (elapsed > 0) bytecodesDownloaded / elapsed else 0
    val overallSlotsPerSec = if (elapsed > 0) storageSlotsSynced / elapsed else 0
    val overallNodesPerSec = if (elapsed > 0) nodesHealed / elapsed else 0

    // Calculate recent throughput (last 60 seconds)
    val recentAccountsPerSec = calculateRecentThroughput(accountsHistory)
    val recentBytecodesPerSec = calculateRecentThroughput(bytecodesHistory)
    val recentSlotsPerSec = calculateRecentThroughput(slotsHistory)
    val recentNodesPerSec = calculateRecentThroughput(nodesHistory)

    // Calculate progress percentage for current phase
    val phaseProgress = currentPhaseState match {
      case AccountRangeSync if estimatedTotalAccounts > 0 =>
        (accountsSynced.toDouble / estimatedTotalAccounts * 100).toInt
      case ByteCodeSync if estimatedTotalBytecodes > 0 =>
        (bytecodesDownloaded.toDouble / estimatedTotalBytecodes * 100).toInt
      case ByteCodeAndStorageSync if estimatedTotalSlots > 0 =>
        // Use storage progress as the overall indicator (it's the longer phase)
        (storageSlotsSynced.toDouble / estimatedTotalSlots * 100).toInt
      case StorageRangeSync if estimatedTotalSlots > 0 =>
        (storageSlotsSynced.toDouble / estimatedTotalSlots * 100).toInt
      case _ => 0
    }

    val finalizeElapsedSec =
      if (finalizingTrie) ((System.currentTimeMillis() - finalizeStartTimeMs) / 1000.0).toInt else 0

    SyncProgress(
      phase = currentPhaseState,
      accountsSynced = accountsSynced,
      bytecodesDownloaded = bytecodesDownloaded,
      storageSlotsSynced = storageSlotsSynced,
      nodesHealed = nodesHealed,
      elapsedSeconds = elapsed,
      phaseElapsedSeconds = phaseElapsed,
      accountsPerSec = overallAccountsPerSec,
      bytecodesPerSec = overallBytecodesPerSec,
      slotsPerSec = overallSlotsPerSec,
      nodesPerSec = overallNodesPerSec,
      recentAccountsPerSec = recentAccountsPerSec,
      recentBytecodesPerSec = recentBytecodesPerSec,
      recentSlotsPerSec = recentSlotsPerSec,
      recentNodesPerSec = recentNodesPerSec,
      phaseProgress = phaseProgress,
      estimatedTotalAccounts = estimatedTotalAccounts,
      estimatedTotalBytecodes = estimatedTotalBytecodes,
      estimatedTotalSlots = estimatedTotalSlots,
      startTime = startTime,
      phaseStartTime = phaseStartTime,
      isFinalizingTrie = finalizingTrie,
      finalizeElapsedSeconds = finalizeElapsedSec,
      storageContractsCompleted = storageContractsCompleted,
      storageContractsTotal = storageContractsTotal,
      chainHeaders = chainHeaders,
      chainBodies = chainBodies,
      chainReceipts = chainReceipts,
      chainTarget = chainTarget,
      bytecodeComplete = bytecodesDone
    )
  }
}

case class SyncProgress(
    phase: SNAPSyncController.SyncPhase,
    accountsSynced: Long,
    bytecodesDownloaded: Long,
    storageSlotsSynced: Long,
    nodesHealed: Long,
    elapsedSeconds: Double,
    phaseElapsedSeconds: Double,
    accountsPerSec: Double,
    bytecodesPerSec: Double,
    slotsPerSec: Double,
    nodesPerSec: Double,
    recentAccountsPerSec: Double,
    recentBytecodesPerSec: Double,
    recentSlotsPerSec: Double,
    recentNodesPerSec: Double,
    phaseProgress: Int,
    estimatedTotalAccounts: Long,
    estimatedTotalBytecodes: Long,
    estimatedTotalSlots: Long,
    startTime: Long,
    phaseStartTime: Long,
    isFinalizingTrie: Boolean = false,
    finalizeElapsedSeconds: Int = 0,
    storageContractsCompleted: Int = 0,
    storageContractsTotal: Int = 0,
    chainHeaders: BigInt = BigInt(0),
    chainBodies: BigInt = BigInt(0),
    chainReceipts: BigInt = BigInt(0),
    chainTarget: BigInt = BigInt(0),
    bytecodeComplete: Boolean = false
) {

  private def wormChasesBrainBar: String = {
    import SNAPSyncController._

    // 6-stage pipeline: Accounts → Code+Storage → Healing → Validation → Chain → Done
    val stages = Vector[SyncPhase](
      AccountRangeSync,
      ByteCodeAndStorageSync,
      StateHealing,
      StateValidation,
      ChainDownloadCompletion,
      Completed
    )

    val stageIndex = stages.indexOf(phase) match {
      case -1 => 0
      case i  => i
    }

    val stageSize = if (stages.size <= 1) 1.0 else 1.0 / (stages.size - 1)
    val withinStage =
      if (phaseProgress > 0 && phaseProgress <= 100) (phaseProgress / 100.0) * stageSize else 0.0

    val globalProgress = math.max(0.0, math.min(1.0, stageIndex * stageSize + withinStage))

    val trackLen = 20
    val wormPos = math.max(0, math.min(trackLen, math.round(globalProgress * trackLen).toInt))
    val filled = "=" * math.max(0, wormPos)
    val remaining = "." * (trackLen - wormPos)
    val worm = "\ud83e\udeb1"
    val brain = "\ud83e\udde0"
    s"$worm[$filled$remaining]$brain"
  }

  private def formatCount(n: Long): String =
    if (n >= 1000000) f"${n / 1000000.0}%.1fM"
    else if (n >= 1000) f"${n / 1000.0}%.1fK"
    else n.toString

  override def toString: String =
    s"SNAP Sync Progress: phase=$phase, accounts=$accountsSynced (${accountsPerSec.toInt}/s), " +
      s"bytecodes=$bytecodesDownloaded (${bytecodesPerSec.toInt}/s), " +
      s"slots=$storageSlotsSynced (${slotsPerSec.toInt}/s), nodes=$nodesHealed (${nodesPerSec.toInt}/s), " +
      s"elapsed=${elapsedSeconds.toInt}s"

  /** Format chain download status as a compact suffix. Shows header/body/receipt progress when active. */
  private def chainStr: String =
    if (chainTarget > 0) {
      val pct = if (chainTarget > 0) (chainHeaders * 100 / chainTarget).toInt else 0
      s" | chain: h=${formatBigInt(chainHeaders)}/${formatBigInt(chainTarget)}($pct%) b=${formatBigInt(chainBodies)} r=${formatBigInt(chainReceipts)}"
    } else ""

  private def formatBigInt(n: BigInt): String =
    if (n >= 1000000) f"${(n.toDouble / 1000000)}%.1fM"
    else if (n >= 1000) f"${(n.toDouble / 1000)}%.1fK"
    else n.toString

  private def elapsedStr: String = {
    val s = elapsedSeconds.toInt
    if (s >= 3600) f"${s / 3600}h${(s % 3600) / 60}%02dm"
    else if (s >= 60) s"${s / 60}m${s % 60}s"
    else s"${s}s"
  }

  def formattedString: String = {
    val bar = wormChasesBrainBar
    val chain = chainStr
    val elapsed = elapsedStr

    phase match {
      case SNAPSyncController.AccountRangeSync if isFinalizingTrie =>
        s"$bar FINALIZING TRIE: flushing ${formatCount(accountsSynced)} accounts to disk (${finalizeElapsedSeconds}s)$chain | $elapsed"

      case SNAPSyncController.AccountRangeSync =>
        val progressStr = if (estimatedTotalAccounts > 0) s" ${phaseProgress}%" else ""
        val totalStr = if (estimatedTotalAccounts > 0) s"/~${formatCount(estimatedTotalAccounts)}" else ""
        s"$bar Accounts$progressStr: ${formatCount(accountsSynced)}$totalStr @ ${accountsPerSec.toInt}/s$chain | $elapsed"

      case SNAPSyncController.ByteCodeSync =>
        val progressStr = if (estimatedTotalBytecodes > 0) s" ${phaseProgress}%" else ""
        val totalStr = if (estimatedTotalBytecodes > 0) s"/${formatCount(estimatedTotalBytecodes)}" else ""
        s"$bar ByteCode$progressStr: ${formatCount(bytecodesDownloaded)}$totalStr @ ${bytecodesPerSec.toInt}/s$chain | $elapsed"

      case SNAPSyncController.ByteCodeAndStorageSync =>
        val contractsStr =
          if (storageContractsTotal > 0) s" (${storageContractsCompleted}/${storageContractsTotal} contracts)" else ""
        val codeStr =
          if (bytecodeComplete) s"codes=${formatCount(bytecodesDownloaded)} \u2714"
          else s"codes=${formatCount(bytecodesDownloaded)} @ ${bytecodesPerSec.toInt}/s"
        s"$bar Code+Storage: $codeStr, slots=${formatCount(storageSlotsSynced)} @ ${slotsPerSec.toInt}/s$contractsStr$chain | $elapsed"

      case SNAPSyncController.StorageRangeSync =>
        val progressStr = if (estimatedTotalSlots > 0) s" ${phaseProgress}%" else ""
        val contractsStr =
          if (storageContractsTotal > 0) s" (${storageContractsCompleted}/${storageContractsTotal} contracts)" else ""
        s"$bar Storage$progressStr: ${formatCount(storageSlotsSynced)} @ ${slotsPerSec.toInt}/s$contractsStr$chain | $elapsed"

      case SNAPSyncController.StateHealing =>
        s"$bar Healing: ${formatCount(nodesHealed)} nodes @ ${nodesPerSec.toInt}/s$chain | $elapsed"

      case SNAPSyncController.StateValidation =>
        s"$bar Validating state trie...$chain | $elapsed"

      case SNAPSyncController.ChainDownloadCompletion =>
        val bodiesPct = if (chainTarget > 0) (chainBodies * 100 / chainTarget).toInt else 0
        val receiptsPct = if (chainTarget > 0) (chainReceipts * 100 / chainTarget).toInt else 0
        s"$bar State done, chain download (boosted): bodies=${formatBigInt(chainBodies)}/$bodiesPct% receipts=${formatBigInt(chainReceipts)}/$receiptsPct% | $elapsed"

      case _ =>
        s"$bar $phase$chain | $elapsed"
    }
  }
}
