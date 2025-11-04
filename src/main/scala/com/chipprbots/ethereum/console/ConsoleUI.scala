package com.chipprbots.ethereum.console

import java.time.Duration
import java.time.Instant

import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder
import org.jline.utils.AttributedString
import org.jline.utils.AttributedStyle

import com.chipprbots.ethereum.utils.Logger

/** Enhanced console UI for monitoring Fukuii node status.
  *
  * Provides a grid-based terminal interface with:
  *   - Real-time peer connection status
  *   - Network information
  *   - Block sync progress
  *   - Keyboard commands
  */
class ConsoleUI extends Logger {

  private var terminal: Option[Terminal] = None
  private var shouldStop = false
  private var enabled = true
  private val startTime = Instant.now()

  // State variables
  @volatile private var peerCount: Int = 0
  @volatile private var maxPeers: Int = 0
  @volatile private var currentBlock: Long = 0
  @volatile private var bestBlock: Long = 0
  @volatile private var networkName: String = "unknown"
  @volatile private var syncStatus: String = "Starting..."
  @volatile private var connectionStatus: String = "Initializing"

  /** Initialize the console UI. */
  def initialize(): Unit = {
    if (!enabled) return

    try {
      terminal = Some(
        TerminalBuilder
          .builder()
          .system(true)
          .jna(true)
          .build()
      )

      terminal.foreach { term =>
        term.enterRawMode()
        // Hide cursor
        term.writer().print("\u001b[?25l")
        term.writer().flush()
        // Clear screen
        clearScreen()
        
        // Show a brief startup banner with the Fukuii mini logo
        showStartupBanner()
      }

      log.info("Console UI initialized")
    } catch {
      case e: Exception =>
        log.warn(s"Failed to initialize console UI: ${e.getMessage}. Falling back to standard logging.")
        enabled = false
        terminal = None
    }
  }

  /** Display startup banner briefly before main UI takes over. */
  private def showStartupBanner(): Unit = {
    terminal.foreach { term =>
      val width = term.getWidth
      
      // Compact Fukuii branding
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
      
      term.writer().print("\u001b[H") // Move to top
      banner.foreach { line =>
        val centered = " " * ((width - line.length) / 2) + line
        val padded = centered + " " * (width - centered.length)
        val styledLine = new AttributedString(padded, greenStyle)
        term.writer().println(styledLine.toAnsi())
      }
      term.writer().flush()
      
      // Brief pause to show banner
      Thread.sleep(1000)
    }
  }

  /** Disable the console UI. */
  def disable(): Unit = {
    enabled = false
  }

  /** Check if the console UI is enabled. */
  def isEnabled: Boolean = enabled

  /** Update the peer count. */
  def updatePeerCount(count: Int, max: Int): Unit = {
    peerCount = count
    maxPeers = max
  }

  /** Update the block information. */
  def updateBlockInfo(current: Long, best: Long): Unit = {
    currentBlock = current
    bestBlock = best
  }

  /** Update the network name. */
  def updateNetwork(name: String): Unit = {
    networkName = name
  }

  /** Update the sync status. */
  def updateSyncStatus(status: String): Unit = {
    syncStatus = status
  }

  /** Update the connection status. */
  def updateConnectionStatus(status: String): Unit = {
    connectionStatus = status
  }

  /** Render the console UI. */
  def render(): Unit = {
    if (!enabled || terminal.isEmpty) return

    terminal.foreach { term =>
      try {
        val width = term.getWidth
        val height = term.getHeight

        // Move cursor to top-left
        term.writer().print("\u001b[H")

        val lines = buildDisplay(width, height)
        lines.foreach { line =>
          term.writer().println(line.toAnsi())
        }

        term.writer().flush()
      } catch {
        case e: Exception =>
          log.error(s"Error rendering console UI: ${e.getMessage}")
      }
    }
  }

