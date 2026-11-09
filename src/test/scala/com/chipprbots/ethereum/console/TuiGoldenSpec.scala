package com.chipprbots.ethereum.console

import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

/** Golden frames of the Overview (spec 027 T013, SC-002, SC-007): the synced, SNAP and regular-sync fixtures at 80×24,
  * 120×40 and 160×48, in both glyph sets, compared on the visible text.
  *
  * The files under `src/test/resources/tui/golden/` are generated, never edited by hand ([[TuiGoldenFiles]] says how to
  * rewrite them). Each frame is at tick 0 under full motion, so a worm that ripples shows its ripple behind the worm.
  */
class TuiGoldenSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll with TuiGoldenFiles:

  protected val goldenTerminal = new TuiTestTerminal()
  override def afterAll(): Unit = goldenTerminal.close()

  private val renderer = TuiRenderer(TuiConfig.default)

  private val states: Seq[(String, GlyphSet => TuiState)] = Seq(
    "synced" -> (g => TuiFixtures.syncedEtc(g)),
    "snap-storage" -> (g => TuiFixtures.snapStorageMordor(g)),
    "regular" -> (g => TuiFixtures.regularSyncEtc(g))
  )
  private val sizes: Seq[(Int, Int)] = Seq((80, 24), (120, 40), (160, 48))

  private def glyphsName(set: GlyphSet): String = set.toString.toLowerCase

  private def fileName(width: Int, height: Int, state: String, set: GlyphSet): String =
    s"overview-${Tier.of(width, height).toString.toLowerCase}-$state-${glyphsName(set)}.txt"

  private def frameText(s: TuiState, width: Int, height: Int, theme: TuiTheme): String =
    visibleFrame(renderer.render(s, width, height, theme))

  "The golden set" should "cover three tiers, three states and two glyph sets: 18 files" taggedAs (UnitTest) in {
    val names = for (w, h) <- sizes; (s, _) <- states; set <- GlyphSet.values.toSeq yield fileName(w, h, s, set)
    names.distinct should have size 18
    sizes.map(Tier.of) shouldBe Seq(Tier.S, Tier.M, Tier.L)
  }

  for (width, height) <- sizes; (stateName, fixture) <- states; set <- GlyphSet.values do
    val name = fileName(width, height, stateName, set)

    "The Overview" should s"match $name" taggedAs (UnitTest) in {
      val frame = frameText(fixture(set), width, height, TuiTheme.Ansi16)
      val rows = frame.split("\n", -1)
      rows should have size height.toLong
      all(rows.map(r => org.jline.utils.AttributedString(r).columnLength())) shouldBe width

      matchGolden(name, frame)
    }

    it should s"carry no colour attribute with the monochrome theme ($name)" taggedAs (UnitTest) in {
      val lines = renderer.render(fixture(set), width, height, TuiTheme.Mono)
      TuiTestTerminal.colourCodes(lines.map(_.toAnsi(goldenTerminal.terminal)).mkString("\r\n")) shouldBe empty
      // The same text as the coloured frame: the theme changes styles, never the layout.
      frameText(fixture(set), width, height, TuiTheme.Mono) shouldBe
        frameText(fixture(set), width, height, TuiTheme.Ansi16)
    }
