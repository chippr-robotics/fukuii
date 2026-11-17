package com.chipprbots.ethereum.console.panels

import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

import org.jline.utils.AttributedString
import org.jline.utils.AttributedStringBuilder

import com.chipprbots.ethereum.console.*
import com.chipprbots.ethereum.console.TuiText.formatNumber
import com.chipprbots.ethereum.console.TuiText.formatShort
import com.chipprbots.ethereum.console.panels.Rows.*

/** The chain as the node has seen it since the TUI started (spec 029 FR-022 to FR-026): the head, the Block Observatory
  * (the newest blocks, as a chart of the gas each used and as a table), the fees, and the reorgs.
  *
  * Every value is read from a block the node holds: the head observer's [[ChainSample]]. What the node does not have is
  * not drawn: a block whose body was not read shows `-` for its transactions and its size, a chain with no base fee has
  * no base-fee column, and a list that skips blocks says it is a sample.
  */
private[console] object ObservatoryPanels:

  private val Clock = DateTimeFormatter.ofPattern("HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

  /** A row of `label` and as many of `parts`, in order, as fit `w` columns whole: none is ever cut. */
  def partsRow(c: Ctx, label: String, parts: Seq[String], w: Int): AttributedString =
    val dot = s" ${c.g.dot.text} "
    val room = w - 1 - (1 + FieldLabelCols)
    val kept = wholeParts(parts, dot, room)
    TuiText.fitToWidth(field(label, text(kept.mkString(dot), c.t.value), c.t), w)

  /** Wei as gwei, to the digits that matter: `12.4 gwei`, `0.004 gwei`, and wei itself below a thousandth. */
  def gwei(wei: BigInt): String =
    val value = BigDecimal(wei) / BigDecimal(1_000_000_000L)
    if wei == 0 then "0 gwei"
    else if value >= 100 then s"${formatNumber(value.setScale(0, BigDecimal.RoundingMode.HALF_UP).toLong)} gwei"
    else if value >= 10 then String.format(Locale.ROOT, "%.1f gwei", value.toDouble)
    else if value >= 1 then String.format(Locale.ROOT, "%.2f gwei", value.toDouble)
    else if value >= BigDecimal("0.001") then String.format(Locale.ROOT, "%.3f gwei", value.toDouble)
    else s"${formatNumber(wei.toLong)} wei"

  /** Wei as gwei whatever its size, to three significant figures below a gwei: `0.75 gwei`, `0.0015 gwei`, `0.000000003
    * gwei`. For figures that are read against each other, where [[gwei]]'s change of unit below a thousandth of a gwei,
    * and its three decimals above it, would have one of them in another unit or rounded onto its neighbour.
    */
  def gweiOnly(wei: BigInt): String =
    val value = BigDecimal(wei) / BigDecimal(1_000_000_000L)
    if wei <= 0 || value >= 1 then gwei(wei.max(0))
    else s"${value.round(new java.math.MathContext(3)).bigDecimal.stripTrailingZeros.toPlainString} gwei"

  private val Magnitudes: Seq[String] = Seq("", "K", "M", "G", "T", "P", "E")

  /** A large count with the unit that keeps its integer part under 1,000: `412.6T`, `8.0M`, `310`. For gas and for a
    * difficulty, which outgrow [[TuiText.formatCount]]'s millions.
    */
  def magnitude(n: BigInt): String =
    if n < 1000 then n.toString
    else
      var value = BigDecimal(n)
      var unit = 0
      while value >= BigDecimal("999.95") && unit < Magnitudes.size - 1 do
        value /= 1000
        unit += 1
      String.format(Locale.ROOT, "%.1f%s", value.toDouble, Magnitudes(unit))

  /** A block's size: `940 B`, `2.1 kB`, `1.4 MB`. */
  def blockSize(bytes: Long): String =
    if bytes < 1000 then s"$bytes B"
    else if bytes < 1_000_000 then String.format(Locale.ROOT, "%.1f kB", bytes / 1000.0)
    else String.format(Locale.ROOT, "%.1f MB", bytes / 1_000_000.0)

  private def age(b: BlockSummary, now: Instant): String =
    formatShort(Duration.between(Instant.ofEpochSecond(b.timestamp), now).getSeconds.max(0L))

  private def percent(share: Double): String = s"${(share * 100).round}%"

  // ---- the head (FR-022, FR-024) --------------------------------------------------------------------------------

  /** The head's block, and the rate blocks arrive at. None until the probe has read a head. */
  def head(c: Ctx): Option[Panel] =
    for
      chain <- c.state.snapshot.flatMap(_.local.chain)
      h <- chain.head
    yield Panel(2, 4, 2, (w, n) => headRows(c, chain, h, w).take(n), separated = true)

  /** Whether the sync's own panels are on screen above: they say how long the last import took. */
  private def syncing(c: Ctx): Boolean =
    c.state.snapSyncState.isDefined || (c.state.bestBlock > 0 && !c.state.isSynchronized)

  private def headRows(c: Ctx, chain: ChainSample, h: BlockSummary, w: Int): Seq[AttributedString] =
    val pos = c.state.chain.exists(_.family == ChainFamily.PoS)
    val identity = Seq(
      formatNumber(h.number),
      ClPanel.shortHex(h.hash, c.g),
      s"${age(h, c.state.now)} old",
      Clock.format(Instant.ofEpochSecond(h.timestamp))
    )
    val content =
      h.txCount.map(n => s"$n ${if n == 1 then "tx" else "txs"}").toSeq ++
        Seq(s"gas ${magnitude(h.gasUsed)} of ${magnitude(h.gasLimit)} (${percent(h.gasShare)})") ++
        h.sizeBytes.map(blockSize) ++
        h.baseFee.map(fee => s"base fee ${gwei(fee)}")
    // The beneficiary is the miner where blocks are mined, and the fee recipient where they are proposed.
    val producer =
      if pos then Seq(s"fee recipient ${ClPanel.shortHex(h.miner, c.g)}") ++ h.slot.map(s => s"slot ${formatNumber(s)}")
      else Seq(s"miner ${ClPanel.shortHex(h.miner, c.g)}", s"difficulty ${magnitude(h.difficulty)}")
    val rate =
      chain.blocksPerSecond.map(r => String.format(Locale.ROOT, "%.2f blocks/s", r)).toSeq ++
        chain.gasPerSecond.map(g => s"${magnitude(BigDecimal(g).toBigInt)} gas/s") ++
        c.state.snapshot.flatMap(_.local.lastImport).filterNot(_ => syncing(c)).map { i =>
          s"last import ${i.blocks} ${if i.blocks == 1 then "block" else "blocks"} in ${i.duration.toMillis} ms"
        }
    Seq(partsRow(c, "Head", identity, w), partsRow(c, "Block", content, w), partsRow(c, "By", producer, w)) ++
      Option.when(rate.nonEmpty)(partsRow(c, "Rate", rate, w))

  // ---- the Block Observatory (FR-023) ----------------------------------------------------------------------------

  /** The columns of a shortened address: `0x`, four digits, the ellipsis, four digits. */
  private val ByCols: Int = 11

  /** The rows above the table's first block: the title, the chart, the column names. */
  val ObservatoryHead: Int = 3

  /** The newest blocks: the gas each used as a chart, and a table, newest first. It takes the rows nobody else wants.
    */
  def observatory(c: Ctx): Option[Panel] =
    c.state.snapshot.flatMap(_.local.chain).filter(_.recent.nonEmpty).map { chain =>
      Panel(ObservatoryHead + 2, ObservatoryHead + 8, 3, (w, n) => observatoryRows(c, chain, w, n), true, fill = true)
    }

  /** The chart's glyphs, from an empty block to a full one. */
  private def levels(g: TuiGlyphs): String = if g.set == GlyphSet.Ascii then "_.:-=+*#" else "▁▂▃▄▅▆▇█"

  /** The whole of `values`, oldest on the left, in at most `cols` glyphs: where there are more values than glyphs, a
    * glyph is the highest of the values it stands for, so a peak is never lost. Each is drawn against the highest of
    * all, or against `full` where the values have a limit of their own (a share of the time, a heap's size), so that a
    * steady low reading is drawn low and not as a full row. All zeroes is a flat line at the bottom, not a full one; a
    * value that is not known, or a glyph none of whose values is, is a gap.
    */
  def spark(values: Seq[Option[Long]], cols: Int, g: TuiGlyphs, full: Option[Long] = None): String =
    val glyphs = levels(g)
    val width = cols.max(0).min(values.size)
    if width == 0 then ""
    else
      val peak = full.filter(_ > 0).getOrElse(values.flatten.maxOption.getOrElse(0L))
      (0 until width).map { i =>
        // The values from i/width to (i+1)/width of the way through.
        val from = i.toLong * values.size / width
        val until = ((i + 1).toLong * values.size / width).max(from + 1)
        values.slice(from.toInt, until.toInt).flatten.maxOption match
          case None => ' '
          case Some(v) =>
            if peak <= 0 then glyphs.head
            else glyphs(((v.max(0L).min(peak).toDouble / peak) * (glyphs.length - 1)).round.toInt)
      }.mkString

  /** One glyph a block, oldest on the left: how much of its gas limit the block used. */
  def gasChart(blocks: Seq[BlockSummary], cols: Int, g: TuiGlyphs): String =
    val glyphs = levels(g)
    blocks
      .takeRight(cols.max(0))
      .map(b => glyphs((b.gasShare * (glyphs.length - 1)).round.toInt.max(0).min(glyphs.length - 1)))
      .mkString

  private def observatoryRows(c: Ctx, chain: ChainSample, w: Int, n: Int): Seq[AttributedString] =
    val blocks = chain.recent
    val withFee = blocks.exists(_.baseFee.isDefined)
    // The title's parts, the least needed last: one that does not fit is left out whole.
    val dot = s" ${c.g.dot.text} "
    val said = Seq(s"the newest ${formatNumber(blocks.size.toLong)} seen") ++
      Option.when(chain.sampled)("a sample: blocks arrive faster than they are read") ++
      Seq("Up Down select, Enter open")
    val kept = said.indices.reverse
      .map(i => said.take(i + 1))
      .find(parts => 10 + new AttributedString(parts.mkString(dot)).columnLength() <= w - 1)
      .getOrElse(said.take(1))
    val title = TuiText.fitToWidth(text(s" Blocks   ${kept.mkString(dot)}", c.t.healthy.bold()), w)
    val label = "   gas used "
    val chartCols = (w - 1 - label.length).max(0)
    val chart = TuiText.fitToWidth(
      concat(text(label, c.t.label), text(gasChart(blocks, chartCols, c.g), c.t.healthy)),
      w
    )
    def cells(block: String, age: String, txs: String, gas: String, size: String, fee: String, by: String): String =
      val base = f"   $block%-12s $age%7s $txs%5s  $gas%-11s $size%8s"
      val withBase = if withFee then base + f"  $fee%-12s" else base
      // Who made the block is the last column, and the first to go: on a row too narrow for it whole, it is left out.
      if withBase.length + 2 + ByCols <= w - 1 then s"$withBase  $by" else withBase
    val header =
      TuiText.fitToWidth(text(cells("block", "age", "txs", "gas used", "size", "base fee", "by"), c.t.label), w)
    // Newest first, a window that keeps the selected block on screen. The selection is a block's hash, so the row
    // under it moves down as blocks arrive, and the selection goes with it.
    val newestFirst = blocks.reverse
    val room = (n - ObservatoryHead).max(0)
    val selected = c.state.selectedBlockIndex
    val from = selected.fold(0)(at => (at - room + 1).max(0))
    val rows = newestFirst.slice(from, from + room).zipWithIndex.map { (b, i) =>
      val line = cells(
        formatNumber(b.number),
        age(b, c.state.now),
        b.txCount.fold("-")(_.toString),
        s"${magnitude(b.gasUsed)} ${percent(b.gasShare)}",
        b.sizeBytes.fold("-")(blockSize),
        b.baseFee.fold("-")(gwei),
        ClPanel.shortHex(b.miner, c.g)
      )
      val drawn = TuiText.fitToWidth(text(line, c.t.value), w - 1)
      if selected.contains(from + i) then TuiText.fitToWidth(text(drawn.toString, c.t.value.inverse()), w)
      else TuiText.fitToWidth(drawn, w)
    }
    Seq(title, chart, header) ++ rows

  // ---- fees (FR-025) and reorgs (FR-026) -------------------------------------------------------------------------

  /** The base fee and what transactions paid above it; on a chain with no base fee, the gas prices paid. */
  def fees(c: Ctx): Option[Panel] =
    c.state.snapshot.flatMap(_.local.chain).filter(_.recent.nonEmpty).map { chain =>
      Panel(1, 1, 4, (w, _) => Seq(feesRow(c, chain, w)), separated = true)
    }

  private def feesRow(c: Ctx, chain: ChainSample, w: Int): AttributedString =
    val paid = chain.recent.flatMap(_.fees)
    val base = chain.recent.flatMap(_.baseFee)
    val over = s"over ${paid.size} ${if paid.size == 1 then "block" else "blocks"} with transactions"
    def spread(what: String): Seq[String] =
      if paid.isEmpty then Seq(s"no transaction in the ${chain.recent.size} newest blocks read")
      else
        // The middle block's percentiles, so one busy block does not set them all.
        def middle(of: FeePercentiles => BigInt): BigInt = paid.map(of).sorted.apply(paid.size / 2)
        Seq(s"$what ${gwei(middle(_.p10))} / ${gwei(middle(_.p50))} / ${gwei(middle(_.p90))} (p10 / p50 / p90)", over)
    val parts = base.lastOption match
      case Some(now) =>
        Seq(s"base ${gwei(now)}") ++ spread("tips") ++
          Seq(s"base ${gwei(base.min)} to ${gwei(base.max)} over ${base.size} blocks")
      case None => spread("gas price")
    partsRow(c, "Fees", parts, w)

  /** The reorgs since the TUI started: a count, and the newest first. */
  def reorgs(c: Ctx): Option[Panel] =
    c.state.snapshot.flatMap(_.local.chain).filter(_.recent.nonEmpty).map { chain =>
      Panel(1, 1 + chain.reorgs.size.min(4), 4, (w, n) => reorgRows(c, chain, w).take(n), separated = true)
    }

  /** `depth 3`, or `depth 64 or more` where the blocks the branches share were not found. */
  def depth(r: ReorgRecord): String = s"depth ${r.depth}${if r.depthIsLowerBound then " or more" else ""}"

  private def reorgRows(c: Ctx, chain: ChainSample, w: Int): Seq[AttributedString] =
    val since = s"since the TUI started (${formatShort(c.state.uptimeSeconds)})"
    if chain.reorgs.isEmpty then Seq(partsRow(c, "Reorgs", Seq(s"none $since"), w))
    else
      val count = chain.reorgs.size
      val head = partsRow(c, "Reorgs", Seq(s"$count $since"), w)
      val rows = chain.reorgs.reverseIterator.map { r =>
        val sb = new AttributedStringBuilder()
        sb.append(s"   ${Clock.format(r.at)}  ", c.t.muted)
        sb.append(f"${depth(r)}%-17s", c.t.warn)
        sb.append(
          s"${formatNumber(r.oldTip.number)} ${ClPanel.shortHex(r.oldTip.hash, c.g)} ${c.g.arrow.text} " +
            s"${formatNumber(r.newTip.number)} ${ClPanel.shortHex(r.newTip.hash, c.g)}",
          c.t.value
        )
        TuiText.fitToWidth(sb.toAttributedString, w)
      }.toSeq
      head +: rows
