package com.chipprbots.ethereum.console

import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.charset.StandardCharsets

import org.jline.terminal.impl.ExternalTerminal
import org.jline.utils.AttributedCharSequence.ForceMode
import org.jline.utils.AttributedString
import org.jline.utils.AttributedStyle
import org.jline.utils.WCWidth
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

/** What spec 029's design assumes of JLine (research §3, experiments E1, E2 and E5), pinned so that a JLine upgrade
  * that changes one of them fails here, by name, and not in a frame.
  */
class JLineAssumptionsSpec extends AnyFlatSpec with Matchers:

  private val placeholder = new String(Character.toChars(0x10eeee))

  "A Kitty image placeholder cell (E1)" should "measure one column, and its row and column marks none" taggedAs (
    UnitTest
  ) in {
    WCWidth.wcwidth(0x10eeee) shouldBe 1
    for mark <- Seq(0x0305, 0x030d, 0x030e, 0x0310, 0x0312, 0x033d, 0x033e, 0x033f, 0x0346, 0x034a) do
      withClue(f"U+$mark%04X: ")(WCWidth.wcwidth(mark) shouldBe 0)
    val cells = new AttributedString(placeholder + "̅̅" + placeholder + placeholder)
    cells.columnLength() shouldBe 3
    cells.columnSubSequence(0, 1).toString shouldBe placeholder + "̅̅" // a cell keeps its marks
  }

  "An indexed colour (E2)" should "be written as the index, exactly, from 16 up" taggedAs (UnitTest) in {
    def ansi(style: AttributedStyle) = new AttributedString("x", style).toAnsi(256, ForceMode.None)
    ansi(AttributedStyle.DEFAULT.foreground(42)) should include("38;5;42m")
    ansi(AttributedStyle.DEFAULT.foreground(255)) should include("38;5;255m")
    ansi(AttributedStyle.DEFAULT.foreground(46).background(21)) should include("38;5;46;48;5;21m")
  }

  "An RGB colour (E2)" should "be rounded to the palette on a 256-colour terminal: the art uses indices" taggedAs (
    UnitTest
  ) in {
    val rgb = new AttributedString("x", AttributedStyle.DEFAULT.foreground(0x12, 0x34, 0x56))
    rgb.toAnsi(256, ForceMode.None) should include("38;5;")
    (rgb.toAnsi(256, ForceMode.None) should not).include("38;2;")
    rgb.toAnsi(0x1000000, ForceMode.None) should include("38;2;18;52;86m")
  }

  "A graphics command (E5)" should "pass through the terminal's writer unchanged" taggedAs (UnitTest) in {
    val out = new ByteArrayOutputStream()
    val terminal =
      new ExternalTerminal(
        "jline-assumptions",
        "xterm-256color",
        new PipedInputStream(new PipedOutputStream()),
        out,
        StandardCharsets.UTF_8
      )
    try
      val command =
        "\u001b_Gi=31,s=1,v=1,a=q,t=d,f=24;AAAA\u001b\\\u001b[5;10H\u001b_Ga=p,i=7,p=1,c=12,r=6,C=1,q=2\u001b\\"
      terminal.writer().print(command)
      terminal.writer().flush()
      out.toString(StandardCharsets.UTF_8) shouldBe command
    finally terminal.close()
  }
