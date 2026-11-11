package com.chipprbots.ethereum.console

import java.time.Duration

import org.jline.utils.AttributedString
import org.jline.utils.AttributedStringBuilder

import com.chipprbots.ethereum.console.TuiText.formatBytes
import com.chipprbots.ethereum.console.TuiText.formatNumber
import com.chipprbots.ethereum.console.TuiText.formatShort

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

  /** No handshaked peer for longer than this is an error. */
  val ZeroPeersAfter: Duration = Duration.ofSeconds(30)

  /** A SNAP sample older than this while SNAP runs is a warning (the monitor samples every 30 s). */
  val SnapSampleMaxAge: Duration = Duration.ofSeconds(90)

  /** No `engine_newPayload` for longer than this, while peers are ahead of the node's head, is a warning. */
  val NewPayloadSilentAfter: Duration = Duration.ofMinutes(5)

  /** A reported hashrate of 0 for longer than this, mining on and the DAG loaded, is a warning. */
  val HashrateZeroAfter: Duration = Duration.ofMinutes(10)

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

    // Only while SNAP counts as running (SyncSample.snapRunning): an aborted run's last sample is not a warning.
    val snapSample = snapshot.flatMap(_.sync.value).filter(_.snapRunning).flatMap(_.snapSampledAt).flatMap { at =>
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

    val all = Seq(zeroPeers, peerManager, syncController).flatten ++ clAlerts(state) ++
      Seq(stalled, snapSample, disk, fewPeers).flatten ++ miningAlert(state)
    all.filter(_.severity == Severity.Error) ++ all.filter(_.severity == Severity.Warn)

  /** ETH (spec 027 T110), in §2.3's order. The consensus layer is silent when its last forkchoiceUpdated is older than
    * [[ClSample.ClSilentAfter]]: an error once it has been heard from, a warning if it never has and the node has been
    * up for [[ClSample.ClFirstCallGrace]] (it may simply have none yet; a JWT secret that does not match is one that
    * calls and is refused, so the count of those is in the text).
    */
  private def clAlerts(state: TuiState): Seq[Alert] =
    val now = state.now
    val uptime = Duration.ofSeconds(state.uptimeSeconds)
    state.snapshot.flatMap(_.local.cl).toSeq.flatMap { c =>
      val silent = c.forkchoiceUpdated.flatMap { call =>
        val age = call.ageAt(now)
        Option.when(age.compareTo(ClSample.ClSilentAfter) > 0)(
          Alert(
            Severity.Error,
            s"CL disconnected: no forkchoiceUpdated for ${formatShort(age.getSeconds)} (last ${call.status})"
          )
        )
      }
      val missing = Option.when(c.forkchoiceUpdated.isEmpty && uptime.compareTo(ClSample.ClFirstCallGrace) > 0) {
        val refused =
          if c.authFailures > 0 then s" (${c.authFailures} JWT failure${if c.authFailures == 1 then "" else "s"})"
          else ""
        Alert(Severity.Warn, s"no CL yet: no forkchoiceUpdated in ${formatShort(uptime.getSeconds)}$refused")
      }
      // Only while the consensus layer is calling at all (a silent one has its own row), and only while a peer is ahead
      // of the node's head. A payload the node already held is answered VALID without a record (EngineApiService), so
      // "no newPayload" alone is not the consensus layer's silence: a node at its head that was fed its blocks some other
      // way would raise it for nothing. A payload never recorded counts from when the consensus layer was first seen, or
      // from the node's start if that is later: one that attaches to a node that has been up for an hour has not been
      // silent for an hour.
      val noPayload =
        if c.forkchoiceUpdated.isEmpty || silent.isDefined then None
        else
          val since = state.clSeenSince.filter(_.isAfter(state.startTime)).getOrElse(state.startTime)
          val age = c.newPayload.fold(Duration.between(since, now))(_.ageAt(now))
          Option.when(age.compareTo(NewPayloadSilentAfter) > 0 && peersAhead(state))(
            Alert(Severity.Warn, s"no newPayload for ${formatShort(age.getSeconds)} while peers are ahead")
          )
      Seq(silent, missing, noPayload).flatten
    }

  /** ETC (spec 027 T111). The clock starts when the DAG is loaded (`TuiState.hashrateZeroSince`), and it is the
    * internal miner's: `mining-enabled` off is no miner to report a hashrate, whatever external miners do.
    */
  private def miningAlert(state: TuiState): Option[Alert] =
    state.hashrateZeroSince.filter(_ => state.nodeSettings.miningEnabled).flatMap { since =>
      val silent = Duration.between(since, state.now)
      Option.when(silent.compareTo(HashrateZeroAfter) > 0)(
        Alert(Severity.Warn, s"mining on, no hashrate reported for ${formatShort(silent.getSeconds)}")
      )
    }

  /** A peer, or the sync's own target, is ahead of the node's head: the best block any handshaked peer reports, or the
    * highest block the sync knows of.
    */
  private def peersAhead(state: TuiState): Boolean =
    val peerBest =
      state.snapshot.flatMap(_.details).flatMap(_.value).fold(0L)(_.values.map(_.bestBlock).maxOption.getOrElse(0L))
    state.bestBlock.max(peerBest) > state.currentBlock

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
      // A reason can quote an exception's message: drawn as text from outside the node (FR-020).
      sb.append(alerts.take(MaxListed).map(a => TuiText.external(a.text, g)).mkString(s" ${g.dot.text} "), style)
    TuiText.fit(sb.toAttributedString, width, g)
