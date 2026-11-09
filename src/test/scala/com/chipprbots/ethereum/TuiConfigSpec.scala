package com.chipprbots.ethereum

import com.typesafe.config.ConfigException
import com.typesafe.config.ConfigFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.console.*
import com.chipprbots.ethereum.testing.Tags.*

/** Tests for TuiConfig - configuration for the TUI module. */
class TuiConfigSpec extends AnyFlatSpec with Matchers:

  "TuiConfig" should "have sensible default values" taggedAs (UnitTest) in {
    val config = TuiConfig.default
    config.updateIntervalMs shouldBe 1000L
    config.shutdownTimeoutMs shouldBe 1000L
    config.showLogo shouldBe true
    config.showProgressBar shouldBe true
    config.showNodeSettings shouldBe true
    config.suppressConsoleLogs shouldBe true
    config.glyphs shouldBe GlyphChoice.Auto
    config.noColor shouldBe false
    // Spec 027 T051, research §7.5.
    config.animationIntervalMs shouldBe 250L
    config.statusPollIntervalMs shouldBe 1000L
    config.reducedMotion shouldBe false
    config.splashMs shouldBe 3000L
  }

  it should "allow creating minimal configuration" taggedAs (UnitTest) in {
    val config = TuiConfig.minimal
    config.updateIntervalMs shouldBe 500L
    config.splashMs shouldBe 0L
    config.showLogo shouldBe false
    config.showNodeSettings shouldBe false
  }

  it should "support custom configuration" taggedAs (UnitTest) in {
    val config = TuiConfig(
      updateIntervalMs = 200,
      shutdownTimeoutMs = 2000,
      showLogo = false,
      showProgressBar = false,
      showNodeSettings = true,
      suppressConsoleLogs = false
    )
    config.updateIntervalMs shouldBe 200L
    config.shutdownTimeoutMs shouldBe 2000L
    config.showLogo shouldBe false
    config.showProgressBar shouldBe false
    config.showNodeSettings shouldBe true
    config.suppressConsoleLogs shouldBe false
  }

  it should "use object constants for default values" taggedAs (UnitTest) in {
    TuiConfig.DefaultUpdateIntervalMs shouldBe 1000L
    TuiConfig.DefaultShutdownTimeoutMs shouldBe 1000L
    TuiConfig.DefaultAnimationIntervalMs shouldBe 250L
    TuiConfig.DefaultStatusPollIntervalMs shouldBe 1000L
    TuiConfig.DefaultSplashMs shouldBe 3000L
  }

  // Spec 027 FR-024, C-12: the TUI's settings come from `fukuii.tui`.
  "TuiConfig.fromConfig" should "read fukuii.tui.glyphs" taggedAs (UnitTest) in {
    def glyphs(value: String) = TuiConfig.fromConfig(ConfigFactory.parseString(s"glyphs = $value")).glyphs
    glyphs("emoji") shouldBe GlyphChoice.Emoji
    glyphs("ascii") shouldBe GlyphChoice.Ascii
    glyphs("auto") shouldBe GlyphChoice.Auto
    glyphs("ASCII") shouldBe GlyphChoice.Ascii
  }

  it should "default to auto when glyphs is absent" taggedAs (UnitTest) in {
    TuiConfig.fromConfig(ConfigFactory.empty()) shouldBe TuiConfig.default
  }

  it should "fail with an error naming fukuii.tui.glyphs on an invalid value" taggedAs (UnitTest) in {
    val e = the[ConfigException.BadValue] thrownBy TuiConfig.fromConfig(ConfigFactory.parseString("glyphs = fancy"))
    e.getMessage should include("fukuii.tui.glyphs")
    e.getMessage should include("fancy")
    e.getMessage should include("emoji, ascii, auto")
  }

  it should "ship glyphs = auto in conf/base/tui.conf" taggedAs (UnitTest) in {
    val shipped = ConfigFactory.load().getConfig("fukuii.tui")
    shipped.getString("glyphs") shouldBe "auto"
    TuiConfig.fromConfig(shipped).glyphs shouldBe GlyphChoice.Auto
  }

  // Spec 027 T051: motion, the two paces and the splash.
  it should "read the frame paces, reduced motion and the splash length" taggedAs (UnitTest) in {
    val c = TuiConfig.fromConfig(
      ConfigFactory.parseString(
        "animation-interval-ms = 200, status-poll-interval-ms = 2000, reduced-motion = true, splash-ms = 0"
      )
    )
    (c.animationIntervalMs, c.statusPollIntervalMs, c.reducedMotion, c.splashMs) shouldBe ((200L, 2000L, true, 0L))
  }

  it should "ship the paces, motion and splash of research §7.5 in conf/base/tui.conf" taggedAs (UnitTest) in {
    val shipped = ConfigFactory.load().getConfig("fukuii.tui")
    shipped.getLong("animation-interval-ms") shouldBe 250L
    shipped.getLong("status-poll-interval-ms") shouldBe 1000L
    shipped.getBoolean("reduced-motion") shouldBe false
    shipped.getLong("splash-ms") shouldBe 3000L
    val c = TuiConfig.fromConfig(shipped)
    (c.animationIntervalMs, c.statusPollIntervalMs, c.reducedMotion, c.splashMs) shouldBe ((250L, 1000L, false, 3000L))
  }

  it should "fail with an error naming the key on an interval that is not positive, or a negative splash" taggedAs (
    UnitTest
  ) in {
    for (key, value) <- Seq("animation-interval-ms" -> "0", "status-poll-interval-ms" -> "-5", "splash-ms" -> "-1") do
      val e = the[ConfigException.BadValue] thrownBy TuiConfig.fromConfig(ConfigFactory.parseString(s"$key = $value"))
      e.getMessage should include(s"fukuii.tui.$key")
    noException should be thrownBy TuiConfig.fromConfig(ConfigFactory.parseString("splash-ms = 0"))
  }

  "TuiConfig.paceMs" should "be the animation interval while something moves and the update interval otherwise" taggedAs (
    UnitTest
  ) in {
    val c = TuiConfig.default.copy(animationIntervalMs = 100, updateIntervalMs = 900)
    c.paceMs(animated = true) shouldBe 100L
    c.paceMs(animated = false) shouldBe 900L
  }

  "TuiConfig.splashTicks" should "be splashMs / animationIntervalMs, 2 under reduced motion, 0 with no splash" taggedAs (
    UnitTest
  ) in {
    TuiConfig.default.splashTicks shouldBe 12L
    TuiConfig.default.copy(splashMs = 1000, animationIntervalMs = 100).splashTicks shouldBe 10L
    TuiConfig.default.copy(reducedMotion = true).splashTicks shouldBe 2L
    TuiConfig.default.copy(splashMs = 0).splashTicks shouldBe 0L
    TuiConfig.default.copy(splashMs = 0, reducedMotion = true).splashTicks shouldBe 0L
    TuiConfig.default.motion shouldBe Motion.Full
    TuiConfig.default.copy(reducedMotion = true).motion shouldBe Motion.Reduced
  }

  "TuiConfig.load" should "apply the environment to fukuii.tui" taggedAs (UnitTest) in {
    val fukuii = ConfigFactory.parseString("tui { glyphs = emoji }")
    TuiConfig.load(fukuii, TuiEnv.from(Map("LANG" -> "C"), Map.empty)).glyphSet shouldBe GlyphSet.Emoji
    TuiConfig.load(fukuii, TuiEnv.from(Map("FUKUII_TUI_GLYPHS" -> "ascii"), Map.empty)).glyphSet shouldBe GlyphSet.Ascii
    val auto = ConfigFactory.parseString("tui { glyphs = auto }")
    TuiConfig.load(auto, TuiEnv.from(Map("LANG" -> "C"), Map.empty)).glyphSet shouldBe GlyphSet.Ascii
    TuiConfig.load(auto, TuiEnv.from(Map("LANG" -> "en_US.UTF-8"), Map.empty)).glyphSet shouldBe GlyphSet.Emoji
    TuiConfig.load(auto, TuiEnv.from(Map("NO_COLOR" -> "1"), Map.empty)).noColor shouldBe true
    TuiConfig.load(auto, TuiEnv.from(Map("NO_COLOR" -> ""), Map.empty)).noColor shouldBe false
  }

  // Slice 1 review W3: an operator's full-copy config written before tui.conf existed has no `tui` block.
  it should "fall back to the defaults when the node config has no tui block" taggedAs (UnitTest) in {
    val fullCopy = ConfigFactory.parseString("datadir = /var/lib/fukuii")
    val utf8 = TuiEnv.from(Map("LANG" -> "en_US.UTF-8"), Map.empty)
    TuiConfig.load(fullCopy, utf8) shouldBe utf8.applyTo(TuiConfig.default)
    TuiConfig
      .load(fullCopy, TuiEnv.from(Map("FUKUII_TUI_GLYPHS" -> "ascii"), Map.empty))
      .glyphSet shouldBe GlyphSet.Ascii
    TuiConfig.load(fullCopy, TuiEnv.from(Map("NO_COLOR" -> "1"), Map.empty)).noColor shouldBe true
  }

  "TuiConfig.glyphSet" should "draw emoji for an unresolved auto, and ASCII only when asked" taggedAs (UnitTest) in {
    TuiConfig.default.glyphSet shouldBe GlyphSet.Emoji
    TuiConfig.default.copy(glyphs = GlyphChoice.Emoji).glyphSet shouldBe GlyphSet.Emoji
    TuiConfig.default.copy(glyphs = GlyphChoice.Ascii).glyphSet shouldBe GlyphSet.Ascii
  }
