package com.chipprbots.ethereum.console

import org.jline.utils.AttributedString
import org.jline.utils.AttributedStringBuilder

import com.chipprbots.ethereum.console.TuiText.fitToWidth

/** Which logo the splash draws at a size (spec 027 FR-013, Q2, research §3.7). */
enum SplashLogo:
  /** The whole `printBanner` logo, its wordmark revealed one row per tick. */
  case Whole

  /** The creature without its wordmark rows: the whole logo does not fit, but [[Splash.CroppedMinRows]] rows do. */
  case Cropped

  /** The 12-line logo above the FUKUII wordmark (tier M and up). */
  case Small

  /** The FUKUII wordmark alone (tier S). */
  case Wordmark

/** The startup splash: a view of the same pure renderer, drawn while `tick < splashUntilTick` (spec 027 FR-013,
  * research §3.7). It replaces the banner that `Tui.initialize` used to paint and hold with a `Thread.sleep`.
  *
  * The logo is chosen by size, with the column threshold measured on the trimmed `printBanner` block
  * ([[FukuiiBanner.Cols]], C-8) and research's row thresholds (66 whole, 52 cropped). Motion comes from `tick` only:
  * the wordmark reveal, the worm's crawl and the spinner. Under reduced motion the splash is one static frame.
  */
object Splash:

  /** Rows for the whole logo: its 60 rows, the info row and the worm row, each after a blank row, and the skip hint. */
  val WholeMinRows: Int = 66

  /** Rows for the creature: its 49 rows, a blank row, the worm row and the skip hint. */
  val CroppedMinRows: Int = 52

  /** Columns for either big logo: the trimmed block and a column of margin on each side. */
  val LogoMinCols: Int = FukuiiBanner.Cols + 2

  /** The bottom row, on every splash frame. */
  val SkipHint: String = " any key to skip"

  /** The splash worm's track: the S tier's 20 slots at every size. */
  private val WormSlots: Int = Tier.S.trackSlots

  /** The letters of the wordmark, figlet's "standard" font: five rows each, every row of a letter the same width. */
  val WordmarkLetters: Map[Char, Seq[String]] = Map(
    'F' -> Seq(" _____ ", "|  ___|", "| |_   ", "|  _|  ", "|_|    "),
    'U' -> Seq(" _   _ ", "| | | |", "| | | |", "| |_| |", " \\___/ "),
    'K' -> Seq(" _  __", "| |/ /", "| ' / ", "| . \\ ", "|_|\\_\\"),
    'I' -> Seq(" ___ ", "|_ _|", " | | ", " | | ", "|___|")
  )

  /** FUKUII, five rows of 42 columns (FR-013; the figlet it replaces spelled FOOO, research §3.7). */
  val Wordmark: Seq[String] = "FUKUII".map(WordmarkLetters).transpose.map(_.mkString(" "))

  /** The logo for a terminal of this size. `showLogo = false` keeps only the wordmark. */
  def logoFor(width: Int, height: Int, showLogo: Boolean = true): SplashLogo =
    if !showLogo then SplashLogo.Wordmark
    else if width >= LogoMinCols && height >= WholeMinRows then SplashLogo.Whole
    else if width >= LogoMinCols && height >= CroppedMinRows then SplashLogo.Cropped
    else if Tier.of(width, height) != Tier.S then SplashLogo.Small
    else SplashLogo.Wordmark

  /** The splash frame for `state.tick`: exactly `height` rows (at least one) of exactly `width` columns, the skip hint
    * on the last row.
    */
  def render(
      state: TuiState,
      width: Int,
      height: Int,
      theme: TuiTheme,
      showLogo: Boolean = true
  ): Seq[AttributedString] =
    val w = width.max(1)
    val g = TuiGlyphs.of(state.glyphs)
    val art = theme.healthy.bold()
    val blank = new AttributedString("")
    // A block of art keeps its shape: every row starts in the same column, the block centred by its widest row.
    def block(rows: Seq[String], cols: Int): Seq[AttributedString] =
      val left = " " * ((w - cols) / 2).max(0)
      rows.map(r => new AttributedString(left + r, art))
    def centredBlock(rows: Seq[String]): Seq[AttributedString] = block(rows, colsOf(rows))

    val infoRow = centred(new AttributedString(info(state, g), theme.value), w)
    val wormRow = worm(state, w, g, theme)
    val content = logoFor(w, height, showLogo) match
      // The whole logo is placed by its full width, so the rows still hidden by the reveal do not move it.
      case SplashLogo.Whole   => block(revealed(state), FukuiiBanner.Cols) ++ Seq(blank, infoRow, blank, wormRow)
      case SplashLogo.Cropped => centredBlock(FukuiiBanner.Creature) ++ Seq(blank, wormRow)
      case SplashLogo.Small =>
        centredBlock(TuiRenderer.SmallLogo) ++ Seq(blank) ++ centredBlock(Wordmark) ++
          Seq(blank, infoRow, blank, wormRow)
      case SplashLogo.Wordmark => centredBlock(Wordmark) ++ Seq(blank, infoRow, blank, wormRow)

    val bodyRows = (height - 1).max(0)
    val top = ((bodyRows - content.size) / 2).max(0)
    val body = (Seq.fill(top)(blank) ++ content).take(bodyRows).padTo(bodyRows, blank)
    (body :+ new AttributedString(SkipHint, theme.muted)).map(fitToWidth(_, w))

  private def colsOf(rows: Seq[String]): Int = rows.map(r => new AttributedString(r).columnLength()).maxOption.getOrElse(0)

  /** The whole logo with the wordmark rows not yet revealed left blank: one more row each tick (§3.7). */
  private def revealed(state: TuiState): Seq[String] =
    val shown = if state.motion == Motion.Full then state.tick else Long.MaxValue
    FukuiiBanner.Logo.zipWithIndex.map { (row, i) =>
      if FukuiiBanner.WordmarkRows.contains(i) && i - FukuiiBanner.WordmarkRows.start >= shown then "" else row
    }

  /** `FUKUII · multi-network EVM client · etc · SNAP`: the network and sync mode once the node has named them. */
  private def info(state: TuiState, g: TuiGlyphs): String =
    val network = Option(state.networkName).filter(n => n.nonEmpty && n != TuiState.initial.networkName)
    val mode = Option(state.nodeSettings.syncMode).filter(_.nonEmpty)
    (Seq("FUKUII", "multi-network EVM client") ++ network ++ mode).mkString(s" ${g.dot.text} ")

  /** The worm crawling once across the splash, then `starting node` and the spinner. Under reduced motion it stays at
    * the start, and the spinner is a static dot.
    */
  private def worm(state: TuiState, width: Int, g: TuiGlyphs, t: TuiTheme): AttributedString =
    val frames = state.splashUntilTick.getOrElse(1L).max(1L)
    val crawled = if state.motion == Motion.Full then state.tick.min(frames - 1).max(0L).toDouble / frames else 0.0
    val sb = new AttributedStringBuilder()
    sb.append(WormBar.render(WormBar.budgetFor(WormSlots, g), WormProgress.Known(crawled), state.tick, g, t, Motion.Reduced))
    sb.append("  starting node ", t.value)
    sb.append(WormBar.spinner(state.tick, state.motion, g), t.value)
    centred(sb.toAttributedString, width)

  private def centred(line: AttributedString, width: Int): AttributedString =
    val left = ((width - line.columnLength()) / 2).max(0)
    val sb = new AttributedStringBuilder()
    sb.append(" " * left)
    sb.append(line)
    sb.toAttributedString
