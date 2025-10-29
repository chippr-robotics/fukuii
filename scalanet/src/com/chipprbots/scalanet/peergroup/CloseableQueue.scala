package com.chipprbots.scalanet.peergroup

import cats.implicits._
import cats.effect.{Deferred, IO}
import cats.effect.std.Queue
import scala.util.{Left, Right}

/** Wraps an underlying concurrent queue so that polling can return None when
  * the producer side is finished, or vice versa the producer can tell when
  * the consumer is no longer interested in receiving more values.
  *
  *
  * @param closed indicates whether the producer side has finished and whether
  * the messages already in the queue should or discarded (true) or consumed (false).
  * @param queue is the underlying message queue
  */
class CloseableQueue[A](
    closed: Deferred[IO, Boolean],
    queue: Queue[IO, A]
) {
  import CloseableQueue.Closed

  /** Fetch the next item from the queue, or None if the production has finished
    * and the queue has been emptied.
    */
  def next: IO[Option[A]] =
    closed.tryGet.flatMap {
      case Some(true) =>
        // Clear the queue by draining all items using iterateWhile
        IO.iterateWhile(queue.tryTake)(_.isDefined).as(None)

      case Some(false) =>
        queue.tryTake

      case None =>
        IO.race(closed.get, queue.take).flatMap {
          case Left(_) =>
            next
          case Right(item) =>
            IO.pure(Some(item))
        }
    }

  /** Stop accepting items in the queue. Clear items if `discard` is true, otherwise let them be drained.
    * If the queue is already closed it does nothing; this is because either the producer or the consumer
    * could have closed the queue before.
    */
  def close(discard: Boolean): IO[Unit] =
    closed.complete(discard).attempt.void

  /** Close the queue and discard any remaining items in it. */
  def closeAndDiscard: IO[Unit] = close(discard = true)

  /** Close the queue but allow the consumer to pull the remaining items from it. */
  def closeAndKeep: IO[Unit] = close(discard = false)

  /** Try to put a new item in the queue, unless the capactiy has been reached or the queue has been closed. */
  def tryOffer(item: A): IO[Either[Closed, Boolean]] =
    // We could drop the oldest item if the queue is full, rather than drop the latest,
    // but the capacity should be set so it only prevents DoS attacks, so it shouldn't
    // be that crucial to serve clients who overproduce.
    unlessClosed(queue.tryOffer(item))

  /** Try to put a new item in the queue unless the queue has already been closed. Waits if the capacity has been reached. */
  def offer(item: A): IO[Either[Closed, Unit]] =
    unlessClosed {
      IO.race(closed.get, queue.offer(item)).map(_.leftMap(_ => Closed))
    }.map(_.joinRight)

  private def unlessClosed[T](task: IO[T]): IO[Either[Closed, T]] =
    closed.tryGet
      .map(_.isDefined)
      .ifM(
        IO.pure(Left(Closed)),
        task.map(Right(_))
      )
}

object CloseableQueue {

  /** Indicate that the queue was closed. */
  object Closed
  type Closed = Closed.type

  /** Create a queue with a given capacity; 0 or negative means unbounded. */
  def apply[A](capacity: Int): IO[CloseableQueue[A]] = {
    for {
      closed <- Deferred[IO, Boolean]
      queue <- if (capacity <= 0) Queue.unbounded[IO, A] else Queue.bounded[IO, A](capacity)
    } yield new CloseableQueue[A](closed, queue)
  }

  def unbounded[A]: IO[CloseableQueue[A]] =
    apply[A](capacity = 0)
}
