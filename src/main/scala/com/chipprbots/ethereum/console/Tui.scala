package com.chipprbots.ethereum.console

import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder

import com.chipprbots.ethereum.utils.Logger

/** Main TUI interface with display logic.
  *
  * This is the main entry point for the Terminal User Interface. It handles:
  *   - Terminal initialization and management
  *   - State management through TuiState
  *   - Rendering through TuiRenderer
  *   - Log suppression through TuiLogSuppressor
  *   - Keyboard input handling
  */
class Tui(config: TuiConfig = TuiConfig.default) extends Logger:

  private var terminal: Option[Terminal] = None
  private var enabled = true
  @volatile private var state: TuiState = TuiState.initial

  private val renderer: TuiRenderer = TuiRenderer(config)
  private val logSuppressor: TuiLogSuppressor = TuiLogSuppressor()

  /** Initialize the TUI.
    *
    * @return
    *   true if initialization was successful
    */
  def initialize(): Boolean =
    if !enabled then return false

    try
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

        // Suppress console logs if configured
        if config.suppressConsoleLogs then
          if logSuppressor.suppressConsoleLogs() then log.debug("Console logs suppressed for TUI")
          else log.warn("Failed to suppress console logs")

        // Show startup banner
        if config.bannerDisplayDurationMs > 0 then showStartupBanner()
      }

      log.info("TUI initialized")
      true
    catch
      case e: Exception =>
        log.warn(s"Failed to initialize TUI: ${e.getMessage}. Falling back to standard logging.")
        enabled = false
        terminal = None
        false

  /** Disable the TUI. */
  def disable(): Unit =
    enabled = false

  /** Check if the TUI is enabled. */
  def isEnabled: Boolean = enabled && terminal.isDefined

  /** Get the current TUI state. */
  def getState: TuiState = state

  // State update methods
  def updatePeerCount(count: Int, max: Int): Unit =
    state = state.withPeerCount(count, max)

  def updateBlockInfo(current: Long, best: Long): Unit =
    state = state.withBlockInfo(current, best)

  def updateNetwork(name: String): Unit =
    state = state.withNetworkName(name)

  def updateSyncStatus(status: String): Unit =
    state = state.withSyncStatus(status)

  def updateConnectionStatus(status: String): Unit =
    state = state.withConnectionStatus(status)

  def updateNodeSettings(settings: NodeSettings): Unit =
    state = state.withNodeSettings(settings)

  /** Render the TUI. */
  def render(): Unit =
    if !enabled || terminal.isEmpty then return

    terminal.foreach { term =>
      try
        val width = term.getWidth
        val height = term.getHeight

        // Move cursor to top-left
        term.writer().print("\u001b[H")

        val lines = renderer.render(state, width, height)
        lines.foreach { line =>
          term.writer().println(line.toAnsi())
        }

        term.writer().flush()
      catch
        case e: Exception =>
          log.error(s"Error rendering TUI: ${e.getMessage}")
    }

  /** Check for keyboard input (non-blocking). */
  def checkInput(): Option[Char] =
    if !enabled || terminal.isEmpty then return None

    terminal.flatMap { term =>
      try
        if term.reader().peek(0) > 0 then Some(term.reader().read().toChar.toLower)
        else None
      catch case _: Exception => None
    }

  /** Handle keyboard commands.
    * @return
    *   true if should continue, false if should quit
    */
  def handleCommand(command: Char): Boolean = command match
    case 'q' =>
      log.info("Quit command received")
      false
    case 'r' =>
      clearScreen()
      render()
      true
    case 'd' =>
      shutdown()
      log.info("TUI disabled, switching to standard logging")
      false
    case _ =>
      true

  /** Shutdown and cleanup the TUI. */
  def shutdown(): Unit = synchronized {
    // Restore console logs first
    if logSuppressor.isConsoleSuppressed then
      if logSuppressor.restoreConsoleLogs() then log.debug("Console logs restored")
      else log.warn("Failed to restore console logs")

    terminal.foreach { term =>
      try
        // Show cursor
        term.writer().print("\u001b[?25h")
        // Reset colors
        term.writer().print("\u001b[0m")
        // Clear screen
        term.writer().print("\u001b[2J")
        term.writer().print("\u001b[H")
        term.writer().flush()
        term.close()
      catch
        case e: Exception =>
          log.error(s"Error shutting down TUI: ${e.getMessage}")
    }
    terminal = None
    enabled = false
    log.info("TUI shutdown complete")
  }

  private def showStartupBanner(): Unit =
    terminal.foreach { term =>
      val width = term.getWidth
      val bannerLines = renderer.renderStartupBanner(width)

      term.writer().print("\u001b[H") // Move to top
      bannerLines.foreach { line =>
        term.writer().println(line.toAnsi())
      }
      term.writer().flush()

      // Brief pause to show banner
      Thread.sleep(config.bannerDisplayDurationMs)
    }

  private def clearScreen(): Unit =
    terminal.foreach { term =>
      // Clear entire screen
      term.writer().print("\u001b[2J")
      // Move cursor to home position
      term.writer().print("\u001b[H")
      term.writer().flush()
    }

object Tui:
  // Singleton instance
  private var instance: Option[Tui] = None

  /** Get or create the singleton instance with default config. */
  def getInstance(): Tui =
    instance match
      case Some(tui) => tui
      case None =>
        val tui = new Tui()
        instance = Some(tui)
        tui

  /** Get or create the singleton instance with custom config. */
  def getInstance(config: TuiConfig): Tui =
    instance match
      case Some(tui) => tui
      case None =>
        val tui = new Tui(config)
        instance = Some(tui)
        tui

  /** Reset the singleton instance (useful for testing). */
  def reset(): Unit =
    instance.foreach(_.shutdown())
    instance = None
