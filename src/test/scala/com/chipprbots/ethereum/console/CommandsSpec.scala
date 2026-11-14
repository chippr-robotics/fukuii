package com.chipprbots.ethereum.console

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

/** The prompt's commands (spec 029 FR-007, contracts/operator.md): parsing, completion, the nearest command. */
class CommandsSpec extends AnyFlatSpec with Matchers:

  "parse" should "read a view's name in any case, with spaces around it" taggedAs (UnitTest) in {
    for v <- TuiView.values do
      Commands.parse(v.title) shouldBe Command.OpenView(v)
      Commands.parse(s"  ${v.title.toUpperCase}  ") shouldBe Command.OpenView(v)
  }

  it should "read the fixed commands and their arguments" taggedAs (UnitTest) in {
    Commands.parse("quit") shouldBe Command.Quit
    Commands.parse("detach") shouldBe Command.Detach
    Commands.parse("help") shouldBe Command.Help
    Commands.parse("peer 0xab12") shouldBe Command.OpenPeer("0xab12")
    Commands.parse("glyphs ASCII") shouldBe Command.Glyphs(GlyphSet.Ascii)
    Commands.parse("glyphs emoji") shouldBe Command.Glyphs(GlyphSet.Emoji)
  }

  it should "say how a command is used when its argument is missing or wrong" taggedAs (UnitTest) in {
    Commands.parse("peer") shouldBe a[Command.Usage]
    Commands.parse("glyphs") shouldBe a[Command.Usage]
    Commands.parse("glyphs braille") shouldBe a[Command.Usage]
  }

  it should "name the nearest commands for a word it does not know" taggedAs (UnitTest) in {
    Commands.parse("mempol") shouldBe Command.Unknown("mempol", Seq("mempool"))
    Commands.parse("stor") shouldBe Command.Unknown("stor", Seq("storage"))
    Commands.parse("xyzzy") shouldBe Command.Unknown("xyzzy", Nil)
    Commands.parse("   ") shouldBe Command.Unknown("", Nil)
  }

  "complete" should "finish a word only one command starts with, and go as far as several agree" taggedAs (UnitTest) in {
    Commands.complete("m") shouldBe "mempool"
    Commands.complete("ov") shouldBe "overview"
    Commands.complete("gl") shouldBe "glyphs "
    Commands.complete("p") shouldBe "peer" // peers, peer
    Commands.complete("s") shouldBe "s" // storage, system
    Commands.complete("st") shouldBe "storage"
    Commands.complete("zz") shouldBe "zz"
    Commands.complete("peer 0x") shouldBe "peer 0x" // an argument is not completed
    Commands.complete("") shouldBe ""
  }

  "Every view" should "be a command" taggedAs (UnitTest) in {
    for v <- TuiView.values do Commands.Words should contain(v.title.toLowerCase)
    Commands.Words.distinct shouldBe Commands.Words
  }

  "distance" should "count edits" taggedAs (UnitTest) in {
    Commands.distance("logs", "logs") shouldBe 0
    Commands.distance("log", "logs") shouldBe 1
    Commands.distance("pers", "peers") shouldBe 1
    Commands.distance("", "ops") shouldBe 3
  }
