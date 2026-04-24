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
import com.chipprbots.ethereum.db.storage.FlatSlotStorage
import com.chipprbots.ethereum.db.storage.HeapEntry
import com.chipprbots.ethereum.db.storage.NodeStorage
import com.chipprbots.ethereum.db.storage.NodeStorage.NodeHash
import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.db.storage.pruning.ArchivePruning
import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.utils.Config

/** Focused coverage for the Bug 30b abandon path. Drives the actor through its `downloading` state via the
  * `preloadedMissingForTesting` + `coordinatorForTesting` hooks and asserts:
  *
  *   - Repeated `PivotStateUnservable` with no progress eventually triggers `RecoveryComplete`.
  *   - A `ProgressStorageSlotsSynced` between unservable events resets the counter so the abandon timer does NOT fire
  *     within the first window.
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

  private def newConfig(abandonAfter: FiniteDuration): SNAPSyncConfig =
    SNAPSyncConfig(storageRecoveryAbandonTimeout = abandonAfter)

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

  "StorageRecoveryActor" should
    "abandon and commit recovery-done after repeated PivotStateUnservable with no progress" taggedAs (
      UnitTest,
      SyncTest
    ) in {
      val syncController = TestProbe("syncController_abandon")
      val networkPeerManager = TestProbe("networkPeerManager_abandon")
      val coordinatorProbe = TestProbe("coordinator_abandon")
      val (stateStorage, appStateStorage, flatSlots) = newStorages()

      // 600ms sits above the Pekko scheduler tick (~10ms) and below default ScalaTest patience.
      val abandonAfter = 600.millis

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
            snapSyncConfig = newConfig(abandonAfter),
            preloadedMissingForTesting = Some(missingOne),
            coordinatorForTesting = Some(coordinatorProbe.ref)
          )
        )
      )

      // Confirm the actor actually entered `downloading` — the coordinator probe should
      // receive AddStorageTasks synchronously after ScanResult is processed.
      coordinatorProbe.expectMsgType[com.chipprbots.ethereum.blockchain.sync.snap.actors.Messages.AddStorageTasks](
        2.seconds
      )

      // Bump counter a handful of times — the first arms the timer, subsequent ones must
      // NOT reset it (otherwise abandon never fires).
      (1 to 5).foreach(_ => actor ! pivotUnservable())

      syncController.expectMsg(3.seconds, StorageRecoveryActor.RecoveryComplete)
      appStateStorage.isStorageRecoveryDone() shouldBe true

      system.stop(actor)
    }

  it should "NOT abandon if slot progress arrives between unservable events" taggedAs (
    UnitTest,
    SyncTest
  ) in {
    val syncController = TestProbe("syncController_noAbandon")
    val networkPeerManager = TestProbe("networkPeerManager_noAbandon")
    val coordinatorProbe = TestProbe("coordinator_noAbandon")
    val (stateStorage, appStateStorage, flatSlots) = newStorages()

    val abandonAfter = 500.millis

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
          snapSyncConfig = newConfig(abandonAfter),
          preloadedMissingForTesting = Some(missingOne),
          coordinatorForTesting = Some(coordinatorProbe.ref)
        )
      )
    )

    actor ! pivotUnservable()
    // Progress resets the counter + cancels the pending abandon.
    actor ! SNAPSyncController.ProgressStorageSlotsSynced(10)
    actor ! pivotUnservable()

    // The second PivotStateUnservable arms a fresh timer after progress reset it.
    // Waiting 2x abandonAfter would catch a premature abandon — and by then, one
    // `abandonAfter` after the fresh arm would have elapsed anyway. Use 1.5x so
    // we're strictly inside the new window.
    syncController.expectNoMessage((abandonAfter.toMillis * 3 / 2).millis)
    appStateStorage.isStorageRecoveryDone() shouldBe false

    system.stop(actor)
  }
}
