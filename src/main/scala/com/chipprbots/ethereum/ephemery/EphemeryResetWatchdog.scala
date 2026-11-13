package com.chipprbots.ethereum.ephemery

import java.nio.file.Path
import java.nio.file.Paths
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

import com.typesafe.config.Config as TConfig

import com.chipprbots.ethereum.ephemery.EphemeryResetWatchdog.*
import com.chipprbots.ethereum.ephemery.EphemeryResolver.Level
import com.chipprbots.ethereum.ephemery.EphemeryResolver.Line
import com.chipprbots.ethereum.utils.LazyLogger

/** What the node does when its Ephemery iteration ends (spec 028 R11, FR-016): it logs a WARN and exits with code 75,
  * so a supervisor's restart picks up the next iteration. The shutdown hooks of the node close it cleanly.
  *
  * It wakes in steps of at most [[MaxWake]] and compares the clock each time, instead of sleeping the 28 days at once:
  * a host that was suspended, or a clock that was set, is noticed within one step.
  *
  * State (data-model.md): idle, armed, fired, cancelled; `WarnOnly` and `Prepared` warn at the reset and do not exit.
  *
  * @param exit
  *   the way out, `StartupDeps.exit`
  * @param report
  *   where the WARN goes
  */
final class EphemeryResetWatchdog(clock: Clock, scheduler: ResetScheduler, exit: Int => Unit, report: Line => Unit):

  /** Arms the watchdog for `target`. The result cancels it (a shutdown for another reason). */
  def start(target: Target): Armed =
    val armed = new Armed
    target.mode match
      // The prepared iteration had ended when the node started: the resolver said so, and there is nothing to add.
      case Mode.Prepared(_) if !clock.instant().isBefore(target.resetTime) => ()
      case _                                                               => check(target, armed)
    armed

  private def check(target: Target, armed: Armed): Unit =
    if !armed.cancelled then
      val remaining = Duration.between(clock.instant(), target.resetTime)
      if remaining.isNegative || remaining.isZero then fire(target)
      else
        armed.pending = scheduler.schedule(
          if remaining.compareTo(MaxWake) < 0 then remaining else MaxWake,
          () => check(target, armed)
        )

  private def fire(target: Target): Unit =
    val ended = s"Ephemery iteration ephemery-${target.iteration} ended at ${target.resetTime}"
    val next = s"ephemery-${target.iteration + 1}"
    target.mode match
      case Mode.ExitAtReset =>
        report(Line(Level.Warn, s"$ended; exiting with code $ExitCode so a supervisor restart picks up $next"))
        exit(ExitCode)
      case Mode.WarnOnly =>
        report(Line(Level.Warn, s"$ended; restart fukuii to move to $next (exit-at-reset = false)"))
      case Mode.Prepared(dir) =>
        report(Line(Level.Warn, EphemeryResolver.preparedEndedText(dir, target.resetTime)))

object EphemeryResetWatchdog extends LazyLogger:

  /** `EX_TEMPFAIL` (sysexits(3)): the running iteration reached its reset; a restart refreshes
    * (contracts/exit-codes.md).
    */
  val ExitCode: Int = 75

  /** The longest the watchdog sleeps between two looks at the clock. */
  val MaxWake: Duration = Duration.ofMinutes(10)

  enum Mode:
    /** A managed iteration: WARN, then exit 75. */
    case ExitAtReset

    /** A managed iteration with `exit-at-reset = false`: WARN only. */
    case WarnOnly

    /** An operator's prepared directory: WARN only, naming it. */
    case Prepared(dir: Path)

  /** @param iteration
    *   N of `ephemery-N`
    */
  final case class Target(iteration: Int, resetTime: Instant, mode: Mode)

  object Target:

    /** From the `fukuii` block of the node's configuration, where the resolver put `ephemery.active.*`. `None` when it
      * put nothing (another network, or an operator directory whose reset is unknown).
      */
    def fromConfig(fukuii: TConfig): Option[Target] =
      def has(path: String): Boolean = fukuii.hasPath(path)
      Option
        .when(has("ephemery.active.reset-time") && has("ephemery.active.iteration")) {
          val iteration = fukuii.getInt("ephemery.active.iteration")
          val reset = Instant.ofEpochSecond(fukuii.getLong("ephemery.active.reset-time"))
          val managed = has("ephemery.active.managed") && fukuii.getBoolean("ephemery.active.managed")
          val exitAtReset = !has("ephemery.exit-at-reset") || fukuii.getBoolean("ephemery.exit-at-reset")
          if managed then Some(Target(iteration, reset, if exitAtReset then Mode.ExitAtReset else Mode.WarnOnly))
          else
            Option.when(has("blockchains.custom-chains-dir"))(
              Target(iteration, reset, Mode.Prepared(Paths.get(fukuii.getString("blockchains.custom-chains-dir"))))
            )
        }
        .flatten

  /** Handle to cancel an armed watchdog. */
  final class Armed:
    @volatile private[EphemeryResetWatchdog] var cancelled: Boolean = false
    @volatile private[EphemeryResetWatchdog] var pending: () => Unit = () => ()

    def cancel(): Unit =
      cancelled = true
      pending()

  /** Runs a task once after a delay. The result cancels it. */
  trait ResetScheduler:
    def schedule(delay: Duration, task: Runnable): () => Unit

  object ResetScheduler:

    /** One daemon thread: it never keeps the JVM alive, and a node that stops for another reason takes it along. */
    def daemon(): ResetScheduler =
      val executor = new ScheduledThreadPoolExecutor(
        1,
        new ThreadFactory:
          override def newThread(r: Runnable): Thread =
            val t = new Thread(r, "ephemery-reset-watchdog")
            t.setDaemon(true)
            t
      )
      executor.setRemoveOnCancelPolicy(true)
      (delay: Duration, task: Runnable) =>
        val future = executor.schedule(task, delay.toMillis, TimeUnit.MILLISECONDS)
        () =>
          future.cancel(false); ()

  /** The watchdog the node starts after it has started: the configuration says whether and how. A shutdown for another
    * reason cancels it.
    */
  def startFor(fukuii: TConfig, deps: StartupDeps): Option[Armed] =
    Target.fromConfig(fukuii).map { target =>
      val watchdog = new EphemeryResetWatchdog(
        deps.clock,
        ResetScheduler.daemon(),
        deps.exit,
        line =>
          line.level match
            case Level.Error => log.error(line.text)
            case Level.Warn  => log.warn(line.text)
            case Level.Info  => log.info(line.text)
      )
      val armed = watchdog.start(target)
      Runtime.getRuntime.addShutdownHook(new Thread(() => armed.cancel(), "EphemeryWatchdogCancel"))
      armed
    }
