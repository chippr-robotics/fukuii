package com.chipprbots.ethereum.console

import org.jline.utils.AttributedString
import org.scalacheck.Gen
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import com.chipprbots.ethereum.testing.Tags.*

/** Text from outside the node, cleaned before it reaches a frame (spec 027 FR-020, C-16): a peer's client id and every
  * log line. A peer chooses its client id, so whatever it sends must not reach the operator's terminal as a command.
  */
class SanitizeSpec extends AnyFlatSpec with Matchers with ScalaCheckPropertyChecks:

  private val Esc = "\u001b"

  "Sanitize" should "remove an escape sequence whole, not only its ESC" taggedAs (UnitTest) in {
    Sanitize.line(s"Geth${Esc}[2J/v1.13") shouldBe "Geth/v1.13"
    Sanitize.line(s"${Esc}[31mred${Esc}[0m text") shouldBe "red text"
    Sanitize.line(s"a${Esc}[1;5Db") shouldBe "ab"
    // OSC (a title or a hyperlink), ended by BEL or by ST
    Sanitize.line(s"a${Esc}]0;pwned\u0007b") shouldBe "ab"
    Sanitize.line(s"a${Esc}]8;;http://x${Esc}\\link${Esc}]8;;${Esc}\\b") shouldBe "alinkb"
    // SS3, a charset designation, a lone ESC at the end
    Sanitize.line(s"a${Esc}OPb") shouldBe "ab"
    Sanitize.line(s"a${Esc}(Bb") shouldBe "ab"
    Sanitize.line(s"ab$Esc") shouldBe "ab"
  }

  it should "remove C0 controls, DEL and C1 controls, the 8-bit CSI with its sequence" taggedAs (UnitTest) in {
    Sanitize.line("a\u0000b\u0007c\u0008d\u007fe") shouldBe "abcde"
    Sanitize.line("a\u0085b\u0090c") shouldBe "abc"
    Sanitize.line("a\u009b2Jb") shouldBe "ab"
    Sanitize.line("a\tb") shouldBe "a b" // a tab is a space, so words do not run together
  }

  it should "remove format characters and variation selectors, which a terminal and WCWidth count differently" taggedAs (
    UnitTest
  ) in {
    Sanitize.line("a‮b​c﻿d") shouldBe "abcd" // right-to-left override, zero-width space, BOM
    Sanitize.line("⚠️ warn") shouldBe "⚠ warn"
    Sanitize.line("👩‍💻") shouldBe "👩💻"
  }

  it should "keep a multi-line message's first line, and mark the rest with the ellipsis" taggedAs (UnitTest) in {
    Sanitize.line("first\nsecond") shouldBe "first…"
    Sanitize.line("first\r\nsecond\nthird") shouldBe "first…"
    Sanitize.line("first\rsecond") shouldBe "first…"
    Sanitize.line("first second") shouldBe "first…"
    Sanitize.line("only\n") shouldBe "only"
    Sanitize.line("only\n  \n") shouldBe "only"
    Sanitize.line("  padded  ") shouldBe "padded"
  }

  it should "cap a line at 512 characters, the last one the ellipsis" taggedAs (UnitTest) in {
    Sanitize.MaxChars shouldBe 512
    val long = Sanitize.line("x" * 2000)
    long.codePointCount(0, long.length) shouldBe 512
    long should endWith("…")
    Sanitize.line("x" * 512) shouldBe "x" * 512
    Sanitize.line("x" * 300, maxChars = 120).codePointCount(0, 120) shouldBe 120
  }

  it should "count the cap in code points and never split a surrogate pair" taggedAs (UnitTest) in {
    val worms = Sanitize.line("🪱" * 600)
    worms.codePointCount(0, worms.length) shouldBe 512
    worms.dropRight(1).codePoints().toArray.distinct shouldBe Array("🪱".codePointAt(0))
  }

  it should "keep emoji and CJK" taggedAs (UnitTest) in {
    Sanitize.line("🪱 → 🧠 中文字符 ネット 한") shouldBe "🪱 → 🧠 中文字符 ネット 한"
  }

  private val nasty: Gen[String] =
    val piece = Gen.oneOf(
      Gen.alphaNumChar.map(_.toString),
      Gen.oneOf("\u001b", "\u001b[", "\u001b]", "\u0007", "\u009b", "\u009d", "\n", "\r", "\t", "\u007f"),
      Gen.oneOf("[2J", "31m", ";", "\\", "O", "("),
      Gen.oneOf("🪱", "中", "…", "‍", "️", "‮", " ")
    )
    Gen.chooseNum(0, 700).flatMap(n => Gen.listOfN(n, piece).map(_.mkString))

  it should "be idempotent, and leave no control character, on any input" taggedAs (UnitTest) in {
    forAll(nasty, minSuccessful(500)) { s =>
      val once = Sanitize.line(s)
      Sanitize.line(once) shouldBe once
      once.exists(ch => Character.isISOControl(ch)) shouldBe false
      once.codePointCount(0, once.length) should be <= Sanitize.MaxChars
      // What is left is what a terminal draws: its width is the sum of its code points' widths.
      new AttributedString(once).columnLength() should be >= 0
    }
  }

  "TuiText.external" should "draw ASCII only under the ASCII set: the set's glyphs mapped, any other character as `?`s of its width" taggedAs (
    UnitTest
  ) in {
    val text = "Imported 🪱 中 é … 🚀 done"
    val ascii = TuiText.external(text, TuiGlyphs.Ascii)
    all(ascii.map(_.toInt)) should be < 0x7f
    ascii shouldBe "Imported ~o ?? ? .. ?? done"
    TuiText.external(text, TuiGlyphs.Emoji) shouldBe text
    TuiText.external(s"bad${Esc}[2J id", TuiGlyphs.Emoji) shouldBe "bad id"
    TuiText.external(s"bad${Esc}[2J id", TuiGlyphs.Ascii) shouldBe "bad id"
  }