  /** Build the display content. */
  private def buildDisplay(width: Int, height: Int): Seq[AttributedString] = {
    val lines = scala.collection.mutable.ArrayBuffer[AttributedString]()

    // Header
    lines += createHeader(width)
    lines += createSeparator(width)

    // Add ASCII art logo if there's enough space
    if (height > 30 && width > 80) {
      addSmallLogo(lines, width)
      lines += createSeparator(width)
    }

    // Network & Connection section
    lines += createSectionHeader("NETWORK & CONNECTION", width)
    lines += createInfoLine("Network", networkName.toUpperCase, width)
    lines += createStatusLine("Connection", connectionStatus, width)
    lines += createPeerStatusLine(peerCount, maxPeers, width)
    lines += createSeparator(width)

    // Blockchain section
    lines += createSectionHeader("BLOCKCHAIN", width)
    lines += createInfoLine("Current Block", formatNumber(currentBlock), width)
    lines += createInfoLine("Best Block", formatNumber(bestBlock), width)
    lines += createInfoLine("Sync Status", syncStatus, width)
    
    if (bestBlock > 0 && currentBlock > 0 && currentBlock < bestBlock) {
      val progress = (currentBlock.toDouble / bestBlock.toDouble) * 100.0
      val remaining = bestBlock - currentBlock
      
      // Create progress bar
      lines += createProgressBar("Sync Progress", progress, width)
      lines += createInfoLine("Blocks Remaining", formatNumber(remaining), width)
      
      // Estimate sync time
      val uptime = Duration.between(startTime, Instant.now()).getSeconds
      if (uptime > 10 && currentBlock > 0) {
        val blocksPerSecond = currentBlock.toDouble / uptime.toDouble
        if (blocksPerSecond > 0) {
          val estimatedSeconds = remaining / blocksPerSecond
          lines += createInfoLine("Est. Sync Time", formatDuration(estimatedSeconds.toLong), width)
          lines += createInfoLine("Sync Speed", f"${blocksPerSecond}%.2f blocks/sec", width)
        }
      }
    } else if (currentBlock >= bestBlock && bestBlock > 0) {
      lines += createInfoLine("Status", "✓ SYNCHRONIZED", width)
    }
    
    lines += createSeparator(width)

    // Runtime section
    lines += createSectionHeader("RUNTIME", width)
    val uptime = Duration.between(startTime, Instant.now()).getSeconds
    lines += createInfoLine("Uptime", formatDuration(uptime), width)
    lines += createSeparator(width)

    // Footer with keyboard commands
    lines += createFooter(width)

    // Fill remaining space
    while (lines.length < height - 1) {
      lines += new AttributedString(" " * width)
    }

    lines.toSeq
  }

  private def createHeader(width: Int): AttributedString = {
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
  }

  private def createSectionHeader(title: String, width: Int): AttributedString = {
    val header = s" ● $title"
    val paddedHeader = header + " " * (width - header.length)
    new AttributedString(
      paddedHeader,
      AttributedStyle.DEFAULT.foreground(AttributedStyle.GREEN).bold()
    )
  }

  private def createInfoLine(label: String, value: String, width: Int): AttributedString = {
    val labelStyle = AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN)
    val valueStyle = AttributedStyle.DEFAULT.foreground(AttributedStyle.WHITE).bold()
    
    val labelPart = new AttributedString(s"   $label: ", labelStyle)
    val valuePart = new AttributedString(value, valueStyle)
    
