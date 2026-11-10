package com.chipprbots.ethereum.console

/** Text from outside the node made safe to draw (spec 027 FR-020, C-16): a peer's client id, which the peer chooses in
  * its Hello, and every log line, which can quote peer data. What is left is one line of printable text, so nothing a
  * peer sends reaches the operator's terminal as a command, and the frame's width arithmetic holds.
  *
  *   - An escape sequence goes whole, not only its ESC: CSI (`ESC [` … final byte, and the 8-bit CSI), OSC, DCS, SOS,
  *     PM and APC strings (to BEL or ST), SS2/SS3 and the other two- and three-byte sequences. Otherwise `ESC[2J` would
  *     leave `[2J` on screen.
  *   - Every other C0 control, DEL and C1 control goes; a tab becomes a space, so words do not run together.
  *   - Format characters (zero-width and joiner characters, bidirectional overrides, the BOM) and variation selectors
  *     go: a terminal and `WCWidth` count them differently, which would shift the rest of the row.
  *   - A line break (CR, LF, U+2028, U+2029) ends the text: the first line is kept, with the ellipsis when anything but
  *     blanks follows.
  *   - The result is trimmed and capped at [[MaxChars]] code points, the last of them the ellipsis when cut. A
  *     surrogate pair is never split.
  *
  * Idempotent: cleaning a cleaned text changes nothing.
  */
object Sanitize:

  /** The longest text kept, in code points (research §7.4). */
  val MaxChars: Int = 512

  /** Marks text cut short: a line after the first, or the cap. Drawn as `..` by the ASCII set (C-15). */
  val Ellipsis: String = "…"

  private val Esc = 0x1b
  private val Bel = 0x07
  private val Csi8 = 0x9b

  /** The 8-bit string introducers: DCS, SOS, OSC, PM, APC. */
  private val Strings8: Set[Int] = Set(0x90, 0x98, 0x9d, 0x9e, 0x9f)

  /** After ESC: the string introducers `P` (DCS), `X` (SOS), `]` (OSC), `^` (PM), `_` (APC). */
  private val Strings7: Set[Int] = Set('P'.toInt, 'X'.toInt, ']'.toInt, '^'.toInt, '_'.toInt)

  /** `text` as one clean line of at most `maxChars` code points. */
  def line(text: String, maxChars: Int = MaxChars): String =
    val (first, more) = firstLine(stripSequences(text))
    val kept = first.trim
    val marked = if more && kept.nonEmpty then kept + Ellipsis else if more then Ellipsis else kept
    cap(marked, maxChars.max(1))

  /** Removes escape sequences and every character that is not drawn, keeping line breaks for [[firstLine]]. */
  private def stripSequences(text: String): String =
    val cps = text.codePoints().toArray
    val sb = new java.lang.StringBuilder(text.length)
    var i = 0
    // Skips a string's body up to and including its terminator (BEL, ST as ESC \, or the 8-bit ST). An unterminated
    // string loses only its introducer: what follows is drawn as text, harmless without it.
    def skipString(from: Int): Int =
      def terminatorAt(j: Int): Boolean =
        cps(j) == Bel || cps(j) == 0x9c || (cps(j) == Esc && j + 1 < cps.length && cps(j + 1) == '\\'.toInt)
      var j = from
      while j < cps.length && !terminatorAt(j) do j += 1
      if j >= cps.length then from
      else if cps(j) == Esc then j + 2
      else j + 1
    // Skips a CSI's parameter and intermediate bytes and its final byte.
    def skipCsi(from: Int): Int =
      var j = from
      while j < cps.length && cps(j) >= 0x20 && cps(j) <= 0x3f do j += 1
      if j < cps.length && cps(j) >= 0x40 && cps(j) <= 0x7e then j + 1 else j
    while i < cps.length do
      val cp = cps(i)
      if cp == Esc then
        val next = if i + 1 < cps.length then cps(i + 1) else -1
        i =
          if next == '['.toInt then skipCsi(i + 2)
          else if Strings7.contains(next) then skipString(i + 2)
          else if next == 'N'.toInt || next == 'O'.toInt then (i + 3).min(cps.length) // SS2, SS3: one more character
          else
            // nF sequences: intermediates (0x20–0x2F), then one final byte; else ESC and one byte (SS2, SS3, Fp, Fs).
            var j = i + 1
            while j < cps.length && cps(j) >= 0x20 && cps(j) <= 0x2f do j += 1
            if j < cps.length && cps(j) >= 0x30 && cps(j) <= 0x7e then j + 1 else j
      else if cp == Csi8 then i = skipCsi(i + 1)
      else if Strings8.contains(cp) then i = skipString(i + 1)
      else
        if cp == '\t'.toInt then sb.append(' ')
        else if isLineBreak(cp) || !dropped(cp) then sb.appendCodePoint(cp)
        i += 1
    sb.toString

  private def isLineBreak(cp: Int): Boolean = cp == '\n'.toInt || cp == '\r'.toInt || cp == 0x2028 || cp == 0x2029

  /** Not drawn, or drawn at a width the terminal and `WCWidth` disagree on. */
  private def dropped(cp: Int): Boolean =
    Character.isISOControl(cp) || Character.getType(cp) == Character.FORMAT.toInt ||
      (cp >= 0xfe00 && cp <= 0xfe0f) || (cp >= 0xe0100 && cp <= 0xe01ef)

  /** The text before the first line break, and whether anything but blanks follows it. */
  private def firstLine(text: String): (String, Boolean) =
    val at = text.indexWhere(ch => isLineBreak(ch.toInt))
    if at < 0 then (text, false)
    else (text.substring(0, at), text.substring(at + 1).exists(ch => !Character.isWhitespace(ch) && !isLineBreak(ch)))

  private def cap(text: String, maxChars: Int): String =
    if text.codePointCount(0, text.length) <= maxChars then text
    else text.substring(0, text.offsetByCodePoints(0, maxChars - 1)) + Ellipsis
