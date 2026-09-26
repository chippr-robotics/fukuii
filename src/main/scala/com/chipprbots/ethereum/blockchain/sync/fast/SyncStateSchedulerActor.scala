package com.chipprbots.ethereum.blockchain.sync.fast

import org.apache.pekko.actor.typed.ActorRef as TypedActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.PreRestart
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.TimerScheduler
import org.apache.pekko.util.ByteString

import cats.data.NonEmptyList
import cats.effect.IO
import cats.effect.unsafe.IORuntime

import scala.concurrent.duration.*
import scala.util.Failure
import scala.util.Success

import com.chipprbots.ethereum.blockchain.sync.Blacklist
import com.chipprbots.ethereum.blockchain.sync.Blacklist.BlacklistReason
import com.chipprbots.ethereum.blockchain.sync.Blacklist.BlacklistReason.InvalidStateResponse
import com.chipprbots.ethereum.blockchain.sync.PeerListHelper
import com.chipprbots.ethereum.blockchain.sync.PeerListSupportNg.PeerWithInfo
import com.chipprbots.ethereum.blockchain.sync.PeerRequestHandler
import com.chipprbots.ethereum.blockchain.sync.PeerRequestHandler.ResponseReceived
import com.chipprbots.ethereum.blockchain.sync.fast.LoadableBloomFilter.BloomFilterLoadingResult
import com.chipprbots.ethereum.blockchain.sync.fast.SyncStateScheduler.CriticalError
import com.chipprbots.ethereum.blockchain.sync.fast.SyncStateScheduler.ProcessingStatistics
import com.chipprbots.ethereum.blockchain.sync.fast.SyncStateScheduler.SchedulerState
import com.chipprbots.ethereum.blockchain.sync.fast.SyncStateScheduler.SyncResponse

import com.chipprbots.ethereum.domain.TrieRoot
import com.chipprbots.ethereum.mpt.HexPrefix
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.PeerEventBusActor.Command as PeerEventBusCommand
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.PeerDisconnected
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.network.p2p.messages.Codes
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.GetNodeData
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.NodeData
import com.chipprbots.ethereum.network.p2p.messages.SNAP
import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetTrieNodes
import com.chipprbots.ethereum.network.p2p.messages.SNAP.TrieNodes
import com.chipprbots.ethereum.utils.ByteStringUtils
import com.chipprbots.ethereum.utils.Config.SyncConfig

