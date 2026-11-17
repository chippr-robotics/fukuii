package com.chipprbots.ethereum

import scala.jdk.CollectionConverters.*

import ch.qos.logback.classic.Logger as LogbackLogger
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.slf4j.LoggerFactory

import com.chipprbots.ethereum.console.*
import com.chipprbots.ethereum.testing.Tags.*

/** Tests for TuiLogSuppressor - log suppression mechanism using Logback.
  *
  * Note: These tests focus on the API and basic functionality. Actual log suppression behavior depends on Logback
  * configuration and is best tested through integration tests.
  */
class TuiLogSuppressorSpec extends AnyFlatSpec with Matchers:

  "TuiLogSuppressor" should "be created via factory method" taggedAs (UnitTest) in {
    val suppressor = TuiLogSuppressor()
    suppressor should not be null
  }

  it should "report not suppressed initially" taggedAs (UnitTest) in {
    val suppressor = TuiLogSuppressor()
    suppressor.isConsoleSuppressed shouldBe false
  }

  it should "have empty suppressed appender names initially" taggedAs (UnitTest) in {
    val suppressor = TuiLogSuppressor()
    suppressor.suppressedAppenderNames shouldBe empty
  }

  it should "allow multiple suppress calls" taggedAs (UnitTest) in {
    val suppressor = TuiLogSuppressor()

    // First call - result depends on whether console appenders exist
    val _ = suppressor.suppressConsoleLogs()

    // Second call should also succeed (idempotent - returns true if already suppressed or no appenders)
    val secondResult = suppressor.suppressConsoleLogs()

    // Second call is always true (either already suppressed, or no-op with no appenders)
    secondResult shouldBe true
  }

  it should "allow restore when not suppressed" taggedAs (UnitTest) in {
    val suppressor = TuiLogSuppressor()

    // Restore without suppress should be a no-op
    val result = suppressor.restoreConsoleLogs()
    result shouldBe true
    suppressor.isConsoleSuppressed shouldBe false
  }

  it should "restore after suppress" taggedAs (UnitTest) in {
    val suppressor = TuiLogSuppressor()

    suppressor.suppressConsoleLogs()
    val result = suppressor.restoreConsoleLogs()

    result shouldBe true
    suppressor.isConsoleSuppressed shouldBe false
    suppressor.suppressedAppenderNames shouldBe empty
  }

  it should "track suppression state" taggedAs (UnitTest) in {
    val suppressor = TuiLogSuppressor()

    suppressor.isConsoleSuppressed shouldBe false

    // suppressConsoleLogs returns true only if console appenders were found and suppressed
    // In test environment, there may be no console appenders, so isConsoleSuppressed
    // will only be true if appenders were actually suppressed
    val wasSupressed = suppressor.suppressConsoleLogs()
    suppressor.isConsoleSuppressed shouldBe wasSupressed

    suppressor.restoreConsoleLogs()
    suppressor.isConsoleSuppressed shouldBe false
  }

  // Spec 027 SC-009, C-11: the TUI's log ring is a separate appender, attached once the console is suppressed and
  // detached before it is restored; the suppressor never sees it, and the root logger ends as it began.
  it should "leave the root logger's appenders as they were around the TUI's log ring (spec 027 SC-009)" taggedAs (
    UnitTest
  ) in {
    val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).asInstanceOf[LogbackLogger]
    def names: List[String] = root.iteratorForAppenders().asScala.map(_.getName).toList.sorted
    val before = names
    val suppressor = TuiLogSuppressor()
    val ring = TuiLogRingAppender.of(TuiConfig.default)

    suppressor.suppressConsoleLogs()
    ring.attach()
    names should contain(TuiLogRingAppender.Name)
    ring.detach()
    suppressor.suppressedAppenderNames should not contain TuiLogRingAppender.Name
    suppressor.restoreConsoleLogs()

    names shouldBe before
    ring.isStarted shouldBe false
  }
