package com.chipprbots.ethereum.ephemery

import java.net.URI
import java.time.Clock
import java.time.Instant

/** What the startup of `fukuii ephemery` takes from outside the process (spec 028 R5, "Test seam"): the clock, the
  * network, and the way out. `App.run` builds one; the resolver and the reset watchdog take it as an argument, so a
  * test (or the forked integration harness) supplies its own and no test-only configuration key exists.
  *
  * @param exit
  *   how the process ends with a code. In production it is `sys.exit`, whose shutdown hooks close the node.
  */
final case class StartupDeps(clock: Clock, transport: ReleaseTransport, exit: Int => Unit)

object StartupDeps:

  /** The process's own: the UTC system clock, no fetching yet, and `sys.exit`. */
  def production: StartupDeps = StartupDeps(Clock.systemUTC(), RefusingTransport, code => sys.exit(code))

/** The transport of a build that cannot fetch yet: every `get` fails, saying so. Slice S4b replaces it with
  * [[JdkHttpsTransport]] in [[StartupDeps.production]].
  */
object RefusingTransport extends ReleaseTransport:
  override def get(
      url: URI,
      maxBytes: Long,
      deadline: Instant,
      accept: Option[String]
  ): Either[TransportFailure, FetchedBody] =
    Left(TransportFailure.Io("fetching the next Ephemery iteration arrives in a later release of this build"))
