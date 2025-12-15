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

  private var accountRangeDownloader: Option[AccountRangeDownloader] = None
  private var bytecodeDownloader: Option[ByteCodeDownloader] = None
  private var storageRangeDownloader: Option[StorageRangeDownloader] = None
  private var trieNodeHealer: Option[TrieNodeHealer] = None

  private val requestTracker = new SNAPRequestTracker()(scheduler)

  private var currentPhase: SyncPhase = Idle
  private var pivotBlock: Option[BigInt] = None
  private var stateRoot: Option[ByteString] = None

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

  def idle: Receive = { case Start =>
    log.info("Starting SNAP sync...")
    startSnapSync()
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

      val taskId = s"account_range_${msg.requestId}"
      val peerId = requestTracker.getPendingRequest(msg.requestId).map(_.peer.id.value).getOrElse("unknown")

      accountRangeDownloader.foreach { downloader =>
        downloader.handleResponse(msg) match {
          case Right(count) =>
            log.info(s"Successfully processed $count accounts from peer $peerId")
            progressMonitor.incrementAccountsSynced(count)
            errorHandler.resetRetries(taskId)
            errorHandler.recordPeerSuccess(peerId)
            errorHandler.recordCircuitBreakerSuccess("account_range_download")

            // Check if account range sync is complete
            if (downloader.isComplete) {
              log.info("Account range sync complete!")
              accountRangeRequestTask.foreach(_.cancel())
              accountRangeRequestTask = None
              
              // Finalize the trie to ensure all nodes including root are persisted
              log.info("Finalizing state trie before proceeding to bytecode sync...")
              downloader.finalizeTrie() match {
                case Right(_) =>
                  log.info("State trie finalized successfully")
                  self ! AccountRangeSyncComplete
                case Left(error) =>
                  log.error(s"Failed to finalize state trie: $error")
                  log.error("Trie finalization is critical for subsequent phases. Cannot proceed.")
                  if (recordCriticalFailure(s"Trie finalization failed: $error")) {
                    fallbackToFastSync()
                  }
                  // Do not send AccountRangeSyncComplete - sync cannot proceed without finalization
              }
            }

          case Left(error) =>
            val context = errorHandler.createErrorContext(
              phase = "AccountRangeSync",
              peerId = Some(peerId),
              requestId = Some(msg.requestId),
              taskId = Some(taskId)
            )
            log.warning(s"Failed to process AccountRange: $error ($context)")

            // Determine error type and record appropriately
            val errorType = if (error.contains("proof")) {
              SNAPErrorHandler.ErrorType.InvalidProof
            } else if (error.contains("malformed") || error.contains("validation")) {
              SNAPErrorHandler.ErrorType.MalformedResponse
            } else if (error.contains("storage")) {
              SNAPErrorHandler.ErrorType.StorageError
            } else {
              SNAPErrorHandler.ErrorType.ProcessingError
            }

            errorHandler.recordPeerFailure(peerId, errorType, error)
            errorHandler.recordRetry(taskId, error)
            errorHandler.recordCircuitBreakerFailure("account_range_download")

            // Check if circuit breaker is open and fallback if needed
            if (!checkCircuitBreakerAndTriggerFallback("account_range_download", error)) {
              // Only proceed with blacklisting if fallback wasn't triggered
              // Check if peer should be blacklisted
              if (errorHandler.shouldBlacklistPeer(peerId)) {
                log.error(s"Blacklisting peer $peerId due to repeated failures")
                blacklist.add(
                  PeerId(peerId),
                  syncConfig.blacklistDuration,
                  InvalidStateResponse("SNAP sync repeated failures")
                )
              }
            }
        }
      }

    case msg: ByteCodes =>
      log.debug(s"Received ByteCodes response: requestId=${msg.requestId}, codes=${msg.codes.size}")

      val taskId = s"bytecode_${msg.requestId}"
      val peerId = requestTracker.getPendingRequest(msg.requestId).map(_.peer.id.value).getOrElse("unknown")

      bytecodeDownloader.foreach { downloader =>
        downloader.handleResponse(msg) match {
          case Right(count) =>
            log.info(s"Successfully processed $count bytecodes from peer $peerId")
            progressMonitor.incrementBytecodesDownloaded(count)
            errorHandler.resetRetries(taskId)
            errorHandler.recordPeerSuccess(peerId)
            errorHandler.recordCircuitBreakerSuccess("bytecode_download")

            // Check if bytecode sync is complete
            if (downloader.isComplete) {
              log.info("ByteCode sync complete!")
              bytecodeRequestTask.foreach(_.cancel())
              bytecodeRequestTask = None
              self ! ByteCodeSyncComplete
            }

          case Left(error) =>
            val context = errorHandler.createErrorContext(
              phase = "ByteCodeSync",
              peerId = Some(peerId),
              requestId = Some(msg.requestId),
              taskId = Some(taskId)
            )
            log.warning(s"Failed to process ByteCodes: $error ($context)")

            val errorType = if (error.contains("hash")) {
              SNAPErrorHandler.ErrorType.HashMismatch
            } else if (error.contains("storage")) {
              SNAPErrorHandler.ErrorType.StorageError
            } else {
              SNAPErrorHandler.ErrorType.ProcessingError
            }

            errorHandler.recordPeerFailure(peerId, errorType, error)
            errorHandler.recordRetry(taskId, error)
            errorHandler.recordCircuitBreakerFailure("bytecode_download")

            // Check if circuit breaker is open and fallback if needed
            if (!checkCircuitBreakerAndTriggerFallback("bytecode_download", error)) {
              // Only proceed with blacklisting if fallback wasn't triggered
              if (errorHandler.shouldBlacklistPeer(peerId)) {
                log.error(s"Blacklisting peer $peerId due to repeated failures")
                blacklist.add(
                  PeerId(peerId),
                  syncConfig.blacklistDuration,
                  InvalidStateResponse("SNAP sync repeated failures")
                )
              }
            }
        }
      }

    case msg: StorageRanges =>
      log.debug(s"Received StorageRanges response: requestId=${msg.requestId}, slots=${msg.slots.size}")

      val taskId = s"storage_range_${msg.requestId}"
      val peerId = requestTracker.getPendingRequest(msg.requestId).map(_.peer.id.value).getOrElse("unknown")

      storageRangeDownloader.foreach { downloader =>
        downloader.handleResponse(msg) match {
          case Right(count) =>
            log.info(s"Successfully processed $count storage slots from peer $peerId")
            progressMonitor.incrementStorageSlotsSynced(count)
            errorHandler.resetRetries(taskId)
            errorHandler.recordPeerSuccess(peerId)
            errorHandler.recordCircuitBreakerSuccess("storage_range_download")

            // Check if storage range sync is complete
            if (downloader.isComplete) {
              log.info("Storage range sync complete!")
              storageRangeRequestTask.foreach(_.cancel())
              storageRangeRequestTask = None
              self ! StorageRangeSyncComplete
            }

          case Left(error) =>
            val context = errorHandler.createErrorContext(
              phase = "StorageRangeSync",
              peerId = Some(peerId),
              requestId = Some(msg.requestId),
              taskId = Some(taskId)
            )
            log.warning(s"Failed to process StorageRanges: $error ($context)")

            val errorType = if (error.contains("proof")) {
              SNAPErrorHandler.ErrorType.InvalidProof
            } else if (error.contains("storage")) {
              SNAPErrorHandler.ErrorType.StorageError
            } else {
              SNAPErrorHandler.ErrorType.ProcessingError
            }

            errorHandler.recordPeerFailure(peerId, errorType, error)
            errorHandler.recordRetry(taskId, error)
            errorHandler.recordCircuitBreakerFailure("storage_range_download")

            // Check if circuit breaker is open and fallback if needed
            if (!checkCircuitBreakerAndTriggerFallback("storage_range_download", error)) {
              // Only proceed with blacklisting if fallback wasn't triggered
              if (errorHandler.shouldBlacklistPeer(peerId)) {
                log.error(s"Blacklisting peer $peerId due to repeated failures")
                blacklist.add(
                  PeerId(peerId),
                  syncConfig.blacklistDuration,
                  InvalidStateResponse("SNAP sync repeated failures")
                )
              }
            }
        }
      }

    case msg: TrieNodes =>
      log.debug(s"Received TrieNodes response: requestId=${msg.requestId}, nodes=${msg.nodes.size}")

      val taskId = s"trie_nodes_${msg.requestId}"
      val peerId = requestTracker.getPendingRequest(msg.requestId).map(_.peer.id.value).getOrElse("unknown")

      trieNodeHealer.foreach { healer =>
        healer.handleResponse(msg) match {
          case Right(count) =>
            log.info(s"Successfully healed $count trie nodes from peer $peerId")
            progressMonitor.incrementNodesHealed(count)
            errorHandler.resetRetries(taskId)
            errorHandler.recordPeerSuccess(peerId)
            errorHandler.recordCircuitBreakerSuccess("trie_node_healing")

            // Check if healing is complete
            if (healer.isComplete) {
              log.info("State healing complete!")
              healingRequestTask.foreach(_.cancel())
              healingRequestTask = None
              self ! StateHealingComplete
            }

          case Left(error) =>
            val context = errorHandler.createErrorContext(
              phase = "StateHealing",
              peerId = Some(peerId),
              requestId = Some(msg.requestId),
              taskId = Some(taskId)
            )
            log.warning(s"Failed to process TrieNodes: $error ($context)")

            val errorType = if (error.contains("hash")) {
              SNAPErrorHandler.ErrorType.HashMismatch
            } else if (error.contains("storage")) {
              SNAPErrorHandler.ErrorType.StorageError
            } else {
              SNAPErrorHandler.ErrorType.ProcessingError
            }

            errorHandler.recordPeerFailure(peerId, errorType, error)
            errorHandler.recordRetry(taskId, error)
            errorHandler.recordCircuitBreakerFailure("trie_node_healing")

            // Check if circuit breaker is open and fallback if needed
            if (!checkCircuitBreakerAndTriggerFallback("trie_node_healing", error)) {
              // Only proceed with blacklisting if fallback wasn't triggered
              if (errorHandler.shouldBlacklistPeer(peerId)) {
                log.error(s"Blacklisting peer $peerId due to repeated failures")
                blacklist.add(
                  PeerId(peerId),
                  syncConfig.blacklistDuration,
                  InvalidStateResponse("SNAP sync repeated failures")
                )
              }
            }
        }
      }

    case AccountRangeSyncComplete =>
      log.info("Account range sync complete. Starting bytecode sync...")
      progressMonitor.startPhase(ByteCodeSync)
      currentPhase = ByteCodeSync
      startBytecodeSync()

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
      
      // Clear bootstrap target from storage
      appStateStorage.clearSnapSyncBootstrapTarget().commit()
      
      // Reset retry counter
      bootstrapRetryCount = 0
      
      // Now we have enough blocks - start SNAP sync properly
      startSnapSync()
    
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
    
    case msg =>
      log.debug(s"Unhandled message in bootstrapping state: $msg")
  }

  def completed: Receive = {
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
    
    // Determine which block number to use as the base for pivot calculation
    val (baseBlockForPivot, pivotSelectionSource) = networkBestBlockOpt match {
      case Some(networkBestBlock) if networkBestBlock > localBestBlock + snapSyncConfig.pivotBlockOffset =>
        // Network is significantly ahead - use network best block for pivot
        // This ensures we sync to a recent state, not just our local state
        (networkBestBlock, "network")
      case Some(networkBestBlock) =>
        // Network is not far ahead or peers report similar blocks
        log.warning(s"Network best block ($networkBestBlock) is not significantly ahead of local ($localBestBlock)")
        log.warning("This may indicate limited peer connectivity or already synced state")
        (networkBestBlock, "network")
      case None =>
        // No peers available yet - fall back to local best block
        log.warning("No peers available for pivot selection, using local best block")
        log.warning("SNAP sync may select a suboptimal pivot - will retry if peers become available")
        (localBestBlock, "local")
    }
    
    val pivotBlockNumber = baseBlockForPivot - snapSyncConfig.pivotBlockOffset

    if (pivotBlockNumber <= 0) {
      // Calculate how many blocks we need to bootstrap
      val bootstrapTarget = snapSyncConfig.pivotBlockOffset + 1
      val blocksNeeded = bootstrapTarget - localBestBlock
      
      log.info("=" * 80)
      log.info("🚀 SNAP Sync Initialization")
      log.info("=" * 80)
      log.info(s"Current blockchain state: $localBestBlock blocks")
      log.info(s"SNAP sync requires at least $bootstrapTarget blocks to begin")
      log.info(s"System will gather $blocksNeeded initial blocks via regular sync")
      log.info(s"Once complete, node will automatically transition to SNAP sync mode")
      log.info("=" * 80)
      log.info(s"⏳ Gathering initial blocks... (target: $bootstrapTarget)")
      
      // Store bootstrap target for persistence and potential restart recovery
      appStateStorage.putSnapSyncBootstrapTarget(bootstrapTarget).commit()
      
      // Request parent to start regular sync bootstrap
      context.parent ! StartRegularSyncBootstrap(bootstrapTarget)
      context.become(bootstrapping)
    } else if (pivotBlockNumber <= localBestBlock) {
      // Sanity check: pivot must be ahead of local state
      log.warning("=" * 80)
      log.warning("⚠️  SNAP Sync Pivot Issue Detected")
      log.warning("=" * 80)
      log.warning(s"Calculated pivot ($pivotBlockNumber) is not ahead of local state ($localBestBlock)")
      log.warning(s"Pivot source: $pivotSelectionSource, base block: $baseBlockForPivot, offset: ${snapSyncConfig.pivotBlockOffset}")
      
      if (pivotSelectionSource == "local" && networkBestBlockOpt.isEmpty) {
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
      } else {
        // Pivot is at or behind local state - this means we're already synced or very close
        // Fall back to regular sync to catch up the remaining blocks
        log.warning("Pivot block is not ahead of local state - likely already synced")
        log.warning("Transitioning to regular sync for final block catch-up")
        context.parent ! Done  // Signal completion, which will transition to regular sync
        return
      }
    } else {
      // Pivot is valid and ahead of local state
      pivotBlock = Some(pivotBlockNumber)

      // Update metrics - pivot block
      SNAPSyncMetrics.setPivotBlockNumber(pivotBlockNumber)

      // Get state root for pivot block
      blockchainReader.getBlockHeaderByNumber(pivotBlockNumber) match {
        case Some(header) =>
          stateRoot = Some(header.stateRoot)
          appStateStorage.putSnapSyncPivotBlock(pivotBlockNumber).commit()
          appStateStorage.putSnapSyncStateRoot(header.stateRoot).commit()

          log.info("=" * 80)
          log.info("🎯 SNAP Sync Ready")
          log.info("=" * 80)
          log.info(s"Local best block: $localBestBlock")
          networkBestBlockOpt.foreach(netBest => log.info(s"Network best block: $netBest"))
          log.info(s"Selected pivot block: $pivotBlockNumber (source: $pivotSelectionSource)")
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
          // Pivot block header not available - this can happen after bootstrap
          // due to asynchronous block import
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

    // Clear downloaders
    accountRangeDownloader = None
    bytecodeDownloader = None
    storageRangeDownloader = None
    trieNodeHealer = None

    // Stop progress monitoring
    progressMonitor.stopPeriodicLogging()

    // Notify parent controller to switch to fast sync
    context.parent ! FallbackToFastSync
    context.become(completed)
  }

  private def startAccountRangeSync(rootHash: ByteString): Unit = {
    log.info(s"Starting account range sync with concurrency ${snapSyncConfig.accountConcurrency}")

    accountRangeDownloader = Some(
      new AccountRangeDownloader(
        stateRoot = rootHash,
        networkPeerManager = networkPeerManager,
        requestTracker = requestTracker,
        mptStorage = mptStorage,
        concurrency = snapSyncConfig.accountConcurrency
      )
    )

    progressMonitor.startPhase(AccountRangeSync)

    // Start periodic task to request account ranges from peers
    accountRangeRequestTask = Some(
      scheduler.scheduleWithFixedDelay(
        0.seconds,
        1.second,
        self,
        RequestAccountRanges
      )(ec)
    )
  }

  // Internal message for periodic account range requests
  private case object RequestAccountRanges

  private def requestAccountRanges(): Unit =
    accountRangeDownloader.foreach { downloader =>
      // Get SNAP-capable peers
      val snapPeers = peersToDownloadFrom.collect {
        case (peerId, peerWithInfo) if peerWithInfo.peerInfo.remoteStatus.supportsSnap =>
          peerWithInfo.peer
      }

      // Update metrics - SNAP-capable peer count
      SNAPSyncMetrics.setSnapCapablePeers(snapPeers.size)

      if (snapPeers.isEmpty) {
        log.debug("No SNAP-capable peers available for account range requests")
      } else {
        log.debug(s"Requesting account ranges from ${snapPeers.size} SNAP peers")

        // Request from each available peer
        snapPeers.foreach { peer =>
          downloader.requestNextRange(peer) match {
            case Some(requestId) =>
              SNAPSyncMetrics.incrementAccountRangeRequests()
              log.debug(s"Sent account range request $requestId to peer ${peer.id}")
            case None =>
              log.debug(s"No more account ranges to request")
          }
        }
      }
    }

  private def startBytecodeSync(): Unit = {
    log.info(s"Starting bytecode sync with batch size ${ByteCodeTask.DEFAULT_BATCH_SIZE}")

    // Get contract accounts from account range downloader
    val contractAccounts = accountRangeDownloader.map(_.getContractAccounts).getOrElse(Seq.empty)

    if (contractAccounts.isEmpty) {
      log.info("No contract accounts found, skipping bytecode sync")
      self ! ByteCodeSyncComplete
      return
    }

    log.info(s"Found ${contractAccounts.size} contract accounts for bytecode download")

    bytecodeDownloader = Some(
      new ByteCodeDownloader(
        evmCodeStorage = evmCodeStorage,
        networkPeerManager = networkPeerManager,
        requestTracker = requestTracker,
        batchSize = ByteCodeTask.DEFAULT_BATCH_SIZE
      )(scheduler)
    )

    // Queue contract accounts for download
    bytecodeDownloader.foreach(_.queueContracts(contractAccounts))

    progressMonitor.startPhase(ByteCodeSync)

    // Start periodic task to request bytecodes from peers
    bytecodeRequestTask = Some(
      scheduler.scheduleWithFixedDelay(
        0.seconds,
        1.second,
        self,
        RequestByteCodes
      )(ec)
    )
  }

  // Internal message for periodic bytecode requests
  private case object RequestByteCodes

  private def requestByteCodes(): Unit =
    bytecodeDownloader.foreach { downloader =>
      // Get SNAP-capable peers
      val snapPeers = peersToDownloadFrom.collect {
        case (peerId, peerWithInfo) if peerWithInfo.peerInfo.remoteStatus.supportsSnap =>
          peerWithInfo.peer
      }

      if (snapPeers.isEmpty) {
        log.debug("No SNAP-capable peers available for bytecode requests")
      } else {
        log.debug(s"Requesting bytecodes from ${snapPeers.size} SNAP peers")

        // Request from each available peer
        snapPeers.foreach { peer =>
          downloader.requestNextBatch(peer) match {
            case Some(requestId) =>
              log.debug(s"Sent bytecode request $requestId to peer ${peer.id}")
            case None =>
              log.debug(s"No more bytecodes to request")
          }
        }
      }

      // If no tasks and downloader is complete, trigger completion immediately
      if (downloader.isComplete) {
        log.info("ByteCode sync complete (no more bytecodes)")
        bytecodeRequestTask.foreach(_.cancel())
        bytecodeRequestTask = None
        self ! ByteCodeSyncComplete
      }
    }

  private def startStorageRangeSync(): Unit = {
    log.info(s"Starting storage range sync with concurrency ${snapSyncConfig.storageConcurrency}")

    stateRoot.foreach { root =>
      storageRangeDownloader = Some(
        new StorageRangeDownloader(
          stateRoot = root,
          networkPeerManager = networkPeerManager,
          requestTracker = requestTracker,
          mptStorage = mptStorage,
          maxAccountsPerBatch = snapSyncConfig.storageBatchSize
        )
      )

      progressMonitor.startPhase(StorageRangeSync)

      // TODO: In a complete implementation, we would identify accounts with storage
      // from the account range sync results and add them as tasks to the downloader.
      // For now, we start the request loop which will complete immediately if no tasks.

      // Start periodic task to request storage ranges from peers
      storageRangeRequestTask = Some(
        scheduler.scheduleWithFixedDelay(
          0.seconds,
          1.second,
          self,
          RequestStorageRanges
        )(ec)
      )
    }
  }

  // Internal message for periodic storage range requests
  private case object RequestStorageRanges

  private def requestStorageRanges(): Unit =
    storageRangeDownloader.foreach { downloader =>
      // Get SNAP-capable peers
      val snapPeers = peersToDownloadFrom.collect {
        case (peerId, peerWithInfo) if peerWithInfo.peerInfo.remoteStatus.supportsSnap =>
          peerWithInfo.peer
      }

      if (snapPeers.isEmpty) {
        log.debug("No SNAP-capable peers available for storage range requests")
      } else {
        log.debug(s"Requesting storage ranges from ${snapPeers.size} SNAP peers")

        // Request from each available peer
        snapPeers.foreach { peer =>
          downloader.requestNextRanges(peer) match {
            case Some(requestId) =>
              log.debug(s"Sent storage range request $requestId to peer ${peer.id}")
            case None =>
              log.debug(s"No more storage ranges to request")
          }
        }
      }

      // If no tasks and downloader is complete, trigger completion immediately
      if (downloader.isComplete) {
        log.info("Storage range sync complete (no storage tasks)")
        storageRangeRequestTask.foreach(_.cancel())
        storageRangeRequestTask = None
        self ! StorageRangeSyncComplete
      }
    }

  private def startStateHealing(): Unit = {
    log.info(s"Starting state healing with batch size ${snapSyncConfig.healingBatchSize}")

    stateRoot.foreach { root =>
      trieNodeHealer = Some(
        new TrieNodeHealer(
          stateRoot = root,
          networkPeerManager = networkPeerManager,
          requestTracker = requestTracker,
          mptStorage = mptStorage,
          batchSize = snapSyncConfig.healingBatchSize
        )
      )

      progressMonitor.startPhase(StateHealing)

      // TODO: In a complete implementation, we would detect missing nodes from
      // account/storage sync and add them to the healer. For now, we start the
      // request loop which will complete immediately if no missing nodes.

      // Start periodic task to request trie node healing from peers
      healingRequestTask = Some(
        scheduler.scheduleWithFixedDelay(
          0.seconds,
          1.second,
          self,
          RequestTrieNodeHealing
        )(ec)
      )
    }
  }

  // Internal message for periodic healing requests
  private case object RequestTrieNodeHealing

  private def requestTrieNodeHealing(): Unit =
    trieNodeHealer.foreach { healer =>
      // Get SNAP-capable peers
      val snapPeers = peersToDownloadFrom.collect {
        case (peerId, peerWithInfo) if peerWithInfo.peerInfo.remoteStatus.supportsSnap =>
          peerWithInfo.peer
      }

      if (snapPeers.isEmpty) {
        log.debug("No SNAP-capable peers available for healing requests")
      } else {
        log.debug(s"Requesting trie node healing from ${snapPeers.size} SNAP peers")

        // Request from each available peer
        snapPeers.foreach { peer =>
          healer.requestNextBatch(peer) match {
            case Some(requestId) =>
              log.debug(s"Sent healing request $requestId to peer ${peer.id}")
            case None =>
              log.debug(s"No more nodes to heal")
          }
        }
      }

      // If no tasks and healer is complete, trigger completion immediately
      if (healer.isComplete) {
        log.info("State healing complete (no missing nodes)")
        healingRequestTask.foreach(_.cancel())
        healingRequestTask = None
        self ! StateHealingComplete
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
        accountRangeDownloader.foreach { downloader =>
          val computedRoot = downloader.getStateRoot

          if (computedRoot == expectedRoot) {
            log.info(
              s"✅ State root verification PASSED: ${computedRoot.take(8).toArray.map("%02x".format(_)).mkString}"
            )

            // Before proceeding with validation, ensure the trie is finalized
            log.info("Ensuring trie is fully persisted before validation...")
            downloader.finalizeTrie() match {
              case Left(error) =>
                log.error(s"Failed to finalize trie before validation: $error")
                log.error("Attempting to recover through healing phase")
                currentPhase = StateHealing
                startStateHealing()
                return
              case Right(_) =>
                log.info("Trie finalization confirmed before validation")
            }

            // Proceed with full trie validation
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
                
                // Check if this is a root node missing error after finalization
                if (error.contains("Missing root node")) {
                  validationRetryCount += 1
                  
                  if (validationRetryCount > MaxValidationRetries) {
                    log.error(s"Root node missing error persists (failed $validationRetryCount times total)")
                    log.error("Maximum validation retries exceeded - falling back to fast sync")
                    if (recordCriticalFailure("Root node persistence failure after retries")) {
                      fallbackToFastSync()
                    }
                  } else {
                    log.error(s"Root node is missing even after finalization (retry attempt $validationRetryCount of $MaxValidationRetries)")
                    log.error("Attempting recovery by re-finalizing the trie...")
                    
                    // Try one more finalization
                    downloader.finalizeTrie() match {
                      case Right(_) =>
                        log.info(s"Re-finalization successful, retrying validation (retry attempt $validationRetryCount of $MaxValidationRetries)...")
                        // Directly retry validation without going through the healing phase
                        // (healing will find no missing nodes since we built the trie locally)
                        scheduler.scheduleOnce(ValidationRetryDelay) {
                          self ! StateHealingComplete
                        }(ec)
                      case Left(finalizeError) =>
                        log.error(s"Re-finalization failed: $finalizeError")
                        log.error("Cannot proceed with validation - falling back to fast sync")
                        if (recordCriticalFailure("Root node persistence failure")) {
                          fallbackToFastSync()
                        }
                    }
                  }
                } else {
                  log.error("Attempting to recover through healing phase")
                  currentPhase = StateHealing
                  startStateHealing()
                }
            }
          } else {
            // CRITICAL: State root mismatch - block sync completion
            log.error(s"❌ CRITICAL: State root verification FAILED!")
            log.error(s"  Expected: ${expectedRoot.take(8).toArray.map("%02x".format(_)).mkString}...")
            log.error(s"  Computed: ${computedRoot.take(8).toArray.map("%02x".format(_)).mkString}...")
            log.error(
              s"  Sync cannot complete with mismatched state root - this indicates incomplete or corrupted state"
            )

            // DO NOT send StateValidationComplete - sync must not proceed
            log.warning("State root mismatch detected - sync blocked until healing completes")

            // Trigger healing to fix the mismatch
            log.info("Transitioning back to healing phase to fix state root mismatch")
            currentPhase = StateHealing
            startStateHealing()
          }
        }

      case _ =>
        log.error("Missing state root or pivot block for validation")
        // Fail sync - we cannot proceed without validation
        log.error("Sync cannot complete - missing state root or pivot block")
    }
  }

  /** Trigger healing for a list of missing node hashes */
  private def triggerHealingForMissingNodes(missingNodes: Seq[ByteString]): Unit = {
    log.info(s"Queueing ${missingNodes.size} missing nodes for healing")

    // Add missing nodes to the healer, or log error if healer is not initialized
    trieNodeHealer match {
      case Some(healer) =>
        // Use batch method for efficiency
        healer.queueNodes(missingNodes)

        // Transition back to healing phase
        currentPhase = StateHealing
        log.info(s"Transitioning to StateHealing phase to heal ${missingNodes.size} missing nodes")

        // Start healing requests
        scheduler.scheduleOnce(100.millis) {
          self ! RequestTrieNodeHealing
        }
      case None =>
        log.error("Cannot heal missing nodes - TrieNodeHealer not initialized")
        log.error("Sync cannot complete - healing infrastructure unavailable")
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
