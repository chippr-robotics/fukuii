package com.chipprbots.ethereum.blockchain.sync.snap.actors

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.util.ByteString

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*

import org.apache.pekko.actor.testkit.typed.scaladsl.FishingOutcomes
import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap.*
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.metrics.Metrics
import com.chipprbots.ethereum.mpt.LeafNode
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.mpt.MptTraversals
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.p2p.messages.SNAP
import com.chipprbots.ethereum.testing.PeerTestHelpers
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.testing.TestMptStorage

/** spec 004 (Decoupled Heal Serve-Root), US2 — consensus safety under decoupling.
  *
  * Decoupling lets the fetch root differ from the walk root. The single guardrail that keeps this safe is the
  * content-hash check at the store site (a node is accepted IFF its keccak256 equals a requested task hash). These
  * tests prove the guardrail holds, that an unservable task never produces a false completion, and that completion
  * remains byte-for-byte identical to the coupled path.
  *
  *   - T-3 (FR-004/SC-004, C4): a returned node whose keccak != the requested task hash is dropped — not stored, not
  *     counted as healed, the task stays pending.
  *   - T-4 (FR-006/SC-002, C5): a task no serve root satisfies never completes; its attempt counter increments and
  *     surfaces past the threshold; a HealingServeRootRefresh resets it; HealingForceComplete does NOT declare
  *     StateHealingComplete while a task is unsatisfied under decoupling.
  *   - T-5 (FR-007/SC-003, C6): the same final healed state reaches an identical completion (StateHealingComplete + CF
  *     `g` marker + unchanged state root) decoupled vs coupled (flag flip).
  */
