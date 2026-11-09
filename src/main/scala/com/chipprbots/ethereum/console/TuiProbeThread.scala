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
  */
final class TuiProbeThread(
    probe: () => NodeStatusSnapshot,
    intervalMs: Long,
    published: AtomicReference[Option[NodeStatusSnapshot]]
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

  private def loop(): Unit =
    while stopped.getCount > 0 do
      val started = System.nanoTime()
      try
        published.set(Some(probe()))
        failing = false
      catch
        case NonFatal(e) =>
          if failing then log.debug("TUI status poll failed again: {}", e.getMessage)
          else log.error("TUI status poll failed: {}", e.getMessage, e)
          failing = true
      val elapsedMs = (System.nanoTime() - started) / 1_000_000L
      stopped.await((intervalMs - elapsedMs).max(0L), TimeUnit.MILLISECONDS)
