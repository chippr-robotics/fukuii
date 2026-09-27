package com.chipprbots.ethereum.blockchain.sync.fast

import org.apache.pekko.actor.typed.ActorRef as TypedActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.PreRestart
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.TimerScheduler

import scala.concurrent.duration.*

import com.chipprbots.ethereum.blockchain.sync.Blacklist
import com.chipprbots.ethereum.blockchain.sync.Blacklist.BlacklistReason
import com.chipprbots.ethereum.blockchain.sync.PeerListHelper
import com.chipprbots.ethereum.blockchain.sync.PeerListSupportNg.PeerWithInfo
import com.chipprbots.ethereum.blockchain.sync.PeerRequestHandler
import com.chipprbots.ethereum.blockchain.sync.PeerRequestHandler.RequestFailed
import com.chipprbots.ethereum.blockchain.sync.PeerRequestHandler.ResponseReceived
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.Blockchain
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerEventBusActor.Command as PeerEventBusCommand
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.PeerDisconnected
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.network.p2p.messages.Codes
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.BlockHeaders as ETH68BlockHeaders
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.GetBlockHeaders as ETH68GetBlockHeaders
import com.chipprbots.ethereum.utils.Config.SyncConfig

/** Finds the first common block between our chain and the chain of the master peer (the peer with the highest block),
  * so regular sync (`BlockImporter`) can discard our diverged tip and re-download from the common ancestor. The name is
  * left over from fast sync, its first caller, which was removed.
  *
  * Pekko Typed migration (Group S2 — narrowed): `Behavior[Command]` with a sealed Command ADT. Three named behavior
  * states: `waitingForPeerWithHighestBlock` / `waitingForRecentBlockHeaders` / `waitingForBinarySearchBlock`. Peer-list
  * responsibilities live in [[PeerListHelper]]; the periodic `GetHandshakedPeers` poll runs via `Behaviors.withTimers`.
  *
  * All inbound message types are Command ADT members. `HandshakedPeers` replies from `NetworkPeerManagerActor` (Classic
  * tell) are wrapped via a `messageAdapter` into `HandshakedPeersMsg`. `PeerRequestHandler` results arrive wrapped as
  * `PeerRequestResult` via another adapter; the PRH child is spawned typed and death-watched with `watchWith(handler,
  * HandlerTerminated(handler))`. `replyTo: TypedActorRef[BranchResolverResponse]` is a typed ref: `BlockImporter`
  * passes a message adapter that wraps each reply in its own Command. The pure binary-search/discard logic stays in
  * [[FastSyncBranchResolver]] via [[BranchLogic]].
  */
