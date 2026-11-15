package com.chipprbots.ethereum.console.panels

import java.time.Duration

import org.jline.utils.AttributedString
import org.jline.utils.AttributedStringBuilder
import org.jline.utils.AttributedStyle

import com.chipprbots.ethereum.console.*
import com.chipprbots.ethereum.console.TuiText.formatShort
import com.chipprbots.ethereum.console.panels.Rows.*

/** The health bars (spec 029 FR-045, research §8.3): a bar a subsystem, as long as the room its nearest threshold
  * leaves, labelled with the check that sets it. A bar's colour is its checks' state, which is the alert row's and
  * nothing else's ([[Health]]), so a bar is never red while the alert row is quiet, or the reverse.
  *
  * Three forms: the strip (one row, on the Overview and where the System view has no room for the list), the list (a
  * row a bar, on the System view, where a bar is selected and Enter opens it), and the screen Enter opens: every check
  * behind the bar, with its value, its threshold, its state and how long it has been in it.
  */
private[console] object HealthPanel:

  /** The list's bar, in cells. */
  val BarCols: Int = 10

  private def style(c: Ctx, state: CheckState): AttributedStyle = state match
    case CheckState.Ok      => c.t.healthy
    case CheckState.Warn    => c.t.warn.bold()
    case CheckState.Error   => c.t.error
    case CheckState.Unknown => c.t.muted

  private def full(g: TuiGlyphs): String = if g.set == GlyphSet.Ascii then "#" else "█"
  private def empty(g: TuiGlyphs): String = if g.set == GlyphSet.Ascii then "-" else "░"

  /** `cols` cells, as many of them full as the room left; a bar with no measure is dots, never an empty or a full bar.
    * Any room at all is at least one full cell, and only no room is none: an empty bar means the threshold is reached.
    */
  def cells(headroom: Option[Double], cols: Int, g: TuiGlyphs): String =
    headroom match
      case None => g.dot.text * cols
      case Some(room) =>
        val filled = if room <= 0 then 0 else (room * cols).round.toInt.max(1).min(cols)
        full(g) * filled + empty(g) * (cols - filled)

  private def percent(headroom: Option[Double]): String =
    headroom.fold("  ? ")(room => f"${(room * 100).round}%3d%%")

  /** The names the strip uses, where a bar's own label is too long for seven of them in 80 columns. */
  private def short(s: Subsystem): String = s match
    case Subsystem.ClLink => "CL"
    case Subsystem.Memory => "Mem"
    case other            => other.label

  /** ` Health P2P ████░ · Chain █████ · …`: every bar, each as wide as lets them all fit (five cells at most, one at
    * least), each in its state's colour. With `selected`, that bar's name is inverted. Where even one cell a bar does
    * not fit, the bars that do are drawn and the count of the rest is said: no bar is cut.
    */
  def strip(c: Ctx, w: Int, selected: Option[Subsystem] = None): AttributedString =
    val bars = c.state.healthBars
    val dot = s" ${c.g.dot.text} "
    val room = w - 1 - (1 + FieldLabelCols)
    def length(n: Int, cols: Int): Int =
      bars.take(n).map(b => short(b.subsystem).length + 1 + cols).sum + dot.length * (n - 1).max(0)
    val cols = (5 to 1 by -1).find(length(bars.size, _) <= room).getOrElse(1)
    val more = (n: Int) => if n < bars.size then s"$dot+${bars.size - n}" else ""
    val shown = (bars.size to 0 by -1).find(n => length(n, cols) + more(n).length <= room).getOrElse(0)
    val sb = new AttributedStringBuilder()
    sb.append(text(" " + "Health".padTo(FieldLabelCols, ' '), c.t.label))
    if bars.isEmpty then sb.append(text("no check has a reading yet", c.t.muted))
    bars.take(shown).zipWithIndex.foreach { (bar, i) =>
      if i > 0 then sb.append(text(dot, c.t.muted))
      val look = style(c, bar.state)
      sb.append(text(short(bar.subsystem), if selected.contains(bar.subsystem) then look.inverse() else look))
      sb.append(text(" " + cells(bar.headroom, cols, c.g), look))
    }
    sb.append(text(more(shown), c.t.muted))
    TuiText.fitToWidth(sb.toAttributedString, w)

  /** A bar's row: the selection's mark, the subsystem, the bar, the room left in percent, and what sets it: the check
    * and what it reads, then where its alert begins. Each is said whole where there is room for it: the threshold goes
    * first, then the reading, and the check's name is kept (cut with the ellipsis only where even it does not fit).
    */
  private def row(c: Ctx, bar: HealthBar, selected: Boolean, w: Int): AttributedString =
    val look = style(c, bar.state)
    val mark = if selected then c.g.arrow.text else " "
    val name = bar.subsystem.label.padTo(FieldLabelCols, ' ')
    val head = concat(
      text(s" $mark ", c.t.value),
      text(name, if selected then c.t.label.inverse() else c.t.label),
      text(" " + cells(bar.headroom, BarCols, c.g), look),
      text(" " + percent(bar.headroom) + "  ", look)
    )
    val room = w - 1 - head.columnLength()
    val dot = s" ${c.g.dot.text} "
    val said = bar.limiting.fold("") { check =>
      val reading = TuiText.external(s"${check.name}: ${check.value}", c.g)
      wholeParts(Seq(reading, check.threshold), dot, room) match
        case Nil  => check.name
        case kept => kept.mkString(dot)
    }
    fit(concat(head, text(said, if bar.state == CheckState.Ok then c.t.value else look)), w, c.g)

  /** A bar's row with nothing selected, for a screen that lists the bars beside something else. */
  def barRow(c: Ctx, bar: HealthBar, w: Int): AttributedString = row(c, bar, selected = false, w)

  /** The System view's bars: a title and a row a bar where the panel has the rows, the rows alone where it has one
    * fewer, and the strip where it has less.
    */
  def list(c: Ctx): Panel =
    val bars = c.state.healthBars
    val selected = c.state.selectedHealth.filter(s => bars.exists(_.subsystem == s))
    def title(w: Int): AttributedString =
      val worst = bars.map(_.state)
      val said =
        if bars.isEmpty then "no check has a reading yet"
        else if worst.contains(CheckState.Error) || worst.contains(CheckState.Warn) then
          val raised = bars.count(b => b.state == CheckState.Error || b.state == CheckState.Warn)
          s"$raised of ${bars.size} raise an alert"
        else s"${bars.size} bars, no alert"
      val left = field("Health", text(said, c.t.value), c.t)
      val right = text("room left before the nearest alert", c.t.muted)
      // The caption is said whole or not at all.
      if left.columnLength() + 2 + right.columnLength() <= w - 1 then leftRight(left, right, w, c.g)
      else fit(left, w, c.g)
    def draw(w: Int, n: Int): Seq[AttributedString] =
      val rows = bars.map(b => row(c, b, selected.contains(b.subsystem), w))
      if n > bars.size then title(w) +: rows
      else if n == bars.size && bars.nonEmpty then rows
      else Seq(strip(c, w, selected))
    Panel(1, bars.size + 1, 2, draw, separated = true)

  /** The screen Enter opens on a bar: the bar, then every check behind it, two rows a check: what it watches and what
    * it reads; then its state and for how long, the room it leaves, and where its alert begins.
    */
  def detail(c: Ctx, subsystem: Subsystem): Seq[Panel] =
    val bar = c.state.healthBars.find(_.subsystem == subsystem)
    val dot = s" ${c.g.dot.text} "
    def head(w: Int): Seq[AttributedString] = bar match
      case None =>
        Seq(fit(field("Health", text(s"${subsystem.label}: no check has a reading now", c.t.muted), c.t), w, c.g))
      case Some(b) =>
        val look = style(c, b.state)
        val set = b.limiting.fold("")(check => s"set by ${check.name}")
        Seq(
          fit(
            concat(
              field("Health", text(subsystem.label.padTo(FieldLabelCols, ' '), c.t.value.bold()), c.t),
              text(" " + cells(b.headroom, BarCols, c.g), look),
              text(" " + percent(b.headroom) + "  ", look),
              text(set, c.t.value)
            ),
            w,
            c.g
          )
        )
    def check(k: HealthCheck, w: Int): Seq[AttributedString] =
      val look = style(c, k.state)
      val since = c.state.healthSince.get(k.key).filter(_._1 == k.state).map(_._2)
      val held = since.fold("")(at => s" for ${formatShort(Duration.between(at, c.state.now).getSeconds.max(0L))}")
      val room = k.room.fold("no measure of room")(r => s"${(r * 100).round}% room")
      val first = concat(
        text(s" ${k.name}", c.t.label),
        text("  ", c.t.value),
        text(TuiText.external(k.value, c.g), if k.state == CheckState.Ok then c.t.value else look)
      )
      val indent = "    "
      val state = s"${k.state.label}$held"
      // The state and the threshold are always said; the room, where there is room for it.
      val parts = Seq(k.threshold, room)
      val left = w - 1 - indent.length - state.length - dot.length
      val kept = wholeParts(parts, dot, left) match
        case Nil   => parts.take(1)
        case whole => whole
      val second = concat(text(indent, c.t.value), text(state, look), text(dot + kept.mkString(dot), c.t.muted))
      Seq(fit(first, w, c.g), fit(second, w, c.g))
    val checks = bar.fold(Seq.empty[HealthCheck])(_.checks)
    Seq(
      vitalsPanel(c, withSummary = true),
      Panel(1, 1, 2, (w, _) => head(w), separated = true),
      Panel(
        2.min(checks.size * 2),
        checks.size * 2,
        3,
        // Whole checks only: a check's second row is never drawn without its first, or the reverse.
        (w, n) => checks.take(n / 2).flatMap(check(_, w)),
        separated = true
      ),
      Panel(
        0,
        1,
        4,
        (w, _) =>
          Seq(
            fit(text(" A bar's colour is the alert row's: these are the rules the alert row reads.", c.t.muted), w, c.g)
          )
      ),
      fillPanel
    )
