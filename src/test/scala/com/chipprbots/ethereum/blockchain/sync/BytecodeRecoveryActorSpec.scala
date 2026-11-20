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
import com.chipprbots.ethereum.db.storage.BfsQueueStorage
import com.chipprbots.ethereum.db.storage.CachedReferenceCountedStorage
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.FlatSlotStorage
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

  // B-396 (lifting B-376's refusal for bytecode recovery only): on the unified layout the actor scans the anchor's rows
  // by position, downloads the missing code into the shared code store, and sets the flag only once that is done.
  // A real state, seeded as rows under an anchor; two of its accounts share one code, a third has another.
  private val codeOfAliceAndCarol = ByteString("alice's code, which carol has too")
  private val codeOfDave = ByteString("dave's code")
  private def hashOf(code: ByteString): ByteString = ByteString(com.chipprbots.ethereum.crypto.kec256(code.toArray))

  final private class UnifiedState:
    import com.chipprbots.ethereum.db.storage.pathstate.PathStateTestKit.*
    private val hashed = new HashedState
    val root: ByteString = hashed.change(hashed.root) { world =>
      world
        .saveCode(alice, codeOfAliceAndCarol)
        .saveCode(carol, codeOfAliceAndCarol)
        .saveCode(dave, codeOfDave)
    }
    val source: EphemDataSource = EphemDataSource()
    seedPositions(source, hashed.positions(root), block(1, root))
    val appState = new AppStateStorage(source)
    val positions =
      new com.chipprbots.ethereum.db.storage.pathstate.PathStateStorage(new PathNodeStorage(source), appState)
    // the code store of the node: empty, or holding what a test puts there
    val codes = new EvmCodeStorage(EphemDataSource())
    val wanted: Set[ByteString] = Set(hashOf(codeOfAliceAndCarol), hashOf(codeOfDave))

  private def spawnOnPositions(state: UnifiedState, label: String, coordinator: TestProbe, controller: TestProbe) =
    testKit.spawn(
      BytecodeRecoveryActor.testApply(
        stateRoot = state.root,
        stateIngest = recoveryIngest(newStorages()._1, state.appState, state.codes),
        networkPeerManager = TestProbe(s"npm_$label").ref,
        syncController = controller.ref.toTyped[Any],
        pivotBlockNumber = BigInt(1),
        snapSyncConfig = newConfig(),
        coordinatorForTesting = Some(coordinator.ref),
        positions = Some(state.positions)
      ),
      s"bytecode-recovery-spec-$label"
    )

  "BytecodeRecoveryActor on the unified layout" should
    "scan the anchor's rows, fill the missing code, and set the flag only when the download is complete" taggedAs (
      UnitTest,
      SyncTest
    ) in {
      val state = new UnifiedState
      val controller = TestProbe("syncController_unified_fill")
      val coordinator = TestProbe("coordinator_unified_fill")
      val actor = spawnOnPositions(state, "unified-fill", coordinator, controller)

      // the scan, reading rows by position, found exactly the codes the state names and the store lacks
      val start = coordinator.expectMsgType[snap.actors.ByteCodeCoordinator.StartByteCodeSync](10.seconds)
      start.codeHashes.toSet shouldBe state.wanted
      start.codeHashes should have size 2
      coordinator.expectMsg(2.seconds, snap.actors.ByteCodeCoordinator.NoMoreByteCodeTasks)
      // nothing is settled while the code is not there
      controller.expectNoMessage(200.millis)
      state.appState.isBytecodeRecoveryDone() shouldBe false
      state.wanted.foreach(hash => state.codes.get(hash) shouldBe None)

      // the download writes the code store (as ByteCodeResponseHandling does), then reports completion
      state.codes
        .put(hashOf(codeOfAliceAndCarol), codeOfAliceAndCarol)
        .and(state.codes.put(hashOf(codeOfDave), codeOfDave))
        .commit()
      actor ! BytecodeRecoveryActor.ByteCodeDownloadComplete

      controller.expectMsg(3.seconds, BytecodeRecoveryActor.RecoveryComplete)
      state.appState.isBytecodeRecoveryDone() shouldBe true
      state.wanted.foreach(hash => state.codes.get(hash) should not be None)
    }

  it should "set the flag at once, with no download, when the anchor's state has all its code" taggedAs (
    UnitTest,
    SyncTest
  ) in {
    val state = new UnifiedState
    state.codes
      .put(hashOf(codeOfAliceAndCarol), codeOfAliceAndCarol)
      .and(state.codes.put(hashOf(codeOfDave), codeOfDave))
      .commit()
    val controller = TestProbe("syncController_unified_whole")
    val coordinator = TestProbe("coordinator_unified_whole")
    spawnOnPositions(state, "unified-whole", coordinator, controller)

    controller.expectMsg(10.seconds, BytecodeRecoveryActor.RecoveryComplete)
    state.appState.isBytecodeRecoveryDone() shouldBe true
    coordinator.expectNoMessage(200.millis)
  }

  it should "not set the flag when the scan cannot read the anchor's state, and tell the controller it did not run" taggedAs (
    UnitTest,
    SyncTest
  ) in {
    val state = new UnifiedState
    val controller = TestProbe("syncController_unified_unreadable")
    val coordinator = TestProbe("coordinator_unified_unreadable")
    // a root the rows do not hold
    val actor = testKit.spawn(
      BytecodeRecoveryActor.testApply(
        stateRoot = fakeStateRoot,
        stateIngest = recoveryIngest(newStorages()._1, state.appState, state.codes),
        networkPeerManager = TestProbe("npm_unified_unreadable").ref,
        syncController = controller.ref.toTyped[Any],
        pivotBlockNumber = BigInt(1),
        snapSyncConfig = newConfig(),
        coordinatorForTesting = Some(coordinator.ref),
        positions = Some(state.positions)
      ),
      "bytecode-recovery-spec-unified-unreadable"
    )
    controller.expectMsg(8.seconds, BytecodeRecoveryActor.RecoveryNotRun)
    state.appState.isBytecodeRecoveryDone() shouldBe false
    coordinator.expectNoMessage(200.millis)
    testKit.stop(actor)
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