// scalastyle:off number.of.methods
object SyncStateSchedulerActor:

  sealed trait Command

  // === Internal timer key and polling message (core-only) ===
  private case object ScanPeers extends Command
  private case object ScanKey

  // Internal commands: StartSyncingTo/RestartRequested are received by the Behavior[Command] core and
  // converted to these Cmd forms using the replyTo passed at construction (no sender() needed).
  final private[fast] case class StartSyncingToCmd(
      stateRoot: TrieRoot,
      blockNumber: BigInt,
      replyTo: TypedActorRef[SyncStateSchedulerActorResponse]
  ) extends Command
  final private[fast] case class RestartRequestedCmd(replyTo: TypedActorRef[SyncStateSchedulerActorResponse])
      extends Command

  // === Private wrapper commands — replace identity adapters and .toClassic self-sends ===
  private case class WrappedPRHResult(result: PeerRequestHandler.Result) extends Command
  private case class WrappedHandshakedPeers(peers: Map[Peer, NetworkPeerManagerActor.PeerInfo]) extends Command
  private case class WrappedPeerDisconnected(pd: PeerDisconnected) extends Command
  private case class WrappedRequestData(nodeData: NodeData, from: Peer) extends Command
  private case class WrappedRequestFailed(from: Peer, reason: String) extends Command

  // === Typed core factory ===
  def behavior(
      sync: SyncStateScheduler,
      syncConfig: SyncConfig,
      networkPeerManager: TypedActorRef[NetworkPeerManagerActor.Command],
      peerEventBus: TypedActorRef[PeerEventBusCommand],
      blacklist: Blacklist,
      replyTo: TypedActorRef[SyncStateSchedulerActorResponse],
      statsReplyTo: TypedActorRef[StateSyncStats]
  ): Behavior[Command] =
    Behaviors.setup[Command] { ctx =>
      Behaviors.withTimers[Command] { timers =>
        val peerDisconnectedAdapter: TypedActorRef[PeerEvent] =
          ctx.messageAdapter[PeerEvent] {
            case pd: PeerDisconnected => WrappedPeerDisconnected(pd)
            case e                    => throw new MatchError(s"unexpected PeerEvent from bus: $e")
          }
        val peerListHelper = new PeerListHelper(peerEventBus, blacklist, peerDisconnectedAdapter, ctx.log)
        val handshakedPeersAdapter =
          ctx.messageAdapter[NetworkPeerManagerActor.HandshakedPeers](hp => WrappedHandshakedPeers(hp.peers))
        // Immediate first poll + periodic rescans (matches PeerListSupportNg's 0-delay scheduleWithFixedDelay).
        networkPeerManager ! NetworkPeerManagerActor.GetHandshakedPeersCmd(handshakedPeersAdapter)
        timers.startTimerWithFixedDelay(ScanKey, ScanPeers, syncConfig.peersScanInterval)
        val behavior = new Impl(
          ctx,
          timers,
          sync,
          syncConfig,
          networkPeerManager,
          peerEventBus,
          blacklist,
          replyTo,
          statsReplyTo,
          peerListHelper
        )
          .waitingForBloomFilterToLoad(None)
        // P19: surface PreRestart so the supervisor-triggered restart documented in §7c-E3 is visible. On restart
        // all in-flight peer assignments and the DownloaderState are lost and re-requested from a fresh state;
        // timers are cancelled by `withTimers` and the PeerListHelper bus subscription is dropped with the helper.
        // Cleanup here is the warning log only — the restarted instance reloads the bloom filter and re-polls peers.
        Behaviors.intercept(() =>
          new org.apache.pekko.actor.typed.BehaviorSignalInterceptor[Command]():
            override def aroundSignal(
                c: org.apache.pekko.actor.typed.TypedActorContext[Command],
                signal: org.apache.pekko.actor.typed.Signal,
                target: org.apache.pekko.actor.typed.BehaviorInterceptor.SignalTarget[Command]
            ): Behavior[Command] =
              if signal == PreRestart then
                c.asScala.log.warn(
                  "{} received PreRestart — discarding in-flight DownloaderState; bloom filter will reload on restart",
                  c.asScala.self.path.name
                )
              target(c, signal)
        )(behavior)
      }
    }

  // === Public API (unchanged) ===

  case object SyncKey
  private case object Sync extends Command

  private def reportStats(
      to: TypedActorRef[StateSyncStats],
      currentStats: ProcessingStatistics,
      currentState: SyncStateScheduler.SchedulerState
  ): Unit =
    to ! StateSyncStats(
      currentStats.saved + currentState.memBatch.size,
      currentState.numberOfPendingRequests
    )

  final case class StateSyncStats(saved: Long, missing: Long)

  final case class ProcessingResult(result: Either[ProcessingError, ProcessingSuccess]) extends Command

  case object PrintInfo extends Command
  case object PrintInfoKey

  sealed trait SyncStateSchedulerActorCommand
  final case class StartSyncingTo(stateRoot: TrieRoot, blockNumber: BigInt)
      extends SyncStateSchedulerActorCommand
      with Command
  case object RestartRequested extends SyncStateSchedulerActorCommand with Command

  sealed trait SyncStateSchedulerActorResponse
  case object StateSyncFinished extends SyncStateSchedulerActorResponse
  case object WaitingForNewTargetBlock extends SyncStateSchedulerActorResponse
  case object NetworkIncompatible extends SyncStateSchedulerActorResponse

  final case class GetMissingNodes(nodesToGet: List[ByteString])
  final case class MissingNodes(missingNodes: List[SyncResponse], downloaderCapacity: Int)

  final case class BloomFilterResult(res: BloomFilterLoadingResult) extends Command

  sealed trait RequestResult
  final case class RequestData(nodeData: NodeData, from: Peer) extends RequestResult
  final case class RequestFailed(from: Peer, reason: String) extends RequestResult

  sealed trait ProcessingError
  final case class Critical(er: CriticalError) extends ProcessingError
  final case class DownloaderError(
      newDownloaderState: DownloaderState,
      by: Peer,
      blacklistWithReason: Option[BlacklistReason]
  ) extends ProcessingError

  final case class ProcessingSuccess(
      newSchedulerState: SchedulerState,
      newDownloaderState: DownloaderState,
      processingStats: ProcessingStatistics
  )

  final case class RequestTerminated(to: Peer) extends Command

  final case class PeerRequest(
      peer: Peer,
      nodes: NonEmptyList[ByteString],
      pathInfo: Map[ByteString, (Seq[Byte], Option[ByteString])] = Map.empty
  )

  case object RegisterScheduler

  sealed trait ResponseProcessingResult
  case object UnrequestedResponse extends ResponseProcessingResult
  case object NoUsefulDataInResponse extends ResponseProcessingResult
  final case class UsefulData(responses: List[SyncResponse]) extends ResponseProcessingResult

  // === Typed core implementation ===

  // scalastyle:off cyclomatic.complexity method.length
  private class Impl(
      ctx: ActorContext[Command],
      timers: TimerScheduler[Command],
      sync: SyncStateScheduler,
      syncConfig: SyncConfig,
      networkPeerManager: TypedActorRef[NetworkPeerManagerActor.Command],
      peerEventBus: TypedActorRef[PeerEventBusCommand],
      @annotation.unused blacklist: Blacklist,
      replyTo: TypedActorRef[SyncStateSchedulerActorResponse],
      statsReplyTo: TypedActorRef[StateSyncStats],
      peerListHelper: PeerListHelper
  ):

    implicit private val ioRuntime: IORuntime = IORuntime.global

    /** State root for the current sync target — needed for GetTrieNodes requests. */
    private var currentStateRoot: ByteString = ByteString.empty

    /** Track consecutive "no useful data" responses — signals the state root is stale. */
    private var consecutiveUselessResponses: Int = 0
    private val UselessResponseThreshold: Int = 20

    private val prhResultAdapter: TypedActorRef[PeerRequestHandler.Result] =
      ctx.messageAdapter[PeerRequestHandler.Result](WrappedPRHResult(_))

    private val handshakedPeersAdapter: TypedActorRef[NetworkPeerManagerActor.HandshakedPeers] =
      ctx.messageAdapter[NetworkPeerManagerActor.HandshakedPeers](hp => WrappedHandshakedPeers(hp.peers))

    /** Live Typed refs to PeerRequestHandler children, keyed by PeerId for explicit unwatch. */
    private var activeHandlers: Map[PeerId, TypedActorRef[PeerRequestHandler.Command]] = Map.empty

    // Monotonically increasing counter that makes each PeerRequestHandler child name unique within
    // this scheduler's lifetime, preventing name collisions when the same peer gets sequential batches.
    private var requestSeq: Int = 0

    // Static SLF4J logger for IO-fiber callbacks — ctx.log is actor-thread-only.
    private val fiberLog = org.slf4j.LoggerFactory.getLogger(getClass)

    // IO fiber for bloom filter loading — runs asynchronously; result delivered via self.
    // If the actor stops before the fiber completes, the BloomFilterResult goes to dead letters (harmless).
    // Fiber handle intentionally discarded — fire-and-forget: the fiber notifies self via message on completion.
    private val _ = sync.loadFilterFromBlockchain.attempt
      .flatMap { result =>
        IO {
          result match
            case Left(ex) =>
              fiberLog.error(
                "Unexpected error while loading bloom filter. Starting state sync with empty bloom filter " +
                  "which may result with degraded performance",
                ex
              )
              ctx.self ! BloomFilterResult(BloomFilterLoadingResult())
            case Right(value) =>
              fiberLog.info("Bloom filter loading finished")
              ctx.self ! BloomFilterResult(value)
        }
      }
      .start
      .unsafeRunSync()(ioRuntime)

    private def handleCommon(message: Command): Option[Behavior[Command]] = message match
      case ScanPeers =>
        networkPeerManager ! NetworkPeerManagerActor.GetHandshakedPeersCmd(handshakedPeersAdapter)
        Some(Behaviors.same)
      case WrappedHandshakedPeers(peers) =>
        peerListHelper.handleHandshakedPeers(peers)
        Some(Behaviors.same)
      case WrappedPeerDisconnected(pd) =>
        peerListHelper.handlePeerDisconnected(pd.peerId)
        Some(Behaviors.same)
      // FastSync spawns this core behavior directly (bypassing the Classic `SyncStateSchedulerActor` shell that
      // captures `sender()`), so the bare public messages arrive here instead of the shell-translated `*Cmd` forms.
      // Re-dispatch them as `*Cmd` with `replyTo` as the reply target — callers pass the typed ref at construction.
      // Without this the core drops `StartSyncingTo` and state download never begins.
      case StartSyncingTo(stateRoot, blockNumber) =>
        ctx.self ! StartSyncingToCmd(stateRoot, blockNumber, replyTo)
        Some(Behaviors.same)
      case RestartRequested =>
        ctx.self ! RestartRequestedCmd(replyTo)
        Some(Behaviors.same)
      case _ => None

    /** Initial behavior: wait for the bloom filter IO fiber to complete, buffering any early command. */
    def waitingForBloomFilterToLoad(lastCmd: Option[Command]): Behavior[Command] =
      Behaviors.receiveMessage { message =>
        handleCommon(message).getOrElse {
          message match
            case BloomFilterResult(result) =>
              ctx.log.debug(
                "Loaded {} already known elements from storage to bloom filter the error while loading was {}",
                result.writtenElements,
                result.error
              )
              val initStats = ProcessingStatistics().addSaved(result.writtenElements)
              lastCmd match
                case Some(StartSyncingToCmd(root, bn, replyTo)) =>
                  startSyncing(root, bn, initStats, replyTo)
                case Some(RestartRequestedCmd(replyTo)) =>
                  replyTo ! WaitingForNewTargetBlock
                  idle(initStats)
                case _ =>
                  idle(initStats)
            case cmd: StartSyncingToCmd =>
              waitingForBloomFilterToLoad(Some(cmd))
            case cmd: RestartRequestedCmd =>
              waitingForBloomFilterToLoad(Some(cmd))
            case _ => Behaviors.same
        }
      }

    def idle(processingStatistics: ProcessingStatistics): Behavior[Command] =
      Behaviors.receiveMessage { message =>
        handleCommon(message).getOrElse {
          message match
            case StartSyncingToCmd(root, bn, replyTo) =>
              startSyncing(root, bn, processingStatistics, replyTo)
            case RestartRequestedCmd(replyTo) =>
              ctx.log.debug("Received RestartRequested while idle. Responding with WaitingForNewTargetBlock.")
              replyTo ! WaitingForNewTargetBlock
              Behaviors.same
            case PrintInfo =>
              ctx.log.info("Waiting for target block to start the state sync")
              Behaviors.same
            case _ => Behaviors.same
        }
      }

    private def startSyncing(
        root: TrieRoot,
        bn: BigInt,
        initialStats: ProcessingStatistics,
        initiator: TypedActorRef[SyncStateSchedulerActorResponse]
    ): Behavior[Command] =
      timers.startTimerAtFixedRate(PrintInfoKey, PrintInfo, 30.seconds)
      currentStateRoot = root.value
      consecutiveUselessResponses = 0
      ctx.log.info("Starting state sync to root {} on block {}", ByteStringUtils.hash2string(root.value), bn)
      sync.initState(root.value) match
        case None =>
          ctx.log.info(
            "Empty state root {} — nothing to sync, completing immediately.",
            ByteStringUtils.hash2string(root.value)
          )
          initiator ! StateSyncFinished
          idle(initialStats)
        case Some(initState) =>
          val nextBehavior = syncing(
            SyncSchedulerActorState.initial(initState, initialStats, bn, initiator, statsReplyTo)
          )
          ctx.self ! Sync
          nextBehavior

    private def finalizeSync(state: SyncSchedulerActorState): Behavior[Command] =
      val memBatch = state.currentSchedulerState.memBatch
      if memBatch.nonEmpty then
        ctx.log.debug("Persisting {} elements to blockchain and finalizing the state sync", memBatch.size)
        val finalState = sync.persistBatch(state.currentSchedulerState, state.targetBlock)
        reportStats(state.statsInitiator, state.currentStats.addSaved(memBatch.size), finalState)
      else ctx.log.info("Finalizing the state sync")
      state.syncInitiator ! StateSyncFinished
      idle(ProcessingStatistics())

    private def handleRestart(
        currentState: SchedulerState,
        currentStats: ProcessingStatistics,
        targetBlock: BigInt,
        restartRequester: TypedActorRef[SyncStateSchedulerActorResponse]
    ): Behavior[Command] =
      ctx.log.debug("Starting request sequence")
      sync.persistBatch(currentState, targetBlock)
      restartRequester ! WaitingForNewTargetBlock
      idle(currentStats.addSaved(currentState.memBatch.size))

    /** Check if a peer supports GetNodeData on the negotiated protocol. GetNodeData is available in ETH63-67 but
      * removed in ETH68 (EIP-4938). Only the negotiated (connection-level) capability matters.
      */
    private def supportsGetNodeData(capability: Capability): Boolean = capability match
      case Capability.ETH63 | Capability.ETH64 | Capability.ETH65 | Capability.ETH66 | Capability.ETH67 => true
      case Capability.ETH68                                                                             => false
      case Capability.ETH69                                                                             => false
      case Capability.ETH70                                                                             => false
      case Capability.ETH71                                                                             => false
      case Capability.ETH72                                                                             => false
      case Capability.SNAP1                                                                             => false
      case Capability.SNAP2                                                                             => false

    private def peerUsesSnap(peer: Peer): Boolean =
      peerListHelper.handshakedPeers.get(peer.id).exists { pwi =>
        !supportsGetNodeData(pwi.peerInfo.remoteStatus.capability) && pwi.peerInfo.remoteStatus.supportsSnap
      }

    private def getFreePeers(state: DownloaderState): List[Peer] =
      val freePeers = peerListHelper.peersToDownloadFrom.collect {
        case (_, PeerWithInfo(peer, peerInfo))
            if !state.activeRequests.contains(peer.id) &&
              (supportsGetNodeData(peerInfo.remoteStatus.capability) || peerInfo.remoteStatus.supportsSnap) =>
          peer
      }.toList

      if freePeers.isEmpty && peerListHelper.peersToDownloadFrom.nonEmpty then
        ctx.log.debug(
          "No free peers for state download ({} total, {} with active requests)",
          peerListHelper.peersToDownloadFrom.size,
          state.activeRequests.size
        )
      freePeers

    /** Spawns a PeerRequestHandler child, registers a death-watch, and tracks the ref. */
    private def requestNodes(request: PeerRequest): Unit =
      requestSeq += 1
      val useSnap = peerUsesSnap(request.peer)
      ctx.log.debug(
        "Requesting {} nodes from peer {} via {}",
        request.nodes.size,
        request.peer.id,
        if useSnap then "GetTrieNodes" else "GetNodeData"
      )
      val handler = if useSnap && currentStateRoot.nonEmpty then
        val paths: Seq[Seq[ByteString]] = request.nodes.toList.map { hash =>
          request.pathInfo.get(hash) match
            case Some((nibblePath, accountHashOpt)) =>
              val compactPath = ByteString(HexPrefix.encode(nibblePath.toArray, isLeaf = false))
              accountHashOpt match
                case Some(acctHash) => Seq(acctHash, compactPath)
                case None           => Seq(compactPath)
            case None =>
              Seq(ByteString(HexPrefix.encode(Array.empty[Byte], isLeaf = false)))
        }
        ctx.spawn(
          PeerRequestHandler.behavior[GetTrieNodes, TrieNodes](
            request.peer,
            syncConfig.peerResponseTimeout,
            networkPeerManager,
            peerEventBus,
            requestMsg = GetTrieNodes(
              requestId = ETHPackets.nextRequestId,
              rootHash = currentStateRoot,
              paths = paths,
              responseBytes = BigInt(512 * 1024)
            ),
            responseMsgCode = SNAP.Codes.TrieNodesCode,
            replyTo = prhResultAdapter,
            requestId = 0
          ),
          s"state-trie-request-${request.peer.id.value}-$requestSeq"
        )
      else
        ctx.spawn(
          PeerRequestHandler.behavior[GetNodeData, NodeData](
            request.peer,
            syncConfig.peerResponseTimeout,
            networkPeerManager,
            peerEventBus,
            requestMsg = GetNodeData(request.nodes.toList),
            responseMsgCode = Codes.NodeDataCode,
            replyTo = prhResultAdapter,
            requestId = 0
          ),
          s"state-nodedata-request-${request.peer.id.value}-$requestSeq"
        )
      ctx.watchWith(handler, RequestTerminated(request.peer))
      activeHandlers = activeHandlers.updated(request.peer.id, handler)

    private def processNodes(
        currentState: SyncSchedulerActorState,
        requestResult: RequestResult
    ): ProcessingResult =
      requestResult match
        case RequestData(nodeData, from) =>
          val (resp, newDownloaderState) = currentState.currentDownloaderState.handleRequestSuccess(from, nodeData)
          resp match
            case UnrequestedResponse =>
              ProcessingResult(Left(DownloaderError(newDownloaderState, from, None)))
            case NoUsefulDataInResponse =>
              ProcessingResult(
                Left(
                  DownloaderError(newDownloaderState, from, Some(InvalidStateResponse("no useful data in response")))
                )
              )
            case UsefulData(responses) =>
              sync.processResponses(currentState.currentSchedulerState, responses) match
                case Left(value) =>
                  ProcessingResult(Left(Critical(value)))
                case Right((newState, stats)) =>
                  ProcessingResult(
                    Right(ProcessingSuccess(newState, newDownloaderState, currentState.currentStats.addStats(stats)))
                  )
        case RequestFailed(from, reason) =>
          fiberLog.debug("Processing failed request from {}. Failure reason {}", from.id.toString, reason)
          val newDownloaderState = currentState.currentDownloaderState.handleRequestFailure(from)
          ProcessingResult(
            Left(DownloaderError(newDownloaderState, from, Some(BlacklistReason.FastSyncRequestFailed(reason))))
          )

    /** Offloads processNodes to the IORuntime compute pool and delivers the result back via the actor mailbox. */
    private def pipeProcess(state: SyncSchedulerActorState, result: RequestResult): Unit =
      ctx.pipeToSelf(IO(processNodes(state, result)).unsafeToFuture()) {
        case Success(pr) => pr
        case Failure(ex) => throw ex
      }

    def syncing(currentState: SyncSchedulerActorState): Behavior[Command] =
      Behaviors.receiveMessage { message =>
        handleCommon(message).getOrElse {
          message match

            // === PRH result forwarding: unwatch + self-dispatch as wrapped RequestResult ===

            case WrappedPRHResult(ResponseReceived(_, peer: Peer, nodeData: NodeData, timeTaken: Long)) =>
              ctx.log.debug("Received {} state nodes via GetNodeData in {} ms", nodeData.values.size, timeTaken)
              FastSyncMetrics.setMptStateDownloadTime(timeTaken)
              activeHandlers.get(peer.id).foreach(h => ctx.unwatch(h))
              activeHandlers -= peer.id
              ctx.self ! WrappedRequestData(nodeData, peer)
              Behaviors.same

            case WrappedPRHResult(ResponseReceived(_, peer: Peer, trieNodes: TrieNodes, timeTaken: Long)) =>
              ctx.log.debug("Received {} state nodes via GetTrieNodes in {} ms", trieNodes.nodes.size, timeTaken)
              FastSyncMetrics.setMptStateDownloadTime(timeTaken)
              activeHandlers.get(peer.id).foreach(h => ctx.unwatch(h))
              activeHandlers -= peer.id
              ctx.self ! WrappedRequestData(NodeData(trieNodes.nodes.toList), peer)
              Behaviors.same

            case WrappedPRHResult(PeerRequestHandler.RequestFailed(_, peer: Peer, reason: String)) =>
              activeHandlers.get(peer.id).foreach(h => ctx.unwatch(h))
              activeHandlers -= peer.id
              ctx.log.debug("Request to peer {} failed due to {}", peer.id, reason)
              ctx.self ! WrappedRequestFailed(peer, reason)
              Behaviors.same

            case RequestTerminated(peer: Peer) =>
              ctx.log.debug("Request to {} terminated", peer.id)
              activeHandlers -= peer.id
              ctx.self ! WrappedRequestFailed(peer, "Peer disconnected in the middle of request")
              Behaviors.same

            // === State machine ===

            case Sync if currentState.hasRemainingPendingRequests && !currentState.restartHasBeenRequested =>
              val freePeers = getFreePeers(currentState.currentDownloaderState)
              (currentState.getRequestToProcess, NonEmptyList.fromList(freePeers)) match
                case (Some((nodes, newState)), Some(peers)) =>
                  ctx.log.debug(
                    "Got {} peer responses remaining to process, and there are {} idle peers available",
                    newState.numberOfRemainingRequests,
                    peers.size
                  )
                  val (requests, newState1) = newState.assignTasksToPeers(peers, syncConfig.nodesPerRequest)
                  requests.foreach(requestNodes)
                  pipeProcess(newState1, nodes)
                  syncing(newState1)

                case (Some((nodes, newState)), None) =>
                  ctx.log.debug(
                    "Got {} peer responses remaining to process, but there are no idle peers to assign new tasks",
                    newState.numberOfRemainingRequests
                  )
                  pipeProcess(newState, nodes)
                  syncing(newState)

                case (None, Some(peers)) =>
                  ctx.log.debug(
                    "There no responses to process, but there are {} free peers to assign new tasks",
                    peers.size
                  )
                  val (requests, newState) = currentState.assignTasksToPeers(peers, syncConfig.nodesPerRequest)
                  requests.foreach(requestNodes)
                  syncing(newState.finishProcessing)

                case (None, None) =>
                  ctx.log.debug(
                    "There no responses to process, and no free peers to assign new tasks. There are" +
                      "{} active requests in flight",
                    currentState.activePeerRequests.size
                  )
                  if currentState.activePeerRequests.isEmpty then
                    timers.startSingleTimer(SyncKey, Sync, syncConfig.syncRetryInterval)
                  syncing(currentState.finishProcessing)

            case Sync if currentState.hasRemainingPendingRequests && currentState.restartHasBeenRequested =>
              currentState.restartRequested.fold[Behavior[Command]](Behaviors.same) { restartRequester =>
                handleRestart(
                  currentState.currentSchedulerState,
                  currentState.currentStats,
                  currentState.targetBlock,
                  restartRequester
                )
              }

            case Sync =>
              finalizeSync(currentState)

            case WrappedRequestData(nodeData, from) =>
              val result = RequestData(nodeData, from)
              if currentState.isProcessing then
                ctx.log.debug(
                  "Response received while processing. Enqueuing for import later. Current response queue size: {}",
                  currentState.nodesToProcess.size + 1
                )
                syncing(currentState.withNewRequestResult(result))
              else
                ctx.log.debug("Response received while idle. Initiating response processing")
                val newState = currentState.initProcessing
                pipeProcess(newState, result)
                syncing(newState)

            case WrappedRequestFailed(from, reason) =>
              val result = RequestFailed(from, reason)
              if currentState.isProcessing then
                ctx.log.debug(
                  "Response received while processing. Enqueuing for import later. Current response queue size: {}",
                  currentState.nodesToProcess.size + 1
                )
                syncing(currentState.withNewRequestResult(result))
              else
                ctx.log.debug("Response received while idle. Initiating response processing")
                val newState = currentState.initProcessing
                pipeProcess(newState, result)
                syncing(newState)

            case RestartRequestedCmd(replyTo) =>
              ctx.log.debug("Received restart request")
              if currentState.isProcessing then
                ctx.log.debug("Received restart while processing. Scheduling it after the task finishes")
                syncing(currentState.withRestartRequested(replyTo))
              else
                ctx.log.debug("Received restart while idle.")
                handleRestart(
                  currentState.currentSchedulerState,
                  currentState.currentStats,
                  currentState.targetBlock,
                  replyTo
                )

            case ProcessingResult(Right(ProcessingSuccess(newState, newDownloaderState, newStats))) =>
              consecutiveUselessResponses = 0
              ctx.log.debug(
                "Finished processing mpt node batch. Got {} missing nodes. Missing queue has {} elements",
                newState.numberOfPendingRequests,
                newState.numberOfMissingHashes
              )
              val (newState1, newStats1) = if newState.memBatch.size >= syncConfig.stateSyncPersistBatchSize then
                ctx.log.debug(
                  "Current membatch size is {}, persisting nodes to database",
                  newState.memBatch.size
                )
                (sync.persistBatch(newState, currentState.targetBlock), newStats.addSaved(newState.memBatch.size))
              else (newState, newStats)
              reportStats(currentState.statsInitiator, newStats1, newState1)
              val nextBehavior =
                syncing(currentState.withNewProcessingResults(newState1, newDownloaderState, newStats1))
              ctx.self ! Sync
              nextBehavior

            case ProcessingResult(Left(err)) =>
              ctx.log.debug("Received error result")
              err match
                case Critical(er) =>
                  ctx.log.error("Critical error while state syncing {}, stopping state sync", er)
                  Behaviors.stopped

                case DownloaderError(newDownloaderState, peer, blacklistWithReason) =>
                  ctx.log.debug("Downloader error by peer {}", peer)
                  blacklistWithReason.foreach(
                    peerListHelper.blacklistIfHandshaked(peer.id, syncConfig.blacklistDuration, _)
                  )
                  blacklistWithReason match
                    case Some(InvalidStateResponse(_)) =>
                      consecutiveUselessResponses += 1
                      if consecutiveUselessResponses >= UselessResponseThreshold && !currentState.restartHasBeenRequested
                      then
                        ctx.log.warn(
                          "{} consecutive useless responses — state root likely stale. Triggering self-restart.",
                          consecutiveUselessResponses
                        )
                        consecutiveUselessResponses = 0
                        handleRestart(
                          currentState.currentSchedulerState,
                          currentState.currentStats,
                          currentState.targetBlock,
                          replyTo
                        )
                      else
                        ctx.self ! Sync
                        syncing(currentState.withNewDownloaderState(newDownloaderState))

                    case _ =>
                      ctx.self ! Sync
                      syncing(currentState.withNewDownloaderState(newDownloaderState))

            case PrintInfo =>
              ctx.log.info("{}", currentState)
              Behaviors.same

            case _ => Behaviors.same
        }
      }
  // scalastyle:on cyclomatic.complexity method.length
