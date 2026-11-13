package com.chipprbots.ethereum.ephemery

import java.time.Clock

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
  def production: StartupDeps = StartupDeps(Clock.systemUTC(), ReleaseTransport.Refusing, code => sys.exit(code))
