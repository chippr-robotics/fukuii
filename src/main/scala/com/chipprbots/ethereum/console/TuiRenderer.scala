package com.chipprbots.ethereum.console

import org.jline.utils.AttributedString
import org.jline.utils.AttributedStyle

/** Rendering logic for terminal output.
  *
  * This class handles all the visual rendering of the TUI, including formatting of text, progress bars, status
  * indicators, and the overall layout.
  */
class TuiRenderer(config: TuiConfig):

  /** Build the complete display content. */
  def render(state: TuiState, width: Int, height: Int): Seq[AttributedString] =
    val lines = scala.collection.mutable.ArrayBuffer[AttributedString]()

    // Header
    lines += createHeader(width)
    lines += createSeparator(width)

    // Add ASCII art logo if there's enough space and config allows
    if config.showLogo && height > 30 && width > 80 then
      addSmallLogo(lines, width)
      lines += createSeparator(width)

    // Network & Connection section
    lines += createSectionHeader("NETWORK & CONNECTION", width)
    lines += createInfoLine("Network", state.networkName.toUpperCase, width)
    lines += createStatusLine("Connection", state.connectionStatus, width)
    lines += createPeerStatusLine(state.peerCount, state.maxPeers, width)
    lines += createSeparator(width)

    // Blockchain section
    lines += createSectionHeader("BLOCKCHAIN", width)
    lines += createInfoLine("Current Block", formatNumber(state.currentBlock), width)
    lines += createInfoLine("Best Block", formatNumber(state.bestBlock), width)
    lines += createInfoLine("Sync Status", state.syncStatus, width)

    if !state.isSynchronized && state.bestBlock > 0 then
      if config.showProgressBar then lines += createProgressBar("Sync Progress", state.syncProgress, width)
      lines += createInfoLine("Blocks Remaining", formatNumber(state.blocksRemaining), width)

      state.estimatedSyncTimeSeconds.foreach { seconds =>
        lines += createInfoLine("Est. Sync Time", formatDuration(seconds), width)
      }
      state.syncSpeedBlocksPerSec.foreach { speed =>
        lines += createInfoLine("Sync Speed", f"$speed%.2f blocks/sec", width)
      }
    else if state.isSynchronized then lines += createInfoLine("Status", "✓ SYNCHRONIZED", width)

    lines += createSeparator(width)

    // Node Settings section (if enabled)
    if config.showNodeSettings && state.nodeSettings.network.nonEmpty then
      lines += createSectionHeader("NODE SETTINGS", width)
      lines += createInfoLine("Data Dir", state.nodeSettings.dataDir, width)
      lines += createInfoLine("Sync Mode", state.nodeSettings.syncMode, width)
      lines += createInfoLine("Pruning", state.nodeSettings.pruningMode, width)
      lines += createInfoLine("Max Peers", state.nodeSettings.maxPeers.toString, width)
      if state.nodeSettings.rpcEnabled then
        lines += createInfoLine("JSON-RPC", s"Enabled (port ${state.nodeSettings.rpcPort})", width)
      else lines += createInfoLine("JSON-RPC", "Disabled", width)
      if state.nodeSettings.miningEnabled then lines += createInfoLine("Mining", "Enabled", width)
      else lines += createInfoLine("Mining", "Disabled", width)
      lines += createSeparator(width)

    // Runtime section
    lines += createSectionHeader("RUNTIME", width)
    lines += createInfoLine("Uptime", formatDuration(state.uptimeSeconds), width)
    lines += createSeparator(width)

    // Footer with keyboard commands
    lines += createFooter(width)

    // Fill remaining space
    while lines.length < height - 1 do lines += new AttributedString(" " * width)

    lines.toSeq

  /** Render the startup banner. */
  def renderStartupBanner(width: Int): Seq[AttributedString] =
    val banner = Seq(
      "",
      "    ___________  ____  ____",
      "   / ____/ __ \\/ __ \\/ __ \\",
      "  / /_  / / / / / / / / / /",
      " / __/ / /_/ / /_/ / /_/ /",
      "/_/    \\____/\\____/\\____/",
      "",
      "   FUKUII ETHEREUM CLASSIC",
      "",
      "    Initializing...",
      ""
    )

    val greenStyle = AttributedStyle.DEFAULT.foreground(AttributedStyle.GREEN).bold()

    banner.map { line =>
      val centered = " " * ((width - line.length) / 2) + line
      val padded = centered + " " * (width - centered.length)
      new AttributedString(padded, greenStyle)
    }

  private def createHeader(width: Int): AttributedString =
    val title = " ◆ FUKUII ETHEREUM CLIENT ◆ "
    val padding = (width - title.length) / 2
    val paddedTitle = " " * padding + title + " " * (width - padding - title.length)
    new AttributedString(
      paddedTitle,
      AttributedStyle.DEFAULT
        .foreground(AttributedStyle.BLACK)
        .background(AttributedStyle.GREEN)
        .bold()
    )

  private def createSectionHeader(title: String, width: Int): AttributedString =
    val header = s" ● $title"
    val paddedHeader = header + " " * (width - header.length)
    new AttributedString(
      paddedHeader,
      AttributedStyle.DEFAULT.foreground(AttributedStyle.GREEN).bold()
    )

  private def createInfoLine(label: String, value: String, width: Int): AttributedString =
    val labelStyle = AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN)
    val valueStyle = AttributedStyle.DEFAULT.foreground(AttributedStyle.WHITE).bold()

    val labelPart = new AttributedString(s"   $label: ", labelStyle)
    val valuePart = new AttributedString(value, valueStyle)

    val combinedLength = labelPart.columnLength() + valuePart.columnLength()
    val padding = " " * Math.max(0, width - combinedLength)
    new AttributedString(labelPart.toAnsi() + valuePart.toAnsi() + padding)

  private def createProgressBar(label: String, percentage: Double, width: Int): AttributedString =
    val labelStyle = AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN)
    val barWidth = Math.min(40, width - 30)
    val filled = ((percentage / 100.0) * barWidth).toInt
    val empty = barWidth - filled

    val labelPart = new AttributedString(s"   $label: ", labelStyle)
    val barStyle = AttributedStyle.DEFAULT.foreground(AttributedStyle.GREEN).bold()
    val emptyStyle = AttributedStyle.DEFAULT.foreground(AttributedStyle.WHITE)

    val filledBar = new AttributedString("█" * filled, barStyle)
    val emptyBar = new AttributedString("░" * empty, emptyStyle)
    val percentText =
      new AttributedString(f" $percentage%.2f%%", AttributedStyle.DEFAULT.foreground(AttributedStyle.WHITE).bold())

    val combinedLength =
      labelPart.columnLength() + filledBar.columnLength() + emptyBar.columnLength() + percentText.columnLength()
    val padding = " " * Math.max(0, width - combinedLength)
    new AttributedString(labelPart.toAnsi() + filledBar.toAnsi() + emptyBar.toAnsi() + percentText.toAnsi() + padding)

  private def createStatusLine(label: String, status: String, width: Int): AttributedString =
    val labelStyle = AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN)
    val statusStyle = status.toLowerCase match
      case s if s.contains("connected") || s.contains("running") =>
        AttributedStyle.DEFAULT.foreground(AttributedStyle.GREEN).bold()
      case s if s.contains("starting") || s.contains("initializing") =>
        AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW).bold()
      case s if s.contains("error") || s.contains("failed") =>
        AttributedStyle.DEFAULT.foreground(AttributedStyle.RED).bold()
      case _ =>
        AttributedStyle.DEFAULT.foreground(AttributedStyle.WHITE).bold()

    val labelPart = new AttributedString(s"   $label: ", labelStyle)
    val statusPart = new AttributedString(s"● $status", statusStyle)

    val combinedLength = labelPart.columnLength() + statusPart.columnLength()
    val padding = " " * Math.max(0, width - combinedLength)
    new AttributedString(labelPart.toAnsi() + statusPart.toAnsi() + padding)

  private def createPeerStatusLine(count: Int, max: Int, width: Int): AttributedString =
    val labelStyle = AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN)
    val peerStyle =
      if count == 0 then AttributedStyle.DEFAULT.foreground(AttributedStyle.RED).bold()
      else if count < max / 2 then AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW).bold()
      else AttributedStyle.DEFAULT.foreground(AttributedStyle.GREEN).bold()

    val labelPart = new AttributedString("   Peers: ", labelStyle)
    val peerText = s"$count / $max"
    val peerPart = new AttributedString(peerText, peerStyle)

    // Add visual indicator
    val indicator = if count > 0 then " ◆" * Math.min(count, 10) else ""
    val indicatorPart = new AttributedString(indicator, peerStyle)

    val combinedLength = labelPart.columnLength() + peerPart.columnLength() + indicatorPart.columnLength()
    val padding = " " * Math.max(0, width - combinedLength)
    new AttributedString(labelPart.toAnsi() + peerPart.toAnsi() + indicatorPart.toAnsi() + padding)

  private def createSeparator(width: Int): AttributedString =
    new AttributedString(
      "─" * width,
      AttributedStyle.DEFAULT.foreground(AttributedStyle.GREEN)
    )

  private def createFooter(width: Int): AttributedString =
    val footer = " Commands: [Q]uit | [R]efresh | [D]isable UI "
    val paddedFooter = footer + " " * Math.max(0, width - footer.length)
    new AttributedString(
      paddedFooter,
      AttributedStyle.DEFAULT
        .foreground(AttributedStyle.BLACK)
        .background(AttributedStyle.GREEN)
    )

  private def addSmallLogo(lines: scala.collection.mutable.ArrayBuffer[AttributedString], width: Int): Unit =
    val logo = Seq(
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

    val greenStyle = AttributedStyle.DEFAULT.foreground(AttributedStyle.GREEN).bold()

    logo.foreach { line =>
      val centered = " " * ((width - line.length) / 2) + line
      val padded = centered + " " * Math.max(0, width - centered.length)
      lines += new AttributedString(padded, greenStyle)
    }

  private def formatNumber(n: Long): String =
    "%,d".format(n)

  private def formatDuration(seconds: Long): String =
    val days = seconds / 86400
    val hours = (seconds % 86400) / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60

    if days > 0 then s"${days}d ${hours}h ${minutes}m"
    else if hours > 0 then s"${hours}h ${minutes}m ${secs}s"
    else if minutes > 0 then s"${minutes}m ${secs}s"
    else s"${secs}s"

object TuiRenderer:
  /** Create a renderer with default configuration. */
  def default: TuiRenderer = new TuiRenderer(TuiConfig.default)

  /** Create a renderer with the specified configuration. */
  def apply(config: TuiConfig): TuiRenderer = new TuiRenderer(config)
