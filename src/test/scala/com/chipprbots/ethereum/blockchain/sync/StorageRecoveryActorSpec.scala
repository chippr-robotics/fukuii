package com.chipprbots.ethereum.blockchain.sync

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
import com.chipprbots.ethereum.db.cache.LruCache
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.CachedReferenceCountedStorage
import com.chipprbots.ethereum.db.storage.FlatAccountStorage
import com.chipprbots.ethereum.db.storage.FlatSlotStorage
import com.chipprbots.ethereum.db.storage.HeapEntry
import com.chipprbots.ethereum.db.storage.NodeStorage
import com.chipprbots.ethereum.db.storage.NodeStorage.NodeHash
import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.db.storage.pruning.ArchivePruning
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.utils.Config

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

  private def newConfig(abandonAfter: FiniteDuration = 10.minutes): SNAPSyncConfig =
    SNAPSyncConfig(storageRecoveryAbandonTimeout = abandonAfter)

  private def pivotUnservable(): SNAPSyncController.PivotStateUnservable =
    SNAPSyncController.PivotStateUnservable(fakeStateRoot, "test", 0)

  private def newStorages(): (StateStorage, AppStateStorage, FlatSlotStorage, FlatAccountStorage) = {
    val ds = EphemDataSource()
    val nodeStorage = new NodeStorage(ds)
    val appStateStorage = new AppStateStorage(ds)
    val flatSlots = new FlatSlotStorage(ds)
    val flatAccounts = new FlatAccountStorage(EphemDataSource())
    val stateStorage = StateStorage(
      ArchivePruning,
      nodeStorage,
      new LruCache[NodeHash, HeapEntry](
        Config.inMemoryPruningNodeCacheConfig,
        Some(CachedReferenceCountedStorage.saveOnlyNotificationHandler(nodeStorage))
      )
    )
    (stateStorage, appStateStorage, flatSlots, flatAccounts)
  }

  private def makeActor(
      appStateStorage: AppStateStorage,
      stateStorage: StateStorage,
      flatSlots: FlatSlotStorage,
      flatAccounts: FlatAccountStorage,
      syncController: TestProbe,
      networkPeerManager: TestProbe,
      abandonAfter: FiniteDuration = 10.minutes,
      preloaded: Option[Seq[(ByteString, ByteString)]] = None,
      coordinator: Option[TestProbe] = None
  ) = system.actorOf(
    Props(
      new StorageRecoveryActor(
        stateRoot = fakeStateRoot,
        stateStorage = stateStorage,
        appStateStorage = appStateStorage,
        flatSlotStorage = flatSlots,
        flatAccountStorage = flatAccounts,
        networkPeerManager = networkPeerManager.ref,
        syncController = syncController.ref,
        pivotBlockNumber = BigInt(100),
        snapSyncConfig = newConfig(abandonAfter),
        preloadedMissingForTesting = preloaded,
        coordinatorForTesting = coordinator.map(_.ref)
      )
    )
  )

  "StorageRecoveryActor" should
    "send AddStorageTasks then NoMoreStorageTasks when tasks are present (Bug 2 fix)" taggedAs (
      UnitTest,
      SyncTest
    ) in {
      val syncController = TestProbe("syncController_seal")
      val networkPeerManager = TestProbe("networkPeerManager_seal")
      val coordinatorProbe = TestProbe("coordinator_seal")
      val (stateStorage, appStateStorage, flatSlots, flatAccounts) = newStorages()

      makeActor(
        appStateStorage, stateStorage, flatSlots, flatAccounts,
        syncController, networkPeerManager,
        preloaded = Some(missingOne),
        coordinator = Some(coordinatorProbe)
      )

      coordinatorProbe.expectMsgType[snap.actors.Messages.AddStorageTasks](2.seconds)
      coordinatorProbe.expectMsg(2.seconds, snap.actors.Messages.NoMoreStorageTasks)
    }

  it should "emit RecoveryComplete immediately and commit flag when no storage is missing" taggedAs (
    UnitTest,
    SyncTest
  ) in {
    val syncController = TestProbe("syncController_empty")
    val networkPeerManager = TestProbe("networkPeerManager_empty")
    val (stateStorage, appStateStorage, flatSlots, flatAccounts) = newStorages()

    makeActor(
      appStateStorage, stateStorage, flatSlots, flatAccounts,
      syncController, networkPeerManager,
      preloaded = Some(Seq.empty)
    )

    syncController.expectMsg(3.seconds, StorageRecoveryActor.RecoveryComplete)
    appStateStorage.isStorageRecoveryDone() shouldBe true
  }

  it should "emit RecoveryComplete and commit flag when coordinator crashes during downloading" taggedAs (
    UnitTest,
    SyncTest
  ) in {
    val syncController = TestProbe("syncController_terminated")
    val networkPeerManager = TestProbe("networkPeerManager_terminated")
    val coordinatorProbe = TestProbe("coordinator_terminated")
    val (stateStorage, appStateStorage, flatSlots, flatAccounts) = newStorages()

    makeActor(
      appStateStorage, stateStorage, flatSlots, flatAccounts,
      syncController, networkPeerManager,
      preloaded = Some(missingOne),
      coordinator = Some(coordinatorProbe)
    )

    coordinatorProbe.expectMsgType[snap.actors.Messages.AddStorageTasks](2.seconds)
    coordinatorProbe.expectMsg(2.seconds, snap.actors.Messages.NoMoreStorageTasks)

    // Coordinator crashes after entering downloading state
    system.stop(coordinatorProbe.ref)

    syncController.expectMsg(5.seconds, StorageRecoveryActor.RecoveryComplete)
    appStateStorage.isStorageRecoveryDone() shouldBe true
  }

  it should "emit RecoveryComplete immediately when real flat scan finds no missing storage" taggedAs (
    UnitTest,
    SyncTest
  ) in {
    val syncController = TestProbe("syncController_realscan")
    val networkPeerManager = TestProbe("networkPeerManager_realscan")
    val coordinatorProbe = TestProbe("coordinator_realscan")
    val (stateStorage, appStateStorage, flatSlots, flatAccounts) = newStorages()

    // seekFrom is RocksDB-only and returns Stream.empty for EphemDataSource.
    // The actor enters `scanning`, gets ScanBatch(empty) + ScanComplete(0), and reports RecoveryComplete
    // without sending any tasks to the coordinator — verifies the scanning→done path.
    makeActor(
      appStateStorage, stateStorage, flatSlots, flatAccounts,
      syncController, networkPeerManager,
      coordinator = Some(coordinatorProbe)
    )

    syncController.expectMsg(5.seconds, StorageRecoveryActor.RecoveryComplete)
    appStateStorage.isStorageRecoveryDone() shouldBe true
    coordinatorProbe.expectNoMessage(200.millis)
  }

  it should
    "abandon and commit recovery-done after repeated PivotStateUnservable with no progress" taggedAs (
      UnitTest,
      SyncTest
    ) in {
      val syncController = TestProbe("syncController_abandon")
      val networkPeerManager = TestProbe("networkPeerManager_abandon")
      val coordinatorProbe = TestProbe("coordinator_abandon")
      val (stateStorage, appStateStorage, flatSlots, flatAccounts) = newStorages()

      val abandonAfter = 600.millis

      val actor = makeActor(
        appStateStorage, stateStorage, flatSlots, flatAccounts,
        syncController, networkPeerManager,
        abandonAfter = abandonAfter,
        preloaded = Some(missingOne),
        coordinator = Some(coordinatorProbe)
      )

      // Confirm the actor actually entered `downloading`
      coordinatorProbe.expectMsgType[snap.actors.Messages.AddStorageTasks](2.seconds)
      coordinatorProbe.expectMsg(2.seconds, snap.actors.Messages.NoMoreStorageTasks)

      // Arm the abandon timer and confirm it fires
      (1 to 5).foreach(_ => actor ! pivotUnservable())

      syncController.expectMsg(3.seconds, StorageRecoveryActor.RecoveryComplete)
      appStateStorage.isStorageRecoveryDone() shouldBe true
    }

  it should "NOT abandon if slot progress arrives between unservable events" taggedAs (
    UnitTest,
    SyncTest
  ) in {
    val syncController = TestProbe("syncController_noAbandon")
    val networkPeerManager = TestProbe("networkPeerManager_noAbandon")
    val coordinatorProbe = TestProbe("coordinator_noAbandon")
    val (stateStorage, appStateStorage, flatSlots, flatAccounts) = newStorages()

    val abandonAfter = 500.millis

    val actor = makeActor(
      appStateStorage, stateStorage, flatSlots, flatAccounts,
      syncController, networkPeerManager,
      abandonAfter = abandonAfter,
      preloaded = Some(missingOne),
      coordinator = Some(coordinatorProbe)
    )

    coordinatorProbe.expectMsgType[snap.actors.Messages.AddStorageTasks](2.seconds)
    coordinatorProbe.expectMsg(2.seconds, snap.actors.Messages.NoMoreStorageTasks)

    // First unservable arms the timer; progress cancels it; no second unservable means no new timer.
    actor ! pivotUnservable()
    actor ! SNAPSyncController.ProgressStorageSlotsSynced(10)

    syncController.expectNoMessage((abandonAfter.toMillis * 3 / 2).millis)
    appStateStorage.isStorageRecoveryDone() shouldBe false

    system.stop(actor)
  }

}
