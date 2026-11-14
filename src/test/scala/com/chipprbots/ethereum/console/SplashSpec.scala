package com.chipprbots.ethereum.console

import org.jline.utils.AttributedString
import org.scalacheck.Gen
import org.scalatest.BeforeAndAfterAll
import org.scalatest.LoneElement
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import com.chipprbots.ethereum.testing.Tags.*

/** The startup splash as a view (spec 027 FR-013, Q2, C-8, research §3.7, T034). */
class SplashSpec
    extends AnyFlatSpec
    with Matchers
    with ScalaCheckPropertyChecks
    with BeforeAndAfterAll
    with LoneElement
    with TuiGoldenFiles:

  protected val goldenTerminal = new TuiTestTerminal()
  override def afterAll(): Unit = goldenTerminal.close()

  private val renderer = TuiRenderer(TuiConfig.default)
  private val theme = TuiTheme.Ansi16

  private def text(s: TuiState, w: Int, h: Int, r: TuiRenderer = renderer): String =
    r.render(s, w, h, theme).map(_.toString).mkString("\n")

  private def splash(tick: Long, glyphs: GlyphSet = GlyphSet.Emoji): TuiState = TuiFixtures.splash(glyphs, tick)

  // ---- C-8: the logo, measured --------------------------------------------------------------------------------------

  "FukuiiBanner" should "keep the printBanner literal: 60 logo rows inside blank padding" taggedAs (UnitTest) in {
    FukuiiBanner.Text.linesIterator.size shouldBe 71
    FukuiiBanner.Logo should have size 60
    FukuiiBanner.Logo.foreach(row => (row should not).endWith(" "))
    FukuiiBanner.Logo.map(_.takeWhile(_ == ' ').length).min shouldBe 0 // the common indent is gone
  }

  it should "measure the trimmed block with the frame's own oracle" taggedAs (UnitTest) in {
    FukuiiBanner.Cols shouldBe FukuiiBanner.Logo.map(r => AttributedString(r).columnLength()).max
    FukuiiBanner.Cols shouldBe 98 // C-8: the padding made it look like 129; the glyphs fit in 98
  }

  it should "find the 11 wordmark rows on the plate, and leave the creature 49" taggedAs (UnitTest) in {
    FukuiiBanner.WordmarkRows should have size 11
    // The plate's top edge is the one solid band of the art; the three rows under the plate are the creature's tail.
    FukuiiBanner.Logo(FukuiiBanner.WordmarkRows.start) should include("ÆÆÆÆÆÆÆÆÆÆÆÆÆÆÆÆ")
    FukuiiBanner.Creature should have size 49
    FukuiiBanner.Creature.takeRight(3) shouldBe FukuiiBanner.Logo.takeRight(3)
  }

  "Splash.logoFor" should "choose by size: whole, cropped, 12-line logo or wordmark, from the trimmed block" taggedAs (
    UnitTest
  ) in {
    Splash.LogoMinCols shouldBe FukuiiBanner.Cols + 2
    (Splash.WholeMinRows, Splash.CroppedMinRows) shouldBe ((66, 52))
    Splash.logoFor(Splash.LogoMinCols, 66) shouldBe SplashLogo.Whole
    Splash.logoFor(Splash.LogoMinCols - 1, 66) shouldBe SplashLogo.Wordmark // 99 columns is tier S
    Splash.logoFor(Splash.LogoMinCols, 65) shouldBe SplashLogo.Cropped
    Splash.logoFor(Splash.LogoMinCols, 52) shouldBe SplashLogo.Cropped
    Splash.logoFor(Splash.LogoMinCols, 51) shouldBe SplashLogo.Small
    Splash.logoFor(160, 48) shouldBe SplashLogo.Small
    Splash.logoFor(120, 40) shouldBe SplashLogo.Small
    Splash.logoFor(80, 24) shouldBe SplashLogo.Wordmark
    Splash.logoFor(99, 80) shouldBe SplashLogo.Wordmark // tier S by width
    Splash.logoFor(200, 80, showLogo = false) shouldBe SplashLogo.Wordmark
  }

  "The splash" should "draw the whole logo where it fits, revealing a wordmark row each tick" taggedAs (UnitTest) in {
    val plate = FukuiiBanner.WordmarkRows.map(FukuiiBanner.Logo)
    for tick <- 0 to 11 do
      val frame = text(splash(tick), 120, 66)
      withClue(s"tick $tick: ") {
        frame should include(FukuiiBanner.Logo.head)
        plate.count(row => frame.contains(row)) shouldBe tick.min(plate.size)
      }
    text(splash(0).copy(motion = Motion.Reduced), 120, 66) should include(plate.last) // no reveal: all at once
  }

  it should "crop the logo to the creature where only 52 rows fit (Q2)" taggedAs (UnitTest) in {
    val frame = text(splash(11), 120, 52)
    FukuiiBanner.Creature.foreach(row => frame should include(row))
    (frame should not).include(FukuiiBanner.Logo(FukuiiBanner.WordmarkRows.start + 1))
  }

  it should "draw the 12-line logo above the FUKUII wordmark from tier M, and the wordmark alone at S" taggedAs (
    UnitTest
  ) in {
    text(splash(3), 120, 40) should include("=+#+.")
    text(splash(3), 120, 40) should include(Splash.Wordmark.head.trim)
    text(splash(3), 80, 24) should include(Splash.Wordmark.head.trim)
    (text(splash(3), 80, 24) should not).include("=+#+.")
    (text(splash(3), 160, 48, TuiRenderer(TuiConfig.minimal.copy(splashMs = 3000))) should not).include("=+#+.")
  }

  // Research §8.7: the old banner test asserted the name; the wordmark spells it, and the info row says it.
  it should "spell FUKUII, never FOOO" taggedAs (UnitTest) in {
    Splash.Wordmark shouldBe "FUKUII".map(Splash.WordmarkLetters).transpose.map(_.mkString(" "))
    Splash.Wordmark should have size 5
    Splash.Wordmark.map(_.length).distinct should have size 1
    val frame = text(splash(3), 80, 24)
    frame should include("FUKUII")
    Splash.Wordmark.foreach(row => frame should include(row.trim))
    (frame should not).include("""/_/    \____/\____/\____/""")
  }

  // Spec 029 FR-020: the worm is the share of the start's phases the node has reported done, never a timer.
  it should "move the worm when the node completes a phase, and for nothing else, and name the phase under way" taggedAs (
    UnitTest
  ) in {
    val g = TuiGlyphs.Emoji
    def worm(s: TuiState) = text(s, 80, 24).linesIterator.find(_.contains(g.brain.text)).get
    def at(done: StartupPhase*) = splash(3).copy(startup = done.toSet)
    // The row is centred, so the worm is found by what is left of it: the track it has crossed.
    def crossed(row: String) = row.substring(row.indexOf('['), row.indexOf(g.worm.text)).count(_ == '=')
    // Time alone moves the spinner only: the worm stays where the node's progress put it.
    (0 to 11).map(t => crossed(worm(splash(t)))).distinct should have size 1
    (0 to 11).map(t => worm(splash(t))).distinct should have size 4 // the spinner's four frames
    worm(splash(3)) should include("starting: peer-to-peer network \\") // spinner frame 3 of |/-\
    // Each phase done moves it on; all done, it has arrived and nothing spins.
    val done = StartupPhase.values.toSeq.inits.toSeq.reverse
    val shares = done.map(phases => """(\d+)%""".r.findFirstMatchIn(worm(at(phases*))).map(_.group(1).toInt))
    shares.init shouldBe Seq(Some(0), Some(20), Some(40), Some(60), Some(80))
    crossed(worm(at(done(1)*))) should be < crossed(worm(at(done(4)*)))
    worm(at()) should include("0%")
    worm(at()) should include("starting: storage and genesis")
    worm(at(StartupPhase.values.toSeq*)) should include("node started")
    // Under reduced motion nothing moves with the tick, and the phases still follow the node.
    def reduced(s: TuiState) = text(s.copy(motion = Motion.Reduced), 80, 24)
    (0 to 11).map(t => reduced(splash(t))).distinct should have size 1
    reduced(at(StartupPhase.Storage, StartupPhase.Network)) should not be reduced(at(StartupPhase.Storage))
  }

  it should "list the phases where there are rows: done, under way, to come" taggedAs (UnitTest) in {
    val g = TuiGlyphs.Emoji
    val rows =
      text(splash(3).copy(startup = Set(StartupPhase.Storage, StartupPhase.Network)), 80, 24).linesIterator.toSeq
    def row(phase: StartupPhase) = rows.find(_.trim.endsWith(phase.label)).get.trim
    row(StartupPhase.Storage) should startWith(g.check.text)
    row(StartupPhase.Network) should startWith(g.check.text)
    row(StartupPhase.Api) should startWith("\\") // the spinner, at tick 3
    row(StartupPhase.Sync) should startWith(g.dot.text)
    row(StartupPhase.Checks) should startWith(g.dot.text)
    // The labels start in one column, whatever the mark beside them.
    StartupPhase.values.toSeq
      .map(p => rows.find(_.trim.endsWith(p.label)).get.indexOf(p.label))
      .distinct should have size 1
    // Where the logo leaves no rows for the list, the worm row still names the phase under way.
    val short = text(splash(3), 80, 12)
    (short should not).include(StartupPhase.Checks.label)
    short should include("starting: peer-to-peer network")
    StartupPhase.share(Set.empty) shouldBe 0.0
    StartupPhase.share(StartupPhase.values.toSet) shouldBe 1.0
    StartupPhase.current(Set(StartupPhase.Storage)) shouldBe Some(StartupPhase.Network)
    StartupPhase.current(StartupPhase.values.toSet) shouldBe None
  }

  it should "draw the badge for the logo in block and pixel mode, where it fits above the phases" taggedAs (UnitTest) in {
    val drawing = TuiRenderer(TuiConfig.default, TuiFixtures.art)
    val pixel = GraphicsMode.Pixel(TuiFixtures.Cell)
    // 120x40: the large badge, which carries the wordmark; 80x24: the small one; neither in text mode.
    Splash.badgeFor(splash(3).copy(graphics = pixel), 120, 40, TuiFixtures.art) shouldBe Some(("badge-large", 32, 16))
    Splash.badgeFor(splash(3).copy(graphics = pixel), 80, 24, TuiFixtures.art) shouldBe Some(("badge", 16, 8))
    Splash.badgeFor(splash(3).copy(graphics = pixel), 80, 12, TuiFixtures.art) shouldBe None
    Splash.badgeFor(splash(3), 120, 40, TuiFixtures.art) shouldBe None
    Splash.badgeFor(splash(3).copy(graphics = pixel), 120, 40, gfx.ArtStore.Empty) shouldBe None
    // Pixel mode: one region, centred, its cells blank. Block mode: the same cells drawn, and no region to place.
    val inPixel = drawing.frame(splash(3).copy(graphics = pixel), 120, 40)
    val region = inPixel.regions.loneElement
    (region.id, region.cols, region.rows, region.col) shouldBe ("badge", 32, 16, 44)
    region.graphic shouldBe gfx.Graphic.SpriteFrame("badge-large", 0, 0)
    val inBlock = drawing.frame(splash(3).copy(graphics = GraphicsMode.Block), 120, 40)
    inBlock.regions shouldBe empty
    inBlock.lines.slice(region.row, region.row + region.rows).map(_.toString).mkString should include("▀")
    // The words and the phases are the same rows in both, and in every mode the frame is exact.
    def under(f: Frame) = f.lines.drop(region.row + region.rows).map(_.toString)
    under(inPixel) shouldBe under(inBlock)
    under(inPixel).mkString should include("FUKUII")
    under(inPixel).mkString should include(StartupPhase.Checks.label)
    for f <- Seq(inPixel, inBlock) do
      f.lines should have size 40
      all(f.lines.map(_.columnLength())) shouldBe 120
    // The hand-over places no picture: the rows under one are changing.
    drawing.frame(splash(12).copy(graphics = pixel), 120, 40).regions shouldBe empty
  }

  it should "show the skip hint on the last row, at every size" taggedAs (UnitTest) in {
    for (w, h) <- Seq((80, 24), (120, 40), (120, 52), (130, 70), (20, 3)) do
      renderer.render(splash(3), w, h, theme).last.toString should startWith(Splash.SkipHint.take(w))
  }

  it should "draw exactly height × width at every size, tick and glyph set" taggedAs (UnitTest) in {
    val cases =
      Gen.zip(Gen.chooseNum(1, 200), Gen.chooseNum(1, 80), Gen.chooseNum(0L, 30L), Gen.oneOf(GlyphSet.values.toSeq))
    forAll(cases, minSuccessful(300)) { case (w, h, tick, set) =>
      val lines = renderer.render(splash(tick, set), w, h, theme)
      lines should have size h.toLong
      all(lines.map(_.columnLength())) shouldBe w
    }
  }

  it should "last splashMs / animationIntervalMs ticks, then hand over 4 rows a tick" taggedAs (UnitTest) in {
    val config = TuiConfig.default
    config.splashTicks shouldBe config.splashMs / config.animationIntervalMs
    config.splashTicks shouldBe 12L
    (0L to 11L).foreach(t => splash(t).splashCover(24) shouldBe Some(0))
    def dashboard(tick: Long) = renderer.render(splash(tick).copy(splashUntilTick = None), 80, 24, theme)
    for tick <- 12L to 16L do
      val k = 4 * (tick - 12 + 1).toInt // 4 × ticks since the splash's last frame
      val frame = renderer.render(splash(tick), 80, 24, theme)
      withClue(s"tick $tick: ") {
        splash(tick).splashCover(24) shouldBe Some(k)
        frame.take(k) shouldBe dashboard(tick).take(k)
        frame.drop(k) shouldBe Splash.render(splash(tick), 80, 24, theme).drop(k)
      }
    // At 24 rows the hand-over takes five ticks; then the dashboard alone.
    splash(17).splashCover(24) shouldBe None
    renderer.render(splash(17), 80, 24, theme) shouldBe dashboard(17)
  }

  it should "be off under FUKUII_TUI_NO_SPLASH, and one static frame under reduced motion" taggedAs (UnitTest) in {
    val env = TuiEnv.from(Map("FUKUII_TUI_NO_SPLASH" -> "1", "LANG" -> "en_US.UTF-8"), Map.empty)
    val noSplash = env.applyTo(TuiSettings())
    noSplash.splashTicks shouldBe 0L
    TuiEnv
      .from(Map("FUKUII_TUI_NO_SPLASH" -> "", "LANG" -> "C.UTF-8"), Map.empty)
      .applyTo(TuiSettings())
      .splashTicks shouldBe 12L
    TuiConfig.default.copy(reducedMotion = true).splashTicks shouldBe 2L
    splash(12).copy(motion = Motion.Reduced).splashCover(24) shouldBe None // no hand-over
  }

  it should "be skipped by any key" taggedAs (UnitTest) in {
    splash(3).skipSplash.splashCover(24) shouldBe None
  }

  "The S-tier splash at tick 3" should "match its golden frame" taggedAs (UnitTest) in {
    for set <- GlyphSet.values do
      matchGolden(
        s"splash-s-tick03-${set.toString.toLowerCase}.txt",
        visibleFrame(renderer.render(splash(3, set), 80, 24, theme))
      )
  }
