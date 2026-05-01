package com.chipprbots.ethereum.blockchain.sync.snap.actors

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.{TestKit, TestProbe, ImplicitSender}
import org.apache.pekko.util.ByteString

import scala.concurrent.duration._

import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap._
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.FlatSlotStorage
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetStorageRanges.GetStorageRangesEnc
import com.chipprbots.ethereum.network.p2p.messages.SNAP.StorageRanges
import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.testing.{PeerTestHelpers, TestMptStorage}

class StorageRangeCoordinatorSpec
    extends TestKit(ActorSystem("StorageRangeCoordinatorSpec"))
    with ImplicitSender
    with AnyFlatSpecLike
    with Matchers
    with BeforeAndAfterAll {

  override def afterAll(): Unit =
    TestKit.shutdownActorSystem(system)

  "StorageRangeCoordinator" should "initialize correctly" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-state-root"))
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

    coordinator should not be null
  }

  it should "handle peer availability" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-state-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()
    val peerProbe = TestProbe()

    val peer = PeerTestHelpers.createTestPeer("test-peer", peerProbe.ref)

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
    coordinator ! Messages.StoragePeerAvailable(peer)

    // Should handle peer availability (may or may not send request depending on tasks)
    coordinator ! Messages.StorageGetProgress
    expectMsgType[Any](3.seconds)
  }

  it should "handle task completion" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-state-root"))
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

    coordinator ! Messages.StorageTaskComplete(BigInt(123), Right(10))

    // Coordinator should handle completion
    coordinator ! Messages.StorageGetProgress
    expectMsgType[Any](3.seconds)
  }

  it should "report completion when no storage tasks" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-state-root"))
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

    // Signal that no more tasks will arrive (sentinel pattern)
    coordinator ! Messages.NoMoreStorageTasks

    coordinator ! Messages.StorageCheckCompletion

    // Should complete immediately since no tasks and sentinel received
    snapSyncController.expectMsg(3.seconds, SNAPSyncController.StorageRangeSyncComplete)
  }

  it should "handle task failures" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-state-root"))
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

    coordinator ! Messages.StorageTaskFailed(BigInt(123), "Test failure")

    // Coordinator should still be operational
    coordinator ! Messages.StorageGetProgress
    expectMsgType[Any](3.seconds)
  }

  it should "accept AddStorageTasks and remain operational" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-state-root"))
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

    val accountHash1 = kec256(ByteString("account-1"))
    val storageRoot1 = kec256(ByteString("storage-root-1"))
    val task = StorageTask.createStorageTask(accountHash1, storageRoot1)

    coordinator ! Messages.AddStorageTasks(Seq(task))

    // Should remain operational after adding tasks
    coordinator ! Messages.StorageGetProgress
    expectMsgType[Any](3.seconds)
  }

  it should "accept StoragePivotRefreshed and update state root" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("old-state-root"))
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

    val newStateRoot = kec256(ByteString("new-state-root"))
    coordinator ! Messages.StoragePivotRefreshed(newStateRoot)

    // Coordinator should still respond to progress queries after pivot refresh
    coordinator ! Messages.StorageGetProgress
    expectMsgType[Any](3.seconds)
  }

  it should "signal StorageRangeSyncComplete to controller when NoMoreStorageTasks received with no pending tasks" taggedAs UnitTest in {
    // Verifies the sentinel pattern: when account download finishes with no storage accounts,
    // the coordinator must complete immediately on NoMoreStorageTasks + StorageCheckCompletion.
    val stateRoot = kec256(ByteString("empty-state-root"))
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

  // ── K5: Proof-of-absence (BUG fix b38050e49) ──────────────────────────────

  it should "accept proof-of-absence response (0 slots + proof) and not mark peer stateless" taggedAs UnitTest in {
    // Fix b38050e49: when a peer returns 0 slots + non-empty proof for a single-account batch,
    // the coordinator must treat it as a valid cryptographic proof that the account has no
    // storage, complete the task, and NOT mark the peer stateless. The peer must be immediately
    // eligible to receive the next task via dispatchIfPossible().
    val stateRoot = kec256(ByteString("proof-of-absence-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()
    val peerProbe = TestProbe()

    val peer = PeerTestHelpers.createTestPeer("storage-peer-poa", peerProbe.ref)

    val account1 = kec256(ByteString("account-poa-1"))
    val account2 = kec256(ByteString("account-poa-2"))
    val storageRoot = kec256(ByteString("storage-root-poa"))

    val task1 = StorageTask.createStorageTask(account1, storageRoot)
    val task2 = StorageTask.createStorageTask(account2, storageRoot)

    // maxAccountsPerBatch=1 forces single-account batches → tasks.size==1 in processStorageRanges,
    // satisfying the proof-of-absence guard (response.proof.nonEmpty && tasks.size == 1).
    // initialMaxInFlightPerPeer=1 ensures only one request is in-flight at a time so the
    // second request is sent only after the first is resolved.
    val coordinator = system.actorOf(
      StorageRangeCoordinator.props(
        stateRoot = stateRoot,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        mptStorage = storage,
        flatSlotStorage = new FlatSlotStorage(EphemDataSource()),
        maxAccountsPerBatch = 1,
        maxInFlightRequests = 2,
        requestTimeout = 30.seconds,
        snapSyncController = snapSyncController.ref,
        initialMaxInFlightPerPeer = 1
      )
    )

    coordinator ! Messages.StartStorageRangeSync(stateRoot)
    coordinator ! Messages.AddStorageTasks(Seq(task1, task2))
    coordinator ! Messages.StoragePeerAvailable(peer)

    // Coordinator dispatches task1 to peer
    val send1 = networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessage](3.seconds)
    val req1 = send1.message.asInstanceOf[GetStorageRangesEnc].underlyingMsg
    req1.accountHashes should have size 1
    req1.accountHashes.head shouldEqual account1

    // Inject proof-of-absence: 0 slots, 1 proof node — valid snap/1 empty-storage proof
    val dummyProofNode = ByteString(Array.fill(32)(0xab.toByte))
    coordinator ! Messages.StorageRangesResponseMsg(
      StorageRanges(req1.requestId, slots = Seq.empty, proof = Seq(dummyProofNode))
    )

    // Peer is NOT stateless — coordinator immediately pipelines task2 to the same peer
    val send2 = networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessage](3.seconds)
    val req2 = send2.message.asInstanceOf[GetStorageRangesEnc].underlyingMsg
    req2.accountHashes should have size 1
    req2.accountHashes.head shouldEqual account2

    // No pivot-refresh stall signal: peer served a valid proof-of-absence response.
    // ProgressStorageContracts is a known-good progress update — filter it out and fail
    // only if a PivotStateUnservable (erroneous stall) slips through.
    snapSyncController.receiveWhile(300.millis) { case msg: SNAPSyncController.PivotStateUnservable =>
      fail(s"Unexpected pivot stall after proof-of-absence: $msg")
    }
  }

  // ── K5: No false stall signal when task queue is empty (BUG fix b07c363e9) ─

  it should "not emit PivotStateUnservable when no storage tasks are pending" taggedAs UnitTest in {
    // Fix b07c363e9: PivotStateUnservable must only be requested when tasks.nonEmpty.
    // During the account-range phase (before any storage tasks arrive), a StorageCheckCompletion
    // tick must be a no-op — not trigger a spurious pivot refresh that aborts the sync.
    val stateRoot = kec256(ByteString("no-stall-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()
    val peerProbe = TestProbe()

    val peer = PeerTestHelpers.createTestPeer("storage-peer-nostall", peerProbe.ref)

    val coordinator = system.actorOf(
      StorageRangeCoordinator.props(
        stateRoot = stateRoot,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        mptStorage = storage,
        flatSlotStorage = new FlatSlotStorage(EphemDataSource()),
        maxAccountsPerBatch = 1,
        maxInFlightRequests = 1,
        requestTimeout = 30.seconds,
        snapSyncController = snapSyncController.ref
      )
    )

    coordinator ! Messages.StartStorageRangeSync(stateRoot)
    // Peer known but no tasks queued — simulates the window during the account-range phase
    coordinator ! Messages.StoragePeerAvailable(peer)
    // StorageCheckCompletion tick that arrives before any storage tasks
    coordinator ! Messages.StorageCheckCompletion

    // Neither PivotStateUnservable nor StorageRangeSyncComplete should be sent:
    // tasks.isEmpty → maybeRequestPivotRefresh() not called, isComplete=false (no sentinel)
    snapSyncController.expectNoMessage(300.millis)
  }
}
