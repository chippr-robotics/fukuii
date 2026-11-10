package com.chipprbots.ethereum.console

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch

import scala.jdk.CollectionConverters.*

import org.jline.terminal.Attributes
import org.jline.terminal.Size
import org.jline.terminal.Terminal
import org.jline.terminal.impl.ExternalTerminal
import org.jline.utils.NonBlockingReader

/** An in-memory xterm for driving [[Tui]] without a TTY: keys are typed into `keyboard`, frames land in `screen`. */
final class TuiTestTerminal(width: Int = 100, height: Int = 40, termType: String = "xterm-256color"):
  val keyboard = new PipedOutputStream()
  private val keyboardIn = new PipedInputStream(keyboard)
  private val screen = new ByteArrayOutputStream()

  /** The process stream behind the terminal: where [[Tui]] restores the screen once the terminal has been closed. */
  val processOutput = new ByteArrayOutputStream()

  /** The attributes most recently applied — still readable after the terminal is closed. */
  @volatile var lastSetAttributes: Option[Attributes] = None

  /** When set, `writer()` throws — simulates an output failure during teardown. */
  @volatile var failWrites: Boolean = false

  /** When set, a key read throws an `IOException`, as a real terminal's reader does when its input fails. */
  @volatile var failReads: Boolean = false

  /** When set, a key read returns end of input. */
  @volatile var eofReads: Boolean = false

  /** When set, a key read blocks until the latch is released, ignoring interrupts, as a read blocked in native code
    * does (`FileInputStream.read0` once JLine's shutdown hook has closed the terminal).
    */
  @volatile var holdReads: Option[CountDownLatch] = None

  private def awaitIgnoringInterrupts(latch: CountDownLatch): Unit =
    var interrupted = false
    var released = false
    while !released do
      try
        latch.await()
        released = true
      catch case _: InterruptedException => interrupted = true
    if interrupted then Thread.currentThread().interrupt()

  private val timeouts = new ConcurrentLinkedQueue[java.lang.Long]()

  /** The timeout of every key read so far, in order: the frame loop's pace. */
  def readTimeouts: Seq[Long] = timeouts.asScala.toSeq.map(_.longValue)

  /** The terminal's reader as a real one behaves (JLine 3.30, `PosixSysTerminal`): a read interrupted while it waits
    * throws `InterruptedIOException`, and a failed input throws `IOException` from `read`.
    */
  final private class ModelledReader(inner: NonBlockingReader) extends NonBlockingReader:
    override protected def read(timeout: Long, isPeek: Boolean): Int =
      if !isPeek then timeouts.add(timeout)
      holdReads.foreach(awaitIgnoringInterrupts)
      if failReads then throw new IOException("simulated input failure")
      if eofReads then NonBlockingReader.EOF
      else
        val deadline = if timeout <= 0 then Long.MaxValue else System.nanoTime() + timeout * 1_000_000L
        var result = NonBlockingReader.READ_EXPIRED
        while result == NonBlockingReader.READ_EXPIRED && System.nanoTime() < deadline do
          if Thread.interrupted() then throw new InterruptedIOException("read interrupted")
          val slice = ((deadline - System.nanoTime()) / 1_000_000L).max(1L).min(10L)
          result = if isPeek then inner.peek(slice) else inner.read(slice)
        result
    override def readBuffered(b: Array[Char], off: Int, len: Int, timeout: Long): Int =
      inner.readBuffered(b, off, len, timeout)
    override def available(): Int = inner.available()
    override def close(): Unit = inner.close()

  /** When set, the next `setAttributes` closes the terminal first: JLine's shutdown hook winning the race between the
    * TUI's reset write and its mode restore.
    */
  @volatile var closeOnSetAttributes: Boolean = false

  // Built directly rather than via TerminalBuilder, which may allocate a real PTY whose output pump copies to `screen`
  // asynchronously. ExternalTerminal writes through synchronously, so assertions see every byte already written.
  val terminal: Terminal =
    val t = new ExternalTerminal("tui-test", termType, keyboardIn, screen, StandardCharsets.UTF_8):
      override def writer(): java.io.PrintWriter =
        if failWrites then throw new IllegalStateException("simulated output failure")
        super.writer()
      private lazy val modelled = new ModelledReader(super.reader())
      override def reader(): NonBlockingReader = modelled
      override def setAttributes(attr: Attributes): Unit =
        if closeOnSetAttributes then
          closeOnSetAttributes = false
          this.close() // the terminal's own close; `close()` alone would be ambiguous with TuiTestTerminal.close
        lastSetAttributes = Some(new Attributes(attr))
        super.setAttributes(attr)
    t.setSize(new Size(width, height))
    t

  def press(keys: String): Unit =
    keyboard.write(keys.getBytes(StandardCharsets.UTF_8))
    keyboard.flush()

  def output: String = screen.synchronized(screen.toString(StandardCharsets.UTF_8))

  /** What was written around the terminal, straight to [[processOutput]]. */
  def processText: String = processOutput.toString(StandardCharsets.UTF_8)

  /** A [[Tui]] on this terminal, with [[processOutput]] as the process stream behind it, on the fixtures' clock: the
    * snapshots tests apply are stamped at [[TuiFixtures.Now]], and a TUI on the wall clock reads them as days old, so
    * Unresponsive.
    */
  def tui(
      config: TuiConfig = TuiTestTerminal.config,
      clock: java.time.Clock = TuiFixtures.clock,
      logRing: Option[TuiLogRingAppender] = None
  ): Tui =
    new Tui(config, () => terminal, _ => Some(processOutput), clock, logRing)

  /** Frames written so far: each frame starts at the home position (spec 027 T036 counts them). */
  def framesWritten: Int = output.split(java.util.regex.Pattern.quote(Tui.CursorHome), -1).length - 1

  /** Text drawn by the most recent frame (everything after the last cursor-home). */
  def lastFrame: String =
    val out = output
    out.substring(out.lastIndexOf(Tui.CursorHome) + Tui.CursorHome.length)

  def close(): Unit =
    keyboard.close()
    terminal.close()