    val combined = labelPart.concat(valuePart)
    val padding = " " * (width - combined.columnLength())
    combined.concat(new AttributedString(padding))
  }

  /** Create a progress bar with percentage. */
  private def createProgressBar(label: String, percentage: Double, width: Int): AttributedString = {
    val labelStyle = AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN)
    val barWidth = Math.min(40, width - 30) // Max 40 chars for bar
    val filled = ((percentage / 100.0) * barWidth).toInt
    val empty = barWidth - filled
    
    val labelPart = new AttributedString(s"   $label: ", labelStyle)
    val barStyle = AttributedStyle.DEFAULT.foreground(AttributedStyle.GREEN).bold()
    val emptyStyle = AttributedStyle.DEFAULT.foreground(AttributedStyle.WHITE)
    
    val filledBar = new AttributedString("█" * filled, barStyle)
    val emptyBar = new AttributedString("░" * empty, emptyStyle)
    val percentText = new AttributedString(f" $percentage%.2f%%", AttributedStyle.DEFAULT.foreground(AttributedStyle.WHITE).bold())
    
    val combined = labelPart.concat(filledBar).concat(emptyBar).concat(percentText)
    val padding = " " * (width - combined.columnLength())
    combined.concat(new AttributedString(padding))
  }

  /** Create a status line with color-coded status indicator. */
  private def createStatusLine(label: String, status: String, width: Int): AttributedString = {
    val labelStyle = AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN)
    val statusStyle = status.toLowerCase match {
      case s if s.contains("connected") || s.contains("running") => 
        AttributedStyle.DEFAULT.foreground(AttributedStyle.GREEN).bold()
      case s if s.contains("starting") || s.contains("initializing") => 
        AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW).bold()
      case s if s.contains("error") || s.contains("failed") => 
        AttributedStyle.DEFAULT.foreground(AttributedStyle.RED).bold()
      case _ => 
        AttributedStyle.DEFAULT.foreground(AttributedStyle.WHITE).bold()
    }
    
    val labelPart = new AttributedString(s"   $label: ", labelStyle)
    val statusPart = new AttributedString(s"● $status", statusStyle)
    
    val combined = labelPart.concat(statusPart)
    val padding = " " * (width - combined.columnLength())
    combined.concat(new AttributedString(padding))
  }

  /** Create peer status line with visual indicator. */
  private def createPeerStatusLine(count: Int, max: Int, width: Int): AttributedString = {
    val labelStyle = AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN)
    val peerStyle = if (count == 0) {
      AttributedStyle.DEFAULT.foreground(AttributedStyle.RED).bold()
    } else if (count < max / 2) {
      AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW).bold()
    } else {
      AttributedStyle.DEFAULT.foreground(AttributedStyle.GREEN).bold()
    }
    
    val labelPart = new AttributedString(s"   Peers: ", labelStyle)
    val peerText = s"$count / $max"
    val peerPart = new AttributedString(peerText, peerStyle)
    
    // Add visual indicator
    val indicator = if (count > 0) " ◆" * Math.min(count, 10) else ""
    val indicatorPart = new AttributedString(indicator, peerStyle)
    
    val combined = labelPart.concat(peerPart).concat(indicatorPart)
    val padding = " " * (width - combined.columnLength())
    combined.concat(new AttributedString(padding))
  }

  private def createSeparator(width: Int): AttributedString = {
    new AttributedString(
      "─" * width,
      AttributedStyle.DEFAULT.foreground(AttributedStyle.GREEN)
    )
  }

  private def createFooter(width: Int): AttributedString = {
    val footer = " Commands: [Q]uit | [R]efresh | [D]isable UI "
    val paddedFooter = footer + " " * (width - footer.length)
    new AttributedString(
      paddedFooter,
      AttributedStyle.DEFAULT
        .foreground(AttributedStyle.BLACK)
        .background(AttributedStyle.GREEN)
    )
  }

  /** Add a small version of the Ethereum Classic logo. */
  private def addSmallLogo(lines: scala.collection.mutable.ArrayBuffer[AttributedString], width: Int): Unit = {
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
      val padded = centered + " " * (width - centered.length)
      lines += new AttributedString(padded, greenStyle)
    }
  }

  private def formatNumber(n: Long): String = {
    "%,d".format(n)
  }

  private def formatDuration(seconds: Long): String = {
    val days = seconds / 86400
    val hours = (seconds % 86400) / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60

    if (days > 0) s"${days}d ${hours}h ${minutes}m"
    else if (hours > 0) s"${hours}h ${minutes}m ${secs}s"
    else if (minutes > 0) s"${minutes}m ${secs}s"
    else s"${secs}s"
  }

  private def clearScreen(): Unit = {
    terminal.foreach { term =>
      // Clear entire screen
      term.writer().print("\u001b[2J")
      // Move cursor to home position
      term.writer().print("\u001b[H")
      term.writer().flush()
    }
  }

  /** Check for keyboard input (non-blocking). */
  def checkInput(): Option[Char] = {
    if (!enabled || terminal.isEmpty) return None

    terminal.flatMap { term =>
      try {
        if (term.reader().peek(0) > 0) {
          Some(term.reader().read().toChar.toLower)
        } else {
          None
        }
      } catch {
        case _: Exception => None
      }
    }
  }

  /** Handle keyboard commands. Returns true if should continue, false if should quit. */
  def handleCommand(command: Char): Boolean = command match {
    case 'q' =>
      log.info("Quit command received")
      false
    case 'r' =>
      clearScreen()
      render()
      true
    case 'd' =>
      shutdown()
      log.info("Console UI disabled, switching to standard logging")
      true
    case _ =>
      true
  }

  /** Shutdown and cleanup the console UI. */
  def shutdown(): Unit = {
    terminal.foreach { term =>
      try {
        // Show cursor
        term.writer().print("\u001b[?25h")
        // Reset colors
        term.writer().print("\u001b[0m")
        // Clear screen
        term.writer().print("\u001b[2J")
        term.writer().print("\u001b[H")
        term.writer().flush()
        term.close()
      } catch {
        case e: Exception =>
          log.error(s"Error shutting down console UI: ${e.getMessage}")
      }
    }
    terminal = None
    enabled = false
    log.info("Console UI shutdown complete")
  }
}

object ConsoleUI {
  // Singleton instance
  private var instance: Option[ConsoleUI] = None

  /** Get or create the singleton instance. */
  def getInstance(): ConsoleUI = {
    instance match {
      case Some(ui) => ui
      case None =>
        val ui = new ConsoleUI()
        instance = Some(ui)
        ui
    }
  }

  /** Reset the singleton instance (useful for testing). */
  def reset(): Unit = {
    instance.foreach(_.shutdown())
    instance = None
  }
}
