package com.chipprbots.ethereum.blockchain.sync.snap

import scala.collection.mutable

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

/** Spec 014: heap watchdog hysteresis. The old-gen reading is a stub the test sets; no JVM heap, no sleeps. */
class SnapHeapWatchdogSpec extends AnyFlatSpec with Matchers:

  private val Max = 1000L

  "HeapPressureHysteresis" should "engage at the high threshold of post-GC occupancy" taggedAs UnitTest in {
    val h = new HeapPressureHysteresis(highFraction = 0.75, lowFraction = 0.60)
    h.observe(postGcUsed = 749L, currentUsed = 900L, max = Max) shouldBe None
    h.isEngaged shouldBe false
    h.observe(postGcUsed = 750L, currentUsed = 760L, max = Max) shouldBe Some(true)
    h.isEngaged shouldBe true
    // Further high readings are not new transitions.
    h.observe(postGcUsed = 900L, currentUsed = 950L, max = Max) shouldBe None
  }

  it should "ignore high current usage that a collection would free (only post-GC occupancy engages)" taggedAs
    UnitTest in {
      val h = new HeapPressureHysteresis(0.75, 0.60)
      h.observe(postGcUsed = 300L, currentUsed = 990L, max = Max) shouldBe None
      h.isEngaged shouldBe false
    }

  it should "stay engaged between the thresholds and release at the low threshold" taggedAs UnitTest in {
    val h = new HeapPressureHysteresis(0.75, 0.60)
    h.observe(800L, 800L, Max) shouldBe Some(true)
    h.observe(700L, 700L, Max) shouldBe None // inside the band: no flapping
    h.observe(601L, 650L, Max) shouldBe None
    h.observe(600L, 650L, Max) shouldBe Some(false)
    h.isEngaged shouldBe false
    h.observe(700L, 700L, Max) shouldBe None // below high again: stays released
  }

  it should "release on low current usage even when the post-GC reading is stale-high" taggedAs UnitTest in {
    // Once intake pauses, allocation drops and the old gen may not be collected again, so its post-GC reading stays at
    // the value that engaged. Current usage is never below the live set, so a low current value proves the drain.
    val h = new HeapPressureHysteresis(0.75, 0.60)
    h.observe(800L, 820L, Max) shouldBe Some(true)
    h.observe(800L, 500L, Max) shouldBe Some(false)
  }

  it should "ignore readings without a known maximum" taggedAs UnitTest in {
    val h = new HeapPressureHysteresis(0.75, 0.60)
    h.observe(900L, 900L, max = 0L) shouldBe None
    h.observe(900L, 900L, max = -1L) shouldBe None
    h.isEngaged shouldBe false
  }

  it should "reject thresholds that leave no hysteresis band" taggedAs UnitTest in {
    an[IllegalArgumentException] should be thrownBy new HeapPressureHysteresis(0.60, 0.60)
    an[IllegalArgumentException] should be thrownBy new HeapPressureHysteresis(0.60, 0.75)
    an[IllegalArgumentException] should be thrownBy new HeapPressureHysteresis(1.0, 0.5)
    an[IllegalArgumentException] should be thrownBy new HeapPressureHysteresis(0.75, 0.0)
  }

  "SnapHeapWatchdog" should "drive the intake gate through engage and release" taggedAs UnitTest in {
    var reading: Option[OldGenReading] = None
    val budget = new SnapIntakeBudget(100L, 100L)
    val transitions = mutable.ArrayBuffer.empty[Boolean]
    val watchdog = new SnapHeapWatchdog(
      new HeapPressureHysteresis(0.75, 0.60),
      () => reading,
      (engaged, _) =>
        transitions += engaged
        budget.setHeapPressure(engaged)
    )

    watchdog.evaluate() // no reading yet: nothing happens
    transitions shouldBe empty

    reading = Some(OldGenReading(postGcUsed = 760L, currentUsed = 800L, max = Max))
    watchdog.evaluate()
    transitions.toList shouldBe List(true)
    budget.intakeBlockedReason() shouldBe Some("heap pressure")

    watchdog.evaluate() // same reading: no duplicate transition
    transitions.toList shouldBe List(true)

    reading = Some(OldGenReading(postGcUsed = 760L, currentUsed = 590L, max = Max))
    watchdog.evaluate()
    transitions.toList shouldBe List(true, false)
    budget.intakeAllowed shouldBe true
    watchdog.isEngaged shouldBe false
  }

  /** A watchdog with a fake clock, stub reading and stub queue state, recording every callback. */
  private class EscapeRig(policy: HeapWatchdogEscapePolicy):
    var now: Long = 0L
    var reading: Option[OldGenReading] = None
    var empty: Boolean = false
    val budget = new SnapIntakeBudget(100L, 100L)
    val events = mutable.ArrayBuffer.empty[Any]
    val watchdog = new SnapHeapWatchdog(
      new HeapPressureHysteresis(0.75, 0.60),
      () => reading,
      (engaged, _) =>
        events += engaged
        budget.setHeapPressure(engaged)
      ,
      queuesEmpty = () => empty,
      policy = policy,
      onEscape = (escape, _) =>
        events += escape
        escape match
          case HeapWatchdogEscape.ForcedRelease(_) => budget.setHeapPressure(false)
          case _                                   => ()
      ,
      nowMs = () => now
    )
    def high(): Unit = reading = Some(OldGenReading(800L, 820L, Max))
    def low(): Unit = reading = Some(OldGenReading(800L, 500L, Max))

  "SnapHeapWatchdog escape" should "warn once, then force-release, when the queues stay empty while paused" taggedAs
    UnitTest in {
      val rig = EscapeRig(HeapWatchdogEscapePolicy(ineffectiveAfterMs = 60000L, maxPauseMs = 300000L))
      rig.high()
      rig.watchdog.evaluate()
      rig.events.toList shouldBe List(true)

      rig.empty = true
      rig.now = 1000L
      rig.watchdog.evaluate() // queues just went empty
      rig.now = 60999L
      rig.watchdog.evaluate()
      rig.events.toList shouldBe List(true) // empty for 59.999 s: not yet ineffective

      rig.now = 61000L
      rig.watchdog.evaluate()
      rig.events.toList shouldBe List(true, HeapWatchdogEscape.PauseIneffective(61000L, 60000L))
      rig.now = 200000L
      rig.watchdog.evaluate() // warned once only
      rig.events.size shouldBe 2
      rig.budget.heapPressureActive shouldBe true

      rig.now = 300000L
      rig.watchdog.evaluate()
      rig.events.last shouldBe HeapWatchdogEscape.ForcedRelease(300000L)
      rig.budget.intakeAllowed shouldBe true
      rig.watchdog.isForceReleased shouldBe true
      rig.now = 400000L
      rig.watchdog.evaluate() // no repeat while force-released
      rig.events.size shouldBe 3

      // Occupancy really falls: the normal release resets the escape and the watchdog re-arms.
      rig.low()
      rig.watchdog.evaluate()
      rig.events.last shouldBe false
      rig.watchdog.isForceReleased shouldBe false
      rig.high()
      rig.watchdog.evaluate()
      rig.events.last shouldBe true
      rig.budget.heapPressureActive shouldBe true
    }

  it should "not escape while the pause is still draining SNAP work" taggedAs UnitTest in {
    val rig = EscapeRig(HeapWatchdogEscapePolicy(ineffectiveAfterMs = 60000L, maxPauseMs = 300000L))
    rig.high()
    rig.watchdog.evaluate()
    rig.empty = false
    rig.now = 1000000L
    rig.watchdog.evaluate()
    rig.events.toList shouldBe List(true)
    // Queues drain, then refill before the window ends: the empty window restarts.
    rig.empty = true
    rig.now = 1010000L
    rig.watchdog.evaluate()
    rig.empty = false
    rig.now = 1050000L
    rig.watchdog.evaluate()
    rig.empty = true
    rig.now = 1080000L
    rig.watchdog.evaluate()
    rig.now = 1139999L
    rig.watchdog.evaluate()
    rig.events.toList shouldBe List(true)
    rig.budget.heapPressureActive shouldBe true
  }

  it should "warn but never force-release when max-pause is 0" taggedAs UnitTest in {
    val rig = EscapeRig(HeapWatchdogEscapePolicy(ineffectiveAfterMs = 60000L, maxPauseMs = 0L))
    rig.high()
    rig.watchdog.evaluate()
    rig.empty = true
    rig.watchdog.evaluate()
    rig.now = 10000000L
    rig.watchdog.evaluate()
    rig.events.toList shouldBe List(true, HeapWatchdogEscape.PauseIneffective(10000000L, 10000000L))
    rig.budget.heapPressureActive shouldBe true
  }

  "OldGenReading" should "report the post-GC fraction of the maximum" taggedAs UnitTest in {
    OldGenReading(postGcUsed = 250L, currentUsed = 400L, max = Max).postGcFraction shouldBe 0.25 +- 1e-12
    OldGenReading(postGcUsed = 250L, currentUsed = 400L, max = 0L).postGcFraction shouldBe 0.0
  }
