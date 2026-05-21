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
  *   T2: Missing present → coordinator receives StartByteCodeSync, completes normally.
  *   T3: Scan Future throws (trie root missing) → RecoveryComplete still fires.
  *   T4: Coordinator crashes mid-download → Terminated handler commits flag and fires RecoveryComplete.
  *   T5: No peer/progress arrives within timeout → abandon fires, RecoveryComplete emitted.
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

  private def newStorages(): (StateStorage, AppStateStorage, EvmCodeStorage) = {
    val ds = EphemDataSource()
    val nodeStorage = new NodeStorage(ds)
    val appStateStorage = new AppStateStorage(ds)
    val evmCodeStorage = new EvmCodeStorage(ds)
    val stateStorage = StateStorage(
      ArchivePruning,
      nodeStorage,
      new LruCache[NodeHash, HeapEntry](
        Config.inMemoryPruningNodeCacheConfig,
        Some(CachedReferenceCountedStorage.saveOnlyNotificationHandler(nodeStorage))
      )
    )
    (stateStorage, appStateStorage, evmCodeStorage)
  }

  "BytecodeRecoveryActor" should
    "emit RecoveryComplete immediately and commit flag when no bytecodes are missing" taggedAs (
      UnitTest,
      SyncTest
    ) in {
      val syncController = TestProbe("syncController_t1")
      val networkPeerManager = TestProbe("networkPeerManager_t1")
      val (stateStorage, appStateStorage, evmCodeStorage) = newStorages()

      val actor = system.actorOf(
        Props(
          new BytecodeRecoveryActor(
            stateRoot = fakeStateRoot,
            stateStorage = stateStorage,
            evmCodeStorage = evmCodeStorage,
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
    "spawn coordinator, forward StartByteCodeSync, and emit RecoveryComplete on successful download" taggedAs (
      UnitTest,
      SyncTest
    ) in {
      val syncController = TestProbe("syncController_t2")
      val networkPeerManager = TestProbe("networkPeerManager_t2")
      val coordinatorProbe = TestProbe("coordinator_t2")
      val (stateStorage, appStateStorage, evmCodeStorage) = newStorages()

      val actor = system.actorOf(
        Props(
          new BytecodeRecoveryActor(
            stateRoot = fakeStateRoot,
            stateStorage = stateStorage,
            evmCodeStorage = evmCodeStorage,
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

      coordinatorProbe.expectMsgType[snap.actors.Messages.StartByteCodeSync](2.seconds)

      actor ! SNAPSyncController.ByteCodeSyncComplete

      syncController.expectMsg(3.seconds, BytecodeRecoveryActor.RecoveryComplete)
      appStateStorage.isBytecodeRecoveryDone() shouldBe true

      system.stop(actor)
    }

  it should
    "emit RecoveryComplete even when the trie scan Future throws (trie root missing)" taggedAs (
      UnitTest,
      SyncTest
    ) in {
      val syncController = TestProbe("syncController_t3")
      val networkPeerManager = TestProbe("networkPeerManager_t3")
      // Empty storages: stateRoot not present in MPT → mptStorage.get throws → Future Failure
      val (stateStorage, appStateStorage, evmCodeStorage) = newStorages()

      val actor = system.actorOf(
        Props(
          new BytecodeRecoveryActor(
            stateRoot = fakeStateRoot,
            stateStorage = stateStorage,
            evmCodeStorage = evmCodeStorage,
            appStateStorage = appStateStorage,
            networkPeerManager = networkPeerManager.ref,
            syncController = syncController.ref,
            pivotBlockNumber = BigInt(100),
            snapSyncConfig = newConfig()
            // preloadedMissingForTesting = None → real scan path → throws
          )
        )
      )

      // Future Failure → ScanResult(Seq.empty) → RecoveryComplete (graceful resilience)
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
      val (stateStorage, appStateStorage, evmCodeStorage) = newStorages()

      val actor = system.actorOf(
        Props(
          new BytecodeRecoveryActor(
            stateRoot = fakeStateRoot,
            stateStorage = stateStorage,
            evmCodeStorage = evmCodeStorage,
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

      coordinatorProbe.expectMsgType[snap.actors.Messages.StartByteCodeSync](2.seconds)

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
      val (stateStorage, appStateStorage, evmCodeStorage) = newStorages()

      val abandonAfter = 400.millis

      val actor = system.actorOf(
        Props(
          new BytecodeRecoveryActor(
            stateRoot = fakeStateRoot,
            stateStorage = stateStorage,
            evmCodeStorage = evmCodeStorage,
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

      coordinatorProbe.expectMsgType[snap.actors.Messages.StartByteCodeSync](2.seconds)

      // No ProgressBytecodesDownloaded → progressSeq stays 0 → CheckAbandon(0) fires and abandons
      syncController.expectMsg(abandonAfter * 4, BytecodeRecoveryActor.RecoveryComplete)
      appStateStorage.isBytecodeRecoveryDone() shouldBe true

      system.stop(actor)
    }
}
