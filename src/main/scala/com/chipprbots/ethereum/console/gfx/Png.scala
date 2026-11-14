package com.chipprbots.ethereum.console.gfx

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32
import java.util.zip.Inflater

import scala.util.control.NonFatal

/** Reads the one kind of PNG a sprite sheet is (spec 029 contracts/art.md): 8 bits a channel, indexed colour with
  * optional transparency or RGBA, not interlaced. Anything else is refused with a reason.
  *
  * It is here, and about a hundred lines, so that a node does not load `java.awt` to read a picture (plan, Decisions).
  * It reads nothing it does not need: no ancillary chunk but `tRNS`, no gamma, no text.
  */
object Png:

  /** A decoded image: `width × height` pixels as `0xAARRGGBB`, row by row. */
  final case class Image(width: Int, height: Int, argb: Array[Int])

  /** The largest side read: a sheet is at most 1,024 by 1,792. */
  val MaxSide: Int = 4096

  private val Signature: Array[Byte] = Array(0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n').map(_.toByte)

  /** The image in `bytes`.
    *
    * @param size
    *   the width and height it must have, if they are known: one of any other size is refused at its header, before a
    *   pixel of it is decoded or a buffer for it allocated
    */
  def read(bytes: Array[Byte], size: Option[(Int, Int)] = None): Either[String, Image] =
    try decode(bytes, size)
    catch case NonFatal(e) => Left(s"not a readable PNG: ${e.getClass.getSimpleName}: ${e.getMessage}")

  private def decode(bytes: Array[Byte], size: Option[(Int, Int)]): Either[String, Image] =
    if bytes.length < 8 || !bytes.take(8).sameElements(Signature) then Left("not a PNG")
    else
      val in = ByteBuffer.wrap(bytes, 8, bytes.length - 8)
      var header: Option[(Int, Int, Int)] = None // width, height, colour type
      var palette: Array[Int] = Array.empty
      var alpha: Array[Int] = Array.empty
      val data = new ByteArrayOutputStream()
      var problem: Option[String] = None
      var ended = false
      while problem.isEmpty && !ended && in.remaining() >= 12 do
        val length = in.getInt
        val typeBytes = new Array[Byte](4)
        in.get(typeBytes)
        val kind = new String(typeBytes, StandardCharsets.US_ASCII)
        if length < 0 || length > in.remaining() - 4 then problem = Some(s"chunk $kind is cut short")
        else
          val body = new Array[Byte](length)
          in.get(body)
          val crc = new CRC32()
          crc.update(typeBytes)
          crc.update(body)
          if crc.getValue.toInt != in.getInt then problem = Some(s"chunk $kind fails its checksum")
          else
            kind match
              case "IHDR" =>
                val b = ByteBuffer.wrap(body)
                val (w, h) = (b.getInt, b.getInt)
                val (depth, colour, _, _, interlace) = (b.get, b.get, b.get, b.get, b.get)
                if w <= 0 || h <= 0 || w > MaxSide || h > MaxSide then
                  problem = Some(s"is ${w}x$h: a side is over $MaxSide")
                else if size.exists(_ != (w, h)) then
                  problem = size.map((mustW, mustH) => s"is ${w}x$h, must be ${mustW}x$mustH")
                else if depth != 8 then problem = Some(s"has $depth bits a channel, not 8")
                else if colour != 3 && colour != 6 then
                  problem = Some(s"is colour type $colour, not indexed (3) or RGBA (6)")
                else if interlace != 0 then problem = Some("is interlaced")
                else header = Some((w, h, colour.toInt))
              case "PLTE" =>
                palette = body.grouped(3).map(c => ((c(0) & 0xff) << 16) | ((c(1) & 0xff) << 8) | (c(2) & 0xff)).toArray
              case "tRNS" => alpha = body.map(_ & 0xff)
              case "IDAT" => data.write(body)
              case "IEND" => ended = true
              case _      => ()
      (problem, header) match
        case (Some(p), _) => Left(p)
        case (None, None) => Left("has no header")
        case (None, Some((w, h, colour))) =>
          if !ended then Left("is cut short")
          else
            unfilter(
              inflate(data.toByteArray, h * (1 + w * (if colour == 3 then 1 else 4))),
              w,
              h,
              colour,
              palette,
              alpha
            )

  private def inflate(compressed: Array[Byte], expected: Int): Array[Byte] =
    val inflater = new Inflater()
    try
      inflater.setInput(compressed)
      val out = new Array[Byte](expected)
      var n = 0
      while n < expected && !inflater.finished() do
        val read = inflater.inflate(out, n, expected - n)
        if read == 0 && (inflater.needsInput() || inflater.needsDictionary()) then n = expected + 1 // cut short
        else n += read
      if n != expected then throw new IllegalArgumentException("its pixel data is not the size its header says")
      out
    finally inflater.end()

  /** Undoes the row filters (None, Sub, Up, Average, Paeth) and turns the samples into pixels. */
  private def unfilter(
      raw: Array[Byte],
      w: Int,
      h: Int,
      colour: Int,
      palette: Array[Int],
      alpha: Array[Int]
  ): Either[String, Image] =
    val bpp = if colour == 3 then 1 else 4
    val stride = w * bpp
    val rows = new Array[Byte](h * stride)
    var bad: Option[String] = None
    var y = 0
    while y < h && bad.isEmpty do
      val filter = raw(y * (stride + 1)) & 0xff
      var x = 0
      while x < stride do
        val value = raw(y * (stride + 1) + 1 + x) & 0xff
        val a = if x >= bpp then rows(y * stride + x - bpp) & 0xff else 0
        val b = if y > 0 then rows((y - 1) * stride + x) & 0xff else 0
        val c = if x >= bpp && y > 0 then rows((y - 1) * stride + x - bpp) & 0xff else 0
        val predicted = filter match
          case 0 => 0
          case 1 => a
          case 2 => b
          case 3 => (a + b) / 2
          case 4 =>
            val p = a + b - c
            val (pa, pb, pc) = (math.abs(p - a), math.abs(p - b), math.abs(p - c))
            if pa <= pb && pa <= pc then a else if pb <= pc then b else c
          case other =>
            bad = Some(s"has a row filter it cannot have: $other")
            0
        rows(y * stride + x) = (value + predicted).toByte
        x += 1
      y += 1
    bad match
      case Some(reason) => Left(reason)
      case None =>
        val argb = new Array[Int](w * h)
        var i = 0
        var missing = false
        while i < w * h do
          if colour == 3 then
            val index = rows(i) & 0xff
            if index >= palette.length then missing = true
            else argb(i) = ((if index < alpha.length then alpha(index) else 0xff) << 24) | palette(index)
          else
            val o = i * 4
            argb(i) = ((rows(o + 3) & 0xff) << 24) | ((rows(o) & 0xff) << 16) | ((rows(o + 1) & 0xff) << 8) | (rows(
              o + 2
            ) & 0xff)
          i += 1
        if missing then Left("uses a palette entry it does not have") else Right(Image(w, h, argb))
