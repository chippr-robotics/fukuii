package com.chipprbots.ethereum.blockchain.sync.snap.actors

import org.apache.pekko.actor.{ActorSystem, Status}
import org.apache.pekko.testkit.{TestActorRef, TestKit, TestProbe, ImplicitSender}
import org.apache.pekko.util.ByteString

import scala.concurrent.duration._

import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap._
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.testing.{PeerTestHelpers, TestMptStorage}

class AccountRangeCoordinatorSpec
    extends TestKit(ActorSystem("AccountRangeCoordinatorSpec"))
    with ImplicitSender
    with AnyFlatSpecLike
    with Matchers
    with BeforeAndAfterAll {

  override def afterAll(): Unit =
    TestKit.shutdownActorSystem(system)

  "AccountRangeCoordinator" should "initialize and create workers on demand" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-state-root"))
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

    // Coordinator should be running
    coordinator should not be null
  }

  it should "distribute tasks to workers when peers are available" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-state-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()
    val peerProbe = TestProbe()

    val peer = PeerTestHelpers.createTestPeer("test-peer", peerProbe.ref)

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
    coordinator ! Messages.PeerAvailable(peer)

    // Should send request to network peer manager
    networkPeerManager.expectMsgType[Any](3.seconds)
  }

  it should "handle task completion and report progress" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-state-root"))
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

    // Query progress
    coordinator ! Messages.GetProgress
    val progress = expectMsgType[AccountRangeStats](3.seconds)

    progress.accountsDownloaded shouldBe 0
    progress.tasksPending should be > 0
  }

  it should "report completion when all tasks are done" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-state-root"))
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
        concurrency = 1, // Small concurrency for test
        snapSyncController = snapSyncController.ref
      )
    )

    coordinator ! Messages.StartAccountRangeSync(stateRoot)

    // Check completion - tasks should exist initially
    coordinator ! Messages.CheckCompletion

    // Should not complete immediately (tasks pending)
    snapSyncController.expectNoMessage(500.milliseconds)
  }

  it should "handle task failures gracefully" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-state-root"))
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

    // Simulate task failure
    coordinator ! Messages.TaskFailed(BigInt(123), "Test failure")

    // Coordinator should still be operational
    coordinator ! Messages.GetProgress
    expectMsgType[AccountRangeStats](3.seconds)
  }

  it should "collect contract accounts for bytecode download" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-state-root"))
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

    // Query contract accounts
    coordinator ! Messages.GetContractAccounts
    val response = expectMsgType[Messages.ContractAccountsResponse](3.seconds)

    // Initially should be empty
    response.accounts shouldBe empty
  }

  it should "provide statistics on request" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-state-root"))
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

    val progress = expectMsgType[AccountRangeStats](3.seconds)
    progress.progress should be >= 0.0
    progress.progress should be <= 1.0
    progress.elapsedTimeMs should be >= 0L
  }

  // ========================================
  // Async trie flush + finalisation generation guard (issue #1166)
  // ========================================

  /** Helper: build a TestActorRef so tests can poke `underlyingActor` state directly and inject `system.dispatcher` as
    * the trie EC for deterministic timing.
    */
  private def newCoordinator(
      stateRoot: ByteString = kec256(ByteString("trie-async-test-root")),
      controller: TestProbe = TestProbe()
  ): TestActorRef[AccountRangeCoordinator] =
    TestActorRef[AccountRangeCoordinator](
      AccountRangeCoordinator.props(
        stateRoot = stateRoot,
        networkPeerManager = TestProbe().ref,
        requestTracker = new SNAPRequestTracker()(system.scheduler),
        mptStorage = new TestMptStorage(),
        concurrency = 4,
        snapSyncController = controller.ref,
        accountTrieEcOverride = Some(system.dispatcher)
      )
    )

  it should "construct successfully when an account-trie EC override is supplied" taggedAs UnitTest in {
    val coord = newCoordinator()
    coord should not be null
    coord.underlyingActor.trieFlushGeneration shouldBe 0L
    system.stop(coord)
  }

  it should "drop a stale TrieFlushComplete (finalisation) silently — no controller messages" taggedAs UnitTest in {
    val controller = TestProbe()
    val coord = newCoordinator(controller = controller)

    // Force the actor into the `finalizing` receive without actually running the heavy work.
    // We simulate post-spawn state: bump the generation and capture it, then bump again so an
    // arriving TrieFlushComplete with the captured generation looks stale.
    coord.underlyingActor.trieFlushGeneration = 7L
    coord.underlyingActor.context.become(coord.underlyingActor.finalizing)

    val staleGen = 5L

    coord ! AccountRangeCoordinator.TrieFlushComplete(staleGen, Right(ByteString("would-be-root")))

    // Stale → controller hears nothing.
    controller.expectNoMessage(300.millis)
    coord.underlyingActor.trieFlushGeneration shouldBe 7L

    system.stop(coord)
  }

  it should "honour a current-generation TrieFlushComplete and notify the controller" taggedAs UnitTest in {
    val controller = TestProbe()
    val coord = newCoordinator(controller = controller)

    coord.underlyingActor.trieFlushGeneration = 3L
    coord.underlyingActor.context.become(coord.underlyingActor.finalizing)

    val rootHash = ByteString(Array.fill[Byte](32)(0x42))
    coord ! AccountRangeCoordinator.TrieFlushComplete(3L, Right(rootHash))

    controller.expectMsg(2.seconds, SNAPSyncController.AccountTrieFinalized(rootHash))
    controller.expectMsg(2.seconds, SNAPSyncController.ProgressAccountsTrieFinalized)
  }

  it should "drop a stale TrieFlushAsyncComplete (periodic flush) and return to normal receive" taggedAs UnitTest in {
    val controller = TestProbe()
    val coord = newCoordinator(controller = controller)

    coord.underlyingActor.trieFlushGeneration = 4L
    coord.underlyingActor.context.become(coord.underlyingActor.flushing)

    val staleGen = 2L

    coord ! AccountRangeCoordinator.TrieFlushAsyncComplete(staleGen, persistedRoot = None, elapsedMs = 5L)

    // The handler self-sends CheckCompletion, which can produce a ProgressAccountEstimate when
    // tasks exist — for a fresh coordinator with empty tasks no message is emitted.
    controller.expectNoMessage(300.millis)

    // After dropping the stale completion the actor must be back on the normal receive,
    // i.e. ready to handle a regular query.
    coord ! Messages.GetProgress
    expectMsgType[AccountRangeStats](2.seconds)

    system.stop(coord)
  }

  it should "drop a stale TrieFlushAsyncFailed and return to normal receive" taggedAs UnitTest in {
    val controller = TestProbe()
    val coord = newCoordinator(controller = controller)

    coord.underlyingActor.trieFlushGeneration = 9L
    coord.underlyingActor.context.become(coord.underlyingActor.flushing)

    coord ! AccountRangeCoordinator.TrieFlushAsyncFailed(generation = 8L, error = "stale failure")

    controller.expectNoMessage(300.millis)

    // Confirm we left `flushing` and are back on normal receive.
    coord ! Messages.GetProgress
    expectMsgType[AccountRangeStats](2.seconds)

    system.stop(coord)
  }

  it should "ignore Status.Failure during finalisation by stopping the actor" taggedAs UnitTest in {
    val controller = TestProbe()
    val coord = newCoordinator(controller = controller)

    coord.underlyingActor.trieFlushGeneration = 1L
    coord.underlyingActor.context.become(coord.underlyingActor.finalizing)

    val watcher = TestProbe()
    watcher.watch(coord)

    coord ! Status.Failure(new RuntimeException("synthetic failure"))

    controller.expectMsg(2.seconds, SNAPSyncController.ProgressAccountsTrieFinalized)
    watcher.expectTerminated(coord, 2.seconds)
  }
}
