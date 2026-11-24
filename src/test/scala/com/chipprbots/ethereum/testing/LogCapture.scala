package com.chipprbots.ethereum.testing

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet

import scala.jdk.CollectionConverters.*

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger as LogbackLogger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.LoggingEvent
import ch.qos.logback.classic.turbo.TurboFilter
import ch.qos.logback.core.spi.FilterReply
import org.slf4j.LoggerFactory
import org.slf4j.Marker

/** Captures the events a named logger (and every logger below it) emits, at every level, without attaching an appender
  * (B-468).
  *
  * An appender added to a logger while another thread logs through it can be lost: logback's appender list
  * (`COWArrayList`) caches an array, and a logging thread that rebuilds the cache from the old list as the appender is
  * added marks that stale copy fresh, so the new appender receives nothing (B-256). Specs that attach a `ListAppender`
  * while actors log through the same logger (network, sync, JSON-RPC) inherit that race. The events are taken instead
  * by a logback turbo filter, which every logging call consults on the logging thread before the level check, and whose
  * list is a plain `CopyOnWriteArrayList`, with no cache: a capture is in effect from the moment it is opened.
  *
  * A call is captured when it passes the calling logger's effective level, as a `ListAppender` on the logger would have
  * received it (a spec chooses what it captures by the level it sets); the filter then answers NEUTRAL, so the logging
  * itself is unchanged. `capture` lowers the scope logger to TRACE while it runs, as it always did. One filter serves
  * every open capture, so captures nest. As `SnapLogCapture` does for the `snap` package (spec 016).
  */
object LogCapture:

  /** An open capture: `list` is what was logged in `scope` since it opened, oldest first; `close()` ends it. The `list`
    * is a live `java.util.List`, as a `ListAppender`'s, so a spec that read `appender.list` reads `capture.list`.
    */
  final class Capture private[LogCapture] (val scope: String) extends AutoCloseable:
    val list: java.util.List[ILoggingEvent] = new CopyOnWriteArrayList[ILoggingEvent]()

    /** The events captured so far, oldest first. */
    def events: List[ILoggingEvent] = list.asScala.toList

    /** The formatted messages captured so far, oldest first. */
    def messages: List[String] = events.map(_.getFormattedMessage)

    private[LogCapture] def open(): Capture =
      register(this)
      this

    override def close(): Unit = unregister(this)

  /** Start capturing what `loggerName`, and every logger below it, logs. Close it (it is `AutoCloseable`). */
  def open(loggerName: String): Capture = new Capture(loggerName).open()

  def open(clazz: Class[?]): Capture = open(clazz.getName)

  /** The result of `f` and the events logged to `loggerName` (and below it) while it ran. */
  def capture[A](loggerName: String)(f: => A): (A, List[ILoggingEvent]) =
    val logger = LoggerFactory.getLogger(loggerName).asInstanceOf[LogbackLogger]
    val previous = logger.getLevel
    logger.setLevel(Level.TRACE)
    val capture = open(loggerName)
    val result =
      try f
      finally
        capture.close()
        logger.setLevel(previous)
    (result, capture.events)

  def capture[A](clazz: Class[?])(f: => A): (A, List[ILoggingEvent]) = capture(clazz.getName)(f)

  /** The messages of the events at INFO or above: what a node running with the INFO level shows. */
  def atInfoOrAbove(events: List[ILoggingEvent]): List[String] =
    events.filter(_.getLevel.isGreaterOrEqual(Level.INFO)).map(e => s"${e.getLevel} ${e.getFormattedMessage}")

  private val openCaptures = new CopyOnWriteArraySet[Capture]()

  // Below `scope` as logback's hierarchy has it: it separates names at '.' and at '$' (an object's logger, `Foo$`, is a
  // child of `Foo`'s), so an appender on `scope` received both.
  private def inScope(scope: String, name: String): Boolean =
    name == scope || (name.startsWith(scope) && name.length > scope.length && {
      val c = name.charAt(scope.length)
      c == '.' || c == '$'
    })

  /** Records each in-scope call that passes its logger's level in every open capture of its scope; answers NEUTRAL, so
    * logback decides as it would without it. A call without a message (a level check) records nothing.
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
      if openCaptures.isEmpty || level == null || format == null || !level.isGreaterOrEqual(logger.getEffectiveLevel)
      then FilterReply.NEUTRAL
      else
        val name = logger.getName
        val capturing = openCaptures.asScala.filter(capture => inScope(capture.scope, name))
        if capturing.nonEmpty then
          val event = new LoggingEvent(classOf[LogbackLogger].getName, logger, level, format, t, params)
          // Its thread name, formatted message and MDC are read now, on the logging thread, not when a test reads it.
          event.prepareForDeferredProcessing()
          capturing.foreach(_.list.add(event))
        FilterReply.NEUTRAL

  private def context: LoggerContext = LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext]

  // Installed while a capture is open (and again if a `LoggerContext.reset()` dropped it), removed after the last.
  private def register(capture: Capture): Unit = synchronized {
    openCaptures.add(capture)
    val ctx = context
    if !ctx.getTurboFilterList.contains(Tap) then
      Tap.setContext(ctx)
      Tap.start()
      ctx.addTurboFilter(Tap)
  }

  private def unregister(capture: Capture): Unit = synchronized {
    openCaptures.remove(capture)
    if openCaptures.isEmpty then context.getTurboFilterList.remove(Tap)
  }