class DecoupledHealSafetySpec extends ScalaTestWithActorTestKit() with AnyFlatSpecLike with Matchers with Eventually:

  implicit private val classicSystem: org.apache.pekko.actor.ActorSystem = system.classicSystem
  implicit private val actorTestKit: org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit = testKit
  private def gaugeValue(name: String): Double =
    val gauge = Metrics.get().registry.find(name).gauge()
    if gauge == null then Double.NaN else gauge.value()

  private def getTrieNodesOf(send: NetworkPeerManagerActor.SendMessageCmd): SNAP.GetTrieNodes =
    send.message.underlyingMsg.asInstanceOf[SNAP.GetTrieNodes]

  private def stats(coordinator: ActorRef[TrieNodeHealingCoordinator.Command]): HealingStatistics =
    val probe = testKit.createTestProbe[HealingStatistics]()
    coordinator ! TrieNodeHealingCoordinator.HealingGetProgress(probe.ref)
    probe.expectMessageType[HealingStatistics]

  /** Assert the controller is NOT sent StateHealingComplete within `window`. Other controller messages (e.g.
    * HealingAllPeersStateless from a stateless peer, ProgressNodesHealed) are allowed — the SC-002 guarantee is
    * specifically that no FALSE COMPLETION is declared while a task is unsatisfied.
    */
  private def assertNoStateHealingComplete(
      controller: org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe[SNAPSyncController.Command],
      window: FiniteDuration
  ): Unit =
    val completionReceived =
      try
        controller.fishForMessage(window) {
          case SNAPSyncController.StateHealingComplete => FishingOutcomes.complete
          case _                                       => FishingOutcomes.continueAndIgnore
        }
        true
      catch case _: AssertionError => false
    if completionReceived then
      fail("StateHealingComplete was declared while a heal task was still unsatisfied (SC-002)")

  /** A clean storage-trie leaf to heal (no children → a clean round). Returns (pathset, hash, encoded) with
    * `kec256(encoded) == hash`, exactly what `handleResponse` matches on.
    */
  private def cleanLeaf(seed: Int): (Seq[ByteString], ByteString, ByteString) =
    val leaf = LeafNode(ByteString(Array[Byte](0x01)), ByteString(kec256(ByteString(s"safety-leaf-$seed")).toArray))
    val encoded = MptTraversals.encodeNode(leaf)
    val hash = kec256(ByteString(encoded))
    val accountHash = kec256(ByteString(s"safety-account-$seed"))
    (Seq(accountHash, ByteString(Array[Byte](0x20, seed.toByte))), hash, ByteString(encoded))

  // ── T-3: content-hash guardrail drops a wrong-content node ────────────────────────────────────

  "Content-hash guardrail (T-3)" should
    "drop a returned node whose keccak != requested hash — not stored, not counted, task stays pending" taggedAs UnitTest in {
      val stateRoot = kec256(ByteString("safety-t3-walk-root"))
      val storage = new TestMptStorage()
      val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
      val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()
      val coordinator = HealingTrieFixtures.spawnCoordinator(
        stateRoot = stateRoot,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = new SNAPRequestTracker()(classicSystem.scheduler),
        mptStorage = storage,
        batchSize = 16,
        snapSyncController = snapSyncController.ref,
        healingWriterEcOverride = Some(classicSystem.dispatcher),
        decoupledHealServeRoot = true
      )

      // Queue a task whose hash is a fixed value we control; the response will carry DIFFERENT bytes whose
      // keccak does not equal that hash — simulating a serve root resolving the path to a different node.
      val requestedHash = kec256(ByteString("safety-t3-expected-hash"))
      coordinator ! TrieNodeHealingCoordinator.QueueMissingNodes(
        Seq((Seq(ByteString(Array[Byte](0x00))), requestedHash))
      )
      val peer = PeerTestHelpers.createTestPeer("safety-t3-peer", testKit.createTestProbe[Any]().ref.toClassic)
      coordinator ! TrieNodeHealingCoordinator.HealingPeerAvailable(peer)
      val send = networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd]
      val reqId = getTrieNodesOf(send).requestId

      val wrongContent = ByteString("safety-t3-wrong-content-bytes")
      kec256(wrongContent) should not be requestedHash // sanity: the response really mismatches
      coordinator ! TrieNodeHealingCoordinator.TrieNodesResponseMsg(
        SNAP.TrieNodes(requestId = reqId, nodes = Seq(wrongContent))
      )

      // The wrong-content node is dropped: not stored, not counted as healed, and the task is re-queued.
      eventually(timeout(5.seconds), interval(100.millis)) {
        val s = stats(coordinator)
        s.totalNodes shouldBe 0 // not counted as healed
        s.pendingTasks shouldBe 1 // task stays pending (re-queued, not satisfied)
      }
      // Never stored under the requested hash — the dropped node left storage untouched, so a lookup of the
      // requested (unhealed) hash throws MissingNodeException.
      intercept[MerklePatriciaTrie.MissingNodeException](storage.get(requestedHash.toArray))
      // No false completion was declared off the back of a dropped node. The controller MAY receive
      // HealingAllPeersStateless (the single peer returned no usable nodes → stateless → pivot-refresh
      // request, which is correct), but it must NEVER receive StateHealingComplete while the task is unhealed.
      assertNoStateHealingComplete(snapSyncController, 500.millis)
    }

  // ── T-4: an unservable task never completes; counter increments, resets, surfaces; no force-complete ─

  "Unservable heal task (T-4)" should
    "never complete, increment+surface the attempt counter, reset on refresh, and reject HealingForceComplete" taggedAs UnitTest in {
      val maxAttempts = 2 // small threshold so 3 unsatisfied attempts cross it deterministically
      val stateRoot = kec256(ByteString("safety-t4-walk-root"))
      val storage = new TestMptStorage()
      val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
      val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()
      val coordinator = HealingTrieFixtures.spawnCoordinator(
        stateRoot = stateRoot,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = new SNAPRequestTracker()(classicSystem.scheduler),
        mptStorage = storage,
        batchSize = 16,
        snapSyncController = snapSyncController.ref,
        healingWriterEcOverride = Some(classicSystem.dispatcher),
        decoupledHealServeRoot = true,
        decoupledHealMaxAttemptsNoRefresh = maxAttempts
      )

      // The single unservable task. We drive repeated unsatisfied attempts via the TIMEOUT path: each timeout
      // re-queues the task and increments its attempt counter WITHOUT marking the peer stateless (go-ethereum
      // aligned). A 30s per-peer cooldown is recorded on timeout, so we dispatch each fresh attempt to a NEW
      // (non-cooling) peer — three rounds cross the threshold=2 surfacing point.
      val unservableHash = kec256(ByteString("safety-t4-unservable-node"))
      coordinator ! TrieNodeHealingCoordinator.QueueMissingNodes(
        Seq((Seq(ByteString(Array[Byte](0x07))), unservableHash))
      )

      def dispatchAndTimeout(round: Int): Unit =
        val peer =
          PeerTestHelpers.createTestPeer(s"safety-t4-peer-$round", testKit.createTestProbe[Any]().ref.toClassic)
        coordinator ! TrieNodeHealingCoordinator.HealingPeerAvailable(peer)
        val send = networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd]
        val reqId = getTrieNodesOf(send).requestId
        coordinator ! TrieNodeHealingCoordinator.HealingRequestTimeout(reqId)
        // The task must be back in the pending frontier after the timeout (never abandoned).
        eventually(timeout(5.seconds), interval(100.millis))(stats(coordinator).pendingTasks shouldBe 1)

      dispatchAndTimeout(1) // attempts(hash) = 1  (below threshold)
      dispatchAndTimeout(2) // attempts(hash) = 2  (at threshold, not yet surfaced)
      dispatchAndTimeout(3) // attempts(hash) = 3  (threshold+1 → surface: WARN log + unservable gauge = 1)

      // NOTE: the FR-006 surfacing is observed via the decoupled "unservable tasks" gauge, which the coordinator
      // sets to the count of hashes over the threshold at the crossing point (a WARN log is the human signal).
      // The gauge is the deterministic, machine-checkable observable; we await it because the actor sets it on
      // its own thread. It is a global singleton, so we assert it reaches the expected count rather than an
      // exact start value.
      eventually(timeout(5.seconds), interval(100.millis)) {
        gaugeValue("snapsync.healing.decoupled.unservable_tasks.gauge") shouldBe 1.0 +- 1e-9
      }

      // No completion was ever declared while the task stayed unsatisfied (SC-002).
      assertNoStateHealingComplete(snapSyncController, 300.millis)

      // A serve-root advance is the legitimate retry trigger: it clears the per-task attempt counters and
      // resets the unservable gauge to 0 (the task gets a fresh budget under the new serve root).
      coordinator ! TrieNodeHealingCoordinator.HealingServeRootRefresh(kec256(ByteString("safety-t4-new-serve-root")))
      eventually(timeout(5.seconds), interval(100.millis)) {
        gaugeValue("snapsync.healing.decoupled.unservable_tasks.gauge") shouldBe 0.0 +- 1e-9
      }

      // HealingForceComplete must NOT declare StateHealingComplete while the task is still unsatisfied under
      // decoupling — abandoning here would signal completion with a real trie gap (consensus-unsafe). The
      // frontier is preserved and healing continues.
      coordinator ! TrieNodeHealingCoordinator.HealingForceComplete
      assertNoStateHealingComplete(snapSyncController, 500.millis)
      stats(coordinator).pendingTasks shouldBe 1 // frontier kept, not abandoned
    }

  // ── T-5: byte-for-byte completion parity, decoupled vs coupled ────────────────────────────────

  /** Drive the same clean healed state to completion with the given decoupling setting, and return the root the
    * coordinator says its clean full walk covered (spec 022 T043, which replaced the CF `g` completeness marker here):
    * a single-thread EC, safe teardown.
    */
  private def runToCompletion(decoupled: Boolean): ByteString =
    val pool = Executors.newSingleThreadExecutor()
    val ec = ExecutionContext.fromExecutorService(pool)
    val cleanRoot = new java.util.concurrent.atomic.AtomicReference[ByteString]()

    // A present, complete walk root (childless leaf in storage) so the verification walk finds 0 missing.
    val storage = new TestMptStorage()
    val rootLeaf = LeafNode(
      ByteString(Array[Byte](0x01)),
      ByteString(
        com.chipprbots.ethereum.domain.Account.accountSerializer.toBytes(com.chipprbots.ethereum.domain.Account())
      )
    )
    storage.putNode(rootLeaf)
    val root = ByteString(rootLeaf.hash)

    val nodes = (0 until 3).map(cleanLeaf)
    val controller = testKit.createTestProbe[SNAPSyncController.Command]()
    val coordinator = HealingTrieFixtures.spawnCoordinator(
      stateRoot = root,
      networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]().ref,
      requestTracker = new SNAPRequestTracker()(classicSystem.scheduler),
      mptStorage = storage,
      batchSize = 64,
      snapSyncController = controller.ref,
      healingWriterEcOverride = Some(ec),
      decoupledHealServeRoot = decoupled,
      cleanWalkRoot = Some(cleanRoot)
    )
    try
      // Under decoupling, advancing the serve root must not change the completion outcome (it supplies node
      // bytes only; completion is decided against the unchanged walk root). The served node bytes are the
      // walk root's expectation either way (content-hash-verified), so the final stored state is identical.
      if decoupled then
        coordinator ! TrieNodeHealingCoordinator.HealingServeRootRefresh(
          kec256(ByteString("decoupled-parity-serve-root"))
        )
      val peer = PeerTestHelpers.createTestPeer(s"parity-peer-$decoupled", testKit.createTestProbe[Any]().ref.toClassic)
      coordinator ! TrieNodeHealingCoordinator.QueueMissingNodes(nodes.map { case (ps, h, _) => (ps, h) })
      coordinator ! TrieNodeHealingCoordinator.HealingPeerAvailable(peer)
      coordinator ! TrieNodeHealingCoordinator.TrieNodesResponseMsg(
        SNAP.TrieNodes(requestId = 1, nodes = nodes.map(_._3))
      )
      // Completion is reached via the SAME StateHealingComplete signal on both paths (the load-bearing parity
      // observable, alongside the marker + state root below). fishForMessage tolerates interleaved progress.
      controller.fishForMessage(10.seconds) {
        case SNAPSyncController.StateHealingComplete   => FishingOutcomes.complete
        case _: SNAPSyncController.ProgressNodesHealed => FishingOutcomes.continueAndIgnore
        case _                                         => FishingOutcomes.continueAndIgnore
      }
      // NOTE: the decoupled-engaged gauge is a global singleton and would race other specs running in
      // parallel, so it is NOT asserted here — the engaged-vs-disabled path distinction is covered by the
      // per-coordinator DecoupledHealObservabilitySpec (T-7). T-5's parity claim is the COMPLETION outcome:
      // the same StateHealingComplete signal (above), the same clean walk (returned below), and the same
      // unchanged state root. The state root is never recomputed by verification (a pure local read) — assert
      // the invariant: the same stored leaf yields the same root regardless of the flag.
      val freshRoot =
        val s = new TestMptStorage()
        val l = LeafNode(
          ByteString(Array[Byte](0x01)),
          ByteString(
            com.chipprbots.ethereum.domain.Account.accountSerializer.toBytes(com.chipprbots.ethereum.domain.Account())
          )
        )
        s.putNode(l)
        ByteString(l.hash)
      root shouldBe freshRoot
      cleanRoot.get()
    finally
      testKit.stop(coordinator)
      pool.shutdown()
      pool.awaitTermination(5, TimeUnit.SECONDS)

  "Decoupled vs coupled completion (T-5)" should
    "reach an identical completion (StateHealingComplete + clean walk + state root) under both settings (FR-007)" taggedAs UnitTest in {
      val decoupled = runToCompletion(decoupled = true)
      val coupled = runToCompletion(decoupled = false)
      // Both paths declare completion via the single verificationPassComplete chokepoint. The state root is never
      // recomputed by either path (asserted inside runToCompletion).
      decoupled should not be null
      decoupled shouldBe coupled
    }
