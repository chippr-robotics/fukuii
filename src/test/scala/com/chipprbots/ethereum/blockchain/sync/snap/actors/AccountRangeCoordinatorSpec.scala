package com.chipprbots.ethereum.blockchain.sync.snap.actors

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.util.ByteString

import scala.concurrent.Await
import scala.concurrent.duration.*

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap.*
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.p2p.messages.SNAP.AccountRange
import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetAccountRange.GetAccountRangeEnc
import com.chipprbots.ethereum.testing.PeerTestHelpers
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.testing.TestMptStorage

/** AccountRangeCoordinator is a Typed actor (Group S3). These tests run in a Classic ActorSystem so they can use the
  * established `system.actorOf` / `expectMsg` machinery against the coordinator and its (Typed) worker children; the
  * coordinator is spawned through `PropsAdapter` to bridge the Classic system to the Typed Behavior. Mirrors the
  * `.props(...)` factory the actor previously exposed, and follows the ByteCodeCoordinatorSpec /
  * StorageRangeCoordinatorSpec precedent.
  *
  * White-box tests that previously poked `coordinator.underlyingActor` internal state (strike maps, `taskStackTries`,
  * `pendingTasks`, `statelessPeers`, the `finalizing` `context.become`, and direct `seedActiveTask` injection) were
  * dropped: a Typed `Behavior` exposes no `underlyingActor`. The behavioral coverage that survives is asserted through
  * observable effects on the `networkPeerManager` and `snapSyncController` probes — the same approach used in BCC/SRC.
  */
