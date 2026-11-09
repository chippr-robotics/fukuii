package com.chipprbots.ethereum.console

import java.util.regex.Pattern

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger as LogbackLogger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.jline.terminal.Attributes.LocalFlag
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.slf4j.LoggerFactory

import com.chipprbots.ethereum.testing.Tags.*

/** Drives [[Tui]] against an in-memory xterm: terminal lifecycle, input, and frame geometry. */
class TuiSpec extends AnyFlatSpec with Matchers:

  private def withTui(width: Int = 100, height: Int = 40)(test: (Tui, TuiTestTerminal) => Unit): Unit =
    val tt = new TuiTestTerminal(width, height)
    val tui = tt.tui()
    try
      tui.initialize() shouldBe true
      test(tui, tt)
    finally
      tui.shutdown()
      tt.close()

  /** Runs `f` and returns the WARN and ERROR events [[Tui]] logged on this thread meanwhile. The test logback config
    * silences everything below ERROR, so Tui's logger is opened to WARN for the duration.
    */
  private def tuiLogDuring(f: => Unit): List[(Level, String)] =
    val logger = LoggerFactory.getLogger(classOf[Tui]).asInstanceOf[LogbackLogger]
    val appender = new ListAppender[ILoggingEvent]
    val previousLevel = logger.getLevel
    val thread = Thread.currentThread().getName
    appender.start()
    logger.addAppender(appender)
    logger.setLevel(Level.WARN)
    try f
    finally
      logger.setLevel(previousLevel)
      logger.detachAppender(appender)
    appender.list.asScala.toList.filter(_.getThreadName == thread).map(e => (e.getLevel, e.getFormattedMessage))

  private def occurrences(text: String, of: String): Int = text.split(Pattern.quote(of), -1).length - 1

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
    val tui = tt.tui()
    try
      tui.initialize() shouldBe false
      tui.isEnabled shouldBe false
      (tt.output should not).include(Tui.EnterAltScreen)
      tui.shutdown()
      tt.processText shouldBe empty
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
    TuiTestTerminal.visible(frame) should include("q Quit")
  }

  // Spec 027 FR-009: NO_COLOR (resolved into the config by TuiEnv) puts the monochrome theme on the wire.
  it should "draw no colour when the config says noColor" taggedAs (UnitTest) in {
    val tt = new TuiTestTerminal()
    val tui = tt.tui(TuiTestTerminal.config.copy(noColor = true))
    try
      tui.initialize() shouldBe true
      tui.updateStatus(TuiFixtures.snapshot(current = 1200, best = 1500, peers = 7))
      tui.render()
      TuiTestTerminal.colourCodes(tt.lastFrame) shouldBe empty
      TuiTestTerminal.visible(tt.lastFrame) should include("7 / 50")
    finally
      tui.shutdown()
      tt.close()
  }

  it should "draw colour on a colour terminal by default" taggedAs (UnitTest) in withTui() { (tui, tt) =>
    tui.updateStatus(TuiFixtures.snapshot(current = 1200, best = 1500, peers = 7))
    tui.render()
    TuiTestTerminal.colourCodes(tt.lastFrame) should not be empty
  }

  // Spec 027 FR-008: the glyph set the config resolved is the one drawn.
  it should "draw the ASCII glyph set when the config selects it" taggedAs (UnitTest) in {
    val tt = new TuiTestTerminal()
    val tui = tt.tui(TuiTestTerminal.config.copy(glyphs = GlyphChoice.Ascii))
    try
      tui.initialize() shouldBe true
      tui.updateStatus(TuiFixtures.snapshot(current = 1200, best = 1500, peers = 7))
      tui.render()
      val text = TuiTestTerminal.visible(tt.lastFrame)
      text should include("~o")
      text should include("@@")
      all(text.map(_.toInt)) should be < 0x7f
    finally
      tui.shutdown()
      tt.close()
  }

  it should "draw separators the terminal shows as box-drawing lines" taggedAs (UnitTest) in withTui() { (tui, tt) =>
    tui.render()
    TuiTestTerminal.visible(tt.lastFrame).split("\n").exists(row => row.nonEmpty && row.forall(_ == '─')) shouldBe true
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
    tui.updateStatus(TuiFixtures.snapshot(current = 1200, best = 1500, peers = 7))
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
    tt.processText shouldBe empty // the terminal was open: nothing goes around it
  }

  it should "shut down idempotently: a second call writes nothing" taggedAs (UnitTest) in withTui() { (tui, tt) =>
    tui.shutdown()
    tt.output should include(Tui.ExitAltScreen)
    val written = tt.output.length
    noException should be thrownBy tui.shutdown()
    tui.isEnabled shouldBe false
    tt.output.length shouldBe written
    tt.processText shouldBe empty
  }

  // Regression: on exit, JLine's own shutdown hook closed the terminal before the TuiRestore hook ran, so the reset
  // could not be written and the user's shell was left in the alternate screen with the cursor hidden.
  it should "write the reset sequence to the process output when the terminal was already closed" taggedAs (UnitTest) in
    withTui() { (tui, tt) =>
      tt.terminal.close() // JLine's hook: closes the terminal and restores its mode, but leaves the screen as it is
      val events = tuiLogDuring(tui.shutdown())
      tui.isEnabled shouldBe false
      tt.processText should include(Tui.ExitAltScreen)
      tt.processText should include(Tui.ShowCursor)
      tt.processText should include(Tui.ResetColors)
      // JLine's close already restored the mode; the saved attributes are not pushed at a closed terminal.
      tt.lastSetAttributes.map(_.getLocalFlag(LocalFlag.ICANON)) shouldBe Some(false)
      // An expected race: one WARN, no ERROR.
      events.map(_._1) shouldBe List(Level.WARN)
      events.head._2 should include("already closed by JLine's shutdown hook (at reset screen)")

      val written = tt.processText.length
      tui.shutdown()
      tt.processText.length shouldBe written
    }

  // Regression (live SIGTERM run): the reset went through, then JLine's hook closed the terminal before the mode was
  // restored, and `Error shutting down TUI (restore terminal mode): Terminal has been closed` was logged at ERROR.
  it should "take the terminal closing between the reset and the mode restore as the expected race" taggedAs (UnitTest) in
    withTui() { (tui, tt) =>
      tt.closeOnSetAttributes = true
      val events = tuiLogDuring(tui.shutdown())
      tui.isEnabled shouldBe false
      events.map(_._1) shouldBe List(Level.WARN)
      events.head._2 should include("already closed by JLine's shutdown hook (at restore terminal mode)")
      // The screen was left exactly once, through the terminal, and nothing was written around it.
      occurrences(tt.output, Tui.ExitAltScreen) shouldBe 1
      occurrences(tt.output, Tui.ShowCursor) shouldBe 1
      tt.processText shouldBe empty

      tuiLogDuring(tui.shutdown()) shouldBe empty
      occurrences(tt.output, Tui.ExitAltScreen) shouldBe 1
    }

  it should "not write the reset sequence around a closed terminal it never switched screens on" taggedAs (UnitTest) in {
    val tt = new TuiTestTerminal()
    val tui = tt.tui()
    try
      // initialize() fails in enterRawMode, before the alternate screen, and releases a closed terminal.
      tt.terminal.close()
      tui.initialize() shouldBe false
      tui.shutdown()
      (tt.output should not).include(Tui.EnterAltScreen)
      tt.processText shouldBe empty
    finally tt.close()
  }

  it should "leave raw mode even when writing the reset sequence fails" taggedAs (UnitTest) in withTui() { (tui, tt) =>
    tt.failWrites = true
    tui.shutdown()
    tui.isEnabled shouldBe false
    tt.lastSetAttributes.map(_.getLocalFlag(LocalFlag.ICANON)) shouldBe Some(true)
  }

  // Spec 027 FR-010, T036: a frame equal to the last one is not written again.
  it should "not write a frame that equals the last one, and write a changed one" taggedAs (UnitTest) in withTui() {
    (tui, tt) =>
      tui.updateStatus(TuiFixtures.snapshot(current = 1200, best = 1500, peers = 7))
      tui.render()
      val written = tt.framesWritten
      val bytes = tt.output.length
      tui.render()
      tui.render()
      tt.framesWritten shouldBe written
      tt.output.length shouldBe bytes
      tui.updateStatus(TuiFixtures.snapshot(current = 1201, best = 1500, peers = 7))
      tui.render()
      tt.framesWritten shouldBe written + 1
      TuiTestTerminal.visible(tt.lastFrame) should include("1,201")
  }

  it should "still redraw on 'r' when nothing changed" taggedAs (UnitTest) in withTui() { (tui, tt) =>
    tui.render()
    val bytes = tt.output.length
    tui.handleCommand('r') shouldBe TuiCommand.Continue
    tt.output.substring(bytes) should include(Tui.ClearScreen)
    TuiTestTerminal.visible(tt.lastFrame) should include("q Quit")
  }

  // Spec 027 FR-012: `p` pauses the frame counter; a second `p` resumes the configured motion.
  it should "pause and resume on 'p'" taggedAs (UnitTest) in withTui() { (tui, _) =>
    tui.getState.motion shouldBe Motion.Full
    tui.handleCommand('p') shouldBe TuiCommand.Continue
    tui.getState.motion shouldBe Motion.Paused
    val tick = tui.getState.tick
    tui.nextFrame(None)
    tui.getState.tick shouldBe tick
    tui.handleCommand('p')
    tui.getState.motion shouldBe Motion.Full
    tui.nextFrame(None)
    tui.getState.tick shouldBe tick + 1
  }

  it should "resume to reduced motion when that is the configured motion" taggedAs (UnitTest) in {
    val tt = new TuiTestTerminal()
    val tui = tt.tui(TuiTestTerminal.config.copy(reducedMotion = true))
    try
      tui.initialize() shouldBe true
      tui.getState.motion shouldBe Motion.Reduced
      tui.handleCommand('p')
      tui.getState.motion shouldBe Motion.Paused
      tui.handleCommand('p')
      tui.getState.motion shouldBe Motion.Reduced
    finally
      tui.shutdown()
      tt.close()
  }

  // Spec 027 FR-013: the splash is a view, skipped by any key; the key does nothing else.
  it should "start on the splash and skip it on any key, consuming the key" taggedAs (UnitTest) in {
    val tt = new TuiTestTerminal(80, 24)
    val tui = tt.tui(TuiTestTerminal.config.copy(splashMs = 3000, animationIntervalMs = 250))
    try
      tui.initialize() shouldBe true
      tui.getState.splashUntilTick shouldBe Some(12L)
      tui.render()
      TuiTestTerminal.visible(tt.lastFrame) should include(Splash.SkipHint.trim)
      tui.handleCommand('q') shouldBe TuiCommand.Continue // skipped the splash; did not quit
      tui.getState.splashCover(24) shouldBe None
      tui.render()
      TuiTestTerminal.visible(tt.lastFrame) should not include Splash.SkipHint.trim
    finally
      tui.shutdown()
      tt.close()
  }

  // B-045 (#1744 follow-up): the banner held the TUI lock through a 1 s Thread.sleep inside initialize().
  it should "initialize without holding the lock through a splash" taggedAs (UnitTest) in {
    val tt = new TuiTestTerminal()
    val tui = tt.tui(TuiConfig.default.copy(suppressConsoleLogs = false))
    try
      Await.result(Future(tui.initialize()), 600.millis) shouldBe true
      Await.result(Future(tui.isEnabled), 600.millis) shouldBe true
    finally
      tui.shutdown()
      tt.close()
  }
