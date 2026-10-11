package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.actor.testkit.typed.scaladsl.FishingOutcomes
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.util.ByteString

import scala.concurrent.duration.*

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.blockchain.sync.snap.actors.ByteCodeCoordinator
import com.chipprbots.ethereum.blockchain.sync.snap.actors.StorageRangeCoordinator
import com.chipprbots.ethereum.testing.Tags.*

/** Spec 016 restructure slice: characterization of `startSnapSync` on its main paths, as it behaves today, before its
  * body is split into named helpers (CQ-SNAP-016-22). The split must keep every line green unchanged.
  *
  * What the existing suites already pin, and so is not repeated here: the genesis and network-pivot account starts (the
  * `SnapControllerFixture` entry routes, used by every S0d suite), the task-file-unusable restarts
  * (`SNAPStorageRecoverySpec`), the bootstrap-retry budget running out into dormancy and the MinPivotBlock floor on a
  * local pivot (`SNAPDormantRecoverySpec`). This suite adds what they leave unasserted:
  *   - fresh start: the network pivot is requested and persisted as the bootstrap target, nothing is spawned yet; with
  *     no peer, nothing is requested and nothing is spawned; the CL-hint pivot on a post-merge chain;
  *   - all phases complete on a fresh pivot: straight to healing, with no download child;
  *   - the accounts-complete recovery branch: spawn order and arguments, what each child receives and in which order,
  *     the escalation-hint and missing-anchor flag wipes;
  *   - a stale pivot: the flags are wiped when a phase is incomplete and kept when all are complete (then the fresh
  *     bootstrapped pivot goes to healing);
  *   - bootstrap retry: an interrupted bootstrap is resumed, a reached one is cleared, and a retry re-enters
  *     `startSnapSync` and requests the network pivot once a SNAP peer shows its height.
  */
