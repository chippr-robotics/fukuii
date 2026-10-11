package com.chipprbots.ethereum.blockchain.sync.snap

import java.nio.file.Files
import java.nio.file.Path

import org.apache.pekko.actor.testkit.typed.scaladsl.FishingOutcomes
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.util.ByteString

import scala.concurrent.duration.*
import scala.util.Using

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetAccountRange.GetAccountRangeEnc
import com.chipprbots.ethereum.testing.Tags.*

/** Spec 016 lift slice T053c: characterization of the last core members the small controller moves (M5c, M7b, M9b)
  * take, as they behave today. It adds only what the existing suites leave unasserted:
  *   - the `AccountRangeProgressCmd` checkpoint arm (M5c): a superseded generation's snapshot is dropped (nothing
  *     persisted, nothing kept for the next launch); a snapshot after accounts complete is dropped; a durable snapshot
  *     with task files is persisted as the versioned checkpoint at the current root and the tracked pivot, and sweeps
  *     superseded task files once per record; a durable snapshot without task files and a non-durable snapshot are kept
  *     in memory only, and the next launch resumes from them; the tracked pivot follows a pivot refresh;
  *   - the proactive pivot-roll trigger inside `checkDownloadStagnationArms` (M7b): the age boundary (100 blocks does
  *     not roll, 101 does), the recently-rolled window (50 blocks past the last roll does not roll, 51 does), the late
  *     storage stage (80 % of contracts done does not roll in `ByteCodeAndStorageSync`, 79 % does), no roll in
  *     `StateHealing`, and no second roll while the first roll's readiness probe is in flight;
  *   - the stop helpers (M9b) at SNAP finalisation: the state-sync child is stopped, the ChainDownloader is not.
  *
  * Already pinned elsewhere and not repeated: the proactive roll firing in `AccountRangeSync` and its probe commit and
  * timeout (`SNAPPivotRefreshCharacterizationSpec`, T023); the roll of an old pivot after a dormant start
  * (`SNAPDormantRecoverySpec`); the zero-peer log and the progress `ask`s of the stagnation tick
  * (`SNAPStagnationCharacterizationSpec`, T021); `delayedRestartArms` in `AccountRangeSync`
  * (`SNAPResetCharacterizationSpec`, T022) and in `ByteCodeAndStorageSync` and `StateHealing`
  * (`SNAPPivotRefreshArmsCharacterizationSpec`, T052a); the heap-watchdog stop in `onStop` and in
  * `stopSnapOnlySchedules` at finalisation (`SNAPBudgetWatchdogPinSpec`); and the arm order for every command and phase
  * (`SNAPSyncingDispatchTableSpec`, T027).
  *
  * Not pinned (no deterministic observation point): the roll trigger's 30-s probe cooldown (`lastProbeAttemptMs`, wall
  * clock; in the in-flight case it is shadowed by `pivotProbeRequestId`); the timers `stopSnapOnlySchedules` cancels
  * and the two scheduler flags it clears (`completedWithBackfill` drops every leaked tick, so a cancelled and an
  * uncancelled timer look the same from outside); `progressMonitor.stopPeriodicLogging`; and the two flags
  * `stopStateSyncChildren` clears (`forceCompleteStorageSent`, `healingWalkLocalOnly`).
  */
