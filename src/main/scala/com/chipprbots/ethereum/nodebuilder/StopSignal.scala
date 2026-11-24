package com.chipprbots.ethereum.nodebuilder

import java.util.concurrent.atomic.AtomicBoolean

import com.chipprbots.ethereum.utils.Logger

/** SIGTERM stops the node and exits 0 when the stop was clean (B-426).
  *
  * The JVM's own SIGTERM handler exits with 143 (128 + 15) whatever the shutdown hooks did, so a supervisor could not
  * tell a clean stop from a failed one, and every stop read as killed. This handler runs the node's stop itself (or,
  * when another path began it, waits for that stop to end: [[StopOnce]]), then exits 0 if the database closed, 1
  * otherwise; the shutdown hooks run as the exit's, and the node's own hook returns at once, its stop already done.
  * go-ethereum exits 0 after a clean stop on SIGTERM the same way.
  *
  * A second SIGTERM while the stop runs is logged and ignored: exiting then would cut the database's close half-way. A
  * supervisor that cannot wait sends SIGKILL.
  */
object StopSignal extends Logger:

  /** The exit status of a stop that closed the database. */
  val CleanExit: Int = 0

  /** The exit status of a stop that did not close the database. */
  val UncleanExit: Int = 1

  /** Handles SIGTERM with [[onSignal]]. `stop` stops the node and says whether the stop was clean. A JVM that does not
    * let the process handle signals (`-Xrs`) keeps its own handler, and exits 143.
    */
  def install(stop: () => Boolean, exit: Int => Unit = code => System.exit(code)): Unit =
    val handled = new AtomicBoolean(false)
    try sun.misc.Signal.handle(new sun.misc.Signal("TERM"), _ => onSignal(handled, stop, exit)): Unit
    catch
      case e: IllegalArgumentException =>
        log.warn(s"SIGTERM is left to the JVM, which exits 143 after the node's stop: ${e.getMessage}")

  /** The first call stops the node and exits with its status; the next ones only log. */
  private[nodebuilder] def onSignal(handled: AtomicBoolean, stop: () => Boolean, exit: Int => Unit): Unit =
    if handled.compareAndSet(false, true) then stopThenExit("SIGTERM", stop, exit)
    else log.warn("[SHUTDOWN] SIGTERM again: the stop under way goes on (SIGKILL ends it)")

  /** Stops the node, then exits: the way out of every exit path that starts once the node is up (SIGTERM, the TUI's
    * `q`, the Ephemery reset; B-426, B-452). `stop` runs the node's stop through its [[StopOnce]], so when another path
    * began the stop it waits for that stop to end, and says whether the database closed. The exit comes only after it:
    * an exit made at once would run the JVM's hook, which does not wait for a stop under way (B-274), and the process
    * would end while the database still closes.
    *
    * Whatever the stop ends in, the process ends (B-467): a stop that throws, even a fatal error, exits with the
    * unclean status, and leaves no node half-stopped and still up. A wait for a stop under way is not cut short by an
    * interrupt ([[StopOnce.run]]): it exits with that stop's outcome, the interrupt set again. A
    * [[VirtualMachineError]] halts instead of exiting: the JVM's hooks may hang on a JVM in that state.
    *
    * @param why
    *   what asked for the stop, for the log
    * @param status
    *   the exit status for a clean stop (true) or one that did not close the database (false); by default [[CleanExit]]
    *   and [[UncleanExit]]
    * @param halt
    *   how the process ends after a [[VirtualMachineError]]; `Runtime.halt`, which runs no hooks
    */
  def stopThenExit(
      why: String,
      stop: () => Boolean,
      exit: Int => Unit,
      status: Boolean => Int = clean => if clean then CleanExit else UncleanExit,
      halt: Int => Unit = code => Runtime.getRuntime.halt(code)
  ): Unit =
    log.info(s"[SHUTDOWN] $why: stopping the node")
    val stopped: Either[Throwable, Boolean] =
      try Right(stop())
      catch case e: Throwable => Left(e)
    stopped match
      case Right(clean) =>
        val code = status(clean)
        if clean then log.info(s"[SHUTDOWN] Stopped cleanly: exit $code")
        else log.error(s"[SHUTDOWN] The database did not close cleanly: exit $code")
        exit(code)
      case Left(e: VirtualMachineError) =>
        val code = status(false)
        // The log may fail on the JVM in this state; the halt must not be skipped for it.
        try log.error(s"[SHUTDOWN] The node's stop died of $e: halting with $code", e)
        catch case _: Throwable => ()
        halt(code)
      case Left(e) =>
        // An interrupt from a stop that is not StopOnce's (whose waits outlast one): keep it for the exit's thread.
        if e.isInstanceOf[InterruptedException] then Thread.currentThread().interrupt()
        val code = status(false)
        log.error(s"[SHUTDOWN] The node's stop failed: exit $code", e)
        exit(code)
