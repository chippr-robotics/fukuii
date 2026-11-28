package com.chipprbots.scalanet.discovery.ethereum.v4.mocks

import cats.effect.IO
import scala.concurrent.duration.*

/** Condition-driven waiting for tests of background fibers.
  *
  * The barrier is the predicate, not the clock: the effect is re-read every `pollInterval` until the predicate holds,
  * so a slow or loaded machine only makes the test slower, never wrong. `guard` exists only to turn a condition that
  * never becomes true into a failure instead of a hang.
  */
object AwaitCondition:
  def until[A](read: IO[A], pollInterval: FiniteDuration = 5.millis, guard: FiniteDuration = 60.seconds)(
      predicate: A => Boolean
  ): IO[A] =
    def loop: IO[A] =
      read.flatMap(a => if predicate(a) then IO.pure(a) else IO.sleep(pollInterval) >> loop)
    loop.timeoutTo(guard, IO.raiseError(new AssertionError(s"Condition not met within $guard")))
