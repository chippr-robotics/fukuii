package com.chipprbots.ethereum.blockchain.sync.regular
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.AbstractBehavior
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.{ActorRef => ClassicActorRef}
import org.apache.pekko.util.ByteString

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import scala.util.Failure
import scala.util.Success

import org.slf4j.Logger

import com.chipprbots.ethereum.blockchain.sync.PeersClient.BestPeer
import com.chipprbots.ethereum.blockchain.sync.PeersClient.Request
import com.chipprbots.ethereum.blockchain.sync.regular.BlockFetcher.FetchCommand
import com.chipprbots.ethereum.blockchain.sync.regular.HeadersFetcher.HeadersFetcherCommand
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.p2p.Message
import com.chipprbots.ethereum.network.p2p.messages.ETH62.BlockHeaders
import com.chipprbots.ethereum.network.p2p.messages.ETH62.GetBlockHeaders
import com.chipprbots.ethereum.utils.Config.SyncConfig

class HeadersFetcher(
    val peersClient: ClassicActorRef,
    val syncConfig: SyncConfig,
    val supervisor: ActorRef[FetchCommand],
    context: ActorContext[HeadersFetcher.HeadersFetcherCommand]
) extends AbstractBehavior[HeadersFetcher.HeadersFetcherCommand](context)
    with FetchRequest[HeadersFetcherCommand] {

  val log: Logger = context.log
  implicit val runtime: IORuntime = IORuntime.global

  import HeadersFetcher._

  override def makeAdaptedMessage[T <: Message](peer: Peer, msg: T): HeadersFetcherCommand = AdaptedMessage(peer, msg)

  override def onMessage(message: HeadersFetcherCommand): Behavior[HeadersFetcherCommand] =
    message match {
      case FetchHeadersByNumber(block: BigInt, amount: BigInt) =>
        log.debug("Start fetching headers from block {}", block)
        requestHeaders(Left(block), amount)
        Behaviors.same
      case FetchHeadersByHash(block: ByteString, amount: BigInt) =>
        log.debug("Start fetching headers from block {}", block)
        requestHeaders(Right(block), amount)
        Behaviors.same
      case AdaptedMessage(peer, BlockHeaders(headers)) =>
        log.debug("Fetched {} headers starting from block {}", headers.size, headers.headOption.map(_.number))
        supervisor ! BlockFetcher.ReceivedHeaders(peer, headers)
        Behaviors.same
      case HeadersFetcher.RetryHeadersRequest =>
        supervisor ! BlockFetcher.RetryHeadersRequest
        Behaviors.same
      case _ => Behaviors.unhandled
    }

  private def requestHeaders(block: Either[BigInt, ByteString], amount: BigInt): Unit = {
    log.debug("Fetching headers from block {}", block)
    val msg = GetBlockHeaders(block, amount, skip = 0, reverse = false)

    val resp = makeRequest(Request.create(msg, BestPeer), HeadersFetcher.RetryHeadersRequest)
      .flatMap {
        case AdaptedMessage(_, BlockHeaders(headers)) if headers.isEmpty =>
          log.debug("Empty BlockHeaders response. Retry in {}", syncConfig.syncRetryInterval)
          IO.pure(HeadersFetcher.RetryHeadersRequest).delayBy(syncConfig.syncRetryInterval)
        case res => IO.pure(res)
      }

    context.pipeToSelf(resp.unsafeToFuture()) {
      case Success(res) => res
      case Failure(_)   => HeadersFetcher.RetryHeadersRequest
    }
  }
}

object HeadersFetcher {

  def apply(
      peersClient: ClassicActorRef,
      syncConfig: SyncConfig,
      supervisor: ActorRef[FetchCommand]
  ): Behavior[HeadersFetcherCommand] =
    Behaviors.setup(context => new HeadersFetcher(peersClient, syncConfig, supervisor, context))

  sealed trait HeadersFetcherCommand
  final case class FetchHeadersByNumber(block: BigInt, amount: BigInt) extends HeadersFetcherCommand
  final case class FetchHeadersByHash(block: ByteString, amount: BigInt) extends HeadersFetcherCommand
  case object RetryHeadersRequest extends HeadersFetcherCommand
  final private case class AdaptedMessage[T <: Message](peer: Peer, msg: T) extends HeadersFetcherCommand
}
