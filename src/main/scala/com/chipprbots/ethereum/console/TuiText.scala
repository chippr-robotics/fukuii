package com.chipprbots.ethereum.console

import java.util.Locale

import org.jline.utils.AttributedString
import org.jline.utils.AttributedStringBuilder
import org.jline.utils.WCWidth

/** Text helpers every TUI row uses: the frame's width rule, the number formats, and text from outside the node
  * ([[external]], over [[Sanitize]]).
  */
object TuiText:

  /** Exactly `width` columns: cut, then padded with spaces. A wide glyph that would straddle the edge is dropped by the
    * cut, which leaves the line one column short; the padding restores it. A line already `width` wide is returned as
    * it is, measured once.
    */
  def fitToWidth(line: AttributedString, width: Int): AttributedString =
    val cols = width.max(0)
    val length = line.columnLength()
    if length == cols then line
    else
      val (cut, cutLength) =
        if length > cols then
          val c = line.columnSubSequence(0, cols)
          (c, c.columnLength())
        else (line, length)
      if cutLength >= cols then cut
      else
        val sb = new AttributedStringBuilder()
        sb.append(cut)
        sb.append(" " * (cols - cutLength))
        sb.toAttributedString

  /** Exactly `width` columns, a cut shown: a line too long is cut and ends with the glyph set's ellipsis. A row keeps
    * its last column blank (`reserveLast`), so a row of exactly `width` counts as too long; a cell whose gap column is
    * already reserved by its caller may fill `width` exactly. A line that fits is padded, as [[fitToWidth]] does.
    */
  def fit(line: AttributedString, width: Int, g: TuiGlyphs, reserveLast: Boolean = true): AttributedString =
    val room = if reserveLast then width - 1 else width
    if line.columnLength() <= room || width <= g.ellipsis.cols + 1 then fitToWidth(line, width)
    else
      val sb = new AttributedStringBuilder()
      sb.append(fitToWidth(line, room - g.ellipsis.cols))
      sb.append(g.ellipsis.text)
      fitToWidth(sb.toAttributedString, width)

  /** 21,884,102 */
  def formatNumber(n: Long): String = String.format(Locale.ROOT, "%,d", n)

  /** 34.1M, 412.0K, 310: the compact counts of the log's SNAP progress line. */
  def formatCount(n: Long): String =
    if n >= 1_000_000 then String.format(Locale.ROOT, "%.1fM", n / 1_000_000.0)
    else if n >= 1_000 then String.format(Locale.ROOT, "%.1fK", n / 1_000.0)
    else n.toString

  def formatCount(n: BigInt): String = formatCount(n.min(BigInt(Long.MaxValue)).toLong)

  /** The two largest units: 12d 7h, 1h 23m, 4m 12s, 31s. For uptimes, ages and ETAs, where seconds of a day are noise.
    */
  def formatShort(seconds: Long): String =
    val s = seconds.max(0L)
    val days = s / 86400
    val hours = (s % 86400) / 3600
    val minutes = (s % 3600) / 60
    val secs = s % 60
    if days > 0 then s"${days}d ${hours}h"
    else if hours > 0 then s"${hours}h ${minutes}m"
    else if minutes > 0 then s"${minutes}m ${secs}s"
    else s"${secs}s"

  private val KiB = 1024.0

  /** Bytes as `df -h` writes them, in powers of 1024: 1.2T, 412G, 14.2G, 512M; negative is unknown, `?`. */
  def formatBytes(bytes: Long): String =
    val gib = bytes / (KiB * KiB * KiB)
    if bytes < 0 then "?"
    else if gib >= 1024 then String.format(Locale.ROOT, "%.1fT", gib / KiB)
    else if gib >= 100 then String.format(Locale.ROOT, "%.0fG", gib)
    else if gib >= 1 then String.format(Locale.ROOT, "%.1fG", gib)
    else String.format(Locale.ROOT, "%.0fM", bytes / (KiB * KiB))

  /** A share of 1 as a percentage with one decimal: 0.3%. */
  def formatPercent(share: Double): String = String.format(Locale.ROOT, "%.1f%%", share * 100)

  // ---- Text from outside the node (spec 027 FR-020, C-16) ------------------------------------------------------------

  /** Typography the ASCII set draws as ASCII of the same width; not glyphs of the TUI's own (those map through
    * [[TuiGlyphs.toAscii]]).
    */
  private val AsciiPunctuation: Map[Int, String] = Map(
    0x2013 -> "-", // en dash (the em dash is the `missing` glyph's)
    0x2018 -> "'",
    0x2019 -> "'",
    0x201c -> "\"",
    0x201d -> "\"",
    0x2022 -> "*", // bullet
    0x2190 -> "<",
    0x00d7 -> "x"
  )

  /** Text from outside the node as a row draws it: sanitised ([[Sanitize.line]]: no escape sequence, no control
    * character, one line, capped), and under the ASCII set printable ASCII only, in the same columns: the TUI's glyphs
    * as the ASCII set has them (§3.10), common typography as its ASCII, and any other character as `?` once per column
    * it takes. So a log line or a client id never widens a row, and `LANG=C` gets no byte it cannot draw.
    */
  def external(raw: String, g: TuiGlyphs): String =
    val clean = Sanitize.line(raw)
    if g.set != GlyphSet.Ascii then clean
    else
      val mapped = TuiGlyphs.toAscii(clean)
      val sb = new java.lang.StringBuilder(mapped.length)
      mapped.codePoints().forEach { cp =>
        if cp >= 0x20 && cp < 0x7f then sb.appendCodePoint(cp)
        else
          AsciiPunctuation.get(cp) match
            case Some(ascii) => sb.append(ascii)
            case None        => sb.append("?" * WCWidth.wcwidth(cp).max(0))
      }
      sb.toString

  /** `-stable`, `-unstable`, and either with its commit: the end of a client's version. */
  private val StableSuffix = "-(un)?stable(-[0-9a-fA-F]+)?$".r

  /** `+<commit>`: a build's commit, the end of a client's version. */
  private val BuildSuffix = "\\+[0-9a-fA-F]{6,}$".r

  /** A client id as the Peers view names it: its name and version, the first two of its `/`-separated parts, without a
    * `-stable-<commit>` or `+<commit>` build suffix. `CoreGeth/v1.12.19-stable-c2b6d2a5/linux-amd64/go1.21.5` is
    * `CoreGeth/v1.12.19`. Sanitised first: a peer chooses its client id (C-16).
    */
  def clientName(clientId: String): String =
    val parts = Sanitize.line(clientId).split('/').toSeq
    val version = parts.lift(1).map(v => BuildSuffix.replaceFirstIn(StableSuffix.replaceFirstIn(v, ""), ""))
    (parts.headOption.toSeq ++ version).filter(_.nonEmpty).mkString("/")
