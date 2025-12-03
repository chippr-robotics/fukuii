package com.chipprbots.ethereum.blockchain.sync

import org.apache.pekko.actor.Actor
import org.apache.pekko.actor.ActorLogging
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.PoisonPill
import org.apache.pekko.actor.Props
import org.apache.pekko.actor.Scheduler

import scala.concurrent.ExecutionContext.Implicits.global

import com.chipprbots.ethereum.blockchain.sync.fast.FastSync
import com.chipprbots.ethereum.blockchain.sync.regular.RegularSync
import com.chipprbots.ethereum.blockchain.sync.snap.{SNAPSyncController, SNAPSyncConfig}
import com.chipprbots.ethereum.consensus.ConsensusAdapter
import com.chipprbots.ethereum.consensus.validators.Validators
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.BlockNumberMappingStorage
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.FastSyncStateStorage
import com.chipprbots.ethereum.db.storage.NodeStorage
import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.domain.Blockchain
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.BlockchainWriter
import com.chipprbots.ethereum.ledger.BranchResolution
import com.chipprbots.ethereum.nodebuilder.BlockchainConfigBuilder
import com.chipprbots.ethereum.utils.Config
import com.chipprbots.ethereum.utils.Config.SyncConfig

class SyncController(
    blockchain: Blockchain,
    blockchainReader: BlockchainReader,
    blockchainWriter: BlockchainWriter,
    appStateStorage: AppStateStorage,
    blockNumberMappingStorage: BlockNumberMappingStorage,
    evmCodeStorage: EvmCodeStorage,
    stateStorage: StateStorage,
    nodeStorage: NodeStorage,
    fastSyncStateStorage: FastSyncStateStorage,
    consensus: ConsensusAdapter,
    validators: Validators,
    peerEventBus: ActorRef,
    pendingTransactionsManager: ActorRef,
    ommersPool: ActorRef,
    etcPeerManager: ActorRef,
    blacklist: Blacklist,
    syncConfig: SyncConfig,
    configBuilder: BlockchainConfigBuilder,
    externalSchedulerOpt: Option[Scheduler] = None
) extends Actor
    with ActorLogging {

  def scheduler: Scheduler = externalSchedulerOpt.getOrElse(context.system.scheduler)

  /** Load SNAP sync configuration with fallback to defaults */
  private def loadSnapSyncConfig(): SNAPSyncConfig = {
    try {
      SNAPSyncConfig.fromConfig(Config.config.getConfig("sync"))
    } catch {
      case e: Exception =>
        log.warning(s"Failed to load SNAP sync config, using defaults: ${e.getMessage}")
        SNAPSyncConfig()
    }
  }

  override def receive: Receive = idle

  def idle: Receive = { case SyncProtocol.Start =>
    start()
  }

  def runningFastSync(fastSync: ActorRef): Receive = {
    case FastSync.Done =>
      fastSync ! PoisonPill
      startRegularSync()

    case other => fastSync.forward(other)
  }

  def runningSnapSync(snapSync: ActorRef): Receive = {
    case com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.Done =>
      snapSync ! PoisonPill
      log.info("SNAP sync completed, transitioning to regular sync")
      startRegularSync()
    
    case com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.FallbackToFastSync =>
      snapSync ! PoisonPill
      log.warning("SNAP sync failed repeatedly, falling back to fast sync")
      startFastSync()
    
    case SyncProtocol.Status.Progress(_, _) =>
      log.debug("SNAP sync in progress")
    
    case msg =>
      snapSync.forward(msg)
  }

  def runningRegularSync(regularSync: ActorRef): Receive = {
    case msg @ SyncProtocol.Status.Progress(blocksProgress, stateNodesProgress) =>
      // Check if we should transition from bootstrap regular sync to SNAP sync
      appStateStorage.getSnapSyncBootstrapTarget() match {
        case Some(targetBlock) if appStateStorage.getBestBlockNumber() >= targetBlock =>
          log.info(s"Bootstrap target block $targetBlock reached (current: ${appStateStorage.getBestBlockNumber()})")
          log.info("Transitioning from regular sync to SNAP sync")
          // Clear the bootstrap target
          appStateStorage.clearSnapSyncBootstrapTarget().commit()
          // Stop regular sync and start SNAP sync
          regularSync ! PoisonPill
          startSnapSync()
        case _ =>
          // No transition needed, message is already handled by regularSync
          ()
      }
    
    case other =>
      regularSync.forward(other)
  }

  def start(): Unit = {
    import syncConfig.{doFastSync, doSnapSync}

    appStateStorage.putSyncStartingBlock(appStateStorage.getBestBlockNumber()).commit()
    
    val bestBlockNumber = appStateStorage.getBestBlockNumber()
    
    (appStateStorage.isSnapSyncDone(), appStateStorage.isFastSyncDone(), doSnapSync, doFastSync) match {
      case (false, _, true, _) =>
        // Check if we have enough blocks to start SNAP sync
        // If not, we need to bootstrap with regular/fast sync first
        val snapSyncConfig = loadSnapSyncConfig()
        
        val minRequiredBlocks = snapSyncConfig.pivotBlockOffset + 1
        if (bestBlockNumber < minRequiredBlocks) {
          log.info(s"SNAP sync requires at least $minRequiredBlocks blocks, but only $bestBlockNumber blocks available")
          log.info(s"Starting hybrid sync: will sync to block $minRequiredBlocks using regular sync, then engage SNAP sync")
          // Store the intent to switch to SNAP sync later
          appStateStorage.putSnapSyncBootstrapTarget(minRequiredBlocks).commit()
          startRegularSync()
        } else {
          startSnapSync()
        }
      case (true, _, true, _) =>
        log.warning("do-snap-sync is true but SNAP sync already completed")
        startRegularSync()
      case (_, false, false, true) =>
        startFastSync()
      case (_, true, false, true) =>
        log.warning("do-fast-sync is true but fast sync already completed")
        startRegularSync()
      case (_, true, false, false) =>
        startRegularSync()
      case (_, false, false, false) =>
        if (fastSyncStateStorage.getSyncState().isDefined) {
          log.warning("do-fast-sync is false but fast sync hasn't completed")
          startFastSync()
        } else
          startRegularSync()
    }
  }

  def startFastSync(): Unit = {
    val fastSync = context.actorOf(
      FastSync.props(
        fastSyncStateStorage,
        appStateStorage,
        blockNumberMappingStorage,
        blockchain,
        blockchainReader,
        blockchainWriter,
        evmCodeStorage,
        stateStorage,
        nodeStorage,
        validators,
        peerEventBus,
        etcPeerManager,
        blacklist,
        syncConfig,
        scheduler,
        configBuilder
      ),
      "fast-sync"
    )
    fastSync ! SyncProtocol.Start
    context.become(runningFastSync(fastSync))
  }

  def startSnapSync(): Unit = {
    log.info("Starting SNAP sync mode")
    
    val snapSyncConfig = loadSnapSyncConfig()
    
    val mptStorage = stateStorage.getReadOnlyStorage
    
    val snapSync = context.actorOf(
      SNAPSyncController.props(
        blockchainReader,
        appStateStorage,
        mptStorage,
        evmCodeStorage,
        etcPeerManager,
        peerEventBus,
        syncConfig,
        snapSyncConfig,
        scheduler
      ),
      "snap-sync"
    )
    
    // Register SNAPSyncController with EtcPeerManagerActor for message routing
    etcPeerManager ! com.chipprbots.ethereum.network.EtcPeerManagerActor.RegisterSnapSyncController(snapSync)
    
    snapSync ! SNAPSyncController.Start
    context.become(runningSnapSync(snapSync))
  }

  def startRegularSync(): Unit = {
    val peersClient =
      context.actorOf(PeersClient.props(etcPeerManager, peerEventBus, blacklist, syncConfig, scheduler), "peers-client")
    val regularSync = context.actorOf(
      RegularSync.props(
        peersClient,
        etcPeerManager,
        peerEventBus,
        consensus,
        blockchainReader,
        stateStorage,
        new BranchResolution(blockchainReader),
        validators.blockValidator,
        blacklist,
        syncConfig,
        ommersPool,
        pendingTransactionsManager,
        scheduler,
        configBuilder
      ),
      "regular-sync"
    )
    regularSync ! SyncProtocol.Start
    context.become(runningRegularSync(regularSync))
  }

}

