package com.chipprbots.ethereum.blockchain.sync

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import scala.concurrent.duration.*

import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncConfig
import com.chipprbots.ethereum.blockchain.sync.snap.StateValidator
import com.chipprbots.ethereum.blockchain.sync.snap.StorageScheme
import com.chipprbots.ethereum.blockchain.sync.snap.actors
import com.chipprbots.ethereum.blockchain.sync.snap.controller.StateIngest
import com.chipprbots.ethereum.db.cache.LruCache
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.BadEntryDelete
import com.chipprbots.ethereum.db.storage.BfsQueueStorage
import com.chipprbots.ethereum.db.storage.CachedReferenceCountedStorage
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.FlatSlotStorage
import com.chipprbots.ethereum.db.storage.HeapEntry
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.NodeStorage
import com.chipprbots.ethereum.db.storage.NodeStorage.NodeHash
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.db.storage.RawNodeRead
import com.chipprbots.ethereum.db.storage.RepairOutcome
import com.chipprbots.ethereum.db.storage.SnapStorageDoneStorage
import com.chipprbots.ethereum.db.storage.SnapSyncProgressStorage
import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.db.storage.pathstate.BlockRef
import com.chipprbots.ethereum.db.storage.pathstate.PositionKey
import com.chipprbots.ethereum.db.storage.pathstate.PositionRepairs
import com.chipprbots.ethereum.db.storage.pruning.ArchivePruning
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.BlockchainWriter
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.Config

/** Coverage for the post-SNAP storage recovery download path:
  *
  *   - Bug 30b abandon: repeated `PivotStateUnservable` with no progress eventually triggers `RecoveryComplete`, and a
  *     `ProgressStorageSlotsSynced` between events resets the abandon timer.
  *   - Progress counts (B-502): the log and the downloaded gauge count storage roots from the coordinator's
  *     `ProgressStorageContracts`; each `ProgressStorageSlotsSynced` adds its slots to a separate tally.
  *   - Recent-root roll (Task #5): when the saved pivot root has aged out of every peer's serve window, the actor asks
  *     SyncController for a recent root and, on receiving one, sends `StoragePivotRefreshed` to the coordinator instead
  *     of abandoning — so the resync can't get permanently wedged on a stale pivot. Bounded + falls back to abandon.
  */
