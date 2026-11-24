package com.chipprbots.ethereum.blockchain.sync.regular

import org.apache.pekko.actor.typed.ActorRef as TypedActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.Behaviors

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import scala.concurrent.duration.FiniteDuration
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import com.chipprbots.ethereum.blockchain.sync.PeerRequestHandler
import com.chipprbots.ethereum.blockchain.sync.PeerRequestHandler.RequestFailed
import com.chipprbots.ethereum.blockchain.sync.PeerRequestHandler.ResponseReceived
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerEventBusActor.Command as PeerEventBusCommand
import com.chipprbots.ethereum.network.p2p.messages.Codes
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.BlockHeaders
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.GetBlockHeaders

/** The headers of a branch that regular sync refused before fetching it, asked of one peer, for the escalation (B-300
  * g): from the block above the common block, one request after another, until the headers fetched outweigh the head,
  * `wanted` are fetched, or the peer answers with none. Then `tell` is run with them, once, and this stops.
  *
  * The branch's headers are the peer's the common block was found against, and no other's: another peer may be on
  * another branch. Nothing is told when a request fails, times out, or ends without an answer, nor when an answer is
  * not the next headers of the branch (the safe direction: a branch not told is not escalated). An answer with fewer
  * headers than were asked for is no end of the branch: a peer serves fewer than it is asked for as it will (forge),
  * and the next are asked for. The feed counts what is told only if it outweighs the head.
  *
  * Each request is a [[PeerRequestHandler]] child, watched: one that stops without an answer ends this too (#1705).
  */
object BranchHeadersFetch:

  sealed trait Command
  final private case class Answer(result: PeerRequestHandler.Result) extends Command
  final private case class RequestStopped(handler: TypedActorRef[PeerRequestHandler.Command]) extends Command

  /** [[RequestStopped]], re-posted to self so it is read after the answer the handler sent before it stopped: a
    * death-watch notification is processed ahead of the mailbox, so the stop of a handler that answered could be read
    * first, and the answer taken for none (B-350, as in FastSyncBranchResolverActor).
    */
  final private case class RequestGone(handler: TypedActorRef[PeerRequestHandler.Command]) extends Command
  final private case class Told(result: Try[Unit]) extends Command

  /** @param commonBlock
    *   the block the branch leaves this node's chain at: the first header asked for is its child
    * @param wanted
    *   how many headers to fetch at most
    * @param perRequest
    *   how many headers one request asks for at most
    * @param outweighsTheHead
    *   whether the headers fetched, in the order of the branch, outweigh the head: enough are fetched
    * @param tell
    *   what is done with the headers fetched, in the order of the branch
    */
  def apply(
      peer: Peer,
      commonBlock: BlockHeader,
      wanted: BigInt,
      perRequest: Int,
      responseTimeout: FiniteDuration,
      networkPeerManager: TypedActorRef[NetworkPeerManagerActor.Command],
      peerEventBus: TypedActorRef[PeerEventBusCommand],
      outweighsTheHead: List[BlockHeader] => Boolean,
      tell: List[BlockHeader] => IO[Unit]
  ): Behavior[Command] =
    Behaviors.setup { ctx =>
      new BranchHeadersFetch(
        ctx,
        peer,
        commonBlock,
        wanted,
        perRequest,
        responseTimeout,
        networkPeerManager,
        peerEventBus,
        outweighsTheHead,
        tell
      )
        .start()
    }

  /** Whether `headers` are the next blocks of a branch whose highest block so far is `below`: each the child of the one
    * before it.
    */
  private[regular] def continues(below: BlockHeader, headers: Seq[BlockHeader]): Boolean =
    headers
      .foldLeft(Option(below)) { (parent, header) =>
        parent.filter(p => header.parentHash == p.hash && header.number.value == p.number.value + 1).map(_ => header)
      }
      .isDefined

