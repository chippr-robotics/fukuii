package com.chipprbots.ethereum.console

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

/** The worm-to-brain bar the logs draw, for the TUI: budgeted in columns, in either glyph set, moved by `tick` (spec
  * 027 FR-006, research §4.1).
  *
  * A known total draws `[`, the track, `]`, the brain and the percentage padded to 5 columns:
  * {{{
  *   [==========🪱.........]🧠 52%
  * }}}
  * The track has `slots` cells, one of them the worm, at `(p × (slots − 1)).toInt` exactly as
  * `WormToBrainBar.renderKnown` places it. With 20 slots, the emoji set and no motion the bar is the log's bar,
  * character for character. An unknown total draws a label of fixed width in both sets (`🪱[QUEUED]🧠`, `🪱[ACTIVE]🧠`
  * padded to [[PulseCols]], `🪱[COMPLETE ✓]🧠`). Every bar is exactly `budget` columns, at every tick.
  *
  * Motion, under [[Motion.Full]] only, and only from `tick`:
  *   - the ripple: the `=` at cell `wormAt − 1 − (tick mod 3)` is drawn `~`, when `wormAt ≥ 2` and the bar is not
  *     stalled;
  *   - the pulse: `[ACTIVE]`, `[ACTIVE.]`, `[ACTIVE..]`, `[ACTIVE...]`, frame `tick mod 4`.
  *
  * A stalled bar (progress unchanged for [[TuiState.StallAfter]]) has no ripple, draws the stalled glyph in the two
  * cells after the worm (before it, when they do not fit), and draws its track in the warn style.
  *
  * `WormToBrainBar`, the log version, is not changed.
  */
object WormBar:

  /** Columns after the brain: the percentage, `" 100%"`. */
  val PercentCols: Int = 5

  /** The `[ACTIVE…]` label's columns at every frame of its pulse: `[ACTIVE...]`. */
  val PulseCols: Int = 11

  /** The spinner's frames, the same in both glyph sets (§4.2). */
  val SpinnerFrames: String = "|/-\\"

  /** The two brackets around the track. */
  private val BracketCols = 2

  /** The ripple's period in ticks: after this many ticks it draws the same cell again. The renderer's lookahead is one
    * less: a frame that equals each of the next `RipplePeriod - 1` ticks' frames equals every later one.
    */
  private[console] val RipplePeriod = 3

  /** The column budget of a bar whose track has `slots` slots. */
  def budgetFor(slots: Int, glyphs: TuiGlyphs): Int =
    (slots - 1) + BracketCols + glyphs.worm.cols + glyphs.brain.cols + PercentCols

  /** The slots of the track a `budget`-column bar draws (the worm is one of them); below 2, no track fits. */
  def slotsFor(budget: Int, glyphs: TuiGlyphs): Int =
    budget - BracketCols - glyphs.worm.cols - glyphs.brain.cols - PercentCols + 1

  /** The worm's cell: `(p × (slots − 1)).toInt`, as `WormToBrainBar.renderKnown` computes it. */
  def wormAt(fraction: Double, slots: Int): Int = (clamp(fraction) * (slots - 1)).toInt

  /** The ripple's cell at `tick`, for a worm at `wormAt` (research §4.1). */
  def rippleAt(wormAt: Int, tick: Long): Int = (wormAt - 1 - Math.floorMod(tick, RipplePeriod.toLong).toInt).max(0)

  /** The `[ACTIVE…]` label at `tick`, padded to [[PulseCols]]: it pulses only under full motion. */
  def pulse(tick: Long, motion: Motion): String =
    val dots = if motion == Motion.Full then Math.floorMod(tick, 4L).toInt else 0
    s"[ACTIVE${"." * dots}]".padTo(PulseCols, ' ')

  /** The spinner next to an in-flight status: `|/-\` by `tick mod 4`, or the set's static dot without full motion. */
  def spinner(tick: Long, motion: Motion, glyphs: TuiGlyphs): String =
    if motion == Motion.Full then SpinnerFrames.charAt(Math.floorMod(tick, 4L).toInt).toString else glyphs.dot.text

  private def clamp(fraction: Double): Double =
    if fraction.isNaN then 0.0 else fraction.max(0.0).min(1.0)

  def render(
      budget: Int,
      progress: WormProgress,
      tick: Long,
      glyphs: TuiGlyphs,
      theme: TuiTheme,
      motion: Motion = Motion.Full,
      stalled: Boolean = false
  ): AttributedString =
    val bar = progress match
      case WormProgress.Known(f) if clamp(f) < 1.0 => known(budget, clamp(f), tick, glyphs, theme, motion, stalled)
      case WormProgress.Known(_) | WormProgress.Complete =>
        labelled(budget, s"[COMPLETE ${glyphs.check.text}]", theme.healthy, glyphs, theme)
      case WormProgress.Queued => labelled(budget, "[QUEUED]", theme.muted, glyphs, theme)
      case WormProgress.Active => labelled(budget, pulse(tick, motion), theme.value, glyphs, theme)
    TuiText.fitToWidth(bar, budget)

  private def known(
      budget: Int,
      p: Double,
      tick: Long,
      g: TuiGlyphs,
      theme: TuiTheme,
      motion: Motion,
      stalled: Boolean
  ): AttributedString =
    val percent = s" ${(p * 100).toInt}%"
    val slots = slotsFor(budget, g)
    if slots < 2 then new AttributedString(percent.trim, theme.value)
    else
      val at = wormAt(p, slots)
      val after = slots - 1 - at
      val track = if stalled then theme.warn else theme.healthy
      // The stalled glyph takes two one-column cells: after the worm when they are there, else before it.
      val zAfter = stalled && after >= g.stalled.cols
      val zBefore = stalled && !zAfter && at >= g.stalled.cols
      val done = if zBefore then at - g.stalled.cols else at
      val ripple = if motion == Motion.Full && !stalled && at >= 2 then Some(rippleAt(at, tick)) else None
      val sb = new AttributedStringBuilder()
      sb.append("[", theme.muted)
      sb.append((0 until done).map(i => if ripple.contains(i) then '~' else '=').mkString, track)
      if zBefore then sb.append(g.stalled.text, theme.warn)
      sb.append(g.worm.text, track)
      if zAfter then
        sb.append(g.stalled.text, theme.warn)
        sb.append("." * (after - g.stalled.cols), theme.muted)
      else sb.append("." * after, theme.muted)
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
