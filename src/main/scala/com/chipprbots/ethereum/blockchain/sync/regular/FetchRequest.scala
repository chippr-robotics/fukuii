package com.chipprbots.ethereum.blockchain.sync.regular

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.pattern.ask
import org.apache.pekko.util.Timeout

import cats.effect.IO

import scala.concurrent.duration._
import scala.util.Failure

import org.slf4j.Logger

import com.chipprbots.ethereum.blockchain.sync.PeersClient
import com.chipprbots.ethereum.blockchain.sync.PeersClient.BlacklistPeer
import com.chipprbots.ethereum.blockchain.sync.PeersClient.NoSuitablePeer
import com.chipprbots.ethereum.blockchain.sync.PeersClient.Request
import com.chipprbots.ethereum.blockchain.sync.PeersClient.RequestFailed
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.p2p.Message
import com.chipprbots.ethereum.utils.Config.SyncConfig
import com.chipprbots.ethereum.utils.FunctorOps._

trait FetchRequest[A] {
  val peersClient: ActorRef
  val syncConfig: SyncConfig
  val log: Logger

  def makeAdaptedMessage[T <: Message](peer: Peer, msg: T): A

  implicit val timeout: Timeout = syncConfig.peerResponseTimeout + 2.second // some margin for actor communication

  /** Makes a request with peer switching on failure.
    *
    * When a peer fails, it is added to the triedPeers set and the next request will exclude it (Besu pattern). When all
    * peers are exhausted, exponential backoff is applied before retrying (go-ethereum grace period pattern).
    */
  def makeRequest(
      request: Request[_],
      responseFallback: A,
      triedPeers: Set[PeerId] = Set.empty,
      retryCount: Int = 0
  ): IO[A] = {
    log.debug(
      "Making request to peers client: {} (tried: {}, retry: {})",
      request.message.getClass.getSimpleName,
      triedPeers.size,
      retryCount
    )
    IO
      .fromFuture(IO(peersClient ? request))
      .tap { result =>
        blacklistPeerOnFailedRequest(result)
        result match {
          case PeersClient.Response(peer, msg) =>
            log.debug("Received response from peer {} - message type: {}", peer.id, msg.getClass.getSimpleName)
          case RequestFailed(peer, reason) =>
            log.debug("Request failed from peer {} ({}): {}", peer.id, peer.remoteAddress, reason)
          case NoSuitablePeer =>
            log.debug("No suitable peer available for request - will retry with backoff")
          case Failure(cause) =>
            log.debug("Request resulted in failure: {} - {}", cause.getClass.getSimpleName, cause.getMessage)
          case _ =>
            log.debug("Request resulted in unexpected response type: {}", result.getClass.getSimpleName)
        }
        IO.unit
      }
      .flatMap(handleRequestResult(responseFallback, retryCount))
      .handleError { error =>
        log.debug("Unexpected error while doing a request: {}", error.getMessage, error)
        responseFallback
      }
  }

  def blacklistPeerOnFailedRequest(msg: Any): Unit = msg match {
    case RequestFailed(peer, reason) =>
      log.debug("Blacklisting peer {} due to failed request: {}", peer.id, reason)
      peersClient ! BlacklistPeer(peer.id, reason)
    case _ => ()
  }

  def handleRequestResult(fallback: A, retryCount: Int = 0)(msg: Any): IO[A] =
    msg match {
      case failed: RequestFailed =>
        val delay = retryBackoffDelay(retryCount)
        log.debug(
          "Request failed from peer {}, applying {}ms backoff before retry (attempt {})",
          failed.peer.id,
          delay.toMillis,
          retryCount + 1
        )
        IO.pure(fallback).delayBy(delay)
      case NoSuitablePeer =>
        val delay = retryBackoffDelay(retryCount)
        log.debug(
          "No suitable peer available, applying {}ms backoff before retry (attempt {})",
          delay.toMillis,
          retryCount + 1
        )
        IO.pure(fallback).delayBy(delay)
      case Failure(cause) =>
        log.debug("Unexpected error on the request result: {}", cause.getMessage, cause)
        IO.pure(fallback)
      case PeersClient.Response(peer, msg) =>
        log.debug("Successfully received response from peer {} - type: {}", peer.id, msg.getClass.getSimpleName)
        IO.pure(makeAdaptedMessage(peer, msg))
    }

  /** Computes exponential backoff delay: min(syncRetryInterval * 2^retryCount, maxRetryDelay) */
  private def retryBackoffDelay(retryCount: Int): FiniteDuration = {
    val base = syncConfig.syncRetryInterval
    val maxDelay = syncConfig.maxRetryDelay
    val multiplier = math.pow(2.0, retryCount.toDouble).toLong
    val delay = base * multiplier
    if (delay > maxDelay) maxDelay else delay
  }
}
