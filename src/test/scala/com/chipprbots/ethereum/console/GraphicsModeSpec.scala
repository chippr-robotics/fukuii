package com.chipprbots.ethereum.console

import org.scalatest.LoneElement
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.console.gfx.CellSize
import com.chipprbots.ethereum.testing.Tags.*

/** Which graphics mode is drawn (spec 029 FR-011, FR-013), and what the terminal is asked at the start. */
class GraphicsModeSpec extends AnyFlatSpec with Matchers with OptionValues with LoneElement:

  private val full = TuiFixtures.KittyCaps
  private val noGraphics = full.copy(kittyGraphics = false)
  private val noCell = full.copy(cell = None)
  private val pixel = GraphicsMode.Pixel(TuiFixtures.Cell)

  "auto" should "draw pixels only where the terminal answered both questions and is no multiplexer" taggedAs (UnitTest) in {
    GraphicsMode.choose(GraphicsChoice.Auto, full, noColor = false) shouldBe (pixel, None)
    GraphicsMode.choose(GraphicsChoice.Auto, noGraphics, noColor = false) shouldBe (GraphicsMode.Block, None)
    GraphicsMode.choose(GraphicsChoice.Auto, noCell, noColor = false) shouldBe (GraphicsMode.Block, None)
    GraphicsMode.choose(GraphicsChoice.Auto, full.copy(multiplexer = true), noColor = false) shouldBe
      (GraphicsMode.Block, None)
    GraphicsMode.choose(GraphicsChoice.Auto, TerminalCaps.None, noColor = false) shouldBe (GraphicsMode.Text, None)
  }

  "pixel, asked for by name" should "fall back to blocks and say what the terminal did not answer" taggedAs (UnitTest) in {
    GraphicsMode.choose(GraphicsChoice.Pixel, full, noColor = false) shouldBe (pixel, None)
    // Asked for by name it is tried under a multiplexer too: one that passes the answers on gets pixels.
    GraphicsMode.choose(GraphicsChoice.Pixel, full.copy(multiplexer = true), noColor = false)._1 shouldBe pixel
    val (mode, why) = GraphicsMode.choose(GraphicsChoice.Pixel, noGraphics, noColor = false)
    mode shouldBe GraphicsMode.Block
    why.value should include("did not answer the graphics query")
    GraphicsMode.choose(GraphicsChoice.Pixel, noCell, noColor = false)._2.value should include("cell size")
  }

  "block and text" should "be drawn as asked" taggedAs (UnitTest) in {
    GraphicsMode.choose(GraphicsChoice.Block, full, noColor = false) shouldBe (GraphicsMode.Block, None)
    GraphicsMode.choose(GraphicsChoice.Block, noGraphics, noColor = false) shouldBe (GraphicsMode.Block, None)
    GraphicsMode.choose(GraphicsChoice.Text, full, noColor = false) shouldBe (GraphicsMode.Text, None)
  }

  "NO_COLOR, or fewer than 256 colours" should "draw text, and say so to who asked for more" taggedAs (UnitTest) in {
    GraphicsMode.choose(GraphicsChoice.Auto, full, noColor = true) shouldBe (GraphicsMode.Text, None)
    GraphicsMode.choose(GraphicsChoice.Text, full, noColor = true) shouldBe (GraphicsMode.Text, None)
    for choice <- Seq(GraphicsChoice.Pixel, GraphicsChoice.Block) do
      val (mode, why) = GraphicsMode.choose(choice, full, noColor = true)
      mode shouldBe GraphicsMode.Text
      why.value should include("no colour")
    GraphicsMode.choose(GraphicsChoice.Block, full.copy(colors = 16), noColor = false)._1 shouldBe GraphicsMode.Text
  }

  "Block graphics" should "need a UTF-8 locale: without one they are text, and pixels are still pixels" taggedAs (
    UnitTest
  ) in {
    val latin = full.copy(utf8 = false)
    GraphicsMode.choose(GraphicsChoice.Auto, latin, noColor = false) shouldBe (pixel, None)
    GraphicsMode.choose(GraphicsChoice.Auto, latin.copy(kittyGraphics = false), noColor = false) shouldBe
      (GraphicsMode.Text, None)
    val (mode, why) = GraphicsMode.choose(GraphicsChoice.Block, latin, noColor = false)
    mode shouldBe GraphicsMode.Text
    why.value should include("UTF-8")
    GraphicsMode.next(pixel, latin, noColor = false) shouldBe GraphicsMode.Text
    GraphicsMode.next(GraphicsMode.Text, latin, noColor = false) shouldBe pixel
  }

  "v" should "go round the modes this terminal can draw" taggedAs (UnitTest) in {
    GraphicsMode.next(pixel, full, noColor = false) shouldBe GraphicsMode.Block
    GraphicsMode.next(GraphicsMode.Block, full, noColor = false) shouldBe GraphicsMode.Text
    GraphicsMode.next(GraphicsMode.Text, full, noColor = false) shouldBe pixel
    // No pixels on this terminal: block and text only.
    GraphicsMode.next(GraphicsMode.Text, noGraphics, noColor = false) shouldBe GraphicsMode.Block
    GraphicsMode.next(GraphicsMode.Block, noGraphics, noColor = false) shouldBe GraphicsMode.Text
    // No colour: text stays text.
    GraphicsMode.next(GraphicsMode.Text, full, noColor = true) shouldBe GraphicsMode.Text
  }

  "The setting" should "be auto, pixel, block or text, in any case" taggedAs (UnitTest) in {
    GraphicsChoice.parse("AUTO") shouldBe Some(GraphicsChoice.Auto)
    GraphicsChoice.parse(" pixel ") shouldBe Some(GraphicsChoice.Pixel)
    GraphicsChoice.parse("Block") shouldBe Some(GraphicsChoice.Block)
    GraphicsChoice.parse("text") shouldBe Some(GraphicsChoice.Text)
    GraphicsChoice.parse("sixel") shouldBe None
    Seq(pixel, GraphicsMode.Block, GraphicsMode.Text).map(_.label) shouldBe Seq("pixel", "block", "text")
  }

  "A multiplexer" should "be told from the environment: TMUX, or a TERM of tmux or screen" taggedAs (UnitTest) in {
    TerminalCaps.multiplexer(Map("TMUX" -> "/tmp/tmux-1000/default,1,0")) shouldBe true
    TerminalCaps.multiplexer(Map("TERM" -> "tmux-256color")) shouldBe true
    TerminalCaps.multiplexer(Map("TERM" -> "screen.xterm-256color")) shouldBe true
    TerminalCaps.multiplexer(Map("TERM" -> "xterm-kitty", "TMUX" -> "")) shouldBe false
    TerminalCaps.multiplexer(Map.empty) shouldBe false
  }

  // ---- the setting ---------------------------------------------------------------------------------------------------

  "fukuii.tui.graphics and art-dir" should "be read from the configuration, and a wrong mode refused by name" taggedAs (
    UnitTest
  ) in {
    import com.typesafe.config.ConfigException
    import com.typesafe.config.ConfigFactory
    TuiSettings.fromConfig(ConfigFactory.empty()).graphics shouldBe GraphicsChoice.Auto
    TuiSettings.fromConfig(ConfigFactory.empty()).artDir shouldBe None
    val set = TuiSettings.fromConfig(ConfigFactory.parseString("graphics = Block\nart-dir = \" /srv/art \""))
    set.graphics shouldBe GraphicsChoice.Block
    set.artDir shouldBe Some("/srv/art")
    val refused = intercept[ConfigException.BadValue](
      TuiSettings.fromConfig(ConfigFactory.parseString("graphics = sixel"))
    )
    refused.getMessage should include("fukuii.tui.graphics")
    refused.getMessage should include(GraphicsChoice.Accepted)
    // The shipped file names the default.
    val shipped = ConfigFactory.parseResources("conf/base/tui.conf").getConfig("tui")
    TuiSettings.fromConfig(shipped).graphics shouldBe GraphicsChoice.Auto
    TuiSettings.fromConfig(shipped).artDir shouldBe None
  }

  "FUKUII_TUI_GRAPHICS and FUKUII_TUI_ART_DIR" should "override the configuration; a wrong mode is a warning" taggedAs (
    UnitTest
  ) in {
    val configured = TuiSettings(graphics = GraphicsChoice.Block, artDir = Some("/srv/art"))
    val none = TuiEnv.from(Map.empty, Map.empty)
    none.applyTo(configured).graphics shouldBe GraphicsChoice.Block
    none.applyTo(configured).artDir shouldBe Some("/srv/art")
    val set = TuiEnv.from(Map(TuiEnv.GraphicsVar -> "text", TuiEnv.ArtDirVar -> "/tmp/sheets"), Map.empty)
    set.warnings shouldBe empty
    set.applyTo(configured).graphics shouldBe GraphicsChoice.Text
    set.applyTo(configured).artDir shouldBe Some("/tmp/sheets")
    val wrong = TuiEnv.from(Map(TuiEnv.GraphicsVar -> "sixel"), Map.empty)
    wrong.warnings.loneElement should (include(TuiEnv.GraphicsVar).and(include("sixel")).and(include("auto, pixel")))
    wrong.applyTo(configured).graphics shouldBe GraphicsChoice.Block
  }

  "The environment" should "say whether the terminal is a multiplexer and the locale UTF-8" taggedAs (UnitTest) in {
    val tmux = TuiEnv.from(Map("TMUX" -> "/tmp/tmux-1000/default,1,0", "LANG" -> "en_US.UTF-8"), Map.empty)
    tmux.applyTo(TuiSettings()).multiplexer shouldBe true
    tmux.applyTo(TuiSettings()).utf8Locale shouldBe true
    val plain = TuiEnv.from(Map("LANG" -> "C"), Map.empty)
    plain.applyTo(TuiSettings()).multiplexer shouldBe false
    plain.applyTo(TuiSettings()).utf8Locale shouldBe false
  }

  // ---- what the terminal is asked ------------------------------------------------------------------------------------

  "The terminal's answers" should "be read: graphics understood, and the size of a cell" taggedAs (UnitTest) in {
    TerminalQuery.parse(TuiTestTerminal.kittyReply(10, 20)) shouldBe (true, Some(CellSize(10, 20)))
    TerminalQuery.parse(TuiTestTerminal.PlainReply) shouldBe (false, None)
    // An error answer to the graphics query is not support.
    TerminalQuery.parse("\u001b_Gi=31;ENOTSUP\u001b\\\u001b[?62;c") shouldBe (false, None)
    // A cell no terminal has is not believed.
    TerminalQuery.parse("\u001b[6;0;0t")._2 shouldBe None
    TerminalQuery.parse("\u001b[6;9999;9999t")._2 shouldBe None
    // The report is input: a cell larger than any display has would have the art enlarged without bound.
    TerminalQuery.parse("\u001b[6;512;256t")._2 shouldBe None
    TerminalQuery.parse("\u001b[6;128;64t")._2 shouldBe Some(CellSize(64, 128))
    gfx.PixelLayer.scale(CellSize(64, 128)) shouldBe gfx.PixelLayer.MaxScale
    gfx.PixelLayer.scale(CellSize(4096, 4096)) shouldBe gfx.PixelLayer.MaxScale
    TerminalQuery.complete(TuiTestTerminal.PlainReply) shouldBe true
    TerminalQuery.complete("\u001b_Gi=31;OK\u001b\\") shouldBe false
  }

  "Asking a terminal" should "read both answers of one that draws pixels, and none of them is left for the keys" taggedAs (
    UnitTest
  ) in {
    val tt = new TuiTestTerminal(queryReply = TuiTestTerminal.kittyReply())
    try
      val answers = TerminalQuery.run(tt.terminal, timeoutMillis = 2000)
      (answers.graphics, answers.cell) shouldBe (true, Some(TuiFixtures.Cell))
      answers.raw shouldBe TuiTestTerminal.kittyReply()
      tt.output should include(TerminalQuery.Questions)
      tt.terminal.reader().read(20L) shouldBe org.jline.utils.NonBlockingReader.READ_EXPIRED
    finally tt.close()
  }

  "A cell-size report" should "be nothing to a decoder that did not ask, and a report, never a key, to one that did" taggedAs (
    UnitTest
  ) in {
    val report = "\u001b[6;20;10t"
    KeyDecoder.decode(report) shouldBe empty
    val asking = new KeyDecoder(cellReports = true)
    KeyDecoder.decode(report, asking) shouldBe Seq(Key.CellSize(10, 20))
    KeyDecoder.decode(s"a${report}b", asking) shouldBe Seq(Key.Char('a'), Key.CellSize(10, 20), Key.Char('b'))
    // A cell no terminal has, and any other report ending in t, is nothing.
    KeyDecoder.decode("\u001b[6;0;0t", asking) shouldBe empty
    KeyDecoder.decode("\u001b[8;48;160t", asking) shouldBe empty
    // It changes no state: not Help, not a dialog, not the notice.
    val ctx = UpdateCtx(page = 10, configuredMotion = Motion.Full)
    for state <- Seq(
        TuiFixtures.syncedEtc().openHelp,
        TuiFixtures.syncedEtc().copy(overlay = Overlay.Dialog(DialogKind.Quit)),
        TuiFixtures.syncedEtc().copy(notice = Some("said"))
      )
    do Update(state, Key.CellSize(10, 20), ctx) shouldBe (state, Nil)
  }

  "A sequence the input went quiet in the middle of" should "be kept, so that its rest is never typed as keys" taggedAs (
    UnitTest
  ) in {
    import KeyDecoder.State
    val decoder = new KeyDecoder()
    def feed(state: State, text: String): (State, Seq[Key]) =
      text.foldLeft((state, Seq.empty[Key])) { case ((s, keys), c) =>
        val (next, more) = decoder.step(s, c)
        (next, keys ++ more)
      }
    // Half of Ctrl-Left, a pause, the other half: one key. Read from the ground the rest would be `5` and `D`: detach.
    val (half, none) = feed(State.Ground, "\u001b[1;")
    none shouldBe empty
    val (kept, idleKeys) = decoder.idle(half)
    idleKeys shouldBe empty
    kept shouldBe half
    feed(kept, "5D") shouldBe (State.Ground, Seq(Key.Left))
    // The same inside a string: the rest of a graphics reply.
    val (inString, _) = feed(State.Ground, "\u001b_Gi=31")
    feed(decoder.idle(inString)._1, ";OK\u001b\\q")._2 shouldBe Seq(Key.Char('q'))
    // A sequence that never ends is given up after its wait, and the keyboard is the operator's again.
    decoder.abandon(half) shouldBe State.Ground
    decoder.abandon(inString) shouldBe State.Ground
    KeyDecoder.waitMillis(half) shouldBe KeyDecoder.SequenceWaitMillis
    KeyDecoder.waitMillis(State.Pasting("", 0, dropped = false)) shouldBe KeyDecoder.PasteWaitMillis
    // A lone Esc is still the Esc key.
    decoder.idle(State.Escape) shouldBe (State.Ground, Seq(Key.Esc))
  }

  "The one-character introducers, and a report with a private marker" should "start a sequence, and be no key" taggedAs (
    UnitTest
  ) in {
    KeyDecoder.decode("\u009bD") shouldBe Seq(Key.Left) // CSI as one character
    KeyDecoder.decode("\u009b?62;4c") shouldBe empty
    KeyDecoder.decode("\u009fGi=31;OK\u001b\\x") shouldBe Seq(Key.Char('x')) // APC as one character
    KeyDecoder.decode("\u001b[?1;2A") shouldBe empty // not Up
    KeyDecoder.decode("\u001b[<0;10;20M") shouldBe empty
    KeyDecoder.decode("\u001b[1;2A") shouldBe Seq(Key.Up)
  }

  it should "stop at the device attributes of a terminal that knows no graphics" taggedAs (UnitTest) in {
    val tt = new TuiTestTerminal(queryReply = TuiTestTerminal.PlainReply)
    try
      val started = System.nanoTime()
      val answers = TerminalQuery.run(tt.terminal, timeoutMillis = 5000)
      (answers.graphics, answers.cell) shouldBe (false, None)
      // The reply ended the wait: nowhere near the bound.
      (System.nanoTime() - started) / 1_000_000L should be < 4000L
    finally tt.close()
  }

  it should "give up at its bound on a terminal that answers nothing" taggedAs (UnitTest) in {
    val tt = new TuiTestTerminal()
    try TerminalQuery.run(tt.terminal, timeoutMillis = 30) shouldBe TerminalQuery.NotAsked
    finally tt.close()
  }
