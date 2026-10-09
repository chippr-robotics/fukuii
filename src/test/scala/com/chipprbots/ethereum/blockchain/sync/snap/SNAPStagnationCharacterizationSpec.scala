package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.actor.testkit.typed.scaladsl.FishingOutcomes
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.ActorRef

import scala.concurrent.duration.*
import scala.util.Using

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap.actors.AccountRangeCoordinator
import com.chipprbots.ethereum.blockchain.sync.snap.actors.AccountRangeStats
import com.chipprbots.ethereum.blockchain.sync.snap.actors.ByteCodeCoordinator
import com.chipprbots.ethereum.blockchain.sync.snap.actors.StorageRangeCoordinator
import com.chipprbots.ethereum.testing.Tags.*

/** Spec 016 T021: characterization of the stagnation watchdog (`CheckDownloadStagnation` and the
  * `maybeRestartIf*`/`maybeForceComplete*` methods), as it behaves today. M7 and P4 must keep it.
  *
  * The coordinators' progress replies are sent straight to the controller as the `*CoordinatorProgress` commands the
  * `ask`s pipe back, so each one is ordered by mailbox; what the children received is read up to a fence (see
  * `SnapControllerFixture`).
  *
  * Reachable without a clock seam: the account stall, because its threshold is the config key
  * `account-stagnation-timeout` (0 here). Not reachable: the storage-tail refresh/force-complete and the bytecode
  * force-complete. Their thresholds (10 min, 60 s, 2 min, 10 min) are constants compared with
  * `System.currentTimeMillis()`, which `ManualTime` does not move, so only their below-threshold behaviour is pinned
  * here. Pinning the firing paths needs a `nowMs` seam on the controller (proposed T012 amendment).
  */
