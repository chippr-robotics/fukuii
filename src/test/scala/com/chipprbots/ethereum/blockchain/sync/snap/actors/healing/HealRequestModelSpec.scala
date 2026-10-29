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
import com.chipprbots.ethereum.blockchain.sync.snap.heal.HealRequest
import com.chipprbots.ethereum.blockchain.sync.snap.heal.HealRequestBook
import com.chipprbots.ethereum.blockchain.sync.snap.heal.HealRequestKind
import com.chipprbots.ethereum.crypto.kec256
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
  *
  * The cases after it test the model itself (`HealRequest`, `HealRequestBook`): the per-parent edge counts are
  * observable only there, on types that did not exist before the fix.
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

  // ---- exits of a joined request (review, forge W1) ------------------------------------------------------------------

  /** The T009 trie and order, up to "B answered while C is in flight": the root, parents A and B (one path per
    * request), A's children leafA and C out, then B answered, which joins C. Runs `body` with the coordinator, the
    * peer, the served map, C's in-flight request and a statistics barrier; stops everything afterwards.
    */
  private def withSharedChildInFlight(
      body: (
          org.apache.pekko.actor.typed.ActorRef[TrieNodeHealingCoordinator.Command],
          ScriptedPeer,
          Map[Seq[ByteString], ByteString],
          SNAP.GetTrieNodes,
          () => HealingStatistics
      ) => Unit
  ): Unit =
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
      mptStorage = new TestMptStorage(),
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
        PeerTestHelpers.createTestPeer("exit-peer-1", createTestProbe[Any]().ref.toClassic)
      )
      peer.answerFrom(next(), served) // the root
      val parents = Seq(next(), next())
      peer.answerFrom(parents.find(_.paths == Seq(accountPathset(0))).get, served) // A: leafA and C go out
      val outOfA = Seq(next(), next())
      val reqC = outOfA.find(_.paths == Seq(accountPathset(0, 5))).get
      peer.answerFrom(parents.find(_.paths == Seq(accountPathset(1))).get, served) // B joins C
      next().paths shouldBe Seq(accountPathset(1, 0)) // leafB; C is not requested again
      body(coordinator, peer, served, reqC, () => stats())
    finally
      testKit.stop(coordinator)
      testKit.stop(peer.ref)
      pool.shutdown()
      pool.awaitTermination(5, TimeUnit.SECONDS)

  it should "re-dispatch a joined child once after its request times out, and fill it with no second fetch" taggedAs UnitTest in {
    withSharedChildInFlight { (coordinator, peer, served, reqC, stats) =>
      coordinator ! TrieNodeHealingCoordinator.HealingRequestTimeout(reqC.requestId) // peer 1 now cools down
      val afterTimeout = stats()
      afterTimeout.pendingTasks shouldBe 1 // C, queued once
      afterTimeout.activeTasks shouldBe 2 // leafA, leafB

      coordinator ! TrieNodeHealingCoordinator.HealingPeerAvailable(
        PeerTestHelpers.createTestPeer("exit-peer-2", createTestProbe[Any]().ref.toClassic)
      )
      val again = peer.manual.expectMessageType[SNAP.GetTrieNodes]
      again.paths shouldBe reqC.paths // C, re-dispatched once
      peer.answerFrom(again, served)
      val afterC = stats() // C filled for both parents: nothing queued, only the two leaves in flight
      afterC.pendingTasks shouldBe 0
      afterC.activeTasks shouldBe 2
    }
  }

  it should "re-dispatch a joined child once after its peer disconnects, and fill it with no second fetch" taggedAs UnitTest in {
    withSharedChildInFlight { (coordinator, peer, served, reqC, stats) =>
      coordinator ! TrieNodeHealingCoordinator.HealingPeerUnavailable(
        "exit-peer-1"
      ) // leafA, C, leafB back to the queue
      val afterDrop = stats()
      afterDrop.pendingTasks shouldBe 3
      afterDrop.activeTasks shouldBe 0

      coordinator ! TrieNodeHealingCoordinator.HealingPeerAvailable(
        PeerTestHelpers.createTestPeer("exit-peer-2", createTestProbe[Any]().ref.toClassic)
      )
      val again = Seq.fill(3)(peer.manual.expectMessageType[SNAP.GetTrieNodes])
      again.count(_.paths == reqC.paths) shouldBe 1 // C, re-dispatched once
      peer.answerFrom(again.find(_.paths == reqC.paths).get, served)
      val afterC = stats()
      afterC.pendingTasks shouldBe 0
      afterC.activeTasks shouldBe 2
    }
  }

  // ---- the request model (spec 022 D2, data-model.md "Heal request") -----------------------------------------------

  private def hashOf(seed: String): ByteString = kec256(ByteString(seed))

  "HealRequestBook under the Hash scheme" should "record a child shared by two parents once, count one edge per parent, and release both parents when it arrives (T009)" taggedAs UnitTest in {
    val book = new HealRequestBook()
    val (pa, pb) = (hashOf("parent-a"), hashOf("parent-b"))
    val c = hashOf("shared-child")
    val leafA = HealRequest.ofNode(accountPathset(0, 0), hashOf("leaf-a"))
    val leafB = HealRequest.ofNode(accountPathset(1, 0), hashOf("leaf-b"))
    val cUnderA = HealRequest.ofNode(accountPathset(0, 5), c)
    val cUnderB = HealRequest.ofNode(accountPathset(1, 5), c)

    book.addChildren(pa, Seq(leafA, cUnderA)) shouldBe Seq(leafA, cUnderA) // both need a fetch
    book.addChildren(pb, Seq(leafB, cUnderB)) shouldBe Seq(leafB) // C is live: B gains an edge, no second fetch
    book.size shouldBe 3
    book.waitingOn(c) shouldBe Seq(cUnderA) // one request for the node, fetched from where it was first found
    book.parentsOf(c) shouldBe Seq(pa, pb)
    book.outstandingChildren(pa) shouldBe 2
    book.outstandingChildren(pb) shouldBe 2

    // C arrives: both parents' counts decrement; neither is done yet, each still waits on its own leaf.
    book.satisfy(c) shouldBe empty
    book.outstandingChildren(pa) shouldBe 1
    book.outstandingChildren(pb) shouldBe 1
    book.isFetching(c) shouldBe false

    book.satisfy(leafA.key) shouldBe Seq(pa)
    book.satisfy(leafB.key) shouldBe Seq(pb)
    book.isEmpty shouldBe true
    book.outstandingChildren(pa) shouldBe 0
  }

  it should "count a child one parent references twice as two edges, fetched once" taggedAs UnitTest in {
    val book = new HealRequestBook()
    val parent = hashOf("parent")
    val c = hashOf("twice")
    val atSlot2 = HealRequest.ofNode(accountPathset(2), c)
    book.addChildren(parent, Seq(atSlot2, HealRequest.ofNode(accountPathset(9), c))) shouldBe Seq(atSlot2)
    book.outstandingChildren(parent) shouldBe 2
    book.parentsOf(c) shouldBe Seq(parent, parent)
    book.satisfy(c) shouldBe Seq(parent)
    book.isEmpty shouldBe true
  }

  it should "add no second set of edges when a parent is processed again" taggedAs UnitTest in {
    val book = new HealRequestBook()
    val parent = hashOf("parent")
    val child = HealRequest.ofNode(accountPathset(3), hashOf("child"))
    book.addChildren(parent, Seq(child)) shouldBe Seq(child)
    book.addChildren(parent, Seq(child)) shouldBe empty
    book.outstandingChildren(parent) shouldBe 1
    book.satisfy(child.key) shouldBe Seq(parent)
  }

  it should "keep a request live until it is satisfied, so a walk entry for a live hash is not fetched again" taggedAs UnitTest in {
    val book = new HealRequestBook()
    val h = hashOf("node")
    book.add(HealRequest.ofNode(accountPathset(1), h)) shouldBe HealRequestBook.Added.NewFetch
    book.add(HealRequest.ofNode(accountPathset(4), h)) shouldBe HealRequestBook.Added.Duplicate
    book.opensNewSubtree(h) shouldBe true
    book.addChildren(hashOf("parent"), Seq(HealRequest.ofNode(accountPathset(1), h))) shouldBe empty
    book.opensNewSubtree(h) shouldBe false // a parent now waits on it
    book.clear()
    book.isEmpty shouldBe true
    book.fetchingHashes shouldBe empty
    book.outstandingChildren(hashOf("parent")) shouldBe 0
  }

  "HealRequestBook under the Path scheme" should "keep each position of one node as its own request, fetched once, each released to its own parent" taggedAs UnitTest in {
    val book = new HealRequestBook()
    val s = hashOf("storage-root")
    val (a0, a1) = (ByteString(Array.fill[Byte](32)(0x0a)), ByteString(Array.fill[Byte](32)(0x0b)))
    val (leaf0, leaf1) = (hashOf("acct0-position"), hashOf("acct1-position"))
    val at0 = HealRequest.atPosition(Seq(a0, compact(Array.empty[Byte])), s)
    val at1 = HealRequest.atPosition(Seq(a1, compact(Array.empty[Byte])), s)
    at0.key should not be at1.key

    book.addChildren(leaf0, Seq(at0)) shouldBe Seq(at0) // the fetch
    book.addChildren(leaf1, Seq(at1)) shouldBe empty // joins it: an alias, recorded, not dropped
    book.add(at1) shouldBe HealRequestBook.Added.Duplicate
    book.add(HealRequest.atPosition(accountPathset(7), s)) shouldBe HealRequestBook.Added.Joined
    book.waitingOn(s).map(_.key) shouldBe Seq(at0.key, at1.key, HealRequest.positionKey(accountPathset(7)))
    book.satisfy(at0.key) shouldBe Seq(leaf0)
    book.isFetching(s) shouldBe true // the other positions still wait on it
    book.satisfy(at1.key) shouldBe Seq(leaf1)
  }

  it should "keep the order of the positions still waiting on a hash when any of them is filled, the first included" taggedAs UnitTest in {
    val book = new HealRequestBook()
    val s = hashOf("aliased")
    val at = (1 to 4).map(i => HealRequest.atPosition(accountPathset(i), s))
    book.add(at(0)) shouldBe HealRequestBook.Added.NewFetch
    at.drop(1).foreach(r => book.add(r) shouldBe HealRequestBook.Added.Joined)

    book.satisfy(at(0).key) // the first: the next one takes its place
    book.waitingOn(s) shouldBe Seq(at(1), at(2), at(3))
    book.isFetching(s) shouldBe true
    book.satisfy(at(2).key) // one in the middle
    book.waitingOn(s) shouldBe Seq(at(1), at(3))
    book.satisfy(at(3).key)
    book.satisfy(at(1).key)
    book.isFetching(s) shouldBe false
    book.waitingOn(s) shouldBe empty
    book.isEmpty shouldBe true
  }

  "HealRequest" should "key a 64-nibble account position apart from a storage-trie root of the same byte length, and give each its depth" taggedAs UnitTest in {
    val accountAt64 = Seq(compact(Array.fill[Byte](64)(0))) // HP: flag byte + 32 bytes = 33 bytes
    val storageRoot = Seq(ByteString(Array.fill[Byte](32)(0)), compact(Array.empty[Byte])) // 32 + 1 = 33 bytes
    (storageRoot.head ++ storageRoot.last).length shouldBe accountAt64.head.length
    HealRequest.positionKey(accountAt64) should not be HealRequest.positionKey(storageRoot)

    HealRequest.depthOf(accountAt64) shouldBe 64
    HealRequest.depthOf(storageRoot) shouldBe 64
    HealRequest.depthOf(accountPathset()) shouldBe 0
    HealRequest.depthOf(accountPathset(1, 2, 3)) shouldBe 3
    val storageNode = Seq(ByteString(Array.fill[Byte](32)(1)), compact(nibbles(1, 2)))
    HealRequest.depthOf(storageNode) shouldBe 66
    HealRequest.atPosition(storageNode, hashOf("x")).kind shouldBe HealRequestKind.StorageNode
    HealRequest.ofNode(accountPathset(4), hashOf("y")).kind shouldBe HealRequestKind.AccountNode
    HealRequest.ofNode(accountPathset(4), hashOf("y")).key shouldBe hashOf("y")
  }
