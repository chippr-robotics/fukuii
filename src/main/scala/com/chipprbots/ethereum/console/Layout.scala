package com.chipprbots.ethereum.console

import org.jline.utils.AttributedString
import org.jline.utils.AttributedStringBuilder

import com.chipprbots.ethereum.console.TuiText.fitToWidth

/** A block of rows in a view (spec 027 FR-005, research §7.3).
  *
  * @param minRows
  *   the rows without which the panel is not drawn at all
  * @param preferredRows
  *   the rows it shows when there is room
  * @param priority
  *   lower is more important: minimums, then preferred rows, are handed out in this order
  * @param draw
  *   the panel's lines for (width, rows); [[Layout]] fits whatever it returns to exactly `rows` lines of `width`
  * @param separated
  *   a separator row is drawn above the panel, and counts against the rows
  * @param fill
  *   the panel takes the rows nobody else wants (the recent-log strip)
  */
final case class Panel(
    minRows: Int,
    preferredRows: Int,
    priority: Int,
    draw: (Int, Int) => Seq[AttributedString],
    separated: Boolean = false,
    fill: Boolean = false
)

/** Hands a view's rows to its panels, and draws them (spec 027 FR-005, research §7.3). */
object Layout:

  /** The rows each panel gets, in display order, out of `rows`: first each panel's minimum, in priority order (a panel
    * whose minimum no longer fits gets none); then preferred rows, in priority order; then the first fill panel takes
    * what is left. A separated panel's separator row counts against `rows`. Whatever is still left is padding.
    */
  def allocate(panels: Seq[Panel], rows: Int): Seq[Int] =
    val alloc = Array.fill(panels.size)(0)
    var left = rows.max(0)
    def cost(i: Int, more: Int): Int = more + (if panels(i).separated && alloc(i) == 0 && more > 0 then 1 else 0)
    val byPriority = panels.indices.sortBy(i => (panels(i).priority, i))
    for i <- byPriority do
      val min = panels(i).minRows.max(0)
      if min > 0 && cost(i, min) <= left then
        left -= cost(i, min)
        alloc(i) = min
    for i <- byPriority do
      val p = panels(i)
      if alloc(i) > 0 || p.minRows <= 0 then
        val want = (p.preferredRows - alloc(i)).max(0)
        val extra = if want == 0 then 0 else want.min(left - cost(i, 1) + 1).max(0)
        if extra > 0 then
          left -= cost(i, extra)
          alloc(i) += extra
    panels.indices.find(i => panels(i).fill).foreach { i =>
      val extra = (left - cost(i, 1) + 1).max(0)
      if extra > 0 then
        left -= cost(i, extra)
        alloc(i) += extra
    }
    alloc.toSeq

  /** The panels drawn into exactly `rows` lines of exactly `width` columns. */
  def render(panels: Seq[Panel], width: Int, rows: Int, separator: AttributedString): Seq[AttributedString] =
    val w = width.max(0)
    val blank = new AttributedString("")
    val drawn = panels.zip(allocate(panels, rows)).flatMap { (p, n) =>
      if n <= 0 then Nil
      else
        val body = p.draw(w, n).take(n).padTo(n, blank)
        if p.separated then separator +: body else body
    }
    drawn.take(rows.max(0)).padTo(rows.max(0), blank).map(fitToWidth(_, w))

  /** Two row lists side by side: `left` in `leftCols`, the divider, `right` in `rightCols`. The shorter list is padded,
    * and every cell is fitted by its columns, never by its length.
    */
  def columns(
      left: Seq[AttributedString],
      right: Seq[AttributedString],
      leftCols: Int,
      rightCols: Int,
      divider: AttributedString
  ): Seq[AttributedString] =
    val blank = new AttributedString("")
    (0 until left.size.max(right.size)).map { i =>
      val sb = new AttributedStringBuilder()
      sb.append(fitToWidth(left.lift(i).getOrElse(blank), leftCols))
      sb.append(divider)
      sb.append(fitToWidth(right.lift(i).getOrElse(blank), rightCols))
      sb.toAttributedString
    }
