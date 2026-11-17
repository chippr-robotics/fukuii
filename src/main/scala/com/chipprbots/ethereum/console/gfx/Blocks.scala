package com.chipprbots.ethereum.console.gfx

import org.jline.utils.AttributedString
import org.jline.utils.AttributedStringBuilder
import org.jline.utils.AttributedStyle

/** Pixels as character cells (spec 029 FR-011, research §5.1): a cell is two pixels, one above the other, drawn with
  * the upper-half block, its foreground the upper pixel and its background the lower. A cell is twice as tall as it is
  * wide on most terminals, so the pixels come out square.
  *
  * Colours are written as palette indices, which every 256-colour terminal draws exactly (research §3 E2). A
  * transparent pixel leaves the cell's own background.
  */
object Blocks:

  private val Upper = "▀"
  private val Lower = "▄"

  /** `buffer` in `cols × rows` cells: `cols` pixels across, `2 × rows` down, from its top-left corner. What the buffer
    * does not cover is blank.
    */
  def render(buffer: PixelBuffer, cols: Int, rows: Int): Seq[AttributedString] =
    (0 until rows.max(0)).map { row =>
      val sb = new AttributedStringBuilder()
      var col = 0
      while col < cols do
        val top = buffer(col, row * 2)
        val bottom = buffer(col, row * 2 + 1)
        if top == Palette.Transparent && bottom == Palette.Transparent then sb.append(" ")
        else if bottom == Palette.Transparent then sb.append(Upper, AttributedStyle.DEFAULT.foreground(top))
        else if top == Palette.Transparent then sb.append(Lower, AttributedStyle.DEFAULT.foreground(bottom))
        else sb.append(Upper, AttributedStyle.DEFAULT.foreground(top).background(bottom))
        col += 1
      sb.toAttributedString
    }
