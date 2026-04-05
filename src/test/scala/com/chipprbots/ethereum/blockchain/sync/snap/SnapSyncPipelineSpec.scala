package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.{TestKit, TestProbe, ImplicitSender}
import org.apache.pekko.util.ByteString

import scala.concurrent.duration._

import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap.actors._
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.FlatSlotStorage
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.p2p.messages.SNAP._
import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetAccountRange.GetAccountRangeEnc
import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetByteCodes.GetByteCodesEnc
import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.testing.{PeerTestHelpers, TestEvmCodeStorage, TestMptStorage}

/** End-to-end pipeline tests exercising the snap sync flow through coordinators.
  *
  * These tests simulate the full snap sync lifecycle by wiring coordinators with
  * test probes and verifying message flow, phase transitions, and completion.
  */
class SnapSyncPipelineSpec
    extends TestKit(ActorSystem("SnapSyncPipelineSpec"))
    with ImplicitSender
    with AnyFlatSpecLike
    with Matchers
    with BeforeAndAfterAll {

  override def afterAll(): Unit =
    TestKit.shutdownActorSystem(system)

  // ========================================
  // Account Range → Completion
  // ========================================

  "AccountRangeCoordinator" should "accept multiple peers and dispatch tasks" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()

    val coordinator = system.actorOf(
      AccountRangeCoordinator.props(
        stateRoot = stateRoot,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        mptStorage = storage,
        concurrency = 2,
        snapSyncController = snapSyncController.ref
      )
    )

    coordinator ! Messages.StartAccountRangeSync(stateRoot)

    // Add 2 peers and verify 2 requests dispatched
    val peer1 = PeerTestHelpers.createTestPeer("peer-1", TestProbe().ref)
    val peer2 = PeerTestHelpers.createTestPeer("peer-2", TestProbe().ref)

    coordinator ! Messages.PeerAvailable(peer1)
    networkPeerManager.expectMsgType[Any](3.seconds)

    coordinator ! Messages.PeerAvailable(peer2)
    networkPeerManager.expectMsgType[Any](3.seconds)
  }

  it should "report non-empty initial progress" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()

    val coordinator = system.actorOf(
      AccountRangeCoordinator.props(
        stateRoot = stateRoot,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        mptStorage = storage,
        concurrency = 4,
        snapSyncController = snapSyncController.ref
      )
    )

    coordinator ! Messages.StartAccountRangeSync(stateRoot)
    coordinator ! Messages.GetProgress

    val stats = expectMsgType[AccountRangeStats](3.seconds)
    stats.tasksPending shouldBe 4 // concurrency = 4 means 4 tasks
    stats.tasksActive shouldBe 0
    stats.tasksCompleted shouldBe 0
    stats.accountsDownloaded shouldBe 0
    stats.progress shouldBe 0.0
  }

  it should "handle pivot refresh without crashing" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()

    val coordinator = system.actorOf(
      AccountRangeCoordinator.props(
        stateRoot = stateRoot,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        mptStorage = storage,
        concurrency = 2,
        snapSyncController = snapSyncController.ref
      )
    )

    coordinator ! Messages.StartAccountRangeSync(stateRoot)

    // Refresh pivot
    val newRoot = kec256(ByteString("new-root"))
    coordinator ! Messages.PivotRefreshed(newRoot)

    // Should still be operational
    coordinator ! Messages.GetProgress
    val stats = expectMsgType[AccountRangeStats](3.seconds)
    stats.tasksPending should be > 0
  }

  // ========================================
  // ByteCode → Completion Flow
  // ========================================

  "ByteCodeCoordinator" should "complete when no tasks and sentinel received" taggedAs UnitTest in {
    val evmCodeStorage = new TestEvmCodeStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()

    val coordinator = system.actorOf(
      ByteCodeCoordinator.props(
        evmCodeStorage = evmCodeStorage,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        batchSize = 8,
        snapSyncController = snapSyncController.ref
      )
    )

    coordinator ! Messages.StartByteCodeSync(Seq.empty)
    coordinator ! Messages.NoMoreByteCodeTasks
    coordinator ! Messages.ByteCodeCheckCompletion

    snapSyncController.expectMsg(3.seconds, SNAPSyncController.ByteCodeSyncComplete)
  }

  it should "not complete before sentinel even with empty task queue" taggedAs UnitTest in {
    val evmCodeStorage = new TestEvmCodeStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()

    val coordinator = system.actorOf(
      ByteCodeCoordinator.props(
        evmCodeStorage = evmCodeStorage,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        batchSize = 8,
        snapSyncController = snapSyncController.ref
      )
    )

    coordinator ! Messages.StartByteCodeSync(Seq.empty)
    // No sentinel sent
    coordinator ! Messages.ByteCodeCheckCompletion

    snapSyncController.expectNoMessage(500.millis)
  }

  it should "handle incremental task addition via AddByteCodeTasks" taggedAs UnitTest in {
    val evmCodeStorage = new TestEvmCodeStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()
    val peerProbe = TestProbe()
    val peer = PeerTestHelpers.createTestPeer("test-peer", peerProbe.ref)

    val coordinator = system.actorOf(
      ByteCodeCoordinator.props(
        evmCodeStorage = evmCodeStorage,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        batchSize = 8,
        snapSyncController = snapSyncController.ref
      )
    )

    // Start empty, then add tasks incrementally
    coordinator ! Messages.StartByteCodeSync(Seq.empty)

    val hash1 = kec256(ByteString("code1"))
    coordinator ! Messages.AddByteCodeTasks(Seq(hash1))
    coordinator ! Messages.ByteCodePeerAvailable(peer)

    // Should dispatch request for the incrementally added hash
    networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessage](3.seconds)
  }

  it should "download and store bytecodes end-to-end" taggedAs UnitTest in {
    val evmCodeStorage = new TestEvmCodeStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()
    val peerProbe = TestProbe()
    val peer = PeerTestHelpers.createTestPeer("test-peer", peerProbe.ref)

    val coordinator = system.actorOf(
      ByteCodeCoordinator.props(
        evmCodeStorage = evmCodeStorage,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        batchSize = 85,
        snapSyncController = snapSyncController.ref
      )
    )

    val code1 = ByteString("contract bytecode one")
    val code2 = ByteString("contract bytecode two")
    val h1 = kec256(code1)
    val h2 = kec256(code2)

    coordinator ! Messages.StartByteCodeSync(Seq(h1, h2))
    coordinator ! Messages.ByteCodePeerAvailable(peer)

    val send1 = networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessage](3.seconds)
    val req1 = send1.message.asInstanceOf[GetByteCodesEnc].underlyingMsg
    req1.hashes should contain allOf (h1, h2)

    // Respond with both bytecodes
    system.actorSelection(coordinator.path / "*") ! Messages.ByteCodesResponseMsg(
      ByteCodes(req1.requestId, Seq(code1, code2))
    )

    // Verify storage
    within(3.seconds) {
      awaitAssert {
        evmCodeStorage.get(h1) shouldEqual Some(code1)
        evmCodeStorage.get(h2) shouldEqual Some(code2)
      }
    }

    // Expect progress message from bytecode download
    snapSyncController.expectMsgType[SNAPSyncController.ProgressBytecodesDownloaded](5.seconds)

    // Now signal completion
    coordinator ! Messages.NoMoreByteCodeTasks
    coordinator ! Messages.ByteCodeCheckCompletion
    snapSyncController.expectMsg(5.seconds, SNAPSyncController.ByteCodeSyncComplete)
  }

  // ========================================
  // Storage → Completion Flow
  // ========================================

  "StorageRangeCoordinator" should "complete immediately with no tasks and sentinel" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()

    val coordinator = system.actorOf(
      StorageRangeCoordinator.props(
        stateRoot = stateRoot,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        mptStorage = storage,
        flatSlotStorage = new FlatSlotStorage(EphemDataSource()),
        maxAccountsPerBatch = 8,
        maxInFlightRequests = 8,
        requestTimeout = 30.seconds,
        snapSyncController = snapSyncController.ref
      )
    )

    coordinator ! Messages.StartStorageRangeSync(stateRoot)
    coordinator ! Messages.NoMoreStorageTasks
    coordinator ! Messages.StorageCheckCompletion

    snapSyncController.expectMsg(3.seconds, SNAPSyncController.StorageRangeSyncComplete)
  }

  it should "handle ForceCompleteStorage during stagnation" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()

    val coordinator = system.actorOf(
      StorageRangeCoordinator.props(
        stateRoot = stateRoot,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        mptStorage = storage,
        flatSlotStorage = new FlatSlotStorage(EphemDataSource()),
        maxAccountsPerBatch = 8,
        maxInFlightRequests = 8,
        requestTimeout = 30.seconds,
        snapSyncController = snapSyncController.ref
      )
    )

    coordinator ! Messages.StartStorageRangeSync(stateRoot)
    coordinator ! Messages.NoMoreStorageTasks

    // Force complete (simulating stagnation recovery)
    coordinator ! Messages.ForceCompleteStorage

    snapSyncController.expectMsg(5.seconds, SNAPSyncController.StorageRangeSyncComplete)
  }

  it should "accept AddStorageTasks incrementally" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()

    val coordinator = system.actorOf(
      StorageRangeCoordinator.props(
        stateRoot = stateRoot,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        mptStorage = storage,
        flatSlotStorage = new FlatSlotStorage(EphemDataSource()),
        maxAccountsPerBatch = 8,
        maxInFlightRequests = 8,
        requestTimeout = 30.seconds,
        snapSyncController = snapSyncController.ref
      )
    )

    coordinator ! Messages.StartStorageRangeSync(stateRoot)

    // Incrementally add storage tasks
    val task1 = StorageTask.createStorageTask(kec256(ByteString("acc1")), kec256(ByteString("root1")))
    val task2 = StorageTask.createStorageTask(kec256(ByteString("acc2")), kec256(ByteString("root2")))
    coordinator ! Messages.AddStorageTasks(Seq(task1, task2))

    // Verify progress
    coordinator ! Messages.StorageGetProgress
    expectMsgType[Any](3.seconds)
  }

  it should "handle pivot refresh without crashing" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()

    val coordinator = system.actorOf(
      StorageRangeCoordinator.props(
        stateRoot = stateRoot,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        mptStorage = storage,
        flatSlotStorage = new FlatSlotStorage(EphemDataSource()),
        maxAccountsPerBatch = 8,
        maxInFlightRequests = 8,
        requestTimeout = 30.seconds,
        snapSyncController = snapSyncController.ref
      )
    )

    coordinator ! Messages.StartStorageRangeSync(stateRoot)
    coordinator ! Messages.StoragePivotRefreshed(kec256(ByteString("new-root")))

    // Should still be operational
    coordinator ! Messages.StorageGetProgress
    expectMsgType[Any](3.seconds)
  }

  // ========================================
  // Healing → Completion Flow
  // ========================================

  "TrieNodeHealingCoordinator" should "complete immediately with no missing nodes" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()

    val coordinator = system.actorOf(
      TrieNodeHealingCoordinator.props(
        stateRoot = stateRoot,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        mptStorage = storage,
        batchSize = 16,
        snapSyncController = snapSyncController.ref
      )
    )

    coordinator ! Messages.StartTrieNodeHealing(stateRoot)
    coordinator ! Messages.HealingCheckCompletion

    snapSyncController.expectMsg(3.seconds, SNAPSyncController.StateHealingComplete(0))
  }

  it should "dispatch healing requests when missing nodes queued" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()
    val peerProbe = TestProbe()
    val peer = PeerTestHelpers.createTestPeer("test-peer", peerProbe.ref)

    val coordinator = system.actorOf(
      TrieNodeHealingCoordinator.props(
        stateRoot = stateRoot,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        mptStorage = storage,
        batchSize = 16,
        snapSyncController = snapSyncController.ref
      )
    )

    val missingNodes = Seq(
      (Seq(ByteString(Array[Byte](0x00))), kec256(ByteString("node1"))),
      (Seq(ByteString(Array[Byte](0x01))), kec256(ByteString("node2")))
    )

    coordinator ! Messages.StartTrieNodeHealing(stateRoot)
    coordinator ! Messages.QueueMissingNodes(missingNodes)
    coordinator ! Messages.HealingPeerAvailable(peer)

    networkPeerManager.expectMsgType[Any](3.seconds)
  }

  it should "handle pivot refresh during healing" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()

    val coordinator = system.actorOf(
      TrieNodeHealingCoordinator.props(
        stateRoot = stateRoot,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        mptStorage = storage,
        batchSize = 16,
        snapSyncController = snapSyncController.ref
      )
    )

    coordinator ! Messages.StartTrieNodeHealing(stateRoot)
    coordinator ! Messages.HealingPivotRefreshed(kec256(ByteString("new-root")))

    // Should still be operational
    coordinator ! Messages.HealingGetProgress
    expectMsgType[Any](3.seconds)
  }

  // ========================================
  // SNAPSyncController Messages
  // ========================================

  "SNAPSyncController companion" should "define all phase types" taggedAs UnitTest in {
    import SNAPSyncController._

    val phases: Seq[SyncPhase] = Seq(
      Idle,
      AccountRangeSync,
      ByteCodeSync,
      ByteCodeAndStorageSync,
      StorageRangeSync,
      StateHealing,
      StateValidation,
      ChainDownloadCompletion,
      Completed
    )

    phases.size shouldBe 9
    phases.distinct.size shouldBe 9
  }

  it should "define PivotSelectionSource variants" taggedAs UnitTest in {
    import SNAPSyncController._

    NetworkPivot.name shouldBe "network"
    LocalPivot.name shouldBe "local"
  }

  it should "define IncrementalContractData" taggedAs UnitTest in {
    import SNAPSyncController._

    val data = IncrementalContractData(
      codeHashes = Seq(kec256(ByteString("code1"))),
      storageTasks = Seq(
        StorageTask.createStorageTask(kec256(ByteString("acc1")), kec256(ByteString("root1")))
      )
    )

    data.codeHashes.size shouldBe 1
    data.storageTasks.size shouldBe 1
  }

  it should "define progress delta messages" taggedAs UnitTest in {
    import SNAPSyncController._

    ProgressAccountsSynced(100).count shouldBe 100
    ProgressBytecodesDownloaded(50).count shouldBe 50
    ProgressStorageSlotsSynced(200).count shouldBe 200
    ProgressNodesHealed(10).count shouldBe 10
    ProgressAccountEstimate(67000000).estimatedTotal shouldBe 67000000L
    ProgressStorageContracts(500, 15000).completedContracts shouldBe 500
    ProgressStorageContracts(500, 15000).totalContracts shouldBe 15000
  }

  it should "define PivotStateUnservable" taggedAs UnitTest in {
    import SNAPSyncController._

    val msg = PivotStateUnservable(
      rootHash = kec256(ByteString("root")),
      reason = "all peers stateless",
      consecutiveEmptyResponses = 3
    )
    msg.consecutiveEmptyResponses shouldBe 3
    msg.reason should include("stateless")
  }

  it should "define AccountTrieFinalized" taggedAs UnitTest in {
    import SNAPSyncController._

    val root = kec256(ByteString("finalized-root"))
    val msg = AccountTrieFinalized(root)
    msg.finalizedRoot shouldBe root
  }

  it should "define StartRegularSyncBootstrap" taggedAs UnitTest in {
    import SNAPSyncController._

    val msg = StartRegularSyncBootstrap(BigInt(19250000))
    msg.targetBlock shouldBe BigInt(19250000)
  }

  it should "define BootstrapComplete" taggedAs UnitTest in {
    import SNAPSyncController._

    val msg = BootstrapComplete(None)
    msg.pivotHeader shouldBe None

    val msg2 = BootstrapComplete()
    msg2.pivotHeader shouldBe None
  }
}
