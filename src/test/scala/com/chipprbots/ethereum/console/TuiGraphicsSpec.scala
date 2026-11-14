package com.chipprbots.ethereum.console

import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.console.gfx.Kitty
import com.chipprbots.ethereum.testing.Tags.*

/** The TUI on a terminal that draws pixels, and on ones that do not (spec 029 FR-011, FR-013, FR-016, SC-006): what it
  * asks at the start, which mode it draws, and that no image outlives it.
  */
class TuiGraphicsSpec extends AnyFlatSpec with Matchers with OptionValues:

  private val auto = TuiTestTerminal.config.copy(graphics = GraphicsChoice.Auto)

  /** A TUI up on a 160×48 terminal that answers `reply`, with the synced ETC fixture's chain, and one frame drawn. */
  private def withTui(reply: String, config: TuiConfig = auto)(f: (Tui, TuiTestTerminal) => Unit): Unit =
    val tt = new TuiTestTerminal(width = 160, height = 48, queryReply = reply)
    val tui = tt.tui(config)
    try
      tui.initialize() shouldBe true
      tui.updateChain(TuiFixtures.chainEtc)
      tui.updateNetwork("etc")
      tui.nextFrame(TuiFixtures.syncedEtc().snapshot)
      tui.render()
      f(tui, tt)
    finally
      tui.shutdown()
      tt.close()

  "A terminal that answers both questions" should "get pixel mode: the art is sent once, and placed" taggedAs (UnitTest) in
    withTui(TuiTestTerminal.kittyReply()) { (tui, tt) =>
      tui.getState.graphics shouldBe GraphicsMode.Pixel(TuiFixtures.Cell)
      val drawn = tt.graphics
      drawn.images should have size 2 // the network icon and the mascot
      drawn.placements should have size 2
      TuiTestTerminal.visible(tt.lastFrame) should include("graphics pixel")
      // The frame after it sends no image again.
      tui.nextFrame(None)
      tui.render()
      tt.graphics.uploads shouldBe drawn.uploads
    }

  it should "have its answers read before the key loop: none of them is a key" taggedAs (UnitTest) in
    withTui(TuiTestTerminal.kittyReply()) { (tui, _) =>
      tui.checkInput(20L) shouldBe empty
      tui.getState.view shouldBe TuiView.Overview
      tui.getState.overlay shouldBe Overlay.None
    }

  "A terminal that knows no graphics" should "get block mode, and never a graphics command after the question" taggedAs (
    UnitTest
  ) in
    withTui(TuiTestTerminal.PlainReply) { (tui, tt) =>
      tui.getState.graphics shouldBe GraphicsMode.Block
      tt.output.sliding(3).count(_ == "\u001b_G") shouldBe 1 // the question itself
      tt.lastFrame should include("▀")
      TuiTestTerminal.visible(tt.lastFrame) should include("graphics block")
    }

  "A terminal that answers nothing" should "get block mode once the bound has passed" taggedAs (UnitTest) in
    withTui("") { (tui, _) =>
      tui.getState.graphics shouldBe GraphicsMode.Block
    }

  "NO_COLOR, a multiplexer, and a mode asked for by name" should "ask the terminal nothing" taggedAs (UnitTest) in {
    for config <- Seq(
        auto.copy(noColor = true),
        auto.copy(multiplexer = true),
        auto.copy(graphics = GraphicsChoice.Block),
        auto.copy(graphics = GraphicsChoice.Text)
      )
    do
      withTui(TuiTestTerminal.kittyReply(), config) { (tui, tt) =>
        (tt.output should not).include("\u001b_G")
        (tt.output should not).include(Kitty.CellSizeQuery)
        tui.getState.graphics should not be a[GraphicsMode.Pixel]
      }
    withTui(TuiTestTerminal.kittyReply(), auto.copy(noColor = true))((tui, _) =>
      tui.getState.graphics shouldBe GraphicsMode.Text
    )
    withTui(TuiTestTerminal.kittyReply(), auto.copy(multiplexer = true))((tui, _) =>
      tui.getState.graphics shouldBe GraphicsMode.Block
    )
  }

  "graphics = pixel on a terminal that cannot" should "draw blocks, and say why in the footer" taggedAs (UnitTest) in
    withTui(TuiTestTerminal.PlainReply, auto.copy(graphics = GraphicsChoice.Pixel)) { (tui, tt) =>
      tui.getState.graphics shouldBe GraphicsMode.Block
      TuiTestTerminal.visible(tt.lastFrame) should include("did not answer the graphics query")
    }

  "v" should "go round pixel, block and text, clearing the screen and leaving no image in the modes without" taggedAs (
    UnitTest
  ) in
    withTui(TuiTestTerminal.kittyReply()) { (tui, tt) =>
      tui.handleKey(Key.Char('v'))
      tui.getState.graphics shouldBe GraphicsMode.Block
      tt.graphics.images shouldBe empty
      tt.graphics.placements shouldBe empty
      tt.lastFrame should include("▀")
      tui.handleKey(Key.Char('v'))
      tui.getState.graphics shouldBe GraphicsMode.Text
      (tt.lastFrame should not).include("▀")
      TuiTestTerminal.visible(tt.lastFrame) should include("ETC")
      tui.handleKey(Key.Char('v'))
      tui.getState.graphics shouldBe GraphicsMode.Pixel(TuiFixtures.Cell)
      tt.graphics.images should have size 2
      tt.graphics.placements should have size 2
    }

  "A resize" should "ask the cell's size again, and a new size send the art again at that size" taggedAs (UnitTest) in
    withTui(TuiTestTerminal.kittyReply()) { (tui, tt) =>
      val asked = tt.output.sliding(Kitty.CellSizeQuery.length).count(_ == Kitty.CellSizeQuery)
      tt.terminal.setSize(new org.jline.terminal.Size(150, 46))
      tui.render()
      tt.output.sliding(Kitty.CellSizeQuery.length).count(_ == Kitty.CellSizeQuery) shouldBe asked + 1
      // The same cell: nothing changes. A cell twice the size (a larger font): pixel mode at that size.
      tt.press("\u001b[6;16;8t")
      tui.checkInput(50L).foreach(tui.handleKey)
      tui.getState.graphics shouldBe GraphicsMode.Pixel(TuiFixtures.Cell)
      val before = tt.graphics.uploads
      tt.press("\u001b[6;32;16t")
      val keys = tui.checkInput(50L)
      keys shouldBe Seq(Key.CellSize(16, 32))
      keys.foreach(tui.handleKey) // a report answers nothing and opens nothing
      tui.getState.graphics shouldBe GraphicsMode.Pixel(gfx.CellSize(16, 32))
      tui.getState.overlay shouldBe Overlay.None
      tui.render()
      tt.graphics.uploads should be > before
      tt.graphics.placements should have size 2
    }

  ":graphics" should "set a mode by name" taggedAs (UnitTest) in
    withTui(TuiTestTerminal.kittyReply()) { (tui, tt) =>
      (Key.Char(':') +: "graphics text".map(Key.Char(_)) :+ Key.Enter).foreach(tui.handleKey)
      tui.getState.graphics shouldBe GraphicsMode.Text
      tt.graphics.images shouldBe empty
    }

  "r" should "send and place every image again" taggedAs (UnitTest) in
    withTui(TuiTestTerminal.kittyReply()) { (tui, tt) =>
      val before = tt.graphics.uploads
      tui.handleKey(Key.Char('r'))
      val after = tt.graphics
      after.uploads shouldBe before * 2
      after.images should have size 2
      after.placements should have size 2
    }

  // ---- SC-006: no image left behind ----------------------------------------------------------------------------------

  private def leftBy(leave: (Tui, TuiTestTerminal) => Unit): Unit =
    val tt = new TuiTestTerminal(width = 160, height = 48, queryReply = TuiTestTerminal.kittyReply())
    val tui = tt.tui(auto)
    try
      tui.initialize() shouldBe true
      tui.updateChain(TuiFixtures.chainEtc)
      tui.nextFrame(TuiFixtures.syncedEtc().snapshot)
      tui.render()
      tt.graphics.images should not be empty
      leave(tui, tt)
      tui.isEnabled shouldBe false
    finally
      tui.shutdown()
      tt.close()

  /** The terminal holds nothing of ours, and was told so before the screen was given back. */
  private def clean(written: String): Unit =
    val played = TuiTestTerminal.replay(written)
    played.images shouldBe empty
    played.placements shouldBe empty
    written.lastIndexOf(Kitty.RemoveAll) should be >= 0
    written.lastIndexOf(Kitty.RemoveAll) should be < written.lastIndexOf(Tui.ExitAltScreen)

  "No image" should "outlive the TUI after d" taggedAs (UnitTest) in
    leftBy { (tui, tt) =>
      tui.handleKey(Key.Char('d')) shouldBe TuiCommand.Disable
      clean(tt.output)
    }

  it should "outlive it after q, confirmed" taggedAs (UnitTest) in
    leftBy { (tui, tt) =>
      tui.handleKey(Key.Char('q')) shouldBe TuiCommand.Continue
      tui.handleKey(Key.Char('y')) shouldBe TuiCommand.Quit
      TuiUpdater.restoreThenQuit(tui, () => ())()
      clean(tt.output)
    }

  it should "outlive it after an interrupt: the shutdown hook's teardown" taggedAs (UnitTest) in
    leftBy { (tui, tt) =>
      tui.shutdown()
      clean(tt.output)
    }

  it should "outlive it when JLine closed the terminal first: removed through the process output" taggedAs (UnitTest) in
    leftBy { (tui, tt) =>
      tt.terminal.close()
      tui.render() // finds the terminal closed
      val around = tt.processText
      around should include(Kitty.RemoveAll)
      around.indexOf(Kitty.RemoveAll) should be < around.indexOf(Tui.ExitAltScreen)
    }

  "A TUI that sent no image" should "send no graphics command when it leaves" taggedAs (UnitTest) in {
    val tt = new TuiTestTerminal(width = 160, height = 48)
    val tui = tt.tui(auto.copy(graphics = GraphicsChoice.Block))
    try
      tui.initialize() shouldBe true
      tui.render()
      tui.shutdown()
      (tt.output should not).include("\u001b_G")
    finally tt.close()
  }

  "A directory of art" should "replace the shipped sheets, and a broken one cost only its pictures" taggedAs (UnitTest) in {
    val dir = java.nio.file.Files.createTempDirectory("tui-art")
    try
      // Only the manifest: every sheet is missing, so every sprite is left out and named.
      java.nio.file.Files.writeString(dir.resolve("art.conf"), "art { badge { cols = 16, rows = 8 } }")
      val tt = new TuiTestTerminal(width = 160, height = 48)
      val tui = tt.tui(auto.copy(graphics = GraphicsChoice.Block, artDir = Some(dir.toString)))
      try
        tui.initialize() shouldBe true
        tui.updateChain(TuiFixtures.chainEtc)
        tui.nextFrame(TuiFixtures.syncedEtc().snapshot)
        tui.render() shouldBe false
        // No picture, and the words that stand for it.
        (tt.lastFrame should not).include("▀")
        TuiTestTerminal.visible(tt.lastFrame) should include("ETC")
        (Key.Char(':') +: "art".map(Key.Char(_)) :+ Key.Enter).foreach(tui.handleKey)
        tui.render()
        TuiTestTerminal.visible(tt.lastFrame) should include("Left out")
      finally
        tui.shutdown()
        tt.close()
    finally
      java.nio.file.Files.deleteIfExists(dir.resolve("art.conf"))
      java.nio.file.Files.deleteIfExists(dir)
  }
