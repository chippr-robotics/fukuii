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
import com.chipprbots.ethereum.crypto.kec256
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

  "TrieNodeHealingCoordinator" should
    "not report clean after a verification pass that found missing nodes, even once they are all healed" taggedAs UnitTest in {
      // Storage side: S -> P -> Q, with Q the hole beneath a present node.
      val missingQ = ByteString(kec256(ByteString("storage-slot-node-never-downloaded")))
      val p = branch(0 -> HashNode(missingQ.toArray))
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
      val coordinator = HealingTrieFixtures.spawnCoordinator(
        stateRoot = ByteString(startRoot.hash),
        networkPeerManager = npm.ref,
        requestTracker = new SNAPRequestTracker()(classicSystem.scheduler),
        mptStorage = storage,
        batchSize = 16,
        snapSyncController = controller.ref,
        healingWriterEcOverride = Some(ec),
        healingReaderEcOverride = Some(ec)
      )
      try
        // Re-peg to the root (present): launches the verification walk, which emits S as missing and is then held.
        coordinator ! TrieNodeHealingCoordinator.HealingPivotRefreshed(ByteString(root.hash))
        storage.reached.await(10, TimeUnit.SECONDS) shouldBe true
        eventually(timeout(5.seconds), interval(20.millis))(stats(coordinator).pendingTasks shouldBe 1) // S queued

        // Heal S while the walk is still running. P is present, so inline discovery finds nothing more to fetch.
        val peerProbe = testKit.createTestProbe[NetworkPeerManagerActor.SendMessageCmd]()
        coordinator ! TrieNodeHealingCoordinator.HealingPeerAvailable(
          PeerTestHelpers.createTestPeer("heal-peer", peerProbe.ref.toClassic)
        )
        val request = npm
          .expectMessageType[NetworkPeerManagerActor.SendMessageCmd](5.seconds)
          .message
          .underlyingMsg
          .asInstanceOf[SNAP.GetTrieNodes]
        coordinator ! TrieNodeHealingCoordinator.TrieNodesResponseMsg(
          SNAP.TrieNodes(requestId = request.requestId, nodes = Seq(ByteString(s.encode)))
        )
        eventually(timeout(5.seconds), interval(20.millis)) {
          val st = stats(coordinator)
          st.totalNodes should be >= 1
          st.pendingTasks + st.activeTasks shouldBe 0
        }

        // The walk now ends. It found S missing, so it is not a clean pass; the next pass must find Q.
        storage.release.countDown()
        // Q is queued and, with the peer available, immediately requested: it is either pending or in flight.
        eventually(timeout(10.seconds), interval(20.millis)) {
          val st = stats(coordinator)
          st.pendingTasks + st.activeTasks shouldBe 1
        }
        val completed =
          try
            controller.fishForMessage(1.second) {
              case SNAPSyncController.StateHealingComplete => FishingOutcomes.complete
              case _                                       => FishingOutcomes.continueAndIgnore
            }
            true
          catch case _: AssertionError => false
        withClue("StateHealingComplete declared while the storage trie still has a hole under a present node: ") {
          completed shouldBe false
        }
      finally
        storage.release.countDown()
        testKit.stop(coordinator)
        pool.shutdown()
        pool.awaitTermination(5, TimeUnit.SECONDS)
    }
