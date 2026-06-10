package com.chipprbots.ethereum.blockchain.sync

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.Props
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import scala.concurrent.duration._

import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.WithActorSystemShutDown
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncConfig
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController
import com.chipprbots.ethereum.blockchain.sync.snap.actors
import com.chipprbots.ethereum.db.cache.LruCache
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.CachedReferenceCountedStorage
import com.chipprbots.ethereum.db.storage.FlatSlotStorage
import com.chipprbots.ethereum.db.storage.HeapEntry
import com.chipprbots.ethereum.db.storage.NodeStorage
import com.chipprbots.ethereum.db.storage.NodeStorage.NodeHash
import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.db.storage.pruning.ArchivePruning
import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.utils.Config

/** Coverage for the post-SNAP storage recovery download path:
  *
  *   - Bug 30b abandon: repeated `PivotStateUnservable` with no progress eventually triggers `RecoveryComplete`, and a
  *     `ProgressStorageSlotsSynced` between events resets the abandon timer.
  *   - Recent-root roll (Task #5): when the saved pivot root has aged out of every peer's serve window, the actor asks
  *     SyncController for a recent root and, on receiving one, sends `StoragePivotRefreshed` to the coordinator instead
  *     of abandoning — so the resync can't get permanently wedged on a stale pivot. Bounded + falls back to abandon.
  */
