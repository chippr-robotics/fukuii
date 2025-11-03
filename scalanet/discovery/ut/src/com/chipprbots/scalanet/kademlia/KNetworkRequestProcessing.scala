package com.chipprbots.scalanet.kademlia

import com.chipprbots.scalanet.kademlia.KMessage.KRequest.{FindNodes, Ping}
import com.chipprbots.scalanet.kademlia.KMessage.KResponse.{Nodes, Pong}
import com.chipprbots.scalanet.kademlia.KMessage.{KRequest, KResponse}
import cats.effect.IO
import cats.effect.unsafe.implicits.global

import fs2.Stream

/**
  * If a user of KNetwork wanted to consume only one kind of request,
  * it is not sufficient to collect or filter the request stream, since it is
  * still necessary to invoke response handers to close channels for excluded request types.
  * The code to do this is demonstrated here.
  * Note that findNodesRequests and pingRequests are mutually exclusive.
  */
object KNetworkRequestProcessing {

  implicit class KNetworkExtension[A](kNetwork: KNetwork[A])() {

    type KRequestT = (KRequest[A], Option[KResponse[A]] => IO[Unit])
    type FindNodesT = (FindNodes[A], Option[Nodes[A]] => IO[Unit])
    type PingT = (Ping[A], Option[Pong[A]] => IO[Unit])

    def findNodesRequests(): Stream[IO, FindNodesT] =
      kNetwork.kRequests
        .collect {
          case (f @ FindNodes(_, _, _), h) =>
            Some((f, h))
          case (_, h) =>
            ignore(h)
        }
        .collect { case Some(v) => v }

    def pingRequests(): Stream[IO, PingT] =
      kNetwork.kRequests
        .map {
          case (p @ Ping(_, _), h) =>
            Some((p, h))
          case (_, h) =>
            ignore(h)
        }
        .collect { case Some(v) => v }

    private def ignore(
        handler: Option[KResponse[A]] => IO[Unit]
    ): None.type = {
      handler(None).unsafeRunSync()
      None
    }
  }
}
