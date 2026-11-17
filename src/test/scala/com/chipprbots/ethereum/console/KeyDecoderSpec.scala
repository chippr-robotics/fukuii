package com.chipprbots.ethereum.console

import org.scalacheck.Gen
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import com.chipprbots.ethereum.testing.Tags.*

/** The key decoder (spec 029 FR-003, FR-004, SC-003; research §3 E3 and E4): a sequence is a key or nothing, never the
  * characters it is made of.
  */
class KeyDecoderSpec extends AnyFlatSpec with Matchers with ScalaCheckPropertyChecks:

  private val esc = "\u001b"
  private def keys(input: String): Seq[Key] = KeyDecoder.decode(input)

  "The decoder" should "read printable characters, Enter, Tab and Backspace as they are" taggedAs (UnitTest) in {
    keys("q") shouldBe Seq(Key.Char('q'))
    keys("Q:/") shouldBe Seq(Key.Char('Q'), Key.Char(':'), Key.Char('/'))
    keys("\r") shouldBe Seq(Key.Enter)
    keys("\n") shouldBe Seq(Key.Enter)
    keys("\t") shouldBe Seq(Key.Tab)
    keys("\u007f") shouldBe Seq(Key.Backspace)
    keys("\b") shouldBe Seq(Key.Backspace)
  }

  it should "drop other control characters" taggedAs (UnitTest) in {
    keys("\u0001\u0003\u001a") shouldBe empty
    keys("a\u0000b") shouldBe Seq(Key.Char('a'), Key.Char('b'))
  }

  it should "read the arrows in the normal and the application form" taggedAs (UnitTest) in {
    keys(s"$esc[A$esc[B$esc[C$esc[D") shouldBe Seq(Key.Up, Key.Down, Key.Right, Key.Left)
    keys(s"${esc}OA${esc}OB${esc}OC${esc}OD") shouldBe Seq(Key.Up, Key.Down, Key.Right, Key.Left)
  }

  it should "read Home, End, Page Up, Page Down, Shift-Tab and the function keys" taggedAs (UnitTest) in {
    keys(s"$esc[H$esc[F$esc[1~$esc[4~${esc}OH${esc}OF") shouldBe
      Seq(Key.Home, Key.End, Key.Home, Key.End, Key.Home, Key.End)
    keys(s"$esc[5~$esc[6~$esc[Z") shouldBe Seq(Key.PageUp, Key.PageDown, Key.BackTab)
    keys(s"${esc}OP${esc}OS$esc[11~$esc[15~$esc[24~") shouldBe Seq(Key.F(1), Key.F(4), Key.F(1), Key.F(5), Key.F(12))
  }

  it should "read a key with a modifier as the key" taggedAs (UnitTest) in {
    keys(s"$esc[1;5A$esc[1;2D$esc[5;2~") shouldBe Seq(Key.Up, Key.Left, Key.PageUp)
  }

  it should "read a lone Esc as the Esc key once the input goes quiet" taggedAs (UnitTest) in {
    keys(esc) shouldBe Seq(Key.Esc)
    val decoder = new KeyDecoder()
    val (afterEsc, none) = decoder.step(KeyDecoder.State.Ground, '\u001b')
    none shouldBe empty
    val (ground, escKey) = decoder.idle(afterEsc)
    escKey shouldBe Seq(Key.Esc)
    // Then `d`, later: the `d` key, never Left arrow (research E3).
    decoder.step(ground, 'D')._2 shouldBe Seq(Key.Char('D'))
  }

  it should "read a sequence that arrives a character at a time as one key" taggedAs (UnitTest) in {
    val decoder = new KeyDecoder()
    val (s1, k1) = decoder.step(KeyDecoder.State.Ground, '\u001b')
    val (s2, k2) = decoder.step(s1, '[')
    val (s3, k3) = decoder.step(s2, 'D')
    (k1 ++ k2 ++ k3) shouldBe Seq(Key.Left)
    s3 shouldBe KeyDecoder.State.Ground
  }

  it should "read Esc twice as Esc, then whatever the second starts" taggedAs (UnitTest) in {
    keys(s"$esc$esc") shouldBe Seq(Key.Esc, Key.Esc)
    keys(s"$esc$esc[A") shouldBe Seq(Key.Esc, Key.Up)
  }

  it should "drop Alt and a key, both" taggedAs (UnitTest) in {
    keys(s"${esc}q") shouldBe empty
    keys(s"${esc}dx") shouldBe Seq(Key.Char('x'))
  }

  it should "drop a CSI sequence it does not know, whole" taggedAs (UnitTest) in {
    // A cell-size report: with the number keys bound to views, its digits must not switch views (research E4).
    keys(s"$esc[6;20;10t") shouldBe empty
    keys(s"$esc[?62;4c") shouldBe empty
    keys(s"$esc[12;40R") shouldBe empty
    keys(s"$esc[6;20;10tz") shouldBe Seq(Key.Char('z'))
  }

  it should "drop a terminal's string reply, whole, at ESC \\ or at BEL" taggedAs (UnitTest) in {
    keys(s"${esc}_Gi=31;OK$esc\\") shouldBe empty
    keys(s"${esc}_Gi=31;OK$esc\\y") shouldBe Seq(Key.Char('y'))
    keys(s"${esc}P>|kitty(0.35.0)$esc\\") shouldBe empty
    keys(s"$esc]11;rgb:0000/0000/0000\u0007q") shouldBe Seq(Key.Char('q'))
  }

  it should "drop an unfinished sequence when the input goes quiet" taggedAs (UnitTest) in {
    keys(s"$esc[") shouldBe empty
    keys(s"$esc[1;") shouldBe empty
    keys(s"${esc}O") shouldBe empty
    keys(s"${esc}_Gi=31") shouldBe empty
  }

  it should "give up on a sequence longer than its bound, and read keys again after it" taggedAs (UnitTest) in {
    keys(s"$esc[" + "1" * (KeyDecoder.MaxCsi + 1)) shouldBe empty
    val long = s"${esc}_" + "x" * (KeyDecoder.MaxString + 1)
    // The character that crosses the bound ends the sequence; what follows is typed text.
    keys(long + "q").lastOption shouldBe Some(Key.Char('q'))
  }

  it should "abandon a CSI sequence at a character that cannot be part of one" taggedAs (UnitTest) in {
    keys(s"$esc[1\u0001q") shouldBe Seq(Key.Char('q'))
  }

  "A bracketed paste" should "arrive as one key, with its control characters removed" taggedAs (UnitTest) in {
    keys(s"$esc[200~q\ry$esc[201~") shouldBe Seq(Key.Paste("qy"))
    keys(s"$esc[200~block 12$esc[201~x") shouldBe Seq(Key.Paste("block 12"), Key.Char('x'))
  }

  it should "keep an Esc inside the paste out of the keys" taggedAs (UnitTest) in {
    keys(s"$esc[200~a$esc[Ab$esc[201~") shouldBe Seq(Key.Paste("a[Ab"))
  }

  it should "be dropped whole when it is longer than the bound: its tail is not typed" taggedAs (UnitTest) in {
    keys(s"$esc[200~" + "q" * (KeyDecoder.MaxPaste + 10) + s"$esc[201~") shouldBe empty
    keys(s"$esc[200~" + "q" * (KeyDecoder.MaxPaste + 10) + s"$esc[201~x") shouldBe Seq(Key.Char('x'))
  }

  it should "be given up on when its end never comes, and the keyboard works again" taggedAs (UnitTest) in {
    val decoder = new KeyDecoder()
    val open = s"$esc[200~abc".foldLeft(KeyDecoder.State.Ground: KeyDecoder.State)((s, c) => decoder.step(s, c)._1)
    decoder.idle(open)._1 shouldBe open
    decoder.abandon(open) shouldBe KeyDecoder.State.Ground
    decoder.abandon(KeyDecoder.State.Escape) shouldBe KeyDecoder.State.Escape
  }

  it should "yield nothing while its end has not arrived" taggedAs (UnitTest) in {
    keys(s"$esc[200~q") shouldBe empty
  }

  // ---- SC-003: no stray keys ------------------------------------------------------------------------------------------

  private val typed: Gen[(String, Seq[Key])] =
    Gen.oneOf("abcdefghijklmnopqrstuvwxyz0123456789:/?+-<>,.[]".toSeq).map(c => (c.toString, Seq(Key.Char(c))))
  private val named: Gen[(String, Seq[Key])] = Gen.oneOf(
    (s"$esc[A", Seq(Key.Up)),
    (s"${esc}OB", Seq(Key.Down)),
    (s"$esc[5~", Seq(Key.PageUp)),
    (s"$esc[Z", Seq(Key.BackTab)),
    ("\r", Seq(Key.Enter)),
    ("\t", Seq(Key.Tab)),
    ("\u007f", Seq(Key.Backspace)),
    (s"${esc}OP", Seq(Key.F(1)))
  )
  private val digits: Gen[String] = Gen.chooseNum(0, 99999).map(_.toString)
  private val reply: Gen[(String, Seq[Key])] = Gen
    .oneOf(
      digits.flatMap(a => digits.map(b => s"$esc[6;$a;${b}t")),
      digits.flatMap(a => digits.map(b => s"$esc[$a;${b}R")),
      digits.map(a => s"$esc[?$a;1;2c"),
      digits.map(a => s"${esc}_Gi=$a;OK$esc\\"),
      digits.map(a => s"${esc}_Gi=$a;ENOENT:q d 1 2 3$esc\\"),
      Gen.alphaNumStr.map(s => s"${esc}P>|$s$esc\\"),
      Gen.alphaNumStr.map(s => s"$esc]11;$s\u0007"),
      digits.map(a => s"$esc[$a;${a}q")
    )
    .map(s => (s, Seq.empty[Key]))
  private val piece: Gen[(String, Seq[Key])] = Gen.frequency(4 -> typed, 3 -> named, 5 -> reply)

  "Any mix of keys, sequences and replies" should "dispatch exactly the keys that were typed (SC-003)" taggedAs (
    UnitTest
  ) in {
    forAll(Gen.listOf(piece), minSuccessful(10000)) { pieces =>
      keys(pieces.map(_._1).mkString) shouldBe pieces.flatMap(_._2)
    }
  }

  it should "never dispatch a key out of a sequence cut short" taggedAs (UnitTest) in {
    forAll(Gen.listOf(piece), reply, Gen.chooseNum(1, 40), minSuccessful(2000)) { (pieces, last, cut) =>
      val truncated = last._1.take(cut.min(last._1.length - 1).max(1))
      // A cut after a lone ESC is the Esc key: that is what a lone ESC means.
      val expected = pieces.flatMap(_._2) ++ (if truncated == esc then Seq(Key.Esc) else Nil)
      keys(pieces.map(_._1).mkString + truncated) shouldBe expected
    }
  }