class StorageRecoveryActorSpec
    extends TestKit(ActorSystem("StorageRecoveryActorSpec_System"))
    with AnyFlatSpecLike
    with WithActorSystemShutDown
    with Matchers
    with Eventually {

  private val fakeStateRoot: ByteString = ByteString(Array.fill[Byte](32)(0x11))
  private val fakeAccountHash: ByteString = ByteString(Array.fill[Byte](32)(0x22))
  private val fakeStorageRoot: ByteString = ByteString(Array.fill[Byte](32)(0x33))
  private val missingOne: Seq[(ByteString, ByteString)] = Seq((fakeAccountHash, fakeStorageRoot))

  private def newConfig(abandonAfter: FiniteDuration, maxRolls: Int): SNAPSyncConfig =
    SNAPSyncConfig(storageRecoveryAbandonTimeout = abandonAfter, storageRecoveryMaxRootRolls = maxRolls)

  private def pivotUnservable(): SNAPSyncController.PivotStateUnservable =
    SNAPSyncController.PivotStateUnservable(fakeStateRoot, "test", 0)

  private def newStorages(): (StateStorage, AppStateStorage, FlatSlotStorage) = {
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
  }

  /** Spin up a recovery actor already in its `downloading` state (via the preloaded-missing hook), wired to the given
    * probes. Returns the actor plus the storages so the test can assert the done-flag.
    */
  private def downloadingActor(
      syncController: TestProbe,
      coordinator: TestProbe,
      abandonAfter: FiniteDuration,
      maxRolls: Int = 8
  ): (ActorRef, AppStateStorage) = {
    val networkPeerManager = TestProbe("npm")
    val (stateStorage, appStateStorage, flatSlots) = newStorages()
    val actor = system.actorOf(
      Props(
        new StorageRecoveryActor(
          stateRoot = fakeStateRoot,
          stateStorage = stateStorage,
          appStateStorage = appStateStorage,
          flatSlotStorage = flatSlots,
          networkPeerManager = networkPeerManager.ref,
          syncController = syncController.ref,
          pivotBlockNumber = BigInt(100),
          snapSyncConfig = newConfig(abandonAfter, maxRolls),
          preloadedMissingForTesting = Some(missingOne),
          coordinatorForTesting = Some(coordinator.ref)
        )
      )
    )
    // The actor enters `downloading` and immediately hands the missing list to the coordinator.
    coordinator.expectMsgType[actors.Messages.AddStorageTasks](2.seconds)
    (actor, appStateStorage)
  }

  "StorageRecoveryActor" should
    "abandon and commit recovery-done after PivotStateUnservable when no recent root is available" taggedAs (
      UnitTest,
      SyncTest
    ) in {
      val syncController = TestProbe("syncController_abandon")
      val coordinator = TestProbe("coordinator_abandon")
      val (actor, appStateStorage) = downloadingActor(syncController, coordinator, abandonAfter = 600.millis)

      (1 to 5).foreach(_ => actor ! pivotUnservable())
      // The actor tries to roll off the aged pivot first; decline (no recent root) → abandon path.
      syncController.expectMsg(2.seconds, StorageRecoveryActor.RequestRecentRoot)
      actor ! StorageRecoveryActor.RecentRoot(BigInt(0), None)

      syncController.expectMsg(3.seconds, StorageRecoveryActor.RecoveryComplete)
      appStateStorage.isStorageRecoveryDone() shouldBe true
      system.stop(actor)
    }

  it should "roll the download root onto the coordinator when SyncController supplies a recent root" taggedAs (
    UnitTest,
    SyncTest
  ) in {
    val syncController = TestProbe("syncController_roll")
    val coordinator = TestProbe("coordinator_roll")
    // Long abandon window so the test asserts the roll (not a race with abandon).
    val (actor, appStateStorage) = downloadingActor(syncController, coordinator, abandonAfter = 5.seconds)

    actor ! pivotUnservable()
    syncController.expectMsg(2.seconds, StorageRecoveryActor.RequestRecentRoot)

    val recentRoot = ByteString(Array.fill[Byte](32)(0x44))
    actor ! StorageRecoveryActor.RecentRoot(BigInt(200), Some(recentRoot))

    // The coordinator is re-armed against the recent root instead of the actor abandoning.
    coordinator.expectMsg(2.seconds, actors.Messages.StoragePivotRefreshed(recentRoot))
    // The roll cancelled the abandon timer → no RecoveryComplete follows.
    syncController.expectNoMessage(1.second)
    appStateStorage.isStorageRecoveryDone() shouldBe false
    system.stop(actor)
  }

  it should "not roll when the recent root equals the current download root, and still abandon" taggedAs (
    UnitTest,
    SyncTest
  ) in {
    val syncController = TestProbe("syncController_sameroot")
    val coordinator = TestProbe("coordinator_sameroot")
    val (actor, appStateStorage) = downloadingActor(syncController, coordinator, abandonAfter = 600.millis)

    actor ! pivotUnservable()
    syncController.expectMsg(2.seconds, StorageRecoveryActor.RequestRecentRoot)
    actor ! StorageRecoveryActor.RecentRoot(BigInt(0), Some(fakeStateRoot)) // == current root → no-op roll

    // No StoragePivotRefreshed sent (the initial AddStorageTasks was already consumed).
    coordinator.expectNoMessage(300.millis)
    // Abandon timer (armed on the unservable) still fires.
    syncController.expectMsg(2.seconds, StorageRecoveryActor.RecoveryComplete)
    appStateStorage.isStorageRecoveryDone() shouldBe true
    system.stop(actor)
  }

  it should "stop requesting rolls after maxRootRolls and fall back to abandon" taggedAs (
    UnitTest,
    SyncTest
  ) in {
    val syncController = TestProbe("syncController_bound")
    val coordinator = TestProbe("coordinator_bound")
    val (actor, _) = downloadingActor(syncController, coordinator, abandonAfter = 700.millis, maxRolls = 1)

    actor ! pivotUnservable()
    syncController.expectMsg(2.seconds, StorageRecoveryActor.RequestRecentRoot) // roll 1 requested
    val root1 = ByteString(Array.fill[Byte](32)(0x55))
    actor ! StorageRecoveryActor.RecentRoot(BigInt(10), Some(root1))
    coordinator.expectMsg(2.seconds, actors.Messages.StoragePivotRefreshed(root1)) // roll 1 applied

    actor ! pivotUnservable() // still unservable, but the single roll is spent → no new request
    syncController.expectMsg(3.seconds, StorageRecoveryActor.RecoveryComplete) // abandons the residue
    system.stop(actor)
  }

  it should "NOT abandon if slot progress arrives between unservable events" taggedAs (
    UnitTest,
    SyncTest
  ) in {
    val syncController = TestProbe("syncController_noAbandon")
    val coordinator = TestProbe("coordinator_noAbandon")
    val abandonAfter = 500.millis
    val (actor, appStateStorage) = downloadingActor(syncController, coordinator, abandonAfter)

    actor ! pivotUnservable()
    syncController.expectMsg(2.seconds, StorageRecoveryActor.RequestRecentRoot) // consume the roll request
    // Progress resets the counter + cancels the pending abandon.
    actor ! SNAPSyncController.ProgressStorageSlotsSynced(10)
    actor ! pivotUnservable()
    syncController.expectMsg(2.seconds, StorageRecoveryActor.RequestRecentRoot) // progress reset the budget → re-asks

    // No RecoveryComplete within the fresh window: the second unservable's abandon timer was armed
    // after progress, so abandon is still pending — we only assert it did not PREMATURELY fire.
    syncController.expectNoMessage((abandonAfter.toMillis / 2).millis)
    appStateStorage.isStorageRecoveryDone() shouldBe false
    system.stop(actor)
  }
}
