package com.chipprbots.ethereum.console

import scala.util.control.NonFatal

import org.jline.terminal.Attributes
import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder

import com.chipprbots.ethereum.utils.Logger

/** Outcome of a keyboard command. */
enum TuiCommand:
  /** Keep running the TUI. */
  case Continue

  /** Stop the node. */
  case Quit

  /** The TUI has been torn down; the node keeps running with standard console logging. */
  case Disable

/** Main TUI interface with display logic.
  *
  * This is the main entry point for the Terminal User Interface. It handles:
  *   - Terminal initialization and management
  *   - State management through TuiState
  *   - Rendering through TuiRenderer
  *   - Log suppression through TuiLogSuppressor
  *   - Keyboard input handling
  *
  * Threading: `render`, `initialize` and `shutdown` are serialized on this instance. `checkInput` waits for a key
  * WITHOUT holding the lock, so a pending read never blocks a redraw or a shutdown.
  *
  * @param terminalFactory
  *   opens the terminal; the default is the process's real TTY (tests pass an in-memory terminal)
  */
class Tui(config: TuiConfig = TuiConfig.default, terminalFactory: () => Terminal = Tui.systemTerminal) extends Logger:

  private var terminal: Option[Terminal] = None
  private var savedAttributes: Option[Attributes] = None
  private var lastSize: (Int, Int) = (0, 0)
  @volatile private var enabled = true
  @volatile private var state: TuiState = TuiState.initial

  private val renderer: TuiRenderer = TuiRenderer(config)
  private val logSuppressor: TuiLogSuppressor = TuiLogSuppressor()

  /** Initialize the TUI.
    *
    * Fails (returning false, with standard logging left in place) when there is no interactive terminal — stdin/stdout
    * redirected, a container started without `-t`, a systemd unit — rather than painting escape sequences into a log
    * stream.
    *
    * @return
    *   true if initialization was successful
    */
  def initialize(): Boolean = synchronized {
    if !enabled || terminal.isDefined then terminal.isDefined
    else
      try
        val term = terminalFactory()
        if Tui.isDumb(term) then
          term.close()
          throw new IllegalStateException(s"terminal type '${term.getType}' cannot be driven by the TUI")

        terminal = Some(term)
        savedAttributes = Some(term.enterRawMode())
        // Alternate screen buffer (restored on exit, so the user's scrollback survives) + hidden cursor.
        write(term, Tui.EnterAltScreen + Tui.HideCursor + Tui.ClearScreen)

        // Suppress console logs if configured
        if config.suppressConsoleLogs then
          if logSuppressor.suppressConsoleLogs() then log.debug("Console logs suppressed for TUI")
          else log.warn("Failed to suppress console logs")

        // Show startup banner
        if config.bannerDisplayDurationMs > 0 then showStartupBanner(term)

        log.info("TUI initialized")
        true
      catch
        case NonFatal(e) =>
          if logSuppressor.isConsoleSuppressed then logSuppressor.restoreConsoleLogs()
          releaseTerminal()
          log.warn(s"Failed to initialize TUI: ${e.getMessage}. Falling back to standard logging.")
          enabled = false
          false
  }

  /** Disable the TUI. */
  def disable(): Unit =
    enabled = false

  /** Check if the TUI is enabled. */
  def isEnabled: Boolean = synchronized(enabled && terminal.isDefined)

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

  def updateStatus(status: NodeStatusSnapshot): Unit =
    state = state.withStatus(status)

  /** Render the TUI. */
  def render(): Unit = synchronized {
    if enabled then
      terminal.foreach { term =>
        try
          val (width, height) = Tui.sizeOf(term)
          // A resize leaves stale glyphs outside the new frame; wipe them.
          val clear = if lastSize != ((width, height)) then Tui.ClearScreen else ""
          lastSize = (width, height)

          val lines = renderer.render(state, width, height)
          // Home the cursor and overwrite in place. No newline after the last line: a frame is exactly `height` rows,
          // and a trailing newline on the bottom row would scroll the whole screen up by one.
          write(term, clear + Tui.CursorHome + lines.map(_.toAnsi(term)).mkString("\r\n"))
        catch
          case NonFatal(e) =>
            log.error(s"Error rendering TUI: ${e.getMessage}")
      }
  }

  /** Wait up to `timeoutMs` for a key press.
    *
    * @return
    *   the lower-cased key, or None if none arrived (or the TUI is not active)
    */
  def checkInput(timeoutMs: Long = 1L): Option[Char] =
    val term = synchronized(if enabled then terminal else None)
    term.flatMap { t =>
      try
        // NonBlockingReader treats a timeout of 0 as "wait forever" — never pass it through.
        val c = t.reader().read(Math.max(1L, timeoutMs))
        if c >= 0 then Some(c.toChar.toLower) else None
      catch case NonFatal(_) => None
    }

  /** Handle keyboard commands. */
  def handleCommand(command: Char): TuiCommand = command match
    case 'q' =>
      log.info("Quit command received")
      TuiCommand.Quit
    case 'r' =>
      synchronized {
        lastSize = (0, 0) // forces a full clear on the next frame
      }
      render()
      TuiCommand.Continue
    case 'd' =>
      shutdown()
      log.info("TUI disabled, switching to standard logging")
      TuiCommand.Disable
    case _ =>
      TuiCommand.Continue

  /** Shutdown and cleanup the TUI. Idempotent. */
  def shutdown(): Unit = synchronized {
    val wasActive = terminal.isDefined
    if logSuppressor.isConsoleSuppressed && !logSuppressor.restoreConsoleLogs() then
      log.error("Failed to restore console logs")
    releaseTerminal()
    enabled = false
    if wasActive then log.info("TUI shutdown complete")
  }

  /** Restore the terminal to the state the user had before the TUI took it over. */
  private def releaseTerminal(): Unit =
    // Each step runs regardless of the others: a failed write must not leave the terminal in raw mode.
    terminal.foreach { term =>
      def attempt(step: String)(f: => Unit): Unit =
        try f
        catch case NonFatal(e) => log.error(s"Error shutting down TUI ($step): ${e.getMessage}")
      attempt("reset screen")(write(term, Tui.ResetColors + Tui.ShowCursor + Tui.ExitAltScreen))
      attempt("restore terminal mode")(savedAttributes.foreach(term.setAttributes))
      attempt("close terminal")(term.close())
    }
    terminal = None
    savedAttributes = None

  private def showStartupBanner(term: Terminal): Unit =
    val (width, _) = Tui.sizeOf(term)
    val bannerLines = renderer.renderStartupBanner(width)
    write(term, Tui.CursorHome + bannerLines.map(_.toAnsi(term)).mkString("\r\n"))
    // Brief pause to show banner
    Thread.sleep(config.bannerDisplayDurationMs)
    write(term, Tui.ClearScreen)

  private def write(term: Terminal, s: String): Unit =
    term.writer().print(s)
    term.writer().flush()

