package com.chipprbots.ethereum.nodebuilder

import java.util.concurrent.atomic.AtomicBoolean

import scala.util.control.NonFatal

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
    if handled.compareAndSet(false, true) then
      log.info("[SHUTDOWN] SIGTERM: stopping the node")
      val clean =
        try stop()
        catch
          case NonFatal(e) =>
            log.error("[SHUTDOWN] The node's stop failed", e)
            false
      val status = if clean then CleanExit else UncleanExit
      if clean then log.info("[SHUTDOWN] Stopped cleanly: exit 0")
      else log.error(s"[SHUTDOWN] The database did not close cleanly: exit $status")
      exit(status)
    else log.warn("[SHUTDOWN] SIGTERM again: the stop under way goes on (SIGKILL ends it)")
