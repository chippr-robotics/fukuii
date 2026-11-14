package com.chipprbots.ethereum.console.gfx

import java.util.Arrays

/** A rectangle of palette indices (spec 029 data-model): a sprite frame, a sheet, a chart. Immutable; two buffers with
  * the same pixels are equal, so a frame's graphics compare by content.
  *
  * @param pixels
  *   `width × height` indices, row by row; [[Palette.Transparent]] or a colour of [[Palette]]. Not copied: the caller
  *   hands the array over
  */
final class PixelBuffer private (val width: Int, val height: Int, private val pixels: Array[Byte]):

  /** The pixel at (x, y); transparent outside the buffer. */
  def apply(x: Int, y: Int): Int =
    if x < 0 || y < 0 || x >= width || y >= height then Palette.Transparent else pixels(y * width + x) & 0xff

  /** The `w × h` rectangle whose top-left corner is (x, y); what lies outside this buffer is transparent. */
  def crop(x: Int, y: Int, w: Int, h: Int): PixelBuffer =
    val out = new Array[Byte](w.max(0) * h.max(0))
    var row = 0
    while row < h do
      var col = 0
      while col < w do
        out(row * w + col) = apply(x + col, y + row).toByte
        col += 1
      row += 1
    new PixelBuffer(w.max(0), h.max(0), out)

  /** Every pixel as a `k × k` square: the whole-factor enlargement that keeps pixel art sharp (FR-015). */
  def enlarge(k: Int): PixelBuffer =
    require(k >= 1, s"a factor below 1: $k")
    if k == 1 then this
    else
      val out = new Array[Byte](width * k * height * k)
      var y = 0
      while y < height * k do
        var x = 0
        while x < width * k do
          out(y * width * k + x) = pixels((y / k) * width + x / k)
          x += 1
        y += 1
      new PixelBuffer(width * k, height * k, out)

  /** The palette indices, row by row: a copy. */
  def indices: Array[Byte] = pixels.clone()

  /** Whether any pixel is a colour. */
  def nonEmpty: Boolean = pixels.exists(_ != 0)

  /** The pixels as RGBA bytes, a transparent pixel as four zeros: what a terminal is sent. */
  def rgba: Array[Byte] =
    val out = new Array[Byte](pixels.length * 4)
    var i = 0
    while i < pixels.length do
      val index = pixels(i) & 0xff
      if index != Palette.Transparent then
        val c = Palette.rgb(index)
        out(i * 4) = (c >> 16).toByte
        out(i * 4 + 1) = (c >> 8).toByte
        out(i * 4 + 2) = c.toByte
        out(i * 4 + 3) = 0xff.toByte
      i += 1
    out

  override lazy val hashCode: Int = 31 * (31 * width + height) + Arrays.hashCode(pixels)

  override def equals(other: Any): Boolean = other match
    case b: PixelBuffer => (b eq this) || (b.width == width && b.height == height && Arrays.equals(b.pixels, pixels))
    case _              => false

  override def toString: String = s"PixelBuffer(${width}x$height)"

object PixelBuffer:

  /** A buffer of `width × height` from `pixels`, which must hold exactly that many indices, each transparent or a
    * colour of [[Palette]].
    */
  def apply(width: Int, height: Int, pixels: Array[Byte]): PixelBuffer =
    require(
      width >= 0 && height >= 0 && pixels.length == width * height,
      s"${pixels.length} pixels for ${width}x$height"
    )
    require(pixels.forall(p => p == 0 || (p & 0xff) >= Palette.First), "a pixel that is not a sprite colour")
    new PixelBuffer(width, height, pixels)

  /** A transparent buffer. */
  def empty(width: Int, height: Int): PixelBuffer = new PixelBuffer(width, height, new Array[Byte](width * height))

  /** A buffer drawn from rows of characters, one character a pixel, `colours` giving each character's index; any other
    * character is transparent. For tests and small fixed art.
    */
  def of(rows: Seq[String], colours: Map[Char, Int]): PixelBuffer =
    val width = rows.map(_.length).maxOption.getOrElse(0)
    val out = new Array[Byte](width * rows.size)
    for (row, y) <- rows.zipWithIndex; (ch, x) <- row.zipWithIndex do
      out(y * width + x) = colours.getOrElse(ch, 0).toByte
    apply(width, rows.size, out)
