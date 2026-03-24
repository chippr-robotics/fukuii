package com.chipprbots.ethereum.blockchain.sync.fast

import org.apache.pekko.actor.Actor
import org.apache.pekko.actor.ActorLogging
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.Props
import org.apache.pekko.actor.Timers
import org.apache.pekko.pattern.pipe
import org.apache.pekko.util.ByteString

import cats.data.NonEmptyList
import cats.effect.IO
import cats.effect.unsafe.IORuntime

import scala.collection.mutable
import scala.concurrent.duration._

import com.chipprbots.ethereum.blockchain.sync.Blacklist
import com.chipprbots.ethereum.blockchain.sync.Blacklist.BlacklistReason
import com.chipprbots.ethereum.blockchain.sync.Blacklist.BlacklistReason.InvalidStateResponse
import com.chipprbots.ethereum.blockchain.sync.PeerListSupportNg
import com.chipprbots.ethereum.blockchain.sync.PeerListSupportNg.PeerWithInfo
import com.chipprbots.ethereum.blockchain.sync.PeerRequestHandler
import com.chipprbots.ethereum.blockchain.sync.PeerRequestHandler.ResponseReceived
import com.chipprbots.ethereum.blockchain.sync.fast.LoadableBloomFilter.BloomFilterLoadingResult
import com.chipprbots.ethereum.blockchain.sync.fast.SyncStateScheduler.CriticalError
import com.chipprbots.ethereum.blockchain.sync.fast.SyncStateScheduler.ProcessingStatistics
import com.chipprbots.ethereum.blockchain.sync.fast.SyncStateScheduler.SchedulerState
import com.chipprbots.ethereum.blockchain.sync.fast.SyncStateScheduler.SyncResponse
import com.chipprbots.ethereum.blockchain.sync.fast.SyncStateSchedulerActor._
import com.chipprbots.ethereum.blockchain.sync.snap.PeerRateTracker
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.network.p2p.messages.Codes
import com.chipprbots.ethereum.network.p2p.messages.ETH63.GetNodeData
import com.chipprbots.ethereum.network.p2p.messages.ETH63.NodeData
import com.chipprbots.ethereum.utils.ByteStringUtils
import com.chipprbots.ethereum.utils.Config.SyncConfig

