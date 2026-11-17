package com.chipprbots.ethereum.console

import org.jline.utils.AttributedString
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

/** The frame's two width rules: `fitToWidth` (cut and pad) and `fit` (a cut shown with the ellipsis). */
class TuiTextSpec extends AnyFlatSpec with Matchers:

  private def s(text: String) = new AttributedString(text)

  "TuiText.fit" should "pass a row that fits, keeping the last column blank" taggedAs (UnitTest) in {
    TuiText.fit(s("abcdefghi"), 10, TuiGlyphs.Emoji).toString shouldBe "abcdefghi "
    TuiText.fit(s("abc"), 10, TuiGlyphs.Emoji).toString shouldBe "abc       "
  }

  it should "cut a row of exactly the width, which would fill the last column, with the ellipsis" taggedAs (
    UnitTest
  ) in {
    TuiText.fit(s("abcdefghij"), 10, TuiGlyphs.Emoji).toString shouldBe "abcdefgh… "
    TuiText.fit(s("abcdefghijklmnop"), 10, TuiGlyphs.Emoji).toString shouldBe "abcdefgh… "
  }

  it should "let a cell whose gap column is reserved by its caller fill the width exactly" taggedAs (UnitTest) in {
    TuiText.fit(s("abcdefghij"), 10, TuiGlyphs.Emoji, reserveLast = false).toString shouldBe "abcdefghij"
    TuiText.fit(s("abcdefghijk"), 10, TuiGlyphs.Emoji, reserveLast = false).toString shouldBe "abcdefghi…"
  }

  it should "use the ASCII set's two-column ellipsis" taggedAs (UnitTest) in {
    TuiText.fit(s("abcdefghijklmnop"), 10, TuiGlyphs.Ascii).toString shouldBe "abcdefg.. "
    TuiText.fit(s("abcdefghijklmnop"), 10, TuiGlyphs.Ascii).columnLength() shouldBe 10
  }

  it should "drop a wide glyph that would straddle the cut, and still be exactly the width" taggedAs (UnitTest) in {
    // "abcdefg" is 7 columns; the worm would take columns 7 and 8, past the 8 left before the ellipsis.
    val cut = TuiText.fit(s("abcdefg🪱xyz"), 10, TuiGlyphs.Emoji)
    cut.columnLength() shouldBe 10
    cut.toString shouldBe "abcdefg … "
    for width <- 1 to 20; set <- Seq(TuiGlyphs.Emoji, TuiGlyphs.Ascii) do
      TuiText.fit(s("🪱中文 abc 🧠" * 3), width, set).columnLength() shouldBe width
  }

  "TuiText.fitToWidth" should "return a line already the width as it is" taggedAs (UnitTest) in {
    val line = s("exactly10!")
    (TuiText.fitToWidth(line, 10) eq line) shouldBe true
    TuiText.fitToWidth(s("ab🪱"), 3).toString shouldBe "ab " // the worm straddles the edge: dropped, padded
  }
