package com.chipprbots.ethereum.console

import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

import scala.util.control.NonFatal

import org.jline.terminal.Attributes
import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder
import org.jline.terminal.spi.SystemStream
import org.jline.terminal.spi.TerminalExt
import org.jline.utils.InfoCmp.Capability
import org.jline.utils.NonBlockingReader

import com.chipprbots.ethereum.console.gfx.ArtStore
import com.chipprbots.ethereum.console.gfx.Kitty
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
  *   - Log suppression through TuiLogSuppressor, and the log tail through TuiLogRingAppender
  *   - Keyboard input: bytes to keys through KeyDecoder, keys to state through Update
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
  *   the TUI's start time and uptime until the first snapshot, and the clock each snapshot is aged against
  *   ([[NodeStatusSnapshot.agedAt]]). It must agree with the probe's clock (`TuiStatusProbe`'s `clock`), which stamps
  *   the snapshots: both default to the system UTC clock, and a test that injects one injects both.
  * @param logRing
  *   the log tail; None builds one from `config` ([[TuiLogRingAppender.of]]). It is attached once the console is
  *   suppressed and detached before the console is restored (C-11)
  * @param queryTimeoutMs
  *   how long the terminal's answers about its graphics are waited for at the start (spec 029 FR-013)
  */
class Tui(
    val config: TuiConfig = TuiConfig.default,
    terminalFactory: () => Terminal = Tui.systemTerminal,
    processOutput: Terminal => Option[OutputStream] = Tui.processOutputOf,
    clock: Clock = Clock.systemUTC(),
    logRing: Option[TuiLogRingAppender] = None,
    queryTimeoutMs: Long = TerminalQuery.TimeoutMillis
) extends Logger:

  private var terminal: Option[Terminal] = None
  private var savedAttributes: Option[Attributes] = None
  private var directOutput: Option[OutputStream] = None

  /** The user's screen is in the alternate buffer with the cursor hidden, and has not been restored yet. */
  private var inAltScreen = false
  private var lastSize: (Int, Int) = (0, 0)

  /** What the terminal shows: an unchanged frame is not written again (FR-010), and of a changed one only the rows and
    * images that differ (spec 029 FR-017).
    */
  private var shown: FrameWriter.Shown = FrameWriter.Blank

  /** The next frame is drawn on a cleared screen: `r`, a change of graphics mode, new art. */
  private var clearNext = false

  /** An image has gone to this terminal: the teardown removes every one (spec 029 FR-016). */
  private var imagesSent = false

  /** The cell's size was asked after a resize, and the answer has not come. */
  private var cellAsked = false

  /** What the terminal said of itself when the TUI started (spec 029 FR-013). */
  private var caps: TerminalCaps = TerminalCaps.None
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

  /** The sprite sheets: read when a mode that draws them is first chosen, and again by `r` on the Art screen. */
  private var art: ArtStore = ArtStore.Empty
  private var artLoaded = false
  private var renderer: TuiRenderer = TuiRenderer(config)
  private val logSuppressor: TuiLogSuppressor = TuiLogSuppressor()
  private val ring: TuiLogRingAppender = logRing.getOrElse(TuiLogRingAppender.of(config))

  /** The ring is on the root logger: set once its attach returns, cleared before its detach runs. */
  private var ringAttached = false

  /** The ring's next sequence number when the frame loop last read its lines: unchanged, nothing new to read. */
  @volatile private var logsRead = -1L

  /** The terminal's bytes become keys here (spec 029 FR-004). Only the frame loop's thread reads keys, so the decoder's
    * place in the input is its own.
    */
  private var decoder: KeyDecoder = new KeyDecoder()
  private var decoding: KeyDecoder.State = KeyDecoder.State.Ground

  /** How long the input has been quiet inside a sequence or a paste whose end has not come. */
  private var quietMillis: Long = 0L

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
        decoder = KeyDecoder.forTerminal(term, cellReports = true)
        decoding = KeyDecoder.State.Ground
        // Alternate screen buffer (restored on exit, so the user's scrollback survives) + hidden cursor. Bracketed
        // paste, so that pasted text arrives marked as such and cannot be read as keys (spec 029 FR-009).
        write(term, Tui.EnterAltScreen + Tui.HideCursor + KeyDecoder.EnablePaste + Tui.ClearScreen)
        inAltScreen = true
        // What the terminal can draw is asked once, here, before a key is read: its answers never reach the key loop
        // (spec 029 FR-013).
        caps = askTerminal(term)
        chooseGraphics(config.graphics)

        // Suppress console logs if configured
        if config.suppressConsoleLogs then
          if logSuppressor.suppressConsoleLogs() then log.debug("Console logs suppressed for TUI")
          else log.warn("Failed to suppress console logs")

        // The log tail goes on once the console is off (C-11). It is attached whether or not there was a console to
        // suppress: it is the Logs view's only source. A failure here gives the terminal and the console back, below.
        ring.attach()
        ringAttached = true

        log.info("TUI initialized")
        true
      catch
        case NonFatal(e) =>
          teardown()
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

  /** The chain's identity and fork timeline, once the node has read its configuration (spec 027 FR-017). */
  def updateChain(chain: ChainFacts): Unit = modify(_.copy(chain = Some(chain)))

  private[console] def updateStatus(status: NodeStatusSnapshot): Unit = modify(_.withStatus(status))

  /** What the terminal says of itself. The questions go out only where their answer can matter: not under `NO_COLOR` or
    * below 256 colours (the mode is text), not when blocks or text were asked for, and under a multiplexer only when
    * pixels were asked for by name.
    */
  private def askTerminal(term: Terminal): TerminalCaps =
    val colors = Tui.maxColors(term)
    val ask = !config.noColor && colors >= GraphicsMode.MinColors && (config.graphics match
      case GraphicsChoice.Pixel => true
      case GraphicsChoice.Auto  => !config.multiplexer
      case _                    => false
    )
    val answers = if ask then TerminalQuery.run(term, queryTimeoutMs) else TerminalQuery.NotAsked
    // The key decoder starts where the answers left off: a reply the bound cut short is finished as a sequence when
    // its rest arrives, and nothing typed while the question was open is acted on.
    decoding = answers.raw.foldLeft(KeyDecoder.State.Ground: KeyDecoder.State)((s, c) => decoder.step(s, c)._1)
    TerminalCaps(answers.graphics, answers.cell, colors, config.multiplexer, config.utf8Locale)

  /** Draw graphics as `choice` asks, as far as this terminal can (spec 029 FR-013); what it cannot do is said in the
    * footer and the log. The art is read the first time a mode that draws it is chosen, and the next frame starts from
    * a cleared screen.
    */
  private def chooseGraphics(choice: GraphicsChoice): Unit =
    val (mode, warning) = GraphicsMode.choose(choice, caps, config.noColor)
    setGraphics(mode)
    warning.foreach { w =>
      log.warn("TUI {}", w)
      modify(_.copy(notice = Some(w)))
    }

  private def setGraphics(mode: GraphicsMode): Unit =
    if mode != GraphicsMode.Text && !artLoaded then loadArt()
    if state.get.graphics != mode then
      log.info("TUI graphics: {}", mode.label)
      modify(_.copy(graphics = mode))
      clearNext = true

  /** Reads the sprite sheets: `fukuii.tui.art-dir` if set, else the shipped ones. A sheet that breaks contracts/art.md
    * is named in the log and left out; nothing here can stop the node.
    */
  private def loadArt(): Unit =
    val loaded =
      try config.artDir.fold(ArtStore.bundled())(dir => ArtStore.fromDirectory(Path.of(dir)))
      catch
        case NonFatal(e) =>
          new ArtStore(Map.empty, Seq(s"the art could not be read: ${e.getMessage}"))
    loaded.problems.foreach(p => log.warn("TUI art: {}", p))
    art = loaded
    artLoaded = true
    renderer = TuiRenderer(config, loaded)
    clearNext = true

  /** A snapshot no newer poll has replaced for two poll intervals and one ask wait is stale: the probe is late.
    * [[Sourced.UnresponsiveAfter]] is a fixed 5 s, so from a poll interval of 2.25 s (when this reaches 5 s) a late
    * snapshot's sources skip Stale and go straight to Unresponsive ([[NodeStatusSnapshot.agedAt]]).
    */
  private val snapshotStaleAfter =
    Duration.ofMillis(2 * config.statusPollIntervalMs + TuiStatusProbe.DefaultAskTimeout.toMillis)

  /** The next frame's state: the frame counter moves on (unless paused), and the latest snapshot, if any, applies, read
    * at this TUI's clock, so a probe that stopped publishing shows ([[NodeStatusSnapshot.agedAt]]). Before the first
    * snapshot this clock is the state's `now`, so the uptime counts while the node starts; the renderer reads no clock.
    */
  def nextFrame(snapshot: Option[NodeStatusSnapshot]): Unit =
    val now = clock.instant()
    val logs = newLogLines()
    modify { s =>
      val next = snapshot match
        case Some(polled) => s.nextFrame(Some(polled.agedAt(now, snapshotStaleAfter)))
        case None         => s.nextFrame(None).copy(now = now)
      logs.fold(next)(next.withLogs)
    }

  /** The ring's lines, if it has kept any since the last frame read them; the read takes no lock (FR-021). */
  private def newLogLines(): Option[Vector[LogLine]] =
    if ring.appended == logsRead then None
    else
      val lines = ring.lines
      // A line whose writer has its number but has not stored it yet is missing from this read. If it is the newest,
      // `appended` is ahead of `logsRead` and the next frame reads again; if a newer one was stored, the gap is picked up
      // on the next append.
      logsRead = lines.lastOption.fold(0L)(_.seq + 1)
      Some(lines)

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
          // A resize may be a change of font size, which changes the cell: ask again. The answer comes back among the
          // keys, as a [[Key.CellSize]], and the art is then placed again at the new size (spec 029 FR-016).
          if resized && lastSize != ((0, 0)) && caps.kittyGraphics then
            write(term, Kitty.CellSizeQuery)
            cellAsked = true
          val clear = clearNext || resized

          val current = state.get
          val frame = renderer.frame(current, width, height, theme)
          // Each changed row is written at its own position, and no newline is ever written: a frame is exactly
          // `height` rows, and a newline on the bottom row would scroll the whole screen up by one.
          val (bytes, next) = FrameWriter.write(shown, frame, current.graphics, art, clear, _.toAnsi(term))
          if bytes.nonEmpty then
            // Set before the write: a write that fails half-way may still have left an image with the terminal.
            if next.hasImages then imagesSent = true
            write(term, bytes)
          // Only now: a frame that failed above is drawn again from a cleared screen, at the size it was meant for.
          shown = next
          lastSize = (width, height)
          clearNext = false
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

  /** Wait up to `timeoutMs` for input, and decode what arrives into keys (spec 029 FR-004).
    *
    * An escape sequence is read to its end before anything is returned: once a sequence has started, the rest of it is
    * waited for [[KeyDecoder.EscapeWaitMillis]] at a time. A sequence the decoder knows is a key; any other, and any
    * reply of the terminal, is nothing. So the tail of a sequence is never dispatched as keys (Left arrow ends in `D`:
    * Detach).
    *
    * @return
    *   the keys read, in order; empty if none arrived (or the TUI is not active)
    */
  def checkInput(timeoutMs: Long = 1L): Seq[Key] =
    val term = synchronized(if enabled then terminal else None)
    term.fold(Seq.empty[Key]) { t =>
      try
        val reader = t.reader()
        val keys = Vector.newBuilder[Key]
        // NonBlockingReader treats a timeout of 0 as "wait forever" — never pass it through.
        var wait = Math.max(1L, timeoutMs)
        var reading = true
        while reading do
          val c = reader.read(wait)
          if c >= 0 then
            quietMillis = 0L
            val (next, decoded) = decoder.step(decoding, c.toChar)
            decoding = next
            keys ++= decoded
            // Between keys: hand back what there is. Inside a sequence: its rest is on the way.
            if next == KeyDecoder.State.Ground then reading = false else wait = KeyDecoder.EscapeWaitMillis
          else if c == NonBlockingReader.READ_EXPIRED then
            val (next, decoded) = decoder.idle(decoding)
            keys ++= decoded
            quietMillis = if next == KeyDecoder.State.Ground then 0L else quietMillis + wait
            decoding = if quietMillis >= KeyDecoder.waitMillis(next) then decoder.abandon(next) else next
            reading = false
          else
            reading = false
            // End of input: nothing can be typed again, and every later read would return at once.
            if c == NonBlockingReader.EOF then
              val wasUp = synchronized(enabled)
              terminalLost("reading a key (end of input)")
              if wasUp then
                log.warn("TUI keyboard input reached end of input; the terminal and standard logging are restored")
        keys.result()
      catch
        case e: IllegalStateException if Tui.isClosedTerminal(e) =>
          terminalLost("reading a key")
          Nil
        case _: InterruptedIOException =>
          // The frame loop is being stopped (TuiUpdater.stop, on Ctrl-C, SIGTERM or `q`): JLine answers the interrupt
          // with InterruptedIOException. Not a failed read; keep the interrupt for the loop to see.
          Thread.currentThread().interrupt()
          Nil
        case NonFatal(e) =>
          // Keys are how the TUI is left (q, d): without them, give the terminal and the console logs back, then log,
          // so the ERROR reaches the console.
          shutdown()
          log.error("TUI could not read a key ({}); the terminal and standard logging are restored", e.getMessage, e)
          Nil
    }

  /** A key (spec 029 FR-003 to FR-010). While the splash is on screen any key skips it, and does nothing else (spec 027
    * FR-013). Otherwise [[Update]] says what the key does to the state, and what it asks of the terminal and the node.
    */
  def handleKey(key: Key): TuiCommand =
    val height = synchronized(lastSize._2)
    key match
      case Key.CellSize(width, height) =>
        cellSizeReported(gfx.CellSize(width, height))
        TuiCommand.Continue
      case _ => keyPressed(key, height)

  /** The terminal's cell is `cell` pixels now. In pixel mode the art is sent and placed again at that size. */
  private def cellSizeReported(cell: gfx.CellSize): Unit = synchronized {
    // Believed only as the answer to a question: unasked, it is text that reached the terminal's input some other way.
    val asked = cellAsked
    cellAsked = false
    if asked && !caps.cell.contains(cell) then
      caps = caps.copy(cell = Some(cell))
      state.get.graphics match
        case GraphicsMode.Pixel(_) => setGraphics(GraphicsMode.Pixel(cell))
        case _                     => ()
  }

  private def keyPressed(key: Key, height: Int): TuiCommand =
    if state.get.splashCover(height).isDefined then
      modify(_.skipSplash)
      TuiCommand.Continue
    else
      val ctx = UpdateCtx(
        page = (height - panels.LogsPanel.ChromeRows).max(1),
        configuredMotion = config.motion,
        readOnly = config.readOnly
      )
      var effects: Seq[Effect] = Nil
      state.updateAndGet { s =>
        val (next, asked) = Update(s, key, ctx)
        effects = asked
        next
      }
      effects.foldLeft(TuiCommand.Continue: TuiCommand) { (result, effect) =>
        if result != TuiCommand.Continue then result
        else
          effect match
            case Effect.Redraw =>
              synchronized {
                clearNext = true
              }
              render()
              TuiCommand.Continue
            case Effect.CycleGraphics =>
              synchronized(setGraphics(GraphicsMode.next(state.get.graphics, caps, config.noColor)))
              render()
              TuiCommand.Continue
            case Effect.SetGraphics(choice) =>
              synchronized(chooseGraphics(choice))
              render()
              TuiCommand.Continue
            case Effect.ReloadArt =>
              synchronized(loadArt())
              TuiCommand.Continue
            case Effect.Detach =>
              shutdown()
              log.info("TUI disabled, switching to standard logging")
              TuiCommand.Disable
            case Effect.Quit =>
              log.info("Quit confirmed in the TUI")
              TuiCommand.Quit
      }

  /** A typed character, as a key. */
  def handleCommand(command: Char): TuiCommand = handleKey(Key.Char(command))

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
    teardown()
    enabled = false
    if wasActive then log.info("TUI shutdown complete")
  }

  /** The teardown ladder (C-11), in the reverse of `initialize`'s order: the log ring off the root logger, the console
    * appenders back, the terminal released. Each rung runs whatever the one before it did, so a ring that will not
    * detach still leaves the console logging and the terminal out of raw mode.
    */
  private def teardown(): Unit =
    attempt("detach the log ring") {
      if ringAttached then
        ringAttached = false
        ring.detach()
    }
    attempt("restore console logs") {
      if logSuppressor.isConsoleSuppressed && !logSuppressor.restoreConsoleLogs() then
        log.error("Failed to restore console logs")
    }
    releaseTerminal()

  /** One rung of the teardown ladder: `f` runs, and a failure is logged with the rung's name and stops no later rung.
    */
  private def attempt(name: String)(f: => Unit): Unit =
    try f
    catch case NonFatal(e) => log.error(s"Error shutting down TUI ($name): ${e.getMessage}")

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
          attempt(name) {
            try f
            catch case e: IllegalStateException if Tui.isClosedTerminal(e) => closedAt = Some(name)
          }
      step("reset screen") {
        write(term, leaveScreen)
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
    shown = FrameWriter.Blank

  /** What leaves the TUI's screen: first every image this terminal was sent, so that none outlives the TUI (spec 029
    * FR-016), then [[Tui.RestoreScreen]]. A terminal that was sent no image is sent no graphics command.
    */
  private def leaveScreen: String = (if imagesSent then Kitty.RemoveAll else "") + Tui.RestoreScreen

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
            out.write(leaveScreen.getBytes(StandardCharsets.US_ASCII))
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

  /** Leaves the TUI's screen: attributes reset, bracketed paste off, cursor shown, back to the normal buffer and the
    * user's scrollback.
    */
  private[console] val RestoreScreen = ResetColors + KeyDecoder.DisablePaste + ShowCursor + ExitAltScreen

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