object TuiTestTerminal:
  /** Config for tests: no splash, fast frames at either pace, and the test logger's console appender left alone. */
  val config: TuiConfig =
    TuiConfig(splashMs = 0, suppressConsoleLogs = false, updateIntervalMs = 20, animationIntervalMs = 20)

  /** Strip ANSI escape sequences (CSI and charset designation) and carriage returns, leaving the visible text.
    *
    * JLine draws box-drawing characters (`─`, `│`, corners) through the DEC special graphics set when the terminal has
    * one: `ESC ( 0`, then `q` for `─`, `x` for `│`, …, then `ESC ( B`. Those letters are decoded back to the characters
    * the terminal shows.
    */
  def visible(s: String): String =
    val out = new StringBuilder
    var lineDrawing = false
    var i = 0
    while i < s.length do
      val c = s.charAt(i)
      val next = if i + 1 < s.length then s.charAt(i + 1) else '\u0000'
      if c == '\u001b' && (next == '(' || next == ')') then
        // Charset designation: ESC ( 0 puts line drawing in G0, ESC ( B puts ASCII back.
        if next == '(' && i + 2 < s.length then lineDrawing = s.charAt(i + 2) == '0'
        i += 3
      else if c == '\u001b' && next == '[' then
        var j = i + 2
        while j < s.length && (s.charAt(j).isDigit || s.charAt(j) == ';' || s.charAt(j) == '?') do j += 1
        i = j + 1 // past the final byte
      else
        if c != '\r' then out += (if lineDrawing then DecSpecialGraphics.getOrElse(c, c) else c)
        i += 1
    out.toString

  /** DEC special graphics letters and the box-drawing characters JLine maps to them. */
  private val DecSpecialGraphics: Map[Char, Char] = Map(
    'j' -> '┘',
    'k' -> '┐',
    'l' -> '┌',
    'm' -> '└',
    'n' -> '┼',
    'q' -> '─',
    't' -> '├',
    'u' -> '┤',
    'v' -> '┴',
    'w' -> '┬',
    'x' -> '│'
  )

  private val Sgr = "\u001b\\[([0-9;]*)m".r

  /** The colour parameters (foreground, background; 16, 256 or RGB) in the SGR sequences of `ansi`. Bold, reverse and
    * the resets carry no colour and are not returned.
    */
  def colourCodes(ansi: String): Seq[String] =
    Sgr
      .findAllMatchIn(ansi)
      .flatMap(_.group(1).split(';').filter(_.nonEmpty))
      .filter { p =>
        val n = p.toInt
        (30 to 38).contains(n) || (40 to 48).contains(n) || (90 to 97).contains(n) || (100 to 107).contains(n)
      }
      .toSeq
