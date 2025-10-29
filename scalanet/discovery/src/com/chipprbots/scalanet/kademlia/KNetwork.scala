package com.chipprbots.scalanet.kademlia

import com.chipprbots.scalanet.kademlia.KMessage.{KRequest, KResponse}
import com.chipprbots.scalanet.kademlia.KMessage.KRequest.{FindNodes, Ping}
import com.chipprbots.scalanet.kademlia.KMessage.KResponse.{Nodes, Pong}
import com.chipprbots.scalanet.kademlia.KRouter.NodeRecord
import com.chipprbots.scalanet.peergroup.implicits._
import com.chipprbots.scalanet.peergroup.{Channel, PeerGroup}
import com.chipprbots.scalanet.peergroup.Channel.MessageReceived
import cats.effect.IO
import fs2.Stream
import scala.util.control.NonFatal

trait KNetwork[A] {

  /**
    * Server side requests stream.
    * @return An Observable for receiving FIND_NODES and PING requests.
    *         Each element contains a tuple consisting of a request
    *         with a function for accepting the required response.
    *         With current conventions, it is mandatory to provide
    *         Some(response) or None for all request types, in order that the
    *         implementation can close the channel.
    */
  def kRequests: Stream[IO, (KRequest[A], Option[KResponse[A]] => IO[Unit])]

  /**
    * Send a FIND_NODES message to another peer.
    * @param to the peer to send the message to
    * @param request the FIND_NODES request
    * @return the future response
    */
  def findNodes(to: NodeRecord[A], request: FindNodes[A]): IO[Nodes[A]]

  /**
    * Send a PING message to another peer.
    * @param to the peer to send the message to
    * @param request the PING request
    * @return the future response
    */
  def ping(to: NodeRecord[A], request: Ping[A]): IO[Pong[A]]
}

object KNetwork {

  import scala.concurrent.duration._

  class KNetworkScalanetImpl[A](
      peerGroup: PeerGroup[A, KMessage[A]],
      requestTimeout: FiniteDuration = 3 seconds
  ) extends KNetwork[A] {

    override lazy val kRequests: Stream[IO, (KRequest[A], Option[KResponse[A]] => IO[Unit])] = {
      peerGroup.serverEventStream.collectChannelCreated
        .flatMap {
          case (channel: Channel[A, KMessage[A]], release) =>
            // NOTE: We use flatMap to avoid holding up the handling of further incoming requests.
            // If we receive a non-request message that gets discarded by collect, we don't want
            // to block the next incoming channel from being picked up.
            Stream.eval {
              channel.nextChannelEvent.toStream
                .collect { case MessageReceived(req: KRequest[_]) => req.asInstanceOf[KRequest[A]] }
                .head
                .compile.lastOrError
                .timeout(requestTimeout)
                .map { request =>
                  Some {
                    request -> { (maybeResponse: Option[KResponse[A]]) =>
                      maybeResponse
                        .fold(IO.unit) { response =>
                          channel.sendMessage(response).timeout(requestTimeout)
                        }
                        .guarantee(release)
                    }
                  }
                }
                .handleErrorWith {
                  case NonFatal(_) =>
                    // Most likely it wasn't a request that initiated the channel.
                    release.as(None)
                }
            }
        }
        .collect { case Some(pair) => pair }
    }

    override def findNodes(to: NodeRecord[A], request: FindNodes[A]): IO[Nodes[A]] = {
      requestTemplate(to, request, { case n @ Nodes(_, _, _) => n })
    }

    override def ping(to: NodeRecord[A], request: Ping[A]): IO[Pong[A]] = {
      requestTemplate(to, request, { case p @ Pong(_, _) => p })
    }

    private def requestTemplate[Request <: KRequest[A], Response <: KResponse[A]](
        to: NodeRecord[A],
        message: Request,
        pf: PartialFunction[KMessage[A], Response]
    ): IO[Response] = {
      peerGroup
        .client(to.routingAddress)
        .use { clientChannel =>
          sendRequest(message, clientChannel, pf)
        }
    }

    private def sendRequest[Request <: KRequest[A], Response <: KResponse[A]](
        message: Request,
        clientChannel: Channel[A, KMessage[A]],
        pf: PartialFunction[KMessage[A], Response]
    ): IO[Response] = {
      for {
        _ <- clientChannel.sendMessage(message).timeout(requestTimeout)
        // This assumes that `requestTemplate` always opens a new channel.
        response <- clientChannel.channelEventObservable
          .collect {
            case MessageReceived(m) if pf.isDefinedAt(m) => pf(m)
          }
          .headL
          .timeout(requestTimeout)
      } yield response
    }
  }
}
