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
import com.chipprbots.ethereum.blockchain.sync.PeersClient.ExcludingPeers
import com.chipprbots.ethereum.blockchain.sync.PeersClient.Request
import com.chipprbots.ethereum.blockchain.sync.regular.BlockFetcher.FetchCommand
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.p2p.Message
import com.chipprbots.ethereum.network.p2p.messages.ETH62.{BlockBodies => Eth62BlockBodies}
import com.chipprbots.ethereum.network.p2p.messages.ETH66
import com.chipprbots.ethereum.network.p2p.messages.ETH66.{
  BlockBodies => Eth66BlockBodies,
  GetBlockBodies => Eth66GetBlockBodies
}
import com.chipprbots.ethereum.utils.Config.SyncConfig

class BodiesFetcher(
    val peersClient: ClassicActorRef,
    val syncConfig: SyncConfig,
    val supervisor: ActorRef[FetchCommand],
    context: ActorContext[BodiesFetcher.BodiesFetcherCommand]
) extends AbstractBehavior[BodiesFetcher.BodiesFetcherCommand](context)
    with FetchRequest[BodiesFetcher.BodiesFetcherCommand] {

  val log = context.log
  implicit val runtime: IORuntime = IORuntime.global

  import BodiesFetcher._
  private type Command = BodiesFetcher.BodiesFetcherCommand

  override def makeAdaptedMessage[T <: Message](peer: Peer, msg: T): Command = AdaptedMessage(peer, msg)

  override def onMessage(message: Command): Behavior[Command] =
    message match {
      case FetchBodies(hashes, triedPeers, retryCount) =>
        log.debug(
          "Start fetching bodies for {} hashes (tried: {}, retry: {})",
          hashes.size,
          triedPeers.size,
          retryCount
        )
        if (hashes.isEmpty) {
          log.warn("FetchBodies called with empty hashes list")
        }
        requestBodies(hashes, triedPeers, retryCount)
        Behaviors.same
      case AdaptedMessage(peer, eth62Bodies: Eth62BlockBodies) =>
        handleBodiesResponse(peer, eth62Bodies.bodies, protocolLabel = "ETH62")
      case AdaptedMessage(peer, eth66Bodies: Eth66BlockBodies) =>
        handleBodiesResponse(peer, eth66Bodies.bodies, protocolLabel = "ETH66")
      case BodiesFetcher.RetryBodiesRequest(failedPeerId, triedPeers, retryCount) =>
        log.debug("Retrying bodies request (tried: {}, retry: {})", triedPeers.size, retryCount)
        supervisor ! BlockFetcher.RetryBodiesRequest(failedPeerId, triedPeers, retryCount)
        Behaviors.same
      case other =>
        log.warn("BodiesFetcher received unhandled message of type: {}", other.getClass.getSimpleName)
        Behaviors.unhandled
    }

  private def handleBodiesResponse(
      peer: Peer,
      bodies: Seq[BlockBody],
      protocolLabel: String
  ): Behavior[Command] = {
    log.debug("Received {} block bodies from peer {} via {}", bodies.size, peer.id, protocolLabel)
    if (bodies.isEmpty) {
      log.debug("Received empty bodies response from peer {}", peer.id)
    }
    // Always forward bodies to supervisor to ensure state is cleared
    supervisor ! BlockFetcher.ReceivedBodies(peer, bodies)
    Behaviors.same
  }

  private def requestBodies(hashes: Seq[ByteString], triedPeers: Set[PeerId], retryCount: Int): Unit = {
    log.debug("Requesting {} block bodies (excluding {} tried peers)", hashes.size, triedPeers.size)
    val msg = Eth66GetBlockBodies(ETH66.nextRequestId, hashes)
    val peerSelector = if (triedPeers.nonEmpty) ExcludingPeers(triedPeers) else BestPeer
    val fallback = BodiesFetcher.RetryBodiesRequest(failedPeerId = None, triedPeers, retryCount)
    val resp = makeRequest(Request.create(msg, peerSelector), fallback, triedPeers, retryCount)
    context.pipeToSelf(resp.unsafeToFuture()) {
      case Success(res: BodiesFetcher.RetryBodiesRequest) =>
        log.debug("Bodies request will be retried")
        res
      case Success(res) =>
        log.debug("Bodies request completed successfully")
        res
      case Failure(ex) =>
        log.warn("Bodies request failed with exception: {}", ex.getMessage)
        BodiesFetcher.RetryBodiesRequest(failedPeerId = None, triedPeers, retryCount)
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
  final case class FetchBodies(
      hashes: Seq[ByteString],
      triedPeers: Set[PeerId] = Set.empty,
      retryCount: Int = 0
  ) extends BodiesFetcherCommand
  final case class RetryBodiesRequest(
      failedPeerId: Option[PeerId],
      triedPeers: Set[PeerId],
      retryCount: Int
  ) extends BodiesFetcherCommand
  final private case class AdaptedMessage[T <: Message](peer: Peer, msg: T) extends BodiesFetcherCommand
}
