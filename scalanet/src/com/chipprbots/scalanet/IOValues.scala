package com.chipprbots.scalanet

import cats.effect.IO
import cats.effect.unsafe.implicits.global

import scala.concurrent.duration.FiniteDuration

/** Test ergonomics for `cats.effect.IO` mimicking the monix `TaskValues`
  * helpers from the original IOHK scalanet. Provides `.evaluated` (run sync,
  * return value) and `.evaluatedFailure` (run sync, expect failure, return
  * the error). Tests-only — keeps existing spec idioms working post Scala 3
  * migration without forcing a wholesale rewrite to plain `unsafeRunSync()`.
  */
object IOValues {
  extension [A](io: IO[A]) {
    def evaluated: A = io.unsafeRunSync()

    def evaluatedFailure: Throwable =
      try {
        io.unsafeRunSync()
        throw new AssertionError("Expected the IO to fail, but it completed successfully.")
      } catch {
        case t: Throwable => t
      }

    def delayBy(d: FiniteDuration): IO[A] = io.delayBy(d)
  }
}

/** Backwards-compat alias so existing specs that imported the monix-era
  * `TaskValues` keep compiling. New code should use `IOValues` directly.
  */
object TaskValues {
  export IOValues.*
}
