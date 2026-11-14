package com.chipprbots.ethereum

import ch.qos.logback.classic.Level
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
    config.glyphs shouldBe GlyphSet.Emoji
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
  "TuiSettings.fromConfig" should "read fukuii.tui.read-only, false when absent (spec 029 FR-053)" taggedAs (
    UnitTest
  ) in {
    TuiSettings.fromConfig(ConfigFactory.parseString("read-only = true")).readOnly shouldBe true
    TuiSettings.fromConfig(ConfigFactory.parseString("read-only = false")).readOnly shouldBe false
    TuiSettings.fromConfig(ConfigFactory.empty()).readOnly shouldBe false
    a[ConfigException.WrongType] should be thrownBy TuiSettings.fromConfig(
      ConfigFactory.parseString("read-only = maybe")
    )
  }

  it should "read fukuii.tui.glyphs" taggedAs (UnitTest) in {
    def glyphs(value: String) = TuiSettings.fromConfig(ConfigFactory.parseString(s"glyphs = $value")).glyphs
    glyphs("emoji") shouldBe GlyphChoice.Emoji
    glyphs("ascii") shouldBe GlyphChoice.Ascii
    glyphs("auto") shouldBe GlyphChoice.Auto
    glyphs("ASCII") shouldBe GlyphChoice.Ascii
  }

  it should "default to auto when glyphs is absent" taggedAs (UnitTest) in {
    TuiSettings.fromConfig(ConfigFactory.empty()) shouldBe TuiSettings()
  }

  it should "fail with an error naming fukuii.tui.glyphs on an invalid value" taggedAs (UnitTest) in {
    val e = the[ConfigException.BadValue] thrownBy TuiSettings.fromConfig(ConfigFactory.parseString("glyphs = fancy"))
    e.getMessage should include("fukuii.tui.glyphs")
    e.getMessage should include("fancy")
    e.getMessage should include("emoji, ascii, auto")
  }

  it should "ship glyphs = auto in conf/base/tui.conf" taggedAs (UnitTest) in {
    val shipped = ConfigFactory.load().getConfig("fukuii.tui")
    shipped.getString("glyphs") shouldBe "auto"
    TuiSettings.fromConfig(shipped).glyphs shouldBe GlyphChoice.Auto
  }

  // Spec 027 T051: motion, the two paces and the splash.
  it should "read the frame paces, reduced motion and the splash length" taggedAs (UnitTest) in {
    val c = TuiSettings.fromConfig(
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
    val c = TuiSettings.fromConfig(shipped)
    (c.animationIntervalMs, c.statusPollIntervalMs, c.reducedMotion, c.splashMs) shouldBe ((250L, 1000L, false, 3000L))
  }

  it should "fail with an error naming the key on an interval that is not positive, or a negative splash" taggedAs (
    UnitTest
  ) in {
    for (key, value) <- Seq("animation-interval-ms" -> "0", "status-poll-interval-ms" -> "-5", "splash-ms" -> "-1") do
      val e = the[ConfigException.BadValue] thrownBy TuiSettings.fromConfig(ConfigFactory.parseString(s"$key = $value"))
      e.getMessage should include(s"fukuii.tui.$key")
    noException should be thrownBy TuiSettings.fromConfig(ConfigFactory.parseString("splash-ms = 0"))
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
    TuiConfig.load(fukuii, TuiEnv.from(Map("LANG" -> "C"), Map.empty)).glyphs shouldBe GlyphSet.Emoji
    TuiConfig.load(fukuii, TuiEnv.from(Map("FUKUII_TUI_GLYPHS" -> "ascii"), Map.empty)).glyphs shouldBe GlyphSet.Ascii
    val auto = ConfigFactory.parseString("tui { glyphs = auto }")
    TuiConfig.load(auto, TuiEnv.from(Map("LANG" -> "C"), Map.empty)).glyphs shouldBe GlyphSet.Ascii
    TuiConfig.load(auto, TuiEnv.from(Map("LANG" -> "en_US.UTF-8"), Map.empty)).glyphs shouldBe GlyphSet.Emoji
    TuiConfig.load(auto, TuiEnv.from(Map("NO_COLOR" -> "1"), Map.empty)).noColor shouldBe true
    TuiConfig.load(auto, TuiEnv.from(Map("NO_COLOR" -> ""), Map.empty)).noColor shouldBe false
  }

  // Slice 1 review W3: an operator's full-copy config written before tui.conf existed has no `tui` block.
  it should "fall back to the defaults when the node config has no tui block" taggedAs (UnitTest) in {
    val fullCopy = ConfigFactory.parseString("datadir = /var/lib/fukuii")
    val utf8 = TuiEnv.from(Map("LANG" -> "en_US.UTF-8"), Map.empty)
    TuiConfig.load(fullCopy, utf8) shouldBe utf8.applyTo(TuiSettings())
    TuiConfig
      .load(fullCopy, TuiEnv.from(Map("FUKUII_TUI_GLYPHS" -> "ascii"), Map.empty))
      .glyphs shouldBe GlyphSet.Ascii
    TuiConfig.load(fullCopy, TuiEnv.from(Map("NO_COLOR" -> "1"), Map.empty)).noColor shouldBe true
  }

  // B-124 (prism, slice 1): `TuiConfig.glyphs` held `auto` before the environment applied and a set after, and
  // `glyphSet` read an unresolved `auto` as emoji. The configured choice and the resolved set are now two types.
  "TuiConfig.glyphs" should "be a resolved glyph set, which has no auto" taggedAs (UnitTest) in {
    val set: GlyphSet = TuiConfig.default.glyphs
    set shouldBe GlyphSet.Emoji
    GlyphSet.values.map(_.toString) should not contain "Auto"
    TuiSettings().glyphs shouldBe GlyphChoice.Auto // the configured choice may still be auto
  }

  it should "come from auto only through the locale" taggedAs (UnitTest) in {
    val auto = TuiSettings(glyphs = GlyphChoice.Auto)
    TuiEnv.from(Map("LANG" -> "C"), Map.empty).applyTo(auto).glyphs shouldBe GlyphSet.Ascii
    TuiEnv.from(Map("LANG" -> "en_US.UTF-8"), Map.empty).applyTo(auto).glyphs shouldBe GlyphSet.Emoji
  }

  it should "carry every fukuii.tui setting into the config the TUI runs with" taggedAs (UnitTest) in {
    val settings = TuiSettings(GlyphChoice.Ascii, reducedMotion = true, 200L, 2000L, 0L)
    val c = TuiEnv.from(Map("LANG" -> "en_US.UTF-8"), Map.empty).applyTo(settings)
    (c.glyphs, c.reducedMotion, c.animationIntervalMs, c.statusPollIntervalMs, c.splashMs) shouldBe
      ((GlyphSet.Ascii, true, 200L, 2000L, 0L))
  }

  // prism, slice 2 review: every non-default `fukuii.tui` value reaches the config the TUI runs with.
  "TuiConfig.load" should "carry every non-default fukuii.tui value through" taggedAs (UnitTest) in {
    val fukuii = ConfigFactory.parseString(
      """tui { glyphs = ascii, reduced-motion = true, animation-interval-ms = 120, status-poll-interval-ms = 3000,
        |splash-ms = 0 }""".stripMargin
    )
    val c = TuiConfig.load(fukuii, TuiEnv.from(Map("LANG" -> "en_US.UTF-8"), Map.empty))
    (c.glyphs, c.reducedMotion, c.animationIntervalMs, c.statusPollIntervalMs, c.splashMs) shouldBe
      ((GlyphSet.Ascii, true, 120L, 3000L, 0L))
    // ... and the fields fukuii.tui does not set keep their defaults.
    c.copy(
      glyphs = GlyphSet.Emoji,
      reducedMotion = false,
      animationIntervalMs = 250,
      statusPollIntervalMs = 1000,
      splashMs = 3000
    ) shouldBe TuiConfig.default
  }

  // prism, slice 2 review: a 1 ms frame pace or poll interval would spin a core.
  "TuiSettings.fromConfig" should "refuse frame paces below 50 ms and poll intervals below 100 ms" taggedAs (
    UnitTest
  ) in {
    for (key, value) <- Seq("animation-interval-ms" -> "49", "status-poll-interval-ms" -> "99") do
      val e = the[ConfigException.BadValue] thrownBy TuiSettings.fromConfig(ConfigFactory.parseString(s"$key = $value"))
      e.getMessage should include(s"fukuii.tui.$key")
    TuiSettings.fromConfig(
      ConfigFactory.parseString("animation-interval-ms = 50, status-poll-interval-ms = 100")
    ) shouldBe
      TuiSettings(animationIntervalMs = 50, statusPollIntervalMs = 100)
  }

  // ---- Spec 027 slice 4 (T100): the log ring's size and level (Q4, C-13) ----------------------------------------------

  it should "ship log-ring-size = 1000 and log-level = INFO in conf/base/tui.conf" taggedAs (UnitTest) in {
    val shipped = ConfigFactory.load().getConfig("fukuii.tui")
    shipped.getInt("log-ring-size") shouldBe 1000
    shipped.getString("log-level") shouldBe "INFO"
    val c = TuiSettings.fromConfig(shipped)
    (c.logRingSize, c.logLevel) shouldBe ((1000, Level.INFO))
    TuiConfig.default.logRingSize shouldBe 1000
    TuiConfig.default.logLevel shouldBe Level.INFO
  }

  it should "read the log ring's size and level, and carry them into the running config" taggedAs (UnitTest) in {
    val c = TuiSettings.fromConfig(ConfigFactory.parseString("log-ring-size = 200\nlog-level = debug"))
    (c.logRingSize, c.logLevel) shouldBe ((200, Level.DEBUG))
    val env = TuiEnv.from(Map.empty, Map.empty)
    val running = env.applyTo(c)
    (running.logRingSize, running.logLevel) shouldBe ((200, Level.DEBUG))
  }

  it should "fail with an error naming the key on an invalid log level or ring size" taggedAs (UnitTest) in {
    val level =
      the[ConfigException.BadValue] thrownBy TuiSettings.fromConfig(ConfigFactory.parseString("log-level = LOUD"))
    level.getMessage should include("fukuii.tui.log-level")
    level.getMessage should include("LOUD")
    val size =
      the[ConfigException.BadValue] thrownBy TuiSettings.fromConfig(ConfigFactory.parseString("log-ring-size = 0"))
    size.getMessage should include("fukuii.tui.log-ring-size")
  }

  it should "take a ring of up to 10,000 lines, and refuse a larger one" taggedAs (UnitTest) in {
    TuiConfig.MaxLogRingSize shouldBe 10_000
    TuiSettings.fromConfig(ConfigFactory.parseString("log-ring-size = 10000")).logRingSize shouldBe 10_000
    val tooMany =
      the[ConfigException.BadValue] thrownBy TuiSettings.fromConfig(ConfigFactory.parseString("log-ring-size = 10001"))
    tooMany.getMessage should include("fukuii.tui.log-ring-size")
    tooMany.getMessage should include("10001")
  }
