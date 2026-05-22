package com.chipprbots.ethereum.blockchain.sync

import org.apache.pekko.actor.Actor
import org.apache.pekko.actor.ActorLogging
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.PoisonPill
import org.apache.pekko.actor.Props
import org.apache.pekko.actor.Scheduler
import org.apache.pekko.actor.Terminated

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

import com.chipprbots.ethereum.blockchain.sync.fast.FastSync
import com.chipprbots.ethereum.blockchain.sync.regular.RegularSync
import com.chipprbots.ethereum.blockchain.sync.snap.{SNAPSyncController, SNAPSyncConfig}
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.{
  StartRegularSyncBootstrap,
  StartRegularSyncBootstrapByHash,
  BootstrapComplete,
  PivotBootstrapFailed
}
import com.chipprbots.ethereum.consensus.ConsensusAdapter
import com.chipprbots.ethereum.consensus.engine.ForkChoiceManager
import com.chipprbots.ethereum.consensus.mess.MESSConfig
import com.chipprbots.ethereum.consensus.validators.Validators
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.BlockNumberMappingStorage
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.FastSyncStateStorage
import com.chipprbots.ethereum.db.storage.FlatAccountStorage
import com.chipprbots.ethereum.db.storage.FlatSlotStorage
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
    flatSlotStorage: FlatSlotStorage,
    flatAccountStorage: FlatAccountStorage,
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
    forkChoiceManagerOpt: Option[ForkChoiceManager] = None,
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

  // Latest CL-driven head hint received from ForkChoiceManager. Buffered so that when SNAP
  // sync starts (which may happen after the CL has already pushed several FCUs), the freshest
  // head is available as the pivot target. Only populated on post-merge chains where TTD is
  // configured AND a ForkChoiceManager was supplied — ETC mainnet leaves this `None` forever
  // and the existing TD-based pivot path is unaffected. Closes #1207.
  private var latestBeaconHead: Option[ForkChoiceManager.BeaconHead] = None

  // Whether SNAP should consume CL-driven pivot selection. Captured once at construction
  // because both `syncConfig` and the chain config are stable for the actor's lifetime.
  private val isPostMergeChain: Boolean = configBuilder.blockchainConfig.terminalTotalDifficulty.isDefined
  private val clPivotEnabled: Boolean = isPostMergeChain && forkChoiceManagerOpt.isDefined

  override def preStart(): Unit = {
    super.preStart()
    if (clPivotEnabled) {
      forkChoiceManagerOpt.foreach { fcm =>
        fcm.setListener(self)
        log.info(
          "Registered SyncController as ForkChoiceManager listener (post-merge chain TTD={}); " +
            "SNAP pivot will be CL-driven once first forkchoiceUpdated arrives.",
          configBuilder.blockchainConfig.terminalTotalDifficulty.get
        )
      }
    }
  }

  override def postStop(): Unit = {
    forkChoiceManagerOpt.foreach(_.clearListener())
    super.postStop()
  }

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
    case bh: ForkChoiceManager.BeaconHead =>
      // Buffer for the eventual SNAP startup; idle predates startSnapSync().
      handleBeaconHead(bh, snapSyncOpt = None)
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
          PivotHeaderBootstrap
            .props(peersClient, blockchainWriter, targetBlock, syncConfig, scheduler, preferSnapPeers = true),
          s"pivot-header-bootstrap-$gen"
        )

      context.become(runningPivotHeaderBootstrap(peersClient, headerBootstrap, targetBlock, snapSync))

    case StartRegularSyncBootstrapByHash(headHash) =>
      // CL-driven bootstrap path (#1207): fetch the head header by hash from peers.
      // The block number isn't known until the header arrives.
      log.info(
        "SNAP requested by-hash pivot header bootstrap for {}",
        com.chipprbots.ethereum.utils.ByteStringUtils.hash2string(headHash)
      )
      bootstrapGeneration += 1
      val gen = bootstrapGeneration
      val peersClient =
        context.actorOf(
          PeersClient.props(networkPeerManager, peerEventBus, blacklist, syncConfig, scheduler),
          s"peers-client-bootstrap-$gen"
        )
      val headerBootstrap =
        context.actorOf(
          PivotHeaderBootstrap
            .propsByHash(peersClient, blockchainWriter, headHash, syncConfig, scheduler, preferSnapPeers = false),
          s"pivot-header-bootstrap-$gen"
        )
      // We pass `targetBlock = 0` as a placeholder — the bootstrap reply carries the
      // resolved `header.number`. The runningPivotHeaderBootstrap state's matching on
      // `block == targetBlock` is bypassed in by-hash mode by using a wildcard handler;
      // the resolved Completed.targetBlock is preserved when handed to SNAP via
      // BootstrapComplete.
      context.become(
        runningPivotHeaderBootstrap(peersClient, headerBootstrap, targetBlock = BigInt(0), snapSync)
      )

    case com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.SnapSyncFinalized(pivot) =>
      log.info(
        s"SNAP state finalised at pivot=$pivot. Starting regular sync; chain backfill continues in background."
      )
      resetSnapFastCycleCount()
      // SNAPSyncController already owns the live ChainDownloader child via its
      // `completedWithBackfill` state — don't spawn a duplicate standalone resumer (#1169).
      val regularSync = startRegularSync(resumeBackfill = false)
      context.watch(snapSync)
      context.become(runningRegularSyncWithBackfill(regularSync, snapSync))

    case com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.Done =>
      // Defensive fallback: with the post-#1162 handshake, SnapSyncFinalized always precedes Done,
      // so this branch should not normally be reached. If it is (e.g., unexpected message ordering),
      // treat as a legacy "SNAP done" signal.
      snapSync ! PoisonPill
      log.info("SNAP sync completed (legacy Done path), transitioning to regular sync")
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

    case SyncProtocol.HealingImpossible =>
      snapSync ! PoisonPill
      log.warning(
        "SNAP finalization aborted (state root mismatch). Clearing sync state and restarting SNAP with a fresh pivot."
      )
      appStateStorage.clearSnapSyncDone().commit()
      appStateStorage.clearFastSyncDone().commit()
      startSnapSync()

    case SyncProtocol.Status.Progress(_, _) =>
      log.debug("SNAP sync in progress")

    case bh: ForkChoiceManager.BeaconHead =>
      handleBeaconHead(bh, snapSyncOpt = Some(snapSync))

    case msg =>
      snapSync.forward(msg)
  }

  def runningRegularSync(regularSync: ActorRef): Receive = { case other =>
    other match {
      case Terminated(actor) if actor == regularSync =>
        log.error("RegularSync actor terminated unexpectedly — restarting regular sync.")
        startRegularSync(resumeBackfill = false)
      case SyncProtocol.ResetFastSync =>
        handleResetFastSync()
      case SyncProtocol.RestartFastSync =>
        handleRestartFastSync()
      case RestartFastSyncNow =>
        doRestartFastSyncNow()
      case SyncProtocol.RegularSyncStuck(blockNumber, missingHash) =>
        // Regular sync can't make progress: state-node recovery has exhausted on the same hash
        // 3+ times. Local parent state is too far behind canonical tip for any peer's snap-serve
        // window, so trie-node fetches keep returning empty. Only viable recovery is to re-run
        // SNAP from a recent pivot. Kill regular sync, clear both SnapSyncDone AND FastSyncDone
        // (so a subsequent restart re-evaluates start() and enters SNAP rather than getting
        // stuck in `do-fast-sync is true but fast sync already completed` → regular-sync), then
        // start SNAP directly.
        log.error(
          "Regular sync stuck on block {} (missing {}). Re-triggering SNAP sync from a recent pivot.",
          blockNumber,
          missingHash
        )
        regularSync ! PoisonPill
        appStateStorage.clearSnapSyncDone().commit()
        appStateStorage.clearFastSyncDone().commit()
        startSnapSync(minPivotBlock = Some(blockNumber))
      case msg =>
        regularSync.forward(msg)
    }
  }

  /** Receive used between `SnapSyncFinalized` and `Done` from the lingering SNAPSyncController.
    *
    * Regular sync is the primary owner of peer slots; SNAP backfill runs at low priority in the background. This
    * Receive lets `SNAPSyncController.Done` arrive (so we can poison-pill the SNAP actor) and intercepts restart paths
    * so the lingering backfill actor is cleaned up before a new sync mode takes over. Everything else is delegated to
    * `runningRegularSync(regularSync)`.
    */
  def runningRegularSyncWithBackfill(regularSync: ActorRef, snapSync: ActorRef): Receive = {
    case com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.Done =>
      log.info("SNAP background backfill complete; shutting down SNAPSyncController.")
      context.unwatch(snapSync)
      snapSync ! PoisonPill
      context.become(runningRegularSync(regularSync))

    case Terminated(actor) if actor == snapSync =>
      log.warning("SNAPSyncController died while regular sync was running; chain backfill aborted.")
      context.become(runningRegularSync(regularSync))

    case Terminated(actor) if actor == regularSync =>
      log.error("RegularSync actor terminated unexpectedly during backfill — restarting regular sync.")
      context.unwatch(snapSync)
      snapSync ! PoisonPill
      startRegularSync(resumeBackfill = false)

    case msg if isRestartTrigger(msg) =>
      log.info("Restart triggered while SNAP backfill was running; poison-pilling SNAP backfill actor first.")
      context.unwatch(snapSync)
      snapSync ! PoisonPill
      context.become(runningRegularSync(regularSync))
      self ! msg // Re-deliver so the new state handles it.

    case msg =>
      runningRegularSync(regularSync).apply(msg)
  }

  /** Restart-style messages that mean the current sync strategy is being abandoned. Used by
    * `runningRegularSyncWithBackfill` to detect when it must terminate the lingering backfill actor before delegating.
    */
  private def isRestartTrigger(msg: Any): Boolean = msg match {
    case SyncProtocol.ResetFastSync       => true
    case SyncProtocol.RestartFastSync     => true
    case RestartFastSyncNow               => true
    case _: SyncProtocol.RegularSyncStuck => true
    case _                                => false
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

    case PivotHeaderBootstrap.Completed(block, header) if block == targetBlock || targetBlock == 0 =>
      // `targetBlock == 0` is the sentinel for by-hash bootstrap (#1207): the actual
      // block number is unknown at request time and resolved from the returned header.
      log.info(
        s"Pivot header bootstrap complete for block ${header.number} (requested $targetBlock) - notifying SNAP sync"
      )
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
          PivotHeaderBootstrap
            .props(newPeersClient, blockchainWriter, newTargetBlock, syncConfig, scheduler, preferSnapPeers = true),
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

    case com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.SnapSyncFinalized(pivot) =>
      log.info(
        s"Received SnapSyncFinalized(pivot=$pivot) during pivot header bootstrap. Stopping bootstrap and transitioning to regular sync."
      )
      headerBootstrap ! PoisonPill
      peersClient ! PoisonPill
      // SNAP finalised mid-bootstrap is an exceptional path; tear down the SNAP actor cleanly
      // (no chain-backfill watch, since the bootstrap state is already racy).
      originalSnapSyncRef ! PoisonPill
      resetSnapFastCycleCount()
      startRegularSync()

    case com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.Done =>
      log.info(
        "Received Done from SNAP sync during pivot header bootstrap. Stopping bootstrap and transitioning to regular sync."
      )
      headerBootstrap ! PoisonPill
      peersClient ! PoisonPill
      originalSnapSyncRef ! PoisonPill
      resetSnapFastCycleCount()
      startRegularSync()

    case bh: ForkChoiceManager.BeaconHead =>
      handleBeaconHead(bh, snapSyncOpt = Some(originalSnapSyncRef))

    case msg =>
      // Forward coordinator and protocol messages to SNAP sync during the brief bootstrap.
      // This keeps coordinators functional while we fetch the pivot header (~1-5 seconds).
      originalSnapSyncRef.forward(msg)
  }

  /** Buffer the latest CL-driven head and, when SNAP is currently running, forward it as a `CLPivotHint` so the pivot
    * can be re-anchored on the freshest CL head. Called from the receive handler in every state where a `BeaconHead`
    * can arrive. No-op on chains without TTD or where `forkChoiceManagerOpt` is `None`.
    */
  private def handleBeaconHead(
      bh: ForkChoiceManager.BeaconHead,
      snapSyncOpt: Option[ActorRef]
  ): Unit = {
    if (!clPivotEnabled) return
    val isNew = !latestBeaconHead.exists(_.headHash == bh.headHash)
    latestBeaconHead = Some(bh)
    if (isNew)
      log.info(
        "Received CL-driven beacon head {} (knownHeader={})",
        com.chipprbots.ethereum.utils.ByteStringUtils.hash2string(bh.headHash),
        bh.knownHeader.map(_.number).getOrElse("unknown")
      )
    snapSyncOpt.foreach { snapSync =>
      snapSync ! com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.CLPivotHint(
        bh.headHash,
        bh.knownHeader
      )
    }
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

    // One-shot operator override. Setting -Dfukuii.reset-fast-sync-done=true on the JVM
    // command line clears the FastSyncDone flag at startup. Used when a node was wedged
    // by the pre-fix premature-completion bug — operator can clear the flag once, the
    // node resumes fast sync to finish state download, then on next normal restart the
    // flag is back to its real value (set by FastSync.finish()). Cheap, surgical recovery
    // that doesn't touch chain data.
    if (System.getProperty("fukuii.reset-fast-sync-done", "false").equalsIgnoreCase("true")) {
      log.warning(
        "System property fukuii.reset-fast-sync-done=true — clearing FastSyncDone flag on this startup"
      )
      appStateStorage.clearFastSyncDone().commit()
    }

    // Dangling-best-block recovery. If the persisted best-block hash points to a block that
    // isn't actually in storage, the previous sync was interrupted before the canonical tip
    // could be written (e.g. mid-SNAP container restart while account download was incomplete).
    // Without this, start() lands in `do-fast-sync is true but fast sync already completed` →
    // regular sync, which loops on `Best block ... not found in storage` indefinitely.
    //
    // Recovery: clear ONLY the *SyncDone flags. SNAPSyncController persists its own progress
    // (snapSyncProgress / snapSyncStateRoot / snapSyncFinalizedRoot) and will resume the
    // partial download from where it left off — DO NOT reset bestBlockNumber, that would throw
    // away potentially many hours of completed account/storage/bytecode work and force a
    // genesis re-sync. Trie nodes are content-addressed, so leftover state from the prior run
    // is automatically reused as SNAP fills in the gaps.
    val persistedBest = appStateStorage.getBestBlockNumber()
    if (persistedBest > 0 && blockchainReader.getBlockHeaderByNumber(persistedBest).isEmpty) {
      log.warning(
        "Persisted best block {} not found in storage — clearing sync-done flags so SNAP can resume from persisted progress",
        persistedBest
      )
      appStateStorage.clearSnapSyncDone().commit()
      appStateStorage.clearFastSyncDone().commit()
    }

    // Incomplete-fast-sync recovery. The fast sync "95% complete" check uses a dynamic
    // total = downloaded + currently-queued-missing. After a JVM restart the scheduler
    // re-walks the trie from the pivot root and queues only the newly-discovered missing
    // frontier; the dynamic total drops to ≈downloaded and the percentage falsely reads
    // 99%+, so fast sync declares itself done with a partial trie. The persisted SyncState
    // now tracks `maxTotalNodesCount` (the high-water mark across the run); if downloaded
    // is far short of that peak and FastSyncDone is set, the prior run finished
    // prematurely. Clear the flag so this start() routes back to fast sync and finishes
    // the missing nodes. Without this auto-recovery, the only fix is a manual re-pivot or
    // wipe — neither of which the node can do "in the wild".
    if (appStateStorage.isFastSyncDone()) {
      fastSyncStateStorage.getSyncState().foreach { ss =>
        val saved = ss.downloadedNodesCount
        val peak = ss.maxTotalNodesCount
        if (peak > 1000L && saved.toDouble / peak.toDouble < 0.90) {
          val pct = (saved.toDouble / peak.toDouble * 100).toInt
          log.warning(
            "FastSyncDone is set but persisted SyncState shows trie incomplete: " +
              "downloaded={} / peak total={} ({}%). Clearing FastSyncDone to resume fast sync " +
              "and finish state download.",
            saved,
            peak,
            pct
          )
          appStateStorage.clearFastSyncDone().commit()
        }
      }
    }

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

    // Checkpoint sync: bootstrap a fresh datadir by importing a `.checkpoint` archive instead
    // of running SNAP. Only fires when DB is fresh (best-block == 0 and SNAP not already done).
    // Resolution order:
    //   1. `checkpoint-sync-file` if set — use the local path directly.
    //   2. else `checkpoint-sync-url` if set — download to `${datadir}/checkpoint.bin`
    //      (resumable via HTTP Range) and import.
    // On success the importer marks SNAP/bytecode/storage as done; the match below routes to
    // RegularSync. On failure we log and fall through to the normal SNAP/Fast/Regular path.
    if (appStateStorage.getBestBlockNumber() == 0 && !appStateStorage.isSnapSyncDone()) {
      val fileOpt: Option[java.nio.file.Path] = syncConfig.checkpointSyncFile.orElse {
        syncConfig.checkpointSyncUrl.flatMap { url =>
          val datadir = java.nio.file.Paths.get(System.getProperty("fukuii.datadir", "."))
          val target = datadir.resolve("checkpoint.bin")
          log.info("[CHECKPOINT DOWNLOAD] {} -> {}", url, target)
          val downloader = new com.chipprbots.ethereum.blockchain.checkpoint.CheckpointDownloader()
          downloader.download(url, target) match {
            case Right(_) => Some(target)
            case Left(err) =>
              log.error("[CHECKPOINT DOWNLOAD] failed: {} — falling through to SNAP/Fast/Regular", err)
              None
          }
        }
      }
      fileOpt.foreach { path =>
        val chainIdBig = configBuilder.blockchainConfig.chainId
        log.info("[CHECKPOINT IMPORT] starting from {} (chainId={})", path, chainIdBig)
        val importer = new com.chipprbots.ethereum.blockchain.checkpoint.CheckpointImporter(
          blockchainWriter,
          stateStorage,
          evmCodeStorage,
          appStateStorage
        )
        importer.importFromFile(path, Some(chainIdBig.toLong)) match {
          case Right(result) =>
            log.info(
              "[CHECKPOINT IMPORT] success: block={} nodes={} bytecodes={} elapsed={}s",
              result.blockNumber,
              result.nodesImported,
              result.bytecodesImported,
              result.elapsedMs / 1000
            )
          case Left(err) =>
            log.error("[CHECKPOINT IMPORT] failed: {} — falling through to SNAP/Fast/Regular", err)
        }
      }
    } else if (syncConfig.checkpointSyncFile.isDefined || syncConfig.checkpointSyncUrl.isDefined) {
      log.info(
        "Checkpoint sync configured but DB already initialized (bestBlock={}, snapDone={}); skipping import",
        appStateStorage.getBestBlockNumber(),
        appStateStorage.isSnapSyncDone()
      )
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

    // Recovery flag: -Dfukuii.snap.clearDoneOnStart=true clears SnapSyncDone to re-enter healing.
    // Use when healing completed prematurely (BUG-006: root mismatch) to resume without a full re-sync.
    if (doSnapSync && System.getProperty("fukuii.snap.clearDoneOnStart", "false").toBoolean) {
      if (appStateStorage.isSnapSyncDone()) {
        log.warning("fukuii.snap.clearDoneOnStart=true: clearing SnapSyncDone to re-enter SNAP healing")
        appStateStorage.clearSnapSyncDone().commit()
      }
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
          val pivotRootExists =
            try { mptStorage.get(header.stateRoot.toArray); true }
            catch { case _: Exception => false }
          log.info(
            "State root availability check: pivotRoot({})={}",
            header.stateRoot.take(8).toArray.map("%02x".format(_)).mkString,
            if (pivotRootExists) "EXISTS" else "MISSING"
          )
          if (!pivotRootExists) {
            val finalizedRoot = appStateStorage.getSnapSyncFinalizedRoot()
            finalizedRoot match {
              case Some(fRoot) =>
                val fRootExists =
                  try { mptStorage.get(fRoot.toArray); true }
                  catch { case _: Exception => false }
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
          } else {
            // Symmetric case (Run-26): pivot root EXISTS in MPT but differs from snapStateRoot.
            // The downloaded account trie is stored under snapStateRoot; update the pivot header
            // to match so the startup diagnostic passes and regular sync reads the correct trie.
            snapStateRoot.foreach { snapRoot =>
              if (snapRoot != header.stateRoot) {
                val snapRootExists =
                  try { mptStorage.get(snapRoot.toArray); true }
                  catch { case _: Exception => false }
                log.info(
                  "snapStateRoot({}) availability: {}",
                  snapRoot.take(8).toArray.map("%02x".format(_)).mkString,
                  if (snapRootExists) "EXISTS" else "MISSING"
                )
                if (snapRootExists) {
                  log.warning(
                    "snapStateRoot({}) differs from pivotHeader.stateRoot({}) — " +
                      "updating pivot block header to use downloaded state root.",
                    snapRoot.take(8).toArray.map("%02x".format(_)).mkString,
                    header.stateRoot.take(8).toArray.map("%02x".format(_)).mkString
                  )
                  val updatedHeader = header.copy(stateRoot = snapRoot)
                  blockchainWriter.storeBlockHeader(updatedHeader).commit()
                }
              }
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

  def startSnapSync(minPivotBlock: Option[BigInt] = None): Unit = {
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
          flatAccountStorage,
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

    // If a CL-driven head arrived before SNAP started (post-merge chains), prime the new
    // SNAP actor with it so pivot selection skips the TD-based path entirely.
    if (clPivotEnabled) {
      latestBeaconHead.foreach { bh =>
        log.info(
          "Priming SNAP sync with buffered CL beacon head {} (knownHeader={})",
          com.chipprbots.ethereum.utils.ByteStringUtils.hash2string(bh.headHash),
          bh.knownHeader.map(_.number).getOrElse("unknown")
        )
        snapSync ! SNAPSyncController.CLPivotHint(bh.headHash, bh.knownHeader)
      }
    }

    minPivotBlock.foreach { minBlock =>
      log.info("Sending MinPivotBlock({}) to new SNAP sync actor", minBlock)
      snapSync ! SNAPSyncController.MinPivotBlock(minBlock)
    }
    snapSync ! SNAPSyncController.Start
    context.become(runningSnapSync(snapSync))
  }

  def startRegularSync(resumeBackfill: Boolean = true): ActorRef = {
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
          evmCodeStorage,
          { val br = new BranchResolution(blockchainReader); br.messConfig = messConfig; br },
          validators.blockValidator,
          blacklist,
          syncConfig,
          ommersPool,
          pendingTransactionsManager,
          scheduler,
          configBuilder
        )
        .withDispatcher("sync-dispatcher"),
      s"regular-sync-$syncGeneration"
    )
    regularSync ! SyncProtocol.Start
    context.watch(regularSync)
    context.become(runningRegularSync(regularSync))
    // After SNAP completes, chain backfill (#1162) writes headers / bodies / receipts in the
    // background. If the node was killed mid-backfill, persisted cursors (#1169) tell us how
    // far it got — spawn a standalone ChainDownloader to finish the job alongside regular sync.
    // Suppressed when called from the SnapSyncFinalized path: SNAPSyncController already owns
    // the live backfill actor in that flow.
    if (resumeBackfill) maybeStartBackfillResume(regularSync)
    regularSync
  }

  /** Spawn a standalone `ChainDownloader` to resume background chain backfill from persisted cursors. No-op when SNAP
    * has not completed, when no `BackfillTarget` was persisted, or when all cursors have already reached the target.
    * Issues #1162 (background backfill) + #1169 (resume across restarts).
    */
  private def maybeStartBackfillResume(regularSync: ActorRef): Unit =
    if (appStateStorage.needsBackfillResume()) {
      val target = appStateStorage.getBackfillTarget()
      val headerCursor = appStateStorage.getBackfillBestHeader()
      val bodyCursor = appStateStorage.getBackfillBestBody()
      val receiptCursor = appStateStorage.getBackfillBestReceipt()
      log.info(
        "Resuming background chain backfill: target={}, header={}, body={}, receipt={}",
        target,
        headerCursor,
        bodyCursor,
        receiptCursor
      )
      val snapSyncConfig = loadSnapSyncConfig()
      syncGeneration += 1
      import com.chipprbots.ethereum.blockchain.sync.snap.ChainDownloader
      val resumer = context.actorOf(
        ChainDownloader
          .props(
            blockchainReader,
            blockchainWriter,
            appStateStorage,
            networkPeerManager,
            peerEventBus,
            syncConfig,
            scheduler,
            snapSyncConfig.chainBackfillConcurrentRequests,
            snapSyncConfig.chainDownloadTimeout
          )
          .withDispatcher("sync-dispatcher"),
        s"backfill-resumer-$syncGeneration"
      )
      context.watch(resumer)
      resumer ! ChainDownloader.Start(target)
      context.become(runningRegularSyncWithStandaloneBackfill(regularSync, resumer))
    }

  /** Receive while regular sync runs alongside a standalone backfill resumer (#1169). Mirrors
    * `runningRegularSyncWithBackfill` but for the post-restart case where we own the backfill actor directly instead of
    * routing through a lingering `SNAPSyncController`.
    */
  def runningRegularSyncWithStandaloneBackfill(regularSync: ActorRef, resumer: ActorRef): Receive = {
    case com.chipprbots.ethereum.blockchain.sync.snap.ChainDownloader.Done =>
      log.info("Standalone chain backfill resume complete.")
      context.unwatch(resumer)
      resumer ! PoisonPill
      context.become(runningRegularSync(regularSync))

    case progress: com.chipprbots.ethereum.blockchain.sync.snap.ChainDownloader.Progress =>
      log.debug(
        "Standalone backfill progress: headers={} bodies={} receipts={} target={}",
        progress.headersDownloaded,
        progress.bodiesDownloaded,
        progress.receiptsDownloaded,
        progress.targetBlock
      )

    case Terminated(actor) if actor == resumer =>
      log.warning("Standalone backfill resumer died; chain backfill aborted (cursors persist for next restart).")
      context.become(runningRegularSync(regularSync))

    case msg if isRestartTrigger(msg) =>
      log.info("Restart triggered while standalone backfill was running; poison-pilling backfill resumer first.")
      context.unwatch(resumer)
      resumer ! PoisonPill
      context.become(runningRegularSync(regularSync))
      self ! msg // Re-deliver so the new state handles it.

    case msg =>
      runningRegularSync(regularSync).apply(msg)
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

        val snapSyncConfig = loadSnapSyncConfig()

        val bytecodeActor = if (needBytecode) {
          Some(
            context.actorOf(
              BytecodeRecoveryActor
                .props(
                  stateRoot = stateRoot,
                  stateStorage = stateStorage,
                  evmCodeStorage = evmCodeStorage,
                  flatAccountStorage = flatAccountStorage,
                  appStateStorage = appStateStorage,
                  networkPeerManager = networkPeerManager,
                  syncController = self,
                  pivotBlockNumber = pivotBlock,
                  snapSyncConfig = snapSyncConfig
                )
                .withDispatcher("sync-dispatcher"),
              s"bytecode-recovery-$syncGeneration"
            )
          )
        } else None

        val storageActor = if (needStorage) {
          Some(
            context.actorOf(
              StorageRecoveryActor
                .props(
                  stateRoot = stateRoot,
                  stateStorage = stateStorage,
                  appStateStorage = appStateStorage,
                  flatSlotStorage = flatSlotStorage,
                  flatAccountStorage = flatAccountStorage,
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

        bytecodeActor.foreach(context.watch)
        storageActor.foreach(context.watch)

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
      log.info(s"[recovery] bytecode recovery complete (storage done: $storageComplete)")
      bytecodeActor.foreach(context.unwatch)
      if (storageComplete) {
        peerPoller.cancel()
        networkPeerManager ! com.chipprbots.ethereum.network.NetworkPeerManagerActor.RegisterSnapSyncController(
          context.system.deadLetters
        )
        log.info("[recovery] both actors complete — transitioning to regular sync")
        startRegularSync()
      } else {
        context.become(
          runningRecovery(bytecodeActor = None, storageActor, bytecodeComplete = true, storageComplete, peerPoller)
        )
      }

    case StorageRecoveryActor.RecoveryComplete =>
      log.info(s"[recovery] storage recovery complete (bytecode done: $bytecodeComplete)")
      storageActor.foreach(context.unwatch)
      if (bytecodeComplete) {
        peerPoller.cancel()
        networkPeerManager ! com.chipprbots.ethereum.network.NetworkPeerManagerActor.RegisterSnapSyncController(
          context.system.deadLetters
        )
        log.info("[recovery] both actors complete — transitioning to regular sync")
        startRegularSync()
      } else {
        context.become(
          runningRecovery(bytecodeActor, storageActor = None, bytecodeComplete, storageComplete = true, peerPoller)
        )
      }

    case PollRecoveryPeers =>
      networkPeerManager ! com.chipprbots.ethereum.network.NetworkPeerManagerActor.GetHandshakedPeers

    case com.chipprbots.ethereum.network.NetworkPeerManagerActor.HandshakedPeers(peers) =>
      val snapPeers = peers.filter { case (_, peerInfo) => peerInfo.remoteStatus.supportsSnap && peerInfo.forkAccepted }
      if (snapPeers.nonEmpty) {
        snapPeers.foreach { case (peer, _) =>
          bytecodeActor.foreach(_ ! snap.actors.Messages.ByteCodePeerAvailable(peer))
          storageActor.foreach(_ ! snap.actors.Messages.StoragePeerAvailable(peer))
        }
      }

    case Terminated(actor) if bytecodeActor.contains(actor) =>
      log.error("[recovery] BytecodeRecoveryActor crashed (no prior RecoveryComplete) — persisting flag and unblocking")
      appStateStorage.bytecodeRecoveryDone().commit()
      context.unwatch(actor)
      if (storageComplete) {
        peerPoller.cancel()
        networkPeerManager ! com.chipprbots.ethereum.network.NetworkPeerManagerActor.RegisterSnapSyncController(
          context.system.deadLetters
        )
        startRegularSync()
      } else {
        context.become(
          runningRecovery(bytecodeActor = None, storageActor, bytecodeComplete = true, storageComplete, peerPoller)
        )
      }

    case Terminated(actor) if storageActor.contains(actor) =>
      log.error("[recovery] StorageRecoveryActor crashed (no prior RecoveryComplete) — persisting flag and unblocking")
      appStateStorage.storageRecoveryDone().commit()
      context.unwatch(actor)
      if (bytecodeComplete) {
        peerPoller.cancel()
        networkPeerManager ! com.chipprbots.ethereum.network.NetworkPeerManagerActor.RegisterSnapSyncController(
          context.system.deadLetters
        )
        startRegularSync()
      } else {
        context.become(
          runningRecovery(bytecodeActor, storageActor = None, bytecodeComplete, storageComplete = true, peerPoller)
        )
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
        evmCodeStorage,
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
      flatAccountStorage: FlatAccountStorage,
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
      messConfig: Option[MESSConfig] = None,
      forkChoiceManagerOpt: Option[ForkChoiceManager] = None
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
        flatAccountStorage,
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
        messConfig,
        forkChoiceManagerOpt
      )
    )
}