class StorageRecoveryActorSpec extends ScalaTestWithActorTestKit() with AnyFlatSpecLike with Matchers with Eventually:

  implicit private val classicSystem: org.apache.pekko.actor.ActorSystem = system.classicSystem

  private val fakeStateRoot: ByteString = ByteString(Array.fill[Byte](32)(0x11))
  private val fakeAccountHash: ByteString = ByteString(Array.fill[Byte](32)(0x22))
  private val fakeStorageRoot: ByteString = ByteString(Array.fill[Byte](32)(0x33))
  private val missingOne: Seq[(ByteString, ByteString)] = Seq((fakeAccountHash, fakeStorageRoot))

  private def newConfig(abandonAfter: FiniteDuration, maxRolls: Int): SNAPSyncConfig =
    SNAPSyncConfig(storageRecoveryAbandonTimeout = abandonAfter, storageRecoveryMaxRootRolls = maxRolls)

  private def pivotUnservable(): StorageRecoveryActor.PivotUnservable =
    StorageRecoveryActor.PivotUnservable(fakeStateRoot, "test", 0)

  private def newStorages(): (StateStorage, AppStateStorage, FlatSlotStorage) =
    val ds = EphemDataSource()
    val nodeStorage = new NodeStorage(ds)
    val appStateStorage = new AppStateStorage(ds)
    val flatSlots = new FlatSlotStorage(ds)
    val stateStorage = StateStorage(
      ArchivePruning,
      nodeStorage,
      new LruCache[NodeHash, HeapEntry](
        Config.inMemoryPruningNodeCacheConfig,
        Some(CachedReferenceCountedStorage.saveOnlyNotificationHandler(nodeStorage))
      )
    )
    (stateStorage, appStateStorage, flatSlots)

  // The recovery actors take one `StateIngest` (spec 016 T060c, D-T060-2). They read only the stores below; any other
  // member fails loudly, so a test that reaches one is a change in what recovery reads.
  private def recoveryIngest(
      stateStorage: StateStorage,
      appStateStorage: AppStateStorage,
      flatSlotStorage: FlatSlotStorage,
      progress: Option[SnapSyncProgressStorage] = None
  ): StateIngest =
    new StateIngest:
      def appState: AppStateStorage = appStateStorage
      def stateTries: StateStorage = stateStorage
      def flatSlots: FlatSlotStorage = flatSlotStorage
      def pathNodes: Option[PathNodeStorage] = notReached("pathNodes")
      def bytecode: EvmCodeStorage = notReached("bytecode")
      def chainReader: BlockchainReader = notReached("chainReader")
      def chainWriter: BlockchainWriter = notReached("chainWriter")
      def validatorFactory: MptStorage => StateValidator = notReached("validatorFactory")
      def durability: StateIngest.Durability = StateIngest.Durability.Default
      def trieStore(pivot: BigInt): MptStorage = notReached("trieStore")
      def snapProgress: SnapSyncProgressStorage = progress.getOrElse(notReached("snapProgress"))
      def storageDone: SnapStorageDoneStorage = notReached("storageDone")
      def bfsQueue: Option[BfsQueueStorage] = notReached("bfsQueue")

  private def notReached(member: String): Nothing =
    throw new UnsupportedOperationException(s"recovery actor read StateIngest.$member")

  // The unified layout, as the recovery actors are told of it (`BlockchainWriter.positionRepairs`). A refused recovery
  // reads nothing of it: every member fails loudly.
  private object OnThePositionLayout extends PositionRepairs:
    def anchor: Option[BlockRef] = positionsRead("anchor")
    def rawRowsAt(requests: Seq[(PositionKey, ByteString)]): Seq[RawNodeRead] = positionsRead("rawRowsAt")
    def repairNodeAt(position: PositionKey, expectedHash: ByteString, bytes: Array[Byte]): RepairOutcome =
      positionsRead("repairNodeAt")
    def deleteBadNodeAt(position: PositionKey, expectedHash: ByteString): BadEntryDelete =
      positionsRead("deleteBadNodeAt")

  private def positionsRead(member: String): Nothing =
    throw new UnsupportedOperationException(s"a refused recovery read PositionRepairs.$member")

  /** Spin up a recovery actor already in its `downloading` state (handed `missingOne`), wired to the given probes.
    * Returns the actor plus the storages so the test can assert the done-flag.
    */
  private def downloadingActor(
      testLabel: String,
      syncController: TestProbe,
      coordinator: TestProbe,
      abandonAfter: FiniteDuration,
      maxRolls: Int = 8,
      missing: Seq[(ByteString, ByteString)] = missingOne
  ): (org.apache.pekko.actor.typed.ActorRef[StorageRecoveryActor.Command], AppStateStorage) =
    val networkPeerManager = TestProbe(s"npm_$testLabel")
    val (stateStorage, appStateStorage, flatSlots) = newStorages()
    val actor = testKit
      .spawn(
        StorageRecoveryActor.testApply(
          stateRoot = fakeStateRoot,
          stateIngest = recoveryIngest(stateStorage, appStateStorage, flatSlots),
          networkPeerManager = networkPeerManager.ref,
          syncController = syncController.ref.toTyped[Any],
          pivotBlockNumber = BigInt(100),
          snapSyncConfig = newConfig(abandonAfter, maxRolls),
          missing = missing,
          coordinatorForTesting = Some(coordinator.ref)
        ),
        s"storage-recovery-spec-$testLabel"
      )
    // The actor enters `downloading` and immediately hands the missing list to the coordinator.
    coordinator.expectMsgType[actors.StorageRangeCoordinator.AddStorageTasks](2.seconds)
    (actor, appStateStorage)

  // The combined scan found no storage missing: the actor settles the flag at once and starts no download. (It had a
  // scan of its own until B-217, which retired it: that scan stopped at the first absent node and the flag was then
  // settled on the partial walk. What the scan finds is `CombinedRecoveryScan*Spec`'s and `RecoveryScansVerdictSpec`'s.)
  "StorageRecoveryActor" should "mark recovery done at once, with no download, when no storage is missing" taggedAs (
    UnitTest,
    SyncTest
  ) in {
    val syncController = TestProbe("syncController_none_missing")
    val coordinator = TestProbe("coordinator_none_missing")
    val networkPeerManager = TestProbe("npm_none_missing")
    val (stateStorage, appStateStorage, flatSlots) = newStorages()
    val actor = testKit.spawn(
      StorageRecoveryActor.testApply(
        stateRoot = fakeStateRoot,
        stateIngest = recoveryIngest(stateStorage, appStateStorage, flatSlots),
        networkPeerManager = networkPeerManager.ref,
        syncController = syncController.ref.toTyped[Any],
        pivotBlockNumber = BigInt(100),
        snapSyncConfig = newConfig(1.minute, 8),
        missing = Seq.empty,
        coordinatorForTesting = Some(coordinator.ref)
      ),
      "storage-recovery-spec-none-missing"
    )
    syncController.expectMsg(3.seconds, StorageRecoveryActor.RecoveryComplete)
    appStateStorage.isStorageRecoveryDone() shouldBe true
    coordinator.expectNoMessage(200.millis)
    testKit.stop(actor)
  }

  it should
    "abandon and commit recovery-done after PivotStateUnservable when no recent root is available" taggedAs (
      UnitTest,
      SyncTest
    ) in {
      val syncController = TestProbe("syncController_abandon")
      val coordinator = TestProbe("coordinator_abandon")
      val (actor, appStateStorage) =
        downloadingActor("abandon", syncController, coordinator, abandonAfter = 600.millis)

      (1 to 5).foreach(_ => actor ! pivotUnservable())
      // The actor tries to roll off the aged pivot first; decline (no recent root) → abandon path.
      syncController.expectMsgType[StorageRecoveryActor.RequestRecentRoot](2.seconds)
      actor ! StorageRecoveryActor.RecentRoot(BigInt(0), None)

      syncController.expectMsg(3.seconds, StorageRecoveryActor.RecoveryComplete)
      appStateStorage.isStorageRecoveryDone() shouldBe true
      testKit.stop(actor)
    }

  it should "roll the download root onto the coordinator when SyncController supplies a recent root" taggedAs (
    UnitTest,
    SyncTest
  ) in {
    val syncController = TestProbe("syncController_roll")
    val coordinator = TestProbe("coordinator_roll")
    // Long abandon window so the test asserts the roll (not a race with abandon).
    val (actor, appStateStorage) = downloadingActor("roll", syncController, coordinator, abandonAfter = 5.seconds)

    actor ! pivotUnservable()
    syncController.expectMsgType[StorageRecoveryActor.RequestRecentRoot](2.seconds)

    val recentRoot = ByteString(Array.fill[Byte](32)(0x44))
    actor ! StorageRecoveryActor.RecentRoot(BigInt(200), Some(recentRoot))

    // The coordinator is re-armed against the recent root instead of the actor abandoning.
    coordinator.expectMsg(2.seconds, actors.StorageRangeCoordinator.StoragePivotRefreshed(recentRoot))
    // The roll cancelled the abandon timer → no RecoveryComplete follows.
    syncController.expectNoMessage(1.second)
    appStateStorage.isStorageRecoveryDone() shouldBe false
    testKit.stop(actor)
  }

  it should "not roll when the recent root equals the current download root, and still abandon" taggedAs (
    UnitTest,
    SyncTest
  ) in {
    val syncController = TestProbe("syncController_sameroot")
    val coordinator = TestProbe("coordinator_sameroot")
    val (actor, appStateStorage) =
      downloadingActor("sameroot", syncController, coordinator, abandonAfter = 600.millis)

    actor ! pivotUnservable()
    syncController.expectMsgType[StorageRecoveryActor.RequestRecentRoot](2.seconds)
    actor ! StorageRecoveryActor.RecentRoot(BigInt(0), Some(fakeStateRoot)) // == current root → no-op roll

    // No StoragePivotRefreshed sent (the initial AddStorageTasks was already consumed).
    coordinator.expectNoMessage(300.millis)
    // Abandon timer (armed on the unservable) still fires.
    syncController.expectMsg(2.seconds, StorageRecoveryActor.RecoveryComplete)
    appStateStorage.isStorageRecoveryDone() shouldBe true
    testKit.stop(actor)
  }

  it should "stop requesting rolls after maxRootRolls and fall back to abandon" taggedAs (
    UnitTest,
    SyncTest
  ) in {
    val syncController = TestProbe("syncController_bound")
    val coordinator = TestProbe("coordinator_bound")
    val (actor, _) = downloadingActor("bound", syncController, coordinator, abandonAfter = 700.millis, maxRolls = 1)

    actor ! pivotUnservable()
    syncController.expectMsgType[StorageRecoveryActor.RequestRecentRoot](2.seconds) // roll 1 requested
    val root1 = ByteString(Array.fill[Byte](32)(0x55))
    actor ! StorageRecoveryActor.RecentRoot(BigInt(10), Some(root1))
    coordinator.expectMsg(2.seconds, actors.StorageRangeCoordinator.StoragePivotRefreshed(root1)) // roll 1 applied

    actor ! pivotUnservable() // still unservable, but the single roll is spent → no new request
    syncController.expectMsg(3.seconds, StorageRecoveryActor.RecoveryComplete) // abandons the residue
    testKit.stop(actor)
  }

  it should "NOT abandon if slot progress arrives between unservable events" taggedAs (
    UnitTest,
    SyncTest
  ) in {
    val syncController = TestProbe("syncController_noAbandon")
    val coordinator = TestProbe("coordinator_noAbandon")
    val abandonAfter = 500.millis
    val (actor, appStateStorage) =
      downloadingActor("noAbandon", syncController, coordinator, abandonAfter)

    actor ! pivotUnservable()
    syncController.expectMsgType[StorageRecoveryActor.RequestRecentRoot](2.seconds) // consume the roll request
    // Progress resets the counter + cancels the pending abandon.
    actor ! StorageRecoveryActor.StorageSlotProgress(10)
    actor ! pivotUnservable()
    syncController.expectMsgType[StorageRecoveryActor.RequestRecentRoot](
      2.seconds
    ) // progress reset the budget → re-asks

    // No RecoveryComplete within the fresh window: the second unservable's abandon timer was armed
    // after progress, so abandon is still pending — we only assert it did not PREMATURELY fire.
    syncController.expectNoMessage((abandonAfter.toMillis / 2).millis)
    appStateStorage.isStorageRecoveryDone() shouldBe false
    testKit.stop(actor)
  }

  it should "take slot progress from the slot message and root progress from the coordinator's root count (B-502)" taggedAs (
    UnitTest,
    SyncTest
  ) in {
    // ProgressStorageSlotsSynced carries the slots one chunk applied; ProgressStorageContracts the roots completed.
    StorageRecoveryActor.fromCoordinator(
      snap.SNAPSyncController.ProgressStorageSlotsSynced(7L)
    ) shouldBe StorageRecoveryActor.StorageSlotProgress(7L)
    StorageRecoveryActor.fromCoordinator(
      snap.SNAPSyncController.ProgressStorageContracts(3, 10)
    ) shouldBe StorageRecoveryActor.StorageRootsProgress(3L)
  }

  it should "count recovered storage roots by the coordinator's root count, and slots by each chunk's count (B-502)" taggedAs (
    UnitTest,
    SyncTest
  ) in {
    import org.apache.pekko.actor.testkit.typed.scaladsl.LoggingTestKit
    val syncController = TestProbe("syncController_counts")
    val coordinator = TestProbe("coordinator_counts")
    val missingTen: Seq[(ByteString, ByteString)] =
      (1 to 10).map(i => (ByteString(Array.fill[Byte](32)(i.toByte)), fakeStorageRoot))
    val (actor, _) =
      downloadingActor("counts", syncController, coordinator, abandonAfter = 1.minute, missing = missingTen)

    def downloadedGauge: Double =
      com.chipprbots.ethereum.metrics.Metrics.get().registry.find("recovery.storage.downloaded.gauge").gauge().value()
    def rootsLine(percent: Int, roots: Int, slots: Long): LoggingTestKit =
      LoggingTestKit.empty
        .withLogLevel(org.slf4j.event.Level.INFO)
        .withMessageRegex(
          s"\\[SNAP-PROGRESS\\] STORAGE-RECOVERY $percent% — $roots / 10 storage roots \\| \\d+ roots/s \\| $slots slots"
        )

    // logback-test.xml keeps the root at ERROR, which drops the actor's INFO progress line before LoggingTestKit can
    // see it; admit INFO for the actor's logger (its object's class) for this test only.
    val actorLogger = org.slf4j.LoggerFactory
      .getLogger(StorageRecoveryActor.getClass)
      .asInstanceOf[ch.qos.logback.classic.Logger]
    val previousLevel = actorLogger.getLevel
    actorLogger.setLevel(ch.qos.logback.classic.Level.INFO)
    try
      // Two chunks of one root are slots, not roots: no root progress is logged for them.
      LoggingTestKit.info("STORAGE-RECOVERY").withOccurrences(0).expect {
        actor ! StorageRecoveryActor.StorageSlotProgress(500L)
        actor ! StorageRecoveryActor.StorageSlotProgress(250L)
        syncController.expectNoMessage(200.millis)
      }
      // Three roots complete: 3 of 10, with the 750 slots so far.
      rootsLine(30, 3, 750L).expect {
        actor ! StorageRecoveryActor.StorageRootsProgress(3L)
      }
      eventually(downloadedGauge shouldBe 3d)
      // The counts accumulate: 50 more slots, then a fourth root.
      rootsLine(40, 4, 800L).expect {
        actor ! StorageRecoveryActor.StorageSlotProgress(50L)
        actor ! StorageRecoveryActor.StorageRootsProgress(4L)
      }
      eventually(downloadedGauge shouldBe 4d)
      syncController.expectNoMessage(100.millis)
    finally actorLogger.setLevel(previousLevel)

    testKit.stop(actor)
  }

  it should "spawn its storage coordinator with the node's storage scheme and the SNAP progress store (#1595)" taggedAs (
    UnitTest,
    SyncTest
  ) in {
    val (stateStorage, appStateStorage, flatSlots) = newStorages()
    val progress = new SnapSyncProgressStorage(EphemDataSource())
    val ingest = recoveryIngest(stateStorage, appStateStorage, flatSlots, Some(progress))

    val path = StorageRecoveryActor.coordinatorWiring(SNAPSyncConfig(storageScheme = StorageScheme.Path), ingest)
    path.storageScheme shouldBe StorageScheme.Path
    path.snapProgressStorage.exists(_ eq progress) shouldBe true

    val hash = StorageRecoveryActor.coordinatorWiring(SNAPSyncConfig(storageScheme = StorageScheme.Hash), ingest)
    hash.storageScheme shouldBe StorageScheme.Hash
  }

  it should "spawn its real Hash-scheme coordinator with the progress store wired and keep running (#1595)" taggedAs (
    UnitTest,
    SyncTest
  ) in {
    val syncController = TestProbe("syncController_hashSpawn")
    val networkPeerManager = TestProbe("npm_hashSpawn")
    val (stateStorage, appStateStorage, flatSlots) = newStorages()
    val progress = new SnapSyncProgressStorage(EphemDataSource())
    val actor = testKit.spawn(
      StorageRecoveryActor.testApply(
        stateRoot = fakeStateRoot,
        stateIngest = recoveryIngest(stateStorage, appStateStorage, flatSlots, Some(progress)),
        networkPeerManager = networkPeerManager.ref,
        syncController = syncController.ref.toTyped[Any],
        pivotBlockNumber = BigInt(100),
        snapSyncConfig = SNAPSyncConfig(storageScheme = StorageScheme.Hash),
        missing = missingOne,
        coordinatorForTesting = None
      ),
      "storage-recovery-spec-hash-spawn"
    )
    // The coordinator is built from the config; had the progress store or scheme broken its construction, the supervised
    // child would be restarted and then stop, and the recovery actor would report its termination.
    syncController.expectNoMessage(2.seconds)
    appStateStorage.isStorageRecoveryDone() shouldBe false
    testKit.stop(actor)
  }

  // B-376 (the user's decision of 2026-11-19): on the unified layout the actor refuses to start, loudly, by its one
  // entry, with the gaps the combined scan handed it
  "StorageRecoveryActor on the unified layout" should "refuse to start, log why at ERROR, write nothing and set no flag" taggedAs (
    UnitTest,
    SyncTest
  ) in {
    val syncController = TestProbe("syncController_refused")
    val coordinator = TestProbe("coordinator_refused")
    val networkPeerManager = TestProbe("npm_refused")
    val (stateStorage, appStateStorage, flatSlots) = newStorages()
    val actor = org.apache.pekko.actor.testkit.typed.scaladsl.LoggingTestKit
      .error(StorageRecoveryActor.RefusedOnPositions)
      .expect {
        testKit.spawn(
          StorageRecoveryActor.testApply(
            stateRoot = fakeStateRoot,
            stateIngest = recoveryIngest(stateStorage, appStateStorage, flatSlots),
            networkPeerManager = networkPeerManager.ref,
            syncController = syncController.ref.toTyped[Any],
            pivotBlockNumber = BigInt(100),
            snapSyncConfig = newConfig(1.minute, 8),
            missing = missingOne,
            coordinatorForTesting = Some(coordinator.ref),
            positions = Some(OnThePositionLayout)
          ),
          "storage-recovery-spec-refused"
        )
      }
    syncController.expectMsg(3.seconds, StorageRecoveryActor.RecoveryNotRun)
    createTestProbe[Any]().expectTerminated(actor, 3.seconds)
    coordinator.expectNoMessage(200.millis)
    appStateStorage.isStorageRecoveryDone() shouldBe false
  }

  it should "start as before on the hash layout" taggedAs (UnitTest, SyncTest) in {
    val syncController = TestProbe("syncController_hash_layout")
    val coordinator = TestProbe("coordinator_hash_layout")
    val networkPeerManager = TestProbe("npm_hash_layout")
    val (stateStorage, appStateStorage, flatSlots) = newStorages()
    val actor = testKit.spawn(
      StorageRecoveryActor.testApply(
        stateRoot = fakeStateRoot,
        stateIngest = recoveryIngest(stateStorage, appStateStorage, flatSlots),
        networkPeerManager = networkPeerManager.ref,
        syncController = syncController.ref.toTyped[Any],
        pivotBlockNumber = BigInt(100),
        snapSyncConfig = newConfig(1.minute, 8),
        missing = missingOne,
        coordinatorForTesting = Some(coordinator.ref)
      ),
      "storage-recovery-spec-hash-layout"
    )
    coordinator.expectMsgType[actors.StorageRangeCoordinator.AddStorageTasks](3.seconds)
    syncController.expectNoMessage(200.millis)
    testKit.stop(actor)
  }