class SNAPStartSnapSyncCharacterizationSpec
    extends ScalaTestWithActorTestKit(SnapControllerFixture.config)
    with AnyFlatSpecLike
    with Matchers:

  import SnapControllerFixture.*

  private val Offset: Long = SNAPSyncConfig().pivotBlockOffset
  private val MaxStaleness: Long = SNAPSyncConfig().maxPivotStalenessBlocks
  private val NoDeferral = SNAPSyncConfig(deferredMerkleization = false)

  private def expectBootstrapRequest(f: SnapControllerFixture, target: BigInt): Unit =
    f.parent.fishForMessage(10.seconds) {
      case SNAPSyncController.StartRegularSyncBootstrap(`target`) => FishingOutcomes.complete
      case _                                                      => FishingOutcomes.continueAndIgnore
    }
    ()

  private def spawnCounts(f: SnapControllerFixture): (Int, Int, Int, Int, Int) =
    (
      f.factories.accountSpawns.get(),
      f.factories.byteCodeSpawns.get(),
      f.factories.storageSpawns.get(),
      f.factories.healingSpawns.get(),
      f.factories.chainSpawns.get()
    )

  private def flags(f: SnapControllerFixture): (Boolean, Boolean, Boolean) =
    (
      f.appStateStorage.isSnapSyncAccountsComplete(),
      f.appStateStorage.isSnapSyncBytecodeComplete(),
      f.appStateStorage.isSnapSyncStorageComplete()
    )

  // ── Fresh start ──────────────────────────────────────────────────────────────────────────────

  "startSnapSync on a fresh start" should
    "request the network pivot's header, persist it as the bootstrap target and spawn nothing yet" taggedAs UnitTest in {
      val f = new SnapControllerFixture(testKit)
      f.storeGenesis()
      f.peers.set(f.peersAt(height = 1_000))
      val snap = f.spawnController(SNAPSyncConfig())
      snap ! SNAPSyncController.Start
      val pivot = BigInt(1_000) - Offset
      expectBootstrapRequest(f, pivot)
      f.awaitProcessed(snap)

      f.appStateStorage.getSnapSyncBootstrapTarget() shouldBe Some(pivot)
      f.appStateStorage.getSnapSyncPivotBlock() shouldBe None
      spawnCounts(f) shouldBe ((0, 0, 0, 0, 0))
    }

  it should "request nothing and spawn nothing while no peer is connected" taggedAs UnitTest in {
    val f = new SnapControllerFixture(testKit)
    f.storeGenesis()
    val snap = f.spawnController(SNAPSyncConfig())
    snap ! SNAPSyncController.Start
    f.awaitProcessed(snap)

    f.parent.expectNoMessage(300.millis)
    f.appStateStorage.getSnapSyncBootstrapTarget() shouldBe None
    f.appStateStorage.getSnapSyncPivotBlock() shouldBe None
    spawnCounts(f) shouldBe ((0, 0, 0, 0, 0))
  }

  it should "start from the genesis pivot when the network is below the pivot offset" taggedAs UnitTest in {
    val f = new SnapControllerFixture(testKit)
    val snap = f.enterGenesisAccountSync()

    f.appStateStorage.getSnapSyncPivotBlock() shouldBe Some(BigInt(0))
    f.appStateStorage.getSnapSyncStateRoot() shouldBe Some(Fixtures.Blocks.Genesis.header.stateRoot.value)
    f.appStateStorage.getSnapSyncBootstrapTarget() shouldBe None
    f.parent.expectNoMessage(300.millis)
    f.awaitProcessed(snap)
  }

  it should "take the CL head as the pivot on a post-merge chain and start the account phase on its root" taggedAs UnitTest in {
    val f = new SnapControllerFixture(testKit)
    f.storeGenesis()
    f.peers.set(f.peersAt(height = 6_000))
    val snap = f.spawnController(SNAPSyncConfig(), isPoS = true)
    val clRoot = root(0x44)
    val clHead = f.headerAt(5_000, clRoot)
    snap ! SNAPSyncController.CLPivotHint(clHead.hash.value, Some(clHead))
    snap ! SNAPSyncController.Start
    val account = f.expectSpawn[ChildSpawn.AccountRange]
    f.awaitProcessed(snap)

    account.stateRoot shouldBe clRoot
    f.appStateStorage.getSnapSyncPivotBlock() shouldBe Some(BigInt(5_000))
    f.appStateStorage.getSnapSyncStateRoot() shouldBe Some(clRoot)
    f.appStateStorage.getSnapSyncBootstrapTarget() shouldBe None
    f.parent.expectNoMessage(300.millis)
  }

  // ── All phases complete ──────────────────────────────────────────────────────────────────────

  "startSnapSync with every phase complete on a fresh pivot" should
    "go straight to healing on the saved root, with no download child" taggedAs UnitTest in {
      val f = new SnapControllerFixture(testKit)
      f.storeGenesis()
      f.storeHeaderAt(Pivot, Root)
      f.seedAllComplete(Pivot, Root)
      val snap = f.spawnController(NoDeferral)
      snap ! SNAPSyncController.Start
      val healing = f.expectSpawn[ChildSpawn.Healing]
      f.awaitProcessed(snap)

      healing.stateRoot shouldBe Root
      // The recovery branch still launches the ChainDownloader before it hands off to healing.
      spawnCounts(f) shouldBe ((0, 0, 0, 1, 1))
      flags(f) shouldBe ((true, true, true))
      f.appStateStorage.getSnapSyncPivotBlock() shouldBe Some(Pivot)
    }

  // ── Accounts-complete recovery ───────────────────────────────────────────────────────────────

  "startSnapSync's accounts-complete recovery" should
    "spawn the bytecode then the storage child, then the ChainDownloader, with the recovery arguments" taggedAs UnitTest in {
      val f = new SnapControllerFixture(testKit)
      f.storeGenesis()
      f.storeHeaderAt(Pivot, Root)
      f.seedStorageAndByteCodeResume(Pivot, Root)
      val snap = f.spawnController(NoDeferral)
      snap ! SNAPSyncController.Start

      val spawned = f.spawns.receiveMessages(3, 10.seconds)
      f.awaitProcessed(snap)
      spawned.map {
        case _: ChildSpawn.ByteCode     => "bytecode"
        case _: ChildSpawn.StorageRange => "storage"
        case _: ChildSpawn.Chain        => "chain"
        case other                      => other.toString
      } shouldBe Seq("bytecode", "storage", "chain")

      val byteCode = spawned.collectFirst { case s: ChildSpawn.ByteCode => s }.get
      byteCode.batchSize shouldBe ByteCodeTask.DEFAULT_BATCH_SIZE
      byteCode.intakeBudget shouldBe Some(f.budget)

      val storage = spawned.collectFirst { case s: ChildSpawn.StorageRange => s }.get
      storage.stateRoot shouldBe Root
      storage.initialMaxInFlightPerPeer shouldBe 3
      storage.maxAccountsPerBatch shouldBe NoDeferral.storageBatchSize
      storage.maxInFlightRequests shouldBe NoDeferral.storageConcurrency
      storage.deferredMerkleization shouldBe false
      storage.intakeBudget shouldBe Some(f.budget)

      spawnCounts(f) shouldBe ((0, 1, 1, 0, 1))
      flags(f) shouldBe ((true, false, false))
    }

  it should "send the bytecode child its start, the 2-then-8 budget and the cooldown reset, in that order" taggedAs UnitTest in {
    val f = new SnapControllerFixture(testKit)
    val snap = f.enterStorageAndByteCodeResume(NoDeferral)
    val fence = f.sendFence(snap)

    // The task stream runs on a Future, so its messages may interleave with these; they are checked below.
    val synchronous = f.byteCodeBefore(fence).filter {
      case _: ByteCodeCoordinator.AddByteCodeTasks | ByteCodeCoordinator.NoMoreByteCodeTasks => false
      case _                                                                                 => true
    }
    synchronous shouldBe Seq(
      ByteCodeCoordinator.StartByteCodeSync(Seq.empty),
      ByteCodeCoordinator.UpdateMaxInFlightPerPeer(2),
      ByteCodeCoordinator.UpdateMaxInFlightPerPeer(8),
      ByteCodeCoordinator.ByteCodePivotRefreshed
    )
  }

  it should "stream the persisted task files to the storage and bytecode children, each ended by its NoMore" taggedAs UnitTest in {
    val f = new SnapControllerFixture(testKit)
    val snap = f.enterStorageAndByteCodeResume(NoDeferral)

    f.fishFor(f.storageInbox)(_ == StorageRangeCoordinator.StartStorageRangeSync(Root))
    val tasks = f.fish[StorageRangeCoordinator.Command, StorageRangeCoordinator.AddStorageTasks](f.storageInbox)
    tasks.tasks.map(t => (t.accountHash, t.storageRoot)) shouldBe Seq(
      (ByteString(Array.fill[Byte](32)(0x01)), ByteString(Array.fill[Byte](32)(0x02)))
    )
    f.fishFor(f.storageInbox)(_ == StorageRangeCoordinator.NoMoreStorageTasks)

    val hashes = f.fish[ByteCodeCoordinator.Command, ByteCodeCoordinator.AddByteCodeTasks](f.byteCodeInbox)
    hashes shouldBe ByteCodeCoordinator.AddByteCodeTasks(Seq(ByteString(Array.fill[Byte](32)(0x03))))
    f.fishFor(f.byteCodeInbox)(_ == ByteCodeCoordinator.NoMoreByteCodeTasks)
    f.awaitProcessed(snap)
  }

  it should "wipe the three phase flags and fall through to pivot selection when the saved pivot is below MinPivotBlock" taggedAs UnitTest in {
    val f = new SnapControllerFixture(testKit)
    f.storeGenesis()
    f.storeHeaderAt(Pivot, Root)
    f.seedAllComplete(Pivot, Root)
    val snap = f.spawnController(NoDeferral)
    snap ! SNAPSyncController.MinPivotBlock(Pivot + 1)
    snap ! SNAPSyncController.Start
    f.awaitProcessed(snap)

    flags(f) shouldBe ((false, false, false))
    // No peer: pivot selection schedules a retry and spawns nothing.
    spawnCounts(f) shouldBe ((0, 0, 0, 0, 0))
    f.parent.expectNoMessage(300.millis)
  }

  it should "wipe the three phase flags when the accounts-complete flag has no saved pivot" taggedAs UnitTest in {
    val f = new SnapControllerFixture(testKit)
    f.storeGenesis()
    f.appStateStorage
      .putSnapSyncAccountsComplete(true)
      .and(f.appStateStorage.putSnapSyncStorageComplete(true))
      .and(f.appStateStorage.putSnapSyncBytecodeComplete(true))
      .commit()
    val snap = f.spawnController(NoDeferral)
    snap ! SNAPSyncController.Start
    f.awaitProcessed(snap)

    flags(f) shouldBe ((false, false, false))
    spawnCounts(f) shouldBe ((0, 0, 0, 0, 0))
  }

  // ── Stale pivot ──────────────────────────────────────────────────────────────────────────────

  "startSnapSync with a saved pivot past the staleness limit" should
    "wipe the phase flags when a phase is incomplete and request a fresh network pivot" taggedAs UnitTest in {
      val f = new SnapControllerFixture(testKit)
      f.storeGenesis()
      f.storeHeaderAt(Pivot, Root)
      f.seedStorageResume(Pivot, Root)
      val height = (Pivot + MaxStaleness + 1_000).toInt
      f.peers.set(f.peersAt(height))
      val snap = f.spawnController(NoDeferral)
      snap ! SNAPSyncController.Start
      val fresh = BigInt(height) - Offset
      expectBootstrapRequest(f, fresh)
      f.awaitProcessed(snap)

      flags(f) shouldBe ((false, false, false))
      f.appStateStorage.getSnapSyncBootstrapTarget() shouldBe Some(fresh)
      spawnCounts(f) shouldBe ((0, 0, 0, 0, 0))
    }

  it should "keep the phase flags when every phase is complete, then heal on the freshly bootstrapped pivot" taggedAs UnitTest in {
    val f = new SnapControllerFixture(testKit)
    f.storeGenesis()
    f.storeHeaderAt(Pivot, Root)
    f.seedAllComplete(Pivot, Root)
    val height = (Pivot + MaxStaleness + 1_000).toInt
    f.peers.set(f.peersAt(height))
    val snap = f.spawnController(NoDeferral)
    snap ! SNAPSyncController.Start
    val fresh = BigInt(height) - Offset
    expectBootstrapRequest(f, fresh)
    f.awaitProcessed(snap)

    flags(f) shouldBe ((true, true, true))
    spawnCounts(f) shouldBe ((0, 0, 0, 0, 0))

    val freshRoot = root(0x55)
    snap ! SNAPSyncController.BootstrapComplete(Some(f.storeHeaderAt(fresh, freshRoot)))
    val healing = f.expectSpawn[ChildSpawn.Healing]
    f.awaitProcessed(snap)

    healing.stateRoot shouldBe freshRoot
    f.appStateStorage.getSnapSyncPivotBlock() shouldBe Some(fresh)
    f.factories.accountSpawns.get() shouldBe 0
    f.factories.byteCodeSpawns.get() shouldBe 0
    f.factories.storageSpawns.get() shouldBe 0
  }

  // ── Bootstrap retry ──────────────────────────────────────────────────────────────────────────

  "startSnapSync with an interrupted bootstrap" should
    "resume it before any pivot selection, even with no peer" taggedAs UnitTest in {
      val f = new SnapControllerFixture(testKit)
      f.storeGenesis()
      val target = BigInt(5_000)
      f.appStateStorage.putSnapSyncBootstrapTarget(target).commit()
      val snap = f.spawnController(SNAPSyncConfig())
      snap ! SNAPSyncController.Start
      expectBootstrapRequest(f, target)
      f.awaitProcessed(snap)

      f.appStateStorage.getSnapSyncBootstrapTarget() shouldBe Some(target)
      spawnCounts(f) shouldBe ((0, 0, 0, 0, 0))
    }

  it should "clear a bootstrap target whose header is already stored and go on to pivot selection" taggedAs UnitTest in {
    val f = new SnapControllerFixture(testKit)
    f.storeGenesis()
    val target = BigInt(5_000)
    f.storeHeaderAt(target, root(0x66))
    f.appStateStorage.putSnapSyncBootstrapTarget(target).commit()
    val snap = f.spawnController(SNAPSyncConfig())
    snap ! SNAPSyncController.Start
    f.awaitProcessed(snap)

    f.appStateStorage.getSnapSyncBootstrapTarget() shouldBe None
    // No peer: pivot selection schedules a retry and requests nothing.
    f.parent.expectNoMessage(300.millis)
    spawnCounts(f) shouldBe ((0, 0, 0, 0, 0))
  }

  "startSnapSync's bootstrap retry" should
    "re-enter pivot selection and request the network pivot once a SNAP peer shows its height" taggedAs UnitTest in {
      val f = new SnapControllerFixture(testKit)
      f.storeGenesis()
      val snap = f.spawnController(SNAPSyncConfig())
      snap ! SNAPSyncController.Start
      f.awaitProcessed(snap)
      f.parent.expectNoMessage(300.millis)

      f.pollWith(snap, f.peersAt(height = 2_000))
      val pivot = BigInt(2_000) - Offset
      expectBootstrapRequest(f, pivot)
      f.awaitProcessed(snap)

      f.appStateStorage.getSnapSyncBootstrapTarget() shouldBe Some(pivot)
      spawnCounts(f) shouldBe ((0, 0, 0, 0, 0))
    }
