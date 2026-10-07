package com.chipprbots.ethereum.console

import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.charset.StandardCharsets

import org.jline.terminal.Attributes
import org.jline.terminal.Size
import org.jline.terminal.Terminal
import org.jline.terminal.impl.ExternalTerminal

/** An in-memory xterm for driving [[Tui]] without a TTY: keys are typed into `keyboard`, frames land in `screen`. */
final class TuiTestTerminal(width: Int = 100, height: Int = 40, termType: String = "xterm-256color"):
  val keyboard = new PipedOutputStream()
  private val keyboardIn = new PipedInputStream(keyboard)
  private val screen = new ByteArrayOutputStream()

  /** The attributes most recently applied — still readable after the terminal is closed. */
  @volatile var lastSetAttributes: Option[Attributes] = None

  /** When set, `writer()` throws — simulates an output failure during teardown. */
  @volatile var failWrites: Boolean = false

  // Built directly rather than via TerminalBuilder, which may allocate a real PTY whose output pump copies to `screen`
  // asynchronously. ExternalTerminal writes through synchronously, so assertions see every byte already written.
  val terminal: Terminal =
    val t = new ExternalTerminal("tui-test", termType, keyboardIn, screen, StandardCharsets.UTF_8):
      override def writer(): java.io.PrintWriter =
        if failWrites then throw new IllegalStateException("simulated output failure")
        super.writer()
      override def setAttributes(attr: Attributes): Unit =
        lastSetAttributes = Some(new Attributes(attr))
        super.setAttributes(attr)
    t.setSize(new Size(width, height))
    t

  def press(keys: String): Unit =
    keyboard.write(keys.getBytes(StandardCharsets.UTF_8))
    keyboard.flush()

  def output: String = screen.synchronized(screen.toString(StandardCharsets.UTF_8))

  /** Text drawn by the most recent frame (everything after the last cursor-home). */
  def lastFrame: String =
    val out = output
    out.substring(out.lastIndexOf(Tui.CursorHome) + Tui.CursorHome.length)

  def close(): Unit =
    keyboard.close()
    terminal.close()

object TuiTestTerminal:
  /** Config for tests: no banner pause, and the test logger's console appender left alone. */
  val config: TuiConfig = TuiConfig(bannerDisplayDurationMs = 0, suppressConsoleLogs = false, updateIntervalMs = 20)

  /** Strip ANSI escape sequences (CSI and charset designation) and carriage returns, leaving the visible text. */
  def visible(s: String): String = s.replaceAll("\u001b\\[[0-9;?]*[A-Za-z]|\u001b[()][0-9A-Za-z]|\r", "")
