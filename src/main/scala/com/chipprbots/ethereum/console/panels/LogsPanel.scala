package com.chipprbots.ethereum.console.panels

import java.util.Locale

import org.jline.utils.AttributedString

import com.chipprbots.ethereum.console.*
import com.chipprbots.ethereum.console.TuiText.formatNumber
import com.chipprbots.ethereum.console.panels.Rows.*

/** The log tail (spec 027 FR-021, research §2.1, §3.2–§3.6): the Logs view, and the Recent strips the Overview and the
  * Sync view end with. Every line comes from [[TuiLogRingAppender]], sanitised on capture, and is drawn through
  * [[TuiText.external]] again, so the ASCII set draws ASCII only and nothing a log line quotes reaches the terminal.
  */
private[console] object LogsPanel:

  /** Loggers under this name are the sync's: the "Recent (sync)" strip keeps only their lines (research §7.4). */
  val SyncLoggers: String = "com.chipprbots.ethereum.blockchain.sync"

  /** The Logs view's rows that are not log lines, at every tier: the header, its separator, the alert row, the vitals
    * (two rows), the separator and title above the lines, and the footer. `+` and `-` move by the rest, one page.
    */
  val ChromeRows: Int = 8

  /** The Recent strips' lines at tiers S and M (research §3.1); at L the strip takes the rows left. */
  def recentLines(tier: Tier): Int = if tier == Tier.S then 6 else 10

  /** The Logs view: the vitals and the sync summary, then the lines, which take the rest of the screen. */
  def panels(c: Ctx): Seq[Panel] =
    Seq(
      vitalsPanel(c, withSummary = true),
      Panel(2, 2, 2, (w, n) => view(c, w, n), separated = true, fill = true)
    )

  /** A Recent strip: its title, then the newest lines `keep` accepts. */
  def recent(c: Ctx, title: String, keep: LogLine => Boolean): Panel =
    Panel(
      2,
      1 + recentLines(c.tier),
      6,
      (w, n) => strip(c, title, keep, w, n),
      separated = true,
      fill = c.tier == Tier.L
    )

  /** The lines on screen, as `(from, until)` indices into `size` lines: `rows` of them, `scroll` lines back from the
    * newest. A view scrolled past the oldest line shows the oldest page, so the window is full when there are enough
    * lines.
    */
  def window(size: Int, scroll: Int, rows: Int): (Int, Int) =
    val n = rows.max(0)
    val until = (size - scroll.max(0)).max(n.min(size)).min(size)
    ((until - n).max(0), until)

  private def view(c: Ctx, w: Int, rows: Int): Seq[AttributedString] =
    val s = c.state
    val lines = s.visibleLogs
    val (from, until) = window(lines.size, s.logScroll, rows - 1)
    val dot = c.g.dot.text
    val below = lines.size - until
    val position = if below <= 0 then "newest at the bottom" else s"${formatNumber(below.toLong)} newer below"
    val filter = s.filterOf(TuiView.Logs)
    val counted =
      if filter.isEmpty then s"${formatNumber(s.logs.size.toLong)} lines"
      else
        s"${formatNumber(lines.size.toLong)} of ${formatNumber(s.logs.size.toLong)} lines match '${TuiText.external(filter, c.g)}'"
    val title = fit(
      text(
        s" Logs   $counted, ${c.config.logLevel} and above $dot $position $dot " +
          "- older  + newer  / filter",
        c.t.healthy.bold()
      ),
      w,
      c.g
    )
    if s.logs.isEmpty then Seq(title, fit(text("   no log line yet", c.t.muted), w, c.g))
    else if lines.isEmpty then Seq(title, fit(text("   no line matches the filter", c.t.muted), w, c.g))
    else title +: lines.slice(from, until).map(row(c, _, w))

  private def strip(c: Ctx, title: String, keep: LogLine => Boolean, w: Int, rows: Int): Seq[AttributedString] =
    val head = fit(text(s" $title", c.t.healthy.bold()), w, c.g)
    // From the newest, so a ring of 10,000 lines is not filtered whole for the handful on screen.
    val lines = c.state.logs.reverseIterator.filter(keep).take((rows - 1).max(0)).toVector.reverse
    if lines.isEmpty then Seq(head, fit(text("   no log line yet", c.t.muted), w, c.g))
    else head +: lines.map(row(c, _, w))

  /** ` 12:04:51 INFO  Imported block …`: the time, the level padded to five, the text in the level's colour. */
  def row(c: Ctx, line: LogLine, w: Int): AttributedString =
    val style = line.level match
      case "ERROR" => c.t.error
      case "WARN"  => c.t.warn
      case _       => c.t.value
    fit(
      concat(
        text(s" ${line.time} ", c.t.muted),
        text(s"${line.level.toUpperCase(Locale.ROOT).padTo(5, ' ')} ", style),
        text(TuiText.external(line.text, c.g), style)
      ),
      w,
      c.g
    )
