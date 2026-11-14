package com.chipprbots.ethereum.console.widgets

import org.jline.utils.AttributedString
import org.jline.utils.AttributedStyle

import com.chipprbots.ethereum.console.*
import com.chipprbots.ethereum.console.TuiText.fitToWidth
import com.chipprbots.ethereum.console.gfx.Blocks
import com.chipprbots.ethereum.console.gfx.Graphic
import com.chipprbots.ethereum.console.gfx.GraphicRegion
import com.chipprbots.ethereum.console.panels.Ctx

/** The cells of a graphic region, by graphics mode (spec 029 FR-011, FR-012): the region is the same rectangle in all
  * three, and only what its cells hold differs.
  *
  *   - pixel: blank cells; the frame writer shows the image over them
  *   - block: the sprite's small sheet as half-block cells
  *   - text: the region's words, on its middle row
  *
  * A sprite the art does not have is drawn as its words in every mode, so a bad sheet costs a picture and no
  * information (contracts/art.md).
  */
private[console] object Art:

  /** `region`'s cells: exactly `region.rows` rows of `region.cols` columns. */
  def cells(c: Ctx, region: GraphicRegion): Seq[AttributedString] =
    val blank = fitToWidth(new AttributedString(""), region.cols)
    def words: Seq[AttributedString] =
      (0 until region.rows).map(i =>
        if i == region.rows / 2 then centred(region.alt, region.cols, c.t.label) else blank
      )
    region.graphic match
      case Graphic.SpriteFrame(name, pose, _) =>
        c.art.sprite(name).filter(_.poses.size > pose) match
          case None => words
          case Some(sprite) =>
            c.state.graphics match
              case GraphicsMode.Text     => words
              case GraphicsMode.Block    => Blocks.render(sprite.loFrame(pose), region.cols, region.rows)
              case GraphicsMode.Pixel(_) => Seq.fill(region.rows)(blank)
      case Graphic.Chart(_, _) =>
        c.state.graphics match
          case GraphicsMode.Pixel(_) => Seq.fill(region.rows)(blank)
          case _                     => words

  /** Whether `region` is drawn as a picture: its sprite is there, and the mode is not text. */
  def pictured(c: Ctx, region: GraphicRegion): Boolean = region.graphic match
    case Graphic.SpriteFrame(name, pose, _) =>
      c.state.graphics != GraphicsMode.Text && c.art.sprite(name).exists(_.poses.size > pose)
    case Graphic.Chart(_, _) => c.state.graphics != GraphicsMode.Text

  /** `text` in the middle of `cols` columns. */
  def centred(text: String, cols: Int, style: AttributedStyle): AttributedString =
    val shown = new AttributedString(text, style)
    val left = ((cols - shown.columnLength()) / 2).max(0)
    fitToWidth(AttributedString.join(new AttributedString(""), new AttributedString(" " * left), shown), cols)

  /** The frame of `pose` of sprite `name` on screen at the state's tick (spec 029 FR-019): in pixel mode the pose's
    * timeline at the frame counter, which pause stops; under reduced motion the first frame. Block and text mode have
    * one frame a pose (FR-017): a sprite changes there only when the state it shows does.
    */
  def frame(c: Ctx, name: String, pose: Int): Int =
    c.state.graphics match
      case GraphicsMode.Pixel(_) if c.state.motion != Motion.Reduced =>
        c.art.sprite(name).flatMap(_.poses.lift(pose)).fold(0)(_.frameAt(c.state.tick))
      case _ => 0
