package com.chipprbots.ethereum.testing

import scala.jdk.CollectionConverters.*

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger as LogbackLogger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory

/** Captures the events a named logger emits while a block runs, at every level (the logger is set to TRACE meanwhile),
  * so a test can pin the level a line is logged at rather than only whether it is logged.
  */
object LogCapture:

  /** The result of `f` and the events logged to `loggerName` (and below it) while it ran. */
  def capture[A](loggerName: String)(f: => A): (A, List[ILoggingEvent]) =
    val logger = LoggerFactory.getLogger(loggerName).asInstanceOf[LogbackLogger]
    val appender = new ListAppender[ILoggingEvent]
    val previous = logger.getLevel
    appender.start()
    logger.addAppender(appender)
    logger.setLevel(Level.TRACE)
    val result =
      try f
      finally
        logger.setLevel(previous)
        logger.detachAppender(appender)
    (result, appender.list.asScala.toList)

  def capture[A](clazz: Class[?])(f: => A): (A, List[ILoggingEvent]) = capture(clazz.getName)(f)

  /** The messages of the events at INFO or above: what a node running with the INFO level shows. */
  def atInfoOrAbove(events: List[ILoggingEvent]): List[String] =
    events.filter(_.getLevel.isGreaterOrEqual(Level.INFO)).map(e => s"${e.getLevel} ${e.getFormattedMessage}")
