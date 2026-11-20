package com.chipprbots.ethereum.blockchain.sync.snap

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger as LogbackLogger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import org.slf4j.LoggerFactory

/** Captures every log line (DEBUG and up) of the `snap` package while open, without printing it (spec 016 S0d).
  *
  * `logback-test.xml` keeps the root at ERROR, so `LoggingTestKit` sees nothing below ERROR. Some characterization
  * tests need lines below that: the `syncing` catch-all ("Unhandled message in syncing state") is DEBUG, and the
  * recovery replay's pause is an INFO from a Future. This appender is attached to the package logger (the controller's
  * `ctx.log` and `asyncLog` both log under it), with additivity off so nothing reaches the console, and `close()`
  * restores the logger. Logging is synchronous, so a line logged while handling a message is captured before the
  * controller handles its next message.
  */
final class SnapLogCapture extends AutoCloseable:
  private val logger =
    LoggerFactory.getLogger("com.chipprbots.ethereum.blockchain.sync.snap").asInstanceOf[LogbackLogger]
  private val previousLevel: Level = logger.getLevel // null: inherited
  private val previousAdditive: Boolean = logger.isAdditive

  private val captured = new ConcurrentLinkedQueue[String]()
  private val arrivals = new LinkedBlockingQueue[String]()

  private val appender = new AppenderBase[ILoggingEvent]:
    override def append(event: ILoggingEvent): Unit =
      val line = event.getFormattedMessage
      captured.add(line)
      arrivals.put(line)

  appender.setContext(logger.getLoggerContext)
  appender.start()
  logger.addAppender(appender)
  logger.setLevel(Level.DEBUG)
  logger.setAdditive(false)

  /** Every line captured so far, oldest first. */
  def lines: List[String] = captured.asScala.toList

  def count(substring: String): Int = lines.count(_.contains(substring))

  def contains(substring: String): Boolean = count(substring) > 0

  def clear(): Unit =
    captured.clear()
    arrivals.clear()

  /** Wait (up to `max`) for a line containing `substring` that arrived since the last `clear`/`awaitLine` match. */
  def awaitLine(substring: String, max: FiniteDuration = 10.seconds): String =
    val deadline = System.nanoTime() + max.toNanos
    @annotation.tailrec
    def loop(): String =
      val remaining = deadline - System.nanoTime()
      if remaining <= 0 then throw missing(substring, s"within $max")
      val line = arrivals.poll(remaining, TimeUnit.NANOSECONDS)
      if line == null then throw missing(substring, s"within $max")
      else if line.contains(substring) then line
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
    logger.detachAppender(appender)
    appender.stop()
    logger.setLevel(previousLevel)
    logger.setAdditive(previousAdditive)
