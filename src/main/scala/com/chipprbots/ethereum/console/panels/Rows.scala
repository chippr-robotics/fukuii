package com.chipprbots.ethereum.console.panels

import java.time.Duration
import java.util.Locale

import org.jline.utils.AttributedString
import org.jline.utils.AttributedStringBuilder
import org.jline.utils.AttributedStyle

import com.chipprbots.ethereum.console.*
import com.chipprbots.ethereum.console.TuiText.fitToWidth
import com.chipprbots.ethereum.console.TuiText.formatBytes
import com.chipprbots.ethereum.console.TuiText.formatNumber
import com.chipprbots.ethereum.console.TuiText.formatPercent
import com.chipprbots.ethereum.console.TuiText.formatShort

/** What every panel draws with: the state, the tier, the glyph set, the theme and the display switches. */
final case class Ctx(state: TuiState, tier: Tier, g: TuiGlyphs, t: TuiTheme, config: TuiConfig):
  def separator(width: Int): AttributedString = new AttributedString(g.separator.text * width.max(0), t.muted)

/** The rows several views share: label/value fields at fixed columns, the worm rows, the vitals strip, the one-line
  * sync summary (spec 027 research §3.2–§3.6). Every row is fitted by columns, so a glyph set never moves a label
  * (§3.10).
  */
