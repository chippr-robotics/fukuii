package com.chipprbots.ethereum.console.panels

import org.jline.utils.AttributedString
import org.jline.utils.AttributedStyle

import com.chipprbots.ethereum.console.*
import com.chipprbots.ethereum.console.TuiText.fitToWidth
import com.chipprbots.ethereum.console.TuiText.formatNumber
import com.chipprbots.ethereum.console.panels.Rows.*

/** The handshaked peers (spec 027 FR-019, research §3.1, §3.4): id, direction and age from the peer manager's
  * `GetPeersCmd`; client and best block from the network peer manager's `GetHandshakedPeersCmd`; the score and its
  * trend from slice 4a's `NetworkMetrics` export, through [[TuiState.peerScore]] and [[TuiState.scoreTrend]]. Highest
  * score first; a peer with no published score yet shows `—`, never zero, and comes last.
  *
  * The columns grow with the tier (research §3.1): at S the id, direction, client, best block and age; from M the
  * address and the score; at L the trend. The Overview's top table (tier L) has no address: it shares its row with the
  * logo column.
  *
  * The client id is the peer's own choice of text: it is sanitised, cut to its name and version, and drawn through
  * [[TuiText.external]] (C-16).
  */
private[console] object PeersPanel:

  /** How many peers the Overview's top table lists (research §3.4). */
  val TopPeers: Int = 8

  private val IdCols = 12
  private val DirCols = 5
  private val AddressCols = 24
  private val ClientCols = 22
  private val BestCols = 12
  private val ScoreCols = 7
  private val TrendCols = 7

  /** The Peers view: the vitals and sync summary, then the table, which takes the rest of the screen. */
  def panels(c: Ctx): Seq[Panel] =
    Seq(
      vitalsPanel(c, withSummary = true),
      Panel(1, 1, 2, (w, n) => table(c, w, n, top = false), separated = true, fill = true)
    )

  private enum Column(val head: String, val cols: Int):
    case Id extends Column("id", IdCols)
    case Dir extends Column("dir", DirCols)
    case Address extends Column("address", AddressCols)
    case Client extends Column("client", ClientCols)
    case Best extends Column("best", BestCols)
    case Score extends Column("score", ScoreCols)
    case Trend extends Column("trend", TrendCols)
    case Age extends Column("age", 0)

  /** The columns at `tier`; the Overview's top table leaves out the address. */
  private def columns(tier: Tier, top: Boolean): Seq[Column] =
    import Column.*
    tier match
      case Tier.S => Seq(Id, Dir, Client, Best, Age)
      case Tier.M => Seq(Id, Dir) ++ Option.when(!top)(Address) ++ Seq(Client, Best, Score, Age)
      case Tier.L => Seq(Id, Dir) ++ Option.when(!top)(Address) ++ Seq(Client, Best, Score, Trend, Age)

  /** The peers in `rows` rows: a title, the column heads, then one peer a row, highest score first. `top` is the
    * Overview's table of the [[TopPeers]] best.
    */
  def table(c: Ctx, w: Int, rows: Int, top: Boolean): Seq[AttributedString] =
    val dot = c.g.dot.text
    c.state.snapshot.map(_.peers) match
      case None => Seq(fit(field("Peers", text("not polled yet", c.t.muted), c.t), w, c.g))
      case Some(Sourced.Unresponsive(reason)) =>
        val why = TuiText.external(reason, c.g)
        Seq(fit(field("Peers", text(s"peer manager Unresponsive ($why)", c.t.error), c.t), w, c.g))
      case Some(source) =>
        val peers = c.state.orderedPeers
        val room = (rows - 2).max(0)
        // Every row a peer when all fit; else the last row says how many more there are. The top table lists its best.
        val shown =
          if top then peers.size.min(TopPeers).min(room)
          else if peers.size <= room then peers.size
          else (room - 1).max(0)
        // The list (spec 029 FR-005): the page of rows that holds the selection, so the selected row is always on
        // screen and the rows do not slide under it.
        val selected = if top then None else c.state.selectedPeerIndex
        val first = if top || shown <= 0 then 0 else selected.fold(0)(i => (i / shown) * shown)
        val more = Option.when(!top && shown < peers.size && room > 0)(
          fit(
            text(
              if first == 0 then s"   + ${peers.size - shown} more"
              else s"   rows ${first + 1}-${(first + shown).min(peers.size)} of ${peers.size}",
              c.t.muted
            ),
            w,
            c.g
          )
        )
        val stale = source match
          case Sourced.Stale(_, since) => s" $dot last answer ${ago(c, since)} ago"
          case _                       => ""
        val details = detailsNote(c)
        val inbound = peers.count(_.incoming)
        // "By score" only when every row listed has one: the top table lists the unscored after the scored.
        val scored = peers.count(p => c.state.peerScore(p.id).isDefined)
        val filter = c.state.filterOf(TuiView.Peers)
        val filtered = if !top && filter.nonEmpty then s" $dot filter '${TuiText.external(filter, c.g)}'" else ""
        val heading =
          if top then
            val by = if scored >= shown then " by score" else s", $scored scored"
            s" Peers (top $shown of ${peers.size}$by)$stale"
          else
            s" Peers   ${peers.size} handshaked of ${c.state.maxPeers} max $dot $inbound in $dot " +
              s"${peers.size - inbound} out$stale$details$filtered"
        val warn = stale.nonEmpty || details.nonEmpty
        val title = fit(text(heading, if warn then c.t.warn else c.t.healthy.bold()), w, c.g)
        val cols = columns(c.tier, top)
        val heads = fit(line(c, cols.map(col => col -> col.head), c.t.muted), w, c.g)
        val body = peers.slice(first, first + shown).zipWithIndex.map { (p, i) =>
          val style = if selected.contains(first + i) then c.t.value.inverse() else c.t.value
          fit(line(c, cols.map(col => col -> cell(c, p, col)), style), w, c.g)
        }
        if peers.isEmpty then
          val none = if filter.nonEmpty && !top then "   no peer matches the filter" else "   no handshaked peer"
          Seq(title, fit(text(none, c.t.muted), w, c.g))
        else Seq(title, heads) ++ body ++ more

  /** Why the client and best block read `?` or may be old: the network peer manager (`GetHandshakedPeersCmd`) did not
    * answer, or missed the last polls.
    */
  private def detailsNote(c: Ctx): String =
    val dot = c.g.dot.text
    c.state.snapshot.flatMap(_.details) match
      case Some(Sourced.Unresponsive(reason)) => s" $dot client/best: Unresponsive (${TuiText.external(reason, c.g)})"
      case Some(Sourced.Stale(_, since))      => s" $dot client/best ${ago(c, since)} old"
      case _                                  => ""

  private def cell(c: Ctx, p: TuiPeer, column: Column): String =
    val details = c.state.snapshot.flatMap(_.details).flatMap(_.value).flatMap(_.get(p.id))
    column match
      case Column.Id      => shortId(p.id, c.g)
      case Column.Dir     => if p.incoming then "in" else "out"
      case Column.Address => TuiText.external(p.address, c.g)
      case Column.Client =>
        details.map(d => TuiText.external(TuiText.clientName(d.clientId), c.g)).filter(_.nonEmpty).getOrElse("?")
      case Column.Best => details.map(_.bestBlock).filter(_ > 0).fold("?")(formatNumber)
      // From the same units the order and the trend use, so what is drawn, what is sorted on and what trends agree
      // exactly: `%.3f` rounds half up on the binary value and can disagree with `scoreUnits` at a half-way tie.
      case Column.Score =>
        c.state
          .peerScore(p.id)
          .fold(c.g.missing.text)(s =>
            java.math.BigDecimal.valueOf(TuiState.scoreUnits(s), TuiState.ScoreDigits).toPlainString
          )
      case Column.Trend =>
        c.state.scoreTrend(p.id).fold("") {
          case Trend.Up     => c.g.up.text
          case Trend.Down   => c.g.down.text
          case Trend.Steady => c.g.steady.text
        }
      case Column.Age => ago(c, p.connectedAt)

  /** One row of the table, each cell fitted to its columns, cut with the ellipsis, so a wide glyph or a long client id
    * never moves the next one.
    */
  private def line(c: Ctx, cells: Seq[(Column, String)], style: AttributedStyle): AttributedString =
    concat(
      (text("   ", style) +: cells.map { (col, value) =>
        if col.cols <= 0 then text(value, style)
        else fitToWidth(fit(text(value, style), col.cols - 1, c.g, reserveLast = false), col.cols)
      })*
    )
