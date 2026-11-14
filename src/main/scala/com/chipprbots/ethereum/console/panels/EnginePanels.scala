package com.chipprbots.ethereum.console.panels

import java.time.Duration
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

import org.jline.utils.AttributedString
import org.jline.utils.AttributedStringBuilder

import com.chipprbots.ethereum.console.*
import com.chipprbots.ethereum.console.TuiText.formatNumber
import com.chipprbots.ethereum.console.TuiText.formatShort
import com.chipprbots.ethereum.console.panels.Rows.*

/** The Engine Synchronization Monitor (spec 029 FR-040), on a node with a consensus client: the link and who is at its
  * other end; the head, safe and finalized blocks and how far apart they are; the last call of each kind with what it
  * was answered and how long that took; the payloads; failed authentications by reason; and the last calls as a log.
  *
  * The link's marker stands at the count of calls: it moves one place for each call that arrived and stays where it is
  * while none does. Nothing here moves with the clock. What the consensus client sent (its name, its version) is drawn
  * as any text from outside the node is.
  */
private[console] object EnginePanels:

  private val Clock = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneOffset.UTC)

  /** The places the link's marker moves through. */
  val LinkCols: Int = 12

  /** `EL ───●──────── CL`: the marker one place on for each call. */
  def link(callsTotal: Long, g: TuiGlyphs): String =
    val (line, mark) = if g.set == GlyphSet.Ascii then ("-", "o") else ("─", "●")
    val at = (callsTotal % LinkCols).toInt
    s"EL ${line * at}$mark${line * (LinkCols - 1 - at)} CL"

  private def ago(c: Ctx, at: java.time.Instant): String =
    s"${formatShort(Duration.between(at, c.state.now).getSeconds.max(0L))} ago"

  /** The last call whose method starts with `prefix`, as the Calls row says it. */
  private def last(c: Ctx, cl: ClSample, name: String, prefix: String, not: String = "\u0000"): String =
    cl.calls.reverseIterator.find(k => k.method.startsWith(prefix) && !k.method.startsWith(not)) match
      case None if cl.calls.isEmpty => s"$name not called yet"
      case None                     => s"$name none of the last ${formatNumber(cl.calls.size.toLong)}"
      case Some(k) =>
        (Seq(name, ago(c, k.at)) ++ k.status.map(TuiText.external(_, c.g)) ++ Seq(RpcPanels.latency(k.duration)))
          .mkString(" ")

  /** The monitor's rows in the order they are drawn, each with its rank: on a screen too short for all, the rows of the
    * lowest ranks stay. Failed authentications outrank the blocks and the calls: a consensus client that cannot
    * authenticate has neither.
    */
  private def rows(c: Ctx, cl: ClSample, w: Int): Seq[(Int, AttributedString)] =
    val state = cl.link(c.state.now, c.state.thresholds.clSilentAfter)
    val (word, style) = state match
      case ClLink.Waiting      => ("waiting for its first forkchoiceUpdated", c.t.warn)
      case ClLink.Connected    => ("connected", c.t.healthy.bold())
      case ClLink.Disconnected => ("DISCONNECTED", c.t.error)
    val dot = s" ${c.g.dot.text} "
    val client = cl.client.map { k =>
      val named = if k.name.nonEmpty then k.name else k.code
      val said = (Seq(named) ++ Option(k.version).filter(_.nonEmpty) ++
        Option(k.commit).filter(_.nonEmpty).map(s => s"($s)")).mkString(" ")
      TuiText.external(said, c.g)
    }
    // The parts after the state, the least needed last; one that does not fit is left out whole.
    val said = Seq(link(cl.callsTotal, c.g)) ++ client.orElse(Some("the consensus client has not said what it is")) ++
      cl.capabilities.map(n => s"$n methods offered") ++ Seq(s"${formatNumber(cl.callsTotal)} calls")
    val head = new AttributedStringBuilder()
    // "Link", not "Engine": the Engine API's transport row is named that, two panels up.
    head.append(field("Link", text(word, style), c.t))
    val room = w - 1 - head.columnLength()
    val kept = said.indices.reverse
      .map(n => said.take(n + 1))
      .find(parts => new AttributedString(dot + parts.mkString(dot)).columnLength() <= room)
      .getOrElse(Nil)
    if kept.nonEmpty then head.append(dot + kept.mkString(dot), c.t.value)

    val missing = c.g.missing.text
    def behind(of: Option[Long]): String =
      (cl.headNumber, of) match
        case (Some(h), Some(n)) if h >= n => s" (${formatNumber(h - n)} behind)"
        case _                            => ""
    val blocks = Seq(
      s"head ${cl.headNumber.fold(cl.head.fold(missing)(ClPanel.shortHex(_, c.g)))(formatNumber)}",
      s"safe ${cl.safe.fold(missing)(formatNumber)}${behind(cl.safe)}",
      s"finalized ${cl.finalized.fold(missing)(formatNumber)}${behind(cl.finalized)}"
    )
    // The payloads first: on a narrow row it is the one that stays.
    val calls = Seq(
      last(c, cl, "newPayload", "engine_newPayload"),
      last(c, cl, "forkchoiceUpdated", "engine_forkchoiceUpdated"),
      last(c, cl, "getPayload", "engine_getPayload", not = "engine_getPayloadBodies")
    )
    val p = cl.payloads
    val counts = Seq(s"VALID ${formatNumber(p.valid)}") ++
      Option.when(p.syncing > 0)(s"SYNCING ${formatNumber(p.syncing)}") ++
      Seq(s"ACCEPTED ${formatNumber(p.accepted)}", s"INVALID ${formatNumber(p.invalid)}")
    val built = cl.builds.last.map(b =>
      s"last built block ${formatNumber(b.block)} with ${formatNumber(b.transactions.toLong)} transactions ${ago(c, b.at)}"
    )
    val payloads = Seq(s"${formatNumber(p.total)} received (${counts.mkString(", ")})") ++
      Seq(s"${formatNumber(cl.builds.built)} built") ++
      Option.when(cl.builds.failed > 0)(s"${formatNumber(cl.builds.failed)} could not be built") ++ built
    val auth = Option.when(cl.authFailures > 0) {
      val reasons =
        cl.authReasons.toSeq.sortBy((reason, n) => (-n, reason)).map((reason, n) => s"${formatNumber(n)} $reason")
      val total = s"${formatNumber(cl.authFailures)} request${if cl.authFailures == 1 then "" else "s"} refused"
      fit(
        concat(
          text(s" ${"Auth".padTo(FieldLabelCols, ' ')}", c.t.label),
          text(partsOf(c, total +: reasons, w - 1 - (1 + FieldLabelCols)), c.t.warn)
        ),
        w,
        c.g
      )
    }
    Seq(
      0 -> fit(head.toAttributedString, w, c.g),
      3 -> ObservatoryPanels.partsRow(c, "Blocks", blocks, w),
      2 -> ObservatoryPanels.partsRow(c, "Calls", calls, w)
    ) ++ auth.map(1 -> _) ++ Seq(4 -> ObservatoryPanels.partsRow(c, "Payload", payloads, w))

  /** As many of `parts` as fit `room` columns whole, joined. */
  private def partsOf(c: Ctx, parts: Seq[String], room: Int): String =
    val dot = s" ${c.g.dot.text} "
    parts.indices.reverse
      .map(n => parts.take(n + 1).mkString(dot))
      .find(shown => new AttributedString(shown).columnLength() <= room)
      .getOrElse("")

  /** The monitor, if the probe read the Engine API. */
  def monitor(c: Ctx): Option[Panel] =
    c.state.snapshot.flatMap(_.local.cl).map { cl =>
      val all = if cl.authFailures > 0 then 5 else 4
      Panel(
        3.min(all),
        all,
        3,
        (w, n) =>
          val ranked = rows(c, cl, w)
          val kept = ranked.map(_._1).sorted.take(n).toSet
          ranked.collect {
            case (rank, row) if kept(rank) => row
          }
        ,
        separated = true
      )
    }

  /** The rows above the log's first call: the title and the column names. */
  val LogHead: Int = 2

  /** The least columns of the log's cells, so that the columns a width shows do not change as the calls do: the time,
    * the method, `error -38003`, a latency, a block number.
    */
  private val LogCols: Vector[Int] = Vector(8, 0, 12, 8, 9)

  /** The last calls, newest first: when each was answered, what with, and how long it took. */
  def log(c: Ctx): Option[Panel] =
    c.state.snapshot.flatMap(_.local.cl).map { cl =>
      Panel(3, LogHead + 6, 5, (w, n) => logRows(c, cl, w, n), separated = true, fill = true)
    }

  private def logRows(c: Ctx, cl: ClSample, w: Int, n: Int): Seq[AttributedString] =
    val title = ObservatoryPanels.partsRow(
      c,
      "Log",
      if cl.calls.isEmpty then Seq("no Engine API call yet")
      else
        Seq(
          s"the last ${formatNumber(cl.calls.size.toLong)} of ${formatNumber(cl.callsTotal)} calls",
          "newest first",
          "times in UTC"
        )
      ,
      w
    )
    if cl.calls.isEmpty then Seq(title)
    else
      val missing = c.g.missing.text
      val names = Vector("answered", "method", "told", "took", "block")
      def cells(k: EngineCallLine): Vector[String] =
        Vector(
          Clock.format(k.at),
          TuiText.external(k.method, c.g),
          k.status.fold(missing)(TuiText.external(_, c.g)),
          RpcPanels.latency(k.duration),
          k.block.fold(missing)(formatNumber)
        )
      // Every call kept, not only those on screen: the columns shown depend on the screen's width, not on which
      // calls are in view.
      val every = cl.calls.reverseIterator.map(cells).toVector
      val cols = names.indices.map(i => (every.map(_(i).length) :+ names(i).length :+ LogCols(i)).max)
      // The block goes first on a narrow screen, then how long it took.
      val kept = Seq(5, 4, 3)
        .map(k => names.indices.take(k))
        .find(is => 3 + is.map(cols(_) + 2).sum - 2 <= w - 1)
        .getOrElse(names.indices.take(3))
      def line(v: Vector[String]): String =
        "   " + kept
          .map(i => if i >= 3 then " " * (cols(i) - v(i).length) + v(i) else v(i).padTo(cols(i), ' '))
          .mkString("  ")
      val rows = every.take((n - LogHead).max(0)).zip(cl.calls.reverseIterator.toVector).map { (v, k) =>
        // An answer that was not a success stands out: an error, or a payload that was not VALID.
        val bad =
          k.status.exists(s => s.startsWith("error") || s == "INVALID" || s == "failed" || s.startsWith("unreadable"))
        fit(text(line(v), if bad then c.t.warn else c.t.value), w, c.g)
      }
      Seq(title, fit(text(line(names), c.t.label), w, c.g)) ++ rows
