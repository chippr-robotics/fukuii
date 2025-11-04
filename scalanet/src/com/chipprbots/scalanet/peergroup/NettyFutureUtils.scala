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
    // Helper to handle completed futures
    def handleCompleted(future: netty.util.concurrent.Future[A]): Unit = {
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

    // Check if the future is already complete to avoid executor rejection
    if (cf.isDone) {
      // Future is already complete, invoke callback immediately
      handleCompleted(cf)
    } else {
      // Try to add listener, but handle rejection gracefully
      try {
        cf.addListener(new GenericFutureListener[Future[A]] {
          override def operationComplete(future: Future[A]): Unit = {
            handleCompleted(future)
          }
        })
      } catch {
        case _: java.util.concurrent.RejectedExecutionException =>
          // Event loop is shutting down or already shut down.
          // Check if the future has completed in the meantime.
          if (cf.isDone) {
            handleCompleted(cf)
          }
          // If not done, we can't do anything. The operation is being cancelled anyway.
      }
    }
    ()
  }
}
