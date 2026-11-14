package com.chipprbots.ethereum.console

import org.jline.utils.AttributedString

import com.chipprbots.ethereum.console.gfx.ArtStore
import com.chipprbots.ethereum.console.gfx.Kitty
import com.chipprbots.ethereum.console.gfx.PixelLayer

/** Turns a frame into the bytes that change the screen into it (spec 029 FR-017, research §5.3): only the rows that
  * differ from what the terminal shows are written, and in pixel mode only the images and placements that differ.
  *
  * Pure: what the terminal shows is a value, [[FrameWriter.Shown]], handed in and handed back.
  */
object FrameWriter:

  /** What the terminal shows, as far as the TUI knows: its rows, and its images. */
  final case class Shown(rows: IndexedSeq[AttributedString], layer: PixelLayer):
    /** Whether the terminal holds an image of ours. */
    def hasImages: Boolean = layer.images.nonEmpty

  /** A terminal that shows nothing of ours: every row is written, every image sent. */
  val Blank: Shown = Shown(Vector.empty, PixelLayer.Empty)

  /** The bytes that turn `shown` into `frame`, and what the terminal shows after them. Nothing at all when nothing
    * differs.
    *
    * Each changed row is written at its own position, so the cursor never wraps or scrolls, and every write starts at
    * the home position. `clear` wipes the screen first (a resize, `r`, a change of graphics mode): the terminal then
    * holds no image, and every one on screen is sent and placed again (FR-016).
    *
    * @param ansi
    *   a row as the terminal takes it
    */
  def write(
      shown: Shown,
      frame: Frame,
      mode: GraphicsMode,
      art: ArtStore,
      clear: Boolean,
      ansi: AttributedString => String
  ): (String, Shown) =
    val out = new StringBuilder
    val before =
      if clear then
        if shown.hasImages then out.append(Kitty.RemoveAll)
        out.append(Tui.ClearScreen)
        Blank
      else shown
    val rows = frame.lines.toIndexedSeq
    rows.indices.foreach { i =>
      if !before.rows.lift(i).contains(rows(i)) then out.append(s"\u001b[${i + 1};1H").append(ansi(rows(i)))
    }
    val layer = mode match
      case GraphicsMode.Pixel(cell) =>
        val (bytes, next) = PixelLayer.diff(before.layer, frame.regions, cell, art)
        out.append(bytes)
        next
      case _ =>
        // The mode left pixel without a clear: nothing of ours may stay on screen.
        if before.hasImages then out.append(Kitty.RemoveAll)
        PixelLayer.Empty
    (if out.isEmpty then "" else Tui.CursorHome + out.toString, Shown(rows, layer))