object SyncController {
  // scalastyle:off parameter.number
  def props(
      blockchain: Blockchain,
      blockchainReader: BlockchainReader,
      blockchainWriter: BlockchainWriter,
      appStateStorage: AppStateStorage,
      blockNumberMappingStorage: BlockNumberMappingStorage,
      evmCodeStorage: EvmCodeStorage,
      stateStorage: StateStorage,
      nodeStorage: NodeStorage,
      syncStateStorage: FastSyncStateStorage,
      consensus: ConsensusAdapter,
      validators: Validators,
      peerEventBus: ActorRef,
      pendingTransactionsManager: ActorRef,
      ommersPool: ActorRef,
      etcPeerManager: ActorRef,
      blacklist: Blacklist,
      syncConfig: SyncConfig,
      configBuilder: BlockchainConfigBuilder
  ): Props =
    Props(
      new SyncController(
        blockchain,
        blockchainReader,
        blockchainWriter,
        appStateStorage,
        blockNumberMappingStorage,
        evmCodeStorage,
        stateStorage,
        nodeStorage,
        syncStateStorage,
        consensus,
        validators,
        peerEventBus,
        pendingTransactionsManager,
        ommersPool,
        etcPeerManager,
        blacklist,
        syncConfig,
        configBuilder
      )
    )
}
