package com.chipprbots.ethereum.ephemery

import java.time.Clock
import java.time.Duration
import java.time.Instant

import com.chipprbots.ethereum.App

/** The process `EphemeryLaunchItSpec` forks: the real launcher, `App.run`, with its own [[StartupDeps]] (spec 028 R16).
  *
  * Arguments: `<anchor> <launcher arguments...>`. The anchor is the instant, in epoch seconds, that the clock should
  * read when this process starts; from then on it runs at the speed of real time. So the test can put the node a few
  * seconds before its iteration's reset, and the watchdog fires soon after the start, without waiting 28 days and
  * without a test-only configuration key. The transport is the placeholder that refuses (nothing fetches in this
  * slice), and the exit is the real `sys.exit`: its shutdown hooks are what close the node.
  *
  * This is the only code of the spec that reads the system clock.
  */
object EphemeryForkedHarness:

  def main(args: Array[String]): Unit =
    val anchor = Instant.ofEpochSecond(args.head.toLong)
    val clock = Clock.offset(Clock.systemUTC(), Duration.between(Instant.now(), anchor))
    val deps = StartupDeps(clock, RefusingTransport, code => sys.exit(code))
    App.run(args.tail, deps)
