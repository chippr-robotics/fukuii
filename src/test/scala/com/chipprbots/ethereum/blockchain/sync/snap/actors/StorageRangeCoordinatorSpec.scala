package com.chipprbots.ethereum.blockchain.sync.snap.actors

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.{TestActorRef, TestKit, TestProbe, ImplicitSender}
import org.apache.pekko.util.ByteString

import scala.collection.mutable
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

  // ========================================
  // Flat-batch aggregator (issue #1165)
  // ========================================

  /** Helper: build a TestActorRef with the override EC and small threshold so flat-batch behaviour is observable
    * without spinning up the storage-writer-dispatcher.
    */
  private def newCoordWithFlatBatch(
      flatSlotStorage: FlatSlotStorage,
      threshold: Int,
      stateRootArg: ByteString = kec256(ByteString("flat-batch-test-root"))
  ): (TestActorRef[StorageRangeCoordinator], TestProbe) = {
    val controller = TestProbe()
    val ref = TestActorRef[StorageRangeCoordinator](
      StorageRangeCoordinator.props(
        stateRoot = stateRootArg,
        networkPeerManager = TestProbe().ref,
        requestTracker = new SNAPRequestTracker()(system.scheduler),
        mptStorage = new TestMptStorage(),
        flatSlotStorage = flatSlotStorage,
        maxAccountsPerBatch = 8,
        maxInFlightRequests = 8,
        requestTimeout = 30.seconds,
        snapSyncController = controller.ref,
        flatBatchEntryThreshold = threshold,
        flatBatchEcOverride = Some(system.dispatcher)
      )
    )
    (ref, controller)
  }

  /** Helper: synthesize an account-hash + slots payload of `slotsPerAccount` entries. */
  private def fakeContract(
      seed: Int,
      slotsPerAccount: Int
  ): (ByteString, mutable.ArrayBuffer[(ByteString, ByteString)]) = {
    val accountHash = kec256(ByteString(s"acct-$seed"))
    val slots = mutable.ArrayBuffer.empty[(ByteString, ByteString)]
    var i = 0
    while (i < slotsPerAccount) {
      val slotHash = kec256(ByteString(s"slot-$seed-$i"))
      val slotValue = ByteString(s"value-$seed-$i".getBytes)
      slots += ((slotHash, slotValue))
      i += 1
    }
    (accountHash, slots)
  }

  it should "buffer small-contract slots in the accumulator without immediate commit" taggedAs UnitTest in {
    val flatSlots = new FlatSlotStorage(EphemDataSource())
    val (coord, _) = newCoordWithFlatBatch(flatSlots, threshold = 100)

    val (accountHash, slots) = fakeContract(seed = 1, slotsPerAccount = 3)
    coord.underlyingActor.writeSmallContractFlatOnly(accountHash, slots)

    coord.underlyingActor.pendingFlatBatchEntries shouldBe 3
    coord.underlyingActor.pendingFlatBatchAccounts.size shouldBe 1
    coord.underlyingActor.inFlightFlatBatches shouldBe 0

    // Nothing was committed — FlatSlotStorage is still empty for our keys.
    flatSlots.getSlot(accountHash, slots.head._1) shouldBe None

    system.stop(coord)
  }

  it should "flush exactly once when the threshold is crossed and persist all buffered slots" taggedAs UnitTest in {
    val flatSlots = new FlatSlotStorage(EphemDataSource())
    val (coord, _) = newCoordWithFlatBatch(flatSlots, threshold = 5)

    // Three contracts, 2 slots each = 6 total slots, crossing the 5-entry threshold.
    val contracts = (1 to 3).map(i => fakeContract(seed = i, slotsPerAccount = 2))
    contracts.foreach { case (h, s) => coord.underlyingActor.writeSmallContractFlatOnly(h, s) }

    // Flush is async on system.dispatcher; the 1-s deadline is generous.
    awaitAssert(coord.underlyingActor.inFlightFlatBatches shouldBe 0, max = 1.second)

    // After flush, all 6 slots are durable.
    contracts.foreach { case (accountHash, slots) =>
      slots.foreach { case (slotHash, value) =>
        flatSlots.getSlot(accountHash, slotHash) shouldBe Some(value)
      }
    }

    // Accumulator was reset.
    coord.underlyingActor.pendingFlatBatchAccounts shouldBe empty
    coord.underlyingActor.pendingFlatBatchEntries shouldBe 0

    system.stop(coord)
  }

  it should "not trigger a flush when accumulator stays below threshold" taggedAs UnitTest in {
    val flatSlots = new FlatSlotStorage(EphemDataSource())
    val (coord, _) = newCoordWithFlatBatch(flatSlots, threshold = 1000)

    val (accountHash, slots) = fakeContract(seed = 42, slotsPerAccount = 50)
    coord.underlyingActor.writeSmallContractFlatOnly(accountHash, slots)

    coord.underlyingActor.pendingFlatBatchEntries shouldBe 50
    coord.underlyingActor.inFlightFlatBatches shouldBe 0
    flatSlots.getSlot(accountHash, slots.head._1) shouldBe None

    system.stop(coord)
  }

  it should "flush remaining accumulator on ForceCompleteStorage" taggedAs UnitTest in {
    val flatSlots = new FlatSlotStorage(EphemDataSource())
    val (coord, controller) = newCoordWithFlatBatch(flatSlots, threshold = 1000)

    val (accountHash, slots) = fakeContract(seed = 99, slotsPerAccount = 4)
    coord.underlyingActor.writeSmallContractFlatOnly(accountHash, slots)
    coord.underlyingActor.pendingFlatBatchEntries shouldBe 4

    coord ! Messages.ForceCompleteStorage

    awaitAssert(coord.underlyingActor.inFlightFlatBatches shouldBe 0, max = 1.second)
    slots.foreach { case (slotHash, value) =>
      flatSlots.getSlot(accountHash, slotHash) shouldBe Some(value)
    }
    controller.expectMsg(3.seconds, SNAPSyncController.StorageRangeSyncForceCompleted)

    system.stop(coord)
  }

  it should "drop bookkeeping for FlatBatchFlushComplete from a stale state root" taggedAs UnitTest in {
    val flatSlots = new FlatSlotStorage(EphemDataSource())
    val (coord, _) = newCoordWithFlatBatch(flatSlots, threshold = 1000)

    coord.underlyingActor.inFlightFlatBatches = 1
    val staleRoot = kec256(ByteString("a-stale-root"))

    coord ! Messages.FlatBatchFlushComplete(staleRoot, entryCount = 7, elapsedMs = 5L)

    coord.underlyingActor.inFlightFlatBatches shouldBe 0

    system.stop(coord)
  }

  it should "flush the accumulator before mutating stateRoot on StoragePivotRefreshed" taggedAs UnitTest in {
    val flatSlots = new FlatSlotStorage(EphemDataSource())
    val oldRoot = kec256(ByteString("old-root"))
    val newRoot = kec256(ByteString("new-root"))
    val (coord, _) = newCoordWithFlatBatch(flatSlots, threshold = 1000, stateRootArg = oldRoot)

    // Buffer some data while stateRoot == oldRoot.
    val (accountHash, slots) = fakeContract(seed = 7, slotsPerAccount = 4)
    coord.underlyingActor.writeSmallContractFlatOnly(accountHash, slots)
    coord.underlyingActor.pendingFlatBatchEntries shouldBe 4

    // Pivot refresh: must commit the accumulator THEN advance the root.
    coord ! Messages.StoragePivotRefreshed(newRoot)

    awaitAssert(coord.underlyingActor.inFlightFlatBatches shouldBe 0, max = 1.second)

    // Data made it to disk despite the pivot refresh.
    slots.foreach { case (slotHash, value) =>
      flatSlots.getSlot(accountHash, slotHash) shouldBe Some(value)
    }
    coord.underlyingActor.pendingFlatBatchAccounts shouldBe empty

    system.stop(coord)
  }

  it should "decrement in-flight count on FlatBatchFlushFailed and stay operational" taggedAs UnitTest in {
    val flatSlots = new FlatSlotStorage(EphemDataSource())
    val (coord, _) = newCoordWithFlatBatch(flatSlots, threshold = 1000)

    coord.underlyingActor.inFlightFlatBatches = 2

    coord ! Messages.FlatBatchFlushFailed(
      forStateRoot = kec256(ByteString("flat-batch-test-root")),
      entryCount = 11,
      error = "synthetic write failure"
    )

    coord.underlyingActor.inFlightFlatBatches shouldBe 1

    coord ! Messages.StorageGetProgress
    expectMsgType[Any](3.seconds)

    system.stop(coord)
  }
}
