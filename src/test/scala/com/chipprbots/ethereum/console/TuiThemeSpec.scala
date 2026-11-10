package com.chipprbots.ethereum.console

import org.jline.utils.AttributedString
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

/** [[TuiTheme]]: one set of colour roles, and a monochrome theme that carries no colour (spec 027 FR-009, §5.1). */
class TuiThemeSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  private val tt = new TuiTestTerminal()
  override def afterAll(): Unit = tt.close()

  /** The colour SGR parameters a styled sample puts on the wire of an xterm-256color terminal. */
  private def colours(theme: TuiTheme): Seq[(String, Seq[String])] =
    theme.roles.map((name, style) =>
      name -> TuiTestTerminal.colourCodes(new AttributedString("x", style).toAnsi(tt.terminal))
    )

  "TuiTheme.of" should "select monochrome when NO_COLOR is set, whatever the terminal supports" taggedAs (UnitTest) in {
    TuiTheme.of(noColor = true, maxColors = 256) shouldBe TuiTheme.Mono
    TuiTheme.of(noColor = true, maxColors = 16_777_216) shouldBe TuiTheme.Mono
    TuiTheme.of(noColor = true, maxColors = 8) shouldBe TuiTheme.Mono
  }

  it should "select monochrome below 8 colours" taggedAs (UnitTest) in {
    TuiTheme.of(noColor = false, maxColors = 7) shouldBe TuiTheme.Mono
    TuiTheme.of(noColor = false, maxColors = 2) shouldBe TuiTheme.Mono
    TuiTheme.of(noColor = false, maxColors = 0) shouldBe TuiTheme.Mono
    TuiTheme.of(noColor = false, maxColors = -1) shouldBe TuiTheme.Mono
  }

  it should "select 16 colours from 8 to 255, and 256 colours from 256" taggedAs (UnitTest) in {
    TuiTheme.of(noColor = false, maxColors = 8) shouldBe TuiTheme.Ansi16
    TuiTheme.of(noColor = false, maxColors = 16) shouldBe TuiTheme.Ansi16
    TuiTheme.of(noColor = false, maxColors = 255) shouldBe TuiTheme.Ansi16
    TuiTheme.of(noColor = false, maxColors = 256) shouldBe TuiTheme.Ansi256
    TuiTheme.of(noColor = false, maxColors = 16_777_216) shouldBe TuiTheme.Ansi256
  }

  "TuiTheme.Mono" should "carry no foreground or background colour in any role" taggedAs (UnitTest) in {
    for (role, codes) <- colours(TuiTheme.Mono) do withClue(role)(codes shouldBe empty)
  }

  it should "mark health with bold and errors and the band with reverse video, and nothing else" taggedAs (UnitTest) in {
    val m = TuiTheme.Mono
    def wire(style: org.jline.utils.AttributedStyle) = new AttributedString("x", style).toAnsi(tt.terminal)
    // The SGR parameters of each style, and the exact bytes a monochrome row puts on the wire.
    (m.healthy.toAnsi, m.warn.toAnsi, m.value.toAnsi) shouldBe (("1", "1", "1"))
    (m.error.toAnsi, m.accent.toAnsi) shouldBe (("7", "7"))
    wire(m.healthy) shouldBe "\u001b[1mx\u001b[0m"
    wire(m.error) shouldBe "\u001b[7mx\u001b[0m"
    wire(m.accent) shouldBe "\u001b[7mx\u001b[0m"
    wire(m.label) shouldBe "x"
    wire(m.muted) shouldBe "x"
  }

  "TuiTheme.Ansi16 and Ansi256" should "colour the roles that §5.1 colours" taggedAs (UnitTest) in {
    for theme <- Seq(TuiTheme.Ansi16, TuiTheme.Ansi256) do
      val coloured = colours(theme).collect { case (role, codes) if codes.nonEmpty => role }.toSet
      withClue(theme.palette.toString)(
        (coloured should contain).allOf("healthy", "warn", "error", "accent", "label", "value", "logWarn", "logError")
      )
  }

  it should "name their palettes" taggedAs (UnitTest) in {
    (TuiTheme.Ansi16.palette, TuiTheme.Ansi256.palette, TuiTheme.Mono.palette) shouldBe
      ((Palette.Ansi16, Palette.Ansi256, Palette.Mono))
  }

  // NO_COLOR removes colour only: reduced motion is its own switch (FR-009, §4.5).
  "NO_COLOR" should "change nothing but the colour" taggedAs (UnitTest) in {
    val settings = TuiSettings()
    val env = TuiEnv.from(Map("NO_COLOR" -> "1", "LANG" -> "en_US.UTF-8"), Map.empty)
    val plain = TuiEnv.from(Map("LANG" -> "en_US.UTF-8"), Map.empty)
    env.applyTo(settings) shouldBe plain.applyTo(settings).copy(noColor = true)
    env.applyTo(settings).updateIntervalMs shouldBe TuiConfig.default.updateIntervalMs
  }
