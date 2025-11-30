package com.chipprbots.ethereum

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.console._
import com.chipprbots.ethereum.testing.Tags._

/** Tests for TuiConfig - configuration for the TUI module. */
class TuiConfigSpec extends AnyFlatSpec with Matchers {

  "TuiConfig" should "have sensible default values" taggedAs (UnitTest) in {
    val config = TuiConfig.default
    config.updateIntervalMs shouldBe 1000L
    config.bannerDisplayDurationMs shouldBe 1000L
    config.shutdownTimeoutMs shouldBe 1000L
    config.showLogo shouldBe true
    config.showProgressBar shouldBe true
    config.showNodeSettings shouldBe true
    config.suppressConsoleLogs shouldBe true
  }

  it should "allow creating minimal configuration" taggedAs (UnitTest) in {
    val config = TuiConfig.minimal
    config.updateIntervalMs shouldBe 500L
    config.bannerDisplayDurationMs shouldBe 0L
    config.showLogo shouldBe false
    config.showNodeSettings shouldBe false
  }

  it should "support custom configuration" taggedAs (UnitTest) in {
    val config = TuiConfig(
      updateIntervalMs = 200,
      bannerDisplayDurationMs = 500,
      shutdownTimeoutMs = 2000,
      showLogo = false,
      showProgressBar = false,
      showNodeSettings = true,
      suppressConsoleLogs = false
    )
    config.updateIntervalMs shouldBe 200L
    config.bannerDisplayDurationMs shouldBe 500L
    config.shutdownTimeoutMs shouldBe 2000L
    config.showLogo shouldBe false
    config.showProgressBar shouldBe false
    config.showNodeSettings shouldBe true
    config.suppressConsoleLogs shouldBe false
  }

  it should "use object constants for default values" taggedAs (UnitTest) in {
    TuiConfig.DefaultUpdateIntervalMs shouldBe 1000L
    TuiConfig.DefaultBannerDisplayDurationMs shouldBe 1000L
    TuiConfig.DefaultShutdownTimeoutMs shouldBe 1000L
  }
}
