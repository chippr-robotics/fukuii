package com.chipprbots.ethereum.console

import java.time.Duration

import org.jline.utils.AttributedString
import org.jline.utils.AttributedStringBuilder

import com.chipprbots.ethereum.console.TuiText.fitToWidth
import com.chipprbots.ethereum.console.TuiText.formatBytes
import com.chipprbots.ethereum.console.TuiText.formatNumber
import com.chipprbots.ethereum.console.TuiText.formatShort

/** How bad an alert is: a warning (yellow) or an error (red). */
enum Severity:
  case Warn, Error

/** One condition the alert row lists. */
final case class Alert(severity: Severity, text: String)

/** The alert row: research §2.3's conditions that need no slice 5 data, with their thresholds and severities (spec 027
  * FR-016, US3). Every duration is measured on the snapshot's clock (`TuiState.now`), never on ticks, so the row is the
  * same at every tick and every pace, and is never animated (FR-002).
  *
  * The consensus-layer and mining rows of §2.3 arrive with slice 5.
  */
object Alerts:

  /** No handshaked peer for longer than this is an error. */
  val ZeroPeersAfter: Duration = Duration.ofSeconds(30)

  /** A SNAP sample older than this while SNAP runs is a warning (the monitor samples every 30 s). */
  val SnapSampleMaxAge: Duration = Duration.ofSeconds(90)

  private val GiB: Long = 1024L * 1024 * 1024

  /** Disk free below this, on the datadir's file system, is a warning. */
  val DiskWarnBytes: Long = 20 * GiB

  /** Disk free below this is an error. */
  val DiskErrorBytes: Long = 5 * GiB

  /** At most this many conditions are listed; the badge counts them all. */
  val MaxListed: Int = 3

  /** Columns of the badge, before the listed conditions. */
  private val BadgeCols = 12

  /** Every condition that holds, errors first, each severity in §2.3's order. */
  def of(state: TuiState): Seq[Alert] =
    val snapshot = state.snapshot
    val now = state.now

    val zeroPeers = state.zeroPeersSince.flatMap { since =>
      val silent = Duration.between(since, now)
      val known = snapshot.forall(_.peers.value.isDefined)
      Option.when(known && state.peerCount == 0 && silent.compareTo(ZeroPeersAfter) > 0)(
        Alert(Severity.Error, s"0 peers for ${formatShort(silent.getSeconds)}")
      )
    }
    def unresponsive(name: String, source: Option[Sourced[?]]): Option[Alert] = source.collect {
      case Sourced.Unresponsive(reason) => Alert(Severity.Error, s"$name Unresponsive ($reason)")
    }
    val peerManager = unresponsive("peer manager", snapshot.map(_.peers))
    val syncController = unresponsive("sync controller", snapshot.map(_.sync))

    val stalled = Option.when(state.stalled)(
      Alert(
        Severity.Warn,
        s"sync stalled: head ${formatNumber(state.currentBlock)} unchanged for " +
          formatShort(state.lastAdvanceAt.fold(0L)(at => Duration.between(at, now).getSeconds))
      )
    )

    // While SNAP runs the sync controller reports no block target (B-044); with one, a sample is a finished run's.
    val snapSample = snapshot.flatMap(_.sync.value).filter(_.best == 0L).flatMap(_.snapSampledAt).flatMap { at =>
      val age = Duration.between(at, now)
      Option.when(age.compareTo(SnapSampleMaxAge) > 0)(
        Alert(Severity.Warn, s"SNAP sample is ${formatShort(age.getSeconds)} old (the monitor samples every 30s)")
      )
    }

    // The datadir's path is on the Overview's Node row; here it would push the next condition off an 80-column row.
    val disk = snapshot.flatMap(_.local.diskFree).filter(_ >= 0).flatMap { free =>
      if free < DiskErrorBytes then
        Some(Alert(Severity.Error, s"disk ${formatBytes(free)} free (error < ${DiskErrorBytes / GiB}G)"))
      else if free < DiskWarnBytes then
        Some(Alert(Severity.Warn, s"disk ${formatBytes(free)} free (warn < ${DiskWarnBytes / GiB}G)"))
      else None
    }

    // No peer at all is the 0-peers rule's, after its grace; this one is for a node that has some, but few.
    val fewPeers = Option.when(
      state.maxPeers > 0 && state.peerCount > 0 && state.peerCount * 4 < state.maxPeers &&
        snapshot.forall(_.peers.value.isDefined)
    )(Alert(Severity.Warn, s"peers ${state.peerCount}/${state.maxPeers} below 25%"))

    val all = Seq(zeroPeers, peerManager, syncController, stalled, snapSample, disk, fewPeers).flatten
    all.filter(_.severity == Severity.Error) ++ all.filter(_.severity == Severity.Warn)

  /** The row: `✓ no alerts`, or the badge with the count, then up to [[MaxListed]] conditions. Exactly `width`. */
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
      sb.append(alerts.take(MaxListed).map(_.text).mkString(s" ${g.dot.text} "), style)
    fitToWidth(sb.toAttributedString, width)
