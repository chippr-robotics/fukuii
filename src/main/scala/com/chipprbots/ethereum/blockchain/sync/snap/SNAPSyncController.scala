package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.actor.{Actor, ActorLogging, ActorRef, Props, Scheduler, Cancellable}
import org.apache.pekko.util.ByteString

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
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

  // Tracks when the current in-place pivot bootstrap was initiated, for stall detection
  private var pendingPivotRefreshStartMs: Long = 0L

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
  private var trieWalkStartedAtMs: Long = 0L
  private var healingNodesAbandoned: Int = 0 // set from StateHealingComplete; drives pre-walk skip check
  private var healingNodesTotal: Int = 0     // total nodes healed (0 = healing was a no-op)
  // BUG-WS3: WALK-SKIP must not fire until a walk has completed in this healing session.
  // Both healing entry points reset this to false; TrieWalkResult sets it to true.
  private var healingWalkRunThisSession: Boolean = false

  // Proof-seeding buffer: interior trie node hashes discovered from boundary proofs during
  // account download. Flushed to the healing coordinator at healing start so it begins with
  // a pre-populated queue instead of an empty one (aligns with core-geth's Sync scheduler).
  private val proofDiscoveredHealNodes = mutable.Set.empty[ByteString]
  private val ProofSeedCap = 500_000 // 32 bytes/hash → ~16MB; log WARN if exceeded
  @volatile private var trieWalkNodesScanned: java.util.concurrent.atomic.AtomicLong =
    new java.util.concurrent.atomic.AtomicLong(0)
  private var consecutiveUnproductiveHealingRounds: Int = 0
  private val maxUnproductiveHealingRounds: Int = 5 // 5 rounds × 2 min = 10-min window before skipping to validation
  private var consecutiveHealingPivotRefreshAttempts: Int = 0
  private val maxHealingPivotRefreshAttempts: Int = 10 // after 10 failed pivot refreshes during healing, restart SNAP sync
  private var healingSaveCounter: Int = 0
  private var lastTrieWalkMissingCount: Option[Int] = None // progress tracker — None on first round, Some(n) thereafter
  // OPT-W1: Walk resume state — partial nodes and completed subtrees from a previous interrupted walk
  private var walkResumedNodes: Seq[(Seq[ByteString], ByteString)] = Seq.empty
  private val walkCurrentNodes = mutable.ArrayBuffer[(Seq[ByteString], ByteString)]()
  private var walkCompletedPrefixes: Set[String] = Set.empty
  private var healingValidationCycles: Int = 0           // counts healing→validation→healing oscillations
  private val maxHealingValidationCycles: Int = 5        // after 5 full cycles, stop oscillating and declare done
  private var bootstrapCheckTask: Option[Cancellable] = None
  private var pivotBootstrapRetryTask: Option[Cancellable] = None
  // OPT-P8: Exponential backoff for RetryPivotRefresh — avoids hammering same peers repeatedly.
  // Resets to 30s on successful pivot refresh. Doubles on each failure, capped at 5 minutes.
  private var pivotRefreshRetryDelay: FiniteDuration = 30.seconds
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
    log.info("=" * 70)
    log.info(s"=== Fukuii SNAP Sync — ${java.time.Instant.now()} ===")
    log.info("=" * 70)
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
        val graceSeconds = snapSyncConfig.snapCapabilityGracePeriod.toSeconds
        log.warning(
          s"No SNAP-capable peers found after ${graceSeconds}s bootstrap grace period. " +
            s"Falling back to fast sync (full header + state download)."
        )
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
      // SnapSyncFinalizedRoot is intentionally not persisted — recovery uses the pivot block's stateRoot.
      log.info("Account trie finalized (root {})", finalizedRoot.take(8).toArray.map("%02x".format(_)).mkString)

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
        val elapsed = (now - lastPivotRestartMs) / 1000
        log.debug(
          s"Rate-limiting pivot refresh: PivotStateUnservable received within ${elapsed}s of last refresh " +
            s"(phase=$currentPhase). Minimum interval: ${MinPivotRestartInterval.toSeconds}s."
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
        log.debug(
          s"Dropping PivotStateUnservable during $currentPhase — signal is irrelevant for this phase. Sync continuing normally."
        )
      }

    // Handle pivot header bootstrap completion during active sync.
    // This arrives when refreshPivotInPlace() requested a header from a peer because
    // it wasn't available locally. The coordinator is still alive with all its state.
    case BootstrapComplete(pivotHeaderOpt) if pendingPivotRefresh.isDefined =>
      val (pendingPivot, reason) = pendingPivotRefresh.get
      val bootstrapMs = System.currentTimeMillis() - pendingPivotRefreshStartMs
      pendingPivotRefresh = None
      pivotHeaderOpt match {
        case Some(header) =>
          log.info(s"[PIVOT] Bootstrap complete for block ${header.number} (requested $pendingPivot, took ${bootstrapMs / 1000}s)")
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
        scheduler.scheduleOnce(pivotRefreshRetryDelay, self, RetryPivotRefresh)(context.dispatcher)
      )
      log.warning(s"Scheduling pivot refresh retry in ${pivotRefreshRetryDelay.toSeconds}s (backoff)")
      pivotRefreshRetryDelay = (pivotRefreshRetryDelay * 2).min(5.minutes)

    case RetryPivotRefresh =>
      pivotBootstrapRetryTask = None
      if (
        currentPhase == AccountRangeSync || currentPhase == StorageRangeSync ||
        currentPhase == ByteCodeAndStorageSync || currentPhase == StateHealing
      ) {
        log.info(s"Retrying pivot refresh after bootstrap failure (phase=$currentPhase)...")
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

        // Persist all temp file paths for crash recovery.
        // Non-blocking asks — if any fail, recovery does a full restart (acceptable, account trie survives).
        accountRangeCoordinator.foreach { coordinator =>
          import org.apache.pekko.pattern.ask
          import org.apache.pekko.util.Timeout
          implicit val timeout: Timeout = Timeout(5.seconds)

          (coordinator ? actors.Messages.GetStorageFileInfo)
            .mapTo[actors.Messages.StorageFileInfoResponse]
            .foreach { info =>
              if (info.filePath != null) {
                appStateStorage.putSnapSyncStorageFilePath(info.filePath.toString).commit()
                log.info(s"[SNAP-PERSIST] contract-storage:  ${info.filePath} (${info.count} entries)")
              }
            }

          (coordinator ? actors.Messages.GetCodeHashesFileInfo)
            .mapTo[actors.Messages.CodeHashesFileInfoResponse]
            .foreach { info =>
              if (info.filePath != null) {
                appStateStorage.putSnapSyncCodeHashesPath(info.filePath.toString).commit()
                log.info(s"[SNAP-PERSIST] unique-codehashes: ${info.filePath} (${info.count} entries)")
              }
            }

          // Also persist the contract-accounts file path (needed for mid-download bytecode re-feed on restart)
          // We know the fixed path from tempDir — no ask needed since the file always exists after account download
          val tempDir = java.nio.file.Paths.get(Config.config.getString("tmpdir"))
          val contractAccountsPath = tempDir.resolve("snap-contract-accounts.bin")
          appStateStorage.putSnapSyncContractAccountsPath(contractAccountsPath.toString).commit()
          log.info(s"[SNAP-PERSIST] contract-accounts: $contractAccountsPath")
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
                log.info(s"[SNAP-PERSIST] completed-storage: ${info.filePath}")
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
      // L-032: persist the completion root so future restarts can skip storage re-download
      stateRoot.foreach { root =>
        appStateStorage.putSnapSyncStorageCompletionRoot(root).commit()
        log.info(s"L-032: persisted storage completion root for fast restart")
      }
      checkAllDownloadsComplete()

    case HealingAllPeersStateless if currentPhase == StateHealing =>
      consecutiveHealingPivotRefreshAttempts += 1
      if (consecutiveHealingPivotRefreshAttempts > maxHealingPivotRefreshAttempts) {
        log.warning(
          s"[HEAL-STALL] Healing pivot refresh failed $consecutiveHealingPivotRefreshAttempts times — " +
          s"restarting SNAP sync to recover from unservable state root"
        )
        restartSnapSync("healing pivot refresh limit exceeded")
      } else {
        log.warning(
          s"All healing peers stateless (attempt $consecutiveHealingPivotRefreshAttempts/$maxHealingPivotRefreshAttempts) — " +
          s"refreshing pivot in-place for healing"
        )
        refreshPivotInPlace("all healing peers stateless")
      }

    case PersistHealingQueue(pending, force) =>
      healingSaveCounter += 1
      if (force || healingSaveCounter % 10 == 0) {
        log.debug(s"[HEAL-PERSIST] Saving ${pending.size} pending nodes (force=$force, flush #$healingSaveCounter)")
        appStateStorage
          .putSnapSyncHealingPendingNodes(serializeHealingNodes(pending))
          .commit()
      }

    case StateHealingComplete(abandonedNodes, totalHealed) =>
      healingNodesAbandoned = abandonedNodes
      healingNodesTotal = totalHealed
      log.info(s"State healing complete [abandonedNodes=$abandonedNodes, healed=$totalHealed]")
      // Clear the coordinator so startStateHealingWithSavedNodes / startStateHealing can create a fresh one.
      // The coordinator sends this message and then stops itself; clearing the reference here prevents
      // startStateHealingWithSavedNodes from seeing isDefined=true and silently ignoring the restart.
      trieNodeHealingCoordinator.foreach(context.stop); trieNodeHealingCoordinator = None
      // Flush healed nodes from LRU to RocksDB before the next walk.
      // TrieNodeHealingCoordinator.persist() is a NO-OP on CachedReferenceCountedStorage —
      // nodes are in LRU only. Without this flush, rootInDb=false in startTrieWalk() and
      // Fix D forces a redundant 6h trie walk even though healing just completed.
      log.info("Flushing healed trie nodes from LRU to RocksDB...")
      stateStorage.forcePersist(StateStorage.GenesisDataLoad)
      log.info("LRU flush complete.")
      if (!trieWalkInProgress) {
        if (abandonedNodes > 0) {
          // Stagnation saved the abandoned list via PersistHealingQueue(force=true) — load and
          // retry healing directly, skipping the 27h+ trie re-walk. If peers are still stateless
          // for the current root, HealingAllPeersStateless → refreshPivotInPlace fires, then a
          // new walk runs for the new root. Walk is only needed after a root change, not reflexively.
          appStateStorage.getSnapSyncHealingPendingNodes() match {
            case Some(data) =>
              val savedNodes = deserializeHealingNodes(data)
              if (savedNodes.nonEmpty) {
                log.info(
                  s"[WALK-SKIP] $abandonedNodes abandoned nodes — resuming healing from saved list " +
                  s"(${savedNodes.size} nodes, skipping trie re-walk)"
                )
                startStateHealingWithSavedNodes(savedNodes)
              } else {
                log.info(s"[WALK] $abandonedNodes abandoned nodes but saved list empty — running trie walk")
                startTrieWalk()
              }
            case None =>
              log.info(s"[WALK] $abandonedNodes abandoned nodes but no saved list — running trie walk")
              startTrieWalk()
          }
        } else {
          startTrieWalk()
        }
      }

    case ProofDiscoveredNodes(nodes) =>
      if (nodes.nonEmpty) {
        val sizeBefore = proofDiscoveredHealNodes.size
        if (sizeBefore < ProofSeedCap) {
          proofDiscoveredHealNodes ++= nodes
          if (proofDiscoveredHealNodes.size >= ProofSeedCap)
            log.warning(
              s"[PROOF-SEED] Proof-discovered node buffer reached cap ($ProofSeedCap entries) — " +
                s"further proof nodes discarded. This is unexpected; inspect proof sizes."
            )
        }
      }

    case TrieWalkHeartbeat =>
      if (trieWalkInProgress) {
        val elapsedSec = ((System.currentTimeMillis() - trieWalkStartedAtMs) / 1000).toInt
        val scanned = trieWalkNodesScanned.get()
        val nodesPerSec = if (elapsedSec > 0) scanned / elapsedSec else 0L
        val etaStr = if (nodesPerSec > 0) {
          val remaining = (145_000_000L - scanned).max(0L)
          val etaSec = remaining / nodesPerSec
          s", ETA ~${etaSec / 3600}h ${(etaSec % 3600) / 60}m"
        } else ""
        log.info(
          f"[WALK] Trie walk in progress: $scanned%,d nodes scanned, " +
            f"$nodesPerSec%,d nodes/s, " +
            s"${elapsedSec / 60}m ${elapsedSec % 60}s elapsed$etaStr"
        )
        scheduler.scheduleOnce(10.seconds, self, TrieWalkHeartbeat)(ec)
      }

    case TrieWalkResult(missingNodes) if currentPhase == StateHealing =>
      trieWalkInProgress = false
      healingWalkRunThisSession = true // BUG-WS3: walk ran — WALK-SKIP is now safe
      // OPT-W1: merge partial nodes from previous interrupted walk into final result
      val allMissingNodes = walkResumedNodes ++ missingNodes
      if (walkResumedNodes.nonEmpty)
        log.info(
          s"[WALK-RESUME] Merged ${walkResumedNodes.size} partial nodes from previous run + " +
          s"${missingNodes.size} from current run = ${allMissingNodes.size} total missing nodes"
        )
      walkResumedNodes = Seq.empty
      walkCurrentNodes.clear()
      walkCompletedPrefixes = Set.empty
      if (allMissingNodes.isEmpty) {
        log.info("Trie walk found no missing nodes — healing complete!")
        MilestoneLog.phase("Trie healing complete | 0 missing nodes")
        consecutiveUnproductiveHealingRounds = 0
        lastTrieWalkMissingCount = None
        // Clear persisted healing state — successfully healed
        appStateStorage.putSnapSyncHealingPendingNodes("")
          .and(appStateStorage.putSnapSyncHealingRound(0))
          .and(appStateStorage.putSnapSyncWalkCompletedPrefixes(Set.empty))
          .commit()
        progressMonitor.startPhase(StateValidation)
        currentPhase = StateValidation
        // The async trie walk already traversed the full state trie and found 0 missing nodes.
        // validateState() would re-traverse the same trie synchronously (~6h on ETC mainnet),
        // blocking the actor with no progress output. It is fully redundant here.
        // completeSnapSync() performs its own forcePersist + rootConfirmed check as a safety net.
        log.info("State trie proven complete by walk (0 missing nodes) — proceeding to finalization.")
        self ! StateValidationComplete
      } else {
        // Reset counter if progress was made (fewer missing nodes than previous walk).
        // On the first round (None), neither increment nor reset — no baseline to compare against.
        lastTrieWalkMissingCount match {
          case Some(prev) if allMissingNodes.size < prev =>
            val healed = prev - allMissingNodes.size
            log.info(
              s"Healing made progress: $healed nodes healed, ${allMissingNodes.size} remaining — resetting stagnation counter"
            )
            consecutiveUnproductiveHealingRounds = 0
          case Some(_) =>
            consecutiveUnproductiveHealingRounds += 1
          case None =>
            // First healing round — no prior baseline, don't penalise stagnation counter
        }
        lastTrieWalkMissingCount = Some(allMissingNodes.size)
        // Persist missing nodes so restart can skip re-walk; clear walk checkpoint (walk done)
        appStateStorage
          .putSnapSyncHealingPendingNodes(SNAPSyncController.serializeHealingNodes(allMissingNodes))
          .and(appStateStorage.putSnapSyncHealingRound(consecutiveUnproductiveHealingRounds))
          .and(appStateStorage.putSnapSyncWalkCompletedPrefixes(Set.empty))
          .commit()
        if (consecutiveUnproductiveHealingRounds >= maxUnproductiveHealingRounds) {
          log.warning(
            s"Healing stagnation: ${allMissingNodes.size} missing nodes persist after " +
              s"$consecutiveUnproductiveHealingRounds consecutive rounds with no progress. " +
              s"Proceeding to validation — regular sync will recover missing nodes on-demand."
          )
          MilestoneLog.error(
            s"Healing stagnation | ${allMissingNodes.size} missing nodes after $consecutiveUnproductiveHealingRounds rounds, proceeding to validation"
          )
          consecutiveUnproductiveHealingRounds = 0
          lastTrieWalkMissingCount = None
          // Clear persisted healing state — giving up on these nodes, validation will continue
          appStateStorage.putSnapSyncHealingPendingNodes("").and(appStateStorage.putSnapSyncHealingRound(0)).commit()
          progressMonitor.startPhase(StateValidation)
          currentPhase = StateValidation
          validateState()
        } else {
          log.info(
            s"Trie walk found ${allMissingNodes.size} missing nodes — queuing for healing " +
              s"(round $consecutiveUnproductiveHealingRounds/$maxUnproductiveHealingRounds)"
          )
          trieNodeHealingCoordinator.foreach { coordinator =>
            coordinator ! actors.Messages.QueueMissingNodes(allMissingNodes)
          }
          // Healing coordinator self-feeds via discoverMissingChildren (BUG-HW1).
          // No periodic walk needed — StateHealingComplete triggers the final validation walk.
        }
      }

    case ScheduledTrieWalk if currentPhase == StateHealing =>
      // Guard: a previous quick healing round may have already started a walk before the
      // scheduled message arrived. Don't launch a second parallel walk.
      if (!trieWalkInProgress) startTrieWalk()
      else log.debug("[HEALING] ScheduledTrieWalk skipped — trie walk already in progress")

    case TrieWalkSubtreeResult(prefixHex, nodes) if trieWalkInProgress =>
      walkCurrentNodes ++= nodes
      walkCompletedPrefixes += prefixHex
      val totalSoFar = walkResumedNodes.size + walkCurrentNodes.size
      log.debug(
        s"[WALK-CHECKPOINT] Subtree $prefixHex done: ${nodes.size} nodes, " +
        s"${walkCompletedPrefixes.size}/256 subtrees complete, $totalSoFar total missing nodes saved"
      )
      // Persist crash-recovery state after each subtree: all nodes so far + completed prefix set
      appStateStorage
        .putSnapSyncHealingPendingNodes(
          serializeHealingNodes(walkResumedNodes ++ walkCurrentNodes.toSeq))
        .and(appStateStorage.putSnapSyncWalkCompletedPrefixes(walkCompletedPrefixes))
        .commit()

    case TrieWalkFailed(error) if currentPhase == StateHealing =>
      trieWalkInProgress = false
      val checkpointedSoFar = walkCompletedPrefixes.size
      // Clear in-memory walk state — startTrieWalk() will reload from storage on retry
      walkResumedNodes = Seq.empty
      walkCurrentNodes.clear()
      walkCompletedPrefixes = Set.empty
      // Leave SnapSyncWalkCompletedPrefixes in storage intact — resume checkpoint preserved
      log.error(
        s"Trie walk failed: $error. " +
        s"$checkpointedSoFar/256 subtrees were checkpointed — will resume from checkpoint on retry. " +
        s"Retrying after delay..."
      )
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
    if (pendingPivotRefresh.isDefined) {
      // Bootstrap request is in-flight. Warn if it's been pending too long — peer unresponsiveness
      // can block the pending flag for hours, silently freezing the pivot (root cause of accounts wipe).
      val pendingMs = System.currentTimeMillis() - pendingPivotRefreshStartMs
      if (pendingMs > 60_000) {
        log.warning(
          s"[PIVOT] In-place bootstrap pending for ${pendingMs / 1000}s — peer may be unresponsive. " +
            s"Pivot frozen at ${pivotBlock.getOrElse("unknown")}. " +
            s"Proactive refresh blocked until BootstrapComplete/PivotBootstrapFailed arrives."
        )
      }
      return
    }

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
        log.info("=" * 60)
        log.info("PHASE: All downloads complete → Trie Healing")
        log.info("=" * 60)
        MilestoneLog.phase("Trie healing starting")
        currentPhase = StateHealing
        // Check if healing was in progress when the node last stopped — skip re-walk if so
        appStateStorage.getSnapSyncHealingPendingNodes() match {
          case Some(data) if data.nonEmpty =>
            val savedNodes = SNAPSyncController.deserializeHealingNodes(data)
            if (savedNodes.isEmpty) {
              log.warning("[HEAL-RESUME] Persisted healing data was malformed — starting fresh trie walk")
            }
            if (savedNodes.nonEmpty) {
              val savedRound = appStateStorage.getSnapSyncHealingRound()
              log.info(
                s"[HEAL-RESUME] Restoring ${savedNodes.size} missing nodes from prior healing " +
                  s"round $savedRound — skipping 5h trie re-walk"
              )
              consecutiveUnproductiveHealingRounds = savedRound
              lastTrieWalkMissingCount = Some(savedNodes.size)
              startStateHealingWithSavedNodes(savedNodes)
            } else {
              startStateHealing()
            }
          case _ =>
            startStateHealing()
        }
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
            log.info(s"Beginning SNAP account download with ${snapSyncConfig.accountConcurrency} concurrent workers")
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
                    log.info(s"Beginning SNAP account download with ${snapSyncConfig.accountConcurrency} concurrent workers")
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

    // Log the current DB state so operators can see what will be restored
    {
      val accountsComplete = appStateStorage.isSnapSyncAccountsComplete()
      val savedPivot       = appStateStorage.getSnapSyncPivotBlock()
      val savedStoragePath = appStateStorage.getSnapSyncStorageFilePath()
      val savedCodeHashes  = appStateStorage.getSnapSyncCodeHashesPath()
      val savedContractAcc = appStateStorage.getSnapSyncContractAccountsPath()
      val savedCompleted   = appStateStorage.getSnapSyncCompletedStoragePath()
      val healingPending   = appStateStorage.getSnapSyncHealingPendingNodes().map(d => s"${deserializeHealingNodes(d).size} nodes")
      val healingRound     = appStateStorage.getSnapSyncHealingRound()
      val snapDone         = appStateStorage.isSnapSyncDone()
      log.info("[SNAP-STATE] Persisted SNAP sync state at startup:")
      log.info(s"[SNAP-STATE]   snap_done=$snapDone  accounts_complete=$accountsComplete  pivot=${savedPivot.getOrElse("none")}")
      log.info(s"[SNAP-STATE]   contract-storage:  ${savedStoragePath.getOrElse("none")}")
      log.info(s"[SNAP-STATE]   unique-codehashes: ${savedCodeHashes.getOrElse("none")}")
      log.info(s"[SNAP-STATE]   contract-accounts: ${savedContractAcc.getOrElse("none")}")
      log.info(s"[SNAP-STATE]   completed-storage: ${savedCompleted.getOrElse("none")}")
      log.info(s"[SNAP-STATE]   healing: ${healingPending.getOrElse("none")} (round $healingRound)")
    }

    // Step 7: Check for accounts-complete recovery (process crash during bytecode/storage phase).
    // If accounts were previously completed, skip account download and re-run bytecodes + storage.
    // Note: pivot staleness is intentionally NOT checked here. Account data is content-addressed
    // (Keccak256 hash → value) and remains valid regardless of how many blocks have passed.
    // A stale pivot is updated by proactive in-place refresh within 30s of startup.
    // Trie walk + healing discovers and repairs any state differences from the new pivot root.
    // (Prior bug: staleness restart during StateHealing + this check = double-clear of accounts_complete)
    if (appStateStorage.isSnapSyncAccountsComplete()) {
      val savedPivot = appStateStorage.getSnapSyncPivotBlock()
      val savedRootOpt = appStateStorage.getSnapSyncStateRoot()
      val savedStoragePath = appStateStorage.getSnapSyncStorageFilePath()

      (savedPivot, savedRootOpt) match {
        case (Some(pivot), Some(rootBs)) if pivot > 0 =>
          val networkBest = currentNetworkBestFromSnapPeers().getOrElse(BigInt(0))
          val drift = if (networkBest > 0) (networkBest - pivot).abs else BigInt(0)
          val driftNote = if (drift > 0) s", drift=$drift blocks from networkBest=$networkBest" else ""
          log.info(s"[SNAP-RECOVERY] accounts previously completed at pivot $pivot — resuming (skipping account phase$driftNote)")
          if (drift > snapSyncConfig.maxPivotStalenessBlocks)
            log.warning(
              s"[SNAP-RECOVERY] Pivot is $drift blocks stale (> ${snapSyncConfig.maxPivotStalenessBlocks} threshold). " +
                s"Proceeding anyway — account data is content-addressed. Proactive refresh will update pivot within 30s."
            )

          {
            // Always recover bytecodes + storage — pivot age does not invalidate account data
            pivotBlock = Some(pivot)
            stateRoot = Some(rootBs)
            accountsComplete = true
            bytecodePhaseComplete = false
            // L-032: if storage completed at this exact pivot root in a prior run, skip re-download
            val storageSkip = appStateStorage.getSnapSyncStorageCompletionRoot().exists(_ == rootBs)
            storagePhaseComplete = storageSkip

            if (storageSkip) {
              log.info(
                s"L-032: storage already completed at root ${Hex.toHexString(rootBs.toArray)} — " +
                  s"skipping StorageRangeCoordinator, resuming bytecodes only"
              )
            } else {
              log.info(s"[SNAP-RECOVERY] resuming bytecodes + storage sync from pivot $pivot")
            }

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

            if (!storageSkip) {
            val recoveryTempDir = java.nio.file.Paths.get(Config.config.getString("tmpdir"))
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
                    deferredMerkleization = snapSyncConfig.deferredMerkleization,
                    tempDir = recoveryTempDir
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
                  log.info(s"[SNAP-RECOVERY] completed-storage: found $filePath, ${completedHashes.size} entries — seeding completed set")
                }
              } else {
                log.warning(s"[SNAP-RECOVERY] completed-storage: path $filePath not found on disk — no prior completions to restore")
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
                    log.info(s"[SNAP-RECOVERY] contract-storage: found $filePath, $count entries — streaming to storage coordinator")
                    // Signal no more tasks — sentinel allows completion
                    coordinator ! actors.Messages.NoMoreStorageTasks
                  }
              } else {
                log.warning(s"[SNAP-RECOVERY] contract-storage: path $filePath not found on disk — will re-download from accounts")
                storageRangeCoordinator.foreach(_ ! actors.Messages.NoMoreStorageTasks)
              }
            }
            if (savedStoragePath.isEmpty) {
              log.warning("[SNAP-RECOVERY] contract-storage: no path persisted — sending NoMore immediately")
              storageRangeCoordinator.foreach(_ ! actors.Messages.NoMoreStorageTasks)
            }
            } // end if (!storageSkip)

            // Bytecodes: re-feed unique codeHashes from the persisted uniqueCodeHashesFile.
            // filterAndDedupeCodeHashes will skip any already fetched via EvmCodeStorage (cross-restart dedup).
            val savedCodeHashesPath = appStateStorage.getSnapSyncCodeHashesPath()
            savedCodeHashesPath match {
              case Some(pathStr) =>
                val filePath = java.nio.file.Paths.get(pathStr)
                if (java.nio.file.Files.exists(filePath)) {
                  val coordinator = bytecodeCoordinator.get
                  import context.dispatcher
                  scala.concurrent.Future {
                    val raf = new java.io.RandomAccessFile(filePath.toFile, "r")
                    val buf = new Array[Byte](32)
                    val batch = new scala.collection.mutable.ArrayBuffer[ByteString](10000)
                    var totalHashes = 0
                    try {
                      while (raf.getFilePointer < raf.length()) {
                        raf.readFully(buf)
                        batch += ByteString(buf.clone())
                        if (batch.size >= 10000) {
                          coordinator ! actors.Messages.AddByteCodeTasks(batch.toSeq)
                          totalHashes += batch.size
                          batch.clear()
                        }
                      }
                      if (batch.nonEmpty) {
                        coordinator ! actors.Messages.AddByteCodeTasks(batch.toSeq)
                        totalHashes += batch.size
                      }
                    } finally raf.close()
                    totalHashes
                  }.foreach { count =>
                    log.info(s"[SNAP-RECOVERY] unique-codehashes: found $filePath, $count entries — re-feeding bytecode coordinator")
                    coordinator ! actors.Messages.NoMoreByteCodeTasks
                  }
                } else {
                  log.warning(s"[SNAP-RECOVERY] unique-codehashes: path $filePath not found on disk — will rely on healing for missing bytecodes")
                  bytecodeCoordinator.foreach(_ ! actors.Messages.NoMoreByteCodeTasks)
                }
              case None =>
                log.warning("[SNAP-RECOVERY] unique-codehashes: no path persisted — sending NoMore immediately")
                bytecodeCoordinator.foreach(_ ! actors.Messages.NoMoreByteCodeTasks)
            }

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
          log.warning("[SNAP-RECOVERY] accounts-complete flag set but missing pivot/root. Clearing and restarting fresh.")
          deleteSnapSyncTempFiles()
          appStateStorage.putSnapSyncAccountsComplete(false).commit()
          appStateStorage.clearSnapSyncStorageCompletionRoot().commit()
          appStateStorage.putSnapSyncCodeHashesPath("").commit()
          appStateStorage.putSnapSyncContractAccountsPath("").commit()
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
          // BUG-RS1: Pivot is at or behind local state after a restart (restartSnapSync clears
          // in-memory pivotBlock/stateRoot but NOT AppStateStorage). Must call finalizeSnapSync
          // to commit storeBlock + putBestBlockInfo before signalling Done, otherwise regular
          // sync cannot find the best block and crashes immediately.
          log.warning("BUG-RS1: Pivot block is not ahead of local state — finalizing SNAP sync at localBestBlock")
          pivotBlock = appStateStorage.getSnapSyncPivotBlock()
          stateRoot = appStateStorage.getSnapSyncStateRoot()
          if (pivotBlock.isDefined && stateRoot.isDefined) {
            log.warning(
              s"BUG-RS1: Restored pivot=${pivotBlock.get}, stateRoot=${stateRoot.get.toArray.take(4).map("%02x".format(_)).mkString} — calling finalizeSnapSync(${pivotBlock.get})"
            )
            finalizeSnapSync(pivotBlock.get)
          } else {
            log.warning(
              s"BUG-RS1: No persisted pivot/stateRoot (pivot=$pivotBlock, root=$stateRoot) — sending Done directly"
            )
            context.parent ! Done
          }
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

    // Delete temp files and clear all persisted file paths
    deleteSnapSyncTempFiles()

    // Clear persisted SNAP progress — retry will start fresh
    appStateStorage.putSnapSyncProgress("").commit()
    appStateStorage.putSnapSyncAccountsComplete(false).commit()
    appStateStorage.clearSnapSyncStorageCompletionRoot().commit()
    appStateStorage.putSnapSyncStorageFilePath("").commit()
    appStateStorage.putSnapSyncCompletedStoragePath("").commit()
    appStateStorage.putSnapSyncCodeHashesPath("").commit()
    appStateStorage.putSnapSyncContractAccountsPath("").commit()
    appStateStorage.putSnapSyncHealingPendingNodes("").commit()
    appStateStorage.putSnapSyncHealingRound(0).commit()
    appStateStorage.putSnapSyncWalkCompletedPrefixes(Set.empty).commit()
    appStateStorage.putSnapSyncWalkRoot("").commit()
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
    // If no verified peers exist, schedule a grace period then retry SNAP with backoff.
    val snapPeerCount = peersToDownloadFrom.count { case (_, p) =>
      p.peerInfo.remoteStatus.supportsSnap && p.peerInfo.isServingSnap
    }

    if (snapPeerCount == 0) {
      val gracePeriod = snapSyncConfig.snapCapabilityGracePeriod
      log.warning(s"No peers with snap/1 capability found ($peersToDownloadFrom.size peers connected)")
      log.warning(s"No verified SNAP peers — scheduling capability check in ${gracePeriod.toSeconds}s (will retry SNAP with backoff if none confirmed)")
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
              minResponseBytes = snapSyncConfig.storageMinResponseBytes,
              tempDir = tempDir
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
        if currentPhase == AccountRangeSync || currentPhase == StorageRangeSync || currentPhase == ByteCodeAndStorageSync || currentPhase == StateHealing =>
      maybeProactivelyRefreshPivot()
      super.aroundReceive(receive, msg)

    case _ =>
      super.aroundReceive(receive, msg)
  }

  // Internal message for async trie walk result
  private case class TrieWalkResult(missingNodes: Seq[(Seq[ByteString], ByteString)])
  private case class TrieWalkFailed(error: String)
  private case class TrieWalkSubtreeResult(prefixHex: String, nodes: Seq[(Seq[ByteString], ByteString)])
  private case object ScheduledTrieWalk
  private case object TrieWalkHeartbeat

  /** Start an async trie walk to discover missing nodes. Guards against concurrent walks. */
  private def startTrieWalk(): Unit = {
    if (trieWalkInProgress) return
    if (currentPhase != StateHealing) return

    // Pre-walk check: if healing completed with 0 abandoned nodes AND the pivot root is
    // confirmed durable in RocksDB, skip the walk — state is verifiably complete.
    // If root is absent, 0 abandoned nodes just means every requested node was received,
    // but the root itself may not have been fetched yet — the walk must run to find it.
    val rootInDb = stateRoot.exists { r =>
      try { stateStorage.getReadOnlyStorage.get(r.toArray); true }
      catch { case _: Exception => false }
    }
    if (!snapSyncConfig.requireValidationWalk && healingNodesAbandoned == 0 && healingNodesTotal == 0 && rootInDb && healingWalkRunThisSession) {
      log.info("=" * 60)
      log.info("PHASE: Trie Healing → State Validation Walk")
      log.info("=" * 60)
      log.info(
        s"[WALK-SKIP] Healing was a no-op (0 nodes healed, 0 abandoned) and root confirmed in RocksDB. " +
        "Skipping validation walk. (snap-sync.require-validation-walk = false)"
      )
      log.info("[WALK-SKIP] Set snap-sync.require-validation-walk = true to force a full trie scan.")
      self ! TrieWalkResult(Seq.empty)
      return
    }
    if (!snapSyncConfig.requireValidationWalk && healingNodesAbandoned == 0 && healingNodesTotal == 0 && !rootInDb) {
      log.warning(
        "[WALK-SKIP overridden] Healing was a no-op but root {} is MISSING " +
          "from RocksDB — forcing trie walk to discover remaining gaps.",
        stateRoot.map(_.take(8).toHex).getOrElse("?")
      )
    }

    val walkReason =
      if (snapSyncConfig.requireValidationWalk) "operator forced via snap-sync.require-validation-walk = true"
      else s"healing abandoned $healingNodesAbandoned nodes — walk required to recover missing state"
    log.info(s"[WALK] Pre-walk check: running full validation walk ($walkReason)")

    trieWalkInProgress = true
    trieWalkStartedAtMs = System.currentTimeMillis()
    val counter = new java.util.concurrent.atomic.AtomicLong(0)
    trieWalkNodesScanned = counter

    // OPT-W1: Check for resumable partial walk (same root, completed subtrees saved)
    val currentRootHex = stateRoot.map(r => Hex.toHexString(r.toArray)).getOrElse("")
    val savedWalkRoot = appStateStorage.getSnapSyncWalkRoot()
    val savedPrefixes = appStateStorage.getSnapSyncWalkCompletedPrefixes()
    if (savedWalkRoot.contains(currentRootHex) && savedPrefixes.nonEmpty) {
      walkResumedNodes = appStateStorage.getSnapSyncHealingPendingNodes()
        .map(data => deserializeHealingNodes(data)).getOrElse(Seq.empty)
      walkCompletedPrefixes = savedPrefixes
      log.info(
        s"[WALK-RESUME] Resuming walk for root ${currentRootHex.take(16)}: " +
        s"${savedPrefixes.size}/256 subtrees already complete, " +
        s"${walkResumedNodes.size} partial nodes saved — skipping completed subtrees"
      )
    } else {
      if (savedWalkRoot.exists(_ != currentRootHex) && savedPrefixes.nonEmpty)
        log.info(
          s"[WALK-RESUME] Root changed (${savedWalkRoot.map(_.take(16)).getOrElse("none")} → " +
          s"${currentRootHex.take(16)}) — discarding stale walk checkpoint"
        )
      walkResumedNodes = Seq.empty
      walkCompletedPrefixes = Set.empty
      appStateStorage
        .putSnapSyncWalkCompletedPrefixes(Set.empty)
        .and(appStateStorage.putSnapSyncWalkRoot(currentRootHex))
        .commit()
    }
    walkCurrentNodes.clear()
    val completedPrefixesForWalk = walkCompletedPrefixes

    stateRoot.foreach { root =>
      log.info("=" * 60)
      log.info("PHASE: Trie Healing → State Validation Walk")
      log.info("=" * 60)
      log.info("[WALK] Starting trie walk (fillCache=false, verifyChecksums=false — cache-safe single-pass scan)")
      val walkThreads = math.max(1, math.min(4, snapSyncConfig.trieWalkParallelism))
      log.info(s"[WALK] Parallel subtree walkers: $walkThreads (configurable via snap-sync.trie-walk-parallelism)")
      val storage = getOrCreateMptStorage(pivotBlock.getOrElse(BigInt(0)))
      val selfRef = self
      // Dedicated thread pool for trie walk — isolated from sync-dispatcher to avoid I/O starvation.
      // Shutdown is called in onComplete once results are delivered to the actor.
      val walkEc = ExecutionContext.fromExecutorService(
        java.util.concurrent.Executors.newFixedThreadPool(walkThreads)
      )
      scheduler.scheduleOnce(10.seconds, self, TrieWalkHeartbeat)(ec)
      val validator = new StateValidator(storage, counter)
      validator.findMissingNodesWithPaths(
        root,
        completedPrefixesForWalk,
        (pfx, nodes) => selfRef ! TrieWalkSubtreeResult(pfx, nodes)
      )(walkEc).onComplete {
        case scala.util.Success(Right(missingNodes)) =>
          walkEc.shutdown()
          selfRef ! TrieWalkResult(missingNodes)
        case scala.util.Success(Left(error)) =>
          walkEc.shutdown()
          selfRef ! TrieWalkFailed(error)
        case scala.util.Failure(ex) =>
          walkEc.shutdown()
          selfRef ! TrieWalkFailed(Option(ex.getMessage).getOrElse(ex.getClass.getSimpleName))
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
    healingWalkRunThisSession = false // BUG-WS3: no walk has run yet this session
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

        // Proof-seeded nodes: we have hashes from boundary proof traversal but not trie paths.
        // QueueMissingNodes requires (pathset, hash) pairs — without paths, we can't make
        // valid GetTrieNodes requests. Log the count and discard; the trie walk discovers
        // these nodes with proper paths. (Path tracking in MerkleProofVerifier: see L-028.)
        if (proofDiscoveredHealNodes.nonEmpty) {
          log.info(s"[PROOF-SEED] ${proofDiscoveredHealNodes.size} proof-discovered interior node hashes collected " +
            s"(path tracking deferred — trie walk will discover with paths)")
          proofDiscoveredHealNodes.clear()
        }
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

  /** Resume healing from a persisted missing-nodes list, skipping the initial trie walk.
    * Creates the healing coordinator and dispatches the saved nodes immediately.
    */
  private def startStateHealingWithSavedNodes(
      missingNodes: Seq[(Seq[ByteString], ByteString)]
  ): Unit = {
    if (trieNodeHealingCoordinator.isDefined) {
      log.warning("startStateHealingWithSavedNodes called but healing coordinator already exists — ignoring")
      return
    }

    trieWalkInProgress = false
    healingWalkRunThisSession = false // BUG-WS3: no walk has run yet this session

    stateRoot match {
      case None =>
        log.error("[HEAL-RESUME] No stateRoot available — falling back to fresh trie walk")
        startStateHealing()

      case Some(root) =>
        log.info(
          s"[HEAL-RESUME] Creating healing coordinator for ${missingNodes.size} saved nodes " +
            s"(root=${root.toHex.take(8)})"
        )

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

        trieNodeHealingCoordinator.foreach { coordinator =>
          coordinator ! actors.Messages.StartTrieNodeHealing(root)
          coordinator ! actors.Messages.UpdateMaxInFlightPerPeer(5)
          coordinator ! actors.Messages.QueueMissingNodes(missingNodes)

          // Proof-seeded nodes: hashes without paths — cannot use QueueMissingNodes.
          // Log count and discard; trie walk discovers with proper paths. (See L-028.)
          if (proofDiscoveredHealNodes.nonEmpty) {
            log.info(s"[PROOF-SEED] ${proofDiscoveredHealNodes.size} proof-discovered hashes collected " +
              s"(path tracking deferred — trie walk will discover with paths)")
            proofDiscoveredHealNodes.clear()
          }
        }

        healingRequestTask = Some(
          scheduler.scheduleWithFixedDelay(
            0.seconds,
            1.second,
            self,
            RequestTrieNodeHealing
          )(ec)
        )

        progressMonitor.startPhase(StateHealing)
        // Healing coordinator self-feeds via discoverMissingChildren (BUG-HW1).
        // No periodic walk needed here — coordinator queues children as it heals nodes.
    }
  }

  // Internal message for periodic healing requests
  private case object RequestTrieNodeHealing

  private def requestTrieNodeHealing(): Unit = {
    // Pivot staleness during healing is handled by proactive in-place refresh (CheckPivotFreshness).
    // A full restart here clears healing state (trie walk queue, round counter) unnecessarily,
    // and can trigger the recovery double-clear bug when accounts_complete=true.

    // Warn if pivot is significantly stale so we can verify in-place refresh is keeping up.
    // This is informational only — in-place refresh corrects staleness, not a restart.
    for {
      pivot       <- pivotBlock
      networkBest <- currentNetworkBestFromSnapPeers()
      if networkBest > pivot
    } {
      val delta = networkBest - pivot
      if (delta > snapSyncConfig.maxPivotStalenessBlocks) {
        if (pendingPivotRefresh.isDefined) {
          val pendingMs = System.currentTimeMillis() - pendingPivotRefreshStartMs
          log.warning(
            s"[HEAL] Pivot $pivot is $delta blocks stale; in-place bootstrap pending ${pendingMs / 1000}s. " +
              s"Healing requests against stale root may fail until bootstrap completes."
          )
        } else {
          log.warning(
            s"[HEAL] Pivot $pivot is $delta blocks stale (> ${snapSyncConfig.maxPivotStalenessBlocks}). " +
              s"In-place refresh should trigger within 30s via CheckPivotFreshness."
          )
        }
      }
    }

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

              if (validationRetryCount == 1) {
                // First failure: flush LRU cache to RocksDB. The account download pipeline writes
                // nodes via DeferredWriteMptStorage.flush() → CachedReferenceCountedStorage.persist()
                // which is a NO-OP — nodes land in the LRU cache only, not in RocksDB.
                // forcePersist(GenesisDataLoad) calls persistCache() which writes all cache entries
                // to RocksDB and clears the cache. After this, mptStorage.get(root) reads from RocksDB
                // directly, so the queueNodes false-positive in healing is also resolved.
                log.info("Root node missing — flushing LRU cache to RocksDB before retrying validation")
                stateStorage.forcePersist(StateStorage.GenesisDataLoad)
                scheduler.scheduleOnce(ValidationRetryDelay) {
                  validateState()
                }(ec)
              } else if (validationRetryCount > MaxValidationRetries) {
                // After retries, root is genuinely missing from RocksDB: trigger a targeted network
                // fetch via trie healing rather than proceeding with a broken state root.
                log.warning(
                  s"Root node missing after $validationRetryCount validation attempts — " +
                    s"triggering targeted healing fetch for state root ${stateRoot.map(_.take(8).toHex).getOrElse("?")}."
                )
                stateRoot match {
                  case Some(root) =>
                    // Reset oscillation guard so healing can run again
                    healingValidationCycles = 0
                    triggerHealingForMissingNodes(Seq(root))
                  case None =>
                    log.error("Cannot force-fetch root: stateRoot is None. Triggering SNAP retry.")
                    context.parent ! RetrySnapSync("stateRoot unknown in root-missing healing fallback")
                }
              } else {
                log.error(s"Root node is missing (retry attempt $validationRetryCount of $MaxValidationRetries)")
                log.info("Retrying validation after brief delay...")
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
        // Include best block number in the batch so regular sync startup doesn't fail.
        pivotBlock match {
          case Some(pivot) =>
            log.warning("Proceeding to regular sync without state validation")
            appStateStorage.snapSyncDone()
              .and(appStateStorage.putBestBlockNumber(pivot))
              .commit()
            context.parent ! Done
          case None =>
            log.error("Cannot finalize: pivotBlock is None in missing-state-root fallback. Triggering SNAP retry.")
            context.parent ! RetrySnapSync("pivot block unknown in missing-state-root fallback")
        }
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
        s"Cannot refresh pivot: no suitable SNAP peers available. Scheduling retry in ${pivotRefreshRetryDelay.toSeconds}s (backoff)."
      )
      // Don't restart — restarting can't help with no peers, and it destroys all in-memory trie data.
      // Schedule an explicit retry so we don't stall indefinitely waiting for coordinator backoff.
      pivotBootstrapRetryTask.foreach(_.cancel())
      pivotBootstrapRetryTask = Some(
        scheduler.scheduleOnce(pivotRefreshRetryDelay, self, RetryPivotRefresh)(context.dispatcher)
      )
      pivotRefreshRetryDelay = (pivotRefreshRetryDelay * 2).min(5.minutes)
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
      pendingPivotRefreshStartMs = System.currentTimeMillis()
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
    // Use single-block difficulty as lower bound for ETH Status TD.
    // Advertising difficulty × blockNumber (~150× real cumulative TD for ETC) causes fully-synced
    // peers to think Fukuii is way ahead, triggering repeated ETH sync attempts. Those fail because
    // Fukuii only has blocks 0..pivot, causing a 15s connect/disconnect loop on static peers.
    val estimatedTotalDifficulty = header.difficulty
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
    // OPT-P8: Reset exponential backoff on successful pivot refresh.
    pivotRefreshRetryDelay = 30.seconds
    val newStateRoot = newPivotHeader.stateRoot
    val oldPivot = pivotBlock.getOrElse(BigInt(0))
    val oldRoot = stateRoot.map(_.take(4).toHex).getOrElse("none")
    val newRoot = newStateRoot.take(4).toHex

    if (stateRoot.contains(newStateRoot)) {
      // Root unchanged — chain hasn't moved or state is identical across blocks. Do NOT restart.
      // Content-addressed trie nodes remain valid; healing coordinator keeps its pending queue.
      // Advance the pivot block number for chain download, but leave all coordinators running.
      if (newPivotBlock > oldPivot) {
        pivotBlock = Some(newPivotBlock)
        appStateStorage.putSnapSyncPivotBlock(newPivotBlock).commit()
        updateBestBlockForPivot(newPivotHeader, newPivotBlock)
        chainDownloader.foreach(_ ! ChainDownloader.UpdateTarget(newPivotBlock))
        chainDownloader.foreach(_ ! ChainDownloader.Resume)
      }
      if (currentPhase == StateHealing) {
        // Near chain head — same root is expected. Reset stall counter and keep healing.
        consecutiveHealingPivotRefreshAttempts = 0
        log.info(s"[HEAL] Pivot refresh: root $newRoot unchanged at block $newPivotBlock — continuing healing")
      } else {
        log.warning(s"[SNAP] Pivot refresh: root $newRoot unchanged in $currentPhase phase — continuing (chain at head?)")
      }
      lastAccountProgressMs = System.currentTimeMillis()
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
    consecutiveHealingPivotRefreshAttempts = 0 // successful pivot refresh — reset stall counter
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

  /** Delete all snap sync work files from tmpdir and clear their DB paths.
    * Called before restartSnapSync() and requestSnapRetry() to ensure a clean slate.
    */
  private def deleteSnapSyncTempFiles(): Unit = {
    val tempDir = java.nio.file.Paths.get(Config.config.getString("tmpdir"))
    val fixedFiles = Seq(
      tempDir.resolve("snap-contract-accounts.bin"),
      tempDir.resolve("snap-contract-storage.bin"),
      tempDir.resolve("snap-unique-codehashes.bin"),
      tempDir.resolve("snap-completed-storage.bin")
    )
    log.info(s"[SNAP] Clearing snap sync state — deleting work files and resetting DB keys:")
    fixedFiles.foreach { f =>
      try {
        val deleted = java.nio.file.Files.deleteIfExists(f)
        if (deleted) log.info(s"[SNAP]   Deleted: $f")
        else log.info(s"[SNAP]   Not found — already absent: $f")
      } catch {
        case e: Exception => log.warning(s"[SNAP]   Failed to delete $f: ${e.getMessage}")
      }
    }
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

    // Snapshot in-memory completion flags before clearing — used to decide what to preserve in DB
    val wasAccountsComplete = accountsComplete
    val wasStorageComplete  = storagePhaseComplete

    // Clear temp files and DB state — conditionally preserve account progress if already done
    if (!wasAccountsComplete) {
      // Accounts still in progress — full reset (existing behavior)
      deleteSnapSyncTempFiles()
      appStateStorage.putSnapSyncAccountsComplete(false).commit()
      appStateStorage.clearSnapSyncStorageCompletionRoot().commit()
      appStateStorage.putSnapSyncStorageFilePath("").commit()
      appStateStorage.putSnapSyncCompletedStoragePath("").commit()
      appStateStorage.putSnapSyncCodeHashesPath("").commit()
      appStateStorage.putSnapSyncContractAccountsPath("").commit()
    } else {
      // Accounts fully downloaded — preserve accounts_complete=true so startup skips re-download.
      // Contract accounts + code hashes files remain valid for the new pivot (content is stable).
      // Only delete storage in-progress files which are stale for a new pivot.
      log.info(s"[SNAP] Preserving accounts_complete=true across restart — startup will skip account phase")
      val tmpDir = java.nio.file.Paths.get(Config.config.getString("tmpdir"))
      Seq("snap-contract-storage.bin", "snap-completed-storage.bin").foreach { name =>
        scala.util.Try(java.nio.file.Files.deleteIfExists(tmpDir.resolve(name)))
      }
      appStateStorage.putSnapSyncStorageFilePath("").commit()
      appStateStorage.putSnapSyncCompletedStoragePath("").commit()
      if (!wasStorageComplete) {
        // Storage was in progress — force re-run with new pivot
        appStateStorage.clearSnapSyncStorageCompletionRoot().commit()
      }
      // Preserved in DB: accounts_complete=true, contractAccountsPath, codeHashesPath
      // Preserved if storage was done: storageCompletionRoot (L-032 will skip storage on next startup)
    }
    // Always clear healing state — it is recomputed for the new pivot root
    appStateStorage.putSnapSyncWalkRoot("").commit()
    appStateStorage.putSnapSyncHealingPendingNodes("").commit()
    appStateStorage.putSnapSyncHealingRound(0).commit()
    appStateStorage.putSnapSyncWalkCompletedPrefixes(Set.empty).commit()

    // Clear in-memory phase state
    accountsComplete = false
    bytecodePhaseComplete = false
    storagePhaseComplete = false

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
    healingValidationCycles += 1
    if (healingValidationCycles >= maxHealingValidationCycles) {
      log.warning(
        s"Healing↔Validation oscillation detected after $healingValidationCycles cycles with " +
          s"${missingNodes.size} remaining missing nodes. Proceeding to regular sync — " +
          s"missing nodes will be fetched on-demand."
      )
      self ! StateValidationComplete
      return
    }
    // Fix 7: re-entering healing from validation is a fresh start — reset stagnation counters
    consecutiveUnproductiveHealingRounds = 0
    lastTrieWalkMissingCount = None
    log.info(
      s"Validation found ${missingNodes.size} missing nodes — re-running trie walk with paths for healing " +
        s"(healing↔validation cycle $healingValidationCycles/$maxHealingValidationCycles)"
    )
    currentPhase = StateHealing
    stateRoot.foreach { root =>
      val storage = getOrCreateMptStorage(pivotBlock.getOrElse(BigInt(0)))
      val selfRef = self
      val walkThreads = math.max(1, math.min(4, snapSyncConfig.trieWalkParallelism))
      val walkEc = ExecutionContext.fromExecutorService(
        java.util.concurrent.Executors.newFixedThreadPool(walkThreads)
      )
      val validator = new StateValidator(storage)
      validator.findMissingNodesWithPaths(root)(walkEc).onComplete {
        case scala.util.Success(Right(nodes)) =>
          walkEc.shutdown()
          selfRef ! TrieWalkResult(nodes)
        case scala.util.Success(Left(error)) =>
          walkEc.shutdown()
          selfRef ! TrieWalkFailed(error)
        case scala.util.Failure(ex) =>
          walkEc.shutdown()
          selfRef ! TrieWalkFailed(Option(ex.getMessage).getOrElse(ex.getClass.getSimpleName))
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
      // Truly unrecoverable after many pivot refreshes — request SNAP retry with backoff
      log.error(
        s"Account sync stalled after $consecutiveAccountStallRefreshes consecutive pivot refreshes. " +
          s"Requesting SNAP retry with backoff."
      )
      MilestoneLog.error(
        s"Account stagnation | $consecutiveAccountStallRefreshes consecutive stall refreshes, requesting SNAP retry"
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
      log.info("=" * 60)
      log.info(s"PHASE: SNAP Sync Complete → Starting Regular Sync from block $pivot")
      log.info("=" * 60)
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
        val estimatedTotalDifficulty = blockchainReader
          .getChainWeightByHash(pivotHash)
          .map(_.totalDifficulty)
          .getOrElse {
            log.warning(
              s"No stored chain weight for pivot block $pivot — using single-block difficulty as lower bound. " +
                s"Chain weight will be corrected during regular sync."
            )
            pivotHeader.difficulty
          }

        // Flush LRU cache to RocksDB BEFORE committing snapSyncDone.
        // DeferredWriteMptStorage.flush() writes nodes to CachedReferenceCountedStorage's
        // LRU cache only (persist() is a NO-OP). If the process dies after snapSyncDone=true
        // but before a real flush, the state root is lost — a false-positive snapSyncDone.
        // forcePersist(GenesisDataLoad) writes all LRU entries to RocksDB and clears the cache.
        log.info("Flushing state trie LRU cache to RocksDB before SNAP finalization...")
        stateStorage.forcePersist(StateStorage.GenesisDataLoad)

        // Verify the pivot's actual stateRoot (not the pre-healing finalizedRoot) is durable.
        // finalizedRoot is computed during account trie assembly BEFORE healing completes and may
        // differ from the pivot's stateRoot. Only mark done when the target root is present, which
        // guarantees regular sync can execute block pivot+1 without MissingNodeException.
        val rootToVerify = stateRoot
        val rootStorage = stateStorage.getReadOnlyStorage
        val rootConfirmed = rootToVerify.exists { r =>
          try { rootStorage.get(r.toArray); true }
          catch { case _: Exception => false }
        }

        if (!rootConfirmed) {
          log.error(
            "Pivot stateRoot NOT confirmed in RocksDB after forcePersist — aborting snapSyncDone commit. " +
              "Resetting validation counters to trigger healing fetch of missing root."
          )
          validationRetryCount = 0
          healingValidationCycles = 0
          scheduler.scheduleOnce(ValidationRetryDelay) { validateState() }(ec)
          // Do NOT finalize — return without sending Done or stopping chainDownloader.
        } else {
          log.info(
            "State root {} confirmed in RocksDB. Committing SNAP finalization.",
            stateRoot.map(_.take(8).toHex).getOrElse("?")
          )
          // Atomic finalization — commit SnapSyncDone marker together with
          // pivot block, chain weight, and best block info in a single batch.
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

          chainDownloader.foreach(context.stop)
          chainDownloader = None

          progressMonitor.complete()
          log.info(progressMonitor.currentProgress.toString)

          context.become(completed)
          context.parent ! Done
        }

      case None =>
        // Fallback: shouldn't happen since PivotHeaderBootstrap stored the header
        log.warning(s"Pivot header for block $pivot not found in storage — setting best block number only")
        appStateStorage.snapSyncDone()
          .and(appStateStorage.putBestBlockNumber(pivot))
          .commit()

        chainDownloader.foreach(context.stop)
        chainDownloader = None

        progressMonitor.complete()
        log.info(progressMonitor.currentProgress.toString)

        context.become(completed)
        context.parent ! Done
    }
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
  case class StateHealingComplete(abandonedNodes: Int, totalHealed: Int)
  case object HealingAllPeersStateless
  case class PersistHealingQueue(pending: Seq[(Seq[ByteString], ByteString)], force: Boolean = false)
  case object StateValidationComplete
  case object ChainDownloadTimeout
  case object GetProgress

  /** Signal from coordinators that the current pivot/stateRoot is likely not serveable by peers.
    *
    * This is analogous to Nethermind's ExpiredRootHash detection (empty payload + empty proofs).
    */
  final case class PivotStateUnservable(rootHash: ByteString, reason: String, consecutiveEmptyResponses: Int)

  /** Sent by AccountRangeCoordinator to SNAPSyncController with interior trie node hashes
    * discovered from boundary Merkle proofs during account download. SNAPSyncController
    * accumulates these and flushes to TrieNodeHealingCoordinator at healing start.
    */
  final case class ProofDiscoveredNodes(nodes: Set[ByteString])

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

  // --- Healing-node serialization helpers (accessible from snap package for testing) ---

  /** Serialize missing trie node paths to a newline-delimited hex string for AppStateStorage.
    * Format per line: "hash:path1,path2,..." — hash first, comma-separated path nibbles.
    * Returns empty string when nodes is empty.
    */
  def serializeHealingNodes(nodes: Seq[(Seq[ByteString], ByteString)]): String =
    nodes.map { case (paths, hash) =>
      val hashHex  = hash.toHex
      val pathsHex = paths.map(_.toHex).mkString(",")
      s"$hashHex:$pathsHex"
    }.mkString("\n")

  /** Deserialize persisted healing nodes. Returns Seq.empty if data is blank or malformed. */
  def deserializeHealingNodes(data: String): Seq[(Seq[ByteString], ByteString)] =
    scala.util.Try {
      data.split('\n').filter(_.nonEmpty).map { line =>
        val colonIdx = line.indexOf(':')
        if (colonIdx < 0) throw new IllegalArgumentException(s"No colon separator in: $line")
        val hash  = ByteString(com.chipprbots.ethereum.utils.Hex.decode(line.substring(0, colonIdx)))
        val paths = line.substring(colonIdx + 1).split(',').filter(_.nonEmpty).map { p =>
          ByteString(com.chipprbots.ethereum.utils.Hex.decode(p))
        }.toSeq
        (paths, hash)
      }.toSeq
    }.getOrElse(Seq.empty)
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
    deferredMerkleization: Boolean = true,
    // Number of parallel subtree walkers for the post-healing trie validation walk.
    // Default 3: ~8,500-9,500 nodes/s vs 6,300 at 2. Max 4 before I/O contention on constrained hardware.
    trieWalkParallelism: Int = 3,
    // When false (default): skip the validation walk if healing completed with 0 abandoned nodes.
    // When true: always run the full trie scan regardless of healing outcome (audit/diagnostic mode).
    requireValidationWalk: Boolean = false
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
        else true,
      trieWalkParallelism =
        if (snapConfig.hasPath("trie-walk-parallelism"))
          snapConfig.getInt("trie-walk-parallelism")
        else 3,
      requireValidationWalk =
        if (snapConfig.hasPath("require-validation-walk"))
          snapConfig.getBoolean("require-validation-walk")
        else false
    )
  }
}

class StateValidator(
    mptStorage: MptStorage,
    nodesScanned: java.util.concurrent.atomic.AtomicLong = new java.util.concurrent.atomic.AtomicLong(0)
) {

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
    * Parallelizes by fanning out at depth-2 of the root BranchNode, launching one Future per subtree.
    * Each subtree gets its own result buffer and visited set — no shared mutable state between workers.
    *
    * @return
    *   Future of (pathset, hash) pairs ready for TrieNodeHealingCoordinator.QueueMissingNodes
    */
  def findMissingNodesWithPaths(
    stateRoot: ByteString,
    completedPrefixes: Set[String] = Set.empty,
    onSubtreeComplete: (String, Seq[(Seq[ByteString], ByteString)]) => Unit = (_, _) => ()
  )(implicit ec: ExecutionContext): Future[Either[String, Seq[(Seq[ByteString], ByteString)]]] = {
    try {
      val rootNode = mptStorage.getForWalk(stateRoot.toArray)
      rootNode match {
        case branch: BranchNode =>
          // Fan out to depth-2 subtrees for parallel walking (up to 256 subtrees)
          val subtrees = collectDepth2Subtrees(branch)
          val futures = subtrees.map { case (prefixPath, subtreeRoot) =>
            val prefixHex = Hex.toHexString(prefixPath)
            if (completedPrefixes.contains(prefixHex)) {
              // OPT-W1: Already completed in a previous run — skip, partial nodes in AppStateStorage
              Future.successful(Seq.empty[(Seq[ByteString], ByteString)])
            } else {
              val fut = Future {
                val localResult = mutable.ArrayBuffer[(Seq[ByteString], ByteString)]()
                traverseAccountTrieWithPaths(subtreeRoot, mptStorage, prefixPath, localResult, mutable.Set.empty)
                localResult.toSeq
              }
              fut.onComplete {
                case scala.util.Success(nodes) => onSubtreeComplete(prefixHex, nodes)
                case _                         => () // failure handled via TrieWalkFailed path
              }
              fut
            }
          }
          Future.sequence(futures).map(results => Right(results.flatten))

        case other =>
          // Small or degenerate trie — run single-threaded
          val result = mutable.ArrayBuffer[(Seq[ByteString], ByteString)]()
          traverseAccountTrieWithPaths(other, mptStorage, Array.empty[Byte], result, mutable.Set.empty)
          Future.successful(Right(result.toSeq))
      }
    } catch {
      case e: MerklePatriciaTrie.MissingNodeException =>
        // Root node itself is missing
        val compactPath = ByteString(HexPrefix.encode(Array.empty[Byte], isLeaf = false))
        Future.successful(Right(Seq((Seq(compactPath), e.hash))))
      case e: Exception =>
        Future.successful(Left(s"Trie walk failed: ${e.getMessage}"))
    }
  }

  /** Collect depth-2 subtrees from the root BranchNode for parallel walking.
    * Uses multiGet to batch-fetch depth-1 and depth-2 HashNode children, reducing JNI overhead.
    * Returns (nibblePath prefix, subtreeRoot) pairs — each is an independent walk unit.
    */
  private def collectDepth2Subtrees(root: BranchNode): Seq[(Array[Byte], MptNode)] = {
    // --- Depth 1: batch-fetch all HashNode children of root ---
    val hashChildrenD1: IndexedSeq[(Int, HashNode)] = (0 until 16).flatMap { i =>
      root.children(i) match {
        case h: HashNode => Some((i, h))
        case _           => None
      }
    }
    val fetchedD1: Seq[Option[MptNode]] =
      if (hashChildrenD1.nonEmpty) mptStorage.getMultipleForWalk(hashChildrenD1.map(_._2.hash))
      else Seq.empty

    // Assemble depth-1 (path, node) pairs
    val d1nodes = mutable.ArrayBuffer[(Array[Byte], MptNode)]()
    hashChildrenD1.zip(fetchedD1).foreach { case ((i, hashNode), maybeResolved) =>
      // Missing node: pass the HashNode itself — traversal will detect + record it
      d1nodes += ((Array(i.toByte), maybeResolved.getOrElse(hashNode)))
    }
    for (i <- 0 until 16) {
      val child = root.children(i)
      if (!child.isNull && !child.isInstanceOf[HashNode])
        d1nodes += ((Array(i.toByte), child))
    }

    // --- Depth 2: for each depth-1 BranchNode, batch-fetch its HashNode children ---
    val d2subtrees = mutable.ArrayBuffer[(Array[Byte], MptNode)]()
    d1nodes.foreach { case (path1, node) =>
      node match {
        case branch: BranchNode =>
          val hashChildrenD2: IndexedSeq[(Int, HashNode)] = (0 until 16).flatMap { i =>
            branch.children(i) match {
              case h: HashNode => Some((i, h))
              case _           => None
            }
          }
          val fetchedD2: Seq[Option[MptNode]] =
            if (hashChildrenD2.nonEmpty) mptStorage.getMultipleForWalk(hashChildrenD2.map(_._2.hash))
            else Seq.empty

          hashChildrenD2.zip(fetchedD2).foreach { case ((i, hashNode), maybeResolved) =>
            d2subtrees += ((path1 :+ i.toByte, maybeResolved.getOrElse(hashNode)))
          }
          for (i <- 0 until 16) {
            val child = branch.children(i)
            if (!child.isNull && !child.isInstanceOf[HashNode])
              d2subtrees += ((path1 :+ i.toByte, child))
          }

        case _ =>
          // Leaf, Extension, or missing node at depth-1 — treat as single subtree
          d2subtrees += ((path1, node))
      }
    }

    if (d2subtrees.nonEmpty) d2subtrees.toSeq else d1nodes.toSeq
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

    nodesScanned.incrementAndGet()
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
              val storageRoot = storage.getForWalk(account.storageRoot.toArray)
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
          // Batch-fetch all unvisited HashNode children in one multiGet call to reduce JNI overhead
          val hashChildEntries = (0 until 16).flatMap { i =>
            branch.children(i) match {
              case h: HashNode if !visited.contains(ByteString(h.hash)) => Some((i, h))
              case _                                                     => None
            }
          }
          if (hashChildEntries.nonEmpty) {
            val fetched = storage.getMultipleForWalk(hashChildEntries.map(_._2.hash))
            hashChildEntries.zip(fetched).foreach { case ((i, hashNode), maybeResolved) =>
              val newPath = currentNibblePath :+ i.toByte
              maybeResolved match {
                case Some(resolved) =>
                  traverseAccountTrieWithPaths(resolved, storage, newPath, result, visited)
                case None =>
                  val compactPath = ByteString(HexPrefix.encode(newPath, isLeaf = false))
                  result += ((Seq(compactPath), ByteString(hashNode.hash)))
              }
            }
          }
          // Process inline children (already decoded — no fetch needed)
          for (i <- 0 until 16) {
            val child = branch.children(i)
            if (!child.isNull && !child.isInstanceOf[HashNode]) {
              val newPath = currentNibblePath :+ i.toByte
              traverseAccountTrieWithPaths(child, storage, newPath, result, visited)
            }
          }
        }

      case hash: HashNode =>
        val hashKey = ByteString(hash.hash)
        if (!visited.contains(hashKey)) {
          try {
            val resolvedNode = storage.getForWalk(hash.hash)
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
  ): Unit = {
    nodesScanned.incrementAndGet()
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
          // Batch-fetch all unvisited HashNode children in one multiGet call
          val hashChildEntries = (0 until 16).flatMap { i =>
            branch.children(i) match {
              case h: HashNode if !visited.contains(ByteString(h.hash)) => Some((i, h))
              case _                                                     => None
            }
          }
          if (hashChildEntries.nonEmpty) {
            val fetched = storage.getMultipleForWalk(hashChildEntries.map(_._2.hash))
            hashChildEntries.zip(fetched).foreach { case ((i, hashNode), maybeResolved) =>
              val newPath = currentNibblePath :+ i.toByte
              maybeResolved match {
                case Some(resolved) =>
                  traverseStorageTrieWithPaths(resolved, storage, newPath, accountHash, result, visited)
                case None =>
                  val compactPath = ByteString(HexPrefix.encode(newPath, isLeaf = false))
                  result += ((Seq(accountHash, compactPath), ByteString(hashNode.hash)))
              }
            }
          }
          // Process inline children (already decoded — no fetch needed)
          for (i <- 0 until 16) {
            val child = branch.children(i)
            if (!child.isNull && !child.isInstanceOf[HashNode]) {
              val newPath = currentNibblePath :+ i.toByte
              traverseStorageTrieWithPaths(child, storage, newPath, accountHash, result, visited)
            }
          }
        }

      case hash: HashNode =>
        val hashKey = ByteString(hash.hash)
        if (!visited.contains(hashKey)) {
          try {
            val resolvedNode = storage.getForWalk(hash.hash)
            traverseStorageTrieWithPaths(resolvedNode, storage, currentNibblePath, accountHash, result, visited)
          } catch {
            case _: MerklePatriciaTrie.MissingNodeException =>
              val compactPath = ByteString(HexPrefix.encode(currentNibblePath, isLeaf = false))
              result += ((Seq(accountHash, compactPath), ByteString(hash.hash)))
          }
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