private[console] object Rows:

  /** A worm row's indent and label: three spaces and a 9-column label. */
  val WormLabelCols: Int = 9
  val WormIndentCols: Int = 3 + WormLabelCols

  /** A field's label column: ` Peers   ` (one space, the label padded to 8). */
  val FieldLabelCols: Int = 8

  val blank: AttributedString = new AttributedString("")

  def text(s: String, style: AttributedStyle): AttributedString = new AttributedString(s, style)

  def concat(parts: AttributedString*): AttributedString =
    val sb = new AttributedStringBuilder()
    parts.foreach(p => sb.append(p))
    sb.toAttributedString

  /** ` Label   value`: one space, the label padded to `labelCols`, then the value. */
  def field(label: String, value: AttributedString, t: TuiTheme, labelCols: Int = FieldLabelCols): AttributedString =
    concat(text(" " + label.padTo(labelCols, ' '), t.label), value)

  /** [[TuiText.fit]], for the panels: rows cut with the ellipsis. */
  export TuiText.fit

  /** Cells at fixed columns: each starts at its column and is cut, with the ellipsis, before the next one, so none
    * collides and a cut shows.
    */
  def strip(width: Int, cells: Seq[(Int, AttributedString)], g: TuiGlyphs): AttributedString =
    val placed = cells.filter((at, _) => at < width).sortBy(_._1)
    val sb = new AttributedStringBuilder()
    var used = 0
    placed.zipWithIndex.foreach { case ((at, cell), i) =>
      // Each cell ends before the next one's gap column, the last before the row's last column: the gap is reserved
      // here, so a cell may fill its room exactly.
      val end = placed.lift(i + 1).fold(width - 1)(_._1 - 1)
      if used < at then
        sb.append(" " * (at - used))
        used = at
      val room = (end - used).max(0)
      val length = cell.columnLength()
      if length <= room then
        sb.append(cell)
        used += length
      else
        sb.append(fit(cell, room, g, reserveLast = false))
        used += room
    }
    fitToWidth(sb.toAttributedString, width)

  /** `left`, then `right` ending one column before the edge; the right side is kept when the row is too narrow. */
  def leftRight(left: AttributedString, right: AttributedString, width: Int, g: TuiGlyphs): AttributedString =
    val room = width - 1 - right.columnLength()
    if right.columnLength() == 0 then fit(left, width, g)
    else if room >= left.columnLength() + 2 then
      fitToWidth(concat(left, new AttributedString(" " * (room - left.columnLength())), right), width)
    else fit(concat(fit(left, (room - 1).max(0), g), new AttributedString(" "), right), width, g)

  /** Text centred in `width`. */
  def centred(line: AttributedString, width: Int): AttributedString =
    val left = ((width - line.columnLength()) / 2).max(0)
    fitToWidth(concat(new AttributedString(" " * left), line), width)

  // ---- fields -------------------------------------------------------------------------------------------------

  /** `18/50 ◆◆◆◆◆◆◆◆◆◆`, the count yellow below half the maximum (C-2: the alert row's 25 % is separate); while
    * connecting, the spinner and the status in a word; or `Unresponsive`, whose source the alert row names.
    */
  def peersValue(c: Ctx): AttributedString =
    val s = c.state
    val count = s"${s.peerCount}/${s.maxPeers}"
    val style =
      if s.peerCount == 0 then c.t.error
      else if s.peerCount < s.maxPeers / 2 then c.t.warn.bold()
      else c.t.healthy.bold()
    if s.connectionStatus.startsWith("Unresponsive") then text("Unresponsive", c.t.error)
    else
      TuiRenderer.InFlight.get(s.connectionStatus) match
        case Some(word) =>
          concat(text(count, style), text(s" ${WormBar.spinner(s.tick, s.motion, c.g)} $word", c.t.warn))
        case None => concat(text(count, style), text(" " + c.g.diamond.text * s.peerCount.min(10).max(0), style))

  /** `15,234,567 → 15,234,890 (−323)`, `21,884,102 (at head)`, `0 → 9,118,000 (pivot)` while SNAP runs (the sync
    * controller reports no target then), or the best block unknown.
    */
  def blockValue(c: Ctx): AttributedString =
    val s = c.state
    val arrow = if c.g.set == GlyphSet.Ascii then "->" else "→"
    val minus = if c.g.set == GlyphSet.Ascii then "-" else "−"
    if s.isSynchronized then text(s"${formatNumber(s.currentBlock)} (at head)", c.t.value)
    else if s.snapPivot.isDefined then
      text(s"${formatNumber(s.currentBlock)} $arrow ${formatNumber(s.snapPivot.getOrElse(0L))} (pivot)", c.t.value)
    else if s.bestBlock > 0 then
      text(
        s"${formatNumber(s.currentBlock)} $arrow ${formatNumber(s.bestBlock)} ($minus${formatNumber(s.blocksRemaining)})",
        c.t.value
      )
    else text(s"${formatNumber(s.currentBlock)} $arrow unknown", c.t.value)

  /** `:8545 on WS :8552 on IPC on Engine :8551 on`: each endpoint that is on, or RPC off. At tier S the RPC cell has 32
    * columns, which `:8545 on Engine :8551` fills and `Engine :8551 on` would cut inside the port number: the port
    * alone says the Engine API is on.
    */
  def endpointsValue(c: Ctx): AttributedString =
    val n = c.state.nodeSettings
    val parts =
      Seq(if n.rpcEnabled then s":${n.rpcPort} on" else "off") ++
        Option.when(n.wsEnabled)(s"WS :${n.wsPort} on") ++
        Option.when(n.ipcEnabled)("IPC on") ++
        Option.when(n.engineEnabled)(s"Engine :${n.enginePort}${if c.tier == Tier.S then "" else " on"}")
    text(parts.mkString("   "), c.t.value)

  private def local(c: Ctx): LocalSample = c.state.snapshot.fold(LocalSample())(_.local)

  /** `412G free`, with the warn glyph below the alert row's threshold. */
  def diskValue(c: Ctx): AttributedString =
    local(c).diskFree.filter(_ >= 0) match
      case Some(free) if free < Alerts.DiskErrorBytes =>
        text(s"${formatBytes(free)} free ${c.g.error.text}", c.t.error)
      case Some(free) if free < Alerts.DiskWarnBytes =>
        text(s"${formatBytes(free)} free ${c.g.warn.text}", c.t.warn.bold())
      case Some(free) => text(s"${formatBytes(free)} free", c.t.value)
      case None       => text("?", c.t.muted)

  def heapValue(c: Ctx): AttributedString =
    local(c).jvm.fold(text("?", c.t.muted)) { j =>
      text(s"${formatBytes(j.heapUsed)} / ${formatBytes(j.heapMax)} heap", c.t.value)
    }

  def gcValue(c: Ctx): AttributedString =
    local(c).jvm.flatMap(_.gcShare).fold(text("?", c.t.muted))(share => text(formatPercent(share), c.t.value))

  def discoveryValue(c: Ctx): AttributedString =
    local(c).discovery.fold(text("?", c.t.muted)) { d =>
      val dot = c.g.dot.text
      text(
        s"${formatNumber(d.known)} known $dot ${formatNumber(d.pending)} pending $dot ${formatNumber(d.blacklisted)} blacklisted",
        c.t.value
      )
    }

  // ---- the vitals strip: what the five-second rule needs, on every view -------------------------------------------

  /** `142`, `1,204 (3 blob)`: the pending transactions and the blob-carrying ones among them; `Unresponsive` when the
    * txpool did not answer, `?` before it is asked.
    */
  def txpoolValue(c: Ctx): AttributedString =
    c.state.snapshot.flatMap(_.txpool) match
      case Some(Sourced.Unresponsive(_)) => text("Unresponsive", c.t.error)
      case Some(source) =>
        source.value.fold(text("?", c.t.muted)) { pool =>
          val blobs = if pool.blobs > 0 then s" (${formatNumber(pool.blobs.toLong)} blob)" else ""
          val style = source match
            case Sourced.Stale(_, _) => c.t.warn
            case _                   => c.t.value
          text(s"${formatNumber(pool.size.toLong)}$blobs", style)
        }
      case None => text("?", c.t.muted)

  private def txpool(c: Ctx): AttributedString = field("Txpool", txpoolValue(c), c.t)

  /** Peers and blocks; from M the txpool; at L the disk and the heap too (research §3.2–§3.4). Each cell starts at a
    * fixed column, so neither glyph set moves a label. At L the columns leave each cell its longest common value in
    * both sets (a regular sync's block range with the ASCII arrow, `1,204 (3 blob)`, `14.2G free ⚠`) down to 140
    * columns, so the GC share is on the next row.
    */
  def vitalsRow(c: Ctx, width: Int): AttributedString =
    val peers = field("Peers", peersValue(c), c.t)
    val block = field("Block", blockValue(c), c.t, 7)
    val cells = c.tier match
      case Tier.S => Seq(0 -> peers, 33 -> block)
      case Tier.M => Seq(0 -> peers, 44 -> block, 92 -> txpool(c))
      case Tier.L =>
        Seq(
          0 -> peers,
          26 -> block,
          73 -> txpool(c),
          97 -> field("Disk", diskValue(c), c.t, 6),
          117 -> field("JVM", heapValue(c), c.t, 5)
        )
    strip(width, cells, c.g)

  /** The endpoints and the disk, with the txpool at S and the heap at M; at L the discovery table and the GC share. */
  def resourcesRow(c: Ctx, width: Int): AttributedString =
    val rpc = field("RPC", endpointsValue(c), c.t)
    val cells = c.tier match
      case Tier.S => Seq(0 -> rpc, 33 -> field("Disk", diskValue(c), c.t, 7), 56 -> txpool(c))
      case Tier.M => Seq(0 -> rpc, 44 -> field("Disk", diskValue(c), c.t, 7), 92 -> field("JVM", heapValue(c), c.t))
      case Tier.L =>
        Seq(
          0 -> rpc,
          76 -> field("Discovery", discoveryValue(c), c.t, 11),
          130 -> field("GC", gcValue(c), c.t, 4)
        )
    strip(width, cells, c.g)

  // ---- sync ---------------------------------------------------------------------------------------------------

  /** `ETA 1h 58m`, or `ETA ?` while there is no estimate yet. */
  def eta(seconds: Option[Long]): String = s"ETA ${seconds.fold("?")(formatShort)}"

  /** A regular sync's rate and ETA: `rate 184.37 blk/s · ETA 5h 14m`. */
  def rateAndEta(c: Ctx): String =
    val s = c.state
    val rate = s.syncSpeedBlocksPerSec.fold("rate ?")(r => String.format(Locale.ROOT, "rate %.2f blk/s", r))
    s"$rate ${c.g.dot.text} ${eta(s.estimatedSyncTimeSeconds)}"

  /** How old the SNAP sample is, when it is too old to trust (§2.3, the spec's stale-sample edge case). */
  def staleSnapSample(c: Ctx): Option[String] =
    c.state.snapSampleAge
      .filter(_.compareTo(Alerts.SnapSampleMaxAge) > 0)
      .map(age => s"sample ${formatShort(age.getSeconds)} old")

  /** One line: the sync phase with its progress and ETA, or "at head", for the views that have no sync panel. */
  def syncSummary(c: Ctx): AttributedString =
    val s = c.state
    val dot = c.g.dot.text
    s.snapSyncState match
      case Some(snap) =>
        val stale = staleSnapSample(c).fold("")(a => s" $dot $a")
        text(
          s"SNAP ${snap.phaseDescription(c.g)} $dot ${(snap.overall.max(0.0).min(1.0) * 100).toInt}% $dot " +
            eta(snap.etaSeconds) + stale,
          if stale.nonEmpty then c.t.warn else c.t.value
        )
      case None if s.isSynchronized => text(s"${c.g.check.text} at head", c.t.healthy)
      case None if s.bestBlock > 0 =>
        val style = if s.stalled then c.t.warn else c.t.value
        text(s"regular ${s.syncProgress.toInt}% $dot ${rateAndEta(c)}", style)
      case None => text(s.syncStatus, c.t.value)

  def syncSummaryRow(c: Ctx, width: Int): AttributedString = fit(field("Sync", syncSummary(c), c.t), width, c.g)

  /** The top of every view but the Overview: the vitals row, and the sync summary unless the view is the sync's own
    * (SC-002).
    */
  def vitalsPanel(c: Ctx, withSummary: Boolean): Panel =
    val rows = if withSummary then 2 else 1
    Panel(rows, rows, 1, (w, _) => vitalsRow(c, w) +: (if withSummary then Seq(syncSummaryRow(c, w)) else Nil))

  /** The rows nobody else wants, left blank (Help). */
  val fillPanel: Panel = Panel(0, 0, 9, (_, _) => Nil, fill = true)

  /** SNAP's phase row: the phase on the left; elapsed (and the time in the phase), ETA and a stale sample's age on the
    * right.
    */
  def snapHeader(c: Ctx, snap: SnapSyncState, w: Int, title: String, withPhaseTime: Boolean): AttributedString =
    val dot = c.g.dot.text
    val stale = staleSnapSample(c)
    val times = Seq(s"elapsed ${formatShort(snap.elapsedSeconds.toLong)}") ++
      Option.when(withPhaseTime)(s"phase ${formatShort(snap.phaseElapsedSeconds.toLong)}") ++
      Seq(eta(snap.etaSeconds)) ++ stale
    val right = text(times.mkString(s" $dot "), if stale.isDefined then c.t.warn else c.t.value)
    leftRight(text(s" $title ${snap.phaseDescription(c.g)}", c.t.healthy.bold()), right, w, c.g)

  /** SNAP's five worm rows on a track of `slots`, then the chain download when `withChain` and it has a target. */
  def snapWorms(c: Ctx, snap: SnapSyncState, slots: Int, w: Int, withChain: Boolean): Seq[AttributedString] =
    val budget = wormBudget(c, slots, w)
    val chain = Option.when(withChain)(chainDownload(c, snap)).flatten.map { d =>
      fit(concat(text(s"   ${"Chain".padTo(WormLabelCols, ' ')}", c.t.label), text(d, c.t.value)), w, c.g)
    }
    SnapRows.of(snap, c.g, compact = c.tier == Tier.S).map(r => wormRow(c, r.label, r.progress, r.detail, budget, w)) ++
      chain

  // ---- worm rows ----------------------------------------------------------------------------------------------

  /** The column budget of a worm row's bar for a track of `slots`, within `width`. */
  def wormBudget(c: Ctx, slots: Int, width: Int): Int =
    WormBar.budgetFor(slots, c.g).min(width - WormIndentCols).max(0)

  /** ` Label [===🪱....]🧠 52% detail`: without the progress bar, the row says in words what the bar would show.
    *
    * A detail is parts joined by the dot (`119.6M slots @ 21.4K/s · 2,114/5,702 contracts`). When the row is too narrow
    * for all of it, the last parts are left out whole: a count cut in the middle reads as another count.
    */
  def wormRow(
      c: Ctx,
      label: String,
      progress: WormProgress,
      detail: String,
      budget: Int,
      width: Int,
      stalled: Boolean = false
  ): AttributedString =
    val sb = new AttributedStringBuilder()
    sb.append(s"   ${label.padTo(WormLabelCols, ' ')}", c.t.label)
    if c.config.showProgressBar then
      sb.append(WormBar.render(budget, progress, c.state.tick, c.g, c.t, c.state.motion, stalled))
    else
      val words = progress match
        case WormProgress.Known(f) => s"${(f.max(0.0).min(1.0) * 100).toInt}%"
        case other                 => other.toString.toLowerCase(Locale.ROOT)
      sb.append(words, c.t.value)
    val head = sb.toAttributedString
    def withDetail(d: String): AttributedString =
      if d.isEmpty then head else concat(head, text(s"  $d", c.t.value))
    val dot = s" ${c.g.dot.text} "
    val parts = detail.split(java.util.regex.Pattern.quote(dot)).toSeq
    val whole = (parts.size to 1 by -1).iterator.map(n => withDetail(parts.take(n).mkString(dot)))
    fit(whole.find(_.columnLength() <= width - 1).getOrElse(withDetail(parts.headOption.getOrElse(""))), width, c.g)

  /** The synced Blocks row during a catch-up: the worm's last crawl into the brain, frame by frame, or the one quiet
    * frame (research §3.8). Its bar is not held to the budget: the row is fitted to the width, like every row.
    */
  def catchupRow(c: Ctx, catchup: Catchup, detail: String, budget: Int, width: Int): AttributedString =
    val g = c.g
    val slots = WormBar.slotsFor(budget, g).max(2)
    val complete = s"${g.worm.text}[COMPLETE ${g.check.text}]${g.brain.text}"
    val sparkle = g.celebration.text
    val bar = catchup match
      case Catchup.Celebrating(frame) =>
        frame / 2 match
          case 0 => s"[${"=" * (slots - 2)}${g.worm.text}.]${g.brain.text} 99%"
          case 1 => s"[${"=" * (slots - 1)}${g.worm.text}]${g.brain.text} 100%"
          case 2 => s"[${"=" * slots}]${g.worm.text}${g.brain.text} 100%"
          case 3 => s"[${"=" * slots}]$sparkle${g.brain.text}$sparkle synced"
          case 4 => s"$complete $sparkle synced"
          case _ => s"$complete ${" " * g.celebration.cols} synced"
      case Catchup.Celebrated => s"$complete $sparkle synced"
      case Catchup.BackAtHead => s"$complete ${g.check.text} back at head"
    val sb = new AttributedStringBuilder()
    sb.append(s"   ${"Blocks".padTo(WormLabelCols, ' ')}", c.t.label)
    sb.append(bar, c.t.healthy)
    sb.append(s"  $detail", c.t.value)
    fit(sb.toAttributedString, width, c.g)

  /** The Blocks worm of a regular sync, or at the head (with a catch-up's frames), with its detail. */
  def blocksRow(c: Ctx, budget: Int, width: Int): Option[AttributedString] =
    val s = c.state
    if s.isSynchronized then
      val detail = formatNumber(s.currentBlock)
      Some(s.catchup.fold(wormRow(c, "Blocks", WormProgress.Complete, detail, budget, width)) { cu =>
        catchupRow(c, cu, detail, budget, width)
      })
    else if s.bestBlock > 0 then
      Some(
        wormRow(
          c,
          "Blocks",
          WormProgress.Known(s.syncProgress / 100.0),
          s"${formatNumber(s.currentBlock)} / ${formatNumber(s.bestBlock)}",
          budget,
          width,
          stalled = s.stalled
        )
      )
    else None

  /** The SNAP chain download, when it has a target: `headers 4.2M / 9.1M · bodies 0 · receipts 0`. */
  def chainDownload(c: Ctx, snap: SnapSyncState): Option[String] =
    Option.when(snap.chainTarget > 0) {
      val dot = c.g.dot.text
      s"headers ${TuiText.formatCount(snap.chainHeaders)} / ${TuiText.formatCount(snap.chainTarget)} $dot " +
        s"bodies ${TuiText.formatCount(snap.chainBodies)} $dot receipts ${TuiText.formatCount(snap.chainReceipts)}"
    }

  /** `0x3fa1…e2` for a hex node id, else the id cut to 12 columns. */
  def shortId(id: String, g: TuiGlyphs): String =
    val hex = id.stripPrefix("0x")
    // ASCII hex digits only: `Character.digit` also takes the full-width digits, which are not for an ASCII frame.
    if hex.length > 8 && hex.forall(ch => ch < 0x80 && Character.digit(ch, 16) >= 0) then
      s"0x${hex.take(4)}${g.ellipsis.text}${hex.takeRight(2)}"
    else
      // Not hex: whatever a peer manager made of the id, so it is outside text like any other (C-16).
      val clean = TuiText.external(id, g)
      // By code points: a cut must not split a surrogate pair.
      if clean.codePointCount(0, clean.length) > 12 then
        clean.substring(0, clean.offsetByCodePoints(0, 11)) + g.ellipsis.text
      else clean

  /** How long ago `at` was, at the state's `now`. */
  def ago(c: Ctx, at: java.time.Instant): String = formatShort(Duration.between(at, c.state.now).getSeconds)
