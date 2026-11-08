package com.chipprbots.ethereum.console

import scala.annotation.unused

import org.jline.utils.AttributedString
import org.jline.utils.AttributedStringBuilder
import org.jline.utils.AttributedStyle

/** What a worm row shows: a fraction of a known total, or one of the labelled states of an unknown total. The TUI twin
  * of [[com.chipprbots.ethereum.blockchain.sync.WormToBrainBar]]'s `renderKnown` and `renderUnknown`.
  */
enum WormProgress:
  /** Done / total; values outside [0, 1] are clamped, and 1 or more draws [[Complete]]. */
  case Known(fraction: Double)
  case Queued, Active, Complete

/** The worm-to-brain bar the logs draw, for the TUI: budgeted in columns, in either glyph set (spec 027 FR-006,
  * research §4.1).
  *
  * A known total draws `[`, the track, `]`, the brain and the percentage padded to 5 columns:
  * {{{
  *   [==========🪱.........]🧠 52%
  * }}}
  * The track has `slots` cells, one of them the worm, at `(p × (slots − 1)).toInt` exactly as
  * `WormToBrainBar.renderKnown` places it. With 20 slots and the emoji set the bar is the log's bar, character for
  * character. An unknown total draws a label of fixed width in both sets (`🪱[QUEUED]🧠`, `🪱[ACTIVE]🧠`, `🪱[COMPLETE
  * ✓]🧠`). Every bar is exactly `budget` columns.
  *
  * `WormToBrainBar`, the log version, is not changed. Motion (the ripple, the `[ACTIVE…]` pulse, the stalled worm)
  * arrives in slice 2; `tick` is already a parameter so that the call sites do not change then.
  */
object WormBar:

  /** Columns after the brain: the percentage, `" 100%"`. */
  val PercentCols: Int = 5

  /** The two brackets around the track. */
  private val BracketCols = 2

  /** The column budget of a bar whose track has `slots` slots. */
  def budgetFor(slots: Int, glyphs: TuiGlyphs): Int =
    (slots - 1) + BracketCols + glyphs.worm.cols + glyphs.brain.cols + PercentCols

  /** The slots of the track a `budget`-column bar draws (the worm is one of them); below 2, no track fits. */
  def slotsFor(budget: Int, glyphs: TuiGlyphs): Int =
    budget - BracketCols - glyphs.worm.cols - glyphs.brain.cols - PercentCols + 1

  /** The worm's cell: `(p × (slots − 1)).toInt`, as `WormToBrainBar.renderKnown` computes it. */
  def wormAt(fraction: Double, slots: Int): Int = (clamp(fraction) * (slots - 1)).toInt

  private def clamp(fraction: Double): Double =
    if fraction.isNaN then 0.0 else fraction.max(0.0).min(1.0)

  def render(
      budget: Int,
      progress: WormProgress,
      @unused tick: Long,
      glyphs: TuiGlyphs,
      theme: TuiTheme
  ): AttributedString =
    val bar = progress match
      case WormProgress.Known(f) if clamp(f) < 1.0 => known(budget, clamp(f), glyphs, theme)
      case WormProgress.Known(_) | WormProgress.Complete =>
        labelled(budget, s"[COMPLETE ${glyphs.check.text}]", theme.healthy, glyphs, theme)
      case WormProgress.Queued => labelled(budget, "[QUEUED]", theme.muted, glyphs, theme)
      case WormProgress.Active => labelled(budget, "[ACTIVE]", theme.value, glyphs, theme)
    TuiText.fitToWidth(bar, budget)

  private def known(budget: Int, p: Double, g: TuiGlyphs, theme: TuiTheme): AttributedString =
    val percent = s" ${(p * 100).toInt}%"
    val slots = slotsFor(budget, g)
    if slots < 2 then new AttributedString(percent.trim, theme.value)
    else
      val at = wormAt(p, slots)
      val sb = new AttributedStringBuilder()
      sb.append("[", theme.muted)
      sb.append("=" * at, theme.healthy)
      sb.append(g.worm.text, theme.healthy)
      sb.append("." * (slots - 1 - at), theme.muted)
      sb.append("]", theme.muted)
      sb.append(g.brain.text, theme.healthy)
      sb.append(percent + " " * (PercentCols - percent.length).max(0), theme.value)
      sb.toAttributedString

  /** A fixed-width label: `worm[TEXT]brain` when it fits the budget, else the bracketed text alone. */
  private def labelled(
      budget: Int,
      text: String,
      style: AttributedStyle,
      g: TuiGlyphs,
      theme: TuiTheme
  ): AttributedString =
    val sb = new AttributedStringBuilder()
    if g.worm.cols + new AttributedString(text).columnLength() + g.brain.cols <= budget then
      sb.append(g.worm.text, theme.healthy)
      sb.append(text, style)
      sb.append(g.brain.text, theme.healthy)
    else sb.append(text, style)
    sb.toAttributedString
