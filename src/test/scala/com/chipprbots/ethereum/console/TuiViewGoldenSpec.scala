package com.chipprbots.ethereum.console

import org.jline.utils.AttributedString
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.console.TuiText.formatNumber
import com.chipprbots.ethereum.testing.Tags.*

/** Golden frames of the Sync, Peers, Chain, Logs and Help views and of the Overview's alert fixtures, at 80×24, 120×40
  * and 160×48 in both glyph sets (spec 027 T066, T091, research §3.5, §3.6, §3.9); and the five-second rule on every
  * view (SC-002).
  *
  * Like the Overview goldens, they are drawn without motion: they pin the layout. [[TuiGoldenFiles]] says how to
  * regenerate them.
  */
class TuiViewGoldenSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll with TuiGoldenFiles:

  protected val goldenTerminal = new TuiTestTerminal()
  override def afterAll(): Unit = goldenTerminal.close()

  private val renderer = TuiRenderer(TuiConfig.default)
  private val sizes: Seq[(Int, Int)] = Seq((80, 24), (120, 40), (160, 48))

  private def still(s: TuiState): TuiState = s.copy(motion = Motion.Reduced)
  private def in(view: TuiView)(fixture: GlyphSet => TuiState): GlyphSet => TuiState = g => fixture(g).withView(view)
  private def help(fixture: GlyphSet => TuiState): GlyphSet => TuiState = g => fixture(g).openHelp

  /** (file prefix, state name, fixture) */
  private val goldens: Seq[(String, String, GlyphSet => TuiState)] = Seq(
    ("sync", "snap-storage", in(TuiView.Sync)(g => TuiFixtures.snapStorageMordor(g))),
    ("sync", "regular", in(TuiView.Sync)(g => TuiFixtures.regularSyncEtc(g))),
    ("peers", "synced", in(TuiView.Peers)(g => TuiFixtures.syncedEtc(g))),
    ("chain", "etc", in(TuiView.Chain)(g => TuiFixtures.syncedEtc(g))),
    ("chain", "sepolia", in(TuiView.Chain)(g => TuiFixtures.sepoliaNearHead(g))),
    ("help", "synced", help(g => TuiFixtures.syncedEtc(g))),
    ("logs", "synced", in(TuiView.Logs)(g => TuiFixtures.syncedEtc(g)))
  ) ++ TuiFixtures.alerts.map((name, fixture) => ("overview", s"alert-$name", fixture))

  private def fileName(prefix: String, width: Int, height: Int, state: String, set: GlyphSet): String =
    s"$prefix-${Tier.of(width, height).toString.toLowerCase}-$state-${set.toString.toLowerCase}.txt"

  "The view goldens" should "cover seven views and four alert fixtures, three tiers, two glyph sets: 66 files" taggedAs (
    UnitTest
  ) in {
    val names = for (p, s, _) <- goldens; (w, h) <- sizes; set <- GlyphSet.values.toSeq yield fileName(p, w, h, s, set)
    names.distinct should have size 66
  }

  for (prefix, stateName, fixture) <- goldens; (width, height) <- sizes; set <- GlyphSet.values do
    val name = fileName(prefix, width, height, stateName, set)

    "A view" should s"match $name" taggedAs (UnitTest) in {
      val lines = renderer.render(still(fixture(set)), width, height, TuiTheme.Ansi16)
      lines should have size height.toLong
      all(lines.map(_.columnLength())) shouldBe width
      matchGolden(name, visibleFrame(lines))
    }

    it should s"carry no colour attribute with the monochrome theme ($name)" taggedAs (UnitTest) in {
      val lines = renderer.render(still(fixture(set)), width, height, TuiTheme.Mono)
      TuiTestTerminal.colourCodes(lines.map(_.toAnsi(goldenTerminal.terminal)).mkString("\r\n")) shouldBe empty
    }

  // ---- SC-002: the five-second rule, on every view ------------------------------------------------------------------

  /** The header, its separator, the alert row and the first four body rows. */
  private def topRows(s: TuiState, w: Int, h: Int): String =
    renderer.render(still(s), w, h).take(7).map(_.toString).mkString("\n")

  private val everyView: Seq[TuiState => TuiState] =
    TuiView.values.toSeq.map(v => (s: TuiState) => s.withView(v)) :+ ((s: TuiState) => s.openHelp)

  "Every view" should "show the network, peers, head vs best, the sync phase with its ETA and the alerts in its top rows" taggedAs (
    UnitTest
  ) in {
    for
      (name, fixture) <- TuiFixtures.all ++ TuiFixtures.alerts; view <- everyView; (w, h) <- sizes;
      set <- GlyphSet.values
    do
      val s = view(fixture(set))
      val top = topRows(s, w, h)
      withClue(s"$name, ${if s.helpOpen then "Help" else s.view.toString}, ${w}x$h, $set:\n$top\n") {
        top should include(s.networkName)
        top should include(
          if s.connectionStatus.startsWith("Unresponsive") then "Unresponsive" else s"${s.peerCount}/50"
        )
        top should include(formatNumber(s.currentBlock))
        // While SNAP runs the target is its pivot: the sync controller reports none (B-044).
        val target = s.snapPivot.filter(_ => s.bestBlock <= 0).getOrElse(s.bestBlock)
        top should include(if s.isSynchronized then "at head" else formatNumber(target))
        if !s.isSynchronized then top should include("ETA")
        if s.snapSyncState.isDefined then top should include("SNAP")
        val alertRow = top.linesIterator.drop(2).next()
        alertRow should (include("no alerts").or(include(" error")).or(include(" warn")))
      }
  }

  it should "keep that true down to 80×24 in the help overlay, whatever view is under it" taggedAs (UnitTest) in {
    for view <- TuiView.values do
      val s = TuiFixtures.regularSyncEtc().withView(view).openHelp
      topRows(s, 80, 24) should include("ETA")
  }

  // ---- Frames stay exact on every view at every size (FR-001) ------------------------------------------------------

  "Every view" should "draw exactly height × width, down to 1×1" taggedAs (UnitTest) in {
    for
      (_, fixture) <- TuiFixtures.all ++ TuiFixtures.alerts; view <- everyView; set <- GlyphSet.values;
      (w, h) <- Seq((1, 1), (20, 3), (40, 10), (79, 23), (99, 31), (100, 32), (139, 44), (140, 45), (200, 80))
    do
      val lines: Seq[AttributedString] = renderer.render(view(fixture(set)), w, h)
      withClue(s"${w}x$h: ") {
        lines should have size h.toLong
        all(lines.map(_.columnLength())) shouldBe w
      }
  }
