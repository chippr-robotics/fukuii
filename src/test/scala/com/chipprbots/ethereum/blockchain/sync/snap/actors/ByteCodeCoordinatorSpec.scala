package com.chipprbots.ethereum.blockchain.sync.snap.actors

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit

import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.scalatest.concurrent.Eventually
import org.apache.pekko.util.ByteString

import scala.concurrent.duration.*

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap.*
import com.chipprbots.ethereum.blockchain.sync.snap.controller.StateIngest
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.BfsQueueStorage
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.FlatSlotStorage
import com.chipprbots.ethereum.db.storage.HealingFrontierStorage
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.db.storage.SnapStorageDoneStorage
import com.chipprbots.ethereum.db.storage.SnapSyncProgressStorage
import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.BlockchainWriter
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.p2p.messages.SNAP.ByteCodes
import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetByteCodes.GetByteCodesEnc
import com.chipprbots.ethereum.testing.PeerTestHelpers
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.testing.TestEvmCodeStorage

class ByteCodeCoordinatorSpec extends ScalaTestWithActorTestKit() with AnyFlatSpecLike with Matchers with Eventually:

  implicit private val classicSystem: org.apache.pekko.actor.ActorSystem = system.classicSystem
  private val statusProbe = testKit.createTestProbe[ByteCodeCoordinator.ByteCodeProgress]()

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

  // ByteCodeCoordinator is a Typed actor (Group S3). These tests run in a Classic ActorSystem so they can use
  // the established `system.actorOf` / `expectMsg` machinery against the coordinator and its (Typed) worker
  // children; the coordinator is spawned through PropsAdapter to bridge the Classic system to the Typed Behavior.
  // Mirrors the `.props(...)` factory the actor previously exposed.
  private def bccProps(
      evmCodeStorage: TestEvmCodeStorage,
      networkPeerManager: org.apache.pekko.actor.typed.ActorRef[NetworkPeerManagerActor.Command],
      requestTracker: SNAPRequestTracker,
      batchSize: Int,
      snapSyncController: org.apache.pekko.actor.typed.ActorRef[SNAPSyncController.Command],
      cooldownConfig: ByteCodeCoordinator.ByteCodePeerCooldownConfig =
        ByteCodeCoordinator.ByteCodePeerCooldownConfig.default,
      backpressureHighWatermark: Int = 50000,
      backpressureLowWatermark: Int = 25000
  ): org.apache.pekko.actor.typed.ActorRef[ByteCodeCoordinator.Command] =
    testKit.spawn(
      ByteCodeCoordinator(
        stateIngest = bytecodeIngest(evmCodeStorage),
        networkPeerManager = networkPeerManager,
        requestTracker = requestTracker,
        batchSize = batchSize,
        snapSyncController = snapSyncController,
        cooldownConfig = cooldownConfig,
        backpressureHighWatermark = backpressureHighWatermark,
        backpressureLowWatermark = backpressureLowWatermark
      )
    )

  // spec 022 T033: where a force-complete records what it abandons.
  private val progressStore = new SnapSyncProgressStorage(com.chipprbots.ethereum.db.dataSource.EphemDataSource())

  // ByteCodeCoordinator takes one `StateIngest` (spec 016 C-B1). It reads only `bytecode`; any other member fails
  // loudly, so a test that reaches one is a change in what the coordinator reads.
  private def bytecodeIngest(evmCodeStorage: EvmCodeStorage): StateIngest =
    new StateIngest:
      def appState: AppStateStorage = notReached("appState")
      def stateTries: StateStorage = notReached("stateTries")
      def flatSlots: FlatSlotStorage = notReached("flatSlots")
      def pathNodes: Option[PathNodeStorage] = notReached("pathNodes")
      def bytecode: EvmCodeStorage = evmCodeStorage
      def chainReader: BlockchainReader = notReached("chainReader")
      def chainWriter: BlockchainWriter = notReached("chainWriter")
      def validatorFactory: MptStorage => StateValidator = notReached("validatorFactory")
      def durability: StateIngest.Durability = StateIngest.Durability.Default
      def trieStore(pivot: BigInt): MptStorage = notReached("trieStore")
      def snapProgress: SnapSyncProgressStorage = progressStore
      def storageDone: SnapStorageDoneStorage = notReached("storageDone")
      def healingFrontier: Option[HealingFrontierStorage] = notReached("healingFrontier")
      def bfsQueue: Option[BfsQueueStorage] = notReached("bfsQueue")

  private def notReached(member: String): Nothing =
    throw new UnsupportedOperationException(s"ByteCodeCoordinator read StateIngest.$member")

  // Resolve the coordinator's single worker child via actorSelection; returns a Typed ref so all
  // sends are compile-time checked. Replaces the former `coordinator.underlyingActor.workers.head`,
  // which is unavailable on the Typed coordinator.
  private def resolveWorkerChild(
      coordinator: org.apache.pekko.actor.typed.ActorRef[?]
  ): org.apache.pekko.actor.typed.ActorRef[ByteCodeCoordinator.WorkerMessage] =
    import scala.concurrent.Await
    Await
      .result(
        classicSystem.actorSelection(coordinator.path / "*").resolveOne(3.seconds),
        3.seconds
      )
      .toTyped[ByteCodeCoordinator.WorkerMessage]

  "ByteCodeCoordinator" should "initialize with empty task queue" taggedAs UnitTest in {
    val evmCodeStorage = new TestEvmCodeStorage()
    val requestTracker = new SNAPRequestTracker()(classicSystem.scheduler)
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()

    val coordinator = bccProps(
      evmCodeStorage = evmCodeStorage,
      networkPeerManager = networkPeerManager.ref,
      requestTracker = requestTracker,
      batchSize = 8,
      snapSyncController = snapSyncController.ref
    )

    coordinator should not be null
  }

  it should "queue contract accounts for download" taggedAs UnitTest in {
    val evmCodeStorage = new TestEvmCodeStorage()
    val requestTracker = new SNAPRequestTracker()(classicSystem.scheduler)
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()

    val coordinator = bccProps(
      evmCodeStorage = evmCodeStorage,
      networkPeerManager = networkPeerManager.ref,
      requestTracker = requestTracker,
      batchSize = 8,
      snapSyncController = snapSyncController.ref
    )

    val codeHashes = Seq(
      kec256(ByteString("code1")),
      kec256(ByteString("code2"))
    )

    coordinator ! ByteCodeCoordinator.StartByteCodeSync(codeHashes)

    // Coordinator should queue the contracts
    coordinator ! ByteCodeCoordinator.ByteCodeGetProgress(statusProbe.ref)
    statusProbe.expectMessageType[ByteCodeCoordinator.ByteCodeProgress]
  }

  it should "create workers when peers are available" taggedAs UnitTest in {
    val evmCodeStorage = new TestEvmCodeStorage()
    val requestTracker = new SNAPRequestTracker()(classicSystem.scheduler)
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()
    val peerProbe = testKit.createTestProbe[Any]()

    val peer = PeerTestHelpers.createTestPeer("test-peer", peerProbe.ref.toClassic)

    val coordinator = bccProps(
      evmCodeStorage = evmCodeStorage,
      networkPeerManager = networkPeerManager.ref,
      requestTracker = requestTracker,
      batchSize = 8,
      snapSyncController = snapSyncController.ref
    )

    val codeHashes = Seq(kec256(ByteString("code1")))

    coordinator ! ByteCodeCoordinator.StartByteCodeSync(codeHashes)
    coordinator ! ByteCodeCoordinator.ByteCodePeerAvailable(peer)

    // Should send request to network peer manager
    networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd]
  }

  it should "handle task completion" taggedAs UnitTest in {
    val evmCodeStorage = new TestEvmCodeStorage()
    val requestTracker = new SNAPRequestTracker()(classicSystem.scheduler)
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()

    val coordinator = bccProps(
      evmCodeStorage = evmCodeStorage,
      networkPeerManager = networkPeerManager.ref,
      requestTracker = requestTracker,
      batchSize = 8,
      snapSyncController = snapSyncController.ref
    )

    coordinator ! ByteCodeCoordinator.ByteCodeTaskComplete(BigInt(123), Right(5))

    // Coordinator should handle completion
    coordinator ! ByteCodeCoordinator.ByteCodeGetProgress(statusProbe.ref)
    statusProbe.expectMessageType[ByteCodeCoordinator.ByteCodeProgress]
  }

  // Verifies Fix 6 / P-5.4: ByteCodeTaskComplete must call tryRedispatchPendingTasks() so the
  // next pending task is dispatched immediately within the same message cycle — not delayed up to
  // 1 second waiting for the next ByteCodePeerAvailable tick from SNAPSyncController.
  it should "dispatch pending task immediately on ByteCodeTaskComplete without a new ByteCodePeerAvailable" taggedAs UnitTest in {
    val evmCodeStorage = new TestEvmCodeStorage()
    val requestTracker = new SNAPRequestTracker()(classicSystem.scheduler)
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()
    val peerProbe = testKit.createTestProbe[Any]()
    val peer = PeerTestHelpers.createTestPeer("redispatch-peer", peerProbe.ref.toClassic)

    // maxInFlightPerPeer=1 ensures only 1 task in flight at a time, leaving the 2nd pending
    val peerCooldown = testCooldownConfig.copy(maxInFlightPerPeer = 1)
    val coordinator = bccProps(
      evmCodeStorage = evmCodeStorage,
      networkPeerManager = networkPeerManager.ref,
      requestTracker = requestTracker,
      batchSize = 1,
      snapSyncController = snapSyncController.ref,
      cooldownConfig = peerCooldown
    )

    val hashes = Seq(kec256(ByteString("redispatch-a")), kec256(ByteString("redispatch-b")))
    coordinator ! ByteCodeCoordinator.StartByteCodeSync(hashes)
    coordinator ! ByteCodeCoordinator.NoMoreByteCodeTasks
    coordinator ! ByteCodeCoordinator.ByteCodePeerAvailable(peer)

    // Only the first task is dispatched (maxInFlightPerPeer=1). The SendMessage proves the active task
    // exists and carries its requestId — read it here instead of inspecting coordinator internal state
    // (the Typed coordinator exposes no `.underlyingActor`).
    val send1 = networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd]
    val reqId = send1.message.asInstanceOf[GetByteCodesEnc].underlyingMsg.requestId

    // ByteCodeWorker uses a `working` behavior and stashes ByteCodeWorkerFetchTask in that state.
    // Release it first so the worker transitions to idle; the coordinator's subsequent
    // tryRedispatchPendingTasks() dispatch will then be accepted rather than stashed.
    resolveWorkerChild(coordinator) ! ByteCodeCoordinator.ByteCodeWorkerRelease(reqId)

    // Complete the in-flight task at coordinator level — calls markWorkerIdle + tryRedispatchPendingTasks()
    coordinator ! ByteCodeCoordinator.ByteCodeTaskComplete(reqId, Right(1))

    // The pending task must be dispatched immediately via tryRedispatchPendingTasks()
    networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd]
  }

  it should "report completion when all bytecodes downloaded" taggedAs UnitTest in {
    val evmCodeStorage = new TestEvmCodeStorage()
    val requestTracker = new SNAPRequestTracker()(classicSystem.scheduler)
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()

    val coordinator = bccProps(
      evmCodeStorage = evmCodeStorage,
      networkPeerManager = networkPeerManager.ref,
      requestTracker = requestTracker,
      batchSize = 8,
      snapSyncController = snapSyncController.ref
    )

    // Start with empty contract list
    coordinator ! ByteCodeCoordinator.StartByteCodeSync(Seq.empty)

    // Signal that no more tasks will arrive (sentinel pattern)
    coordinator ! ByteCodeCoordinator.NoMoreByteCodeTasks

    // Should complete immediately since no tasks and sentinel received
    coordinator ! ByteCodeCoordinator.ByteCodeCheckCompletion
    snapSyncController.expectMessage(SNAPSyncController.ByteCodeSyncComplete)
  }

  it should "handle task failures gracefully" taggedAs UnitTest in {
    val evmCodeStorage = new TestEvmCodeStorage()
    val requestTracker = new SNAPRequestTracker()(classicSystem.scheduler)
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()

    val coordinator = bccProps(
      evmCodeStorage = evmCodeStorage,
      networkPeerManager = networkPeerManager.ref,
      requestTracker = requestTracker,
      batchSize = 8,
      snapSyncController = snapSyncController.ref
    )

    coordinator ! ByteCodeCoordinator.ByteCodeTaskFailed(BigInt(123), "Test failure")

    // Coordinator should still be operational
    coordinator ! ByteCodeCoordinator.ByteCodeGetProgress(statusProbe.ref)
    statusProbe.expectMessageType[ByteCodeCoordinator.ByteCodeProgress]
  }

  it should "accept ByteCodes as a subsequence and re-queue missing hashes" taggedAs UnitTest in {
    val evmCodeStorage = new TestEvmCodeStorage()
    val requestTracker = new SNAPRequestTracker()(classicSystem.scheduler)
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()
    val peerProbe = testKit.createTestProbe[Any]()

    val peer = PeerTestHelpers.createTestPeer("test-peer", peerProbe.ref.toClassic)

    val coordinator = bccProps(
      evmCodeStorage = evmCodeStorage,
      networkPeerManager = networkPeerManager.ref,
      requestTracker = requestTracker,
      batchSize = 8,
      snapSyncController = snapSyncController.ref
    )

    val code1 = ByteString("code1")
    val code2 = ByteString("code2")
    val code3 = ByteString("code3")
    val h1 = kec256(code1)
    val h2 = kec256(code2)
    val h3 = kec256(code3)

    val codeHashes = Seq(h1, h2, h3)

    coordinator ! ByteCodeCoordinator.StartByteCodeSync(codeHashes)
    coordinator ! ByteCodeCoordinator.ByteCodePeerAvailable(peer)

    val send1 = networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd]
    val req1 = send1.message.asInstanceOf[GetByteCodesEnc].underlyingMsg
    req1.hashes shouldEqual Seq(h1, h2, h3)

    // Respond with a single middle element (gap allowed by snap/1 semantics)
    resolveWorkerChild(coordinator) ! ByteCodeCoordinator.ByteCodesResponseMsg(
      ByteCodes(req1.requestId, Seq(code2))
    )

    // Ensure the returned code got persisted
    eventually(timeout(3.seconds), interval(100.millis)) {
      evmCodeStorage.get(h2) shouldEqual Some(code2)
    }

    // Drive next dispatch and assert the missing hashes were re-queued
    coordinator ! ByteCodeCoordinator.ByteCodePeerAvailable(peer)
    val send2 = networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd]
    val req2 = send2.message.asInstanceOf[GetByteCodesEnc].underlyingMsg
    req2.hashes shouldEqual Seq(h1, h3)
  }

  it should "reject out-of-order ByteCodes responses and retry" taggedAs UnitTest in {
    val evmCodeStorage = new TestEvmCodeStorage()
    val requestTracker = new SNAPRequestTracker()(classicSystem.scheduler)
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()
    val peerProbe = testKit.createTestProbe[Any]()

    val peer = PeerTestHelpers.createTestPeer("test-peer", peerProbe.ref.toClassic)

    val coordinator = bccProps(
      evmCodeStorage = evmCodeStorage,
      networkPeerManager = networkPeerManager.ref,
      requestTracker = requestTracker,
      batchSize = 8,
      snapSyncController = snapSyncController.ref,
      cooldownConfig = testCooldownConfig
    )

    val code1 = ByteString("code1")
    val code2 = ByteString("code2")
    val h1 = kec256(code1)
    val h2 = kec256(code2)

    val codeHashes = Seq(h1, h2)

    coordinator ! ByteCodeCoordinator.StartByteCodeSync(codeHashes)
    coordinator ! ByteCodeCoordinator.ByteCodePeerAvailable(peer)

    val send1 = networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd]
    val req1 = send1.message.asInstanceOf[GetByteCodesEnc].underlyingMsg
    req1.hashes shouldEqual Seq(h1, h2)

    // Respond out-of-order (violates snap/1 ordering requirement)
    resolveWorkerChild(coordinator) ! ByteCodeCoordinator.ByteCodesResponseMsg(
      ByteCodes(req1.requestId, Seq(code2, code1))
    )

    // Ensure nothing was persisted
    eventually(timeout(3.seconds), interval(100.millis)) {
      evmCodeStorage.get(h1) shouldEqual None
      evmCodeStorage.get(h2) shouldEqual None
    }

    // Verify peer is in cooldown by attempting immediate retry
    coordinator ! ByteCodeCoordinator.ByteCodePeerAvailable(peer)
    networkPeerManager.expectNoMessage(80.millis)

    // Drive retry after cooldown expires
    coordinator ! ByteCodeCoordinator.ByteCodePeerAvailable(peer)
    val send2 = networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd]
    val req2 = send2.message.asInstanceOf[GetByteCodesEnc].underlyingMsg
    req2.hashes shouldEqual Seq(h1, h2)
  }

  it should "reject duplicate bytecodes in a ByteCodes response" taggedAs UnitTest in {
    val evmCodeStorage = new TestEvmCodeStorage()
    val requestTracker = new SNAPRequestTracker()(classicSystem.scheduler)
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()
    val peerProbe = testKit.createTestProbe[Any]()

    val peer = PeerTestHelpers.createTestPeer("test-peer", peerProbe.ref.toClassic)

    val coordinator = bccProps(
      evmCodeStorage = evmCodeStorage,
      networkPeerManager = networkPeerManager.ref,
      requestTracker = requestTracker,
      batchSize = 8,
      snapSyncController = snapSyncController.ref,
      cooldownConfig = testCooldownConfig
    )

    val code1 = ByteString("code1")
    val h1 = kec256(code1)

    val codeHashes = Seq(h1)

    coordinator ! ByteCodeCoordinator.StartByteCodeSync(codeHashes)
    coordinator ! ByteCodeCoordinator.ByteCodePeerAvailable(peer)

    val send1 = networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd]
    val req1 = send1.message.asInstanceOf[GetByteCodesEnc].underlyingMsg
    req1.hashes shouldEqual Seq(h1)

    // Duplicate code for the same hash should be rejected
    resolveWorkerChild(coordinator) ! ByteCodeCoordinator.ByteCodesResponseMsg(
      ByteCodes(req1.requestId, Seq(code1, code1))
    )

    eventually(timeout(3.seconds), interval(100.millis)) {
      evmCodeStorage.get(h1) shouldEqual None
    }

    // Verify peer is in cooldown by attempting immediate retry
    coordinator ! ByteCodeCoordinator.ByteCodePeerAvailable(peer)
    networkPeerManager.expectNoMessage(80.millis)

    // Drive retry after cooldown expires (task should be re-queued)
    coordinator ! ByteCodeCoordinator.ByteCodePeerAvailable(peer)
    val send2 = networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd]
    val req2 = send2.message.asInstanceOf[GetByteCodesEnc].underlyingMsg
    req2.hashes shouldEqual Seq(h1)
  }

  it should "cool down peers after empty ByteCodes responses" taggedAs UnitTest in {
    val evmCodeStorage = new TestEvmCodeStorage()
    val requestTracker = new SNAPRequestTracker()(classicSystem.scheduler)
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()
    val peerProbe = testKit.createTestProbe[Any]()

    val peer = PeerTestHelpers.createTestPeer("test-peer", peerProbe.ref.toClassic)

    val coordinator = bccProps(
      evmCodeStorage = evmCodeStorage,
      networkPeerManager = networkPeerManager.ref,
      requestTracker = requestTracker,
      batchSize = 8,
      snapSyncController = snapSyncController.ref,
      cooldownConfig = testCooldownConfig
    )

    val code1 = ByteString("code1")
    val h1 = kec256(code1)
    val codeHashes = Seq(h1)

    coordinator ! ByteCodeCoordinator.StartByteCodeSync(codeHashes)
    coordinator ! ByteCodeCoordinator.ByteCodePeerAvailable(peer)

    val send1 = networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd]
    val req1 = send1.message.asInstanceOf[GetByteCodesEnc].underlyingMsg
    req1.hashes shouldEqual Seq(h1)

    // Respond with empty ByteCodes (peer had none of the requested hashes)
    resolveWorkerChild(coordinator) ! ByteCodeCoordinator.ByteCodesResponseMsg(
      ByteCodes(req1.requestId, Seq.empty)
    )

    // Immediately advertising the same peer should not trigger a re-request due to cooldown
    coordinator ! ByteCodeCoordinator.ByteCodePeerAvailable(peer)
    networkPeerManager.expectNoMessage(80.millis)

    // After cooldown elapses (already waited 80ms above, cooldown is 50ms), coordinator should send again
    coordinator ! ByteCodeCoordinator.ByteCodePeerAvailable(peer)
    networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd]
  }

  // ---- J7: Peer reputation cleared on pivot refresh ----------------------------

  it should "allow a cooled-down peer to dispatch immediately after ByteCodePivotRefreshed" taggedAs UnitTest in {
    val evmCodeStorage = new TestEvmCodeStorage()
    val requestTracker = new SNAPRequestTracker()(classicSystem.scheduler)
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()
    val peerProbe = testKit.createTestProbe[Any]()

    val peer = PeerTestHelpers.createTestPeer("pivot-peer", peerProbe.ref.toClassic)

    val coordinator = bccProps(
      evmCodeStorage = evmCodeStorage,
      networkPeerManager = networkPeerManager.ref,
      requestTracker = requestTracker,
      batchSize = 8,
      snapSyncController = snapSyncController.ref,
      cooldownConfig = testCooldownConfig
    )

    val h1 = kec256(ByteString("pivot-code"))

    coordinator ! ByteCodeCoordinator.StartByteCodeSync(Seq(h1))
    coordinator ! ByteCodeCoordinator.ByteCodePeerAvailable(peer)

    val send1 = networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd]
    val req1 = send1.message.asInstanceOf[GetByteCodesEnc].underlyingMsg

    // Empty response → peer enters cooldown
    resolveWorkerChild(coordinator) ! ByteCodeCoordinator.ByteCodesResponseMsg(
      ByteCodes(req1.requestId, Seq.empty)
    )

    // Verify cooldown is active — same peer should not dispatch immediately
    coordinator ! ByteCodeCoordinator.ByteCodePeerAvailable(peer)
    networkPeerManager.expectNoMessage(80.millis)

    // Pivot refresh clears both peerFailureCounts and peerCooldownUntilMillis (BUG-S1 fix)
    coordinator ! ByteCodeCoordinator.ByteCodePivotRefreshed

    // Peer should dispatch again immediately (no cooldown wait)
    coordinator ! ByteCodeCoordinator.ByteCodePeerAvailable(peer)
    networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd]
  }

  // ── K5-ext-b: Peer retention across pivot refresh (BUG-S1 fix 84290a175) ─────

  it should "dispatch new tasks to a peer retained in knownAvailablePeers after ByteCodePivotRefreshed" taggedAs UnitTest in {
    val evmCodeStorage = new TestEvmCodeStorage()
    val requestTracker = new SNAPRequestTracker()(classicSystem.scheduler)
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()
    val peerProbe = testKit.createTestProbe[Any]()

    val peer = PeerTestHelpers.createTestPeer("retained-peer", peerProbe.ref.toClassic)

    val coordinator = bccProps(
      evmCodeStorage = evmCodeStorage,
      networkPeerManager = networkPeerManager.ref,
      requestTracker = requestTracker,
      batchSize = 8,
      snapSyncController = snapSyncController.ref,
      cooldownConfig = testCooldownConfig
    )

    val h1 = kec256(ByteString("retained-code"))

    // Register peer with no initial tasks → peer enters knownAvailablePeers pool.
    coordinator ! ByteCodeCoordinator.StartByteCodeSync(Seq.empty)
    coordinator ! ByteCodeCoordinator.ByteCodePeerAvailable(peer)

    // Pivot refresh. In the old code knownAvailablePeers was cleared here (BUG-S1).
    // In the fixed code the peer is retained: bytecodes are content-addressed,
    // not state-root-dependent, so the peer can serve the same hashes after a pivot.
    coordinator ! ByteCodeCoordinator.ByteCodePivotRefreshed

    // Add tasks AFTER the pivot. AddByteCodeTasks queues work but does not call
    // tryRedispatchPendingTasks(). UpdateMaxInFlightPerPeer is the coordinator-internal
    // trigger that calls tryRedispatchPendingTasks(), which iterates knownAvailablePeers.
    // With the BUG-S1 fix the retained peer is found there and dispatch proceeds.
    // Without the fix (peer cleared) tryRedispatchPendingTasks() finds nobody → timeout.
    coordinator ! ByteCodeCoordinator.AddByteCodeTasks(Seq(h1))
    coordinator ! ByteCodeCoordinator.UpdateMaxInFlightPerPeer(testCooldownConfig.maxInFlightPerPeer)

    val send = networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd]
    val req = send.message.asInstanceOf[GetByteCodesEnc].underlyingMsg
    req.hashes shouldEqual Seq(h1)
  }

  // ---- J9: Corruption detection -----------------------------------------------

  it should "reject a bytecode whose kec256 hash is not in the requested list and put peer in cooldown" taggedAs UnitTest in {
    val evmCodeStorage = new TestEvmCodeStorage()
    val requestTracker = new SNAPRequestTracker()(classicSystem.scheduler)
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()
    val peerProbe = testKit.createTestProbe[Any]()

    val peer = PeerTestHelpers.createTestPeer("corrupt-peer", peerProbe.ref.toClassic)

    val coordinator = bccProps(
      evmCodeStorage = evmCodeStorage,
      networkPeerManager = networkPeerManager.ref,
      requestTracker = requestTracker,
      batchSize = 8,
      snapSyncController = snapSyncController.ref,
      cooldownConfig = testCooldownConfig
    )

    val realCode = ByteString("real-bytecode")
    val realHash = kec256(realCode)
    val corruptCode = ByteString("corrupted-bytecode-with-wrong-hash")
    // Sanity: corruptCode's hash must not equal realHash
    kec256(corruptCode) should not be realHash

    coordinator ! ByteCodeCoordinator.StartByteCodeSync(Seq(realHash))
    coordinator ! ByteCodeCoordinator.ByteCodePeerAvailable(peer)

    val send1 = networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd]
    val req1 = send1.message.asInstanceOf[GetByteCodesEnc].underlyingMsg
    req1.hashes shouldEqual Seq(realHash)

    // Respond with a code whose hash != realHash (corrupted / wrong code)
    resolveWorkerChild(coordinator) ! ByteCodeCoordinator.ByteCodesResponseMsg(
      ByteCodes(req1.requestId, Seq(corruptCode))
    )

    // Corrupted code must NOT be stored
    eventually(timeout(3.seconds), interval(100.millis)) {
      evmCodeStorage.get(realHash) shouldEqual None
    }

    // Peer must be in cooldown (invalid response)
    coordinator ! ByteCodeCoordinator.ByteCodePeerAvailable(peer)
    networkPeerManager.expectNoMessage(80.millis)

    // After cooldown, task is re-queued and peer dispatches again
    coordinator ! ByteCodeCoordinator.ByteCodePeerAvailable(peer)
    val send2 = networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd]
    val req2 = send2.message.asInstanceOf[GetByteCodesEnc].underlyingMsg
    req2.hashes shouldEqual Seq(realHash)
  }

  // #1164: ForceCompleteByteCodes drains pending+active tasks and reports completion. Without this, a small set of
  // unservable code hashes could hold the bytecode phase open indefinitely (the existing completion check requires
  // `pendingTasks.isEmpty && activeTasks.isEmpty` and there's no per-task failure cap).
  it should "force-complete bytecode sync, abandoning pending tasks (#1164)" taggedAs UnitTest in {
    val evmCodeStorage = new TestEvmCodeStorage()
    val requestTracker = new SNAPRequestTracker()(classicSystem.scheduler)
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()

    val coordinator = bccProps(
      evmCodeStorage = evmCodeStorage,
      networkPeerManager = networkPeerManager.ref,
      requestTracker = requestTracker,
      batchSize = 8,
      snapSyncController = snapSyncController.ref,
      cooldownConfig = testCooldownConfig
    )

    // Queue a non-trivial set of bytecode hashes. No peer is registered, so they'll sit in pendingTasks
    // forever — modelling the wedged state where peers can't serve a small unservable subset.
    val codeHashes = (1 to 10).map(i => kec256(ByteString(s"code$i")))
    coordinator ! ByteCodeCoordinator.StartByteCodeSync(codeHashes)
    coordinator ! ByteCodeCoordinator.NoMoreByteCodeTasks

    // Without the force-complete, ByteCodeCheckCompletion stays blocked because pendingTasks is non-empty.
    coordinator ! ByteCodeCoordinator.ByteCodeCheckCompletion
    snapSyncController.expectNoMessage(200.millis)

    // Force-complete drains the queue and signals the parent.
    coordinator ! ByteCodeCoordinator.ForceCompleteByteCodes
    snapSyncController.expectMessage(SNAPSyncController.ByteCodeSyncComplete)

    // spec 022 T033: every hash it abandoned is in the durable excluded set by the time it reports.
    val recorded = progressStore.excludedPage(None, 1000).map(_.subject).toSet
    codeHashes.foreach(h => recorded should contain(h))
  }

  // ForceCompleteByteCodes may fire while tasks are still in activeTasks (e.g. the 10-min stall
  // watchdog triggers mid-flight). The handler must drain active tasks AND return their workers
  // to the idle pool — otherwise the worker pool invariant (workers.size == idleWorkers.size +
  // activeTasks.size) would be broken, and any subsequent coordinator reuse would leak workers.
  it should "return active workers to idle pool on ForceCompleteByteCodes mid-flight" taggedAs UnitTest in {
    val evmCodeStorage = new TestEvmCodeStorage()
    val requestTracker = new SNAPRequestTracker()(classicSystem.scheduler)
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()
    val peerProbe = testKit.createTestProbe[Any]()
    val peer = PeerTestHelpers.createTestPeer("fc-active-peer", peerProbe.ref.toClassic)

    // batchSize=1 + maxInFlightPerPeer=2 → 2 tasks dispatched concurrently; 1 left pending
    val peerCooldown = testCooldownConfig.copy(maxInFlightPerPeer = 2)
    val coordinator = bccProps(
      evmCodeStorage = evmCodeStorage,
      networkPeerManager = networkPeerManager.ref,
      requestTracker = requestTracker,
      batchSize = 1,
      snapSyncController = snapSyncController.ref,
      cooldownConfig = peerCooldown
    )

    val hashes = (1 to 3).map(i => kec256(ByteString(s"fc-active-$i")))
    coordinator ! ByteCodeCoordinator.StartByteCodeSync(hashes)
    coordinator ! ByteCodeCoordinator.NoMoreByteCodeTasks
    coordinator ! ByteCodeCoordinator.ByteCodePeerAvailable(peer)

    // Two tasks dispatched concurrently (the two SendMessages prove 2 active in-flight requests).
    networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd]
    networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd]

    // Force-complete fires while 2 tasks are still in flight and 1 is pending. The Typed coordinator
    // exposes no `.underlyingActor`, so the pool invariant (active drained, workers returned to idle)
    // is verified indirectly: the parent receives ByteCodeSyncComplete, and the coordinator remains
    // operational and idle afterwards (a follow-up GetProgress returns 100% with no pending/active work).
    coordinator ! ByteCodeCoordinator.ForceCompleteByteCodes
    snapSyncController.expectMessage(SNAPSyncController.ByteCodeSyncComplete)

    // ByteCodeGetProgress now carries a typed replyTo; route the reply to the Classic test actor via .toTyped.
    coordinator ! ByteCodeCoordinator.ByteCodeGetProgress(statusProbe.ref)
    val progress = statusProbe.expectMessageType[ByteCodeCoordinator.ByteCodeProgress]
    // All queues drained by force-complete → progress reports complete (no pending/active tasks remain).
    progress.progress shouldBe 1.0
  }

  it should "handle ForceCompleteByteCodes when queues are already empty" taggedAs UnitTest in {
    val evmCodeStorage = new TestEvmCodeStorage()
    val requestTracker = new SNAPRequestTracker()(classicSystem.scheduler)
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()

    val coordinator = bccProps(
      evmCodeStorage = evmCodeStorage,
      networkPeerManager = networkPeerManager.ref,
      requestTracker = requestTracker,
      batchSize = 8,
      snapSyncController = snapSyncController.ref,
      cooldownConfig = testCooldownConfig
    )

    // Empty queue + ForceCompleteByteCodes: should still emit ByteCodeSyncComplete (idempotent terminal state).
    coordinator ! ByteCodeCoordinator.ForceCompleteByteCodes
    snapSyncController.expectMessage(SNAPSyncController.ByteCodeSyncComplete)
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
    val requestTracker = new SNAPRequestTracker()(classicSystem.scheduler)
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()
    val peerProbe = testKit.createTestProbe[Any]()

    val peer = PeerTestHelpers.createTestPeer("term-peer", peerProbe.ref.toClassic)

    val coordinator = bccProps(
      evmCodeStorage = evmCodeStorage,
      networkPeerManager = networkPeerManager.ref,
      requestTracker = requestTracker,
      batchSize = 8,
      snapSyncController = snapSyncController.ref,
      cooldownConfig = testCooldownConfig
    )

    coordinator ! ByteCodeCoordinator.StartByteCodeSync(Seq(kec256(ByteString("term-code"))))
    coordinator ! ByteCodeCoordinator.ByteCodePeerAvailable(peer)

    // Worker created and request dispatched
    networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd]

    // Resolve the single worker child (the Typed coordinator exposes no `.underlyingActor`)
    // and stop it permanently. `context.watchWith` delivers WorkerTerminated to the coordinator.
    val workerRef = resolveWorkerChild(coordinator)
    classicSystem.stop(workerRef.toClassic)

    // Task was re-queued after WorkerTerminated handling — providing peer again triggers re-dispatch,
    // which is observable proof the dead worker was removed and the task re-queued.
    coordinator ! ByteCodeCoordinator.ByteCodePeerAvailable(peer)
    networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd]
  }

  it should "remove a terminated worker with no active task without affecting pending dispatch" taggedAs UnitTest in {
    val evmCodeStorage = new TestEvmCodeStorage()
    val requestTracker = new SNAPRequestTracker()(classicSystem.scheduler)
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()
    val peerProbe = testKit.createTestProbe[Any]()

    val peer = PeerTestHelpers.createTestPeer("term-idle-peer", peerProbe.ref.toClassic)

    val coordinator = bccProps(
      evmCodeStorage = evmCodeStorage,
      networkPeerManager = networkPeerManager.ref,
      requestTracker = requestTracker,
      batchSize = 8,
      snapSyncController = snapSyncController.ref,
      cooldownConfig = testCooldownConfig
    )

    // Queue a task and dispatch — worker created
    coordinator ! ByteCodeCoordinator.StartByteCodeSync(Seq(kec256(ByteString("idle-code"))))
    coordinator ! ByteCodeCoordinator.ByteCodePeerAvailable(peer)
    networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd]

    // Resolve the worker child, then mark the sync done and stop the worker. The coordinator must
    // process WorkerTerminated without exception and stay operational (no `.underlyingActor` access).
    val workerRef = resolveWorkerChild(coordinator)
    coordinator ! ByteCodeCoordinator.NoMoreByteCodeTasks

    classicSystem.stop(workerRef.toClassic)

    // Coordinator stays operational after the worker stops — a GetProgress query still returns.
    coordinator ! ByteCodeCoordinator.ByteCodeGetProgress(statusProbe.ref)
    statusProbe.expectMessageType[ByteCodeCoordinator.ByteCodeProgress]
  }

  // ── P-0 regression: ByteCodePeerUnavailable must restore workers to idle pool ─
  // Run 25 root cause: ByteCodePeerUnavailable sent ByteCodeWorkerRelease to each in-flight
  // worker but never called markWorkerIdle. Workers stayed in `workers` (alive) but were absent
  // from `idleWorkers`, so dispatchIfPossible permanently returned None after the peer cascade.
  // This test would have caught that: after ByteCodePeerUnavailable, the idle count must
  // equal the pre-dispatch idle count (workers returned), and dispatch must succeed again.
  it should "restore workers to idle pool when a peer disconnects mid-flight" taggedAs UnitTest in {
    val evmCodeStorage = new TestEvmCodeStorage()
    val requestTracker = new SNAPRequestTracker()(classicSystem.scheduler)
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()
    val peerProbe = testKit.createTestProbe[Any]()

    val peerId = "unavail-peer"
    val peer = PeerTestHelpers.createTestPeer(peerId, peerProbe.ref.toClassic)

    // batchSize=1 so each hash → one task; maxInFlightPerPeer=3 → up to 3 concurrent workers
    val peerCooldown = testCooldownConfig.copy(maxInFlightPerPeer = 3)
    val coordinator = bccProps(
      evmCodeStorage = evmCodeStorage,
      networkPeerManager = networkPeerManager.ref,
      requestTracker = requestTracker,
      batchSize = 1,
      snapSyncController = snapSyncController.ref,
      cooldownConfig = peerCooldown
    )

    // Queue 3 hashes (one per task) and dispatch
    val hashes = (1 to 3).map(i => kec256(ByteString(s"unavail-code-$i")))
    coordinator ! ByteCodeCoordinator.StartByteCodeSync(hashes)
    coordinator ! ByteCodeCoordinator.ByteCodePeerAvailable(peer)

    // Three tasks dispatched — one worker per task. The three SendMessages prove 3 active in-flight
    // requests (formerly `workers.size == 3, idleWorkers empty` on internal state).
    networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd]
    networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd]
    networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd]

    // Peer disconnects — ByteCodePeerUnavailable must release all in-flight workers back to idle.
    coordinator ! ByteCodeCoordinator.ByteCodePeerUnavailable(peerId)

    // Observable proof of the P-0 fix (markWorkerIdle after release): dispatch succeeds again because
    // the released workers are back in the idle pool and the tasks were re-queued. Without the fix the
    // idle pool would be empty and no SendMessage would follow.
    coordinator ! ByteCodeCoordinator.ByteCodePeerAvailable(peer)
    networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd]
  }

  it should "emit ByteCodeBackpressureChanged when the pending queue crosses watermarks" taggedAs UnitTest in {
    val evmCodeStorage = new TestEvmCodeStorage()
    val requestTracker = new SNAPRequestTracker()(classicSystem.scheduler)
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()

    // Tiny watermarks: high=4, low=2. batchSize=1 so one hash → one task → one queue entry,
    // letting us drive the transition with a handful of hashes.
    val coordinator = bccProps(
      evmCodeStorage = evmCodeStorage,
      networkPeerManager = networkPeerManager.ref,
      requestTracker = requestTracker,
      batchSize = 1,
      snapSyncController = snapSyncController.ref,
      cooldownConfig = testCooldownConfig,
      backpressureHighWatermark = 4,
      backpressureLowWatermark = 2
    )

    // Queue 4 hashes → 4 tasks → crosses the high-water mark.
    val hashes = (1 to 4).map(i => kec256(ByteString(s"hash-$i")))
    coordinator ! ByteCodeCoordinator.AddByteCodeTasks(hashes)
    snapSyncController.expectMessage(SNAPSyncController.ByteCodeBackpressureChanged(paused = true))

    // Re-checking at the same depth must NOT emit a duplicate transition.
    coordinator ! ByteCodeCoordinator.ByteCodeCheckCompletion
    snapSyncController.expectNoMessage(500.millis)
  }
