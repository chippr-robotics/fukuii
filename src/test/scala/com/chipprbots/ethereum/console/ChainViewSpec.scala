package com.chipprbots.ethereum.console

import org.scalatest.BeforeAndAfterAll
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.console.panels.ObservatoryPanels
import com.chipprbots.ethereum.testing.Tags.*

/** The Chain view with the node's blocks read (spec 029 FR-022 to FR-027): the head, the Block Observatory, the fees
  * and the reorgs. Golden frames at three sizes in both glyph sets; [[TuiGoldenFiles]] says how to regenerate them.
  */
class ChainViewSpec extends AnyFlatSpec with Matchers with OptionValues with BeforeAndAfterAll with TuiGoldenFiles:

  protected val goldenTerminal = new TuiTestTerminal()
  override def afterAll(): Unit = goldenTerminal.close()

  private val renderer = TuiRenderer(TuiConfig.default)
  private val sizes: Seq[(Int, Int)] = Seq((80, 24), (120, 40), (160, 48))

  private def chainView(s: TuiState): TuiState = s.withView(TuiView.Chain).copy(motion = Motion.Reduced)

  /** The view's rows as text, without the sidebar's columns. */
  private def rows(s: TuiState, w: Int = 160, h: Int = 48): Seq[String] =
    TuiFixtures.panelRows(visibleFrame(renderer.render(chainView(s), w, h)).split("\n", -1).toSeq, w, h)

  private val goldens: Seq[(String, GlyphSet => TuiState)] = Seq(
    "observed-etc" -> (g => TuiFixtures.observedEtc(g)),
    "observed-sepolia" -> (g => TuiFixtures.observedSepolia(g)),
    "observed-syncing" -> (g => TuiFixtures.observedSyncingEtc(g))
  )

  for (name, fixture) <- goldens; (w, h) <- sizes; set <- GlyphSet.values do
    val file = s"chain-${Tier.of(w, h).toString.toLowerCase}-$name-${set.toString.toLowerCase}.txt"

    "The Chain view" should s"match $file" taggedAs (UnitTest) in {
      val lines = renderer.render(chainView(fixture(set)), w, h)
      lines should have size h.toLong
      all(lines.map(_.columnLength())) shouldBe w
      matchGolden(file, visibleFrame(lines))
    }

  "The head panel" should "name the miner and the difficulty on ETC, the fee recipient on ETH, and a base fee where there is one" taggedAs (
    UnitTest
  ) in {
    val etc = rows(TuiFixtures.observedEtc()).mkString("\n")
    etc should include("miner 0x9f3a")
    etc should include("difficulty")
    (etc should not).include("fee recipient")
    (etc should not).include("base fee")
    val eth = rows(TuiFixtures.observedSepolia()).mkString("\n")
    eth should include("fee recipient 0x")
    eth should include("base fee 13.7 gwei")
    (eth should not).include("difficulty")
    (eth should not).include(" miner ")
  }

  it should "say how fast blocks arrive, and how long the last import took" taggedAs (UnitTest) in {
    val rate = rows(TuiFixtures.observedEtc()).find(_.contains(" Rate ")).value
    rate should include("0.08 blocks/s")
    rate should include("310.0K gas/s")
    // While the node syncs, the sync's own panel says how long an import took: once is enough.
    (rows(TuiFixtures.observedSyncingEtc()).find(_.contains(" Rate ")).value should not).include("last import")
    rate should include("last import 1 block in 22 ms")
  }

  "The Block Observatory" should "list the newest blocks first, and draw one chart glyph a block" taggedAs (UnitTest) in {
    val s = TuiFixtures.observedEtc()
    val all = rows(s)
    val header = all.indexWhere(_.contains("block") && all.exists(_.contains("gas used")))
    val head = s.currentBlock
    val table = all.dropWhile(!_.trim.startsWith("block ")).drop(1).takeWhile(_.trim.headOption.exists(_.isDigit))
    table.head should include(TuiText.formatNumber(head))
    table(1) should include(TuiText.formatNumber(head - 1))
    header should be >= 0
    // A block whose body was not read shows that, not a zero.
    val unread = TuiFixtures.withChain(
      s,
      ChainSample(s.snapshot.value.local.chain.value.recent.map(_.copy(txCount = None, sizeBytes = None, fees = None)))
    )
    val headRow = rows(unread).find(_.trim.startsWith(TuiText.formatNumber(head))).value
    (headRow should include).regex("""\d+s +- +\d""") // the age, no transaction count, the gas
    (headRow should include).regex("""% +- +0x""") // no size
    val chain = s.snapshot.value.local.chain.value
    (ObservatoryPanels.gasChart(chain.recent, 200, TuiGlyphs.Emoji) should have).length(64)
    (ObservatoryPanels.gasChart(chain.recent, 10, TuiGlyphs.Emoji) should have).length(10)
    ObservatoryPanels.gasChart(chain.recent, 64, TuiGlyphs.Ascii).forall(_ < 128) shouldBe true
  }

  it should "say it is a sample when blocks arrive faster than they are read" taggedAs (UnitTest) in {
    rows(TuiFixtures.observedSyncingEtc()).mkString("\n") should include(
      "a sample: blocks arrive faster than they are read"
    )
    (rows(TuiFixtures.observedEtc()).mkString("\n") should not).include("a sample")
  }

  "The fees" should "be the base fee and the tips on a chain with a base fee, and gas prices on one without" taggedAs (
    UnitTest
  ) in {
    val eth = rows(TuiFixtures.observedSepolia()).find(_.contains(" Fees ")).value
    eth should include("base 1")
    (eth should include).regex("tips 1\\.00 gwei / 1\\.\\d0 gwei / 3\\.00 gwei \\(p10 / p50 / p90\\)")
    val etc = rows(TuiFixtures.observedEtc()).find(_.contains(" Fees ")).value
    etc should include("gas price 1.00 gwei / ")
    (etc should not).include("base")
    ObservatoryPanels.gwei(BigInt(0)) shouldBe "0 gwei"
    ObservatoryPanels.gwei(BigInt(12_400_000_000L)) shouldBe "12.4 gwei"
    ObservatoryPanels.gwei(BigInt(1_500_000_000L)) shouldBe "1.50 gwei"
    ObservatoryPanels.gwei(BigInt(4_000_000L)) shouldBe "0.004 gwei"
    ObservatoryPanels.gwei(BigInt(900L)) shouldBe "900 wei"
    ObservatoryPanels.gwei(BigInt("1234000000000")) shouldBe "1,234 gwei"
    ObservatoryPanels.magnitude(BigInt("412600000000000")) shouldBe "412.6T"
    ObservatoryPanels.magnitude(BigInt(8_000_000)) shouldBe "8.0M"
    ObservatoryPanels.magnitude(BigInt(310)) shouldBe "310"
    ObservatoryPanels.magnitude(BigInt(999_960)) shouldBe "1.0M"
    ObservatoryPanels.blockSize(940) shouldBe "940 B"
    ObservatoryPanels.blockSize(2_100) shouldBe "2.1 kB"
    ObservatoryPanels.blockSize(1_400_000) shouldBe "1.4 MB"
  }

  "The reorgs" should "be listed with their depth and both tips, and a depth that is a lower bound said so" taggedAs (
    UnitTest
  ) in {
    val eth = rows(TuiFixtures.observedSepolia())
    eth.find(_.contains(" Reorgs ")).value should include("1 since the TUI started")
    eth.exists(r => r.contains("depth 2") && r.contains("0xdead")) shouldBe true
    rows(TuiFixtures.observedSyncingEtc()).exists(_.contains("depth 64 or more")) shouldBe true
    rows(TuiFixtures.observedEtc()).find(_.contains(" Reorgs ")).value should include("none since the TUI started")
  }

  "A fork a network started with" should "be said to be active since genesis, not since 1970" taggedAs (UnitTest) in {
    // Ephemery's forks are all at time 0.
    val atGenesis = TuiFixtures.chainSepolia.copy(forks = TuiFixtures.chainSepolia.forks.map(_.copy(at = 0L)))
    atGenesis.since(atGenesis.forks.last) shouldBe "since genesis"
    TuiFixtures.chainSepolia.since(TuiFixtures.chainSepolia.forks.head) should startWith("since 20")
    TuiFixtures.chainEtc.since(ForkPoint("Frontier", 0L)) shouldBe "since genesis"
    val shown = rows(TuiFixtures.sepoliaNearHead().copy(chain = Some(atGenesis)), 80, 24).mkString("\n")
    shown should include("since genesis")
    (shown should not).include("1970")
    // And in the timeline, a fork a row (the terminal gate of slice 3 found 1970 still there).
    val timeline = rows(TuiFixtures.syncedEtc().copy(chain = Some(atGenesis)), 160, 48).mkString("\n")
    timeline should include("Shanghai")
    timeline should include("at genesis")
    (timeline should not).include("1970")
  }

  "A node whose blocks are not read" should "draw spec 027's Chain view" taggedAs (UnitTest) in {
    val plain = rows(TuiFixtures.syncedEtc()).mkString("\n")
    (plain should not).include(" Head ")
    (plain should not).include(" Blocks ")
    plain should include("Forks")
  }
