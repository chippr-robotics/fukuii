package com.chipprbots.ethereum.ephemery

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Clock
import java.time.Duration
import java.time.Instant

import com.chipprbots.ethereum.App

/** The process `EphemeryLaunchItSpec` forks: the real launcher, `App.run`, with its own [[StartupDeps]] (spec 028 R16).
  *
  * Arguments: `<anchor> <launcher arguments...>`. The anchor is the instant, in epoch seconds, that the clock should
  * read when this process starts; from then on it runs at the speed of real time. So the test can put the node a few
  * seconds before its iteration's reset, and the watchdog fires soon after the start, without waiting 28 days and
  * without a test-only configuration key. The transport is a fixed one, standing in for the network: with
  * `-Dephemery.it.archive=<file>` it serves that `testnet-all.tar.gz` for the one release URL
  * `-Dephemery.it.archive.iteration=<N>` names, and answers 404 for every other; with neither it refuses every call.
  * The exit is the real `sys.exit`: its shutdown hooks are what close the node.
  *
  * This is the only code of the spec that reads the system clock.
  */
object EphemeryForkedHarness:

  def main(args: Array[String]): Unit =
    val anchor = Instant.ofEpochSecond(args.head.toLong)
    val clock = Clock.offset(Clock.systemUTC(), Duration.between(Instant.now(), anchor))
    val transport: ReleaseTransport =
      (
        Option(System.getProperty("ephemery.it.archive")).filter(_.nonEmpty),
        Option(System.getProperty("ephemery.it.archive.iteration")).filter(_.nonEmpty)
      ) match
        case (Some(file), Some(n)) => new ArchiveTransport(Paths.get(file), n.toInt)
        case _                     => RefusingTransport
    val deps = StartupDeps(clock, transport, code => sys.exit(code))
    App.run(args.tail, deps)

  /** Serves one archive for `ephemery-<iteration>`'s release URL, and a 404 for any other. */
  final private class ArchiveTransport(archive: Path, iteration: Int) extends ReleaseTransport:
    override def get(
        url: URI,
        maxBytes: Long,
        deadline: Instant,
        accept: Option[String]
    ): Either[TransportFailure, FetchedBody] =
      if url.getPath.endsWith(s"/releases/download/ephemery-$iteration/testnet-all.tar.gz") then
        Right(FetchedBody(url, 200, Files.readAllBytes(archive)))
      else Left(TransportFailure.HttpStatus(404))
