package com.chipprbots.scalanet.peergroup

import java.util.concurrent.CancellationException

import cats.effect.IO

import io.netty
import io.netty.util.concurrent.Future
import io.netty.util.concurrent.GenericFutureListener

private[scalanet] object NettyFutureUtils {
  def toTask(f: => netty.util.concurrent.Future[_]): IO[Unit] = {
    val future = f  // Assign to val first as required by Scala 3
    fromNettyFuture(IO.delay(future)).void
  }

  def fromNettyFuture[A](ff: IO[netty.util.concurrent.Future[A]]): IO[A] = {
    ff.flatMap { nettyFuture =>
      IO.async { cb =>
        IO {
          subscribeToFuture(nettyFuture, cb)
          Some(IO.delay({ nettyFuture.cancel(true); () }))
        }
      }
    }
  }

  private def subscribeToFuture[A](cf: netty.util.concurrent.Future[A], cb: Either[Throwable, A] => Unit): Unit = {
    cf.addListener(new GenericFutureListener[Future[A]] {
      override def operationComplete(future: Future[A]): Unit = {
        if (future.isSuccess) {
          cb(Right(future.getNow))
        } else {
          future.cause() match {
            case _: CancellationException =>
              ()
            case ex => cb(Left(ex))
          }
        }
      }
    })
    ()
  }
}
