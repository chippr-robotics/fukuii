package com.chipprbots.ethereum.console.panels

import java.time.Duration
import java.util.Locale

import org.jline.utils.AttributedString

import com.chipprbots.ethereum.console.*
import com.chipprbots.ethereum.console.TuiText.formatNumber
import com.chipprbots.ethereum.console.TuiText.formatShort
import com.chipprbots.ethereum.console.panels.Rows.*

/** The RPC view (spec 029 FR-038 to FR-041): each transport and whether it listens, what it serves and to whom; the
  * Engine Synchronization Monitor on a node with a consensus client, or the mining monitor on one that mines; and every
  * method called, with its calls, its errors and its latencies.
  *
  * Every figure is the node's own count since it started. A transport whose server has not said whether it bound is
  * said to be enabled, not listening; a method never called is not listed.
  */
private[console] object RpcPanels:

  def panels(c: Ctx): Seq[Panel] =
    val rpc = c.state.snapshot.flatMap(_.local.rpc)
    val monitor = EnginePanels.monitor(c).orElse(MiningPanel.monitor(c))
    // The call log takes the rows nobody else wants on a node with a consensus client; else the methods do.
    val log = EnginePanels.log(c)
    val methodRows = rpc.fold(0)(_.methods.size)
    Seq(vitalsPanel(c, withSummary = true)) ++
      Seq(transports(c, rpc)) ++ monitor ++
      Seq(
        Panel(
          if methodRows == 0 then MethodsHead else MethodsHead + 1,
          if methodRows == 0 then MethodsHead
          else if log.isDefined then MethodsHead + methodRows.min(8)
          else MethodsHead + methodRows,
          4,
          (w, n) => methods(c, rpc, w, n),
          separated = true,
          fill = log.isEmpty
        )
      ) ++ log

  // ---- transports -------------------------------------------------------------------------------------------------

  private def shortName(kind: TransportKind): String = kind match
    case TransportKind.Http   => "HTTP"
    case TransportKind.Ws     => "WS"
    case TransportKind.Ipc    => "IPC"
    case TransportKind.Engine => "Engine"

  /** Whether the transport listens, as its server said. An enabled one whose server has said nothing is not claimed to
    * listen, nor to have failed.
    */
  private def state(t: TransportStatus): String =
    if !t.enabled then "off"
    // Stopped from this dashboard: said as that, not as a server that failed (spec 029 FR-055).
    else if t.stoppedHere then
      s"stopped here (rpc start ${if t.kind == TransportKind.Ws then "ws" else "http"} starts it)"
    else
      t.listening match
        case Some(true)  => "listening"
        case Some(false) => "NOT LISTENING: it could not start, or was closed"
        case None        => "enabled; its server has not said it is bound"

  /** How a caller is authenticated, in the one place that words it: with TLS, whether a client certificate is asked for
    * or required (spec 030 FR-061), and a plaintext transport kept by `allow-insecure-bind` says so.
    */
  def authentication(t: TransportStatus): String =
    val plaintextOptOut = if !t.tls && t.insecureBind then ", plaintext by allow-insecure-bind" else ""
    t.auth match
      case TransportAuth.None if t.tls =>
        t.clientAuth match
          case TransportClientAuth.Need => "client certificate required (mTLS)"
          case TransportClientAuth.Want => "client certificate asked, not required (TLS)"
          case TransportClientAuth.None => "no authentication (TLS)"
      case TransportAuth.None => s"no authentication$plaintextOptOut"
      case TransportAuth.Jwt  => if t.tls then "authenticated by JWT (TLS)" else s"authenticated by JWT$plaintextOptOut"
      case TransportAuth.SocketFile => "restricted to the socket file's owner"

  /** The longest address shown whole. A longer one (a socket's path) keeps both its ends. */
  val AddressCols: Int = 40

  private def address(c: Ctx, t: TransportStatus): String =
    val whole = TuiText.external(t.address, c.g)
    if whole.length <= AddressCols then whole
    else
      val mark = c.g.ellipsis.text
      val tail = (AddressCols - mark.length) / 2
      whole.take(AddressCols - mark.length - tail) + mark + whole.takeRight(tail)

  /** A transport's row. Its parts are drawn in one order and kept in another: on a row too narrow for all, the state
    * stays, then where it listens, how it authenticates and what it was asked, and the list of namespaces, the longest
    * part, goes first. A part is whole or absent.
    */
  private def transportRow(c: Ctx, t: TransportStatus, w: Int): AttributedString =
    val ranked: Seq[(Int, String)] =
      if !t.enabled then Seq(0 -> state(t))
      else
        // The Engine API's own calls are the monitor's to count: this row's are the calls it passed on to JSON-RPC.
        val calls =
          if t.kind == TransportKind.Engine then s"${formatNumber(t.calls)} eth, net and web3 calls passed on"
          else s"${formatNumber(t.calls)} call${if t.calls == 1 then "" else "s"}"
        Seq(
          0 -> state(t),
          1 -> address(c, t),
          5 -> (if t.apis.isEmpty then "no namespace" else t.apis.mkString(" ")),
          2 -> authentication(t),
          3 -> calls,
          4 -> s"${formatNumber(t.errors)} failed"
        )
    val dot = s" ${c.g.dot.text} "
    val room = w - 1 - (1 + FieldLabelCols)
    def width(kept: Set[Int]): Int =
      new AttributedString(ranked.collect { case (rank, part) if kept(rank) => part }.mkString(dot)).columnLength()
    // By rank, each part taken if the row still fits with it: a long part left out does not take the ones after it.
    val kept = ranked
      .map(_._1)
      .sorted
      // The failures (rank 4) are of the calls (rank 3): they are not said without them.
      .foldLeft(Set.empty[Int])((taken, rank) =>
        if (rank != 4 || taken(3)) && width(taken + rank) <= room then taken + rank else taken
      )
    val said = ranked.collect { case (rank, part) if kept(rank) => part }
    TuiText.fitToWidth(field(shortName(t.kind), text(said.mkString(dot), c.t.value), c.t), w)

  /** `5s`, `1.5 s`, `500 ms`: an interval as it was configured, not rounded to a second. */
  def interval(d: Duration): String =
    val ms = d.toMillis
    if ms < 1_000 then s"$ms ms"
    else if ms % 1_000 == 0 then formatShort(ms / 1_000)
    else String.format(Locale.ROOT, "%.1f s", ms / 1000.0)

  private def transportRows(c: Ctx, rpc: RpcSample, w: Int): Seq[(Int, AttributedString)] =
    // An exposed namespace is a warning the operator must not have to look for: it goes first (FR-039).
    val exposed = rpc.transports.filter(_.exposed.nonEmpty).map { t =>
      val lead = s" ${c.g.warn.text} "
      val dot = s" ${c.g.dot.text} "
      val room = w - 1 - new AttributedString(lead).columnLength()
      // Said in parts, the first of which is the warning itself: a part that does not fit is left out whole, and
      // the warning itself is said shorter before it is cut.
      val parts = Seq(
        s"${t.kind.label} serves ${t.exposed.mkString(", ")} to other hosts",
        s"bound to ${address(c, t)}",
        authentication(t)
      )
      val shorter = Seq(
        s"${t.kind.label} serves ${t.exposed.size} sensitive namespaces to other hosts",
        s"${shortName(t.kind)} is exposed"
      )
      val kept = parts.indices.reverse
        .map(n => parts.take(n + 1).mkString(dot))
        .concat(shorter)
        .find(said => new AttributedString(said).columnLength() <= room)
        .getOrElse(shorter.last)
      0 -> fit(text(lead + kept, c.t.warn.bold()), w, c.g)
    }
    val rows = rpc.transports.map(t => (if t.enabled then 1 else 3) -> transportRow(c, t, w))
    val limit = rpc.rateLimit match
      case Some(least) =>
        Seq(s"HTTP: one request each ${interval(least)} from an address", s"${formatNumber(rpc.rateLimited)} refused")
      case None => Seq("off")
    exposed ++ rows :+ (2 -> ObservatoryPanels.partsRow(c, "Limit", limit, w))

  private def transports(c: Ctx, rpc: Option[RpcSample]): Panel =
    rpc match
      case None =>
        Panel(
          1,
          1,
          2,
          (w, _) => Seq(ObservatoryPanels.partsRow(c, "RPC", Seq("not read on this node"), w)),
          separated = true
        )
      case Some(sample) =>
        val all = sample.transports.size + 1 + sample.transports.count(_.exposed.nonEmpty)
        val needed = sample.transports.count(_.enabled) + sample.transports.count(_.exposed.nonEmpty)
        Panel(
          needed.max(1),
          all,
          2,
          (w, n) =>
            // On a short screen the warnings and the enabled transports stay; the limit and the ones off go first.
            val ranked = transportRows(c, sample, w).zipWithIndex
            val kept = ranked.sortBy { case ((rank, _), i) => (rank, i) }.take(n).map(_._2).toSet
            ranked.collect { case ((_, row), i) if kept(i) => row }
          ,
          separated = true
        )

  // ---- methods ----------------------------------------------------------------------------------------------------

  /** The rows above the first method: the title and the column names. */
  val MethodsHead: Int = 2

  /** A latency to three figures: `0.31 ms`, `12.4 ms`, `212 ms`, `1.20 s`, `84 s`. The unit is chosen for the figure as
    * it rounds, so 9.996 ms is `10.0 ms`, not `10.00 ms`.
    */
  def latency(d: Duration): String =
    val ms = d.toNanos / 1e6
    if ms < 9.995 then String.format(Locale.ROOT, "%.2f ms", ms)
    else if ms < 99.95 then String.format(Locale.ROOT, "%.1f ms", ms)
    else if ms < 999.5 then String.format(Locale.ROOT, "%.0f ms", ms)
    else if ms < 9_995 then String.format(Locale.ROOT, "%.2f s", ms / 1_000)
    else String.format(Locale.ROOT, "%.0f s", ms / 1_000)

  /** A percentile as the table says it: `<0.10 ms` where the histogram only knows it is under its range. */
  def quantile(q: Quantile): String = (if q.below then "<" else "") + latency(q.value)

  /** The least columns of each figure, so that the columns a width shows do not change as the figures do: a count to
    * 9,999,999, the latencies, `59m 59s ago`.
    */
  private val MinCols: Vector[Int] = Vector(0, 9, 7, 8, 8, 8, 11)

  /** The names calls are counted under when not under a method's own. */
  private def isMethod(m: MethodStats): Boolean = !m.method.startsWith("(")

  private def methods(c: Ctx, rpc: Option[RpcSample], w: Int, n: Int): Seq[AttributedString] =
    val called = rpc.fold(Vector.empty[MethodStats])(_.methods)
    val missing = c.g.missing.text
    if called.isEmpty then
      Seq(
        ObservatoryPanels.partsRow(c, "Methods", Seq("none called since the node started"), w),
        fit(text("   a failed call is counted and timed like any other", c.t.muted), w, c.g)
      )
    else
      val calls = called.map(_.calls).sum
      val errors = called.map(_.errors).sum
      val real = called.filter(isMethod)
      val slowest = real.filter(_.p99.isDefined).maxByOption(_.p99.get.value)
      val title = ObservatoryPanels.partsRow(
        c,
        "Methods",
        Seq(
          s"${formatNumber(real.size.toLong)} called",
          s"${formatNumber(calls)} calls",
          s"${formatNumber(errors)} failed"
        ) ++ slowest.map(m => s"slowest at p99 ${TuiText.external(m.method, c.g)} ${quantile(m.p99.get)}") ++
          Seq("since the node started"),
        w
      )
      val names = Vector("method", "calls", "failed", "p50", "p95", "p99", "last")
      def cells(m: MethodStats): Vector[String] =
        Vector(
          // A registered method's name, or one of the two the node counts the rest under: drawn as any text that
          // came from outside is.
          TuiText.external(m.method, c.g),
          formatNumber(m.calls),
          formatNumber(m.errors),
          m.p50.fold(missing)(quantile),
          m.p95.fold(missing)(quantile),
          m.p99.fold(missing)(quantile),
          m.lastAt.fold(missing)(at => s"${formatShort(Duration.between(at, c.state.now).getSeconds.max(0L))} ago")
        )
      // Every method's cells, not only those on screen: a column is as wide as it must be for all of them, and no
      // narrower than its least, so the columns shown depend on the screen's width and not on which rows are in view.
      val every = called.map(cells)
      // The name's column is never wider than leaves the calls, the failures and a percentile beside it: a longer
      // name is cut, a figure never is. No method this node registers is that long.
      val nameCap = (w - 1 - 3 - (2 + MinCols(1)) - (2 + MinCols(2)) - (2 + MinCols(5))).max(8)
      val cols = names.indices.map { i =>
        val widest = (every.map(_(i).length) :+ names(i).length :+ MinCols(i)).max
        if i == 0 then widest.min(nameCap) else widest
      }
      // The percentiles in the middle go first on a screen too narrow for all: p95, then p50, then the last call.
      val order = Seq(0, 1, 2, 5, 6, 3, 4)
      val shown = order.indices.reverse
        .map(k => order.take(k + 1).sorted)
        .find(kept => 3 + kept.map(cols(_) + 2).sum - 2 <= w - 1)
        .getOrElse(Seq(0, 1))
      def name(v: String): String =
        if v.length <= cols(0) then v.padTo(cols(0), ' ')
        else v.take(cols(0) - c.g.ellipsis.text.length) + c.g.ellipsis.text
      def line(v: Vector[String]): String =
        "   " + shown.map(i => if i == 0 then name(v(i)) else " " * (cols(i) - v(i).length) + v(i)).mkString("  ")
      val rows = every.take((n - MethodsHead).max(0)).zip(called).map { (v, m) =>
        fit(text(line(v), if m.errors > 0 then c.t.warn else c.t.value), w, c.g)
      }
      Seq(title, fit(text(line(names), c.t.label), w, c.g)) ++ rows
