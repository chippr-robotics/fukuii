package com.chipprbots.ethereum.jsonrpc

import akka.actor.Actor
import akka.actor.ActorRef
import akka.pattern.ask
import akka.util.Timeout

import cats.effect.IO

import scala.reflect.ClassTag

object AkkaTaskOps {
  implicit class TaskActorOps(val to: ActorRef) extends AnyVal {

    def askFor[A](
        message: Any
    )(implicit timeout: Timeout, classTag: ClassTag[A], sender: ActorRef = Actor.noSender): IO[A] =
      IO
        .fromFuture(IO((to ? message).mapTo[A]))
        .timeout(timeout.duration)
  }
}
