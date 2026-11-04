package com.chipprbots.ethereum.blockchain.sync.snap.actors

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.util.ByteString

import scala.concurrent.duration.*

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap.*
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.p2p.messages.SNAP
import com.chipprbots.ethereum.testing.PeerTestHelpers
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.testing.TestMptStorage

/** spec 004 (Decoupled Heal Serve-Root), US1 — fetch-root decoupling.
  *
  * The coordinator keeps two roots: the fixed completeness WALK root (`stateRoot`) that the BFS seeds and the
  * completion gate key off, and an advancing SERVE root (`serveRoot`) used only to build the `GetTrieNodes` fetch.
  * These tests drive a real coordinator and assert on the actual `GetTrieNodes` message it sends to its
  * `networkPeerManager` (captured via a `TestProbe` standing in for `NetworkPeerManagerActor`).
  *
  *   - T-1 (FR-001/FR-002, C1/C3): with the feature on and `serveRoot != stateRoot`, the emitted `GetTrieNodes` carries
  *     `rootHash == serveRoot`, while the walk continues to seed against `stateRoot`.
  *   - T-2 (FR-001/FR-009, C2): `HealingServeRootRefresh` advances `serveRoot` ONLY — the walk root, the pending
  *     frontier, and the completion behavior are untouched.
  *   - T-6 (FR-008/SC-006, C3): with the feature off, the `GetTrieNodes` always carries `rootHash == stateRoot` and a
  *     `HealingServeRootRefresh` is ignored — byte-identical to the coupled path.
  */
