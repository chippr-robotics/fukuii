package com.chipprbots.ethereum.console

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

import scala.util.control.NonFatal

import com.chipprbots.ethereum.utils.Logger

/** Polls the node for the TUI on its own daemon thread, every `intervalMs`, and publishes each snapshot to `published`
  * for the frame loop to read without waiting (spec 027 FR-011, research §7.2).
  *
  * A slow actor delays the next snapshot by at most the probe's one bounded wait; it never delays a frame. The wait
  * between polls is a latch, so [[stop]] returns at once.
  *
  * @param probe
  *   one poll ([[TuiStatusProbe]]); may block up to its wait, and may throw
  * @param published
  *   where the latest snapshot goes; [[TuiUpdater]] reads it once a frame
  * @param active
  *   whether anyone still reads the snapshots: polling ends once the TUI it feeds is gone (`d`, a lost terminal, a
  *   failed key read), instead of asking the actors every second for nobody
  */
final class TuiProbeThread(
    probe: () => NodeStatusSnapshot,
    intervalMs: Long,
    published: AtomicReference[Option[NodeStatusSnapshot]],
    active: () => Boolean = () => true
) extends Logger:

  private val stopped = new CountDownLatch(1)
  @volatile private var thread: Option[Thread] = None

  /** A failed poll is logged once, with its stack trace; the ones after it at DEBUG, until a poll succeeds. */
  @volatile private var failing = false

  def start(): Unit = synchronized {
    if thread.isEmpty && stopped.getCount > 0 then
      val t = new Thread(() => loop(), "TuiProbeThread")
      t.setDaemon(true)
      thread = Some(t)
      t.start()
  }

  /** Stop polling and wait (up to `timeoutMs`) for an in-flight poll to finish. Safe from any thread. */
  def stop(timeoutMs: Long = TuiConfig.DefaultShutdownTimeoutMs): Unit =
    stopped.countDown()
    val t = synchronized {
      val current = thread
      thread = None
      current
    }
    t.filterNot(_ eq Thread.currentThread()).foreach(_.join(timeoutMs))

  def isRunning: Boolean = thread.exists(_.isAlive)

  // Nothing escapes this thread: an uncaught throwable would reach the default handler, which can print over the
  // TUI's screen.
  private def loop(): Unit =
    try
      while stopped.getCount > 0 && active() do
        val started = System.nanoTime()
        poll()
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L
        stopped.await((intervalMs - elapsedMs).max(0L), TimeUnit.MILLISECONDS)
    catch case _: InterruptedException => () // the JVM is going down; there is nothing left to poll for

  private def poll(): Unit =
    try
      val snapshot = probe()
      // A poll that was in flight when stop() was called publishes nothing.
      if stopped.getCount > 0 then published.set(Some(snapshot))
      failing = false
    catch
      case e: InterruptedException => throw e
      case NonFatal(e) =>
        if failing then log.debug("TUI status poll failed again: {}", e.getMessage)
        else log.error("TUI status poll failed: {}", e.getMessage, e)
        failing = true
      case e: Throwable =>
        // Fatal (a class that failed to load, out of memory): polling stops, and the frame loop shows both sources
        // go Unresponsive as the last snapshot ages.
        log.error("TUI status poll failed fatally; the TUI stops polling: {}", e.toString, e)
        stopped.countDown()
