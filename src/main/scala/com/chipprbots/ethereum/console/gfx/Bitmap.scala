package com.chipprbots.ethereum.console.gfx

import java.util.Arrays

/** A rectangle of full-colour pixels (contracts/art.md): a `hi` sheet and its frames, which pixel mode sends as they
  * are. Each pixel is `0xAARRGGBB` with straight alpha, so a soft edge blends over whatever the terminal shows behind
  * it. Immutable; two bitmaps with the same pixels are equal.
  *
  * @param pixels
  *   `width × height` pixels, row by row. Not copied: the caller hands the array over
  */
final class Bitmap private (val width: Int, val height: Int, private val pixels: Array[Int]):

  /** The pixel at (x, y), `0xAARRGGBB`; transparent outside the bitmap. */
  def apply(x: Int, y: Int): Int =
    if x < 0 || y < 0 || x >= width || y >= height then 0 else pixels(y * width + x)

  /** The `w × h` rectangle whose top-left corner is (x, y); what lies outside this bitmap is transparent. */
  def crop(x: Int, y: Int, w: Int, h: Int): Bitmap =
    val out = new Array[Int](w.max(0) * h.max(0))
    var row = 0
    while row < h do
      var col = 0
      while col < w do
        out(row * w + col) = apply(x + col, y + row)
        col += 1
      row += 1
    new Bitmap(w.max(0), h.max(0), out)

  /** Every pixel as a `k × k` square: how pixel art is enlarged and stays sharp (FR-015). */
  def enlarge(k: Int): Bitmap =
    require(k >= 1, s"a factor below 1: $k")
    if k == 1 then this
    else
      val out = new Array[Int](width * k * height * k)
      var y = 0
      while y < height * k do
        var x = 0
        while x < width * k do
          out(y * width * k + x) = pixels((y / k) * width + x / k)
          x += 1
        y += 1
      new Bitmap(width * k, height * k, out)

  /** This bitmap drawn at `w × h`, smoothly: each side shrunk by averaging the pixels a new one covers, or grown by
    * interpolating between neighbours. It works on premultiplied colour, so a transparent pixel lends no colour to the
    * soft edge beside it. How drawn art, as opposed to pixel art, is fitted to a terminal's cell.
    */
  def resize(w: Int, h: Int): Bitmap =
    require(w >= 1 && h >= 1, s"a size below 1: ${w}x$h")
    if w == width && h == height then this
    else
      val src = new Array[Float](pixels.length * 4)
      var i = 0
      while i < pixels.length do
        val p = pixels(i)
        val a = (p >>> 24) / 255f
        src(i * 4) = ((p >> 16) & 0xff) / 255f * a
        src(i * 4 + 1) = ((p >> 8) & 0xff) / 255f * a
        src(i * 4 + 2) = (p & 0xff) / 255f * a
        src(i * 4 + 3) = a
        i += 1
      val across = Bitmap.pass(src, width, height, w, horizontal = true)
      val down = Bitmap.pass(across, w, height, h, horizontal = false)
      val out = new Array[Int](w * h)
      i = 0
      while i < out.length do
        val a = down(i * 4 + 3)
        val alpha = Math.round(a * 255).max(0).min(255)
        if alpha > 0 then
          def channel(c: Float): Int = Math.round(c / a * 255).max(0).min(255)
          out(i) = (alpha << 24) | (channel(down(i * 4)) << 16) | (channel(down(i * 4 + 1)) << 8) |
            channel(down(i * 4 + 2))
        i += 1
      new Bitmap(w, h, out)

  /** The pixels, `0xAARRGGBB`, row by row: a copy. */
  def argb: Array[Int] = pixels.clone()

  /** Whether any pixel shows. */
  def nonEmpty: Boolean = pixels.exists(p => (p >>> 24) != 0)

  /** The pixels as RGBA bytes, what a terminal is sent. */
  def rgba: Array[Byte] =
    val out = new Array[Byte](pixels.length * 4)
    var i = 0
    while i < pixels.length do
      val p = pixels(i)
      out(i * 4) = (p >> 16).toByte
      out(i * 4 + 1) = (p >> 8).toByte
      out(i * 4 + 2) = p.toByte
      out(i * 4 + 3) = (p >>> 24).toByte
      i += 1
    out

  override lazy val hashCode: Int = 31 * (31 * width + height) + Arrays.hashCode(pixels)

  override def equals(other: Any): Boolean = other match
    case b: Bitmap => (b eq this) || (b.width == width && b.height == height && Arrays.equals(b.pixels, pixels))
    case _         => false

  override def toString: String = s"Bitmap(${width}x$height)"