class DecoupledHealServeRootSpec extends ScalaTestWithActorTestKit() with AnyFlatSpecLike with Matchers:

  implicit private val classicSystem: org.apache.pekko.actor.ActorSystem = system.classicSystem
  implicit private val actorTestKit: org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit = testKit

  /** Extract the underlying `GetTrieNodes` from the captured `NetworkPeerManagerActor.SendMessageCmd`. The coordinator
    * wraps the request in a `GetTrieNodesEnc` (a `MessageSerializable`); `underlyingMsg` is the original message.
    */
  private def getTrieNodesOf(send: NetworkPeerManagerActor.SendMessageCmd): SNAP.GetTrieNodes =
    send.message.underlyingMsg.asInstanceOf[SNAP.GetTrieNodes]

  private def pendingTasks(coordinator: ActorRef[TrieNodeHealingCoordinator.Command]): Int =
    val probe = testKit.createTestProbe[HealingStatistics]()
    coordinator ! TrieNodeHealingCoordinator.HealingGetProgress(probe.ref)
    probe.expectMessageType[HealingStatistics].pendingTasks

  /** Build a coordinator with the given decoupling flag, returning (coordinator, networkPeerManager probe,
    * snapSyncController probe). The walk root is the supplied `stateRoot`.
    */
  private def buildCoordinator(
      stateRoot: ByteString,
      decoupled: Boolean
  ): (
      ActorRef[TrieNodeHealingCoordinator.Command],
      org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe[NetworkPeerManagerActor.Command],
      org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe[SNAPSyncController.Command]
  ) =
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()
    val coordinator = HealingTrieFixtures.spawnCoordinator(
      stateRoot = stateRoot,
      networkPeerManager = networkPeerManager.ref,
      requestTracker = new SNAPRequestTracker()(using classicSystem.scheduler),
      mptStorage = new TestMptStorage(),
      batchSize = 16,
      snapSyncController = snapSyncController.ref,
      healingWriterEcOverride = Some(classicSystem.dispatcher),
      decoupledHealServeRoot = decoupled
    )
    (coordinator, networkPeerManager, snapSyncController)

  // ── T-1: decoupled fetch targets the serve root, walk seeds the walk root ─────────────────────

  "Decoupled heal serve-root (T-1)" should
    "emit a GetTrieNodes carrying serveRoot once advanced, while the walk seeds stateRoot" taggedAs UnitTest in {
      val stateRoot = kec256(ByteString("decoupled-t1-walk-root"))
      val serveRoot = kec256(ByteString("decoupled-t1-serve-root"))
      serveRoot should not be stateRoot // distinct 32-byte roots

      val (coordinator, networkPeerManager, _) = buildCoordinator(stateRoot, decoupled = true)
      val peer = PeerTestHelpers.createTestPeer("decoupled-t1-peer", testKit.createTestProbe[Any]().ref.toClassic)

      // Advance the serve root, then queue a missing node and make a peer available so a fetch dispatches.
      coordinator ! TrieNodeHealingCoordinator.HealingServeRootRefresh(serveRoot)
      val nodeHash = kec256(ByteString("decoupled-t1-missing-node"))
      coordinator ! TrieNodeHealingCoordinator.QueueMissingNodes(Seq((Seq(ByteString(Array[Byte](0x00))), nodeHash)))
      coordinator ! TrieNodeHealingCoordinator.HealingPeerAvailable(peer)

      val send = networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd]
      val request = getTrieNodesOf(send)
      // C3: the fetch root is the advancing serve root, NOT the walk root.
      request.rootHash shouldBe serveRoot
      request.rootHash should not be stateRoot
    }

  // ── T-2: HealingServeRootRefresh advances the serve root ONLY ─────────────────────────────────

  it should "advance serveRoot only — walk root, frontier and completion behavior unchanged (T-2)" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("decoupled-t2-walk-root"))
    val serveRoot = kec256(ByteString("decoupled-t2-serve-root"))

    val (coordinator, networkPeerManager, snapSyncController) = buildCoordinator(stateRoot, decoupled = true)

    // Queue two missing nodes BEFORE the refresh — the persisted/in-memory frontier under test.
    val h1 = kec256(ByteString("decoupled-t2-node-1"))
    val h2 = kec256(ByteString("decoupled-t2-node-2"))
    coordinator ! TrieNodeHealingCoordinator.QueueMissingNodes(
      Seq(
        (Seq(ByteString(Array[Byte](0x00))), h1),
        (Seq(ByteString(Array[Byte](0x01))), h2)
      )
    )
    val pendingBefore = pendingTasks(coordinator)
    pendingBefore shouldBe 2

    // The refresh advances the serve root. It MUST NOT clear the frontier or re-seed the walk.
    coordinator ! TrieNodeHealingCoordinator.HealingServeRootRefresh(serveRoot)

    // The pending frontier is unchanged (NOT cleared the way HealingPivotRefreshed would).
    pendingTasks(coordinator) shouldBe pendingBefore

    // Completion still keys off the WALK root: an idle (no pending) coordinator completes, but here the
    // frontier is non-empty so HealingCheckCompletion must NOT declare completion — the refresh did not
    // perturb the completion gate (which reads stateRoot, never serveRoot).
    coordinator ! TrieNodeHealingCoordinator.HealingCheckCompletion
    snapSyncController.expectNoMessage(300.millis)

    // And the fetch now uses the advanced serve root, proving the refresh took effect on serveRoot alone.
    val peer = PeerTestHelpers.createTestPeer("decoupled-t2-peer", testKit.createTestProbe[Any]().ref.toClassic)
    coordinator ! TrieNodeHealingCoordinator.HealingPeerAvailable(peer)
    val send = networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd]
    getTrieNodesOf(send).rootHash shouldBe serveRoot
  }

  // ── T-6: feature off ⇒ fetch uses the walk root; refresh is ignored (coupled parity) ──────────

  "Decoupled heal serve-root disabled (T-6)" should
    "emit a GetTrieNodes carrying stateRoot even after a HealingServeRootRefresh (coupled, byte-identical)" taggedAs UnitTest in {
      val stateRoot = kec256(ByteString("coupled-t6-walk-root"))
      val serveRoot = kec256(ByteString("coupled-t6-serve-root"))

      val (coordinator, networkPeerManager, _) = buildCoordinator(stateRoot, decoupled = false)
      val peer = PeerTestHelpers.createTestPeer("coupled-t6-peer", testKit.createTestProbe[Any]().ref.toClassic)

      // A serve-root refresh must be a no-op when the feature is disabled.
      coordinator ! TrieNodeHealingCoordinator.HealingServeRootRefresh(serveRoot)
      val nodeHash = kec256(ByteString("coupled-t6-missing-node"))
      coordinator ! TrieNodeHealingCoordinator.QueueMissingNodes(Seq((Seq(ByteString(Array[Byte](0x00))), nodeHash)))
      coordinator ! TrieNodeHealingCoordinator.HealingPeerAvailable(peer)

      val send = networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd]
      val request = getTrieNodesOf(send)
      // C3 (off): the fetch root is the walk root, NOT the ignored serve root — coupled behavior.
      request.rootHash shouldBe stateRoot
      request.rootHash should not be serveRoot
    }
