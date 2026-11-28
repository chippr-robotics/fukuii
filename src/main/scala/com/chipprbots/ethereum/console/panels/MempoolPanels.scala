package com.chipprbots.ethereum.console.panels

import java.time.Duration
import java.util.Locale

import org.jline.utils.AttributedString
import org.jline.utils.AttributedStringBuilder

import com.chipprbots.ethereum.console.*
import com.chipprbots.ethereum.console.TuiText.formatNumber
import com.chipprbots.ethereum.console.TuiText.formatShort
import com.chipprbots.ethereum.console.panels.Rows.*

/** The Mempool view (spec 029 FR-031 to FR-037): the pool's size and how full it is, what moves through it, the Mempool
  * Radar (pending transactions in four tiers by their fee against the fee the node itself would suggest), who sends
  * most, what the pool refused and lost, and the best-paying transactions, any of which opens.
  *
  * The whole pool is read only while this view is on screen (FR-037): until the first such read arrives the view shows
  * the count every view has, and says it is reading.
  */
private[console] object MempoolPanels:

  def panels(c: Ctx): Seq[Panel] =
    c.state.snapshot.flatMap(_.pool) match
      case None =>
        val waiting = Panel(
          2,
          2,
          2,
          (w, _) =>
            Seq(
              fit(field("Pool", txpoolValue(c), c.t), w, c.g),
              fit(text("   reading the pool: it is read whole only while this view is open", c.t.muted), w, c.g)
            ),
          separated = true
        )
        Seq(vitalsPanel(c, withSummary = true), waiting, about(c, None))
      case Some(pool) =>
        // The radar is whole or absent: three tiers of four would read as a pool with nothing in the fourth.
        val radar = if pool.suggested.isEmpty then 1 else 1 + FeeTier.values.length
        Seq(
          vitalsPanel(c, withSummary = true),
          Panel(2, 2, 2, (w, n) => poolRows(c, pool, w).take(n), separated = true),
          Panel(radar, radar, 3, (w, n) => radarRows(c, pool, w).take(n), separated = true),
          about(c, Some(pool)),
          Panel(3, 8, 4, (w, n) => table(c, pool, w, n), separated = true, fill = true)
        )

  /** The senders, the counters and the queue, in that order. On a short screen the rows that stay are what was refused
    * and what this client does not do (FR-035: said, not left blank), then the senders, then the rest.
    */
  private def about(c: Ctx, pool: Option[PoolSample]): Panel =
    def rows(w: Int): Seq[(Int, AttributedString)] =
      val queue = ObservatoryPanels.partsRow(
        c,
        "Queue",
        Seq("not kept by this client", "a transaction whose nonce leaves a gap is refused, not held"),
        w
      )
      val counted = counterRows(c, w)
      pool.map(p => 3 -> sendersRow(c, p, w)).toSeq ++ Seq(1 -> counted.head) ++
        counted.tail.zipWithIndex.map((row, i) => (4 + i) -> row) ++ Seq(2 -> queue)
    val all = 2 + pool.size + (if c.state.snapshot.flatMap(_.local.poolCounters).isDefined then 2 else 0)
    Panel(
      2,
      all,
      5,
      (w, n) =>
        val ranked = rows(w)
        val kept = ranked.map(_._1).sorted.take(n).toSet
        ranked.collect { case (rank, row) if kept(rank) => row }
      ,
      separated = true
    )

  /** From this age a reading of the pool says how old it is: the probe reads it every second. */
  val StaleSeconds: Long = 3L

  private def rate(perSecond: Option[Double], what: String): String =
    perSecond.fold(s"$what: measuring")(r => String.format(Locale.ROOT, "%.1f %s/s", r, what))

  private def poolRows(c: Ctx, p: PoolSample, w: Int): Seq[AttributedString] =
    def ago(at: java.time.Instant): Long = Duration.between(at, c.state.now).getSeconds.max(0L)
    // Rounded down: a pool with a slot free is not 100% full, and one with a transaction in it is not 0%.
    val percent = if p.capacity <= 0 then 0L else p.size.toLong * 100L / p.capacity
    val full =
      if p.capacity <= 0 then Nil
      else
        Seq(s"${if p.size > 0 && percent == 0 then "<1" else percent.toString}% of ${formatNumber(p.capacity.toLong)}")
    // A reading the probe could not renew says how old it is.
    val stale = Option.when(ago(p.at) >= StaleSeconds)(s"read ${formatShort(ago(p.at))} ago")
    // Blob transactions are said where the chain has them: a PoW chain's pool has none to count.
    val blobs = Option
      .when(p.blobs > 0 || c.state.chain.exists(_.family == ChainFamily.PoS))(s"${formatNumber(p.blobs.toLong)} blob")
    val oldest = p.oldest.map(at => s"oldest ${formatShort(ago(at))}")
    val pool = Seq(s"${formatNumber(p.size.toLong)} pending") ++ stale ++ full ++ blobs ++ oldest ++
      Seq(s"${formatNumber(p.local.toLong)} sent from this node")
    // The replacements count from when this watch of the pool began: the row says for how long that is.
    val flow = Seq(
      rate(p.arrivedPerSecond, "in"),
      rate(p.includedPerSecond, "mined"),
      rate(p.droppedPerSecond, "dropped"),
      s"${formatNumber(p.replaced)} replaced in the ${formatShort(ago(p.watchedSince))} watched"
    ) ++ Option.when(p.awaiting > 0)(s"${formatNumber(p.awaiting.toLong)} that left not yet told apart") ++
      Option.when(p.leftUnchecked > 0)(s"${formatNumber(p.leftUnchecked)} that left never told apart")
    Seq(ObservatoryPanels.partsRow(c, "Pool", pool, w), ObservatoryPanels.partsRow(c, "Flow", flow, w))

  /** Whether the head carries a base fee: then a fee here is a tip above it; else it is a gas price. */
  private def hasBaseFee(c: Ctx): Boolean =
    c.state.snapshot.flatMap(_.local.chain).flatMap(_.head).exists(_.baseFee.isDefined)

  /** The cells of a tier's bar. */
  val BarCols: Int = 24

  /** The Mempool Radar (FR-032): a tier a row, from the most urgent, each with its count as a bar and a figure and the
    * fee it starts at. The fees are all in gwei, however small: a column that changed unit half-way down would have to
    * be read twice.
    */
  private def radarRows(c: Ctx, p: PoolSample, w: Int): Seq[AttributedString] =
    p.suggested match
      case None =>
        Seq(
          ObservatoryPanels.partsRow(
            c,
            "Radar",
            Seq("the node has no fee to suggest yet", "no tier is drawn without one"),
            w
          )
        )
      case Some(suggested) =>
        // On a chain with a base fee the node suggests a tip, and a transaction is measured by its tip.
        val head = ObservatoryPanels.partsRow(
          c,
          "Radar",
          if hasBaseFee(c) then
            Seq(
              s"tips against the tip this node would suggest, ${ObservatoryPanels.gweiOnly(suggested)}",
              "above the base fee"
            )
          else Seq(s"gas prices against the price this node would suggest, ${ObservatoryPanels.gweiOnly(suggested)}"),
          w
        )
        val most = p.tiers.values.maxOption.getOrElse(0).max(1)
        val starts = FeeTier.boundaries(suggested).toMap
        val (full, empty) = if c.g.set == GlyphSet.Ascii then ("#", ".") else ("█", "░")
        val rows = FeeTier.values.toSeq.reverse.map { tier =>
          val count = p.tiers.getOrElse(tier, 0)
          // A tier with a transaction in it always shows a cell: a bar that rounds to nothing would say none.
          val cells = if count == 0 then 0 else ((count.toDouble / most) * BarCols).round.toInt.max(1)
          val style = tier match
            case FeeTier.Urgent => c.t.error
            case FeeTier.High   => c.t.warn
            case FeeTier.Normal => c.t.healthy
            case FeeTier.Low    => c.t.label
          val from = starts
            .get(tier)
            .fold(s"below ${ObservatoryPanels.gweiOnly(starts(FeeTier.Normal))}")(fee =>
              s"from ${ObservatoryPanels.gweiOnly(fee)}"
            )
          val sb = new AttributedStringBuilder()
          sb.append(f"   ${tier.label}%-7s ", c.t.label)
          sb.append(full * cells, style)
          sb.append(empty * (BarCols - cells), c.t.muted)
          sb.append(f" ${formatNumber(count.toLong)}%7s   $from", c.t.value)
          fit(sb.toAttributedString, w, c.g)
        }
        head +: rows

  private def sendersRow(c: Ctx, p: PoolSample, w: Int): AttributedString =
    val parts =
      if p.topSenders.isEmpty then Seq("no pending transaction")
      else p.topSenders.map((sender, n) => s"${ClPanel.shortHex(sender, c.g)} $n")
    ObservatoryPanels.partsRow(c, "Senders", parts, w)

  /** What the pool refused, lost and fetched since the node started (FR-034). */
  private def counterRows(c: Ctx, w: Int): Seq[AttributedString] =
    c.state.snapshot.flatMap(_.local.poolCounters) match
      case None => Seq(ObservatoryPanels.partsRow(c, "Refused", Seq("not counted on this node"), w))
      case Some(n) =>
        val refused =
          if n.rejected.isEmpty then Seq("none since the node started")
          else
            n.rejected.toSeq
              .sortBy((reason, count) => (-count, reason))
              .map((reason, count) => s"$reason ${formatNumber(count)}")
        Seq(
          ObservatoryPanels.partsRow(c, "Refused", refused, w),
          ObservatoryPanels.partsRow(
            c,
            "Lost",
            Seq(
              s"evicted for room ${formatNumber(n.evicted)}",
              s"expired unmined ${formatNumber(n.expired)}",
              s"replaced ${formatNumber(n.replaced)}"
            ),
            w
          ),
          ObservatoryPanels.partsRow(
            c,
            "Fetch",
            Seq(
              s"announced ${formatNumber(n.announced)}",
              s"asked for ${formatNumber(n.fetchRequested)}",
              s"timed out ${formatNumber(n.fetchTimedOut)}"
            ),
            w
          )
        )

  /** The columns of a fee, and of a transaction's kind (`blob local`). */
  private val FeeCols: Int = 12
  private val KindCols: Int = 10

  /** The rows above the table's first transaction: the title and the column names. */
  val TableHead: Int = 2

  /** The best-paying transactions, with a selection kept by hash: Enter opens one.
    *
    * A column is as wide as its widest value on screen, and never narrower than its usual width: a nonce in the
    * millions or a fee in the thousands of gwei widens its column, and is not cut or pushed out of line.
    */
  private def table(c: Ctx, p: PoolSample, w: Int, n: Int): Seq[AttributedString] =
    val more =
      if p.size > p.txs.size then
        s"the ${formatNumber(p.txs.size.toLong)} best-paying of ${formatNumber(p.size.toLong)}"
      else "best-paying first"
    val title = ObservatoryPanels.partsRow(c, "Pending", Seq(more, "Up Down select, Enter open"), w)
    if p.txs.isEmpty then Seq(title, fit(text("   no pending transaction", c.t.muted), w, c.g))
    else
      val room = (n - TableHead).max(0)
      val selected = c.state.selectedTxIndex
      // The page that holds the selection (B-267): the cursor moves within it.
      val from = ObservatoryPanels.pageStart(selected, room)
      // hash, from, nonce, fee, age, fee cap, kind
      val names =
        Vector("hash", "from", "nonce", if hasBaseFee(c) then "tip" else "gas price", "age", "fee cap", "kind")
      val values = p.txs.slice(from, from + room).map { tx =>
        Vector(
          ClPanel.shortHex(tx.hash, c.g),
          ClPanel.shortHex(tx.sender, c.g),
          String.format(Locale.ROOT, "%,d", tx.nonce.bigInteger),
          ObservatoryPanels.gwei(tx.tip),
          formatShort(Duration.between(tx.addedAt, c.state.now).getSeconds.max(0L)),
          ObservatoryPanels.gwei(tx.feeCap),
          Seq(Option.when(tx.isBlob)("blob"), Option.when(tx.local)("local")).flatten.mkString(" ")
        )
      }
      val least = Vector(0, 0, 8, FeeCols, 8, FeeCols, KindCols)
      val cols = names.indices.map(i => (values.map(_(i).length) :+ names(i).length :+ least(i)).max)
      val base = 3 + cols(0) + 2 + cols(1) + 1 + cols(2) + 2 + cols(3) + 1 + cols(4)
      // The fee cap and the kind are the last columns, and the first to go on a narrow screen: for the whole table,
      // so no row shows a column its neighbour lacks. Without a base fee a transaction's cap is its gas price: the
      // column would say the same thing twice.
      val showCap = hasBaseFee(c) && base + 2 + cols(5) <= w - 1
      val capCols = if showCap then 2 + cols(5) else 0
      val showKind = base + capCols + 2 + cols(6) <= w - 1
      def line(v: Vector[String]): String =
        val sb = new StringBuilder("   ")
        sb.append(v(0).padTo(cols(0), ' ')).append("  ").append(v(1).padTo(cols(1), ' ')).append(' ')
        sb.append(" " * (cols(2) - v(2).length)).append(v(2)).append("  ")
        sb.append(v(3).padTo(cols(3), ' ')).append(' ')
        sb.append(" " * (cols(4) - v(4).length)).append(v(4))
        if showCap then sb.append("  ").append(v(5).padTo(cols(5), ' '))
        if showKind then sb.append("  ").append(v(6))
        sb.toString
      val header = fit(text(line(names), c.t.label), w, c.g)
      val rows = values.zipWithIndex.map { (v, i) =>
        val drawn = TuiText.fitToWidth(text(line(v), c.t.value), w - 1)
        if selected.contains(from + i) then TuiText.fitToWidth(text(drawn.toString, c.t.value.inverse()), w)
        else TuiText.fitToWidth(drawn, w)
      }
      Seq(title, header) ++ rows
