package com.chipprbots.ethereum.blockchain.sync.snap.actors

import org.apache.pekko.actor.testkit.typed.scaladsl.FishingOutcomes
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.util.ByteString

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*

import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

import com.chipprbots.ethereum.blockchain.sync.snap.*
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.TrieRoot
import com.chipprbots.ethereum.mpt.*
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.p2p.messages.SNAP
import com.chipprbots.ethereum.testing.PeerTestHelpers
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.testing.TestMptStorage

/** Devnet-8 recovery round (2026-10-03): storage range sync was force-completed, the heal walk found and healed missing
  * nodes, and the coordinator then declared the trie clean ("verified the entire account+storage trie"). Block 317844
  * then failed with missing trie nodes.
  *
  * The verification walk found the missing nodes while it ran, and the heal finished them BEFORE the walk ended, so at
  * its end the coordinator was idle (`isComplete`) and took that as a clean pass. But the walk never descends below a
  * node that was missing when it reached it, and inline discovery of a healed node only checks its direct children for
  * presence; a present child whose own subtree has a hole (what a force-completed storage download leaves) was never
  * verified. A pass that found any missing node must be followed by another pass.
  *
  * Fixture (hash scheme): account root R = branch { [0] -> account leaf X (storageRoot S, S absent), [1] -> chain1 ->
  * chain2 -> chain3 }. S = branch { [0] -> P } with P present and P = branch { [0] -> Q } with Q absent. The walk is
  * held (latch) on its read of chain3, i.e. after it emitted S as missing, so S can be healed while it is in flight.
  */
