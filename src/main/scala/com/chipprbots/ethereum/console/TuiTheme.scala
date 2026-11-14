package com.chipprbots.ethereum.console

import org.jline.utils.AttributedStyle

/** The colour depth a theme draws with (research §5.1). */
enum Palette:
  case Ansi16, Ansi256, Mono

/** The TUI's colour roles (spec 027 FR-009, research §5.1). Every style the renderer draws comes from one theme, so a
  * frame rendered with [[TuiTheme.Mono]] carries no colour at all.
  *
  * @param healthy
  *   good state: connected, synced, the worm's track
  * @param accent
  *   the header and footer bands
  * @param label
  *   field names
  * @param value
  *   field values
  * @param muted
  *   separators, queued rows, DEBUG log lines
  */
final case class TuiTheme(
    palette: Palette,
    healthy: AttributedStyle,
    warn: AttributedStyle,
    error: AttributedStyle,
    accent: AttributedStyle,
    label: AttributedStyle,
    value: AttributedStyle,
    muted: AttributedStyle,
    logDebug: AttributedStyle,
    logInfo: AttributedStyle,
    logWarn: AttributedStyle,
    logError: AttributedStyle
):
  /** Every role by field name, in declaration order. */
  def roles: Seq[(String, AttributedStyle)] =
    productElementNames.zip(productIterator).collect { case (name, s: AttributedStyle) => name -> s }.toSeq

object TuiTheme:
  private val Plain = AttributedStyle.DEFAULT

  /** 16 colours: the look the TUI has always had. */
  val Ansi16: TuiTheme = TuiTheme(
    palette = Palette.Ansi16,
    healthy = Plain.foreground(AttributedStyle.GREEN),
    warn = Plain.foreground(AttributedStyle.YELLOW),
    error = Plain.foreground(AttributedStyle.RED).bold(),
    accent = Plain.foreground(AttributedStyle.BLACK).background(AttributedStyle.GREEN),
    label = Plain.foreground(AttributedStyle.CYAN),
    value = Plain.foreground(AttributedStyle.WHITE).bold(),
    muted = Plain.faint(),
    logDebug = Plain.faint(),
    logInfo = Plain,
    logWarn = Plain.foreground(AttributedStyle.YELLOW),
    logError = Plain.foreground(AttributedStyle.RED)
  )

  private def rgb(hex: Int): (Int, Int, Int) = ((hex >> 16) & 0xff, (hex >> 8) & 0xff, hex & 0xff)
  private def fg(style: AttributedStyle, hex: Int): AttributedStyle =
    val (r, g, b) = rgb(hex)
    style.foreground(r, g, b)
  private def bg(style: AttributedStyle, hex: Int): AttributedStyle =
    val (r, g, b) = rgb(hex)
    style.background(r, g, b)

  /** 256 colours and true colour: the §5.1 hex values. JLine rounds them to what the terminal reports. */
  val Ansi256: TuiTheme = TuiTheme(
    palette = Palette.Ansi256,
    healthy = fg(Plain, 0x3fb950),
    warn = fg(Plain, 0xd29922),
    error = fg(Plain, 0xf85149).bold(),
    accent = bg(fg(Plain, 0x0d1117), 0x3fb950),
    label = fg(Plain, 0x58a6ff),
    value = fg(Plain, 0xe6edf3).bold(),
    muted = fg(Plain, 0x6e7681),
    logDebug = fg(Plain, 0x6e7681),
    logInfo = Plain,
    logWarn = fg(Plain, 0xd29922),
    logError = fg(Plain, 0xf85149)
  )

  /** The command-center look (spec 029 research §5.4, contracts/art.md "Colours the interface itself uses"): the art's
    * own palette, by index, so that the text beside a sprite is in the colours the sprite is drawn in. Drawn with the
    * art, in pixel and block mode, which need 256 colours anyway; text mode keeps the themes above.
    */
  val CommandCenter: TuiTheme = TuiTheme(
    palette = Palette.Ansi256,
    healthy = Plain.foreground(46),
    warn = Plain.foreground(214),
    error = Plain.foreground(196).bold(),
    accent = Plain.foreground(16).background(40),
    label = Plain.foreground(44),
    value = Plain.foreground(255),
    muted = Plain.foreground(244),
    logDebug = Plain.foreground(244),
    logInfo = Plain.foreground(250),
    logWarn = Plain.foreground(214),
    logError = Plain.foreground(196)
  )

  /** No colour: bold and reverse video only (`NO_COLOR`, or a terminal with fewer than 8 colours). */
  val Mono: TuiTheme = TuiTheme(
    palette = Palette.Mono,
    healthy = Plain.bold(),
    warn = Plain.bold(),
    error = Plain.inverse(),
    accent = Plain.inverse(),
    label = Plain,
    value = Plain.bold(),
    muted = Plain,
    logDebug = Plain,
    logInfo = Plain,
    logWarn = Plain,
    logError = Plain
  )

  /** The theme for a terminal: monochrome when `noColor` is set or the terminal has fewer than 8 colours, else 16 or
    * 256 colours by its `max_colors`. `NO_COLOR` changes colour only, never motion (§4.5).
    */
  def of(noColor: Boolean, maxColors: Int): TuiTheme =
    if noColor || maxColors < 8 then Mono
    else if maxColors >= 256 then Ansi256
    else Ansi16
