package com.chipprbots.ethereum.console

import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.console.panels.Ctx
import com.chipprbots.ethereum.console.panels.MiningPanel
import com.chipprbots.ethereum.testing.Tags.*

/** The mining panel and its alert (spec 027 FR-023, FR-016, T111, research §3.4): mining on or off, the hashrate, the
  * blocks mined, and the DAG with its epoch and progress, on an ETC node; nothing on an ETH node.
  *
  * It carries nothing of Olympia, a treasury or a base fee: Olympia is deferred on every chain.
  */
class MiningPanelSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll with TuiGoldenFiles:

  protected val goldenTerminal = new TuiTestTerminal()
  override def afterAll(): Unit = goldenTerminal.close()

  private val renderer = TuiRenderer(TuiConfig.default)
  private val sizes: Seq[(Int, Int)] = Seq((80, 24), (120, 40), (160, 48))
  private val t0 = TuiFixtures.Now

  private def still(s: TuiState): TuiState = s.copy(motion = Motion.Reduced)

  private def rows(s: TuiState, w: Int, h: Int): Seq[String] =
    TuiFixtures.panelRows(renderer.render(s, w, h).map(_.toString), w, h)

  /** The panel's rows: from its title row down to the separator before the next panel. */
  private def miningRows(s: TuiState, w: Int, h: Int): Seq[String] =
    val all = rows(s, w, h)
    val start = all.indexWhere(_.startsWith(" Mining  "))
    if start < 0 then Nil
    else all.drop(start).takeWhile(r => !r.startsWith("──") && !r.startsWith("--")).filter(_.trim.nonEmpty)

  private def texts(s: TuiState): Seq[String] = Alerts.of(s).map(_.text)

  private def ctx(s: TuiState, w: Int, h: Int): Ctx =
    Ctx(s, Tier.of(w, h), TuiGlyphs.of(s.glyphs), TuiTheme.Ansi16, TuiConfig.default)

  /** The panel's own rows, as the panel draws them, with nothing else on the screen. */
  private def drawn(s: TuiState, sample: MiningSample, w: Int, h: Int): Seq[String] =
    MiningPanel.rows(ctx(s, w, h), sample, w).map(_.toString)

  private val dagPhases: Seq[(String, DagSample)] = Seq(
    "preparing" -> DagSample(epoch = 364L, phase = DagPhase.Pending, progress = 0, loaded = false),
    "loading" -> DagSample(epoch = 364L, phase = DagPhase.Loading, progress = 42, loaded = false),
    "generating" -> DagSample(epoch = 364L, phase = DagPhase.Generating, progress = 37, loaded = false)
  )

  // ---- the panel -------------------------------------------------------------------------------------------------

  "The mining panel" should "say off, in one line, when mining is off" taggedAs (UnitTest) in {
    for (w, h) <- sizes; set <- GlyphSet.values do
      val panel = miningRows(TuiFixtures.miningOffEtc(set), w, h)
      withClue(s"${w}x$h $set:\n${panel.mkString("\n")}\n") {
        // One row: the next line is the separator of the panel under it. At tier L the logo is drawn beside it.
        panel shouldBe Seq(panel.head)
        panel.head should startWith(" Mining  off")
      }
  }

  it should "say so, in one row, when the internal miner is off and external miners are using the node" taggedAs (
    UnitTest
  ) in {
    // `mining-enabled` gates the internal CPU miner only: eth_getWork and eth_submitHashrate serve ethminer and pools.
    for (w, h) <- sizes; set <- GlyphSet.values do
      val panel = miningRows(TuiFixtures.miningExternalEtc(set), w, h)
      withClue(s"${w}x$h $set:\n${panel.mkString("\n")}\n") {
        panel should have size 1
        panel.head should startWith(" Mining  off (internal)")
        panel.head should include("external: 48.2 MH/s")
        panel.head should include("getWork 1,204 (20/min)")
      }
    val s = TuiFixtures.miningExternalEtc()
    // Work asked for, no hashrate submitted: say so, not 0 H/s.
    val noHashrate = drawn(s, TuiFixtures.miningOn.copy(hashrate = 0L, dag = None), 120, 40)
    noHashrate shouldBe Seq(noHashrate.head)
    noHashrate.head should include("external: none reported")
    noHashrate.head should include("getWork 1,204")
    // A hashrate with no getWork (a pool that only reports).
    drawn(s, MiningSample(hashrate = 1_500L), 120, 40).head should include("external: 1.5 kH/s")
    (drawn(s, MiningSample(hashrate = 1_500L), 120, 40).head should not).include("getWork")
    // Nothing from an external miner: plain off, as before.
    drawn(s, MiningSample(), 120, 40).head.trim shouldBe "Mining  off"
    // And no total when the rate is not known yet.
    drawn(s, MiningSample(getWork = 9L), 120, 40).head should include("getWork 9")
    (drawn(s, MiningSample(getWork = 9L), 120, 40).head should not).include("/min")
  }

  it should "show on, the coinbase, the DAG epoch and that it is loaded in a row, the hashrate and the counts in a second, from tier M" taggedAs (
    UnitTest
  ) in {
    for (w, h) <- sizes.drop(1) do
      val panel = miningRows(TuiFixtures.miningEtc(), w, h)
      withClue(s"${w}x$h:\n${panel.mkString("\n")}\n") {
        panel should have size 2
        panel.head should startWith(" Mining  on")
        panel.head should include("coinbase 0x4f2a0b9c…c91e")
        panel.head should include("DAG epoch 364 ✓ loaded")
        panel(1) should include("Hashrate")
        panel(1) should include("48.2 MH/s")
        panel(1) should include("blocks mined 3 (last 2h 14m ago)")
        panel(1) should include("stale 0")
        panel(1) should include("getWork 1,204 (20/min)")
      }
  }

  it should "show on, the hashrate and the DAG in one row at tier S" taggedAs (UnitTest) in {
    val panel = miningRows(TuiFixtures.miningEtc(), 80, 24)
    panel should have size 1
    panel.head should startWith(" Mining  on")
    panel.head should include("48.2 MH/s")
    panel.head should include("DAG epoch 364 ✓ loaded")
  }

  it should "say none reported for a hashrate of 0, and no time for a block that was never mined" taggedAs (UnitTest) in {
    val panel = miningRows(TuiFixtures.noHashrateEtc(), 120, 40)
    panel(1) should include("none reported")
    panel(1) should include("blocks mined 0")
    (panel(1) should not).include("last")
  }

  it should "say when the DAG is not started, and with the epoch when it is" taggedAs (UnitTest) in {
    val s = TuiFixtures.miningEtc()
    drawn(s, MiningSample(), 120, 40).head should include("DAG not started")
    val started = MiningSample(dag = Some(DagSample(364L, DagPhase.Pending, 0, loaded = false)))
    drawn(s, started, 120, 40).head should include("DAG epoch 364 preparing")
  }

  it should "show a DAG that is generating or loading with its percent and a spinner" taggedAs (UnitTest) in {
    val s = TuiFixtures.miningEtc().copy(motion = Motion.Full)
    for (word, dag) <- dagPhases.tail; tick <- 0L to 7L do
      val spin = "|/-\\".charAt((tick % 4).toInt)
      val row = drawn(s.copy(tick = tick), MiningSample(dag = Some(dag)), 120, 40).head
      withClue(s"$word tick $tick: '$row'\n") {
        row should include(s"DAG epoch 364 $word ${dag.progress}% $spin")
      }
  }

  it should "show the spinner as a still dot under reduced motion and pause" taggedAs (UnitTest) in {
    for motion <- Seq(Motion.Reduced, Motion.Paused); tick <- 0L to 3L; set <- GlyphSet.values do
      val s = TuiFixtures.miningEtc(set).copy(motion = motion, tick = tick)
      val dot = TuiGlyphs.of(set).dot.text
      drawn(s, MiningSample(dag = Some(dagPhases.last._2)), 120, 40).head should include(
        s"DAG epoch 364 generating 37% $dot"
      )
  }

  it should "draw the same frame at every tick under reduced motion and pause" taggedAs (UnitTest) in {
    for motion <- Seq(Motion.Reduced, Motion.Paused); set <- GlyphSet.values; (w, h) <- sizes do
      val frames = (0L to 3L).map(t => rows(TuiFixtures.dagGeneratingEtc(set).copy(motion = motion, tick = t), w, h))
      withClue(s"$motion $set ${w}x$h: ")(frames.distinct should have size 1)
  }

  it should "be on an ETC node only: an ETH node has no mining row, though its Node row still says mining off" taggedAs (
    UnitTest
  ) in {
    for (name, fixture) <- TuiFixtures.all; (w, h) <- sizes; set <- GlyphSet.values do
      val s = fixture(set)
      if s.chain.exists(_.family == ChainFamily.PoS) then
        withClue(s"$name ${w}x$h $set: ")(miningRows(s, w, h) shouldBe empty)
    // And the probe's gate is the only gate: an ETC node whose probe read nothing has no mining row.
    miningRows(TuiFixtures.syncedEtc(), 160, 48) shouldBe empty
  }

  it should "carry nothing of Olympia, a treasury or a base fee, in any state, tier or glyph set" taggedAs (UnitTest) in {
    val samples =
      Seq(TuiFixtures.miningOn, MiningSample(), MiningSample(dag = Some(DagSample(1L, DagPhase.Loading, 5, false)))) ++
        dagPhases.map((_, dag) => MiningSample(dag = Some(dag)))
    for sample <- samples; (w, h) <- sizes; set <- GlyphSet.values do
      val text = drawn(TuiFixtures.miningEtc(set), sample, w, h).mkString("\n").toLowerCase
      for word <- Seq("olympia", "treasury", "base fee", "basefee", "base-fee") do
        withClue(s"$word in:\n$text\n")((text should not).include(word))
  }

  it should "draw ASCII only under the ASCII set" taggedAs (UnitTest) in {
    for
      (w, h) <- sizes;
      fixture <- Seq(
        (g: GlyphSet) => TuiFixtures.miningEtc(g),
        (g: GlyphSet) => TuiFixtures.dagGeneratingEtc(g),
        (g: GlyphSet) => TuiFixtures.noHashrateEtc(g)
      )
    do
      val panel = miningRows(fixture(GlyphSet.Ascii), w, h)
      panel should not be empty
      for row <- panel do withClue(s"'$row': ")(row.forall(ch => ch >= ' ' && ch < '\u007f') shouldBe true)
  }

  // ---- the spinner is motion -----------------------------------------------------------------------------------------

  "The DAG spinner" should "keep the 250 ms pace while the DAG is being built, and not after" taggedAs (UnitTest) in {
    def animated(s: TuiState, w: Int = 120, h: Int = 40) = renderer.frame(s.copy(motion = Motion.Full), w, h).animated
    animated(TuiFixtures.dagGeneratingEtc()) shouldBe true
    animated(TuiFixtures.dagGeneratingEtc(), 80, 24) shouldBe true
    animated(TuiFixtures.miningEtc()) shouldBe false // loaded: the node is at its head and nothing moves
    animated(TuiFixtures.syncedEtc()) shouldBe false
    // Under reduced motion or pause the frame loop is at 1 s, whatever the DAG is doing.
    for motion <- Seq(Motion.Reduced, Motion.Paused) do
      renderer.frame(TuiFixtures.dagGeneratingEtc().copy(motion = motion), 120, 40).animated shouldBe false
  }

  // ---- the alert: mining on, hashrate 0 for more than 10 min is a warning --------------------------------------------

  /** A node with mining `miningOn`, that has read `samples` at `t0` plus the given seconds, in order. */
  private def polled(miningOn: Boolean, samples: (Long, Option[MiningSample])*): TuiState =
    val start = TuiState(
      maxPeers = 50,
      startTime = t0,
      now = t0,
      nodeSettings = NodeSettings(network = "etc", miningEnabled = miningOn)
    )
    samples.foldLeft(start) { case (state, (seconds, mining)) =>
      val at = t0.plusSeconds(seconds)
      val snapshot =
        TuiFixtures.snapshot(current = 100, best = 100, now = at).copy(local = LocalSample(mining = mining))
      state.withStatus(snapshot)
    }

  private val loadedNoHashrate = TuiFixtures.miningOn.copy(hashrate = 0L)

  "The hashrate alert" should "warn after 10 min of no hashrate with the DAG loaded, and not at 10 min" taggedAs (
    UnitTest
  ) in {
    texts(polled(true, 0L -> Some(loadedNoHashrate), 600L -> Some(loadedNoHashrate))) shouldBe empty
    Alerts.of(polled(true, 0L -> Some(loadedNoHashrate), 601L -> Some(loadedNoHashrate))) shouldBe Seq(
      Alert(Severity.Warn, "mining on, no hashrate reported for 10m 1s")
    )
  }

  it should "start its clock when the DAG loads, not before: a DAG that is generating submits nothing" taggedAs (
    UnitTest
  ) in {
    val generating = loadedNoHashrate.copy(dag = Some(DagSample(364L, DagPhase.Generating, 80, loaded = false)))
    // Generating for an hour, loaded a minute ago: no warning.
    texts(
      polled(true, 0L -> Some(generating), 3_600L -> Some(generating), 3_660L -> Some(loadedNoHashrate))
    ) shouldBe empty
    // Loaded at 3,660 s: the warning comes 10 min later.
    val s = polled(true, 0L -> Some(generating), 3_660L -> Some(loadedNoHashrate), 4_261L -> Some(loadedNoHashrate))
    texts(s) shouldBe Seq("mining on, no hashrate reported for 10m 1s")
  }

  it should "clear, and start over, when a hashrate is reported, or the DAG starts to build again" taggedAs (UnitTest) in {
    val reported = TuiFixtures.miningOn
    val back = polled(
      true,
      0L -> Some(loadedNoHashrate),
      700L -> Some(loadedNoHashrate),
      701L -> Some(reported),
      800L -> Some(loadedNoHashrate)
    )
    texts(back) shouldBe empty
    back.hashrateZeroSince shouldBe Some(t0.plusSeconds(800))
    val rebuilt = loadedNoHashrate.copy(dag = Some(DagSample(365L, DagPhase.Generating, 3, loaded = false)))
    polled(true, 0L -> Some(loadedNoHashrate), 700L -> Some(rebuilt)).hashrateZeroSince shouldBe None
  }

  it should "keep its clock when the probe could not read the mining metrics" taggedAs (UnitTest) in {
    val s = polled(true, 0L -> Some(loadedNoHashrate), 300L -> None, 601L -> None)
    texts(s) shouldBe Seq("mining on, no hashrate reported for 10m 1s")
  }

  it should "not fire when mining is off, or on a node whose probe reads no mining at all" taggedAs (UnitTest) in {
    // The hashrate alert is the internal miner's: external miners that submit nothing are not "mining on" with no hashrate.
    texts(polled(false, 0L -> Some(loadedNoHashrate), 900L -> Some(loadedNoHashrate))) shouldBe empty
    texts(polled(true, 0L -> None, 900L -> None)) shouldBe empty
  }

  it should "show on the alert row, and the panel says none reported" taggedAs (UnitTest) in {
    val frame = rows(TuiFixtures.noHashrateEtc(), 80, 24)
    frame(2) should include("1 warn")
    frame(2) should include("mining on, no hashrate reported for 12m 0s")
  }

  // ---- the hashrate, as a number ---------------------------------------------------------------------------------

  "formatHashrate" should "write the unit that keeps three digits or fewer before the point" taggedAs (UnitTest) in {
    TuiText.formatHashrate(0L) shouldBe "0 H/s"
    TuiText.formatHashrate(999L) shouldBe "999 H/s"
    TuiText.formatHashrate(1_500L) shouldBe "1.5 kH/s"
    TuiText.formatHashrate(48_200_000L) shouldBe "48.2 MH/s"
    TuiText.formatHashrate(3_100_000_000L) shouldBe "3.1 GH/s"
    TuiText.formatHashrate(3_100_000_000_000L) shouldBe "3.1 TH/s"
  }

  it should "survive the miner's Long.MaxValue, and a negative reading, without a throw or a mess" taggedAs (UnitTest) in {
    // Miner.submitHashRate submits Long.MaxValue when the time it took was 0 (Miner.scala:51).
    TuiText.formatHashrate(Long.MaxValue) shouldBe "9.2 EH/s"
    TuiText.formatHashrate(-5L) shouldBe "0 H/s"
  }

  // ---- goldens: research §3.4 ------------------------------------------------------------------------------------

  private val goldens: Seq[(String, GlyphSet => TuiState)] = Seq(
    "on" -> (g => TuiFixtures.miningEtc(g)),
    "dag" -> (g => TuiFixtures.dagGeneratingEtc(g)),
    "off" -> (g => TuiFixtures.miningOffEtc(g)),
    "no-hashrate" -> (g => TuiFixtures.noHashrateEtc(g)),
    "external" -> (g => TuiFixtures.miningExternalEtc(g))
  )

  private def fileName(width: Int, height: Int, state: String, set: GlyphSet): String =
    s"mining-${Tier.of(width, height).toString.toLowerCase}-$state-${set.toString.toLowerCase}.txt"

  "The mining goldens" should "cover five states, three tiers, two glyph sets: 30 files" taggedAs (UnitTest) in {
    val names = for (s, _) <- goldens; (w, h) <- sizes; set <- GlyphSet.values.toSeq yield fileName(w, h, s, set)
    names.distinct should have size 30
  }

  for (stateName, fixture) <- goldens; (width, height) <- sizes; set <- GlyphSet.values do
    val name = fileName(width, height, stateName, set)

    "The Overview of an ETC node" should s"match $name" taggedAs (UnitTest) in {
      val lines = renderer.render(still(fixture(set)), width, height, TuiTheme.Ansi16)
      lines should have size height.toLong
      all(lines.map(_.columnLength())) shouldBe width
      matchGolden(name, visibleFrame(lines))
    }