object Bitmap:

  /** A bitmap of `width × height` from `argb`, which must hold exactly that many pixels. A pixel with no alpha is
    * stored as 0, whatever its colour, so two bitmaps that look the same are equal.
    */
  def apply(width: Int, height: Int, argb: Array[Int]): Bitmap =
    require(width >= 0 && height >= 0 && argb.length == width * height, s"${argb.length} pixels for ${width}x$height")
    var i = 0
    while i < argb.length do
      if (argb(i) >>> 24) == 0 then argb(i) = 0
      i += 1
    new Bitmap(width, height, argb)

  /** A transparent bitmap. */
  def empty(width: Int, height: Int): Bitmap = new Bitmap(width, height, new Array[Int](width * height))

  /** A buffer of palette indices in full colour: a chart, or pixel art, as pixel mode sends it. */
  def of(buffer: PixelBuffer): Bitmap =
    val out = new Array[Int](buffer.width * buffer.height)
    var y = 0
    while y < buffer.height do
      var x = 0
      while x < buffer.width do
        val index = buffer(x, y)
        if index != Palette.Transparent then out(y * buffer.width + x) = 0xff000000 | Palette.rgb(index)
        x += 1
      y += 1
    new Bitmap(buffer.width, buffer.height, out)

  /** For each of `m` new pixels along a side of `n`, the old pixels it is made of and how much each counts: the share
    * of it a new pixel covers when shrinking, the two nearest by distance when growing.
    */
  private def taps(n: Int, m: Int): Array[(Array[Int], Array[Float])] =
    Array.tabulate(m) { i =>
      if m < n then
        val span = n.toDouble / m
        val (from, to) = (i * span, (i + 1) * span)
        val covered = (from.toInt to (Math.ceil(to).toInt - 1).min(n - 1)).toArray
        (covered, covered.map(j => ((to.min(j + 1.0) - from.max(j.toDouble)) / span).toFloat))
      else
        val at = ((i + 0.5) * n / m - 0.5).max(0.0).min(n - 1.0)
        val j = at.toInt
        val t = (at - j).toFloat
        if j + 1 < n then (Array(j, j + 1), Array(1 - t, t)) else (Array(j), Array(1f))
    }

  /** One side of [[Bitmap.resize]]: `src`, `w × h` pixels of four floats, with one side made `size` long. */
  private def pass(src: Array[Float], w: Int, h: Int, size: Int, horizontal: Boolean): Array[Float] =
    val (outW, outH) = if horizontal then (size, h) else (w, size)
    val weights = taps(if horizontal then w else h, size)
    val out = new Array[Float](outW * outH * 4)
    var y = 0
    while y < outH do
      var x = 0
      while x < outW do
        val (from, share) = weights(if horizontal then x else y)
        val o = (y * outW + x) * 4
        var k = 0
        while k < from.length do
          val s = if horizontal then (y * w + from(k)) * 4 else (from(k) * w + x) * 4
          val f = share(k)
          out(o) += src(s) * f
          out(o + 1) += src(s + 1) * f
          out(o + 2) += src(s + 2) * f
          out(o + 3) += src(s + 3) * f
          k += 1
        x += 1
      y += 1
    out