object Tui:
  private[console] val EnterAltScreen = "\u001b[?1049h"
  private[console] val ExitAltScreen = "\u001b[?1049l"
  private[console] val HideCursor = "\u001b[?25l"
  private[console] val ShowCursor = "\u001b[?25h"
  private[console] val ResetColors = "\u001b[0m"
  private[console] val ClearScreen = "\u001b[2J\u001b[H"
  private[console] val CursorHome = "\u001b[H"

  /** Size used when the terminal cannot report one (some PTYs report 0x0). */
  private[console] val FallbackSize: (Int, Int) = (80, 24)

  /** The process's real terminal. Throws (rather than degrading to a dumb terminal) when there is none. */
  def systemTerminal(): Terminal =
    TerminalBuilder.builder().system(true).dumb(false).build()

  private[console] def isDumb(term: Terminal): Boolean =
    Option(term.getType).forall(t => t.startsWith(Terminal.TYPE_DUMB))

  private[console] def sizeOf(term: Terminal): (Int, Int) =
    val w = term.getWidth
    val h = term.getHeight
    if w > 0 && h > 0 then (w, h) else FallbackSize

  // Singleton instance
  private var instance: Option[Tui] = None

  /** Get or create the singleton instance with default config. */
  def getInstance(): Tui = getInstance(TuiConfig.default)

  /** Get or create the singleton instance with custom config.
    *
    * Note: If an instance already exists, the provided config is ignored and the existing instance is returned. This is
    * intentional to maintain singleton semantics. Use `reset()` first if you need to change the configuration.
    */
  def getInstance(config: TuiConfig): Tui = synchronized {
    instance match
      case Some(tui) => tui
      case None =>
        val tui = new Tui(config)
        instance = Some(tui)
        tui
  }

  /** Reset the singleton instance (useful for testing). */
  def reset(): Unit = synchronized {
    instance.foreach(_.shutdown())
    instance = None
  }
