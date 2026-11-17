package com.chipprbots.ethereum.console

import java.util.Locale

/** The TUI's two glyph sets (spec 027 FR-008, research §5.2). */
enum GlyphSet:
  /** Emoji and Unicode symbols: the default under a UTF-8 locale. */
  case Emoji

  /** Printable ASCII only, with the same column budgets: for consoles without UTF-8 or with fonts that draw emoji
    * badly.
    */
  case Ascii

/** The `fukuii.tui.glyphs` and `FUKUII_TUI_GLYPHS` value: a glyph set, or `auto` (ASCII when the locale is not UTF-8,
  * Q3).
  */
enum GlyphChoice:
  case Emoji, Ascii, Auto

object GlyphChoice:
  /** The accepted spellings, for error and warning messages. */
  val Accepted: String = "emoji, ascii, auto"

  def parse(value: String): Option[GlyphChoice] = value.trim.toLowerCase(Locale.ROOT) match
    case "emoji" => Some(Emoji)
    case "ascii" => Some(Ascii)
    case "auto"  => Some(Auto)
    case _       => None

/** A glyph and the terminal columns it occupies. The width is declared, never measured at render time; `TuiGlyphsSpec`
  * checks every declaration against JLine's `WCWidth`, the oracle `AttributedString.columnLength` uses.
  */
final case class Glyph(text: String, cols: Int)

/** One glyph set: every symbol the TUI draws, with its width in that set.
  *
  * Widths are equal in both sets except the ellipsis (C-15) and the SNAP phase glyphs, which the renderer pads to
  * [[TuiGlyphs.PhaseCols]] in both sets ([[TuiGlyphs.VariableWidth]]). No glyph carries U+FE0F, and the glyphs whose
  * East Asian width is ambiguous (`◆ ● ⚠ ▶ ─ … —`) are pinned to one column. `timelineTrack` is the fork timeline's
  * track (§5.2 "track done"); the worm bar always draws `=` and `.`, as the log's bar does.
  */
final case class TuiGlyphs(
    set: GlyphSet,
    worm: Glyph,
    brain: Glyph,
    stalled: Glyph,
    accounts: Glyph,
    storage: Glyph,
    healing: Glyph,
    validating: Glyph,
    done: Glyph,
    idle: Glyph,
    clLink: Glyph,
    celebration: Glyph,
    check: Glyph,
    dot: Glyph,
    diamond: Glyph,
    bullet: Glyph,
    warn: Glyph,
    error: Glyph,
    separator: Glyph,
    divider: Glyph,
    timelineTrack: Glyph,
    arrow: Glyph,
    ellipsis: Glyph,
    up: Glyph,
    down: Glyph,
    steady: Glyph,
    missing: Glyph
):
  /** Every glyph by field name, in declaration order. */
  def all: Seq[(String, Glyph)] =
    productElementNames.zip(productIterator).collect { case (name, g: Glyph) => name -> g }.toSeq

object TuiGlyphs:

  /** Columns of the phase field: a phase glyph is padded to this width in both sets (§5.2). */
  val PhaseCols: Int = 4

  /** The glyphs whose width differs between the sets: the phase glyphs (padded to [[PhaseCols]]) and the ellipsis. */
  val VariableWidth: Set[String] = Set("accounts", "storage", "healing", "done", "ellipsis")

  val Emoji: TuiGlyphs = TuiGlyphs(
    set = GlyphSet.Emoji,
    worm = Glyph("🪱", 2),
    brain = Glyph("🧠", 2),
    stalled = Glyph("💤", 2),
    accounts = Glyph("📦", 2),
    storage = Glyph("💾", 2),
    healing = Glyph("🔧", 2),
    validating = Glyph("✓", 1),
    done = Glyph("✅", 2),
    idle = Glyph("·", 1),
    clLink = Glyph("🔗", 2),
    celebration = Glyph("✨", 2),
    check = Glyph("✓", 1),
    dot = Glyph("·", 1),
    diamond = Glyph("◆", 1),
    bullet = Glyph("●", 1),
    warn = Glyph("⚠", 1),
    error = Glyph("✗", 1),
    separator = Glyph("─", 1),
    divider = Glyph("│", 1),
    timelineTrack = Glyph("═", 1),
    arrow = Glyph("▶", 1),
    ellipsis = Glyph("…", 1),
    up = Glyph("↑", 1),
    down = Glyph("↓", 1),
    steady = Glyph("→", 1),
    missing = Glyph("—", 1)
  )

  val Ascii: TuiGlyphs = TuiGlyphs(
    set = GlyphSet.Ascii,
    worm = Glyph("~o", 2),
    brain = Glyph("@@", 2),
    stalled = Glyph("z ", 2),
    accounts = Glyph("[A]", 3),
    storage = Glyph("[S]", 3),
    healing = Glyph("[H]", 3),
    validating = Glyph("+", 1),
    done = Glyph("[ok]", 4),
    idle = Glyph(".", 1),
    clLink = Glyph("CL", 2),
    celebration = Glyph("**", 2),
    check = Glyph("+", 1),
    dot = Glyph(".", 1),
    diamond = Glyph("#", 1),
    bullet = Glyph("*", 1),
    warn = Glyph("!", 1),
    error = Glyph("x", 1),
    separator = Glyph("-", 1),
    divider = Glyph("|", 1),
    timelineTrack = Glyph("=", 1),
    arrow = Glyph(">", 1),
    ellipsis = Glyph("..", 2),
    up = Glyph("^", 1),
    down = Glyph("v", 1),
    steady = Glyph("-", 1),
    missing = Glyph("-", 1)
  )

  def of(set: GlyphSet): TuiGlyphs = set match
    case GlyphSet.Emoji => Emoji
    case GlyphSet.Ascii => Ascii

  private val VariationSelector16 = 0xfe0f

  /** Each emoji-set glyph (one code point) and its ASCII form. */
  private val toAsciiByCodePoint: Map[Int, String] =
    Emoji.all
      .zip(Ascii.all)
      .collect {
        case ((_, e), (_, a)) if e.text.codePointCount(0, e.text.length) == 1 => e.text.codePointAt(0) -> a.text
      }
      .toMap

  /** Text drawn with the ASCII set: every emoji-set glyph replaced by its ASCII form, U+FE0F removed. For text the TUI
    * does not compose itself, such as captured log lines (§3.10), so logs never widen a row with a glyph the ASCII set
    * avoids.
    */
  def toAscii(text: String): String =
    val sb = new java.lang.StringBuilder(text.length)
    text.codePoints().forEach { cp =>
      if cp != VariationSelector16 then
        toAsciiByCodePoint.get(cp) match
          case Some(ascii) => sb.append(ascii)
          case None        => sb.appendCodePoint(cp)
    }
    sb.toString
