package com.chipprbots.ethereum.blockchain.sync

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.ActorRef as TypedActorRef
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import scala.concurrent.duration.*

import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncConfig
import com.chipprbots.ethereum.blockchain.sync.snap.StateValidator
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

/** Unit tests for BytecodeRecoveryActor covering all recovery paths:
  *
  * T1: No missing bytecodes → immediate RecoveryComplete, flag committed. T2: Missing present → coordinator receives
  * StartByteCodeSync, completes normally. T3: Scan Future throws (trie root missing) → RecoveryComplete still fires.
  * T4: Coordinator crashes mid-download → Terminated handler commits flag and fires RecoveryComplete. T5: No
  * peer/progress arrives within timeout → abandon fires, RecoveryComplete emitted.
  */
class BytecodeRecoveryActorSpec extends ScalaTestWithActorTestKit() with AnyFlatSpecLike with Matchers with Eventually:

  implicit private val classicSystem: org.apache.pekko.actor.ActorSystem = system.classicSystem

  private val fakeStateRoot: ByteString = ByteString(Array.fill[Byte](32)(0x11))
  private val fakeCodeHash: ByteString = ByteString(Array.fill[Byte](32)(0xaa.toByte))
  private val missingOne: Seq[ByteString] = Seq(fakeCodeHash)

  private def newConfig(abandonAfter: FiniteDuration = 10.minutes): SNAPSyncConfig =
    SNAPSyncConfig(storageRecoveryAbandonTimeout = abandonAfter)

  private def newStorages(): (StateStorage, AppStateStorage, EvmCodeStorage) =
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

  // The recovery actors take one `StateIngest` (spec 016 T060c, D-T060-2). They read only the stores below; any other
  // member fails loudly, so a test that reaches one is a change in what recovery reads.
  private def recoveryIngest(
      stateStorage: StateStorage,
      appStateStorage: AppStateStorage,
      evmCodeStorage: EvmCodeStorage
  ): StateIngest =
    new StateIngest:
      def appState: AppStateStorage = appStateStorage
      def stateTries: StateStorage = stateStorage
      def flatSlots: FlatSlotStorage = notReached("flatSlots")
      def pathNodes: Option[PathNodeStorage] = notReached("pathNodes")
      def bytecode: EvmCodeStorage = evmCodeStorage
      def chainReader: BlockchainReader = notReached("chainReader")
      def chainWriter: BlockchainWriter = notReached("chainWriter")
      def validatorFactory: MptStorage => StateValidator = notReached("validatorFactory")
      def durability: StateIngest.Durability = StateIngest.Durability.Default
      def trieStore(pivot: BigInt): MptStorage = notReached("trieStore")
      def snapProgress: SnapSyncProgressStorage = notReached("snapProgress")
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

  "BytecodeRecoveryActor" should
    "emit RecoveryComplete immediately and commit flag when no bytecodes are missing" taggedAs (
      UnitTest,
      SyncTest
    ) in {
      val syncController = TestProbe("syncController_t1")
      val networkPeerManager = TestProbe("networkPeerManager_t1")
      val (stateStorage, appStateStorage, evmCodeStorage) = newStorages()

      val actor: TypedActorRef[BytecodeRecoveryActor.Command] = testKit
        .spawn(
          BytecodeRecoveryActor.testApply(
            stateRoot = fakeStateRoot,
            stateIngest = recoveryIngest(stateStorage, appStateStorage, evmCodeStorage),
            networkPeerManager = networkPeerManager.ref,
            syncController = syncController.ref.toTyped[Any],
            pivotBlockNumber = BigInt(100),
            snapSyncConfig = newConfig(),
            preloaded = Some(Seq.empty)
          ),
          "bytecode-recovery-spec-t1"
        )

      syncController.expectMsg(3.seconds, BytecodeRecoveryActor.RecoveryComplete)
      appStateStorage.isBytecodeRecoveryDone() shouldBe true

      testKit.stop(actor)
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

      val actor: TypedActorRef[BytecodeRecoveryActor.Command] = testKit
        .spawn(
          BytecodeRecoveryActor.testApply(
            stateRoot = fakeStateRoot,
            stateIngest = recoveryIngest(stateStorage, appStateStorage, evmCodeStorage),
            networkPeerManager = networkPeerManager.ref,
            syncController = syncController.ref.toTyped[Any],
            pivotBlockNumber = BigInt(100),
            snapSyncConfig = newConfig(),
            preloaded = Some(missingOne),
            coordinatorForTesting = Some(coordinatorProbe.ref)
          ),
          "bytecode-recovery-spec-t2"
        )

      coordinatorProbe.expectMsgType[snap.actors.ByteCodeCoordinator.StartByteCodeSync](2.seconds)
      // The gap list is complete: the coordinator must be told, or it waits for more tasks and never announces completion.
      coordinatorProbe.expectMsg(2.seconds, snap.actors.ByteCodeCoordinator.NoMoreByteCodeTasks)

      actor ! BytecodeRecoveryActor.ByteCodeDownloadComplete

      syncController.expectMsg(3.seconds, BytecodeRecoveryActor.RecoveryComplete)
      appStateStorage.isBytecodeRecoveryDone() shouldBe true

      testKit.stop(actor)
    }

  it should
    "tell the controller the scan did not run when it throws (trie root missing), and not mark recovery done" taggedAs (
      UnitTest,
      SyncTest
    ) in {
      val syncController = TestProbe("syncController_t3")
      val networkPeerManager = TestProbe("networkPeerManager_t3")
      // Empty storages: stateRoot not present in MPT → mptStorage.get throws → Future Failure
      val (stateStorage, appStateStorage, evmCodeStorage) = newStorages()

      val actor: TypedActorRef[BytecodeRecoveryActor.Command] = testKit
        .spawn(
          BytecodeRecoveryActor.testApply(
            stateRoot = fakeStateRoot,
            stateIngest = recoveryIngest(stateStorage, appStateStorage, evmCodeStorage),
            networkPeerManager = networkPeerManager.ref,
            syncController = syncController.ref.toTyped[Any],
            pivotBlockNumber = BigInt(100),
            snapSyncConfig = newConfig()
            // preloaded = None → real scan path → throws
          ),
          "bytecode-recovery-spec-t3"
        )

      // A scan that could not run still ends recovery for this run, so that regular sync starts. It settles nothing:
      // it was taken as a scan that found no code missing, and the flag was written on a state nobody had read (spec
      // 018 Track C, C2b; the combined scan already leaves both flags unwritten when it cannot run). The controller is
      // told which it was: it does not count a scan that never ran against its cap on bulk recovery.
      syncController.expectMsg(8.seconds, BytecodeRecoveryActor.RecoveryNotRun)
      appStateStorage.isBytecodeRecoveryDone() shouldBe false

      testKit.stop(actor)
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

      val actor: TypedActorRef[BytecodeRecoveryActor.Command] = testKit
        .spawn(
          BytecodeRecoveryActor.testApply(
            stateRoot = fakeStateRoot,
            stateIngest = recoveryIngest(stateStorage, appStateStorage, evmCodeStorage),
            networkPeerManager = networkPeerManager.ref,
            syncController = syncController.ref.toTyped[Any],
            pivotBlockNumber = BigInt(100),
            snapSyncConfig = newConfig(),
            preloaded = Some(missingOne),
            coordinatorForTesting = Some(coordinatorProbe.ref)
          ),
          "bytecode-recovery-spec-t4"
        )

      coordinatorProbe.expectMsgType[snap.actors.ByteCodeCoordinator.StartByteCodeSync](2.seconds)

      // Kill the coordinator — recovery actor watches it and should handle CoordinatorTerminated
      classicSystem.stop(coordinatorProbe.ref)

      syncController.expectMsg(5.seconds, BytecodeRecoveryActor.RecoveryComplete)
      appStateStorage.isBytecodeRecoveryDone() shouldBe true

      testKit.stop(actor)
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

      val actor: TypedActorRef[BytecodeRecoveryActor.Command] = testKit
        .spawn(
          BytecodeRecoveryActor.testApply(
            stateRoot = fakeStateRoot,
            stateIngest = recoveryIngest(stateStorage, appStateStorage, evmCodeStorage),
            networkPeerManager = networkPeerManager.ref,
            syncController = syncController.ref.toTyped[Any],
            pivotBlockNumber = BigInt(100),
            snapSyncConfig = newConfig(abandonAfter),
            preloaded = Some(missingOne),
            coordinatorForTesting = Some(coordinatorProbe.ref)
          ),
          "bytecode-recovery-spec-t5"
        )

      coordinatorProbe.expectMsgType[snap.actors.ByteCodeCoordinator.StartByteCodeSync](2.seconds)

      // No ProgressBytecodesDownloaded → progressSeq stays 0 → CheckAbandon(0) fires and abandons
      syncController.expectMsg(abandonAfter * 4, BytecodeRecoveryActor.RecoveryComplete)
      appStateStorage.isBytecodeRecoveryDone() shouldBe true

      testKit.stop(actor)
    }

  // B-376 (the user's decision of 2026-11-19): on the unified layout the actor refuses to start, loudly, by its one
  // entry; with gaps handed to it (the combined scan's form) and with a scan of its own
  "BytecodeRecoveryActor on the unified layout" should "refuse to start, log why at ERROR, write nothing and set no flag" taggedAs (
    UnitTest,
    SyncTest
  ) in {
    for (preloaded, label) <- Seq((Some(missingOne), "preloaded"), (None, "scan")) do
      val syncController = TestProbe(s"syncController_refused_$label")
      val coordinator = TestProbe(s"coordinator_refused_$label")
      val networkPeerManager = TestProbe(s"npm_refused_$label")
      val (stateStorage, appStateStorage, evmCodeStorage) = newStorages()
      val actor = org.apache.pekko.actor.testkit.typed.scaladsl.LoggingTestKit
        .error(BytecodeRecoveryActor.RefusedOnPositions)
        .expect {
          testKit.spawn(
            BytecodeRecoveryActor.testApply(
              stateRoot = fakeStateRoot,
              stateIngest = recoveryIngest(stateStorage, appStateStorage, evmCodeStorage),
              networkPeerManager = networkPeerManager.ref,
              syncController = syncController.ref.toTyped[Any],
              pivotBlockNumber = BigInt(100),
              snapSyncConfig = newConfig(),
              preloaded = preloaded,
              coordinatorForTesting = Some(coordinator.ref),
              positions = Some(OnThePositionLayout)
            ),
            s"bytecode-recovery-spec-refused-$label"
          )
        }
      syncController.expectMsg(3.seconds, BytecodeRecoveryActor.RecoveryNotRun)
      createTestProbe[Any]().expectTerminated(actor, 3.seconds)
      coordinator.expectNoMessage(200.millis)
      appStateStorage.isBytecodeRecoveryDone() shouldBe false
  }

  it should "start as before on the hash layout" taggedAs (UnitTest, SyncTest) in {
    val syncController = TestProbe("syncController_hash_layout")
    val coordinator = TestProbe("coordinator_hash_layout")
    val networkPeerManager = TestProbe("npm_hash_layout")
    val (stateStorage, appStateStorage, evmCodeStorage) = newStorages()
    val actor = testKit.spawn(
      BytecodeRecoveryActor.testApply(
        stateRoot = fakeStateRoot,
        stateIngest = recoveryIngest(stateStorage, appStateStorage, evmCodeStorage),
        networkPeerManager = networkPeerManager.ref,
        syncController = syncController.ref.toTyped[Any],
        pivotBlockNumber = BigInt(100),
        snapSyncConfig = newConfig(),
        preloaded = Some(missingOne),
        coordinatorForTesting = Some(coordinator.ref)
      ),
      "bytecode-recovery-spec-hash-layout"
    )
    coordinator.expectMsgType[snap.actors.ByteCodeCoordinator.StartByteCodeSync](3.seconds)
    syncController.expectNoMessage(200.millis)
    testKit.stop(actor)
  }