class ForceCompletedStorageVerificationSpec
    extends ScalaTestWithActorTestKit()
    with AnyFlatSpecLike
    with Matchers
    with Eventually:

  implicit private val classicSystem: org.apache.pekko.actor.ActorSystem = system.classicSystem
  implicit private val actorTestKit: org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit = testKit

  /** Blocks the first read of `holdOn` until `release` is counted down; signals `reached` when it gets there. */
  final private class HoldingStorage(holdOn: ByteString) extends TestMptStorage:
    val reached = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    @volatile private var held = false
    override def multiGetNodes(hashes: Seq[Array[Byte]]): Seq[Option[MptNode]] =
      if !held && hashes.exists(h => ByteString(h) == holdOn) then
        held = true
        reached.countDown()
        release.await(30, TimeUnit.SECONDS)
      super.multiGetNodes(hashes)

  private def branch(slots: (Int, MptNode)*): BranchNode =
    val children = Array.fill[MptNode](16)(NullNode)
    slots.foreach { case (i, c) => children(i) = c }
    BranchNode(children, None)

  private def accountLeaf(keyNibbles: Int, storageRoot: ByteString): LeafNode =
    LeafNode(
      ByteString(Array.fill[Byte](keyNibbles)(7.toByte)),
      ByteString(Account(storageRoot = TrieRoot(storageRoot)).toBytes)
    )

  private def stats(coordinator: ActorRef[TrieNodeHealingCoordinator.Command]): HealingStatistics =
    val probe = testKit.createTestProbe[HealingStatistics]()
    coordinator ! TrieNodeHealingCoordinator.HealingGetProgress(probe.ref)
    probe.expectMessageType[HealingStatistics]

  /** Everything a test needs once the walk is held with S healed. `q` is the leaf the dirty pass will find missing. */
  final private class Fixture(
      val storage: HoldingStorage,
      val coordinator: ActorRef[TrieNodeHealingCoordinator.Command],
      val npm: org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe[NetworkPeerManagerActor.Command],
      val controller: org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe[SNAPSyncController.Command],
      val localOnly: java.util.concurrent.atomic.AtomicBoolean,
      val q: LeafNode,
      val respond: (ActorRef[TrieNodeHealingCoordinator.Command], MptNode) => Unit
  )

  private def withFixture(body: Fixture => Unit): Unit =
    // Storage side: S -> P -> Q, with Q the hole beneath a present node.
    val q = LeafNode(ByteString(Array.fill[Byte](63)(5.toByte)), ByteString(Array.fill[Byte](40)(9.toByte)))
    val p = branch(0 -> HashNode(q.hash))
    val s = branch(0 -> HashNode(p.hash))
    // Account side. X sits at root slot 0 (63 more key nibbles -> a 64-nibble path, so its storage root is followed).
    val x = accountLeaf(63, ByteString(s.hash))
    val chain3 = accountLeaf(63, Account.EmptyStorageRootHash.value)
    val chain2 = branch(0 -> HashNode(chain3.hash))
    val chain1 = branch(0 -> HashNode(chain2.hash))
    val root = branch(0 -> HashNode(x.hash), 1 -> HashNode(chain1.hash))
    val startRoot = accountLeaf(64, Account.EmptyStorageRootHash.value)

    val storage = new HoldingStorage(holdOn = ByteString(chain3.hash))
    Seq[MptNode](x, chain3, chain2, chain1, root, p, startRoot).foreach(storage.putNode) // S and Q are absent

    val pool = Executors.newSingleThreadExecutor()
    val ec = ExecutionContext.fromExecutorService(pool)
    val npm = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val controller = testKit.createTestProbe[SNAPSyncController.Command]()
    val localOnly = new java.util.concurrent.atomic.AtomicBoolean(false)
    val coordinator = HealingTrieFixtures.spawnCoordinator(
      stateRoot = ByteString(startRoot.hash),
      networkPeerManager = npm.ref,
      requestTracker = new SNAPRequestTracker()(classicSystem.scheduler),
      mptStorage = storage,
      batchSize = 16,
      snapSyncController = controller.ref,
      healingWriterEcOverride = Some(ec),
      healingReaderEcOverride = Some(ec),
      walkLocalOnly = Some(localOnly)
    )
    val respond: (ActorRef[TrieNodeHealingCoordinator.Command], MptNode) => Unit = (c, node) =>
      val request = npm
        .expectMessageType[NetworkPeerManagerActor.SendMessageCmd](10.seconds)
        .message
        .underlyingMsg
        .asInstanceOf[SNAP.GetTrieNodes]
      c ! TrieNodeHealingCoordinator.TrieNodesResponseMsg(
        SNAP.TrieNodes(requestId = request.requestId, nodes = Seq(ByteString(node.encode)))
      )
    try
      // Re-peg to the root (present): launches the verification walk, which emits S as missing and is then held.
      coordinator ! TrieNodeHealingCoordinator.HealingPivotRefreshed(ByteString(root.hash))
      storage.reached.await(10, TimeUnit.SECONDS) shouldBe true
      eventually(timeout(5.seconds), interval(20.millis))(stats(coordinator).pendingTasks shouldBe 1) // S queued
      localOnly.get() shouldBe false // heal work is pending: the walk is NOT local-only

      // Heal S while the walk is still running. P is present, so inline discovery finds nothing more to fetch.
      val peerProbe = testKit.createTestProbe[NetworkPeerManagerActor.SendMessageCmd]()
      coordinator ! TrieNodeHealingCoordinator.HealingPeerAvailable(
        PeerTestHelpers.createTestPeer("heal-peer", peerProbe.ref.toClassic)
      )
      respond(coordinator, s)
      eventually(timeout(5.seconds), interval(20.millis)) {
        val st = stats(coordinator)
        st.totalNodes should be >= 1
        st.pendingTasks + st.activeTasks shouldBe 0
      }
      body(new Fixture(storage, coordinator, npm, controller, localOnly, q, respond))
    finally
      storage.release.countDown()
      testKit.stop(coordinator)
      pool.shutdown()
      pool.awaitTermination(5, TimeUnit.SECONDS)

  private def completionSeen(
      controller: org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe[SNAPSyncController.Command],
      window: FiniteDuration
  ): Boolean =
    try
      controller.fishForMessage(window) {
        case SNAPSyncController.StateHealingComplete => FishingOutcomes.complete
        case _                                       => FishingOutcomes.continueAndIgnore
      }
      true
    catch case _: AssertionError => false

  "TrieNodeHealingCoordinator" should
    "not report clean after a verification pass that found missing nodes, even once they are all healed" taggedAs UnitTest in {
      withFixture { f =>
        // The walk now ends. It found S missing, so it is not a clean pass; the next pass must find Q.
        f.storage.release.countDown()
        // Q is queued and, with the peer available, immediately requested: it is either pending or in flight.
        eventually(timeout(10.seconds), interval(20.millis)) {
          val st = stats(f.coordinator)
          st.pendingTasks + st.activeTasks shouldBe 1
        }
        withClue("StateHealingComplete declared while the storage trie still has a hole under a present node: ") {
          completionSeen(f.controller, 1.second) shouldBe false
        }
      }
    }

  it should "declare exactly one completion once the dirty pass is healed and a later pass is clean" taggedAs UnitTest in {
    withFixture { f =>
      f.storage.release.countDown()
      // Heal Q, the hole the second pass found. The pass after that finds nothing and is the clean one.
      f.respond(f.coordinator, f.q)
      withClue("no StateHealingComplete after the clean pass: ") {
        completionSeen(f.controller, 10.seconds) shouldBe true
      }
      withClue("StateHealingComplete must be declared exactly once: ") {
        completionSeen(f.controller, 1500.millis) shouldBe false
      }
    }
  }

  it should "report a purely local walk (nothing pending) so the controller can hold off a proactive re-peg" taggedAs UnitTest in {
    withFixture { f =>
      // Walk still held, S healed, nothing pending or in flight: the heal needs no peers.
      eventually(timeout(5.seconds), interval(20.millis))(f.localOnly.get() shouldBe true)
      f.storage.release.countDown()
      // The walk ends dirty and Q is queued: heal work is pending again, so the re-peg rules apply as before.
      eventually(timeout(10.seconds), interval(20.millis)) {
        val st = stats(f.coordinator)
        st.pendingTasks + st.activeTasks shouldBe 1
        f.localOnly.get() shouldBe false
      }
    }
  }

  it should "clear the local-walk flag when it stops mid-walk, so a dead coordinator cannot hold off re-pegs" taggedAs UnitTest in {
    withFixture { f =>
      eventually(timeout(5.seconds), interval(20.millis))(f.localOnly.get() shouldBe true)
      // Force-complete stops the coordinator while the walk is still held.
      f.coordinator ! TrieNodeHealingCoordinator.HealingForceComplete
      eventually(timeout(5.seconds), interval(20.millis))(f.localOnly.get() shouldBe false)
    }
  }

  "SNAPSyncController" should "suppress the proactive heal re-peg only for a local-only walk under movingRootDeltaHeal" taggedAs UnitTest in {
    SNAPSyncController.healRepegSuppressedByLocalWalk(movingRootDeltaHeal = true, walkLocalOnly = true) shouldBe true
    // heal work pending: the normal re-peg rules (#59) apply
    SNAPSyncController.healRepegSuppressedByLocalWalk(movingRootDeltaHeal = true, walkLocalOnly = false) shouldBe false
    // the serve-root-only path never invalidates the walk, so it is never suppressed here
    SNAPSyncController.healRepegSuppressedByLocalWalk(movingRootDeltaHeal = false, walkLocalOnly = true) shouldBe false
    SNAPSyncController.healRepegSuppressedByLocalWalk(movingRootDeltaHeal = false, walkLocalOnly = false) shouldBe false
  }
