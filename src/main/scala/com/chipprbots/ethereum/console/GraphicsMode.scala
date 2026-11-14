package com.chipprbots.ethereum.console

import com.chipprbots.ethereum.console.gfx.CellSize

/** How graphics are drawn (spec 029 FR-011): three modes over one layout. */
enum GraphicsMode:
  /** The Kitty graphics protocol, on a terminal that said it speaks it and how large its cells are. */
  case Pixel(cell: CellSize)

  /** Half-block character cells in the 240 palette colours. */
  case Block

  /** Spec 027's glyphs: no graphics. */
  case Text

  /** The name the setting, the sidebar and the log use. */
  def label: String = this match
    case Pixel(_) => "pixel"
    case Block    => "block"
    case Text     => "text"

/** `fukuii.tui.graphics`: a mode, or `auto`. */
enum GraphicsChoice:
  case Auto, Pixel, Block, Text

object GraphicsChoice:
  val Accepted: String = "auto, pixel, block, text"

  def parse(value: String): Option[GraphicsChoice] =
    values.find(_.toString.equalsIgnoreCase(value.trim))

/** What the terminal said of itself when the TUI started, and what the environment says of it (spec 029 FR-013).
  *
  * @param kittyGraphics
  *   it answered the graphics query
  * @param cell
  *   the size of a cell in pixels, if it said
  * @param colors
  *   the colours its terminfo entry names
  * @param multiplexer
  *   it is tmux or screen, which pass no pixels on
  * @param utf8
  *   the locale's encoding is UTF-8: block graphics are drawn with a character outside ASCII
  */
final case class TerminalCaps(
    kittyGraphics: Boolean,
    cell: Option[CellSize],
    colors: Int,
    multiplexer: Boolean,
    utf8: Boolean = true
)

object TerminalCaps:
  /** A terminal that answered nothing. */
  val None: TerminalCaps = TerminalCaps(kittyGraphics = false, cell = scala.None, colors = 8, multiplexer = false)

  /** Whether the environment names a terminal multiplexer: `TMUX` is set, or `TERM` is tmux's or screen's. */
  def multiplexer(env: Map[String, String]): Boolean =
    env.get("TMUX").exists(_.nonEmpty) || env.get("TERM").exists(t => t.startsWith("tmux") || t.startsWith("screen"))

object GraphicsMode:

  /** The colours a terminal needs for block or pixel graphics: the art's palette is the 256-colour one. */
  val MinColors: Int = 256

  /** The mode to draw in (spec 029 FR-013), and why it is not the one asked for when it is not.
    *
    * Without colour, text. Else what was asked: `auto` is pixel on a terminal that answered both queries and is not a
    * multiplexer, else block. `pixel` on a terminal that cannot do it is block, and says so. Block graphics are drawn
    * with the half-block character, so where the locale is not UTF-8 they are text.
    */
  def choose(choice: GraphicsChoice, caps: TerminalCaps, noColor: Boolean): (GraphicsMode, Option[String]) =
    def pixel: Option[GraphicsMode] = caps.cell.filter(_ => caps.kittyGraphics).map(GraphicsMode.Pixel(_))
    val blocks: GraphicsMode = if caps.utf8 then GraphicsMode.Block else GraphicsMode.Text
    if noColor || caps.colors < MinColors then
      val why = Option.when(choice == GraphicsChoice.Pixel || choice == GraphicsChoice.Block)(
        s"graphics = ${choice.toString.toLowerCase}: this terminal has no colour for it (NO_COLOR, or fewer than $MinColors colours); drawing text"
      )
      (GraphicsMode.Text, why)
    else
      choice match
        case GraphicsChoice.Text => (GraphicsMode.Text, scala.None)
        case GraphicsChoice.Block =>
          (blocks, Option.when(!caps.utf8)("graphics = block: the locale is not UTF-8; drawing text"))
        case GraphicsChoice.Auto => (if caps.multiplexer then blocks else pixel.getOrElse(blocks), scala.None)
        case GraphicsChoice.Pixel =>
          pixel match
            case Some(mode) => (mode, scala.None)
            case scala.None =>
              val missing =
                if !caps.kittyGraphics then "did not answer the graphics query" else "did not report its cell size"
              (blocks, Some(s"graphics = pixel: the terminal $missing; drawing ${blocks.label} graphics"))

  /** The mode after `v`: pixel, then block, then text, and round again. Pixel is skipped on a terminal that cannot. */
  def next(mode: GraphicsMode, caps: TerminalCaps, noColor: Boolean): GraphicsMode =
    val order = Seq(GraphicsChoice.Pixel, GraphicsChoice.Block, GraphicsChoice.Text)
    val at = mode match
      case Pixel(_) => 0
      case Block    => 1
      case Text     => 2
    (1 to 3).iterator
      .map(i => order((at + i) % 3))
      .map(choice => (choice, choose(choice, caps, noColor)))
      .collectFirst { case (choice, (m, scala.None)) if m.label == choice.toString.toLowerCase => m }
      .getOrElse(mode)
