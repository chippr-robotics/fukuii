package com.chipprbots.ethereum.console

import java.time.Duration

import org.jline.utils.AttributedString
import org.jline.utils.AttributedStringBuilder

/** How bad an alert is: a warning (yellow) or an error (red). */
enum Severity:
  case Warn, Error

/** One condition the alert row lists. */
final case class Alert(severity: Severity, text: String)

/** The alert row: research §2.3's conditions, with their thresholds and severities (spec 027 FR-016, US3). Every
  * duration is measured on the snapshot's clock (`TuiState.now`), never on ticks, so the row is the same at every tick
  * and every pace, and is never animated (FR-002).
  *
  * Slice 5 added the consensus-layer rows (ETH) and the mining row (ETC). They read what the probe put in
  * `LocalSample.cl` and `LocalSample.mining`, so an ETC node has no consensus-layer row and an ETH node no mining one.
  */
object Alerts:

  /** How long a reorg stays on the alert row: long enough to be seen by someone who looks up every few minutes. The
    * Chain view lists it for as long as the TUI runs.
    */
  val ReorgAlertFor: Duration = Duration.ofMinutes(10)

  /** At most this many conditions are listed; the badge counts them all. */
  val MaxListed: Int = 3

  /** Columns of the badge, before the listed conditions. */
  private val BadgeCols = 12

  /** Every condition that holds, errors first, each severity in §2.3's order, then spec 029's four. They are the alerts
    * of [[Health.checks]], so the row and the health bars are one reading (spec 029 FR-045).
    */
  def of(state: TuiState): Seq[Alert] =
    val all = state.healthChecks.flatMap(_.alert)
    all.filter(_.severity == Severity.Error) ++ all.filter(_.severity == Severity.Warn)

  /** The row: `✓ no alerts`, or the badge with the count, then up to [[MaxListed]] conditions, as many as fit whole.
    * Exactly `width`.
    */
  def row(state: TuiState, width: Int, g: TuiGlyphs, t: TuiTheme): AttributedString =
    val alerts = of(state)
    val errors = alerts.count(_.severity == Severity.Error)
    val warns = alerts.size - errors
    def counted(n: Int, what: String): String = s"$n $what${if n == 1 then "" else "s"}"
    val sb = new AttributedStringBuilder()
    if alerts.isEmpty then sb.append(s" ${g.check.text} no alerts", t.healthy)
    else
      val badge =
        if errors > 0 then
          s"${g.error.text} ${counted(errors, "error")}" + (if warns > 0 then s" ${counted(warns, "warn")}" else "")
        else s"${g.warn.text} ${counted(warns, "warn")}"
      val badgeCols = new AttributedString(badge).columnLength()
      val style = if errors > 0 then t.error else t.warn.bold()
      sb.append(s" $badge${" " * (BadgeCols - badgeCols).max(2)}", style)
      // A reason can quote an exception's message: drawn as text from outside the node (FR-020).
      val listed = alerts.take(MaxListed).map(a => TuiText.external(a.text, g))
      val dot = s" ${g.dot.text} "
      val room = width - 1 - sb.toAttributedString.columnLength()
      // As many of them as fit whole: a condition is never cut in the middle of its figure, and the badge has counted
      // the ones not listed. The first is always listed, cut with the ellipsis if it alone is wider than the row.
      val whole = (listed.size to 1 by -1)
        .find(n => new AttributedString(listed.take(n).mkString(dot)).columnLength() <= room)
        .getOrElse(1)
      sb.append(listed.take(whole).mkString(dot), style)
    TuiText.fit(sb.toAttributedString, width, g)
