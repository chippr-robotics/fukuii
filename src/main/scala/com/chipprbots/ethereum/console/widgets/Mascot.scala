package com.chipprbots.ethereum.console.widgets

import org.jline.utils.AttributedString

import com.chipprbots.ethereum.console.*
import com.chipprbots.ethereum.console.TuiText.fitToWidth
import com.chipprbots.ethereum.console.gfx.Graphic
import com.chipprbots.ethereum.console.gfx.GraphicRegion
import com.chipprbots.ethereum.console.panels.Ctx

/** The mascot, beside a panel (spec 029 FR-018): a status lamp in the panel's bottom-right corner, with the state it
  * shows named in words under it. It takes columns from the panel only where the sidebar is shown and the panel has the
  * rows for it; the larger sprite where the screen is wide and the panel tall enough.
  */
private[console] object Mascot:

  /** A sprite, and the cells it takes. */
  final case class Size(sprite: String, cols: Int, rows: Int)

  val Large: Size = Size("mascot", 32, 16)
  val Small: Size = Size("mascot-small", 16, 8)

  /** The columns a panel keeps for itself beside the large mascot. */
  val MinPanelCols: Int = 80

  /** The columns beside the sprite: one between it and the panel, and the row's last column, which stays blank on every
    * row of the frame (spec 027 FR-001).
    */
  val Margin: Int = 2

  /** The mascot for a panel of `w` columns and `n` rows: none below tier M (contracts/art.md), or without a row for the
    * caption under it.
    */
  def sizeFor(c: Ctx, w: Int, n: Int): Option[Size] =
    if c.screenTier == Tier.S then None
    else if c.screenTier == Tier.L && n >= Large.rows + 1 && w - Large.cols - Margin >= MinPanelCols then Some(Large)
    else if n >= Small.rows + 1 && w - Small.cols - Margin >= 40 then Some(Small)
    else None

  /** `panel` with the mascot in its bottom-right corner, where it fits. */
  def beside(c: Ctx, panel: Panel): Panel =
    panel.copy(
      draw = (w, n) =>
        sizeFor(c, w, n) match
          case None => panel.draw(w, n)
          case Some(size) =>
            val left = w - size.cols - Margin
            Layout.columns(
              panel.draw(left, n).take(n).padTo(n, new AttributedString("")),
              column(c, size, n),
              left,
              size.cols + 1,
              new AttributedString(" ")
            )
      ,
      regions = (w, n) =>
        sizeFor(c, w, n).toSeq
          .map(size => region(c, size).shift(n - size.rows - 1, w - size.cols - 1))
          .filter(_ => shown(c)) ++ panel.regions(w, n)
    )

  /** The mascot keeps still under a question (FR-018): while a dialog is open it is not drawn at all. */
  private def shown(c: Ctx): Boolean = c.state.overlay match
    case Overlay.Dialog(_) => false
    case _                 => true

  /** The mascot's region, at the top-left corner of its own cells. */
  def region(c: Ctx, size: Size): GraphicRegion =
    val pose = Pose.of(c.state)
    GraphicRegion(
      "mascot",
      0,
      0,
      size.cols,
      size.rows,
      Graphic.SpriteFrame(size.sprite, pose.ordinal, Art.frame(c, size.sprite, pose.ordinal)),
      alt = Pose.face(pose)
    )

  /** The mascot's column: blank above, the sprite, and the state it shows in words on the last row. */
  private def column(c: Ctx, size: Size, n: Int): Seq[AttributedString] =
    val pose = Pose.of(c.state)
    val blank = fitToWidth(new AttributedString(""), size.cols)
    val style = pose match
      case Pose.Error                   => c.t.error
      case Pose.Warning | Pose.Asleep   => c.t.warn
      case Pose.Starting | Pose.Syncing => c.t.label
      case _                            => c.t.healthy
    val sprite = if shown(c) then Art.cells(c, region(c, size)) else Seq.fill(size.rows)(blank)
    Seq.fill((n - size.rows - 1).max(0))(blank) ++ sprite :+ Art.centred(Pose.caption(pose), size.cols, style)
