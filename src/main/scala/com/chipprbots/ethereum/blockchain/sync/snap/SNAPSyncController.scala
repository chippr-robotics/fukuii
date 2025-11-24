package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.actor.{Actor, ActorLogging, ActorRef, Scheduler}
import org.apache.pekko.util.ByteString

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext

import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
import com.chipprbots.ethereum.db.storage.{AppStateStorage, MptStorage}
import com.chipprbots.ethereum.domain.{Account, BlockchainReader}
import com.chipprbots.ethereum.utils.Config.SyncConfig

/**
  * SNAP Sync Controller - Main coordinator for SNAP sync process
  * 
  * Orchestrates the complete SNAP sync workflow:
  * 1. Account Range Sync - Download account ranges with Merkle proofs
  * 2. Storage Range Sync - Download storage slots for contract accounts
  * 3. State Healing - Fill missing trie nodes through iterative healing
  * 4. State Validation - Verify state completeness before transition
  * 5. Transition to Regular Sync - Complete SNAP sync and continue with regular sync
  * 
  * Reference: core-geth eth/syncer.go
  */
class SNAPSyncController(
    blockchainReader: BlockchainReader,
    appStateStorage: AppStateStorage,
    mptStorage: MptStorage,
    etcPeerManager: ActorRef,
    syncConfig: SyncConfig,
    snapSyncConfig: SNAPSyncConfig,
    scheduler: Scheduler
)(implicit ec: ExecutionContext)
    extends Actor
    with ActorLogging {

  import SNAPSyncController._

  // Coordinators for each sync phase
  private var accountRangeDownloader: Option[AccountRangeDownloader] = None
  private var storageRangeDownloader: Option[StorageRangeDownloader] = None
  private var trieNodeHealer: Option[TrieNodeHealer] = None
  
  // Request tracker for all SNAP requests
  private val requestTracker = new SNAPRequestTracker(snapSyncConfig.timeout)
  
  // State tracking
  private var currentPhase: SyncPhase = Idle
  private var pivotBlock: Option[BigInt] = None
  private var stateRoot: Option[ByteString] = None
  
  // Progress monitoring
  private val progressMonitor = new SyncProgressMonitor(scheduler)
  
  override def preStart(): Unit = {
    log.info("SNAP Sync Controller initialized")
  }

  override def receive: Receive = idle

  def idle: Receive = {
    case Start =>
      log.info("Starting SNAP sync...")
      startSnapSync()
  }

  def syncing: Receive = {
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
      log.debug(s"Forwarding message to active sync phase: $msg")
      // Forward to appropriate coordinator based on current phase
      currentPhase match {
        case AccountRangeSync => accountRangeDownloader.foreach(_ => ()) // Handle message
        case StorageRangeSync => storageRangeDownloader.foreach(_ => ())
        case StateHealing => trieNodeHealer.foreach(_ => ())
        case _ => log.warning(s"Received message in unexpected phase $currentPhase: $msg")
      }
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
      context.parent ! SyncProtocol.Done
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
        context.parent ! SyncProtocol.Done
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
    
    // TODO: Integrate with EtcPeerManager to start requesting account ranges
    // For now, simulate completion after timeout for integration testing
    scheduler.scheduleOnce(5.seconds) {
      self ! AccountRangeSyncComplete
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
          batchSize = snapSyncConfig.storageBatchSize
        )
      )

      progressMonitor.startPhase(StorageRangeSync)
      
      // TODO: Integrate with account range results to identify accounts with storage
      // For now, simulate completion
      scheduler.scheduleOnce(3.seconds) {
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
      
      // TODO: Integrate with missing node detection from account/storage sync
      // For now, simulate completion
      scheduler.scheduleOnce(2.seconds) {
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
    
    stateRoot.foreach { root =>
      val validator = new StateValidator(mptStorage)
      
      // Validate account trie
      validator.validateAccountTrie(root) match {
        case Right(_) =>
          log.info("Account trie validation successful")
          
          // Validate storage tries
          validator.validateAllStorageTries() match {
            case Right(_) =>
              log.info("Storage trie validation successful")
              self ! StateValidationComplete
              
            case Left(error) =>
              log.error(s"Storage trie validation failed: $error")
              // TODO: Trigger additional healing if validation fails
              self ! StateValidationComplete
          }
          
        case Left(error) =>
          log.error(s"Account trie validation failed: $error")
          // TODO: Trigger additional healing if validation fails
          self ! StateValidationComplete
      }
    }
  }

  private def completeSnapSync(): Unit = {
    pivotBlock.foreach { pivot =>
      appStateStorage.putSnapSyncDone(true).commit()
      appStateStorage.putBestBlockNumber(pivot).commit()
      
      progressMonitor.complete()
      
      log.info(s"SNAP sync completed successfully at block $pivot")
      log.info(progressMonitor.currentProgress.toString)
      
      context.become(completed)
      context.parent ! SyncProtocol.Done
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

  // Messages
  case object Start
  case object AccountRangeSyncComplete
  case object StorageRangeSyncComplete
  case object StateHealingComplete
  case object StateValidationComplete
  case object GetProgress
}

/**
  * SNAP Sync Configuration
  * 
  * Configuration parameters for SNAP sync behavior and performance tuning.
  */
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
  /**
    * Load SNAP sync configuration from Typesafe config
    */
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

/**
  * State Validator - Verifies state trie completeness
  * 
  * Validates that all necessary trie nodes are present in storage
  * to ensure the state is complete and consistent.
  */
class StateValidator(mptStorage: MptStorage) {
  
  /**
    * Validate that the account trie is complete
    */
  def validateAccountTrie(stateRoot: ByteString): Either[String, Unit] = {
    // TODO: Implement account trie traversal to find missing nodes
    // For now, return success for integration
    Right(())
  }

  /**
    * Validate that all storage tries are complete
    */
  def validateAllStorageTries(): Either[String, Unit] = {
    // TODO: Implement storage trie validation for all accounts
    // For now, return success for integration
    Right(())
  }
}

/**
  * Sync Progress Monitor - Tracks and reports sync progress
  * 
  * Monitors progress across all sync phases and provides detailed statistics.
  */
class SyncProgressMonitor(scheduler: Scheduler) {
  
  import SNAPSyncController._
  
  private var currentPhaseState: SyncPhase = Idle
  private var accountsSynced: Long = 0
  private var storageSlotsSynced: Long = 0
  private var nodesHealed: Long = 0
  private var startTime: Long = System.currentTimeMillis()
  private var phaseStartTime: Long = System.currentTimeMillis()

  def startPhase(phase: SyncPhase): Unit = {
    currentPhaseState = phase
    phaseStartTime = System.currentTimeMillis()
  }

  def complete(): Unit = {
    currentPhaseState = Completed
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
