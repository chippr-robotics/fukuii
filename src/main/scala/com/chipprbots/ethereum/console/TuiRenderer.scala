package com.chipprbots.ethereum.console

import java.util.Locale

import scala.collection.mutable.ArrayBuffer

import org.jline.utils.AttributedString
import org.jline.utils.AttributedStringBuilder
import org.jline.utils.AttributedStyle

import com.chipprbots.ethereum.console.TuiText.fitToWidth
import com.chipprbots.ethereum.console.TuiText.formatCount
import com.chipprbots.ethereum.console.TuiText.formatDuration
import com.chipprbots.ethereum.console.TuiText.formatNumber

/** Rendering logic for terminal output.
  *
  * This class handles all the visual rendering of the TUI, including formatting of text, progress bars, status
  * indicators, and the overall layout.
  *
  * The renderer is a function of (state, width, height, theme): the glyph set is the state's, every style is the
  * theme's, and the size picks the [[Tier]] (spec 027 FR-003, FR-004).
  */
class TuiRenderer(config: TuiConfig):

  import TuiRenderer.*

  /** Build the complete display content.
    *
    * The result is exactly `height` lines (for `height > 0`), each exactly `width` columns, so that drawing it from the
    * home position never scrolls or wraps the terminal. When the content is taller than the terminal, the body is
    * truncated and the footer with the keyboard commands is kept on the last line.
    */
  def render(state: TuiState, width: Int, height: Int, theme: TuiTheme = TuiTheme.Ansi16): Seq[AttributedString] =
    val w = Math.max(width, 1)
    val tier = Tier.of(w, height)
    val g = TuiGlyphs.of(state.glyphs)
    val header = Seq(createHeader(w, g, theme), createSeparator(w, g, theme))
    val footer = createFooter(w, theme)

    // §3.1: the dashboard draws the logo only at tier L, in a right-hand column. That column leaves the body fewer
    // columns than a tier-M screen has, so the body takes M's worm track there and each row keeps its count and rate.
    val body =
      if config.showLogo && tier.logo.inDashboard then
        withLogoColumn(renderBody(state, w - LogoColumnCols - 1, Tier.M.trackSlots, g, theme), w, g, theme)
      else renderBody(state, w, tier.trackSlots, g, theme)

    val content = header ++ body
    val lines =
      if height <= 0 then content :+ footer
      else
        val fitted = content.take(height - 1)
        val padding = Seq.fill(height - 1 - fitted.size)(new AttributedString(" " * w))
        (fitted ++ padding) :+ footer

    lines.map(fitToWidth(_, w))

  /** Concatenate styled fragments, keeping styles as attributes (not embedded escape codes). */
  private def concat(parts: AttributedString*): AttributedString =
    val sb = new AttributedStringBuilder()
    parts.foreach(p => sb.append(p))
    sb.toAttributedString

  private def renderBody(
      state: TuiState,
      width: Int,
      trackSlots: Int,
      g: TuiGlyphs,
      t: TuiTheme
  ): Seq[AttributedString] =
    val lines = ArrayBuffer[AttributedString]()
    val budget = WormBar.budgetFor(trackSlots, g).min(width - RowIndentCols).max(0)
    def worm(label: String, progress: WormProgress, detail: String): AttributedString =
      wormRow(label, progress, detail, budget, width, g, t)

    // Network & Connection section
    lines += createSectionHeader("NETWORK & CONNECTION", width, g, t)
    lines += createInfoLine("Network", state.networkName.toUpperCase(Locale.ROOT), width, t)
    lines += createStatusLine("Connection", state.connectionStatus, width, g, t)
    lines += createPeerStatusLine(state.peerCount, state.maxPeers, width, g, t)
    lines += createSeparator(width, g, t)

    // Blockchain section
    lines += createSectionHeader("BLOCKCHAIN", width, g, t)
    lines += createInfoLine("Current Block", formatNumber(state.currentBlock), width, t)
    lines += createInfoLine(
      "Best Block",
      if state.bestBlock > 0 then formatNumber(state.bestBlock) else "unknown",
      width,
      t
    )
    lines += createInfoLine("Sync Status", state.syncStatus, width, t)

    if !state.isSynchronized && state.bestBlock > 0 then
      if config.showProgressBar then
        lines += worm(
          "Blocks",
          WormProgress.Known(state.syncProgress / 100.0),
          s"${formatNumber(state.currentBlock)} / ${formatNumber(state.bestBlock)}"
        )
      lines += createInfoLine("Blocks Remaining", formatNumber(state.blocksRemaining), width, t)

      state.estimatedSyncTimeSeconds.foreach { seconds =>
        lines += createInfoLine("Est. Sync Time", formatDuration(seconds), width, t)
      }
      state.syncSpeedBlocksPerSec.foreach { speed =>
        lines += createInfoLine("Sync Speed", String.format(Locale.ROOT, "%.2f blocks/sec", speed), width, t)
      }
    else if state.isSynchronized then
      if config.showProgressBar then lines += worm("Blocks", WormProgress.Complete, formatNumber(state.currentBlock))
      lines += createInfoLine("Status", s"${g.check.text} SYNCHRONIZED", width, t)

    lines += createSeparator(width, g, t)

    // SNAP Sync section (if active): one worm row per part of the state, each count and rate shown once.
    state.snapSyncState.foreach { snap =>
      lines += createSectionHeader("SNAP SYNC", width, g, t)
      lines += createInfoLine("Phase", snap.phaseDescription(g), width, t)
      SnapRows.of(snap, g).foreach(row => lines += worm(row.label, row.progress, row.detail))
      if snap.chainTarget > 0 then
        val dot = g.dot.text
        lines += createInfoLine(
          "Chain",
          s"headers ${formatCount(snap.chainHeaders)} / ${formatCount(snap.chainTarget)} $dot " +
            s"bodies ${formatCount(snap.chainBodies)} $dot receipts ${formatCount(snap.chainReceipts)}",
          width,
          t
        )
      lines += createInfoLine(
        "Elapsed",
        s"${formatDuration(snap.elapsedSeconds.toLong)} (phase ${formatDuration(snap.phaseElapsedSeconds.toLong)})",
        width,
        t
      )
      lines += createSeparator(width, g, t)
    }

    // Node Settings section (if enabled)
    if config.showNodeSettings && state.nodeSettings.network.nonEmpty then
      lines += createSectionHeader("NODE SETTINGS", width, g, t)
      lines += createInfoLine("Data Dir", state.nodeSettings.dataDir, width, t)
      lines += createInfoLine("Sync Mode", state.nodeSettings.syncMode, width, t)
      lines += createInfoLine("Pruning", state.nodeSettings.pruningMode, width, t)
      lines += createInfoLine("Max Peers", state.nodeSettings.maxPeers.toString, width, t)
      if state.nodeSettings.rpcEnabled then
        lines += createInfoLine("JSON-RPC", s"Enabled (port ${state.nodeSettings.rpcPort})", width, t)
      else lines += createInfoLine("JSON-RPC", "Disabled", width, t)
      if state.nodeSettings.miningEnabled then lines += createInfoLine("Mining", "Enabled", width, t)
      else lines += createInfoLine("Mining", "Disabled", width, t)
      lines += createSeparator(width, g, t)

    // Runtime section
    lines += createSectionHeader("RUNTIME", width, g, t)
    lines += createInfoLine("Uptime", formatDuration(state.uptimeSeconds), width, t)
    lines += createSeparator(width, g, t)

    lines.toSeq

  /** ` Label [===🪱....]🧠 52% detail`: a 12-column label, the bar in `budget` columns, then the detail. Without the
    * progress bar the row keeps its label and says in words what the bar would show.
    */
  private def wormRow(
      label: String,
      progress: WormProgress,
      detail: String,
      budget: Int,
      width: Int,
      g: TuiGlyphs,
      t: TuiTheme
  ): AttributedString =
    val sb = new AttributedStringBuilder()
    sb.append(s"   ${label.padTo(WormLabelCols, ' ')}", t.label)
    if config.showProgressBar then sb.append(WormBar.render(budget, progress, 0L, g, t))
    else
      val words = progress match
        case WormProgress.Known(f) => s"${(f.max(0.0).min(1.0) * 100).toInt}%"
        case other                 => other.toString.toLowerCase(Locale.ROOT)
      sb.append(words, t.value)
    if detail.nonEmpty then sb.append(s"  $detail", t.value)
    fitToWidth(sb.toAttributedString, width)

  /** The body on the left, a divider, and the 12-line logo centred in a column on the right (tier L, §3.4). */
  private def withLogoColumn(
      body: Seq[AttributedString],
      width: Int,
      g: TuiGlyphs,
      t: TuiTheme
  ): Seq[AttributedString] =
    val leftCols = width - LogoColumnCols - 1
    val divider = new AttributedString(g.divider.text, t.muted)
    val blank = new AttributedString("")
    val logo = SmallLogo.map(row => centred(row, LogoColumnCols, t.healthy.bold()))
    (0 until body.size.max(logo.size)).map { i =>
      concat(
        fitToWidth(body.lift(i).getOrElse(blank), leftCols),
        divider,
        fitToWidth(logo.lift(i).getOrElse(blank), LogoColumnCols)
      )
    }

  /** Render the startup banner: the FUKUII wordmark, and from tier M the 12-line logo above it (§3.1). */
  def renderStartupBanner(
      width: Int,
      height: Int = Tui.FallbackSize._2,
      theme: TuiTheme = TuiTheme.Ansi16
  ): Seq[AttributedString] =
    val w = Math.max(width, 1)
    val logo =
      if config.showLogo && Tier.of(w, height).logo != TierLogo.WordmarkOnSplash then SmallLogo :+ "" else Seq.empty
    val banner = Seq("") ++ logo ++ Wordmark ++ Seq("", "FUKUII EVM CLIENT", "", "Initializing...", "")
    val lines = banner.map(line => centred(line, w, theme.healthy.bold()))
    if height > 0 then lines.take(height) else lines

  private def centred(text: String, width: Int, style: AttributedStyle): AttributedString =
    val line = new AttributedString(text, style)
    val left = Math.max(0, (width - line.columnLength()) / 2)
    fitToWidth(concat(new AttributedString(" " * left), line), width)

  private def createHeader(width: Int, g: TuiGlyphs, t: TuiTheme): AttributedString =
    val title = new AttributedString(s" ${g.diamond.text} FUKUII ETHEREUM CLIENT ${g.diamond.text} ", t.accent)
    val left = Math.max(0, (width - title.columnLength()) / 2)
    val right = Math.max(0, width - left - title.columnLength())
    concat(new AttributedString(" " * left, t.accent), title, new AttributedString(" " * right, t.accent))

  private def createSectionHeader(title: String, width: Int, g: TuiGlyphs, t: TuiTheme): AttributedString =
    fitToWidth(new AttributedString(s" ${g.bullet.text} $title", t.healthy.bold()), width)

  private def createInfoLine(label: String, value: String, width: Int, t: TuiTheme): AttributedString =
    val labelPart = new AttributedString(s"   $label: ", t.label)
    val valuePart = new AttributedString(value, t.value)

    val combinedLength = labelPart.columnLength() + valuePart.columnLength()
    val padding = " " * Math.max(0, width - combinedLength)
    concat(labelPart, valuePart, new AttributedString(padding))

  private def createStatusLine(label: String, status: String, width: Int, g: TuiGlyphs, t: TuiTheme): AttributedString =
    val statusStyle = status.toLowerCase(Locale.ROOT) match
      case s if s.contains("connected") || s.contains("running")     => t.healthy.bold()
      case s if s.contains("starting") || s.contains("initializing") => t.warn.bold()
      case s if s.contains("error") || s.contains("failed")          => t.error
      case _                                                         => t.value

    val labelPart = new AttributedString(s"   $label: ", t.label)
    val statusPart = new AttributedString(s"${g.bullet.text} $status", statusStyle)

    val combinedLength = labelPart.columnLength() + statusPart.columnLength()
    val padding = " " * Math.max(0, width - combinedLength)
    concat(labelPart, statusPart, new AttributedString(padding))

  private def createPeerStatusLine(count: Int, max: Int, width: Int, g: TuiGlyphs, t: TuiTheme): AttributedString =
    // The 50 % colour threshold stays; the alert row's 25 % rule is a separate check (spec 027 C-2).
    val peerStyle =
      if count == 0 then t.error
      else if count < max / 2 then t.warn.bold()
      else t.healthy.bold()

    val labelPart = new AttributedString("   Peers: ", t.label)
    val peerText = s"$count / $max"
    val peerPart = new AttributedString(peerText, peerStyle)

    // Add visual indicator
    val indicator = if count > 0 then s" ${g.diamond.text}" * Math.min(count, 10) else ""
    val indicatorPart = new AttributedString(indicator, peerStyle)

    val combinedLength = labelPart.columnLength() + peerPart.columnLength() + indicatorPart.columnLength()
    val padding = " " * Math.max(0, width - combinedLength)
    concat(labelPart, peerPart, indicatorPart, new AttributedString(padding))

  private def createSeparator(width: Int, g: TuiGlyphs, t: TuiTheme): AttributedString =
    new AttributedString(g.separator.text * width, t.muted)

  private def createFooter(width: Int, t: TuiTheme): AttributedString =
    new AttributedString(Footer + " " * Math.max(0, width - Footer.length), t.accent)

