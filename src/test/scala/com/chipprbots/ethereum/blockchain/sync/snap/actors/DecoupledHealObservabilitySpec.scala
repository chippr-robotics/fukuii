package com.chipprbots.ethereum.blockchain.sync.snap.actors

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.util.ByteString

import scala.concurrent.duration.*

import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap.*
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.metrics.Metrics
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.p2p.messages.SNAP
import com.chipprbots.ethereum.testing.PeerTestHelpers
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.testing.TestMptStorage

/** spec 004 (Decoupled Heal Serve-Root), US3 — engagement + observability (T-7, FR-010, C9).
  *
  * With the feature ON the coordinator emits the engagement signal at start (decoupled-engaged gauge = 1, walk-root and
  * serve-root short-label gauges seeded), advances the serve-root gauge on each HealingServeRootRefresh, and tracks
  * cross-root heals / unservable tasks. With the feature OFF the "decoupling disabled" path is taken (engaged gauge =
  * 0) and the fetch uses the walk root.
  *
  * The decoupled gauges are global JVM singletons, so this spec ties each observation to its own coordinator by
  * asserting the walk-root / serve-root gauges equal `shortRootLabel` of distinct roots no other test uses — that value
  * can only have been written by this coordinator's preStart / refresh handler.
  */
class DecoupledHealObservabilitySpec
    extends ScalaTestWithActorTestKit()
    with AnyFlatSpecLike
    with Matchers
    with Eventually:

  implicit private val classicSystem: org.apache.pekko.actor.ActorSystem = system.classicSystem
  implicit private val actorTestKit: org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit = testKit
  private def gaugeValue(name: String): Double =
    val gauge = Metrics.get().registry.find(name).gauge()
    if gauge == null then Double.NaN else gauge.value()

  private def getTrieNodesOf(send: NetworkPeerManagerActor.SendMessageCmd): SNAP.GetTrieNodes =
    send.message.underlyingMsg.asInstanceOf[SNAP.GetTrieNodes]

  /** Mirror of `TrieNodeHealingCoordinator.shortRootLabel`: the leading (up to) 6 bytes of a root packed big-endian
    * into a Long. Replicated here so a gauge observation can be tied to a specific, distinct root value (the gauges are
    * global singletons; matching the exact short label proves THIS coordinator wrote it). Six bytes (48 bits) keeps the
    * label non-negative and exactly representable as a Prometheus double — must stay in lockstep with production.
    */
  private def shortRootLabel(root: ByteString): Long =
    var acc = 0L
    val n = root.length.min(6)
    var i = 0
    while i < n do
      acc = (acc << 8) | (root(i) & 0xffL)
      i += 1
    acc

  private def buildCoordinator(
      stateRoot: ByteString,
      decoupled: Boolean
  ): (
      ActorRef[TrieNodeHealingCoordinator.Command],
      org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe[NetworkPeerManagerActor.Command]
  ) =
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val coordinator = HealingTrieFixtures.spawnCoordinator(
      stateRoot = stateRoot,
      networkPeerManager = networkPeerManager.ref,
      requestTracker = new SNAPRequestTracker()(using classicSystem.scheduler),
      mptStorage = new TestMptStorage(),
      batchSize = 16,
      snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]().ref,
      healingWriterEcOverride = Some(classicSystem.dispatcher),
      decoupledHealServeRoot = decoupled
    )
    (coordinator, networkPeerManager)

  // ── T-7 (on): engagement signal + observability gauges emitted ────────────────────────────────

  "Decoupled heal observability (T-7, feature on)" should
    "emit the engagement signal + walk/serve-root gauges and advance the serve-root gauge on refresh" taggedAs UnitTest in {
      val stateRoot = kec256(ByteString("observ-t7-on-walk-root"))
      val (coordinator, _) = buildCoordinator(stateRoot, decoupled = true)

      // preStart engagement signal: decoupled engaged = 1, walk-root gauge seeded to the walk root's short label,
      // serve-root gauge seeded equal to the walk root (serveRoot == stateRoot at init).
      eventually(timeout(5.seconds), interval(100.millis)) {
        gaugeValue("snapsync.healing.decoupled.engaged.gauge") shouldBe 1.0 +- 1e-9
        gaugeValue("snapsync.healing.decoupled.walk_root.gauge") shouldBe shortRootLabel(stateRoot).toDouble +- 1e-9
        gaugeValue("snapsync.healing.decoupled.serve_root.gauge") shouldBe shortRootLabel(stateRoot).toDouble +- 1e-9
      }

      // A serve-root advance updates the serve-root gauge (to the new root's short label) while the walk-root
      // gauge stays pinned to the walk root — the two roots are observably distinct after the advance.
      val newServeRoot = kec256(ByteString("observ-t7-on-serve-root"))
      coordinator ! TrieNodeHealingCoordinator.HealingServeRootRefresh(newServeRoot)
      eventually(timeout(5.seconds), interval(100.millis)) {
        gaugeValue("snapsync.healing.decoupled.serve_root.gauge") shouldBe shortRootLabel(
          newServeRoot
        ).toDouble +- 1e-9
        gaugeValue("snapsync.healing.decoupled.walk_root.gauge") shouldBe shortRootLabel(stateRoot).toDouble +- 1e-9
      }
    }

  // ── T-7 (off): decoupling-disabled path taken; fetch uses the walk root ───────────────────────

  "Decoupled heal observability (T-7, feature off)" should
    "take the disabled path (engaged gauge = 0) and fetch against the walk root" taggedAs UnitTest in {
      val stateRoot = kec256(ByteString("observ-t7-off-walk-root"))
      val (coordinator, networkPeerManager) = buildCoordinator(stateRoot, decoupled = false)

      // preStart takes the "decoupling disabled" path: engaged gauge = 0. The walk-root gauge is still seeded
      // (observation-only, always set), and the serve-root gauge equals the walk root (no decoupling).
      eventually(timeout(5.seconds), interval(100.millis)) {
        gaugeValue("snapsync.healing.decoupled.engaged.gauge") shouldBe 0.0 +- 1e-9
        gaugeValue("snapsync.healing.decoupled.walk_root.gauge") shouldBe shortRootLabel(stateRoot).toDouble +- 1e-9
      }

      // The fetch uses the walk root (decoupling disabled) — observed on the actual GetTrieNodes.
      val nodeHash = kec256(ByteString("observ-t7-off-missing-node"))
      coordinator ! TrieNodeHealingCoordinator.QueueMissingNodes(Seq((Seq(ByteString(Array[Byte](0x00))), nodeHash)))
      val peer = PeerTestHelpers.createTestPeer("observ-t7-off-peer", testKit.createTestProbe[Any]().ref.toClassic)
      coordinator ! TrieNodeHealingCoordinator.HealingPeerAvailable(peer)
      val send = networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd]
      getTrieNodesOf(send).rootHash shouldBe stateRoot
    }
