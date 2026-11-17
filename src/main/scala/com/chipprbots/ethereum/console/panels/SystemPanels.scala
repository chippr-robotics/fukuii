package com.chipprbots.ethereum.console.panels

import java.util.Locale

import org.jline.utils.AttributedString

import com.chipprbots.ethereum.console.*
import com.chipprbots.ethereum.console.TuiText.formatBytes
import com.chipprbots.ethereum.console.TuiText.formatNumber
import com.chipprbots.ethereum.console.TuiText.formatShort
import com.chipprbots.ethereum.console.panels.Rows.*

/** The System view (spec 029 FR-044, FR-045): the health bars, then the process and the machine, a meter a row: the
  * readings kept (ten minutes of them, once the dashboard has run that long) as a chart, the figure now, the highest of
  * them, and what else the JVM says of it. A share of the time and the heap are charted against their limit, so a quiet
  * node's chart is low; a figure with no limit of its own is charted against the highest reading kept.
  *
  * Every figure is the JVM's own or the node's own count. A figure the JVM does not give (a CPU load on a platform with
  * no such bean) is said to be not given, and its chart is gaps: it is never drawn as nothing. The traffic is what the
  * node's RLPx connections carried, counted in this process as it was handed to and read from their sockets;
  * discovery's UDP, the RPC servers and the consensus client's link are not in it, and the rows say so.
  */
