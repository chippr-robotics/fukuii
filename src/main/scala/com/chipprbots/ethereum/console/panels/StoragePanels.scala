package com.chipprbots.ethereum.console.panels

import java.time.Duration
import java.util.Locale

import org.jline.utils.AttributedString

import com.chipprbots.ethereum.console.*
import com.chipprbots.ethereum.console.TuiText.formatBytes
import com.chipprbots.ethereum.console.TuiText.formatNumber
import com.chipprbots.ethereum.console.TuiText.formatShort
import com.chipprbots.ethereum.console.panels.Rows.*

/** The Storage view (spec 029 FR-043): the database's size and growth, the disk, compaction and whether writes are
  * held, the caches, the stores by size, the node's own marks of what its state is and owes, and the background audit.
  *
  * Every figure is RocksDB's own property or the node's own marker, read without waiting: the read gives way to any
  * write, so under a long write the figures are of a while ago, and the first row says when they were read. Sizes are
  * the files on disk; key counts and the live size are the database's estimates and are said to be. Hit rates are drawn
  * only where the database counts them (`db.rocksdb.enable-statistics`); what this client does not have (a freezer for
  * old blocks, state snapshots) is said, not left out.
  */
private[console] object StoragePanels:

  private def storage(c: Ctx): Option[StorageSample] = c.state.snapshot.flatMap(_.local.storage)

  private def bytes(value: Option[Long], g: TuiGlyphs): String = value.fold(g.missing.text)(formatBytes)

  /** A store's size: as [[formatBytes]] from a mebibyte, and in K or bytes under it, so that a store of a few kilobytes
    * is not drawn as `0M` beside the count of the stores that are empty.
    */
  private def storeSize(value: Option[Long], g: TuiGlyphs): String = value.fold(g.missing.text) { size =>
    if size >= 1024L * 1024 then formatBytes(size) else if size >= 1024 then s"${size / 1024}K" else s"${size}B"
  }

  /** `+1.2G/h`, `-300M/h`: a database that compaction shrank grows at less than nothing. */
  private def growth(perHour: Double): String =
    val sign = if perHour < 0 then "-" else "+"
    s"$sign${formatBytes(perHour.abs.round)}/h"

  private def summary(c: Ctx, s: StorageSample, w: Int): Seq[AttributedString] =
    val g = c.g
    val now = c.state.now
    val age = Duration.between(s.at, now).getSeconds.max(0L)
    val span = for
      (first, _) <- s.sizeHistory.headOption
      (last, _) <- s.sizeHistory.lastOption
    yield Duration.between(first, last).getSeconds.max(0L)
    val database = ObservatoryPanels.partsRow(
      c,
      "Size",
      Seq(s"${bytes(s.sstBytes, g)} on disk") ++
        s.liveDataBytes.map(live => s"${formatBytes(live)} live (estimate)") ++
        s.growthPerHour.map(perHour => s"${growth(perHour)} over ${formatShort(span.getOrElse(0L))}") ++
        // The database is read every five seconds: the age is said from two, so a reading is not "now" for five.
        Seq(if age <= 1 then "read now" else s"read ${formatShort(age)} ago"),
      w
    )
    val free = c.state.snapshot.flatMap(_.local.diskFree).filter(_ >= 0)
    val diskTotal = c.state.snapshot.flatMap(_.local.diskTotal)
    val disk = ObservatoryPanels.partsRow(
      c,
      "Disk",
      Seq(s"${bytes(free, g)} free" + diskTotal.fold("")(total => s" of ${formatBytes(total)}")) ++
        (for
          total <- diskTotal
          size <- s.sstBytes
          if total > 0
        yield String.format(Locale.ROOT, "the database is %.0f%% of the disk", size * 100.0 / total)) ++
        Seq(s"${c.state.nodeSettings.pruningMode} pruning").filter(_ => c.state.nodeSettings.pruningMode.nonEmpty),
      w
    )
    val compaction = ObservatoryPanels.partsRow(
      c,
      "Compact",
      Seq(
        s.compactionPending.fold(s"pending: ${g.missing.text}")(n =>
          if n == 0 then "none pending" else s"$n store${if n == 1 then "" else "s"} pending"
        )
      ) ++
        s.runningCompactions.map(n => if n == 0 then "none running" else s"$n running") ++
        s.pendingCompactionBytes.filter(_ > 0).map(b => s"${formatBytes(b)} to rewrite"),
      w
    )
    val writes =
      val (said, look) = s.writeStopped match
        case Some(true) => ("REFUSED until flush and compaction catch up", c.t.warn.bold())
        case Some(false) =>
          s.delayedWriteRate.filter(_ > 0) match
            case Some(r) =>
              (s"accepted, held to ${formatBytes(r)}/s while flush and compaction catch up", c.t.warn.bold())
            case None => ("accepted, not held", c.t.value)
        case None => ("the database did not say", c.t.muted)
      fit(field("Writes", text(said, look), c.t), w, g)
    val hits = s.cache match
      case CacheReading.Off => Seq("hit rates not counted (db.rocksdb.enable-statistics is off)")
      case CacheReading.On(_, _, rate) =>
        Seq(rate.fold("no lookup since the last reading")(r => String.format(Locale.ROOT, "%.1f%% hits", r * 100)))
    val cache = ObservatoryPanels.partsRow(
      c,
      "Cache",
      // The memtables reserve their memory in the block cache: they are part of its use, and said to be.
      Seq(
        s"block cache ${formatBytes(s.blockCacheUsed)} of ${formatBytes(s.blockCacheCapacity)}" +
          s.memTables.fold("")(m => s" (memtables ${formatBytes(m)} of it)")
      ) ++ hits ++ s.tableReaders.map(r => s"table readers ${formatBytes(r)}"),
      w
    )
    // By rank: where the panel is short, whether writes are taken outlasts the disk and the cache.
    Seq(database, writes, compaction, disk, cache)

  /** The size over the readings kept, drawn from the lowest of them to the highest: against nothing, a database of
    * hundreds of gigabytes that grew by a few is a full flat row. The caption gives both ends, so the chart's height is
    * read against them and not as a size.
    */
  private def sizeChart(c: Ctx, s: StorageSample, w: Int): AttributedString =
    val chart = (w / 4).max(8).min(40)
    val sizes = s.sizeHistory.map(_._2)
    val lowest = sizes.minOption
    val highest = sizes.maxOption
    val drawn = ObservatoryPanels.spark(sizes.map(size => Some(size - lowest.getOrElse(0L))), chart, c.g)
    val parts = Seq(s"now ${bytes(s.sstBytes, c.g)}") ++
      (for
        low <- lowest
        high <- highest
      yield s"drawn from the lowest kept, ${formatBytes(low)}, to the highest, ${formatBytes(high)}")
    val dot = s" ${c.g.dot.text} "
    val room = w - 1 - (1 + FieldLabelCols) - chart - 2
    val kept = wholeParts(parts, dot, room) match
      case Nil   => parts.take(1)
      case whole => whole
    TuiText.fitToWidth(
      concat(
        text(" " + "Growth".padTo(FieldLabelCols, ' '), c.t.label),
        text(drawn + " " * (chart - drawn.length).max(0), c.t.healthy),
        text("  " + kept.mkString(dot), c.t.value)
      ),
      w
    )

  /** The stores, the largest first; a store with nothing on disk and no key is not listed, and the title says how many
    * are not. The columns are as wide as their widest figure whatever is in view.
    */
  private def stores(c: Ctx, s: StorageSample, w: Int, n: Int): Seq[AttributedString] =
    val g = c.g
    val all = s.stores.sortBy(store => (-store.sstBytes.getOrElse(-1L), store.name))
    val (used, unused) = all.partition(store => store.sstBytes.exists(_ > 0) || store.keys.exists(_ > 0))
    val nameCols = all.map(_.name.length).maxOption.getOrElse(0).max("store".length)
    val sizeCols = 8
    val keysCols = 16
    val shareCols = "of the database".length
    def right(cell: String, cols: Int): String = " " * (cols - cell.length).max(0) + cell
    def line(name: String, size: String, keys: String, share: String): String =
      s" ${name.padTo(nameCols, ' ')}  ${right(size, sizeCols)}  ${right(keys, keysCols)}  ${right(share, shareCols)}"
    val total = s.sstBytes.filter(_ > 0)
    val title = fit(
      field(
        "Stores",
        text(
          s"${used.size} of ${all.size} hold data" +
            (if unused.nonEmpty then s" ${g.dot.text} ${unused.size} empty not listed" else ""),
          c.t.value
        ),
        c.t
      ),
      w,
      g
    )
    val header = fit(text(line("store", "on disk", "keys (estimate)", "of the database"), c.t.label), w, g)
    val rows = used.map { store =>
      val share = for
        size <- store.sstBytes
        whole <- total
      yield String.format(Locale.ROOT, "%.1f%%", size * 100.0 / whole)
      fit(
        text(
          line(
            store.name,
            storeSize(store.sstBytes, g),
            store.keys.fold(g.missing.text)(formatNumber),
            share.getOrElse(g.missing.text)
          ),
          c.t.value
        ),
        w,
        g
      )
    }
    // The stores that do not fit are counted, never silently cut.
    val room = (n - 2).max(0)
    if rows.size <= room then title +: header +: rows
    else if room == 0 then Seq(title)
    else
      (title +: header +: rows.take(room - 1)) :+
        fit(text(s" … ${rows.size - (room - 1)} smaller stores not shown at this height", c.t.muted), w, g)

  /** The node's marks and the audit: a row a mark, then the audit, then what this client does not have. */
  private def integrity(c: Ctx, s: StorageSample, w: Int, n: Int): Seq[AttributedString] =
    val g = c.g
    val marks = s.markers.map((name, value) =>
      fit(concat(text(s" ${name.padTo(22, ' ')}", c.t.label), text(TuiText.external(value, g), c.t.value)), w, g)
    )
    val audit = s.audit match
      case AuditReading.NotRunning =>
        ObservatoryPanels.partsRow(c, "Audit", Seq("not running now", "it runs after a sync hands over"), w)
      case AuditReading.NoAnswer =>
        fit(field("Audit", text("running; it did not answer in time", c.t.muted), c.t), w, g)
      case AuditReading.Running(a) =>
        ObservatoryPanels.partsRow(
          c,
          "Audit",
          Seq(
            s"pass ${formatNumber(a.pass)}" +
              (if a.paused then ", paused" else if a.waitingForNextPass then ", waiting for the next" else ""),
            s"${formatNumber(a.nodes)} nodes",
            s"${formatNumber(a.holes)} holes",
            s"${formatNumber(a.repaired)} repaired",
            s"${formatNumber(a.unresolved)} unresolved",
            s"${formatNumber(a.accounts)} accounts",
            s"for ${formatShort(a.elapsed.getSeconds.max(0L))}",
            "it counts what it has walked: the whole is not known until it ends"
          ),
          w
        )
    val absent = fit(
      text(" Not kept by this client: a freezer for old blocks (no history is expired).", c.t.muted),
      w,
      g
    )
    val title = fit(text(" Integrity", c.t.healthy.bold()), w, g)
    // Where the panel is short: first the note of what is not kept goes, then the title; then the audit is kept with
    // as many marks as there are rows for, and the rest are counted. A mark is never left out unsaid, and the count
    // never takes the row of the one mark it would count.
    if n >= marks.size + 3 then Seq(title, audit) ++ marks :+ absent
    else if n >= marks.size + 2 then Seq(title, audit) ++ marks
    else if n >= marks.size + 1 then audit +: marks
    else if n <= 1 then Seq(audit)
    else
      val shown = marks.take(n - 2)
      val hidden = marks.size - shown.size
      (audit +: shown) :+ fit(
        text(s" … $hidden ${if shown.isEmpty then "marks" else "more marks"} not shown at this height", c.t.muted),
        w,
        g
      )

  def panels(c: Ctx): Seq[Panel] =
    storage(c) match
      case None =>
        // What the dashboard reads without the database: the disk and the settings.
        val n = c.state.nodeSettings
        def rows(w: Int): Seq[AttributedString] = Seq(
          fit(field("Size", text("the database has not been read yet", c.t.muted), c.t), w, c.g),
          fit(field("Disk", diskValue(c), c.t), w, c.g),
          fit(field("Datadir", text(TuiText.external(n.dataDir, c.g), c.t.value), c.t), w, c.g),
          fit(field("Pruning", text(n.pruningMode, c.t.value), c.t), w, c.g)
        )
        Seq(vitalsPanel(c, withSummary = true), Panel(1, 4, 2, (w, _) => rows(w), separated = true), fillPanel)
      case Some(s) =>
        val listed = s.stores.count(store => store.sstBytes.exists(_ > 0) || store.keys.exists(_ > 0))
        val marks = s.markers.size + 3
        Seq(
          vitalsPanel(c, withSummary = true),
          Panel(3, 5, 2, (w, n) => summary(c, s, w).take(n), separated = true),
          Panel(0, 1, 5, (w, _) => Seq(sizeChart(c, s, w))),
          Panel(4.min(listed + 2), listed + 2, 3, (w, n) => stores(c, s, w, n), separated = true),
          Panel(3.min(marks), marks, 4, (w, n) => integrity(c, s, w, n), separated = true),
          fillPanel
        )
