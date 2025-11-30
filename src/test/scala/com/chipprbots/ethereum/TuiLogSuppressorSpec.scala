package com.chipprbots.ethereum

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.console._
import com.chipprbots.ethereum.testing.Tags._

/** Tests for TuiLogSuppressor - log suppression mechanism using Logback.
  *
  * Note: These tests focus on the API and basic functionality. Actual log suppression behavior depends on Logback
  * configuration and is best tested through integration tests.
  */
class TuiLogSuppressorSpec extends AnyFlatSpec with Matchers {

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

    // First call should work
    val firstResult = suppressor.suppressConsoleLogs()

    // Second call should also work (idempotent)
    val secondResult = suppressor.suppressConsoleLogs()

    // Both should succeed (second is a no-op if already suppressed)
    firstResult shouldBe true
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

    suppressor.suppressConsoleLogs()
    suppressor.isConsoleSuppressed shouldBe true

    suppressor.restoreConsoleLogs()
    suppressor.isConsoleSuppressed shouldBe false
  }
}
