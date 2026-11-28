package com.chipprbots.ethereum.blockchain.sync.fast

import org.apache.pekko.actor.typed.ActorRef as TypedActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.PreRestart
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.TimerScheduler

import cats.data.NonEmptyList
import cats.effect.IO
import cats.effect.unsafe.IORuntime

import scala.concurrent.duration.*
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import com.chipprbots.ethereum.blockchain.sync.Blacklist
import com.chipprbots.ethereum.blockchain.sync.Blacklist.BlacklistReason
import com.chipprbots.ethereum.blockchain.sync.PeerListHelper
import com.chipprbots.ethereum.blockchain.sync.PeerListSupportNg.PeerWithInfo
import com.chipprbots.ethereum.blockchain.sync.PeerRequestHandler
import com.chipprbots.ethereum.blockchain.sync.PeerRequestHandler.RequestFailed
import com.chipprbots.ethereum.blockchain.sync.PeerRequestHandler.ResponseReceived
import com.chipprbots.ethereum.blockchain.sync.regular.ImportLock
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
  * downloader likewise refuses an ancestor at or below its fork-ancestry floor (`errInvalidAncestor`, and the peer is
  * dropped), and its `findAncestor` is read-only: a failed search sets no head back. A chain that leaves this node's
  * below the floor is searched for its common block there and weighed from it, with headers only (B-346): the heavier
  * is answered with [[BranchHeavierBeyondReach]] for the caller to escalate, and nothing below the floor is ever
  * discarded. Where the branch is weighed, no failure of the resolution is left to the caller's unweighed fallback
  * (B-347): a height this node holds no header at is not common, and the search goes on below it (core-geth's `known`
  * check); a chain that shares no block above the genesis is weighed from the genesis; and a common block whose header
  * is gone by the time it is weighed is refused ([[BranchResolutionFailed.HeaderNotStored]]).
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

  /** A watched PeerRequestHandler child terminated: what `watchWith` delivers, as the death-watch notification is
    * processed. It is re-posted to self as [[HandlerGone]] before anything reads it (B-350).
    */
  final private case class HandlerTerminated(ref: TypedActorRef[PeerRequestHandler.Command]) extends Command

  /** [[HandlerTerminated]], re-posted to self, so it is read after every message the child sent before it stopped.
    *
    * A death-watch notification is a system message, processed ahead of the mailbox: a PeerRequestHandler that tells
    * its `ResponseReceived` and stops can have its termination read before its response. The resolver then took a peer
    * that had answered for one that had not, and restarted the resolution (B-350: the scripted spec's replies then ran
    * one request ahead, ~1 run in 3). Re-posted, it queues behind the response; by then the state has moved to the next
    * request, whose handler is another, and the stale termination is ignored.
    */
  final private case class HandlerGone(ref: TypedActorRef[PeerRequestHandler.Command]) extends Command

  /** Wraps the `HandshakedPeers` reply from `NetworkPeerManagerActor` (Classic tell → adapter). */
  final private case class HandshakedPeersMsg(msg: NetworkPeerManagerActor.HandshakedPeers) extends Command

  /** Wraps a `PeerDisconnected` event from the peer event bus adapter. */
  final private case class PeerDisconnectedMsg(event: PeerDisconnected) extends Command

  /** Wraps a `PeerRequestHandler.Result` reply from the PRH result adapter. */
  final private case class PeerRequestResult(result: PeerRequestHandler.Result) extends Command

  /** The discard above the common block, made under the import lock (B-012): the blocks it removed, or its failure. */
  final private case class Discarded(
      firstCommonBlockNumber: BigInt,
      masterPeer: Peer,
      discarded: Try[Seq[(BigInt, org.apache.pekko.util.ByteString)]]
  ) extends Command

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

    /** The common block found with the master peer's chain has no header here (B-347), so the branch cannot be weighed
      * from it, and is not followed, as a branch that cannot be shown the heavier never is (B-298). Nothing is
      * discarded, and the peer is blacklisted so that the next recovery does not ask it again for the same branch.
      *
      * Only a change of the index during the resolution leads here: while the common block is searched for, a height
      * this node holds no header at is taken for one that is not common, as core-geth's `findAncestor` takes a header
      * it does not hold (`known` false), so a hole in the index is searched past, never refused.
      */
    final case class HeaderNotStored(blockNumber: BigInt) extends BranchNotFollowed

  /** The master peer's chain leaves this node's at `commonBlock`, deeper than a fork recovery may rewind to, and its
    * branch above it was weighed and is the heavier (B-346): this node cannot follow the chain with what it holds.
    * Nothing was taken off this node's chain; the caller escalates to a SNAP re-sync instead of rewinding. The peer is
    * not blacklisted: its chain is the one to follow.
    *
    * @param commonHash
    *   the hash of the common block, by which the caller asks for the escalation once per fork
    * @param weighed
    *   how many of the peer's headers were weighed; 0 where the fork was escalated already and was not weighed again
    * @param firstOfBranch
    *   the branch's first block above the common block, as the peer served it (B-351): the SNAP re-sync is held to the
    *   branch by it, as the sync after an escalation on the unified layout is (B-307). None where it was not weighed
    *   again
    */
  final case class BranchHeavierBeyondReach(
      commonBlock: BigInt,
      commonHash: org.apache.pekko.util.ByteString,
      masterPeer: Peer,
      weighed: Int,
      firstOfBranch: Option[BlockHeader] = None
  ) extends BranchResolverResponse

  /** What the weighing of a branch whose common block lies below the floor says of it (B-346). */
  enum BeyondReachVerdict:
    /** Heavier than this node's chain, not refused by MESS, and every header valid: to be escalated. */
    case Heavier

    /** Not (yet) shown the heavier, by weight or by MESS: more of its headers may show it. */
    case NotHeavier

    /** Heavy enough, and a header of it is not valid: refused, whatever follows. */
    case Invalid(why: String)

  /** How a branch whose common block lies below the floor is weighed (B-346): from the common block, with headers only,
    * and never rewound to.
    *
    * @param maxHeaders
    *   the most of the peer's headers fetched to weigh it
    * @param verdict
    *   the weighing, of the headers from the child of the common block up
    * @param escalatedAt
    *   the hash of a common block a heavier branch was escalated for already: a branch from it is answered as heavier
    *   at once, with nothing fetched or weighed again, and the caller does not ask again
    */
  final case class BeyondReach(
      maxHeaders: Int,
      verdict: NonEmptyList[BlockHeader] => BeyondReachVerdict,
      escalatedAt: Option[org.apache.pekko.util.ByteString] = None
  )

  /** What the hash layout checks before it takes this node's blocks above the common block off the chain (B-298).
    * Without it the resolver only reports the common block (the unified layout, spec 018 C4e part 6).
    *
    * @param deepestCommonBlock
    *   the lowest common block a discard may go down to: a chain that leaves this node's below it is never rewound to
    * @param heavier
    *   whether a branch, given by its headers from the child of the common block up, is to be taken over this node's
    *   blocks above the common block: the fork choice the import applies, by its rule alone
    *   (`BranchResolution.discardsFor`: weight, a tie the rule itself breaks, and MESS; never a tie only a coin would
    *   decide, B-284). `None` where the import does not choose by weight (a chain that follows a consensus layer): the
    *   branch is then not weighed here, as before
    * @param beyondReach
    *   how a chain that leaves this node's below `deepestCommonBlock` is weighed (B-346): the search goes on below the
    *   floor to find the common block, and a heavier branch is answered with [[BranchHeavierBeyondReach]]. `None`: such
    *   a chain is refused unweighed ([[BranchResolutionFailed.CommonBlockBeyondReach]])
    * @param importLock
    *   regular sync's import lock, which the discard is made under (B-012): it moves the best block and the index down,
    *   and an import must never run beside it, nor resolve a batch against the head it is taking away. Taken off the
    *   resolver's thread ([[ImportLock.guardAs]] run by `pipeToSelf`): the import that started the recovery may still
    *   hold it, and nothing that holds it waits for this actor
    */
  final case class DiscardGuard(
      deepestCommonBlock: BigInt,
      heavier: Option[NonEmptyList[BlockHeader] => Boolean],
      importLock: ImportLock,
      beyondReach: Option[BeyondReach] = None
  )

  /** What the master peer's headers above a common block are fetched for. */
  sealed private trait Weighing

  /** Within the floor: discarded down to if the branch is the heavier (B-298), under `guard`'s import lock. */
  final private case class ToDiscard(guard: DiscardGuard, heavier: NonEmptyList[BlockHeader] => Boolean)
      extends Weighing

  /** Below the floor: escalated if the branch is the heavier, never discarded down to (B-346). */
  final private case class ToEscalate(beyondReach: BeyondReach, deepestCommonBlock: BigInt) extends Weighing

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

    // the runtime the discard under the import lock runs on (B-012), as BlockImporter's imports do
    private given runtime: IORuntime = IORuntime.global

    /** Whether this resolver weighs the master peer's branch before it takes anything off this node's chain: the hash
      * layout of a chain that chooses by work (B-298). Where it does, no failure of the resolution is left to the
      * caller's fallback, which rewinds unweighed (B-347): each is weighed, or refused.
      */
    private val weighs: Boolean = discardGuard.exists(_.heavier.isDefined)

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
      case HandlerTerminated(ref) =>
        context.self ! HandlerGone(ref)
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
            case HandlerGone(ref) if ref == requestHandler =>
              handlePeerTermination(masterPeer, ref)
            case _ => Behaviors.same
        }
      }

    // `belowFloor`: the search goes on below the floor, to weigh the peer's branch (B-346); the floor, if so
    private def waitingForBinarySearchBlock(
        searchState: SearchState,
        blockHeaderNumberToSearch: BigInt,
        requestHandler: TypedActorRef[PeerRequestHandler.Command],
        belowFloor: Option[BigInt]
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
                  handleBinarySearchBlockHeaderResponse(searchState, childHeader, belowFloor)
                case _ =>
                  log.warn(ReceivedWrongHeaders, blockHeaderNumberToSearch, headers.map(_.number.value))
                  handleInvalidResponse(peer, requestHandler)
            case PeerRequestResult(RequestFailed(_, peer, reason)) =>
              handleRequestFailure(peer, requestHandler, reason)
            case HandlerGone(ref) if ref == requestHandler =>
              handlePeerTermination(searchState.masterPeer, ref)
            case HandlerGone(_) => Behaviors.same // ignore stale death-watch
            case _              => Behaviors.same
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
              // to be common first (B-298); the search assumes its lower bound is. Where the branch is weighed, a height
              // this node holds no header at is not common (B-347; core-geth's `known` check), so the block shown is the
              // lowest at or above the floor that it holds: a hole in the index from the floor up cannot be common, and
              // the chains part either above it or below the floor
              val probe =
                if weighs then
                  Iterator
                    .iterate(deepest)(_ + 1)
                    .takeWhile(_ <= bestBlockNumber)
                    .flatMap(blockchainReader.getBlockHeaderByNumber)
                    .nextOption()
                else blockchainReader.getBlockHeaderByNumber(deepest)
              probe match
                case Some(probeHeader) =>
                  val handler = sendGetBlockHeadersRequest(masterPeer, childOf(probeHeader.number.value), 1)
                  waitingForDeepestCommonBlock(masterPeer, bestBlockNumber, deepest, probeHeader, handler)
                case None if weighs => belowTheFloor(masterPeer, deepest)
                case None           => stopWithFailure(BranchResolutionFailed.blockHeaderNotFound(deepest))
            case None =>
              requestBlockHeaderForBinarySearch(
                SearchState(minBlockNumber = 1, maxBlockNumber = bestBlockNumber, masterPeer)
              )

    /** The answer for the child of `probeHeader`, the deepest block a discard may reach (`floor`, B-298), or where this
      * node holds no header there, the lowest above it that it holds (B-347): that block is common, and the search goes
      * on above it, or the master peer's chain leaves this node's below it, and so below the floor, since no height in
      * between is one this node holds.
      */
    private def waitingForDeepestCommonBlock(
        masterPeer: Peer,
        bestBlockNumber: BigInt,
        floor: BigInt,
        probeHeader: BlockHeader,
        requestHandler: TypedActorRef[PeerRequestHandler.Command]
    ): Behavior[Command] =
      val probe = probeHeader.number.value
      Behaviors.receiveMessage { message =>
        handleCommon(message).getOrElse {
          message match
            case PeerRequestResult(ResponseReceived(_, peer, ETH68BlockHeaders(_, headers), _)) if peer == masterPeer =>
              context.unwatch(requestHandler)
              headers.toList match
                case child :: Nil if child.number.value == childOf(probe) =>
                  if !probeHeader.isParentOf(child) then belowTheFloor(masterPeer, floor)
                  else if probe >= bestBlockNumber then finalizeBranchResolver(probe, masterPeer)
                  else
                    requestBlockHeaderForBinarySearch(
                      SearchState(minBlockNumber = probe, maxBlockNumber = bestBlockNumber, masterPeer)
                    )
                case _ =>
                  log.warn(ReceivedWrongHeaders, childOf(probe), headers.map(_.number.value))
                  handleInvalidResponse(peer, requestHandler)
            case PeerRequestResult(RequestFailed(_, peer, reason)) =>
              handleRequestFailure(peer, requestHandler, reason)
            case HandlerGone(ref) if ref == requestHandler =>
              handlePeerTermination(masterPeer, ref)
            case _ => Behaviors.same
        }
      }

    /** The master peer's chain leaves this node's below `floor`. Where such a chain is weighed (B-346), the search goes
      * on below the floor to find where; whatever it finds there is weighed, never rewound to. Refused otherwise.
      */
    private def belowTheFloor(masterPeer: Peer, floor: BigInt): Behavior[Command] =
      if discardGuard.exists(_.beyondReach.isDefined) then
        log.info(
          "[SYNC-FORK] Peer {}'s chain leaves this node's below block {}: searching below it for the common block, to " +
            "weigh the peer's branch (nothing below it is rewound)",
          masterPeer.id,
          floor
        )
        requestBlockHeaderForBinarySearch(
          SearchState(minBlockNumber = 1, maxBlockNumber = parentOf(floor), masterPeer),
          belowFloor = Some(floor)
        )
      else refuseBeyondReach(masterPeer, floor)

    private def requestBlockHeaderForBinarySearch(
        searchState: SearchState,
        belowFloor: Option[BigInt] = None
    ): Behavior[Command] =
      val headerNumberToRequest = blockHeaderNumberToRequest(searchState.minBlockNumber, searchState.maxBlockNumber)
      val handler = sendGetBlockHeadersRequest(searchState.masterPeer, headerNumberToRequest, 1)
      waitingForBinarySearchBlock(searchState, headerNumberToRequest, handler, belowFloor)

    private def handleBinarySearchBlockHeaderResponse(
        searchState: SearchState,
        childHeader: BlockHeader,
        belowFloor: Option[BigInt]
    ): Behavior[Command] =
      import BinarySearchSupport.*
      // below the floor a search that ends without a common block is a refusal, as the floor's was before it went on
      // (B-346): never the caller's fallback, which rewinds
      def failed(failure: BranchResolutionFailed): Behavior[Command] =
        belowFloor.fold(stopWithFailure(failure))(refuseBeyondReach(searchState.masterPeer, _))
      val parentNumber = parentOf(childHeader.number.value)
      def next(result: BinarySearchResult): Behavior[Command] =
        result match
          // The search never asks below block 1, so the chains may still share the genesis (B-347). core-geth's
          // findAncestor takes the genesis for common there, unchecked; here, where the branch is weighed, it is
          // weighed from the genesis, as from any common block, and the peer's first header must name this node's
          // genesis as its parent, or the peer is blamed for its headers. Below the floor that weighing is the one
          // for an escalation
          case NoCommonBlock if weighs =>
            log.info(
              "[SYNC-FORK] Peer {}'s chain shares no block above the genesis with this node's: its branch is " +
                "weighed from the genesis",
              searchState.masterPeer.id
            )
            finalizeBranchResolver(0, searchState.masterPeer)
          case NoCommonBlock => failed(BranchResolutionFailed.noCommonBlock)
          case BinarySearchCompleted(highestCommonBlockNumber) =>
            finalizeBranchResolver(highestCommonBlockNumber, searchState.masterPeer)
          case ContinueBinarySearch(newSearchState) =>
            log.debug(s"Continuing binary search with new search state: $newSearchState")
            requestBlockHeaderForBinarySearch(newSearchState, belowFloor)
      blockchainReader.getBlockHeaderByNumber(parentNumber) match
        case Some(parentHeader) => next(validateBlockHeaders(parentHeader, childHeader, searchState))
        // B-347: a header this node does not hold is not common, and the search goes on below it, as core-geth's
        // findAncestorBinarySearch does (`if !known { end = check }`): a hole in this node's index no longer stops the
        // search, and the common block below it is weighed as any other
        case None if weighs =>
          log.info(
            "[SYNC-FORK] This node holds no header at block {}: taken as not common, the search for where peer {}'s " +
              "chain leaves this one goes on below it",
            parentNumber,
            searchState.masterPeer.id
          )
          next(notCommon(parentNumber, searchState))
        case None => failed(BranchResolutionFailed.blockHeaderNotFound(childHeader.number.value))

    private def finalizeBranchResolver(firstCommonBlockNumber: BigInt, masterPeer: Peer): Behavior[Command] =
      discardGuard match
        case None => reply(firstCommonBlockNumber, masterPeer, Nil)
        // every way here (the recent window, the search, a peer's answers that contradict each other) is held to the
        // floor: a common block below it is never discarded down to. It is weighed for an escalation where the guard
        // says so (B-346), and refused otherwise
        case Some(guard) if firstCommonBlockNumber < guard.deepestCommonBlock =>
          guard.beyondReach match
            case Some(beyondReach) => weighBeyondReach(firstCommonBlockNumber, masterPeer, guard, beyondReach)
            case None              => refuseBeyondReach(masterPeer, guard.deepestCommonBlock)
        case Some(guard) =>
          val best = blockchainReader.getBestBlockNumber
          guard.heavier match
            // nothing of this node's chain lies above the common block, or the branch is not weighed here: as before
            case Some(heavier) if firstCommonBlockNumber < best =>
              blockchainReader.getBlockHeaderByNumber(firstCommonBlockNumber) match
                case Some(commonHeader) =>
                  val wanted = (best - firstCommonBlockNumber) + 2 * recentHeadersSize
                  requestBranchHeaders(
                    masterPeer,
                    commonHeader,
                    Vector.empty,
                    wanted,
                    ToDiscard(guard, acrossAHole(guard, firstCommonBlockNumber, best).getOrElse(heavier))
                  )
                // nothing to weigh the branch from: refused, never the caller's unweighed rewind (B-347)
                case None => refuseHeaderNotStored(masterPeer, firstCommonBlockNumber)
            case _ => discardAndReply(firstCommonBlockNumber, masterPeer, guard)

    /** The weighing of the branch where this node's index has a hole between the common block and the head (B-347): the
      * import's fork choice (`heavier`) reads this node's blocks above the common block by number, and weighs no branch
      * across a block it cannot read (B-395), so a hole the search went past would leave every branch refused. There
      * the branch is weighed as one below the floor is (B-346, [[BeyondReach.verdict]]): by the same rule, without a
      * coin, and MESS, against this node's head by its stored weight, with no block between read; and its headers are
      * validated as an import validates them. None where there is no hole, or no such weighing is given.
      */
    private def acrossAHole(
        guard: DiscardGuard,
        common: BigInt,
        best: BigInt
    ): Option[NonEmptyList[BlockHeader] => Boolean] =
      guard.beyondReach
        .filter(_ =>
          Iterator
            .iterate(common + 1)(_ + 1)
            .takeWhile(_ <= best)
            .exists(blockchainReader.getBlockHeaderByNumber(_).isEmpty)
        )
        .map { beyondReach =>
          log.info(
            "[SYNC-FORK] This node's index has a hole between the common block {} and the head {}: the branch is weighed " +
              "against the head's stored weight",
            common,
            best
          )
          (branch: NonEmptyList[BlockHeader]) => beyondReach.verdict(branch) == BeyondReachVerdict.Heavier
        }

    /** A common block below the floor (B-346): the master peer's branch above it is weighed from it, with no more than
      * the guard's headers, to be escalated if it is the heavier. Nothing is discarded on any outcome. A fork escalated
      * already is answered at once.
      */
    private def weighBeyondReach(
        common: BigInt,
        masterPeer: Peer,
        guard: DiscardGuard,
        beyondReach: BeyondReach
    ): Behavior[Command] =
      blockchainReader.getBlockHeaderByNumber(common) match
        case None => refuseBeyondReach(masterPeer, guard.deepestCommonBlock)
        case Some(commonHeader) if beyondReach.escalatedAt.contains(commonHeader.hash.value) =>
          log.info(
            "[SYNC-FORK] Peer {}'s chain leaves this node's at block {}, a fork escalated already: not weighed again",
            masterPeer.id,
            common
          )
          replyTo ! BranchHeavierBeyondReach(common, commonHeader.hash.value, masterPeer, weighed = 0)
          Behaviors.stopped
        case Some(commonHeader) =>
          val wanted =
            ((blockchainReader.getBestBlockNumber - common) + 2 * recentHeadersSize).min(BigInt(beyondReach.maxHeaders))
          requestBranchHeaders(
            masterPeer,
            commonHeader,
            Vector.empty,
            wanted,
            ToEscalate(beyondReach, guard.deepestCommonBlock)
          )

    /** Removes this node's blocks above the common block, then answers with them. Under the import lock (B-012), which
      * is taken off this actor's thread: the answer waits for an import that holds it, the one that started this
      * recovery among them, and nothing that holds the lock waits for this actor. A discard that fails stops this
      * resolver without an answer, as a failure in it always has: the importer watches it, and falls back.
      */
    private def discardAndReply(
        firstCommonBlockNumber: BigInt,
        masterPeer: Peer,
        guard: DiscardGuard
    ): Behavior[Command] =
      context.pipeToSelf(
        guard.importLock
          .guardAs(s"the discard of the blocks above block $firstCommonBlockNumber")(
            IO.blocking(branchLogic.discardBlocksAfter(firstCommonBlockNumber))
          )
          .unsafeToFuture()
      )(Discarded(firstCommonBlockNumber, masterPeer, _))
      discarding()

    /** Waiting for the discard: nothing else is acted on, the peers' answers and the scan of peers included. */
    private def discarding(): Behavior[Command] =
      Behaviors.receiveMessage {
        case Discarded(firstCommonBlockNumber, masterPeer, Success(discarded)) =>
          reply(firstCommonBlockNumber, masterPeer, discarded)
        case Discarded(firstCommonBlockNumber, _, Failure(error)) =>
          log.error(s"[SYNC-FORK] The discard of the blocks above block $firstCommonBlockNumber failed", error)
          throw error
        case _ => Behaviors.same
      }

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
      * already the heavier stays so with the rest of it, MESS included, so the first verdict for it is final. Headers
      * only, below the floor too (B-346): no body is asked for.
      */
    private def requestBranchHeaders(
        masterPeer: Peer,
        commonHeader: BlockHeader,
        fetched: Vector[BlockHeader],
        wanted: BigInt,
        weighing: Weighing
    ): Behavior[Command] =
      val from = fetched.lastOption.getOrElse(commonHeader).number.value + 1
      val handler = sendGetBlockHeadersRequest(masterPeer, from, (wanted - fetched.size).min(BigInt(recentHeadersSize)))
      waitingForBranchHeaders(masterPeer, commonHeader, fetched, wanted, weighing, handler)

    private def waitingForBranchHeaders(
        masterPeer: Peer,
        commonHeader: BlockHeader,
        fetched: Vector[BlockHeader],
        wanted: BigInt,
        weighing: Weighing,
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
                // only an answer with none ends the branch: one with fewer than were asked for is a peer's limit
                def ended: Boolean = headers.isEmpty || all.size >= wanted
                def more: Behavior[Command] = requestBranchHeaders(masterPeer, commonHeader, all, wanted, weighing)
                (weighing, NonEmptyList.fromList(all.toList)) match
                  case (ToDiscard(guard, heavier), Some(branch)) if heavier(branch) =>
                    log.info(
                      "[SYNC-FORK] Peer {}'s branch above block {} is the heavier with {} header(s): this node's " +
                        "blocks above it are discarded",
                      masterPeer.id,
                      common,
                      all.size
                    )
                    discardAndReply(common, masterPeer, guard)
                  case (ToDiscard(_, _), _) => if ended then refuseNotHeavier(masterPeer, common, all.size) else more
                  case (ToEscalate(beyondReach, deepest), branch) =>
                    branch.map(beyondReach.verdict).getOrElse(BeyondReachVerdict.NotHeavier) match
                      case BeyondReachVerdict.Heavier =>
                        log.warn(
                          "[SYNC-FORK] Peer {}'s branch above block {}, below the floor {}, is the heavier with {} " +
                            "header(s): nothing is discarded, and it is handed on for a SNAP re-sync",
                          masterPeer.id,
                          common,
                          deepest,
                          all.size
                        )
                        // the branch's first block goes with it: the SNAP re-sync is held to it (B-351)
                        replyTo ! BranchHeavierBeyondReach(
                          common,
                          commonHeader.hash.value,
                          masterPeer,
                          all.size,
                          firstOfBranch = all.headOption
                        )
                        Behaviors.stopped
                      case BeyondReachVerdict.Invalid(why) =>
                        log.warn(
                          "[SYNC-FORK] Peer {}'s branch above block {} is not valid ({}): refused",
                          masterPeer.id,
                          common,
                          why
                        )
                        refuseBeyondReach(masterPeer, deepest)
                      case BeyondReachVerdict.NotHeavier =>
                        if ended then refuseBeyondReach(masterPeer, deepest) else more
            case PeerRequestResult(RequestFailed(_, peer, reason)) =>
              handleRequestFailure(peer, requestHandler, reason)
            case HandlerGone(ref) if ref == requestHandler =>
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

    /** The common block found has no header here any more (the search read it when it took it for common, so only a
      * change of the index since leads here): the branch cannot be weighed from it, and is refused (B-347).
      */
    private def refuseHeaderNotStored(masterPeer: Peer, blockNumber: BigInt): Behavior[Command] =
      log.error(
        "[SYNC-FORK] This node holds no header at block {} (best {}), the common block found with peer {}'s chain: " +
          "its branch cannot be weighed from it and is refused. Nothing is discarded and the peer is blacklisted",
        blockNumber,
        blockchainReader.getBestBlockNumber,
        masterPeer.id
      )
      peerListHelper.blacklistIfHandshaked(
        masterPeer.id,
        syncConfig.blacklistDuration,
        BlacklistReason.ForkNotFollowed(s"this node holds no header at block $blockNumber to weigh its chain from")
      )
      stopWithFailure(BranchResolutionFailed(HeaderNotStored(blockNumber)))

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
