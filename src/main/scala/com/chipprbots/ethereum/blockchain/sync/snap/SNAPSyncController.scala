package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.actor.{Actor, ActorLogging, ActorRef, Props, Scheduler, Cancellable}
import org.apache.pekko.util.ByteString

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext
import scala.collection.mutable

import com.chipprbots.ethereum.blockchain.sync.{Blacklist, CacheBasedBlacklist, PeerListSupportNg, SyncProtocol}
import com.chipprbots.ethereum.blockchain.sync.Blacklist.BlacklistReason._
import com.chipprbots.ethereum.db.storage.{AppStateStorage, EvmCodeStorage, MptStorage}
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
    mptStorage: MptStorage,
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

  private val requestTracker = new SNAPRequestTracker()(scheduler)

  private var currentPhase: SyncPhase = Idle
  private var pivotBlock: Option[BigInt] = None
  private var stateRoot: Option[ByteString] = None

  // Contract accounts collected for bytecode download
  private var contractAccounts = Seq.empty[(ByteString, ByteString)]

  private val progressMonitor = new SyncProgressMonitor(scheduler)

  // Failure tracking for fallback to fast sync
  private var criticalFailureCount: Int = 0
  
  // Retry counter for validation failures to prevent infinite loops
  private var validationRetryCount: Int = 0
  private val MaxValidationRetries = 3
  private val ValidationRetryDelay = 500.millis

  // Retry counter for bootstrap-to-SNAP transition
  private var bootstrapRetryCount: Int = 0
  private val MaxBootstrapRetries = 10
  private val BootstrapRetryDelay = 2.seconds

  // Scheduled tasks for periodic peer requests
  private var accountRangeRequestTask: Option[Cancellable] = None
  private var bytecodeRequestTask: Option[Cancellable] = None
  private var storageRangeRequestTask: Option[Cancellable] = None
  private var healingRequestTask: Option[Cancellable] = None
  private var bootstrapCheckTask: Option[Cancellable] = None

  override def preStart(): Unit = {
    log.info("SNAP Sync Controller initialized")
    progressMonitor.startPeriodicLogging()
  }

  override def postStop(): Unit = {
    // Cancel all scheduled tasks
    accountRangeRequestTask.foreach(_.cancel())
    bytecodeRequestTask.foreach(_.cancel())
    storageRangeRequestTask.foreach(_.cancel())
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

      // Actor-based: forward message to all account range workers
      // The workers will check if the requestId matches their current task
      context.children.foreach { child =>
        child ! actors.Messages.AccountRangeResponseMsg(msg)
      }

    case msg: ByteCodes =>
      log.debug(s"Received ByteCodes response: requestId=${msg.requestId}, codes=${msg.codes.size}")

      // Forward message to all bytecode workers
      context.children.foreach { child =>
        child ! actors.Messages.ByteCodesResponseMsg(msg)
      }

    case msg: StorageRanges =>
      log.debug(s"Received StorageRanges response: requestId=${msg.requestId}, slots=${msg.slots.size}")

      // Actor-based: forward message to all storage workers
      context.children.foreach { child =>
        child ! actors.Messages.StorageRangesResponseMsg(msg)
      }

    case msg: TrieNodes =>
      log.debug(s"Received TrieNodes response: requestId=${msg.requestId}, nodes=${msg.nodes.size}")

      // Forward message to all healing workers
      context.children.foreach { child =>
        child ! actors.Messages.TrieNodesResponseMsg(msg)
      }

    case AccountRangeSyncComplete =>
      log.info("Account range sync complete. Starting bytecode sync...")
      
      // Query the coordinator for contract accounts
      import org.apache.pekko.pattern.ask
      import org.apache.pekko.util.Timeout
      import scala.concurrent.duration._
      implicit val timeout: Timeout = Timeout(5.seconds)
      
      accountRangeCoordinator.foreach { coordinator =>
        import context.dispatcher
        (coordinator ? actors.Messages.GetContractAccounts).mapTo[actors.Messages.ContractAccountsResponse].foreach { response =>
          contractAccounts = response.accounts
          log.info(s"Collected ${contractAccounts.size} contract accounts for bytecode sync from coordinator")
          // Proceed with bytecode sync
          progressMonitor.startPhase(ByteCodeSync)
          currentPhase = ByteCodeSync
          startBytecodeSync()
        }
      }

    case ByteCodeSyncComplete =>
      log.info("ByteCode sync complete. Starting storage range sync...")
      progressMonitor.startPhase(StorageRangeSync)
      currentPhase = StorageRangeSync
      startStorageRangeSync()

    case StorageRangeSyncComplete =>
      log.info("Storage range sync complete. Starting state healing...")
      progressMonitor.startPhase(StateHealing)
      currentPhase = StateHealing
      startStateHealing()

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

  def bootstrapping: Receive = handlePeerListMessages.orElse {
    case BootstrapComplete =>
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
      
      // If we have a bootstrap target and we've reached it, use that as our pivot
      // This ensures we use the pivot we bootstrapped to, not a recalculated one
      bootstrapTarget match {
        case Some(targetPivot) if localBestBlock >= targetPivot =>
          // We successfully reached the bootstrap target - use it as our pivot
          log.info(s"Using bootstrap target $targetPivot as pivot (we synced to it)")
          
          // Try to get the header for this pivot block
          blockchainReader.getBlockHeaderByNumber(targetPivot) match {
            case Some(header) =>
              // Perfect! We have the header for our target pivot
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
              
              // Start account range sync
              currentPhase = AccountRangeSync
              startAccountRangeSync(header.stateRoot)
              context.become(syncing)
              
            case None =>
              // Header not available yet - this shouldn't happen but handle it gracefully
              log.warning(s"Bootstrap target $targetPivot reached but header not available yet")
              log.warning("Falling back to recalculating pivot from current network state")
              startSnapSync()
          }
          
        case Some(targetPivot) =>
          // We have a target but haven't reached it yet - this shouldn't happen
          log.warning(s"Bootstrap incomplete: target=$targetPivot, current=$localBestBlock")
          log.warning("Falling back to recalculating pivot from current network state")
          startSnapSync()
          
        case None =>
          // No bootstrap target stored - fall back to normal pivot selection
          log.info("No bootstrap target stored - calculating pivot from network state")
          startSnapSync()
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
        
        if (bestBlockNumber >= bootstrapTarget) {
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
    
    // Query peers to find the highest block in the network
    val networkBestBlockOpt = getPeerWithHighestBlock.map(_.peerInfo.maxBlockNumber)
    
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

    // Core-geth behavior: If chain height <= pivot offset, use genesis as pivot
    // This allows SNAP sync to start immediately from any height
    if (baseBlockForPivot <= snapSyncConfig.pivotBlockOffset) {
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
          if (bootstrapRetryCount < MaxBootstrapRetries) {
            bootstrapRetryCount += 1
            log.warning(s"No peers available for pivot selection. Retrying in $BootstrapRetryDelay... (attempt ${bootstrapRetryCount}/$MaxBootstrapRetries)")
            
            bootstrapCheckTask = Some(
              scheduler.scheduleOnce(BootstrapRetryDelay) {
                self ! RetrySnapSyncStart
              }(ec)
            )
            context.become(bootstrapping)
            return
          } else {
            log.error("Max retries exceeded waiting for peers. Falling back to fast sync.")
            bootstrapRetryCount = 0
            context.parent ! FallbackToFastSync
            return
          }
        
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
      
      // Check if we have the pivot block header locally
      blockchainReader.getBlockHeaderByNumber(pivotBlockNumber) match {
        case Some(header) =>
          // Pivot header is available - proceed with SNAP sync
          pivotBlock = Some(pivotBlockNumber)

          // Update metrics - pivot block
          SNAPSyncMetrics.setPivotBlockNumber(pivotBlockNumber)
          
          stateRoot = Some(header.stateRoot)
          appStateStorage.putSnapSyncPivotBlock(pivotBlockNumber).commit()
          appStateStorage.putSnapSyncStateRoot(header.stateRoot).commit()

          log.info("=" * 80)
          log.info("🎯 SNAP Sync Ready")
          log.info("=" * 80)
          log.info(s"Local best block: $localBestBlock")
          networkBestBlockOpt.foreach(netBest => log.info(s"Network best block: $netBest"))
          log.info(s"Selected pivot block: $pivotBlockNumber (source: ${pivotSelectionSource.name})")
          log.info(s"Pivot offset: ${snapSyncConfig.pivotBlockOffset} blocks")
          log.info(s"State root: ${header.stateRoot.toHex.take(16)}...")
          log.info(s"Beginning fast state sync with ${snapSyncConfig.accountConcurrency} concurrent workers")
          log.info("=" * 80)

          // Reset bootstrap retry counter on success
          bootstrapRetryCount = 0

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
              // This is the original race condition case - retry
              if (bootstrapRetryCount == 0) {
                log.info("⏳ Waiting for pivot block header to become available...")
              }
              log.info(s"   Pivot block $pivotBlockNumber not ready yet (attempt ${bootstrapRetryCount + 1}/$MaxBootstrapRetries)")
              
              if (bootstrapRetryCount < MaxBootstrapRetries) {
                bootstrapRetryCount += 1
                log.info(s"   Retrying in $BootstrapRetryDelay...")
                
                // Schedule a retry and store the cancellable for proper cleanup
                bootstrapCheckTask = Some(
                  scheduler.scheduleOnce(BootstrapRetryDelay) {
                    self ! RetrySnapSyncStart
                  }(ec)
                )
                
                // Transition to bootstrapping state to handle the retry
                context.become(bootstrapping)
              } else {
                log.error("=" * 80)
                log.error(s"❌ Pivot block header not available after $MaxBootstrapRetries retries")
                log.error("   SNAP sync cannot proceed - falling back to fast sync")
                log.error("=" * 80)
                bootstrapRetryCount = 0
                context.parent ! FallbackToFastSync
              }
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
    
    accountRangeCoordinator = Some(
      context.actorOf(
        actors.AccountRangeCoordinator.props(
          stateRoot = rootHash,
          networkPeerManager = networkPeerManager,
          requestTracker = requestTracker,
          mptStorage = mptStorage,
          concurrency = snapSyncConfig.accountConcurrency,
          snapSyncController = self
        ),
        "account-range-coordinator"
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

    progressMonitor.startPhase(AccountRangeSync)
  }

  // Internal message for periodic account range requests
  private case object RequestAccountRanges

  private def requestAccountRanges(): Unit = {
    // Notify coordinator of available peers
    accountRangeCoordinator.foreach { coordinator =>
      val snapPeers = peersToDownloadFrom.collect {
        case (peerId, peerWithInfo) if peerWithInfo.peerInfo.remoteStatus.supportsSnap =>
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
        "bytecode-coordinator"
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

    stateRoot.foreach { root =>
      log.info("Using actor-based concurrency for storage range sync")
      
      storageRangeCoordinator = Some(
        context.actorOf(
          actors.StorageRangeCoordinator.props(
            stateRoot = root,
            networkPeerManager = networkPeerManager,
            requestTracker = requestTracker,
            mptStorage = mptStorage,
            maxAccountsPerBatch = snapSyncConfig.storageBatchSize,
            snapSyncController = self
          ),
          "storage-range-coordinator"
        )
      )
      
      // Start the coordinator
      storageRangeCoordinator.foreach(_ ! actors.Messages.StartStorageRangeSync(root))
      
      // Periodically send peer availability notifications
      storageRangeRequestTask = Some(
        scheduler.scheduleWithFixedDelay(
          0.seconds,
          1.second,
          self,
          RequestStorageRanges
        )(ec)
      )

      progressMonitor.startPhase(StorageRangeSync)
    }
  }

  // Internal message for periodic storage range requests
  private case object RequestStorageRanges

  private def requestStorageRanges(): Unit = {
    // Notify coordinator of available peers
    storageRangeCoordinator.foreach { coordinator =>
      val snapPeers = peersToDownloadFrom.collect {
        case (peerId, peerWithInfo) if peerWithInfo.peerInfo.remoteStatus.supportsSnap =>
          peerWithInfo.peer
      }

      if (snapPeers.isEmpty) {
        log.debug("No SNAP-capable peers available for storage range requests")
      } else {
        snapPeers.foreach { peer =>
          coordinator ! actors.Messages.StoragePeerAvailable(peer)
        }
      }
    }
  }

  private def startStateHealing(): Unit = {
    log.info(s"Starting state healing with batch size ${snapSyncConfig.healingBatchSize}")

    stateRoot.foreach { root =>
      log.info("Using actor-based concurrency for state healing")
      
      trieNodeHealingCoordinator = Some(
        context.actorOf(
          actors.TrieNodeHealingCoordinator.props(
            stateRoot = root,
            networkPeerManager = networkPeerManager,
            requestTracker = requestTracker,
            mptStorage = mptStorage,
            batchSize = snapSyncConfig.healingBatchSize,
            snapSyncController = self
          ),
          "trie-node-healing-coordinator"
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
        val validator = new StateValidator(mptStorage)

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
    
    // Calculate state progress based on current phase
    // SNAP sync involves multiple phases: accounts, bytecode, storage, healing
    val (pulledStates, knownStates) = currentPhase match {
      case AccountRangeSync =>
        // In account range sync, we track accounts synced
        // Use max() as a fallback when estimates are not yet available (0)
        (progress.accountsSynced, progress.estimatedTotalAccounts.max(progress.accountsSynced))
      
      case ByteCodeSync =>
        // In bytecode sync, we add accounts + bytecodes
        val pulled = progress.accountsSynced + progress.bytecodesDownloaded
        // Use max() as a fallback when estimates are not yet available (0)
        val known = progress.estimatedTotalAccounts + progress.estimatedTotalBytecodes.max(progress.bytecodesDownloaded)
        (pulled, known)
      
      case StorageRangeSync =>
        // In storage sync, we add accounts + bytecodes + storage slots
        val pulled = progress.accountsSynced + progress.bytecodesDownloaded + progress.storageSlotsSynced
        // Use max() as a fallback when estimates are not yet available (0)
        val known = progress.estimatedTotalAccounts + progress.estimatedTotalBytecodes + 
                   progress.estimatedTotalSlots.max(progress.storageSlotsSynced)
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
        // If we're healing, ensure known is at least as much as pulled
        (pulled, known.max(pulled))
      
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
  case object BootstrapComplete // Signal from SyncController that bootstrap is done
  private case object RetrySnapSyncStart // Internal message to retry SNAP sync start after bootstrap
  case object AccountRangeSyncComplete
  case object ByteCodeSyncComplete
  case object StorageRangeSyncComplete
  case object StateHealingComplete
  case object StateValidationComplete
  case object GetProgress

  def props(
      blockchainReader: BlockchainReader,
      appStateStorage: AppStateStorage,
      mptStorage: MptStorage,
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
        mptStorage,
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
    pivotBlockOffset: Long = 1024,
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

  def startPhase(phase: SyncPhase): Unit = {
    val previousPhase = currentPhaseState
    currentPhaseState = phase
    phaseStartTime = System.currentTimeMillis()

    log.info(s"📊 SNAP Sync phase transition: $previousPhase → $phase")
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
  override def toString: String =
    s"SNAP Sync Progress: phase=$phase, accounts=$accountsSynced (${accountsPerSec.toInt}/s), " +
      s"bytecodes=$bytecodesDownloaded (${bytecodesPerSec.toInt}/s), " +
      s"slots=$storageSlotsSynced (${slotsPerSec.toInt}/s), nodes=$nodesHealed (${nodesPerSec.toInt}/s), " +
      s"elapsed=${elapsedSeconds.toInt}s"

  def formattedString: String =
    phase match {
      case SNAPSyncController.AccountRangeSync =>
        val progressStr = if (estimatedTotalAccounts > 0) s" (${phaseProgress}%)" else ""
        s"phase=AccountRange$progressStr, accounts=$accountsSynced@${recentAccountsPerSec.toInt}/s, elapsed=${elapsedSeconds.toInt}s"

      case SNAPSyncController.ByteCodeSync =>
        val progressStr = if (estimatedTotalBytecodes > 0) s" (${phaseProgress}%)" else ""
        s"phase=ByteCode$progressStr, codes=$bytecodesDownloaded@${recentBytecodesPerSec.toInt}/s, elapsed=${elapsedSeconds.toInt}s"

      case SNAPSyncController.StorageRangeSync =>
        val progressStr = if (estimatedTotalSlots > 0) s" (${phaseProgress}%)" else ""
        s"phase=Storage$progressStr, slots=$storageSlotsSynced@${recentSlotsPerSec.toInt}/s, elapsed=${elapsedSeconds.toInt}s"

      case SNAPSyncController.StateHealing =>
        s"phase=Healing, nodes=$nodesHealed@${recentNodesPerSec.toInt}/s, elapsed=${elapsedSeconds.toInt}s"

      case SNAPSyncController.StateValidation =>
        s"phase=Validation, elapsed=${elapsedSeconds.toInt}s"

      case _ =>
        s"phase=$phase, elapsed=${elapsedSeconds.toInt}s"
    }
}
