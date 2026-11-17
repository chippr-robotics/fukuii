package com.chipprbots.ethereum.blockchain.sync.regular

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.Scheduler
import org.apache.pekko.actor.typed.scaladsl.AbstractBehavior
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.Behaviors

import cats.effect.unsafe.IORuntime

import scala.annotation.tailrec
import scala.util.Failure
import scala.util.Success

import com.chipprbots.ethereum.blockchain.sync.Blacklist.BlacklistReason
import com.chipprbots.ethereum.blockchain.sync.PeersClient
import com.chipprbots.ethereum.blockchain.sync.PeersClient.BestPeer
import com.chipprbots.ethereum.blockchain.sync.PeersClient.BlacklistPeer
import com.chipprbots.ethereum.blockchain.sync.PeersClient.ExcludingPeers
import com.chipprbots.ethereum.blockchain.sync.PeersClient.Request
import com.chipprbots.ethereum.blockchain.sync.StoredBodyCheck
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.p2p.Message
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets
import com.chipprbots.ethereum.utils.Config.SyncConfig

/** Fetches the bodies of blocks of this node's own chain whose headers are stored and whose bodies are not: the window
  * of ancestors the ommers of a block are checked against, when it is short after a sync that began from a pivot
  * (B-301). One per fetch: spawned by [[BlockFetcher]] for a [[BlockFetcher.FetchAncestorBodies]], it asks peers for
  * the bodies still wanted, at most [[AncestorBodiesFetcher.MaxRequests]] times and each time of a peer that has not
  * answered yet, then tells its parent what it found and waits to be stopped.
  *
  * A body is kept only if it is the one its header commits to ([[StoredBodyCheck.mismatch]]: transactions root, ommers
  * hash, withdrawals root and the rest of `validateHeaderAndBody`). A peer whose answer holds a body that is none of
  * those asked for is blacklisted; one that leaves bodies out is not, as it may not have them.
  */
class AncestorBodiesFetcher(
    val peersClient: ActorRef[PeersClient.Command],
    val syncConfig: SyncConfig,
    requested: Seq[BlockHeader],
    parent: ActorRef[BlockFetcher.FetchCommand],
    context: ActorContext[AncestorBodiesFetcher.Command]
) extends AbstractBehavior[AncestorBodiesFetcher.Command](context)
    with FetchRequest[AncestorBodiesFetcher.Command]:

  import AncestorBodiesFetcher.*

  val log = context.log
  given scheduler: Scheduler = context.system.scheduler
  given runtime: IORuntime = IORuntime.global

  private var wanted: Seq[BlockHeader] = requested
  private var found: Vector[(BlockHeader, BlockBody)] = Vector.empty
  private var requests: Int = 0
  private var answered: Set[PeerId] = Set.empty
  private var reported: Boolean = false

  override def makeAdaptedMessage[T <: Message](peer: Peer, msg: T): Command = AdaptedMessage(peer, msg)

  askNext()

  override def onMessage(message: Command): Behavior[Command] =
    message match
      case _ if reported =>
        Behaviors.same

      case AdaptedMessage(peer, ETHPackets.BlockBodies(_, bodies)) =>
        val askedFor = wanted.size
        val (matched, unmatched) = matchBodies(wanted, bodies)
        found = found ++ matched
        wanted = wanted.filterNot(header => matched.exists(_._1.hash == header.hash))
        answered += peer.id
        unmatched.foreach { _ =>
          log.warn(
            "[ANCESTOR-BODIES] peer {} answered with a body that is none of the {} asked for: blacklisting it",
            peer.id,
            askedFor
          )
          peersClient ! BlacklistPeer(peer.id, BlacklistReason.BlockBodiesNotMatchingHeaders)
        }
        log.debug(
          "[ANCESTOR-BODIES] peer {} served {} of the bodies, {} still wanted",
          peer.id,
          matched.size,
          wanted.size
        )
        askNext()
        Behaviors.same

      case AdaptedMessage(peer, other) =>
        log.warn("[ANCESTOR-BODIES] peer {} answered with {}, not block bodies", peer.id, other.getClass.getSimpleName)
        answered += peer.id
        askNext()
        Behaviors.same

      case RequestNotAnswered =>
        askNext()
        Behaviors.same

  /** The next request, or, when nothing is wanted or the requests are spent, the report to the parent. */
  private def askNext(): Unit =
    if wanted.isEmpty || requests >= MaxRequests then
      reported = true
      parent ! BlockFetcher.AncestorBodiesFetched(context.self, requested, found)
    else
      val msg = ETHPackets.GetBlockBodies(ETHPackets.nextRequestId, wanted.map(_.hash.value))
      val selector = if answered.isEmpty then BestPeer else ExcludingPeers(answered)
      val attempt = requests
      requests += 1
      val response = makeRequest(Request.create(msg, selector), RequestNotAnswered, answered, attempt)
      context.pipeToSelf(response.unsafeToFuture()) {
        case Success(res) => res
        case Failure(ex) =>
          log.warn("[ANCESTOR-BODIES] request failed: {}", ex.getMessage)
          RequestNotAnswered
      }

object AncestorBodiesFetcher:

  /** How many requests one fetch makes, across peers, before it reports what it has. */
  val MaxRequests: Int = 3

  def apply(
      peersClient: ActorRef[PeersClient.Command],
      syncConfig: SyncConfig,
      requested: Seq[BlockHeader],
      parent: ActorRef[BlockFetcher.FetchCommand]
  ): Behavior[Command] =
    Behaviors.setup(ctx => new AncestorBodiesFetcher(peersClient, syncConfig, requested, parent, ctx))

  /** The bodies of `bodies` that belong to the headers of `wanted`, in order, with the header each belongs to; and the
    * first body that belongs to none of the headers left when it was reached, after which nothing more is matched.
    *
    * Matched as an ordered subsequence, as peers answer: a peer leaves out a body it does not have and keeps the order
    * of the rest. A body belongs to a header only when it is the body that header commits to
    * ([[StoredBodyCheck.mismatch]] finds nothing).
    */
  def matchBodies(
      wanted: Seq[BlockHeader],
      bodies: Seq[BlockBody]
  ): (Vector[(BlockHeader, BlockBody)], Option[BlockBody]) =
    @tailrec
    def go(
        headers: List[BlockHeader],
        left: List[BlockBody],
        matched: Vector[(BlockHeader, BlockBody)]
    ): (Vector[(BlockHeader, BlockBody)], Option[BlockBody]) =
      (headers, left) match
        case (_, Nil)         => (matched, None)
        case (Nil, body :: _) => (matched, Some(body))
        case (header :: restHeaders, body :: restBodies) =>
          if StoredBodyCheck.mismatch(header, body).isEmpty then
            go(restHeaders, restBodies, matched :+ (header -> body))
          else go(restHeaders, left, matched)
    go(wanted.toList, bodies.toList, Vector.empty)

  sealed trait Command
  final private[regular] case class AdaptedMessage[T <: Message](peer: Peer, msg: T) extends Command
  // No peer answered the request: none was suitable, or the one asked failed (and was blacklisted for it).
  private[regular] case object RequestNotAnswered extends Command