class SyncStateSchedulerActor(
    sync: SyncStateScheduler,
    val syncConfig: SyncConfig,
    val networkPeerManager: ActorRef,
    val peerEventBus: ActorRef,
    val blacklist: Blacklist,
    val scheduler: org.apache.pekko.actor.Scheduler
)(implicit val actorScheduler: org.apache.pekko.actor.Scheduler)
    extends Actor
    with PeerListSupportNg
    with ActorLogging
    with Timers {

  implicit private val monixScheduler: IORuntime = IORuntime.global
  implicit private val ec: scala.concurrent.ExecutionContext = context.dispatcher

  /** Adaptive per-peer rate tracker (Geth msgrate port). Computes adaptive timeouts and per-peer request capacities
    * based on EMA-smoothed RTT measurements.
    */
  private val rateTracker = new PeerRateTracker()

  /** Maps PeerRequestHandler actor refs to their requestIds for response correlation. */
  private val handlerToRequestId = mutable.Map[ActorRef, Long]()

  /** Maximum concurrent in-flight requests per peer. Matches SNAP coordinator pipelining (5 concurrent requests per
    * peer eliminates idle time between request send and response processing).
    */
  private val MaxInFlightPerPeer: Int = 5

  /** Track consecutive sync cycles with zero ETH63-67 peers (all ETH68/SNAP only). */
  private var noCompatiblePeersCount: Int = 0
  private val NoCompatiblePeersThreshold: Int = 5

  /** Check if a capability supports GetNodeData message. GetNodeData is available in ETH63-67 but removed in ETH68 per
    * EIP-4938. SNAP protocol uses different messages (GetAccountRange, etc.) and doesn't support GetNodeData.
    *
    * Note: Capability is a sealed trait, so this match is exhaustive. If new capabilities are added in the future, this
    * method will need to be updated accordingly.
    */
  private def supportsGetNodeData(capability: Capability): Boolean = capability match {
    case Capability.ETH63 | Capability.ETH64 | Capability.ETH65 | Capability.ETH66 | Capability.ETH67 => true
    case Capability.ETH68 => false // GetNodeData removed in ETH68 per EIP-4938
    case Capability.SNAP1 => false // SNAP uses different sync protocol
  }

  /** Get free peers with available pipelining slots.
    *
    * Returns a list where each peer appears once per available request slot. A peer with 3 free slots (out of
    * MaxInFlightPerPeer) appears 3 times, enabling the existing assignTasksToPeers loop to assign multiple batches per
    * peer.
    */
  private def getFreePeers(state: DownloaderState): List[Peer] = {
    val (compatiblePeersWithSlots, incompatibleCount) =
      peersToDownloadFrom.foldLeft((List.empty[Peer], 0)) {
        case ((slots, inCount), (_, PeerWithInfo(peer, peerInfo))) =>
          if (supportsGetNodeData(peerInfo.remoteStatus.capability)) {
            val inFlight = state.inFlightCount(peer.id)
            val availableSlots = (MaxInFlightPerPeer - inFlight).max(0)
            (List.fill(availableSlots)(peer) ::: slots, inCount)
          } else {
            (slots, inCount + 1) // Incompatible peer
          }
      }

    if (incompatibleCount > 0) {
      log.debug(
        "Filtered out {} peers not supporting GetNodeData (ETH68/SNAP peers). {} peer slots available for state sync.",
        incompatibleCount,
        compatiblePeersWithSlots.size
      )
    }

    // Track consecutive cycles with no compatible peers
    if (compatiblePeersWithSlots.isEmpty && incompatibleCount > 0) {
      noCompatiblePeersCount += 1
      log.warning(
        "No ETH63-67 peers available for GetNodeData ({} consecutive cycles). {} incompatible (ETH68/SNAP) peers present.",
        noCompatiblePeersCount,
        incompatibleCount
      )
      if (noCompatiblePeersCount >= NoCompatiblePeersThreshold) {
        log.warning(
          "No compatible peers for {} consecutive cycles (threshold: {}). Network appears ETH68-only. Signaling NetworkIncompatible.",
          noCompatiblePeersCount,
          NoCompatiblePeersThreshold
        )
        noCompatiblePeersCount = 0
        context.parent ! NetworkIncompatible
      }
      // Return empty list — don't fall back to incompatible peers that can't serve GetNodeData
      List.empty
    } else {
      if (compatiblePeersWithSlots.nonEmpty) {
        noCompatiblePeersCount = 0
      }
      compatiblePeersWithSlots
    }
  }

  private def requestNodes(request: PeerRequest): ActorRef = {
    log.debug("Requesting {} nodes from peer {} (requestId={})", request.nodes.size, request.peer.id, request.requestId)
    val timeout = rateTracker.targetTimeout()
    val handler = context.actorOf(
      PeerRequestHandler.props[GetNodeData, NodeData](
        request.peer,
        timeout,
        networkPeerManager,
        peerEventBus,
        requestMsg = GetNodeData(request.nodes.toList),
        responseMsgCode = Codes.NodeDataCode
      )
    )
    handlerToRequestId(handler) = request.requestId
    context.watchWith(handler, RequestTerminated(request.peer, request.requestId))
  }

  def handleRequestResults: Receive = {
    case ResponseReceived(peer, nodeData: NodeData, timeTaken) =>
      log.debug("Received {} state nodes in {} ms", nodeData.values.size, timeTaken)
      FastSyncMetrics.setMptStateDownloadTime(timeTaken)

      val handlerRef = sender()
      context.unwatch(handlerRef)
      val requestId = handlerToRequestId.remove(handlerRef).getOrElse(-1L)

      // Track measurement for adaptive timeouts
      rateTracker.update(
        peer.id.value,
        PeerRateTracker.MsgGetNodeData,
        timeTaken,
        nodeData.values.size
      )

      self ! RequestData(nodeData, peer, requestId)

    case PeerRequestHandler.RequestFailed(peer, reason) =>
      val handlerRef = sender()
      context.unwatch(handlerRef)
      val requestId = handlerToRequestId.remove(handlerRef).getOrElse(-1L)
      log.debug("Request {} to peer {} failed due to {}", requestId, peer.id, reason)

      // Track failure (items=0 slashes capacity)
      rateTracker.update(peer.id.value, PeerRateTracker.MsgGetNodeData, 0, 0)

      self ! RequestFailed(peer, reason, requestId)

    case RequestTerminated(peer, requestId) =>
      log.debug("Request {} to {} terminated", requestId, peer.id)
      // Handler may already be removed if response/failure was processed first
      handlerToRequestId.remove(sender())
      self ! RequestFailed(peer, "Peer disconnected in the middle of request", requestId)
  }

  private val loadingCancelable = sync.loadFilterFromBlockchain.attempt
    .flatMap { result =>
      IO {
        result match {
          case Left(value) =>
            log.error(
              "Unexpected error while loading bloom filter. Starting state sync with empty bloom filter" +
                "which may result with degraded performance",
              value
            )
            self ! BloomFilterResult(BloomFilterLoadingResult())
          case Right(value) =>
            log.info("Bloom filter loading finished")
            self ! BloomFilterResult(value)
        }
      }
    }
    .start
    .unsafeRunSync()(monixScheduler)

  def waitingForBloomFilterToLoad(lastReceivedCommand: Option[(SyncStateSchedulerActorCommand, ActorRef)]): Receive =
    handlePeerListMessages.orElse {
      case BloomFilterResult(result) =>
        log.debug(
          "Loaded {} already known elements from storage to bloom filter the error while loading was {}",
          result.writtenElements,
          result.error
        )
        lastReceivedCommand match {
          case Some((startSignal: StartSyncingTo, sender)) =>
            val initStats = ProcessingStatistics().addSaved(result.writtenElements)
            startSyncing(startSignal.stateRoot, startSignal.blockNumber, initStats, sender)
          case Some((RestartRequested, sender)) =>
            // TODO: are we testing this path?
            sender ! WaitingForNewTargetBlock
            context.become(idle(ProcessingStatistics().addSaved(result.writtenElements)))
          case _ =>
            context.become(idle(ProcessingStatistics().addSaved(result.writtenElements)))
        }

      case command: SyncStateSchedulerActorCommand =>
        context.become(waitingForBloomFilterToLoad(Some((command, sender()))))
    }

  private def startSyncing(
      root: ByteString,
      bn: BigInt,
      initialStats: ProcessingStatistics,
      initiator: ActorRef
  ): Unit = {
    timers.startTimerAtFixedRate(PrintInfoKey, PrintInfo, 30.seconds)
    timers.startTimerAtFixedRate(TuneRateTrackerKey, TuneRateTracker, 5.seconds)
    log.info("Starting state sync to root {} on block {}", ByteStringUtils.hash2string(root), bn)
    // TODO handle case when we already have root i.e state is synced up to this point
    val initState = sync.initState(root).getOrElse {
      throw new IllegalStateException(s"Failed to initialize state sync for root ${ByteStringUtils.hash2string(root)}")
    }
    context.become(
      syncing(
        SyncSchedulerActorState.initial(initState, initialStats, bn, initiator)
      )
    )
    self ! Sync
  }

  def idle(processingStatistics: ProcessingStatistics): Receive = handlePeerListMessages.orElse {
    case StartSyncingTo(root, bn) =>
      startSyncing(root, bn, processingStatistics, sender())
    case RestartRequested =>
      log.debug("Received RestartRequested while idle. Responding with WaitingForNewTargetBlock.")
      sender() ! WaitingForNewTargetBlock
    case PrintInfo =>
      log.info("Waiting for target block to start the state sync")
    case TuneRateTracker => // Ignore tune ticks when idle
  }

  private def finalizeSync(
      state: SyncSchedulerActorState
  ): Unit =
    if (state.memBatch.nonEmpty) {
      log.debug("Persisting {} elements to blockchain and finalizing the state sync", state.memBatch.size)
      val finalState = sync.persistBatch(state.currentSchedulerState, state.targetBlock)
      reportStats(state.syncInitiator, state.currentStats.addSaved(state.memBatch.size), finalState)
      state.syncInitiator ! StateSyncFinished
      timers.cancel(TuneRateTrackerKey)
      context.become(idle(ProcessingStatistics()))
    } else {
      log.info("Finalizing the state sync")
      state.syncInitiator ! StateSyncFinished
      timers.cancel(TuneRateTrackerKey)
      context.become(idle(ProcessingStatistics()))
    }

  private def processNodes(
      currentState: SyncSchedulerActorState,
      requestResult: RequestResult
  ): ProcessingResult =
    requestResult match {
      case RequestData(nodeData, from, requestId) =>
        val (resp, newDownloaderState) =
          currentState.currentDownloaderState.handleRequestSuccess(requestId, nodeData)
        resp match {
          case UnrequestedResponse =>
            ProcessingResult(
              Left(DownloaderError(newDownloaderState, from, None))
            )
          case NoUsefulDataInResponse =>
            ProcessingResult(
              Left(DownloaderError(newDownloaderState, from, Some(InvalidStateResponse("no useful data in response"))))
            )
          case UsefulData(responses) =>
            sync.processResponses(currentState.currentSchedulerState, responses) match {
              case Left(value) =>
                ProcessingResult(Left(Critical(value)))
              case Right((newState, stats)) =>
                ProcessingResult(
                  Right(ProcessingSuccess(newState, newDownloaderState, currentState.currentStats.addStats(stats)))
                )
            }
        }
      case RequestFailed(from, reason, requestId) =>
        log.debug("Processing failed request {} from {}. Failure reason {}", requestId, from, reason)
        val newDownloaderState = currentState.currentDownloaderState.handleRequestFailure(requestId)
        ProcessingResult(
          Left(DownloaderError(newDownloaderState, from, Some(BlacklistReason.FastSyncRequestFailed(reason))))
        )
    }

  private def handleRestart(
      currentState: SchedulerState,
      currentStats: ProcessingStatistics,
      targetBlock: BigInt,
      restartRequester: ActorRef
  ): Unit = {
    log.debug("Starting request sequence")
    sync.persistBatch(currentState, targetBlock)
    restartRequester ! WaitingForNewTargetBlock
    timers.cancel(TuneRateTrackerKey)
    context.become(idle(currentStats.addSaved(currentState.memBatch.size)))
  }

  // scalastyle:off cyclomatic.complexity method.length
  def syncing(currentState: SyncSchedulerActorState): Receive =
    handlePeerListMessages.orElse(handleRequestResults).orElse {
      case Sync if currentState.hasRemainingPendingRequests && !currentState.restartHasBeenRequested =>
        val freePeers = getFreePeers(currentState.currentDownloaderState)
        (currentState.getRequestToProcess, NonEmptyList.fromList(freePeers)) match {
          case (Some((nodes, newState)), Some(peers)) =>
            log.debug(
              "Got {} peer responses remaining to process, and there are {} peer slots available",
              newState.numberOfRemainingRequests,
              peers.size
            )
            val (requests, newState1) = newState.assignTasksToPeers(peers, syncConfig.nodesPerRequest)
            implicit val ec = context.dispatcher
            requests.foreach(req => requestNodes(req))
            IO(processNodes(newState1, nodes)).unsafeToFuture().pipeTo(self)
            context.become(syncing(newState1))

          case (Some((nodes, newState)), None) =>
            log.debug(
              "Got {} peer responses remaining to process, but there are no peer slots to assign new tasks",
              newState.numberOfRemainingRequests
            )
            // we do not have any peers and cannot assign new tasks, but we can still process remaining requests
            IO(processNodes(newState, nodes)).unsafeToFuture().pipeTo(self)
            context.become(syncing(newState))

          case (None, Some(peers)) =>
            log.debug("There no responses to process, but there are {} free peer slots to assign new tasks", peers.size)
            val (requests, newState) = currentState.assignTasksToPeers(peers, syncConfig.nodesPerRequest)
            requests.foreach(req => requestNodes(req))
            context.become(syncing(newState.finishProcessing))

          case (None, None) =>
            log.debug(
              "There no responses to process, and no free peer slots. There are" +
                "{} active requests in flight",
              currentState.activeRequestCount
            )
            if (currentState.activeRequestCount == 0) {
              // we are not processing anything, and there are no free peers and we not waiting for any requests in flight
              // reschedule sync check
              timers.startSingleTimer(SyncKey, Sync, syncConfig.syncRetryInterval)
            }
            context.become(syncing(currentState.finishProcessing))
        }

      case Sync if currentState.hasRemainingPendingRequests && currentState.restartHasBeenRequested =>
        currentState.restartRequested.foreach { restartRequester =>
          handleRestart(
            currentState.currentSchedulerState,
            currentState.currentStats,
            currentState.targetBlock,
            restartRequester
          )
        }

      case Sync if !currentState.hasRemainingPendingRequests =>
        finalizeSync(currentState)

      case result: RequestResult =>
        if (currentState.isProcessing) {
          log.debug(
            "Response received while processing. Enqueuing for import later. Current response queue size: {}",
            currentState.nodesToProcess.size + 1
          )
          context.become(syncing(currentState.withNewRequestResult(result)))
        } else {
          log.debug("Response received while idle. Initiating response processing")
          val newState = currentState.initProcessing
          IO(processNodes(newState, result)).unsafeToFuture().pipeTo(self)
          context.become(syncing(newState))
        }

      case RestartRequested =>
        log.debug("Received restart request")
        if (currentState.isProcessing) {
          log.debug("Received restart while processing. Scheduling it after the task finishes")
          context.become(syncing(currentState.withRestartRequested(sender())))
        } else {
          log.debug("Received restart while idle.")
          handleRestart(
            currentState.currentSchedulerState,
            currentState.currentStats,
            currentState.targetBlock,
            sender()
          )
        }

      case ProcessingResult(Right(ProcessingSuccess(newState, newDownloaderState, newStats))) =>
        log.debug(
          "Finished processing mpt node batch. Got {} missing nodes. Missing queue has {} elements",
          newState.numberOfPendingRequests,
          newState.numberOfMissingHashes
        )
        val (newState1, newStats1) = if (newState.memBatch.size >= syncConfig.stateSyncPersistBatchSize) {
          log.debug("Current membatch size is {}, persisting nodes to database", newState.memBatch.size)
          (sync.persistBatch(newState, currentState.targetBlock), newStats.addSaved(newState.memBatch.size))
        } else {
          (newState, newStats)
        }

        reportStats(currentState.syncInitiator, newStats1, newState1)
        context.become(syncing(currentState.withNewProcessingResults(newState1, newDownloaderState, newStats1)))
        self ! Sync

      case ProcessingResult(Left(err)) =>
        log.debug("Received error result")
        err match {
          case Critical(er) =>
            log.error("Critical error while state syncing {}, stopping state sync", er)
            // TODO we should probably start sync again from new target block, as current trie is malformed or declare
            // fast sync as failure and start normal sync from scratch
            context.stop(self)
          case DownloaderError(newDownloaderState, peer, blacklistWithReason) =>
            log.debug("Downloader error by peer {}", peer)
            blacklistWithReason.foreach(blacklistIfHandshaked(peer.id, syncConfig.blacklistDuration, _))
            context.become(syncing(currentState.withNewDownloaderState(newDownloaderState)))
            self ! Sync
        }

      case TuneRateTracker =>
        rateTracker.tune()

      case PrintInfo =>
        log.info("{}", currentState)
        log.info(
          "PeerRateTracker: timeout={}s, medianRTT={}ms, confidence={}, tracked peers={}",
          rateTracker.targetTimeout().toSeconds,
          rateTracker.currentMedianRTT,
          "%.3f".format(rateTracker.currentConfidence),
          rateTracker.peerCount
        )
    }

  override def receive: Receive = waitingForBloomFilterToLoad(None)

  override def postStop(): Unit = {
    loadingCancelable.cancel.unsafeRunSync()(monixScheduler)
    super.postStop()
  }
}

