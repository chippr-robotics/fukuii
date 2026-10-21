package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.actor.testkit.typed.scaladsl.FishingOutcomes
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.util.ByteString

import scala.concurrent.duration.*
import scala.util.Using

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap.actors.StorageRangeCoordinator
import com.chipprbots.ethereum.testing.Tags.*

/** Spec 016 T025: pins of the #1501 (spec 014) intake-budget and heap-watchdog behaviour in the controller, through the
  * S0b seams (injected `SnapIntakeBudget`, `heapWatchdogStart`). P1, M5, M9 and M11 must keep them.
  */
class SNAPBudgetWatchdogPinSpec
    extends ScalaTestWithActorTestKit(SnapControllerFixture.config)
    with AnyFlatSpecLike
    with Matchers:

  private def hashes(n: Int): Seq[ByteString] = (1 to n).map(i => ByteString(Array.fill(32)(i.toByte)))
  private def tasks(n: Int): Seq[StorageTask] =
    (1 to n).map(i =>
      StorageTask.createStorageTask(ByteString(Array.fill(32)(i.toByte)), ByteString(Array.fill(32)(9.toByte)))
    )

  // ── IncrementalContractData credit release ─────────────────────────────────────────────────────

  "SNAPSyncController (#1501 budget)" should
    "release the credit of an IncrementalContractData dropped in idle" taggedAs UnitTest in {
      val f = new SnapControllerFixture(testKit)
      val snap = f.spawnController(SNAPSyncConfig())
      f.budget.reserve(2, 3)
      f.budget.pendingStorageTasks shouldBe 2L
      f.budget.pendingByteCodeHashes shouldBe 3L
      snap ! SNAPSyncController.IncrementalContractData(hashes(3), tasks(2))
      f.awaitProcessed(snap)
      f.budget.pendingStorageTasks shouldBe 0L
      f.budget.pendingByteCodeHashes shouldBe 0L
    }

  it should
    "release the credit of an IncrementalContractData dropped in syncing with no bytecode or storage coordinator" taggedAs UnitTest in {
      val f = new SnapControllerFixture(testKit)
      val snap = f.enterStateHealing() // all download phases done: neither coordinator exists
      f.budget.reserve(2, 3)
      snap ! SNAPSyncController.IncrementalContractData(hashes(3), tasks(2))
      f.awaitProcessed(snap)
      f.budget.pendingStorageTasks shouldBe 0L
      f.budget.pendingByteCodeHashes shouldBe 0L
    }

  it should
    "release only the bytecode credit when the storage coordinator exists, and forward the storage tasks to it" taggedAs UnitTest in {
      val f = new SnapControllerFixture(testKit)
      val snap = f.enterStorageResume() // storage coordinator only (bytecode already complete)
      f.storageBefore(f.sendFence(snap))
      val storagePending = f.budget.pendingStorageTasks // the recovery stream's own reservation, if it ran yet
      f.budget.reserve(0, 3)
      snap ! SNAPSyncController.IncrementalContractData(hashes(3), tasks(2))
      f.awaitProcessed(snap)
      f.budget.pendingByteCodeHashes shouldBe 0L
      f.storageBefore(f.sendFence(snap)) should contain(StorageRangeCoordinator.AddStorageTasks(tasks(2)))
      f.budget.pendingStorageTasks should be >= storagePending // not released: the coordinator acknowledges it
    }

  // ── Gated recovery replay ──────────────────────────────────────────────────────────────────────

  it should "retry a paused recovery replay after RecoveryReplayPausedRetry = 1 s" taggedAs UnitTest in {
    SNAPSyncController.RecoveryReplayPausedRetry shouldBe 1.second
  }

  it should "hold the storage recovery stream while the intake gate is closed and resume it one retry after it opens" taggedAs UnitTest in {
    val f = new SnapControllerFixture(testKit)
    f.budget.setHeapPressure(true) // gate closed before the replay starts
    Using.resource(new SnapLogCapture) { log =>
      val snap = f.enterStorageResume()
      log.awaitLine("replay waiting at entry 0")
      // Still closed after a retry period: the replay pauses again and nothing reaches the coordinator.
      f.manualTime.timePasses(1.second)
      f.awaitProcessed(snap)
      f.budget.pendingStorageTasks shouldBe 0L
      f.storageBefore(f.sendFence(snap)).collect { case m: StorageRangeCoordinator.AddStorageTasks => m } shouldBe empty

      f.budget.setHeapPressure(false)
      f.manualTime.timePasses(1.second)
      val added = f.fish[StorageRangeCoordinator.Command, StorageRangeCoordinator.AddStorageTasks](f.storageInbox)
      added.tasks.map(t => (t.accountHash, t.storageRoot)) shouldBe
        Seq((ByteString(Array.fill(32)(0x01.toByte)), ByteString(Array.fill(32)(0x02.toByte))))
      f.fishFor(f.storageInbox)(_ == StorageRangeCoordinator.NoMoreStorageTasks)
    }
  }

  // ── Heap watchdog lifecycle ────────────────────────────────────────────────────────────────────

  "SNAPSyncController (#1501 heap watchdog)" should
    "start the watchdog with the first SNAP coordinators and stop it in onStop" taggedAs UnitTest in {
      val f = new SnapControllerFixture(testKit)
      f.heapWatchdogStarts.get() shouldBe 0
      val snap = f.enterStorageResume(SNAPSyncConfig(deferredMerkleization = false, heapWatchdogEnabled = true))
      f.heapWatchdogStarts.get() shouldBe 1
      f.heapWatchdogStops.get() shouldBe 0
      testKit.stop(snap)
      f.heapWatchdogStops.get() shouldBe 1
    }

  it should "stop the watchdog in stopSnapOnlySchedules when SNAP finalises" taggedAs UnitTest in {
    val f = new SnapControllerFixture(testKit)
    val snap = f.enterStorageResume(SNAPSyncConfig(deferredMerkleization = true, heapWatchdogEnabled = true))
    f.heapWatchdogStarts.get() shouldBe 1
    // Deferred merkleization + storage force-completed: lazy-healing handoff, finalizeSnapSync → stopSnapOnlySchedules.
    snap ! SNAPSyncController.StorageRangeSyncForceCompleted
    f.parent.fishForMessage(10.seconds) {
      case SNAPSyncController.SnapSyncFinalized(SnapControllerFixture.Pivot) => FishingOutcomes.complete
      case _                                                                 => FishingOutcomes.continueAndIgnore
    }
    f.awaitProcessed(snap)
    f.heapWatchdogStops.get() shouldBe 1
  }

  it should "not start the watchdog when heap-watchdog-enabled is off" taggedAs UnitTest in {
    val f = new SnapControllerFixture(testKit)
    f.enterStorageResume(SNAPSyncConfig(deferredMerkleization = false, heapWatchdogEnabled = false))
    f.heapWatchdogStarts.get() shouldBe 0
  }
