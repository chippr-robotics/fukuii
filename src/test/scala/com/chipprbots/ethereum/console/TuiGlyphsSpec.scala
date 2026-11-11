package com.chipprbots.ethereum.console

import org.jline.utils.AttributedString
import org.jline.utils.WCWidth
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

/** [[TuiGlyphs]]: every glyph's declared width is the width JLine measures and terminals draw (spec 027 SC-006,
  * FR-008).
  */
class TuiGlyphsSpec extends AnyFlatSpec with Matchers:

  private val sets = Seq(TuiGlyphs.Emoji, TuiGlyphs.Ascii)

  private def codePoints(s: String): Seq[Int] = s.codePoints().toArray.toSeq

  "TuiGlyphs" should "declare, for every glyph in both sets, the sum of WCWidth over its code points" taggedAs (
    UnitTest
  ) in {
    for set <- sets; (name, glyph) <- set.all do
      withClue(s"${set.set} $name '${glyph.text}': ") {
        codePoints(glyph.text).map(WCWidth.wcwidth).sum shouldBe glyph.cols
        new AttributedString(glyph.text).columnLength() shouldBe glyph.cols
      }
  }

  // JLine's table and the terminal must agree, or a row that JLine measures as `width` wraps on screen. A code point
  // with the Emoji_Presentation property is drawn two columns wide by terminals.
  it should "contain no code point that terminals draw wider than JLine counts" taggedAs (UnitTest) in {
    for set <- sets; (name, glyph) <- set.all; cp <- codePoints(glyph.text) do
      if Character.isEmojiPresentation(cp) then withClue(s"${set.set} $name: ")(WCWidth.wcwidth(cp) shouldBe 2)
  }

  it should "pin the ambiguous-width glyphs to one column" taggedAs (UnitTest) in {
    val e = TuiGlyphs.Emoji
    Seq(
      e.diamond,
      e.bullet,
      e.warn,
      e.arrow,
      e.separator,
      e.timelineTrack,
      e.dot,
      e.ellipsis,
      e.up,
      e.down,
      e.steady,
      e.missing
    )
      .foreach(g => withClue(g.text)(g.cols shouldBe 1))
    e.diamond.text shouldBe "◆"
    e.bullet.text shouldBe "●"
    e.warn.text shouldBe "⚠"
    e.arrow.text shouldBe "▶"
  }

  it should "contain no variation selector U+FE0F" taggedAs (UnitTest) in {
    for set <- sets; (name, glyph) <- set.all do withClue(name)(codePoints(glyph.text) should not contain 0xfe0f)
  }

  it should "give the ellipsis one column in emoji and two in ASCII (C-15)" taggedAs (UnitTest) in {
    TuiGlyphs.Emoji.ellipsis shouldBe Glyph("…", 1)
    TuiGlyphs.Ascii.ellipsis shouldBe Glyph("..", 2)
  }

  it should "keep every other glyph the same width in both sets" taggedAs (UnitTest) in {
    val ascii = TuiGlyphs.Ascii.all.toMap
    for (name, glyph) <- TuiGlyphs.Emoji.all if !TuiGlyphs.VariableWidth.contains(name) do
      withClue(name)(ascii(name).cols shouldBe glyph.cols)
  }

  it should "fit every phase glyph in the four-column phase field of both sets (§5.2)" taggedAs (UnitTest) in {
    for set <- sets; g <- Seq(set.accounts, set.storage, set.healing, set.validating, set.done, set.idle) do
      withClue(s"${set.set} '${g.text}': ")(g.cols should be <= TuiGlyphs.PhaseCols)
    TuiGlyphs.VariableWidth shouldBe Set("accounts", "storage", "healing", "done", "ellipsis")
  }

  it should "use only printable ASCII in the ASCII set" taggedAs (UnitTest) in {
    for (name, glyph) <- TuiGlyphs.Ascii.all do
      withClue(name)(all(glyph.text.map(_.toInt)) should ((be >= 0x20).and(be < 0x7f)))
  }

  it should "draw the worm as ~o and the brain as @@ in ASCII" taggedAs (UnitTest) in {
    TuiGlyphs.Ascii.worm shouldBe Glyph("~o", 2)
    TuiGlyphs.Ascii.brain shouldBe Glyph("@@", 2)
    TuiGlyphs.Emoji.worm shouldBe Glyph("🪱", 2)
    TuiGlyphs.Emoji.brain shouldBe Glyph("🧠", 2)
  }

  it should "select a set by GlyphSet" taggedAs (UnitTest) in {
    TuiGlyphs.of(GlyphSet.Emoji) shouldBe TuiGlyphs.Emoji
    TuiGlyphs.of(GlyphSet.Ascii) shouldBe TuiGlyphs.Ascii
    TuiGlyphs.Emoji.set shouldBe GlyphSet.Emoji
    TuiGlyphs.Ascii.set shouldBe GlyphSet.Ascii
  }

  "GlyphChoice.parse" should "read ASCII and EMOJI under a Turkish default locale" taggedAs (UnitTest) in {
    TuiFixtures.withDefaultLocale(TuiFixtures.Turkish) {
      "EMOJI".toLowerCase shouldBe "emojı" // the trap: the default locale lower-cases I to a dotless ı
      GlyphChoice.parse("ASCII") shouldBe Some(GlyphChoice.Ascii)
      GlyphChoice.parse("EMOJI") shouldBe Some(GlyphChoice.Emoji)
      GlyphChoice.parse("AUTO") shouldBe Some(GlyphChoice.Auto)
    }
  }

  "TuiGlyphs.toAscii" should "map the log glyphs to their ASCII forms" taggedAs (UnitTest) in {
    TuiGlyphs.toAscii("🪱 🧠 ✓ ↑ ↓ → …") shouldBe "~o @@ + ^ v - .."
  }

  it should "map every emoji-set glyph and drop U+FE0F" taggedAs (UnitTest) in {
    val ascii = TuiGlyphs.Ascii.all.toMap
    for (name, glyph) <- TuiGlyphs.Emoji.all do withClue(name)(TuiGlyphs.toAscii(glyph.text) shouldBe ascii(name).text)
    TuiGlyphs.toAscii("⏸️ paused") shouldBe "⏸ paused"
  }

  it should "leave plain text alone" taggedAs (UnitTest) in {
    TuiGlyphs.toAscii("Imported block 21,884,102 in 22ms") shouldBe "Imported block 21,884,102 in 22ms"
    TuiGlyphs.toAscii("") shouldBe ""
  }