object TuiRenderer:
  /** Create a renderer with default configuration. */
  def default: TuiRenderer = new TuiRenderer(TuiConfig.default)

  /** Create a renderer with the specified configuration. */
  def apply(config: TuiConfig): TuiRenderer = new TuiRenderer(config)

  /** The footer: today's keys, with `d` labelled Detach (Q7). Slice 3 adds the view keys. */
  private[console] val Footer: String = " r Redraw  d Detach  q Quit"

  /** A worm row's indent and label: three spaces and a 9-column label. */
  private val WormLabelCols = 9
  private val RowIndentCols = 3 + WormLabelCols

  /** The right-hand column that holds the logo at tier L. */
  private[console] val LogoColumnCols: Int = 46

  /** The 12-line logo. */
  private[console] val SmallLogo: Seq[String] = Seq(
    "                 --                ",
    "               .=+#+.              ",
    "              .+++*#*.             ",
    "             :++++*###-            ",
    "           .=+++++*####+.          ",
    "          .=++++++*#####+.         ",
    "         :++++++++*#######:        ",
    "        :+++++++++*########-       ",
    "       =++++++++++*#########+      ",
    "     .++++++++++++*##########*.    ",
    "   .:+++++++++++++*############:   ",
    "   -++++++++++++++*#############=  "
  )

  /** The letters of the wordmark, figlet's "standard" font: five rows each, every row of a letter the same width. */
  val WordmarkLetters: Map[Char, Seq[String]] = Map(
    'F' -> Seq(" _____ ", "|  ___|", "| |_   ", "|  _|  ", "|_|    "),
    'U' -> Seq(" _   _ ", "| | | |", "| | | |", "| |_| |", " \\___/ "),
    'K' -> Seq(" _  __", "| |/ /", "| ' / ", "| . \\ ", "|_|\\_\\"),
    'I' -> Seq(" ___ ", "|_ _|", " | | ", " | | ", "|___|")
  )

  /** FUKUII, five rows of 42 columns (spec 027 FR-013; the figlet it replaces spelled FOOO, research §3.7). */
  val Wordmark: Seq[String] = "FUKUII".map(WordmarkLetters).transpose.map(_.mkString(" "))
