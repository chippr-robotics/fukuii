package com.chipprbots.ethereum.blockchain.sync.fast

import org.apache.pekko.actor.typed.ActorRef as TypedActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.PreRestart
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.TimerScheduler

import cats.data.NonEmptyList

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

/** Finds the first common block between our chain and the chain of the master peer (the peer with the highest block; on
  * the hash layout the peer with the heaviest chain), so regular sync (`BlockImporter`) can discard our diverged tip
  * and re-download from the common ancestor. The name is left over from fast sync, its first caller, which was removed.
  *
  * On the hash layout ([[DiscardGuard]], B-298) nothing is discarded unless the common block lies no deeper than the
  * guard allows and the master peer's branch above it, fetched as headers, is the heavier by the import's fork choice;
  * otherwise the peer is blacklisted and the answer is a [[BranchResolutionFailed.BranchNotFollowed]]. core-geth's
  * downloader likewise refuses an ancestor below its fork-ancestry floor, and never sets its head back for a branch it
  * has not weighed.
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

  /** @param discarded
    *   the blocks the resolver removed above the common block (hash layout), by number and hash, read before their
    *   entries of the index were removed: the import undoes their reference counts (B-248). Not part of the extractor.
    */
  final case class BranchResolvedSuccessful(highestCommonBlockNumber: BigInt, masterPeer: Peer)(
      val discarded: Seq[(BigInt, org.apache.pekko.util.ByteString)] = Nil
  ) extends BranchResolverResponse

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

    /** The hash layout's refusals (B-298): the master peer's chain is one this node does not follow, and nothing was
      * taken off this node's chain. The caller does not rewind; the peer is blacklisted.
      */
    sealed trait BranchNotFollowed extends BranchResolutionFailure

    /** The master peer's chain leaves this node's below `deepestCommonBlock`, deeper than a rewind may reach. */
    final case class CommonBlockBeyondReach(deepestCommonBlock: BigInt) extends BranchNotFollowed

    /** The master peer's branch above `commonBlock` was weighed against this node's own blocks above it and is not the
      * heavier (or its weight could not be shown with the headers it served).
      */
    final case class BranchNotHeavier(commonBlock: BigInt) extends BranchNotFollowed

  /** What the hash layout checks before it takes this node's blocks above the common block off the chain (B-298).
    * Without it the resolver only reports the common block (the unified layout, spec 018 C4e part 6).
    *
    * @param deepestCommonBlock
    *   the lowest common block a discard may go down to: a chain that leaves this node's below it is refused, never
    *   rewound to
    * @param heavier
    *   whether a branch, given by its headers from the child of the common block up, is to be taken over this node's
    *   blocks above the common block: the fork choice the import applies (`BranchResolution.resolveBranch`, weight and
    *   MESS). `None` where the import does not choose by weight (a chain that follows a consensus layer): the branch is
    *   then not weighed here, as before
    */
  final case class DiscardGuard(
      deepestCommonBlock: BigInt,
      heavier: Option[NonEmptyList[BlockHeader] => Boolean]
  )

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
      syncConfig: SyncConfig,
      // Given: this node's own blocks above the common block are removed once it is found, and only within the guard's
      // depth and for a heavier branch (the hash layout, B-298). None on the unified state layout, where a block is not
      // taken off the chain apart from its state: the caller only learns the block (spec 018 C4e part 6).
      discardGuard: Option[DiscardGuard]
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
          handshakedPeersAdapter,
          discardGuard
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
      handshakedPeersAdapter: TypedActorRef[NetworkPeerManagerActor.HandshakedPeers],
      discardGuard: Option[DiscardGuard]
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
              // the hash layout resolves against the heaviest chain, as fork choice is by weight (B-298); the unified
              // layout keeps the highest block, as it was
              val master =
                if discardGuard.isDefined then peerListHelper.getPeerWithHighestWeight
                else peerListHelper.getPeerWithHighestBlock
              master match
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
          discardGuard.map(_.deepestCommonBlock).filter(_ > 1) match
            case Some(deepest) =>
              // the hash layout: the search is held above the deepest common block a discard may reach, which is shown
              // to be common first (B-298); the search assumes its lower bound is
              blockchainReader.getBlockHeaderByNumber(deepest) match
                case Some(deepestHeader) =>
                  val handler = sendGetBlockHeadersRequest(masterPeer, childOf(deepest), 1)
                  waitingForDeepestCommonBlock(masterPeer, bestBlockNumber, deepestHeader, handler)
                case None => stopWithFailure(BranchResolutionFailed.blockHeaderNotFound(deepest))
            case None =>
              requestBlockHeaderForBinarySearch(
                SearchState(minBlockNumber = 1, maxBlockNumber = bestBlockNumber, masterPeer)
              )

    /** The answer for the child of the deepest common block a discard may reach (B-298): it is common, and the search
      * goes on above it, or the master peer's chain leaves this node's deeper than that and is refused.
      */
    private def waitingForDeepestCommonBlock(
        masterPeer: Peer,
        bestBlockNumber: BigInt,
        deepestHeader: BlockHeader,
        requestHandler: TypedActorRef[PeerRequestHandler.Command]
    ): Behavior[Command] =
      val deepest = deepestHeader.number.value
      Behaviors.receiveMessage { message =>
        handleCommon(message).getOrElse {
          message match
            case PeerRequestResult(ResponseReceived(_, peer, ETH68BlockHeaders(_, headers), _)) if peer == masterPeer =>
              context.unwatch(requestHandler)
              headers.toList match
                case child :: Nil if child.number.value == childOf(deepest) =>
                  // the peer's child of the floor does not hang off this node's block there: the chains part below it
                  if !deepestHeader.isParentOf(child) then refuseBeyondReach(masterPeer, deepest)
                  else if deepest >= bestBlockNumber then finalizeBranchResolver(deepest, masterPeer)
                  else
                    requestBlockHeaderForBinarySearch(
                      SearchState(minBlockNumber = deepest, maxBlockNumber = bestBlockNumber, masterPeer)
                    )
                case _ =>
                  log.warn(ReceivedWrongHeaders, childOf(deepest), headers.map(_.number.value))
                  handleInvalidResponse(peer, requestHandler)
            case PeerRequestResult(RequestFailed(_, peer, reason)) =>
              handleRequestFailure(peer, requestHandler, reason)
            case HandlerTerminated(ref) if ref == requestHandler =>
              handlePeerTermination(masterPeer, ref)
            case _ => Behaviors.same
        }
      }

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
      discardGuard match
        case None => reply(firstCommonBlockNumber, masterPeer, Nil)
        // every way here (the recent window, the search, a peer's answers that contradict each other) is held to the
        // floor: a common block below it is refused, never discarded down to
        case Some(guard) if firstCommonBlockNumber < guard.deepestCommonBlock =>
          refuseBeyondReach(masterPeer, guard.deepestCommonBlock)
        case Some(guard) =>
          val best = blockchainReader.getBestBlockNumber
          guard.heavier match
            // nothing of this node's chain lies above the common block, or the branch is not weighed here: as before
            case Some(heavier) if firstCommonBlockNumber < best =>
              blockchainReader.getBlockHeaderByNumber(firstCommonBlockNumber) match
                case Some(commonHeader) =>
                  val wanted = (best - firstCommonBlockNumber) + 2 * recentHeadersSize
                  requestBranchHeaders(masterPeer, commonHeader, Vector.empty, wanted, heavier)
                case None => stopWithFailure(BranchResolutionFailed.blockHeaderNotFound(firstCommonBlockNumber))
            case _ => discardAndReply(firstCommonBlockNumber, masterPeer)

    private def discardAndReply(firstCommonBlockNumber: BigInt, masterPeer: Peer): Behavior[Command] =
      reply(firstCommonBlockNumber, masterPeer, branchLogic.discardBlocksAfter(firstCommonBlockNumber))

    private def reply(
        firstCommonBlockNumber: BigInt,
        masterPeer: Peer,
        discarded: Seq[(BigInt, org.apache.pekko.util.ByteString)]
    ): Behavior[Command] =
      log.info(s"Branch resolution completed with first common block number [$firstCommonBlockNumber]")
      replyTo ! BranchResolvedSuccessful(highestCommonBlockNumber = firstCommonBlockNumber, masterPeer = masterPeer)(
        discarded
      )
      Behaviors.stopped

    /** The master peer's headers of its branch above `commonHeader`, one request after another, until they outweigh
      * this node's blocks above it, `wanted` are fetched, or the peer serves none (B-298). A prefix of a branch that is
      * already the heavier stays so with the rest of it, MESS included, so the first verdict for it is final.
      */
    private def requestBranchHeaders(
        masterPeer: Peer,
        commonHeader: BlockHeader,
        fetched: Vector[BlockHeader],
        wanted: BigInt,
        heavier: NonEmptyList[BlockHeader] => Boolean
    ): Behavior[Command] =
      val from = fetched.lastOption.getOrElse(commonHeader).number.value + 1
      val handler = sendGetBlockHeadersRequest(masterPeer, from, (wanted - fetched.size).min(BigInt(recentHeadersSize)))
      waitingForBranchHeaders(masterPeer, commonHeader, fetched, wanted, heavier, handler)

    private def waitingForBranchHeaders(
        masterPeer: Peer,
        commonHeader: BlockHeader,
        fetched: Vector[BlockHeader],
        wanted: BigInt,
        heavier: NonEmptyList[BlockHeader] => Boolean,
        requestHandler: TypedActorRef[PeerRequestHandler.Command]
    ): Behavior[Command] =
      val common = commonHeader.number.value
      Behaviors.receiveMessage { message =>
        handleCommon(message).getOrElse {
          message match
            case PeerRequestResult(ResponseReceived(_, peer, ETH68BlockHeaders(_, headers), _)) if peer == masterPeer =>
              context.unwatch(requestHandler)
              if !continuesBranch(fetched.lastOption.getOrElse(commonHeader), headers) then
                log.warn(
                  ReceivedWrongHeaders,
                  fetched.lastOption.getOrElse(commonHeader).number.value + 1,
                  headers.map(_.number.value)
                )
                handleInvalidResponse(peer, requestHandler)
              else
                val all = fetched ++ headers
                NonEmptyList.fromList(all.toList) match
                  case Some(branch) if heavier(branch) =>
                    log.info(
                      "[SYNC-FORK] Peer {}'s branch above block {} is the heavier with {} header(s): this node's " +
                        "blocks above it are discarded",
                      masterPeer.id,
                      common,
                      all.size
                    )
                    discardAndReply(common, masterPeer)
                  // only an answer with none ends the branch: one with fewer than were asked for is a peer's limit
                  case _ if headers.isEmpty || all.size >= wanted =>
                    refuseNotHeavier(masterPeer, common, all.size)
                  case _ => requestBranchHeaders(masterPeer, commonHeader, all, wanted, heavier)
            case PeerRequestResult(RequestFailed(_, peer, reason)) =>
              handleRequestFailure(peer, requestHandler, reason)
            case HandlerTerminated(ref) if ref == requestHandler =>
              handlePeerTermination(masterPeer, ref)
            case _ => Behaviors.same
        }
      }

    private def continuesBranch(below: BlockHeader, headers: Seq[BlockHeader]): Boolean =
      headers
        .foldLeft(Option(below))((parent, header) => parent.filter(_.isParentOf(header)).map(_ => header))
        .isDefined

    private def refuseBeyondReach(masterPeer: Peer, deepest: BigInt): Behavior[Command] =
      log.error(
        "[SYNC-FORK] Peer {}'s chain leaves this node's below block {}, deeper than a fork recovery may rewind to " +
          "(best {}): refused, nothing is discarded and the peer is blacklisted",
        masterPeer.id,
        deepest,
        blockchainReader.getBestBlockNumber
      )
      peerListHelper.blacklistIfHandshaked(
        masterPeer.id,
        syncConfig.blacklistDuration,
        BlacklistReason.ForkNotFollowed(s"its chain leaves ours below block $deepest")
      )
      stopWithFailure(BranchResolutionFailed(CommonBlockBeyondReach(deepest)))

    private def refuseNotHeavier(masterPeer: Peer, common: BigInt, weighed: Int): Behavior[Command] =
      log.warn(
        "[SYNC-FORK] Peer {}'s branch above block {} is not the heavier ({} header(s) weighed against this node's " +
          "blocks up to {}): nothing is discarded and the peer is blacklisted",
        masterPeer.id,
        common,
        weighed,
        blockchainReader.getBestBlockNumber
      )
      peerListHelper.blacklistIfHandshaked(
        masterPeer.id,
        syncConfig.blacklistDuration,
        BlacklistReason.ForkNotFollowed(s"its branch above block $common is not the heavier")
      )
      stopWithFailure(BranchResolutionFailed(BranchNotHeavier(common)))

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
