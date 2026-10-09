package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.actor.testkit.typed.scaladsl.FishingOutcomes
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.util.ByteString

import scala.concurrent.duration.*
import scala.util.Using

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
import com.chipprbots.ethereum.blockchain.sync.snap.actors.AccountRangeCoordinator
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.testing.Tags.*

/** Spec 016 T022: what each of the three reset paths leaves behind today — `restartSnapSync` (via `DelayedRestart`),
  * `enterDormantMode` (via the critical-failure path of validation) and `wakeFromDormant` (via `DormantWakeUp`):
  *   - which children are stopped and re-spawned (the ChainDownloader is never stopped);
  *   - the arguments of the next `Start*`/spawn;
  *   - the persisted AppState flags;
  *   - `GetStatus`;
  *   - and that none of them stops the #1501 heap watchdog (only `onStop`/`stopSnapOnlySchedules` do; T025).
  *
  * These assert today's behaviour. P2 (`PhaseFlags.reset(kind)`) and M9 must keep every line.
  */
class SNAPResetCharacterizationSpec
    extends ScalaTestWithActorTestKit(SnapControllerFixture.config)
    with AnyFlatSpecLike
    with Matchers:

  private def stoppedKinds(f: SnapControllerFixture, n: Int): Set[String] =
    f.stops.receiveMessages(n, 10.seconds).map(_.kind).toSet

  private def expectBootstrapRequest(f: SnapControllerFixture, target: BigInt): Unit =
    f.parent.fishForMessage(10.seconds) {
      case SNAPSyncController.StartRegularSyncBootstrap(`target`) => FishingOutcomes.complete
      case _                                                      => FishingOutcomes.continueAndIgnore
    }
    ()

  private def bootstrappingStatus(f: SnapControllerFixture, target: BigInt): SyncProtocol.Status =
    SyncProtocol.Status.Syncing(
      startingBlockNumber = f.appStateStorage.getSyncStartingBlock(),
      blocksProgress = SyncProtocol.Status.Progress(f.appStateStorage.getBestBlockNumber(), target),
      stateNodesProgress = None
    )

  // ── restartSnapSync ────────────────────────────────────────────────────────────────────────────

  "SNAPSyncController restartSnapSync (DelayedRestart in AccountRangeSync)" should
    "stop the three download children but not the ChainDownloader, clear the phase flags and re-bootstrap" taggedAs UnitTest in {
      val f = new SnapControllerFixture(testKit)
      val entry = f.enterAccountSync(SNAPSyncConfig(heapWatchdogEnabled = true))
      val pivot = entry.header.number.value
      f.factories.chainSpawns.get() shouldBe 1
      f.heapWatchdogStarts.get() shouldBe 1

      // The network head has moved on (with an unchanged head the new pivot would not be ahead of the stored best
      // block, and startSnapSync would hand off to regular sync instead).
      f.pollWith(entry.snap, f.peersAt(height = 1_200))
      val newPivot = BigInt(1_200) - SNAPSyncConfig().pivotBlockOffset
      entry.snap ! SNAPSyncController.DelayedRestart("s0d characterization")
      expectBootstrapRequest(f, newPivot) // startSnapSync again: a fresh network pivot
      f.awaitProcessed(entry.snap)

      stoppedKinds(f, 3) shouldBe Set(ChildStopped.Account, ChildStopped.ByteCode, ChildStopped.Storage)
      f.stops.expectNoMessage(200.millis) // the ChainDownloader survives

      // Persisted: the phase flags and the task-file handoff are cleared; the pivot anchor is not.
      f.appStateStorage.isSnapSyncAccountsComplete() shouldBe false
      f.appStateStorage.isSnapSyncStorageComplete() shouldBe false
      f.appStateStorage.isSnapSyncBytecodeComplete() shouldBe false
      f.appStateStorage.getSnapSyncStorageFilePath().filter(_.nonEmpty) shouldBe None
      f.appStateStorage.getSnapSyncStorageFileCount() shouldBe None
      f.appStateStorage.getSnapSyncCodeHashesCount() shouldBe None
      f.appStateStorage.getSnapSyncPivotBlock() shouldBe Some(pivot)
      f.appStateStorage.getSnapSyncBootstrapTarget() shouldBe Some(newPivot)

      f.status(entry.snap) shouldBe bootstrappingStatus(f, newPivot)

      // The heap watchdog keeps running across the restart.
      f.heapWatchdogStops.get() shouldBe 0

      // Next start: the new pivot's header arrives and the account phase relaunches on its root.
      val newRoot = SnapControllerFixture.root(0x33)
      entry.snap ! SNAPSyncController.BootstrapComplete(Some(f.storeHeaderAt(newPivot, newRoot)))
      val relaunched = f.expectSpawn[ChildSpawn.AccountRange]
      f.awaitProcessed(entry.snap)
      relaunched.stateRoot shouldBe newRoot
      // +1 for the ChainDownloader launch after the first account launch, +1 for the restart.
      relaunched.progressGeneration shouldBe entry.account.progressGeneration + 2
      relaunched.resumeProgress shouldBe empty
      relaunched.carriedTaskFiles shouldBe None
      relaunched.intakeBudget shouldBe Some(f.budget)
      f.fishFor(f.accountInbox)(_ == AccountRangeCoordinator.StartAccountRangeSync(newRoot))
      f.factories.accountSpawns.get() shouldBe 2
      f.factories.byteCodeSpawns.get() shouldBe 2
      f.factories.storageSpawns.get() shouldBe 2
      f.factories.chainSpawns.get() shouldBe 1 // still the first one
      f.heapWatchdogStarts.get() shouldBe 1 // not re-started: the handle was never stopped
      f.heapWatchdogStops.get() shouldBe 0
    }

  // ── enterDormantMode (critical failure) ────────────────────────────────────────────────────────

  "SNAPSyncController enterDormantMode via the validation critical-failure path" should
    "stop the healing and bytecode children (not the ChainDownloader), keep the flags, and stay in `syncing`" taggedAs UnitTest in {
      val f = new SnapControllerFixture(testKit)
      val missingRoot: MptStorage => StateValidator =
        storage => new FakeStateValidator(storage, Left("Missing root node: s0d"), Right(Seq.empty))
      val snap = f.enterStateHealing(
        SNAPSyncConfig(deferredMerkleization = false, maxSnapSyncFailures = 1, heapWatchdogEnabled = true),
        validatorFactory = missingRoot
      )
      f.factories.chainSpawns.get() shouldBe 1
      f.heapWatchdogStarts.get() shouldBe 1
      val spawnsBefore =
        () => (f.factories.accountSpawns.get(), f.factories.byteCodeSpawns.get(), f.factories.storageSpawns.get())

      Using.resource(new SnapLogCapture) { log =>
        // Hold StateValidation: a healed account's code is outstanding, so the clean walk's completion waits for it.
        snap ! SNAPSyncController.HealedCodeHashes(Seq(ByteString(Array.fill(32)(0x44.toByte))))
        snap ! SNAPSyncController.TrieWalkComplete(0)
        log.awaitLine("[HEAL-CODE] Holding SNAP finalisation")
        f.awaitProcessed(snap)

        // A validation pass that finds the root missing four times (MaxValidationRetries = 3) is a critical failure.
        snap ! SNAPSyncController.ValidationRetry(0L)
        (1 to 3).foreach { attempt =>
          log.awaitLine(s"Root node is missing (retry attempt $attempt of 3)")
          f.awaitProcessed(snap) // the retry timer is armed in the same handler
          f.manualTime.timePasses(500.millis)
        }
        log.awaitLine("Too many critical SNAP failures")
        log.awaitLine("Entering dormant mode")
        f.awaitProcessed(snap)

        stoppedKinds(f, 2) shouldBe Set(ChildStopped.Healing, ChildStopped.ByteCode)
        f.stops.expectNoMessage(200.millis) // the ChainDownloader survives

        // Persisted phase flags are left alone.
        f.appStateStorage.isSnapSyncAccountsComplete() shouldBe true
        f.appStateStorage.isSnapSyncStorageComplete() shouldBe true
        f.appStateStorage.isSnapSyncBytecodeComplete() shouldBe true

        // Today the dormant behaviour returned by enterDormantMode is discarded on this path: the controller stays in
        // `syncing` with currentPhase = Dormant, so GetStatus reports it as syncing with no state progress ...
        f.status(snap) shouldBe SyncProtocol.Status.Syncing(
          startingBlockNumber = f.appStateStorage.getSyncStartingBlock(),
          blocksProgress = SyncProtocol.Status.Progress(SnapControllerFixture.Pivot, SnapControllerFixture.Pivot),
          stateNodesProgress = Some(SyncProtocol.Status.Progress(BigInt(0), BigInt(0)))
        )
        // ... and the wake-up it schedules falls through to the `syncing` catch-all: nothing is re-spawned.
        val before = spawnsBefore()
        log.clear()
        snap ! SNAPSyncController.DormantWakeUp
        f.awaitProcessed(snap)
        log.contains("Unhandled message in syncing state: DormantWakeUp") shouldBe true
        spawnsBefore() shouldBe before
      }
      f.heapWatchdogStops.get() shouldBe 0
    }

  // ── wakeFromDormant ────────────────────────────────────────────────────────────────────────────

  "SNAPSyncController wakeFromDormant (DormantWakeUp in dormantRetry)" should
    "restart pivot selection from scratch and relaunch the download children; the ChainDownloader is kept" taggedAs UnitTest in {
      val f = new SnapControllerFixture(testKit)
      val entry = f.enterAccountSync(SNAPSyncConfig(heapWatchdogEnabled = true))
      val snap = entry.snap
      val pivot = entry.header.number.value

      // Dormant: the only peer stops serving snap, and the capability check sends the controller to dormantRetry.
      f.pollWith(snap, f.peersAt(height = 1_000, snap = false))
      snap ! SNAPSyncController.CheckSnapCapability
      f.awaitProcessed(snap)
      stoppedKinds(f, 3) shouldBe Set(ChildStopped.Account, ChildStopped.ByteCode, ChildStopped.Storage)
      f.stops.expectNoMessage(200.millis)
      f.status(snap) shouldBe SyncProtocol.Status.Syncing(
        startingBlockNumber = f.appStateStorage.getSyncStartingBlock(),
        blocksProgress = SyncProtocol.Status.Progress(pivot, pivot),
        stateNodesProgress = None
      )
      f.heapWatchdogStops.get() shouldBe 0

      // Wake with a snap peer at a newer head: a fresh network pivot is selected and bootstrapped.
      f.pollWith(snap, f.peersAt(height = 2_000))
      snap ! SNAPSyncController.DormantWakeUp
      val newPivot = BigInt(2_000) - SNAPSyncConfig().pivotBlockOffset
      expectBootstrapRequest(f, newPivot)
      f.awaitProcessed(snap)
      f.status(snap) shouldBe bootstrappingStatus(f, newPivot)
      f.appStateStorage.isSnapSyncAccountsComplete() shouldBe false
      f.appStateStorage.getSnapSyncBootstrapTarget() shouldBe Some(newPivot)
      f.heapWatchdogStops.get() shouldBe 0

      val newRoot = SnapControllerFixture.root(0x22)
      val newHeader = f.storeHeaderAt(newPivot, newRoot)
      snap ! SNAPSyncController.BootstrapComplete(Some(newHeader))
      val relaunched = f.expectSpawn[ChildSpawn.AccountRange]
      f.awaitProcessed(snap)
      relaunched.stateRoot shouldBe newRoot
      // +1 for the ChainDownloader launch after the first account launch, +1 for the wake.
      relaunched.progressGeneration shouldBe entry.account.progressGeneration + 2
      f.factories.accountSpawns.get() shouldBe 2
      f.factories.byteCodeSpawns.get() shouldBe 2
      f.factories.storageSpawns.get() shouldBe 2
      f.factories.chainSpawns.get() shouldBe 1
      f.heapWatchdogStarts.get() shouldBe 1
      f.heapWatchdogStops.get() shouldBe 0
    }
