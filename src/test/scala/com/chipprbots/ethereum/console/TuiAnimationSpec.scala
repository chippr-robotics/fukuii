package com.chipprbots.ethereum.console

import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

/** Motion from `tick` (spec 027 FR-004, FR-006, FR-010, FR-012; research §4; T031, T032, T035; SC-008). */
class TuiAnimationSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll with TuiGoldenFiles:

  protected val goldenTerminal = new TuiTestTerminal()
  override def afterAll(): Unit = goldenTerminal.close()

  private val renderer = TuiRenderer(TuiConfig.default)
  private val sets = Seq(TuiGlyphs.Emoji, TuiGlyphs.Ascii)
  private val theme = TuiTheme.Ansi16
  private val ticks = 0L to 11L

  private def rows(s: TuiState, w: Int, h: Int): Seq[String] = renderer.render(s, w, h).map(_.toString)
  private def blocksRow(s: TuiState, w: Int = 80, h: Int = 24): String =
    rows(s, w, h).find(_.trim.startsWith("Blocks")).get

  /** The cells between the bar's brackets. */
  private def track(bar: String): String = bar.substring(bar.indexOf('[') + 1, bar.indexOf(']'))

  // ---- T031: determinism, ripple, pulse, spinner -------------------------------------------------------------------

  "The renderer" should "draw the same frame for the same state, every time" taggedAs (UnitTest) in {
    for (name, fixture) <- TuiFixtures.all; set <- GlyphSet.values; tick <- ticks do
      val s = fixture(set).copy(tick = tick)
      withClue(s"$name $set tick $tick: ")(renderer.render(s, 120, 40) shouldBe renderer.render(s, 120, 40))
  }

  "The ripple" should "sit at wormAt − 1 − (tick mod 3) for ticks 0–11" taggedAs (UnitTest) in {
    for g <- sets; budget <- Seq(30, 50, 70); p <- Seq(0.12, 0.37, 0.5, 0.68, 0.95) do
      val slots = WormBar.slotsFor(budget, g)
      val at = WormBar.wormAt(p, slots)
      for tick <- ticks do
        val bar = WormBar.render(budget, WormProgress.Known(p), tick, g, theme).toString
        withClue(s"${g.set} budget $budget p $p tick $tick bar $bar: ") {
          track(bar).indexOf('~') shouldBe (at - 1 - (tick % 3).toInt).max(0) // clamped to ≥ 0 (§4.1)
          track(bar).count(_ == '~') shouldBe (if g.set == GlyphSet.Ascii then 2 else 1) // ASCII's worm is `~o`
          bar.count(_ == '=') shouldBe at - 1 // one `=` became the ripple
        }
  }

  it should "move the Blocks row of a regular sync, one cell a tick" taggedAs (UnitTest) in {
    val frames = ticks.map(t => blocksRow(TuiFixtures.regularSyncEtc().copy(tick = t)))
    frames.distinct should have size 3 // the period is 3
    frames.foreach(_ should include("~"))
  }

  it should "not be drawn when wormAt < 2, when stalled, or under reduced motion or pause" taggedAs (UnitTest) in {
    for g <- sets; tick <- ticks do
      val budget = 50
      val slots = WormBar.slotsFor(budget, g)
      val low = 1.0 / (slots - 1) // wormAt = 1
      def bar(p: Double, motion: Motion = Motion.Full, stalled: Boolean = false) =
        track(WormBar.render(budget, WormProgress.Known(p), tick, g, theme, motion, stalled).toString)
      withClue(s"${g.set} tick $tick: ") {
        (bar(low).replace(g.worm.text, "") should not).include("~")
        (bar(0.5, stalled = true).replace(g.worm.text, "") should not).include("~")
        (bar(0.5, Motion.Reduced).replace(g.worm.text, "") should not).include("~")
        (bar(0.5, Motion.Paused).replace(g.worm.text, "") should not).include("~")
      }
  }

  "The [ACTIVE…] pulse" should "cycle [ACTIVE] to [ACTIVE...] by tick mod 4, always 11 columns" taggedAs (UnitTest) in {
    WormBar.PulseCols shouldBe 11
    for tick <- ticks do
      val label = WormBar.pulse(tick, Motion.Full)
      label.length shouldBe 11
      label.trim shouldBe s"[ACTIVE${"." * (tick % 4).toInt}]"
    for g <- sets do
      val bars = ticks.map(t => WormBar.render(40, WormProgress.Active, t, g, theme))
      bars.map(_.columnLength()).distinct shouldBe Seq(40)
      bars.map(_.toString).distinct should have size 4
  }

  it should "stand still under reduced motion and pause" taggedAs (UnitTest) in {
    for motion <- Seq(Motion.Reduced, Motion.Paused) do
      ticks.map(t => WormBar.pulse(t, motion)).distinct shouldBe Seq("[ACTIVE]   ")
  }

  "The spinner" should "show frame tick mod 4 of |/-\\, and a static dot under reduced motion" taggedAs (UnitTest) in {
    for tick <- ticks do
      WormBar.spinner(tick, Motion.Full, TuiGlyphs.Emoji) shouldBe "|/-\\".charAt((tick % 4).toInt).toString
    for g <- sets; motion <- Seq(Motion.Reduced, Motion.Paused); tick <- ticks do
      WormBar.spinner(tick, motion, g) shouldBe g.dot.text
  }

  it should "turn next to an in-flight connection status" taggedAs (UnitTest) in {
    val searching = TuiState.initial.withConnectionStatus("Searching for peers")
    val lines = ticks.map(t => rows(searching.copy(tick = t), 100, 40).find(_.contains("Connection")).get)
    lines.take(4).map(_.trim) shouldBe "|/-\\".map(c => s"Connection: $c Searching for peers")
    val reduced = rows(searching.copy(motion = Motion.Reduced), 100, 40).find(_.contains("Connection")).get
    reduced.trim shouldBe "Connection: · Searching for peers"
    // A settled status keeps its bullet.
    rows(TuiFixtures.syncedEtc(), 100, 40).find(_.contains("Connection")).get.trim shouldBe "Connection: ● Connected"
  }

  // SC-008: under reduced motion and pause, nothing moves.
  "Reduced motion and pause" should "draw identical frames for ticks 0–11" taggedAs (UnitTest) in {
    val states =
      TuiFixtures.all.map(_._2) :+ ((g: GlyphSet) => TuiState(glyphs = g).withConnectionStatus("Starting node..."))
    for
      fixture <- states; set <- GlyphSet.values; motion <- Seq(Motion.Reduced, Motion.Paused);
      (w, h) <- Seq(
        (80, 24),
        (160, 48)
      )
    do
      val frames = ticks.map(t => renderer.render(fixture(set).copy(tick = t, motion = motion), w, h))
      withClue(s"$set $motion ${w}x$h: ")(frames.distinct should have size 1)
  }

  // ---- T032: the stall, measured on snapshot time ------------------------------------------------------------------

  private def regularAt(advancedSecondsAgo: Long, set: GlyphSet = GlyphSet.Emoji): TuiState =
    TuiFixtures.regularSyncEtc(set).copy(lastAdvanceAt = Some(TuiFixtures.Now.minusSeconds(advancedSecondsAgo)))

  "A stall" should "show the stalled glyph and the warn style after 120 s of unchanged progress" taggedAs (UnitTest) in {
    for set <- GlyphSet.values do
      val g = TuiGlyphs.of(set)
      regularAt(119, set).stalled shouldBe false
      (blocksRow(regularAt(119, set)) should not).include(g.stalled.text.trim)
      regularAt(120, set).stalled shouldBe true
      val row = blocksRow(regularAt(120, set))
      withClue(row)(row should include(g.worm.text + g.stalled.text))
    // The track and the worm take the warn style; the brain stays healthy.
    val bar = WormBar.render(50, WormProgress.Known(0.3), 0L, TuiGlyphs.Emoji, theme, Motion.Full, stalled = true)
    val wormCol = bar.toString.indexOf(TuiGlyphs.Emoji.worm.text)
    bar.styleAt(wormCol) shouldBe theme.warn
    bar.styleAt(1) shouldBe theme.warn
  }

  it should "stop the ripple" taggedAs (UnitTest) in {
    ticks.map(t => blocksRow(regularAt(200).copy(tick = t))).distinct should have size 1
  }

  it should "keep the bar exactly its budget, with the glyph after the worm or, near the end, before it" taggedAs (
    UnitTest
  ) in {
    for g <- sets; budget <- 8 to 80; p <- Seq(0.0, 0.05, 0.3, 0.9, 0.97, 0.999); tick <- ticks do
      val bar = WormBar.render(budget, WormProgress.Known(p), tick, g, theme, Motion.Full, stalled = true)
      withClue(s"${g.set} budget $budget p $p: ")(bar.columnLength() shouldBe budget)
    val nearEnd = WormBar.render(30, WormProgress.Known(0.97), 0L, TuiGlyphs.Emoji, theme, stalled = true).toString
    nearEnd should include(TuiGlyphs.Emoji.stalled.text + TuiGlyphs.Emoji.worm.text)
  }

  it should "be measured on snapshot time, not on ticks" taggedAs (UnitTest) in {
    val s = TuiFixtures.regularSyncEtc().copy(lastAdvanceAt = Some(TuiFixtures.Now))
    // A thousand frames without a new snapshot: no time has passed.
    (1 to 1000).foldLeft(s)((st, _) => st.nextFrame(None)).stalled shouldBe false
    val later =
      TuiFixtures.snapshot(current = s.currentBlock, best = s.bestBlock, now = TuiFixtures.Now.plusSeconds(120))
    s.nextFrame(Some(later)).stalled shouldBe true
  }

  it should "clear on the next advance" taggedAs (UnitTest) in {
    val stalled = regularAt(300)
    stalled.stalled shouldBe true
    val advanced =
      TuiFixtures.snapshot(current = stalled.currentBlock + 1, best = stalled.bestBlock, now = TuiFixtures.Now)
    val next = stalled.withStatus(advanced)
    next.stalled shouldBe false
    next.lastAdvanceAt shouldBe Some(TuiFixtures.Now)
  }

  it should "reset the speed baseline when the current block goes down, as a rewind does" taggedAs (UnitTest) in {
    val stalled = regularAt(300)
    // Below the baseline (18,300,893), as TuiState.scala:69-72 did it: the speed restarts from the rewound block.
    val rewoundTo = stalled.syncBaseline.get.block - 1_000
    val rewound = TuiFixtures.snapshot(current = rewoundTo, best = stalled.bestBlock, now = TuiFixtures.Now)
    val next = stalled.withStatus(rewound)
    next.syncBaseline shouldBe Some(SyncBaseline(rewoundTo, TuiFixtures.Now))
    next.stalled shouldBe false
  }

  // In ASCII the worm is `~o`: at tick 0 the ripple's `~` touches the worm's own (`~~o`), at tick 1 it is one cell back
  // (`~=~o`). Both ticks, both sets.
  "The rippling Overview" should "match its golden frames at ticks 0 and 1, 80×24, in both sets" taggedAs (UnitTest) in {
    for set <- GlyphSet.values; tick <- Seq(0L, 1L) do
      matchGolden(
        f"overview-s-regular-ripple-t$tick%02d-${set.toString.toLowerCase}.txt",
        visibleFrame(renderer.render(TuiFixtures.regularSyncEtc(set).copy(tick = tick), 80, 24, theme))
      )
  }

  "The stalled Overview" should "match its golden frames at 80×24" taggedAs (UnitTest) in {
    for set <- GlyphSet.values do
      val frame = visibleFrame(renderer.render(TuiFixtures.stalledEtc(set), 80, 24, theme))
      matchGolden(s"overview-s-stalled-${set.toString.toLowerCase}.txt", frame)
  }

  // ---- T035: the pace ----------------------------------------------------------------------------------------------

  private def animated(s: TuiState, w: Int = 120, h: Int = 40): Boolean = renderer.frame(s, w, h, theme).animated

  "The pace" should "be 250 ms while something moves and 1000 ms otherwise (Q1)" taggedAs (UnitTest) in {
    val config = TuiConfig.default
    config.paceMs(animated = true) shouldBe 250L
    config.paceMs(animated = false) shouldBe 1000L
  }

  it should "count a rippling worm, a pulse, a spinner, the splash and the celebration as motion" taggedAs (
    UnitTest
  ) in {
    animated(TuiFixtures.regularSyncEtc()) shouldBe true // ripple
    // The pulse alone: connected (no spinner), and an Overall worm at 0 (no ripple).
    val pulsing = TuiState.initial
      .withConnectionStatus("Connected")
      .withBlockInfo(0, 100)
      .withSnapSyncState(SnapSyncState(phase = "AccountRangeSync", accountsSynced = 5))
    animated(pulsing) shouldBe true
    animated(pulsing.copy(snapSyncState = None)) shouldBe false
    animated(TuiState.initial.withConnectionStatus("Searching for peers")) shouldBe true // spinner
    animated(TuiFixtures.splash(GlyphSet.Emoji, tick = 3)) shouldBe true
    animated(TuiFixtures.celebration(GlyphSet.Emoji, frame = 4)) shouldBe true
  }

  it should "count a synced, idle node, and a stalled bar, as still" taggedAs (UnitTest) in {
    animated(TuiFixtures.syncedEtc()) shouldBe false
    animated(TuiFixtures.stalledEtc(GlyphSet.Emoji)) shouldBe false
    animated(TuiFixtures.celebration(GlyphSet.Emoji, frame = 12)) shouldBe false // the celebration is over
  }

  it should "be 1000 ms under reduced motion and pause, whatever is on screen" taggedAs (UnitTest) in {
    val moving = Seq(
      TuiFixtures.regularSyncEtc(),
      TuiState.initial.withConnectionStatus("Searching for peers"),
      TuiFixtures.snapStorageMordor()
    )
    for s <- moving; motion <- Seq(Motion.Reduced, Motion.Paused) do
      TuiConfig.default.paceMs(animated(s.copy(motion = motion))) shouldBe 1000L
  }

  it should "count only the rows on screen" taggedAs (UnitTest) in {
    // The Blocks worm is below the fold of a five-row terminal.
    animated(TuiFixtures.regularSyncEtc(), 80, 5) shouldBe false
  }
