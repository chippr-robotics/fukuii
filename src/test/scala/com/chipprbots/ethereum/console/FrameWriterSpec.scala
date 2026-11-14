package com.chipprbots.ethereum.console

import org.jline.utils.AttributedString
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.console.gfx.Graphic
import com.chipprbots.ethereum.console.gfx.GraphicRegion
import com.chipprbots.ethereum.console.gfx.Kitty
import com.chipprbots.ethereum.testing.Tags.*

/** A frame write sends only what changed (spec 029 FR-017, SC-007): rows, images and placements. */
class FrameWriterSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  private val tt = new TuiTestTerminal(width = 160, height = 48)
  override def afterAll(): Unit = tt.close()

  private val pixel = GraphicsMode.Pixel(TuiFixtures.Cell)
  private def ansi(row: AttributedString): String = row.toAnsi(tt.terminal)
  private def frame(rows: String*): Frame = Frame(rows.map(new AttributedString(_)), animated = false)
  private def write(
      shown: FrameWriter.Shown,
      f: Frame,
      mode: GraphicsMode = GraphicsMode.Text,
      clear: Boolean = false
  ) =
    FrameWriter.write(shown, f, mode, TuiFixtures.art, clear, ansi)

  "The first frame" should "write every row at its own position, from the home position, with no line end" taggedAs (
    UnitTest
  ) in {
    val (bytes, shown) = write(FrameWriter.Blank, frame("one", "two", "three"))
    bytes shouldBe s"${Tui.CursorHome}\u001b[1;1Hone\u001b[2;1Htwo\u001b[3;1Hthree"
    (bytes should not).include("\n")
    shown.rows.map(_.toString) shouldBe Seq("one", "two", "three")
  }

  "An unchanged frame" should "write nothing at all" taggedAs (UnitTest) in {
    val (_, shown) = write(FrameWriter.Blank, frame("one", "two"))
    write(shown, frame("one", "two"))._1 shouldBe empty
  }

  "A changed row" should "be the only row written" taggedAs (UnitTest) in {
    val (_, shown) = write(FrameWriter.Blank, frame("one", "two", "three"))
    val (bytes, next) = write(shown, frame("one", "TWO", "three"))
    bytes shouldBe s"${Tui.CursorHome}\u001b[2;1HTWO"
    next.rows.map(_.toString) shouldBe Seq("one", "TWO", "three")
  }

  "A clear" should "wipe the screen and write every row again" taggedAs (UnitTest) in {
    val (_, shown) = write(FrameWriter.Blank, frame("one", "two"))
    val (bytes, _) = write(shown, frame("one", "two"), clear = true)
    bytes shouldBe s"${Tui.CursorHome}${Tui.ClearScreen}\u001b[1;1Hone\u001b[2;1Htwo"
  }

  private val sprite = GraphicRegion("network", 3, 1, 16, 8, Graphic.SpriteFrame("network-etc", 0, 0), "ETC")
  private val pictured = frame("one", "two").copy(regions = Seq(sprite))

  "Pixel mode" should "send an image once and place it, and nothing for the frame after" taggedAs (UnitTest) in {
    val (bytes, shown) = write(FrameWriter.Blank, pictured, pixel)
    val played = TuiTestTerminal.replay(bytes)
    played.images shouldBe Set(1)
    played.placements shouldBe Set((1, 1))
    played.rows shouldBe Vector("one", "two")
    shown.hasImages shouldBe true
    write(shown, pictured, pixel)._1 shouldBe empty
  }

  it should "send and place every image again after a clear, having freed what the terminal held" taggedAs (UnitTest) in {
    val (_, shown) = write(FrameWriter.Blank, pictured, pixel)
    val (bytes, _) = write(shown, pictured, pixel, clear = true)
    bytes should startWith(Tui.CursorHome + Kitty.RemoveAll + Tui.ClearScreen)
    val played = TuiTestTerminal.replay(bytes)
    played.uploads shouldBe 1
    played.placements shouldBe Set((1, 1))
  }

  it should "leave no image behind when the mode changes" taggedAs (UnitTest) in {
    val (_, shown) = write(FrameWriter.Blank, pictured, pixel)
    val (bytes, next) = write(shown, pictured.copy(regions = Nil), GraphicsMode.Block)
    bytes shouldBe Tui.CursorHome + Kitty.RemoveAll
    next.hasImages shouldBe false
    // A terminal that was sent no image is sent no graphics command.
    (write(FrameWriter.Blank, frame("one"), GraphicsMode.Block)._1 should not).include("\u001b_G")
  }

  // ---- SC-007: the busiest frames on a 160×48 terminal ---------------------------------------------------------------

  private val renderer = TuiRenderer(TuiConfig.default, TuiFixtures.art)

  /** The frames before the count starts: every sprite frame on screen is sent the first time it is shown, once, and the
    * longest timeline is 16 ticks.
    */
  private val WarmUp = 32

  /** The bytes of `ticks` frames of `state`, after the one-time upload of the art. */
  private def bytesOver(state: TuiState, ticks: Int): Int =
    var shown = FrameWriter.Blank
    var total = 0
    for t <- 0 until WarmUp + ticks do
      val s = state.copy(tick = state.tick + t)
      val (bytes, next) = write(shown, renderer.frame(s, 160, 48), s.graphics)
      if t >= WarmUp then total += bytes.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
      shown = next
    total

  "A syncing Overview at 160×48" should "write under 20 KiB a second at 4 frames a second, in pixel and in block mode" taggedAs (
    UnitTest
  ) in {
    for mode <- Seq(pixel, GraphicsMode.Block) do
      val syncing = TuiFixtures.snapStorageMordor().copy(graphics = mode)
      // 40 frames are ten seconds.
      val perSecond = bytesOver(syncing, 40) / 10
      withClue(s"${mode.label}: $perSecond bytes a second: ") {
        perSecond should be < 20 * 1024
      }
  }

  "A node at its head" should "cost a blink of the mascot in pixel mode and nothing in block mode" taggedAs (UnitTest) in {
    val idle = TuiFixtures.syncedEtc()
    bytesOver(idle.copy(graphics = GraphicsMode.Block), 40) shouldBe 0
    // Two placements a blink (shut, open), each a few dozen bytes, and no pixel data.
    val blinking = bytesOver(idle.copy(graphics = pixel), 40)
    blinking should be > 0
    blinking should be < 1024
    // The art itself is sent once: a second pass over the same frames sends no image.
    var shown = FrameWriter.Blank
    val uploads = (0 until 64).map { t =>
      val s = idle.copy(graphics = pixel, tick = t.toLong)
      val (bytes, next) = write(shown, renderer.frame(s, 160, 48), pixel)
      shown = next
      TuiTestTerminal.replay(bytes).uploads
    }
    uploads.sum shouldBe 3 // the icon, the mascot watching, the mascot's blink
    uploads.drop(16).sum shouldBe 0
  }

  "The first pixel frame" should "carry each image on screen once" taggedAs (UnitTest) in {
    val s = TuiFixtures.syncedEtc().copy(graphics = pixel)
    val f = renderer.frame(s, 160, 48)
    f.regions.map(_.id) shouldBe Seq("network", "mascot")
    val played = TuiTestTerminal.replay(write(FrameWriter.Blank, f, pixel)._1)
    played.uploads shouldBe 2
    played.placements should have size 2
  }
