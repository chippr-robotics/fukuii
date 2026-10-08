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

  "OldGenReading" should "report the post-GC fraction of the maximum" taggedAs UnitTest in {
    OldGenReading(postGcUsed = 250L, currentUsed = 400L, max = Max).postGcFraction shouldBe 0.25 +- 1e-12
    OldGenReading(postGcUsed = 250L, currentUsed = 400L, max = 0L).postGcFraction shouldBe 0.0
  }
