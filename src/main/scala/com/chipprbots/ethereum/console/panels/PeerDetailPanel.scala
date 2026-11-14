package com.chipprbots.ethereum.console.panels

import org.jline.utils.AttributedString

import com.chipprbots.ethereum.console.*
import com.chipprbots.ethereum.console.TuiText.formatNumber
import com.chipprbots.ethereum.console.panels.Rows.*

/** One peer, opened from the Peers list with Enter (spec 029 FR-005, FR-030): everything the node knows about it, in
  * full. The client id is the peer's own choice of text and is drawn through [[TuiText.external]] (spec 027 C-16).
  */
private[console] object PeerDetailPanel:

  def panels(c: Ctx, peerId: String): Seq[Panel] =
    Seq(
      vitalsPanel(c, withSummary = true),
      Panel(2, 12, 2, (w, _) => rows(c, peerId, w), separated = true),
      fillPanel
    )

  private def rows(c: Ctx, peerId: String, w: Int): Seq[AttributedString] =
    val s = c.state
    val peer = s.snapshot.flatMap(_.peers.value).getOrElse(Nil).find(_.id == peerId)
    val title = fit(text(s" Peer   Esc returns to the list", c.t.healthy.bold()), w, c.g)
    // The id in full, over as many rows as it takes: it is what an operator copies, so it is never cut.
    val room = (w - 2 - FieldLabelCols - 1).max(8)
    val chunk = Seq(128, 64, 32, 16, 8).find(_ <= room).getOrElse(8)
    val id = TuiText.external(peerId, c.g).grouped(chunk).toSeq.zipWithIndex.map { (part, i) =>
      fit(field(if i == 0 then "Id" else "", text(part, c.t.value), c.t), w, c.g)
    }
    peer match
      case None =>
        (title +: id) :+ fit(field("State", text("no longer connected", c.t.warn), c.t), w, c.g)
      case Some(p) =>
        val details = s.snapshot.flatMap(_.details).flatMap(_.value).flatMap(_.get(p.id))
        val score = s.peerScore(p.id).fold(s"${c.g.missing.text} not scored yet") { v =>
          val units = java.math.BigDecimal.valueOf(TuiState.scoreUnits(v), TuiState.ScoreDigits).toPlainString
          val trend = s.scoreTrend(p.id).fold("") {
            case Trend.Up     => s" ${c.g.up.text} rising"
            case Trend.Down   => s" ${c.g.down.text} falling"
            case Trend.Steady => s" ${c.g.steady.text} steady"
          }
          units + trend
        }
        val fields: Seq[(String, String)] = Seq(
          "Address" -> TuiText.external(p.address, c.g),
          "Dir" -> (if p.incoming then "inbound: it dialled this node" else "outbound: this node dialled it"),
          "Age" -> s"connected ${ago(c, p.connectedAt)} ago",
          "Client" -> details.map(d => TuiText.external(d.clientId, c.g)).filter(_.nonEmpty).getOrElse("?"),
          "Best" -> details.map(_.bestBlock).filter(_ > 0).fold("?")(formatNumber),
          "Score" -> score
        )
        (title +: id) ++ fields.map((label, value) => fit(field(label, text(value, c.t.value), c.t), w, c.g))
