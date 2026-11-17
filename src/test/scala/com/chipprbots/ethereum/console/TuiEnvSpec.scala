package com.chipprbots.ethereum.console

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

/** [[TuiEnv]]: the TUI's environment overrides, parsed from an injected map (spec 027 FR-008, FR-009, FR-024, Q3). */
class TuiEnvSpec extends AnyFlatSpec with Matchers:

  private val utf8 = Map("LANG" -> "en_US.UTF-8")
  private def env(vars: (String, String)*): TuiEnv = TuiEnv.from(vars.toMap, Map.empty)
  private def resolved(e: TuiEnv, settings: TuiSettings = TuiSettings()): GlyphSet = e.applyTo(settings).glyphs

  "TuiEnv" should "read FUKUII_TUI_GLYPHS = ascii, emoji or auto, in any case" taggedAs (UnitTest) in {
    env("FUKUII_TUI_GLYPHS" -> "ascii").glyphs shouldBe Some(GlyphChoice.Ascii)
    env("FUKUII_TUI_GLYPHS" -> "emoji").glyphs shouldBe Some(GlyphChoice.Emoji)
    env("FUKUII_TUI_GLYPHS" -> "auto").glyphs shouldBe Some(GlyphChoice.Auto)
    env("FUKUII_TUI_GLYPHS" -> " ASCII ").glyphs shouldBe Some(GlyphChoice.Ascii)
    env().glyphs shouldBe None
    env("FUKUII_TUI_GLYPHS" -> "").glyphs shouldBe None
    env("FUKUII_TUI_GLYPHS" -> "ascii").warnings shouldBe empty
  }

  it should "let FUKUII_TUI_GLYPHS override fukuii.tui.glyphs" taggedAs (UnitTest) in {
    val ascii = TuiSettings(glyphs = GlyphChoice.Ascii)
    resolved(TuiEnv.from(utf8 + ("FUKUII_TUI_GLYPHS" -> "emoji"), Map.empty), ascii) shouldBe GlyphSet.Emoji
    resolved(TuiEnv.from(utf8 + ("FUKUII_TUI_GLYPHS" -> "ascii"), Map.empty)) shouldBe GlyphSet.Ascii
    resolved(TuiEnv.from(utf8, Map.empty), ascii) shouldBe GlyphSet.Ascii
  }

  it should "resolve auto to emoji under a UTF-8 locale and to ASCII otherwise (Q3)" taggedAs (UnitTest) in {
    resolved(env("LANG" -> "en_US.UTF-8")) shouldBe GlyphSet.Emoji
    resolved(env("LANG" -> "C.utf8")) shouldBe GlyphSet.Emoji
    resolved(env("LANG" -> "C")) shouldBe GlyphSet.Ascii
    resolved(env("LANG" -> "POSIX")) shouldBe GlyphSet.Ascii
    resolved(env("LANG" -> "en_US.ISO-8859-1")) shouldBe GlyphSet.Ascii
    resolved(env("LANG" -> "C", "FUKUII_TUI_GLYPHS" -> "auto")) shouldBe GlyphSet.Ascii
  }

  it should "read the locale from LC_ALL, then LC_CTYPE, then LANG, then sun.jnu.encoding" taggedAs (UnitTest) in {
    resolved(env("LC_ALL" -> "C", "LC_CTYPE" -> "en_US.UTF-8", "LANG" -> "en_US.UTF-8")) shouldBe GlyphSet.Ascii
    resolved(env("LC_ALL" -> "en_US.UTF-8", "LANG" -> "C")) shouldBe GlyphSet.Emoji
    resolved(env("LC_CTYPE" -> "C", "LANG" -> "en_US.UTF-8")) shouldBe GlyphSet.Ascii
    resolved(env("LC_CTYPE" -> "en_US.UTF-8", "LANG" -> "C")) shouldBe GlyphSet.Emoji
    // An empty variable is unset (POSIX), so the next one decides.
    resolved(env("LC_ALL" -> "", "LANG" -> "C")) shouldBe GlyphSet.Ascii
    resolved(TuiEnv.from(Map.empty, Map("sun.jnu.encoding" -> "UTF-8"))) shouldBe GlyphSet.Emoji
    resolved(TuiEnv.from(Map.empty, Map("sun.jnu.encoding" -> "ANSI_X3.4-1968"))) shouldBe GlyphSet.Ascii
    resolved(TuiEnv.from(Map("LANG" -> "C"), Map("sun.jnu.encoding" -> "UTF-8"))) shouldBe GlyphSet.Ascii
  }

  it should "take ASCII when nothing names the encoding" taggedAs (UnitTest) in {
    env().utf8Locale shouldBe false
    resolved(env()) shouldBe GlyphSet.Ascii
  }

  it should "warn about an invalid FUKUII_TUI_GLYPHS, naming the variable, the accepted values and the default" taggedAs (
    UnitTest
  ) in {
    val e = TuiEnv.from(utf8 + ("FUKUII_TUI_GLYPHS" -> "fancy"), Map.empty)
    e.glyphs shouldBe None
    e.warnings should have size 1
    val w = e.warnings.head
    w should include("FUKUII_TUI_GLYPHS")
    w should include("fancy")
    w should include("emoji, ascii, auto")
    w should include("fukuii.tui.glyphs")
    // ... and falls back to the configured value.
    resolved(e) shouldBe GlyphSet.Emoji
    resolved(e, TuiSettings(glyphs = GlyphChoice.Ascii)) shouldBe GlyphSet.Ascii
  }

  // no-color.org: NO_COLOR counts when it is present and not empty, whatever the value.
  it should "take NO_COLOR set to a non-empty value as no colour, and an empty one as unset" taggedAs (UnitTest) in {
    env("NO_COLOR" -> "1").noColor shouldBe true
    env("NO_COLOR" -> "false").noColor shouldBe true
    env("NO_COLOR" -> " ").noColor shouldBe true
    env("NO_COLOR" -> "").noColor shouldBe false
    env().noColor shouldBe false
    env("NO_COLOR" -> "1").applyTo(TuiSettings()).noColor shouldBe true
    env("NO_COLOR" -> "").applyTo(TuiSettings()).noColor shouldBe false
    env().applyTo(TuiSettings()).noColor shouldBe false
  }

  it should "parse FUKUII_TUI_GLYPHS and the locale the same under a Turkish default locale" taggedAs (UnitTest) in {
    TuiFixtures.withDefaultLocale(TuiFixtures.Turkish) {
      env("FUKUII_TUI_GLYPHS" -> "EMOJI").glyphs shouldBe Some(GlyphChoice.Emoji)
      env("FUKUII_TUI_GLYPHS" -> "ASCII").warnings shouldBe empty
      resolved(env("LANG" -> "tr_TR.utf8")) shouldBe GlyphSet.Emoji
      resolved(env("LANG" -> "tr_TR.ISO-8859-9")) shouldBe GlyphSet.Ascii
    }
  }

  it should "read the process environment and system properties" taggedAs (UnitTest) in {
    noException should be thrownBy TuiEnv.system()
  }

  // Spec 027 T051, FR-012, FR-013: FUKUII_TUI_MOTION and FUKUII_TUI_NO_SPLASH.
  it should "read FUKUII_TUI_MOTION = reduced or full, overriding fukuii.tui.reduced-motion" taggedAs (UnitTest) in {
    env("FUKUII_TUI_MOTION" -> "reduced").reducedMotion shouldBe Some(true)
    env("FUKUII_TUI_MOTION" -> " FULL ").reducedMotion shouldBe Some(false)
    env().reducedMotion shouldBe None
    env("FUKUII_TUI_MOTION" -> "").reducedMotion shouldBe None
    env("FUKUII_TUI_MOTION" -> "reduced").applyTo(TuiSettings()).reducedMotion shouldBe true
    env("FUKUII_TUI_MOTION" -> "full").applyTo(TuiSettings(reducedMotion = true)).reducedMotion shouldBe false
    env().applyTo(TuiSettings(reducedMotion = true)).reducedMotion shouldBe true
  }

  it should "warn about an invalid FUKUII_TUI_MOTION, naming the variable and the accepted values" taggedAs (UnitTest) in {
    val e = env("FUKUII_TUI_MOTION" -> "slow")
    e.reducedMotion shouldBe None
    e.warnings should have size 1
    e.warnings.head should include("FUKUII_TUI_MOTION")
    e.warnings.head should include("slow")
    e.warnings.head should include("full, reduced")
    e.warnings.head should include("fukuii.tui.reduced-motion")
  }

  it should "turn the splash off when FUKUII_TUI_NO_SPLASH is set to a non-empty value" taggedAs (UnitTest) in {
    env("FUKUII_TUI_NO_SPLASH" -> "1").noSplash shouldBe true
    env("FUKUII_TUI_NO_SPLASH" -> "").noSplash shouldBe false
    env().noSplash shouldBe false
    env("FUKUII_TUI_NO_SPLASH" -> "1").applyTo(TuiSettings()).splashMs shouldBe 0L
    env().applyTo(TuiSettings()).splashMs shouldBe TuiConfig.default.splashMs
  }

  it should "set read-only when FUKUII_TUI_READ_ONLY is non-empty, and never unset it (spec 029 FR-053)" taggedAs (
    UnitTest
  ) in {
    env("FUKUII_TUI_READ_ONLY" -> "1").applyTo(TuiSettings()).readOnly shouldBe true
    env("FUKUII_TUI_READ_ONLY" -> "").applyTo(TuiSettings()).readOnly shouldBe false
    env().applyTo(TuiSettings()).readOnly shouldBe false
    // The variable adds the restriction; leaving it unset does not lift the configured one.
    env().applyTo(TuiSettings(readOnly = true)).readOnly shouldBe true
    env("FUKUII_TUI_READ_ONLY" -> "").applyTo(TuiSettings(readOnly = true)).readOnly shouldBe true
  }

  it should "leave motion alone under NO_COLOR (§4.5)" taggedAs (UnitTest) in {
    env("NO_COLOR" -> "1").applyTo(TuiSettings()).reducedMotion shouldBe false
  }
