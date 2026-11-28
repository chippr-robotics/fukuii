package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

/** Spec 014: the SNAP intake gate. Deterministic — the clock is an injected cell, no sleeps. */
class SnapIntakeBudgetSpec extends AnyFlatSpec with Matchers:

  private class Clock(var now: Long) extends (() => Long):
    def apply(): Long = now

  private def budget(storage: Long = 100L, codes: Long = 1000L, staleMs: Long = 60000L, clock: Clock = Clock(0L)) =
    new SnapIntakeBudget(storage, codes, staleMs, clock)

  "SnapIntakeBudget" should "admit intake while in-transit plus queued work is below the ceiling" taggedAs UnitTest in {
    val b = budget(storage = 100L)
    b.intakeAllowed shouldBe true
    b.reserve(storageTasks = 99, byteCodeHashes = 0)
    b.intakeAllowed shouldBe true
    b.reserve(storageTasks = 1, byteCodeHashes = 0)
    b.intakeAllowed shouldBe false
    b.intakeBlockedReason().get should include("storage tasks pending 100")
  }

  it should "count work already sent but not yet received (the mailbox lag that defeated the watermark)" taggedAs
    UnitTest in {
      val b = budget(storage = 100L)
      // The producer sends 3 chunks before the consumer has processed any of them.
      (1 to 3).foreach(_ => b.reserve(40, 0))
      b.pendingStorageTasks shouldBe 120L
      b.intakeAllowed shouldBe false
      // The consumer receives one chunk: 40 leave transit, its queue now holds 40.
      b.storageReceived(40, queuedAfter = 40L)
      b.storageTasksInTransit shouldBe 80L
      b.pendingStorageTasks shouldBe 120L
      b.intakeAllowed shouldBe false
      // Dispatch drains the queue; the remaining in-transit work still counts.
      b.storageQueueDepth(0L)
      b.pendingStorageTasks shouldBe 80L
      b.intakeAllowed shouldBe true
    }

  it should "gate on codeHashes independently of storage tasks" taggedAs UnitTest in {
    val b = budget(storage = 100L, codes = 50L)
    b.reserve(storageTasks = 0, byteCodeHashes = 50)
    b.intakeBlockedReason().get should include("codeHashes pending 50")
    b.byteCodeReceived(50, queuedHashesAfter = 10L)
    b.intakeAllowed shouldBe true
  }

  it should "release work the controller drops, clamping at zero" taggedAs UnitTest in {
    val b = budget()
    b.reserve(10, 5)
    b.release(10, 5)
    b.pendingStorageTasks shouldBe 0L
    b.pendingByteCodeHashes shouldBe 0L
    // A release or receipt larger than what is in transit (e.g. unreserved healing bytecodes) never goes negative.
    b.release(7, 7)
    b.storageReceived(3, queuedAfter = 3L)
    b.storageTasksInTransit shouldBe 0L
    b.byteCodeHashesInTransit shouldBe 0L
    b.pendingStorageTasks shouldBe 3L
  }

  it should "reset a consumer's counts when it (re)attaches" taggedAs UnitTest in {
    val b = budget(storage = 100L)
    b.reserve(100, 0)
    b.storageQueueDepth(50L)
    b.intakeAllowed shouldBe false
    b.attachStorageConsumer()
    b.pendingStorageTasks shouldBe 0L
    b.intakeAllowed shouldBe true
  }

  it should "write off in-transit work no consumer acknowledged within the stale window" taggedAs UnitTest in {
    val clock = Clock(1000L)
    val b = budget(storage = 100L, staleMs = 60000L, clock = clock)
    b.reserve(100, 0) // e.g. sent to a coordinator that was stopped before reading its mailbox
    b.intakeAllowed shouldBe false
    clock.now += 59999L
    b.intakeAllowed shouldBe false
    clock.now += 2L
    b.intakeAllowed shouldBe true
    b.storageTasksInTransit shouldBe 0L
  }

  it should "not write off in-transit work while the consumer keeps acknowledging" taggedAs UnitTest in {
    val clock = Clock(0L)
    val b = budget(storage = 100L, staleMs = 60000L, clock = clock)
    b.reserve(100, 0)
    clock.now += 50000L
    b.storageReceived(10, queuedAfter = 10L) // a receipt refreshes the window
    clock.now += 50000L
    b.storageTasksInTransit shouldBe 90L
    b.intakeAllowed shouldBe false
  }

  it should "close the gate under heap pressure whatever the queue depth" taggedAs UnitTest in {
    val b = budget()
    b.setHeapPressure(true)
    b.intakeBlockedReason() shouldBe Some("heap pressure")
    b.setHeapPressure(false)
    b.intakeAllowed shouldBe true
  }

  it should "reject non-positive ceilings" taggedAs UnitTest in {
    an[IllegalArgumentException] should be thrownBy new SnapIntakeBudget(0L, 10L)
    an[IllegalArgumentException] should be thrownBy new SnapIntakeBudget(10L, 0L)
  }

  "StorageTask.createStorageTask" should "share the full-range boundaries across tasks" taggedAs UnitTest in {
    val a = StorageTask.createStorageTask(ByteString(Array[Byte](1)), ByteString(Array[Byte](2)))
    val b = StorageTask.createStorageTask(ByteString(Array[Byte](3)), ByteString(Array[Byte](4)))
    (a.next eq b.next) shouldBe true
    (a.last eq b.last) shouldBe true
    a.next shouldBe ByteString(Array.fill(32)(0.toByte))
    a.last shouldBe ByteString(Array.fill(32)(0xff.toByte))
  }

  // B-546: the write-off ran only after the heap-pressure early return, so under pressure stale in-transit work kept
  // the queues non-empty and the watchdog's max-pause escape could not fire.
  it should "write off stale in-transit work while heap pressure holds intake closed (B-546)" taggedAs UnitTest in {
    val clock = Clock(0L)
    val b = budget(storage = 100L, staleMs = 60000L, clock = clock)
    b.reserve(10, 3)
    b.setHeapPressure(true)
    clock.now += 60001L
    b.intakeBlockedReason() shouldBe Some("heap pressure")
    b.storageTasksInTransit shouldBe 0L
    b.byteCodeHashesInTransit shouldBe 0L
  }

  it should "let the watchdog's max-pause escape fire once stale in-transit work is written off (B-546)" taggedAs
    UnitTest in {
      val clock = Clock(0L)
      val b = budget(storage = 100L, staleMs = 60000L, clock = clock)
      val escapes = scala.collection.mutable.ArrayBuffer.empty[HeapWatchdogEscape]
      val watchdog = new SnapHeapWatchdog(
        new HeapPressureHysteresis(0.8, 0.5),
        read = () => Some(OldGenReading(postGcUsed = 90L, currentUsed = 90L, max = 100L)),
        onChange = (engaged, _) => b.setHeapPressure(engaged),
        queuesEmpty = () => b.pendingStorageTasks == 0L && b.pendingByteCodeHashes == 0L,
        policy = HeapWatchdogEscapePolicy(ineffectiveAfterMs = 1000L, maxPauseMs = 5000L),
        onEscape = (e, _) => escapes += e,
        nowMs = clock
      )
      b.reserve(10, 0) // sent to a coordinator that died before reading it
      watchdog.evaluate() // engages: the pause starts at t=0
      b.heapPressureActive shouldBe true

      clock.now += 60001L
      watchdog.evaluate()
      escapes shouldBe empty // the lost reservation still reads as queued work

      b.intakeBlockedReason() shouldBe Some("heap pressure") // a producer's poll writes it off
      watchdog.evaluate() // queues empty from here
      clock.now += 1000L
      watchdog.evaluate()
      // The pause is 61 s old, past the 5 s maximum, so the idle window ends it at once.
      escapes.toSeq shouldBe Seq(
        HeapWatchdogEscape.PauseIneffective(pausedMs = 61001L, idleMs = 1000L),
        HeapWatchdogEscape.ForcedRelease(pausedMs = 61001L)
      )
      watchdog.isForceReleased shouldBe true
    }
