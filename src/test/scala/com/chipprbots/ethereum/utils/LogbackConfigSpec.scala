package com.chipprbots.ethereum.utils

import java.nio.file.Files

import scala.jdk.CollectionConverters.*

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.core.ConsoleAppender
import ch.qos.logback.core.status.Status
import com.typesafe.config.ConfigFactory
import net.logstash.logback.encoder.LogstashEncoder
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

/** The shipped `logback.xml` (B-264). Any WARN or ERROR while logback reads it makes logback print its whole
  * configuration status, about 150 lines, on every start; and the console appender's encoder follows
  * `logging.json-output`.
  */
class LogbackConfigSpec extends AnyFlatSpec with Matchers:

  private val keys = Seq("logging.json-output", "logging.logs-dir", "logging.logs-level", "logging.logs-file")

  /** Reads the shipped file into a fresh context, with the four `logging.*` keys it asks for set as system properties
    * (and put back afterwards), so the test's own configuration does not decide them.
    */
  private def configured(json: Boolean)(check: LoggerContext => Unit): Unit =
    val logsDir = Files.createTempDirectory("logback-config-spec")
    val prior = keys.map(k => k -> Option(System.getProperty(k)))
    val values =
      Map(
        "logging.json-output" -> json.toString,
        "logging.logs-dir" -> logsDir.toString,
        "logging.logs-level" -> "INFO",
        "logging.logs-file" -> "spec"
      )
    values.foreach { case (k, v) => System.setProperty(k, v) }
    ConfigFactory.invalidateCaches()
    val context = new LoggerContext()
    try
      val configurator = new JoranConfigurator()
      configurator.setContext(context)
      val shipped = getClass.getResource("/logback.xml")
      shipped should not be null
      configurator.doConfigure(shipped)
      check(context)
    finally
      context.stop()
      prior.foreach {
        case (k, Some(v)) => System.setProperty(k, v)
        case (k, None)    => System.clearProperty(k)
      }
      ConfigFactory.invalidateCaches()
      Files.list(logsDir).iterator().asScala.foreach(Files.delete)
      Files.delete(logsDir)

  private def problems(context: LoggerContext): Seq[String] =
    context.getStatusManager.getCopyOfStatusList.asScala.toSeq.filter(_.getLevel >= Status.WARN).map(_.getMessage)

  private def consoleEncoder(context: LoggerContext): Any =
    context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).getAppender("CONSOLE") match
      case console: ConsoleAppender[?] => console.getEncoder
      case other                       => fail(s"the root logger's CONSOLE appender is $other")

  "logback.xml" should "be read with no warning and write plain text by default" taggedAs UnitTest in {
    configured(json = false) { context =>
      problems(context) shouldBe empty
      consoleEncoder(context) shouldBe a[PatternLayoutEncoder]
    }
  }

  it should "be read with no warning and write JSON when logging.json-output is true" taggedAs UnitTest in {
    configured(json = true) { context =>
      problems(context) shouldBe empty
      consoleEncoder(context) shouldBe a[LogstashEncoder]
    }
  }
