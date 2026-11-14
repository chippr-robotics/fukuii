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
import ch.qos.logback.core.ConsoleAppender
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
    tui.checkInput(10) shouldBe empty
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
      Await.result(pending, 5.seconds) shouldBe empty
  }

  it should "deliver a pressed key as it was typed: the key table reads either case" taggedAs (UnitTest) in withTui() {
    (tui, tt) =>
      tt.press("Q")
      tui.checkInput(5000) shouldBe Seq(Key.Char('Q'))
      Bindings.actionFor(Key.Char('Q')) shouldBe Bindings.actionFor(Key.Char('q'))
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
    TuiTestTerminal.visible(frame) should include("mordor")
    TuiTestTerminal.visible(frame) should include("q quit")
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
      TuiTestTerminal.visible(tt.lastFrame) should include("7/50")
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
    val tui = tt.tui(TuiTestTerminal.config.copy(glyphs = GlyphSet.Ascii))
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
    text should include("7/50")
    text should include("1,200")
    text should include("1,500")
    text should include("regular") // slice 3: the sync panel names the sync
  }

  it should "keep running on 'q' then 'y' — quitting the node is the caller's job" taggedAs (UnitTest) in withTui() {
    (tui, _) =>
      tui.handleCommand('q') shouldBe TuiCommand.Continue // q asks (spec 029 FR-049)
      tui.handleCommand('y') shouldBe TuiCommand.Quit
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
    TuiTestTerminal.visible(tt.lastFrame) should include("q quit")
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
      (TuiTestTerminal.visible(tt.lastFrame) should not).include(Splash.SkipHint.trim)
    finally
      tui.shutdown()
      tt.close()
  }

  // B-045 (#1744 follow-up): the banner held the TUI lock through a 1 s Thread.sleep inside initialize().
  // Checked on what initialize() does, not on how long it takes: it sets the screen up and draws nothing, so there is no
  // banner or splash for it to hold the lock through; the splash is drawn by the frame loop. The time bounds only stop
  // a regression from hanging the suite: they are well clear of a loaded runner, and of nothing initialize() waits for.
  it should "initialize without holding the lock through a splash" taggedAs (UnitTest) in {
    val tt = new TuiTestTerminal()
    val tui = tt.tui(TuiConfig.default.copy(suppressConsoleLogs = false))
    try
      tui.getState.splashUntilTick shouldBe Some(12L) // the default config has a splash to show
      Await.result(Future(tui.initialize()), 10.seconds) shouldBe true
      // No frame drawn, none held: nothing visible on the screen, whatever escape sequences JLine wrote.
      TuiTestTerminal.visible(tt.output).trim shouldBe empty
      Await.result(Future(tui.isEnabled), 10.seconds) shouldBe true // the lock is free
    finally
      tui.shutdown()
      tt.close()
  }

  // B-045 (#1744 follow-up): after Ctrl-C, JLine's hook closed the terminal and every frame logged
  // `Error rendering TUI: Terminal has been closed` at ERROR until the node stopped the loop.
  it should "stop drawing once JLine has closed the terminal: one WARN, no ERROR per frame" taggedAs (UnitTest) in
    withTui() { (tui, tt) =>
      tt.terminal.close()
      val events = tuiLogDuring { tui.render(); tui.render(); tui.render() }
      events.map(_._1) should not contain Level.ERROR
      events.size should be <= 1
      tui.isEnabled shouldBe false
      // The screen was given back through the process stream, once.
      occurrences(tt.processText, Tui.ExitAltScreen) shouldBe 1
    }

  it should "end the frame loop once the terminal is closed" taggedAs (UnitTest) in {
    val tt = new TuiTestTerminal()
    val tui = tt.tui()
    tui.initialize() shouldBe true
    val updater = new TuiUpdater(tui, TuiTestTerminal.config, () => None, "mordor", () => ())
    try
      tt.terminal.close()
      Await.result(Future(updater.tick()), 5.seconds) shouldBe true // the frame finds the terminal gone ...
      tui.isEnabled shouldBe false // ... and the loop's condition ends it
    finally
      updater.stop()
      tui.shutdown()
      tt.close()
  }

  // B-045: checkInput caught every non-fatal error and returned None, silently, on every frame.
  it should "report a failed key read, and give the terminal and the logs back" taggedAs (UnitTest) in withTui() {
    (tui, tt) =>
      tt.failReads = true
      val events = tuiLogDuring(tui.checkInput(10) shouldBe empty)
      events.map(_._1) shouldBe List(Level.ERROR)
      events.head._2 should include("could not read a key")
      tui.isEnabled shouldBe false
      tt.output should include(Tui.ExitAltScreen)
  }

  it should "stop reading once the terminal is closed, without an ERROR" taggedAs (UnitTest) in withTui() { (tui, tt) =>
    tt.terminal.close()
    val events = tuiLogDuring(Await.result(Future(tui.checkInput(5000)), 2.seconds) shouldBe empty)
    events.map(_._1) should not contain Level.ERROR
    tui.isEnabled shouldBe false
  }
  // B-045: StdNode's shutdown asked Tui.getInstance(), which made a TUI in runs started without --tui.
  "Tui.current" should "be empty until a TUI is made, and never make one" taggedAs (UnitTest) in {
    Tui.reset()
    Tui.current shouldBe None
    Tui.current shouldBe None
    val tui = Tui.getInstance(TuiTestTerminal.config)
    try Tui.current shouldBe Some(tui)
    finally Tui.reset()
    Tui.current shouldBe None
  }

  // prism, slice 2 review: the failed-read ERROR was logged while the console appenders were still detached, so it
  // never reached the console. The terminal and the logs are given back first, then the ERROR is logged.
  it should "give the console back before it logs a failed key read" taggedAs (UnitTest) in withTui() { (tui, tt) =>
    val enabledAtError = new java.util.concurrent.atomic.AtomicReference[Option[Boolean]](None)
    val logger = LoggerFactory.getLogger(classOf[Tui]).asInstanceOf[LogbackLogger]
    val watcher = new ch.qos.logback.core.AppenderBase[ILoggingEvent]:
      override def append(e: ILoggingEvent): Unit =
        if e.getLevel == Level.ERROR then enabledAtError.compareAndSet(None, Some(tui.isEnabled))
    watcher.start()
    logger.addAppender(watcher)
    try
      tt.failReads = true
      tui.checkInput(10) shouldBe empty
    finally logger.detachAppender(watcher)
    enabledAtError.get shouldBe Some(false)
  }

  // prism, slice 2 review: a probe that hangs or dies left its last snapshot on screen, still Fresh, under frames that
  // kept moving. Read at the TUI's clock, a snapshot that no newer poll has replaced turns its sources Unresponsive.
  it should "show the sources Unresponsive when no newer status poll arrives" taggedAs (UnitTest) in {
    val clock = new TuiFixtures.TestClock(TuiFixtures.Now)
    val tt = new TuiTestTerminal()
    val tui = new Tui(TuiTestTerminal.config, () => tt.terminal, _ => Some(tt.processOutput), clock)
    try
      val polled = TuiFixtures.snapshot(current = 10, best = 20, now = TuiFixtures.Now)
      tui.nextFrame(Some(polled))
      tui.getState.connectionStatus shouldBe "Connected"
      clock.advance(6)
      tui.nextFrame(Some(polled))
      tui.getState.connectionStatus shouldBe "Unresponsive (peer manager)"
      tui.getState.syncStatus shouldBe "Unresponsive (sync controller)"
    finally tt.close()
  }

  // prism, slice 2 review: until the first poll the state's `now` stayed at the TUI's start, so the uptime read 0 s
  // while the node started. Until a snapshot is applied, the TUI's own clock moves it.
  it should "count uptime on its own clock until the first status poll" taggedAs (UnitTest) in {
    val clock = new TuiFixtures.TestClock(TuiFixtures.Now)
    val tt = new TuiTestTerminal()
    val tui = new Tui(TuiTestTerminal.config, () => tt.terminal, _ => Some(tt.processOutput), clock)
    try
      clock.advance(30)
      tui.nextFrame(None)
      tui.getState.uptimeSeconds shouldBe 30L
      tui.nextFrame(Some(TuiFixtures.snapshot(current = 10, best = 20, now = TuiFixtures.Now.plusSeconds(31))))
      tui.getState.uptimeSeconds shouldBe 31L
    finally tt.close()
  }

  // prism, slice 2 review: a terminal lost at the end of its input went quietly; it now says so, once.
  it should "say so, once, when keyboard input ends" taggedAs (UnitTest) in withTui() { (tui, tt) =>
    tt.eofReads = true
    val events = tuiLogDuring {
      tui.checkInput(10); tui.checkInput(10)
    }
    events.map(_._1) shouldBe List(Level.WARN)
    events.head._2 should include("end of input")
    tui.isEnabled shouldBe false
  }

  // prism, slice 2 re-review: the late-snapshot threshold scales with the poll interval (two intervals and one wait), so
  // a node polled every 5 s is not called late after 6 s. From a 2.25 s interval the threshold passes the fixed 5 s of
  // Sourced.UnresponsiveAfter, and a late snapshot goes straight to Unresponsive.
  it should "scale the late-snapshot threshold with the poll interval" taggedAs (UnitTest) in {
    val clock = new TuiFixtures.TestClock(TuiFixtures.Now)
    val tt = new TuiTestTerminal()
    val config = TuiTestTerminal.config.copy(statusPollIntervalMs = 5_000)
    val tui = new Tui(config, () => tt.terminal, _ => Some(tt.processOutput), clock)
    try
      val polled = TuiFixtures.snapshot(current = 10, best = 20, now = TuiFixtures.Now)
      clock.advance(6)
      tui.nextFrame(Some(polled))
      tui.getState.connectionStatus shouldBe "Connected" // 6 s is under 2 × 5 s + 0.5 s: not late
      clock.advance(5) // 11 s: late, and past 5 s, so Unresponsive with no Stale step
      tui.nextFrame(Some(polled))
      tui.getState.connectionStatus shouldBe "Unresponsive (peer manager)"
    finally tt.close()
  }

  // ---- The log ring (spec 027 T085, C-11, SC-009) ------------------------------------------------------------------

  private def rootLogger: LogbackLogger =
    LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).asInstanceOf[LogbackLogger]
  private def rootAppenders: Set[String] = rootLogger.iteratorForAppenders().asScala.map(_.getName).toSet
  private def consoleOnRoot: Boolean =
    rootLogger.iteratorForAppenders().asScala.exists(_.isInstanceOf[ConsoleAppender[?]])

  /** A ring that notes whether a console appender was on the root logger when it was attached and when it was detached,
    * and can fail either step.
    */
  private class WatchedRing(failAttach: Boolean = false, failDetach: Boolean = false)
      extends TuiLogRingAppender(100, Level.INFO, java.time.ZoneOffset.UTC):
    @volatile var consoleAtAttach: Option[Boolean] = None
    @volatile var consoleAtDetach: Option[Boolean] = None
    override def attach(): Unit =
      consoleAtAttach = Some(consoleOnRoot)
      if failAttach then throw new IllegalStateException("simulated attach failure")
      super.attach()
    override def detach(): Unit =
      consoleAtDetach = Some(consoleOnRoot)
      if failDetach then throw new IllegalStateException("simulated detach failure")
      super.detach()

  /** A TUI that suppresses the console, as a node's does, with `ring` as its log tail. */
  private def suppressingTui(tt: TuiTestTerminal, ring: TuiLogRingAppender): Tui =
    new Tui(
      TuiTestTerminal.config.copy(suppressConsoleLogs = true),
      () => tt.terminal,
      _ => Some(tt.processOutput),
      TuiFixtures.clock,
      Some(ring)
    )

  "The log ring" should "attach after the console is suppressed and detach before it is restored (C-11)" taggedAs (
    UnitTest
  ) in {
    val tt = new TuiTestTerminal()
    val ring = new WatchedRing()
    val tui = suppressingTui(tt, ring)
    try
      consoleOnRoot shouldBe true // the test configuration's STDOUT
      tui.initialize() shouldBe true
      ring.consoleAtAttach shouldBe Some(false)
      rootAppenders should contain(TuiLogRingAppender.Name)
      consoleOnRoot shouldBe false
      tui.shutdown()
      ring.consoleAtDetach shouldBe Some(false)
      consoleOnRoot shouldBe true
    finally
      tui.shutdown()
      tt.close()
  }

  it should "leave the root logger's appenders as they were (SC-009)" taggedAs (UnitTest) in {
    val before = rootAppenders
    val tt = new TuiTestTerminal()
    val tui = suppressingTui(tt, new WatchedRing())
    try
      tui.initialize() shouldBe true
      tui.shutdown()
      rootAppenders shouldBe before
    finally tt.close()
  }

  it should "give back the terminal and the console logs when it fails to attach (C-11)" taggedAs (UnitTest) in {
    val before = rootAppenders
    val tt = new TuiTestTerminal()
    val tui = suppressingTui(tt, new WatchedRing(failAttach = true))
    try
      tui.initialize() shouldBe false
      tui.isEnabled shouldBe false
      rootAppenders shouldBe before
      consoleOnRoot shouldBe true
      tt.output should include(Tui.ExitAltScreen)
      tt.lastSetAttributes.map(_.getLocalFlag(LocalFlag.ICANON)) shouldBe Some(true)
    finally tt.close()
  }

  it should "still restore the console and the terminal when it fails to detach" taggedAs (UnitTest) in {
    val tt = new TuiTestTerminal()
    val ring = new WatchedRing(failDetach = true)
    val tui = suppressingTui(tt, ring)
    try
      tui.initialize() shouldBe true
      tui.shutdown()
      consoleOnRoot shouldBe true
      tui.isEnabled shouldBe false
      tt.lastSetAttributes.map(_.getLocalFlag(LocalFlag.ICANON)) shouldBe Some(true)
    finally
      rootLogger.detachAppender(ring)
      tt.close()
  }

  it should "bring the node's log lines to the Logs view" taggedAs (UnitTest) in {
    val tt = new TuiTestTerminal(100, 30)
    val logger = LoggerFactory.getLogger("com.chipprbots.ethereum.console.TuiSpec.ring").asInstanceOf[LogbackLogger]
    val tui = tt.tui()
    val previous = logger.getLevel
    try
      tui.initialize() shouldBe true
      logger.setLevel(Level.INFO) // the test configuration's root is at ERROR
      logger.info("hello from the node")
      tui.nextFrame(None)
      tui.handleCommand('8')
      tui.render()
      TuiTestTerminal.visible(tt.lastFrame) should include("hello from the node")
    finally
      logger.setLevel(previous)
      tui.shutdown()
      tt.close()
  }
