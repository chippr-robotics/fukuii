package com.chipprbots.ethereum.ephemery

import java.nio.file.Paths
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

import scala.collection.mutable

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.ephemery.EphemeryResetWatchdog.*
import com.chipprbots.ethereum.ephemery.EphemeryResolver.Level
import com.chipprbots.ethereum.ephemery.EphemeryResolver.Line
import com.chipprbots.ethereum.testing.Tags.*

/** The exit at the reset (spec 028 FR-016, R11), driven by a clock the test advances and a scheduler that runs its
  * tasks when the test says: nothing waits, and nothing reads the system clock.
  */
class EphemeryResetWatchdogSpec extends AnyFlatSpec with Matchers:

  private val reset = Instant.parse("2026-11-19T19:00:00Z")

  /** A clock that moves only when the test moves it. */
  final private class TestClock(start: Instant) extends Clock:
    var current: Instant = start
    override def getZone: ZoneId = ZoneOffset.UTC
    override def withZone(zone: ZoneId): Clock = this
    override def instant(): Instant = current

  /** Runs a task when the clock the test advances reaches its due time. */
  final private class ManualScheduler(clock: TestClock) extends ResetScheduler:
    private case class Task(due: Instant, run: Runnable, var cancelled: Boolean)
    private val tasks = mutable.ArrayBuffer.empty[Task]
    val delays: mutable.ArrayBuffer[Duration] = mutable.ArrayBuffer.empty

    override def schedule(delay: Duration, task: Runnable): () => Unit =
      delays += delay
      val t = Task(clock.current.plus(delay), task, cancelled = false)
      tasks += t
      () => t.cancelled = true

    def pending: Int = tasks.count(!_.cancelled)

    /** Moves the clock to `to`, running every task that falls due on the way, earliest first. */
    def advanceTo(to: Instant): Unit =
      var next = tasks.filter(t => !t.cancelled && !t.due.isAfter(to)).sortBy(_.due).headOption
      while next.isDefined do
        val t = next.get
        tasks -= t
        clock.current = if t.due.isAfter(clock.current) then t.due else clock.current
        t.run.run()
        next = tasks.filter(x => !x.cancelled && !x.due.isAfter(to)).sortBy(_.due).headOption
      if to.isAfter(clock.current) then clock.current = to

  private class Fixture(start: Instant):
    val clock = new TestClock(start)
    val scheduler = new ManualScheduler(clock)
    val exits: mutable.ArrayBuffer[Int] = mutable.ArrayBuffer.empty[Int]
    val lines: mutable.ArrayBuffer[Line] = mutable.ArrayBuffer.empty[Line]
    val watchdog = new EphemeryResetWatchdog(clock, scheduler, code => exits += code, line => lines += line)

  "the watchdog" should "log the WARN of contracts/exit-codes.md and exit 75 at the reset time, not before" taggedAs UnitTest in {
    val f = new Fixture(reset.minus(Duration.ofHours(3)))
    f.watchdog.start(Target(165, reset, Mode.ExitAtReset))
    f.scheduler.advanceTo(reset.minusMillis(1))
    f.exits shouldBe empty
    f.lines shouldBe empty
    f.scheduler.advanceTo(reset)
    f.exits.toList shouldBe List(75)
    f.lines.toList shouldBe List(
      Line(
        Level.Warn,
        "Ephemery iteration ephemery-165 ended at 2026-11-19T19:00:00Z; exiting with code 75 so a supervisor restart " +
          "picks up ephemery-166"
      )
    )
    f.scheduler.advanceTo(reset.plus(Duration.ofDays(1)))
    f.exits.toList shouldBe List(75)
  }

  it should "wake in bounded steps and check the clock each time, so a clock that jumps is still caught" taggedAs UnitTest in {
    val f = new Fixture(reset.minus(Duration.ofDays(20)))
    f.watchdog.start(Target(165, reset, Mode.ExitAtReset))
    f.scheduler.delays.max.compareTo(EphemeryResetWatchdog.MaxWake) should be <= 0
    // the clock jumps past the reset (a suspended host); the next wake-up sees it
    f.clock.current = reset.plusSeconds(7)
    f.scheduler.advanceTo(f.clock.current.plus(Duration.ofHours(1)))
    f.exits.toList shouldBe List(75)
  }

  it should "exit at once when the reset has already passed at the start" taggedAs UnitTest in {
    val f = new Fixture(reset.plusSeconds(2))
    f.watchdog.start(Target(165, reset, Mode.ExitAtReset))
    f.scheduler.advanceTo(f.clock.current)
    f.exits.toList shouldBe List(75)
  }

  it should "only warn when exit-at-reset is false, once, and keep the node running" taggedAs UnitTest in {
    val f = new Fixture(reset.minusSeconds(5))
    f.watchdog.start(Target(165, reset, Mode.WarnOnly))
    f.scheduler.advanceTo(reset.plus(Duration.ofDays(2)))
    f.exits shouldBe empty
    f.lines.toList shouldBe List(
      Line(
        Level.Warn,
        "Ephemery iteration ephemery-165 ended at 2026-11-19T19:00:00Z; restart fukuii to move to ephemery-166 " +
          "(exit-at-reset = false)"
      )
    )
    f.scheduler.pending shouldBe 0
  }

  it should "be cancelled on shutdown: nothing fires afterwards" taggedAs UnitTest in {
    val f = new Fixture(reset.minusSeconds(5))
    val armed = f.watchdog.start(Target(165, reset, Mode.ExitAtReset))
    armed.cancel()
    f.scheduler.advanceTo(reset.plus(Duration.ofHours(2)))
    f.exits shouldBe empty
    f.lines shouldBe empty
    f.scheduler.pending shouldBe 0
  }

  it should "only warn on the operator path, naming the directory and the next step" taggedAs UnitTest in {
    val dir = Paths.get("/ops/ephemery-165/chains")
    val f = new Fixture(reset.minusSeconds(5))
    f.watchdog.start(Target(165, reset, Mode.Prepared(dir)))
    f.scheduler.advanceTo(reset.plusSeconds(1))
    f.exits shouldBe empty
    f.lines.toList shouldBe List(
      Line(
        Level.Warn,
        "Ephemery: the prepared iteration in /ops/ephemery-165/chains ended at 2026-11-19T19:00:00Z; re-run " +
          "scripts/ephemery/fetch-genesis.sh, or unset custom-chains-dir to use the built-in refresh"
      )
    )
  }

  it should "say nothing more on the operator path when the prepared iteration had ended before the start" taggedAs UnitTest in {
    val f = new Fixture(reset.plusSeconds(60))
    f.watchdog.start(Target(165, reset, Mode.Prepared(Paths.get("/ops"))))
    f.scheduler.advanceTo(f.clock.current.plus(Duration.ofHours(1)))
    f.lines shouldBe empty
    f.exits shouldBe empty
  }

  "Target.fromConfig" should "arm the exit for a managed iteration, a warning with exit-at-reset = false, and nothing otherwise" taggedAs UnitTest in {
    import com.typesafe.config.ConfigFactory
    def target(hocon: String) = Target.fromConfig(ConfigFactory.parseString(hocon))
    val managed =
      "ephemery.active { managed = true, iteration = 165, reset-time = 1795114800 }"
    target(managed + "\nephemery.exit-at-reset = true") shouldBe Some(Target(165, reset, Mode.ExitAtReset))
    target(managed + "\nephemery.exit-at-reset = false") shouldBe Some(Target(165, reset, Mode.WarnOnly))
    target(
      "ephemery.active { managed = false, iteration = 165, reset-time = 1795114800 }\nblockchains.custom-chains-dir = \"/d\""
    ) shouldBe
      Some(Target(165, reset, Mode.Prepared(Paths.get("/d"))))
    target("ephemery.exit-at-reset = true") shouldBe None
    target("") shouldBe None
  }
