package com.chipprbots.ethereum.console

import org.jline.utils.AttributedString
import org.jline.utils.AttributedStringBuilder

import com.chipprbots.ethereum.console.TuiText.fitToWidth
import com.chipprbots.ethereum.console.gfx.ArtStore
import com.chipprbots.ethereum.console.gfx.Blocks
import com.chipprbots.ethereum.console.gfx.Graphic
import com.chipprbots.ethereum.console.gfx.GraphicRegion

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
  * ([[FukuiiBanner.Cols]], C-8) and research's row thresholds (66 whole, 52 cropped). In pixel and block mode the badge
  * sprite stands where the logo would, where it fits.
  *
  * Under the logo is the node's start (spec 029 FR-020): the phases `StdNode.start` reports, each marked done when the
  * node says it has completed, and a worm bar whose progress is the share of them done. Nothing here is scripted: a
  * node that hangs in a phase shows that phase, for as long as the splash is up. Motion comes from `tick` only (the
  * wordmark reveal and the spinner); under reduced motion nothing moves, and the phases still follow the node.
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

  /** The rows the phases take where they are listed: a blank row, then one a phase. */
  val ChecklistRows: Int = 1 + StartupPhase.values.length

  /** The rows under any logo: a blank, the info row, a blank, the worm row. */
  private val UnderLogoRows: Int = 4

  /** The badge that stands for the logo in pixel and block mode: the large one, which carries the wordmark, where it
    * fits above the phases; else the small one; none where neither fits, in text mode, or without the sprite.
    */
  def badgeFor(state: TuiState, width: Int, height: Int, art: ArtStore): Option[(String, Int, Int)] =
    if state.graphics == GraphicsMode.Text then None
    else
      val bodyRows = (height - 1).max(0)
      Seq("badge-large", "badge")
        .flatMap(art.sprite)
        .find(s => s.rows + UnderLogoRows + ChecklistRows <= bodyRows && s.cols + 2 <= width)
        .map(s => (s.name, s.cols, s.rows))

  /** The splash frame for `state.tick`: exactly `height` rows (at least one) of exactly `width` columns, the skip hint
    * on the last row.
    */
  def render(
      state: TuiState,
      width: Int,
      height: Int,
      theme: TuiTheme,
      showLogo: Boolean = true,
      art: ArtStore = ArtStore.Empty
  ): Seq[AttributedString] = frame(state, width, height, theme, showLogo, art)._1

  /** The splash frame, and the badge's region where it is a picture. */
  def frame(
      state: TuiState,
      width: Int,
      height: Int,
      theme: TuiTheme,
      showLogo: Boolean,
      art: ArtStore
  ): (Seq[AttributedString], Seq[GraphicRegion]) =
    val w = width.max(1)
    val g = TuiGlyphs.of(state.glyphs)
    val logoStyle = theme.healthy.bold()
    val blank = new AttributedString("")
    // A block of art keeps its shape: every row starts in the same column, the block centred by its widest row.
    def block(rows: Seq[String], cols: Int): Seq[AttributedString] =
      val left = " " * ((w - cols) / 2).max(0)
      rows.map(r => new AttributedString(left + r, logoStyle))
    def centredBlock(rows: Seq[String]): Seq[AttributedString] = block(rows, colsOf(rows))

    val infoRow = centred(new AttributedString(info(state, g), theme.value), w)
    val wormRow = worm(state, w, g, theme)
    val bodyRows = (height - 1).max(0)
    val badge = if showLogo then badgeFor(state, w, height, art) else None
    val logo = badge match
      case Some((name, cols, rows)) =>
        // The badge's cells: half-block cells in block mode, blank under the image in pixel mode.
        val left = " " * ((w - cols) / 2).max(0)
        val cells = state.graphics match
          case GraphicsMode.Block =>
            art.sprite(name).fold(Seq.empty[AttributedString])(s => Blocks.render(s.loFrame(0), cols, rows))
          case _ => Seq.fill(rows)(blank)
        cells.map(row => AttributedString.join(blank, new AttributedString(left), row)) ++ Seq(
          blank,
          infoRow,
          blank,
          wormRow
        )
      case None =>
        logoFor(w, height, showLogo) match
          // The whole logo is placed by its full width, so the rows still hidden by the reveal do not move it.
          case SplashLogo.Whole   => block(revealed(state), FukuiiBanner.Cols) ++ Seq(blank, infoRow, blank, wormRow)
          case SplashLogo.Cropped => centredBlock(FukuiiBanner.Creature) ++ Seq(blank, wormRow)
          case SplashLogo.Small =>
            centredBlock(TuiRenderer.SmallLogo) ++ Seq(blank) ++ centredBlock(Wordmark) ++
              Seq(blank, infoRow, blank, wormRow)
          case SplashLogo.Wordmark => centredBlock(Wordmark) ++ Seq(blank, infoRow, blank, wormRow)
    // The phases are listed where there are rows for them; where there are not, the worm row names the one under way.
    val content =
      if logo.size + ChecklistRows <= bodyRows then logo ++ (blank +: checklist(state, w, g, theme)) else logo

    val top = ((bodyRows - content.size) / 2).max(0)
    val body = (Seq.fill(top)(blank) ++ content).take(bodyRows).padTo(bodyRows, blank)
    val regions = badge.toSeq.map { (name, cols, rows) =>
      GraphicRegion("badge", top, ((w - cols) / 2).max(0), cols, rows, Graphic.SpriteFrame(name, 0, 0), alt = "FUKUII")
    }
    ((body :+ new AttributedString(SkipHint, theme.muted)).map(fitToWidth(_, w)), regions)

  /** The node's start, a phase a row: a check beside each the node has reported done, a spinner beside the one under
    * way, a dot beside those to come. The block is centred by its widest row, so no row moves as phases complete.
    */
  private def checklist(state: TuiState, w: Int, g: TuiGlyphs, t: TuiTheme): Seq[AttributedString] =
    val cols = 3 + StartupPhase.values.map(_.label.length).max
    val left = " " * ((w - cols) / 2).max(0)
    val current = StartupPhase.current(state.startup)
    StartupPhase.values.toSeq.map { phase =>
      val sb = new AttributedStringBuilder()
      sb.append(left)
      if state.startup.contains(phase) then
        sb.append(TuiText.fitToWidth(new AttributedString(g.check.text, t.healthy), 3))
        sb.append(phase.label, t.value)
      else if current.contains(phase) then
        sb.append(TuiText.fitToWidth(new AttributedString(WormBar.spinner(state.tick, state.motion, g), t.value), 3))
        sb.append(phase.label, t.value.bold())
      else
        sb.append(TuiText.fitToWidth(new AttributedString(g.dot.text, t.muted), 3))
        sb.append(phase.label, t.muted)
      sb.toAttributedString
    }

  private def colsOf(rows: Seq[String]): Int =
    rows.map(r => new AttributedString(r).columnLength()).maxOption.getOrElse(0)

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

  /** The worm at the share of the start's phases the node has reported done (FR-020), then the phase under way and the
    * spinner; `node started` once all are done. The worm moves when a phase completes, and for nothing else.
    */
  private def worm(state: TuiState, width: Int, g: TuiGlyphs, t: TuiTheme): AttributedString =
    val share = StartupPhase.share(state.startup)
    val sb = new AttributedStringBuilder()
    sb.append(
      WormBar.render(WormBar.budgetFor(WormSlots, g), WormProgress.Known(share), state.tick, g, t, Motion.Reduced)
    )
    StartupPhase.current(state.startup) match
      case Some(phase) =>
        sb.append(s"  starting: ${phase.label} ", t.value)
        sb.append(WormBar.spinner(state.tick, state.motion, g), t.value)
      case None => sb.append("  node started", t.value)
    centred(sb.toAttributedString, width)

  private def centred(line: AttributedString, width: Int): AttributedString =
    val left = ((width - line.columnLength()) / 2).max(0)
    val sb = new AttributedStringBuilder()
    sb.append(" " * left)
    sb.append(line)
    sb.toAttributedString
