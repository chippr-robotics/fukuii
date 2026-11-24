package com.chipprbots.ethereum.blockchain.sync.snap

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger as LogbackLogger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.turbo.TurboFilter
import ch.qos.logback.core.spi.FilterReply
import org.slf4j.LoggerFactory
import org.slf4j.Marker
import org.slf4j.helpers.MessageFormatter

/** Captures every log line (DEBUG and up) of the `snap` package while open, without printing it (spec 016 S0d).
  *
  * `logback-test.xml` keeps the root at ERROR, so `LoggingTestKit` sees nothing below ERROR. Some characterization
  * tests need lines below that: the `syncing` catch-all ("Unhandled message in syncing state") is DEBUG, and the
  * recovery replay's pause is an INFO from a Future.
  *
  * The lines are taken by a logback turbo filter, which every logging call consults on the logging thread before the
  * level check, so a line logged while handling a message is captured before the controller handles its next message.
  * The capture does not attach an appender (B-256): an appender added to a logger while another thread logs through it
  * can be lost, because logback's appender list (`COWArrayList`) caches an array, and a logging thread that rebuilds
  * the cache from the old list as the appender is added marks that stale copy fresh. The characterization specs open a
  * capture just after sending the controller a message, so its thread is logging in the package at that moment; in CI a
  * capture so opened saw nothing at all. The turbo filter list is a plain `CopyOnWriteArrayList`, with no cache.
  *
  * The filter accepts the package's DEBUG-and-up lines whatever the loggers' levels (an explicitly levelled logger
  * under the package, such as the TrieNodeHealingCoordinator's INFO in `logback-test.xml`, is captured at DEBUG too),
  * and the package logger's additivity is off while a capture is open, so nothing reaches the console. One filter
  * serves every open capture, so captures nest; `close()` restores the additivity.
  */
final class SnapLogCapture extends AutoCloseable:
  private val logger = LoggerFactory.getLogger(SnapLogCapture.Package).asInstanceOf[LogbackLogger]
  private val previousAdditive: Boolean = logger.isAdditive

  private val captured = new ConcurrentLinkedQueue[(Level, String)]()
  private val arrivals = new LinkedBlockingQueue[(Level, String)]()

  private def record(level: Level, line: String): Unit =
    captured.add((level, line))
    arrivals.put((level, line))

  SnapLogCapture.register(this)
  logger.setAdditive(false)

  /** Every line captured so far, oldest first. */
  def lines: List[String] = events.map(_._2)

  /** Every line captured so far with its level, oldest first. */
  def events: List[(Level, String)] = captured.asScala.toList

  def count(substring: String): Int = lines.count(_.contains(substring))

  def contains(substring: String): Boolean = count(substring) > 0

  def clear(): Unit =
    captured.clear()
    arrivals.clear()

  /** Wait (up to `max`) for a line containing `substring` that arrived since the last `clear`/`awaitLine` match. */
  def awaitLine(substring: String, max: FiniteDuration = 10.seconds): String = awaitEvent(substring, max)._2

  /** [[awaitLine]], with the line's level. */
  def awaitEvent(substring: String, max: FiniteDuration = 10.seconds): (Level, String) =
    val deadline = System.nanoTime() + max.toNanos
    @annotation.tailrec
    def loop(): (Level, String) =
      val remaining = deadline - System.nanoTime()
      if remaining <= 0 then throw missing(substring, s"within $max")
      val event = arrivals.poll(remaining, TimeUnit.NANOSECONDS)
      if event == null then throw missing(substring, s"within $max")
      else if event._2.contains(substring) then event
      else loop()
    loop()

  /** Fail unless a line containing `substring` has been captured: for a line the caller has already synchronised on (a
    * mailbox barrier), where waiting on the clock would only hide what the actor did instead.
    */
  def assertLogged(substring: String): Unit =
    if !contains(substring) then throw missing(substring, "after the barrier")

  // The failure says what was logged instead (a CI failure otherwise leaves nothing: this capture prints nothing).
  private def missing(substring: String, when: String): AssertionError =
    val recent = lines.takeRight(25)
    new AssertionError(
      s"no log line containing '$substring' $when; the last ${recent.size} captured:\n  " + recent.mkString("\n  ")
    )

  override def close(): Unit =
    SnapLogCapture.unregister(this)
    logger.setAdditive(previousAdditive)

object SnapLogCapture:
  private val Package = "com.chipprbots.ethereum.blockchain.sync.snap"

  private val open = new CopyOnWriteArraySet[SnapLogCapture]()

  private def inPackage(name: String): Boolean =
    name == Package || (name.startsWith(Package) && name.charAt(Package.length) == '.')

  /** Records the package's DEBUG-and-up lines in every open capture. A call without a message is a level check
    * (`isDebugEnabled`, or Pekko's `Slf4jLoggingFilter` before it publishes): it is accepted, so that the line follows.
    */
  private object Tap extends TurboFilter:
    override def decide(
        marker: Marker,
        logger: LogbackLogger,
        level: Level,
        format: String,
        params: Array[AnyRef],
        t: Throwable
    ): FilterReply =
      if open.isEmpty || level == null || !level.isGreaterOrEqual(Level.DEBUG) || !inPackage(logger.getName) then
        FilterReply.NEUTRAL
      else
        if format != null then
          val line = MessageFormatter.arrayFormat(format, params).getMessage
          open.forEach(_.record(level, line))
        FilterReply.ACCEPT

  private def context: LoggerContext = LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext]

  // Installed while a capture is open (and again if a `LoggerContext.reset()` dropped it), removed after the last.
  private def register(capture: SnapLogCapture): Unit = synchronized {
    open.add(capture)
    val ctx = context
    if !ctx.getTurboFilterList.contains(Tap) then
      Tap.setContext(ctx)
      Tap.start()
      ctx.addTurboFilter(Tap)
  }

  private def unregister(capture: SnapLogCapture): Unit = synchronized {
    open.remove(capture)
    if open.isEmpty then context.getTurboFilterList.remove(Tap)
  }
