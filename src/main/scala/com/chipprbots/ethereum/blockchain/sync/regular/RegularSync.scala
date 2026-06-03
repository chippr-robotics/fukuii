package com.chipprbots.ethereum.blockchain.sync.regular

import org.apache.pekko.actor.Actor
import org.apache.pekko.actor.ActorLogging
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.AllForOneStrategy
import org.apache.pekko.actor.Cancellable
import org.apache.pekko.actor.Props
import org.apache.pekko.actor.Scheduler
import org.apache.pekko.actor.SupervisorStrategy
import org.apache.pekko.actor.typed.scaladsl.adapter._
import org.apache.pekko.actor.typed.{ActorRef => TypedActorRef}

import com.chipprbots.ethereum.blockchain.sync.Blacklist
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol.Status
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol.Status.Progress
import com.chipprbots.ethereum.blockchain.sync.regular.BlockFetcher.InternalLastBlockImport
import com.chipprbots.ethereum.blockchain.sync.regular.RegularSync.ProgressProtocol
import com.chipprbots.ethereum.blockchain.sync.regular.RegularSync.ProgressState
import com.chipprbots.ethereum.consensus.ConsensusAdapter
import com.chipprbots.ethereum.consensus.validators.BlockValidator
import com.chipprbots.ethereum.db.storage.{EvmCodeStorage, StateStorage}
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.ledger.BranchResolution
import com.chipprbots.ethereum.nodebuilder.BlockchainConfigBuilder
import com.chipprbots.ethereum.utils.Config.SyncConfig

import scala.concurrent.duration._

class RegularSync(
    peersClient: ActorRef,
    networkPeerManager: ActorRef,
    peerEventBus: ActorRef,
    consensus: ConsensusAdapter,
    blockchainReader: BlockchainReader,
    stateStorage: StateStorage,
    evmCodeStorage: EvmCodeStorage,
    branchResolution: BranchResolution,
    blockValidator: BlockValidator,
    blacklist: Blacklist,
    syncConfig: SyncConfig,
    ommersPool: ActorRef,
    pendingTransactionsManager: ActorRef,
    scheduler: Scheduler,
    configBuilder: BlockchainConfigBuilder
) extends Actor
    with ActorLogging {

  val fetcher: TypedActorRef[BlockFetcher.FetchCommand] =
    context.spawn(
      BlockFetcher(peersClient, peerEventBus, self, syncConfig, blockValidator),
      "block-fetcher"
    )

  context.watch(fetcher)

  val broadcaster: ActorRef = context.actorOf(
    BlockBroadcasterActor
      .props(
        new BlockBroadcast(networkPeerManager, isPoWChain = configBuilder.blockchainConfig.terminalTotalDifficulty.isEmpty),
        peerEventBus,
        networkPeerManager,
        blacklist,
        syncConfig,
        scheduler
      ),
    "block-broadcaster"
  )
  val importer: ActorRef =
    context.actorOf(
      BlockImporter.props(
        fetcher.toClassic,
        consensus,
        blockchainReader,
        stateStorage,
        evmCodeStorage,
        branchResolution,
        syncConfig,
        ommersPool,
        broadcaster,
        pendingTransactionsManager,
        self,
        configBuilder
      ),
      "block-importer"
    )

  val printFetcherSchedule: Cancellable =
    scheduler.scheduleWithFixedDelay(
      syncConfig.printStatusInterval,
      syncConfig.printStatusInterval,
      fetcher.toClassic,
      BlockFetcher.PrintStatus
    )(context.dispatcher, self)

  val printStatusSchedule: Cancellable =
    scheduler.scheduleWithFixedDelay(
      60.seconds,
      60.seconds,
      self,
      RegularSync.PrintStatus
    )(context.dispatcher, self)

  override def receive: Receive = running(
    ProgressState(startedFetching = false, initialBlock = 0, currentBlock = 0, bestKnownNetworkBlock = 0)
  )

  def running(progressState: ProgressState): Receive = {
    case SyncProtocol.Start =>
      log.info("Starting regular sync")
      importer ! BlockImporter.Start
    case SyncProtocol.MinedBlock(block) =>
      log.info(s"Block mined [number = {}, hash = {}]", block.number, block.header.hashAsHexString)
      importer ! BlockImporter.MinedBlock(block)

    case SyncProtocol.GetStatus =>
      sender() ! progressState.toStatus

    case ProgressProtocol.StartedFetching =>
      val newState = progressState.copy(startedFetching = true)
      context.become(running(newState))
    case ProgressProtocol.StartingFrom(blockNumber) =>
      val newState = progressState.copy(initialBlock = blockNumber, currentBlock = blockNumber)
      RegularSyncMetrics.setCurrentBlock(blockNumber)
      context.become(running(newState))
    case ProgressProtocol.GotNewBlock(blockNumber) =>
      log.debug(s"Got information about new block [number = $blockNumber]")
      val newState = progressState.copy(bestKnownNetworkBlock = blockNumber)
      RegularSyncMetrics.setBestKnownNetworkBlock(blockNumber)
      context.become(running(newState))
    case ProgressProtocol.ImportedBlock(blockNumber, internally) =>
      log.debug(s"Imported new block [number = $blockNumber, internally = $internally]")
      val newState = progressState.copy(currentBlock = blockNumber)
      RegularSyncMetrics.setCurrentBlock(blockNumber)
      RegularSyncMetrics.incrementBlocksImported()
      if (internally) {
        fetcher ! InternalLastBlockImport(blockNumber)
      }
      context.become(running(newState))

    case msg: SyncProtocol.RegularSyncStuck =>
      // Forward escape-valve signal to SyncController (our parent). BlockImporter detects this
      // condition and emits the message; we just relay it up so SyncController can re-trigger
      // SNAP sync from a recent pivot.
      log.warning(
        "Regular sync stuck on block {} (missing {}); forwarding to SyncController for SNAP re-sync",
        msg.blockNumber,
        msg.missingHash
      )
      context.parent ! msg

    case RegularSync.PrintStatus =>
      val lag = progressState.bestKnownNetworkBlock - progressState.currentBlock
      log.info(
        "RegularSync following head: current={} best={} lag={}",
        progressState.currentBlock,
        progressState.bestKnownNetworkBlock,
        lag
      )
  }

  override def supervisorStrategy: SupervisorStrategy = AllForOneStrategy()(SupervisorStrategy.defaultDecider)

  override def postStop(): Unit = {
    log.info("Regular Sync stopped")
    printFetcherSchedule.cancel()
    printStatusSchedule.cancel()
  }
}
object RegularSync {
  private[regular] case object PrintStatus