class AccountRangeCoordinatorSpec extends ScalaTestWithActorTestKit() with AnyFlatSpecLike with Matchers:

  implicit private val classicSystem: org.apache.pekko.actor.ActorSystem = system.classicSystem
  private val statusProbe = testKit.createTestProbe[AccountRangeStats]()

  private def arcProps(
      stateRoot: ByteString,
      networkPeerManager: org.apache.pekko.actor.typed.ActorRef[NetworkPeerManagerActor.Command],
      requestTracker: SNAPRequestTracker,
      mptStorage: TestMptStorage,
      concurrency: Int,
      snapSyncController: org.apache.pekko.actor.typed.ActorRef[SNAPSyncController.Command],
      resumeProgress: Map[ByteString, ByteString] = Map.empty,
      initialMaxInFlightPerPeer: Int = 5
  ): org.apache.pekko.actor.typed.ActorRef[AccountRangeCoordinator.Command] =
    testKit.spawn(
      AccountRangeCoordinator(
        stateRoot = stateRoot,
        networkPeerManager = networkPeerManager,
        requestTracker = requestTracker,
        mptStorage = mptStorage,
        concurrency = concurrency,
        snapSyncController = snapSyncController,
        resumeProgress = resumeProgress,
        initialMaxInFlightPerPeer = initialMaxInFlightPerPeer,
        accountTrieEcOverride = Some(classicSystem.dispatcher)
      )
    )

  /** Resolve the coordinator's single (concurrency = 1) worker child via actorSelection; returns a Typed ref so sends
    * are compile-time checked. Replaces the former `coordinator.underlyingActor.activeTasks(reqId)._2`, which is
    * unavailable on the Typed coordinator.
    */
  private def resolveWorkerChild(
      coordinator: org.apache.pekko.actor.typed.ActorRef[?]
  ): org.apache.pekko.actor.typed.ActorRef[AccountRangeCoordinator.WorkerMessage] =
    Await
      .result(
        classicSystem.actorSelection(coordinator.path / "*").resolveOne(3.seconds),
        3.seconds
      )
      .toTyped[AccountRangeCoordinator.WorkerMessage]

  "AccountRangeCoordinator" should "initialize and create workers on demand" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-state-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(classicSystem.scheduler)
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()

    val coordinator = arcProps(
      stateRoot = stateRoot,
      networkPeerManager = networkPeerManager.ref,
      requestTracker = requestTracker,
      mptStorage = storage,
      concurrency = 4,
      snapSyncController = snapSyncController.ref
    )

    coordinator ! AccountRangeCoordinator.StartAccountRangeSync(stateRoot)

    coordinator should not be null
  }

  it should "distribute tasks to workers when peers are available" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-state-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(classicSystem.scheduler)
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()
    val peerProbe = testKit.createTestProbe[Any]()

    val peer = PeerTestHelpers.createTestPeer("test-peer", peerProbe.ref.toClassic)

    val coordinator = arcProps(
      stateRoot = stateRoot,
      networkPeerManager = networkPeerManager.ref,
      requestTracker = requestTracker,
      mptStorage = storage,
      concurrency = 4,
      snapSyncController = snapSyncController.ref
    )

    coordinator ! AccountRangeCoordinator.StartAccountRangeSync(stateRoot)
    coordinator ! AccountRangeCoordinator.PeerAvailable(peer)

    networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd]
  }

  it should "handle task completion and report progress" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-state-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(classicSystem.scheduler)
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()

    val coordinator = arcProps(
      stateRoot = stateRoot,
      networkPeerManager = networkPeerManager.ref,
      requestTracker = requestTracker,
      mptStorage = storage,
      concurrency = 4,
      snapSyncController = snapSyncController.ref
    )

    coordinator ! AccountRangeCoordinator.StartAccountRangeSync(stateRoot)

    coordinator ! AccountRangeCoordinator.AccountGetProgress(statusProbe.ref)
    val progress = statusProbe.expectMessageType[AccountRangeStats]

    progress.accountsDownloaded shouldBe 0
    progress.tasksPending should be > 0
  }

  it should "report completion when all tasks are done" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-state-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(classicSystem.scheduler)
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()

    val coordinator = arcProps(
      stateRoot = stateRoot,
      networkPeerManager = networkPeerManager.ref,
      requestTracker = requestTracker,
      mptStorage = storage,
      concurrency = 1, // Small concurrency for test
      snapSyncController = snapSyncController.ref
    )

    coordinator ! AccountRangeCoordinator.StartAccountRangeSync(stateRoot)
    coordinator ! AccountRangeCoordinator.CheckCompletion

    // Should not complete immediately (tasks pending)
    snapSyncController.expectNoMessage(500.milliseconds)
  }

  it should "handle task failures gracefully" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-state-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(classicSystem.scheduler)
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()

    val coordinator = arcProps(
      stateRoot = stateRoot,
      networkPeerManager = networkPeerManager.ref,
      requestTracker = requestTracker,
      mptStorage = storage,
      concurrency = 4,
      snapSyncController = snapSyncController.ref
    )

    coordinator ! AccountRangeCoordinator.StartAccountRangeSync(stateRoot)
    coordinator ! AccountRangeCoordinator.TaskFailed(BigInt(123), "Test failure")

    // Coordinator should still be operational
    coordinator ! AccountRangeCoordinator.AccountGetProgress(statusProbe.ref)
    statusProbe.expectMessageType[AccountRangeStats]
  }

  it should "collect contract accounts for bytecode download" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-state-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(classicSystem.scheduler)
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()

    val coordinator = arcProps(
      stateRoot = stateRoot,
      networkPeerManager = networkPeerManager.ref,
      requestTracker = requestTracker,
      mptStorage = storage,
      concurrency = 4,
      snapSyncController = snapSyncController.ref
    )

    coordinator ! AccountRangeCoordinator.StartAccountRangeSync(stateRoot)

    val contractProbe = testKit.createTestProbe[AccountRangeCoordinator.ContractAccountsResponse]()
    coordinator ! AccountRangeCoordinator.AccountGetContractAccounts(contractProbe.ref)
    val response = contractProbe.expectMessageType[AccountRangeCoordinator.ContractAccountsResponse]

    response.accounts shouldBe empty
  }

  it should "provide statistics on request" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-state-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(classicSystem.scheduler)
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()

    val coordinator = arcProps(
      stateRoot = stateRoot,
      networkPeerManager = networkPeerManager.ref,
      requestTracker = requestTracker,
      mptStorage = storage,
      concurrency = 4,
      snapSyncController = snapSyncController.ref
    )

    coordinator ! AccountRangeCoordinator.StartAccountRangeSync(stateRoot)
    coordinator ! AccountRangeCoordinator.AccountGetProgress(statusProbe.ref)

    val progress = statusProbe.expectMessageType[AccountRangeStats]
    progress.progress should be >= 0.0
    progress.progress should be <= 1.0
    progress.elapsedTimeMs should be >= 0L
  }

  // ── K5-ext-a: Empty account range completion --------------------------------

  it should "complete task on proof-only empty account range response" taggedAs UnitTest in {
    val stateRoot = ByteString(MerklePatriciaTrie.EmptyRootHash)
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(classicSystem.scheduler)
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()
    val peerProbe = testKit.createTestProbe[Any]()

    val peer = PeerTestHelpers.createTestPeer("empty-range-peer", peerProbe.ref.toClassic)

    val coordinator = arcProps(
      stateRoot = stateRoot,
      networkPeerManager = networkPeerManager.ref,
      requestTracker = requestTracker,
      mptStorage = storage,
      concurrency = 1,
      snapSyncController = snapSyncController.ref
    )

    coordinator ! AccountRangeCoordinator.StartAccountRangeSync(stateRoot)
    coordinator ! AccountRangeCoordinator.PeerAvailable(peer)

    // Worker dispatches a GetAccountRange — consume to keep the probe clean.
    // SNAPRequestTracker starts at nextRequestId=1, so the first task is requestId=BigInt(1).
    networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd]

    // Simulate what the AccountRangeWorker sends back when it verifies a proof-only empty AccountRange.
    coordinator ! AccountRangeCoordinator.TaskComplete(
      BigInt(1),
      Right((0, Seq.empty, Seq(ByteString("boundary-proof"))))
    )

    snapSyncController.expectMessageType[SNAPSyncController.AccountRangeProgressCmd]
    snapSyncController.expectMessage(SNAPSyncController.AccountRangeSyncComplete)
  }

  // ── activeTasks leak fix (#1184): worker-reuse race, observed end-to-end ───

  it should "redispatch through a drained worker without TaskFailed(0, \"Worker busy\") (#1184 worker-reuse race)" taggedAs UnitTest in {
    // End-to-end test using REAL coordinator-created workers so the assertion observes actual
    // network-send behaviour. Without WorkerRequestCancelled the redispatch step would drive the
    // worker (still in `working` state) through the "Worker is busy" branch and emit
    // TaskFailed(0, "Worker busy") — the canonical leak-fix-incomplete signature.
    val stateRoot = kec256(ByteString("worker-reuse-test-root"))
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val peerProbeA = testKit.createTestProbe[Any]()
    val peerProbeB = testKit.createTestProbe[Any]()
    val peerA = PeerTestHelpers.createTestPeer("reuse-peerA", peerProbeA.ref.toClassic)
    val peerB = PeerTestHelpers.createTestPeer("reuse-peerB", peerProbeB.ref.toClassic)
    val syncController = testKit.createTestProbe[SNAPSyncController.Command]()

    val coordinator = arcProps(
      stateRoot = stateRoot,
      networkPeerManager = networkPeerManager.ref,
      requestTracker = new SNAPRequestTracker()(classicSystem.scheduler),
      mptStorage = new TestMptStorage(),
      concurrency = 1, // exactly one worker so reuse is unambiguous
      snapSyncController = syncController.ref
    )

    coordinator ! AccountRangeCoordinator.StartAccountRangeSync(stateRoot)
    coordinator ! AccountRangeCoordinator.PeerAvailable(peerA)

    // First dispatch: real worker → networkPeerManager receives a SendMessage.
    networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd]

    // Drain via PeerUnavailable. WorkerRequestCancelled goes to the worker (clears currentTask,
    // become(idle)); coordinator re-queues the task.
    coordinator ! AccountRangeCoordinator.PeerUnavailable(peerA.id.value)

    // Second dispatch via a fresh peer. Without the worker-reuse fix, the still-busy worker would
    // emit TaskFailed(0, "Worker busy") instead of dispatching. We assert that we DO see a second send.
    coordinator ! AccountRangeCoordinator.PeerAvailable(peerB)
    networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd]

    testKit.stop(coordinator)
  }

  // NOTE: The snapless-peer promotion tests (#1197) and the post-PivotRefreshed redispatch test
  // previously relied on `coordinator.underlyingActor` to pre-seed `emptyResponseStrikes(peer.id) = 4`
  // and inspect `statelessPeers` / `snaplessPeers` directly. The Typed coordinator exposes no
  // `underlyingActor`, and the 5-strike promotion cannot be driven cleanly black-box (each empty-proof
  // failure also benches the peer via `recordPeerCooldown`, so same-peer redispatch within a test window
  // is non-deterministic). These white-box tests are dropped per the BCC/SRC precedent. The escalation
  // path itself is still covered black-box by the PivotStateUnservable test below.

  // ── requeueOrEscalate → PivotStateUnservable ──────────────────────────────

  it should "escalate PivotStateUnservable to controller after MaxRequeuesPerTask+1 consecutive failures" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("requeue-test-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(classicSystem.scheduler)
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()
    val peerProbe = testKit.createTestProbe[Any]()
    val peer = PeerTestHelpers.createTestPeer("requeue-peer", peerProbe.ref.toClassic)

    val coordinator = arcProps(
      stateRoot = stateRoot,
      networkPeerManager = networkPeerManager.ref,
      requestTracker = requestTracker,
      mptStorage = storage,
      concurrency = 1,
      snapSyncController = snapSyncController.ref,
      initialMaxInFlightPerPeer = 1
    )

    coordinator ! AccountRangeCoordinator.StartAccountRangeSync(stateRoot)
    coordinator ! AccountRangeCoordinator.PeerAvailable(peer)

    // Route failures through the worker so it properly transitions to idle before each re-dispatch.
    // The worker (Typed) is resolved via selection; WorkerPeerDisconnected skips cooldown and stateless
    // marking, allowing immediate re-dispatch each iteration.
    for _ <- 1 to (AccountRangeCoordinator.MaxRequeuesPerTask + 1) do
      networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd]
      val workerRef = resolveWorkerChild(coordinator)
      workerRef ! AccountRangeCoordinator.WorkerPeerDisconnected(peer.id.value)

    snapSyncController.expectMessageType[SNAPSyncController.PivotStateUnservable]
  }

  // ── task.rootHash guard prevents stale-root stateless marking ──────────────

  it should "not mark peer stateless when TaskFailed arrives for a stale-root in-flight task" taggedAs UnitTest in {
    val rootR1 = kec256(ByteString("stale-guard-root-r1"))
    val rootR2 = kec256(ByteString("stale-guard-root-r2"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(classicSystem.scheduler)
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()
    val peerProbe = testKit.createTestProbe[Any]()
    val peer = PeerTestHelpers.createTestPeer("stale-guard-peer", peerProbe.ref.toClassic)

    val coordinator = arcProps(
      stateRoot = rootR1,
      networkPeerManager = networkPeerManager.ref,
      requestTracker = requestTracker,
      mptStorage = storage,
      concurrency = 1,
      snapSyncController = snapSyncController.ref,
      initialMaxInFlightPerPeer = 1
    )

    coordinator ! AccountRangeCoordinator.StartAccountRangeSync(rootR1)
    coordinator ! AccountRangeCoordinator.PeerAvailable(peer)

    val sendMsg1 = networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd]
    val reqId1 = sendMsg1.message.asInstanceOf[GetAccountRangeEnc].underlyingMsg.requestId

    // Pivot refreshes while the task is still in-flight at rootR1
    coordinator ! AccountRangeCoordinator.PivotRefreshed(rootR2)

    // Task fails with "Missing proof" — but its rootHash is rootR1 (stale). The rootHash guard must
    // prevent marking peer as stateless for rootR2.
    coordinator ! AccountRangeCoordinator.TaskFailed(reqId1, "Missing proof for empty account range")

    // GetProgress as a synchronization barrier — by the time we get a response, the coordinator has
    // fully processed the TaskFailed (including any re-dispatch attempts).
    coordinator ! AccountRangeCoordinator.AccountGetProgress(statusProbe.ref)
    statusProbe.expectMessageType[AccountRangeStats]

    // PivotStateUnservable must NOT have been sent — peer was not marked stateless for rootR2
    snapSyncController.expectNoMessage(200.millis)
  }

  // ── postStop sends AccountRangeProgress snapshot ───────────────────────────

  it should "send AccountRangeProgress snapshot to controller when stopped" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("poststop-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(classicSystem.scheduler)
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()

    val coordinator = arcProps(
      stateRoot = stateRoot,
      networkPeerManager = networkPeerManager.ref,
      requestTracker = requestTracker,
      mptStorage = storage,
      concurrency = 1,
      snapSyncController = snapSyncController.ref
    )

    coordinator ! AccountRangeCoordinator.StartAccountRangeSync(stateRoot)
    // Stop the coordinator — PostStop fires and sends AccountRangeProgress
    testKit.stop(coordinator)

    val progressMsg = snapSyncController.expectMessageType[SNAPSyncController.AccountRangeProgressCmd]
    // concurrency=1 → 1 task → 1 entry in the progress map
    progressMsg.progress should not be empty
  }

  // ── PivotRefreshed drains in-flight + immediately redispatches ─────────────

  it should "drain in-flight tasks and immediately redispatch to a known peer when pivot is refreshed" taggedAs UnitTest in {
    // PivotRefreshed re-tags pending tasks to the new root and immediately calls dispatchIfPossible
    // for every known peer — work resumes without waiting for another PeerAvailable. Observable: a
    // second SendMessage follows the PivotRefreshed even though no new peer was announced.
    val rootR1 = kec256(ByteString("pivot-clear-root-r1"))
    val rootR2 = kec256(ByteString("pivot-clear-root-r2"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(classicSystem.scheduler)
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()
    val peerProbe = testKit.createTestProbe[Any]()
    val peer = PeerTestHelpers.createTestPeer("pivot-clear-peer", peerProbe.ref.toClassic)

    val coordinator = arcProps(
      stateRoot = rootR1,
      networkPeerManager = networkPeerManager.ref,
      requestTracker = requestTracker,
      mptStorage = storage,
      concurrency = 1,
      snapSyncController = snapSyncController.ref,
      initialMaxInFlightPerPeer = 1
    )

    coordinator ! AccountRangeCoordinator.StartAccountRangeSync(rootR1)
    coordinator ! AccountRangeCoordinator.PeerAvailable(peer)

    // First dispatch at rootR1.
    networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd]

    // Pivot refresh: the in-flight task is drained back to pending, re-tagged to rootR2, and
    // immediately redispatched to the still-known peer.
    coordinator ! AccountRangeCoordinator.PivotRefreshed(rootR2)
    networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd]
  }

  // ── Peer disconnect mid-flight ─────────────────────────────────────────────
  // Cross-reference: core-geth eth/downloader/downloader_test.go dropPeer() pattern —
  // responses from dropped peers are silently ignored; in-flight tasks return to pending.

  it should "re-queue in-flight task and redispatch to a different peer after PeerUnavailable" taggedAs UnitTest in {
    val root = kec256(ByteString("disconnect-mid-flight-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(classicSystem.scheduler)
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()
    val peerProbe1 = testKit.createTestProbe[Any]()
    val peerProbe2 = testKit.createTestProbe[Any]()
    val peer1 = PeerTestHelpers.createTestPeer("disconnect-peer-1", peerProbe1.ref.toClassic)
    val peer2 = PeerTestHelpers.createTestPeer("disconnect-peer-2", peerProbe2.ref.toClassic)

    val coordinator = arcProps(
      stateRoot = root,
      networkPeerManager = networkPeerManager.ref,
      requestTracker = requestTracker,
      mptStorage = storage,
      concurrency = 1,
      snapSyncController = snapSyncController.ref,
      initialMaxInFlightPerPeer = 1
    )

    coordinator ! AccountRangeCoordinator.StartAccountRangeSync(root)
    coordinator ! AccountRangeCoordinator.PeerAvailable(peer1)
    networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd]

    // Peer1 disconnects mid-flight: coordinator drains the slot (WorkerRequestCancelled to the worker)
    // and removes peer1 from knownAvailablePeers.
    coordinator ! AccountRangeCoordinator.PeerUnavailable(peer1.id.value)

    // Peer2 becomes available → coordinator re-dispatches the now-pending task to peer2.
    coordinator ! AccountRangeCoordinator.PeerAvailable(peer2)
    val redispatch = networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd]
    redispatch.peerId shouldBe peer2.id
  }

  it should "not fire a second TaskFailed when a late response arrives after RequestTimeout" taggedAs UnitTest in {
    val root = kec256(ByteString("late-response-guard-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(classicSystem.scheduler)
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()
    val peerProbe = testKit.createTestProbe[Any]()
    val peer = PeerTestHelpers.createTestPeer("late-resp-peer", peerProbe.ref.toClassic)

    val coordinator = arcProps(
      stateRoot = root,
      networkPeerManager = networkPeerManager.ref,
      requestTracker = requestTracker,
      mptStorage = storage,
      concurrency = 1,
      snapSyncController = snapSyncController.ref,
      initialMaxInFlightPerPeer = 1
    )

    coordinator ! AccountRangeCoordinator.StartAccountRangeSync(root)
    coordinator ! AccountRangeCoordinator.PeerAvailable(peer)
    val sendMsg = networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd]
    val reqId = sendMsg.message.asInstanceOf[GetAccountRangeEnc].underlyingMsg.requestId
    val worker = resolveWorkerChild(coordinator)

    // Worker times out — fires TaskFailed("Request timeout") to coordinator.
    worker ! AccountRangeCoordinator.RequestTimeout(reqId)

    // Coordinator requeues; controller receives no escalation (not enough retries).
    snapSyncController.expectNoMessage(300.millis)

    // Late AccountRangeResponse arrives at worker (now in idle state) — must be silently dropped;
    // coordinator must NOT receive a second TaskFailed or TaskComplete for this request.
    worker ! AccountRangeCoordinator.AccountRangeResponseMsg(
      AccountRange(requestId = reqId, accounts = Seq.empty, proof = Seq.empty)
    )

    snapSyncController.expectNoMessage(300.millis)
  }

  // ── Peer cooldown (gray-list) ──────────────────────────────────────────────
  // After a transient protocol failure (timeout), the peer enters a cooldown ("gray list").
  // Tasks are not dispatched to cooling peers.

  it should "not dispatch a task to a peer that is still in cooldown after a timeout failure" taggedAs UnitTest in {
    val root = kec256(ByteString("cooldown-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(classicSystem.scheduler)
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()
    val peerProbe1 = testKit.createTestProbe[Any]()
    val peerProbe2 = testKit.createTestProbe[Any]()
    val peer1 = PeerTestHelpers.createTestPeer("cooldown-peer-1", peerProbe1.ref.toClassic)
    val peer2 = PeerTestHelpers.createTestPeer("cooldown-peer-2", peerProbe2.ref.toClassic)

    val coordinator = arcProps(
      stateRoot = root,
      networkPeerManager = networkPeerManager.ref,
      requestTracker = requestTracker,
      mptStorage = storage,
      concurrency = 2,
      snapSyncController = snapSyncController.ref,
      initialMaxInFlightPerPeer = 1
    )

    coordinator ! AccountRangeCoordinator.StartAccountRangeSync(root)
    coordinator ! AccountRangeCoordinator.PeerAvailable(peer1)

    val sendMsg1 = networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd]
    val reqId1 = sendMsg1.message.asInstanceOf[GetAccountRangeEnc].underlyingMsg.requestId
    val worker1 = resolveWorkerChild(coordinator)

    // peer1 times out — enters cooldown via recordPeerCooldown.
    worker1 ! AccountRangeCoordinator.RequestTimeout(reqId1)

    // peer2 connects after peer1 enters cooldown.
    coordinator ! AccountRangeCoordinator.PeerAvailable(peer2)

    // The requeued task should be dispatched to peer2, NOT to the cooling peer1.
    val sendMsg2 = networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd]
    sendMsg2.peerId shouldBe peer2.id
  }

  // ── Resume durability (StackTrie path, observable via dispatch behaviour) ──

  it should "SKIP a fully-complete range on resume (committed last session)" taggedAs UnitTest in {
    // A range whose saved cursor reached its `last` boundary had commit() called last session, so its
    // full subtrie is on disk. It is skipped (not re-downloaded), leaving nothing pending for a
    // single-range coord. Observable: with no pending tasks and a peer available, no SendMessage fires.
    val root = kec256(ByteString("stacktrie-resume-complete-root"))
    val rangeLast = AccountTask.MaxHash32
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val peerProbe = testKit.createTestProbe[Any]()
    val peer = PeerTestHelpers.createTestPeer("resume-complete-peer", peerProbe.ref.toClassic)

    val coord = arcProps(
      stateRoot = root,
      networkPeerManager = networkPeerManager.ref,
      requestTracker = new SNAPRequestTracker()(classicSystem.scheduler),
      mptStorage = new TestMptStorage(),
      concurrency = 1,
      snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]().ref,
      resumeProgress = Map(rangeLast -> rangeLast) // savedNext == last => fully complete
    )

    coord ! AccountRangeCoordinator.StartAccountRangeSync(root)
    coord ! AccountRangeCoordinator.PeerAvailable(peer)

    // Nothing to download — no GetAccountRange dispatched.
    networkPeerManager.expectNoMessage(500.millis)

    testKit.stop(coord)
  }

  // ── Storage / bytecode back-pressure pause/resume (#1232 follow-up) ────────

  it should "skip dispatch while storage back-pressure is set, resume on release" taggedAs UnitTest in {
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val root = kec256(ByteString("backpressure-dispatch-root"))
    val coord = arcProps(
      stateRoot = root,
      networkPeerManager = networkPeerManager.ref,
      requestTracker = new SNAPRequestTracker()(classicSystem.scheduler),
      mptStorage = new TestMptStorage(),
      concurrency = 4,
      snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]().ref
    )

    val peerProbe = testKit.createTestProbe[Any]()
    val peer = PeerTestHelpers.createTestPeer("backpressure-peer", peerProbe.ref.toClassic)

    coord ! AccountRangeCoordinator.StartAccountRangeSync(root)

    // Engage back-pressure BEFORE peer becomes available — coordinator accepts the peer but dispatches
    // no GetAccountRange requests until back-pressure releases.
    coord ! AccountRangeCoordinator.StorageQueuePressure(paused = true)
    coord ! AccountRangeCoordinator.PeerAvailable(peer)

    networkPeerManager.expectNoMessage(500.millis)

    // Release: the coordinator wakes up and dispatches against the known peer.
    coord ! AccountRangeCoordinator.StorageQueuePressure(paused = false)
    networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd]
  }

  it should "treat storage and bytecode pressure as ANY-of: only release once every source clears" taggedAs UnitTest in {
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val root = kec256(ByteString("two-source-backpressure-root"))
    val coord = arcProps(
      stateRoot = root,
      networkPeerManager = networkPeerManager.ref,
      requestTracker = new SNAPRequestTracker()(classicSystem.scheduler),
      mptStorage = new TestMptStorage(),
      concurrency = 4,
      snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]().ref
    )

    val peerProbe = testKit.createTestProbe[Any]()
    val peer = PeerTestHelpers.createTestPeer("two-source-peer", peerProbe.ref.toClassic)

    coord ! AccountRangeCoordinator.StartAccountRangeSync(root)

    // Engage BOTH sources.
    coord ! AccountRangeCoordinator.StorageQueuePressure(paused = true)
    coord ! AccountRangeCoordinator.ByteCodeQueuePressure(paused = true)
    coord ! AccountRangeCoordinator.PeerAvailable(peer)

    networkPeerManager.expectNoMessage(500.millis)

    // Release storage only — bytecode is still engaged, so dispatch must remain paused.
    coord ! AccountRangeCoordinator.StorageQueuePressure(paused = false)
    networkPeerManager.expectNoMessage(500.millis)

    // Release bytecode — set is now empty, dispatch resumes.
    coord ! AccountRangeCoordinator.ByteCodeQueuePressure(paused = false)
    networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd]
  }

  // ── Idle-stall classification (Sepolia 2026-10-06: scarcity refreshes reached the restart threshold) ──

  private val pA = com.chipprbots.ethereum.network.PeerId("a")
  private val pB = com.chipprbots.ethereum.network.PeerId("b")

  "AccountRangeCoordinator.classifyIdleStall" should "treat a deliberate pause as Paused regardless of the pool" taggedAs UnitTest in {
    AccountRangeCoordinator.classifyIdleStall(
      dispatchPaused = true,
      knownPeers = Set(pA, pB),
      stateless = Set(pA, pB),
      snapless = Set.empty
    ) shouldBe AccountRangeCoordinator.IdleStallVerdict.Paused
  }

  it should "classify cooling / partly-stateless / empty pools as PeerScarcity" taggedAs UnitTest in {
    import AccountRangeCoordinator.IdleStallVerdict.PeerScarcity
    // Sepolia log: "2 known peers, 0 stateless, 1 cooling"
    AccountRangeCoordinator.classifyIdleStall(false, Set(pA, pB), Set.empty, Set.empty) shouldBe PeerScarcity
    AccountRangeCoordinator.classifyIdleStall(false, Set(pA, pB), Set(pA), Set.empty) shouldBe PeerScarcity
    AccountRangeCoordinator.classifyIdleStall(false, Set.empty, Set.empty, Set.empty) shouldBe PeerScarcity
  }

  it should "classify all-non-snapless-stateless as Stateless and all-snapless as Snapless" taggedAs UnitTest in {
    import AccountRangeCoordinator.IdleStallVerdict.*
    AccountRangeCoordinator.classifyIdleStall(false, Set(pA, pB), Set(pA, pB), Set.empty) shouldBe Stateless
    AccountRangeCoordinator.classifyIdleStall(false, Set(pA, pB), Set(pA), Set(pB)) shouldBe Stateless
    AccountRangeCoordinator.classifyIdleStall(false, Set(pA, pB), Set.empty, Set(pA, pB)) shouldBe Snapless
  }

  "SNAPSyncController.countsTowardRestart" should "count stateless/snapless evidence but never peer scarcity" taggedAs UnitTest in {
    SNAPSyncController.countsTowardRestart(SNAPSyncController.UnservableCause.Stateless) shouldBe true
    SNAPSyncController.countsTowardRestart(SNAPSyncController.UnservableCause.Snapless) shouldBe true
    SNAPSyncController.countsTowardRestart(SNAPSyncController.UnservableCause.PeerScarcity) shouldBe false
  }

  "AccountRangeCoordinator stall watchdog" should "not escalate while downstream back-pressure pauses dispatch" taggedAs UnitTest in {
    val root = kec256(ByteString("backpressure-no-stall-root"))
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()
    val coord = arcProps(
      stateRoot = root,
      networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]().ref,
      requestTracker = new SNAPRequestTracker()(classicSystem.scheduler),
      mptStorage = new TestMptStorage(),
      concurrency = 4,
      snapSyncController = snapSyncController.ref
    )
    coord ! AccountRangeCoordinator.StorageQueuePressure(paused = true)
    // Far more than the 3-tick escalation threshold.
    (1 to 10).foreach(_ => coord ! AccountRangeCoordinator.CheckDispatchStalled)
    coord ! AccountRangeCoordinator.AccountGetProgress(statusProbe.ref)
    statusProbe.expectMessageType[AccountRangeStats].dispatchPaused shouldBe true

    // The progress reply above is a barrier: all ten ticks were processed. Before the fix the third tick sent
    // PivotStateUnservable here; now nothing may reach the controller.
    snapSyncController.expectNoMessage(300.millis)
    testKit.stop(coord)
  }

  it should "escalate a peerless idle phase as PeerScarcity (refresh only, never counted toward restart)" taggedAs UnitTest in {
    val root = kec256(ByteString("scarcity-stall-root"))
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()
    val coord = arcProps(
      stateRoot = root,
      networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]().ref,
      requestTracker = new SNAPRequestTracker()(classicSystem.scheduler),
      mptStorage = new TestMptStorage(),
      concurrency = 4,
      snapSyncController = snapSyncController.ref
    )
    (1 to 3).foreach(_ => coord ! AccountRangeCoordinator.CheckDispatchStalled)
    val fished = snapSyncController.fishForMessage(3.seconds) {
      case _: SNAPSyncController.PivotStateUnservable =>
        org.apache.pekko.actor.testkit.typed.FishingOutcome.Complete
      case _ => org.apache.pekko.actor.testkit.typed.FishingOutcome.ContinueAndIgnore
    }
    val unservable = fished.collect { case m: SNAPSyncController.PivotStateUnservable => m }.head
    unservable.cause shouldBe SNAPSyncController.UnservableCause.PeerScarcity
    SNAPSyncController.countsTowardRestart(unservable.cause) shouldBe false
    testKit.stop(coord)
  }

  // ── Resume from a mid-range cursor with carried contract task files (Sepolia 2026-10-06: 17.35M accounts lost) ──

  private def entry(tag: String, i: Int, size: Int): Array[Byte] =
    if size == 32 then kec256(ByteString(s"$tag-$i")).toArray
    else (kec256(ByteString(s"$tag-acct-$i")) ++ kec256(ByteString(s"$tag-root-$i"))).toArray

  /** Previous coordinator's task files: `counted` valid entries plus `trailing` entries written after the checkpoint
    * (they belong to accounts above the cursor and must NOT be carried).
    */
  private def previousTaskFiles(dir: java.nio.file.Path, counted: Int, codeCounted: Int): ContractTaskFiles =
    val storage = dir.resolve("prev-storage.bin")
    val code = dir.resolve("prev-code.bin")
    java.nio.file.Files.write(storage, (0 until counted + 2).flatMap(i => entry("s", i, 64)).toArray)
    java.nio.file.Files.write(code, (0 until codeCounted + 2).flatMap(i => entry("c", i, 32)).toArray)
    ContractTaskFiles(storage.toString, counted.toLong, code.toString, codeCounted.toLong)

  private def spawnResumed(
      root: ByteString,
      networkPeerManager: org.apache.pekko.actor.typed.ActorRef[NetworkPeerManagerActor.Command],
      controller: org.apache.pekko.actor.typed.ActorRef[SNAPSyncController.Command],
      resumeProgress: Map[ByteString, ByteString],
      carried: Option[ContractTaskFiles],
      dir: java.nio.file.Path,
      generation: Long
  ): org.apache.pekko.actor.typed.ActorRef[AccountRangeCoordinator.Command] =
    testKit.spawn(
      AccountRangeCoordinator(
        stateRoot = root,
        networkPeerManager = networkPeerManager,
        requestTracker = new SNAPRequestTracker()(classicSystem.scheduler),
        mptStorage = new TestMptStorage(),
        concurrency = 1,
        snapSyncController = controller,
        resumeProgress = resumeProgress,
        accountTrieEcOverride = Some(classicSystem.dispatcher),
        taskFileDir = Some(dir),
        carriedTaskFiles = carried,
        progressGeneration = generation
      )
    )

  private def fish[T <: SNAPSyncController.Command](
      probe: org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe[SNAPSyncController.Command]
  )(pf: PartialFunction[SNAPSyncController.Command, T]): T =
    probe
      .fishForMessage(5.seconds) { m =>
        if pf.isDefinedAt(m) then org.apache.pekko.actor.testkit.typed.FishingOutcome.Complete
        else org.apache.pekko.actor.testkit.typed.FishingOutcome.ContinueAndIgnore
      }
      .collect(pf)
      .head

  private def firstStartingHash(
      networkPeerManager: org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe[NetworkPeerManagerActor.Command]
  ): ByteString =
    networkPeerManager
      .expectMessageType[NetworkPeerManagerActor.SendMessageCmd]
      .message
      .asInstanceOf[GetAccountRangeEnc]
      .underlyingMsg
      .startingHash

  private val midCursor: ByteString = ByteString(Array.fill(32)(0x40.toByte))

  "A resumed AccountRangeCoordinator" should "resume a partial range from its cursor and replay the carried contract work (pivot change / in-process restart)" taggedAs UnitTest in {
    val dir = java.nio.file.Files.createTempDirectory("arc-resume-")
    val carried = previousTaskFiles(dir, counted = 3, codeCounted = 2)
    val nm = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val controller = testKit.createTestProbe[SNAPSyncController.Command]()
    val peer = PeerTestHelpers.createTestPeer("resume-peer", testKit.createTestProbe[Any]().ref.toClassic)
    val root = kec256(ByteString("resume-root"))

    val coord =
      spawnResumed(root, nm.ref, controller.ref, Map(AccountTask.MaxHash32 -> midCursor), Some(carried), dir, 7)
    coord ! AccountRangeCoordinator.PeerAvailable(peer)

    // The range is NOT restarted from 0x00: the first request starts at the saved cursor.
    firstStartingHash(nm) shouldBe midCursor

    // The contract work of the accounts below the cursor is replayed — exactly the counted entries, not the trailing.
    val replay = fish(controller) { case m: SNAPSyncController.IncrementalContractData => m }
    replay.replayed shouldBe true
    replay.storageTasks.map(_.accountHash) shouldBe (0 until 3).map(i => ByteString(entry("s", i, 64).take(32)))
    replay.codeHashes shouldBe (0 until 2).map(i => ByteString(entry("c", i, 32)))

    // Stop (pivot-change restart): the final snapshot is durable, echoes the generation, keeps the cursor and points
    // at this coordinator's OWN files, whose prefix is the carried prefix.
    testKit.stop(coord)
    val snap = fish(controller) { case m: SNAPSyncController.AccountRangeProgressCmd if m.durable => m }
    snap.generation shouldBe 7L
    snap.progress(AccountTask.MaxHash32) shouldBe midCursor
    val files = snap.taskFiles.get
    files.storageCount shouldBe 3L
    files.codeHashesCount shouldBe 2L
    files.storagePath should not be carried.storagePath
    java.nio.file.Files.readAllBytes(java.nio.file.Path.of(files.storagePath)).toSeq shouldBe
      java.nio.file.Files.readAllBytes(java.nio.file.Path.of(carried.storagePath)).toSeq.take(3 * 64)
    java.nio.file.Files.readAllBytes(java.nio.file.Path.of(files.codeHashesPath)).toSeq shouldBe
      java.nio.file.Files.readAllBytes(java.nio.file.Path.of(carried.codeHashesPath)).toSeq.take(2 * 32)
  }

  it should "resume the same way after a simulated process restart through the persisted checkpoint" taggedAs UnitTest in {
    val dir = java.nio.file.Files.createTempDirectory("arc-restart-")
    val carried = previousTaskFiles(dir, counted = 3, codeCounted = 2)
    val root = kec256(ByteString("restart-root"))

    // Run 1: resumed coordinator, stopped -> durable snapshot (what the controller persists).
    val controller1 = testKit.createTestProbe[SNAPSyncController.Command]()
    val coord1 = spawnResumed(
      root,
      testKit.createTestProbe[NetworkPeerManagerActor.Command]().ref,
      controller1.ref,
      Map(AccountTask.MaxHash32 -> midCursor),
      Some(carried),
      dir,
      1
    )
    fish(controller1) { case m: SNAPSyncController.IncrementalContractData => m }
    testKit.stop(coord1)
    val snap = fish(controller1) { case m: SNAPSyncController.AccountRangeProgressCmd if m.durable => m }

    // Persist / reload through the versioned checkpoint codec, then the controller's resumability gate.
    val json = AccountResumeCheckpoint.encode(
      AccountResumeCheckpoint(root, BigInt(11855776), snap.progress, snap.taskFiles.get)
    )
    val reloaded = AccountResumeCheckpoint.decode(json).fold(e => fail(e), identity)
    reloaded.cursors shouldBe snap.progress
    reloaded.pivotBlock shouldBe BigInt(11855776)
    SNAPSyncController.checkResumable(reloaded.cursors, reloaded.taskFiles, concurrency = 1) shouldBe Right(())

    // Run 2 (new process, new pivot root): resumes from the cursor and replays the same 3 tasks — no duplicates.
    val nm2 = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val controller2 = testKit.createTestProbe[SNAPSyncController.Command]()
    val coord2 = spawnResumed(
      kec256(ByteString("restart-root-new-pivot")),
      nm2.ref,
      controller2.ref,
      reloaded.cursors,
      Some(reloaded.taskFiles),
      dir,
      2
    )
    coord2 ! AccountRangeCoordinator.PeerAvailable(
      PeerTestHelpers.createTestPeer("restart-peer", testKit.createTestProbe[Any]().ref.toClassic)
    )
    firstStartingHash(nm2) shouldBe midCursor
    val replay = fish(controller2) { case m: SNAPSyncController.IncrementalContractData => m }
    replay.storageTasks.size shouldBe 3
    replay.codeHashes.size shouldBe 2
    testKit.stop(coord2)
  }

  it should "re-download a partial range from its start when no task files are carried (legacy record)" taggedAs UnitTest in {
    val dir = java.nio.file.Files.createTempDirectory("arc-legacy-")
    val nm = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val controller = testKit.createTestProbe[SNAPSyncController.Command]()
    val coord = spawnResumed(
      kec256(ByteString("legacy-root")),
      nm.ref,
      controller.ref,
      Map(AccountTask.MaxHash32 -> midCursor),
      None,
      dir,
      0
    )
    coord ! AccountRangeCoordinator.PeerAvailable(
      PeerTestHelpers.createTestPeer("legacy-peer", testKit.createTestProbe[Any]().ref.toClassic)
    )
    firstStartingHash(nm) shouldBe ByteString(Array.fill(32)(0.toByte))
    testKit.stop(coord)
  }

  "AccountResumeCheckpoint / checkResumable" should "reject other versions, a mismatched range layout and short task files" taggedAs UnitTest in {
    val dir = java.nio.file.Files.createTempDirectory("arc-gate-")
    val files = previousTaskFiles(dir, counted = 3, codeCounted = 2)
    val cp = AccountResumeCheckpoint(
      ByteString(Array.fill(32)(1.toByte)),
      BigInt(5),
      Map(AccountTask.MaxHash32 -> midCursor),
      files
    )
    val json = AccountResumeCheckpoint.encode(cp)
    AccountResumeCheckpoint.decode(json).map(_.taskFiles) shouldBe Right(files)
    AccountResumeCheckpoint.decode(json.replace("\"version\":1", "\"version\":2")).isLeft shouldBe true
    AccountResumeCheckpoint.decode("{}").isLeft shouldBe true
    // 1 cursor but 16 ranges at concurrency 16 — a partial carry would duplicate contract work.
    SNAPSyncController.checkResumable(cp.cursors, files, concurrency = 16).isLeft shouldBe true
    // Counts larger than the files hold.
    SNAPSyncController.checkResumable(cp.cursors, files.copy(storageCount = 100), concurrency = 1).isLeft shouldBe true
    SNAPSyncController
      .checkResumable(cp.cursors, files.copy(codeHashesPath = dir.resolve("gone").toString), 1)
      .isLeft shouldBe
      true
  }
