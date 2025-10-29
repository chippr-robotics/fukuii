package com.chipprbots.scalanet.peergroup

import cats.effect.{Deferred, IO}
import fs2.Stream
import com.chipprbots.scalanet.peergroup.PeerGroup.ServerEvent
import com.chipprbots.scalanet.peergroup.Channel.ChannelEvent

package object implicits {
  // Functions to be applied on the `.nextChannelEvent()` or `.nextServerEvent()` results.
  implicit class NextOps[A](val next: IO[Option[A]]) extends AnyVal {
    def toStream: Stream[IO, A] =
      Stream.repeatEval(next).unNoneTerminate

    def withCancelToken(token: Deferred[IO, Unit]): IO[Option[A]] =
      IO.race(token.get, next).map {
        case Left(()) => None
        case Right(x) => x
      }
  }

  implicit class PeerGroupOps[A, M](val group: PeerGroup[A, M]) extends AnyVal {
    def serverEventStream: Stream[IO, ServerEvent[A, M]] =
      group.nextServerEvent.toStream
  }

  implicit class ChannelOps[A, M](val channel: Channel[A, M]) extends AnyVal {
    def channelEventStream: Stream[IO, ChannelEvent[M]] =
      channel.nextChannelEvent.toStream
  }
}
