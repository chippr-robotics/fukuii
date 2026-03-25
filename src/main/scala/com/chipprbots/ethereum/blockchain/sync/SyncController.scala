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
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.{
  StartRegularSyncBootstrap,
  BootstrapComplete,
  PivotBootstrapFailed
}
import com.chipprbots.ethereum.consensus.ConsensusAdapter
import com.chipprbots.ethereum.consensus.mess.MESSConfig
import com.chipprbots.ethereum.consensus.validators.Validators
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.BlockNumberMappingStorage
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.FastSyncStateStorage
import com.chipprbots.ethereum.db.storage.FlatSlotStorage
import com.chipprbots.ethereum.db.storage.NodeStorage
import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.domain.Blockchain
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.BlockchainWriter
import com.chipprbots.ethereum.ledger.BranchResolution
import com.chipprbots.ethereum.ledger.StatePrefetcher
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
    flatSlotStorage: FlatSlotStorage,
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
    messConfig: Option[MESSConfig] = None,
    externalSchedulerOpt: Option[Scheduler] = None
) extends Actor
    with ActorLogging {

  private case object RestartFastSyncNow
  private case object PollRecoveryPeers

  // Generation counters for actor names to prevent Pekko name collisions
  // (context.stop is async — new actors can race with still-stopping ones).
  private var bootstrapGeneration: Long = 0
  private var syncGeneration: Long = 0

  // SNAP<->Fast sync bounce cycle counter, persisted across restarts.
  private var snapFastCycleCount: Int = appStateStorage.getSnapFastCycleCount()

  private val statePrefetcher = new StatePrefetcher(blockchain, blockchainReader, evmCodeStorage)

  private def stopSyncChildren(): Unit = {
    // Stop all sync-related child actors. Names may have generation suffixes
    // (e.g. "fast-sync-3") because PoisonPill is async and a new actor can
    // race with a still-stopping one.
    val prefixes = Seq(
      "fast-sync",
      "regular-sync",
      "peers-client",
      "snap-sync"
    )
    context.children
      .filter { child =>
        val n = child.path.name
        prefixes.exists(p => n == p || n.startsWith(s"$p-"))
      }
      .foreach(_ ! PoisonPill)

    // Stop any generation-numbered bootstrap children
    context.children
      .filter { child =>
        val n = child.path.name
        n.startsWith("peers-client-bootstrap") || n.startsWith("pivot-header-bootstrap")
      }
      .foreach(_ ! PoisonPill)

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

  def idle: Receive = {
    case SyncProtocol.Start =>
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

      resetSnapFastCycleCount()
      startRegularSync()

    case FastSync.FallbackToSnapSync =>
      fastSync ! PoisonPill
      log.warning("Fast sync detected ETH68-only network (no GetNodeData support), falling back to SNAP sync")
      snapFastCycleCount += 1
      appStateStorage.putSnapFastCycleCount(snapFastCycleCount).commit()
      log.info("SNAP<->Fast cycle count: {}", snapFastCycleCount)
      if (!checkSnapFastEscapeHatch()) {
        startSnapSync()
      }

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
      bootstrapGeneration += 1
      val gen = bootstrapGeneration
      val peersClient =
        context.actorOf(
          PeersClient.props(networkPeerManager, peerEventBus, blacklist, syncConfig, scheduler),
          s"peers-client-bootstrap-$gen"
        )
      val headerBootstrap =
        context.actorOf(
          PivotHeaderBootstrap.props(peersClient, blockchainWriter, targetBlock, syncConfig, scheduler, preferSnapPeers = true),
          s"pivot-header-bootstrap-$gen"
        )

      context.become(runningPivotHeaderBootstrap(peersClient, headerBootstrap, targetBlock, snapSync))

    case com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.Done =>
      snapSync ! PoisonPill
      log.info("SNAP sync completed, transitioning to regular sync")
      resetSnapFastCycleCount()
      startRegularSync()

    case com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.FallbackToFastSync =>
      snapSync ! PoisonPill
      log.warning("SNAP sync failed repeatedly, falling back to fast sync")
      snapFastCycleCount += 1
      appStateStorage.putSnapFastCycleCount(snapFastCycleCount).commit()
      log.info("SNAP<->Fast cycle count: {}", snapFastCycleCount)
      if (!checkSnapFastEscapeHatch()) {
        startFastSync()
      }

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

  def runningRegularSyncBootstrap(
      regularSync: ActorRef,
      targetBlock: BigInt,
      originalSnapSyncRef: ActorRef
  ): Receive = {
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
            log.warning(
              s"Bootstrap reached target $targetBlock but pivot header not found locally; notifying SNAP without header"
            )
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
      log.warning(s"Pivot header bootstrap failed (reason: $reason). Notifying SNAP sync controller.")
      headerBootstrap ! PoisonPill
      peersClient ! PoisonPill
      originalSnapSyncRef ! PivotBootstrapFailed(reason)
      context.become(runningSnapSync(originalSnapSyncRef))

    case SyncProtocol.GetStatus =>
      // Expose progress as a generic syncing state.
      sender() ! SyncProtocol.Status.Syncing(
        startingBlockNumber = appStateStorage.getSyncStartingBlock(),
        blocksProgress = SyncProtocol.Status.Progress(appStateStorage.getBestBlockNumber(), targetBlock),
        stateNodesProgress = None
      )

    case StartRegularSyncBootstrap(newTargetBlock) =>
      // A new bootstrap request arrived while one is already in progress.
      // Stop stale bootstrap actors and start fresh ones.
      log.info(
        s"New pivot header bootstrap requested for block $newTargetBlock (was $targetBlock). Restarting bootstrap."
      )
      headerBootstrap ! PoisonPill
      peersClient ! PoisonPill
      bootstrapGeneration += 1
      val gen = bootstrapGeneration
      val newPeersClient =
        context.actorOf(
          PeersClient.props(networkPeerManager, peerEventBus, blacklist, syncConfig, scheduler),
          s"peers-client-bootstrap-$gen"
        )
      val newHeaderBootstrap =
        context.actorOf(
          PivotHeaderBootstrap.props(newPeersClient, blockchainWriter, newTargetBlock, syncConfig, scheduler, preferSnapPeers = true),
          s"pivot-header-bootstrap-$gen"
        )
      context.become(
        runningPivotHeaderBootstrap(newPeersClient, newHeaderBootstrap, newTargetBlock, originalSnapSyncRef)
      )

    case com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.FallbackToFastSync =>
      log.warning("Received FallbackToFastSync during pivot header bootstrap. Stopping bootstrap and falling back.")
      headerBootstrap ! PoisonPill
      peersClient ! PoisonPill
      originalSnapSyncRef ! PoisonPill
      snapFastCycleCount += 1
      appStateStorage.putSnapFastCycleCount(snapFastCycleCount).commit()
      log.info("SNAP<->Fast cycle count: {}", snapFastCycleCount)
      if (!checkSnapFastEscapeHatch()) {
        startFastSync()
      }

    case com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.Done =>
      log.info(
        "Received Done from SNAP sync during pivot header bootstrap. Stopping bootstrap and transitioning to regular sync."
      )
      headerBootstrap ! PoisonPill
      peersClient ! PoisonPill
      originalSnapSyncRef ! PoisonPill
      resetSnapFastCycleCount()
      startRegularSync()

    case msg =>
      // Forward coordinator and protocol messages to SNAP sync during the brief bootstrap.
      // This keeps coordinators functional while we fetch the pivot header (~1-5 seconds).
      originalSnapSyncRef.forward(msg)
  }

  /** Check if the SNAP<->Fast bounce cycle count has exceeded the configured threshold. If so, mark both sync modes as
    * done and escape to regular sync.
    * @return
    *   true if the escape hatch fired (caller should NOT start another sync), false otherwise
    */
  private def checkSnapFastEscapeHatch(): Boolean = {
    val threshold = syncConfig.maxSnapFastCycleTransitions
    if (threshold > 0 && snapFastCycleCount >= threshold) {
      log.warning(
        "SNAP<->Fast sync bounce cycle count ({}) reached threshold ({}). " +
          "Escaping to regular sync — missing state will be fetched on-demand via GetTrieNodes.",
        snapFastCycleCount,
        threshold
      )
      // Mark both sync modes as done so they won't restart
      appStateStorage.snapSyncDone().commit()
      appStateStorage.fastSyncDone().commit()
      // Purge persisted fast sync state to prevent stale state on next restart
      fastSyncStateStorage.purge()
      // Reset cycle count
      resetSnapFastCycleCount()
      startRegularSync()
      true
    } else false
  }

  private def resetSnapFastCycleCount(): Unit = {
    snapFastCycleCount = 0
    appStateStorage.clearSnapFastCycleCount().commit()
  }

  def start(): Unit = {
    import syncConfig.{doFastSync, doSnapSync}

    val nowMillis = System.currentTimeMillis()

    appStateStorage.putSyncStartingBlock(appStateStorage.getBestBlockNumber()).commit()

    // Load bootstrap checkpoints if enabled and DB is fresh (best block = 0).
    // The highest checkpoint becomes the bootstrap pivot — SNAPSyncController uses it
    // for peer filtering and pivot selection, bypassing the peer discovery delay.
    if (syncConfig.useBootstrapCheckpoints && appStateStorage.getBestBlockNumber() == 0) {
      val checkpoints = syncConfig.bootstrapCheckpoints
      if (checkpoints.nonEmpty) {
        val (highestBlock, highestHash) = checkpoints.maxBy(_._1)
        val existingPivot = appStateStorage.getBootstrapPivotBlock()
        if (existingPivot == 0 || highestBlock > existingPivot) {
          import org.apache.pekko.util.ByteString
          val hashBytes = ByteString(com.chipprbots.ethereum.utils.Hex.decode(highestHash.stripPrefix("0x")))
          appStateStorage.putBootstrapPivotBlock(highestBlock, hashBytes).commit()
          log.info(
            s"Loaded bootstrap checkpoint: block $highestBlock (${highestHash.take(10)}...) " +
              s"from ${checkpoints.size} configured checkpoints"
          )
        } else {
          log.info(s"Bootstrap checkpoint already loaded (block $existingPivot), skipping")
        }
      }
    }

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
        // Diagnostic: log stored SNAP sync state root vs pivot block state root
        val snapStateRoot = appStateStorage.getSnapSyncStateRoot()
        val bestBlockNum = appStateStorage.getBestBlockNumber()
        val bestBlockHeader = blockchainReader.getBlockHeaderByNumber(bestBlockNum)
        val pivotStateRoot = bestBlockHeader.map(_.stateRoot)
        log.info(
          "SNAP state root diagnostic: stored snapStateRoot={}, pivotBlockStateRoot={}, bestBlock={}, match={}",
          snapStateRoot.map(r => r.take(8).toArray.map("%02x".format(_)).mkString).getOrElse("none"),
          pivotStateRoot.map(r => r.take(8).toArray.map("%02x".format(_)).mkString).getOrElse("none"),
          bestBlockNum,
          snapStateRoot == pivotStateRoot
        )
        // After SNAP sync with deferred merkleization + pivot refreshes, the finalized trie root
        // may differ from the pivot block header's stateRoot. The trie nodes are stored under
        // the finalized root's hash, but the pivot header references the original (now orphaned) root.
        // Fix: substitute the finalized root into the pivot block header.
        bestBlockHeader.foreach { header =>
          val mptStorage = stateStorage.getReadOnlyStorage
          val pivotRootExists = try { mptStorage.get(header.stateRoot.toArray); true } catch { case _: Exception => false }
          log.info(
            "State root availability check: pivotRoot({})={}",
            header.stateRoot.take(8).toArray.map("%02x".format(_)).mkString,
            if (pivotRootExists) "EXISTS" else "MISSING"
          )
          if (!pivotRootExists) {
            val finalizedRoot = appStateStorage.getSnapSyncFinalizedRoot()
            finalizedRoot match {
              case Some(fRoot) =>
                val fRootExists = try { mptStorage.get(fRoot.toArray); true } catch { case _: Exception => false }
                log.info(
                  "Finalized trie root {} availability: {}",
                  fRoot.take(8).toArray.map("%02x".format(_)).mkString,
                  if (fRootExists) "EXISTS" else "MISSING"
                )
                if (fRootExists) {
                  log.warning(
                    "Substituting finalized trie root {} into pivot block header (replacing missing root {})",
                    fRoot.take(8).toArray.map("%02x".format(_)).mkString,
                    header.stateRoot.take(8).toArray.map("%02x".format(_)).mkString
                  )
                  val updatedHeader = header.copy(stateRoot = fRoot)
                  blockchainWriter.storeBlockHeader(updatedHeader).commit()
                }
              case None =>
                log.error(
                  "Pivot state root {} MISSING and no finalized root stored! " +
                    "Database is in an unrecoverable state — clear data and re-sync.",
                  header.stateRoot.take(8).toArray.map("%02x".format(_)).mkString
                )
            }
          }
        }
        val needBytecode = !appStateStorage.isBytecodeRecoveryDone()
        val needStorage = !appStateStorage.isStorageRecoveryDone()
        if (needBytecode || needStorage) {
          startRecovery(needBytecode, needStorage)
        } else {
          startRegularSync()
        }
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
    syncGeneration += 1
    val fastSync = context.actorOf(
      FastSync
        .props(
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
        )
        .withDispatcher("sync-dispatcher"),
      s"fast-sync-$syncGeneration"
    )
    fastSync ! SyncProtocol.Start
    context.become(runningFastSync(fastSync))
  }

  def startSnapSync(): Unit = {
    log.info("Starting SNAP sync mode")
    syncGeneration += 1

    val snapSyncConfig = loadSnapSyncConfig()

    val snapSync = context.actorOf(
      SNAPSyncController
        .props(
          blockchainReader,
          blockchainWriter,
          appStateStorage,
          stateStorage,
          evmCodeStorage,
          flatSlotStorage,
          networkPeerManager,
          peerEventBus,
          syncConfig,
          snapSyncConfig,
          scheduler
        )
        .withDispatcher("sync-dispatcher"),
      s"snap-sync-$syncGeneration"
    )

    // Register SNAPSyncController with NetworkPeerManagerActor for message routing
    networkPeerManager ! com.chipprbots.ethereum.network.NetworkPeerManagerActor.RegisterSnapSyncController(snapSync)

    snapSync ! SNAPSyncController.Start
    context.become(runningSnapSync(snapSync))
  }

  def startRegularSync(): Unit = {
    syncGeneration += 1
    val peersClient =
      context.actorOf(
        PeersClient
          .props(networkPeerManager, peerEventBus, blacklist, syncConfig, scheduler)
          .withDispatcher("sync-dispatcher"),
        s"peers-client-$syncGeneration"
      )
    val regularSync = context.actorOf(
      RegularSync
        .props(
          peersClient,
          networkPeerManager,
          peerEventBus,
          consensus,
          blockchainReader,
          stateStorage,
          { val br = new BranchResolution(blockchainReader); br.messConfig = messConfig; br },
          validators.blockValidator,
          blacklist,
          syncConfig,
          ommersPool,
          pendingTransactionsManager,
          scheduler,
          configBuilder,
          Some(statePrefetcher)
        )
        .withDispatcher("sync-dispatcher"),
      s"regular-sync-$syncGeneration"
    )
    regularSync ! SyncProtocol.Start
    context.become(runningRegularSync(regularSync))
  }

  def startRecovery(needBytecode: Boolean, needStorage: Boolean): Unit = {
    syncGeneration += 1
    val stateRootOpt = appStateStorage.getSnapSyncStateRoot()
    val pivotBlockOpt = appStateStorage.getSnapSyncPivotBlock()

    (stateRootOpt, pivotBlockOpt) match {
      case (Some(stateRoot), Some(pivotBlock)) =>
        log.info(
          s"Starting SNAP recovery (bytecode=$needBytecode, storage=$needStorage, " +
            s"stateRoot=${stateRoot.take(4).toArray.map("%02x".format(_)).mkString}..., pivotBlock=$pivotBlock)"
        )

        val bytecodeActor = if (needBytecode) {
          Some(
            context.actorOf(
              BytecodeRecoveryActor
                .props(
                  stateRoot = stateRoot,
                  stateStorage = stateStorage,
                  evmCodeStorage = evmCodeStorage,
                  appStateStorage = appStateStorage,
                  networkPeerManager = networkPeerManager,
                  syncController = self,
                  pivotBlockNumber = pivotBlock
                )
                .withDispatcher("sync-dispatcher"),
              s"bytecode-recovery-$syncGeneration"
            )
          )
        } else None

        val storageActor = if (needStorage) {
          val snapSyncConfig = loadSnapSyncConfig()
          Some(
            context.actorOf(
              StorageRecoveryActor
                .props(
                  stateRoot = stateRoot,
                  stateStorage = stateStorage,
                  appStateStorage = appStateStorage,
                  flatSlotStorage = flatSlotStorage,
                  networkPeerManager = networkPeerManager,
                  syncController = self,
                  pivotBlockNumber = pivotBlock,
                  snapSyncConfig = snapSyncConfig
                )
                .withDispatcher("sync-dispatcher"),
              s"storage-recovery-$syncGeneration"
            )
          )
        } else None

        // Register self for SNAP message routing so responses reach coordinators
        networkPeerManager ! com.chipprbots.ethereum.network.NetworkPeerManagerActor.RegisterSnapSyncController(self)

        // Poll for snap-capable peers every 5 seconds to feed coordinators
        val peerPoller = context.system.scheduler.scheduleWithFixedDelay(
          2.seconds,
          5.seconds,
          self,
          PollRecoveryPeers
        )

        context.become(
          runningRecovery(
            bytecodeActor = bytecodeActor,
            storageActor = storageActor,
            bytecodeComplete = !needBytecode,
            storageComplete = !needStorage,
            peerPoller = peerPoller
          )
        )

      case _ =>
        log.warning("Cannot run recovery: missing stateRoot or pivotBlock. Marking done and proceeding.")
        if (needBytecode) appStateStorage.bytecodeRecoveryDone().commit()
        if (needStorage) appStateStorage.storageRecoveryDone().commit()
        startRegularSync()
    }
  }

  def runningRecovery(
      bytecodeActor: Option[ActorRef],
      storageActor: Option[ActorRef],
      bytecodeComplete: Boolean,
      storageComplete: Boolean,
      peerPoller: org.apache.pekko.actor.Cancellable = org.apache.pekko.actor.Cancellable.alreadyCancelled
  ): Receive = {
    case BytecodeRecoveryActor.RecoveryComplete =>
      log.info(s"Bytecode recovery complete. Storage complete: $storageComplete")
      if (storageComplete) {
        peerPoller.cancel()
        networkPeerManager ! com.chipprbots.ethereum.network.NetworkPeerManagerActor.RegisterSnapSyncController(
          context.system.deadLetters
        )
        log.info("All recovery complete. Transitioning to regular sync.")
        startRegularSync()
      } else {
        context.become(
          runningRecovery(bytecodeActor = None, storageActor, bytecodeComplete = true, storageComplete, peerPoller)
        )
      }

    case StorageRecoveryActor.RecoveryComplete =>
      log.info(s"Storage recovery complete. Bytecode complete: $bytecodeComplete")
      if (bytecodeComplete) {
        peerPoller.cancel()
        networkPeerManager ! com.chipprbots.ethereum.network.NetworkPeerManagerActor.RegisterSnapSyncController(
          context.system.deadLetters
        )
        log.info("All recovery complete. Transitioning to regular sync.")
        startRegularSync()
      } else {
        context.become(
          runningRecovery(bytecodeActor, storageActor = None, bytecodeComplete, storageComplete = true, peerPoller)
        )
      }

    case PollRecoveryPeers =>
      networkPeerManager ! com.chipprbots.ethereum.network.NetworkPeerManagerActor.GetHandshakedPeers

    case com.chipprbots.ethereum.network.NetworkPeerManagerActor.HandshakedPeers(peers) =>
      val snapPeers = peers.filter { case (_, peerInfo) => peerInfo.remoteStatus.supportsSnap }
      if (snapPeers.nonEmpty) {
        snapPeers.foreach { case (peer, _) =>
          bytecodeActor.foreach(_ ! snap.actors.Messages.ByteCodePeerAvailable(peer))
          storageActor.foreach(_ ! snap.actors.Messages.StoragePeerAvailable(peer))
        }
      }

    case msg =>
      // Forward SNAP protocol responses to both active recovery actors
      bytecodeActor.foreach(_.forward(msg))
      storageActor.foreach(_.forward(msg))
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
        { val br = new BranchResolution(blockchainReader); br.messConfig = messConfig; br },
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
      flatSlotStorage: FlatSlotStorage,
      syncStateStorage: FastSyncStateStorage,
      consensus: ConsensusAdapter,
      validators: Validators,
      peerEventBus: ActorRef,
      pendingTransactionsManager: ActorRef,
      ommersPool: ActorRef,
      networkPeerManager: ActorRef,
      blacklist: Blacklist,
      syncConfig: SyncConfig,
      configBuilder: BlockchainConfigBuilder,
      messConfig: Option[MESSConfig] = None
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
        flatSlotStorage,
        syncStateStorage,
        consensus,
        validators,
        peerEventBus,
        pendingTransactionsManager,
        ommersPool,
        networkPeerManager,
        blacklist,
        syncConfig,
        configBuilder,
        messConfig
      )
    )
}
