package com.chipprbots.ethereum.console

import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

import scala.util.control.NonFatal

import org.jline.terminal.Attributes
import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder
import org.jline.terminal.spi.SystemStream
import org.jline.terminal.spi.TerminalExt
import org.jline.utils.AttributedString
import org.jline.utils.InfoCmp.Capability
import org.jline.utils.NonBlockingReader

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
  * WITHOUT holding the lock, so a pending read never blocks a redraw or a shutdown. The state is one atomic reference:
  * the frame loop and the node's own updates (network, settings) may change it from different threads.
  *
  * @param config
  *   the TUI's settings, with the environment already applied ([[TuiConfig.load]])
  * @param terminalFactory
  *   opens the terminal; the default is the process's real TTY (tests pass an in-memory terminal)
  * @param processOutput
  *   the process stream behind a terminal, used to restore the screen when JLine has closed the terminal before
  *   [[shutdown]] runs; None for a terminal that is not the process's own (tests pass an in-memory stream)
  * @param clock
  *   the TUI's start time, for uptime; after that every time comes from the status snapshots (C-3)
  */
class Tui(
    val config: TuiConfig = TuiConfig.default,
    terminalFactory: () => Terminal = Tui.systemTerminal,
    processOutput: Terminal => Option[OutputStream] = Tui.processOutputOf,
    clock: Clock = Clock.systemUTC()
) extends Logger:

  private var terminal: Option[Terminal] = None
  private var savedAttributes: Option[Attributes] = None
  private var directOutput: Option[OutputStream] = None

  /** The user's screen is in the alternate buffer with the cursor hidden, and has not been restored yet. */
  private var inAltScreen = false
  private var lastSize: (Int, Int) = (0, 0)

  /** The last frame written, so an unchanged frame is not written again (FR-010). */
  private var lastLines: Option[Seq[AttributedString]] = None
  @volatile private var enabled = true

  /** A frame failed to draw: logged at ERROR once, with its stack trace; the ones after it at DEBUG until one draws. */
  private var renderFailing = false

  private val state: AtomicReference[TuiState] =
    val started = clock.instant()
    new AtomicReference(
      TuiState.initial.copy(
        glyphs = config.glyphs,
        motion = config.motion,
        splashUntilTick = Option.when(config.splashTicks > 0)(config.splashTicks),
        startTime = started,
        now = started
      )
    )

  /** Chosen once the terminal is open, from `NO_COLOR` and the colours it reports (spec 027 FR-009). */
  @volatile private var theme: TuiTheme = TuiTheme.of(config.noColor, Tui.DefaultMaxColors)

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
        theme = TuiTheme.of(config.noColor, Tui.maxColors(term))
        directOutput = processOutput(term)
        savedAttributes = Some(term.enterRawMode())
        // Alternate screen buffer (restored on exit, so the user's scrollback survives) + hidden cursor.
        write(term, Tui.EnterAltScreen + Tui.HideCursor + Tui.ClearScreen)
        inAltScreen = true

        // Suppress console logs if configured
        if config.suppressConsoleLogs then
          if logSuppressor.suppressConsoleLogs() then log.debug("Console logs suppressed for TUI")
          else log.warn("Failed to suppress console logs")

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
  def getState: TuiState = state.get

  private def modify(f: TuiState => TuiState): Unit =
    state.updateAndGet(s => f(s))
    ()

  // State update methods
  private[console] def updatePeerCount(count: Int, max: Int): Unit = modify(_.withPeerCount(count, max))

  private[console] def updateBlockInfo(current: Long, best: Long): Unit = modify(_.withBlockInfo(current, best))

  def updateNetwork(name: String): Unit = modify(_.withNetworkName(name))

  private[console] def updateSyncStatus(status: String): Unit = modify(_.withSyncStatus(status))

  def updateConnectionStatus(status: String): Unit = modify(_.withConnectionStatus(status))

  def updateNodeSettings(settings: NodeSettings): Unit = modify(_.withNodeSettings(settings))

  private[console] def updateStatus(status: NodeStatusSnapshot): Unit = modify(_.withStatus(status))

  /** A snapshot no newer poll has replaced for two poll intervals and one ask wait is stale: the probe is late. */
  private val snapshotStaleAfter =
    Duration.ofMillis(2 * config.statusPollIntervalMs + TuiStatusProbe.DefaultAskTimeout.toMillis)

  /** The next frame's state: the frame counter moves on (unless paused), and the latest snapshot, if any, applies, read
    * at this TUI's clock, so a probe that stopped publishing shows ([[NodeStatusSnapshot.agedAt]]). Before the first
    * snapshot this clock is the state's `now`, so the uptime counts while the node starts; the renderer reads no clock.
    */
  def nextFrame(snapshot: Option[NodeStatusSnapshot]): Unit =
    val now = clock.instant()
    modify { s =>
      snapshot match
        case Some(polled) => s.nextFrame(Some(polled.agedAt(now, snapshotStaleAfter)))
        case None         => s.nextFrame(None).copy(now = now)
    }

  /** Render the TUI, unless the frame equals the last one written (FR-010).
    *
    * @return
    *   whether the frame moves with `tick`: the frame loop's pace (Q1)
    */
  def render(): Boolean = synchronized {
    if !enabled then false
    else
      terminal.fold(false) { term =>
        try
          val (width, height) = Tui.sizeOf(term)
          // A resize leaves stale glyphs outside the new frame; wipe them.
          val resized = lastSize != ((width, height))
          lastSize = (width, height)

          val frame = renderer.frame(state.get, width, height, theme)
          if resized || !lastLines.contains(frame.lines) then
            val clear = if resized then Tui.ClearScreen else ""
            // Home the cursor and overwrite in place. No newline after the last line: a frame is exactly `height`
            // rows, and a trailing newline on the bottom row would scroll the whole screen up by one.
            write(term, clear + Tui.CursorHome + frame.lines.map(_.toAnsi(term)).mkString("\r\n"))
            lastLines = Some(frame.lines)
          renderFailing = false
          frame.animated
        catch
          case e: IllegalStateException if Tui.isClosedTerminal(e) =>
            terminalLost("drawing a frame")
            false
          case NonFatal(e) =>
            if renderFailing then log.debug("Error rendering TUI again: {}", e.getMessage)
            else log.error("Error rendering TUI: {}", e.getMessage, e)
            renderFailing = true
            false
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
        if c >= 0 then Some(c.toChar.toLower)
        else
          // End of input: nothing can be typed again, and every later read would return at once.
          if c == NonBlockingReader.EOF then
            val wasUp = synchronized(enabled)
            terminalLost("reading a key (end of input)")
            if wasUp then
              log.warn("TUI keyboard input reached end of input; the terminal and standard logging are restored")
          None
      catch
        case e: IllegalStateException if Tui.isClosedTerminal(e) =>
          terminalLost("reading a key")
          None
        case _: InterruptedIOException =>
          // The frame loop is being stopped (TuiUpdater.stop, on Ctrl-C, SIGTERM or `q`): JLine answers the interrupt
          // with InterruptedIOException. Not a failed read; keep the interrupt for the loop to see.
          Thread.currentThread().interrupt()
          None
        case NonFatal(e) =>
          // Keys are how the TUI is left (q, d): without them, give the terminal and the console logs back, then log,
          // so the ERROR reaches the console.
          shutdown()
          log.error("TUI could not read a key ({}); the terminal and standard logging are restored", e.getMessage, e)
          None
    }

  /** Handle keyboard commands. While the splash is on screen any key skips it, and does nothing else (FR-013). */
  def handleCommand(command: Char): TuiCommand =
    val height = synchronized(lastSize._2)
    if state.get.splashCover(height).isDefined then
      modify(_.skipSplash)
      TuiCommand.Continue
    else dispatch(command)

  private def dispatch(command: Char): TuiCommand = command match
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
    case 'p' =>
      // FR-012: pause the frame counter (polling continues), or resume the configured motion.
      modify(_.togglePause(config.motion))
      TuiCommand.Continue
    case _ =>
      TuiCommand.Continue

  /** The terminal went away under the TUI: JLine's shutdown hook closed it (Ctrl-C, SIGTERM), or its input ended. Stop
    * drawing and reading at once, and restore what can be restored, so the frame loop ends instead of failing on every
    * pass (B-045). Logged once, at WARN, by [[releaseTerminal]].
    */
  private def terminalLost(during: String): Unit = synchronized {
    if enabled then
      log.debug("TUI terminal lost while {}", during)
      shutdown()
  }

  /** Shutdown and cleanup the TUI: the screen, the cursor and the terminal mode. Idempotent; a second call writes
    * nothing.
    *
    * `q` calls this before the JVM exits ([[TuiUpdater.restoreThenQuit]]). On Ctrl-C or SIGTERM it runs from a shutdown
    * hook, after JLine's own hook may already have closed the terminal; see [[releaseTerminal]].
    */
  def shutdown(): Unit = synchronized {
    val wasActive = terminal.isDefined
    if logSuppressor.isConsoleSuppressed && !logSuppressor.restoreConsoleLogs() then
      log.error("Failed to restore console logs")
    releaseTerminal()
    enabled = false
    if wasActive then log.info("TUI shutdown complete")
  }

  /** Restore the terminal to the state the user had before the TUI took it over.
    *
    * JVM shutdown hooks run concurrently and in no order, and JLine registers one that closes the system terminal. It
    * can close the terminal before any step here, or between two of them. JLine's close restores the terminal mode but
    * not the screen, so from the step that finds the terminal closed on, nothing else goes through it: the remaining
    * steps are skipped, and a screen not yet reset is reset through the process stream behind the terminal instead.
    */
  private def releaseTerminal(): Unit =
    // Each step runs regardless of the others (a failed write must not leave the terminal in raw mode), except after
    // one finds the terminal closed.
    terminal.foreach { term =>
      var closedAt: Option[String] = None
      def step(name: String)(f: => Unit): Unit =
        if closedAt.isEmpty then
          try f
          catch
            case e: IllegalStateException if Tui.isClosedTerminal(e) => closedAt = Some(name)
            case NonFatal(e) => log.error(s"Error shutting down TUI ($name): ${e.getMessage}")
      step("reset screen") {
        write(term, Tui.RestoreScreen)
        inAltScreen = false
      }
      step("restore terminal mode")(savedAttributes.foreach(term.setAttributes))
      step("close terminal")(term.close())
      closedAt.foreach(closedByJLine)
    }
    terminal = None
    savedAttributes = None
    directOutput = None
    inAltScreen = false

  /** [[releaseTerminal]] found the terminal closed at `step`: JLine's shutdown hook ran first and restored the terminal
    * mode. Reset the screen through the process stream if that had not happened yet. An expected race, so WARN, once; a
    * screen that cannot be reset is an ERROR.
    */
  private def closedByJLine(step: String): Unit =
    val closed = s"TUI terminal already closed by JLine's shutdown hook (at $step); it restored the terminal mode"
    if !inAltScreen then log.warn(closed)
    else
      directOutput match
        case Some(out) =>
          try
            out.write(Tui.RestoreScreen.getBytes(StandardCharsets.US_ASCII))
            out.flush()
            log.warn(s"$closed; the screen and cursor were restored through the process output")
          catch
            case NonFatal(e) =>
              log.error(s"$closed, but restoring the screen through the process output failed: ${e.getMessage}")
        case None =>
          log.error(s"$closed, but the screen was not restored: the terminal has no process output to write it to")

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

  /** Leaves the TUI's screen: attributes reset, cursor shown, back to the normal buffer and the user's scrollback. */
  private[console] val RestoreScreen = ResetColors + ShowCursor + ExitAltScreen

  /** Size used when the terminal cannot report one (some PTYs report 0x0). */
  private[console] val FallbackSize: (Int, Int) = (80, 24)

  /** Colours assumed when the terminal does not report `max_colors`: the 16-colour theme the TUI has always drawn. */
  private[console] val DefaultMaxColors: Int = 8

  /** The colours the terminal reports (terminfo `max_colors`). */
  private[console] def maxColors(term: Terminal): Int =
    Option(term.getNumericCapability(Capability.max_colors)).map(_.intValue).getOrElse(DefaultMaxColors)

  /** JLine's message for any use of a closed terminal (`AbstractTerminal.checkClosed`, JLine 3.30). */
  private val ClosedTerminalMessage = "Terminal has been closed"

  /** The process's real terminal. Throws (rather than degrading to a dumb terminal) when there is none. */
  def systemTerminal(): Terminal =
    TerminalBuilder.builder().system(true).dumb(false).build()

  /** The process stream a system terminal writes to: stdout, or stderr when JLine chose it because stdout is not a
    * terminal. None for any other terminal. The stream is never closed; that would close the process's descriptor.
    */
  private[console] def processOutputOf(term: Terminal): Option[OutputStream] =
    term match
      case ext: TerminalExt =>
        Option(ext.getSystemStream).collect {
          case SystemStream.Output => new FileOutputStream(FileDescriptor.out)
          case SystemStream.Error  => new FileOutputStream(FileDescriptor.err)
        }
      case _ => None

  private[console] def isClosedTerminal(e: IllegalStateException): Boolean = e.getMessage == ClosedTerminalMessage

  private[console] def isDumb(term: Terminal): Boolean =
    Option(term.getType).forall(t => t.startsWith(Terminal.TYPE_DUMB))

  private[console] def sizeOf(term: Terminal): (Int, Int) =
    val w = term.getWidth
    val h = term.getHeight
    if w > 0 && h > 0 then (w, h) else FallbackSize

  // Singleton instance
  private var instance: Option[Tui] = None

  /** The instance [[getInstance]] created, if any; never creates one. A node started without `--tui` has none, and must
    * not get one by asking (B-045).
    */
  def current: Option[Tui] = synchronized(instance)

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
