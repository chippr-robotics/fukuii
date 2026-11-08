package com.chipprbots.ethereum.blockchain.sync.regular

import org.apache.pekko.actor.typed.ActorRef as TypedActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.SupervisorStrategy
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.TimerScheduler

import scala.concurrent.duration.*

import com.chipprbots.ethereum.blockchain.sync.Blacklist
import com.chipprbots.ethereum.blockchain.sync.PeersClient
import com.chipprbots.ethereum.blockchain.sync.SyncController
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol.Status
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol.Status.Progress
import com.chipprbots.ethereum.blockchain.sync.WormToBrainBar
import com.chipprbots.ethereum.blockchain.sync.regular.BlockFetcher.InternalLastBlockImport
import com.chipprbots.ethereum.consensus.ConsensusAdapter
import com.chipprbots.ethereum.consensus.ReorgStateHandler
import com.chipprbots.ethereum.consensus.validators.BlockValidator
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.domain.Blockchain
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.BlockchainWriter
import com.chipprbots.ethereum.ledger.BranchResolution
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.PeerEventBusActor.Command as PeerEventBusCommand
import com.chipprbots.ethereum.nodebuilder.BlockchainConfigBuilder
import com.chipprbots.ethereum.ommers.OmmersPool
import com.chipprbots.ethereum.transactions.PendingTransactionsManager
import com.chipprbots.ethereum.utils.Config.SyncConfig

