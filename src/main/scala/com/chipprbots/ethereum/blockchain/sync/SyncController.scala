package com.chipprbots.ethereum.blockchain.sync

import org.apache.pekko.actor.Actor
import org.apache.pekko.actor.ActorLogging
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.PoisonPill
import org.apache.pekko.actor.Props
import org.apache.pekko.actor.Scheduler

import com.chipprbots.ethereum.blockchain.sync.fast.FastSync
import com.chipprbots.ethereum.blockchain.sync.regular.RegularSync
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

  def runningSnapSync(): Receive = {
    case SyncProtocol.Status.Progress(_, _) =>
      log.debug("SNAP sync in progress")
    
    case other =>
      log.warning(s"SNAP sync received unexpected message: $other")
      startRegularSync()
  }

  def runningRegularSync(regularSync: ActorRef): Receive = { case other =>
    regularSync.forward(other)
  }

  def start(): Unit = {
    import syncConfig.{doFastSync, doSnapSync}

    appStateStorage.putSyncStartingBlock(appStateStorage.getBestBlockNumber()).commit()
    
    (appStateStorage.isSnapSyncDone(), appStateStorage.isFastSyncDone(), doSnapSync, doFastSync) match {
      case (false, _, true, _) =>
        startSnapSync()
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
    log.info("SNAP sync mode selected - transitioning to regular sync (full implementation pending)")
    appStateStorage.snapSyncDone().commit()
    startRegularSync()
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
