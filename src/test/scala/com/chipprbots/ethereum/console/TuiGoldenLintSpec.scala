package com.chipprbots.ethereum.console

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

import scala.jdk.CollectionConverters.*

import org.jline.utils.AttributedString
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

/** Every golden frame read for what a cut row looks like (prism, slice 3 review): a row the frame was too narrow for is
  * cut one column early with the ellipsis, so no content row reaches the last column, no bracket is left open, and the
  * Node row keeps its mining flag at every width.
  */
class TuiGoldenLintSpec extends AnyFlatSpec with Matchers:

  private val dir = Paths.get("src", "test", "resources", "tui", "golden")

  private val goldens: Seq[(String, Seq[String])] =
    Files
      .list(dir)
      .iterator()
      .asScala
      .filter(_.toString.endsWith(".txt"))
      .toSeq
      .sortBy(_.getFileName.toString)
      .map(p =>
        p.getFileName.toString -> Files
          .readString(p, StandardCharsets.UTF_8)
          .stripSuffix("\n")
          .split("\n")
          .toSeq
          .map(withoutSidebar)
      )

  /** A body row of a frame with the sidebar (spec 029 FR-006) starts with its columns and the divider; what the panels
    * drew is what follows. The rules below are the panels'.
    */
  private def withoutSidebar(row: String): String =
    val divider = widgets.Sidebar.Cols
    if row.length > divider && (row.charAt(divider) == '│' || row.charAt(divider) == '|') then row.drop(divider + 1)
    else row

  /** A separator: drawn to the edge on purpose. */
  private def border(row: String): Boolean = row.nonEmpty && row.forall(ch => ch == '─' || ch == '-')

  "The golden set" should "be there to lint" taggedAs (UnitTest) in {
    goldens.size should be > 90
  }

  "Every golden row" should "close every bracket it opens" taggedAs (UnitTest) in {
    for (name, rows) <- goldens; (row, i) <- rows.zipWithIndex do
      withClue(s"$name row $i: '$row'\n")(row.count(_ == '(') shouldBe row.count(_ == ')'))
  }

  it should "leave the last column blank, unless it is a separator" taggedAs (UnitTest) in {
    for (name, rows) <- goldens; (row, i) <- rows.zipWithIndex if !border(row) && row.nonEmpty do
      val width = new AttributedString(row).columnLength()
      val last = new AttributedString(row).columnSubSequence(width - 1, width).toString
      withClue(s"$name row $i: '$row'\n")(last shouldBe " ")
  }

  it should "never cut a number: no ellipsis right after a digit or a comma" taggedAs (UnitTest) in {
    // The hex ids the TUI shortens on purpose, in the three shapes it draws: a peer's (0x3fa1…e2), a block hash
    // (0x7b3e…a91c) and the coinbase (0x4f2a0b9c…c91e). Each is a name, not a number, and shows both of its ends.
    val shortId =
      ("0x(?:[0-9a-f]{4}(?:…|\\.\\.)[0-9a-f]{2}|[0-9a-f]{4}(?:…|\\.\\.)[0-9a-f]{4}|[0-9a-f]{8}(?:…|\\.\\.)[0-9a-f]{4})(?![0-9a-f])").r
    val cutNumber = "[0-9,](…|\\.\\.)".r
    for (name, rows) <- goldens; (row, i) <- rows.zipWithIndex do
      withClue(s"$name row $i: '$row'\n")(cutNumber.findFirstIn(shortId.replaceAllIn(row, "")) shouldBe None)
  }

  "The Node row" should "end with the mining flag at every width" taggedAs (UnitTest) in {
    val nodeRows = for (name, rows) <- goldens; row <- rows if row.startsWith(" Node    ") yield name -> row
    nodeRows should not be empty
    for (name, row) <- nodeRows do
      withClue(s"$name: '$row'\n")(row.trim should (endWith("mining on").or(endWith("mining off"))))
  }
