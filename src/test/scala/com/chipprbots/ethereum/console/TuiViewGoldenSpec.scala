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

  /** The synced ETC fixture after `keys` (spec 029): a selection, a screen opened with Enter, the prompt, a dialog. */
  private def after(keys: Key*): GlyphSet => TuiState = g =>
    keys.foldLeft(TuiFixtures.syncedEtc(g))((s, k) =>
      Update(s, k, UpdateCtx(page = 10, configuredMotion = Motion.Reduced))._1
    )
  private def typed(text: String): Seq[Key] = text.map(Key.Char(_))

  /** (file prefix, state name, fixture) */
  private val goldens: Seq[(String, String, GlyphSet => TuiState)] = Seq(
    ("sync", "snap-storage", in(TuiView.Chain)(g => TuiFixtures.snapStorageMordor(g))),
    ("sync", "regular", in(TuiView.Chain)(g => TuiFixtures.regularSyncEtc(g))),
    ("peers", "synced", in(TuiView.Peers)(g => TuiFixtures.syncedEtc(g))),
    ("chain", "etc", in(TuiView.Chain)(g => TuiFixtures.syncedEtc(g))),
    ("chain", "sepolia", in(TuiView.Chain)(g => TuiFixtures.sepoliaNearHead(g))),
    ("help", "synced", help(g => TuiFixtures.syncedEtc(g))),
    ("logs", "synced", in(TuiView.Logs)(g => TuiFixtures.syncedEtc(g))),
    // Spec 029 slice 1: the views that wait for their slices, a list with a selection, a screen opened over it, a
    // filter, the prompt, a notice, the quit dialog.
    ("mempool", "synced", in(TuiView.Mempool)(g => TuiFixtures.syncedEtc(g))),
    ("rpc", "synced", in(TuiView.Rpc)(g => TuiFixtures.syncedEtc(g))),
    ("storage", "synced", in(TuiView.Storage)(g => TuiFixtures.syncedEtc(g))),
    ("system", "synced", in(TuiView.System)(g => TuiFixtures.syncedEtc(g))),
    ("ops", "synced", in(TuiView.Ops)(g => TuiFixtures.syncedEtc(g))),
    ("peers", "selected", after(Key.Char('3'), Key.Down, Key.Down, Key.Down)),
    ("peer", "detail", after(Key.Char('3'), Key.Down, Key.Down, Key.Enter)),
    ("logs", "filtered", after((Key.Char('8') +: Key.Char('/') +: typed("imported") :+ Key.Enter)*)),
    ("peers", "filtering", after((Key.Char('3') +: Key.Char('/') +: typed("besu"))*)),
    ("prompt", "typing", after((Key.Char(':') +: typed("mem"))*)),
    ("prompt", "unknown", after((Key.Char(':') +: typed("mempol") :+ Key.Enter)*)),
    ("quit", "asked", after(Key.Char('q')))
  ) ++ TuiFixtures.alerts.map((name, fixture) => ("overview", s"alert-$name", fixture))

  private def fileName(prefix: String, width: Int, height: Int, state: String, set: GlyphSet): String =
    s"$prefix-${Tier.of(width, height).toString.toLowerCase}-$state-${set.toString.toLowerCase}.txt"

  "The view goldens" should "cover nineteen screens and four alert fixtures, three tiers, two glyph sets: 138 files" taggedAs (
    UnitTest
  ) in {
    val names = for (p, s, _) <- goldens; (w, h) <- sizes; set <- GlyphSet.values.toSeq yield fileName(p, w, h, s, set)
    names.distinct should have size 138
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

  /** What spec 029 slice 1 can put on screen over a view: a selection, a peer's screen, the prompt (short, and longer
    * than any row), a filter, a notice, the quit dialog.
    */
  private val everyOverlay: Seq[TuiState => TuiState] =
    val ctx = UpdateCtx(page = 10, configuredMotion = Motion.Reduced)
    def keys(ks: Key*): TuiState => TuiState = s => ks.foldLeft(s)((state, k) => Update(state, k, ctx)._1)
    Seq(
      keys(Key.Char('3'), Key.Down, Key.Down),
      keys(Key.Char('3'), Key.Down, Key.Enter),
      keys(Key.Char('3'), Key.End),
      keys((Key.Char(':') +: typed("peer 0x"))*),
      keys((Key.Char(':') +: typed("x" * 300))*),
      keys((Key.Char(':') +: typed("nonsense") :+ Key.Enter)*),
      keys((Key.Char('8') +: Key.Char('/') +: typed("block"))*),
      keys((Key.Char('8') +: Key.Char('/') +: typed("no such line") :+ Key.Enter)*),
      keys(Key.Char('q')),
      keys(Key.Char('?'), Key.Char('q'))
    )

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
      (_, fixture) <- TuiFixtures.all ++ TuiFixtures.alerts; view <- everyView ++ everyOverlay; set <- GlyphSet.values;
      (w, h) <- Seq((1, 1), (20, 3), (40, 10), (79, 23), (99, 31), (100, 32), (139, 44), (140, 45), (200, 80))
    do
      val lines: Seq[AttributedString] = renderer.render(view(fixture(set)), w, h)
      withClue(s"${w}x$h: ") {
        lines should have size h.toLong
        all(lines.map(_.columnLength())) shouldBe w
      }
  }

  // ---- Spec 029 slice 1 ---------------------------------------------------------------------------------------------

  "The quit dialog" should "never cover the alert row, at any size it is drawn at (spec 029 FR-048)" taggedAs (
    UnitTest
  ) in {
    for (name, fixture) <- TuiFixtures.alerts; (w, h) <- sizes :+ ((80, 8)) :+ ((60, 5)) do
      val asked = Update(fixture(GlyphSet.Emoji), Key.Char('q'), UpdateCtx(10, Motion.Reduced))._1
      val plain = renderer.render(still(fixture(GlyphSet.Emoji)), w, h).map(_.toString)
      val rows = renderer.render(still(asked), w, h).map(_.toString)
      withClue(s"$name ${w}x$h: ") {
        rows.take(3) shouldBe plain.take(3) // the header, its separator, the alert row
        rows.mkString("\n") should include("Stop this node?")
      }
  }

  it should "say what stops with this node: mining on a mining node, the consensus client's link on an ETH node" taggedAs (
    UnitTest
  ) in {
    def asked(s: TuiState) =
      renderer
        .render(still(Update(s, Key.Char('q'), UpdateCtx(10, Motion.Reduced))._1), 120, 40)
        .map(_.toString)
        .mkString("\n")
    asked(TuiFixtures.sepoliaNearHead()) should include("consensus client loses its execution client")
    (asked(TuiFixtures.sepoliaNearHead()) should not).include("Mining stops")
    (asked(TuiFixtures.syncedEtc()) should not).include("consensus client")
    val mining = TuiFixtures.syncedEtc()
    asked(mining.copy(nodeSettings = mining.nodeSettings.copy(miningEnabled = true))) should include("Mining stops")
  }

  "The sidebar" should "be drawn from tier M, list the nine views, and mark the one on screen" taggedAs (UnitTest) in {
    val s = TuiFixtures.syncedEtc().withView(TuiView.Storage)
    val wide = renderer.render(still(s), 120, 40).map(_.toString)
    for v <- TuiView.values do wide.count(_.startsWith(s" [${v.key}] ${v.title}")) shouldBe 1
    wide.exists(_.startsWith(s" ${s.networkName}")) shouldBe true
    val narrow = renderer.render(still(s), 80, 24).map(_.toString)
    narrow.exists(_.startsWith(" [1] Overview")) shouldBe false
    // The body beside it is drawn for the columns it has: 120 columns leave a tier-M body, 100 a tier-S one.
    TuiFixtures.SidebarCols shouldBe 19
    Tier.of(120 - TuiFixtures.SidebarCols, 40) shouldBe Tier.M
    Tier.of(100 - TuiFixtures.SidebarCols, 40) shouldBe Tier.S
  }

  "Every view and screen" should "be reached at 80×24 with printable keys, Tab and Enter only (spec 029 SC-002)" taggedAs (
    UnitTest
  ) in {
    val ctx = UpdateCtx(page = 10, configuredMotion = Motion.Reduced)
    def press(s: TuiState, keys: String): TuiState =
      keys.foldLeft(s)((state, c) => Update(state, if c == '\n' then Key.Enter else Key.Char(c), ctx)._1)
    val start = TuiFixtures.syncedEtc()
    for v <- TuiView.values do
      press(start, v.key.toString).view shouldBe v
      press(start, s":${v.title.toLowerCase}\n").view shouldBe v
      renderer.render(still(press(start, v.key.toString)), 80, 24).head.toString.trim should endWith(v.title)
    press(start, "3jj\n").screen shouldBe a[Some[?]] // a peer's screen, with j and Enter
    press(start, "3jj\n").viewTitle shouldBe "Peer"
    press(start, "?").helpOpen shouldBe true
    press(start, "q").overlay shouldBe Overlay.Dialog(DialogKind.Quit)
    press(start, "8/block\n").filterOf(TuiView.Logs) shouldBe "block"
  }