private[console] object SystemPanels:

  /** The fewest glyphs a chart is drawn with, and the most. */
  private val MinChartCols: Int = 8
  private val MaxChartCols: Int = 40

  private def percent(share: Double): String = String.format(Locale.ROOT, "%.0f%%", share * 100)

  /** Bytes a second to three figures: `812 B/s`, `88.4K/s`, `310K/s`, `1.2M/s`. */
  private def rate(bytes: Double): String =
    val units = Seq("K", "M", "G", "T")
    if bytes < 1024 then s"${bytes.max(0).round} B/s"
    else
      val (value, unit) = units.foldLeft((bytes, "")) { case ((v, u), next) =>
        if v >= 1024 || u.isEmpty then (v / 1024, next) else (v, u)
      }
      String.format(Locale.ROOT, if value < 100 then "%.1f%s/s" else "%.0f%s/s", value, unit)

  private def perSecond(n: Double): String =
    if n > 0 && n < 10 then String.format(Locale.ROOT, "%.1f/s", n) else s"${formatNumber(n.round)}/s"

  /** One meter: its label, its chart at `chart` columns (the same for every meter, so the captions line up), then as
    * many of `parts`, in order, as fit whole. The first is the figure now and is always said.
    */
  private def meter(
      c: Ctx,
      label: String,
      values: Seq[Option[Long]],
      parts: Seq[String],
      chart: Int,
      w: Int,
      full: Option[Long] = None
  ): AttributedString =
    val dot = s" ${c.g.dot.text} "
    val room = w - 1 - (1 + FieldLabelCols) - chart - 2
    val kept = wholeParts(parts, dot, room) match
      case Nil   => parts.take(1)
      case whole => whole
    val drawn = ObservatoryPanels.spark(values, chart, c.g, full)
    TuiText.fitToWidth(
      concat(
        text(" " + label.padTo(FieldLabelCols, ' '), c.t.label),
        text(drawn + " " * (chart - drawn.length).max(0), c.t.healthy),
        text("  " + kept.mkString(dot), c.t.value)
      ),
      w
    )

  /** A share's full scale, in the units [[meters]] charts shares in. */
  private val Whole: Long = 10_000L

  /** How many meters there are. */
  private val Meters: Int = 11

  /** The meters, the most telling first, each drawn only if the panel has a row for it. Where it has not a row for
    * each, its last row counts the ones not shown: a meter is never left out unsaid.
    */
  private def meters(c: Ctx, w: Int, n: Int): Seq[AttributedString] =
    val local = c.state.snapshot.fold(LocalSample())(_.local)
    val missing = c.g.missing.text
    local.system.fold(Seq(fit(field("System", text("the process has not been read yet", c.t.muted), c.t), w, c.g))) {
      s =>
        val history = s.history
        val span = for
          first <- history.headOption
          last <- history.lastOption
        yield java.time.Duration.between(first.at, last.at).getSeconds.max(0L)
        val over = span.filter(_ > 0).fold("")(t => s" in ${formatShort(t)}")
        val chart = (w / 4).max(MinChartCols).min(MaxChartCols)
        def highest[A](of: SystemPoint => Option[A], say: A => String)(using Ordering[A]): Option[String] =
          history.flatMap(of).maxOption.map(peak => s"highest ${say(peak)}$over")
        def shares(of: SystemPoint => Option[Double]): Seq[Option[Long]] =
          history.map(of(_).map(v => (v * 10_000).round))
        def longs(of: SystemPoint => Option[Double]): Seq[Option[Long]] = history.map(of(_).map(_.round))
        val traffic = s.traffic
        val jvm = local.jvm
        def cpu = meter(
          c,
          "CPU",
          shares(_.processCpu),
          Seq(s"now ${s.processCpu.fold("not given by this JVM")(percent)}") ++
            highest(_.processCpu, percent) ++
            Option.when(s.processors > 0)(s"of ${s.processors} processors"),
          chart,
          w,
          Some(Whole)
        )
        def heap = meter(
          c,
          "Heap",
          history.map(_.heapUsed),
          Seq(jvm.fold(s"now $missing")(j => s"now ${formatBytes(j.heapUsed)} of ${formatBytes(j.heapMax)}")) ++
            highest(_.heapUsed, formatBytes),
          chart,
          w,
          jvm.map(_.heapMax).filter(_ > 0)
        )
        def netIn = meter(
          c,
          "Net in",
          longs(_.bytesInPerSecond),
          Seq(s"now ${traffic.flatMap(_.bytesInPerSecond).fold(missing)(rate)}") ++
            highest(_.bytesInPerSecond, rate) ++
            traffic.map(t => s"${formatBytes(t.bytesIn)} since the start") ++ Seq("RLPx only: no discovery, no RPC"),
          chart,
          w
        )
        def netOut = meter(
          c,
          "Net out",
          longs(_.bytesOutPerSecond),
          Seq(s"now ${traffic.flatMap(_.bytesOutPerSecond).fold(missing)(rate)}") ++
            highest(_.bytesOutPerSecond, rate) ++
            traffic.map(t => s"${formatBytes(t.bytesOut)} since the start") ++ Seq("RLPx only: no discovery, no RPC"),
          chart,
          w
        )
        def gc = meter(
          c,
          "GC",
          shares(_.gcShare),
          Seq(s"now ${history.lastOption.flatMap(_.gcShare).fold(missing)(percent)} of the time") ++
            highest(_.gcShare, percent) ++
            s.gcCount.map(n => s"${formatNumber(n)} pause${if n == 1 then "" else "s"}") ++
            s.lastGcPause.map(d => s"last ${formatNumber(d.toMillis)} ms"),
          chart,
          w,
          Some(Whole)
        )
        def threads = meter(
          c,
          "Threads",
          history.map(_.threads.map(_.toLong)),
          Seq(s"now ${s.threads.fold(missing)(n => formatNumber(n.toLong))}") ++
            s.peakThreads.map(n => s"peak ${formatNumber(n.toLong)} since the start"),
          chart,
          w
        )
        def machine = meter(
          c,
          "Machine",
          shares(_.systemCpu),
          Seq(s"now ${s.systemCpu.fold("not given by this JVM")(percent)}") ++
            highest(_.systemCpu, percent) ++
            s.loadAverage.map(l => String.format(Locale.ROOT, "load %.2f", l)),
          chart,
          w,
          Some(Whole)
        )
        def nonHeap = meter(
          c,
          "NonHeap",
          history.map(_.nonHeap),
          Seq(s"now ${s.nonHeap.fold(missing)(formatBytes)}") ++ highest(_.nonHeap, formatBytes) ++
            Seq("the JVM's own; RocksDB's memory is on Storage"),
          chart,
          w
        )
        def msgIn = meter(
          c,
          "Msg in",
          longs(_.messagesInPerSecond),
          Seq(s"now ${traffic.flatMap(_.messagesInPerSecond).fold(missing)(perSecond)}") ++
            highest(_.messagesInPerSecond, perSecond) ++
            traffic.map(t => s"${formatNumber(t.messagesIn)} since the start"),
          chart,
          w
        )
        def msgOut = meter(
          c,
          "Msg out",
          longs(_.messagesOutPerSecond),
          Seq(s"now ${traffic.flatMap(_.messagesOutPerSecond).fold(missing)(perSecond)}") ++
            highest(_.messagesOutPerSecond, perSecond) ++
            traffic.map(t => s"${formatNumber(t.messagesOut)} since the start"),
          chart,
          w
        )
        def uptime = ObservatoryPanels.partsRow(
          c,
          "Process",
          Seq(s"up ${s.uptime.fold(missing)(d => formatShort(d.getSeconds))}") ++
            span.filter(_ > 0).map(t => s"charts span ${formatShort(t)}") ++
            Seq("a point every 5s", "CPU, heap and GC against their limit, the rest against their highest"),
          w
        )
        val all: Seq[() => AttributedString] = Seq(
          () => uptime,
          () => cpu,
          () => heap,
          () => netIn,
          () => netOut,
          () => gc,
          () => threads,
          () => machine,
          () => nonHeap,
          () => msgIn,
          () => msgOut
        )
        if n >= all.size then all.map(_())
        else
          val shown = (n - 1).max(0)
          all.take(shown).map(_()) ++ Option.when(n >= 1)(
            fit(text(s" … ${all.size - shown} more meters not shown at this height", c.t.muted), w, c.g)
          )
    }

  def panels(c: Ctx): Seq[Panel] =
    val rows = if c.state.snapshot.flatMap(_.local.system).isDefined then Meters else 1
    Seq(
      vitalsPanel(c, withSummary = true),
      HealthPanel.list(c),
      Panel(rows.min(4), rows, 3, (w, n) => meters(c, w, n).take(n), separated = true),
      fillPanel
    )