object RegularSync:
  type Command = SyncProtocol.RegularSyncCommand

  private val FetcherStatusKey = "RegularSyncFetcherStatus"
  private val PrintStatusKey = "RegularSyncPrintStatus"
  private val RestartPipelineKey = "RegularSyncRestartPipeline"

  // Epoch counter for pipeline (BlockFetcher + BlockImporter) spawns. Pekko requires a unique child name before the old
  // child's Terminated signal is processed; appending the epoch avoids InvalidActorNameException when re-spawning right
  // after ctx.stop. The epoch also tells which pipeline a PipelineComponentStopped is about.
  private val spawnEpoch = new java.util.concurrent.atomic.AtomicInteger(0)

  /** Type alias so callers using `RegularSync.ProgressProtocol` keep working without import changes. */
  type ProgressProtocol = SyncProtocol.ProgressProtocol
  val ProgressProtocol: SyncProtocol.ProgressProtocol.type = SyncProtocol.ProgressProtocol

  /** How long regular sync waits before it starts a new fetch-and-import pipeline in place of one that stopped (#1705).
    *
    * The wait doubles from `initial` up to `max` while pipelines keep stopping soon after they start, and goes back to
    * `initial` once one has run for `stableAfter`. That reset is tied to time, not to an imported block: on a chain
    * that follows a consensus layer regular sync can import nothing for hours while `engine_newPayload` keeps the node
    * at the head (beacon). Nothing escalates: a stopped actor is a fault in this node's code, not missing state, so a
    * re-SNAP (days on Sepolia) would not cure it. It retries for as long as the node runs, as go-ethereum's chain
    * syncer does after a failed sync cycle (the user's decision of 2026-11-07).
    */
  final case class PipelineBackoff(initial: FiniteDuration, max: FiniteDuration, stableAfter: FiniteDuration):

    /** The wait before the next pipeline starts. `waitedBefore`: the wait before the one that stopped started (`None`:
      * the first, which SyncProtocol.Start began). `ranFor`: how long that one ran.
      */
    def next(waitedBefore: Option[FiniteDuration], ranFor: FiniteDuration): FiniteDuration =
      waitedBefore match
        case Some(waited) if ranFor < stableAfter => (waited * 2).min(max)
        case _                                    => initial

  object PipelineBackoff:
    val default: PipelineBackoff = PipelineBackoff(initial = 1.second, max = 30.seconds, stableAfter = 60.seconds)

  /** One fetch-and-import pipeline: a BlockFetcher and the BlockImporter that starts and drives it, death-watched under
    * one epoch and replaced as a unit, as go-ethereum's downloader runs its fetchers and its importer as one group and
    * ends them together (`spawnSync`).
    */
  final private case class Pipeline(
      epoch: Int,
      fetcher: TypedActorRef[BlockFetcher.FetchCommand],
      importer: TypedActorRef[BlockImporter.Command],
      startedAtNanos: Long,
      waitedBefore: Option[FiniteDuration]
  )

  // scalastyle:off parameter.number
  def apply(
      peersClient: TypedActorRef[PeersClient.Command],
      networkPeerManager: TypedActorRef[NetworkPeerManagerActor.Command],
      peerEventBus: TypedActorRef[PeerEventBusCommand],
      consensus: ConsensusAdapter,
      blockchain: Blockchain,
      blockchainReader: BlockchainReader,
      blockchainWriter: BlockchainWriter,
      stateStorage: StateStorage,
      evmCodeStorage: EvmCodeStorage,
      branchResolution: BranchResolution,
      blockValidator: BlockValidator,
      blacklist: Blacklist,
      syncConfig: SyncConfig,
      ommersPool: TypedActorRef[OmmersPool.Command],
      pendingTransactionsManager: TypedActorRef[PendingTransactionsManager.Command],
      blockTopic: TypedActorRef[
        org.apache.pekko.actor.typed.pubsub.Topic.Command[com.chipprbots.ethereum.jsonrpc.NewBlockImported]
      ],
      configBuilder: BlockchainConfigBuilder,
      supervisor: TypedActorRef[SyncController.Command],
      reorgState: ReorgStateHandler = ReorgStateHandler.NoOp,
      // Called whenever progress reaches SyncDone (imported up to the best block peers announced), or a block is mined:
      // go-ethereum's downloader success callback / core-geth's StartMining, both `enableSyncedFeatures()`.
      onCaughtUp: () => Unit = () => (),
      // The importer's count of exhausted state-node fetches towards a re-SNAP. One per regular-sync session: every
      // importer this session spawns is handed it, so a pipeline started again keeps the count (#1705).
      exhausts: BlockImporter.ExhaustCount = new BlockImporter.ExhaustCount,
      pipelineBackoff: PipelineBackoff = PipelineBackoff.default
  ): Behavior[Command] =
    Behaviors.setup { ctx =>
      Behaviors.withTimers { timers =>
        val broadcaster: TypedActorRef[BlockBroadcasterActor.BroadcasterMsg] =
          ctx.spawn(
            Behaviors
              .supervise(
                BlockBroadcasterActor.apply(
                  new BlockBroadcast(
                    networkPeerManager,
                    isPoWChain = configBuilder.blockchainConfig.terminalTotalDifficulty.isEmpty
                  ),
                  peerEventBus,
                  networkPeerManager,
                  blacklist,
                  syncConfig
                )
              )
              .onFailure[Throwable](SupervisorStrategy.restart),
            "block-broadcaster"
          )

        // Spawns a fetcher and the importer that drives it, both death-watched under one new epoch. The only place
        // either is spawned: SyncProtocol.Start and every replacement come here. The same `branchResolution` goes to
        // every importer of the session, so the consensus layer's designated head it reads survives a replacement.
        def spawnPipeline(waitedBefore: Option[FiniteDuration]): Pipeline =
          val epoch = spawnEpoch.getAndIncrement()
          // BlockFetcher spawns its children (HeadersFetcher, BodiesFetcher, StateNodeFetcher) in its constructor, so a
          // Pekko restart would re-run it and ghost the old ones. No supervision: any failure stops it, and the whole
          // pipeline is replaced (see `running`).
          val fetcher = ctx.spawn(
            BlockFetcher(peersClient, peerEventBus, ctx.self.narrow[ProgressProtocol], syncConfig, blockValidator),
            s"block-fetcher-$epoch"
          )
          ctx.watchWith(fetcher, SyncProtocol.PipelineComponentStopped(epoch, "block-fetcher"))
          // No supervision either. A Pekko restart re-ran the importer's setup against a fetcher already past its own
          // start, waiting for a start that never came, and regular sync stopped for good (#1705). Any failure stops
          // it, and the whole pipeline is replaced. The importer starts the fetcher itself, as it starts.
          val importer = ctx.spawn(
            BlockImporter.apply(
              fetcher,
              consensus,
              blockchainReader,
              blockchainWriter,
              stateStorage,
              evmCodeStorage,
              branchResolution,
              syncConfig,
              ommersPool,
              broadcaster,
              pendingTransactionsManager,
              blockTopic,
              ctx.self,
              peerEventBus,
              networkPeerManager,
              blockchain,
              blacklist,
              configBuilder,
              Some(peersClient),
              reorgState,
              exhausts = exhausts
            ),
            s"block-importer-$epoch"
          )
          ctx.watchWith(importer, SyncProtocol.PipelineComponentStopped(epoch, "block-importer"))
          Pipeline(epoch, fetcher, importer, System.nanoTime(), waitedBefore)

        timers.startTimerWithFixedDelay(
          FetcherStatusKey,
          SyncProtocol.FetcherStatusTick,
          syncConfig.printStatusInterval
        )
        timers.startTimerWithFixedDelay(PrintStatusKey, SyncProtocol.PrintStatusTick, 60.seconds)

        new Logic(ctx, timers, supervisor, broadcaster, spawnPipeline, pipelineBackoff, onCaughtUp).awaitingStart(
          ProgressState(startedFetching = false, initialBlock = 0, currentBlock = 0, bestKnownNetworkBlock = 0)
        )
      }
    }
  // scalastyle:on parameter.number

  /** RegularSync's states. It owns the fetch-and-import pipeline's lifecycle, the one place it is started and replaced:
    *   - `awaitingStart`: no pipeline until SyncProtocol.Start.
    *   - `running`: a pipeline runs. When its fetcher or its importer stops (a failure, or a child of the fetcher that
    *     stopped), the rest of it is stopped and a new one starts after a backoff.
    *   - `restarting`: waiting out that backoff.
    *
    * What a replacement keeps: the progress below, the session's exhaust count and `branchResolution`. What it does
    * not: the fetcher's queues (they may hold what stopped it; go-ethereum resets its queue every cycle too) and the
    * importer's state, deferred batches included, which the new fetcher serves again from the best block.
    */
  final private class Logic(
      ctx: ActorContext[Command],
      timers: TimerScheduler[Command],
      supervisor: TypedActorRef[SyncController.Command],
      broadcaster: TypedActorRef[BlockBroadcasterActor.BroadcasterMsg],
      spawnPipeline: Option[FiniteDuration] => Pipeline,
      backoff: PipelineBackoff,
      onCaughtUp: () => Unit
  ):

    def awaitingStart(progress: ProgressState): Behavior[Command] =
      Behaviors.receiveMessage {
        case SyncProtocol.Start =>
          ctx.log.info("Starting regular sync")
          running(progress, spawnPipeline(None))
        case other => common(other, progress, None, awaitingStart)
      }

    private def running(progress: ProgressState, pipeline: Pipeline): Behavior[Command] =
      Behaviors.receiveMessage {
        case SyncProtocol.PipelineComponentStopped(epoch, component) if epoch == pipeline.epoch =>
          replace(progress, pipeline, component)
        case other => common(other, progress, Some(pipeline), running(_, pipeline))
      }

    private def restarting(progress: ProgressState, stopped: Pipeline, waiting: FiniteDuration): Behavior[Command] =
      Behaviors.receiveMessage {
        case SyncProtocol.RestartPipeline(epoch) if epoch == stopped.epoch =>
          ctx.log.info("Starting a new regular sync pipeline in place of pipeline {}", stopped.epoch)
          running(progress.copy(startedFetching = false), spawnPipeline(Some(waiting)))
        case other => common(other, progress, None, restarting(_, stopped, waiting))
      }

    private def replace(progress: ProgressState, pipeline: Pipeline, component: String): Behavior[Command] =
      val ranFor = (System.nanoTime() - pipeline.startedAtNanos).nanos
      val waiting = backoff.next(pipeline.waitedBefore, ranFor)
      RegularSyncMetrics.incrementPipelineRestarts(component)
      ctx.log.error(
        "Regular sync's {} stopped (pipeline {}, up {} s): stopping the rest of that pipeline, a new one starts from the " +
          "best block in {}",
        component,
        pipeline.epoch,
        ranFor.toSeconds,
        waiting
      )
      // Stopping a child already stopped does nothing; the fetcher's own children stop with it.
      ctx.stop(pipeline.fetcher)
      ctx.stop(pipeline.importer)
      timers.startSingleTimer(RestartPipelineKey, SyncProtocol.RestartPipeline(pipeline.epoch), waiting)
      restarting(progress, pipeline, waiting)

    /** What every state handles alike. `pipeline`: the one running, if any. `next`: the state, with new progress. */
    private def common(
        command: Command,
        progress: ProgressState,
        pipeline: Option[Pipeline],
        next: ProgressState => Behavior[Command]
    ): Behavior[Command] =
      command match
        case SyncProtocol.Start =>
          ctx.log.debug("Regular sync already started")
          Behaviors.same

        case SyncProtocol.MinedBlock(block) =>
          ctx.log.info("Block mined [number = {}, hash = {}]", block.number, block.header.hashAsHexString)
          onCaughtUp()
          pipeline match
            case Some(p) => p.importer ! BlockImporter.MinedBlock(block)
            case None =>
              ctx.log.warn("Mined block {} not imported: regular sync's pipeline is not running", block.number)
          Behaviors.same

        case msg: SyncProtocol.GetStatus =>
          msg.replyTo ! progress.toStatus
          Behaviors.same

        case ProgressProtocol.StartedFetching =>
          next(progress.copy(startedFetching = true))

        case ProgressProtocol.StartingFrom(blockNumber) =>
          RegularSyncMetrics.setCurrentBlock(blockNumber)
          next(progress.copy(initialBlock = blockNumber, currentBlock = blockNumber))

        case ProgressProtocol.GotNewBlock(blockNumber) =>
          ctx.log.debug("Got information about new block [number = {}]", blockNumber)
          val newState = progress.copy(bestKnownNetworkBlock = blockNumber)
          RegularSyncMetrics.setBestKnownNetworkBlock(blockNumber)
          if newState.toStatus == Status.SyncDone then onCaughtUp()
          next(newState)

        case ProgressProtocol.ImportedBlock(blockNumber, internally) =>
          ctx.log.debug("Imported new block [number = {}, internally = {}]", blockNumber, internally)
          val newState = progress.copy(currentBlock = blockNumber)
          RegularSyncMetrics.setCurrentBlock(blockNumber)
          RegularSyncMetrics.incrementBlocksImported()
          if newState.toStatus == Status.SyncDone then onCaughtUp()
          if internally then pipeline.foreach(_.fetcher ! InternalLastBlockImport(blockNumber))
          next(newState)

        case msg: SyncProtocol.MissingCodeNeedsBulkRecovery =>
          ctx.log.warn(
            "Block {} needs contract code this node never stored; forwarding to SyncController for bulk bytecode recovery",
            msg.blockNumber
          )
          supervisor ! SyncController.WrappedSyncProtocol(msg)
          Behaviors.same

        case msg: SyncProtocol.RegularSyncStuck =>
          // Forward escape-valve signal to SyncController. BlockImporter detects this condition and emits the
          // message; we just relay it up so SyncController can re-trigger SNAP sync from a recent pivot.
          // 8k-F: SyncController is Behavior[Command]; its typed ref is injected as `supervisor` at spawn. Wrap
          // the raw SyncProtocol message in WrappedSyncProtocol so it is unwrapped by handleRegularSyncMsg
          // (the escape valve that re-runs SNAP sync from a recent pivot).
          ctx.log.warn(
            "Regular sync stuck on block {} (missing {}); forwarding to SyncController for SNAP re-sync",
            msg.blockNumber,
            msg.missingHash
          )
          supervisor ! SyncController.WrappedSyncProtocol(msg)
          Behaviors.same

        case SyncProtocol.NewCanonicalHead(_, Some(header)) =>
          // PoS/post-merge only: CL advanced its canonical head via forkchoiceUpdated. Announce to all
          // already-connected eth peers so downloaders that handshaked before the FCU learn about our
          // advanced head (fixes Hive "fukuii as sync server" bug). On PoW chains (ETC/Mordor) this
          // message is never emitted — clPivotEnabled=false means BeaconHead is never published.
          broadcaster ! BlockBroadcasterActor.AnnounceCanonicalHead(header)
          Behaviors.same

        case SyncProtocol.NewCanonicalHead(_, None) =>
          // Header was not yet in storage at FCU time. The next forkchoiceUpdated will retry with a
          // populated knownHeader once engine_newPayload has stored the block. This is the routine
          // FCU-races-ahead-of-newPayload case (CL moves its head before the EL has the payload), not
          // an error — log at info and skip; do NOT crash.
          ctx.log.info("NewCanonicalHead: canonical head header not yet in storage — skipping peer announcement")
          Behaviors.same

        case SyncProtocol.FetcherStatusTick =>
          pipeline.foreach(_.fetcher ! BlockFetcher.PrintStatus)
          Behaviors.same

        case SyncProtocol.PrintStatusTick =>
          val lag = progress.bestKnownNetworkBlock - progress.currentBlock
          val now = System.currentTimeMillis()
          val dtSecs =
            if progress.lastPrintTimeMs > 0 then (now - progress.lastPrintTimeMs) / 1000.0 else 0.0
          val deltaBlocks = progress.currentBlock - progress.lastPrintBlock
          val rate =
            if dtSecs > 0 && progress.lastPrintTimeMs > 0 then deltaBlocks.toDouble / dtSecs else 0.0
          val etaStr =
            if rate > 0.1 && lag > 0 then f"${lag.toDouble / rate / 3600}%.1fh"
            else if lag == 0 then "at head"
            else "unknown"
          ctx.log.info(
            "RegularSync: current={} best={} lag={} rate={}/s eta={}",
            progress.currentBlock,
            progress.bestKnownNetworkBlock,
            lag,
            f"$rate%.1f",
            etaStr
          )
          val wormBar =
            if progress.bestKnownNetworkBlock == 0 then WormToBrainBar.renderUnknown(WormToBrainBar.WormState.Active)
            else if progress.currentBlock >= progress.bestKnownNetworkBlock then
              WormToBrainBar.renderUnknown(WormToBrainBar.WormState.Complete)
            else
              val p = (progress.currentBlock - progress.initialBlock).toDouble /
                (progress.bestKnownNetworkBlock - progress.initialBlock).toDouble
              WormToBrainBar.renderKnown(p)
          ctx.log.info("{} — RegularSync", wormBar)
          next(progress.copy(lastPrintBlock = progress.currentBlock, lastPrintTimeMs = now))

        case SyncProtocol.PipelineComponentStopped(epoch, component) =>
          // From a pipeline already replaced: the part the replacement stopped, or a part that stopped with it.
          ctx.log.debug("Regular sync's {} of replaced pipeline {} stopped", component, epoch)
          Behaviors.same

        case SyncProtocol.RestartPipeline(epoch) =>
          ctx.log.debug("Stale restart of regular sync pipeline {} ignored", epoch)
          Behaviors.same

  case class ProgressState(
      startedFetching: Boolean,
      initialBlock: BigInt,
      currentBlock: BigInt,
      bestKnownNetworkBlock: BigInt,
      lastPrintBlock: BigInt = BigInt(0),
      lastPrintTimeMs: Long = 0L
  ):
    def toStatus: SyncProtocol.Status =
      if startedFetching && bestKnownNetworkBlock != 0 && currentBlock < bestKnownNetworkBlock then
        Status.Syncing(initialBlock, Progress(currentBlock, bestKnownNetworkBlock), None)
      else if startedFetching && bestKnownNetworkBlock != 0 && currentBlock >= bestKnownNetworkBlock then
        Status.SyncDone
      else Status.NotSyncing
