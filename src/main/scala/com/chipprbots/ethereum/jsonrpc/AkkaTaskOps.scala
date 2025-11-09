package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.actor.Actor
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.pattern.ask
import org.apache.pekko.util.Timeout

import cats.effect.IO

import scala.reflect.ClassTag

object AkkaTaskOps {
  implicit class TaskActorOps(val to: ActorRef) extends AnyVal {

    def askFor[A](
        message: Any
    )(implicit timeout: Timeout, classTag: ClassTag[A], sender: ActorRef = Actor.noSender): IO[A] =
      // let the akka ask future manage its timeout instead of adding a second timeout layer
      IO.fromFuture(IO((to ? message).mapTo[A]))
  }
}
