package com.chipprbots.ethereum.console

import com.chipprbots.ethereum.console.ops.SupportBundle

/** What the dashboard itself puts in a support bundle (spec 029 FR-054): its log lines, its alerts and health checks,
  * the database's figures, and every view as text. Each is read from the state at the moment the bundle is made.
  */
object BundleInputs:

  /** The size each view is written at: the widest layout, so nothing a view can show is left out for width. */
  val Cols: Int = 160
  val Rows: Int = 48

  /** A view as rows of text: ASCII glyphs, no colour, no motion, no overlay, trailing blanks dropped. */
  def view(renderer: TuiRenderer, state: TuiState, view: TuiView): Seq[String] =
    val still = state
      .copy(glyphs = GlyphSet.Ascii, motion = Motion.Reduced, overlay = Overlay.None, splashUntilTick = None)
      .withView(view)
    renderer.render(still, Cols, Rows, TuiTheme.Mono).map(_.toString.replaceAll("\\s+$", ""))

  /** The alert row's conditions, then each health check with its state and since when. */
  def alerts(state: TuiState): Seq[String] =
    val shown = Alerts.of(state)
    val rows =
      if shown.isEmpty then Seq("no alert")
      else shown.map(a => s"${if a.severity == Severity.Error then "ERROR" else "WARN"} ${a.text}")
    rows ++ Seq("", "health checks:") ++ state.healthChecks.map { c =>
      val since = state.healthSince.get(c.key).fold("")((_, at) => s", since $at")
      s"${c.key}: ${c.state.label}, value ${c.value}, threshold ${c.threshold}$since"
    }

  /** The database's figures as the Storage view reads them; a figure the database did not give is said as unknown. */
  def database(state: TuiState): Seq[String] =
    def known[A](value: Option[A]): String = value.fold("unknown")(_.toString)
    state.snapshot.flatMap(_.local.storage) match
      case None => Seq("the database has not been read yet")
      case Some(s) =>
        Seq(
          s"read at ${s.at}",
          s"size on disk (bytes): ${known(s.sstBytes)}",
          s"live data, estimated (bytes): ${known(s.liveDataBytes)}",
          s"compaction pending: ${known(s.compactionPending)}",
          s"compaction pending (bytes): ${known(s.pendingCompactionBytes)}",
          s"compactions running: ${known(s.runningCompactions)}",
          s"writes stopped: ${known(s.writeStopped)}",
          s"delayed write rate (bytes/s): ${known(s.delayedWriteRate)}",
          s"block cache (bytes): ${s.blockCacheUsed} of ${s.blockCacheCapacity}",
          s"memtables (bytes): ${known(s.memTables)}",
          s"table readers (bytes): ${known(s.tableReaders)}",
          s"block cache hits: ${s.cache}",
          s"audit: ${s.audit}"
        ) ++ s.stores.map(store =>
          s"store ${store.name}: size ${known(store.sstBytes)} bytes, keys ${known(store.keys)}"
        ) ++
          s.markers.map((name, value) => s"mark $name: $value")

  /** @param build
    *   the build's name, version and commit
    * @param config
    *   the effective configuration's rows, secrets already hidden
    */
  def of(
      state: () => TuiState,
      build: Seq[String],
      config: () => Seq[String],
      renderer: TuiRenderer = TuiRenderer(TuiConfig.default)
  ): SupportBundle.Inputs =
    SupportBundle.Inputs(
      build = () => build,
      config = config,
      logs = () => state().logs.map(l => s"${l.time} ${l.level} ${l.logger} ${l.text}"),
      alerts = () => alerts(state()),
      views = () =>
        val now = state()
        TuiView.values.toSeq.map(v => v.title -> view(renderer, now, v))
      ,
      database = () => database(state())
    )
