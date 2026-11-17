package com.chipprbots.ethereum.utils

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.util.ContextInitializer
import org.slf4j.LoggerFactory

/** Re-reads the logger configuration after the system properties that feed it have changed.
  *
  * `App` sets its logger up when the launcher starts, from the configuration of that moment (the launcher's
  * `-Dconfig.file=app.conf`, whose network is the default one). The Ephemery resolver then moves `fukuii.datadir`, and
  * the log must follow it: [[reload]] drops logback's state and runs the usual auto-configuration again (honouring
  * `-Dlogback.configurationFile`), which asks [[ConfigPropertyDefiner]] again. It reads no `Config` object.
  */
object LogbackReload:

  /** Resets the logger context and configures it again from the same file. A backend other than logback is left alone.
    */
  def reload(): Unit =
    LoggerFactory.getILoggerFactory match
      case context: LoggerContext =>
        context.reset()
        new ContextInitializer(context).autoConfig()
      case _ => ()

  /** Detaches every file appender from the root logger: the console keeps working, and nothing more is written to a log
    * file. For a command (`cli ephemery-fetch --out`) whose output must not land in a node's datadir.
    */
  def consoleOnly(): Unit =
    LoggerFactory.getILoggerFactory match
      case context: LoggerContext =>
        val root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
        val files = collection.mutable.ListBuffer.empty[String]
        val it = root.iteratorForAppenders()
        while it.hasNext do
          val appender = it.next()
          if appender.isInstanceOf[ch.qos.logback.core.FileAppender[?]] then files += appender.getName
        files.foreach { name =>
          Option(root.getAppender(name)).foreach(_.stop())
          root.detachAppender(name)
        }
      case _ => ()
