package com.chipprbots.ethereum.blockchain.sync

import org.apache.pekko.actor.Actor
import org.apache.pekko.actor.ActorLogging
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.PoisonPill
import org.apache.pekko.actor.Props
import org.apache.pekko.actor.Scheduler

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

import com.chipprbots.ethereum.blockchain.sync.fast.FastSync
import com.chipprbots.ethereum.blockchain.sync.regular.RegularSync
import com.chipprbots.ethereum.blockchain.sync.snap.{SNAPSyncController, SNAPSyncConfig}
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.{StartRegularSyncBootstrap, BootstrapComplete}
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
    networkPeerManager: ActorRef,
    blacklist: Blacklist,
    syncConfig: SyncConfig,
    configBuilder: BlockchainConfigBuilder,
    externalSchedulerOpt: Option[Scheduler] = None
) extends Actor
    with ActorLogging {

  private case object RestartFastSyncNow

  private def stopSyncChildren(): Unit = {
    val names = Seq(
      "fast-sync",
      "regular-sync",
      "peers-client",
      "snap-sync",
      "regular-sync-bootstrap",
      "peers-client-bootstrap"
    )
    names.flatMap(context.child).foreach(_ ! PoisonPill)

    // Ensure snap-sync routing is not left pointing at a dead actor.
    networkPeerManager ! com.chipprbots.ethereum.network.NetworkPeerManagerActor.RegisterSnapSyncController(
      context.system.deadLetters
    )
  }

  private def handleResetFastSync(): Unit = {
    log.warning("ResetFastSync requested: clearing persisted fast-sync markers")
    appStateStorage.clearFastSyncDone().commit()
    fastSyncStateStorage.purge()
    sender() ! SyncProtocol.ResetFastSyncResponse(reset = true)
  }

  private def handleRestartFastSync(): Unit = {
    val nowMillis = System.currentTimeMillis()
    val cooldownUntil = appStateStorage.getFastSyncCooldownUntilMillis()

    if (cooldownUntil > nowMillis) {
      val delay = (cooldownUntil - nowMillis).millis
      log.warning(
        "RestartFastSync requested but circuit-breaker is open (cool-off {} remaining); scheduling restart",
        delay
      )
      scheduler.scheduleOnce(delay, self, RestartFastSyncNow)
      sender() ! SyncProtocol.RestartFastSyncResponse(started = false, cooldownUntilMillis = cooldownUntil)
    } else {
      self ! RestartFastSyncNow
      sender() ! SyncProtocol.RestartFastSyncResponse(started = true, cooldownUntilMillis = nowMillis)
    }
  }

  private def doRestartFastSyncNow(): Unit = {
    val nowMillis = System.currentTimeMillis()
    val cooldownUntil = nowMillis + syncConfig.fastSyncRestartCooloff.toMillis

    log.warning(
      "Restarting fast sync now (cool-off {}); stopping current sync actors and clearing fast-sync markers",
      syncConfig.fastSyncRestartCooloff
    )

    stopSyncChildren()
    appStateStorage.clearFastSyncDone().and(appStateStorage.putFastSyncCooldownUntilMillis(cooldownUntil)).commit()
    fastSyncStateStorage.purge()

    startFastSync()
  }

  def scheduler: Scheduler = externalSchedulerOpt.getOrElse(context.system.scheduler)

  /** Load SNAP sync configuration with fallback to defaults */
  private def loadSnapSyncConfig(): SNAPSyncConfig =
    try
      SNAPSyncConfig.fromConfig(Config.config.getConfig("sync"))
    catch {
      case e: Exception =>
        log.warning(s"Failed to load SNAP sync config, using defaults: ${e.getMessage}")
        SNAPSyncConfig()
    }

  override def receive: Receive = idle

  def idle: Receive = { case SyncProtocol.Start =>
    start()
  case SyncProtocol.ResetFastSync =>
    handleResetFastSync()
  case SyncProtocol.RestartFastSync =>
    handleRestartFastSync()
  case RestartFastSyncNow =>
    doRestartFastSyncNow()
  }

  def runningFastSync(fastSync: ActorRef): Receive = {
    case SyncProtocol.ResetFastSync =>
      handleResetFastSync()
    case SyncProtocol.RestartFastSync =>
      handleRestartFastSync()
    case RestartFastSyncNow =>
      doRestartFastSyncNow()
    case FastSync.Done =>
      fastSync ! PoisonPill

      // Open circuit-breaker for a cool-off period before allowing another fast-sync restart.
      val cooldownUntil = System.currentTimeMillis() + syncConfig.fastSyncRestartCooloff.toMillis
      appStateStorage.putFastSyncCooldownUntilMillis(cooldownUntil).commit()

      startRegularSync()

    case other => fastSync.forward(other)
  }

  def runningSnapSync(snapSync: ActorRef): Receive = {
    case SyncProtocol.ResetFastSync =>
      handleResetFastSync()
    case SyncProtocol.RestartFastSync =>
      handleRestartFastSync()
    case RestartFastSyncNow =>
      doRestartFastSyncNow()
    case StartRegularSyncBootstrap(targetBlock) =>
      log.info(s"SNAP sync requested bootstrap to pivot ${targetBlock}")

      // Prefer a header-only bootstrap: SNAP only needs the pivot header (stateRoot).
      // Falling back to full regular sync bootstrap if we can't fetch/store the header.
      val peersClient =
        context.actorOf(
          PeersClient.props(networkPeerManager, peerEventBus, blacklist, syncConfig, scheduler),
          "peers-client-bootstrap"
        )
      val headerBootstrap =
        context.actorOf(
          PivotHeaderBootstrap.props(peersClient, blockchainWriter, targetBlock, syncConfig, scheduler),
          "pivot-header-bootstrap"
        )

      context.become(runningPivotHeaderBootstrap(peersClient, headerBootstrap, targetBlock, snapSync))
    
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

  def runningRegularSync(regularSync: ActorRef): Receive = { case other =>
    other match {
      case SyncProtocol.ResetFastSync =>
        handleResetFastSync()
      case SyncProtocol.RestartFastSync =>
        handleRestartFastSync()
      case RestartFastSyncNow =>
        doRestartFastSyncNow()
      case msg =>
        regularSync.forward(msg)
    }
  }

  def runningRegularSyncBootstrap(regularSync: ActorRef, targetBlock: BigInt, originalSnapSyncRef: ActorRef): Receive = {
    case SyncProtocol.ResetFastSync =>
      handleResetFastSync()
    case SyncProtocol.RestartFastSync =>
      handleRestartFastSync()
    case RestartFastSyncNow =>
      doRestartFastSyncNow()
    case RegularSync.ProgressProtocol.ImportedBlock(blockNumber, _) =>
      log.debug(s"Bootstrap progress: block $blockNumber / $targetBlock")
      
      if (blockNumber >= targetBlock) {
        log.info(s"Bootstrap target ${targetBlock} reached - transitioning to SNAP sync")
        
        // Stop regular sync
        regularSync ! PoisonPill
        
        // Notify SNAP sync controller that bootstrap is complete, including the pivot header if available.
        blockchainReader.getBlockHeaderByNumber(targetBlock) match {
          case Some(header) =>
            originalSnapSyncRef ! BootstrapComplete(Some(header))
          case None =>
            log.warning(s"Bootstrap reached target $targetBlock but pivot header not found locally; notifying SNAP without header")
            originalSnapSyncRef ! BootstrapComplete()
        }
        
        // Switch back to runningSnapSync state
        context.become(runningSnapSync(originalSnapSyncRef))
      }
    
    case SyncProtocol.GetStatus =>
      // Forward status requests to regular sync
      regularSync.forward(SyncProtocol.GetStatus)
    
    case other => 
      regularSync.forward(other)
  }

  def runningPivotHeaderBootstrap(
      peersClient: ActorRef,
      headerBootstrap: ActorRef,
      targetBlock: BigInt,
      originalSnapSyncRef: ActorRef
  ): Receive = {
    case SyncProtocol.ResetFastSync =>
      handleResetFastSync()
    case SyncProtocol.RestartFastSync =>
      handleRestartFastSync()
    case RestartFastSyncNow =>
      doRestartFastSyncNow()

    case PivotHeaderBootstrap.Completed(block, header) if block == targetBlock =>
      log.info(s"Pivot header bootstrap complete for block $targetBlock - notifying SNAP sync")
      headerBootstrap ! PoisonPill
      peersClient ! PoisonPill
      originalSnapSyncRef ! BootstrapComplete(Some(header))
      context.become(runningSnapSync(originalSnapSyncRef))

    case PivotHeaderBootstrap.Failed(reason) =>
      log.warning(s"Pivot header bootstrap failed (reason: $reason). Falling back to regular sync bootstrap")
      headerBootstrap ! PoisonPill
      peersClient ! PoisonPill
      val regularSync = startRegularSyncForBootstrap()
      context.become(runningRegularSyncBootstrap(regularSync, targetBlock, originalSnapSyncRef))

    case SyncProtocol.GetStatus =>
      // Expose progress as a generic syncing state.
      sender() ! SyncProtocol.Status.Syncing(
        startingBlockNumber = appStateStorage.getSyncStartingBlock(),
        blocksProgress = SyncProtocol.Status.Progress(appStateStorage.getBestBlockNumber(), targetBlock),
        stateNodesProgress = None
      )

    case other =>
      // Ignore unrelated messages while we bootstrap the pivot header.
      log.debug("Ignoring message during pivot header bootstrap: {}", other.getClass.getSimpleName)
  }

  def start(): Unit = {
    import syncConfig.{doFastSync, doSnapSync}

    val nowMillis = System.currentTimeMillis()

    appStateStorage.putSyncStartingBlock(appStateStorage.getBestBlockNumber()).commit()

    // If fast sync is desired but the circuit-breaker is open, start regular sync for now and
    // schedule an in-process restart of fast sync once the cool-off expires.
    if (doFastSync && appStateStorage.isFastSyncCoolingOff(nowMillis)) {
      val until = appStateStorage.getFastSyncCooldownUntilMillis()
      val delay = (until - nowMillis).millis
      log.warning(
        "Fast sync requested but in cool-off until {} ({} remaining); starting regular sync and scheduling fast-sync restart",
        until,
        delay
      )
      startRegularSync()
      scheduler.scheduleOnce(delay, self, RestartFastSyncNow)
      return
    }

    (appStateStorage.isSnapSyncDone(), appStateStorage.isFastSyncDone(), doSnapSync, doFastSync) match {
      case (false, _, true, _) =>
        // SNAP sync requested - just start it
        // It will fall back to fast sync if needed
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
        networkPeerManager,
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
        networkPeerManager,
        peerEventBus,
        syncConfig,
        snapSyncConfig,
        scheduler
      ),
      "snap-sync"
    )

    // Register SNAPSyncController with NetworkPeerManagerActor for message routing
    networkPeerManager ! com.chipprbots.ethereum.network.NetworkPeerManagerActor.RegisterSnapSyncController(snapSync)

    snapSync ! SNAPSyncController.Start
    context.become(runningSnapSync(snapSync))
  }

  def startRegularSync(): Unit = {
    val peersClient =
      context.actorOf(
        PeersClient.props(networkPeerManager, peerEventBus, blacklist, syncConfig, scheduler),
        "peers-client"
      )
    val regularSync = context.actorOf(
      RegularSync.props(
        peersClient,
        networkPeerManager,
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

  def startRegularSyncForBootstrap(): ActorRef = {
    log.info("Starting regular sync for SNAP sync bootstrap")
    
    val peersClient =
      context.actorOf(
        PeersClient.props(networkPeerManager, peerEventBus, blacklist, syncConfig, scheduler),
        "peers-client-bootstrap"
      )
    val regularSync = context.actorOf(
      RegularSync.props(
        peersClient,
        networkPeerManager,
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
      "regular-sync-bootstrap"
    )
    regularSync ! SyncProtocol.Start
    regularSync
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
      networkPeerManager: ActorRef,
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
        networkPeerManager,
        blacklist,
        syncConfig,
        configBuilder
      )
    )
}
