package com.chipprbots.ethereum.console

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.Appender
import ch.qos.logback.core.ConsoleAppender

import scala.jdk.CollectionConverters.*

import org.slf4j.LoggerFactory

import com.chipprbots.ethereum.utils.{Logger => FukuiiLogger}

/** Log suppression mechanism using Logback.
  *
  * This class provides the ability to suppress console log output while the TUI is active, while still allowing
  * file-based logging to continue. This is achieved by temporarily disabling the console appender.
  */
class TuiLogSuppressor extends FukuiiLogger:

  @volatile private var suppressedAppenders: Map[String, Appender[ILoggingEvent]] = Map.empty
  @volatile private var isSuppressed: Boolean = false

  /** Suppress console logs. File-based logging continues.
    *
    * @return
    *   true if suppression was successfully applied
    */
  def suppressConsoleLogs(): Boolean =
    if isSuppressed then
      log.debug("Console logs already suppressed")
      return true

    try
      val loggerContext = LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext]
      val rootLogger = loggerContext.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)

      // Find and detach console appenders
      val appenders = rootLogger.iteratorForAppenders().asScala.toList
      val consoleAppenders = appenders.collect { case appender: ConsoleAppender[ILoggingEvent] =>
        log.debug(s"Suppressing console appender: ${appender.getName}")
        (appender.getName, appender)
      }

      consoleAppenders.foreach { case (name, appender) =>
        rootLogger.detachAppender(appender)
        suppressedAppenders = suppressedAppenders + (name -> appender)
      }

      if consoleAppenders.nonEmpty then
        isSuppressed = true
        log.debug(s"Suppressed ${consoleAppenders.size} console appenders")
        true
      else
        log.debug("No console appenders found to suppress")
        false
    catch
      case e: Exception =>
        log.error(s"Failed to suppress console logs: ${e.getMessage}", e)
        false

  /** Restore console logs to normal operation.
    *
    * @return
    *   true if restoration was successful
    */
  def restoreConsoleLogs(): Boolean =
    if !isSuppressed then
      log.debug("Console logs not suppressed, nothing to restore")
      return true

    try
      val loggerContext = LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext]
      val rootLogger = loggerContext.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)

      // Reattach previously suppressed console appenders
      suppressedAppenders.foreach { case (name, appender) =>
        log.debug(s"Restoring console appender: $name")
        rootLogger.addAppender(appender)
      }

      suppressedAppenders = Map.empty
      isSuppressed = false
      log.debug("Console appenders restored")
      true
    catch
      case e: Exception =>
        log.error(s"Failed to restore console logs: ${e.getMessage}", e)
        false

  /** Check if console logs are currently suppressed. */
  def isConsoleSuppressed: Boolean = isSuppressed

  /** Get the names of currently suppressed appenders. */
  def suppressedAppenderNames: Set[String] = suppressedAppenders.keySet

object TuiLogSuppressor:
  /** Create a new log suppressor instance. */
  def apply(): TuiLogSuppressor = new TuiLogSuppressor()
