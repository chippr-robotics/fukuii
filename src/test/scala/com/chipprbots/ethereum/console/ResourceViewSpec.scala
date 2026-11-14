package com.chipprbots.ethereum.console

import org.scalatest.BeforeAndAfterAll
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

/** The Storage and System views, the health bars and the screen a bar opens (spec 029 FR-043 to FR-045). Golden frames
  * at three sizes in both glyph sets; [[TuiGoldenFiles]] says how to regenerate them. The views on a node whose
  * database and process were not read are `storage-*-synced-*` and `system-*-synced-*` in [[TuiViewGoldenSpec]].
  */
class ResourceViewSpec extends AnyFlatSpec with Matchers with OptionValues with BeforeAndAfterAll with TuiGoldenFiles:

  protected val goldenTerminal = new TuiTestTerminal()
  override def afterAll(): Unit = goldenTerminal.close()

  private val renderer = TuiRenderer(TuiConfig.default)
  private val sizes: Seq[(Int, Int)] = Seq((80, 24), (120, 40), (160, 48))
  private val ctx = UpdateCtx(page = 10, configuredMotion = Motion.Reduced)

  private def still(s: TuiState): TuiState = s.copy(motion = Motion.Reduced)
  private def in(view: TuiView)(s: TuiState): TuiState = still(s.withView(view))
  private def keys(s: TuiState, pressed: Key*): TuiState = pressed.foldLeft(s)((state, k) => Update(state, k, ctx)._1)

  /** The System view with the bar of `subsystem` opened by the keys an operator presses. */
  private def opened(s: TuiState, subsystem: Subsystem): TuiState =
    val at = s.healthBars.indexWhere(_.subsystem == subsystem)
    at should be >= 0
    keys(in(TuiView.System)(s), (Seq.fill(at + 1)(Key.Down) :+ Key.Enter)*)

  /** The view's rows as text, without the sidebar's columns. */
  private def rows(s: TuiState, w: Int = 160, h: Int = 48): Seq[String] =
    TuiFixtures.panelRows(visibleFrame(renderer.render(s, w, h)).split("\n", -1).toSeq, w, h)

  private def row(s: TuiState, label: String, w: Int = 160, h: Int = 48): String =
    rows(s, w, h).find(_.startsWith(s" $label ")).value

  private val goldens: Seq[(String, GlyphSet => TuiState)] = Seq(
    // Statistics off, no audit, nothing pending.
    "storage-%s-quiet" -> (g => in(TuiView.Storage)(TuiFixtures.resourcesEtc(g))),
    // Statistics on, compaction behind, writes held, the audit running.
    "storage-%s-busy" -> (g => in(TuiView.Storage)(TuiFixtures.resourcesSepolia(g))),
    "system-%s-well" -> (g => in(TuiView.System)(TuiFixtures.resourcesEtc(g))),
    "system-%s-pressure" -> (g => keys(in(TuiView.System)(TuiFixtures.pressureEtc(g)), Key.Down, Key.Down)),
    "health-%s-chain" -> (g => opened(TuiFixtures.resourcesSepolia(g), Subsystem.Chain)),
    "health-%s-memory" -> (g => opened(TuiFixtures.pressureEtc(g), Subsystem.Memory)),
    "overview-%s-health" -> (g => still(TuiFixtures.resourcesSepolia(g))),
    "overview-%s-pressure" -> (g => still(TuiFixtures.pressureEtc(g)))
  )

  for (name, fixture) <- goldens; (w, h) <- sizes; set <- GlyphSet.values do
    val file = s"${name.format(Tier.of(w, h).toString.toLowerCase)}-${set.toString.toLowerCase}.txt"

    "The resource views" should s"match $file" taggedAs (UnitTest) in {
      val lines = renderer.render(fixture(set), w, h)
      lines should have size h.toLong
      all(lines.map(_.columnLength())) shouldBe w
      matchGolden(file, visibleFrame(lines))
    }

  it should "leave the last column blank on every row but the rules, at every size" taggedAs (UnitTest) in {
    for (w, h) <- sizes; (_, fixture) <- goldens; set <- GlyphSet.values do
      val text = renderer.render(fixture(set), w, h).map(l => visibleFrame(Seq(l)))
      all(text.filterNot(l => l.endsWith("──") || l.endsWith("--")).map(_.last)) shouldBe ' '
  }

  // ---- the Storage view ---------------------------------------------------------------------------------------------

  "The Storage view" should "say the size on disk, the estimate of what is live, the growth and when it was read" taggedAs (
    UnitTest
  ) in {
    val size = row(in(TuiView.Storage)(TuiFixtures.resourcesEtc()), "Size")
    size should include("412G on disk · 390G live (estimate) · +2.1G/h over 10m 0s · read now")
  }

  it should "say how old the reading is when the database was busy at every read since" taggedAs (UnitTest) in {
    val s = TuiFixtures.withResources(
      TuiFixtures.syncedEtc(),
      storage = TuiFixtures.storageSample.copy(at = TuiFixtures.Now.minusSeconds(95))
    )
    row(in(TuiView.Storage)(s), "Size") should include("read 1m 35s ago")
    // The database is read every five seconds: a reading three seconds old says so.
    val recent = TuiFixtures.withResources(
      TuiFixtures.syncedEtc(),
      storage = TuiFixtures.storageSample.copy(at = TuiFixtures.Now.minusSeconds(3))
    )
    row(in(TuiView.Storage)(recent), "Size") should include("read 3s ago")
  }

  it should "say that hit rates are not counted where the database does not count them, and never draw a rate" taggedAs (
    UnitTest
  ) in {
    val quiet = row(in(TuiView.Storage)(TuiFixtures.resourcesEtc()), "Cache")
    quiet should include("hit rates not counted (db.rocksdb.enable-statistics is off)")
    (quiet should not).include("% hits")
    row(in(TuiView.Storage)(TuiFixtures.resourcesSepolia()), "Cache") should include("97.1% hits")
  }

  it should "say writes are held, or refused, in the database's own terms" taggedAs (UnitTest) in {
    row(in(TuiView.Storage)(TuiFixtures.resourcesEtc()), "Writes") should include("accepted, not held")
    row(in(TuiView.Storage)(TuiFixtures.resourcesSepolia()), "Writes") should include("held to 16M/s")
    row(in(TuiView.Storage)(TuiFixtures.pressureEtc()), "Writes") should include(
      "REFUSED until flush and compaction catch up"
    )
    row(in(TuiView.Storage)(TuiFixtures.resourcesSepolia()), "Compact") should include(
      "3 stores pending · 2 running · 41.0G to rewrite"
    )
  }

  it should "list the stores that hold data, the largest first, and count the empty ones it leaves out" taggedAs (
    UnitTest
  ) in {
    val all = rows(in(TuiView.Storage)(TuiFixtures.resourcesEtc()))
    row(in(TuiView.Storage)(TuiFixtures.resourcesEtc()), "Stores") should include(
      "13 of 18 hold data · 5 empty not listed"
    )
    val listed = all.dropWhile(!_.startsWith(" store ")).drop(1).takeWhile(_.trim.nonEmpty).map(_.trim)
    listed.head should startWith("state trie by path")
    listed.head should include("141G")
    listed.head should include("1,204,118,220")
    listed.exists(_.startsWith("healing frontier")) shouldBe false
    listed.exists(_.startsWith("trie nodes by hash")) shouldBe false
  }

  it should "draw a store of a few kilobytes in kilobytes, not as nothing" taggedAs (UnitTest) in {
    val small = rows(in(TuiView.Storage)(TuiFixtures.resourcesEtc())).find(_.trim.startsWith("node state ")).value
    small should include(" 12K ")
    (small should not).include(" 0M ")
  }

  it should "count the stores it has no rows for, and cut none" taggedAs (UnitTest) in {
    val small = rows(in(TuiView.Storage)(TuiFixtures.resourcesEtc()), 80, 24)
    val shown = small.count(r => TuiFixtures.stores.exists(s => r.trim.startsWith(s.name + " ")))
    val notShown = small.find(_.contains("smaller stores not shown")).value
    notShown should include(s"${13 - shown} smaller stores not shown")
  }

  it should "say what the audit has walked, and that the whole is not known" taggedAs (UnitTest) in {
    val audit = row(in(TuiView.Storage)(TuiFixtures.resourcesSepolia()), "Audit")
    audit should include("pass 2 · 412,880,114 nodes · 3 holes · 3 repaired · 0 unresolved")
    (audit should not).include("%")
    row(in(TuiView.Storage)(TuiFixtures.resourcesEtc()), "Audit") should include("not running now")
    val silent = TuiFixtures.withResources(
      TuiFixtures.syncedEtc(),
      storage = TuiFixtures.storageSample.copy(audit = AuditReading.NoAnswer)
    )
    row(in(TuiView.Storage)(silent), "Audit") should include("it did not answer in time")
  }

  it should "count the marks it has no rows for at every height, never none of them and never one" taggedAs (
    UnitTest
  ) in {
    val names = TuiFixtures.markers.map(_._1)
    for h <- 16 to 48 do
      val all = rows(in(TuiView.Storage)(TuiFixtures.resourcesEtc()), 120, h)
      val shown = all.count(r => names.exists(n => r.startsWith(s" $n ")))
      val counted = """(\d+) (more )?marks not shown""".r.findFirstMatchIn(all.mkString("\n"))
      withClue(s"120x$h: ") {
        counted match
          case Some(m) =>
            val hidden = m.group(1).toInt
            // The count never takes the row of the one mark it would count.
            hidden should be >= 2
            shown + hidden shouldBe names.size
            (m.group(2) == null) shouldBe (shown == 0)
          // No count: every mark is on screen, or the panel has only its audit row.
          case None => Seq(0, names.size) should contain(shown)
      }
  }

  it should "say what this client does not keep" taggedAs (UnitTest) in {
    rows(in(TuiView.Storage)(TuiFixtures.resourcesEtc())).exists(
      _.contains("Not kept by this client: a freezer for old blocks (no history is expired).")
    ) shouldBe true
  }

  // ---- the System view ----------------------------------------------------------------------------------------------

  "The Overview's disk cell and the SNAP sample's age" should "warn where the configured thresholds do, as the alert row does" taggedAs (
    UnitTest
  ) in {
    // prism: 388G free is well with the default warning at 20G, and low with one at 500G: the cell, the bar and the
    // alert row read the one configured threshold.
    val strict = AlertThresholds.Default.copy(diskWarnBytes = 500L * 1024 * 1024 * 1024)
    val calm = still(TuiFixtures.resourcesEtc())
    val warned = calm.copy(thresholds = strict)
    val g = TuiGlyphs.of(GlyphSet.Emoji)
    // The resources row's cell, not the alert row's text.
    def cell(state: TuiState): String = rows(state, 120, 40).find(_.contains("Disk   388G free")).value
    (cell(calm) should not).include(g.warn.text)
    cell(warned) should include(s"388G free ${g.warn.text}")
    Alerts.of(warned).map(_.text) should contain("disk 388G free (warn < 500G)")
    warned.healthBars.find(_.subsystem == Subsystem.Disk).value.state shouldBe CheckState.Warn
    // The sample is 12 s old in the fixture: stale under a 10 s limit, and not under the default 90 s.
    val snap = still(TuiFixtures.snapStorageMordor()).withView(TuiView.Chain)
    val impatient =
      snap.copy(thresholds = AlertThresholds.Default.copy(snapSampleMaxAge = java.time.Duration.ofSeconds(10)))
    rows(snap, 120, 40).exists(_.contains("sample 12s old")) shouldBe false
    rows(impatient, 120, 40).exists(_.contains("sample 12s old")) shouldBe true
    Alerts.of(impatient).map(_.text).exists(_.startsWith("SNAP sample is 12s old")) shouldBe true
  }

  "The System view" should "give each meter its figure now and the highest of the readings kept" taggedAs (UnitTest) in {
    val s = in(TuiView.System)(TuiFixtures.resourcesEtc())
    row(s, "CPU") should include("highest 45% in 10m 0s")
    row(s, "CPU") should include("of 8 processors")
    row(s, "Heap") should include("now 3.4G of 8.0G")
    row(s, "GC") should include("2,114 pauses · last 14 ms")
    row(s, "Threads") should include("peak 171 since the start")
    row(s, "Machine") should include("load 1.84")
    row(s, "Net in") should include("RLPx only: no discovery, no RPC")
    row(s, "Process") should include("up 12d 7h")
  }

  it should "say a CPU load the JVM does not give is not given, and chart it as a gap" taggedAs (UnitTest) in {
    val sample = TuiFixtures.systemSample()
    val blind = sample.copy(processCpu = None, history = sample.history.map(_.copy(processCpu = None)))
    val cpu = row(in(TuiView.System)(TuiFixtures.withResources(TuiFixtures.syncedEtc(), system = blind)), "CPU")
    cpu should include("now not given by this JVM")
    (cpu should not).include("0%")
    (cpu should not).include("highest")
  }

  it should "count the meters it has no rows for" taggedAs (UnitTest) in {
    val small = rows(in(TuiView.System)(TuiFixtures.resourcesEtc()), 80, 24)
    val labels =
      Seq("Process", "CPU", "Heap", "Net in", "Net out", "GC", "Threads", "Machine", "NonHeap", "Msg in", "Msg out")
    val shown = labels.count(l => small.exists(_.startsWith(s" $l ")))
    shown should be < labels.size
    small.find(_.contains("more meters not shown")).value should include(s"${labels.size - shown} more meters")
    // With the rows for all of them there is no count.
    val tall = rows(in(TuiView.System)(TuiFixtures.resourcesEtc()), 160, 48)
    labels.count(l => tall.exists(_.startsWith(s" $l "))) shouldBe labels.size
    tall.exists(_.contains("more meters not shown")) shouldBe false
  }

  it should "line the meters' captions up whatever each chart holds" taggedAs (UnitTest) in {
    val s = in(TuiView.System)(TuiFixtures.resourcesEtc())
    val starts =
      Seq("CPU", "Heap", "Net in", "Net out", "GC", "Threads", "NonHeap", "Msg out").map(row(s, _).indexOf("now "))
    starts.distinct should have size 1
  }

  // ---- the health bars ----------------------------------------------------------------------------------------------

  "A health bar" should "be coloured by the alert row's rules and nothing else, on every fixture" taggedAs (UnitTest) in {
    val fixtures = TuiFixtures.all ++ TuiFixtures.alerts ++ Seq[(String, GlyphSet => TuiState)](
      "resources" -> (g => TuiFixtures.resourcesEtc(g)),
      "busy" -> (g => TuiFixtures.resourcesSepolia(g)),
      "pressure" -> (g => TuiFixtures.pressureEtc(g))
    )
    for (name, fixture) <- fixtures do
      val s = fixture(GlyphSet.Emoji)
      withClue(s"$name: ") {
        val raised = s.healthBars.flatMap(_.checks).flatMap(_.alert)
        raised.toSet shouldBe Alerts.of(s).toSet
        for bar <- s.healthBars do
          val worst = bar.checks.flatMap(_.alert).map(_.severity)
          bar.state shouldBe (
            if worst.contains(Severity.Error) then CheckState.Error
            else if worst.contains(Severity.Warn) then CheckState.Warn
            else bar.state
          )
          if worst.isEmpty then bar.state should (be(CheckState.Ok).or(be(CheckState.Unknown)))
          // A bar that raises an alert has no room left, and names a check that raises one.
          if worst.nonEmpty then
            bar.headroom shouldBe Some(0.0)
            bar.limiting.value.alert shouldBe defined
      }
  }

  it should "name the check that sets it, and list that check on the screen it opens" taggedAs (UnitTest) in {
    val s = TuiFixtures.pressureEtc()
    for bar <- s.healthBars do
      val limiting = bar.limiting.value
      val screen = rows(opened(s, bar.subsystem))
      withClue(s"${bar.subsystem}: ") {
        screen.exists(_.startsWith(s" ${limiting.name}  ")) shouldBe true
        for check <- bar.checks do screen.exists(_.startsWith(s" ${check.name}  ")) shouldBe true
      }
  }

  it should "say each check's value, threshold, state and how long it has been in it" taggedAs (UnitTest) in {
    val screen = rows(opened(TuiFixtures.pressureEtc(), Subsystem.Memory))
    val heap = screen.indexWhere(_.startsWith(" heap  "))
    screen(heap) should include("never under 93% of 8.0G in the last minute")
    screen(heap + 1) should include("WARN for 3m 4s · warn when above 90% for a minute · 0% room")
    val gc = screen.indexWhere(_.startsWith(" garbage collection  "))
    screen(gc + 1) should include("WARN for 3m 4s · warn at 20% of the time")
  }

  it should "be selected with the arrows on the System view, opened with Enter and left with Esc" taggedAs (UnitTest) in {
    val s = in(TuiView.System)(TuiFixtures.resourcesEtc())
    s.selectedHealth shouldBe None
    val first = keys(s, Key.Down)
    first.selectedHealth shouldBe Some(Subsystem.P2p)
    val second = keys(first, Key.Down)
    second.selectedHealth shouldBe Some(Subsystem.Chain)
    val open = keys(second, Key.Enter)
    open.screen shouldBe Some(Screen.HealthDetail(Subsystem.Chain))
    open.viewTitle shouldBe "Health"
    val back = keys(open, Key.Esc)
    back.screen shouldBe None
    back.selectedHealth shouldBe Some(Subsystem.Chain)
    // Enter with nothing selected opens nothing.
    keys(s, Key.Enter).screen shouldBe None
  }

  it should "draw no bar as empty or full that has no measure, and keep one cell for any room at all" taggedAs (
    UnitTest
  ) in {
    val g = TuiGlyphs.of(GlyphSet.Emoji)
    panels.HealthPanel.cells(None, 5, g) shouldBe "·····"
    panels.HealthPanel.cells(Some(0.0), 5, g) shouldBe "░░░░░"
    panels.HealthPanel.cells(Some(0.01), 5, g) shouldBe "█░░░░"
    panels.HealthPanel.cells(Some(1.0), 5, g) shouldBe "█████"
    panels.HealthPanel.cells(Some(0.5), 10, TuiGlyphs.of(GlyphSet.Ascii)) shouldBe "#####-----"
  }

  "The Overview's health strip" should "draw every bar whole at 80 columns, or count the ones it has no room for" taggedAs (
    UnitTest
  ) in {
    for fixture <- Seq(TuiFixtures.resourcesSepolia(), TuiFixtures.pressureEtc(), TuiFixtures.resourcesEtc()) do
      val strip = row(still(fixture), "Health", 80, 24)
      val names = Seq("P2P", "Chain", "CL", "Mining", "RPC", "DB", "Disk", "Mem")
      val shown = names.count(n => strip.contains(s" $n ") || strip.contains(s"· $n "))
      val more = """\+(\d+)""".r.findFirstMatchIn(strip).fold(0)(_.group(1).toInt)
      shown + more shouldBe fixture.healthBars.size
  }
