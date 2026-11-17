package com.chipprbots.ethereum.console.panels

import java.time.Duration

import org.jline.utils.AttributedString
import org.jline.utils.AttributedStringBuilder
import org.jline.utils.AttributedStyle

import com.chipprbots.ethereum.console.*
import com.chipprbots.ethereum.console.panels.Rows.*

/** The peer radar (spec 029 FR-030, D-2, research §6.6): this node at the centre and one blip a peer.
  *
  *   - Distance is the peer's request latency, in bands: the nearer the centre, the faster it answers. A peer that has
  *     answered nothing yet is on the outer ring, and drawn as unmeasured.
  *   - The angle is fixed by the peer's node id, so a peer is in the same direction from frame to frame and from run to
  *     run. Two peers that would share a cell are set side by side along their ring.
  *   - The colour is its score band; the shape is its direction; it is bold for a second after it answers a request.
  *
  * Nothing else is drawn: no line between two peers, which the node does not know of, and no geography, which it has no
  * source for (FR-021). Every peer here is a row of the list beside it.
  */
private[console] object RadarPanel:

  /** The latency bands, in milliseconds: a peer is on the ring of the first band it is under. */
  val Bands: Seq[Double] = Seq(100.0, 500.0, 2000.0)

  /** The rings: one a band, one for the slower, and the outer one for the unmeasured. */
  val Rings: Int = Bands.size + 2

  /** How long a blip stays bold after its peer answered. */
  val FlashFor: Duration = Duration.ofSeconds(1)

  /** A peer on the radar.
    *
    * @param ring
    *   from 0, the innermost, to `Rings - 1`, the unmeasured
    * @param angle
    *   in turns, from 0 to 1, clockwise from the top
    */
  final case class Blip(
      id: String,
      ring: Int,
      angle: Double,
      incoming: Boolean,
      score: Option[Double],
      flashing: Boolean
  ):
    def measured: Boolean = ring < Rings - 1

  /** The ring of a latency: the band it is under; the last measured ring for anything slower; the outer for none. */
  def ringOf(latencyMs: Option[Double]): Int = latencyMs match
    case None => Rings - 1
    case Some(ms) =>
      Bands.indexWhere(ms < _) match
        case -1 => Bands.size
        case i  => i

  /** The angle of a node id, in turns: its first eight hexadecimal digits as a fraction. A peer's place depends on
    * nothing but its id.
    */
  def angleOf(id: String): Double =
    val digits = id.stripPrefix("0x").filter(c => Character.digit(c, 16) >= 0).take(8)
    if digits.isEmpty then 0.0 else java.lang.Long.parseLong(digits, 16).toDouble / math.pow(16, digits.length)

  /** The peers of `state` as blips. */
  def blips(state: TuiState): Seq[Blip] =
    state.orderedPeers.map { peer =>
      val stats = state.peerStats(peer.id)
      val flashing = stats.flatMap(_.lastSeen).exists(at => Duration.between(at, state.now).compareTo(FlashFor) <= 0)
      Blip(
        peer.id,
        ringOf(stats.flatMap(_.latencyMs)),
        angleOf(peer.id),
        peer.incoming,
        state.peerScore(peer.id),
        flashing
      )
    }

  /** The radar's size for a height: `rows` rows of twice as many columns, since a cell is twice as tall as it is wide.
    */
  def cols(rows: Int): Int = rows * 2 - 1

  /** Where each blip is drawn in a radar of `rows` rows: its (row, column). Two peers that fall on one cell are moved
    * apart along their ring, the first by id keeping its place, so that every peer has a cell while there are cells.
    */
  def place(all: Seq[Blip], rows: Int): Map[(Int, Int), Blip] =
    val centre = (rows - 1) / 2.0
    val radius = centre
    def cell(ring: Int, angle: Double): (Int, Int) =
      val r = radius * (ring + 1) / Rings
      val theta = angle * 2 * math.Pi
      // Clockwise from the top; the column runs twice as fast as the row.
      val row = (centre - r * math.cos(theta)).round.toInt
      val col = (2 * centre + 2 * r * math.sin(theta)).round.toInt
      (row.max(0).min(rows - 1), col.max(0).min(cols(rows) - 1))
    all.sortBy(_.id).foldLeft(Map.empty[(Int, Int), Blip]) { (taken, blip) =>
      val steps = Iterator.from(0).take(64).map(i => blip.angle + i * 0.02 * (if i % 2 == 0 then 1 else -1))
      val free =
        steps.map(a => cell(blip.ring, a - math.floor(a))).find(c => !taken.contains(c) && c != centreCell(rows))
      free.fold(taken)(c => taken.updated(c, blip))
    }

  private def centreCell(rows: Int): (Int, Int) = ((rows - 1) / 2, rows - 1)

  /** The rows a radar of this many peers' panel needs: an odd number, so that it has a middle row. */
  def rowsFor(available: Int): Int =
    val odd = if available % 2 == 0 then available - 1 else available
    odd.min(21)

  /** The smallest radar drawn: its five rings need a radius of five rows. */
  val MinRows: Int = 11

  /** The columns beside the radar that its legend takes. */
  val LegendCols: Int = 32

  /** The radar and its legend for a panel of `n` rows: `n` rows, a column in from the panel's edge. */
  def draw(c: Ctx, n: Int): Seq[AttributedString] =
    val rows = rowsFor(n)
    val width = cols(rows)
    val placed = place(blips(c.state), rows)
    val selected = c.state.selectedPeer
    val ascii = c.g.set == GlyphSet.Ascii
    val centre = centreCell(rows)
    def glyph(b: Blip): String =
      if ascii then (if b.incoming then "x" else "o") else (if b.incoming then "◆" else "●")
    def style(b: Blip): AttributedStyle =
      val band = b.score match
        case Some(s) if s >= 0.7 => c.t.healthy
        case Some(s) if s >= 0.4 => c.t.warn
        case Some(_)             => c.t.error
        case None                => c.t.muted
      val measured = if b.measured then band else band.faint()
      val lit = if b.flashing then measured.bold() else measured
      if selected.contains(b.id) then lit.inverse() else lit
    // The rings, as dots on the middle row and the middle column: enough to read a blip's band, and no more.
    def ringMark(row: Int, col: Int): Boolean =
      val radius = (rows - 1) / 2.0
      (1 to Rings).exists { ring =>
        val r = (radius * ring / Rings).round.toInt
        (row == centre._1 && (col == centre._2 + 2 * r || col == centre._2 - 2 * r)) ||
        (col == centre._2 && (row == centre._1 + r || row == centre._1 - r))
      }
    val grid = (0 until rows).map { row =>
      val sb = new AttributedStringBuilder()
      (0 until width).foreach { col =>
        placed.get((row, col)) match
          case Some(b)                      => sb.append(glyph(b), style(b))
          case None if (row, col) == centre => sb.append(if ascii then "+" else "✛", c.t.value.bold())
          case None if ringMark(row, col)   => sb.append(if ascii then "." else "·", c.t.muted)
          case None                         => sb.append(" ")
      }
      TuiText.fitToWidth(sb.toAttributedString, width)
    }
    val shown = placed.size
    val all = c.state.orderedPeers.size
    val unmeasured = placed.values.count(!_.measured)
    val legend: Seq[AttributedString] = Seq(
      text("Radar", c.t.healthy.bold()),
      text(s"$shown of $all peers", c.t.value),
      blank,
      text("nearer = answers faster", c.t.label),
      text(s"rings: under ${Bands.map(_.toInt).mkString(", ")} ms,", c.t.muted),
      text("slower, and not measured", c.t.muted),
      text(s"$unmeasured not measured yet", c.t.muted),
      blank,
      concat(
        text(if ascii then "o" else "●", c.t.value),
        text(" outbound  ", c.t.muted),
        text(if ascii then "x" else "◆", c.t.value),
        text(" inbound", c.t.muted)
      ),
      concat(text("score ", c.t.muted), text("good", c.t.healthy), text(" fair ", c.t.warn), text("poor", c.t.error)),
      text("bold: answered just now", c.t.muted),
      text("angle: fixed by the node id", c.t.muted)
    )
    val body = (0 until n).map { i =>
      val sb = new AttributedStringBuilder()
      sb.append(" ")
      sb.append(grid.lift(i).getOrElse(TuiText.fitToWidth(blank, width)))
      sb.append("  ")
      sb.append(TuiText.fitToWidth(legend.lift(i).getOrElse(blank), LegendCols))
      sb.toAttributedString
    }
    body

  /** The radar as a panel of the Peers view: from tier M, where there is a peer to draw. It asks for more rows on a
    * taller screen, and is not drawn at all with fewer than [[MinRows]].
    */
  def panel(c: Ctx): Option[Panel] =
    Option.when(c.screenTier != Tier.S && c.state.orderedPeers.nonEmpty) {
      val preferred = if c.screenTier == Tier.L then 17 else 13
      Panel(MinRows, preferred, 3, (w, n) => draw(c, n).map(TuiText.fitToWidth(_, w)), separated = true)
    }
