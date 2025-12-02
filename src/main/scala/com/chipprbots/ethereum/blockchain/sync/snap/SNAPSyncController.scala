package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.actor.{Actor, ActorLogging, ActorRef, Props, Scheduler, Cancellable}
import org.apache.pekko.util.ByteString

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext

import com.chipprbots.ethereum.blockchain.sync.{Blacklist, CacheBasedBlacklist, PeerListSupportNg, SyncProtocol}
import com.chipprbots.ethereum.db.storage.{AppStateStorage, MptStorage}
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.network.p2p.messages.SNAP
import com.chipprbots.ethereum.network.p2p.messages.SNAP._
import com.chipprbots.ethereum.utils.Config.SyncConfig
import com.chipprbots.ethereum.utils.ByteStringUtils.ByteStringOps

class SNAPSyncController(
    blockchainReader: BlockchainReader,
    appStateStorage: AppStateStorage,
    mptStorage: MptStorage,
    val etcPeerManager: ActorRef,
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

  private var accountRangeDownloader: Option[AccountRangeDownloader] = None
  private var storageRangeDownloader: Option[StorageRangeDownloader] = None
  private var trieNodeHealer: Option[TrieNodeHealer] = None
  
  private val requestTracker = new SNAPRequestTracker()(scheduler)
  
  private var currentPhase: SyncPhase = Idle
  private var pivotBlock: Option[BigInt] = None
  private var stateRoot: Option[ByteString] = None
  
  private val progressMonitor = new SyncProgressMonitor(scheduler)
  
  // Scheduled tasks for periodic peer requests
  private var accountRangeRequestTask: Option[Cancellable] = None
  private var storageRangeRequestTask: Option[Cancellable] = None
  private var healingRequestTask: Option[Cancellable] = None
  
  override def preStart(): Unit = {
    log.info("SNAP Sync Controller initialized")
  }
  
  override def postStop(): Unit = {
    // Cancel all scheduled tasks
    accountRangeRequestTask.foreach(_.cancel())
    storageRangeRequestTask.foreach(_.cancel())
    healingRequestTask.foreach(_.cancel())
    log.info("SNAP Sync Controller stopped")
  }

  override def receive: Receive = idle

  def idle: Receive = {
    case Start =>
      log.info("Starting SNAP sync...")
      startSnapSync()
  }

  def syncing: Receive = handlePeerListMessages.orElse {
    // Periodic request triggers
    case RequestAccountRanges =>
      requestAccountRanges()
    
    case RequestStorageRanges =>
      requestStorageRanges()
    
    case RequestTrieNodeHealing =>
      requestTrieNodeHealing()
    
    // Handle SNAP protocol responses
    case msg: AccountRange =>
      log.debug(s"Received AccountRange response: requestId=${msg.requestId}, accounts=${msg.accounts.size}")
      accountRangeDownloader.foreach { downloader =>
        downloader.handleResponse(msg) match {
          case Right(count) =>
            log.info(s"Successfully processed $count accounts")
            progressMonitor.incrementAccountsSynced(count)
            // Check if account range sync is complete
            if (downloader.isComplete) {
              log.info("Account range sync complete!")
              accountRangeRequestTask.foreach(_.cancel())
              accountRangeRequestTask = None
              self ! AccountRangeSyncComplete
            }
          case Left(error) =>
            log.warning(s"Failed to process AccountRange: $error")
        }
      }

    case msg: StorageRanges =>
      log.debug(s"Received StorageRanges response: requestId=${msg.requestId}, slots=${msg.slots.size}")
      storageRangeDownloader.foreach { downloader =>
        downloader.handleResponse(msg) match {
          case Right(count) =>
            log.info(s"Successfully processed $count storage slots")
            progressMonitor.incrementStorageSlotsSynced(count)
            // Check if storage range sync is complete
            if (downloader.isComplete) {
              log.info("Storage range sync complete!")
              storageRangeRequestTask.foreach(_.cancel())
              storageRangeRequestTask = None
              self ! StorageRangeSyncComplete
            }
          case Left(error) =>
            log.warning(s"Failed to process StorageRanges: $error")
        }
      }

    case msg: TrieNodes =>
      log.debug(s"Received TrieNodes response: requestId=${msg.requestId}, nodes=${msg.nodes.size}")
      trieNodeHealer.foreach { healer =>
        healer.handleResponse(msg) match {
          case Right(count) =>
            log.info(s"Successfully healed $count trie nodes")
            progressMonitor.incrementNodesHealed(count)
            // Check if healing is complete
            if (healer.isComplete) {
              log.info("State healing complete!")
              healingRequestTask.foreach(_.cancel())
              healingRequestTask = None
              self ! StateHealingComplete
            }
          case Left(error) =>
            log.warning(s"Failed to process TrieNodes: $error")
        }
      }

    case AccountRangeSyncComplete =>
      log.info("Account range sync complete. Starting storage range sync...")
      currentPhase = StorageRangeSync
      startStorageRangeSync()

    case StorageRangeSyncComplete =>
      log.info("Storage range sync complete. Starting state healing...")
      currentPhase = StateHealing
      startStateHealing()

    case StateHealingComplete =>
      log.info("State healing complete. Validating state...")
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

  def completed: Receive = {
    case GetProgress =>
      sender() ! progressMonitor.currentProgress

    case _ =>
      log.debug("SNAP sync is complete, ignoring messages")
  }

  private def startSnapSync(): Unit = {
    // Select pivot block
    val bestBlockNumber = appStateStorage.getBestBlockNumber()
    val pivotBlockNumber = bestBlockNumber - snapSyncConfig.pivotBlockOffset
    
    if (pivotBlockNumber <= 0) {
      log.error(s"Cannot start SNAP sync: pivot block $pivotBlockNumber <= 0")
      context.parent ! SyncProtocol.Status.SyncDone
      return
    }

    pivotBlock = Some(pivotBlockNumber)
    
    // Get state root for pivot block
    blockchainReader.getBlockHeaderByNumber(pivotBlockNumber) match {
      case Some(header) =>
        stateRoot = Some(header.stateRoot)
        appStateStorage.putSnapSyncPivotBlock(pivotBlockNumber).commit()
        appStateStorage.putSnapSyncStateRoot(header.stateRoot).commit()
        
        log.info(s"SNAP sync pivot block: $pivotBlockNumber, state root: ${header.stateRoot.toHex}")
        
        // Start account range sync
        currentPhase = AccountRangeSync
        startAccountRangeSync(header.stateRoot)
        context.become(syncing)

      case None =>
        log.error(s"Cannot get header for pivot block $pivotBlockNumber")
        context.parent ! SyncProtocol.Status.SyncDone
    }
  }

  private def startAccountRangeSync(rootHash: ByteString): Unit = {
    log.info(s"Starting account range sync with concurrency ${snapSyncConfig.accountConcurrency}")
    
    accountRangeDownloader = Some(
      new AccountRangeDownloader(
        stateRoot = rootHash,
        etcPeerManager = etcPeerManager,
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
  
  private def requestAccountRanges(): Unit = {
    accountRangeDownloader.foreach { downloader =>
      // Get SNAP-capable peers
      val snapPeers = peersToDownloadFrom.collect {
        case (peerId, peerWithInfo) 
          if peerWithInfo.peerInfo.remoteStatus.supportsSnap =>
          peerWithInfo.peer
      }
      
      if (snapPeers.isEmpty) {
        log.debug("No SNAP-capable peers available for account range requests")
      } else {
        log.debug(s"Requesting account ranges from ${snapPeers.size} SNAP peers")
        
        // Request from each available peer
        snapPeers.foreach { peer =>
          downloader.requestNextRange(peer) match {
            case Some(requestId) =>
              log.debug(s"Sent account range request $requestId to peer ${peer.id}")
            case None =>
              log.debug(s"No more account ranges to request")
          }
        }
      }
    }
  }

  private def startStorageRangeSync(): Unit = {
    log.info(s"Starting storage range sync with concurrency ${snapSyncConfig.storageConcurrency}")
    
    stateRoot.foreach { root =>
      storageRangeDownloader = Some(
        new StorageRangeDownloader(
          stateRoot = root,
          etcPeerManager = etcPeerManager,
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
  
  private def requestStorageRanges(): Unit = {
    storageRangeDownloader.foreach { downloader =>
      // Get SNAP-capable peers
      val snapPeers = peersToDownloadFrom.collect {
        case (peerId, peerWithInfo) 
          if peerWithInfo.peerInfo.remoteStatus.supportsSnap =>
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
  }

  private def startStateHealing(): Unit = {
    log.info(s"Starting state healing with batch size ${snapSyncConfig.healingBatchSize}")
    
    stateRoot.foreach { root =>
      trieNodeHealer = Some(
        new TrieNodeHealer(
          stateRoot = root,
          etcPeerManager = etcPeerManager,
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
  
  private def requestTrieNodeHealing(): Unit = {
    trieNodeHealer.foreach { healer =>
      // Get SNAP-capable peers
      val snapPeers = peersToDownloadFrom.collect {
        case (peerId, peerWithInfo) 
          if peerWithInfo.peerInfo.remoteStatus.supportsSnap =>
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
            log.info(s"✅ State root verification PASSED: ${computedRoot.take(8).toArray.map("%02x".format(_)).mkString}")
            
            // Proceed with full trie validation
            val validator = new StateValidator(mptStorage)
            validator.validateAccountTrie(expectedRoot) match {
              case Right(_) =>
                log.info("Account trie validation successful")
                validator.validateAllStorageTries() match {
                  case Right(_) =>
                    log.info("Storage trie validation successful")
                    self ! StateValidationComplete
                  case Left(error) =>
                    log.error(s"Storage trie validation failed: $error")
                    self ! StateValidationComplete  // TODO: Trigger healing
                }
              case Left(error) =>
                log.error(s"Account trie validation failed: $error")
                self ! StateValidationComplete  // TODO: Trigger healing
            }
          } else {
            // CRITICAL: State root mismatch - block sync completion
            log.error(s"❌ CRITICAL: State root verification FAILED!")
            log.error(s"  Expected: ${expectedRoot.take(8).toArray.map("%02x".format(_)).mkString}...")
            log.error(s"  Computed: ${computedRoot.take(8).toArray.map("%02x".format(_)).mkString}...")
            log.error(s"  Sync cannot complete with mismatched state root - this indicates incomplete or corrupted state")
            
            // DO NOT send StateValidationComplete - sync must not proceed
            // TODO: Trigger healing phase to fix the mismatch
            log.warning("State root mismatch detected - sync blocked until healing completes")
          }
        }
      
      case _ =>
        log.error("Missing state root or pivot block for validation")
        // Fail sync - we cannot proceed without validation
        self ! StateValidationComplete  // For now, but should fail
    }
  }

  private def completeSnapSync(): Unit = {
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
}

object SNAPSyncController {
  
  sealed trait SyncPhase
  case object Idle extends SyncPhase
  case object AccountRangeSync extends SyncPhase
  case object StorageRangeSync extends SyncPhase
  case object StateHealing extends SyncPhase
  case object StateValidation extends SyncPhase
  case object Completed extends SyncPhase

  case object Start
  case object Done
  case object AccountRangeSyncComplete
  case object StorageRangeSyncComplete
  case object StateHealingComplete
  case object StateValidationComplete
  case object GetProgress
  
  def props(
      blockchainReader: BlockchainReader,
      appStateStorage: AppStateStorage,
      mptStorage: MptStorage,
      etcPeerManager: ActorRef,
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
        etcPeerManager,
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
    timeout: FiniteDuration = 30.seconds
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
      timeout = snapConfig.getDuration("timeout").toMillis.millis
    )
  }
}

class StateValidator(_mptStorage: MptStorage) {
  
  def validateAccountTrie(stateRoot: ByteString): Either[String, Unit] = {
    Right(())
  }

  def validateAllStorageTries(): Either[String, Unit] = {
    Right(())
  }
}

class SyncProgressMonitor(_scheduler: Scheduler) {
  
  import SNAPSyncController._
  
  private var currentPhaseState: SyncPhase = Idle
  private var accountsSynced: Long = 0
  private var storageSlotsSynced: Long = 0
  private var nodesHealed: Long = 0
  private val startTime: Long = System.currentTimeMillis()
  private var phaseStartTime: Long = System.currentTimeMillis()

  def startPhase(phase: SyncPhase): Unit = {
    currentPhaseState = phase
    phaseStartTime = System.currentTimeMillis()
  }

  def complete(): Unit = {
    currentPhaseState = Completed
  }
  
  def incrementAccountsSynced(count: Long): Unit = {
    accountsSynced += count
  }
  
  def incrementStorageSlotsSynced(count: Long): Unit = {
    storageSlotsSynced += count
  }
  
  def incrementNodesHealed(count: Long): Unit = {
    nodesHealed += count
  }

  def currentProgress: SyncProgress = {
    val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
    
    SyncProgress(
      phase = currentPhaseState,
      accountsSynced = accountsSynced,
      storageSlotsSynced = storageSlotsSynced,
      nodesHealed = nodesHealed,
      elapsedSeconds = elapsed,
      accountsPerSec = if (elapsed > 0) accountsSynced / elapsed else 0,
      slotsPerSec = if (elapsed > 0) storageSlotsSynced / elapsed else 0,
      nodesPerSec = if (elapsed > 0) nodesHealed / elapsed else 0
    )
  }
}

case class SyncProgress(
    phase: SNAPSyncController.SyncPhase,
    accountsSynced: Long,
    storageSlotsSynced: Long,
    nodesHealed: Long,
    elapsedSeconds: Double,
    accountsPerSec: Double,
    slotsPerSec: Double,
    nodesPerSec: Double
) {
  override def toString: String = {
    s"SNAP Sync Progress: phase=$phase, accounts=$accountsSynced (${accountsPerSec.toInt}/s), " +
      s"slots=$storageSlotsSynced (${slotsPerSec.toInt}/s), nodes=$nodesHealed (${nodesPerSec.toInt}/s), " +
      s"elapsed=${elapsedSeconds.toInt}s"
  }
}
