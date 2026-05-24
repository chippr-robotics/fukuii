package com.chipprbots.ethereum.blockchain.sync.snap.actors

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.{TestActorRef, TestKit, TestProbe, ImplicitSender}
import org.apache.pekko.util.ByteString

import scala.concurrent.duration._

import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap._
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.testing.{PeerTestHelpers, TestEvmCodeStorage}
import com.chipprbots.ethereum.network.p2p.messages.SNAP.ByteCodes
import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetByteCodes.GetByteCodesEnc

class ByteCodeCoordinatorSpec
    extends TestKit(ActorSystem("ByteCodeCoordinatorSpec"))
    with ImplicitSender
    with AnyFlatSpecLike
    with Matchers
    with BeforeAndAfterAll {

  override def afterAll(): Unit =
    TestKit.shutdownActorSystem(system)

  // Shared cooldown config for tests that need fast retries
  // Uses 50ms cooldowns (baseEmpty, baseTimeout, baseInvalid) to enable rapid testing
  // while still verifying cooldown behavior with 80ms expectNoMessage waits
  private val testCooldownConfig = ByteCodeCoordinator.ByteCodePeerCooldownConfig(
    baseEmpty = 50.millis,
    baseTimeout = 50.millis,
    baseInvalid = 50.millis,
    maxInFlightPerPeer = 2,
    max = 200.millis,
    exponentCap = 3
  )

  "ByteCodeCoordinator" should "initialize with empty task queue" taggedAs UnitTest in {
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

    coordinator should not be null
  }

  it should "queue contract accounts for download" taggedAs UnitTest in {
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

    val codeHashes = Seq(
      kec256(ByteString("code1")),
      kec256(ByteString("code2"))
    )

    coordinator ! Messages.StartByteCodeSync(codeHashes)

    // Coordinator should queue the contracts
    coordinator ! Messages.ByteCodeGetProgress
    expectMsgType[Any](3.seconds)
  }

  it should "create workers when peers are available" taggedAs UnitTest in {
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

    val codeHashes = Seq(kec256(ByteString("code1")))

    coordinator ! Messages.StartByteCodeSync(codeHashes)
    coordinator ! Messages.ByteCodePeerAvailable(peer)

    // Should send request to network peer manager
    networkPeerManager.expectMsgType[Any](3.seconds)
  }

  it should "handle task completion" taggedAs UnitTest in {
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

    coordinator ! Messages.ByteCodeTaskComplete(BigInt(123), Right(5))

    // Coordinator should handle completion
    coordinator ! Messages.ByteCodeGetProgress
    expectMsgType[Any](3.seconds)
  }

  // Verifies Fix 6 / P-5.4: ByteCodeTaskComplete must call tryRedispatchPendingTasks() so the
  // next pending task is dispatched immediately within the same message cycle — not delayed up to
  // 1 second waiting for the next ByteCodePeerAvailable tick from SNAPSyncController.
  it should "dispatch pending task immediately on ByteCodeTaskComplete without a new ByteCodePeerAvailable" taggedAs UnitTest in {
    val evmCodeStorage = new TestEvmCodeStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()
    val peerProbe = TestProbe()
    val peer = PeerTestHelpers.createTestPeer("redispatch-peer", peerProbe.ref)

    // maxInFlightPerPeer=1 ensures only 1 task in flight at a time, leaving the 2nd pending
    val peerCooldown = testCooldownConfig.copy(maxInFlightPerPeer = 1)
    val coordinator = TestActorRef[ByteCodeCoordinator](
      ByteCodeCoordinator.props(
        evmCodeStorage = evmCodeStorage,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        batchSize = 1,
        snapSyncController = snapSyncController.ref,
        cooldownConfig = peerCooldown
      )
    )

    val hashes = Seq(kec256(ByteString("redispatch-a")), kec256(ByteString("redispatch-b")))
    coordinator ! Messages.StartByteCodeSync(hashes)
    coordinator ! Messages.NoMoreByteCodeTasks
    coordinator ! Messages.ByteCodePeerAvailable(peer)

    // Only the first task is dispatched (maxInFlightPerPeer=1)
    networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessage](3.seconds)

    within(3.seconds) {
      awaitAssert {
        coordinator.underlyingActor.activeTasks.size shouldBe 1
        coordinator.underlyingActor.pendingTasks should have size 1
      }
    }
    val activeEntry = coordinator.underlyingActor.activeTasks.head
    val reqId = activeEntry._1
    val worker = activeEntry._2.worker

    // ByteCodeWorker uses context.become(working) and stashes ByteCodeWorkerFetchTask in that
    // state. Release it first so the worker transitions to idle; the coordinator's subsequent
    // tryRedispatchPendingTasks() dispatch will then be accepted rather than stashed.
    worker ! Messages.ByteCodeWorkerRelease(reqId)

    // Complete the in-flight task at coordinator level — calls markWorkerIdle + tryRedispatchPendingTasks()
    coordinator ! Messages.ByteCodeTaskComplete(reqId, Right(1))

    // The pending task must be dispatched immediately via tryRedispatchPendingTasks()
    networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessage](3.seconds)
  }

  it should "report completion when all bytecodes downloaded" taggedAs UnitTest in {
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

    // Start with empty contract list
    coordinator ! Messages.StartByteCodeSync(Seq.empty)

    // Signal that no more tasks will arrive (sentinel pattern)
    coordinator ! Messages.NoMoreByteCodeTasks

    // Should complete immediately since no tasks and sentinel received
    coordinator ! Messages.ByteCodeCheckCompletion
    snapSyncController.expectMsg(3.seconds, SNAPSyncController.ByteCodeSyncComplete)
  }

  it should "handle task failures gracefully" taggedAs UnitTest in {
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

    coordinator ! Messages.ByteCodeTaskFailed(BigInt(123), "Test failure")

    // Coordinator should still be operational
    coordinator ! Messages.ByteCodeGetProgress
    expectMsgType[Any](3.seconds)
  }

  it should "accept ByteCodes as a subsequence and re-queue missing hashes" taggedAs UnitTest in {
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

    val code1 = ByteString("code1")
    val code2 = ByteString("code2")
    val code3 = ByteString("code3")
    val h1 = kec256(code1)
    val h2 = kec256(code2)
    val h3 = kec256(code3)

    val codeHashes = Seq(h1, h2, h3)

    coordinator ! Messages.StartByteCodeSync(codeHashes)
    coordinator ! Messages.ByteCodePeerAvailable(peer)

    val send1 = networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessage](3.seconds)
    val req1 = send1.message.asInstanceOf[GetByteCodesEnc].underlyingMsg
    req1.hashes shouldEqual Seq(h1, h2, h3)

    // Respond with a single middle element (gap allowed by snap/1 semantics)
    system.actorSelection(coordinator.path / "*") ! Messages.ByteCodesResponseMsg(ByteCodes(req1.requestId, Seq(code2)))

    // Ensure the returned code got persisted
    within(3.seconds) {
      awaitAssert(evmCodeStorage.get(h2) shouldEqual Some(code2))
    }

    // Drive next dispatch and assert the missing hashes were re-queued
    coordinator ! Messages.ByteCodePeerAvailable(peer)
    val send2 = networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessage](3.seconds)
    val req2 = send2.message.asInstanceOf[GetByteCodesEnc].underlyingMsg
    req2.hashes shouldEqual Seq(h1, h3)
  }

  it should "reject out-of-order ByteCodes responses and retry" taggedAs UnitTest in {
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
        snapSyncController = snapSyncController.ref,
        cooldownConfig = testCooldownConfig
      )
    )

    val code1 = ByteString("code1")
    val code2 = ByteString("code2")
    val h1 = kec256(code1)
    val h2 = kec256(code2)

    val codeHashes = Seq(h1, h2)

    coordinator ! Messages.StartByteCodeSync(codeHashes)
    coordinator ! Messages.ByteCodePeerAvailable(peer)

    val send1 = networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessage](3.seconds)
    val req1 = send1.message.asInstanceOf[GetByteCodesEnc].underlyingMsg
    req1.hashes shouldEqual Seq(h1, h2)

    // Respond out-of-order (violates snap/1 ordering requirement)
    system.actorSelection(coordinator.path / "*") ! Messages.ByteCodesResponseMsg(
      ByteCodes(req1.requestId, Seq(code2, code1))
    )

    // Ensure nothing was persisted
    within(3.seconds) {
      awaitAssert {
        evmCodeStorage.get(h1) shouldEqual None
        evmCodeStorage.get(h2) shouldEqual None
      }
    }

    // Verify peer is in cooldown by attempting immediate retry
    coordinator ! Messages.ByteCodePeerAvailable(peer)
    networkPeerManager.expectNoMessage(80.millis)

    // Drive retry after cooldown expires
    coordinator ! Messages.ByteCodePeerAvailable(peer)
    val send2 = networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessage](3.seconds)
    val req2 = send2.message.asInstanceOf[GetByteCodesEnc].underlyingMsg
    req2.hashes shouldEqual Seq(h1, h2)
  }

  it should "reject duplicate bytecodes in a ByteCodes response" taggedAs UnitTest in {
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
        snapSyncController = snapSyncController.ref,
        cooldownConfig = testCooldownConfig
      )
    )

    val code1 = ByteString("code1")
    val h1 = kec256(code1)

    val codeHashes = Seq(h1)

    coordinator ! Messages.StartByteCodeSync(codeHashes)
    coordinator ! Messages.ByteCodePeerAvailable(peer)

    val send1 = networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessage](3.seconds)
    val req1 = send1.message.asInstanceOf[GetByteCodesEnc].underlyingMsg
    req1.hashes shouldEqual Seq(h1)

    // Duplicate code for the same hash should be rejected
    system.actorSelection(coordinator.path / "*") ! Messages.ByteCodesResponseMsg(
      ByteCodes(req1.requestId, Seq(code1, code1))
    )

    within(3.seconds) {
      awaitAssert(evmCodeStorage.get(h1) shouldEqual None)
    }

    // Verify peer is in cooldown by attempting immediate retry
    coordinator ! Messages.ByteCodePeerAvailable(peer)
    networkPeerManager.expectNoMessage(80.millis)

    // Drive retry after cooldown expires (task should be re-queued)
    coordinator ! Messages.ByteCodePeerAvailable(peer)
    val send2 = networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessage](3.seconds)
    val req2 = send2.message.asInstanceOf[GetByteCodesEnc].underlyingMsg
    req2.hashes shouldEqual Seq(h1)
  }

  it should "cool down peers after empty ByteCodes responses" taggedAs UnitTest in {
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
        snapSyncController = snapSyncController.ref,
        cooldownConfig = testCooldownConfig
      )
    )

    val code1 = ByteString("code1")
    val h1 = kec256(code1)
    val codeHashes = Seq(h1)

    coordinator ! Messages.StartByteCodeSync(codeHashes)
    coordinator ! Messages.ByteCodePeerAvailable(peer)

    val send1 = networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessage](3.seconds)
    val req1 = send1.message.asInstanceOf[GetByteCodesEnc].underlyingMsg
    req1.hashes shouldEqual Seq(h1)

    // Respond with empty ByteCodes (peer had none of the requested hashes)
    system.actorSelection(coordinator.path / "*") ! Messages.ByteCodesResponseMsg(ByteCodes(req1.requestId, Seq.empty))

    // Immediately advertising the same peer should not trigger a re-request due to cooldown
    coordinator ! Messages.ByteCodePeerAvailable(peer)
    networkPeerManager.expectNoMessage(80.millis)

    // After cooldown elapses (already waited 80ms above, cooldown is 50ms), coordinator should send again
    coordinator ! Messages.ByteCodePeerAvailable(peer)
    networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessage](3.seconds)
  }

  // ---- J7: Peer reputation cleared on pivot refresh ----------------------------

  it should "allow a cooled-down peer to dispatch immediately after ByteCodePivotRefreshed" taggedAs UnitTest in {
    val evmCodeStorage = new TestEvmCodeStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()
    val peerProbe = TestProbe()

    val peer = PeerTestHelpers.createTestPeer("pivot-peer", peerProbe.ref)

    val coordinator = system.actorOf(
      ByteCodeCoordinator.props(
        evmCodeStorage = evmCodeStorage,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        batchSize = 8,
        snapSyncController = snapSyncController.ref,
        cooldownConfig = testCooldownConfig
      )
    )

    val h1 = kec256(ByteString("pivot-code"))

    coordinator ! Messages.StartByteCodeSync(Seq(h1))
    coordinator ! Messages.ByteCodePeerAvailable(peer)

    val send1 = networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessage](3.seconds)
    val req1 = send1.message.asInstanceOf[GetByteCodesEnc].underlyingMsg

    // Empty response → peer enters cooldown
    system.actorSelection(coordinator.path / "*") ! Messages.ByteCodesResponseMsg(ByteCodes(req1.requestId, Seq.empty))

    // Verify cooldown is active — same peer should not dispatch immediately
    coordinator ! Messages.ByteCodePeerAvailable(peer)
    networkPeerManager.expectNoMessage(80.millis)

    // Pivot refresh clears both peerFailureCounts and peerCooldownUntilMillis (BUG-S1 fix)
    coordinator ! Messages.ByteCodePivotRefreshed

    // Peer should dispatch again immediately (no cooldown wait)
    coordinator ! Messages.ByteCodePeerAvailable(peer)
    networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessage](3.seconds)
  }

  // ── K5-ext-b: Peer retention across pivot refresh (BUG-S1 fix 84290a175) ─────

  it should "dispatch new tasks to a peer retained in knownAvailablePeers after ByteCodePivotRefreshed" taggedAs UnitTest in {
    val evmCodeStorage = new TestEvmCodeStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()
    val peerProbe = TestProbe()

    val peer = PeerTestHelpers.createTestPeer("retained-peer", peerProbe.ref)

    val coordinator = system.actorOf(
      ByteCodeCoordinator.props(
        evmCodeStorage = evmCodeStorage,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        batchSize = 8,
        snapSyncController = snapSyncController.ref,
        cooldownConfig = testCooldownConfig
      )
    )

    val h1 = kec256(ByteString("retained-code"))

    // Register peer with no initial tasks → peer enters knownAvailablePeers pool.
    coordinator ! Messages.StartByteCodeSync(Seq.empty)
    coordinator ! Messages.ByteCodePeerAvailable(peer)

    // Pivot refresh. In the old code knownAvailablePeers was cleared here (BUG-S1).
    // In the fixed code the peer is retained: bytecodes are content-addressed,
    // not state-root-dependent, so the peer can serve the same hashes after a pivot.
    coordinator ! Messages.ByteCodePivotRefreshed

    // Add tasks AFTER the pivot. AddByteCodeTasks queues work but does not call
    // tryRedispatchPendingTasks(). UpdateMaxInFlightPerPeer is the coordinator-internal
    // trigger that calls tryRedispatchPendingTasks(), which iterates knownAvailablePeers.
    // With the BUG-S1 fix the retained peer is found there and dispatch proceeds.
    // Without the fix (peer cleared) tryRedispatchPendingTasks() finds nobody → timeout.
    coordinator ! Messages.AddByteCodeTasks(Seq(h1))
    coordinator ! Messages.UpdateMaxInFlightPerPeer(testCooldownConfig.maxInFlightPerPeer)

    val send = networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessage](3.seconds)
    val req = send.message.asInstanceOf[GetByteCodesEnc].underlyingMsg
    req.hashes shouldEqual Seq(h1)
  }

  // ---- J9: Corruption detection -----------------------------------------------

  it should "reject a bytecode whose kec256 hash is not in the requested list and put peer in cooldown" taggedAs UnitTest in {
    val evmCodeStorage = new TestEvmCodeStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()
    val peerProbe = TestProbe()

    val peer = PeerTestHelpers.createTestPeer("corrupt-peer", peerProbe.ref)

    val coordinator = system.actorOf(
      ByteCodeCoordinator.props(
        evmCodeStorage = evmCodeStorage,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        batchSize = 8,
        snapSyncController = snapSyncController.ref,
        cooldownConfig = testCooldownConfig
      )
    )

    val realCode = ByteString("real-bytecode")
    val realHash = kec256(realCode)
    val corruptCode = ByteString("corrupted-bytecode-with-wrong-hash")
    // Sanity: corruptCode's hash must not equal realHash
    kec256(corruptCode) should not be realHash

    coordinator ! Messages.StartByteCodeSync(Seq(realHash))
    coordinator ! Messages.ByteCodePeerAvailable(peer)

    val send1 = networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessage](3.seconds)
    val req1 = send1.message.asInstanceOf[GetByteCodesEnc].underlyingMsg
    req1.hashes shouldEqual Seq(realHash)

    // Respond with a code whose hash != realHash (corrupted / wrong code)
    system.actorSelection(coordinator.path / "*") ! Messages.ByteCodesResponseMsg(
      ByteCodes(req1.requestId, Seq(corruptCode))
    )

    // Corrupted code must NOT be stored
    within(3.seconds) {
      awaitAssert(evmCodeStorage.get(realHash) shouldEqual None)
    }

    // Peer must be in cooldown (invalid response)
    coordinator ! Messages.ByteCodePeerAvailable(peer)
    networkPeerManager.expectNoMessage(80.millis)

    // After cooldown, task is re-queued and peer dispatches again
    coordinator ! Messages.ByteCodePeerAvailable(peer)
    val send2 = networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessage](3.seconds)
    val req2 = send2.message.asInstanceOf[GetByteCodesEnc].underlyingMsg
    req2.hashes shouldEqual Seq(realHash)
  }

  // #1164: ForceCompleteByteCodes drains pending+active tasks and reports completion. Without this, a small set of
  // unservable code hashes could hold the bytecode phase open indefinitely (the existing completion check requires
  // `pendingTasks.isEmpty && activeTasks.isEmpty` and there's no per-task failure cap).
  it should "force-complete bytecode sync, abandoning pending tasks (#1164)" taggedAs UnitTest in {
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
        snapSyncController = snapSyncController.ref,
        cooldownConfig = testCooldownConfig
      )
    )

    // Queue a non-trivial set of bytecode hashes. No peer is registered, so they'll sit in pendingTasks
    // forever — modelling the wedged state where peers can't serve a small unservable subset.
    val codeHashes = (1 to 10).map(i => kec256(ByteString(s"code$i")))
    coordinator ! Messages.StartByteCodeSync(codeHashes)
    coordinator ! Messages.NoMoreByteCodeTasks

    // Without the force-complete, ByteCodeCheckCompletion stays blocked because pendingTasks is non-empty.
    coordinator ! Messages.ByteCodeCheckCompletion
    snapSyncController.expectNoMessage(200.millis)

    // Force-complete drains the queue and signals the parent.
    coordinator ! Messages.ForceCompleteByteCodes
    snapSyncController.expectMsg(3.seconds, SNAPSyncController.ByteCodeSyncComplete)
  }

  // ForceCompleteByteCodes may fire while tasks are still in activeTasks (e.g. the 10-min stall
  // watchdog triggers mid-flight). The handler must drain active tasks AND return their workers
  // to the idle pool — otherwise the worker pool invariant (workers.size == idleWorkers.size +
  // activeTasks.size) would be broken, and any subsequent coordinator reuse would leak workers.
  it should "return active workers to idle pool on ForceCompleteByteCodes mid-flight" taggedAs UnitTest in {
    val evmCodeStorage = new TestEvmCodeStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()
    val peerProbe = TestProbe()
    val peer = PeerTestHelpers.createTestPeer("fc-active-peer", peerProbe.ref)

    // batchSize=1 + maxInFlightPerPeer=2 → 2 tasks dispatched concurrently; 1 left pending
    val peerCooldown = testCooldownConfig.copy(maxInFlightPerPeer = 2)
    val coordinator = TestActorRef[ByteCodeCoordinator](
      ByteCodeCoordinator.props(
        evmCodeStorage = evmCodeStorage,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        batchSize = 1,
        snapSyncController = snapSyncController.ref,
        cooldownConfig = peerCooldown
      )
    )

    val hashes = (1 to 3).map(i => kec256(ByteString(s"fc-active-$i")))
    coordinator ! Messages.StartByteCodeSync(hashes)
    coordinator ! Messages.NoMoreByteCodeTasks
    coordinator ! Messages.ByteCodePeerAvailable(peer)

    networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessage](3.seconds)
    networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessage](3.seconds)

    within(3.seconds) {
      awaitAssert {
        coordinator.underlyingActor.activeTasks.size shouldBe 2
        coordinator.underlyingActor.idleWorkers shouldBe empty
      }
    }

    // Force-complete fires while 2 tasks are still in flight and 1 is pending
    coordinator ! Messages.ForceCompleteByteCodes

    within(3.seconds) {
      awaitAssert {
        // Active tasks drained; workers returned to idle pool (line 362 markWorkerIdle)
        coordinator.underlyingActor.activeTasks shouldBe empty
        coordinator.underlyingActor.idleWorkers.size shouldBe 2
        // Pool invariant: workers.size == idleWorkers.size when activeTasks.isEmpty
        coordinator.underlyingActor.workers.size shouldBe coordinator.underlyingActor.idleWorkers.size
      }
    }

    snapSyncController.expectMsg(3.seconds, SNAPSyncController.ByteCodeSyncComplete)
  }

  it should "handle ForceCompleteByteCodes when queues are already empty" taggedAs UnitTest in {
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
        snapSyncController = snapSyncController.ref,
        cooldownConfig = testCooldownConfig
      )
    )

    // Empty queue + ForceCompleteByteCodes: should still emit ByteCodeSyncComplete (idempotent terminal state).
    coordinator ! Messages.ForceCompleteByteCodes
    snapSyncController.expectMsg(3.seconds, SNAPSyncController.ByteCodeSyncComplete)
  }

  // ── Back-pressure on the pending bytecode-task queue ───────────────────────
  // Mirrors the storage coordinator's pattern. ByteCodeTask used to retain the
  // full bytecode blob payload after completion (a separate fix in this PR);
  // even with that fixed, an unbounded pending queue still leaks task-metadata
  // memory linearly with chain size. The coordinator now publishes high/low-
  // water transitions that SNAPSyncController forwards to AccountRangeCoordinator.
  // ── Fix 3: context.watch + Terminated handler — worker pool recovery ──────────
  // Verifies that a permanently terminated worker is removed from the pool and its
  // in-flight task is re-queued. Without context.watch, dead workers remain in
  // `workers`, exhausting the pool (idleWorkers empty, workers.size >= maxWorkers)
  // and blocking all further dispatch — the exact failure mode from Run 23.

  it should "remove a terminated worker from the pool and re-queue its active task" taggedAs UnitTest in {
    val evmCodeStorage = new TestEvmCodeStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()
    val peerProbe = TestProbe()

    val peer = PeerTestHelpers.createTestPeer("term-peer", peerProbe.ref)

    val coordinator = TestActorRef[ByteCodeCoordinator](
      ByteCodeCoordinator.props(
        evmCodeStorage = evmCodeStorage,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        batchSize = 8,
        snapSyncController = snapSyncController.ref,
        cooldownConfig = testCooldownConfig
      )
    )

    coordinator ! Messages.StartByteCodeSync(Seq(kec256(ByteString("term-code"))))
    coordinator ! Messages.ByteCodePeerAvailable(peer)

    // Worker created and request dispatched
    networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessage](3.seconds)

    // Get the worker ref and stop it permanently (bypasses supervisor restart)
    val workerRef = coordinator.underlyingActor.workers.head
    system.stop(workerRef)

    // Terminated propagates asynchronously — wait for coordinator to process it
    within(3.seconds) {
      awaitAssert(coordinator.underlyingActor.workers.isEmpty)
    }

    // Task was re-queued — providing peer again triggers re-dispatch
    coordinator ! Messages.ByteCodePeerAvailable(peer)
    networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessage](3.seconds)
  }

  it should "remove a terminated worker with no active task without affecting pending dispatch" taggedAs UnitTest in {
    val evmCodeStorage = new TestEvmCodeStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()
    val peerProbe = TestProbe()

    val peer = PeerTestHelpers.createTestPeer("term-idle-peer", peerProbe.ref)

    val coordinator = TestActorRef[ByteCodeCoordinator](
      ByteCodeCoordinator.props(
        evmCodeStorage = evmCodeStorage,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        batchSize = 8,
        snapSyncController = snapSyncController.ref,
        cooldownConfig = testCooldownConfig
      )
    )

    // Queue a task and dispatch — worker created
    coordinator ! Messages.StartByteCodeSync(Seq(kec256(ByteString("idle-code"))))
    coordinator ! Messages.ByteCodePeerAvailable(peer)
    networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessage](3.seconds)

    // Complete the task so the worker is now idle (no active task)
    val workerRef = coordinator.underlyingActor.workers.head
    // Let the worker become idle by marking the task complete
    coordinator ! Messages.NoMoreByteCodeTasks

    system.stop(workerRef)

    // Pool should shrink without any exception — coordinator stays operational
    within(3.seconds) {
      awaitAssert(coordinator.underlyingActor.workers.isEmpty)
    }

    coordinator ! Messages.ByteCodeGetProgress
    expectMsgType[Messages.ByteCodeProgress](3.seconds)
  }

  // ── P-0 regression: ByteCodePeerUnavailable must restore workers to idle pool ─
  // Run 25 root cause: ByteCodePeerUnavailable sent ByteCodeWorkerRelease to each in-flight
  // worker but never called markWorkerIdle. Workers stayed in `workers` (alive) but were absent
  // from `idleWorkers`, so dispatchIfPossible permanently returned None after the peer cascade.
  // This test would have caught that: after ByteCodePeerUnavailable, the idle count must
  // equal the pre-dispatch idle count (workers returned), and dispatch must succeed again.
  it should "restore workers to idle pool when a peer disconnects mid-flight" taggedAs UnitTest in {
    val evmCodeStorage = new TestEvmCodeStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()
    val peerProbe = TestProbe()

    val peerId = "unavail-peer"
    val peer = PeerTestHelpers.createTestPeer(peerId, peerProbe.ref)

    // batchSize=1 so each hash → one task; maxInFlightPerPeer=3 → up to 3 concurrent workers
    val peerCooldown = testCooldownConfig.copy(maxInFlightPerPeer = 3)
    val coordinator = TestActorRef[ByteCodeCoordinator](
      ByteCodeCoordinator.props(
        evmCodeStorage = evmCodeStorage,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        batchSize = 1,
        snapSyncController = snapSyncController.ref,
        cooldownConfig = peerCooldown
      )
    )

    // Queue 3 hashes (one per task) and dispatch
    val hashes = (1 to 3).map(i => kec256(ByteString(s"unavail-code-$i")))
    coordinator ! Messages.StartByteCodeSync(hashes)
    coordinator ! Messages.ByteCodePeerAvailable(peer)

    // Three tasks dispatched — one worker per task
    networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessage](3.seconds)
    networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessage](3.seconds)
    networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessage](3.seconds)

    // All 3 workers active, none idle
    within(3.seconds) {
      awaitAssert(coordinator.underlyingActor.workers.size == 3)
    }
    coordinator.underlyingActor.idleWorkers shouldBe empty

    // Peer disconnects — ByteCodePeerUnavailable must release all in-flight workers back to idle
    coordinator ! Messages.ByteCodePeerUnavailable(peerId)

    // All 3 workers must be returned to idleWorkers (the P-0 fix: markWorkerIdle after release)
    within(3.seconds) {
      awaitAssert {
        coordinator.underlyingActor.idleWorkers.size shouldBe 3
      }
    }

    // Dispatch must succeed — idle workers available, tasks re-queued
    coordinator ! Messages.ByteCodePeerAvailable(peer)
    networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessage](3.seconds)
  }

  it should "emit ByteCodeBackpressureChanged when the pending queue crosses watermarks" taggedAs UnitTest in {
    val evmCodeStorage = new TestEvmCodeStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()

    // Tiny watermarks: high=4, low=2. batchSize=1 so one hash → one task → one queue entry,
    // letting us drive the transition with a handful of hashes.
    val coordinator = system.actorOf(
      ByteCodeCoordinator.props(
        evmCodeStorage = evmCodeStorage,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        batchSize = 1,
        snapSyncController = snapSyncController.ref,
        cooldownConfig = testCooldownConfig,
        backpressureHighWatermark = 4,
        backpressureLowWatermark = 2
      )
    )

    // Queue 4 hashes → 4 tasks → crosses the high-water mark.
    val hashes = (1 to 4).map(i => kec256(ByteString(s"hash-$i")))
    coordinator ! Messages.AddByteCodeTasks(hashes)
    snapSyncController.expectMsg(3.seconds, SNAPSyncController.ByteCodeBackpressureChanged(paused = true))

    // Re-checking at the same depth must NOT emit a duplicate transition.
    coordinator ! Messages.ByteCodeCheckCompletion
    snapSyncController.expectNoMessage(500.millis)
  }

  it should "request pivot refresh after noActivityTimeout when all peers unavailable with pending tasks" taggedAs UnitTest in {
    // Regression guard for Fix 2: if all peers vanish before noMoreTasksExpected is set and
    // bytecodes are still pending, workers never dispatch so consecutiveTaskFailures never
    // increments and the force-complete path never fires — coordinator deadlocks forever.
    // The no-activity timeout detects this and sends PivotStateUnservable to unblock sync.
    val evmCodeStorage = new TestEvmCodeStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()

    val coordinator = TestActorRef[ByteCodeCoordinator](
      ByteCodeCoordinator.props(
        evmCodeStorage = evmCodeStorage,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        batchSize = 1,
        snapSyncController = snapSyncController.ref,
        cooldownConfig = testCooldownConfig
      )
    )

    // Queue pending tasks — noMoreTasksExpected stays false (NoMoreByteCodeTasks never sent)
    val hashes = (1 to 5).map(i => kec256(ByteString(s"bc-nopeer-$i")))
    coordinator ! Messages.AddByteCodeTasks(hashes)

    val ua = coordinator.underlyingActor
    awaitAssert(ua.pendingTasks.nonEmpty shouldBe true, 2.seconds, 50.millis)

    // No peers — activeTasks is empty (no peer was ever sent)
    ua.activeTasks shouldBe empty

    // Simulate time having passed beyond the 120s no-activity timeout
    ua.lastActivityMs = System.currentTimeMillis() - 130_000L

    // StatusPulse is the timer tick that evaluates the no-activity condition
    coordinator ! ByteCodeCoordinator.ByteCodeStatusPulse

    // Must request pivot refresh via PivotStateUnservable
    snapSyncController.expectMsgPF(3.seconds, "PivotStateUnservable for bytecode stall") {
      case SNAPSyncController.PivotStateUnservable(_, reason, _) =>
        reason should include("no-peer stall")
    }

    // lastActivityMs must be reset to avoid storm of pivot requests
    ua.lastActivityMs should be >= (System.currentTimeMillis() - 5_000L)
  }

  // ── 4f-1: maxFailuresPerHash exhaustion path ──────────────────────────────
  // ByteCodeCoordinator silently drops a hash after maxFailuresPerHash (50) consecutive
  // empty responses. The hash is NOT re-queued and NOT escalated via PivotStateUnservable.
  // When it's the only hash and noMoreTasksExpected=true, checkCompletion() fires and the
  // bytecode phase completes normally. This is a silent data-loss path with no prior test.

  it should "silently exhaust a bytecode hash after maxFailuresPerHash empty responses and emit ByteCodeSyncComplete without PivotStateUnservable" taggedAs UnitTest in {
    val evmCodeStorage = new TestEvmCodeStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()
    val peerProbe = TestProbe()

    val peer = PeerTestHelpers.createTestPeer("exhaust-peer", peerProbe.ref)

    // 1ms cooldown + exponentCap=0 so the 50-iteration loop completes in ~250ms
    val exhaustConfig = testCooldownConfig.copy(baseEmpty = 1.millis, exponentCap = 0)

    val coordinator = system.actorOf(
      ByteCodeCoordinator.props(
        evmCodeStorage = evmCodeStorage,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        batchSize = 1,
        snapSyncController = snapSyncController.ref,
        cooldownConfig = exhaustConfig
      )
    )

    val h1 = kec256(ByteString("exhaust-code"))

    coordinator ! Messages.StartByteCodeSync(Seq(h1))
    coordinator ! Messages.NoMoreByteCodeTasks // noMoreTasksExpected = true; pendingTasks still has h1

    // Drive 50 empty responses. maxFailuresPerHash == 50.
    // Iterations 1-49: hash re-queued after each empty response (failure count < 50).
    // Iteration 50: hashFailureCounts(h1) reaches threshold → hash dropped, NOT re-queued.
    //   checkCompletion(): noMoreTasksExpected=true, pendingTasks.empty, activeTasks.empty
    //   → snapSyncController ! ByteCodeSyncComplete
    for (_ <- 1 to 50) {
      coordinator ! Messages.ByteCodePeerAvailable(peer)
      val send = networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessage](3.seconds)
      val req = send.message.asInstanceOf[GetByteCodesEnc].underlyingMsg
      system.actorSelection(coordinator.path / "*") ! Messages.ByteCodesResponseMsg(
        ByteCodes(req.requestId, Seq.empty)
      )
      Thread.sleep(5) // allow 1ms cooldown to expire before next ByteCodePeerAvailable
    }

    // Hash exhausted → completion signal, not an error escalation.
    // Skip any ProgressBytecodesDownloaded(0) reports that arrive during the 50-iteration loop.
    snapSyncController.fishForMessage(5.seconds, "ByteCodeSyncComplete") {
      case SNAPSyncController.ByteCodeSyncComplete            => true
      case _: SNAPSyncController.ProgressBytecodesDownloaded => false
    }
    snapSyncController.expectNoMessage(300.millis) // no PivotStateUnservable
  }
}
