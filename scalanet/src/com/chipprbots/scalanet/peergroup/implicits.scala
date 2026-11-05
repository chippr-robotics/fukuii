package com.chipprbots.scalanet.peergroup

import cats.effect.Deferred
import cats.effect.IO

import com.chipprbots.scalanet.peergroup.Channel.ChannelEvent
import com.chipprbots.scalanet.peergroup.PeerGroup.ServerEvent
import fs2.Stream

package object implicits:
  // Functions to be applied on the `.nextChannelEvent()` or `.nextServerEvent()` results.
  extension [A](next: IO[Option[A]])
    def toStream: Stream[IO, A] =
      Stream.repeatEval(next).unNoneTerminate

    def withCancelToken(token: Deferred[IO, Unit]): IO[Option[A]] =
      IO.race(token.get, next).map {
        case Left(()) => None
        case Right(x) => x
      }

  extension [A, M](group: PeerGroup[A, M])
    def serverEventStream: Stream[IO, ServerEvent[A, M]] =
      group.nextServerEvent.toStream

  extension [A, M](channel: Channel[A, M])
    def channelEventStream: Stream[IO, ChannelEvent[M]] =
      channel.nextChannelEvent.toStream
