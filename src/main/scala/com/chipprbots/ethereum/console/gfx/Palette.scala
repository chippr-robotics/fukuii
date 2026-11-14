package com.chipprbots.ethereum.console.gfx

/** The colours a sprite or a chart may use (spec 029 FR-014, contracts/art.md): xterm-256 colours 16 to 255, the 6 × 6
  * × 6 cube and 24 greys. Every 256-colour terminal draws them the same, so a half-block cell shows exactly the colour
  * a pixel image does (research §3 E2). Colours 0 to 15 are left out: terminals let users redefine them.
  *
  * A pixel is a palette index; index 0 is transparent.
  */
object Palette:

  val Transparent: Int = 0

  /** The first and the last colour index. */
  val First: Int = 16
  val Last: Int = 255

  private val Cube: Array[Int] = Array(0, 95, 135, 175, 215, 255)

  /** `index` as `0xRRGGBB`. */
  def rgb(index: Int): Int =
    require(index >= First && index <= Last, s"not a sprite colour: $index")
    if index <= 231 then
      val n = index - 16
      (Cube(n / 36) << 16) | (Cube((n / 6) % 6) << 8) | Cube(n % 6)
    else
      val v = 8 + 10 * (index - 232)
      (v << 16) | (v << 8) | v

  private val byRgb: Map[Int, Int] = (First to Last).map(i => rgb(i) -> i).toMap

  /** The index of the colour `0xRRGGBB`, if it is one of the 240. */
  def indexOf(rgb: Int): Option[Int] = byRgb.get(rgb & 0xffffff)
