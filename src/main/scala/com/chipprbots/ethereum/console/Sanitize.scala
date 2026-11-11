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
  *
  * Linear and input-bounded (prism, slice 4 review): a peer chooses its client id up to the 16 MiB message cap, and the
  * probe cleans it on every poll. At most [[InputFactor]] times the cap in code points is read, whatever the text's
  * length, and everything past that counts as more text, so the result ends in the ellipsis. Within what is read, no
  * escape sequence is scanned twice: an unterminated string introducer scans to the end once, not once per introducer.
  */
object Sanitize:

  /** The longest text kept, in code points (research §7.4). */
  val MaxChars: Int = 512

  /** Marks text cut short: a line after the first, or the cap. Drawn as `..` by the ASCII set (C-15). */
  val Ellipsis: String = "…"

  /** How many code points are read, as a multiple of the cap: room for the escape sequences and blanks a real line
    * carries around its text, and no more.
    */
  val InputFactor: Int = 4

  private val Esc = 0x1b
  private val Bel = 0x07
  private val Csi8 = 0x9b

  /** The 8-bit string introducers: DCS (0x90), SOS (0x98), OSC (0x9d), PM (0x9e), APC (0x9f). */
  private def isString8(cp: Int): Boolean = cp == 0x90 || cp == 0x98 || (cp >= 0x9d && cp <= 0x9f)

  /** After ESC: the string introducers `P` (DCS), `X` (SOS), `]` (OSC), `^` (PM), `_` (APC). */
  private def isString7(cp: Int): Boolean =
    cp == 'P'.toInt || cp == 'X'.toInt || cp == ']'.toInt || cp == '^'.toInt || cp == '_'.toInt

  /** `text` as one clean line of at most `maxChars` code points. */
  def line(text: String, maxChars: Int = MaxChars): String =
    val limit = maxChars.max(1)
    if isPlain(text, limit) then text
    else
      val (cps, cut) = readBounded(text, (limit.toLong * InputFactor).min(Int.MaxValue).toInt)
      val (first, more) = firstLine(stripSequences(cps))
      val kept = first.trim
      val marked = if (more || cut) && kept.nonEmpty then kept + Ellipsis else if more || cut then Ellipsis else kept
      cap(marked, limit)

  /** Already what [[line]] would return: printable ASCII only, no blank at either end, within the cap. The common case
    * (a client id, a short log message) allocates nothing.
    */
  private def isPlain(text: String, maxChars: Int): Boolean =
    val n = text.length
    n <= maxChars && (n == 0 || (text.charAt(0) != ' ' && text.charAt(n - 1) != ' ')) && {
      var i = 0
      while i < n && text.charAt(i) >= ' ' && text.charAt(i) <= '~' do i += 1
      i == n
    }

  /** The first `bound` code points of `text`, and whether any follow. Costs `bound`, not `text.length`. */
  private def readBounded(text: String, bound: Int): (Array[Int], Boolean) =
    val buf = new Array[Int](bound.min(text.length))
    var n = 0
    var at = 0
    while at < text.length && n < bound do
      val cp = text.codePointAt(at)
      buf(n) = cp
      n += 1
      at += Character.charCount(cp)
    (if n == buf.length then buf else java.util.Arrays.copyOf(buf, n), at < text.length)

  /** Removes escape sequences and every character that is not drawn, keeping line breaks for [[firstLine]]. */
  private def stripSequences(cps: Array[Int]): String =
    val sb = new java.lang.StringBuilder(cps.length)
    var i = 0
    // A scan that reached the end without a terminator found none for any later start either: the later introducers
    // need not scan again (this is what keeps a text of a million `ESC ]` linear).
    var noTerminator = false
    // Skips a string's body up to and including its terminator (BEL, ST as ESC \, or the 8-bit ST). An unterminated
    // string loses only its introducer: what follows is drawn as text, harmless without it.
    def skipString(from: Int): Int =
      def terminatorAt(j: Int): Boolean =
        cps(j) == Bel || cps(j) == 0x9c || (cps(j) == Esc && j + 1 < cps.length && cps(j + 1) == '\\'.toInt)
      var j = from
      while !noTerminator && j < cps.length && !terminatorAt(j) do j += 1
      if noTerminator || j >= cps.length then
        noTerminator = true
        from
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
          else if isString7(next) then skipString(i + 2)
          else if next == 'N'.toInt || next == 'O'.toInt then (i + 3).min(cps.length) // SS2, SS3: one more character
          else
            // nF sequences: intermediates (0x20–0x2F), then one final byte; else ESC and one byte (SS2, SS3, Fp, Fs).
            var j = i + 1
            while j < cps.length && cps(j) >= 0x20 && cps(j) <= 0x2f do j += 1
            if j < cps.length && cps(j) >= 0x30 && cps(j) <= 0x7e then j + 1 else j
      else if cp == Csi8 then i = skipCsi(i + 1)
      else if isString8(cp) then i = skipString(i + 1)
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
