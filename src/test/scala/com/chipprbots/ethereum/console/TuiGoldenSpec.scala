package com.chipprbots.ethereum.console

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import scala.io.Source
import scala.util.Using

import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

/** Golden frames of the Overview (spec 027 T013, SC-002, SC-007): the synced, SNAP and regular-sync fixtures at 80×24,
  * 120×40 and 160×48, in both glyph sets, compared on the visible text.
  *
  * The files under `src/test/resources/tui/golden/` are generated, never edited by hand. To rewrite them after an
  * intended change, run with the system property in the forked test JVM and review the diff:
  * {{{
  * sbt 'set Test / javaOptions += "-Dtui.golden.update=true"' "testOnly *TuiGoldenSpec"
  * }}}
  */
class TuiGoldenSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  private val update = sys.props.get("tui.golden.update").contains("true")
  private val sourceDir: Path = Paths.get("src", "test", "resources", "tui", "golden")

  private val tt = new TuiTestTerminal()
  override def afterAll(): Unit = tt.close()

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

  /** The frame as a terminal shows it: rendered to escape sequences for an xterm, then stripped back to its text. */
  private def visibleFrame(s: TuiState, width: Int, height: Int, theme: TuiTheme): String =
    TuiTestTerminal.visible(renderer.render(s, width, height, theme).map(_.toAnsi(tt.terminal)).mkString("\r\n"))

  private def golden(name: String): Option[String] =
    Option(getClass.getResourceAsStream(s"/tui/golden/$name")).map(in =>
      Using.resource(Source.fromInputStream(in, StandardCharsets.UTF_8.name()))(_.mkString)
    )

  "The golden set" should "cover three tiers, three states and two glyph sets: 18 files" taggedAs (UnitTest) in {
    val names = for (w, h) <- sizes; (s, _) <- states; set <- GlyphSet.values.toSeq yield fileName(w, h, s, set)
    names.distinct should have size 18
    sizes.map(Tier.of) shouldBe Seq(Tier.S, Tier.M, Tier.L)
  }

  for (width, height) <- sizes; (stateName, fixture) <- states; set <- GlyphSet.values do
    val name = fileName(width, height, stateName, set)

    "The Overview" should s"match $name" taggedAs (UnitTest) in {
      val frame = visibleFrame(fixture(set), width, height, TuiTheme.Ansi16)
      val rows = frame.split("\n", -1)
      rows should have size height.toLong
      all(rows.map(r => org.jline.utils.AttributedString(r).columnLength())) shouldBe width

      if update then
        Files.createDirectories(sourceDir)
        Files.writeString(sourceDir.resolve(name), frame + "\n", StandardCharsets.UTF_8)
        info(s"wrote ${sourceDir.toAbsolutePath.resolve(name)}")
      else
        golden(name) match
          case None =>
            fail(s"missing golden file tui/golden/$name; generate it with -Dtui.golden.update=true (see the scaladoc)")
          case Some(expected) => frame shouldBe expected.stripSuffix("\n")
    }

    it should s"carry no colour attribute with the monochrome theme ($name)" taggedAs (UnitTest) in {
      val lines = renderer.render(fixture(set), width, height, TuiTheme.Mono)
      TuiTestTerminal.colourCodes(lines.map(_.toAnsi(tt.terminal)).mkString("\r\n")) shouldBe empty
      // The same text as the coloured frame: the theme changes styles, never the layout.
      visibleFrame(fixture(set), width, height, TuiTheme.Mono) shouldBe
        visibleFrame(fixture(set), width, height, TuiTheme.Ansi16)
    }
