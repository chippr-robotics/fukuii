package com.chipprbots.ethereum.console

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import scala.io.Source
import scala.util.Using

import org.jline.utils.AttributedString
import org.scalatest.Assertions

/** Golden frames under `src/test/resources/tui/golden/` (spec 027 research §8.1): compared on the visible text, written
  * instead when the forked test JVM has `-Dtui.golden.update=true`:
  * {{{
  * sbt 'set Test / javaOptions += "-Dtui.golden.update=true"' "testOnly com.chipprbots.ethereum.console.*"
  * }}}
  * The files are generated, never edited by hand; review the diff of a regenerated file, since a changed golden is a
  * changed screen.
  */
trait TuiGoldenFiles extends Assertions:

  /** An in-memory xterm, to turn a frame into the escape sequences a terminal gets. Closed by the spec. */
  protected def goldenTerminal: TuiTestTerminal

  private val update = sys.props.get("tui.golden.update").contains("true")
  private val sourceDir: Path = Paths.get("src", "test", "resources", "tui", "golden")

  /** The frame as a terminal shows it: rendered to escape sequences for an xterm, then stripped back to its text. */
  protected def visibleFrame(lines: Seq[AttributedString]): String =
    TuiTestTerminal.visible(lines.map(_.toAnsi(goldenTerminal.terminal)).mkString("\r\n"))

  private def golden(name: String): Option[String] =
    Option(getClass.getResourceAsStream(s"/tui/golden/$name")).map(in =>
      Using.resource(Source.fromInputStream(in, StandardCharsets.UTF_8.name()))(_.mkString)
    )

  /** Fails naming every row that differs (up to five shown), with the expected and the actual row. */
  private def compareRows(name: String, expected: Seq[String], actual: Seq[String]): Unit =
    val differing = (0 until expected.size.max(actual.size)).filter(i => expected.lift(i) != actual.lift(i))
    if differing.nonEmpty then
      def show(row: Option[String]) = row.fold("<no row>")(r => s"'$r'")
      val shown = differing.take(5).map { i =>
        s"  row $i\n    expected: ${show(expected.lift(i))}\n    actual:   ${show(actual.lift(i))}"
      }
      fail(
        s"$name: ${differing.size} row(s) differ (expected ${expected.size} rows, drew ${actual.size}):\n" +
          shown.mkString("\n")
      )

  /** Compares `frame` (visible text, rows joined by `\n`) with the golden file `name`, or writes it. */
  protected def matchGolden(name: String, frame: String): Unit =
    if update then
      Files.createDirectories(sourceDir)
      Files.writeString(sourceDir.resolve(name), frame + "\n", StandardCharsets.UTF_8)
    else
      golden(name) match
        case None =>
          fail(s"missing golden file tui/golden/$name; generate it with -Dtui.golden.update=true (see TuiGoldenFiles)")
        case Some(expected) =>
          compareRows(name, expected.stripSuffix("\n").split("\n", -1).toSeq, frame.split("\n", -1).toSeq)
