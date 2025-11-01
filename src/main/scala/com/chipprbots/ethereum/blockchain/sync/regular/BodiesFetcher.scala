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
import com.chipprbots.ethereum.blockchain.sync.regular.BlockFetcher.FetchCommand
import com.chipprbots.ethereum.blockchain.sync.regular.BodiesFetcher.BodiesFetcherCommand
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
  implicit val ec: IORuntime = IORuntime.global

  import BodiesFetcher._

  override def makeAdaptedMessage[T <: Message](peer: Peer, msg: T): BodiesFetcherCommand = AdaptedMessage(peer, msg)

  override def onMessage(message: BodiesFetcherCommand): Behavior[BodiesFetcherCommand] =
    message match {
      case FetchBodies(hashes) =>
        log.debug("Start fetching bodies")
        requestBodies(hashes)
        Behaviors.same
      case AdaptedMessage(peer, BlockBodies(bodies)) =>
        log.debug(s"Received ${bodies.size} block bodies")
        supervisor ! BlockFetcher.ReceivedBodies(peer, bodies)
        Behaviors.same
      case BodiesFetcher.RetryBodiesRequest =>
        supervisor ! BlockFetcher.RetryBodiesRequest
        Behaviors.same
      case _ => Behaviors.unhandled
    }

  private def requestBodies(hashes: Seq[ByteString]): Unit = {
    val resp = makeRequest(Request.create(GetBlockBodies(hashes), BestPeer), BodiesFetcher.RetryBodiesRequest)
    context.pipeToSelf(resp.unsafeToFuture()) {
      case Success(res) => res
      case Failure(_)   => BodiesFetcher.RetryBodiesRequest
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
