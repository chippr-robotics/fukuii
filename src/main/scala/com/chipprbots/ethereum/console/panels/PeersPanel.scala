package com.chipprbots.ethereum.console.panels

import org.jline.utils.AttributedString

import com.chipprbots.ethereum.console.*
import com.chipprbots.ethereum.console.TuiText.fitToWidth
import com.chipprbots.ethereum.console.panels.Rows.*

/** The handshaked peers, from the peer manager's `GetPeersCmd`: id, direction, address and how long connected (spec 027
  * FR-019, research §3.1). Slice 4 adds the client id and best block (`GetHandshakedPeersCmd`) and the score with its
  * trend, and sorts by score; until then the longest-connected come first.
  */
private[console] object PeersPanel:

  private val IdCols = 14
  private val DirCols = 5
  private val AddressCols = 26

  /** The Peers view: the vitals and sync summary, then the table, which takes the rest of the screen. */
  def panels(c: Ctx): Seq[Panel] =
    Seq(
      Panel(2, 2, 1, (w, _) => Seq(vitalsRow(c, w), syncSummaryRow(c, w))),
      Panel(1, 1, 2, (w, n) => table(c, w, n, top = false), separated = true, fill = true)
    )

  /** The peers in `rows` rows: a title, the column heads, then one peer a row. `top` is the Overview's short table. */
  def table(c: Ctx, w: Int, rows: Int, top: Boolean): Seq[AttributedString] =
    val dot = c.g.dot.text
    c.state.snapshot.map(_.peers) match
      case None => Seq(fitToWidth(field("Peers", text("not polled yet", c.t.muted), c.t), w))
      case Some(Sourced.Unresponsive(reason)) =>
        Seq(fitToWidth(field("Peers", text(s"peer manager Unresponsive ($reason)", c.t.error), c.t), w))
      case Some(source) =>
        val peers = source.value.getOrElse(Nil).sortBy(p => (p.connectedAt, p.id))
        val shown = (rows - 2).max(0)
        val stale = source match
          case Sourced.Stale(_, since) => s" $dot last answer ${ago(c, since)} ago"
          case _                       => ""
        val inbound = peers.count(_.incoming)
        val heading =
          if top then s" Peers (${peers.size.min(shown)} of ${peers.size}, longest connected)$stale"
          else
            s" Peers   ${peers.size} handshaked of ${c.state.maxPeers} max $dot $inbound in $dot " +
              s"${peers.size - inbound} out$stale"
        val title = fitToWidth(text(heading, if stale.nonEmpty then c.t.warn else c.t.healthy.bold()), w)
        val heads = fitToWidth(line("id", "dir", "address", "connected", c.t.muted), w)
        val body = peers.take(shown).map { p =>
          fitToWidth(
            line(shortId(p.id, c.g), if p.incoming then "in" else "out", p.address, ago(c, p.connectedAt), c.t.value),
            w
          )
        }
        if peers.isEmpty then Seq(title, fitToWidth(text("   no handshaked peer", c.t.muted), w))
        else Seq(title, heads) ++ body

  /** One row of the table, each cell fitted to its columns so a wide glyph never moves the next one. */
  private def line(id: String, dir: String, address: String, age: String, style: org.jline.utils.AttributedStyle) =
    concat(
      text("   ", style),
      fitToWidth(text(id, style), IdCols),
      fitToWidth(text(dir, style), DirCols),
      fitToWidth(text(address, style), AddressCols),
      text(age, style)
    )
