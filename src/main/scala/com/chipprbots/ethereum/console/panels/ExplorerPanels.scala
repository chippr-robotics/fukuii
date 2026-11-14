package com.chipprbots.ethereum.console.panels

import java.time.Duration
import java.time.Instant
import java.util.Locale

import org.jline.utils.AttributedString

import com.chipprbots.ethereum.console.*
import com.chipprbots.ethereum.console.TuiText.formatNumber
import com.chipprbots.ethereum.console.panels.Rows.*

/** The explorer's screens (spec 029 FR-028, FR-029): a block, a transaction, a receipt, an account, each drawn from
  * what the lookup worker found. A screen's rows that lead somewhere (a block's transactions, a transaction's receipt,
  * the addresses in it) can be selected and opened, so the operator walks from a block to a transaction to its receipt
  * to an account without typing a hash.
  *
  * While the worker reads, the screen says so once the read has taken [[Lookups.SlowAfterMillis]]; a failure is the
  * sentence the reader gave.
  */
private[console] object ExplorerPanels:

  /** A row that leads somewhere: what it says, and the lookup Enter opens. */
  final case class Link(label: String, value: String, query: LookupQuery)

  /** The rows of `result` that lead somewhere, in the order the screen lists them. */
  def links(result: LookupResult): Vector[Link] = result match
    case LookupResult.Block(summary, txs, _, _) =>
      Link("parent", summary.parentHash, LookupQuery.BlockByHash(summary.parentHash)) +:
        Link("by", summary.miner, LookupQuery.Account(summary.miner)) +:
        txs.map(t => Link(s"tx ${t.index}", t.hash, LookupQuery.Tx(t.hash)))
    case LookupResult.Tx(line, _, blockHash, _, _) =>
      Vector(
        Link("receipt", line.hash, LookupQuery.Receipt(line.hash)),
        Link("block", blockHash, LookupQuery.BlockByHash(blockHash))
      ) ++
        line.from.map(a => Link("from", a, LookupQuery.Account(a))) ++
        line.to.map(a => Link("to", a, LookupQuery.Account(a)))
    case r: LookupResult.Receipt =>
      Vector(Link("tx", r.txHash, LookupQuery.Tx(r.txHash))) ++
        r.from.map(a => Link("from", a, LookupQuery.Account(a))) ++
        r.to.map(a => Link("to", a, LookupQuery.Account(a))) ++
        r.contract.map(a => Link("created", a, LookupQuery.Account(a))) ++
        r.logs.zipWithIndex.map((l, i) => Link(s"log $i", l.address, LookupQuery.Account(l.address)))
    case _: LookupResult.Account => Vector.empty

  /** The lookup a screen's selected row opens, if the screen has a result and the row exists. */
  def target(state: TuiState, query: LookupQuery, selected: Int): Option[LookupQuery] =
    state
      .lookupOf(query)
      .collect { case LookupState.Done(result) => links(result) }
      .flatMap(_.lift(selected))
      .map(_.query)

  /** How many rows of `query`'s screen lead somewhere. */
  def linkCount(state: TuiState, query: LookupQuery): Int =
    state.lookupOf(query).collect { case LookupState.Done(result) => links(result).size }.getOrElse(0)

  /** Wei as the chain's coin, to six decimals: `1.25 ETC`, `0.000001 ETH`, and `0` for nothing. */
  def coin(wei: BigInt, c: Ctx): String =
    val unit = if c.state.chain.exists(_.family == ChainFamily.PoS) then "ETH" else "ETC"
    if wei == 0 then s"0 $unit"
    else
      val value = BigDecimal(wei) / BigDecimal("1000000000000000000")
      if value < BigDecimal("0.000001") then s"${ObservatoryPanels.gwei(wei)} of $unit"
      else
        val text = value.setScale(6, BigDecimal.RoundingMode.DOWN).bigDecimal.stripTrailingZeros.toPlainString
        s"$text $unit"

  def panels(c: Ctx, query: LookupQuery, selected: Int): Seq[Panel] =
    Seq(Panel(2, 40, 1, (w, n) => draw(c, query, selected, w, n), fill = true))

  /** What the title names: a hash by its two ends, since the screen's own rows carry it whole. */
  private def subject(query: LookupQuery, g: TuiGlyphs): String = query match
    case LookupQuery.LatestBlock      => "the head"
    case LookupQuery.BlockByNumber(n) => formatNumber(n)
    case LookupQuery.BlockByHash(h)   => ClPanel.shortHex(h, g)
    case LookupQuery.Tx(h)            => ClPanel.shortHex(h, g)
    case LookupQuery.Receipt(h)       => s"of transaction ${ClPanel.shortHex(h, g)}"
    case LookupQuery.Account(a)       => ClPanel.shortHex(a, g)

  private def draw(c: Ctx, query: LookupQuery, selected: Int, w: Int, n: Int): Seq[AttributedString] =
    val title = fit(text(s" ${query.title}   ${subject(query, c.g)}", c.t.healthy.bold()), w, c.g)
    def note(words: String, style: org.jline.utils.AttributedStyle) = fit(text(s"   $words", style), w, c.g)
    val body = c.state.lookupOf(query) match
      case None => Seq(note("not looked up: the node has not handed the TUI its lookups", c.t.muted))
      case Some(LookupState.Running(since)) =>
        val waited = Duration.between(since, c.state.now).toMillis
        // A read that returns at once shows nothing of itself; one that takes long says so, and how to leave it.
        if waited < Lookups.SlowAfterMillis then Seq(blank)
        else Seq(note(s"looking it up: ${TuiText.formatShort(waited / 1000)} so far. Esc cancels", c.t.label))
      case Some(LookupState.Failed(reason)) => Seq(note(TuiText.external(reason, c.g), c.t.warn))
      case Some(LookupState.Done(result)) =>
        val shown = details(c, result, w)
        // The rows the links have: what the title, the blank under it and the details leave.
        shown ++ linkRows(c, links(result), selected, w, n - 2 - shown.size)
    title +: blank +: body

  /** The columns a label takes: the longest label and a space. */
  private val LabelCols: Int = 11

  /** A label and its value, as one row. A value too long for the row (a hash, or an old receipt's state root, on an
    * 80-column screen) goes whole onto a row of its own under the label: no value is ever cut or dropped.
    */
  private def rows(c: Ctx, label: String, value: String, w: Int): Seq[AttributedString] =
    val padded = label.padTo(LabelCols, ' ')
    if 3 + LabelCols + value.length <= w - 1 then
      Seq(TuiText.fitToWidth(concat(text(s"   $padded", c.t.label), text(value, c.t.value)), w))
    else
      // Under the label, in as many rows as it takes: each holds what fits before the row's last column.
      val room = (w - 4).max(8)
      TuiText.fitToWidth(text(s"   $padded", c.t.label), w) +:
        value.grouped(room).toSeq.map(part => TuiText.fitToWidth(text(s"   $part", c.t.value), w))

  /** A number of any size with thousands separators: a nonce or a difficulty may not fit a `Long`. */
  private def big(n: BigInt): String = String.format(Locale.ROOT, "%,d", n.bigInteger)

  private def details(c: Ctx, result: LookupResult, w: Int): Seq[AttributedString] =
    def row(label: String, value: String): Seq[AttributedString] = rows(c, label, value, w)
    def some(label: String, value: Option[String]): Seq[AttributedString] = value.toSeq.flatMap(row(label, _))
    result match
      case LookupResult.Block(b, txs, uncles, withdrawals) =>
        val pos = c.state.chain.exists(_.family == ChainFamily.PoS)
        val age =
          TuiText.formatShort(Duration.between(Instant.ofEpochSecond(b.timestamp), c.state.now).getSeconds.max(0L))
        val contents = b.txCount.fold("the body is not stored on this node") { count =>
          val parts = Seq(s"$count ${if count == 1 then "transaction" else "transactions"}") ++
            Option.when(!pos)(s"$uncles ${if uncles == 1 then "uncle" else "uncles"}") ++
            withdrawals.map(n => s"$n ${if n == 1 then "withdrawal" else "withdrawals"}")
          parts.mkString(s" ${c.g.dot.text} ") + (if txs.isEmpty && count > 0 then " (not listed)" else "")
        }
        row("number", formatNumber(b.number)) ++ row("hash", b.hash) ++
          row("time", s"${Instant.ofEpochSecond(b.timestamp)} ($age ago)") ++
          row("gas", s"${big(b.gasUsed)} of ${big(b.gasLimit)} (${(b.gasShare * 100).round}%)") ++
          some("base fee", b.baseFee.map(ObservatoryPanels.gwei)) ++
          some("size", b.sizeBytes.map(ObservatoryPanels.blockSize)) ++
          some("difficulty", Option.when(!pos)(big(b.difficulty))) ++
          some("slot", b.slot.map(formatNumber)) ++
          row("contents", contents) :+ blank
      case LookupResult.Tx(t, blockNumber, _, nonce, inputBytes) =>
        // What it paid, and for a fee-market transaction what it offered: the two differ, and both are asked for.
        val offered = t.feeCaps.fold("") { (fee, tip) =>
          s" paid (max fee ${ObservatoryPanels.gwei(fee)}, max tip ${ObservatoryPanels.gwei(tip)})"
        }
        row("hash", t.hash) ++ row("block", s"${formatNumber(blockNumber)}, transaction ${t.index}") ++
          row("type", t.kind) ++ row("value", coin(t.valueWei, c)) ++
          row("gas price", s"${ObservatoryPanels.gwei(t.gasPrice)}$offered") ++
          row("gas limit", big(t.gasLimit)) ++ row("nonce", big(nonce)) ++
          row("input", if inputBytes == 0 then "none" else s"${formatNumber(inputBytes.toLong)} bytes") :+ blank
      case r: LookupResult.Receipt =>
        // The receipt's running total is its own count: on a chain whose header counts gas another way (Amsterdam)
        // the last receipt's total is not the header's gas used, so it is not called the block's.
        row("status", r.status) ++ row("block", s"${formatNumber(r.blockNumber)}, transaction ${r.index}") ++
          row("gas used", s"${big(r.gasUsed)} (cumulative ${big(r.cumulativeGasUsed)})") ++
          row("logs", if r.logs.isEmpty then "none" else r.logs.size.toString) :+ blank
      case LookupResult.Account(address, atBlock, balance, nonce, code) =>
        row("address", address) ++
          row("at block", s"${formatNumber(atBlock)}, the head when it was read; r reads it again") ++
          row("balance", coin(balance, c)) ++ row("nonce", big(nonce)) ++
          row("code", code.fold("none: not a contract")(bytes => s"${formatNumber(bytes.toLong)} bytes"))

  /** The rows that lead somewhere, the selected one in reverse video, windowed so that it stays on screen. */
  private def linkRows(c: Ctx, all: Vector[Link], selected: Int, w: Int, n: Int): Seq[AttributedString] =
    if all.isEmpty then Nil
    else
      val at = selected.max(0).min(all.size - 1)
      val head = fit(text("   Enter opens the selected row", c.t.muted), w, c.g)
      // The rows under this heading; at least one, so the selected row is always drawn.
      val room = (n - 1).max(1)
      val from = (at - room + 1).max(0)
      head +: all.slice(from, from + room).zipWithIndex.map { (link, i) =>
        // A label of ten columns: a 64-digit hash then ends one column short of an 80-column row.
        val line = String.format(Locale.ROOT, "   %-10s%s", link.label, link.value)
        val drawn = TuiText.fitToWidth(new AttributedString(line, c.t.value), w - 1)
        if from + i == at then TuiText.fitToWidth(new AttributedString(drawn.toString, c.t.value.inverse()), w)
        else TuiText.fitToWidth(drawn, w)
      }
