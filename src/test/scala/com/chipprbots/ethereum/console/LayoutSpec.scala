package com.chipprbots.ethereum.console

import org.jline.utils.AttributedString
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

/** The panel layout engine (spec 027 FR-005, research §7.3, T059). */
class LayoutSpec extends AnyFlatSpec with Matchers:

  private def text(rows: String*): Seq[AttributedString] = rows.map(new AttributedString(_))

  /** A panel that draws `rows` lines named after it, numbered. */
  private def panel(
      name: String,
      min: Int,
      preferred: Int,
      priority: Int,
      fill: Boolean = false,
      separated: Boolean = false
  ): Panel =
    Panel(
      min,
      preferred,
      priority,
      (_, rows) => (1 to rows).map(i => new AttributedString(s"$name$i")),
      separated,
      fill
    )

  private val separator = new AttributedString("-" * 10)

  "Layout.allocate" should "give each panel its minimum rows in priority order, dropping a panel that does not fit" taggedAs (
    UnitTest
  ) in {
    val panels = Seq(panel("a", 2, 4, priority = 2), panel("b", 3, 3, priority = 1), panel("c", 2, 2, priority = 3))
    Layout.allocate(panels, 5) shouldBe Seq(2, 3, 0) // b first, then a; c no longer fits
    Layout.allocate(panels, 4) shouldBe Seq(0, 3, 0) // a's minimum is 2; one row left
    Layout.allocate(panels, 0) shouldBe Seq(0, 0, 0)
  }

  it should "then give preferred rows, in priority order" taggedAs (UnitTest) in {
    val panels = Seq(panel("a", 2, 4, priority = 2), panel("b", 1, 3, priority = 1), panel("c", 2, 2, priority = 3))
    Layout.allocate(panels, 6) shouldBe Seq(2, 2, 2) // minimums 5, then b grows by the one row left
    Layout.allocate(panels, 9) shouldBe Seq(4, 3, 2) // everyone preferred
    Layout.allocate(panels, 20) shouldBe Seq(4, 3, 2) // no fill panel: the rest is padding
  }

  it should "let the fill panel absorb the rest" taggedAs (UnitTest) in {
    val panels =
      Seq(panel("a", 1, 2, priority = 1), panel("logs", 0, 0, priority = 9, fill = true), panel("b", 1, 1, 2))
    Layout.allocate(panels, 3) shouldBe Seq(2, 0, 1)
    Layout.allocate(panels, 10) shouldBe Seq(2, 7, 1)
  }

  it should "count a separator row above a separated panel" taggedAs (UnitTest) in {
    val panels = Seq(panel("a", 1, 1, priority = 1), panel("b", 2, 2, priority = 2, separated = true))
    Layout.allocate(panels, 3) shouldBe Seq(1, 0) // b needs 2 + its separator
    Layout.allocate(panels, 4) shouldBe Seq(1, 2)
  }

  "Layout.render" should "draw the panels in display order, a separator above each separated one, padded to rows" taggedAs (
    UnitTest
  ) in {
    val panels = Seq(panel("a", 1, 2, priority = 1), panel("b", 1, 1, priority = 2, separated = true))
    Layout.render(panels, 10, 6, separator).map(_.toString.trim) shouldBe Seq("a1", "a2", "-" * 10, "b1", "", "")
  }

  it should "draw exactly rows lines of exactly width columns, whatever a panel draws" taggedAs (UnitTest) in {
    val unruly = Seq(
      Panel(2, 2, 1, (_, _) => text("one line only")), // too few
      Panel(1, 1, 2, (_, _) => text("too", "many", "lines")), // too many
      Panel(1, 3, 3, (_, _) => text("🪱" * 40, "中文字符" * 10, "x" * 300)), // too wide, wide glyphs
      Panel(0, 0, 9, (_, rows) => Seq.fill(rows + 5)(new AttributedString("fill")), fill = true)
    )
    for width <- Seq(1, 7, 19, 80, 160); rows <- 0 to 30 do
      val lines = Layout.render(unruly, width, rows, separator)
      withClue(s"${width}x$rows: ") {
        lines should have size rows.toLong
        all(lines.map(_.columnLength())) shouldBe width
      }
  }

  "Layout.columns" should "zip two row lists, pad the shorter side, and measure each cell by columnLength" taggedAs (
    UnitTest
  ) in {
    val left = text("🪱🧠 worm", "中文", "plain")
    val right = text("R1")
    val divider = new AttributedString("│")
    val rows = Layout.columns(left, right, leftCols = 12, rightCols = 6, divider)
    rows should have size 3
    all(rows.map(_.columnLength())) shouldBe 12 + 1 + 6
    rows.map(_.toString) shouldBe Seq("🪱🧠 worm   │R1    ", "中文        │      ", "plain       │      ")
    // The right side longer: the left is padded.
    Layout.columns(text("L"), text("a", "b"), 3, 2, divider).map(_.toString) shouldBe Seq("L  │a ", "   │b ")
  }
