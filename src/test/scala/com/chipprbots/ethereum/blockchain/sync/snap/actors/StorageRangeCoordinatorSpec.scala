package com.chipprbots.ethereum.blockchain.sync.snap.actors

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit

import org.apache.pekko.actor.testkit.typed.scaladsl.BehaviorTestKit
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.adapter.*

import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.concurrent.duration.*

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap.*
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.FlatSlotStorage
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetStorageRanges.GetStorageRangesEnc
import com.chipprbots.ethereum.network.p2p.messages.SNAP.StorageRanges
import com.chipprbots.ethereum.testing.PeerTestHelpers
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.testing.TestMptStorage
import com.chipprbots.ethereum.utils.ByteStringUtils.ByteStringOps

class StorageRangeCoordinatorSpec extends ScalaTestWithActorTestKit() with AnyFlatSpecLike with Matchers:

  implicit private val classicSystem: org.apache.pekko.actor.ActorSystem = system.classicSystem
  private val statusProbe = testKit.createTestProbe[StorageRangeCoordinator.SyncStatistics]()

  // StorageRangeCoordinator is a Typed actor (Group S3). These tests run in a Classic ActorSystem so they can keep
  // the established `system.actorOf` / `expectMsg` machinery; the coordinator is spawned through PropsAdapter to
  // bridge the Classic system to the Typed Behavior. Mirrors the `.props(...)` factory the actor previously exposed.
  private def srcProps(
      stateRoot: ByteString,
      networkPeerManager: org.apache.pekko.actor.typed.ActorRef[NetworkPeerManagerActor.Command],
      requestTracker: SNAPRequestTracker,
      mptStorage: TestMptStorage,
      flatSlotStorage: FlatSlotStorage,
      maxAccountsPerBatch: Int,
      maxInFlightRequests: Int,
      requestTimeout: FiniteDuration,
      snapSyncController: org.apache.pekko.actor.typed.ActorRef[SNAPSyncController.Command],
      initialMaxInFlightPerPeer: Int = 5,
      backpressureHighWatermark: Int = 100000,
      backpressureLowWatermark: Int = 50000
  ): org.apache.pekko.actor.typed.ActorRef[StorageRangeCoordinator.Command] =
    testKit.spawn(
      StorageRangeCoordinator(
        stateRoot = stateRoot,
        networkPeerManager = networkPeerManager,
        requestTracker = requestTracker,
        mptStorage = mptStorage,
        flatSlotStorage = flatSlotStorage,
        maxAccountsPerBatch = maxAccountsPerBatch,
        maxInFlightRequests = maxInFlightRequests,
        requestTimeout = requestTimeout,
        snapSyncController = snapSyncController,
        initialMaxInFlightPerPeer = initialMaxInFlightPerPeer,
        backpressureHighWatermark = backpressureHighWatermark,
        backpressureLowWatermark = backpressureLowWatermark
      )
    )

  // Typed `StorageGetProgress` carries a `replyTo: ActorRef[SyncStatistics]`. In these Classic tests the reply target
  // is the test actor (ImplicitSender); adapt it to a typed ref so the coordinator can reply.
  private def getProgress: StorageRangeCoordinator.StorageGetProgress =
    StorageRangeCoordinator.StorageGetProgress(statusProbe.ref)

  // White-box helper: build the `StorageRangeCoordinatorImpl` directly through a synchronous `BehaviorTestKit`,
  // capturing the Impl instance so tests can drive `private[actors]` accumulator/counter logic in isolation (the
  // Typed coordinator has no `.underlyingActor`). `kit.run(msg)` processes a Command synchronously on the same Impl.
  // `flatBatchEcOverride` defaults to `Some(parasitic)` because `StorageRangeCoordinatorImpl` evaluates
  // `flatBatchEc` eagerly in its constructor; under BehaviorTestKit `context.system.classicSystem` is unavailable,
  // so the production `storage-writer-dispatcher` lookup must never be reached. Parasitic also keeps any flush the
  // test does trigger synchronous (see `newCoordWithFlatBatch`).
  private def newImpl(
      stateRoot: ByteString,
      flatSlotStorage: FlatSlotStorage,
      snapSyncControllerRef: org.apache.pekko.actor.typed.ActorRef[SNAPSyncController.Command],
      flatBatchEntryThreshold: Int = 1000,
      flatBatchEcOverride: Option[scala.concurrent.ExecutionContext] = Some(
        scala.concurrent.ExecutionContext.parasitic
      ),
      maxAccountsPerBatch: Int = 8,
      maxInFlightRequests: Int = 8,
      backpressureHighWatermark: Int = 100000,
      backpressureLowWatermark: Int = 50000,
      // Defaults to the constructor's own default (true) to keep every EXISTING caller of newImpl
      // unchanged. Tests that need to inspect `pendingAccountTries` (the ordering-gate tests) must
      // pass `false` explicitly — with deferred merkleization on, applyReadyStorageChunk never
      // builds a trie at all (flat-slot writes only).
      deferredMerkleization: Boolean = true
  ): (StorageRangeCoordinatorImpl, BehaviorTestKit[StorageRangeCoordinator.Command]) =
    var captured: StorageRangeCoordinatorImpl = null
    val behavior = Behaviors.setup[StorageRangeCoordinator.Command] { ctx =>
      Behaviors.withTimers { timers =>
        captured = new StorageRangeCoordinatorImpl(
          ctx,
          timers,
          initialStateRoot = stateRoot,
          networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]().ref,
          requestTracker = new SNAPRequestTracker()(classicSystem.scheduler),
          mptStorage = new TestMptStorage(),
          flatSlotStorage = flatSlotStorage,
          maxAccountsPerBatch = maxAccountsPerBatch,
          maxInFlightRequests = maxInFlightRequests,
          requestTimeout = 30.seconds,
          snapSyncController = snapSyncControllerRef,
          flatBatchEntryThreshold = flatBatchEntryThreshold,
          flatBatchEcOverride = flatBatchEcOverride,
          backpressureHighWatermark = backpressureHighWatermark,
          backpressureLowWatermark = backpressureLowWatermark,
          deferredMerkleization = deferredMerkleization
        )
        captured.start()
      }
    }
    val kit = BehaviorTestKit(behavior)
    (captured, kit)

  "StorageRangeCoordinator" should "initialize correctly" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-state-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(classicSystem.scheduler)
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()

    val coordinator = srcProps(
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

    coordinator should not be null
  }

  it should "handle peer availability" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-state-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(classicSystem.scheduler)
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()
    val peerProbe = testKit.createTestProbe[Any]()

    val peer = PeerTestHelpers.createTestPeer("test-peer", peerProbe.ref.toClassic)

    val coordinator = srcProps(
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

    coordinator ! StorageRangeCoordinator.StartStorageRangeSync(stateRoot)
    coordinator ! StorageRangeCoordinator.StoragePeerAvailable(peer)

    // Should handle peer availability (may or may not send request depending on tasks)
    coordinator ! getProgress
    statusProbe.expectMessageType[StorageRangeCoordinator.SyncStatistics]
  }

  it should "handle task completion" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-state-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(classicSystem.scheduler)
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()

    val coordinator = srcProps(
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

    coordinator ! StorageRangeCoordinator.StorageTaskComplete(BigInt(123), Right(10))

    // Coordinator should handle completion
    coordinator ! getProgress
    statusProbe.expectMessageType[StorageRangeCoordinator.SyncStatistics]
  }

  it should "report completion when no storage tasks" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-state-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(classicSystem.scheduler)
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()

    val coordinator = srcProps(
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

    coordinator ! StorageRangeCoordinator.StartStorageRangeSync(stateRoot)

    // Signal that no more tasks will arrive (sentinel pattern)
    coordinator ! StorageRangeCoordinator.NoMoreStorageTasks

    coordinator ! StorageRangeCoordinator.StorageCheckCompletion

    // Should complete immediately since no tasks and sentinel received
    snapSyncController.expectMessage(SNAPSyncController.StorageRangeSyncComplete)
  }

  it should "handle task failures" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-state-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(classicSystem.scheduler)
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()

    val coordinator = srcProps(
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

    coordinator ! StorageRangeCoordinator.StorageTaskFailed(BigInt(123), "Test failure")

    // Coordinator should still be operational
    coordinator ! getProgress
    statusProbe.expectMessageType[StorageRangeCoordinator.SyncStatistics]
  }

  it should "accept AddStorageTasks and remain operational" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-state-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(classicSystem.scheduler)
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()

    val coordinator = srcProps(
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

    val accountHash1 = kec256(ByteString("account-1"))
    val storageRoot1 = kec256(ByteString("storage-root-1"))
    val task = StorageTask.createStorageTask(accountHash1, storageRoot1)

    coordinator ! StorageRangeCoordinator.AddStorageTasks(Seq(task))

    // Should remain operational after adding tasks
    coordinator ! getProgress
    statusProbe.expectMessageType[StorageRangeCoordinator.SyncStatistics]
  }

  it should "accept StoragePivotRefreshed and update state root" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("old-state-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(classicSystem.scheduler)
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()

    val coordinator = srcProps(
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

    val newStateRoot = kec256(ByteString("new-state-root"))
    coordinator ! StorageRangeCoordinator.StoragePivotRefreshed(newStateRoot)

    // Coordinator should still respond to progress queries after pivot refresh
    coordinator ! getProgress
    statusProbe.expectMessageType[StorageRangeCoordinator.SyncStatistics]
  }

  it should "signal StorageRangeSyncComplete to controller when NoMoreStorageTasks received with no pending tasks" taggedAs UnitTest in {
    // Verifies the sentinel pattern: when account download finishes with no storage accounts,
    // the coordinator must complete immediately on NoMoreStorageTasks + StorageCheckCompletion.
    val stateRoot = kec256(ByteString("empty-state-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(classicSystem.scheduler)
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()

    val coordinator = srcProps(
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

    coordinator ! StorageRangeCoordinator.StartStorageRangeSync(stateRoot)
    coordinator ! StorageRangeCoordinator.NoMoreStorageTasks
    coordinator ! StorageRangeCoordinator.StorageCheckCompletion

    snapSyncController.expectMessage(SNAPSyncController.StorageRangeSyncComplete)
  }

  // ── K5: Proof-of-absence (BUG fix b38050e49) ──────────────────────────────

  it should "accept proof-of-absence response (0 slots + proof) and not mark peer stateless" taggedAs UnitTest in {
    // Fix b38050e49: when a peer returns 0 slots + non-empty proof for a single-account batch,
    // the coordinator must treat it as a valid cryptographic proof that the account has no
    // storage, complete the task, and NOT mark the peer stateless. The peer must be immediately
    // eligible to receive the next task via dispatchIfPossible().
    val stateRoot = kec256(ByteString("proof-of-absence-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(classicSystem.scheduler)
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()
    val peerProbe = testKit.createTestProbe[Any]()

    val peer = PeerTestHelpers.createTestPeer("storage-peer-poa", peerProbe.ref.toClassic)

    val account1 = kec256(ByteString("account-poa-1"))
    val account2 = kec256(ByteString("account-poa-2"))
    val storageRoot = kec256(ByteString("storage-root-poa"))

    val task1 = StorageTask.createStorageTask(account1, storageRoot)
    val task2 = StorageTask.createStorageTask(account2, storageRoot)

    // maxAccountsPerBatch=1 forces single-account batches → tasks.size==1 in processStorageRanges,
    // satisfying the proof-of-absence guard (response.proof.nonEmpty && tasks.size == 1).
    // initialMaxInFlightPerPeer=1 ensures only one request is in-flight at a time so the
    // second request is sent only after the first is resolved.
    val coordinator = srcProps(
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

    coordinator ! StorageRangeCoordinator.StartStorageRangeSync(stateRoot)
    coordinator ! StorageRangeCoordinator.AddStorageTasks(Seq(task1, task2))
    coordinator ! StorageRangeCoordinator.StoragePeerAvailable(peer)

    // Coordinator dispatches task1 to peer
    val send1 = networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd]
    val req1 = send1.message.asInstanceOf[GetStorageRangesEnc].underlyingMsg
    req1.accountHashes should have size 1
    req1.accountHashes.head shouldEqual account1

    // Inject proof-of-absence: 0 slots, 1 proof node — valid snap/1 empty-storage proof
    val dummyProofNode = ByteString(Array.fill(32)(0xab.toByte))
    coordinator ! StorageRangeCoordinator.StorageRangesResponseMsg(
      StorageRanges(req1.requestId, slots = Seq.empty, proof = Seq(dummyProofNode))
    )

    // Peer is NOT stateless — coordinator immediately pipelines task2 to the same peer
    val send2 = networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd]
    val req2 = send2.message.asInstanceOf[GetStorageRangesEnc].underlyingMsg
    req2.accountHashes should have size 1
    req2.accountHashes.head shouldEqual account2

    // Progress is now correctly reported for the completed (proof-of-absence) account1: 1 of the
    // 2 contracts added via AddStorageTasks. Before the ordering-gate fix, handleProofOfAbsence
    // bypassed completedAccountCount entirely (see applyOrderedStorageChunk /
    // applyReadyStorageChunk in StorageRangeCoordinator.scala), so this message was silently never
    // sent for a proof-of-absence account — progress reporting under-counted completed contracts.
    snapSyncController.expectMessage(SNAPSyncController.ProgressStorageContracts(1, 2))
    // No pivot-refresh stall signal: peer served a valid proof-of-absence response.
    // Coordinator dispatches task2 immediately; no PivotStateUnservable expected.
    snapSyncController.expectNoMessage(300.millis)
  }

  // ── Category 1d: ForceCompleteStorage escape valve ─────────────────────────

  it should "signal StorageRangeSyncForceCompleted immediately on ForceCompleteStorage even with pending tasks" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("force-complete-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(classicSystem.scheduler)
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()

    val coordinator = srcProps(
      stateRoot = stateRoot,
      networkPeerManager = networkPeerManager.ref,
      requestTracker = requestTracker,
      mptStorage = storage,
      flatSlotStorage = new FlatSlotStorage(EphemDataSource()),
      maxAccountsPerBatch = 8,
      maxInFlightRequests = 4,
      requestTimeout = 30.seconds,
      snapSyncController = snapSyncController.ref
    )

    // Add tasks that will not be dispatched (no peer)
    val accountHash = kec256(ByteString("account-force"))
    val storageRoot = kec256(ByteString("storage-root-force"))
    val task = StorageTask.createStorageTask(accountHash, storageRoot)
    coordinator ! StorageRangeCoordinator.StartStorageRangeSync(stateRoot)
    coordinator ! StorageRangeCoordinator.AddStorageTasks(Seq(task))

    // Force completion without a peer — should immediately promote to healing
    coordinator ! StorageRangeCoordinator.ForceCompleteStorage

    snapSyncController.expectMessage(SNAPSyncController.StorageRangeSyncForceCompleted)
  }

  // ── K5: No false stall signal when task queue is empty (BUG fix b07c363e9) ─

  it should "not emit PivotStateUnservable when no storage tasks are pending" taggedAs UnitTest in {
    // Fix b07c363e9: PivotStateUnservable must only be requested when tasks.nonEmpty.
    // During the account-range phase (before any storage tasks arrive), a StorageCheckCompletion
    // tick must be a no-op — not trigger a spurious pivot refresh that aborts the sync.
    val stateRoot = kec256(ByteString("no-stall-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(classicSystem.scheduler)
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()
    val peerProbe = testKit.createTestProbe[Any]()

    val peer = PeerTestHelpers.createTestPeer("storage-peer-nostall", peerProbe.ref.toClassic)

    val coordinator = srcProps(
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

    coordinator ! StorageRangeCoordinator.StartStorageRangeSync(stateRoot)
    // Peer known but no tasks queued — simulates the window during the account-range phase
    coordinator ! StorageRangeCoordinator.StoragePeerAvailable(peer)
    // StorageCheckCompletion tick that arrives before any storage tasks
    coordinator ! StorageRangeCoordinator.StorageCheckCompletion

    // Neither PivotStateUnservable nor StorageRangeSyncComplete should be sent:
    // tasks.isEmpty → maybeRequestPivotRefresh() not called, isComplete=false (no sentinel)
    snapSyncController.expectNoMessage(300.millis)
  }

  // ── Fix 2: consecutiveTaskFailures reset on StoragePivotRefreshed ────────────
  // Verifies that a pivot refresh zeroes the consecutive failure counter, preventing
  // pivot-invalidated task failures (counted during AccountRange phase) from
  // triggering a premature ForceComplete during the subsequent ByteCode+Storage phase.
  // In Run 23 this counter reached 102-104 during AccountRange and triggered
  // StorageRangeSyncForceCompleted at 14:28:43, permanently blocking recovery.

  it should "reset consecutiveTaskFailures to 0 on StoragePivotRefreshed" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("reset-consec-root"))
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()

    val (impl, kit) = newImpl(
      stateRoot = stateRoot,
      flatSlotStorage = new FlatSlotStorage(EphemDataSource()),
      snapSyncControllerRef = snapSyncController.ref
    )

    // Simulate failures accumulated during AccountRange phase (before storage phase begins)
    impl.consecutiveTaskFailures = 50

    val newStateRoot = kec256(ByteString("pivot-reset-root"))
    kit.run(StorageRangeCoordinator.StoragePivotRefreshed(newStateRoot))

    // BehaviorTestKit processes synchronously — counter must be 0 immediately after
    impl.consecutiveTaskFailures shouldBe 0
  }

  it should "not trigger ForceCompleteStorage when failures accumulated before a pivot are reset" taggedAs UnitTest in {
    // Regression: Run 23 had 102-104 consecutive failures from pivot-invalidated tasks.
    // The threshold is 100. Without the reset, those failures triggered ForceComplete during
    // AccountRange, permanently setting storagePhaseComplete=true before storage even started.
    // With the reset, a pivot refresh zeroes the counter so failures before and after a pivot
    // are counted independently — only a sustained run of 100 failures from one pivot epoch triggers.
    val stateRoot = kec256(ByteString("no-force-after-pivot-root"))
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()

    val (impl, kit) = newImpl(
      stateRoot = stateRoot,
      flatSlotStorage = new FlatSlotStorage(EphemDataSource()),
      snapSyncControllerRef = snapSyncController.ref
    )

    // Accumulate 99 failures — one below the 100-failure force-complete threshold
    impl.consecutiveTaskFailures = 99

    // Pivot refresh (mirrors what happens when SNAPSyncController updates the pivot block)
    val newRoot = kec256(ByteString("mid-session-pivot"))
    kit.run(StorageRangeCoordinator.StoragePivotRefreshed(newRoot))

    // Counter is now 0. Set it to 99 again (simulating another near-threshold accumulation
    // after the pivot — still one below the threshold from this epoch).
    impl.consecutiveTaskFailures = 99

    // No ForceCompleteStorage should have been sent across either epoch
    snapSyncController.expectNoMessage(300.millis)
  }

  // ========================================
  // Flat-batch aggregator (issue #1165)
  // ========================================

  /** Helper: build the Impl synchronously (via `newImpl`/BehaviorTestKit) with an override EC and small threshold so
    * flat-batch behaviour is observable without spinning up the storage-writer-dispatcher. Returns the Impl (for
    * white-box field access), the BehaviorTestKit (for synchronous `kit.run(msg)` message processing), and the
    * controller probe.
    */
  private def newCoordWithFlatBatch(
      flatSlotStorage: FlatSlotStorage,
      threshold: Int,
      stateRootArg: ByteString = kec256(ByteString("flat-batch-test-root"))
  ): (
      StorageRangeCoordinatorImpl,
      BehaviorTestKit[StorageRangeCoordinator.Command],
      org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe[SNAPSyncController.Command]
  ) =
    val controller = testKit.createTestProbe[SNAPSyncController.Command]()
    // `parasitic` runs the flush Future + its onComplete callback inline, so the resulting
    // `FlatBatchFlushComplete` is already in the BehaviorTestKit self-inbox when the staging call returns.
    // `kit.runOne()` then processes it synchronously (decrementing inFlightFlatBatches).
    val (impl, kit) = newImpl(
      stateRoot = stateRootArg,
      flatSlotStorage = flatSlotStorage,
      snapSyncControllerRef = controller.ref,
      flatBatchEntryThreshold = threshold,
      flatBatchEcOverride = Some(scala.concurrent.ExecutionContext.parasitic)
    )
    (impl, kit, controller)

  /** Helper: synthesize an account-hash + slots payload of `slotsPerAccount` entries. */
  private def fakeContract(
      seed: Int,
      slotsPerAccount: Int
  ): (ByteString, mutable.ArrayBuffer[(ByteString, ByteString)]) =
    val accountHash = kec256(ByteString(s"acct-$seed"))
    val slots = mutable.ArrayBuffer.empty[(ByteString, ByteString)]
    var i = 0
    while i < slotsPerAccount do
      val slotHash = kec256(ByteString(s"slot-$seed-$i"))
      val slotValue = ByteString(s"value-$seed-$i".getBytes)
      slots += ((slotHash, slotValue))
      i += 1
    (accountHash, slots)

  // Drain all self-sent Commands sitting in the BehaviorTestKit's self-inbox (e.g. FlatBatchFlushComplete
  // produced by the parasitic flush, plus chained StorageCheckCompletion ticks), processing each on the Impl.
  private def drainSelf(kit: BehaviorTestKit[StorageRangeCoordinator.Command]): Unit =
    while kit.selfInbox().hasMessages do kit.runOne()

  it should "buffer small-contract slots in the accumulator without immediate commit" taggedAs UnitTest in {
    val flatSlots = new FlatSlotStorage(EphemDataSource())
    val (impl, _, _) = newCoordWithFlatBatch(flatSlots, threshold = 100)

    val (accountHash, slots) = fakeContract(seed = 1, slotsPerAccount = 3)
    impl.stageFlatSlotChunk(accountHash, slots.toSeq)

    impl.pendingFlatBatchEntries shouldBe 3
    impl.pendingFlatBatchAccounts.size shouldBe 1
    impl.inFlightFlatBatches shouldBe 0

    // Nothing was committed — FlatSlotStorage is still empty for our keys.
    flatSlots.getSlot(accountHash, slots.head._1) shouldBe None
  }

  it should "flush exactly once when the threshold is crossed and persist all buffered slots" taggedAs UnitTest in {
    val flatSlots = new FlatSlotStorage(EphemDataSource())
    val (impl, kit, _) = newCoordWithFlatBatch(flatSlots, threshold = 5)

    // Three contracts, 2 slots each = 6 total slots, crossing the 5-entry threshold.
    val contracts = (1 to 3).map(i => fakeContract(seed = i, slotsPerAccount = 2))
    contracts.foreach { case (h, s) => impl.stageFlatSlotChunk(h, s.toSeq) }

    // The parasitic flush already persisted the batch and enqueued FlatBatchFlushComplete; drain it.
    drainSelf(kit)
    impl.inFlightFlatBatches shouldBe 0

    // After flush, all 6 slots are durable.
    contracts.foreach { case (accountHash, slots) =>
      slots.foreach { case (slotHash, value) =>
        flatSlots.getSlot(accountHash, slotHash) shouldBe Some(value)
      }
    }

    // Accumulator was reset.
    impl.pendingFlatBatchAccounts shouldBe empty
    impl.pendingFlatBatchEntries shouldBe 0
  }

  it should "not trigger a flush when accumulator stays below threshold" taggedAs UnitTest in {
    val flatSlots = new FlatSlotStorage(EphemDataSource())
    val (impl, _, _) = newCoordWithFlatBatch(flatSlots, threshold = 1000)

    val (accountHash, slots) = fakeContract(seed = 42, slotsPerAccount = 50)
    impl.stageFlatSlotChunk(accountHash, slots.toSeq)

    impl.pendingFlatBatchEntries shouldBe 50
    impl.inFlightFlatBatches shouldBe 0
    flatSlots.getSlot(accountHash, slots.head._1) shouldBe None
  }

  it should "flush remaining accumulator on ForceCompleteStorage" taggedAs UnitTest in {
    val flatSlots = new FlatSlotStorage(EphemDataSource())
    val (impl, kit, controller) = newCoordWithFlatBatch(flatSlots, threshold = 1000)

    val (accountHash, slots) = fakeContract(seed = 99, slotsPerAccount = 4)
    impl.stageFlatSlotChunk(accountHash, slots.toSeq)
    impl.pendingFlatBatchEntries shouldBe 4

    kit.run(StorageRangeCoordinator.ForceCompleteStorage)

    drainSelf(kit)
    impl.inFlightFlatBatches shouldBe 0
    slots.foreach { case (slotHash, value) =>
      flatSlots.getSlot(accountHash, slotHash) shouldBe Some(value)
    }
    controller.expectMessage(SNAPSyncController.StorageRangeSyncForceCompleted)
  }

  it should "drop bookkeeping for FlatBatchFlushComplete from a stale state root" taggedAs UnitTest in {
    val flatSlots = new FlatSlotStorage(EphemDataSource())
    val (impl, kit, _) = newCoordWithFlatBatch(flatSlots, threshold = 1000)

    impl.inFlightFlatBatches = 1
    val staleRoot = kec256(ByteString("a-stale-root"))

    kit.run(StorageRangeCoordinator.FlatBatchFlushComplete(staleRoot, entryCount = 7, elapsedMs = 5L))

    impl.inFlightFlatBatches shouldBe 0
  }

  it should "flush the accumulator before mutating stateRoot on StoragePivotRefreshed" taggedAs UnitTest in {
    val flatSlots = new FlatSlotStorage(EphemDataSource())
    val oldRoot = kec256(ByteString("old-root"))
    val newRoot = kec256(ByteString("new-root"))
    val (impl, kit, _) = newCoordWithFlatBatch(flatSlots, threshold = 1000, stateRootArg = oldRoot)

    // Buffer some data while stateRoot == oldRoot.
    val (accountHash, slots) = fakeContract(seed = 7, slotsPerAccount = 4)
    impl.stageFlatSlotChunk(accountHash, slots.toSeq)
    impl.pendingFlatBatchEntries shouldBe 4

    // Pivot refresh: must commit the accumulator THEN advance the root.
    kit.run(StorageRangeCoordinator.StoragePivotRefreshed(newRoot))

    drainSelf(kit)
    impl.inFlightFlatBatches shouldBe 0

    // Data made it to disk despite the pivot refresh.
    slots.foreach { case (slotHash, value) =>
      flatSlots.getSlot(accountHash, slotHash) shouldBe Some(value)
    }
    impl.pendingFlatBatchAccounts shouldBe empty
  }

  it should "decrement in-flight count on FlatBatchFlushFailed and stay operational" taggedAs UnitTest in {
    val flatSlots = new FlatSlotStorage(EphemDataSource())
    val (impl, kit, _) = newCoordWithFlatBatch(flatSlots, threshold = 1000)

    impl.inFlightFlatBatches = 2

    kit.run(
      StorageRangeCoordinator.FlatBatchFlushFailed(
        forStateRoot = kec256(ByteString("flat-batch-test-root")),
        entryCount = 11,
        error = "synthetic write failure"
      )
    )

    impl.inFlightFlatBatches shouldBe 1

    // Still operational: a progress query yields a reply via the typed replyTo probe.
    val probe = org.apache.pekko.actor.testkit.typed.scaladsl.TestInbox[StorageRangeCoordinator.SyncStatistics]()
    kit.run(StorageRangeCoordinator.StorageGetProgress(probe.ref))
    probe.receiveMessage()
  }

  // -----------------------------------------------------------------------
  // StackTrie write-path (Step 4 of `snap-stacktrie-port` plan)
  // -----------------------------------------------------------------------
  // Verify the coordinator constructs and operates normally with the flag
  // enabled. The actual per-contract trie-building happens asynchronously on
  // `trieBuilderEc` (a separate thread pool); end-to-end verification of the
  // node emissions is exercised by the Sepolia test run that follows Step 5.

  it should "construct and accept lifecycle messages" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("storage-stacktrie-construct-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(classicSystem.scheduler)
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()

    val coordinator = srcProps(
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

    coordinator should not be null

    // Smoke: accept the basic lifecycle messages without error.
    coordinator ! StorageRangeCoordinator.StartStorageRangeSync(stateRoot)
    coordinator ! getProgress
    statusProbe.expectMessageType[StorageRangeCoordinator.SyncStatistics]

    testKit.stop(coordinator)
  }

  // ── Back-pressure on the pending storage-task queue ───────────────────────
  // Regression coverage for the sepolia OOM (May 13 2026): account-range
  // download produced storage tasks faster than peers could serve them, growing
  // the queue to ~2.8M entries before the JVM hit Xmx. The coordinator now emits
  // StorageBackpressureChanged(paused = true) when the queue crosses the
  // high-water mark, and StorageBackpressureChanged(paused = false) when it
  // drains below the low-water mark. SNAPSyncController forwards both to
  // AccountRangeCoordinator so account workers stop producing new tasks.
  it should "emit StorageBackpressureChanged when the pending queue crosses watermarks" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("backpressure-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(classicSystem.scheduler)
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()

    // Tiny watermarks so the test can drive the transition without enqueuing 100K tasks.
    val coordinator = srcProps(
      stateRoot = stateRoot,
      networkPeerManager = networkPeerManager.ref,
      requestTracker = requestTracker,
      mptStorage = storage,
      flatSlotStorage = new FlatSlotStorage(EphemDataSource()),
      maxAccountsPerBatch = 8,
      maxInFlightRequests = 8,
      requestTimeout = 30.seconds,
      snapSyncController = snapSyncController.ref,
      backpressureHighWatermark = 5,
      backpressureLowWatermark = 2
    )

    coordinator ! StorageRangeCoordinator.StartStorageRangeSync(stateRoot)

    // Build a small batch of tasks, enough to push the queue to 5 entries.
    val tasks =
      (1 to 5).map(i =>
        StorageTask.createStorageTask(
          accountHash = kec256(ByteString(s"acct-$i")),
          storageRoot = kec256(ByteString(s"root-$i"))
        )
      )
    coordinator ! StorageRangeCoordinator.AddStorageTasks(tasks)

    // Crossing the high-water mark triggers a pause signal upward.
    snapSyncController.expectMessage(SNAPSyncController.StorageBackpressureChanged(paused = true))

    // Re-checking with the same depth must NOT emit another transition (no duplicate signals).
    coordinator ! StorageRangeCoordinator.StorageCheckCompletion
    snapSyncController.expectNoMessage(500.millis)
  }

  it should "release back-pressure once the queue drains below the low-water mark" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("backpressure-release-root"))
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()

    val (impl, kit) = newImpl(
      stateRoot = stateRoot,
      flatSlotStorage = new FlatSlotStorage(EphemDataSource()),
      snapSyncControllerRef = snapSyncController.ref,
      backpressureHighWatermark = 5,
      backpressureLowWatermark = 2
    )

    kit.run(StorageRangeCoordinator.StartStorageRangeSync(stateRoot))

    // Drive across the high-water mark first.
    val tasks =
      (1 to 5).map(i =>
        StorageTask.createStorageTask(
          accountHash = kec256(ByteString(s"acct-$i")),
          storageRoot = kec256(ByteString(s"root-$i"))
        )
      )
    kit.run(StorageRangeCoordinator.AddStorageTasks(tasks))
    snapSyncController.expectMessage(SNAPSyncController.StorageBackpressureChanged(paused = true))

    // Drain the underlying queue to 2 entries (≤ low-water mark) and trigger a check.
    val q = impl.tasks
    while q.size > 2 do q.dequeue()

    kit.run(StorageRangeCoordinator.StorageCheckCompletion)
    snapSyncController.expectMessage(SNAPSyncController.StorageBackpressureChanged(paused = false))
  }

  // ========================================
  // Streaming storage-trie memory bound
  // ========================================
  //
  // Verifies the per-account `SnapHashTrie` stays bounded across continuation responses
  // for a single contract: even with thousands of slots, the in-memory batch never crosses
  // the 8 MiB flush threshold (it auto-flushes), and `commit()` returns a stable root.

  // ========================================
  // Spec 005 — Storage subtask parallelism
  // ========================================

  it should "start with empty accountSubtaskCounters (no subtask tracking before any response)" taggedAs UnitTest in {
    val (impl, _) = newImpl(
      stateRoot = kec256(ByteString("subtask-init-root")),
      flatSlotStorage = new FlatSlotStorage(EphemDataSource()),
      snapSyncControllerRef = testKit.createTestProbe[SNAPSyncController.Command]().ref
    )

    impl.accountSubtaskCounters shouldBe empty
    impl.completedAccountCount shouldBe 0L
  }

  it should "increment completedAccountCount only once when all subtasks for an account complete" taggedAs UnitTest in {
    val accountHash = kec256(ByteString("large-contract"))
    val (impl, _) = newImpl(
      stateRoot = kec256(ByteString("subtask-complete-root")),
      flatSlotStorage = new FlatSlotStorage(EphemDataSource()),
      snapSyncControllerRef = testKit.createTestProbe[SNAPSyncController.Command]().ref
    )

    // Simulate: 3 subtasks registered for a large-storage account
    impl.accountSubtaskCounters(accountHash) = (3, 0)
    impl.completedAccountCount shouldBe 0L

    // First subtask completes — still 2 remaining; count must NOT advance
    impl.recordSubtaskCompletion(accountHash)
    impl.completedAccountCount shouldBe 0L
    impl.accountSubtaskCounters.get(accountHash) shouldBe Some((3, 1))

    // Second subtask completes — 1 remaining
    impl.recordSubtaskCompletion(accountHash)
    impl.completedAccountCount shouldBe 0L
    impl.accountSubtaskCounters.get(accountHash) shouldBe Some((3, 2))

    // Third (final) subtask completes — all done; count advances and entry is removed
    impl.recordSubtaskCompletion(accountHash)
    impl.completedAccountCount shouldBe 1L
    impl.accountSubtaskCounters.get(accountHash) shouldBe None
  }

  it should "increment completedAccountCount directly (no subtasks) when no counter entry exists" taggedAs UnitTest in {
    val accountHash = kec256(ByteString("small-contract"))
    val (impl, _) = newImpl(
      stateRoot = kec256(ByteString("subtask-nosplit-root")),
      flatSlotStorage = new FlatSlotStorage(EphemDataSource()),
      snapSyncControllerRef = testKit.createTestProbe[SNAPSyncController.Command]().ref
    )

    // No subtask entry for this account — small contract, single task, no split
    impl.accountSubtaskCounters shouldBe empty

    impl.recordSubtaskCompletion(accountHash)
    impl.completedAccountCount shouldBe 1L
    impl.accountSubtaskCounters shouldBe empty
  }

  it should "handle independent subtask completions for two large-storage contracts without cross-contamination" taggedAs UnitTest in {
    val acctA = kec256(ByteString("contract-A"))
    val acctB = kec256(ByteString("contract-B"))
    val (impl, _) = newImpl(
      stateRoot = kec256(ByteString("subtask-two-accts-root")),
      flatSlotStorage = new FlatSlotStorage(EphemDataSource()),
      snapSyncControllerRef = testKit.createTestProbe[SNAPSyncController.Command]().ref
    )

    // Register 2 subtasks for A, 3 for B
    impl.accountSubtaskCounters(acctA) = (2, 0)
    impl.accountSubtaskCounters(acctB) = (3, 0)

    // Complete A subtask 1 → A not done; B not done
    impl.recordSubtaskCompletion(acctA)
    impl.completedAccountCount shouldBe 0L
    impl.accountSubtaskCounters.get(acctA) shouldBe Some((2, 1))

    // Complete B subtask 1 → nothing done
    impl.recordSubtaskCompletion(acctB)
    impl.completedAccountCount shouldBe 0L

    // Complete A subtask 2 → A done; count=1; B still incomplete
    impl.recordSubtaskCompletion(acctA)
    impl.completedAccountCount shouldBe 1L
    impl.accountSubtaskCounters.get(acctA) shouldBe None
    impl.accountSubtaskCounters.get(acctB).isDefined shouldBe true

    // Complete B subtasks 2 and 3 → B done; count=2
    impl.recordSubtaskCompletion(acctB)
    impl.recordSubtaskCompletion(acctB)
    impl.completedAccountCount shouldBe 2L
    impl.accountSubtaskCounters.get(acctB) shouldBe None
  }

  it should "bound per-account streaming trie memory across continuation responses" taggedAs UnitTest in {
    import com.chipprbots.ethereum.blockchain.sync.snap.SnapHashTrie

    val accumulated = mutable.ArrayBuffer.empty[(ByteString, Array[Byte])]
    val trie = new SnapHashTrie(batch => accumulated ++= batch)

    // Three "responses" of 1000 slots each, strictly ascending — mirrors the wire-level
    // monotonicity that `SNAPRequestTracker.validateStorageRanges` enforces.
    val totalSlots = 3000
    val sortedSlotKeys = (0 until totalSlots).map { i =>
      // Use big-endian-encoded 32-byte keys so sort order = numeric order
      val keyBytes = new Array[Byte](32)
      java.nio.ByteBuffer.wrap(keyBytes).putInt(28, i)
      ByteString(keyBytes)
    }
    val slotsByResponse = sortedSlotKeys.grouped(1000).toSeq

    slotsByResponse.foreach { batch =>
      batch.foreach { slotHash =>
        val value = ByteString(s"slotvalue-${slotHash.takeRight(4).toHex}".getBytes)
        trie.update(slotHash.toArray, value.toArray)
      }
      // After each "response", the in-heap batch should never exceed the flush threshold.
      trie.pendingBatchBytes should be <= SnapHashTrie.DefaultBatchSizeBytes.toLong
    }

    val root = trie.commit()
    root should not be ByteString.empty
    root.size shouldBe 32

    // After commit, all nodes have been emitted to the accumulator.
    accumulated.nonEmpty shouldBe true
  }

  // ========================================
  // Storage-ordering gate (StackTrie "keys must be strictly ascending" crash fix)
  // ========================================
  //
  // Root cause: storageConcurrency (16) parallel subtask chunks for one large-storage account
  // share a single per-account StackTrie (pendingAccountTries, keyed only by accountHash) that
  // requires strictly-ascending inserts across the FULL account key space. Nothing in
  // dispatch/response handling guaranteed sibling chunk responses were PROCESSED back in range
  // order — requestNextRanges' acceptsNewAccount explicitly allows unlimited concurrent dispatch
  // once an account's trie exists, so a peer serving a higher sub-range could (and in the
  // 2026-09-27 Platåberget soak, reliably did) answer before a peer serving a lower one, tripping
  // StackTrie.update's `require` and crashing the actor (RestartSupervisor then restarted it with
  // empty in-memory task state).
  //
  // These tests drive the ordering gate (applyOrderedStorageChunk / drainOrderedStorageChunks /
  // applyReadyStorageChunk) directly via the white-box Impl, bypassing MerkleProofVerifier —
  // verification's "monotonic WITHIN one response" guarantee (MerkleProofVerifierSpec) is a
  // different property from "monotonic ACROSS sibling chunk responses", which is what these cover.

  private def slotKey(lastByte: Int): ByteString = ByteString(Array.fill(31)(0x00.toByte) :+ lastByte.toByte)

  it should "apply storageConcurrency-style parallel chunks fed out of range order without tripping the StackTrie ascending-order invariant" taggedAs UnitTest in {
    val accountHash = kec256(ByteString("scrambled-storage-account"))
    val storageRoot = kec256(ByteString("scrambled-storage-root"))
    val (impl, _) = newImpl(
      stateRoot = kec256(ByteString("ordering-gate-root")),
      flatSlotStorage = new FlatSlotStorage(EphemDataSource()),
      snapSyncControllerRef = testKit.createTestProbe[SNAPSyncController.Command]().ref,
      deferredMerkleization = false // must build the real streaming trie to exercise StackTrie.update
    )
    val peer = PeerTestHelpers.createTestPeer("scrambled-storage-peer", testKit.createTestProbe[Any]().ref.toClassic)

    // Three disjoint, range-ascending chunks — mirrors StorageTask.createSubTasks' output shape for
    // a large-storage account split into parallel subtasks. Each is a one-shot response here (empty
    // proof ⇒ per SNAP spec that chunk's own range is fully served, no further continuation).
    val chunk0 = StorageTask(accountHash, storageRoot, next = slotKey(0x01), last = slotKey(0x1f))
    val chunk1 = StorageTask(accountHash, storageRoot, next = slotKey(0x20), last = slotKey(0x3f))
    val chunk2 = StorageTask(accountHash, storageRoot, next = slotKey(0x40), last = slotKey(0xfe))

    // As if a prior (pre-split) response already established the cursor at chunk0's start, and the
    // split into 3 parallel subtasks was already registered (StorageRangeCoordinatorImpl.scala's
    // `createStorageSubTasks` call site does both together, atomically, when a response first needs
    // continuation).
    impl.accountSubtaskCounters(accountHash) = (3, 0)
    impl.storageTrieCursor(accountHash) = chunk0.next

    // Feed the HIGHEST-range chunk first, then the two lower ones — the exact shape of the crash: a
    // faster peer answering a higher sub-range before a slower peer's lower sub-range lands.
    noException should be thrownBy {
      impl.applyOrderedStorageChunk(
        peer,
        chunk2,
        Seq(slotKey(0x50) -> ByteString("value-50"), slotKey(0x90) -> ByteString("value-90")),
        Seq.empty
      )
      impl.applyOrderedStorageChunk(peer, chunk0, Seq(slotKey(0x10) -> ByteString("value-10")), Seq.empty)
      impl.applyOrderedStorageChunk(peer, chunk1, Seq(slotKey(0x30) -> ByteString("value-30")), Seq.empty)
    }

    // chunk2 must have been buffered (not applied) until chunk0 and chunk1 landed, then drained
    // automatically once chunk1 completed the ascending run up to chunk2's start.
    impl.pendingOrderedChunks.get(accountHash) shouldBe empty
    impl.storageTrieCursor.get(accountHash) shouldBe empty // account fully done ⇒ cursor cleared
    impl.accountSubtaskCounters.get(accountHash) shouldBe None // all 3 subtasks recorded complete
    impl.completedAccountCount shouldBe 1L
    impl.pendingAccountTries.get(accountHash) shouldBe empty // auto-committed on the final (range-highest) chunk
  }

  it should "buffer an out-of-order chunk and drain it once its predecessor lands, producing the same root as true in-order arrival" taggedAs UnitTest in {
    import com.chipprbots.ethereum.blockchain.sync.snap.SnapHashTrie

    val accountHash = kec256(ByteString("root-check-account"))
    val storageRoot = kec256(ByteString("root-check-root"))
    val (impl, _) = newImpl(
      stateRoot = kec256(ByteString("ordering-gate-root-2")),
      flatSlotStorage = new FlatSlotStorage(EphemDataSource()),
      snapSyncControllerRef = testKit.createTestProbe[SNAPSyncController.Command]().ref,
      deferredMerkleization = false
    )
    val peer = PeerTestHelpers.createTestPeer("root-check-peer", testKit.createTestProbe[Any]().ref.toClassic)

    val chunk0 = StorageTask(accountHash, storageRoot, next = slotKey(0x01), last = slotKey(0x1f))
    val chunk1 = StorageTask(accountHash, storageRoot, next = slotKey(0x20), last = slotKey(0x3f))
    val chunk2 = StorageTask(accountHash, storageRoot, next = slotKey(0x40), last = slotKey(0xfe))
    val slots0 = Seq(slotKey(0x10) -> ByteString("value-10"))
    val slots1 = Seq(slotKey(0x30) -> ByteString("value-30"))
    val slots2 = Seq(slotKey(0x50) -> ByteString("value-50"), slotKey(0x90) -> ByteString("value-90"))

    // Registered as 4 subtasks but only 3 are ever fed — the account is deliberately left
    // "incomplete" so the shared trie is never auto-committed/removed, letting this test inspect it
    // directly afterward instead of racing the internal commit.
    impl.accountSubtaskCounters(accountHash) = (4, 0)
    impl.storageTrieCursor(accountHash) = chunk0.next

    // Out-of-order arrival: chunk2 (highest range) before chunk0/chunk1.
    impl.applyOrderedStorageChunk(peer, chunk2, slots2, Seq.empty)
    impl.pendingOrderedChunks(accountHash) should have size 1 // buffered, not yet applied

    impl.applyOrderedStorageChunk(peer, chunk0, slots0, Seq.empty)
    impl.pendingOrderedChunks(accountHash) should have size 1 // chunk2 still waiting on chunk1

    impl.applyOrderedStorageChunk(peer, chunk1, slots1, Seq.empty)
    impl.pendingOrderedChunks.get(accountHash) shouldBe empty // chunk2 drained once chunk1 landed

    // Trie is still open (4th subtask never arrived) — safe to commit it directly for inspection.
    val actualRoot = impl.pendingAccountTries(accountHash).commit()

    // Independently-built reference: the SAME slots inserted in TRUE ascending order.
    val reference = new SnapHashTrie(_ => ())
    (slots0 ++ slots1 ++ slots2).foreach { case (k, v) => reference.update(k.toArray, v.toArray) }
    val expectedRoot = reference.commit()

    actualRoot shouldEqual expectedRoot
  }

  it should "re-queue (not lose) buffered out-of-order storage chunks on StoragePivotRefreshed" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("prefresh-ordering-root"))
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()
    val (impl, kit) = newImpl(
      stateRoot = stateRoot,
      flatSlotStorage = new FlatSlotStorage(EphemDataSource()),
      snapSyncControllerRef = snapSyncController.ref,
      deferredMerkleization = false
    )

    val accountHash = kec256(ByteString("prefresh-account"))
    val storageRoot = kec256(ByteString("prefresh-storage-root"))
    val chunkLo = StorageTask(accountHash, storageRoot, next = slotKey(0x01), last = slotKey(0x1f))
    val chunkHi = StorageTask(accountHash, storageRoot, next = slotKey(0x20), last = slotKey(0x3f))
    val peer = PeerTestHelpers.createTestPeer("prefresh-peer", testKit.createTestProbe[Any]().ref.toClassic)

    impl.accountSubtaskCounters(accountHash) = (2, 0)
    impl.storageTrieCursor(accountHash) = chunkLo.next

    // chunkHi arrives first and is buffered; chunkLo never arrives before the pivot refreshes.
    impl.applyOrderedStorageChunk(peer, chunkHi, Seq(slotKey(0x30) -> ByteString("value-30")), Seq.empty)
    impl.pendingOrderedChunks(accountHash) should have size 1

    impl.tasks.exists(_.next == chunkHi.next) shouldBe false // not yet in the retry queue

    kit.run(StorageRangeCoordinator.StoragePivotRefreshed(kec256(ByteString("new-pivot-root"))))

    // The buffered chunk must be re-queued, not silently dropped.
    impl.tasks.exists(t => t.accountHash == accountHash && t.next == chunkHi.next) shouldBe true
    impl.pendingOrderedChunks.get(accountHash) shouldBe empty

    // Re-derived cursor = the lowest surviving chunk's start (chunkHi is the only survivor here —
    // chunkLo was never buffered or active, so — like today's pre-fix trie discard — it is
    // abandoned rather than fabricated from nothing; healing reconciles).
    impl.storageTrieCursor.get(accountHash) shouldBe Some(chunkHi.next)
  }

  it should "force-complete cleanly (StorageRangeSyncForceCompleted) even with a buffered out-of-order storage chunk pending" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("force-complete-ordering-root"))
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()
    val (impl, kit) = newImpl(
      stateRoot = stateRoot,
      flatSlotStorage = new FlatSlotStorage(EphemDataSource()),
      snapSyncControllerRef = snapSyncController.ref,
      deferredMerkleization = false
    )

    val accountHash = kec256(ByteString("force-complete-account"))
    val storageRoot = kec256(ByteString("force-complete-storage-root"))
    val chunkLo = StorageTask(accountHash, storageRoot, next = slotKey(0x01), last = slotKey(0x1f))
    val chunkHi = StorageTask(accountHash, storageRoot, next = slotKey(0x20), last = slotKey(0x3f))
    val peer = PeerTestHelpers.createTestPeer("force-complete-peer", testKit.createTestProbe[Any]().ref.toClassic)

    impl.accountSubtaskCounters(accountHash) = (2, 0)
    impl.storageTrieCursor(accountHash) = chunkLo.next
    impl.applyOrderedStorageChunk(peer, chunkHi, Seq(slotKey(0x30) -> ByteString("value-30")), Seq.empty)
    impl.pendingOrderedChunks(accountHash) should have size 1

    kit.run(StorageRangeCoordinator.ForceCompleteStorage)

    // The existing "abandon and force-complete" recovery path must still fire cleanly — this is
    // what a controller detecting the "restarted empty" signature (SNAPSyncController's
    // StorageRestartedEmptyThreshold) actually triggers to avoid the silent stall.
    snapSyncController.expectMessage(SNAPSyncController.StorageRangeSyncForceCompleted)
    // No buffered chunk, cursor, or trie is left behind — nothing to leak or double-apply if this
    // account's data is later re-synced.
    impl.pendingOrderedChunks shouldBe empty
    impl.storageTrieCursor shouldBe empty
    impl.pendingAccountTries shouldBe empty
  }

  // ── forge review follow-up: a legitimately-empty middle sub-range must not stall the account ──
  //
  // handleProofOfAbsence (servedCount==0, response.proof.nonEmpty, tasks.size==1 — always true for
  // a solo subtask/continuation chunk per isInitialRange batching) used to bypass the ordering gate
  // entirely: it marked the task done and returned without ever calling recordSubtaskCompletion or
  // advancing storageTrieCursor. A sparse multi-chunk account whose middle sub-range genuinely has
  // zero slots (a realistic, not rare, shape for large sparse contracts) could then never reach
  // accountSubtaskCounters' total, so completedAccountCount never advanced for it AND any
  // higher-range sibling already buffered behind that stuck cursor stayed buffered — until the
  // whole storage phase reported 0 pending/0 active and the (now 60s, see StorageRestartedEmptyThreshold)
  // force-complete fast path swept it up regardless. It never corrupted anything, but it defeated
  // this fix's purpose of resolving ordering WITHOUT falling back to force-complete.
  //
  // handleProofOfAbsence now delegates to applyOrderedStorageChunk with an empty slot set (see
  // StorageRangeCoordinator.scala) — these tests drive that exact call shape directly.

  it should "let a sparse account complete normally (no force-complete) when one middle sub-range is a legitimate proof-of-absence, arriving out of order" taggedAs UnitTest in {
    val accountHash = kec256(ByteString("sparse-empty-middle-account"))
    val storageRoot = kec256(ByteString("sparse-empty-middle-root"))
    val (impl, _) = newImpl(
      stateRoot = kec256(ByteString("sparse-empty-middle-state-root")),
      flatSlotStorage = new FlatSlotStorage(EphemDataSource()),
      snapSyncControllerRef = testKit.createTestProbe[SNAPSyncController.Command]().ref,
      deferredMerkleization = false
    )

    val chunk0 = StorageTask(accountHash, storageRoot, next = slotKey(0x01), last = slotKey(0x1f))
    // chunk1 (the MIDDLE sub-range) is legitimately empty: a proof-of-absence response for
    // [chunk1.next, chunk1.last] — zero slots, non-empty proof — exactly what handleProofOfAbsence
    // routes here on a solo chunk request.
    val chunk1 = StorageTask(accountHash, storageRoot, next = slotKey(0x20), last = slotKey(0x3f))
    val chunk2 = StorageTask(accountHash, storageRoot, next = slotKey(0x40), last = slotKey(0xfe))
    val absenceProof = Seq(ByteString(Array.fill(32)(0xab.toByte)))
    val peer = PeerTestHelpers.createTestPeer("sparse-empty-middle-peer", testKit.createTestProbe[Any]().ref.toClassic)

    impl.accountSubtaskCounters(accountHash) = (3, 0)
    impl.storageTrieCursor(accountHash) = chunk0.next

    // Scrambled arrival: both non-first chunks (one empty, one with real slots) land before the
    // chunk that establishes the ascending run.
    noException should be thrownBy {
      impl.applyOrderedStorageChunk(peer, chunk2, Seq(slotKey(0x50) -> ByteString("value-50")), Seq.empty)
      impl.applyOrderedStorageChunk(peer, chunk1, Seq.empty, absenceProof) // proof-of-absence, out of order
      impl.applyOrderedStorageChunk(peer, chunk0, Seq(slotKey(0x10) -> ByteString("value-10")), Seq.empty)
    }

    // The empty middle chunk must count towards subtask completion like any other, and must not
    // leave itself or chunk2 stuck behind it.
    impl.pendingOrderedChunks.get(accountHash) shouldBe empty
    impl.storageTrieCursor.get(accountHash) shouldBe empty
    impl.accountSubtaskCounters.get(accountHash) shouldBe None
    impl.completedAccountCount shouldBe 1L
    impl.pendingAccountTries.get(accountHash) shouldBe empty // auto-committed once all 3 landed
  }

  it should "produce the correct storage root when a sparse account's out-of-order middle sub-range is empty" taggedAs UnitTest in {
    import com.chipprbots.ethereum.blockchain.sync.snap.SnapHashTrie

    val accountHash = kec256(ByteString("sparse-empty-middle-root-check"))
    val storageRoot = kec256(ByteString("sparse-empty-middle-root-check-root"))
    val (impl, _) = newImpl(
      stateRoot = kec256(ByteString("sparse-empty-middle-root-check-state")),
      flatSlotStorage = new FlatSlotStorage(EphemDataSource()),
      snapSyncControllerRef = testKit.createTestProbe[SNAPSyncController.Command]().ref,
      deferredMerkleization = false
    )

    val chunk0 = StorageTask(accountHash, storageRoot, next = slotKey(0x01), last = slotKey(0x1f))
    val chunk1 = StorageTask(accountHash, storageRoot, next = slotKey(0x20), last = slotKey(0x3f)) // empty
    val chunk2 = StorageTask(accountHash, storageRoot, next = slotKey(0x40), last = slotKey(0xfe))
    val slots0 = Seq(slotKey(0x10) -> ByteString("value-10"))
    val slots2 = Seq(slotKey(0x50) -> ByteString("value-50"))
    val absenceProof = Seq(ByteString(Array.fill(32)(0xab.toByte)))
    val peer =
      PeerTestHelpers.createTestPeer(
        "sparse-empty-middle-root-check-peer",
        testKit.createTestProbe[Any]().ref.toClassic
      )

    // Registered as 4 subtasks but only 3 are ever fed — deliberately left "incomplete" so the
    // shared trie is never auto-committed/removed, letting this test inspect it directly.
    impl.accountSubtaskCounters(accountHash) = (4, 0)
    impl.storageTrieCursor(accountHash) = chunk0.next

    // The empty middle chunk arrives before EITHER of its neighbours.
    impl.applyOrderedStorageChunk(peer, chunk1, Seq.empty, absenceProof)
    impl.pendingOrderedChunks(accountHash) should have size 1

    impl.applyOrderedStorageChunk(peer, chunk2, slots2, Seq.empty)
    impl.pendingOrderedChunks(accountHash) should have size 2 // both still waiting on chunk0

    impl.applyOrderedStorageChunk(peer, chunk0, slots0, Seq.empty)
    impl.pendingOrderedChunks.get(accountHash) shouldBe empty // both drained once chunk0 landed

    val actualRoot = impl.pendingAccountTries(accountHash).commit()

    // Reference: ONLY chunk0's and chunk2's slots (chunk1 contributes nothing), true ascending order.
    val reference = new SnapHashTrie(_ => ())
    (slots0 ++ slots2).foreach { case (k, v) => reference.update(k.toArray, v.toArray) }
    val expectedRoot = reference.commit()

    actualRoot shouldEqual expectedRoot
  }

  // ── forge review follow-up: boundary DUPLICATE (new == last, not just out-of-order) ──────────
  //
  // Platåberget soak, 2026-09-27 23:51:04: `StackTrie keys must be strictly ascending: last=X >=
  // new=X` — an EXACT repeat, not a reordering. SNAP/1's `startingHash` origin is documented as
  // inclusive; go-ethereum's own genTrie/stacktrie boundary handling anticipates a continuation or
  // sub-range response whose first key repeats the boundary this account's trie already has.
  // applyReadyStorageChunk now filters an exact (key, value) repeat before it ever reaches
  // `trie.update`, and rejects the whole response (peer penalty, retry) if the value differs under
  // that same key. (Verified red-before-green-after: temporarily removing the dedup guard makes
  // this test reproduce the exact `IllegalArgumentException` from the log.)

  it should "silently drop an exact repeat of the last-applied boundary slot instead of tripping StackTrie's ascending-order invariant" taggedAs UnitTest in {
    import com.chipprbots.ethereum.blockchain.sync.snap.SnapHashTrie

    val accountHash = kec256(ByteString("boundary-duplicate-account"))
    val storageRoot = kec256(ByteString("boundary-duplicate-root"))
    val (impl, _) = newImpl(
      stateRoot = kec256(ByteString("boundary-duplicate-state-root")),
      flatSlotStorage = new FlatSlotStorage(EphemDataSource()),
      snapSyncControllerRef = testKit.createTestProbe[SNAPSyncController.Command]().ref,
      deferredMerkleization = false // must build the real streaming trie to exercise StackTrie.update
    )
    val peer = PeerTestHelpers.createTestPeer("boundary-duplicate-peer", testKit.createTestProbe[Any]().ref.toClassic)

    val boundaryKey = slotKey(0x1f)
    val boundaryValue = ByteString("value-boundary")

    // chunk0's response includes the boundary slot as its LAST entry — after this lands, the trie's
    // last-applied key/value is exactly (boundaryKey, boundaryValue).
    val chunk0 = StorageTask(accountHash, storageRoot, next = slotKey(0x01), last = boundaryKey)
    // chunk1 is this account's continuation: per SNAP/1, its `next` is startingHash =
    // incrementHash32(boundaryKey) — but the crash's own evidence is a peer whose response
    // nonetheless RE-INCLUDES boundaryKey as chunk1's first slot (same value: an inclusive-origin
    // artifact, not corruption).
    val chunk1 =
      StorageTask(accountHash, storageRoot, next = StorageTask.incrementHash32(boundaryKey), last = slotKey(0xfe))

    // Registered as 3 subtasks but only 2 are ever fed — deliberately left "incomplete" so the
    // shared trie is never auto-committed/removed, letting this test inspect it directly.
    impl.accountSubtaskCounters(accountHash) = (3, 0)
    impl.storageTrieCursor(accountHash) = chunk0.next

    val chunk0Slots = Seq(slotKey(0x10) -> ByteString("value-10"), boundaryKey -> boundaryValue)
    // chunk1's response repeats boundaryKey (same value) as its FIRST slot, then continues ascending
    // — exactly the shape of the crash: "last=X >= new=X" where X is the repeated boundary key.
    val chunk1Slots = Seq(boundaryKey -> boundaryValue, slotKey(0x50) -> ByteString("value-50"))

    noException should be thrownBy {
      impl.applyOrderedStorageChunk(peer, chunk0, chunk0Slots, Seq.empty)
      impl.applyOrderedStorageChunk(peer, chunk1, chunk1Slots, Seq.empty)
    }

    // Both chunks completed normally — the duplicate was dropped, not treated as a failure.
    impl.accountSubtaskCounters.get(accountHash) shouldBe Some((3, 2))
    impl.pendingOrderedChunks.get(accountHash) shouldBe empty

    // Trie is still open (3rd subtask never arrived) — safe to commit it directly for inspection.
    val actualRoot = impl.pendingAccountTries(accountHash).commit()

    // Reference: the TRUE deduplicated slot set (boundaryKey inserted ONCE, not twice), true
    // ascending order. If the duplicate had been inserted twice (or dropped along with a NEIGHBOUR),
    // this would not match.
    val reference = new SnapHashTrie(_ => ())
    val dedupedExpected = Seq(
      slotKey(0x10) -> ByteString("value-10"),
      boundaryKey -> boundaryValue,
      slotKey(0x50) -> ByteString("value-50")
    )
    dedupedExpected.foreach { case (k, v) => reference.update(k.toArray, v.toArray) }
    val expectedRoot = reference.commit()

    actualRoot shouldEqual expectedRoot
  }

  it should "reject (not silently accept) a boundary slot re-served with a DIFFERENT value under the same key" taggedAs UnitTest in {
    val accountHash = kec256(ByteString("boundary-mismatch-account"))
    val storageRoot = kec256(ByteString("boundary-mismatch-root"))
    val (impl, _) = newImpl(
      stateRoot = kec256(ByteString("boundary-mismatch-state-root")),
      flatSlotStorage = new FlatSlotStorage(EphemDataSource()),
      snapSyncControllerRef = testKit.createTestProbe[SNAPSyncController.Command]().ref,
      deferredMerkleization = false
    )
    val peer = PeerTestHelpers.createTestPeer("boundary-mismatch-peer", testKit.createTestProbe[Any]().ref.toClassic)

    val boundaryKey = slotKey(0x1f)
    val originalValue = ByteString("value-original")
    val mismatchedValue = ByteString("value-DIFFERENT")

    val chunk0 = StorageTask(accountHash, storageRoot, next = slotKey(0x01), last = boundaryKey)
    val chunk1 =
      StorageTask(accountHash, storageRoot, next = StorageTask.incrementHash32(boundaryKey), last = slotKey(0xfe))

    impl.accountSubtaskCounters(accountHash) = (2, 0)
    impl.storageTrieCursor(accountHash) = chunk0.next

    val chunk0Slots = Seq(slotKey(0x10) -> ByteString("value-10"), boundaryKey -> originalValue)
    // chunk1's response re-serves boundaryKey with a DIFFERENT value — inconsistent peer data.
    val chunk1Slots = Seq(boundaryKey -> mismatchedValue, slotKey(0x50) -> ByteString("value-50"))

    impl.applyOrderedStorageChunk(peer, chunk0, chunk0Slots, Seq.empty)
    val cursorAfterChunk0 = impl.storageTrieCursor(accountHash)
    val tasksBefore = impl.tasks.size

    noException should be thrownBy {
      impl.applyOrderedStorageChunk(peer, chunk1, chunk1Slots, Seq.empty)
    }

    // Rejected, not applied: chunk1's data never reached the trie, the cursor did not move past it,
    // subtask completion was not recorded, and the task is back in the retry queue.
    impl.storageTrieCursor(accountHash) shouldEqual cursorAfterChunk0
    impl.accountSubtaskCounters.get(accountHash) shouldBe Some((2, 1)) // only chunk0 recorded
    impl.completedAccountCount shouldBe 0L
    impl.tasks.size shouldBe tasksBefore + 1
    impl.tasks.exists(t => t.accountHash == accountHash && t.next == chunk1.next) shouldBe true
  }

  // ── forge review follow-up: bounded verification-failure retry (soak v6 tail livelock) ────────
  //
  // Platåberget soak v6, 2026-09-28: an account whose account-range record was fetched at an OLDER
  // pivot carries a stale `storageRoot` no peer can ever satisfy. Before this guard,
  // processServedTasks's verification-failure branch re-queued such a task unconditionally,
  // forever: soak evidence showed ~1245 identical batched responses over 25+ minutes, the same
  // handful of accounts re-failing every cycle with "complete-range hash mismatch" while `pending`
  // oscillated 1-33 without ever draining — a livelock in the storage phase's tail, invisible to
  // the (slot-count-based) stagnation watchdog because unrelated accounts kept completing and
  // resetting its clock. These tests drive `processServedTasks` directly (widened to
  // `private[actors]`) rather than through the full dispatch/response cycle, since what's under
  // test is the verification-failure branch's bookkeeping, not request/response correlation;
  // `activeTasks` is cleared after each call to mirror the production invariant that
  // `handleResponse` always removes its entry before `processServedTasks` runs.

  private def computeCompleteRangeRoot(slots: Seq[(ByteString, ByteString)]): ByteString =
    val t = new SnapHashTrie(_ => ())
    slots.foreach { case (k, v) => t.update(k.toArray, v.toArray) }
    t.commit()

  it should "drop an account to healing after maxStaleRootFailuresPerAccount consecutive complete-range verification failures spanning 2+ peers, and let the storage phase complete" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("kcap-state-root"))
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()
    val (impl, kit) = newImpl(
      stateRoot = stateRoot,
      flatSlotStorage = new FlatSlotStorage(EphemDataSource()),
      snapSyncControllerRef = snapSyncController.ref,
      deferredMerkleization = true
    )

    val peerA = PeerTestHelpers.createTestPeer("kcap-peer-a", testKit.createTestProbe[Any]().ref.toClassic)
    val peerB = PeerTestHelpers.createTestPeer("kcap-peer-b", testKit.createTestProbe[Any]().ref.toClassic)

    val badAccountHash = kec256(ByteString("kcap-bad-account"))
    // Deliberately WRONG storageRoot: no slot data can ever produce a matching complete-range hash
    // — simulates an account record fetched at an older pivot whose real storage root has moved.
    val badStorageRoot = kec256(ByteString("kcap-bad-storage-root-stale"))
    val badTask = StorageTask.createStorageTask(badAccountHash, badStorageRoot)
    val badSlots = Seq(slotKey(0x10) -> ByteString("bad-value"))

    // processServedTasks unconditionally pipelines more work to the responding peer at its end
    // (dispatchIfPossible) — a just-re-queued retry can be immediately picked back up from `tasks`
    // into `activeTasks` before this helper returns. Clearing BOTH after every call keeps each
    // attempt self-contained (mirrors production: handleResponse always removes its activeTasks
    // entry before processServedTasks runs) without caring which of the two queues a retry
    // transiently landed in.
    def feedBadAttempt(peer: com.chipprbots.ethereum.network.Peer, reqId: Int): Unit =
      impl.processServedTasks(
        peer,
        Seq(badTask),
        BigInt(1024),
        StorageRanges(requestId = reqId, slots = Seq(badSlots), proof = Seq.empty),
        servedCount = 1
      )
      impl.tasks.clear()
      impl.activeTasks.clear()

    // Attempts 1-2: below K=3 — retried each time, not yet given up on.
    feedBadAttempt(peerA, 1)
    impl.staleRootFailuresByAccount(badAccountHash) should have size 1

    feedBadAttempt(peerB, 2)
    impl.staleRootFailuresByAccount(badAccountHash) should have size 2

    // Attempt 3: failures.size=3 >= K=3 AND distinctPeers=2 >= 2 -> give up, hand off to healing —
    // exactly the same "mark done, discard partial trie, do not re-queue" pattern
    // handleEmptyResponse already uses.
    feedBadAttempt(peerA, 3)
    impl.staleRootFailuresByAccount.get(badAccountHash) shouldBe None // cleared on give-up
    impl.pendingAccountTries.get(badAccountHash) shouldBe empty
    // Unlike attempts 1-2, a give-up must NOT re-queue: `tasks`/`activeTasks` were already cleared
    // by the helper above, so their emptiness here would be trivial — the meaningful proof that
    // nothing was re-queued is `staleRootFailuresByAccount` being gone (a give-up removes it; a
    // retry would have left it present, as attempts 1-2 show) combined with completion succeeding
    // below without this account ever answering again.

    // A second, healthy account completes normally in the same phase.
    val goodAccountHash = kec256(ByteString("kcap-good-account"))
    val goodSlots = Seq(slotKey(0x20) -> ByteString("good-value"))
    val goodStorageRoot = computeCompleteRangeRoot(goodSlots)
    val goodTask = StorageTask.createStorageTask(goodAccountHash, goodStorageRoot)
    impl.processServedTasks(
      peerA,
      Seq(goodTask),
      BigInt(1024),
      StorageRanges(requestId = 4, slots = Seq(goodSlots), proof = Seq.empty),
      servedCount = 1
    )
    impl.tasks.clear()
    impl.activeTasks.clear()
    // The good account's successful insertion sends its own progress message first.
    snapSyncController.expectMessageType[SNAPSyncController.ProgressStorageSlotsSynced]

    // Both accounts are resolved (one via healing hand-off, one normally) — the phase completes.
    kit.run(StorageRangeCoordinator.NoMoreStorageTasks)
    drainSelf(kit) // NoMoreStorageTasks may itself trigger the flat-batch flush + FlatBatchFlushComplete
    kit.run(StorageRangeCoordinator.StorageCheckCompletion)
    drainSelf(kit)
    snapSyncController.expectMessage(SNAPSyncController.StorageRangeSyncComplete)
  }

  it should "NOT drop an account that fails complete-range verification once and then succeeds" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("kcap-recovers-state-root"))
    val (impl, _) = newImpl(
      stateRoot = stateRoot,
      flatSlotStorage = new FlatSlotStorage(EphemDataSource()),
      snapSyncControllerRef = testKit.createTestProbe[SNAPSyncController.Command]().ref,
      deferredMerkleization = true
    )
    val peerA = PeerTestHelpers.createTestPeer("kcap-recovers-peer-a", testKit.createTestProbe[Any]().ref.toClassic)

    val accountHash = kec256(ByteString("kcap-recovers-account"))
    val slots = Seq(slotKey(0x30) -> ByteString("eventual-value"))
    val realStorageRoot = computeCompleteRangeRoot(slots)
    // The FIRST attempt's response uses the wrong root (transient corruption / a peer momentarily
    // out of sync) — the second, with the correct root, succeeds.
    val wrongStorageRoot = kec256(ByteString("kcap-recovers-wrong-root"))

    val taskWithWrongRoot = StorageTask.createStorageTask(accountHash, wrongStorageRoot)
    impl.processServedTasks(
      peerA,
      Seq(taskWithWrongRoot),
      BigInt(1024),
      StorageRanges(requestId = 1, slots = Seq(slots), proof = Seq.empty),
      servedCount = 1
    )
    impl.staleRootFailuresByAccount(accountHash) should have size 1
    impl.activeTasks.clear()
    impl.tasks.clear()

    // Second attempt: correct root this time (e.g. the account record was refreshed) — succeeds.
    val taskWithRealRoot = StorageTask.createStorageTask(accountHash, realStorageRoot)
    impl.processServedTasks(
      peerA,
      Seq(taskWithRealRoot),
      BigInt(1024),
      StorageRanges(requestId = 2, slots = Seq(slots), proof = Seq.empty),
      servedCount = 1
    )

    // Success clears the failure history — this account is NOT on a path to being dropped.
    impl.staleRootFailuresByAccount.get(accountHash) shouldBe None
    impl.completedAccountCount shouldBe 1L
  }

  // ── forge review follow-ups on the K-cap ──────────────────────────────────────────────────────

  private def feedStaleRootAttempt(
      impl: StorageRangeCoordinatorImpl,
      peer: com.chipprbots.ethereum.network.Peer,
      task: StorageTask,
      reqId: Int,
      clearQueues: Boolean = true
  ): Unit =
    impl.processServedTasks(
      peer,
      Seq(task),
      BigInt(1024),
      StorageRanges(requestId = reqId, slots = Seq(Seq(slotKey(0x40) -> ByteString("v"))), proof = Seq.empty),
      servedCount = 1
    )
    if clearQueues then
      impl.tasks.clear()
      impl.activeTasks.clear()

  it should "NOT give up on an account whose repeated stale-root failures all come from ONE peer under ONE root" taggedAs UnitTest in {
    val (impl, _) = newImpl(
      stateRoot = kec256(ByteString("kcap-single-root")),
      flatSlotStorage = new FlatSlotStorage(EphemDataSource()),
      snapSyncControllerRef = testKit.createTestProbe[SNAPSyncController.Command]().ref,
      deferredMerkleization = true
    )
    val peerA = PeerTestHelpers.createTestPeer("kcap-single-a", testKit.createTestProbe[Any]().ref.toClassic)
    val acct = kec256(ByteString("kcap-single-account"))
    val task = StorageTask.createStorageTask(acct, kec256(ByteString("kcap-single-stale-root")))
    (1 to 5).foreach(i => feedStaleRootAttempt(impl, peerA, task, i))
    // Past K=3 but no diversity: could be one bad peer, so the account is still being retried.
    impl.staleRootFailuresByAccount(acct) should have size 5
    impl.abandonedAccounts should not contain acct
  }

  it should "give up once failures span 2+ distinct roots, even from a single peer" taggedAs UnitTest in {
    val (impl, kit) = newImpl(
      stateRoot = kec256(ByteString("kcap-roots-1")),
      flatSlotStorage = new FlatSlotStorage(EphemDataSource()),
      snapSyncControllerRef = testKit.createTestProbe[SNAPSyncController.Command]().ref,
      deferredMerkleization = true
    )
    val peerA = PeerTestHelpers.createTestPeer("kcap-roots-a", testKit.createTestProbe[Any]().ref.toClassic)
    val acct = kec256(ByteString("kcap-roots-account"))
    val task = StorageTask.createStorageTask(acct, kec256(ByteString("kcap-roots-stale-root")))
    feedStaleRootAttempt(impl, peerA, task, 1)
    feedStaleRootAttempt(impl, peerA, task, 2)
    impl.abandonedAccounts should not contain acct
    kit.run(StorageRangeCoordinator.StoragePivotRefreshed(kec256(ByteString("kcap-roots-2"))))
    feedStaleRootAttempt(impl, peerA, task, 3) // 3rd failure, 2nd distinct root, still ONE peer
    impl.abandonedAccounts should contain(acct)
    impl.staleRootFailuresByAccount.get(acct) shouldBe None
  }

  it should "keep stale-root failure history across StoragePivotRefreshed" taggedAs UnitTest in {
    val (impl, kit) = newImpl(
      stateRoot = kec256(ByteString("kcap-persist-1")),
      flatSlotStorage = new FlatSlotStorage(EphemDataSource()),
      snapSyncControllerRef = testKit.createTestProbe[SNAPSyncController.Command]().ref,
      deferredMerkleization = true
    )
    val peerA = PeerTestHelpers.createTestPeer("kcap-persist-a", testKit.createTestProbe[Any]().ref.toClassic)
    val acct = kec256(ByteString("kcap-persist-account"))
    val task = StorageTask.createStorageTask(acct, kec256(ByteString("kcap-persist-stale-root")))
    feedStaleRootAttempt(impl, peerA, task, 1)
    feedStaleRootAttempt(impl, peerA, task, 2)
    kit.run(StorageRangeCoordinator.StoragePivotRefreshed(kec256(ByteString("kcap-persist-2"))))
    impl.staleRootFailuresByAccount(acct) should have size 2 // survived the refresh
  }

  it should "give up the WHOLE account on give-up: reset a live partial trie, drop queued sibling subtasks, ignore late sibling responses, no requeue" taggedAs UnitTest in {
    val (impl, _) = newImpl(
      stateRoot = kec256(ByteString("kcap-live-trie")),
      flatSlotStorage = new FlatSlotStorage(EphemDataSource()),
      snapSyncControllerRef = testKit.createTestProbe[SNAPSyncController.Command]().ref,
      deferredMerkleization = false // a real partial trie exists
    )
    val peerA = PeerTestHelpers.createTestPeer("kcap-live-a", testKit.createTestProbe[Any]().ref.toClassic)
    val peerB = PeerTestHelpers.createTestPeer("kcap-live-b", testKit.createTestProbe[Any]().ref.toClassic)
    val acct = kec256(ByteString("kcap-live-account"))
    val staleRoot = kec256(ByteString("kcap-live-stale-root"))

    // First response needs continuation: builds a live partial trie and splits into parallel subtasks.
    val seed = StorageTask.createStorageTask(acct, staleRoot)
    impl.applyOrderedStorageChunk(
      peerA,
      seed,
      Seq(slotKey(0x05) -> ByteString("v5")),
      Seq(ByteString(Array.fill(32)(0xab.toByte)))
    )
    impl.pendingAccountTries.contains(acct) shouldBe true
    impl.accountSubtaskCounters.contains(acct) shouldBe true
    val sub = impl.tasks.find(_.accountHash == acct).get
    impl.tasks.count(_.accountHash == acct) should be > 1

    // 3 stale-root failures across 2 peers on ONE subtask (queues deliberately not cleared).
    feedStaleRootAttempt(impl, peerA, sub, 1, clearQueues = false)
    feedStaleRootAttempt(impl, peerB, sub, 2, clearQueues = false)
    feedStaleRootAttempt(impl, peerA, sub, 3, clearQueues = false)

    impl.abandonedAccounts should contain(acct)
    impl.pendingAccountTries.contains(acct) shouldBe false // partial trie reset
    impl.accountSubtaskCounters.contains(acct) shouldBe false // siblings do not each need K more failures
    impl.tasks.exists(_.accountHash == acct) shouldBe false // no requeue, siblings dropped
    impl.storageTrieCursor.contains(acct) shouldBe false

    // A late response for a sibling that was already in flight is ignored (not verified, not applied).
    val eventsBefore = impl.staleRootFailureEvents
    feedStaleRootAttempt(impl, peerB, sub, 4, clearQueues = false)
    impl.staleRootFailureEvents shouldBe eventsBefore
    impl.pendingAccountTries.contains(acct) shouldBe false
    impl.tasks.exists(_.accountHash == acct) shouldBe false
  }

  // ── Zombie coordinator (devnet-8, 2026-10-03) ─────────────────────────────
  // The tracker's request timers outlive the coordinator. [STORAGE-FORCE-COMPLETE] kept firing for 6+ minutes after
  // SNAP had completed and the coordinator had been stopped, because nothing cancelled the in-flight request timers.

  private def startWithOneRequestInFlight(requestTracker: SNAPRequestTracker) =
    val stateRoot = kec256(ByteString("zombie-root"))
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()
    val peerProbe = testKit.createTestProbe[Any]()
    val peer = PeerTestHelpers.createTestPeer("storage-peer-zombie", peerProbe.ref.toClassic)
    val coordinator = srcProps(
      stateRoot = stateRoot,
      networkPeerManager = networkPeerManager.ref,
      requestTracker = requestTracker,
      mptStorage = new TestMptStorage(),
      flatSlotStorage = new FlatSlotStorage(EphemDataSource()),
      maxAccountsPerBatch = 1,
      maxInFlightRequests = 2,
      requestTimeout = 30.seconds,
      snapSyncController = snapSyncController.ref
    )
    coordinator ! StorageRangeCoordinator.StartStorageRangeSync(stateRoot)
    coordinator ! StorageRangeCoordinator.AddStorageTasks(
      Seq(StorageTask.createStorageTask(kec256(ByteString("zombie-account")), kec256(ByteString("zombie-storage"))))
    )
    coordinator ! StorageRangeCoordinator.StoragePeerAvailable(peer)
    networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd]
    requestTracker.pendingCount shouldBe 1
    (coordinator, snapSyncController)

  it should "cancel its in-flight request timers when it stops" taggedAs UnitTest in {
    val requestTracker = new SNAPRequestTracker()(classicSystem.scheduler)
    val (coordinator, _) = startWithOneRequestInFlight(requestTracker)
    testKit.stop(coordinator)
    requestTracker.pendingCount shouldBe 0
  }

  it should "cancel its in-flight request timers when it is force-completed" taggedAs UnitTest in {
    val requestTracker = new SNAPRequestTracker()(classicSystem.scheduler)
    val (coordinator, snapSyncController) = startWithOneRequestInFlight(requestTracker)
    coordinator ! StorageRangeCoordinator.ForceCompleteStorage
    snapSyncController.expectMessage(SNAPSyncController.StorageRangeSyncForceCompleted)
    requestTracker.pendingCount shouldBe 0
    testKit.stop(coordinator)
  }

  // ── StorageRequestTimedOut mailbox path ───────────────────────────────────

  private def implWithOneRequestInFlight() =
    val (impl, kit) = newImpl(
      stateRoot = kec256(ByteString("timedout-root")),
      flatSlotStorage = new FlatSlotStorage(EphemDataSource()),
      snapSyncControllerRef = testKit.createTestProbe[SNAPSyncController.Command]().ref,
      maxAccountsPerBatch = 1
    )
    val peer = PeerTestHelpers.createTestPeer("timedout-peer", testKit.createTestProbe[Any]().ref.toClassic)
    val task = StorageTask.createStorageTask(kec256(ByteString("timedout-account")), kec256(ByteString("timedout-sr")))
    kit.run(StorageRangeCoordinator.AddStorageTasks(Seq(task)))
    kit.run(StorageRangeCoordinator.StoragePeerAvailable(peer))
    impl.activeTasks should have size 1
    (impl, kit, task, impl.activeTasks.keys.head)

  it should "requeue the task and count a failure when the tracker's timeout arrives through the mailbox" taggedAs UnitTest in {
    val (impl, kit, task, requestId) = implWithOneRequestInFlight()
    val failuresBefore = impl.consecutiveTaskFailures
    kit.run(StorageRangeCoordinator.StorageRequestTimedOut(requestId))
    impl.activeTasks.contains(requestId) shouldBe false
    // Requeued, and (the peer being available) immediately re-dispatched under a NEW request id.
    val requeued = impl.tasks.exists(_.accountHash == task.accountHash) ||
      impl.activeTasks.values.exists(_._2.exists(_.accountHash == task.accountHash))
    requeued shouldBe true
    impl.consecutiveTaskFailures shouldBe failuresBefore + 1
  }

  it should "drop a timeout and a late response for a request abandoned by force-complete" taggedAs UnitTest in {
    val (impl, kit, _, requestId) = implWithOneRequestInFlight()
    kit.run(StorageRangeCoordinator.ForceCompleteStorage)
    impl.activeTasks shouldBe empty
    val failures = impl.consecutiveTaskFailures
    val tasksBefore = impl.tasks.size
    kit.run(StorageRangeCoordinator.StorageRequestTimedOut(requestId))
    kit.run(
      StorageRangeCoordinator.StorageRangesResponseMsg(StorageRanges(requestId, slots = Seq.empty, proof = Seq.empty))
    )
    impl.consecutiveTaskFailures shouldBe failures
    impl.tasks.size shouldBe tasksBefore
    impl.activeTasks shouldBe empty
  }