class SNAPStagnationCharacterizationSpec
    extends ScalaTestWithActorTestKit(SnapControllerFixture.config)
    with AnyFlatSpecLike
    with Matchers:

  private val stalled = AccountRangeStats(
    accountsDownloaded = 0L,
    bytesDownloaded = 0L,
    tasksCompleted = 0,
    tasksActive = 1,
    tasksPending = 3,
    progress = 0.0,
    elapsedTimeMs = 1_000L,
    contractAccountsFound = 0L
  )

  private val instantStall = SNAPSyncConfig(accountStagnationTimeout = Duration.Zero)

  /** `RecoverStalledAccountTasks` the account child received since the previous call. */
  private def recoverRequests(f: SnapControllerFixture, snap: ActorRef[SNAPSyncController.Command]): Int =
    f.accountBefore(f.sendFence(snap)).count(_ == AccountRangeCoordinator.RecoverStalledAccountTasks)

  "SNAPSyncController stagnation check" should
    "ask only the account coordinator for progress during AccountRangeSync" taggedAs UnitTest in {
      val f = new SnapControllerFixture(testKit)
      val snap = f.enterGenesisAccountSync()
      val before = f.sendFence(snap)
      f.storageBefore(before)
      f.byteCodeBefore(before)
      snap ! SNAPSyncController.CheckDownloadStagnation
      f.fish[AccountRangeCoordinator.Command, AccountRangeCoordinator.AccountGetProgress](f.accountInbox)
      val after = f.sendFence(snap)
      f.storageBefore(after).collect { case m: StorageRangeCoordinator.StorageGetProgress => m } shouldBe empty
      f.byteCodeBefore(after).collect { case m: ByteCodeCoordinator.ByteCodeGetProgress => m } shouldBe empty
    }

  it should "ask the storage and bytecode coordinators for progress during ByteCodeAndStorageSync" taggedAs UnitTest in {
    val f = new SnapControllerFixture(testKit)
    val snap = f.enterStorageAndByteCodeResume()
    snap ! SNAPSyncController.CheckDownloadStagnation
    f.fish[StorageRangeCoordinator.Command, StorageRangeCoordinator.StorageGetProgress](f.storageInbox)
    f.fish[ByteCodeCoordinator.Command, ByteCodeCoordinator.ByteCodeGetProgress](f.byteCodeInbox)
  }

  // ── Account stall ──────────────────────────────────────────────────────────────────────────────

  "SNAPSyncController account stall" should
    "with snap peers: ask the account coordinator to recover stalled tasks and refresh the pivot in place" taggedAs UnitTest in {
      val f = new SnapControllerFixture(testKit)
      val snap = f.enterGenesisAccountSync(instantStall)
      recoverRequests(f, snap) shouldBe 0
      Using.resource(new SnapLogCapture) { log =>
        snap ! SNAPSyncController.AccountCoordinatorProgress(stalled)
        f.awaitProcessed(snap)
        log.contains("Account stall detected") shouldBe true
        log.contains("Refreshing pivot in-place: account stall") shouldBe true
        // The only snap peer is at height 10, below the pivot offset: no newer pivot, so a retry is scheduled.
        log.contains("Cannot refresh pivot: no suitable SNAP peers available. Scheduling retry in 30s.") shouldBe true
        recoverRequests(f, snap) shouldBe 1

        // A second stalled report right away is debounced by MinPivotRestartInterval (30 s of wall clock).
        log.clear()
        snap ! SNAPSyncController.AccountCoordinatorProgress(stalled)
        f.awaitProcessed(snap)
        log.contains("Account stall detected") shouldBe false
        recoverRequests(f, snap) shouldBe 0

        // The scheduled retry fires after 30 s.
        f.manualTime.timePasses(30.seconds)
        f.awaitProcessed(snap)
        log.contains("Retrying pivot refresh after bootstrap failure") shouldBe true
      }
    }

  it should
    "with snap peers at a newer head: start the refresh by pausing the chain download and asking for the new pivot header" taggedAs UnitTest in {
      val f = new SnapControllerFixture(testKit)
      val snap = f.enterAccountSync(instantStall, height = 1_000).snap
      f.pollWith(snap, f.peersAt(height = 1_200))
      recoverRequests(f, snap) shouldBe 0
      snap ! SNAPSyncController.AccountCoordinatorProgress(stalled)
      f.fishFor(f.chainInbox)(_ == ChainDownloader.Pause)
      val newPivot = BigInt(1_200) - instantStall.pivotBlockOffset
      f.parent.fishForMessage(10.seconds) {
        case SNAPSyncController.StartRegularSyncBootstrap(`newPivot`) => FishingOutcomes.complete
        case _                                                        => FishingOutcomes.continueAndIgnore
      }
      recoverRequests(f, snap) shouldBe 1
    }

  it should "with no snap peer connected: wait for peers, without recovering tasks or refreshing" taggedAs UnitTest in {
    val f = new SnapControllerFixture(testKit)
    val snap = f.enterGenesisAccountSync(instantStall)
    f.pollWith(snap, Map.empty)
    recoverRequests(f, snap) shouldBe 0
    Using.resource(new SnapLogCapture) { log =>
      snap ! SNAPSyncController.AccountCoordinatorProgress(stalled)
      f.awaitProcessed(snap)
      log.contains("but no SNAP peers available") shouldBe true
      log.contains("Refreshing pivot in-place") shouldBe false
    }
    recoverRequests(f, snap) shouldBe 0
  }

  it should "treat paused dispatch as flow control, not a stall" taggedAs UnitTest in {
    val f = new SnapControllerFixture(testKit)
    val snap = f.enterGenesisAccountSync(instantStall)
    recoverRequests(f, snap) shouldBe 0
    snap ! SNAPSyncController.AccountCoordinatorProgress(stalled.copy(dispatchPaused = true))
    recoverRequests(f, snap) shouldBe 0
  }

  it should "treat any downloaded-account progress as liveness" taggedAs UnitTest in {
    val f = new SnapControllerFixture(testKit)
    val snap = f.enterGenesisAccountSync(instantStall)
    recoverRequests(f, snap) shouldBe 0
    snap ! SNAPSyncController.AccountCoordinatorProgress(stalled.copy(accountsDownloaded = 5L))
    recoverRequests(f, snap) shouldBe 0
  }

  it should "not count a report with no pending or active task" taggedAs UnitTest in {
    val f = new SnapControllerFixture(testKit)
    val snap = f.enterGenesisAccountSync(instantStall)
    recoverRequests(f, snap) shouldBe 0
    snap ! SNAPSyncController.AccountCoordinatorProgress(stalled.copy(tasksActive = 0, tasksPending = 0))
    recoverRequests(f, snap) shouldBe 0
  }

  // ── Storage and bytecode: below their wall-clock thresholds ────────────────────────────────────

  private def storageStats(pending: Int, active: Int, completed: Int, elapsedMs: Long) =
    StorageRangeCoordinator.SyncStatistics(
      slotsDownloaded = 0L,
      bytesDownloaded = 0L,
      tasksCompleted = completed,
      tasksActive = active,
      tasksPending = pending,
      elapsedTimeMs = elapsedMs,
      progress = 0.0
    )

  private def forceCompletes(f: SnapControllerFixture, snap: ActorRef[SNAPSyncController.Command]): Int =
    f.storageBefore(f.sendFence(snap)).count(_ == StorageRangeCoordinator.ForceCompleteStorage)

  "SNAPSyncController storage stall check" should
    "take no action while work remains and the 10-minute threshold has not passed" taggedAs UnitTest in {
      val f = new SnapControllerFixture(testKit)
      val snap = f.enterStorageResume()
      forceCompletes(f, snap) shouldBe 0
      Using.resource(new SnapLogCapture) { log =>
        snap ! SNAPSyncController.StorageCoordinatorProgress(storageStats(pending = 5, active = 1, 2, 1_000L))
        f.awaitProcessed(snap)
        log.contains("Storage stagnation check: pending=5, active=1, completed=2") shouldBe true
        log.contains("Attempting pivot refresh") shouldBe false
      }
      forceCompletes(f, snap) shouldBe 0
    }

  it should "treat an all-zero report (the ask timed out) as liveness" taggedAs UnitTest in {
    val f = new SnapControllerFixture(testKit)
    val snap = f.enterStorageResume()
    forceCompletes(f, snap) shouldBe 0
    snap ! SNAPSyncController.StorageCoordinatorProgress(storageStats(0, 0, 0, 0L))
    forceCompletes(f, snap) shouldBe 0
  }

  it should
    "not force-complete a coordinator reporting no pending or active work before the 60-second threshold" taggedAs UnitTest in {
      val f = new SnapControllerFixture(testKit)
      val snap = f.enterStorageResume()
      forceCompletes(f, snap) shouldBe 0
      Using.resource(new SnapLogCapture) { log =>
        snap ! SNAPSyncController.StorageCoordinatorProgress(storageStats(pending = 0, active = 0, 7, 1_000L))
        f.awaitProcessed(snap)
        log.contains("Trie construction likely stuck") shouldBe false
      }
      forceCompletes(f, snap) shouldBe 0
    }

  "SNAPSyncController bytecode stall check" should
    "not force-complete bytecode before the 10-minute threshold" taggedAs UnitTest in {
      val f = new SnapControllerFixture(testKit)
      val snap = f.enterStorageAndByteCodeResume()
      f.byteCodeBefore(f.sendFence(snap))
      Using.resource(new SnapLogCapture) { log =>
        snap ! SNAPSyncController.ByteCodeCoordinatorProgress(ByteCodeCoordinator.ByteCodeProgress(0.0, 0L, 0L))
        f.awaitProcessed(snap)
        log.contains("ByteCode stagnation check: downloaded=0") shouldBe true
      }
      f.byteCodeBefore(f.sendFence(snap)) should not contain ByteCodeCoordinator.ForceCompleteByteCodes
    }
