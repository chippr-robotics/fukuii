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
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.FlatAccountStorage
import com.chipprbots.ethereum.db.storage.HeapEntry
import com.chipprbots.ethereum.db.storage.NodeStorage
import com.chipprbots.ethereum.db.storage.NodeStorage.NodeHash
import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.db.storage.pruning.ArchivePruning
import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.utils.Config

/** Unit tests for BytecodeRecoveryActor covering all recovery paths:
  *
  *   T1: No missing bytecodes → immediate RecoveryComplete, flag committed.
  *   T2: Missing present → AddByteCodeTasks + NoMoreByteCodeTasks + full download cycle.
  *   T3: Empty flat scan (no preloaded, empty FlatAccountStorage) → RecoveryComplete.
  *   T4: Coordinator crashes mid-download → Terminated handler commits flag and fires RecoveryComplete.
  *   T5: No peer/progress arrives within timeout → abandon fires, RecoveryComplete emitted.
  *   T6: AddByteCodeTasks then NoMoreByteCodeTasks seal sent in order (Bug 2 regression test).
  */
class BytecodeRecoveryActorSpec
    extends TestKit(ActorSystem("BytecodeRecoveryActorSpec_System"))
    with AnyFlatSpecLike
    with WithActorSystemShutDown
    with Matchers
    with Eventually {

  private val fakeStateRoot: ByteString = ByteString(Array.fill[Byte](32)(0x11))
  private val fakeCodeHash: ByteString = ByteString(Array.fill[Byte](32)(0xaa.toByte))
  private val missingOne: Seq[ByteString] = Seq(fakeCodeHash)

  private def newConfig(abandonAfter: FiniteDuration = 10.minutes): SNAPSyncConfig =
    SNAPSyncConfig(storageRecoveryAbandonTimeout = abandonAfter)

  private def newStorages(): (StateStorage, AppStateStorage, EvmCodeStorage, FlatAccountStorage) = {
    val ds = EphemDataSource()
    val nodeStorage = new NodeStorage(ds)
    val appStateStorage = new AppStateStorage(ds)
    val evmCodeStorage = new EvmCodeStorage(ds)
    val flatAccounts = new FlatAccountStorage(EphemDataSource())
    val stateStorage = StateStorage(
      ArchivePruning,
      nodeStorage,
      new LruCache[NodeHash, HeapEntry](
        Config.inMemoryPruningNodeCacheConfig,
        Some(CachedReferenceCountedStorage.saveOnlyNotificationHandler(nodeStorage))
      )
    )
    (stateStorage, appStateStorage, evmCodeStorage, flatAccounts)
  }

  "BytecodeRecoveryActor" should
    "emit RecoveryComplete immediately and commit flag when no bytecodes are missing" taggedAs (
      UnitTest,
      SyncTest
    ) in {
      val syncController = TestProbe("syncController_t1")
      val networkPeerManager = TestProbe("networkPeerManager_t1")
      val (stateStorage, appStateStorage, evmCodeStorage, flatAccounts) = newStorages()

      val actor = system.actorOf(
        Props(
          new BytecodeRecoveryActor(
            stateRoot = fakeStateRoot,
            stateStorage = stateStorage,
            evmCodeStorage = evmCodeStorage,
            flatAccountStorage = flatAccounts,
            appStateStorage = appStateStorage,
            networkPeerManager = networkPeerManager.ref,
            syncController = syncController.ref,
            pivotBlockNumber = BigInt(100),
            snapSyncConfig = newConfig(),
            preloadedMissingForTesting = Some(Seq.empty)
          )
        )
      )

      syncController.expectMsg(3.seconds, BytecodeRecoveryActor.RecoveryComplete)
      appStateStorage.isBytecodeRecoveryDone() shouldBe true

      system.stop(actor)
    }

  it should
    "send AddByteCodeTasks + NoMoreByteCodeTasks and emit RecoveryComplete on download" taggedAs (
      UnitTest,
      SyncTest
    ) in {
      val syncController = TestProbe("syncController_t2")
      val networkPeerManager = TestProbe("networkPeerManager_t2")
      val coordinatorProbe = TestProbe("coordinator_t2")
      val (stateStorage, appStateStorage, evmCodeStorage, flatAccounts) = newStorages()

      val actor = system.actorOf(
        Props(
          new BytecodeRecoveryActor(
            stateRoot = fakeStateRoot,
            stateStorage = stateStorage,
            evmCodeStorage = evmCodeStorage,
            flatAccountStorage = flatAccounts,
            appStateStorage = appStateStorage,
            networkPeerManager = networkPeerManager.ref,
            syncController = syncController.ref,
            pivotBlockNumber = BigInt(100),
            snapSyncConfig = newConfig(),
            preloadedMissingForTesting = Some(missingOne),
            coordinatorForTesting = Some(coordinatorProbe.ref)
          )
        )
      )

      coordinatorProbe.expectMsgType[snap.actors.Messages.AddByteCodeTasks](2.seconds)
      coordinatorProbe.expectMsg(2.seconds, snap.actors.Messages.NoMoreByteCodeTasks)

      actor ! SNAPSyncController.ByteCodeSyncComplete

      syncController.expectMsg(3.seconds, BytecodeRecoveryActor.RecoveryComplete)
      appStateStorage.isBytecodeRecoveryDone() shouldBe true

      system.stop(actor)
    }

  it should
    "emit RecoveryComplete when flat scan finds no missing bytecodes (empty storage)" taggedAs (
      UnitTest,
      SyncTest
    ) in {
      val syncController = TestProbe("syncController_t3")
      val networkPeerManager = TestProbe("networkPeerManager_t3")
      val coordinatorProbe = TestProbe("coordinator_t3")
      // Empty FlatAccountStorage: seekFrom returns empty stream → 0 accounts → RecoveryComplete
      val (stateStorage, appStateStorage, evmCodeStorage, flatAccounts) = newStorages()

      val actor = system.actorOf(
        Props(
          new BytecodeRecoveryActor(
            stateRoot = fakeStateRoot,
            stateStorage = stateStorage,
            evmCodeStorage = evmCodeStorage,
            flatAccountStorage = flatAccounts,
            appStateStorage = appStateStorage,
            networkPeerManager = networkPeerManager.ref,
            syncController = syncController.ref,
            pivotBlockNumber = BigInt(100),
            snapSyncConfig = newConfig(),
            coordinatorForTesting = Some(coordinatorProbe.ref)
            // preloadedMissingForTesting = None → real flat scan → empty storage → RecoveryComplete
          )
        )
      )

      syncController.expectMsg(8.seconds, BytecodeRecoveryActor.RecoveryComplete)
      appStateStorage.isBytecodeRecoveryDone() shouldBe true

      system.stop(actor)
    }

  it should
    "emit RecoveryComplete when coordinator crashes unexpectedly (Terminated handler)" taggedAs (
      UnitTest,
      SyncTest
    ) in {
      val syncController = TestProbe("syncController_t4")
      val networkPeerManager = TestProbe("networkPeerManager_t4")
      val coordinatorProbe = TestProbe("coordinator_t4")
      val (stateStorage, appStateStorage, evmCodeStorage, flatAccounts) = newStorages()

      val actor = system.actorOf(
        Props(
          new BytecodeRecoveryActor(
            stateRoot = fakeStateRoot,
            stateStorage = stateStorage,
            evmCodeStorage = evmCodeStorage,
            flatAccountStorage = flatAccounts,
            appStateStorage = appStateStorage,
            networkPeerManager = networkPeerManager.ref,
            syncController = syncController.ref,
            pivotBlockNumber = BigInt(100),
            snapSyncConfig = newConfig(),
            preloadedMissingForTesting = Some(missingOne),
            coordinatorForTesting = Some(coordinatorProbe.ref)
          )
        )
      )

      coordinatorProbe.expectMsgType[snap.actors.Messages.AddByteCodeTasks](2.seconds)
      coordinatorProbe.expectMsg(2.seconds, snap.actors.Messages.NoMoreByteCodeTasks)

      // Kill the coordinator — recovery actor watches it and should handle Terminated
      system.stop(coordinatorProbe.ref)

      syncController.expectMsg(5.seconds, BytecodeRecoveryActor.RecoveryComplete)
      appStateStorage.isBytecodeRecoveryDone() shouldBe true

      system.stop(actor)
    }

  it should
    "abandon and emit RecoveryComplete when no download progress arrives within the timeout" taggedAs (
      UnitTest,
      SyncTest
    ) in {
      val syncController = TestProbe("syncController_t5")
      val networkPeerManager = TestProbe("networkPeerManager_t5")
      val coordinatorProbe = TestProbe("coordinator_t5")
      val (stateStorage, appStateStorage, evmCodeStorage, flatAccounts) = newStorages()

      val abandonAfter = 400.millis

      val actor = system.actorOf(
        Props(
          new BytecodeRecoveryActor(
            stateRoot = fakeStateRoot,
            stateStorage = stateStorage,
            evmCodeStorage = evmCodeStorage,
            flatAccountStorage = flatAccounts,
            appStateStorage = appStateStorage,
            networkPeerManager = networkPeerManager.ref,
            syncController = syncController.ref,
            pivotBlockNumber = BigInt(100),
            snapSyncConfig = newConfig(abandonAfter),
            preloadedMissingForTesting = Some(missingOne),
            coordinatorForTesting = Some(coordinatorProbe.ref)
          )
        )
      )

      coordinatorProbe.expectMsgType[snap.actors.Messages.AddByteCodeTasks](2.seconds)
      coordinatorProbe.expectMsg(2.seconds, snap.actors.Messages.NoMoreByteCodeTasks)

      // No ProgressBytecodesDownloaded → progressSeq stays 0 → CheckAbandon(0) fires and abandons
      syncController.expectMsg(abandonAfter * 4, BytecodeRecoveryActor.RecoveryComplete)
      appStateStorage.isBytecodeRecoveryDone() shouldBe true

      system.stop(actor)
    }

  it should
    "send AddByteCodeTasks then NoMoreByteCodeTasks when tasks are present (Bug 2 fix)" taggedAs (
      UnitTest,
      SyncTest
    ) in {
      val syncController = TestProbe("syncController_seal")
      val networkPeerManager = TestProbe("networkPeerManager_seal")
      val coordinatorProbe = TestProbe("coordinator_seal")
      val (stateStorage, appStateStorage, evmCodeStorage, flatAccounts) = newStorages()

      system.actorOf(
        Props(
          new BytecodeRecoveryActor(
            stateRoot = fakeStateRoot,
            stateStorage = stateStorage,
            evmCodeStorage = evmCodeStorage,
            flatAccountStorage = flatAccounts,
            appStateStorage = appStateStorage,
            networkPeerManager = networkPeerManager.ref,
            syncController = syncController.ref,
            pivotBlockNumber = BigInt(100),
            snapSyncConfig = newConfig(),
            preloadedMissingForTesting = Some(missingOne),
            coordinatorForTesting = Some(coordinatorProbe.ref)
          )
        )
      )

      coordinatorProbe.expectMsgType[snap.actors.Messages.AddByteCodeTasks](2.seconds)
      coordinatorProbe.expectMsg(2.seconds, snap.actors.Messages.NoMoreByteCodeTasks)
    }
}
