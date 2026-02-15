package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.actor.{Actor, ActorLogging, ActorRef, Props, Scheduler, Cancellable}
import org.apache.pekko.util.ByteString

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext
import scala.collection.mutable

import com.chipprbots.ethereum.blockchain.sync.{Blacklist, CacheBasedBlacklist, PeerListSupportNg, SyncProtocol}
import com.chipprbots.ethereum.blockchain.sync.Blacklist.BlacklistReason._
import com.chipprbots.ethereum.db.storage.{AppStateStorage, EvmCodeStorage, MptStorage, StateStorage}
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.p2p.messages.SNAP
import com.chipprbots.ethereum.network.p2p.messages.SNAP._
import com.chipprbots.ethereum.utils.{Config, Logger}
import com.chipprbots.ethereum.utils.Config.SyncConfig
import com.chipprbots.ethereum.utils.ByteStringUtils.ByteStringOps

class SNAPSyncController(
    blockchainReader: BlockchainReader,
    appStateStorage: AppStateStorage,
    stateStorage: StateStorage,
    evmCodeStorage: EvmCodeStorage,
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

  // Monotonic counter appended to coordinator actor names so restarts don't collide
  // with still-stopping actors from the previous cycle.
  private var coordinatorGeneration: Long = 0

  private val requestTracker = new SNAPRequestTracker()(scheduler)

  private var currentPhase: SyncPhase = Idle
  private var pivotBlock: Option[BigInt] = None
  private var stateRoot: Option[ByteString] = None

  // Guards to prevent duplicate phase-starts when upstream coordinators emit duplicate completion signals.
  private var bytecodeSyncStarting: Boolean = false
  private var storageRangeSyncStarting: Boolean = false

  // Contract accounts collected for bytecode download
  private var contractAccounts = Seq.empty[(ByteString, ByteString)]
  private var contractStorageAccounts = Seq.empty[(ByteString, ByteString)]

  // Internal message used to deliver contract-account query results back through the actor mailbox.
  private case class ContractAccountsReady(
      bytecodeAccounts: Seq[(ByteString, ByteString)],
      storageAccounts: Seq[(ByteString, ByteString)]
  )

  private val progressMonitor = new SyncProgressMonitor(scheduler)

  // Failure tracking for fallback to fast sync
  private var criticalFailureCount: Int = 0
  
  // Retry counter for validation failures to prevent infinite loops
  private var validationRetryCount: Int = 0
  private val MaxValidationRetries = 3
  private val ValidationRetryDelay = 500.millis

  // Retry counter for bootstrap-to-SNAP transition
  private var bootstrapRetryCount: Int = 0
  private val BootstrapRetryDelay = 2.seconds

  // Pivot restart guard (prevents noisy rapid restarts if peer head fluctuates)
  private var lastPivotRestartMs: Long = 0L
  private val MinPivotRestartInterval: FiniteDuration = 30.seconds

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
  private var bootstrapCheckTask: Option[Cancellable] = None

  // Storage stagnation watchdog: if storage stops advancing while tasks remain, repivot/restart.
  // This addresses the common case where peers no longer serve the chosen pivot/state window.
  // Threshold must be generous enough to allow large chains to complete within the SNAP serve window.
  private val StorageStagnationThreshold: FiniteDuration = 20.minutes
  private val StorageStagnationCheckInterval: FiniteDuration = 30.seconds
  private var lastStorageProgressMs: Long = System.currentTimeMillis()

  // AccountRange stagnation watchdog: if tasks stop completing, repivot/restart (and possibly fallback).
  // Threshold must be generous enough to allow large chains to complete within the SNAP serve window
  // (~128 blocks * ~13s = ~28 min on ETC). Too aggressive causes infinite restart loops.
  private val AccountStagnationThreshold: FiniteDuration = 15.minutes
  private val AccountStagnationCheckInterval: FiniteDuration = 30.seconds
  private var lastAccountProgressMs: Long = System.currentTimeMillis()
  private var lastAccountTasksCompleted: Int = 0

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

  def syncing: Receive = handlePeerListMessages.orElse {
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
      // AccountRange can legitimately produce empty segments (count=0) while still progressing.
      // Treat any progress update as a liveness signal.
      lastAccountProgressMs = System.currentTimeMillis()

    case ProgressBytecodesDownloaded(count) =>
      progressMonitor.incrementBytecodesDownloaded(count)

    case ProgressStorageSlotsSynced(count) =>
      progressMonitor.incrementStorageSlotsSynced(count)
      lastStorageProgressMs = System.currentTimeMillis()

    case ProgressNodesHealed(count) =>
      progressMonitor.incrementNodesHealed(count)

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
      } else if (currentPhase == AccountRangeSync || currentPhase == StorageRangeSync) {
        lastPivotRestartMs = now
        refreshPivotInPlace(reason)
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
          completePivotRefreshWithStateRoot(pendingPivot, header.stateRoot, reason)
        case None =>
          log.warning(s"Pivot header bootstrap for block $pendingPivot returned no header. Falling back to full restart.")
          restartSnapSync(s"pivot refresh bootstrap returned no header for $pendingPivot: $reason")
      }

    // Note: phase transitions are driven by the *start* methods (e.g. startBytecodeSync)
    // to keep phase-start logging idempotent and centralized.

    case AccountRangeSyncComplete =>
      if (storageRangeSyncStarting || currentPhase != AccountRangeSync) {
        log.info(
          s"Ignoring AccountRangeSyncComplete in phase=$currentPhase (storageRangeSyncStarting=$storageRangeSyncStarting)"
        )
      } else {
        storageRangeSyncStarting = true
        log.info("Account range sync complete. Starting storage range sync...")

        import org.apache.pekko.pattern.{ ask, pipe }
        import org.apache.pekko.util.Timeout
        import scala.concurrent.duration._
        implicit val timeout: Timeout = Timeout(5.seconds)

        accountRangeCoordinator match {
          case Some(coordinator) =>
            val bytecodeAccountsF = (coordinator ? actors.Messages.GetContractAccounts)
              .mapTo[actors.Messages.ContractAccountsResponse]
              .map(_.accounts)

            val storageAccountsF = (coordinator ? actors.Messages.GetContractStorageAccounts)
              .mapTo[actors.Messages.ContractStorageAccountsResponse]
              .map(_.accounts)

            (for {
              bytecodeAccounts <- bytecodeAccountsF
              storageAccounts <- storageAccountsF
            } yield ContractAccountsReady(bytecodeAccounts, storageAccounts))
              .recover { case ex =>
                log.warning(s"Failed to query contract accounts for bytecode/storage sync: ${ex.getMessage}")
                ContractAccountsReady(Seq.empty, Seq.empty)
              }
              .pipeTo(self)
          case None =>
            self ! ContractAccountsReady(Seq.empty, Seq.empty)
        }
      }

    case ContractAccountsReady(bytecodeAccounts, storageAccounts) =>
      if (currentPhase != AccountRangeSync) {
        log.info(s"Ignoring ContractAccountsReady in phase=$currentPhase")
      } else {
        contractAccounts = bytecodeAccounts
        contractStorageAccounts = storageAccounts
        log.info(
          s"Collected ${contractAccounts.size} contract accounts for bytecode sync and ${contractStorageAccounts.size} for storage sync from coordinator"
        )
        currentPhase = ByteCodeSync
        startBytecodeSync()
      }

    case ByteCodeSyncComplete =>
      if (currentPhase != ByteCodeSync) {
        log.info(s"Ignoring ByteCodeSyncComplete in phase=$currentPhase")
      } else {
        log.info("ByteCode sync complete. Starting storage range sync...")
        currentPhase = StorageRangeSync
        startStorageRangeSync()
      }

    case StorageRangeSyncComplete =>
      if (currentPhase != StorageRangeSync) {
        log.info(s"Ignoring StorageRangeSyncComplete in phase=$currentPhase")
      } else {
        log.info("Storage range sync complete. Starting state healing...")
        currentPhase = StateHealing
        startStateHealing()
      }

    case StateHealingComplete =>
      log.info("State healing complete. Validating state...")
      progressMonitor.startPhase(StateValidation)
      currentPhase = StateValidation
      validateState()

    case StateValidationComplete =>
      log.info("State validation complete. SNAP sync finished!")
      completeSnapSync()

    case SyncProtocol.GetStatus =>
      sender() ! currentSyncStatus

    case GetProgress =>
      sender() ! progressMonitor.currentProgress

    case msg =>
      log.debug(s"Unhandled message in syncing state: $msg")
  }

  // Internal message for periodic storage stagnation checks
  private case object CheckStorageStagnation

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
    if (currentPhase != StorageRangeSync) return

    // Only consider it a stall if there is still work to do.
    val workRemaining = stats.tasksPending > 0 || stats.tasksActive > 0
    if (!workRemaining) return

    val now = System.currentTimeMillis()
    val stalledForMs = now - lastStorageProgressMs
    if (stalledForMs < StorageStagnationThreshold.toMillis) return

    // Avoid noisy rapid restarts.
    if (now - lastPivotRestartMs < MinPivotRestartInterval.toMillis) return
    lastPivotRestartMs = now

    restartSnapSync(
      s"storage sync stalled: no storage slot progress for ${stalledForMs / 1000}s " +
        s"(threshold=${StorageStagnationThreshold.toSeconds}s), tasksPending=${stats.tasksPending}, tasksActive=${stats.tasksActive}"
    )
  }

  // Internal message for periodic account stagnation checks
  private case object CheckAccountStagnation

  private case class AccountCoordinatorProgress(progress: actors.AccountRangeProgress)

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

  def bootstrapping: Receive = handlePeerListMessages.orElse {
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
    log.info(s"SNAP pivot selection: bootstrapPivot=$bootstrapPivotBlock, visible peer heights (top 5) = [$topPeerHeights]")
    
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
          log.warning("No peers available yet; network height is unknown. Deferring SNAP start until peers are available")
          log.warning(s"Retrying SNAP start in $BootstrapRetryDelay... (attempt $bootstrapRetryCount)")

          bootstrapCheckTask.foreach(_.cancel())
          bootstrapCheckTask = Some(
            scheduler.scheduleOnce(BootstrapRetryDelay) {
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
          
          SNAPSyncMetrics.setPivotBlockNumber(0)
          
          // Reset bootstrap retry counter
          bootstrapRetryCount = 0
          
          // Start account range sync with genesis state root
          currentPhase = AccountRangeSync
          startAccountRangeSync(genesisHeader.stateRoot)
          context.become(syncing)
          
        case None =>
          log.error("Genesis block header not available - cannot start SNAP sync")
          context.parent ! FallbackToFastSync
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
      log.warning(s"Pivot source: ${pivotSelectionSource.name}, base block: $baseBlockForPivot, offset: ${snapSyncConfig.pivotBlockOffset}")
      
      // LocalPivot is only used when no peers are available (see match expression above)
      // This check determines whether to retry for peers or transition to regular sync
      pivotSelectionSource match {
        case LocalPivot =>
          // No peers available - schedule retry
          bootstrapRetryCount += 1
          log.warning(s"No peers available for pivot selection. Retrying in $BootstrapRetryDelay... (attempt $bootstrapRetryCount)")

          bootstrapCheckTask.foreach(_.cancel())
          bootstrapCheckTask = Some(
            scheduler.scheduleOnce(BootstrapRetryDelay) {
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
          context.parent ! Done  // Signal completion, which will transition to regular sync
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

          // Update metrics - pivot block
          SNAPSyncMetrics.setPivotBlockNumber(pivotBlockNumber)
              log.info(s"   Pivot block $pivotBlockNumber not ready yet (attempt ${bootstrapRetryCount + 1})")

              bootstrapRetryCount += 1
              log.info(s"   Retrying in $BootstrapRetryDelay...")

              // Schedule a retry and store the cancellable for proper cleanup
              bootstrapCheckTask.foreach(_.cancel())
              bootstrapCheckTask = Some(
                scheduler.scheduleOnce(BootstrapRetryDelay) {
                  self ! RetrySnapSyncStart
                }(ec)
              )

              // Transition to bootstrapping state to handle the retry
              context.become(bootstrapping)

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
              // This is the original race condition case - retry
              if (bootstrapRetryCount == 0) {
                log.info("⏳ Waiting for pivot block header to become available...")
              }
              log.info(s"   Pivot block $pivotBlockNumber not ready yet (attempt ${bootstrapRetryCount + 1})")

              bootstrapRetryCount += 1
              log.info(s"   Retrying in $BootstrapRetryDelay...")

              // Schedule a retry and store the cancellable for proper cleanup
              bootstrapCheckTask.foreach(_.cancel())
              bootstrapCheckTask = Some(
                scheduler.scheduleOnce(BootstrapRetryDelay) {
                  self ! RetrySnapSyncStart
                }(ec)
              )

              // Transition to bootstrapping state to handle the retry
              context.become(bootstrapping)
          }
      }
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

  /** Check if circuit breaker is open and trigger fallback if needed. This is a reusable helper for checking circuit
    * breaker state across different download types (account range, storage, bytecode, healing).
    *
    * @param circuitName
    *   The name of the circuit to check
    * @param errorContext
    *   Context about the error for logging
    * @return
    *   true if fallback to fast sync was triggered (caller should return early)
    */
  private def checkCircuitBreakerAndTriggerFallback(circuitName: String, errorContext: String): Boolean =
    if (errorHandler.isCircuitOpen(circuitName)) {
      if (recordCriticalFailure(s"Circuit breaker open for $circuitName: $errorContext")) {
        fallbackToFastSync()
        true
      } else {
        false
      }
    } else {
      false
    }

  /** Trigger fallback to fast sync due to repeated SNAP sync failures */
  private def fallbackToFastSync(): Unit = {
    log.warning("Triggering fallback to fast sync due to repeated SNAP sync failures")

    // Cancel all scheduled tasks
    accountRangeRequestTask.foreach(_.cancel())
    bytecodeRequestTask.foreach(_.cancel())
    storageRangeRequestTask.foreach(_.cancel())
    healingRequestTask.foreach(_.cancel())

    // Stop progress monitoring
    progressMonitor.stopPeriodicLogging()

    // Notify parent controller to switch to fast sync
    context.parent ! FallbackToFastSync
    context.become(completed)
  }

  private def startAccountRangeSync(rootHash: ByteString): Unit = {
    log.info(s"Starting account range sync with concurrency ${snapSyncConfig.accountConcurrency}")
    log.info("Using actor-based concurrency for account range sync")

    // Reset stagnation tracking for this phase.
    lastAccountProgressMs = System.currentTimeMillis()
    lastAccountTasksCompleted = 0
    
    val storage = getOrCreateMptStorage(pivotBlock.getOrElse(BigInt(0)))

    accountRangeCoordinator = Some(
      context.actorOf(
        actors.AccountRangeCoordinator.props(
          stateRoot = rootHash,
          networkPeerManager = networkPeerManager,
          requestTracker = requestTracker,
          mptStorage = storage,
          concurrency = snapSyncConfig.accountConcurrency,
          snapSyncController = self
        ),
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
  }

  private def startBytecodeSync(): Unit = {
    log.info(s"Starting bytecode sync with batch size ${ByteCodeTask.DEFAULT_BATCH_SIZE}")

    if (bytecodeCoordinator.nonEmpty) {
      log.warning("Bytecode sync already started (bytecodeCoordinator is defined); skipping duplicate start")
      return
    }

    if (contractAccounts.isEmpty) {
      log.info("No contract accounts found, skipping bytecode sync")
      self ! ByteCodeSyncComplete
      return
    }

    log.info(s"Found ${contractAccounts.size} contract accounts for bytecode download")
    log.info("Using actor-based concurrency for bytecode sync")
    
    bytecodeCoordinator = Some(
      context.actorOf(
        actors.ByteCodeCoordinator.props(
          evmCodeStorage = evmCodeStorage,
          networkPeerManager = networkPeerManager,
          requestTracker = requestTracker,
          batchSize = ByteCodeTask.DEFAULT_BATCH_SIZE,
          snapSyncController = self
        ),
        s"bytecode-coordinator-$coordinatorGeneration"
      )
    )
    
    // Start the coordinator with contract accounts
    bytecodeCoordinator.foreach(_ ! actors.Messages.StartByteCodeSync(contractAccounts))
    
    // Periodically send peer availability notifications
    bytecodeRequestTask = Some(
      scheduler.scheduleWithFixedDelay(
        0.seconds,
        1.second,
        self,
        RequestByteCodes
      )(ec)
    )

    progressMonitor.startPhase(ByteCodeSync)
  }

  // Internal message for periodic bytecode requests
  private case object RequestByteCodes

  private def requestByteCodes(): Unit = {
    if (maybeRestartIfPivotTooStale("ByteCodeSync")) return
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
  }

  private def startStorageRangeSync(): Unit = {
    log.info(s"Starting storage range sync with concurrency ${snapSyncConfig.storageConcurrency}")

    if (storageRangeCoordinator.nonEmpty) {
      log.warning("Storage range sync already started (storageRangeCoordinator is defined); skipping duplicate start")
      return
    }

    stateRoot.foreach { root =>
      log.info("Using actor-based concurrency for storage range sync")

      val storage = getOrCreateMptStorage(pivotBlock.getOrElse(BigInt(0)))

      storageRangeCoordinator = Some(
        context.actorOf(
          actors.StorageRangeCoordinator.props(
            stateRoot = root,
            networkPeerManager = networkPeerManager,
            requestTracker = requestTracker,
            mptStorage = storage,
            maxAccountsPerBatch = snapSyncConfig.storageBatchSize,
            maxInFlightRequests = snapSyncConfig.storageConcurrency,
            requestTimeout = snapSyncConfig.timeout,
            snapSyncController = self
          ),
          s"storage-range-coordinator-$coordinatorGeneration"
        )
      )
      
      // Start the coordinator
      storageRangeCoordinator.foreach(_ ! actors.Messages.StartStorageRangeSync(root))

      // Enqueue initial storage tasks derived from contract accounts found during AccountRangeSync.
      val emptyRoot = ByteString(com.chipprbots.ethereum.mpt.MerklePatriciaTrie.EmptyRootHash)
      val (nonEmptyStorage, emptyStorage) = contractStorageAccounts.partition { case (_, storageRoot) =>
        storageRoot.nonEmpty && storageRoot != emptyRoot
      }
      if (emptyStorage.nonEmpty) {
        log.info(s"Skipping ${emptyStorage.size} contract accounts with empty storageRoot")
      }

      val storageTasks = StorageTask.createStorageTasks(nonEmptyStorage)
      if (storageTasks.isEmpty) {
        log.warning(
          "No contract accounts with non-empty storageRoot; completing StorageRangeSync immediately"
        )
        self ! StorageRangeSyncComplete
      } else {
        log.info(s"Enqueuing ${storageTasks.size} storage tasks (non-empty storageRoot only)")
        storageRangeCoordinator.foreach(_ ! actors.Messages.AddStorageTasks(storageTasks))
      }
      
      // Periodically send peer availability notifications
      storageRangeRequestTask = Some(
        scheduler.scheduleWithFixedDelay(
          0.seconds,
          1.second,
          self,
          RequestStorageRanges
        )(ec)
      )

      // Start/refresh storage stagnation watchdog state.
      lastStorageProgressMs = System.currentTimeMillis()
      scheduleStorageStagnationChecks()

      progressMonitor.startPhase(StorageRangeSync)
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
            if peerWithInfo.peerInfo.remoteStatus.supportsSnap && peerWithInfo.peerInfo.maxBlockNumber >= pivot =>
          peerWithInfo.peer
      }

      SNAPSyncMetrics.setSnapCapablePeers(snapPeers.size)

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
          import org.apache.pekko.pattern.{ ask, pipe }
          import org.apache.pekko.util.Timeout
          implicit val timeout: Timeout = Timeout(2.seconds)

          (coordinator ? actors.Messages.GetProgress)
            .mapTo[actors.AccountRangeProgress]
            .map(AccountCoordinatorProgress.apply)
            .recover { case ex =>
              log.debug(s"Account stagnation check failed to query coordinator: ${ex.getMessage}")
              AccountCoordinatorProgress(
                actors.AccountRangeProgress(
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
      if (progress.elapsedTimeMs > 0 || progress.tasksPending > 0 || progress.tasksActive > 0 || progress.tasksCompleted > 0)
        maybeRestartIfAccountStagnant(progress)
      super.aroundReceive(receive, msg)

    case CheckStorageStagnation if currentPhase == StorageRangeSync =>
      storageRangeCoordinator match {
        case Some(coordinator) =>
          import org.apache.pekko.pattern.{ ask, pipe }
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

    case StorageCoordinatorProgress(stats) if currentPhase == StorageRangeSync =>
      // Ignore the dummy stats used on recover.
      if (stats.elapsedTimeMs > 0 || stats.tasksPending > 0 || stats.tasksActive > 0 || stats.tasksCompleted > 0)
        maybeRestartIfStorageStagnant(stats)
      super.aroundReceive(receive, msg)

    case _ =>
      super.aroundReceive(receive, msg)
  }

  private def startStateHealing(): Unit = {
    log.info(s"Starting state healing with batch size ${snapSyncConfig.healingBatchSize}")

    stateRoot.foreach { root =>
      log.info("Using actor-based concurrency for state healing")

      val storage = getOrCreateMptStorage(pivotBlock.getOrElse(BigInt(0)))

      trieNodeHealingCoordinator = Some(
        context.actorOf(
          actors.TrieNodeHealingCoordinator.props(
            stateRoot = root,
            networkPeerManager = networkPeerManager,
            requestTracker = requestTracker,
            mptStorage = storage,
            batchSize = snapSyncConfig.healingBatchSize,
            snapSyncController = self
          ),
          s"trie-node-healing-coordinator-$coordinatorGeneration"
        )
      )
      
      // Start the coordinator
      trieNodeHealingCoordinator.foreach(_ ! actors.Messages.StartTrieNodeHealing(root))
      
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
    }
  }

  // Internal message for periodic healing requests
  private case object RequestTrieNodeHealing

  private def requestTrieNodeHealing(): Unit = {
    if (maybeRestartIfPivotTooStale("StateHealing")) return
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

        // Validate account trie and collect missing nodes
        validator.validateAccountTrie(expectedRoot) match {
          case Right(missingAccountNodes) if missingAccountNodes.isEmpty =>
            log.info("Account trie validation successful - no missing nodes")
            
            // Reset validation retry counter on success
            validationRetryCount = 0

            // Now validate storage tries
            validator.validateAllStorageTries(expectedRoot) match {
              case Right(missingStorageNodes) if missingStorageNodes.isEmpty =>
                log.info("Storage trie validation successful - no missing nodes")
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
                log.error(s"Root node missing error persists (failed $validationRetryCount times total)")
                log.error("Maximum validation retries exceeded - falling back to fast sync")
                if (recordCriticalFailure("Root node persistence failure after retries")) {
                  fallbackToFastSync()
                }
              } else {
                log.error(s"Root node is missing (retry attempt $validationRetryCount of $MaxValidationRetries)")
                log.info("Retrying validation after brief delay...")
                // Retry validation after a short delay
                scheduler.scheduleOnce(ValidationRetryDelay) {
                  self ! StateHealingComplete
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
    * 1. Selects a fresher pivot from current network best
    * 2. Updates internal pivot/stateRoot tracking
    * 3. Sends PivotRefreshed to the active coordinator
    * 4. Resets stagnation timer so the watchdog doesn't trigger
    *
    * Downloaded trie nodes are content-addressed (keyed by keccak256 hash),
    * so ~99.9% remain valid across pivot changes. Root mismatch (if any)
    * is resolved during the healing phase.
    */
  private def refreshPivotInPlace(reason: String): Unit = {
    log.info(s"Refreshing pivot in-place: $reason")

    // Select a new pivot from the current network best
    val newPivotOpt = currentNetworkBestFromSnapPeers().map { networkBest =>
      networkBest - snapSyncConfig.pivotBlockOffset
    }.filter(_ > 0)

    if (newPivotOpt.isEmpty) {
      log.warning("Cannot refresh pivot: no suitable SNAP peers available. Falling back to full restart.")
      restartSnapSync(s"pivot refresh failed (no new pivot): $reason")
      return
    }

    val newPivotBlock = newPivotOpt.get
    val newStateRootOpt = blockchainReader.getBlockHeaderByNumber(newPivotBlock)
      .map(_.stateRoot)

    if (newStateRootOpt.isEmpty) {
      // Header not available locally — request it from a peer via the bootstrap mechanism.
      // The coordinator stays alive (all peers are stateless, so no dispatch will happen).
      // When BootstrapComplete arrives in the syncing state, the refresh is completed.
      log.info(s"Pivot header for block $newPivotBlock not available locally. Requesting header bootstrap...")
      pendingPivotRefresh = Some((newPivotBlock, reason))
      context.parent ! StartRegularSyncBootstrap(newPivotBlock)
      // Reset stagnation timers while we wait for the header
      lastAccountProgressMs = System.currentTimeMillis()
      lastStorageProgressMs = System.currentTimeMillis()
      return
    }

    completePivotRefreshWithStateRoot(newPivotBlock, newStateRootOpt.get, reason)
  }

  /** Complete the pivot refresh once we have the state root (either from local header or bootstrapped header). */
  private def completePivotRefreshWithStateRoot(newPivotBlock: BigInt, newStateRoot: ByteString, reason: String): Unit = {
    val oldPivot = pivotBlock.getOrElse(BigInt(0))
    val oldRoot = stateRoot.map(_.take(4).toHex).getOrElse("none")
    val newRoot = newStateRoot.take(4).toHex

    if (stateRoot.contains(newStateRoot)) {
      log.warning(s"Pivot refresh: new root $newRoot is same as old. Falling back to full restart.")
      restartSnapSync(s"pivot refresh produced same root ($newRoot): $reason")
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
    SNAPSyncMetrics.setPivotBlockNumber(newPivotBlock)

    // Send refresh signal to active coordinator
    if (currentPhase == AccountRangeSync) {
      accountRangeCoordinator.foreach(_ ! actors.Messages.PivotRefreshed(newStateRoot))
    } else if (currentPhase == StorageRangeSync) {
      storageRangeCoordinator.foreach(_ ! actors.Messages.StoragePivotRefreshed(newStateRoot))
    }

    // Reset stagnation timers so watchdogs don't trigger during recovery
    lastAccountProgressMs = System.currentTimeMillis()
    lastStorageProgressMs = System.currentTimeMillis()
  }

  private def restartSnapSync(reason: String): Unit = {
    log.warning(s"Restarting SNAP sync with a fresher pivot: $reason")

    // Clear any pending pivot refresh (we're doing a full restart instead)
    pendingPivotRefresh = None

    // Cancel periodic phase request ticks
    accountRangeRequestTask.foreach(_.cancel()); accountRangeRequestTask = None
    bytecodeRequestTask.foreach(_.cancel()); bytecodeRequestTask = None
    storageRangeRequestTask.foreach(_.cancel()); storageRangeRequestTask = None
    accountStagnationCheckTask.foreach(_.cancel()); accountStagnationCheckTask = None
    storageStagnationCheckTask.foreach(_.cancel()); storageStagnationCheckTask = None
    healingRequestTask.foreach(_.cancel()); healingRequestTask = None
    bootstrapCheckTask.foreach(_.cancel()); bootstrapCheckTask = None

    // Stop coordinators so we don't double-run phases
    accountRangeCoordinator.foreach(context.stop); accountRangeCoordinator = None
    bytecodeCoordinator.foreach(context.stop); bytecodeCoordinator = None
    storageRangeCoordinator.foreach(context.stop); storageRangeCoordinator = None
    trieNodeHealingCoordinator.foreach(context.stop); trieNodeHealingCoordinator = None

    // Clear inflight request timeouts and internal phase state
    requestTracker.clear()

    // Clear phase start guards and cached contract accounts
    bytecodeSyncStarting = false
    storageRangeSyncStarting = false
    contractAccounts = Seq.empty
    contractStorageAccounts = Seq.empty

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

  /** Trigger healing for a list of missing node hashes */
  private def triggerHealingForMissingNodes(missingNodes: Seq[ByteString]): Unit = {
    log.info(s"Queueing ${missingNodes.size} missing nodes for healing")

    // Add missing nodes to the healing coordinator
    trieNodeHealingCoordinator match {
      case Some(coordinator) =>
        // Send missing nodes to coordinator for healing
        coordinator ! actors.Messages.QueueMissingNodes(missingNodes)

        // Transition back to healing phase
        currentPhase = StateHealing
        log.info(s"Transitioning to StateHealing phase to heal ${missingNodes.size} missing nodes")

        // Start healing requests
        scheduler.scheduleOnce(100.millis) {
          self ! RequestTrieNodeHealing
        }(ec)
      case None =>
        log.error("Cannot heal missing nodes - TrieNodeHealingCoordinator not initialized")
        log.info("Restarting healing phase to initialize coordinator")
        currentPhase = StateHealing
        startStateHealing()
        
        // After coordinator is started, queue the nodes
        scheduler.scheduleOnce(500.millis) {
          trieNodeHealingCoordinator.foreach { coordinator =>
            coordinator ! actors.Messages.QueueMissingNodes(missingNodes)
          }
        }(ec)
    }
  }

  /** Convert SNAP sync progress to SyncProtocol.Status for eth_syncing RPC endpoint.
    * 
    * Returns syncing status with:
    * - startingBlock: The block number we started syncing from
    * - currentBlock: The pivot block we're syncing state for
    * - highestBlock: The pivot block (same as current for SNAP sync)
    * - knownStates: Estimated total state nodes based on current phase
    * - pulledStates: Number of state nodes synced so far
    */
  private def currentSyncStatus: SyncProtocol.Status = {
    val progress = progressMonitor.currentProgress
    val startingBlock = appStateStorage.getSyncStartingBlock()
    val currentBlock = pivotBlock.getOrElse(startingBlock)
    
    /** Helper to get estimate or actual count, whichever is larger.
      * Used as a defensive fallback when estimates are not yet available (0).
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
        val known = progress.estimatedTotalAccounts + estimateOrActual(progress.estimatedTotalBytecodes, progress.bytecodesDownloaded)
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
      
      case Idle | Completed =>
        (0L, 0L)
    }
    
    SyncProtocol.Status.Syncing(
      startingBlockNumber = startingBlock,
      blocksProgress = SyncProtocol.Status.Progress(currentBlock, currentBlock),
      stateNodesProgress = Some(SyncProtocol.Status.Progress(BigInt(pulledStates), BigInt(knownStates)))
    )
  }

  private def maybeRestartIfAccountStagnant(progress: actors.AccountRangeProgress): Unit = {
    if (currentPhase != AccountRangeSync) return

    // Only consider it a stall if there is still work to do.
    val workRemaining = progress.tasksPending > 0 || progress.tasksActive > 0
    if (!workRemaining) return

    // Update liveness based on task completions (even if accountsDownloaded stays flat).
    if (progress.tasksCompleted > lastAccountTasksCompleted) {
      lastAccountTasksCompleted = progress.tasksCompleted
      lastAccountProgressMs = System.currentTimeMillis()
      return
    }

    val now = System.currentTimeMillis()
    val stalledForMs = now - lastAccountProgressMs
    if (stalledForMs < AccountStagnationThreshold.toMillis) return

    // Avoid noisy rapid restarts.
    if (now - lastPivotRestartMs < MinPivotRestartInterval.toMillis) return
    lastPivotRestartMs = now

    val context =
      s"account range sync stalled: no task completions for ${stalledForMs / 1000}s " +
        s"(threshold=${AccountStagnationThreshold.toSeconds}s), accountsDownloaded=${progress.accountsDownloaded}, " +
        s"tasksPending=${progress.tasksPending}, tasksActive=${progress.tasksActive}"

    if (recordCriticalFailure(context)) {
      fallbackToFastSync()
    } else {
      restartSnapSync(context)
    }
  }

  private def completeSnapSync(): Unit =
    pivotBlock.foreach { pivot =>
      appStateStorage.snapSyncDone().commit()
      appStateStorage.putBestBlockNumber(pivot).commit()

      progressMonitor.complete()

      log.info(s"SNAP sync completed successfully at block $pivot")
      log.info(progressMonitor.currentProgress.toString)

      context.become(completed)
      context.parent ! Done
    }
}

object SNAPSyncController {

  sealed trait SyncPhase
  case object Idle extends SyncPhase
  case object AccountRangeSync extends SyncPhase
  case object ByteCodeSync extends SyncPhase
  case object StorageRangeSync extends SyncPhase
  case object StateHealing extends SyncPhase
  case object StateValidation extends SyncPhase
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
  final case class BootstrapComplete(pivotHeader: Option[BlockHeader] = None) // Signal from SyncController that bootstrap is done
  private case object RetrySnapSyncStart // Internal message to retry SNAP sync start after bootstrap
  case object AccountRangeSyncComplete
  case object ByteCodeSyncComplete
  case object StorageRangeSyncComplete
  case object StateHealingComplete
  case object StateValidationComplete
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
  final case class ProgressBytecodesDownloaded(count: Long)
  final case class ProgressStorageSlotsSynced(count: Long)
  final case class ProgressNodesHealed(count: Long)

  def props(
      blockchainReader: BlockchainReader,
      appStateStorage: AppStateStorage,
      stateStorage: StateStorage,
      evmCodeStorage: EvmCodeStorage,
      networkPeerManager: ActorRef,
      peerEventBus: ActorRef,
      syncConfig: SyncConfig,
      snapSyncConfig: SNAPSyncConfig,
      scheduler: Scheduler
  )(implicit ec: ExecutionContext): Props =
    Props(
      new SNAPSyncController(
        blockchainReader,
        appStateStorage,
        stateStorage,
        evmCodeStorage,
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
    storageConcurrency: Int = 8,
    storageBatchSize: Int = 8,
    healingBatchSize: Int = 16,
    stateValidationEnabled: Boolean = true,
    maxRetries: Int = 3,
    timeout: FiniteDuration = 30.seconds,
    maxSnapSyncFailures: Int = 5 // Max failures before fallback to fast sync
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
      healingBatchSize = snapConfig.getInt("healing-batch-size"),
      stateValidationEnabled = snapConfig.getBoolean("state-validation-enabled"),
      maxRetries = snapConfig.getInt("max-retries"),
      timeout = snapConfig.getDuration("timeout").toMillis.millis,
      maxSnapSyncFailures =
        if (snapConfig.hasPath("max-snap-sync-failures"))
          snapConfig.getInt("max-snap-sync-failures")
        else 5
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
  ): Unit = {
    // Get the hash of the current node
    val nodeHash = ByteString(node.hash)

    // Skip if already visited (prevent infinite loops)
    if (visited.contains(nodeHash)) {
      return
    }
    visited += nodeHash

    node match {
      case _: LeafNode =>
        // Leaf nodes have no children, done
        ()

      case ext: ExtensionNode =>
        // Extension nodes have one child
        traverseForMissingNodes(ext.next, storage, missingNodes, visited)

      case branch: BranchNode =>
        // Branch nodes have up to 16 children
        branch.children.foreach { child =>
          traverseForMissingNodes(child, storage, missingNodes, visited)
        }

      case hash: HashNode =>
        // Hash node - need to resolve it from storage
        try {
          val resolvedNode = storage.get(hash.hash)
          traverseForMissingNodes(resolvedNode, storage, missingNodes, visited)
        } catch {
          case _: MerklePatriciaTrie.MissingNodeException =>
            // Node is missing, record it
            missingNodes += ByteString(hash.hash)
          case e: Exception =>
            // Unexpected error - don't add to missing nodes as this indicates a more serious issue
            ()
        }

      case NullNode =>
        // Null nodes have no children, done
        ()
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

    // Get the hash of the current node
    val nodeHash = ByteString(node.hash)

    // Skip if already visited (prevent infinite loops)
    if (visited.contains(nodeHash)) {
      return
    }
    visited += nodeHash

    node match {
      case leaf: LeafNode =>
        // Leaf node contains an account
        try {
          val account = accountSerializer.fromBytes(leaf.value.toArray)
          accounts += account
        } catch {
          case e: Exception =>
            // Failed to deserialize - skip this leaf
            ()
        }

      case ext: ExtensionNode =>
        // Extension nodes point to the next node
        collectAccounts(ext.next, storage, accounts, visited)

      case branch: BranchNode =>
        // Branch nodes have up to 16 children
        branch.children.foreach { child =>
          collectAccounts(child, storage, accounts, visited)
        }
        // Branch node can also have a value (account) at its terminator
        branch.terminator.foreach { value =>
          try {
            val account = accountSerializer.fromBytes(value.toArray)
            accounts += account
          } catch {
            case e: Exception =>
              // Failed to deserialize - skip this terminator
              ()
          }
        }

      case hash: HashNode =>
        // Hash node - need to resolve it from storage
        try {
          val resolvedNode = storage.get(hash.hash)
          collectAccounts(resolvedNode, storage, accounts, visited)
        } catch {
          case _: MerklePatriciaTrie.MissingNodeException =>
            // Cannot traverse further if node is missing
            ()
          case e: Exception =>
            // Unexpected error - this indicates a more serious issue than just missing nodes
            ()
        }

      case NullNode =>
        // Null nodes have no children, done
        ()
    }
  }
}

class SyncProgressMonitor(_scheduler: Scheduler) extends Logger {

  import SNAPSyncController._
  import scala.concurrent.ExecutionContext.Implicits.global

  private var currentPhaseState: SyncPhase = Idle
  private var accountsSynced: Long = 0
  private var bytecodesDownloaded: Long = 0
  private var storageSlotsSynced: Long = 0
  private var nodesHealed: Long = 0
  private val startTime: Long = System.currentTimeMillis()
  private var phaseStartTime: Long = System.currentTimeMillis()

  // Estimated totals for ETA calculation (updated during sync)
  private var estimatedTotalAccounts: Long = 0
  private var estimatedTotalBytecodes: Long = 0
  private var estimatedTotalSlots: Long = 0

  // Periodic logging
  private var lastLogTime: Long = System.currentTimeMillis()
  private val logInterval: Long = 30000 // Log every 30 seconds
  private var periodicLogTask: Option[Cancellable] = None

  // Metrics history for throughput averaging
  private val metricsWindow = 60 // 60 seconds window for averaging
  private val accountsHistory = mutable.Queue[(Long, Long)]() // (timestamp, count)
  private val bytecodesHistory = mutable.Queue[(Long, Long)]()
  private val slotsHistory = mutable.Queue[(Long, Long)]()
  private val nodesHistory = mutable.Queue[(Long, Long)]()

  /** Start periodic progress logging */
  def startPeriodicLogging(): Unit =
    periodicLogTask = Some(_scheduler.scheduleAtFixedRate(30.seconds, 30.seconds) { () =>
      logProgress()
    })

  /** Stop periodic progress logging */
  def stopPeriodicLogging(): Unit = {
    periodicLogTask.foreach(_.cancel())
    periodicLogTask = None
  }

  /** Reset all counters/ETA state for a fresh SNAP attempt */
  def reset(): Unit = synchronized {
    currentPhaseState = Idle
    accountsSynced = 0
    bytecodesDownloaded = 0
    storageSlotsSynced = 0
    nodesHealed = 0

    estimatedTotalAccounts = 0
    estimatedTotalBytecodes = 0
    estimatedTotalSlots = 0

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
      case StorageRangeSync if estimatedTotalSlots > 0 =>
        (storageSlotsSynced.toDouble / estimatedTotalSlots * 100).toInt
      case _ => 0
    }

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
      phaseStartTime = phaseStartTime
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
    phaseStartTime: Long
) {

  private def wormChasesBrainBar: String = {
    import SNAPSyncController._

    // Global “stage” progress across phases; within-stage progress uses `phaseProgress` when available.
    val stages = Vector[SyncPhase](
      AccountRangeSync,
      ByteCodeSync,
      StorageRangeSync,
      StateHealing,
      StateValidation,
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

    val trackLen = 18
    val wormPos = math.max(0, math.min(trackLen, math.round(globalProgress * trackLen).toInt))
    val remaining = trackLen - wormPos

    val headroom = " " * wormPos
    val distance = "-" * remaining
    s"[$headroom🪱$distance🧠]"
  }

  override def toString: String =
    s"SNAP Sync Progress: phase=$phase, accounts=$accountsSynced (${accountsPerSec.toInt}/s), " +
      s"bytecodes=$bytecodesDownloaded (${bytecodesPerSec.toInt}/s), " +
      s"slots=$storageSlotsSynced (${slotsPerSec.toInt}/s), nodes=$nodesHealed (${nodesPerSec.toInt}/s), " +
      s"elapsed=${elapsedSeconds.toInt}s"

  def formattedString: String =
    phase match {
      case SNAPSyncController.AccountRangeSync =>
        val progressStr = if (estimatedTotalAccounts > 0) s" (${phaseProgress}%)" else ""
        s"phase=AccountRange$progressStr, accounts=$accountsSynced@${recentAccountsPerSec.toInt}/s, elapsed=${elapsedSeconds.toInt}s ${wormChasesBrainBar}"

      case SNAPSyncController.ByteCodeSync =>
        val progressStr = if (estimatedTotalBytecodes > 0) s" (${phaseProgress}%)" else ""
        s"phase=ByteCode$progressStr, codes=$bytecodesDownloaded@${recentBytecodesPerSec.toInt}/s, elapsed=${elapsedSeconds.toInt}s ${wormChasesBrainBar}"

      case SNAPSyncController.StorageRangeSync =>
        val progressStr = if (estimatedTotalSlots > 0) s" (${phaseProgress}%)" else ""
        s"phase=Storage$progressStr, slots=$storageSlotsSynced@${recentSlotsPerSec.toInt}/s, elapsed=${elapsedSeconds.toInt}s ${wormChasesBrainBar}"

      case SNAPSyncController.StateHealing =>
        s"phase=Healing, nodes=$nodesHealed@${recentNodesPerSec.toInt}/s, elapsed=${elapsedSeconds.toInt}s ${wormChasesBrainBar}"

      case SNAPSyncController.StateValidation =>
        s"phase=Validation, elapsed=${elapsedSeconds.toInt}s ${wormChasesBrainBar}"

      case _ =>
        s"phase=$phase, elapsed=${elapsedSeconds.toInt}s ${wormChasesBrainBar}"
    }
}
