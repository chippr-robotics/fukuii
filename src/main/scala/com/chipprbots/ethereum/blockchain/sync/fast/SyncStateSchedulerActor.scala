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
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.mpt.HexPrefix
import com.chipprbots.ethereum.network.p2p.messages.Codes
import com.chipprbots.ethereum.network.p2p.messages.ETH63.GetNodeData
import com.chipprbots.ethereum.network.p2p.messages.ETH63.NodeData
import com.chipprbots.ethereum.network.p2p.messages.ETH66
import com.chipprbots.ethereum.network.p2p.messages.SNAP
import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetTrieNodes
import com.chipprbots.ethereum.network.p2p.messages.SNAP.TrieNodes
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

  /** State root for the current sync target — needed for GetTrieNodes requests. */
  private var currentStateRoot: ByteString = ByteString.empty

  /** Track consecutive "no useful data" responses — signals the state root is stale. After the threshold, the scheduler
    * requests a restart with a fresh root.
    */
  private var consecutiveUselessResponses: Int = 0
  private val UselessResponseThreshold: Int = 20

  /** Check if a capability supports GetNodeData message. GetNodeData is available in ETH63-67 but removed in ETH68 per
    * EIP-4938. SNAP protocol uses different messages (GetAccountRange, etc.) and doesn't support GetNodeData.
    *
    * Note: Capability is a sealed trait, so this match is exhaustive. If new capabilities are added in the future, this
    * method will need to be updated accordingly.
    */
  /** Check if a peer supports GetNodeData on the negotiated protocol. GetNodeData is available in ETH63-67 but removed
    * in ETH68 (EIP-4938). Only the negotiated (connection-level) capability matters — sending GetNodeData on an ETH68
    * connection causes the peer to disconnect.
    */
  private def supportsGetNodeData(capability: Capability): Boolean = capability match {
    case Capability.ETH63 | Capability.ETH64 | Capability.ETH65 | Capability.ETH66 | Capability.ETH67 => true
    case Capability.ETH68                                                                             => false
    case Capability.SNAP1                                                                             => false
  }

  /** Find free peers that can serve state nodes via either GetNodeData (ETH66/67) or GetTrieNodes (SNAP). On modern ETC
    * networks, most peers negotiate ETH68 which doesn't support GetNodeData. These peers typically also support snap/1,
    * so we can use GetTrieNodes with nibble paths instead.
    */
  private def getFreePeers(state: DownloaderState): List[Peer] = {
    val freePeers = peersToDownloadFrom.collect {
      case (_, PeerWithInfo(peer, peerInfo))
          if !state.activeRequests.contains(peer.id) &&
            (supportsGetNodeData(peerInfo.remoteStatus.capability) || peerInfo.remoteStatus.supportsSnap) =>
        peer
    }.toList

    if (freePeers.isEmpty && peersToDownloadFrom.nonEmpty) {
      log.debug(
        "No free peers for state download ({} total, {} with active requests)",
        peersToDownloadFrom.size,
        state.activeRequests.size
      )
    }
    freePeers
  }

  /** Check if a specific peer should use SNAP GetTrieNodes instead of GetNodeData */
  private def peerUsesSnap(peer: Peer): Boolean =
    handshakedPeers.get(peer.id).exists { pwi =>
      !supportsGetNodeData(pwi.peerInfo.remoteStatus.capability) && pwi.peerInfo.remoteStatus.supportsSnap
    }

  private def requestNodes(request: PeerRequest): ActorRef = {
    val useSnap = peerUsesSnap(request.peer)
    log.debug(
      "Requesting {} nodes from peer {} via {}",
      request.nodes.size,
      request.peer.id,
      if (useSnap) "GetTrieNodes" else "GetNodeData"
    )

    val handler = if (useSnap && currentStateRoot.nonEmpty) {
      // Convert hash-based requests to SNAP GetTrieNodes with nibble paths
      val paths: Seq[Seq[ByteString]] = request.nodes.toList.map { hash =>
        request.pathInfo.get(hash) match {
          case Some((nibblePath, accountHashOpt)) =>
            val compactPath = ByteString(HexPrefix.encode(nibblePath.toArray, isLeaf = false))
            accountHashOpt match {
              case Some(acctHash) => Seq(acctHash, compactPath)
              case None           => Seq(compactPath)
            }
          case None =>
            // No path info — request root path as fallback
            Seq(ByteString(HexPrefix.encode(Array.empty[Byte], isLeaf = false)))
        }
      }
      context.actorOf(
        PeerRequestHandler.props[GetTrieNodes, TrieNodes](
          request.peer,
          syncConfig.peerResponseTimeout,
          networkPeerManager,
          peerEventBus,
          requestMsg = GetTrieNodes(
            requestId = ETH66.nextRequestId,
            rootHash = currentStateRoot,
            paths = paths,
            responseBytes = BigInt(512 * 1024)
          ),
          responseMsgCode = SNAP.Codes.TrieNodesCode
        )
      )
    } else {
      // Original GetNodeData path for ETH66/67 peers
      context.actorOf(
        PeerRequestHandler.props[GetNodeData, NodeData](
          request.peer,
          syncConfig.peerResponseTimeout,
          networkPeerManager,
          peerEventBus,
          requestMsg = GetNodeData(request.nodes.toList),
          responseMsgCode = Codes.NodeDataCode
        )
      )
    }
    context.watchWith(handler, RequestTerminated(request.peer))
  }

  def handleRequestResults: Receive = {
    case ResponseReceived(peer, nodeData: NodeData, timeTaken) =>
      log.debug("Received {} state nodes via GetNodeData in {} ms", nodeData.values.size, timeTaken)
      FastSyncMetrics.setMptStateDownloadTime(timeTaken)
      context.unwatch(sender())
      self ! RequestData(nodeData, peer)

    case ResponseReceived(peer, trieNodes: TrieNodes, timeTaken) =>
      // Convert SNAP TrieNodes response to NodeData format.
      // TrieNodes returns raw node bytes — wrap them as NodeData values.
      log.debug("Received {} state nodes via GetTrieNodes in {} ms", trieNodes.nodes.size, timeTaken)
      FastSyncMetrics.setMptStateDownloadTime(timeTaken)
      context.unwatch(sender())
      val asNodeData = NodeData(trieNodes.nodes.toList)
      self ! RequestData(asNodeData, peer)

    case PeerRequestHandler.RequestFailed(peer, reason) =>
      context.unwatch(sender())
      log.debug("Request to peer {} failed due to {}", peer.id, reason)
      self ! RequestFailed(peer, reason)
    case RequestTerminated(peer) =>
      log.debug("Request to {} terminated", peer.id)
      self ! RequestFailed(peer, "Peer disconnected in the middle of request")
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
    currentStateRoot = root
    consecutiveUselessResponses = 0
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
  }

  private def finalizeSync(
      state: SyncSchedulerActorState
  ): Unit =
    if (state.memBatch.nonEmpty) {
      log.debug("Persisting {} elements to blockchain and finalizing the state sync", state.memBatch.size)
      val finalState = sync.persistBatch(state.currentSchedulerState, state.targetBlock)
      reportStats(state.syncInitiator, state.currentStats.addSaved(state.memBatch.size), finalState)
      state.syncInitiator ! StateSyncFinished
      context.become(idle(ProcessingStatistics()))
    } else {
      log.info("Finalizing the state sync")
      state.syncInitiator ! StateSyncFinished
      context.become(idle(ProcessingStatistics()))
    }

  private def processNodes(
      currentState: SyncSchedulerActorState,
      requestResult: RequestResult
  ): ProcessingResult =
    requestResult match {
      case RequestData(nodeData, from) =>
        val (resp, newDownloaderState) = currentState.currentDownloaderState.handleRequestSuccess(from, nodeData)
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
      case RequestFailed(from, reason) =>
        log.debug("Processing failed request from {}. Failure reason {}", from, reason)
        val newDownloaderState = currentState.currentDownloaderState.handleRequestFailure(from)
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
              "Got {} peer responses remaining to process, and there are {} idle peers available",
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
              "Got {} peer responses remaining to process, but there are no idle peers to assign new tasks",
              newState.numberOfRemainingRequests
            )
            // we do not have any peers and cannot assign new tasks, but we can still process remaining requests
            IO(processNodes(newState, nodes)).unsafeToFuture().pipeTo(self)
            context.become(syncing(newState))

          case (None, Some(peers)) =>
            log.debug("There no responses to process, but there are {} free peers to assign new tasks", peers.size)
            val (requests, newState) = currentState.assignTasksToPeers(peers, syncConfig.nodesPerRequest)
            requests.foreach(req => requestNodes(req))
            context.become(syncing(newState.finishProcessing))

          case (None, None) =>
            log.debug(
              "There no responses to process, and no free peers to assign new tasks. There are" +
                "{} active requests in flight",
              currentState.activePeerRequests.size
            )
            if (currentState.activePeerRequests.isEmpty) {
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
        consecutiveUselessResponses = 0 // Reset on successful processing
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
            // Track consecutive useless responses — all peers returning useless data
            // means the state root is stale and needs refreshing.
            blacklistWithReason match {
              case Some(InvalidStateResponse(_)) =>
                consecutiveUselessResponses += 1
                if (consecutiveUselessResponses >= UselessResponseThreshold && !currentState.restartHasBeenRequested) {
                  log.warning(
                    "{} consecutive useless responses — state root likely stale. Triggering self-restart.",
                    consecutiveUselessResponses
                  )
                  consecutiveUselessResponses = 0
                  handleRestart(
                    currentState.currentSchedulerState,
                    currentState.currentStats,
                    currentState.targetBlock,
                    context.parent
                  )
                } else {
                  context.become(syncing(currentState.withNewDownloaderState(newDownloaderState)))
                  self ! Sync
                }
              case _ =>
                context.become(syncing(currentState.withNewDownloaderState(newDownloaderState)))
                self ! Sync
            }
        }

      case PrintInfo =>
        log.info("{}", currentState)
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

  final case class RequestTerminated(to: Peer)

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
}
