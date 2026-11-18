package com.chipprbots.ethereum.nodebuilder

import scala.concurrent.duration.*

import com.chipprbots.ethereum.utils.Logger

/** What a launcher does when a node fails to start (B-274).
  *
  * A throw from `StdNode.start` used to escape `main` as "Exception in thread main" on stderr alone: P2P and discovery
  * were already up, their non-daemon threads kept the JVM alive with no API, the log said nothing, and under `--tui`
  * the screen said "Starting..." forever. A failed start now ends the process loudly: the error is logged at ERROR (so
  * it is in the log file), the terminal leaves raw mode and the alternate screen, the error is printed on stderr where
  * the user can see it, what started is shut down within a bound, and the process exits non-zero.
  *
  * Every Throwable is handled, fatal ones included (a LinkageError from a missing native library, a
  * StackOverflowError): the process ends either way, and reporting it first is what this is for. Each step is attempted
  * on its own, so a step that throws does not keep the next from running, and the exit is always reached.
  */
object NodeStartup extends Logger:

  /** The exit status of a node that failed to start. */
  val FailedExitCode: Int = 1

  /** How long a failed start's shutdown may take before the process exits regardless. */
  val DefaultShutdownBound: FiniteDuration = 60.seconds

  /** What a failed start does beyond logging, injectable so that a test runs it without leaving its JVM.
    *
    * @param restoreTerminal
    *   leaves the TUI's raw mode and alternate screen (a no-op without a TUI); runs before anything is printed
    * @param printToUser
    *   shows the error to the user (stderr in production)
    * @param shutdown
    *   stops what started: actor systems, P2P server, discovery, database
    * @param shutdownBound
    *   how long `shutdown` may run; past it the exit goes ahead and the shutdown is abandoned
    * @param exit
    *   ends the process with a status (`sys.exit` in production, whose JVM hooks restore the terminal again and find
    *   the node already shut down)
    */
  final case class OnFailure(
      restoreTerminal: () => Unit,
      printToUser: String => Unit,
      shutdown: () => Unit,
      shutdownBound: FiniteDuration,
      exit: Int => Unit
  )

  /** Runs `start`. True if it returned. If it threw: log at ERROR, restore the terminal, print the error, shut down
    * (bounded), exit with [[FailedExitCode]]. False only when `exit` returned, which a production exit never does.
    */
  def startOrExit(start: () => Unit, onFailure: OnFailure): Boolean =
    attemptStart(start) match
      case None => true
      case Some(error) =>
        attempt("restore the terminal")(onFailure.restoreTerminal())
        attempt("print the startup error")(onFailure.printToUser(userMessage(error)))
        shutDownBounded(onFailure.shutdown, onFailure.shutdownBound)
        onFailure.exit(FailedExitCode)
        false

  /** Runs `start`; if it throws, logs the error at ERROR and runs `shutdown` within `bound`. The error, if any. The
    * multi-instance runtime uses this for each instance, so one that fails to start is not left half-running.
    */
  def startOrShutDown(start: () => Unit, shutdown: () => Unit, bound: FiniteDuration): Option[Throwable] =
    val failure = attemptStart(start)
    if failure.isDefined then shutDownBounded(shutdown, bound)
    failure

  /** Runs `start`; the error it threw, logged at ERROR, if it threw. */
  private def attemptStart(start: () => Unit): Option[Throwable] =
    try
      start()
      None
    catch
      case error: Throwable =>
        attempt("log the startup error")(logError(error))
        Some(error)

  /** The line the user sees. A refusal carries an operator-facing message of its own; anything else is named, and the
    * stack trace is left to the log.
    */
  def userMessage(error: Throwable): String = error match
    case refusal: EngineApiStartupRefusal => refusal.getMessage
    case other => s"Fukuii failed to start: $other. Shutting down; the log file has the stack trace."

  private def logError(error: Throwable): Unit = error match
    // A refusal is the operator's to fix and says so: its message, once, with no trace (B-246).
    case refusal: EngineApiStartupRefusal => log.error(refusal.getMessage)
    case other                            => log.error(s"Fukuii failed to start: $other", other)

  /** Runs `shutdown` on a daemon thread and waits at most `bound` for it: a shutdown that hangs (an actor system that
    * will not terminate) must not keep the process from exiting.
    */
  private def shutDownBounded(shutdown: () => Unit, bound: FiniteDuration): Unit =
    attempt("shut down") {
      val thread = new Thread(() => attempt("shut down")(shutdown()), "fukuii-startup-failure-shutdown")
      thread.setDaemon(true)
      thread.start()
      thread.join(bound.toMillis.max(1L))
      if thread.isAlive then log.error(s"The shutdown after a failed start did not finish within $bound; going on")
    }

  private def attempt(what: String)(f: => Unit): Unit =
    try f
    catch
      case e: Throwable =>
        try log.error(s"Failed to $what after a failed start: $e", e)
        catch case _: Throwable => ()