object FastSyncBranchResolverActor:

  import FastSyncBranchResolver.*

  // ----- Sealed Command ADT -----

  sealed trait Command

  /** Begin (or restart) branch resolution. Public command sent by the parent / test. */
  case object StartBranchResolver extends Command

  /** Periodic timer fire: poll `networkPeerManager` for handshaked peers. */
  private case object ScanPeers extends Command

  /** A watched PeerRequestHandler child terminated. */
  final private case class HandlerTerminated(ref: TypedActorRef[PeerRequestHandler.Command]) extends Command

  /** Wraps the `HandshakedPeers` reply from `NetworkPeerManagerActor` (Classic tell → adapter). */
  final private case class HandshakedPeersMsg(msg: NetworkPeerManagerActor.HandshakedPeers) extends Command

  /** Wraps a `PeerDisconnected` event from the peer event bus adapter. */
  final private case class PeerDisconnectedMsg(event: PeerDisconnected) extends Command

  /** Wraps a `PeerRequestHandler.Result` reply from the PRH result adapter. */
  final private case class PeerRequestResult(result: PeerRequestHandler.Result) extends Command

  // ----- Replies to the caller (`replyTo`) -----

  sealed trait BranchResolverResponse
  final case class BranchResolvedSuccessful(highestCommonBlockNumber: BigInt, masterPeer: Peer)
      extends BranchResolverResponse

  import BranchResolutionFailed.*
  final case class BranchResolutionFailed(failure: BranchResolutionFailure) extends BranchResolverResponse
  object BranchResolutionFailed:
    def noCommonBlock: BranchResolutionFailed = BranchResolutionFailed(NoCommonBlockFound)
    def blockHeaderNotFound(blockHeaderNum: BigInt): BranchResolutionFailed = BranchResolutionFailed(
      BlockHeaderNotFound(blockHeaderNum)
    )

    sealed trait BranchResolutionFailure
    case object NoCommonBlockFound extends BranchResolutionFailure
    final case class BlockHeaderNotFound(blockHeaderNum: BigInt) extends BranchResolutionFailure

  // ----- Timer / log constants -----

  private val ScanKey: String = "ScanPeers"
  private val RestartTimerKey: String = "Restart"

  private val SwitchToBinarySearchLog: String =
    "Branch diverged earlier than {} blocks ago. Switching to binary search to determine first common block."

  private val ReceivedBlockHeaderLog: String =
    "Received requested block header [{}] from peer [{}] in {} ms"

  private val ReceivedWrongHeaders: String =
    "Received invalid response when requesting block header [{}]. Received: {}"

  private val peerTerminatedLog: String =
    "Peer request handler [{}] for peer [{}] terminated. Restarting branch resolver."

  /** Bundles the pure branch-resolution logic (`discardBlocksAfter`) provided by the trait. */
  private class BranchLogic(val blockchain: Blockchain, val blockchainReader: BlockchainReader)
      extends FastSyncBranchResolver

  // scalastyle:off parameter.number
  def apply(
      replyTo: TypedActorRef[BranchResolverResponse],
      peerEventBus: TypedActorRef[PeerEventBusCommand],
      networkPeerManager: TypedActorRef[NetworkPeerManagerActor.Command],
      blockchain: Blockchain,
      blockchainReader: BlockchainReader,
      blacklist: Blacklist,
      syncConfig: SyncConfig
  ): Behavior[Command] =
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        val peerDisconnectedAdapter: TypedActorRef[PeerEvent] =
          context.messageAdapter[PeerEvent] {
            case pd: PeerDisconnected => PeerDisconnectedMsg(pd)
            case e                    => throw new MatchError(s"unexpected PeerEvent from bus: $e")
          }

        val handshakedPeersAdapter: TypedActorRef[NetworkPeerManagerActor.HandshakedPeers] =
          context.messageAdapter[NetworkPeerManagerActor.HandshakedPeers](HandshakedPeersMsg(_))

        val peerListHelper = new PeerListHelper(
          peerEventBus,
          blacklist,
          peerDisconnectedAdapter,
          context.log
        )

        val branchLogic = new BranchLogic(blockchain, blockchainReader)

        val resolver = new Resolver(
          context,
          timers,
          replyTo,
          peerEventBus,
          networkPeerManager,
          blockchainReader,
          syncConfig,
          peerListHelper,
          branchLogic,
          new RecentBlocksSearch(blockchainReader),
          syncConfig.blockHeadersPerRequest,
          handshakedPeersAdapter
        )

        // Immediate poll, then periodic poll for handshaked peers (replaces PeerListSupportNg's scheduleWithFixedDelay).
        networkPeerManager ! NetworkPeerManagerActor.GetHandshakedPeersCmd(handshakedPeersAdapter)
        timers.startTimerWithFixedDelay(ScanKey, ScanPeers, syncConfig.peersScanInterval)

        // P19: surface PreRestart so a supervisor-triggered restart is visible. Timers are cancelled by
        // `withTimers` and the watched PeerRequestHandler children are stopped by Pekko on restart; the bus
        // subscriptions are owned by those children, so cleanup here is the warning log only. The restarted
        // instance re-polls for handshaked peers and re-issues StartBranchResolver from the parent.
        Behaviors.intercept(() =>
          new org.apache.pekko.actor.typed.BehaviorSignalInterceptor[Command]():
            override def aroundSignal(
                c: org.apache.pekko.actor.typed.TypedActorContext[Command],
                signal: org.apache.pekko.actor.typed.Signal,
                target: org.apache.pekko.actor.typed.BehaviorInterceptor.SignalTarget[Command]
            ): Behavior[Command] =
              if signal == PreRestart then
                c.asScala.log.warn(
                  "{} received PreRestart — abandoning in-flight branch resolution; children will be recreated",
                  c.asScala.self.path.name
                )
              target(c, signal)
        )(resolver.waitingForPeerWithHighestBlock())
      }
    }

  /** Holds the wiring shared across the behavior states. Each `waiting...` method returns the next `Behavior`. */
  private class Resolver(
      context: ActorContext[Command],
      timers: TimerScheduler[Command],
      replyTo: TypedActorRef[BranchResolverResponse],
      peerEventBus: TypedActorRef[PeerEventBusCommand],
      networkPeerManager: TypedActorRef[NetworkPeerManagerActor.Command],
      blockchainReader: BlockchainReader,
      syncConfig: SyncConfig,
      peerListHelper: PeerListHelper,
      branchLogic: BranchLogic,
      recentBlocksSearch: RecentBlocksSearch,
      recentHeadersSize: Int,
      handshakedPeersAdapter: TypedActorRef[NetworkPeerManagerActor.HandshakedPeers]
  ):
    import BinarySearchSupport.*

    private val prhResultAdapter: TypedActorRef[PeerRequestHandler.Result] =
      context.messageAdapter[PeerRequestHandler.Result](PeerRequestResult(_))

    // Monotonically increasing counter that makes each PeerRequestHandler child name unique within
    // this resolver's lifetime, even when the same peer is used across multiple binary-search steps.
    private var requestSeq: Int = 0

    private def log = context.log

    /** Shared peer-list / scan handling for every state. Returns `Some(next)` if the message was handled. */
    private def handleCommon(message: Command): Option[Behavior[Command]] = message match
      case ScanPeers =>
        networkPeerManager ! NetworkPeerManagerActor.GetHandshakedPeersCmd(handshakedPeersAdapter)
        Some(Behaviors.same)
      case HandshakedPeersMsg(NetworkPeerManagerActor.HandshakedPeers(peers)) =>
        peerListHelper.handleHandshakedPeers(peers)
        Some(Behaviors.same)
      case PeerDisconnectedMsg(PeerDisconnected(peerId)) =>
        peerListHelper.handlePeerDisconnected(peerId)
        Some(Behaviors.same)
      case _ => None

    def waitingForPeerWithHighestBlock(): Behavior[Command] =
      Behaviors.receiveMessage { message =>
        handleCommon(message).getOrElse {
          message match
            case StartBranchResolver =>
              peerListHelper.getPeerWithHighestBlock match
                case Some(peerWithInfo @ PeerWithInfo(peer, _)) =>
                  log.debug(
                    "Starting branch resolution now with peer {} and block number {}",
                    peerWithInfo,
                    blockchainReader.getBestBlockNumber
                  )
                  requestRecentBlockHeaders(peer, blockchainReader.getBestBlockNumber)
                case None =>
                  log.info("Waiting for peers, rescheduling StartBranchResolver")
                  timers.startSingleTimer(RestartTimerKey, StartBranchResolver, 1.second)
                  Behaviors.same
            case _ => Behaviors.same
        }
      }

    private def waitingForRecentBlockHeaders(
        masterPeer: Peer,
        bestBlockNumber: BigInt,
        requestHandler: TypedActorRef[PeerRequestHandler.Command]
    ): Behavior[Command] =
      Behaviors.receiveMessage { message =>
        handleCommon(message).getOrElse {
          message match
            case PeerRequestResult(ResponseReceived(_, peer, ETH68BlockHeaders(_, headers), timeTaken))
                if peer == masterPeer =>
              if headers.size == recentHeadersSize then
                log.debug("Received {} block headers from peer {} in {} ms", headers.size, masterPeer.id, timeTaken)
                handleRecentBlockHeadersResponse(headers, masterPeer, bestBlockNumber)
              else handleInvalidResponse(peer, requestHandler)
            case PeerRequestResult(RequestFailed(_, peer, reason)) =>
              handleRequestFailure(peer, requestHandler, reason)
            case HandlerTerminated(ref) if ref == requestHandler =>
              handlePeerTermination(masterPeer, ref)
            case _ => Behaviors.same
        }
      }

    private def waitingForBinarySearchBlock(
        searchState: SearchState,
        blockHeaderNumberToSearch: BigInt,
        requestHandler: TypedActorRef[PeerRequestHandler.Command]
    ): Behavior[Command] =
      Behaviors.receiveMessage { message =>
        handleCommon(message).getOrElse {
          message match
            case PeerRequestResult(ResponseReceived(_, peer, ETH68BlockHeaders(_, headers), durationMs))
                if peer == searchState.masterPeer =>
              context.unwatch(requestHandler)
              headers.toList match
                case childHeader :: Nil if childHeader.number.value == blockHeaderNumberToSearch =>
                  log.debug(ReceivedBlockHeaderLog, blockHeaderNumberToSearch, peer.id, durationMs)
                  handleBinarySearchBlockHeaderResponse(searchState, childHeader)
                case _ =>
                  log.warn(ReceivedWrongHeaders, blockHeaderNumberToSearch, headers.map(_.number.value))
                  handleInvalidResponse(peer, requestHandler)
            case PeerRequestResult(RequestFailed(_, peer, reason)) =>
              handleRequestFailure(peer, requestHandler, reason)
            case HandlerTerminated(ref) if ref == requestHandler =>
              handlePeerTermination(searchState.masterPeer, ref)
            case HandlerTerminated(_) => Behaviors.same // ignore stale death-watch
            case _                    => Behaviors.same
        }
      }

    private def requestRecentBlockHeaders(masterPeer: Peer, bestBlockNumber: BigInt): Behavior[Command] =
      val requestHandler = sendGetBlockHeadersRequest(
        masterPeer,
        fromBlock = childOf((bestBlockNumber - recentHeadersSize).max(0)),
        amount = recentHeadersSize
      )
      waitingForRecentBlockHeaders(masterPeer, bestBlockNumber, requestHandler)

    /** Searches recent blocks for a valid parent/child relationship. If none found, switch to binary search. */
    private def handleRecentBlockHeadersResponse(
        blockHeaders: Seq[BlockHeader],
        masterPeer: Peer,
        bestBlockNumber: BigInt
    ): Behavior[Command] =
      recentBlocksSearch.getHighestCommonBlock(blockHeaders, bestBlockNumber) match
        case Some(highestCommonBlockNumber) =>
          finalizeBranchResolver(highestCommonBlockNumber, masterPeer)
        case None =>
          log.info(SwitchToBinarySearchLog, recentHeadersSize)
          requestBlockHeaderForBinarySearch(
            SearchState(minBlockNumber = 1, maxBlockNumber = bestBlockNumber, masterPeer)
          )

    private def requestBlockHeaderForBinarySearch(searchState: SearchState): Behavior[Command] =
      val headerNumberToRequest = blockHeaderNumberToRequest(searchState.minBlockNumber, searchState.maxBlockNumber)
      val handler = sendGetBlockHeadersRequest(searchState.masterPeer, headerNumberToRequest, 1)
      waitingForBinarySearchBlock(searchState, headerNumberToRequest, handler)

    private def handleBinarySearchBlockHeaderResponse(
        searchState: SearchState,
        childHeader: BlockHeader
    ): Behavior[Command] =
      import BinarySearchSupport.*
      blockchainReader.getBlockHeaderByNumber(parentOf(childHeader.number.value)) match
        case Some(parentHeader) =>
          validateBlockHeaders(parentHeader, childHeader, searchState) match
            case NoCommonBlock => stopWithFailure(BranchResolutionFailed.noCommonBlock)
            case BinarySearchCompleted(highestCommonBlockNumber) =>
              finalizeBranchResolver(highestCommonBlockNumber, searchState.masterPeer)
            case ContinueBinarySearch(newSearchState) =>
              log.debug(s"Continuing binary search with new search state: $newSearchState")
              requestBlockHeaderForBinarySearch(newSearchState)
        case None => stopWithFailure(BranchResolutionFailed.blockHeaderNotFound(childHeader.number.value))

    private def finalizeBranchResolver(firstCommonBlockNumber: BigInt, masterPeer: Peer): Behavior[Command] =
      branchLogic.discardBlocksAfter(firstCommonBlockNumber)
      log.info(s"Branch resolution completed with first common block number [$firstCommonBlockNumber]")
      replyTo ! BranchResolvedSuccessful(highestCommonBlockNumber = firstCommonBlockNumber, masterPeer = masterPeer)
      Behaviors.stopped

    /** On fatal errors (and to prevent trying forever) signal the caller and let it decide whether to retry. */
    private def stopWithFailure(response: BranchResolutionFailed): Behavior[Command] =
      replyTo ! response
      Behaviors.stopped

    private def sendGetBlockHeadersRequest(
        peer: Peer,
        fromBlock: BigInt,
        amount: BigInt
    ): TypedActorRef[PeerRequestHandler.Command] =
      // ETH68+ always uses request-id; capability check kept for parity with the Classic implementation.
      val _ = peerListHelper.handshakedPeers
        .get(peer.id)
        .exists(peerWithInfo => Capability.usesRequestId(peerWithInfo.peerInfo.remoteStatus.capability))

      requestSeq += 1
      val handler = context.spawn(
        PeerRequestHandler.behavior[ETH68GetBlockHeaders, ETH68BlockHeaders](
          peer,
          syncConfig.peerResponseTimeout,
          networkPeerManager,
          peerEventBus,
          requestMsg =
            ETH68GetBlockHeaders(ETHPackets.nextRequestId, Left(fromBlock), amount, skip = 0, reverse = false),
          responseMsgCode = Codes.BlockHeadersCode,
          replyTo = prhResultAdapter,
          requestId = 0
        ),
        s"branch-resolver-header-request-${peer.id.value}-$requestSeq"
      )
      context.watchWith(handler, HandlerTerminated(handler))
      handler

    private def handleInvalidResponse(
        peer: Peer,
        peerRef: TypedActorRef[PeerRequestHandler.Command]
    ): Behavior[Command] =
      log.warn(s"Received invalid response from peer [${peer.id}]. Restarting branch resolver.")
      context.unwatch(peerRef)
      peerListHelper.blacklistIfHandshaked(
        peer.id,
        syncConfig.blacklistDuration,
        BlacklistReason.WrongBlockHeaders
      )
      restart()

    private def handleRequestFailure(
        peer: Peer,
        peerRef: TypedActorRef[PeerRequestHandler.Command],
        reason: String
    ): Behavior[Command] =
      log.warn(s"Request to peer [${peer.id}] failed: [$reason]. Restarting branch resolver.")
      context.unwatch(peerRef)
      peerListHelper.blacklistIfHandshaked(
        peer.id,
        syncConfig.blacklistDuration,
        BlacklistReason.FastSyncRequestFailed(reason)
      )
      restart()

    private def handlePeerTermination(
        peer: Peer,
        peerHandlerRef: TypedActorRef[PeerRequestHandler.Command]
    ): Behavior[Command] =
      log.warn(peerTerminatedLog, peerHandlerRef.path.name, peer.id)
      restart()

    private def restart(): Behavior[Command] =
      context.self ! StartBranchResolver
      waitingForPeerWithHighestBlock()
