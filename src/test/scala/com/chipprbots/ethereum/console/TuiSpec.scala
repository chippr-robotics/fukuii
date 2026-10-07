package com.chipprbots.ethereum.console

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.*

import org.jline.terminal.Attributes.LocalFlag
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

/** Drives [[Tui]] against an in-memory xterm: terminal lifecycle, input, and frame geometry. */
class TuiSpec extends AnyFlatSpec with Matchers:

  private def withTui(width: Int = 100, height: Int = 40)(test: (Tui, TuiTestTerminal) => Unit): Unit =
    val tt = new TuiTestTerminal(width, height)
    val tui = new Tui(TuiTestTerminal.config, () => tt.terminal)
    try
      tui.initialize() shouldBe true
      test(tui, tt)
    finally
      tui.shutdown()
      tt.close()

  "Tui" should "take over the terminal: raw mode, alternate screen, hidden cursor" taggedAs (UnitTest) in withTui() {
    (tui, tt) =>
      tui.isEnabled shouldBe true
      tt.terminal.getAttributes.getLocalFlag(LocalFlag.ICANON) shouldBe false
      tt.terminal.getAttributes.getLocalFlag(LocalFlag.ECHO) shouldBe false
      tt.output should include(Tui.EnterAltScreen)
      tt.output should include(Tui.HideCursor)
  }

  it should "fall back to standard logging when the terminal factory fails (no TTY)" taggedAs (UnitTest) in {
    val tui = new Tui(TuiTestTerminal.config, () => throw new IllegalStateException("not a tty"))
    tui.initialize() shouldBe false
    tui.isEnabled shouldBe false
    tui.checkInput(10) shouldBe None
    noException should be thrownBy tui.render()
  }

  it should "refuse a dumb terminal instead of painting escape codes into a log stream" taggedAs (UnitTest) in {
    val tt = new TuiTestTerminal(termType = "dumb")
    val tui = new Tui(TuiTestTerminal.config, () => tt.terminal)
    try
      tui.initialize() shouldBe false
      tui.isEnabled shouldBe false
      (tt.output should not).include(Tui.EnterAltScreen)
    finally tt.close()
  }

  // Regression: checkInput used NonBlockingReader.peek(0), and 0 means "wait forever" — the update loop drew one frame
  // and then froze until a key was pressed.
  it should "return from checkInput within its timeout when no key is pressed" taggedAs (UnitTest) in withTui() {
    (tui, _) =>
      val pending = Future(tui.checkInput(50))
      Await.result(pending, 5.seconds) shouldBe None
  }

  it should "deliver a pressed key, lower-cased" taggedAs (UnitTest) in withTui() { (tui, tt) =>
    tt.press("Q")
    tui.checkInput(5000) shouldBe Some('q')
  }

  it should "draw a frame of exactly the terminal height, without a trailing newline" taggedAs (UnitTest) in withTui(
    width = 90,
    height = 30
  ) { (tui, tt) =>
    tui.updateNetwork("mordor")
    tui.render()
    val frame = tt.lastFrame
    frame.split("\r\n", -1).length shouldBe 30
    (frame should not).endWith("\n")
    TuiTestTerminal.visible(frame) should include("MORDOR")
    TuiTestTerminal.visible(frame) should include("[Q]uit")
  }

  it should "never draw a row wider than the terminal" taggedAs (UnitTest) in withTui(width = 40, height = 20) {
    (tui, tt) =>
      tui.updateNodeSettings(
        NodeSettings(dataDir = "/a/very/long/data/directory/path/that/would/wrap", network = "etc")
      )
      tui.render()
      val rows = TuiTestTerminal.visible(tt.lastFrame).split("\n", -1)
      all(rows.map(_.length)) should be <= 40
  }

  it should "apply a status snapshot" taggedAs (UnitTest) in withTui() { (tui, tt) =>
    tui.updateStatus(NodeStatusSnapshot("Connected", 7, 50, 1200, 1500, "Syncing"))
    tui.render()
    val text = TuiTestTerminal.visible(tt.lastFrame)
    text should include("7 / 50")
    text should include("1,200")
    text should include("1,500")
    text should include("Syncing")
  }

  it should "keep running on 'q' — quitting the node is the caller's job" taggedAs (UnitTest) in withTui() { (tui, _) =>
    tui.handleCommand('q') shouldBe TuiCommand.Quit
    tui.isEnabled shouldBe true
  }

  it should "redraw on 'r'" taggedAs (UnitTest) in withTui() { (tui, tt) =>
    tui.render()
    val before = tt.output.length
    tui.handleCommand('r') shouldBe TuiCommand.Continue
    tt.output.substring(before) should include(Tui.ClearScreen)
  }

  it should "ignore unknown keys" taggedAs (UnitTest) in withTui() { (tui, _) =>
    tui.handleCommand('x') shouldBe TuiCommand.Continue
    tui.isEnabled shouldBe true
  }

  it should "restore the terminal on 'd' and leave the node running" taggedAs (UnitTest) in withTui() { (tui, tt) =>
    tui.handleCommand('d') shouldBe TuiCommand.Disable
    tui.isEnabled shouldBe false
    tt.output should include(Tui.ExitAltScreen)
    tt.output should include(Tui.ShowCursor)
    tt.lastSetAttributes.map(_.getLocalFlag(LocalFlag.ICANON)) shouldBe Some(true)
  }

  it should "shut down idempotently" taggedAs (UnitTest) in withTui() { (tui, _) =>
    tui.shutdown()
    noException should be thrownBy tui.shutdown()
    tui.isEnabled shouldBe false
  }