class SNAPFinalLiftCharacterizationSpec
    extends ScalaTestWithActorTestKit(SnapControllerFixture.config)
    with AnyFlatSpecLike
    with Matchers:
  import SnapControllerFixture.*

  private val Offset = SNAPSyncConfig().pivotBlockOffset // 64

  private val RollLine = "[PIVOT-ROLL] Proactive pivot roll"

  private def cursor(tag: Int): ByteString = ByteString(Array.fill(32)(tag.toByte))

  /** Two account ranges: the first complete (next >= last), the second not. */
  private val Cursors: Map[ByteString, ByteString] = Map(cursor(0x7f) -> cursor(0x80), cursor(0xff) -> cursor(0x10))

  private def tempDir(): Path =
    val dir = Files.createTempDirectory("snap-016-t053c")
    dir.toFile.deleteOnExit()
    dir

  private def touch(dir: Path, name: String): Path =
    val p = Files.createFile(dir.resolve(name))
    p.toFile.deleteOnExit()
    p

  /** The coordinator's own contract task files in `dir`. */
  private def ownTaskFiles(dir: Path): ContractTaskFiles =
    ContractTaskFiles(
      storagePath = touch(dir, "fukuii-contract-storage-own.bin").toString,
      storageCount = 3L,
      codeHashesPath = touch(dir, "fukuii-unique-codehashes-own.bin").toString,
      codeHashesCount = 2L
    )

  private def expectBootstrapRequest(f: SnapControllerFixture, target: BigInt): Unit =
    f.parent.fishForMessage(10.seconds) {
      case SNAPSyncController.StartRegularSyncBootstrap(`target`) => FishingOutcomes.complete
      case _                                                      => FishingOutcomes.continueAndIgnore
    }
    ()

  /** The `SNAPResetCharacterizationSpec` route: the head moves to 1,200, `DelayedRestart` re-bootstraps, and the new
    * pivot's header relaunches the account phase. Returns the relaunched account child's spawn.
    */
  private def restartAndRelaunch(
      f: SnapControllerFixture,
      snap: ActorRef[SNAPSyncController.Command]
  ): ChildSpawn.AccountRange =
    f.pollWith(snap, f.peersAt(height = 1_200))
    val newPivot = BigInt(1_200) - Offset
    snap ! SNAPSyncController.DelayedRestart("lift-t053c")
    expectBootstrapRequest(f, newPivot)
    f.awaitProcessed(snap)
    snap ! SNAPSyncController.BootstrapComplete(Some(f.storeHeaderAt(newPivot, root(0x33))))
    val relaunched = f.expectSpawn[ChildSpawn.AccountRange]
    f.awaitProcessed(snap)
    relaunched

  /** The `SNAPPivotRefreshCharacterizationSpec` route: a peer-scarcity unservable report refreshes the pivot in place
    * to `1,200 - offset`, and the header's arrival commits it at `newRoot`. Returns the new pivot.
    */
  private def refreshPivotTo1200(
      f: SnapControllerFixture,
      entry: AccountSyncEntry,
      newRoot: ByteString
  ): BigInt =
    val snap = entry.snap
    f.pollWith(snap, f.peersAt(height = 1_200))
    snap ! SNAPSyncController.PivotStateUnservable(
      entry.header.stateRoot.value,
      "lift-t053c",
      3,
      SNAPSyncController.UnservableCause.PeerScarcity
    )
    val newPivot = BigInt(1_200) - Offset
    expectBootstrapRequest(f, newPivot)
    snap ! SNAPSyncController.BootstrapComplete(Some(f.headerAt(newPivot, newRoot)))
    f.awaitProcessed(snap)
    f.appStateStorage.getSnapSyncPivotBlock() shouldBe Some(newPivot)
    newPivot

  private def tick(f: SnapControllerFixture, snap: ActorRef[SNAPSyncController.Command]): Unit =
    snap ! SNAPSyncController.CheckDownloadStagnation
    f.awaitProcessed(snap)

  // ── AccountRangeProgressCmd (the checkpoint arm) ─────────────────────────────────────────────

  "SNAPSyncController's AccountRangeProgressCmd arm" should
    "drop a snapshot from a superseded coordinator generation: nothing persisted, swept or kept for the next launch" taggedAs UnitTest in {
      val dir = tempDir()
      val f = new SnapControllerFixture(testKit)
      val entry = f.enterAccountSync(SNAPSyncConfig(taskFileDir = Some(dir)))
      val superseded = entry.account.progressGeneration - 1
      val files = ownTaskFiles(dir)
      val stale = touch(dir, "fukuii-contract-storage-stale.bin")

      Using.resource(new SnapLogCapture) { log =>
        entry.snap ! SNAPSyncController.AccountRangeProgressCmd(
          Cursors,
          Some(files),
          durable = true,
          generation = superseded
        )
        f.awaitProcessed(entry.snap)
        log.contains(
          s"Dropping account progress snapshot from superseded coordinator generation $superseded"
        ) shouldBe true
        log.contains("Account resume checkpoint") shouldBe false
      }
      f.appStateStorage.getSnapAccountResumeCheckpoint() shouldBe None
      Files.exists(stale) shouldBe true

      restartAndRelaunch(f, entry.snap).resumeProgress shouldBe empty
    }

  it should "drop a snapshot that arrives after accounts complete" taggedAs UnitTest in {
    val dir = tempDir()
    val f = new SnapControllerFixture(testKit)
    val snap = f.enterStorageResume(SNAPSyncConfig(deferredMerkleization = false, taskFileDir = Some(dir)))
    f.appStateStorage.isSnapSyncAccountsComplete() shouldBe true
    val files = ownTaskFiles(dir)

    Using.resource(new SnapLogCapture) { log =>
      snap ! SNAPSyncController.AccountRangeProgressCmd(
        Cursors,
        Some(files),
        durable = true,
        generation = Long.MaxValue
      )
      f.awaitProcessed(snap)
      log.contains("Dropping account progress snapshot after accounts complete") shouldBe true
      log.contains("Account resume checkpoint") shouldBe false
    }
    f.appStateStorage.getSnapAccountResumeCheckpoint() shouldBe None
  }

  it should "persist a durable snapshot with task files as the versioned checkpoint at the current root and pivot, " +
    "and sweep superseded task files once per record" taggedAs UnitTest in {
      val dir = tempDir()
      val f = new SnapControllerFixture(testKit)
      val entry = f.enterAccountSync(SNAPSyncConfig(taskFileDir = Some(dir)))
      val pivot = entry.header.number.value
      val generation = entry.account.progressGeneration
      val files = ownTaskFiles(dir)
      val stale = touch(dir, "fukuii-contract-storage-stale.bin")
      val unrelated = touch(dir, "not-a-task-file.bin")

      Using.resource(new SnapLogCapture) { log =>
        entry.snap ! SNAPSyncController.AccountRangeProgressCmd(
          Cursors,
          Some(files),
          durable = true,
          generation = generation
        )
        f.awaitProcessed(entry.snap)
        log.contains(
          s"Account resume checkpoint persisted: 2 ranges (1 fully complete), 3 storage-task entries, 2 codeHashes, " +
            s"pivot $pivot"
        ) shouldBe true
        log.count("Deleted 1 superseded SNAP contract task file(s) (new account resume checkpoint)") shouldBe 1
      }
      f.appStateStorage.getSnapAccountResumeCheckpoint().map(AccountResumeCheckpoint.decode) shouldBe
        Some(Right(AccountResumeCheckpoint(entry.header.stateRoot.value, pivot, Cursors, files)))
      Files.exists(stale) shouldBe false
      Files.exists(Path.of(files.storagePath)) shouldBe true
      Files.exists(Path.of(files.codeHashesPath)) shouldBe true
      Files.exists(unrelated) shouldBe true

      // A second record naming the same storage file persists again but does not sweep again.
      val staleAgain = touch(dir, "fukuii-unique-codehashes-stale.bin")
      Using.resource(new SnapLogCapture) { log =>
        entry.snap ! SNAPSyncController.AccountRangeProgressCmd(
          Cursors,
          Some(files),
          durable = true,
          generation = generation
        )
        f.awaitProcessed(entry.snap)
        log.contains("Account resume checkpoint persisted: 2 ranges") shouldBe true
        log.contains("superseded SNAP contract task file") shouldBe false
      }
      Files.exists(staleAgain) shouldBe true
    }

  it should "keep a durable snapshot without task files in memory only; the next launch resumes from it" taggedAs UnitTest in {
    val f = new SnapControllerFixture(testKit)
    val entry = f.enterAccountSync()
    Using.resource(new SnapLogCapture) { log =>
      entry.snap ! SNAPSyncController.AccountRangeProgressCmd(
        Cursors,
        None,
        durable = true,
        generation = entry.account.progressGeneration
      )
      f.awaitProcessed(entry.snap)
      log.contains(
        "Account resume checkpoint kept in memory only (stateRoot=true, pivot=true, taskFiles=false)"
      ) shouldBe true
    }
    f.appStateStorage.getSnapAccountResumeCheckpoint() shouldBe None

    restartAndRelaunch(f, entry.snap).resumeProgress shouldBe Cursors
  }

  it should "keep a non-durable snapshot in memory only; the next launch resumes from it" taggedAs UnitTest in {
    val f = new SnapControllerFixture(testKit)
    val entry = f.enterAccountSync()
    Using.resource(new SnapLogCapture) { log =>
      entry.snap ! SNAPSyncController.AccountRangeProgressCmd(
        Cursors,
        None,
        durable = false,
        generation = entry.account.progressGeneration
      )
      f.awaitProcessed(entry.snap)
      log.contains("Account resume checkpoint") shouldBe false
    }
    f.appStateStorage.getSnapAccountResumeCheckpoint() shouldBe None

    restartAndRelaunch(f, entry.snap).resumeProgress shouldBe Cursors
  }

  it should "record the pivot the cursors were last advanced against, not the first one" taggedAs UnitTest in {
    val dir = tempDir()
    val f = new SnapControllerFixture(testKit)
    val entry = f.enterAccountSync(SNAPSyncConfig(taskFileDir = Some(dir)))
    val generation = entry.account.progressGeneration
    // Advanced at the first pivot (memory only), then the pivot is refreshed in place.
    entry.snap ! SNAPSyncController.AccountRangeProgressCmd(Cursors, None, durable = false, generation = generation)
    val newRoot = root(0x44)
    val newPivot = refreshPivotTo1200(f, entry, newRoot)

    val files = ownTaskFiles(dir)
    entry.snap ! SNAPSyncController.AccountRangeProgressCmd(
      Cursors,
      Some(files),
      durable = true,
      generation = generation
    )
    f.awaitProcessed(entry.snap)
    f.appStateStorage.getSnapAccountResumeCheckpoint().map(AccountResumeCheckpoint.decode) shouldBe
      Some(Right(AccountResumeCheckpoint(newRoot, newPivot, Cursors, files)))
  }

  // ── The proactive pivot-roll trigger (inside checkDownloadStagnationArms) ────────────────────

  "SNAPSyncController's proactive pivot-roll trigger" should
    "not roll a pivot 100 blocks behind the network best, and roll one 101 blocks behind" taggedAs UnitTest in {
      val f = new SnapControllerFixture(testKit)
      val entry = f.enterAccountSync(height = 1_000)
      val snap = entry.snap
      val pivot = entry.header.number.value // 936
      Using.resource(new SnapLogCapture) { log =>
        f.pollWith(snap, f.peersAt(height = (pivot + 100).toInt))
        tick(f, snap)
        log.contains(RollLine) shouldBe false

        f.pollWith(snap, f.peersAt(height = (pivot + 101).toInt))
        tick(f, snap)
        log.contains(s"$RollLine: pivot=$pivot network=${pivot + 101} age=101") shouldBe true
        log.count(RollLine) shouldBe 1
      }
    }

  it should "not roll within 50 blocks of the last committed refresh, and roll 51 blocks past it" taggedAs UnitTest in {
    val f = new SnapControllerFixture(testKit)
    val entry = f.enterAccountSync(height = 1_000)
    val snap = entry.snap
    val newPivot = refreshPivotTo1200(f, entry, root(0x44)) // 1,136; last roll recorded at 1,136 + 64 = 1,200
    Using.resource(new SnapLogCapture) { log =>
      f.pollWith(snap, f.peersAt(height = 1_250)) // age 114, 50 past the last roll
      tick(f, snap)
      log.contains(RollLine) shouldBe false

      f.pollWith(snap, f.peersAt(height = 1_251)) // age 115, 51 past the last roll
      tick(f, snap)
      log.contains(s"$RollLine: pivot=$newPivot network=1251 age=115") shouldBe true
    }
  }

  it should "not roll in ByteCodeAndStorageSync once 80% of the storage contracts are done, and roll at 79%" taggedAs UnitTest in {
    val f = new SnapControllerFixture(testKit)
    val snap = f.enterStorageResume()
    val network = Pivot + 101
    Using.resource(new SnapLogCapture) { log =>
      f.pollWith(snap, f.peersAt(height = network.toInt))
      snap ! SNAPSyncController.ProgressStorageContracts(80, 100)
      tick(f, snap)
      log.contains(RollLine) shouldBe false

      snap ! SNAPSyncController.ProgressStorageContracts(79, 100)
      tick(f, snap)
      log.contains(s"$RollLine: pivot=$Pivot network=$network age=101") shouldBe true
    }
  }

  it should "not roll in StateHealing" taggedAs UnitTest in {
    val f = new SnapControllerFixture(testKit)
    val snap = f.enterStateHealing()
    Using.resource(new SnapLogCapture) { log =>
      f.pollWith(snap, f.peersAt(height = (Pivot + 200).toInt))
      tick(f, snap)
      log.contains(RollLine) shouldBe false
    }
  }

  it should "not roll again while the first roll's readiness probe is in flight" taggedAs UnitTest in {
    val f = new SnapControllerFixture(testKit)
    val entry = f.enterAccountSync(height = 1_000)
    val snap = entry.snap
    // The new pivot's header is local, so the roll goes straight to the readiness probe.
    f.storeHeaderAt(BigInt(1_200) - Offset, root(0x55))
    Using.resource(new SnapLogCapture) { log =>
      f.pollWith(snap, f.peersAt(height = 1_200))
      snap ! SNAPSyncController.CheckDownloadStagnation
      f.fishFor(f.npm) {
        case NetworkPeerManagerActor.SendMessageCmd(_: GetAccountRangeEnc, _) => true
        case _                                                                => false
      }
      tick(f, snap)
      log.count(RollLine) shouldBe 1
    }
  }

  // ── The stop helpers (stopSnapOnlySchedules, stopStateSyncChildren) at finalisation ──────────

  "SNAPSyncController at SNAP finalisation" should
    "stop the state-sync child but not the ChainDownloader" taggedAs UnitTest in {
      val f = new SnapControllerFixture(testKit)
      val snap = f.enterStorageResume(SNAPSyncConfig(deferredMerkleization = true))
      f.factories.chainSpawns.get() shouldBe 1
      // Deferred merkleization + storage force-completed: lazy-healing handoff, finalizeSnapSync.
      snap ! SNAPSyncController.StorageRangeSyncForceCompleted
      f.parent.fishForMessage(10.seconds) {
        case SNAPSyncController.SnapSyncFinalized(Pivot) => FishingOutcomes.complete
        case _                                           => FishingOutcomes.continueAndIgnore
      }
      f.awaitProcessed(snap)
      f.stops.receiveMessage(10.seconds).kind shouldBe ChildStopped.Storage
      f.stops.expectNoMessage(300.millis) // the ChainDownloader survives
    }