  // scalastyle:off parameter.number
  def props(
      peersClient: ActorRef,
      networkPeerManager: ActorRef,
      peerEventBus: ActorRef,
      consensus: ConsensusAdapter,
      blockchainReader: BlockchainReader,
      stateStorage: StateStorage,
      evmCodeStorage: EvmCodeStorage,
      branchResolution: BranchResolution,
      blockValidator: BlockValidator,
      blacklist: Blacklist,
      syncConfig: SyncConfig,
      ommersPool: ActorRef,
      pendingTransactionsManager: ActorRef,
      scheduler: Scheduler,
      configBuilder: BlockchainConfigBuilder
  ): Props =
    Props(
      new RegularSync(
        peersClient,
        networkPeerManager,
        peerEventBus,
        consensus,
        blockchainReader,
        stateStorage,
        evmCodeStorage,
        branchResolution,
        blockValidator,
        blacklist,
        syncConfig,
        ommersPool,
        pendingTransactionsManager,
        scheduler,
        configBuilder
      )
    )

  case class ProgressState(
      startedFetching: Boolean,
      initialBlock: BigInt,
      currentBlock: BigInt,
      bestKnownNetworkBlock: BigInt
  ) {
    def toStatus: SyncProtocol.Status =
      if (startedFetching && bestKnownNetworkBlock != 0 && currentBlock < bestKnownNetworkBlock) {
        Status.Syncing(initialBlock, Progress(currentBlock, bestKnownNetworkBlock), None)
      } else if (startedFetching && bestKnownNetworkBlock != 0 && currentBlock >= bestKnownNetworkBlock) {
        Status.SyncDone
      } else {
        Status.NotSyncing
      }
  }
  sealed trait ProgressProtocol
  object ProgressProtocol {
    case object StartedFetching extends ProgressProtocol
    case class StartingFrom(blockNumber: BigInt) extends ProgressProtocol
    case class GotNewBlock(blockNumber: BigInt) extends ProgressProtocol
    case class ImportedBlock(blockNumber: BigInt, internally: Boolean) extends ProgressProtocol
  }
}
