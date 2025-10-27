package com.chipprbots.ethereum.blockchain.sync

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.PoisonPill
import akka.actor.Props
import akka.actor.Scheduler

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

  def runningRegularSync(regularSync: ActorRef): Receive = { case other =>
    regularSync.forward(other)
  }

  def start(): Unit = {
    import syncConfig.doFastSync

    appStateStorage.putSyncStartingBlock(appStateStorage.getBestBlockNumber()).commit()
    (appStateStorage.isFastSyncDone(), doFastSync) match {
      case (false, true) =>
        startFastSync()
      case (true, true) =>
        log.warning(
          s"do-fast-sync is set to $doFastSync but fast sync cannot start because it has already been completed"
        )
        startRegularSync()
      case (true, false) =>
        startRegularSync()
      case (false, false) =>
        // Check whether fast sync was started before
        if (fastSyncStateStorage.getSyncState().isDefined) {
          log.warning(
            s"do-fast-sync is set to $doFastSync but regular sync cannot start because fast sync hasn't completed"
          )
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
