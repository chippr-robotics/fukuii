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
  * The renderer is a function of (state, width, height, theme): the glyph set and the frame counter `tick` are the
  * state's, every style is the theme's, and the size picks the [[Tier]] (spec 027 FR-003, FR-004). It reads no clock
  * and no random source, so one state always draws one frame.
  */
class TuiRenderer(config: TuiConfig):

  import TuiRenderer.*

  /** Build the complete display content: [[frame]]'s lines. */
  def render(state: TuiState, width: Int, height: Int, theme: TuiTheme = TuiTheme.Ansi16): Seq[AttributedString] =
    frame(state, width, height, theme).lines

  /** The frame, and whether it moves with `tick`: the frame loop paces itself by it (Q1).
    *
    * The lines are exactly `height` (for `height > 0`), each exactly `width` columns, so that drawing them from the
    * home position never scrolls or wraps the terminal. When the content is taller than the terminal, the body is
    * truncated and the footer with the keyboard commands is kept on the last line. While the splash is on screen its
    * rows replace the dashboard's, all of them, or all but the top `k` during the hand-over (§3.7).
    */
  def frame(state: TuiState, width: Int, height: Int, theme: TuiTheme = TuiTheme.Ansi16): Frame =
    val w = Math.max(width, 1)
    state.splashCover(height) match
      case Some(0) => Frame(Splash.render(state, w, height, theme, config.showLogo), moving(state))
      case Some(rows) =>
        val dashboard = dashboardFrame(state, w, height, theme)
        Frame(dashboard.lines.take(rows) ++ Splash.render(state, w, height, theme, config.showLogo).drop(rows), true)
      case None => dashboardFrame(state, w, height, theme)

  /** Whether anything at all can move: only under full motion. */
  private def moving(state: TuiState): Boolean = state.motion == Motion.Full

  private def dashboardFrame(state: TuiState, w: Int, height: Int, theme: TuiTheme): Frame =
    val tier = Tier.of(w, height)
    val g = TuiGlyphs.of(state.glyphs)
    val header = Seq(createHeader(w, g, theme, state.headerFlash), createSeparator(w, g, theme))
    val footer = createFooter(w, theme)

    // §3.1: the dashboard draws the logo only at tier L, in a right-hand column. That column leaves the body fewer
    // columns than a tier-M screen has, so the body takes M's worm track there and each row keeps its count and rate.
    val (body, firstMoving) =
      if config.showLogo && tier.logo.inDashboard then
        val b = renderBody(state, w - LogoColumnCols - 1, Tier.M.trackSlots, g, theme)
        (withLogoColumn(b.rows, w, g, theme), b.firstMoving)
      else
        val b = renderBody(state, w, tier.trackSlots, g, theme)
        (b.rows, b.firstMoving)

    val content = header ++ body
    val lines =
      if height <= 0 then content :+ footer
      else
        val fitted = content.take(height - 1)
        val padding = Seq.fill(height - 1 - fitted.size)(new AttributedString(" " * w))
        (fitted ++ padding) :+ footer

    // A row that moves counts only if it is on screen; the celebration counts while it runs.
    val bodyOnScreen = if height <= 0 then body.size else (height - 1 - header.size).max(0)
    val animated = moving(state) && (firstMoving.exists(_ < bodyOnScreen) || state.catchup.isDefined)
    Frame(lines.map(fitToWidth(_, w)), animated)

  /** Concatenate styled fragments, keeping styles as attributes (not embedded escape codes). */
  private def concat(parts: AttributedString*): AttributedString =
    val sb = new AttributedStringBuilder()
    parts.foreach(p => sb.append(p))
    sb.toAttributedString

  /** The body's rows, and the first of them that moves with `tick`. */
  final private case class Body(rows: Seq[AttributedString], firstMoving: Option[Int])

  private def renderBody(
      state: TuiState,
      width: Int,
      trackSlots: Int,
      g: TuiGlyphs,
      t: TuiTheme
  ): Body =
    val lines = ArrayBuffer[AttributedString]()
    var firstMoving: Option[Int] = None
    def moves(row: AttributedString): AttributedString =
      if firstMoving.isEmpty then firstMoving = Some(lines.size)
      row
    val budget = WormBar.budgetFor(trackSlots, g).min(width - RowIndentCols).max(0)
    def worm(label: String, progress: WormProgress, detail: String, stalled: Boolean = false): AttributedString =
      val row = wormRow(label, progress, detail, budget, width, state, g, t, stalled)
      if WormBar.animates(budget, progress, g, state.motion, stalled) && config.showProgressBar then moves(row) else row

    // Network & Connection section
    lines += createSectionHeader("NETWORK & CONNECTION", width, g, t)
    lines += createInfoLine("Network", state.networkName.toUpperCase(Locale.ROOT), width, t)
    val connection = createStatusLine("Connection", state.connectionStatus, width, state, g, t)
    lines += (if InFlight.contains(state.connectionStatus) && moving(state) then moves(connection) else connection)
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
          s"${formatNumber(state.currentBlock)} / ${formatNumber(state.bestBlock)}",
          stalled = state.stalled
        )
      lines += createInfoLine("Blocks Remaining", formatNumber(state.blocksRemaining), width, t)

      state.estimatedSyncTimeSeconds.foreach { seconds =>
        lines += createInfoLine("Est. Sync Time", formatDuration(seconds), width, t)
      }
      state.syncSpeedBlocksPerSec.foreach { speed =>
        lines += createInfoLine("Sync Speed", String.format(Locale.ROOT, "%.2f blocks/sec", speed), width, t)
      }
    else if state.isSynchronized then
      if config.showProgressBar then
        val detail = formatNumber(state.currentBlock)
        lines += state.catchup.fold(worm("Blocks", WormProgress.Complete, detail)) { c =>
          catchupRow(c, detail, budget, width, g, t)
        }
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

    Body(lines.toSeq, firstMoving)

  /** ` Label [===🪱....]🧠 52% detail`: a 12-column label, the bar in `budget` columns, then the detail. Without the
    * progress bar the row keeps its label and says in words what the bar would show.
    */
  private def wormRow(
      label: String,
      progress: WormProgress,
      detail: String,
      budget: Int,
      width: Int,
      state: TuiState,
      g: TuiGlyphs,
      t: TuiTheme,
      stalled: Boolean
  ): AttributedString =
    val sb = new AttributedStringBuilder()
    sb.append(s"   ${label.padTo(WormLabelCols, ' ')}", t.label)
    if config.showProgressBar then sb.append(WormBar.render(budget, progress, state.tick, g, t, state.motion, stalled))
    else
      val words = progress match
        case WormProgress.Known(f) => s"${(f.max(0.0).min(1.0) * 100).toInt}%"
        case other                 => other.toString.toLowerCase(Locale.ROOT)
      sb.append(words, t.value)
    if detail.nonEmpty then sb.append(s"  $detail", t.value)
    fitToWidth(sb.toAttributedString, width)

  /** The synced Blocks row during a catch-up: the worm's last crawl into the brain, frame by frame, or the one quiet
    * frame (research §3.8). Its bar is not held to the budget: the row is fitted to the width, like every row.
    */
  private def catchupRow(
      catchup: Catchup,
      detail: String,
      budget: Int,
      width: Int,
      g: TuiGlyphs,
      t: TuiTheme
  ): AttributedString =
    val slots = WormBar.slotsFor(budget, g).max(2)
    val complete = s"${g.worm.text}[COMPLETE ${g.check.text}]${g.brain.text}"
    val sparkle = g.celebration.text
    val bar = catchup match
      case Catchup.Celebrating(frame) =>
        frame / 2 match
          case 0 => s"[${"=" * (slots - 2)}${g.worm.text}.]${g.brain.text} 99%"
          case 1 => s"[${"=" * (slots - 1)}${g.worm.text}]${g.brain.text} 100%"
          case 2 => s"[${"=" * slots}]${g.worm.text}${g.brain.text} 100%"
          case 3 => s"[${"=" * slots}]$sparkle${g.brain.text}$sparkle synced"
          case 4 => s"$complete $sparkle synced"
          case _ => s"$complete ${" " * g.celebration.cols} synced"
      case Catchup.Celebrated => s"$complete $sparkle synced"
      case Catchup.BackAtHead => s"$complete ${g.check.text} back at head"
    val sb = new AttributedStringBuilder()
    sb.append(s"   ${"Blocks".padTo(WormLabelCols, ' ')}", t.label)
    sb.append(bar, t.healthy)
    sb.append(s"  $detail", t.value)
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

  private def centred(text: String, width: Int, style: AttributedStyle): AttributedString =
    val line = new AttributedString(text, style)
    val left = Math.max(0, (width - line.columnLength()) / 2)
    fitToWidth(concat(new AttributedString(" " * left), line), width)

  /** The header band, in the accent style; during the celebration's flash (frames 2–6, §3.8) in the flash style. */
  private def createHeader(width: Int, g: TuiGlyphs, t: TuiTheme, flash: Boolean): AttributedString =
    val style = if flash then flashStyle(t) else t.accent
    val title = new AttributedString(s" ${g.diamond.text} FUKUII ETHEREUM CLIENT ${g.diamond.text} ", style)
    val left = Math.max(0, (width - title.columnLength()) / 2)
    val right = Math.max(0, width - left - title.columnLength())
    concat(new AttributedString(" " * left, style), title, new AttributedString(" " * right, style))

  private def createSectionHeader(title: String, width: Int, g: TuiGlyphs, t: TuiTheme): AttributedString =
    fitToWidth(new AttributedString(s" ${g.bullet.text} $title", t.healthy.bold()), width)

  private def createInfoLine(label: String, value: String, width: Int, t: TuiTheme): AttributedString =
    val labelPart = new AttributedString(s"   $label: ", t.label)
    val valuePart = new AttributedString(value, t.value)

    val combinedLength = labelPart.columnLength() + valuePart.columnLength()
    val padding = " " * Math.max(0, width - combinedLength)
    concat(labelPart, valuePart, new AttributedString(padding))

  /** `Label: ● status`; an in-flight status turns a spinner where the bullet is (§4.2), so the row's width holds. */
  private def createStatusLine(
      label: String,
      status: String,
      width: Int,
      state: TuiState,
      g: TuiGlyphs,
      t: TuiTheme
  ): AttributedString =
    val marker = if InFlight.contains(status) then WormBar.spinner(state.tick, state.motion, g) else g.bullet.text
    val statusStyle = status.toLowerCase(Locale.ROOT) match
      case s if s.contains("connected") || s.contains("running")     => t.healthy.bold()
      case s if s.contains("starting") || s.contains("initializing") => t.warn.bold()
      case s if s.contains("error") || s.contains("failed")          => t.error
      case _                                                         => t.value

    val labelPart = new AttributedString(s"   $label: ", t.label)
    val statusPart = new AttributedString(s"$marker $status", statusStyle)

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

/** A rendered frame, and whether it moves with `tick` (the frame loop's pace, Q1). */
final case class Frame(lines: Seq[AttributedString], animated: Boolean)

object TuiRenderer:
  /** Connection statuses that are waiting for something: a spinner turns beside them (§4.2). */
  private[console] val InFlight: Set[String] = Set("Initializing", "Starting node...", "Searching for peers")

  /** The header band's flash: the accent's colour as its background, in bold, so it reads in every palette. */
  private[console] def flashStyle(t: TuiTheme): AttributedStyle = t.healthy.bold().inverse()

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
