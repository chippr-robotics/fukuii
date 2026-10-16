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
import com.chipprbots.ethereum.blockchain.sync.snap.actors
import com.chipprbots.ethereum.blockchain.sync.snap.controller.StateIngest
import com.chipprbots.ethereum.db.cache.LruCache
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.BfsQueueStorage
import com.chipprbots.ethereum.db.storage.CachedReferenceCountedStorage
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.FlatSlotStorage
import com.chipprbots.ethereum.db.storage.HealingFrontierStorage
import com.chipprbots.ethereum.db.storage.HeapEntry
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.NodeStorage
import com.chipprbots.ethereum.db.storage.NodeStorage.NodeHash
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.db.storage.SnapStorageDoneStorage
import com.chipprbots.ethereum.db.storage.SnapSyncProgressStorage
import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.db.storage.pruning.ArchivePruning
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.BlockchainWriter
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.Config

/** Coverage for the post-SNAP storage recovery download path:
  *
  *   - Bug 30b abandon: repeated `PivotStateUnservable` with no progress eventually triggers `RecoveryComplete`, and a
  *     `ProgressStorageSlotsSynced` between events resets the abandon timer.
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
      flatSlotStorage: FlatSlotStorage
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
      def snapProgress: SnapSyncProgressStorage = notReached("snapProgress")
      def storageDone: SnapStorageDoneStorage = notReached("storageDone")
      def healingFrontier: Option[HealingFrontierStorage] = notReached("healingFrontier")
      def bfsQueue: Option[BfsQueueStorage] = notReached("bfsQueue")

  private def notReached(member: String): Nothing =
    throw new UnsupportedOperationException(s"recovery actor read StateIngest.$member")

  /** Spin up a recovery actor already in its `downloading` state (via the preloaded-missing hook), wired to the given
    * probes. Returns the actor plus the storages so the test can assert the done-flag.
    */
  private def downloadingActor(
      testLabel: String,
      syncController: TestProbe,
      coordinator: TestProbe,
      abandonAfter: FiniteDuration,
      maxRolls: Int = 8
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
          preloaded = Some(missingOne),
          coordinatorForTesting = Some(coordinator.ref)
        ),
        s"storage-recovery-spec-$testLabel"
      )
    // The actor enters `downloading` and immediately hands the missing list to the coordinator.
    coordinator.expectMsgType[actors.StorageRangeCoordinator.AddStorageTasks](2.seconds)
    (actor, appStateStorage)

  "StorageRecoveryActor" should
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
