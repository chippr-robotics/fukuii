package com.chipprbots.ethereum.console

import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.LoggingEvent
import org.scalatest.LoneElement
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.slf4j.LoggerFactory

import com.chipprbots.ethereum.testing.Tags.*

/** The log tail's ring (spec 027 FR-021, SC-009, Q4): a separate appender that keeps the newest lines, sanitised.
  *
  * Events are handed to the appender directly: the test logback configuration's root is at ERROR, so a real logger
  * would never pass it an INFO line.
  */
class TuiLogRingAppenderSpec extends AnyFlatSpec with Matchers with LoneElement:

  private val context = LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext]
  private val at = Instant.parse("2026-11-07T12:04:51Z")

  private def ring(capacity: Int = 1000, level: Level = Level.INFO): TuiLogRingAppender =
    val r = new TuiLogRingAppender(capacity, level, ZoneOffset.UTC)
    r.setContext(context)
    r.start()
    r

  private def event(
      message: String,
      level: Level = Level.INFO,
      logger: String = "com.chipprbots.ethereum.Fukuii",
      args: Array[AnyRef] = null
  ): LoggingEvent =
    val e =
      new LoggingEvent(classOf[TuiLogRingAppenderSpec].getName, context.getLogger(logger), level, message, null, args)
    e.setTimeStamp(at.toEpochMilli)
    e

  "The ring" should "be started and named once built" taggedAs (UnitTest) in {
    val r = ring()
    r.isStarted shouldBe true
    r.getName shouldBe TuiLogRingAppender.Name
  }

  it should "keep the newest 1,000 of 1,500 lines, oldest first (SC-009)" taggedAs (UnitTest) in {
    val r = ring()
    (1 to 1500).foreach(i => r.doAppend(event(s"line $i")))
    val lines = r.lines
    lines should have size 1000
    lines.head.text shouldBe "line 501"
    lines.last.text shouldBe "line 1500"
    lines.map(_.seq) shouldBe (500L until 1500L)
    r.appended shouldBe 1500L
  }

  it should "keep INFO and above by default, and what the configured level allows (Q4)" taggedAs (UnitTest) in {
    val info = ring()
    Seq(Level.TRACE, Level.DEBUG, Level.INFO, Level.WARN, Level.ERROR).foreach(l => info.doAppend(event(l.toString, l)))
    info.lines.map(_.level) shouldBe Seq("INFO", "WARN", "ERROR")

    val debug = ring(level = Level.DEBUG)
    Seq(Level.TRACE, Level.DEBUG, Level.INFO).foreach(l => debug.doAppend(event(l.toString, l)))
    debug.lines.map(_.level) shouldBe Seq("DEBUG", "INFO")

    val warn = ring(level = Level.WARN)
    Seq(Level.INFO, Level.WARN).foreach(l => warn.doAppend(event(l.toString, l)))
    warn.lines.map(_.level) shouldBe Seq("WARN")
  }

  it should "keep the logger's name, the time of the event and the formatted message" taggedAs (UnitTest) in {
    val r = ring()
    r.doAppend(
      event(
        "Imported block {} with {} txs",
        logger = "com.chipprbots.ethereum.blockchain.sync.regular.BlockImporter",
        args = Array[AnyRef]("21,884,102", Integer.valueOf(14))
      )
    )
    val line = r.lines.loneElement
    line.logger shouldBe "com.chipprbots.ethereum.blockchain.sync.regular.BlockImporter"
    line.time shouldBe "12:04:51"
    line.text shouldBe "Imported block 21,884,102 with 14 txs"
  }

  it should "sanitise a line on capture: no escape sequence, one line (FR-020)" taggedAs (UnitTest) in {
    val r = ring()
    r.doAppend(event("peer said \u001b[2Jhello\nand more"))
    r.lines.loneElement.text shouldBe "peer said hello…"
  }

  it should "mark a line that carries an exception with the ellipsis" taggedAs (UnitTest) in {
    val r = ring()
    val e = event("import failed")
    e.setThrowableProxy(new ch.qos.logback.classic.spi.ThrowableProxy(new IllegalStateException("boom")))
    r.doAppend(e)
    r.lines.loneElement.text shouldBe "import failed…"
  }

  it should "keep nothing once stopped" taggedAs (UnitTest) in {
    val r = ring()
    r.stop()
    r.doAppend(event("after stop"))
    r.lines shouldBe empty
  }

  it should "drop a late writer's line for a slot the ring has lapped, and keep the newer line (lost-write race)" taggedAs (
    UnitTest
  ) in {
    val r = ring(capacity = 4)
    (0 until 8).foreach(i => r.doAppend(event(s"line $i"))) // numbers 0 to 7: the ring holds 4 to 7
    r.lines.map(_.seq) shouldBe (4L until 8L)
    // The writer of number 0 took it, was descheduled while the others went a lap round the ring, and stores only now:
    // its slot holds number 4. The late line must not overwrite it (eye, slice 4 gate: 999 lines instead of 1,000).
    r.store(LogLine(0L, "12:04:51", "INFO", "late", "line 0, a lap late"))
    r.lines.map(_.seq) shouldBe (4L until 8L)
    r.lines.map(_.text) shouldBe (4 until 8).map(i => s"line $i")
    // The other order: number 8 is stored over 4 (its slot), and a late 4 does not bring 4 back. The window is then 4
    // to 7 with number 8 not yet taken, so the read has 5 to 7.
    r.store(LogLine(8L, "12:04:51", "INFO", "x", "line 8"))
    r.store(LogLine(4L, "12:04:51", "INFO", "x", "line 4 again"))
    r.lines.map(_.seq) shouldBe Vector(5L, 6L, 7L)
  }

  it should "keep every line appended from four threads, each thread's lines in the order it logged them" taggedAs (
    UnitTest
  ) in {
    // 8,000 lines into a ring of 1,000: the ring wraps eight times while four writers race (prism, slice 4 review).
    val r = ring(capacity = 1000)
    val perThread = 2000
    val start = new CountDownLatch(1)
    val done = new CountDownLatch(4)
    val threads = (0 until 4).map { t =>
      new Thread(() =>
        try
          start.await()
          (0 until perThread).foreach(i => r.doAppend(event(s"t$t-$i")))
        finally done.countDown()
      )
    }
    threads.foreach(_.start())
    start.countDown()
    done.await(30, TimeUnit.SECONDS) shouldBe true

    val lines = r.lines
    lines should have size 1000
    r.appended shouldBe 8000L
    lines.map(_.seq) shouldBe (7000L until 8000L)
    for t <- 0 until 4 do
      val kept = lines.map(_.text).filter(_.startsWith(s"t$t-")).map(_.stripPrefix(s"t$t-").toInt)
      withClue(s"thread $t: ")(kept.zip(kept.drop(1)).foreach((a, b) => a should be < b))
  }
