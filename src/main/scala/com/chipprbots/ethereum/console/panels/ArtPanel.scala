package com.chipprbots.ethereum.console.panels

import org.jline.utils.AttributedString

import com.chipprbots.ethereum.console.*
import com.chipprbots.ethereum.console.gfx.ArtStore
import com.chipprbots.ethereum.console.gfx.Graphic
import com.chipprbots.ethereum.console.gfx.GraphicRegion
import com.chipprbots.ethereum.console.gfx.Sprite
import com.chipprbots.ethereum.console.panels.Rows.*
import com.chipprbots.ethereum.console.widgets.Art

/** The art, a pose of a sprite a page, in the graphics mode on screen (`:art`; contracts/art.md): how a replacement
  * sheet from `fukuii.tui.art-dir` is tried. Every frame of the pose stands side by side in pixel mode; block mode has
  * one frame a pose; text mode shows the words that stand for the picture. What the loader refused is listed under it.
  */
private[console] object ArtPanel:

  /** The rows above the frames: the title, the keys, a blank. */
  val HeadRows: Int = 3

  /** The columns between two frames. */
  val Gap: Int = 2

  /** Every (sprite, pose) the art has, in the contract's order. */
  def pages(art: ArtStore): Seq[(Sprite, Int)] =
    ArtStore.Sprites.flatMap(e => art.sprite(e.name)).flatMap(s => s.poses.indices.map(s -> _))

  def panels(c: Ctx, page: Int): Seq[Panel] =
    val all = pages(c.art)
    val at = if all.isEmpty then 0 else Math.floorMod(page, all.size)
    Seq(Panel(3, 40, 1, (w, n) => draw(c, all, at, w, n), fill = true, regions = (w, n) => regions(c, all, at, w, n)))

  /** The frames of the page's pose that fit `w` columns: all of them in pixel mode, one otherwise. */
  private def frames(c: Ctx, sprite: Sprite, pose: Int, w: Int): Int =
    val wanted = c.state.graphics match
      case GraphicsMode.Pixel(_) => sprite.poses(pose).frames
      case _                     => 1
    wanted.min(((w - 1 + Gap) / (sprite.cols + Gap)).max(0))

  private def regions(c: Ctx, all: Seq[(Sprite, Int)], at: Int, w: Int, n: Int): Seq[GraphicRegion] =
    all.lift(at).toSeq.flatMap { (sprite, pose) =>
      if n < HeadRows + sprite.rows then Nil
      else
        (0 until frames(c, sprite, pose, w)).map { i =>
          GraphicRegion(
            s"art-$i",
            HeadRows,
            1 + i * (sprite.cols + Gap),
            sprite.cols,
            sprite.rows,
            Graphic.SpriteFrame(sprite.name, pose, i),
            alt = s"${sprite.name} ${sprite.poses(pose).name} $i"
          )
        }
    }

  private def draw(c: Ctx, all: Seq[(Sprite, Int)], at: Int, w: Int, n: Int): Seq[AttributedString] =
    val dot = s" ${c.g.dot.text} "
    val keys =
      fit(text("   Up Down another pose   v another mode   r read the sheets again   Esc back", c.t.muted), w, c.g)
    val problems =
      if c.art.problems.isEmpty then Nil
      else
        fit(text(" Left out", c.t.warn.bold()), w, c.g) +:
          c.art.problems.map(p => fit(text(s"   ${TuiText.external(p, c.g)}", c.t.warn), w, c.g))
    all.lift(at) match
      case None =>
        val why =
          if c.state.graphics == GraphicsMode.Text then "text mode loads no art: v switches the graphics mode"
          else "no sprite sheet could be read"
        Seq(fit(text(s" Art   $why", c.t.healthy.bold()), w, c.g), keys, blank) ++ problems
      case Some((sprite, pose)) =>
        val art = sprite.poses(pose)
        val timeline = art.timeline.map((frame, ticks) => s"$frame for $ticks").mkString(", ")
        val title = fit(
          text(
            s" Art   ${sprite.name}$dot${art.name}$dot${sprite.cols} x ${sprite.rows} cells$dot" +
              s"${art.frames} ${if art.frames == 1 then "frame" else "frames"} ($timeline)$dot" +
              s"${at + 1} of ${all.size}$dot${c.state.graphics.label}",
            c.t.healthy.bold()
          ),
          w,
          c.g
        )
        val shown = regions(c, all, at, w, n)
        val body =
          if shown.isEmpty then
            Seq(
              fit(
                text(s"   this sprite needs ${sprite.cols + 1} columns and ${HeadRows + sprite.rows} rows", c.t.muted),
                w,
                c.g
              )
            )
          else
            val cells = shown.map(Art.cells(c, _))
            (0 until sprite.rows).map { row =>
              concat((text(" ", c.t.value) +: cells.flatMap(frame => Seq(frame(row), text(" " * Gap, c.t.value))))*)
            }
        Seq(title, keys, blank) ++ body ++ Seq(blank) ++ problems
