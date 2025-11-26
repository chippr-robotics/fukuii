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
import com.chipprbots.ethereum.blockchain.sync.regular.HeadersFetcher.HeadersFetcherCommand
import com.chipprbots.ethereum.blockchain.sync.regular.BlockFetcher.FetchCommand

import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.p2p.Message
import com.chipprbots.ethereum.network.p2p.messages.ETH62.{BlockHeaders => ETH62BlockHeaders}
import com.chipprbots.ethereum.network.p2p.messages.ETH66
import com.chipprbots.ethereum.network.p2p.messages.ETH66.{BlockHeaders => ETH66BlockHeaders}
import com.chipprbots.ethereum.network.p2p.messages.ETH66.{GetBlockHeaders => ETH66GetBlockHeaders}
import com.chipprbots.ethereum.utils.ByteStringUtils
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
        log.debug("Start fetching headers from block {} (amount: {})", block, amount)
        requestHeaders(Left(block), amount)
        Behaviors.same
      case FetchHeadersByHash(block: ByteString, amount: BigInt) =>
        log.debug("Start fetching headers from block hash {} (amount: {})", block, amount)
        requestHeaders(Right(block), amount)
        Behaviors.same
      case AdaptedMessage(peer, ETH62BlockHeaders(headers)) =>
        log.debug(
          "Fetched {} headers starting from block {} (peer: {})",
          headers.size,
          headers.headOption.map(_.number),
          peer.id
        )
        if (headers.isEmpty) {
          log.debug("Received empty headers response from peer {}", peer.id)
        } else {
          log.debug("Headers range: {} to {}", headers.headOption.map(_.number), headers.lastOption.map(_.number))
        }
        supervisor ! BlockFetcher.ReceivedHeaders(peer, headers)
        Behaviors.same
      case AdaptedMessage(peer, ETH66BlockHeaders(_, headers)) =>
        log.debug(
          "Fetched {} headers starting from block {} (peer: {})",
          headers.size,
          headers.headOption.map(_.number),
          peer.id
        )
        if (headers.isEmpty) {
          log.debug("Received empty headers response from peer {}", peer.id)
        } else {
          log.debug("Headers range: {} to {}", headers.headOption.map(_.number), headers.lastOption.map(_.number))
        }
        supervisor ! BlockFetcher.ReceivedHeaders(peer, headers)
        Behaviors.same
      case HeadersFetcher.RetryHeadersRequest =>
        log.debug("Retrying headers request")
        supervisor ! BlockFetcher.RetryHeadersRequest
        Behaviors.same
      case _ =>
        log.debug("HeadersFetcher received unhandled message")
        Behaviors.unhandled
    }

  private def requestHeaders(block: Either[BigInt, ByteString], amount: BigInt): Unit = {
    val blockDesc = block.fold(num => s"number $num", hash => s"hash ${ByteStringUtils.hash2string(hash)}")
    log.debug("Requesting headers from block {} (amount: {})", blockDesc, amount)
    val msg = ETH66GetBlockHeaders(ETH66.nextRequestId, block, amount, skip = 0, reverse = false)

    val resp = makeRequest(Request.create(msg, BestPeer), HeadersFetcher.RetryHeadersRequest)
      .flatMap {
        case AdaptedMessage(_, ETH62BlockHeaders(headers)) if headers.isEmpty =>
          log.debug("Empty BlockHeaders response. Retry in {}", syncConfig.syncRetryInterval)
          IO.pure(HeadersFetcher.RetryHeadersRequest).delayBy(syncConfig.syncRetryInterval)
        case AdaptedMessage(_, ETH66BlockHeaders(_, headers)) if headers.isEmpty =>
          log.debug("Empty BlockHeaders response. Retry in {}", syncConfig.syncRetryInterval)
          IO.pure(HeadersFetcher.RetryHeadersRequest).delayBy(syncConfig.syncRetryInterval)
        case res =>
          log.debug("Received non-empty headers response")
          IO.pure(res)
      }

    context.pipeToSelf(resp.unsafeToFuture()) {
      case Success(res: HeadersFetcher.RetryHeadersRequest.type) =>
        log.debug("Headers request will be retried")
        res
      case Success(res) =>
        log.debug("Headers request completed successfully")
        res
      case Failure(ex) =>
        log.debug("Headers request failed with exception: {}", ex.getMessage)
        HeadersFetcher.RetryHeadersRequest
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
