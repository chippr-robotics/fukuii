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
import com.chipprbots.ethereum.utils.{Config, Hex}
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

  // Actor-based coordinators
  private var accountRangeCoordinator: Option[ActorRef] = None
  private var bytecodeCoordinator: Option[ActorRef] = None
  private var storageRangeCoordinator: Option[ActorRef] = None
  private var trieNodeHealingCoordinator: Option[ActorRef] = None
  private var chainDownloader: Option[ActorRef] = None
  private var chainDownloadComplete: Boolean = false

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

  // Force-complete flags — prevent stagnation timer from re-firing after force-complete is sent.
  // storageForceCompleted closes the race window between ForceCompleteStorage dispatch and
  // StorageRangeSyncComplete arrival (storagePhaseComplete is still false in that window).
  // bytecodeForceCompleted prevents re-polling/re-triggering after ForceCompleteByteCode is sent.
  private var storageForceCompleted: Boolean = false
  private var bytecodeForceCompleted: Boolean = false

  private val progressMonitor = new SyncProgressMonitor(scheduler)

  // Retry counter for validation failures to prevent infinite loops
  private var validationRetryCount: Int = 0
  private val MaxValidationRetries = 3
  private val ValidationRetryDelay = 500.millis

  // Retry counter for bootstrap-to-SNAP transition (exponential backoff: 2s→60s cap, max 10 retries)
  private var bootstrapRetryCount: Int = 0
  private val BootstrapRetryBaseDelay = 2.seconds
  private val BootstrapRetryMaxDelay = 60.seconds
  private val MaxBootstrapRetries = 10

  // Pivot restart guard (prevents noisy rapid restarts if peer head fluctuates)
  private var lastPivotRestartMs: Long = 0L
  private val MinPivotRestartInterval: FiniteDuration = 30.seconds

  // Consecutive pivot refresh counter: when all peers are repeatedly stateless after
  // pivot refreshes, it strongly indicates no peer has a snapshot database. Each
  // PivotStateUnservable increments this; any successful state download (accounts,
  // storage slots, or bytecodes) resets it.
  // After MaxConsecutivePivotRefreshes, we record a critical failure to accelerate
  // fallback to fast sync instead of cycling pivots for 75+ minutes.
  private var consecutivePivotRefreshes: Int = 0
  private val MaxConsecutivePivotRefreshes = 3

  // Healing pivot refresh counter: tracks consecutive pivots where all peers fail to serve
  // the root node during healing. After MaxHealingPivotRefreshes, fall back to the
  // state-download pivot (whose state IS in the DB) instead of cycling indefinitely.
  private var healingPivotRefreshes: Int = 0
  private val MaxHealingPivotRefreshes = 6

  // The pivot used for the actual state download (account/storage ranges).
  // Saved before the pre-healing pivot switch so we can fall back to it if the
  // new pivot's root is unservable by the current ETC SNAP peer set.
  private var stateDownloadPivot: Option[BigInt] = None

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
  private var walkStartedAt: Option[java.time.Instant] = None // set when walk launches, cleared on result; used for hung-walk detection (A7)
  private var walkPivotRefreshSuppressLogged: Boolean = false // dedup flag: suppress repeated "walk in progress, deferring" messages
  private var consecutiveUnproductiveHealingRounds: Int = 0
  private val maxUnproductiveHealingRounds: Int =
    3 // After 3 rounds of finding same missing nodes with 0 healed, skip to validation
  private var bootstrapCheckTask: Option[Cancellable] = None
  private var pivotBootstrapRetryTask: Option[Cancellable] = None
  private var rateTrackerTuneTask: Option[Cancellable] = None

  private case object RetryPivotRefresh
  private case object CheckSnapCapability
  private case object TuneRateTracker
  private case object EvictNonSnapPeers
  private case object CheckPivotStaleness
  private var snapCapabilityCheckTask: Option[Cancellable] = None
  private var snapPeerEvictionTask: Option[Cancellable] = None
  private var pivotStalenessCheckTask: Option[Cancellable] = None

  // Besu DynamicPivotBlockSelector.java constants
  private val PivotWindowValidity: Int = 126 // blocks behind chain tip before pivot switch
  private val PivotCheckInterval: FiniteDuration = 60.seconds

  /** Like handlePeerListMessagesWithRateTracking, but also reactively triggers a bootstrap retry when new SNAP-capable
    * peers arrive during the bootstrapping state. Without this, the node waits for the full exponential backoff timer
    * (up to 60s) even though peers are already available. Core-geth starts syncing within 200ms of first peer — we
    * should too.
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
  // Unified stagnation watchdog thresholds — one check interval, phase-specific thresholds
  private val DownloadStagnationCheckInterval: FiniteDuration = 30.seconds
  private val StorageStagnationThreshold: FiniteDuration = 20.minutes
  private val AccountStagnationThreshold: FiniteDuration = snapSyncConfig.accountStagnationTimeout
  // If bytecodes show no progress for this long, force-complete and let healing recover missing code.
  private val BytecodeStagnationThreshold: FiniteDuration = 5.minutes
  // Tail-stuck detection: when only a small number of storage accounts remain and no progress has
  // been made, these accounts are almost certainly unservable by any peer (very large contracts
  // whose storage trie exceeds per-request limits). Route to trie healing immediately rather than
  // waiting the full StorageStagnationThreshold.
  private val TailThreshold: Int = 1000
  private val TailStagnationMs: Long = 5.minutes.toMillis
  private var lastStorageProgressMs: Long = System.currentTimeMillis()
  private var lastAccountProgressMs: Long = System.currentTimeMillis()
  private var lastAccountTasksCompleted: Int = 0
  private var lastAccountsDownloaded: Long = 0
  private var lastBytecodeProgressCount: Long = 0
  private var lastBytecodeProgressMs: Long = System.currentTimeMillis()

  override def preStart(): Unit = {
    log.info("=" * 60)
    log.info("=== Fukuii SNAP Sync ===")
    log.info("=" * 60)
    progressMonitor.startPeriodicLogging()
    scheduler.scheduleOnce(60.seconds, self, ActorLivenessProbe)(context.dispatcher)
  }

  override def postStop(): Unit = {
    // Cancel all scheduled tasks
    accountRangeRequestTask.foreach(_.cancel())
    bytecodeRequestTask.foreach(_.cancel())
    storageRangeRequestTask.foreach(_.cancel())
    accountStagnationCheckTask.foreach(_.cancel())
    storageStagnationCheckTask.foreach(_.cancel())
    healingRequestTask.foreach(_.cancel())
    pivotStalenessCheckTask.foreach(_.cancel())
    bootstrapCheckTask.foreach(_.cancel())
    pivotBootstrapRetryTask.foreach(_.cancel())
    snapCapabilityCheckTask.foreach(_.cancel())
    snapPeerEvictionTask.foreach(_.cancel())
    rateTrackerTuneTask.foreach(_.cancel())
    progressMonitor.stopPeriodicLogging()

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

    // Snap capability grace period check: reschedule if no snap/1 peers found yet.
    // Besu-aligned: never fall back to fast sync. Keep waiting indefinitely for SNAP peers.
    case CheckSnapCapability =>
      val snapPeerCount = peersToDownloadFrom.count { case (_, p) =>
        p.peerInfo.remoteStatus.supportsSnap
      }
      if (snapPeerCount > 0) {
        val effectiveConcurrency = math.min(snapSyncConfig.accountConcurrency, snapPeerCount).max(1)
        log.info(
          s"Found $snapPeerCount snap-capable peer(s) during grace period, starting account range sync (concurrency=$effectiveConcurrency)"
        )
        stateRoot.foreach(launchAccountRangeWorkers(_, effectiveConcurrency))
      } else {
        // Besu-aligned: no fallback to fast sync. Reschedule and keep waiting for SNAP peers.
        val gracePeriod = snapSyncConfig.snapCapabilityGracePeriod
        log.warning(
          s"No snap-capable peers found after grace period. " +
            s"Rescheduling check in ${gracePeriod.toSeconds}s (Besu-aligned: no fallback)."
        )
        snapCapabilityCheckTask = Some(scheduler.scheduleOnce(gracePeriod, self, CheckSnapCapability)(ec))
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
      log.info(
        "Persisting finalized account trie root: {}",
        finalizedRoot.take(8).toArray.map("%02x".format(_)).mkString
      )
      appStateStorage.putSnapSyncFinalizedRoot(finalizedRoot).commit()

    case ProgressAccountsTrieFinalized =>
      progressMonitor.setFinalizingTrie(false)
      log.info("Account range trie finalization complete")

    case ProgressBytecodesDownloaded(count) =>
      progressMonitor.incrementBytecodesDownloaded(count)
      if (count > 0) consecutivePivotRefreshes = 0 // Bytecodes downloading = SNAP is working

    case ProgressStorageSlotsSynced(count) =>
      progressMonitor.incrementStorageSlotsSynced(count)
      // Only reset stagnation timer on meaningful progress (>10 slots).
      // Trickle progress from stale in-flight responses or pivot refresh cycles
      // shouldn't keep the stagnation timer from eventually firing.
      if (count > 10) {
        lastStorageProgressMs = System.currentTimeMillis()
        if (count > 100) storageStagnationRefreshAttempted = false
        consecutivePivotRefreshes = 0 // Storage downloads confirm SNAP is working
      }

    case ProgressNodesHealed(count) =>
      progressMonitor.incrementNodesHealed(count)
      consecutiveUnproductiveHealingRounds = 0 // Reset — healing made progress
      if (count > 0) healingPivotRefreshes = 0 // Root was served — reset healing fallback counter

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
          s"Ignoring PivotStateUnservable — rate-limited ${elapsed}s ago " +
            s"(phase=$currentPhase, emptyResponses=$emptyResponses, reason=$reason)"
        )
      } else if (currentPhase == AccountRangeSync || currentPhase == ByteCodeAndStorageSync) {
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
                "Refreshing in-place (preserving accounts). Stagnation watchdog is the real escape hatch."
            )
            consecutivePivotRefreshes = 0 // Reset — accounts completing IS progress
            refreshPivotInPlace(reason)
          } else {
            // Accounts still in progress — Besu-aligned: no fallback, no critical failure tracking.
            // Besu persists indefinitely. Reset counter and refresh pivot.
            log.warning(
              s"$consecutivePivotRefreshes consecutive stateless pivot refreshes during account sync. " +
                "Besu-aligned: resetting counter and refreshing pivot (no fallback to fast sync)."
            )
            consecutivePivotRefreshes = 0
            refreshPivotInPlace(reason)
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
            s"Pivot header bootstrap for block $pendingPivot returned no header. " +
              s"Scheduling retry in 60s (preserving sync state)."
          )
          // Besu: switchToNewPivotBlock() never destroys state on header fetch failure — it simply
          // retries. PivotBootstrapFailed (below) already schedules a 60s retry via RetryPivotRefresh;
          // BootstrapComplete(None) must do the same. restartSnapSync() here destroys 5+ days of
          // downloaded account/storage data on a transient header unavailability.
          pivotBootstrapRetryTask.foreach(_.cancel())
          pivotBootstrapRetryTask = Some(
            scheduler.scheduleOnce(60.seconds, self, RetryPivotRefresh)(context.dispatcher)
          )
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

    case ActorLivenessProbe =>
      log.info(
        s"[ACTOR-ALIVE] phase=$currentPhase trieWalkInProgress=$trieWalkInProgress " +
          s"consecutiveUnproductiveHealingRounds=$consecutiveUnproductiveHealingRounds"
      )
      scheduler.scheduleOnce(60.seconds, self, ActorLivenessProbe)(context.dispatcher)

    case RetryPivotRefresh =>
      pivotBootstrapRetryTask = None
      if (currentPhase == AccountRangeSync || currentPhase == ByteCodeAndStorageSync || currentPhase == StateHealing) {
        log.info(s"Retrying pivot refresh (phase=$currentPhase)...")
        refreshPivotInPlace("retry pivot refresh")
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

    case AccountRangeSyncComplete(totalCodeHashes) =>
      if (accountsComplete) {
        log.info("Ignoring duplicate AccountRangeSyncComplete")
      } else {
        accountsComplete = true
        log.info(
          s"Account range sync complete ($totalCodeHashes unique codeHashes). Signaling NoMore to bytecode/storage coordinators."
        )

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

        // Clear persisted range progress — account phase is done, no need to resume it
        appStateStorage.putSnapSyncProgress("").commit()
        preservedRangeProgress = Map.empty
        preservedAtPivotBlock = None

        // Reset consecutive pivot refreshes — account completion IS progress
        consecutivePivotRefreshes = 0

        // Signal expected count BEFORE the sentinel — ByteCodeCoordinator uses this to verify
        // it has received all AddByteCodeTasks batches before declaring completion (Fix 4 guard).
        bytecodeCoordinator.foreach(_ ! actors.Messages.SetExpectedByteCodeCount(totalCodeHashes))
        // Signal that no more work will arrive (sentinel pattern — prevents premature completion)
        bytecodeCoordinator.foreach(_ ! actors.Messages.NoMoreByteCodeTasks)
        storageRangeCoordinator.foreach(_ ! actors.Messages.NoMoreStorageTasks)

        // Transition to ByteCodeAndStorageSync for status reporting and stagnation checks
        currentPhase = ByteCodeAndStorageSync
        progressMonitor.startPhase(ByteCodeAndStorageSync)

        // Besu-aligned D11: 1 request per peer for all coordinators.
        storageRangeCoordinator.foreach(_ ! actors.Messages.UpdateMaxInFlightPerPeer(1))
        bytecodeCoordinator.foreach(_ ! actors.Messages.UpdateMaxInFlightPerPeer(1))

        // Cancel account stagnation checks (no longer relevant)
        accountStagnationCheckTask.foreach(_.cancel()); accountStagnationCheckTask = None

        // Start storage stagnation watchdog now that accounts are done
        lastStorageProgressMs = System.currentTimeMillis()
        lastBytecodeProgressMs = System.currentTimeMillis()
        lastBytecodeProgressCount = 0
        scheduleStagnationChecks()

        checkAllDownloadsComplete()
      }

    case ByteCodeSyncComplete if !bytecodePhaseComplete =>
      bytecodePhaseComplete = true
      progressMonitor.setBytecodeComplete()
      val downloaded = progressMonitor.currentProgress.bytecodesDownloaded
      log.info(
        s"ByteCode sync complete ($downloaded bytecodes). Storage: $storagePhaseComplete, Accounts: $accountsComplete"
      )
      // Besu-aligned D11: keep 1 req/peer even after bytecode completes.
      if (!storagePhaseComplete) {
        storageRangeCoordinator.foreach { coord =>
          coord ! actors.Messages.UpdateMaxInFlightPerPeer(1)
          log.info(
            "Storage per-peer budget remains 1 (Besu-aligned D11: no pipelining)"
          )
        }
      }
      checkAllDownloadsComplete()

    case StorageRangeSyncComplete if !storagePhaseComplete =>
      storagePhaseComplete = true
      log.info(s"Storage range sync complete. ByteCode: $bytecodePhaseComplete, Accounts: $accountsComplete")
      checkAllDownloadsComplete()

    // Sender-guards on all healing coordinator messages: Pekko context.stop is async — old
    // coordinator instances continue processing their mailbox after context.stop() is called.
    // Only the CURRENT coordinator (trieNodeHealingCoordinator.contains(sender())) may trigger
    // state transitions. Stale messages from stopped coordinators are logged and dropped.
    case HealingAllPeersStateless if currentPhase == StateHealing && trieNodeHealingCoordinator.contains(sender()) =>
      healingPivotRefreshes += 1
      log.warning(
        s"All healing peers stateless — pivot refresh $healingPivotRefreshes/$MaxHealingPivotRefreshes"
      )
      if (healingPivotRefreshes >= MaxHealingPivotRefreshes) {
        attemptHealingFallbackToStateDownloadPivot()
      } else {
        refreshPivotInPlace("all healing peers stateless")
      }

    case HealingAllPeersStateless if currentPhase == StateHealing =>
      log.debug(s"Ignoring HealingAllPeersStateless from non-current coordinator (${sender().path.name})")

    // HealingStagnated is no longer sent (Besu-aligned: no stagnation watchdog in TrieNodeHealingCoordinator).
    // Handler removed in april-besu-alignment D7.

    case PersistHealingQueue(pending, _) =>
      log.debug(
        s"[HEAL-PERSIST] ${pending.size} pending nodes (persistence not implemented on this branch — discarding)"
      )

    case StateHealingComplete(abandonedNodes, totalHealed) if trieNodeHealingCoordinator.contains(sender()) =>
      log.info(s"State healing complete [abandonedNodes=$abandonedNodes, healed=$totalHealed].")
      // D17 (Besu alignment): Besu finalizes SNAP sync immediately after healing with no
      // post-healing validation trie walk. SnapWorldDownloadState.onSnapServerDown() /
      // TrieNodeHealingStep just calls worldStateDownloadState.checkCompletion() which
      // calls onAllTrieNodeHealingRequestsComplete() → triggers finalization directly.
      // fukuii's 3-pass validation (findMissingNodesWithPaths + validateAccountTrie +
      // validateAllStorageTries) has no Besu counterpart and takes 30+ minutes on mainnet.
      // When abandonedNodes==0, every referenced trie node is in storage — skip all validation.
      if (abandonedNodes == 0) {
        log.info(
          s"[D17] Healing complete with 0 abandoned nodes (healed=$totalHealed) — " +
            "skipping validation walk, finalizing SNAP sync directly (Besu-aligned)"
        )
        pivotStalenessCheckTask.foreach(_.cancel())
        pivotStalenessCheckTask = None
        pivotBlock.foreach(finalizeSnapSync)
      } else if (trieWalkInProgress) {
        // A trie walk is already running — its result will determine next step
        log.info("Trie walk in progress, waiting for result...")
      } else {
        // abandonedNodes > 0: some nodes couldn't be healed — run the walk to find stragglers
        startTrieWalk()
      }

    case StateHealingComplete(_, _) =>
      log.debug(s"Ignoring StateHealingComplete from non-current coordinator (${sender().path.name})")

    case TrieWalkResult(missingNodes, walkedRoot) if currentPhase == StateHealing =>
      trieWalkInProgress = false
      walkStartedAt = None
      walkPivotRefreshSuppressLogged = false // walk finished — allow next stale-pivot log
      // Discard results from walks that completed after a pivot change (BUG-W1/W2).
      // April-confluence uses async Futures for walks; a walk launched on pivot N can complete
      // after pivot changes to N+1. Without this guard, stale missing nodes queue against the
      // wrong root and can trigger premature StateValidationComplete.
      val currentRoot = stateRoot.getOrElse(ByteString.empty)
      if (walkedRoot != currentRoot) {
        log.info(
          s"[WALK-FUTURE] Discarding stale walk result: walked=${walkedRoot.take(4).map("%02x".format(_)).mkString} " +
            s"current=${currentRoot.take(4).map("%02x".format(_)).mkString} — walk ran against old pivot, re-launching"
        )
        // Re-launch walk against the current root immediately
        startTrieWalk()
      } else if (missingNodes.isEmpty) {
        log.info("Trie walk found no missing nodes — healing complete!")
        consecutiveUnproductiveHealingRounds = 0
        pivotStalenessCheckTask.foreach(_.cancel())
        pivotStalenessCheckTask = None
        progressMonitor.startPhase(StateValidation)
        currentPhase = StateValidation
        validateState()
      } else {
        consecutiveUnproductiveHealingRounds += 1
        if (consecutiveUnproductiveHealingRounds >= maxUnproductiveHealingRounds) {
          // Besu alignment: markAsStalled() is a no-op for healing — Besu never proceeds to
          // finalization while nodes are still missing. We mirror this for the state root:
          // if the root is among the missing nodes, it cannot be recovered by regular sync
          // on-demand (unlike interior nodes). Force a pivot refresh instead of finalizing.
          val rootMissing = stateRoot.exists(root => missingNodes.exists { case (_, hash) => hash == root })
          if (rootMissing) {
            log.warning(
              s"[HEAL-STAGNATION] State root is among ${missingNodes.size} unhealed nodes after " +
                s"$consecutiveUnproductiveHealingRounds rounds — cannot proceed to finalization. " +
                s"Triggering pivot refresh (Besu: markAsStalled no-op → reloadTrieHeal on new pivot)."
            )
            consecutiveUnproductiveHealingRounds = 0
            healingPivotRefreshes += 1
            if (healingPivotRefreshes >= MaxHealingPivotRefreshes) {
              attemptHealingFallbackToStateDownloadPivot()
            } else {
              refreshPivotInPlace(s"healing-stagnation-root-missing ($healingPivotRefreshes/$MaxHealingPivotRefreshes)")
            }
          } else {
            log.warning(
              s"Healing stagnation: ${missingNodes.size} non-root missing nodes persist after " +
                s"$consecutiveUnproductiveHealingRounds consecutive rounds with no progress. " +
                s"Proceeding to validation — regular sync will recover missing nodes on-demand."
            )
            consecutiveUnproductiveHealingRounds = 0
            pivotStalenessCheckTask.foreach(_.cancel())
            pivotStalenessCheckTask = None
            progressMonitor.startPhase(StateValidation)
            currentPhase = StateValidation
            validateState()
          }
        } else {
          val walkRound = consecutiveUnproductiveHealingRounds
          val walkLabel = if (walkRound == 0) "initial walk" else s"round $walkRound/$maxUnproductiveHealingRounds"
          log.info(
            s"Trie walk found ${missingNodes.size} missing nodes — queuing for healing ($walkLabel)"
          )
          // A4 (BUG-HEAL1): Explicitly handle coordinator None — Option.foreach silently drops
          // nodes when coordinator is None (actor model race: walk completes before coordinator
          // initializes). Log and retry rather than silently losing missing nodes.
          trieNodeHealingCoordinator match {
            case Some(coordinator) =>
              coordinator ! actors.Messages.QueueMissingNodes(missingNodes)
            case None =>
              log.warning(
                s"[SNAP-HEALING] TrieWalkResult arrived but healing coordinator is None — " +
                  s"${missingNodes.size} missing nodes would be dropped. Re-scheduling walk to retry."
              )
              scheduler.scheduleOnce(5.seconds, self, ScheduledTrieWalk)(ec)
          }
          // Schedule next overlapping trie walk — don't wait for healing to complete
          trieNodeHealingCoordinator.foreach { _ =>
            scheduler.scheduleOnce(2.minutes) {
              self ! ScheduledTrieWalk
            }(ec)
          }
        }
      }

    case ScheduledTrieWalk if currentPhase == StateHealing =>
      startTrieWalk()

    case TrieWalkFailed(error) if currentPhase == StateHealing =>
      trieWalkInProgress = false
      walkStartedAt = None
      walkPivotRefreshSuppressLogged = false
      log.error(s"Trie walk failed: $error. Retrying after delay...")
      scheduler.scheduleOnce(5.seconds) {
        self ! ScheduledTrieWalk
      }(ec)

    // Besu DynamicPivotBlockSelector: every 60s check if pivot drifted >126 blocks behind chain tip.
    case CheckPivotStaleness if currentPhase == StateHealing =>
      val currentPivot = pivotBlock.getOrElse(BigInt(0))
      // A7 (BUG-H3): detect hung walk — if trieWalkInProgress has been true for longer than
      // WalkHangTimeout, the walk Future is likely stuck (GC pause, I/O stall, or deadlock).
      // In this case, force-reset walk state so pivot staleness recovery can proceed.
      // Besu uses threadpool workers (no async Future) so this problem doesn't arise there.
      val walkIsHung = trieWalkInProgress && walkStartedAt.exists { started =>
        java.time.Duration.between(started, java.time.Instant.now()).toMinutes >= 30
      }
      if (walkIsHung) {
        log.warning(
          s"[WALK-HUNG] Trie walk has been running for >30 minutes — presumed hung. " +
            s"Resetting trieWalkInProgress and allowing pivot staleness check to proceed."
        )
        trieWalkInProgress = false
        walkStartedAt = None
        walkPivotRefreshSuppressLogged = false
      }
      currentNetworkBestFromSnapPeers().foreach { networkBest =>
        val pivotAge = networkBest - currentPivot
        if (pivotAge > PivotWindowValidity) {
          if (trieWalkInProgress) {
            if (!walkPivotRefreshSuppressLogged) {
              log.debug(
                s"[PIVOT-STALENESS] Pivot $currentPivot is $pivotAge blocks stale but trie walk is in progress — deferring refresh"
              )
              walkPivotRefreshSuppressLogged = true
            }
          } else {
            walkPivotRefreshSuppressLogged = false
            log.info(
              s"[PIVOT-STALENESS] Pivot $currentPivot is $pivotAge blocks behind network head $networkBest " +
                s"(threshold: $PivotWindowValidity). Refreshing pivot."
            )
            refreshPivotInPlace("pivot-staleness-check")
          }
        }
      }

    case StateValidationComplete =>
      log.info("State validation complete. SNAP sync finished!")
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
  // Unified stagnation detection — single timer dispatches to the active coordinator
  private case object CheckDownloadStagnation
  private case class AccountCoordinatorProgress(progress: actors.AccountRangeStats)
  private case class StorageCoordinatorProgress(stats: actors.StorageRangeCoordinator.SyncStatistics)
  private case class ByteCodeCoordinatorProgress(progress: actors.Messages.ByteCodeProgress)

  private def scheduleStagnationChecks(): Unit = {
    accountStagnationCheckTask.foreach(_.cancel())
    storageStagnationCheckTask.foreach(_.cancel())
    val interval = DownloadStagnationCheckInterval
    accountStagnationCheckTask = Some(
      scheduler.scheduleWithFixedDelay(interval, interval, self, CheckDownloadStagnation)(ec)
    )
  }

  /** Handle stagnation for storage phase.
    *
    * When storage has no progress for StorageStagnationThreshold:
    *   - First stall: attempt pivot refresh (cheaper recovery)
    *   - Second stall (30s later, not 20min): force-complete to healing
    *
    * The second check uses a short window because the pivot refresh either works immediately (peers serve new root) or
    * it doesn't (all peers stateless again). Waiting another 20 minutes just delays the inevitable force-complete.
    */
  private def maybeRestartIfStorageStagnant(stats: actors.StorageRangeCoordinator.SyncStatistics): Unit = {
    // Already force-completed — don't re-fire. Closes the race window between ForceCompleteStorage
    // dispatch and StorageRangeSyncComplete arrival (storagePhaseComplete still false in that window).
    if (storageForceCompleted) return
    if (currentPhase != ByteCodeAndStorageSync) return

    // If coordinator responded with real stats, check if work remains.
    // If all stats are zero (ask timeout), assume work IS remaining since
    // we're still in ByteCodeAndStorageSync phase (would have transitioned if truly complete).
    val isTimeoutResponse =
      stats.tasksPending == 0 && stats.tasksActive == 0 && stats.tasksCompleted == 0 && stats.elapsedTimeMs == 0
    val workRemaining = isTimeoutResponse || stats.tasksPending > 0 || stats.tasksActive > 0

    // Special case: coordinator reports 0 pending + 0 active but never sent StorageRangeSyncComplete.
    // This means trie construction is stuck (accountsInTrieConstruction/pendingAccountSlots not empty).
    // After the stagnation threshold, force-complete to unstick it.
    if (!workRemaining && !isTimeoutResponse && !storagePhaseComplete) {
      val now = System.currentTimeMillis()
      val stalledForMs = now - lastStorageProgressMs
      if (stalledForMs > StorageStagnationThreshold.toMillis) {
        log.warning(
          s"Storage coordinator reports 0 pending/0 active but never sent StorageRangeSyncComplete " +
            s"(stalled ${stalledForMs / 1000}s). Trie construction likely stuck. Force-completing."
        )
        storageForceCompleted = true
        storageRangeRequestTask.foreach(_.cancel()); storageRangeRequestTask = None
        // accountStagnationCheckTask holds the unified stagnation timer (scheduleStagnationChecks()
        // stores it there for both account and storage phases). storageStagnationCheckTask is never
        // assigned in scheduleStagnationChecks() so cancelling it is always a no-op.
        accountStagnationCheckTask.foreach(_.cancel()); accountStagnationCheckTask = None
        storageRangeCoordinator.foreach(_ ! actors.Messages.ForceCompleteStorage)
      }
      return
    }
    if (!workRemaining) return

    val now = System.currentTimeMillis()
    val stalledForMs = now - lastStorageProgressMs

    // Tail-stuck fast path: a small residual of accounts has been unservable for TailStagnationMs.
    // These are typically very large contracts whose storage trie exceeds per-request timeout limits.
    // No peer on any pivot can serve them — force-complete immediately to let trie healing recover them.
    val isTailStuck = stats.tasksPending > 0 &&
      stats.tasksPending < TailThreshold &&
      stalledForMs >= TailStagnationMs
    if (isTailStuck) {
      log.warning(
        s"Tail-stuck: ${stats.tasksPending} storage tasks unservable for ${stalledForMs / 1000}s. " +
          "Force-completing to trie healing phase."
      )
      storageForceCompleted = true
      storageRangeRequestTask.foreach(_.cancel()); storageRangeRequestTask = None
      accountStagnationCheckTask.foreach(_.cancel()); accountStagnationCheckTask = None
      storageRangeCoordinator.foreach(_ ! actors.Messages.ForceCompleteStorage)
      return
    }

    if (!storageStagnationRefreshAttempted) {
      // First stall: needs full threshold before triggering
      if (stalledForMs < StorageStagnationThreshold.toMillis) return
      if (now - lastPivotRestartMs < MinPivotRestartInterval.toMillis) return
      lastPivotRestartMs = now
      storageStagnationRefreshAttempted = true
      log.warning(
        s"Storage sync stalled: no progress for ${stalledForMs / 1000}s " +
          s"(threshold=${StorageStagnationThreshold.toSeconds}s). Attempting pivot refresh."
      )
      lastStorageProgressMs = now
      refreshPivotInPlace(s"storage stagnation: no progress for ${stalledForMs / 1000}s")
    } else {
      // Second stall after refresh: short grace period (2 min), then force-complete.
      // The pivot refresh either works quickly or not at all.
      val postRefreshGrace = 2.minutes
      if (stalledForMs < postRefreshGrace.toMillis) return
      log.warning(
        s"Storage sync stalled after pivot refresh: no progress for ${stalledForMs / 1000}s. " +
          s"Promoting to healing phase (preserving downloaded state)."
      )
      storageForceCompleted = true
      storageRangeRequestTask.foreach(_.cancel()); storageRangeRequestTask = None
      accountStagnationCheckTask.foreach(_.cancel()); accountStagnationCheckTask = None
      storageRangeCoordinator.foreach(_ ! actors.Messages.ForceCompleteStorage)
    }
  }

  /** Geth-aligned: check if all 3 concurrent download phases are complete. Only transitions to healing when accounts +
    * bytecodes + storage are ALL done. The sentinel pattern (NoMoreByteCodeTasks/NoMoreStorageTasks) ensures bytecodes
    * and storage cannot complete before accounts.
    */
  private def checkAllDownloadsComplete(): Unit =
    if (
      accountsComplete && bytecodePhaseComplete && storagePhaseComplete &&
      currentPhase != StateHealing && currentPhase != ChainDownloadCompletion && currentPhase != Completed
    ) {
      // Besu alignment: healing is always required before SNAP sync is considered complete.
      // The deferred-merkleization path (which skipped healing) has been removed — it caused
      // SnapSyncDone to be persisted before trie nodes were present, breaking regular sync.
      log.info("=" * 60)
      log.info("PHASE: State downloads complete — starting healing")
      log.info("=" * 60)
      if (snapSyncConfig.deferredMerkleization) {
        log.warning(
          "All state downloads complete. deferred-merkleization=true is set but healing is " +
            "required for correctness — forcing healing phase (Besu-aligned)."
        )
      } else {
        log.info("All state downloads complete (accounts + bytecodes + storage). Starting healing...")
      }
      currentPhase = StateHealing

      // Release bytecode coordinator and its worker children now that downloads are complete.
      // ByteCodeCoordinator is never stopped by ByteCodeSyncComplete alone, so we stop it
      // explicitly here to clean up worker child actors.
      bytecodeCoordinator.foreach(context.stop)
      bytecodeCoordinator = None

      // Besu alignment: startTrieHeal() calls pivotBlockSelector.switchToNewPivotBlock()
      // UNCONDITIONALLY before seeding healing. No staleness check — always refresh.
      // The pivot from refreshPivotInPlace() comes from the NETWORK (networkBest - offset),
      // not from local chain download progress. This ensures healing always starts from a
      // root within the SNAP serve window (~64 blocks from head).
      val currentPivot = pivotBlock.getOrElse(BigInt(0))
      // Save the state-download pivot before switching — used as fallback if the new pivot's
      // root is unservable by the ETC SNAP peer set after MaxHealingPivotRefreshes attempts.
      stateDownloadPivot = pivotBlock
      log.info(
        s"Pre-healing pivot switch: refreshing from pivot $currentPivot to network head " +
          s"before seeding healing coordinator " +
          s"(mirrors Besu startTrieHeal → switchToNewPivotBlock unconditionally). " +
          s"State-download pivot saved for fallback: $currentPivot"
      )
      refreshPivotInPlace("pre-healing pivot switch")
      // startStateHealing() called from completePivotRefreshWithStateRoot()
      // when currentPhase == StateHealing && trieNodeHealingCoordinator.isEmpty
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
      checkBootstrapRetryTimeout(s"bootstrap failed: $reason")
      val delay = bootstrapRetryDelay
      log.info(s"Retrying SNAP sync start in $delay (attempt $bootstrapRetryCount)")
      bootstrapCheckTask.foreach(_.cancel())
      bootstrapCheckTask = Some(
        scheduler.scheduleOnce(delay)(self ! RetrySnapSyncStart)(ec)
      )

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
      val savedStoragePath = appStateStorage.getSnapSyncStorageFilePath().filter(_.nonEmpty)

      (savedPivot, savedRootOpt) match {
        case (Some(pivot), Some(rootBs)) if pivot > 0 =>
          log.info(s"[SNAP-RECOVERY] Accounts previously completed at pivot $pivot. Checking freshness...")

          // Check if pivot is still fresh enough
          val networkBest = currentNetworkBestFromSnapPeers().getOrElse(BigInt(0))
          val drift = if (networkBest > 0) (networkBest - pivot).abs else BigInt(0)
          if (networkBest > 0 && drift > snapSyncConfig.maxPivotStalenessBlocks) {
            log.warning(
              s"[SNAP-RECOVERY] Pivot $pivot drifted $drift blocks from network best $networkBest. " +
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
            storageForceCompleted = false
            bytecodeForceCompleted = false
            lastBytecodeProgressCount = 0
            lastBytecodeProgressMs = System.currentTimeMillis()

            log.info(s"[SNAP-RECOVERY] Resuming bytecodes + storage sync from pivot $pivot (drift=$drift blocks)")

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
                    initialMaxInFlightPerPeer = 1, // Besu-aligned D11: 1 request per peer, no pipelining
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

            // Besu-aligned D11: 1 request per peer, no pipelining.
            bytecodeCoordinator.foreach(_ ! actors.Messages.UpdateMaxInFlightPerPeer(1))

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
                  .recover { case ex: Exception =>
                    log.warning(s"[SNAP-RECOVERY] Failed to stream storage tasks from $filePath: ${ex.getMessage}")
                    -1
                  }
                  .foreach { count =>
                    if (count >= 0)
                      log.info(s"[SNAP-RECOVERY] Streamed $count storage tasks from ${filePath}")
                    else
                      log.warning(
                        s"[SNAP-RECOVERY] Storage file read failed, proceeding without storage tasks from $filePath"
                      )
                    // Signal no more tasks — sentinel allows completion
                    coordinator ! actors.Messages.NoMoreStorageTasks
                  }
              } else {
                log.warning(s"[SNAP-RECOVERY] Storage file $filePath not found. Sending NoMore immediately.")
                storageRangeCoordinator.foreach(_ ! actors.Messages.NoMoreStorageTasks)
              }
            }
            if (savedStoragePath.isEmpty) {
              log.warning("[SNAP-RECOVERY] No storage file path persisted. Sending NoMore immediately.")
              storageRangeCoordinator.foreach(_ ! actors.Messages.NoMoreStorageTasks)
            }

            // Bytecodes: with inline dispatch, codeHashes were already sent to the old coordinator.
            // On recovery, we can't recover those. Send NoMore — the healing phase will catch any
            // missing bytecodes via trie walk.
            bytecodeCoordinator.foreach(_ ! actors.Messages.NoMoreByteCodeTasks)

            currentPhase = ByteCodeAndStorageSync
            lastStorageProgressMs = System.currentTimeMillis()
            scheduleStagnationChecks()
            progressMonitor.startPhase(ByteCodeAndStorageSync)

            // Start parallel chain download during recovery too
            startChainDownloader()

            context.become(syncing)
            return
          }
        case _ =>
          log.warning("[SNAP-RECOVERY] Accounts-complete flag set but missing pivot/root. Clearing and restarting fresh.")
          appStateStorage.putSnapSyncAccountsComplete(false).commit()
      }
    }

    // [SNAP-STATE] Log persisted state at startup for visibility into previous session progress
    {
      val savedPivot = appStateStorage.getSnapSyncPivotBlock()
      val snapDone = appStateStorage.isSnapSyncDone()
      val accountsDone = appStateStorage.isSnapSyncAccountsComplete()
      val bootstrapTarget = appStateStorage.getSnapSyncBootstrapTarget()
      log.info(
        s"[SNAP-STATE] DB state at startup: pivot=${savedPivot.getOrElse("none")}, " +
          s"snapDone=$snapDone, accountsComplete=$accountsDone, " +
          s"bootstrapTarget=${bootstrapTarget.getOrElse("none")}"
      )
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

          // Start account range sync with genesis state root
          currentPhase = AccountRangeSync
          startAccountRangeSync(genesisHeader.stateRoot)
          context.become(syncing)

        case None =>
          // Besu-aligned: genesis not available is a transient startup error; retry instead of falling back.
          log.error("Genesis block header not available — retrying SNAP sync start in 5s (Besu-aligned: no fallback)")
          scheduler.scheduleOnce(5.seconds, self, Start)(ec)
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

  /** Check if bootstrap retry has exceeded the maximum count. Besu-aligned: never fall back to fast sync. Reset counter
    * and keep retrying indefinitely.
    */
  private def checkBootstrapRetryTimeout(context: String): Boolean = {
    if (bootstrapRetryCount >= MaxBootstrapRetries) {
      log.warning(
        s"Reached max bootstrap retries ($bootstrapRetryCount, $context) — " +
          "Besu-aligned: resetting counter and continuing to retry (no fallback to fast sync)."
      )
      bootstrapRetryCount = 0
    }
    false // never signal fallback
  }

  /** Evict non-SNAP outgoing peers when SNAP peer count is below threshold.
    *
    * Core-Geth completes full SNAP sync in ~5 minutes because it connects to SNAP-capable peers rapidly. Fukuii's peer
    * slots can fill with non-SNAP peers (ETH-only), leaving no room for SNAP-capable peers to connect. This method
    * actively disconnects the oldest non-SNAP outgoing peers to free connection slots, allowing discovery to fill them
    * with SNAP-capable peers.
    */
  private def evictNonSnapPeers(): Unit = {
    val allPeers = handshakedPeers.values.toSeq
    val snapPeerCount = allPeers.count(_.peerInfo.remoteStatus.supportsSnap)
    val nonSnapOutgoing = allPeers
      .filter(p => !p.peerInfo.remoteStatus.supportsSnap && !p.peer.incomingConnection)
      .sortBy(_.peer.createTimeMillis) // oldest first

    if (snapPeerCount >= snapSyncConfig.minSnapPeers || nonSnapOutgoing.isEmpty) {
      log.debug(
        s"SNAP peer eviction: $snapPeerCount snap peers (need ${snapSyncConfig.minSnapPeers}), " +
          s"${nonSnapOutgoing.size} non-snap outgoing — no eviction needed"
      )
      return
    }

    val numToEvict = math.min(
      snapSyncConfig.maxEvictionsPerCycle,
      math.min(nonSnapOutgoing.size, snapSyncConfig.minSnapPeers - snapPeerCount)
    )

    if (numToEvict > 0) {
      log.info(
        s"SNAP peer eviction: only $snapPeerCount snap peers (need ${snapSyncConfig.minSnapPeers}), " +
          s"evicting $numToEvict of ${nonSnapOutgoing.size} non-snap outgoing peers to free slots for discovery"
      )
      nonSnapOutgoing.take(numToEvict).foreach { peerWithInfo =>
        log.info(
          s"Evicting non-SNAP peer ${peerWithInfo.peer.id} (${peerWithInfo.peer.remoteAddress}, " +
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
    // Before starting workers, check if any connected peer supports the snap/1 protocol.
    // If no peers support snap, the workers will send requests that are silently ignored,
    // stalling sync until the 3-minute stagnation watchdog fires. Instead, check upfront
    // and schedule a grace period for peers to connect before falling back to fast sync.
    val snapPeerCount = peersToDownloadFrom.count { case (_, p) =>
      p.peerInfo.remoteStatus.supportsSnap
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
            initialMaxInFlightPerPeer = 1, // Besu-aligned D11: 1 request per peer, no pipelining
            trieFlushThreshold = snapSyncConfig.accountTrieFlushThreshold,
            initialResponseBytes = snapSyncConfig.accountInitialResponseBytes,
            minResponseBytes = snapSyncConfig.accountMinResponseBytes
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

    scheduleStagnationChecks()

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
              initialMaxInFlightPerPeer =
                0, // Defer storage dispatch during AccountRangeSync — prevents false pivot refreshes from stale-root timeouts
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

    // ByteCode and storage start with budget=0 during account phase (tasks accumulate, dispatch deferred).
    // During AccountRangeSync: accounts=5, storage=0, bytecode=0 — avoids false pivot refreshes
    // from stale-root storage timeouts and gives accounts full per-peer bandwidth.
    bytecodeCoordinator.foreach(_ ! actors.Messages.UpdateMaxInFlightPerPeer(0))
    storageRangeCoordinator.foreach(_ ! actors.Messages.UpdateMaxInFlightPerPeer(0))

    progressMonitor.startPhase(AccountRangeSync)
  }

  // Internal message for periodic account range requests
  private case object RequestAccountRanges

  private def requestAccountRanges(): Unit =
    // Notify coordinator of available peers
    accountRangeCoordinator.foreach { coordinator =>
      val pivot = pivotBlock.getOrElse(BigInt(0))

      val snapPeers = peersToDownloadFrom.collect {
        case (_, peerWithInfo)
            if peerWithInfo.peerInfo.remoteStatus.supportsSnap && peerWithInfo.peerInfo.maxBlockNumber >= pivot =>
          peerWithInfo.peer
      }

      SNAPSyncMetrics.setSnapCapablePeers(snapPeers.size)

      if (snapPeers.isEmpty) {
        log.debug("No SNAP-capable peers available for account range requests")
      } else {
        snapPeers.foreach { peer =>
          coordinator ! actors.Messages.PeerAvailable(peer)
        }
      }
    }

  // Internal message for periodic bytecode requests
  private case object RequestByteCodes

  private def requestByteCodes(): Unit =
    // Notify coordinator of available peers
    bytecodeCoordinator.foreach { coordinator =>
      val snapPeers = peersToDownloadFrom.collect {
        case (peerId, peerWithInfo) if peerWithInfo.peerInfo.remoteStatus.supportsSnap =>
          peerWithInfo.peer
      }

      if (snapPeers.isEmpty) {
        log.debug("No SNAP-capable peers available for bytecode requests")
      } else {
        snapPeers.foreach { peer =>
          coordinator ! actors.Messages.ByteCodePeerAvailable(peer)
        }
      }
    }

  // Internal message for periodic storage range requests
  private case object RequestStorageRanges

  private def requestStorageRanges(): Unit =
    // Notify coordinator of available peers
    storageRangeCoordinator.foreach { coordinator =>
      val pivot = pivotBlock.getOrElse(BigInt(0))

      // maxBlockNumber==0 means "not yet known" for ETH/68 peers (no block number in ETH/68 Status).
      // Include them as candidates — if they can't serve the range they'll respond with an error.
      // Aligned with Besu: SNAP peer selection is isServingSnap() AND estimatedChainHeight >= pivot,
      // but ETH/68 peers with unknown height (0) are still attempted before their first header fetch.
      val snapPeers = peersToDownloadFrom.collect {
        case (peerId, peerWithInfo)
            if peerWithInfo.peerInfo.remoteStatus.supportsSnap &&
              (peerWithInfo.peerInfo.maxBlockNumber == 0 || peerWithInfo.peerInfo.maxBlockNumber >= pivot) =>
          peerWithInfo.peer
      }

      SNAPSyncMetrics.setSnapCapablePeers(snapPeers.size)

      if (snapPeers.isEmpty) {
        if (log.isDebugEnabled) {
          val peerDump = peersToDownloadFrom.values
            .take(5)
            .map { p =>
              s"${p.peer.remoteAddress}: snap=${p.peerInfo.remoteStatus.supportsSnap}, maxBlock=${p.peerInfo.maxBlockNumber}"
            }
            .mkString(", ")
          log.debug(s"peersToDownloadFrom (${peersToDownloadFrom.size} total): $peerDump")
        }
        log.info(s"No SNAP-capable peers at or above pivot $pivot available for storage range requests")
      } else {
        snapPeers.foreach { peer =>
          coordinator ! actors.Messages.StoragePeerAvailable(peer)
        }
      }
    }

  // Unified stagnation check — dispatches to the active coordinator based on current phase.
  override def aroundReceive(receive: Receive, msg: Any): Unit = msg match {
    case CheckDownloadStagnation =>
      import org.apache.pekko.pattern.{ask, pipe}
      import org.apache.pekko.util.Timeout
      implicit val timeout: Timeout = Timeout(2.seconds)
      log.debug(
        s"Stagnation check: phase=$currentPhase, stalledMs=${System.currentTimeMillis() - lastStorageProgressMs}"
      )

      currentPhase match {
        case AccountRangeSync =>
          accountRangeCoordinator.foreach { coordinator =>
            (coordinator ? actors.Messages.GetProgress)
              .mapTo[actors.AccountRangeStats]
              .map(AccountCoordinatorProgress.apply)
              .recover { case _ =>
                AccountCoordinatorProgress(
                  actors.AccountRangeStats(0L, 0L, 0, 0, 0, 0.0, 0L, 0)
                )
              }
              .pipeTo(self)
          }
        case ByteCodeAndStorageSync =>
          storageRangeCoordinator.foreach { coordinator =>
            (coordinator ? actors.Messages.StorageGetProgress)
              .mapTo[actors.StorageRangeCoordinator.SyncStatistics]
              .map(StorageCoordinatorProgress.apply)
              .recover { case _ =>
                StorageCoordinatorProgress(
                  actors.StorageRangeCoordinator.SyncStatistics(0, 0, 0, 0, 0, 0, 0.0)
                )
              }
              .pipeTo(self)
          }
          // Also poll bytecode coordinator so we can detect stagnation there.
          // If bytecodes stall (peers cooling down, workers timing out), ByteCodeSyncComplete
          // is never sent and healing is blocked forever without this check.
          if (!bytecodePhaseComplete && !bytecodeForceCompleted) {
            bytecodeCoordinator.foreach { coordinator =>
              (coordinator ? actors.Messages.ByteCodeGetProgress)
                .mapTo[actors.Messages.ByteCodeProgress]
                .map(ByteCodeCoordinatorProgress.apply)
                .recover { case _ =>
                  ByteCodeCoordinatorProgress(actors.Messages.ByteCodeProgress(0.0, lastBytecodeProgressCount, 0L))
                }
                .pipeTo(self)
            }
          }
        case _ => // No stagnation check needed in other phases
      }
      super.aroundReceive(receive, msg)

    case AccountCoordinatorProgress(progress) if currentPhase == AccountRangeSync =>
      if (
        progress.elapsedTimeMs > 0 || progress.tasksPending > 0 || progress.tasksActive > 0 || progress.tasksCompleted > 0
      )
        maybeRestartIfAccountStagnant(progress)
      super.aroundReceive(receive, msg)

    case StorageCoordinatorProgress(stats) if currentPhase == ByteCodeAndStorageSync =>
      log.info(
        s"Storage stagnation check: pending=${stats.tasksPending}, active=${stats.tasksActive}, " +
          s"completed=${stats.tasksCompleted}, stalledMs=${System.currentTimeMillis() - lastStorageProgressMs}, " +
          s"refreshAttempted=$storageStagnationRefreshAttempted"
      )
      maybeRestartIfStorageStagnant(stats)
      super.aroundReceive(receive, msg)

    case ByteCodeCoordinatorProgress(progress) if currentPhase == ByteCodeAndStorageSync =>
      if (progress.bytecodesDownloaded > lastBytecodeProgressCount) {
        lastBytecodeProgressCount = progress.bytecodesDownloaded
        lastBytecodeProgressMs = System.currentTimeMillis()
      } else {
        val stalledMs = System.currentTimeMillis() - lastBytecodeProgressMs
        if (stalledMs > BytecodeStagnationThreshold.toMillis && !bytecodeForceCompleted) {
          log.warning(
            s"Bytecode sync stalled for ${stalledMs / 1000}s with no progress " +
              s"(${progress.bytecodesDownloaded} downloaded, threshold=${BytecodeStagnationThreshold.toSeconds}s). " +
              s"Force-completing (healing will recover missing code)."
          )
          bytecodeForceCompleted = true
          bytecodeCoordinator.foreach(_ ! actors.Messages.ForceCompleteByteCode)
        }
      }
      super.aroundReceive(receive, msg)

    case _ =>
      super.aroundReceive(receive, msg)
  }

  // Internal message for async trie walk result.
  // walkedRoot records the pivot state root this walk was launched against.
  // The handler discards results from walks that completed after a pivot change (BUG-W1/W2).
  private case class TrieWalkResult(missingNodes: Seq[(Seq[ByteString], ByteString)], walkedRoot: ByteString)
  private case class TrieWalkFailed(error: String)
  private case object ScheduledTrieWalk

  /** Start an async trie walk to discover missing nodes. Guards against concurrent walks. */
  private def startTrieWalk(): Unit = {
    if (trieWalkInProgress) return
    if (currentPhase != StateHealing) return
    trieWalkInProgress = true
    walkStartedAt = Some(java.time.Instant.now())
    stateRoot.foreach { root =>
      log.info("Starting trie walk to discover missing nodes for healing...")
      val storage = getOrCreateMptStorage(pivotBlock.getOrElse(BigInt(0)))
      val selfRef = self
      // Capture the root this walk is running against. The walk runs on a separate thread
      // and may complete after a pivot change — the actor uses walkedRoot to discard stale results.
      val launchedForRoot = root
      scala.concurrent
        .Future {
          val validator = new StateValidator(storage)
          validator.findMissingNodesWithPaths(root)
        }(ec)
        .foreach {
          case Right(missingNodes) =>
            log.info(s"[WALK-FUTURE] Walk threads done: ${missingNodes.size} missing node(s) — sending TrieWalkResult")
            selfRef ! TrieWalkResult(missingNodes, launchedForRoot)
          case Left(error) =>
            log.error(s"[WALK-FUTURE] Walk threads returned error: $error — sending TrieWalkFailed")
            selfRef ! TrieWalkFailed(error)
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

    // Cancel all download-phase schedulers before entering healing (BUG-W5).
    // These fire every 1s and trigger CheckPivotStaleness → pivot staleness restart
    // at ~13-14 min into healing if left running. They have no useful work to do once
    // account/bytecode/storage download phases are complete.
    accountRangeRequestTask.foreach(_.cancel())
    accountRangeRequestTask = None
    bytecodeRequestTask.foreach(_.cancel())
    bytecodeRequestTask = None
    storageRangeRequestTask.foreach(_.cancel())
    storageRangeRequestTask = None
    log.debug("[SNAP-HEALING] Cancelled download-phase schedulers (accountRange/bytecode/storage) on StateHealing entry")

    trieWalkInProgress = false // Reset for fresh healing phase
    log.info("=" * 60)
    log.info("PHASE: Trie node healing starting")
    log.info("=" * 60)
    log.info(s"Starting state healing with batch size ${snapSyncConfig.healingBatchSize}")

    stateRoot.foreach { root =>
      log.info("Using actor-based concurrency for state healing")

      val storage = getOrCreateMptStorage(pivotBlock.getOrElse(BigInt(0)))

      coordinatorGeneration += 1
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

      // Start the coordinator — Besu-aligned D11: 1 request per peer, no pipelining.
      trieNodeHealingCoordinator.foreach { coordinator =>
        coordinator ! actors.Messages.StartTrieNodeHealing(root)
        coordinator ! actors.Messages.UpdateMaxInFlightPerPeer(1)
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

      // Besu DynamicPivotBlockSelector: 60s periodic staleness check during healing.
      // If pivot drifts >126 blocks behind chain tip, refresh pivot in-place.
      pivotStalenessCheckTask.foreach(_.cancel())
      pivotStalenessCheckTask = Some(
        scheduler.scheduleWithFixedDelay(
          PivotCheckInterval,
          PivotCheckInterval,
          self,
          CheckPivotStaleness
        )(ec)
      )

      progressMonitor.startPhase(StateHealing)

      // Run initial trie walk asynchronously to discover missing nodes
      startTrieWalk()
    }
  }

  // Internal message for periodic healing requests
  private case object RequestTrieNodeHealing

  private def requestTrieNodeHealing(): Unit =
    // Notify coordinator of available peers
    trieNodeHealingCoordinator.foreach { coordinator =>
      val snapPeers = peersToDownloadFrom.collect {
        case (peerId, peerWithInfo) if peerWithInfo.peerInfo.remoteStatus.supportsSnap =>
          peerWithInfo.peer
      }

      if (snapPeers.isEmpty) {
        log.debug("No SNAP-capable peers available for healing requests")
      } else {
        snapPeers.foreach { peer =>
          coordinator ! actors.Messages.HealingPeerAvailable(peer)
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
                // Finalize SNAP sync properly so regular sync has a valid best block anchor.
                // The bare snapSyncDone().commit() path was missing the pivot block body, chain
                // weight, and BestBlockInfo hash — causing ConsensusAdapter.getBestBlock() to
                // return None on every block import attempt. finalizeSnapSync() stores all of
                // them atomically (H-013) before sending Done to SyncController.
                finalizeSnapSync(pivot)
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
        log.error("Missing state root or pivot block for validation")
        log.error("Sync cannot complete - missing state root or pivot block")
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
      // Don't restart or fallback — SNAP peers are intermittent on ETC mainnet.
      // The serve window is ~28 min; peers will reappear when new blocks are mined.
      // Restarting can't help with no peers, and it destroys all downloaded trie data.
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
      log.info(
        s"Pivot refresh: new root $newRoot same as current — proceeding with healing " +
          s"(Besu: switchToNewPivotBlock fires callback even when newPivotBlockFound=false)"
      )
      // Stop coordinator if running; restart from same (confirmed-current) stateRoot.
      // DO NOT call restartSnapSync() — that destroys 5+ days of downloaded account data.
      if (currentPhase == StateHealing) {
        trieNodeHealingCoordinator.foreach(context.stop)
        trieNodeHealingCoordinator = None
        trieWalkInProgress = false
        consecutiveUnproductiveHealingRounds = 0
        startStateHealing()
      }
      return
    }

    log.info(s"Pivot refreshed: block $oldPivot -> $newPivotBlock, root $oldRoot -> $newRoot")

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
    // Besu alignment: reloadTrieHeal() clears ALL pending requests and restarts healing
    // from scratch with the fresh stateRoot. In-place update (HealingPivotRefreshed) leaves
    // stale in-flight requests against the old root outstanding — peers respond with
    // empty/error because the old root is outside their 64-block serve window, locking up workers.
    // Stop coordinator if running; Change 2 guard below restarts with fresh stateRoot.
    if (currentPhase == StateHealing) {
      trieNodeHealingCoordinator.foreach(context.stop)
      trieNodeHealingCoordinator = None
      trieWalkInProgress = false
      consecutiveUnproductiveHealingRounds = 0 // New pivot = new root, reset stagnation counter
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

    // Besu alignment: after any pivot refresh during the healing phase, restart the healing
    // coordinator with the fresh stateRoot if it is not already running. This covers:
    //
    //   (a) Pre-healing pivot switch (Gap 1): coordinator was never created because
    //       checkAllDownloadsComplete() deferred startStateHealing() above.
    //
    //   (b) Post-pivot restart (Gap 2): coordinator was stopped, pivot refreshed,
    //       healing must restart with the fresh stateRoot.
    //
    //   (c) This case no longer exists: coordinator is always hard-restarted (Besu D10 alignment)
    //       via the stop at L2249-2253 above, so trieNodeHealingCoordinator is always None here.
    if (currentPhase == StateHealing && trieNodeHealingCoordinator.isEmpty) {
      log.info(
        s"Restarting healing coordinator with fresh pivot=$newPivotBlock " +
          s"stateRoot=$newRoot (Besu startTrieHeal → switchToNewPivotBlock alignment)"
      )
      startStateHealing()
    }
  }

  // restartSnapSync() removed: Besu-aligned (D7). Besu never does a full restart from stagnation.
  // All pivot updates use refreshPivotInPlace() instead.

  /** Trigger healing by re-running the trie walk with path tracking. Called from validateState() when missing nodes are
    * discovered. The passed hashes are just an indicator — we re-walk to get proper paths for GetTrieNodes.
    */
  private def triggerHealingForMissingNodes(missingNodes: Seq[ByteString]): Unit = {
    log.info(s"Validation found ${missingNodes.size} missing nodes — re-running trie walk with paths for healing")
    currentPhase = StateHealing
    // Re-use startTrieWalk() so trieWalkInProgress, walkStartedAt, and walkedRoot tracking
    // all apply correctly (A1/A2/A7 guards). The walk will send TrieWalkResult back to self.
    startTrieWalk()
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

    // Check if the stall is due to no peers (not a processing failure).
    // On ETC mainnet, SNAP peer gaps of 30-60 minutes are normal.
    // Don't escalate when the root cause is simply waiting for peers.
    val snapPeerCount = handshakedPeers.values.count(_.peerInfo.remoteStatus.supportsSnap)
    if (snapPeerCount == 0) {
      log.info(
        s"Account sync stalled (${stalledForMs / 1000}s) but no SNAP peers available. " +
          s"Waiting for peers to reconnect (downloaded=${progress.accountsDownloaded})."
      )
      // Don't increment stall counter — this is a peer availability issue, not a sync failure.
      // Reset the stall timer so we don't immediately escalate when peers return.
      lastAccountProgressMs = System.currentTimeMillis()
      return
    }

    consecutiveAccountStallRefreshes += 1

    val context =
      s"account range sync stalled: no download progress for ${stalledForMs / 1000}s " +
        s"(threshold=${AccountStagnationThreshold.toSeconds}s), accountsDownloaded=${progress.accountsDownloaded}, " +
        s"tasksPending=${progress.tasksPending}, tasksActive=${progress.tasksActive}, snapPeers=$snapPeerCount"

    // In-place pivot refresh to try to find a serveable root.
    // Never restart or fallback — restartSnapSync() destroys all downloaded data,
    // and fast sync doesn't work on ETH68 networks.
    log.warning(
      s"Account stall detected ($context). " +
        s"Refreshing pivot in-place to recover (attempt $consecutiveAccountStallRefreshes). " +
        s"Account data preserved."
    )
    lastAccountProgressMs = System.currentTimeMillis()
    refreshPivotInPlace(s"account stall: $context")
  }

  /** After MaxHealingPivotRefreshes consecutive pivot refreshes all fail to serve the root node, attempt to finalize at
    * the state-download pivot whose state IS in the MPT DB. This is the last-resort path when the ETC SNAP peer set
    * cannot serve any recent pivot's root.
    */
  private def attemptHealingFallbackToStateDownloadPivot(): Unit =
    stateDownloadPivot match {
      case Some(savedPivot) =>
        blockchainReader.getBlockHeaderByNumber(savedPivot) match {
          case Some(header) =>
            val readonlyMpt = stateStorage.getReadOnlyStorage
            val rootExists =
              try { readonlyMpt.get(header.stateRoot.toArray); true }
              catch { case _: Exception => false }
            if (rootExists) {
              log.warning(
                s"[HEAL-FALLBACK] Root unservable after $MaxHealingPivotRefreshes pivot refreshes. " +
                  s"Falling back to state-download pivot $savedPivot " +
                  s"(root ${header.stateRoot.take(4).toArray.map("%02x".format(_)).mkString} IS in DB). " +
                  s"Regular sync will start from $savedPivot with valid state."
              )
              pivotBlock = Some(savedPivot)
              finalizeSnapSync(savedPivot)
            } else {
              log.warning(
                s"[HEAL-FALLBACK] State-download pivot $savedPivot root also missing. " +
                  s"Resetting heal counter and retrying pivot refresh."
              )
              healingPivotRefreshes = 0
              refreshPivotInPlace("heal fallback — state-download root missing, retrying")
            }
          case None =>
            log.warning(
              s"[HEAL-FALLBACK] State-download pivot $savedPivot header not found. " +
                s"Resetting heal counter and retrying pivot refresh."
            )
            healingPivotRefreshes = 0
            refreshPivotInPlace("heal fallback — state-download pivot header missing")
        }
      case None =>
        log.warning("[HEAL-FALLBACK] No state-download pivot recorded. Resetting counter and retrying.")
        healingPivotRefreshes = 0
        refreshPivotInPlace("heal fallback — no saved pivot")
    }

  private def completeSnapSync(): Unit =
    pivotBlock.foreach { pivot =>
      // Besu-aligned D2: pivot header was stored by updateBestBlockForPivot during bootstrap.
      // finalizeSnapSync only requires getBlockHeaderByNumber(pivot) — available immediately.
      // Do NOT wait for chain download to complete (genesis→pivot = ~22M blocks = days).
      // Regular sync handles blocks from pivot forward. Chain downloader is stopped by
      // finalizeSnapSync. This matches Besu's isBlockchainBehind() pattern: once the pivot
      // header is available, the node is not "behind" and can proceed to regular sync.
      log.info(
        s"SNAP state sync complete. Finalizing SNAP sync at pivot $pivot " +
          "(Besu-aligned D2: no chain download wait — pivot header available from bootstrap)."
      )
      finalizeSnapSync(pivot)
    }

  /** Final SNAP sync completion — called when both state sync and chain download are done. */
  private def finalizeSnapSync(pivot: BigInt): Unit = {
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
        appStateStorage
          .snapSyncDone()
          .and(blockchainWriter.storeBlock(Block(pivotHeader, BlockBody.empty)))
          .and(
            blockchainWriter.storeChainWeight(
              pivotHash,
              ChainWeight.totalDifficultyOnly(estimatedTotalDifficulty)
            )
          )
          .and(
            appStateStorage.putBestBlockInfo(
              com.chipprbots.ethereum.domain.appstate.BlockInfo(pivotHash, pivot)
            )
          )
          .commit()

        log.info("=" * 60)
        log.info(s"PHASE: SNAP sync complete at block $pivot")
        log.info("=" * 60)
        log.info(s"SNAP sync completed successfully at block $pivot (hash=${pivotHash.take(8).toHex})")

      case None =>
        // Fallback: shouldn't happen since PivotHeaderBootstrap stored the header
        log.warning(s"Pivot header for block $pivot not found in storage — setting best block number only")
        appStateStorage
          .snapSyncDone()
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

  /** Waiting for parallel chain download to finish after SNAP state sync completed.
    *
    * NOTE (Besu-aligned D2): completeSnapSync() no longer enters this state. finalizeSnapSync() is called immediately
    * when SNAP state sync completes, without waiting for chain download. This state is retained for compatibility with
    * any external callers but should never be entered in normal operation.
    */
  def waitingForChainDownload: Receive = handlePeerListMessagesWithRateTracking.orElse {
    case ChainDownloader.Done =>
      log.info("Parallel chain download complete. Finalizing SNAP sync.")
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
  case object ByteCodeAndStorageSync extends SyncPhase
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
  case object FallbackToFastSync // Signal to fallback to fast sync due to repeated failures
  case class StartRegularSyncBootstrap(targetBlock: BigInt) // Request bootstrap from SyncController
  final case class BootstrapComplete(
      pivotHeader: Option[BlockHeader] = None
  ) // Signal from SyncController that bootstrap is done
  final case class PivotBootstrapFailed(
      reason: String
  ) // Signal from SyncController that pivot header bootstrap exhausted retries
  private case object RetrySnapSyncStart // Internal message to retry SNAP sync start after bootstrap
  /** Sent by AccountRangeCoordinator when account range download is fully complete.
    *
    * @param totalCodeHashes
    *   number of unique codeHashes dispatched during account download (Bloom-filtered). Forwarded to
    *   ByteCodeCoordinator as SetExpectedByteCodeCount so it can verify receipt of all AddByteCodeTasks batches before
    *   declaring completion.
    */
  case class AccountRangeSyncComplete(totalCodeHashes: Long)
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
  case object GetProgress
  // Periodic actor liveness probe — fires every 60s independent of walk state.
  private case object ActorLivenessProbe

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
    storageBatchSize: Int = 384,
    storageInitialResponseBytes: Int = 1048576,
    storageMinResponseBytes: Int = 131072,
    healingBatchSize: Int = 384,
    healingConcurrency: Int = 16,
    stateValidationEnabled: Boolean = true,
    maxRetries: Int = 3,
    timeout: FiniteDuration = 10.seconds,
    // Grace period after bootstrap to wait for snap/1-capable peers before rescheduling.
    // Besu-aligned: no fallback to fast sync — reschedule indefinitely until snap peers appear.
    snapCapabilityGracePeriod: FiniteDuration = 30.seconds,
    // Account stagnation timeout: if no account range tasks complete within this window,
    // Besu-aligned: stall triggers in-place pivot refresh only, never fallback.
    accountStagnationTimeout: FiniteDuration = 10.minutes,
    maxInFlightPerPeer: Int = 1, // Besu-aligned D11: 1 request per peer, no pipelining
    accountTrieFlushThreshold: Int = 50000,
    accountInitialResponseBytes: Int = 524288,
    accountMinResponseBytes: Int = 102400,
    chainDownloadEnabled: Boolean = true,
    chainDownloadMaxConcurrentRequests: Int = 2,
    // Besu-aligned D14: no boosted concurrency mode. Single concurrency throughout.
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
      // Besu-aligned D14: chainDownloadBoostedConcurrentRequests removed.
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

// StateValidator has been extracted to StateValidator.scala
// SyncProgressMonitor and SyncProgress have been extracted to SyncProgressMonitor.scala
