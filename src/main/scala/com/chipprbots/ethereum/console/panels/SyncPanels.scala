package com.chipprbots.ethereum.console.panels

import org.jline.utils.AttributedString

import com.chipprbots.ethereum.console.*
import com.chipprbots.ethereum.console.TuiText.fitToWidth
import com.chipprbots.ethereum.console.TuiText.formatCount
import com.chipprbots.ethereum.console.TuiText.formatNumber
import com.chipprbots.ethereum.console.TuiText.formatShort
import com.chipprbots.ethereum.console.panels.Rows.*

/** The Sync view (spec 027 research §3.5, §3.6): SNAP's phase and worm rows on the tier's track (20, 40 or 60 slots),
  * with its rates, request counters, snap-capable peers and phase timeline from tier M; or a regular sync's Blocks worm,
  * session and lag.
  *
  * Not here yet: the L tier's rate sparkline (it needs the rate history slice 4 keeps), the S tier's Import row (slice
  * 4's import-duration gauge) and its Fetcher row (no metric source, C-7).
  */
private[console] object SyncPanels:

  def panels(c: Ctx): Seq[Panel] =
    val vitals = Panel(1, 1, 1, (w, _) => Seq(vitalsRow(c, w)))
    val fill = Panel(0, 0, 9, (_, _) => Nil, fill = true)
    c.state.snapSyncState match
      case Some(snap) => Seq(vitals) ++ snapPanels(c, snap) :+ fill
      case None       => Seq(vitals) ++ regularPanels(c) :+ fill

  private def snapPanels(c: Ctx, snap: SnapSyncState): Seq[Panel] =
    val dot = c.g.dot.text
    def header(w: Int): Seq[AttributedString] =
      val stale = staleSnapSample(c)
      val right = text(
        (Seq(
          s"elapsed ${formatShort(snap.elapsedSeconds.toLong)}",
          s"phase ${formatShort(snap.phaseElapsedSeconds.toLong)}",
          eta(snap.etaSeconds)
        ) ++ stale).mkString(s" $dot "),
        if stale.isDefined then c.t.warn else c.t.value
      )
      Seq(leftRight(text(s" SNAP sync   ${snap.phaseDescription(c.g)}", c.t.healthy.bold()), right, w))
    def worms(w: Int): Seq[AttributedString] =
      val budget = wormBudget(c, c.tier.trackSlots, w)
      SnapRows.of(snap, c.g).map(r => wormRow(c, r.label, r.progress, r.detail, budget, w)) ++
        chainDownload(c, snap).map { d =>
          fitToWidth(concat(text(s"   ${"Chain".padTo(WormLabelCols, ' ')}", c.t.label), text(d, c.t.value)), w)
        }
    val detail = Option.when(c.tier != Tier.S)(
      Seq(
        Panel(2, 3, 4, (w, _) => stats(c, snap, w), separated = true),
        Panel(2, 2, 5, (w, _) => phases(c, snap, w), separated = true)
      )
    )
    Seq(
      Panel(1, 1, 2, (w, _) => header(w), separated = true),
      Panel(5, 6, 3, (w, _) => worms(w), separated = true)
    ) ++ detail.getOrElse(Nil)

  /** Rates (recent / overall), the request counters, the snap-capable peers. */
  private def stats(c: Ctx, snap: SnapSyncState, w: Int): Seq[AttributedString] =
    val dot = c.g.dot.text
    def pair(recent: Double, overall: Double) = s"${formatCount(recent.toLong)}/${formatCount(overall.toLong)}"
    val rates = Seq(
      s"accounts ${pair(snap.recentAccountsPerSec, snap.accountsPerSec)}",
      s"slots ${pair(snap.recentSlotsPerSec, snap.slotsPerSec)}",
      s"bytecodes ${pair(snap.recentBytecodesPerSec, snap.bytecodesPerSec)}",
      s"nodes ${pair(snap.recentNodesPerSec, snap.nodesPerSec)}"
    ).mkString(s"  $dot  ")
    val labelCols = 26
    val counters = c.state.snapshot.flatMap(_.local.snapCounters)
    val (ok, failed) = (c.g.check.text, c.g.error.text)
    val requests = counters.fold("?") { k =>
      Seq(
        s"account ${formatNumber(k.accountRequests)} $ok ${formatNumber(k.accountFailures)} $failed",
        s"storage ${formatNumber(k.storageRequests)} $ok ${formatNumber(k.storageFailures)} $failed",
        s"bytecode ${formatNumber(k.bytecodeRequests)} $ok ${formatNumber(k.bytecodeFailures)} $failed",
        s"healing ${formatNumber(k.healingRequests)} $ok ${formatNumber(k.healingFailures)} $failed"
      ).mkString("  ")
    }
    val peers = counters.fold("?") { k =>
      s"${formatNumber(k.snapCapablePeers)} of ${c.state.peerCount} $dot blacklisted this run " +
        s"${formatNumber(k.blacklisted)} $dot request timeouts ${formatNumber(k.timeouts)}"
    }
    Seq(
      field("Rates (recent / overall)", text(rates, c.t.value), c.t, labelCols),
      field("Requests", text(requests, c.t.value), c.t, labelCols),
      field("Peers (snap-capable)", text(peers, c.t.value), c.t, labelCols)
    ).map(fitToWidth(_, w))

  private val PhaseNames: Seq[(String, String)] = Seq(
    "AccountRangeSync" -> "AccountRange",
    "ByteCodeAndStorageSync" -> "ByteCodeAndStorage",
    "StateHealing" -> "StateHealing",
    "StateValidation" -> "StateValidation",
    "ChainDownloadCompletion" -> "ChainDownload"
  )

  /** `AccountRange ✓ ── ByteCodeAndStorage ● 1h 8m ── StateHealing · ── …`: done, current with its time, to come. */
  private def phases(c: Ctx, snap: SnapSyncState, w: Int): Seq[AttributedString] =
    val current = snap.phaseIndex
    val sep = text(s" ${c.g.separator.text * 2} ", c.t.muted)
    val parts = PhaseNames.zipWithIndex.map { case ((_, name), i) =>
      if current >= 0 && i < current then text(s"$name ${c.g.check.text}", c.t.healthy)
      else if i == current then text(s"$name ${c.g.bullet.text} ${formatShort(snap.phaseElapsedSeconds.toLong)}", c.t.value)
      else text(s"$name ${c.g.dot.text}", c.t.muted)
    }
    val timeline = parts.zipWithIndex.flatMap((p, i) => if i == 0 then Seq(p) else Seq(sep, p))
    Seq(
      fitToWidth(text(" Phase timeline", c.t.healthy.bold()), w),
      fitToWidth(concat((text("   ", c.t.value) +: timeline)*), w)
    )

  private def regularPanels(c: Ctx): Seq[Panel] =
    val s = c.state
    def header(w: Int): Seq[AttributedString] =
      val row =
        if s.isSynchronized then fitToWidth(field("Sync", text(s"${c.g.check.text} at head", c.t.healthy.bold()), c.t), w)
        else if s.bestBlock > 0 then
          leftRight(text(" Regular sync", c.t.healthy.bold()), text(rateAndEta(c), c.t.value), w)
        else fitToWidth(field("Sync", text(s.syncStatus, c.t.value), c.t), w)
      Seq(row)
    def blocks(w: Int): Seq[AttributedString] =
      val budget = wormBudget(c, c.tier.trackSlots, w)
      (if c.config.showProgressBar then blocksRow(c, budget, w).toSeq else Nil) ++ session(c, w)
    Seq(Panel(1, 1, 2, (w, _) => header(w), separated = true)) ++
      Option.when(s.bestBlock > 0)(Panel(1, 3, 3, (w, _) => blocks(w)))

  /** `Session  imported 101,220 blocks since 18,300,893 (9m 9s ago)` and `Lag  3,481,989 blocks behind best known`. */
  private def session(c: Ctx, w: Int): Seq[AttributedString] =
    val s = c.state
    val imported = s.syncBaseline.map { b =>
      val n = (s.currentBlock - b.block).max(0L)
      s"imported ${formatNumber(n)} blocks since ${formatNumber(b.block)} (${ago(c, b.observedAt)} ago)"
    }
    val lag = Option.when(s.blocksRemaining > 0)(s"${formatNumber(s.blocksRemaining)} blocks behind best known")
    def row(label: String, value: String) =
      fitToWidth(concat(text(s"   ${label.padTo(WormLabelCols, ' ')}", c.t.label), text(value, c.t.value)), w)
    imported.map(row("Session", _)).toSeq ++ lag.map(row("Lag", _))
