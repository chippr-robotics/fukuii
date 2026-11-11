package com.chipprbots.ethereum.console

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReferenceArray

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger as LogbackLogger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.UnsynchronizedAppenderBase
import org.slf4j.LoggerFactory

/** One captured log line, as the Logs view and the Recent strips draw it.
  *
  * @param seq
  *   the ring's sequence number: lines are kept and shown in its order
  * @param time
  *   `HH:mm:ss` in the ring's zone, at the event's own timestamp
  * @param level
  *   `INFO`, `WARN`, `ERROR` (or `DEBUG`, `TRACE` with `fukuii.tui.log-level`)
  * @param logger
  *   the logger's name, for the "Recent (sync)" filter
  * @param text
  *   the formatted message, sanitised ([[Sanitize.line]])
  */
final case class LogLine(seq: Long, time: String, level: String, logger: String, text: String)

/** The log tail (spec 027 FR-021, research §7.4): an appender of its own on the root logger that keeps the newest
  * `capacity` lines at or above `threshold`. It is separate from the console appenders [[TuiLogSuppressor]] detaches
  * and restores, so neither path changes (C-13): it is attached once the console is suppressed and detached before the
  * console is restored, through [[Tui]]'s teardown ladder (C-11).
  *
  * The ring is lock-free: a writer takes the next sequence number and stores its line in that number's slot, a reader
  * collects the slots of the last `capacity` numbers and keeps those whose line carries the number it expects. A line
  * whose writer has its number but has not stored it yet is missing from that read, never out of order or duplicated.
  *
  * The store is monotone: a slot keeps the line with the highest sequence number ever offered to it ([[store]]). A
  * writer that took its number and was descheduled while others went a whole lap round the ring stores a line the ring
  * has already replaced; that line is dropped, and does not overwrite the newer one. So once every writer has stored,
  * the ring holds exactly the newest `capacity` lines, whatever the scheduling. While writers are in flight, a read
  * may miss a line that is not stored yet, or one whose slot a lap newer line took while the read ran: it never shows
  * a line out of order or twice. Lines are sanitised on capture (FR-020), so nothing a peer sent reaches a frame from a
  * log line.
  *
  * `UnsynchronizedAppenderBase`, not `AppenderBase`: every logging thread passes through `doAppend`, and the ring needs
  * no lock, so it takes none.
  *
  * @param zone
  *   the zone of the `HH:mm:ss` time; the JVM's default, as the log file has it
  */
class TuiLogRingAppender(capacity: Int, threshold: Level, zone: ZoneId = ZoneId.systemDefault())
    extends UnsynchronizedAppenderBase[ILoggingEvent]:
  require(capacity > 0, s"capacity must be positive, was $capacity")
  setName(TuiLogRingAppender.Name)

  private val slots = new AtomicReferenceArray[LogLine](capacity)
  private val next = new AtomicLong(0L)
  private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(zone)

  /** How many lines have been kept since the start: the next line's sequence number. */
  def appended: Long = next.get()

  /** The newest lines, at most `capacity`, oldest first. */
  def lines: Vector[LogLine] =
    val end = next.get()
    val start = (end - capacity).max(0L)
    val out = Vector.newBuilder[LogLine]
    var seq = start
    while seq < end do
      val line = slots.get(slot(seq))
      if line != null && line.seq == seq then out += line
      seq += 1
    out.result()

  /** Never logs: a line logged here would come back through this appender. */
  override protected def append(event: ILoggingEvent): Unit =
    if event.getLevel.isGreaterOrEqual(threshold) then
      val message = Option(event.getFormattedMessage).getOrElse("")
      // A stack trace is not drawn: its presence is, as the first line's ellipsis.
      val text = Sanitize.line(if event.getThrowableProxy != null then s"$message\n…" else message)
      val time = timeFormat.format(Instant.ofEpochMilli(event.getTimeStamp))
      val seq = next.getAndIncrement()
      store(LogLine(seq, time, event.getLevel.toString, event.getLoggerName, text))

  /** Keeps `line` in its slot unless the slot holds a newer one: a writer that was descheduled for a lap of the ring
    * must not overwrite what the lap wrote there (the lost-write race eye found in the slice 4 gate). Open to the
    * tests, which make that writer late deterministically.
    */
  private[console] def store(line: LogLine): Unit =
    slots.accumulateAndGet(
      slot(line.seq),
      line,
      (held, offered) => if held != null && held.seq > offered.seq then held else offered
    )

  private def slot(seq: Long): Int = (seq % capacity).toInt

  /** Started, on the root logger. Open so a test can make it fail (C-11). */
  def attach(): Unit =
    val root = TuiLogRingAppender.rootLogger()
    setContext(root.getLoggerContext)
    start()
    root.addAppender(this)

  /** Off the root logger, and stopped: it keeps nothing more. */
  def detach(): Unit =
    try TuiLogRingAppender.rootLogger().detachAppender(this)
    finally stop()

object TuiLogRingAppender:
  /** The appender's name on the root logger. */
  val Name: String = "TUI-LOG-RING"

  /** The ring the TUI runs with: `fukuii.tui.log-ring-size` lines at `fukuii.tui.log-level` and above (Q4). */
  def of(config: TuiConfig): TuiLogRingAppender = new TuiLogRingAppender(config.logRingSize, config.logLevel)

  private[console] def rootLogger(): LogbackLogger =
    // interop: SLF4J returns ILoggerFactory; the Logback implementation is always a LoggerContext
    LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext].getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
