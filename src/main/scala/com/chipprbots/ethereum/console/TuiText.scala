package com.chipprbots.ethereum.console

import java.util.Locale

import org.jline.utils.AttributedString
import org.jline.utils.AttributedStringBuilder

/** Text helpers every TUI row uses: the frame's width rule and the number formats. Slice 4's sanitiser for text from
  * outside the node (FR-020) belongs here too.
  */
object TuiText:

  /** Exactly `width` columns: cut, then padded with spaces. A wide glyph that would straddle the edge is dropped by the
    * cut, which leaves the line one column short; the padding restores it.
    */
  def fitToWidth(line: AttributedString, width: Int): AttributedString =
    val cols = width.max(0)
    val cut = if line.columnLength() > cols then line.columnSubSequence(0, cols) else line
    val short = cols - cut.columnLength()
    if short <= 0 then cut
    else
      val sb = new AttributedStringBuilder()
      sb.append(cut)
      sb.append(" " * short)
      sb.toAttributedString

  /** 21,884,102 */
  def formatNumber(n: Long): String = String.format(Locale.ROOT, "%,d", n)

  /** 34.1M, 412.0K, 310: the compact counts of the log's SNAP progress line. */
  def formatCount(n: Long): String =
    if n >= 1_000_000 then String.format(Locale.ROOT, "%.1fM", n / 1_000_000.0)
    else if n >= 1_000 then String.format(Locale.ROOT, "%.1fK", n / 1_000.0)
    else n.toString

  def formatCount(n: BigInt): String = formatCount(n.min(BigInt(Long.MaxValue)).toLong)

  /** 12d 7h 0m, 2h 11m 0s, 4m 31s, 9s */
  def formatDuration(seconds: Long): String =
    val days = seconds / 86400
    val hours = (seconds % 86400) / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60

    if days > 0 then s"${days}d ${hours}h ${minutes}m"
    else if hours > 0 then s"${hours}h ${minutes}m ${secs}s"
    else if minutes > 0 then s"${minutes}m ${secs}s"
    else s"${secs}s"

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
