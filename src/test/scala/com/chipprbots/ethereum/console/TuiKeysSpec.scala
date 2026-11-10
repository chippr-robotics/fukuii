package com.chipprbots.ethereum.console

import org.jline.utils.AttributedString
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*
import scala.collection.mutable.ArrayBuffer

/** The view keys (spec 027 FR-015, research §2.1–§2.2, T061). `5`, `l`, `+` and `-` arrive with the Logs view in slice
  * 4, so here they are unknown keys.
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

  "The number keys" should "open Overview, Sync, Peers and Chain" taggedAs (UnitTest) in withTui { (tui, _) =>
    tui.getState.view shouldBe TuiView.Overview
    for (key, view) <- Seq('2' -> TuiView.Sync, '3' -> TuiView.Peers, '4' -> TuiView.Chain, '1' -> TuiView.Overview) do
      tui.handleCommand(key) shouldBe TuiCommand.Continue
      tui.getState.view shouldBe view
  }

  they should "name the view in the header" taggedAs (UnitTest) in withTui { (tui, tt) =>
    for key <- "1234" do
      tui.handleCommand(key)
      tui.render()
      val header = TuiTestTerminal.visible(tt.lastFrame).linesIterator.next()
      header.trim should endWith(tui.getState.view.title)
  }

  "Tab" should "cycle the views and wrap" taggedAs (UnitTest) in withTui { (tui, _) =>
    '\t'.toLower shouldBe '\t' // checkInput lower-cases every key
    val seen = (1 to 5).map { _ =>
      tui.handleCommand('\t') shouldBe TuiCommand.Continue
      tui.getState.view
    }
    seen shouldBe Seq(TuiView.Sync, TuiView.Peers, TuiView.Chain, TuiView.Overview, TuiView.Sync)
  }

  "? and h" should "open Help over the current view, and any key return to it" taggedAs (UnitTest) in withTui {
    (tui, _) =>
      for open <- Seq('?', 'h') do
        tui.handleCommand('3')
        tui.handleCommand(open) shouldBe TuiCommand.Continue
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
    for key <- Seq("1 Overview", "Tab", "g ", "p ", "q ", "d ", "r ") do frame should include(key)
  }

  it should "still quit on q and detach on d" taggedAs (UnitTest) in {
    withTui { (tui, _) =>
      tui.handleCommand('h')
      tui.handleCommand('q') shouldBe TuiCommand.Quit
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
    celebrating.withView(TuiView.Sync).catchup shouldBe None
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
    for key <- Seq('x', '5', 'l', '+', '-', '[', '0') do
      tui.handleCommand(key) shouldBe TuiCommand.Continue
      tui.getState shouldBe before
  }

  "q, r and d" should "keep their meaning" taggedAs (UnitTest) in {
    withTui { (tui, tt) =>
      tui.handleCommand('2')
      tui.render()
      val bytes = tt.output.length
      tui.handleCommand('r') shouldBe TuiCommand.Continue
      tt.output.substring(bytes) should include(Tui.ClearScreen)
      tui.getState.view shouldBe TuiView.Sync
      tui.handleCommand('q') shouldBe TuiCommand.Quit
      tui.isEnabled shouldBe true
    }
    withTui { (tui, _) =>
      tui.handleCommand('d') shouldBe TuiCommand.Disable
      tui.isEnabled shouldBe false
    }
  }

  "The footer" should "list the view keys" taggedAs (UnitTest) in {
    val renderer = TuiRenderer(TuiConfig.default)
    val s = TuiFixtures.syncedEtc()
    renderer.render(s, 80, 24).last.toString.trim shouldBe
      "1 Over 2 Sync 3 Peers 4 Chain │ p Pause ? Help r Redraw d Detach q Quit"
    renderer.render(s, 120, 40).last.toString.trim shouldBe
      "1 Overview  2 Sync  3 Peers  4 Chain │ p Pause  ? Help  r Redraw  d Detach  q Quit"
    renderer.render(s.copy(glyphs = GlyphSet.Ascii), 80, 24).last.toString should include("4 Chain | p Pause")
  }

  // prism, slice 3 review: an arrow, Home, Insert, Delete or a function key is an escape sequence, and its tail was
  // dispatched as keys: Left arrow (ESC [ D) detached the TUI, Home (ESC [ H) opened Help.
  "An escape sequence" should "be read whole and dropped, never dispatched as keys" taggedAs (UnitTest) in withTui {
    (tui, tt) =>
      val sequences = Seq(
        "\u001b[D" -> "Left arrow",
        "\u001b[H" -> "Home",
        "\u001b[2~" -> "Insert",
        "\u001b[3~" -> "Delete",
        "\u001bOP" -> "F1",
        "\u001b[1;5D" -> "Ctrl-Left"
      )
      for (keys, name) <- sequences do
        withClue(s"$name: ") {
          tt.press(keys)
          tui.checkInput(1000) shouldBe None
          tui.checkInput(50) shouldBe None // nothing of it is left to read
        }
      tui.isEnabled shouldBe true
      tui.getState.helpOpen shouldBe false
      tui.getState.motion shouldBe Motion.Full
      // A key typed after a sequence is still read.
      tt.press("\u001b[C2")
      tui.checkInput(1000) shouldBe None
      tui.checkInput(1000) shouldBe Some('2')
  }

  "r in Help" should "redraw and leave Help open" taggedAs (UnitTest) in withTui { (tui, tt) =>
    tui.handleCommand('?')
    tui.render()
    val bytes = tt.output.length
    tui.handleCommand('r') shouldBe TuiCommand.Continue
    tt.output.substring(bytes) should include(Tui.ClearScreen)
    tui.getState.helpOpen shouldBe true
  }

  // prism, slice 3 re-review: a sequence that arrives in two writes is still one sequence.
  it should "be dropped whole when it arrives in two writes" taggedAs (UnitTest) in withTui { (tui, tt) =>
    tt.press("\u001b[1;")
    tt.press("5D")
    tui.checkInput(1000) shouldBe None
    tui.checkInput(50) shouldBe None
    tui.isEnabled shouldBe true
  }

  // A stall past the drain's deadline (a GC pause on a loaded host) must not leave the buffered tail to be dispatched:
  // every byte gets at least one 1 ms read.
  it should "be drained after a stall that outlasts the deadline" taggedAs (UnitTest) in {
    final class Buffered(bytes: String) extends org.jline.utils.NonBlockingReader:
      private val queue = scala.collection.mutable.Queue.from(bytes.map(_.toInt))
      val waits: ArrayBuffer[Long] = scala.collection.mutable.ArrayBuffer.empty[Long]
      override protected def read(timeout: Long, isPeek: Boolean): Int =
        waits += timeout
        if queue.isEmpty then org.jline.utils.NonBlockingReader.READ_EXPIRED
        else if isPeek then queue.head
        else queue.dequeue()
      override def readBuffered(b: Array[Char], off: Int, len: Int, timeout: Long): Int = 0
      override def available(): Int = queue.size
      override def close(): Unit = ()
    // The clock jumps a second ahead after the first read: the deadline is long gone when the rest is read.
    var now = 0L
    val reader = new Buffered("[3~x")
    Tui.drainEscape(
      reader,
      () =>
        val t = now; now += 1_000_000_000L; t
    )
    reader.read(1L) shouldBe 'x'.toInt // only what follows the sequence is left
    reader.waits.take(3).foreach(_ should be >= 1L) // each of the sequence's reads waited at least 1 ms
    // A CSI sequence that never ends is drained for at most EscapeMaxBytes bytes.
    val endless = new Buffered("[" + "1;" * 40)
    Tui.drainEscape(endless, () => 0L)
    endless.waits.size shouldBe 1 + Tui.EscapeMaxBytes // the introducer, then at most 32 bytes
  }
