package com.chipprbots.ethereum.blockchain.sync.regular

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.AbstractBehavior
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.{ActorRef => ClassicActorRef}
import org.apache.pekko.util.ByteString

import cats.effect.unsafe.IORuntime

import scala.util.Failure
import scala.util.Success

import com.chipprbots.ethereum.blockchain.sync.PeersClient.BestPeer
import com.chipprbots.ethereum.blockchain.sync.PeersClient.Request
import com.chipprbots.ethereum.blockchain.sync.regular.BodiesFetcher.BodiesFetcherCommand
import com.chipprbots.ethereum.blockchain.sync.regular.BlockFetcher.FetchCommand
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.p2p.Message
import com.chipprbots.ethereum.network.p2p.messages.ETH62.BlockBodies
import com.chipprbots.ethereum.network.p2p.messages.ETH62.GetBlockBodies
import com.chipprbots.ethereum.utils.Config.SyncConfig

class BodiesFetcher(
    val peersClient: ClassicActorRef,
    val syncConfig: SyncConfig,
    val supervisor: ActorRef[FetchCommand],
    context: ActorContext[BodiesFetcher.BodiesFetcherCommand]
) extends AbstractBehavior[BodiesFetcher.BodiesFetcherCommand](context)
    with FetchRequest[BodiesFetcherCommand] {

  val log = context.log
  implicit val runtime: IORuntime = IORuntime.global

  import BodiesFetcher._

  override def makeAdaptedMessage[T <: Message](peer: Peer, msg: T): BodiesFetcherCommand = AdaptedMessage(peer, msg)

  override def onMessage(message: BodiesFetcherCommand): Behavior[BodiesFetcherCommand] =
    message match {
      case FetchBodies(hashes) =>
        log.debug("Start fetching bodies for {} hashes", hashes.size)
        if (hashes.isEmpty) {
          log.warn("FetchBodies called with empty hashes list")
        }
        requestBodies(hashes)
        Behaviors.same
      case AdaptedMessage(peer, BlockBodies(bodies)) =>
        log.debug("Received {} block bodies from peer {}", bodies.size, peer.id)
        if (bodies.isEmpty) {
          log.debug("Received empty bodies response from peer {}", peer.id)
        }
        // Always forward bodies to supervisor to ensure state is cleared
        supervisor ! BlockFetcher.ReceivedBodies(peer, bodies)
        Behaviors.same
      case BodiesFetcher.RetryBodiesRequest =>
        log.debug("Retrying bodies request")
        // Always forward retry to supervisor to ensure state is cleared
        supervisor ! BlockFetcher.RetryBodiesRequest
        Behaviors.same
      case other =>
        log.warn("BodiesFetcher received unhandled message of type: {}", other.getClass.getSimpleName)
        Behaviors.unhandled
    }

  private def requestBodies(hashes: Seq[ByteString]): Unit = {
    log.debug("Requesting {} block bodies", hashes.size)
    val resp = makeRequest(Request.create(GetBlockBodies(hashes), BestPeer), BodiesFetcher.RetryBodiesRequest)
    context.pipeToSelf(resp.unsafeToFuture()) {
      case Success(res: BodiesFetcher.RetryBodiesRequest.type) =>
        log.debug("Bodies request will be retried")
        res
      case Success(res) =>
        log.debug("Bodies request completed successfully")
        res
      case Failure(ex) =>
        log.warn("Bodies request failed with exception: {}", ex.getMessage)
        BodiesFetcher.RetryBodiesRequest
    }
  }
}

object BodiesFetcher {

  def apply(
      peersClient: ClassicActorRef,
      syncConfig: SyncConfig,
      supervisor: ActorRef[FetchCommand]
  ): Behavior[BodiesFetcherCommand] =
    Behaviors.setup(context => new BodiesFetcher(peersClient, syncConfig, supervisor, context))

  sealed trait BodiesFetcherCommand
  final case class FetchBodies(hashes: Seq[ByteString]) extends BodiesFetcherCommand
  case object RetryBodiesRequest extends BodiesFetcherCommand
  final private case class AdaptedMessage[T <: Message](peer: Peer, msg: T) extends BodiesFetcherCommand
}