// scalastyle:off number.of.methods
object SyncStateSchedulerActor {
  case object SyncKey
  case object Sync

  case object TuneRateTrackerKey
  case object TuneRateTracker

  private def reportStats(
      to: ActorRef,
      currentStats: ProcessingStatistics,
      currentState: SyncStateScheduler.SchedulerState
  ): Unit =
    to ! StateSyncStats(
      currentStats.saved + currentState.memBatch.size,
      currentState.numberOfPendingRequests
    )

  final case class StateSyncStats(saved: Long, missing: Long)

  final case class ProcessingResult(result: Either[ProcessingError, ProcessingSuccess])

  def props(
      sync: SyncStateScheduler,
      syncConfig: SyncConfig,
      networkPeerManager: ActorRef,
      peerEventBus: ActorRef,
      blacklist: Blacklist,
      scheduler: org.apache.pekko.actor.Scheduler
  ): Props =
    Props(
      new SyncStateSchedulerActor(sync, syncConfig, networkPeerManager, peerEventBus, blacklist, scheduler)(scheduler)
    )

  case object PrintInfo
  case object PrintInfoKey

  sealed trait SyncStateSchedulerActorCommand
  final case class StartSyncingTo(stateRoot: ByteString, blockNumber: BigInt) extends SyncStateSchedulerActorCommand
  case object RestartRequested extends SyncStateSchedulerActorCommand

  sealed trait SyncStateSchedulerActorResponse
  case object StateSyncFinished extends SyncStateSchedulerActorResponse
  case object WaitingForNewTargetBlock extends SyncStateSchedulerActorResponse
  case object NetworkIncompatible extends SyncStateSchedulerActorResponse

  final case class GetMissingNodes(nodesToGet: List[ByteString])
  final case class MissingNodes(missingNodes: List[SyncResponse], downloaderCapacity: Int)

  final case class BloomFilterResult(res: BloomFilterLoadingResult)

  sealed trait RequestResult
  final case class RequestData(nodeData: NodeData, from: Peer, requestId: Long) extends RequestResult
  final case class RequestFailed(from: Peer, reason: String, requestId: Long) extends RequestResult

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

  final case class RequestTerminated(to: Peer, requestId: Long)

  final case class PeerRequest(peer: Peer, nodes: NonEmptyList[ByteString], requestId: Long)

  case object RegisterScheduler

  sealed trait ResponseProcessingResult
  case object UnrequestedResponse extends ResponseProcessingResult
  case object NoUsefulDataInResponse extends ResponseProcessingResult
  final case class UsefulData(responses: List[SyncResponse]) extends ResponseProcessingResult
}