final private class BranchHeadersFetch(
    ctx: ActorContext[BranchHeadersFetch.Command],
    peer: Peer,
    commonBlock: BlockHeader,
    wanted: BigInt,
    perRequest: Int,
    responseTimeout: FiniteDuration,
    networkPeerManager: TypedActorRef[NetworkPeerManagerActor.Command],
    peerEventBus: TypedActorRef[PeerEventBusCommand],
    outweighsTheHead: List[BlockHeader] => Boolean,
    tell: List[BlockHeader] => IO[Unit]
):
  import BranchHeadersFetch.*

  private given IORuntime = IORuntime.global

  private val answers: TypedActorRef[PeerRequestHandler.Result] =
    ctx.messageAdapter[PeerRequestHandler.Result](Answer(_))

  private def log = ctx.log

  private def name(fetched: Vector[BlockHeader]): String =
    s"[${commonBlock.number.value + 1}-${fetched.lastOption.fold(commonBlock.number.value)(_.number.value)}]"

  def start(): Behavior[Command] = request(Vector.empty)

  private def request(fetched: Vector[BlockHeader]): Behavior[Command] =
    val from = fetched.lastOption.getOrElse(commonBlock).number.value + 1
    val asked = (wanted - fetched.size).min(BigInt(perRequest))
    val handler = ctx.spawnAnonymous(
      PeerRequestHandler.behavior[GetBlockHeaders, BlockHeaders](
        peer,
        responseTimeout,
        networkPeerManager,
        peerEventBus,
        requestMsg = GetBlockHeaders(ETHPackets.nextRequestId, Left(from), asked, skip = 0, reverse = false),
        responseMsgCode = Codes.BlockHeadersCode,
        replyTo = answers,
        requestId = 0
      )
    )
    ctx.watchWith(handler, RequestStopped(handler))
    fetching(fetched, handler)

  private def fetching(
      fetched: Vector[BlockHeader],
      handler: TypedActorRef[PeerRequestHandler.Command]
  ): Behavior[Command] =
    Behaviors.receiveMessage {
      case Answer(ResponseReceived(_, _, BlockHeaders(_, headers), _)) =>
        ctx.unwatch(handler)
        if !continues(fetched.lastOption.getOrElse(commonBlock), headers) then
          log.warn(
            "[STATE-ESCALATION] Peer {} answered for the branch above block {} with headers that are not its next " +
              "blocks: nothing is escalated",
            peer.id,
            commonBlock.number.value
          )
          Behaviors.stopped
        else
          val all = fetched ++ headers
          // only an answer with none ends the branch: one with fewer than were asked for is a peer's limit (forge)
          if headers.isEmpty || all.size >= wanted || outweighsTheHead(all.toList) then told(all)
          else request(all)
      case Answer(RequestFailed(_, _, reason)) =>
        ctx.unwatch(handler)
        log.warn(
          "[STATE-ESCALATION] The headers of the branch {} could not be fetched from peer {} ({}): nothing is escalated",
          name(fetched),
          peer.id,
          reason
        )
        Behaviors.stopped
      case RequestStopped(stopped) =>
        ctx.self ! RequestGone(stopped)
        Behaviors.same
      case RequestGone(stopped) if stopped == handler =>
        log.warn(
          "[STATE-ESCALATION] The request for the headers of the branch {} to peer {} stopped without an answer: " +
            "nothing is escalated",
          name(fetched),
          peer.id
        )
        Behaviors.stopped
      case _ => Behaviors.same
    }

  private def told(headers: Vector[BlockHeader]): Behavior[Command] =
    if headers.isEmpty then
      log.warn(
        "[STATE-ESCALATION] Peer {} has no block above block {}: nothing is escalated",
        peer.id,
        commonBlock.number.value
      )
      Behaviors.stopped
    else
      log.info(
        "[STATE-ESCALATION] {} header(s) of the branch {} fetched from peer {}: told to the escalation",
        headers.size,
        name(headers),
        peer.id
      )
      ctx.pipeToSelf(tell(headers.toList).unsafeToFuture())(Told(_))
      Behaviors.receiveMessage {
        case Told(Success(())) => Behaviors.stopped
        case Told(Failure(error)) =>
          log.error(s"[STATE-ESCALATION] The branch ${name(headers)} could not be told to the escalation", error)
          Behaviors.stopped
        case _ => Behaviors.same
      }
