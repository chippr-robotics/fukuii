package com.chipprbots.ethereum.console

import org.jline.terminal.Terminal
import org.jline.utils.NonBlockingReader

import com.chipprbots.ethereum.console.gfx.CellSize
import com.chipprbots.ethereum.console.gfx.Kitty

/** Asks the terminal what it can do, once, before any key is read (spec 029 FR-013, research §3 E4).
  *
  * Three questions go out together: the Kitty graphics query, the size of a cell, and the primary device attributes.
  * Every terminal answers the last, so its reply ends the wait; a terminal that knows the others answers them first.
  * The replies are read here and nowhere else: none of them reaches the key loop.
  */
object TerminalQuery:

  /** The longest the answers are waited for. */
  val TimeoutMillis: Long = 500L

  /** The most characters read: the three replies are under a hundred. */
  val MaxReply: Int = 1024

  /** What goes to the terminal. */
  val Questions: String = Kitty.Query + Kitty.CellSizeQuery + Kitty.DeviceAttributesQuery

  private val GraphicsOk = s"\u001b_Gi=${Kitty.QueryId};OK\u001b\\\\".r
  private val CellReport = "\u001b\\[6;(\\d+);(\\d+)t".r
  private val Attributes = "\u001b\\[\\?[0-9;]*c".r

  /** Whether `reply` holds the device-attributes answer: the terminal has said all it will. */
  def complete(reply: String): Boolean = Attributes.findFirstIn(reply).isDefined

  /** What `reply` says: whether graphics were answered, and the cell's size if it was reported and is real. */
  def parse(reply: String): (Boolean, Option[CellSize]) =
    val cell = CellReport.findFirstMatchIn(reply).flatMap { m =>
      val (height, width) = (m.group(1).toIntOption, m.group(2).toIntOption)
      for h <- height; w <- width; if w >= 4 && h >= 4 && w <= 256 && h <= 512 yield CellSize(w, h)
    }
    (GraphicsOk.findFirstIn(reply).isDefined, cell)

  /** Asks `terminal`, waits for its answers, and reads them. A terminal that does not answer within [[TimeoutMillis]]
    * is one with no graphics.
    *
    * @param nanoTime
    *   the clock the wait is measured on; tests move it
    */
  def run(
      terminal: Terminal,
      timeoutMillis: Long = TimeoutMillis,
      nanoTime: () => Long = () => System.nanoTime()
  ): (Boolean, Option[CellSize]) =
    terminal.writer().print(Questions)
    terminal.writer().flush()
    val reader = terminal.reader()
    val deadline = nanoTime() + timeoutMillis * 1_000_000L
    val reply = new StringBuilder
    var reading = true
    while reading do
      val left = (deadline - nanoTime()) / 1_000_000L
      if left <= 0 || reply.length >= MaxReply then reading = false
      else
        val c = reader.read(left)
        if c == NonBlockingReader.READ_EXPIRED || c < 0 then reading = false
        else
          reply.append(c.toChar)
          if complete(reply.toString) then reading = false
    parse(reply.toString)
