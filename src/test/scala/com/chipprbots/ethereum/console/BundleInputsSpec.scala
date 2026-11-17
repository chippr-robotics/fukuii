package com.chipprbots.ethereum.console

import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

/** What the dashboard puts in a support bundle (spec 029 FR-054, FR-058, T099): every view as plain text, its log
  * lines, its alerts and health checks, and the database's figures, each read from the state when it is asked for.
  */
class BundleInputsSpec extends AnyFlatSpec with Matchers with OptionValues:

  private val renderer = TuiRenderer(TuiConfig.default)

  /** Every fixture a view can be drawn from: the nodes at their head and syncing, with their resources read, with
    * alerts, mining and not, drawn with the emoji glyphs the dashboard starts with.
    */
  private val fixtures: Seq[(String, TuiState)] =
    (TuiFixtures.all ++ TuiFixtures.alerts).map((name, make) => name -> make(GlyphSet.Emoji)) ++ Seq(
      "resources-etc" -> TuiFixtures.resourcesEtc(),
      "resources-sepolia" -> TuiFixtures.resourcesSepolia(),
      "observed-sepolia" -> TuiFixtures.observedSepolia(),
      "mempool-sepolia" -> TuiFixtures.mempoolSepolia(),
      "empty" -> TuiState(now = TuiFixtures.Now)
    )

  private def inputs(state: TuiState) =
    BundleInputs.of(() => state, Seq("fukuii 0.11.0"), () => Seq("fukuii.datadir = /data"))

  "The views of a bundle" should "be every view of the dashboard, by its title, in the dashboard's order" taggedAs (
    UnitTest
  ) in {
    for (name, state) <- fixtures do
      withClue(s"$name: ") {
        inputs(state).views().map(_._1) shouldBe TuiView.values.toSeq.map(_.title)
      }
    (TuiView.values.map(_.title).distinct should have).length(TuiView.values.length.toLong)
  }

  it should "be plain text: 160 columns at most, no escape sequence, ASCII only, no trailing blanks" taggedAs (
    UnitTest
  ) in {
    BundleInputs.Cols shouldBe 160
    for
      (name, state) <- fixtures
      (title, rows) <- inputs(state).views()
    do
      withClue(s"$name, $title: ") {
        rows should have size BundleInputs.Rows.toLong
        rows.exists(_.nonEmpty) shouldBe true
        for (row, i) <- rows.zipWithIndex do
          withClue(s"row $i '$row': ") {
            row.length should be <= 160
            (row should not).include("\u001b")
            row.filterNot(ch => ch >= ' ' && ch <= '~') shouldBe ""
            row shouldBe row.replaceAll("\\s+$", "")
          }
      }
  }

  it should "be each view itself: no Help, no screen opened over it, no dialog, no splash" taggedAs (UnitTest) in {
    val plain = TuiFixtures.syncedEtc()
    val covered = Seq(
      "Help" -> plain.openHelp,
      "a dialog" -> TuiFixtures.askedToQuit(plain),
      "the prompt" -> plain.copy(overlay = Overlay.Prompt("peer drop 3fa1", 14, None)),
      "a screen" -> plain.copy(screens = List(Screen.Art(0))),
      "the splash" -> plain.copy(splashUntilTick = Some(Long.MaxValue)),
      "another view" -> plain.withView(TuiView.Logs)
    )
    for
      (what, state) <- covered
      view <- TuiView.values
    do
      withClue(s"under $what, ${view.title}: ") {
        BundleInputs.view(renderer, state, view) shouldBe BundleInputs.view(renderer, plain, view)
      }
  }

  it should "show what each view shows: the peers, the log, the actions" taggedAs (UnitTest) in {
    val views = inputs(TuiFixtures.syncedEtc()).views().toMap
    views("Peers").mkString("\n") should include("203.0.113.")
    views("Logs").mkString("\n") should include("RegularSync: at head, block 21884102")
    views("Ops").mkString("\n") should include("maxpeers")
    views("Ops").mkString("\n") should include("Not offered, and why")
  }

  // ---- the log, the alerts, the database ----------------------------------------------------------------------------

  "The log of a bundle" should "be the dashboard's lines, oldest first: time, level, logger, text" taggedAs (UnitTest) in {
    val state = TuiFixtures.syncedEtc()
    val lines = inputs(state).logs()
    lines should have size state.logs.size.toLong
    lines.head shouldBe
      "11:58:21 INFO com.chipprbots.ethereum.network.PeerManagerActor Handshaked peer 0x3fa1…e2 CoreGeth/v1.12.19"
    lines.last shouldBe
      "12:00:00 INFO com.chipprbots.ethereum.blockchain.sync.regular.RegularSync RegularSync: at head, block 21884102"
    inputs(TuiState(now = TuiFixtures.Now)).logs() shouldBe empty
  }

  "The alerts of a bundle" should "say that there is none, then each health check with its state" taggedAs (UnitTest) in {
    val calm = TuiFixtures.syncedEtc()
    Alerts.of(calm) shouldBe empty
    val rows = BundleInputs.alerts(calm)
    rows.take(3) shouldBe Seq("no alert", "", "health checks:")
    rows.drop(3) should have size calm.healthChecks.size.toLong
    calm.healthChecks should not be empty
    for (row, check) <- rows.drop(3).zip(calm.healthChecks) do
      row shouldBe s"${check.key}: ${check.state.label}, value ${check.value}, threshold ${check.threshold}"
  }

  it should "say each alert with its severity, the errors first" taggedAs (UnitTest) in {
    val noPeers = TuiFixtures.zeroPeersEtc()
    val shown = Alerts.of(noPeers)
    shown.map(_.severity) should contain(Severity.Error)
    val rows = BundleInputs.alerts(noPeers)
    rows.take(shown.size) shouldBe shown.map(a =>
      s"${if a.severity == Severity.Error then "ERROR" else "WARN"} ${a.text}"
    )
    rows.head should startWith("ERROR ")
    rows(shown.size) shouldBe ""
    val warned = BundleInputs.alerts(TuiFixtures.lowDiskEtc())
    warned.takeWhile(_.nonEmpty) should have size 2
    for row <- warned.takeWhile(_.nonEmpty) do row should startWith("WARN ")
  }

  it should "say since when each check has been in its state, where that is known" taggedAs (UnitTest) in {
    // Every check of this fixture has been in its state for twelve minutes.
    val read = TuiFixtures.resourcesEtc()
    val checks = BundleInputs.alerts(read).dropWhile(_ != "health checks:").drop(1)
    checks should have size read.healthChecks.size.toLong
    for row <- checks do row should endWith(", since 2026-11-07T11:48:00Z")
  }

  "The database of a bundle" should "say that it has not been read, before it has" taggedAs (UnitTest) in {
    BundleInputs.database(TuiFixtures.syncedEtc()) shouldBe Seq("the database has not been read yet")
    BundleInputs.database(TuiState(now = TuiFixtures.Now)) shouldBe Seq("the database has not been read yet")
  }

  it should "give the figures the Storage view reads, each store, and each mark" taggedAs (UnitTest) in {
    val storage = TuiFixtures.storageSample
    val rows = BundleInputs.database(TuiFixtures.resourcesEtc())
    rows.take(11) shouldBe Seq(
      "read at 2026-11-07T11:59:59Z",
      s"size on disk (bytes): ${storage.sstBytes.value}",
      s"live data, estimated (bytes): ${storage.liveDataBytes.value}",
      "compaction pending: 0",
      "compaction pending (bytes): 0",
      "compactions running: 0",
      "writes stopped: false",
      "delayed write rate (bytes/s): 0",
      s"block cache (bytes): ${1_480L * 1024 * 1024} of ${2L * 1024 * 1024 * 1024}",
      s"memtables (bytes): ${312L * 1024 * 1024}",
      s"table readers (bytes): ${84L * 1024 * 1024}"
    )
    rows.filter(_.startsWith("store ")) should have size storage.stores.size.toLong
    rows.filter(_.startsWith("mark ")) shouldBe storage.markers.map((name, value) => s"mark $name: $value")
    rows.count(_.startsWith("block cache hits: ")) shouldBe 1
    rows.count(_.startsWith("audit: ")) shouldBe 1
  }

  it should "say a figure the database did not give as unknown" taggedAs (UnitTest) in {
    val bare = TuiFixtures.withLocal(
      TuiFixtures.syncedEtc(),
      _.copy(storage = Some(StorageSample(at = TuiFixtures.Now, stores = Vector(StoreSize("Headers", None, None)))))
    )
    val rows = BundleInputs.database(bare)
    rows should contain("size on disk (bytes): unknown")
    rows should contain("writes stopped: unknown")
    rows should contain("memtables (bytes): unknown")
    rows should contain("store Headers: size unknown bytes, keys unknown")
  }

  "The inputs" should "read the state again each time they are asked" taggedAs (UnitTest) in {
    var state = TuiFixtures.syncedEtc()
    val of = BundleInputs.of(() => state, Seq("fukuii 0.11.0"), () => Seq("fukuii.datadir = /data"))
    of.build() shouldBe Seq("fukuii 0.11.0")
    of.config() shouldBe Seq("fukuii.datadir = /data")
    of.alerts().head shouldBe "no alert"
    state = TuiFixtures.zeroPeersEtc()
    of.alerts().head should startWith("ERROR ")
    of.database() shouldBe Seq("the database has not been read yet")
    state = TuiFixtures.resourcesEtc()
    of.database().head shouldBe "read at 2026-11-07T11:59:59Z"
  }
