package com.chipprbots.ethereum.console

import org.jline.utils.AttributedString
import org.jline.utils.AttributedStringBuilder
import org.scalatest.BeforeAndAfterAll
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.console.gfx.ArtStore
import com.chipprbots.ethereum.console.gfx.Graphic
import com.chipprbots.ethereum.console.gfx.GraphicRegion
import com.chipprbots.ethereum.testing.Tags.*

/** Graphics in the frame (spec 029 FR-011, FR-012, FR-018, FR-061, SC-005): three modes over one layout, the network
  * icon, the mascot, and the Art screen. Golden frames of each; [[TuiGoldenFiles]] says how to regenerate them.
  */
class GraphicsRenderSpec extends AnyFlatSpec with Matchers with OptionValues with BeforeAndAfterAll with TuiGoldenFiles:

  protected val goldenTerminal = new TuiTestTerminal()
  override def afterAll(): Unit = goldenTerminal.close()

  private val renderer = TuiRenderer(TuiConfig.default, TuiFixtures.art)
  private val pixel = GraphicsMode.Pixel(TuiFixtures.Cell)
  private val modes: Seq[GraphicsMode] = Seq(GraphicsMode.Text, GraphicsMode.Block, pixel)
  private val sizes: Seq[(Int, Int)] = Seq((120, 40), (160, 48))

  private def still(s: TuiState): TuiState = s.copy(motion = Motion.Reduced)
  private def text(lines: Seq[AttributedString]): Seq[String] = visibleFrame(lines).split("\n", -1).toSeq

  /** `line` with the columns of every region on its row blanked. */
  private def outside(line: AttributedString, row: Int, regions: Seq[GraphicRegion]): AttributedString =
    regions.filter(r => row >= r.row && row < r.row + r.rows).foldLeft(line) { (l, r) =>
      val sb = new AttributedStringBuilder()
      sb.append(l.columnSubSequence(0, r.col))
      sb.append(" " * r.cols)
      sb.append(l.columnSubSequence(r.col + r.cols, l.columnLength()))
      sb.toAttributedString
    }

  /** The frame with the sidebar's row that names the mode left out: the one row a mode may change (FR-012). */
  private def comparable(lines: Seq[AttributedString], regions: Seq[GraphicRegion]): Seq[AttributedString] =
    lines.zipWithIndex.collect {
      case (l, row) if !l.toString.take(widgets.Sidebar.Cols).startsWith(" graphics ") => outside(l, row, regions)
    }

  // ---- FR-012, SC-005 ------------------------------------------------------------------------------------------------

  for (name, fixture) <- TuiFixtures.all ++ TuiFixtures.alerts; (w, h) <- sizes do
    "Changing the graphics mode" should s"change no cell outside the graphic regions: $name at ${w}x$h" taggedAs (
      UnitTest
    ) in {
      val state = still(fixture(GlyphSet.Emoji))
      val frames = modes.map(m => renderer.frame(state.copy(graphics = m), w, h))
      // The pictures are in the same rectangles in block and in pixel mode; text mode has none.
      val regions = frames(1).regions
      regions should not be empty
      frames(2).regions shouldBe regions
      frames(0).regions shouldBe empty
      val Seq(asText, asBlock, asPixel) = frames.map(f => comparable(f.lines, regions))
      asBlock shouldBe asText
      asPixel shouldBe asText
      all(frames.flatMap(_.lines.map(_.columnLength()))) shouldBe w
    }

  "A picture" should "never be the only place a value is: the frame's text names the family and the mascot's state" taggedAs (
    UnitTest
  ) in {
    for (name, fixture) <- TuiFixtures.all ++ TuiFixtures.alerts; mode <- modes do
      val state = still(fixture(GlyphSet.Emoji)).copy(graphics = mode)
      val rows = text(renderer.render(state, 160, 48))
      withClue(s"$name in ${mode.label}: ") {
        // The family, and whether it is a test network, in words beside the icon.
        rows.exists(_.contains(s" ${state.chain.value.family.label} ")) shouldBe true
        rows.exists(_.contains(s"id ${state.chain.value.chainId}")) shouldBe true
        // The state the mascot shows, in words under it.
        rows.exists(_.contains(Pose.caption(Pose.of(state)))) shouldBe true
      }
  }

  // ---- FR-061 --------------------------------------------------------------------------------------------------------

  private def icon(state: TuiState): GraphicRegion =
    renderer.frame(still(state).copy(graphics = pixel), 120, 40).regions.find(_.id == "network").value

  "The sidebar's corner" should "carry Ethereum Classic's mark on an ETC network and Ethereum's on an ETH one" taggedAs (
    UnitTest
  ) in {
    icon(TuiFixtures.syncedEtc()).graphic shouldBe Graphic.SpriteFrame("network-etc", 0, 0)
    icon(TuiFixtures.snapStorageMordor()).graphic shouldBe Graphic.SpriteFrame("network-etc", 0, 0)
    icon(TuiFixtures.sepoliaNearHead()).graphic shouldBe Graphic.SpriteFrame("network-eth", 0, 0)
    val region = icon(TuiFixtures.syncedEtc())
    (region.row, region.col, region.cols, region.rows) shouldBe (3, 1, 16, 8)
  }

  it should "say in words which network it is, and that a test network is one" taggedAs (UnitTest) in {
    def sidebar(state: TuiState): Seq[String] =
      text(renderer.render(still(state), 120, 40)).map(_.take(widgets.Sidebar.Cols))
    (sidebar(TuiFixtures.syncedEtc()).map(_.trim) should contain).allOf("ETC", "etc", "PoW · id 61", "main network")
    (sidebar(TuiFixtures.snapStorageMordor()).map(_.trim) should contain).allOf("ETC", "mordor", "test network")
    (sidebar(TuiFixtures.sepoliaNearHead()).map(_.trim) should contain)
      .allOf("ETH", "PoS", "id 11155111", "test network")
    // A chain id is never cut: Platåberget's, ten digits, has a row of its own.
    val wide =
      TuiFixtures.sepoliaNearHead().copy(chain = Some(TuiFixtures.chainSepolia.copy(chainId = BigInt(7091047534L))))
    (sidebar(wide).map(_.trim) should contain).allOf("PoS", "id 7091047534", "test network")
    // A chain id the node does not know is called neither.
    val custom = TuiFixtures.syncedEtc().copy(chain = Some(TuiFixtures.chainEtc.copy(chainId = BigInt(1337))))
    sidebar(custom).map(_.trim) should contain noneOf ("main network", "test network")
    ChainFacts.MainNetworks.intersect(ChainFacts.TestNetworks) shouldBe empty
  }

  it should "be blank, and no picture, until the node has read its chain" taggedAs (UnitTest) in {
    val unknown = still(TuiFixtures.syncedEtc()).copy(chain = None, graphics = pixel)
    renderer.frame(unknown, 120, 40).regions.map(_.id) should not contain "network"
  }

  // ---- FR-018 --------------------------------------------------------------------------------------------------------

  "The mascot" should "stand in the corner of the Recent strip, the large one on a 160x48 screen" taggedAs (UnitTest) in {
    val large = renderer
      .frame(still(TuiFixtures.syncedEtc()).copy(graphics = pixel), 160, 48)
      .regions
      .find(_.id == "mascot")
      .value
    (large.cols, large.rows) shouldBe (32, 16)
    // The row's last column stays blank; the caption's row and the footer are under it.
    (large.col + large.cols, large.row + large.rows) shouldBe (159, 46)
    val small = renderer
      .frame(still(TuiFixtures.syncedEtc()).copy(graphics = pixel), 120, 40)
      .regions
      .find(_.id == "mascot")
      .value
    (small.cols, small.rows) shouldBe (16, 8)
  }

  it should "not be drawn below tier M, on another view, or under a question" taggedAs (UnitTest) in {
    def ids(state: TuiState, w: Int, h: Int) =
      renderer.frame(still(state).copy(graphics = pixel), w, h).regions.map(_.id)
    ids(TuiFixtures.syncedEtc(), 80, 24) shouldBe empty
    ids(TuiFixtures.syncedEtc().withView(TuiView.Peers), 160, 48) shouldBe Seq("network")
    val asked = TuiFixtures.syncedEtc().copy(overlay = Overlay.Dialog(DialogKind.Quit))
    ids(asked, 160, 48) shouldBe Seq("network")
    // And no cell moves when the question opens: only the mascot's own cells and the dialog's rows change.
    val before = renderer.render(still(TuiFixtures.syncedEtc()).copy(graphics = GraphicsMode.Block), 160, 48)
    val after = renderer.render(still(asked).copy(graphics = GraphicsMode.Block), 160, 48)
    before.map(_.columnLength()) shouldBe after.map(_.columnLength())
  }

  it should "show its pose's first frame in block mode whatever the tick" taggedAs (UnitTest) in {
    val syncing = TuiFixtures.regularSyncEtc().copy(graphics = GraphicsMode.Block)
    val frames = (0L until 8L).map(t => renderer.frame(syncing.copy(tick = t), 160, 48))
    frames.map(_.regions.find(_.id == "mascot").value.graphic).distinct should have size 1
  }

  "Art that could not be read" should "cost the pictures and no information: every region shows its words" taggedAs (
    UnitTest
  ) in {
    val bare = TuiRenderer(TuiConfig.default, ArtStore.Empty)
    for mode <- modes do
      val state = still(TuiFixtures.syncedEtc()).copy(graphics = mode)
      val f = bare.frame(state, 160, 48)
      f.regions shouldBe empty
      // But for the row that names the mode, the frame is text mode's.
      def rows(lines: Seq[AttributedString]) = text(lines).filterNot(_.startsWith(" graphics "))
      rows(f.lines) shouldBe rows(bare.render(state.copy(graphics = GraphicsMode.Text), 160, 48))
      rows(f.lines).exists(_.contains("ETC")) shouldBe true
  }

  // ---- keys ----------------------------------------------------------------------------------------------------------

  private val ctx = UpdateCtx(page = 10, configuredMotion = Motion.Full)
  private def press(state: TuiState, keys: Key*): (TuiState, Seq[Effect]) =
    keys.foldLeft((state, Seq.empty[Effect])) { case ((s, effects), k) =>
      val (next, more) = Update(s, k, ctx)
      (next, effects ++ more)
    }
  private def typed(line: String): Seq[Key] = (Key.Char(':') +: line.map(Key.Char(_))) :+ Key.Enter

  "v" should "ask for the next graphics mode, and be in Help and in the widest footer" taggedAs (UnitTest) in {
    press(TuiFixtures.syncedEtc(), Key.Char('v'))._2 shouldBe Seq(Effect.CycleGraphics)
    press(TuiFixtures.syncedEtc(), Key.Char('V'))._2 shouldBe Seq(Effect.CycleGraphics)
    Bindings.helpRows.map(_._1) should contain("v")
    TuiRenderer.FooterKeys(Tier.L) should include("v graphics")
  }

  ":graphics" should "ask for a mode by name, and say how it is used when the name is wrong" taggedAs (UnitTest) in {
    press(TuiFixtures.syncedEtc(), typed("graphics block")*)._2 shouldBe Seq(Effect.SetGraphics(GraphicsChoice.Block))
    press(TuiFixtures.syncedEtc(), typed("graphics AUTO")*)._2 shouldBe Seq(Effect.SetGraphics(GraphicsChoice.Auto))
    val (state, effects) = press(TuiFixtures.syncedEtc(), typed("graphics sixel")*)
    effects shouldBe empty
    state.notice.value should include("usage: graphics")
    Commands.complete("gr") shouldBe "graphics "
    Commands.complete("ar") shouldBe "art"
  }

  ":art" should "open the Art screen; Up and Down turn its pages, r reads the sheets again, Esc leaves" taggedAs (
    UnitTest
  ) in {
    val (opened, _) = press(TuiFixtures.syncedEtc(), typed("art")*)
    opened.screen shouldBe Some(Screen.Art(0))
    opened.viewTitle shouldBe "Art"
    press(opened, Key.Down)._1.screen shouldBe Some(Screen.Art(1))
    press(opened, Key.Char('k'))._1.screen shouldBe Some(Screen.Art(-1))
    press(opened, Key.Down, Key.Down, Key.Home)._1.screen shouldBe Some(Screen.Art(0))
    press(opened, Key.End)._1.screen shouldBe Some(Screen.Art(-1))
    press(opened, Key.Char('r'))._2 shouldBe Seq(Effect.ReloadArt, Effect.Redraw)
    press(opened, Key.Esc)._1.screen shouldBe None
    // Anywhere else r only redraws.
    press(TuiFixtures.syncedEtc(), Key.Char('r'))._2 shouldBe Seq(Effect.Redraw)
  }

  "The Art screen" should "show every pose of every sprite, a page each, and wrap" taggedAs (UnitTest) in {
    val pages = panels.ArtPanel.pages(TuiFixtures.art)
    pages.map((s, p) => s"${s.name}/${s.poses(p).name}") should have size 19
    def title(page: Int): String =
      text(renderer.render(still(TuiFixtures.syncedEtc()).copy(screens = List(Screen.Art(page))), 160, 48))(3)
    title(0) should include("mascot · starting")
    title(1) should include("mascot · syncing")
    title(-1) should include("splash-scene")
    title(19) shouldBe title(0)
  }

  it should "place every frame of a pose side by side in pixel mode, and one in block mode" taggedAs (UnitTest) in {
    val syncing = still(TuiFixtures.syncedEtc()).copy(screens = List(Screen.Art(1)))
    val inPixel = renderer.frame(syncing.copy(graphics = pixel), 160, 48).regions.filter(_.id.startsWith("art-"))
    inPixel.map(_.graphic) shouldBe (0 until 4).map(Graphic.SpriteFrame("mascot", 1, _))
    inPixel.map(_.col).sliding(2).foreach(pair => pair(1) - pair(0) shouldBe 34)
    val inBlock = renderer.frame(syncing.copy(graphics = GraphicsMode.Block), 160, 48).regions
    inBlock.filter(_.id.startsWith("art-")).map(_.graphic) shouldBe Seq(Graphic.SpriteFrame("mascot", 1, 0))
  }

  it should "list what the loader left out" taggedAs (UnitTest) in {
    val broken =
      new ArtStore(TuiFixtures.art.sprites - "badge", Seq("badge.hi.png: a colour outside the 240 (#123456)"))
    val rows = text(
      TuiRenderer(TuiConfig.default, broken)
        .render(
          still(TuiFixtures.syncedEtc()).copy(screens = List(Screen.Art(0)), graphics = GraphicsMode.Block),
          160,
          48
        )
    )
    rows.exists(_.contains("Left out")) shouldBe true
    rows.exists(_.contains("badge.hi.png: a colour outside the 240")) shouldBe true
  }

  // ---- goldens -------------------------------------------------------------------------------------------------------

  private val art: TuiState => TuiState = _.copy(screens = List(Screen.Art(2)))

  /** (name, state, mode) */
  private val goldens: Seq[(String, TuiState, GraphicsMode)] = Seq(
    ("overview-synced", TuiFixtures.syncedEtc(), GraphicsMode.Block),
    ("overview-synced", TuiFixtures.syncedEtc(), pixel),
    ("overview-snap", TuiFixtures.snapStorageMordor(), GraphicsMode.Block),
    ("overview-sepolia", TuiFixtures.sepoliaNearHead(), GraphicsMode.Block),
    ("overview-zero-peers", TuiFixtures.zeroPeersEtc(), GraphicsMode.Block),
    ("art-watching", art(TuiFixtures.syncedEtc()), GraphicsMode.Block),
    ("art-watching", art(TuiFixtures.syncedEtc()), GraphicsMode.Text),
    ("art-watching", art(TuiFixtures.syncedEtc()), pixel)
  )

  // B-201: at tier M's narrowest screen an ETH node's Forks detail row was cut inside a timestamp. The whole frame is
  // a golden now, so the golden lint reads every row of it.
  for (name, state) <- Seq("sepolia" -> TuiFixtures.sepoliaNearHead(), "etc" -> TuiFixtures.syncedEtc()) do
    "The narrowest tier M screen, 100x32" should s"match overview-m-narrowest-$name-text.txt" taggedAs (UnitTest) in {
      val lines = renderer.render(still(state), Tier.MinMWidth, Tier.MinMHeight)
      lines should have size Tier.MinMHeight.toLong
      all(lines.map(_.columnLength())) shouldBe Tier.MinMWidth
      matchGolden(s"overview-m-narrowest-$name-text.txt", visibleFrame(lines))
    }

  for (name, state, mode) <- goldens; (w, h) <- sizes do
    val file = s"gfx-${Tier.of(w, h).toString.toLowerCase}-$name-${mode.label}.txt"

    "A frame with graphics" should s"match $file" taggedAs (UnitTest) in {
      val lines = renderer.render(still(state).copy(graphics = mode), w, h)
      lines should have size h.toLong
      all(lines.map(_.columnLength())) shouldBe w
      matchGolden(file, visibleFrame(lines))
    }
