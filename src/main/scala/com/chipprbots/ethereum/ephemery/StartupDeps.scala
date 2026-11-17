package com.chipprbots.ethereum.ephemery

import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

import com.chipprbots.ethereum.utils.BuildInfo

/** How the refresh waits between two attempts. A test passes one that moves its manual clock; the process's own blocks
  * the calling thread, which is the one that runs the resolver, before the node starts.
  */
trait Sleeper:
  def sleep(duration: Duration): Unit

object Sleeper:

  /** Blocks on a latch nobody counts down, and keeps an interrupt: a wait that is interrupted ends at once, and the
    * flag stays set for whoever asked.
    */
  val real: Sleeper = (duration: Duration) =>
    if !duration.isNegative && !duration.isZero then
      try
        new CountDownLatch(1).await(duration.toNanos, TimeUnit.NANOSECONDS)
        ()
      catch case _: InterruptedException => Thread.currentThread().interrupt()

/** What the startup of `fukuii ephemery` takes from outside the process (spec 028 R5, "Test seam"): the clock, the
  * network, the way to wait, and the way out. `App.run` builds one; the resolver and the reset watchdog take it as an
  * argument, so a test (or the forked integration harness) supplies its own and no test-only configuration key exists.
  *
  * @param transportFor
  *   the HTTPS client for a source policy, or why none can be made (an unusable proxy variable). The policy is chosen
  *   by the resolver from the operator's `fukuii.ephemery.source`; nothing here lets a caller loosen it. The resolver
  *   closes the transport when it has done with it, if it is `AutoCloseable`.
  * @param exit
  *   how the process ends with a code. In production it is `sys.exit`, whose shutdown hooks close the node.
  */
final case class StartupDeps(
    clock: Clock,
    transportFor: SourcePolicy => Either[String, ReleaseTransport],
    exit: Int => Unit,
    sleeper: Sleeper
)

object StartupDeps:

  /** One transport for whatever policy is asked: for a test, or for the placeholder. */
  def apply(
      clock: Clock,
      transport: ReleaseTransport,
      exit: Int => Unit,
      sleeper: Sleeper = Sleeper.real
  ): StartupDeps =
    StartupDeps(clock, (_: SourcePolicy) => Right(transport), exit, sleeper)

  /** The process's own: the UTC system clock, the strict HTTPS transport (proxy and trust store from the JVM and the
    * environment, nothing from the configuration), and `sys.exit`.
    */
  def production: StartupDeps =
    val clock = Clock.systemUTC()
    StartupDeps(
      clock,
      (policy: SourcePolicy) => JdkHttpsTransport.default(policy, BuildInfo.version, sys.env, clock),
      code => sys.exit(code),
      Sleeper.real
    )

/** The transport of a caller that must not fetch: every `get` fails, saying so. */
object RefusingTransport extends ReleaseTransport:
  override def get(
      url: URI,
      maxBytes: Long,
      deadline: Instant,
      accept: Option[String]
  ): Either[TransportFailure, FetchedBody] =
    Left(TransportFailure.Io("this build was given no network to fetch with"))
