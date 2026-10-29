package com.chipprbots.ethereum.blockchain.sync.snap.actors.healing

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.util.ByteString

import scala.concurrent.ExecutionContext

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap.SNAPRequestTracker
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController
import com.chipprbots.ethereum.blockchain.sync.snap.StorageScheme
import com.chipprbots.ethereum.blockchain.sync.snap.actors.HealingStatistics
import com.chipprbots.ethereum.blockchain.sync.snap.actors.HealingTrieFixtures
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator
import com.chipprbots.ethereum.network.p2p.messages.SNAP
import com.chipprbots.ethereum.testing.PeerTestHelpers
import com.chipprbots.ethereum.testing.Tags.UnitTest
import com.chipprbots.ethereum.testing.TestMptStorage

/** Spec 022 slice 1, T009 (D2, Hash scheme): a heal request is keyed by node hash, so a child shared by two parents is
  * one request, reference-counted per parent edge.
  *
  * The bug: the dispatch removed a request's hash from the de-duplication set (`pendingHashSet`) when it sent it, so a
  * second parent that arrived while the shared child was in flight found it neither pending nor stored, and queued it
  * again: the same node was fetched twice.
  *
  * Method: a real Hash-scheme coordinator, one path per request (`batchSize = 1`), and a fake peer the test answers by
  * hand, so the order is exact: the root; then parent A, whose children (one of them the shared child C) go out; then,
  * with C still in flight, parent B. The coordinator's own statistics are the barrier and the oracle: after B, the
  * in-flight set is A's leaf, C and B's leaf, with C once.
  */
class HealRequestModelSpec extends ScalaTestWithActorTestKit() with AnyFlatSpecLike with Matchers:

  implicit private val classicSystem: org.apache.pekko.actor.ActorSystem = system.classicSystem
  implicit private val actorTestKit: ActorTestKit = testKit

  import HealRequestFixtures.*

  "Hash-scheme healing" should "request a child shared by two parents once, even when the second parent arrives while it is in flight (T009)" taggedAs UnitTest in {
    // root (branch) []
    //   ├─[0] A (branch)  [0]      ├─[0] leafA [0,0]    └─[5] C [0,5]
    //   └─[1] B (branch)  [1]      ├─[0] leafB [1,0]    └─[5] C [1,5]     ← the same node C under both parents
    val c = plainLeaf(nibbles(0x07), "s022/t009/shared")
    val leafA = plainLeaf(nibbles(0x01), "s022/t009/leafA")
    val leafB = plainLeaf(nibbles(0x02), "s022/t009/leafB")
    val a = branch(0 -> leafA, 5 -> c)
    val b = branch(0 -> leafB, 5 -> c)
    val root = branch(0 -> a, 1 -> b)
    val served: Map[Seq[ByteString], ByteString] = Map(
      accountPathset() -> ByteString(root.encode),
      accountPathset(0) -> ByteString(a.encode),
      accountPathset(1) -> ByteString(b.encode),
      accountPathset(0, 0) -> ByteString(leafA.encode),
      accountPathset(0, 5) -> ByteString(c.encode),
      accountPathset(1, 0) -> ByteString(leafB.encode),
      accountPathset(1, 5) -> ByteString(c.encode)
    )

    val pool = Executors.newSingleThreadExecutor()
    val peer = new ScriptedPeer(testKit)
    val coordinator = HealingTrieFixtures.spawnCoordinator(
      stateRoot = ByteString(root.hash),
      networkPeerManager = peer.ref,
      requestTracker = new SNAPRequestTracker()(classicSystem.scheduler),
      mptStorage = new TestMptStorage(), // empty: nothing is stored yet
      batchSize = 1,
      snapSyncController = createTestProbe[SNAPSyncController.Command]().ref,
      healingWriterEcOverride = Some(ExecutionContext.fromExecutorService(pool)),
      storageScheme = StorageScheme.Hash,
      movingRootDeltaHeal = true
    )
    peer.coordinator.set(coordinator)

    def next(): SNAP.GetTrieNodes = peer.manual.expectMessageType[SNAP.GetTrieNodes]

    def stats(): HealingStatistics =
      val probe = createTestProbe[HealingStatistics]()
      coordinator ! TrieNodeHealingCoordinator.HealingGetProgress(probe.ref)
      probe.expectMessageType[HealingStatistics]

    try
      coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(ByteString(root.hash))
      coordinator ! TrieNodeHealingCoordinator.HealingPeerAvailable(
        PeerTestHelpers.createTestPeer("t009-peer", createTestProbe[Any]().ref.toClassic)
      )

      val rootReq = next()
      rootReq.paths shouldBe Seq(accountPathset())
      peer.answerFrom(rootReq, served)

      val parents = Seq(next(), next())
      parents.map(_.paths).toSet shouldBe Set(Seq(accountPathset(0)), Seq(accountPathset(1)))
      val reqA = parents.find(_.paths == Seq(accountPathset(0))).get
      val reqB = parents.find(_.paths == Seq(accountPathset(1))).get

      peer.answerFrom(reqA, served) // A arrives: leafA and C go out
      Seq(next(), next()).map(_.paths).toSet shouldBe Set(Seq(accountPathset(0, 0)), Seq(accountPathset(0, 5)))

      peer.answerFrom(reqB, served) // B arrives while C is in flight
      // Barrier: the statistics reply is sent after B's response is processed. In flight: leafA, C, leafB. C is not
      // requested again for B: B gains an edge to the request already out.
      val afterB = stats()
      afterB.activeTasks shouldBe 3
      afterB.pendingTasks shouldBe 0
      next().paths shouldBe Seq(accountPathset(1, 0))
    finally
      testKit.stop(coordinator)
      testKit.stop(peer.ref)
      pool.shutdown()
      pool.awaitTermination(5, TimeUnit.SECONDS)
  }
