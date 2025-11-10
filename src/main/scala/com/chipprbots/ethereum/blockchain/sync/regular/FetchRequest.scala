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
import com.chipprbots.ethereum.network.p2p.Message
import com.chipprbots.ethereum.utils.Config.SyncConfig
import com.chipprbots.ethereum.utils.FunctorOps._

trait FetchRequest[A] {
  val peersClient: ActorRef
  val syncConfig: SyncConfig
  val log: Logger

  def makeAdaptedMessage[T <: Message](peer: Peer, msg: T): A

  implicit val timeout: Timeout = syncConfig.peerResponseTimeout + 2.second // some margin for actor communication

  def makeRequest(request: Request[_], responseFallback: A): IO[A] =
    IO
      .fromFuture(IO(peersClient ? request))
      .tap { result =>
        blacklistPeerOnFailedRequest(result)
        result match {
          case PeersClient.Response(peer, _) => 
            log.debug("Received response from peer {}", peer.id)
          case RequestFailed(peer, reason) => 
            log.debug("Request failed from peer {}: {}", peer.id, reason)
          case NoSuitablePeer => 
            log.debug("No suitable peer available for request")
          case Failure(cause) => 
            log.debug("Request resulted in failure: {}", cause.getMessage)
          case _ => 
            log.debug("Request resulted in unexpected response: {}", result)
        }
        IO.unit
      }
      .flatMap(handleRequestResult(responseFallback))
      .handleError { error =>
        log.error("Unexpected error while doing a request", error)
        responseFallback
      }

  def blacklistPeerOnFailedRequest(msg: Any): Unit = msg match {
    case RequestFailed(peer, reason) => peersClient ! BlacklistPeer(peer.id, reason)
    case _                           => ()
  }

  def handleRequestResult(fallback: A)(msg: Any): IO[A] =
    msg match {
      case failed: RequestFailed =>
        log.debug("Request failed due to {}, using fallback", failed)
        IO.pure(fallback)
      case NoSuitablePeer =>
        log.debug("No suitable peer, retrying after {}", syncConfig.syncRetryInterval)
        IO.pure(fallback).delayBy(syncConfig.syncRetryInterval)
      case Failure(cause) =>
        log.error("Unexpected error on the request result", cause)
        IO.pure(fallback)
      case PeersClient.Response(peer, msg) =>
        log.debug("Successfully received response from peer {}", peer.id)
        IO.pure(makeAdaptedMessage(peer, msg))
    }
}
