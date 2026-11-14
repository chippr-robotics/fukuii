package com.chipprbots.ethereum.console

import org.jline.utils.AttributedString
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

/** The keys through a [[Tui]] on a terminal (spec 029 FR-003 to FR-010; spec 027 FR-015): the views, Help, the quit
  * dialog, escape sequences read as keys or as nothing, and the Logs view's scrolling with the Recent strips. What a
  * key does to the state alone is in `UpdateSpec`; what bytes are which key is in `KeyDecoderSpec`.
  */
class TuiKeysSpec extends AnyFlatSpec with Matchers:

  private def withTui(test: (Tui, TuiTestTerminal) => Unit): Unit =
    val tt = new TuiTestTerminal(100, 40)
    val tui = tt.tui()
    try
      tui.initialize() shouldBe true
      test(tui, tt)
    finally
      tui.shutdown()
      tt.close()

  "The number keys" should "open the nine views" taggedAs (UnitTest) in withTui { (tui, _) =>
    tui.getState.view shouldBe TuiView.Overview
    for view <- TuiView.values.toSeq.reverse do
      tui.handleCommand(view.key) shouldBe TuiCommand.Continue
      tui.getState.view shouldBe view
  }

  they should "name the view in the header" taggedAs (UnitTest) in withTui { (tui, tt) =>
    for key <- "123456789" do
      tui.handleCommand(key)
      tui.render()
      val header = TuiTestTerminal.visible(tt.lastFrame).linesIterator.next()
      header.trim should endWith(tui.getState.view.title)
  }

  "] and [" should "cycle the views and wrap" taggedAs (UnitTest) in withTui { (tui, _) =>
    val forward = (1 to 10).map { _ =>
      tui.handleCommand(']') shouldBe TuiCommand.Continue
      tui.getState.view
    }
    forward shouldBe (TuiView.values.toSeq.tail :+ TuiView.Overview :+ TuiView.Chain)
    tui.handleCommand('[')
    tui.handleCommand('[')
    tui.getState.view shouldBe TuiView.Ops
  }

  "Tab" should "leave the view where it is: it moves between panels, not views (spec 029 FR-006)" taggedAs (
    UnitTest
  ) in withTui { (tui, tt) =>
    tui.handleCommand('3')
    tt.press("\t")
    tui.checkInput(1000) shouldBe Seq(Key.Tab)
    tui.handleKey(Key.Tab) shouldBe TuiCommand.Continue
    tui.getState.view shouldBe TuiView.Peers
  }

  "?, h and F1" should "open Help over the current view, and any key return to it" taggedAs (UnitTest) in withTui {
    (tui, _) =>
      for open <- Seq(Key.Char('?'), Key.Char('h'), Key.Char('H'), Key.F(1)) do
        tui.handleCommand('3')
        tui.handleKey(open) shouldBe TuiCommand.Continue
        tui.getState.helpOpen shouldBe true
        tui.handleCommand('2') shouldBe TuiCommand.Continue // returns; does not switch views
        tui.getState.helpOpen shouldBe false
        tui.getState.view shouldBe TuiView.Peers
  }

  "Help" should "title the header and list the keys" taggedAs (UnitTest) in withTui { (tui, tt) =>
    tui.handleCommand('?')
    tui.render()
    val frame = TuiTestTerminal.visible(tt.lastFrame)
    frame.linesIterator.next().trim should endWith("Help")
    for key <- Seq(
        "1 Overview",
        "8 Logs",
        "9 Ops",
        "Tab",
        "Up k",
        "Enter",
        "Esc",
        ": ",
        "/ ",
        "g ",
        "p ",
        "q ",
        "d ",
        "r "
      )
    do frame should include(key)
  }

  it should "still ask to quit on q and detach on d" taggedAs (UnitTest) in {
    withTui { (tui, _) =>
      tui.handleCommand('h')
      tui.handleCommand('q') shouldBe TuiCommand.Continue
      tui.getState.overlay shouldBe Overlay.Dialog(DialogKind.Quit)
    }
    withTui { (tui, _) =>
      tui.handleCommand('h')
      tui.handleCommand('d') shouldBe TuiCommand.Disable
      tui.isEnabled shouldBe false
    }
  }

  "A view change" should "cancel a celebration on screen (FR-014)" taggedAs (UnitTest) in {
    val celebrating = TuiFixtures.celebration(GlyphSet.Emoji, frame = 3)
    celebrating.catchup shouldBe Some(Catchup.Celebrating(3))
    celebrating.withView(TuiView.Chain).catchup shouldBe None
    celebrating.nextView.catchup shouldBe None
    celebrating.openHelp.catchup shouldBe None
    celebrating.withView(TuiView.Overview).catchup shouldBe Some(Catchup.Celebrating(3)) // not a change
  }

  "g" should "switch the glyph set and back" taggedAs (UnitTest) in withTui { (tui, tt) =>
    tui.getState.glyphs shouldBe GlyphSet.Emoji
    tui.handleCommand('g') shouldBe TuiCommand.Continue
    tui.getState.glyphs shouldBe GlyphSet.Ascii
    tui.render()
    all(TuiTestTerminal.visible(tt.lastFrame).map(_.toInt)) should be < 0x7f
    tui.handleCommand('g')
    tui.getState.glyphs shouldBe GlyphSet.Emoji
  }

  it should "not shift the layout: every label starts in the same column in both sets" taggedAs (UnitTest) in {
    val renderer = TuiRenderer(TuiConfig.default)
    val labels = Seq("Peers", "Block", "RPC", "Disk", "Overall", "Accounts", "Storage", "Bytecode", "Healing", "Forks")

    /** The column a label starts at in each row that has it. */
    def columns(s: TuiState, w: Int, h: Int): Seq[(Int, String, Int)] =
      renderer.render(s, w, h).zipWithIndex.flatMap { (row, i) =>
        val text = row.toString
        labels.flatMap { label =>
          val at = text.indexOf(s" $label ")
          Option.when(at >= 0)((i, label, new AttributedString(text.substring(0, at)).columnLength()))
        }
      }
    for (name, fixture) <- TuiFixtures.all; view <- TuiView.values.toSeq; (w, h) <- Seq((80, 24), (120, 40), (160, 48))
    do
      val emoji = fixture(GlyphSet.Emoji).copy(view = view, motion = Motion.Reduced)
      withClue(s"$name $view ${w}x$h: ") {
        columns(emoji.toggleGlyphs, w, h) shouldBe columns(emoji, w, h)
      }
  }

  "Unknown keys" should "continue and change nothing" taggedAs (UnitTest) in withTui { (tui, _) =>
    val before = tui.getState
    for key <- Seq('x', '0', '!', 'z') do
      tui.handleCommand(key) shouldBe TuiCommand.Continue
      tui.getState shouldBe before
  }

  "r and d" should "keep their meaning" taggedAs (UnitTest) in {
    withTui { (tui, tt) =>
      tui.handleCommand('2')
      tui.render()
      val bytes = tt.output.length
      tui.handleCommand('r') shouldBe TuiCommand.Continue
      tt.output.substring(bytes) should include(Tui.ClearScreen)
      tui.getState.view shouldBe TuiView.Chain
    }
    withTui { (tui, _) =>
      tui.handleCommand('d') shouldBe TuiCommand.Disable
      tui.isEnabled shouldBe false
    }
  }

  // ---- q asks (spec 029 FR-049) -------------------------------------------------------------------------------------

  "q" should "open a dialog and not stop the node" taggedAs (UnitTest) in withTui { (tui, tt) =>
    tui.handleCommand('q') shouldBe TuiCommand.Continue
    tui.isEnabled shouldBe true
    tui.render()
    val frame = TuiTestTerminal.visible(tt.lastFrame)
    frame should include("Stop this node?")
    frame should include("y  stop the node")
    // The alert row is still the third row: the dialog covers body rows only.
    frame.linesIterator.drop(2).next() should include("no alerts")
  }

  it should "stop the node on y, and on nothing else" taggedAs (UnitTest) in {
    withTui { (tui, _) =>
      tui.handleCommand('q')
      for key <- Seq(Key.Enter, Key.Char('x'), Key.Char('q'), Key.Tab, Key.Paste("y"), Key.Paste("q\ny")) do
        tui.handleKey(key) shouldBe TuiCommand.Continue
        tui.getState.overlay shouldBe Overlay.Dialog(DialogKind.Quit)
      tui.handleCommand('y') shouldBe TuiCommand.Quit
      tui.isEnabled shouldBe true // the caller restores the terminal, then exits
    }
    for cancel <- Seq(Key.Char('n'), Key.Esc, Key.Backspace) do
      withTui { (tui, _) =>
        tui.handleCommand('q')
        tui.handleKey(cancel) shouldBe TuiCommand.Continue
        tui.getState.overlay shouldBe Overlay.None
        tui.handleCommand('y') shouldBe TuiCommand.Continue // y alone is not a key
      }
  }

  it should "not be offered in read-only mode (spec 029 FR-053)" taggedAs (UnitTest) in {
    val tt = new TuiTestTerminal(100, 40)
    val tui = tt.tui(TuiTestTerminal.config.copy(readOnly = true))
    try
      tui.initialize() shouldBe true
      tui.handleCommand('q') shouldBe TuiCommand.Continue
      tui.getState.overlay shouldBe Overlay.None
      tui.getState.notice shouldBe Some(Update.ReadOnlyNotice)
      tui.render()
      TuiTestTerminal.visible(tt.lastFrame).linesIterator.toSeq.last should include("read-only")
    finally
      tui.shutdown()
      tt.close()
  }

  "Pasted text" should "arrive as one key and do nothing outside a text field (spec 029 FR-009)" taggedAs (
    UnitTest
  ) in withTui { (tui, tt) =>
    tt.press("\u001b[200~q\ry2d\u001b[201~")
    val keys = tui.checkInput(1000)
    keys shouldBe Seq(Key.Paste("qy2d"))
    val before = tui.getState
    keys.foreach(tui.handleKey(_) shouldBe TuiCommand.Continue)
    tui.getState shouldBe before
    tui.isEnabled shouldBe true
  }

  "The TUI" should "switch bracketed paste on when it starts and off when it leaves" taggedAs (UnitTest) in {
    val tt = new TuiTestTerminal(100, 40)
    val tui = tt.tui()
    try
      tui.initialize() shouldBe true
      tt.output should include(KeyDecoder.EnablePaste)
      (tt.output should not).include(KeyDecoder.DisablePaste)
      tui.shutdown()
      tt.output should include(KeyDecoder.DisablePaste)
    finally tt.close()
  }

  "The footer" should "list the keys for its tier" taggedAs (UnitTest) in {
    val renderer = TuiRenderer(TuiConfig.default)
    val s = TuiFixtures.syncedEtc()
    renderer.render(s, 80, 24).last.toString.trim shouldBe
      "1-9 view  : prompt  / filter  ? help  p pause  r redraw  d detach  q quit"
    renderer.render(s, 120, 40).last.toString.trim shouldBe
      "1-9 view  Enter open  Esc back  : prompt  / filter  ? help  p pause  r redraw  d detach  q quit"
    renderer.render(s, 160, 48).last.toString should include("Up Down select")
    // Each tier's keys fit the narrowest screen of that tier.
    TuiRenderer.FooterKeys(Tier.S).length should be <= 80
    TuiRenderer.FooterKeys(Tier.M).length should be <= Tier.MinMWidth
    TuiRenderer.FooterKeys(Tier.L).length should be <= Tier.MinLWidth
  }

  // Spec 027, prism's slice 3 review: an arrow, Home, Insert, Delete or a function key is an escape sequence, and its
  // tail was dispatched as keys: Left arrow (ESC [ D) detached the TUI, Home (ESC [ H) opened Help. Spec 029 reads the
  // ones it knows as keys, and the rest as nothing (FR-004).
  "An escape sequence" should "be read whole: a key, or nothing, never the characters it is made of" taggedAs (
    UnitTest
  ) in withTui { (tui, tt) =>
    val sequences = Seq(
      ("\u001b[D", "Left arrow", Seq(Key.Left)),
      ("\u001b[H", "Home", Seq(Key.Home)),
      ("\u001b[2~", "Insert", Nil),
      ("\u001b[3~", "Delete", Nil),
      ("\u001bOP", "F1", Seq(Key.F(1))),
      ("\u001b[1;5D", "Ctrl-Left", Seq(Key.Left)),
      ("\u001b[6;20;10t", "a cell-size report", Nil),
      ("\u001b_Gi=31;OK\u001b\\", "a graphics reply", Nil)
    )
    for (bytes, name, expected) <- sequences do
      withClue(s"$name: ") {
        tt.press(bytes)
        tui.checkInput(1000) shouldBe expected
        tui.checkInput(50) shouldBe empty // nothing of it is left to read
      }
    // A key typed after a sequence is still read.
    tt.press("\u001b[2~2")
    tui.checkInput(1000) shouldBe empty
    tui.checkInput(1000) shouldBe Seq(Key.Char('2'))
  }

  it should "be one key when it arrives in two writes" taggedAs (UnitTest) in withTui { (tui, tt) =>
    tt.press("\u001b[1;")
    tt.press("5D")
    tui.checkInput(1000) shouldBe Seq(Key.Left)
    tui.checkInput(50) shouldBe empty
    tui.isEnabled shouldBe true
  }

  "A lone Esc" should "be the Esc key, and a d after it the d key" taggedAs (UnitTest) in withTui { (tui, tt) =>
    tt.press("\u001b")
    tui.checkInput(1000) shouldBe Seq(Key.Esc)
    tt.press("d")
    tui.checkInput(1000) shouldBe Seq(Key.Char('d'))
  }

  "r in Help" should "redraw and leave Help open" taggedAs (UnitTest) in withTui { (tui, tt) =>
    tui.handleCommand('?')
    tui.render()
    val bytes = tt.output.length
    tui.handleCommand('r') shouldBe TuiCommand.Continue
    tt.output.substring(bytes) should include(Tui.ClearScreen)
    tui.getState.helpOpen shouldBe true
  }

  // ---- The Logs view and the Recent strips (spec 027 slice 4, T086) -------------------------------------------------

  /** A TUI whose log ring holds `lines` INFO lines, `line 0001` the oldest, already read by a frame. */
  private def withLogs(lines: Int, width: Int = 100, height: Int = 40)(test: (Tui, TuiTestTerminal) => Unit): Unit =
    val tt = new TuiTestTerminal(width, height)
    val ring = new TuiLogRingAppender(1000, ch.qos.logback.classic.Level.INFO, java.time.ZoneOffset.UTC)
    val tui = tt.tui(logRing = Some(ring))
    try
      tui.initialize() shouldBe true
      (1 to lines).foreach(i => ring.doAppend(TuiFixtures.logEvent(f"line $i%04d")))
      tui.nextFrame(None)
      test(tui, tt)
    finally
      tui.shutdown()
      tt.close()

  "8 and l" should "open the Logs view" taggedAs (UnitTest) in withTui { (tui, _) =>
    tui.handleCommand('8') shouldBe TuiCommand.Continue
    tui.getState.view shouldBe TuiView.Logs
    tui.handleCommand('1')
    tui.handleCommand('l') shouldBe TuiCommand.Continue
    tui.getState.view shouldBe TuiView.Logs
  }

  "+ and -" should "scroll the Logs view: - to older lines, + back to newer, never past either end" taggedAs (
    UnitTest
  ) in withLogs(200) { (tui, tt) =>
    def shown(): String =
      tui.render()
      TuiTestTerminal.visible(tt.lastFrame)
    tui.handleCommand('8')
    shown() should include("line 0200")
    tui.handleCommand('+') shouldBe TuiCommand.Continue
    tui.getState.logScroll shouldBe 0 // already at the newest line
    tui.handleCommand('-') shouldBe TuiCommand.Continue
    tui.getState.logScroll should be > 0
    (shown() should not).include("line 0200")
    (1 to 50).foreach(_ => tui.handleCommand('-'))
    val top = tui.getState.logScroll
    tui.handleCommand('-')
    tui.getState.logScroll shouldBe top // the oldest line is on screen: no further
    val oldest = shown()
    oldest should include("line 0001")
    (oldest should not).include("line 0200")
    (1 to 50).foreach(_ => tui.handleCommand('+'))
    tui.getState.logScroll shouldBe 0
    shown() should include("line 0200")
  }

  they should "do nothing in any other view" taggedAs (UnitTest) in withLogs(200) { (tui, _) =>
    for key <- "12" do
      tui.handleCommand(key)
      val before = tui.getState
      tui.handleCommand('-') shouldBe TuiCommand.Continue
      tui.handleCommand('+') shouldBe TuiCommand.Continue
      tui.getState shouldBe before
  }

  "Opening the Logs view" should "show the newest lines, wherever it was left scrolled" taggedAs (UnitTest) in withLogs(
    200
  ) { (tui, _) =>
    tui.handleCommand('8')
    tui.handleCommand('-')
    tui.getState.logScroll should be > 0
    tui.handleCommand('1')
    tui.handleCommand('l')
    tui.getState.logScroll shouldBe 0
  }

  private val renderer = TuiRenderer(TuiConfig.default)

  /** The log rows under a strip's title row: ` HH:mm:ss LEVEL text`. The sidebar's columns are left out. */
  private def strip(s: TuiState, title: String, w: Int, h: Int): Seq[String] =
    renderer
      .render(s.copy(motion = Motion.Reduced), w, h)
      .map(row => TuiFixtures.body(row.toString, w, h))
      .dropWhile(row => !row.startsWith(s" $title"))
      .drop(1)
      .takeWhile(_.matches(" \\d\\d:\\d\\d:\\d\\d .*"))

  "The Overview's Recent strip" should "show the newest 6 lines at S and 10 at M" taggedAs (UnitTest) in {
    val s = TuiFixtures.syncedEtc()
    TuiFixtures.recentLogs.size should be > 10
    val small = strip(s, "Recent", 80, 24)
    small should have size 6
    small.last should include(TuiFixtures.recentLogs.last.text.take(20))
    strip(s, "Recent", 120, 40) should have size 10
  }

  "The Chain view's Recent (sync) strip, while syncing," should "keep only lines whose logger is under blockchain.sync" taggedAs (
    UnitTest
  ) in {
    val s = TuiFixtures.regularSyncEtc().withView(TuiView.Chain)
    val (sync, other) =
      TuiFixtures.recentLogs.partition(_.logger.startsWith("com.chipprbots.ethereum.blockchain.sync"))
    sync should not be empty
    other should not be empty
    val rows = strip(s, "Recent (sync)", 160, 48)
    rows should have size sync.size.toLong
    rows.zip(sync).foreach((row, line) => row should include(line.text.take(30)))
  }
