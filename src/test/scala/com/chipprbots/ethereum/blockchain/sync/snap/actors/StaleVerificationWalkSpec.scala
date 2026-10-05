package com.chipprbots.ethereum.blockchain.sync.snap.actors

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.util.ByteString

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*

import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap.*
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.TrieRoot
import com.chipprbots.ethereum.mpt.LeafNode
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.testing.TestMptStorage

/** SNAP heal gap on per-block-hot system-contract storage tries (EIP-4788 / 2935 / 8282, Platåberget devnet-8, pivot
  * 317843 failing at 317844).
  *
  * The heal root re-pegs while a verification walk is in flight. That walk is not cancelled, so its completion can
  * arrive after `stateRoot` moved. The completion used to set `verificationPassComplete` unconditionally, i.e. a clean
  * walk of the OLD pivot root was taken as proof that the NEW pivot root is complete. The cold bulk of the state is
  * shared between roots, so the old walk is clean; only tries rewritten every block (the system contracts) differ, and
  * those were never walked for the final root.
  *
  * The scenario here: root A2 (clean) is being verified, the pivot moves to root B whose only account has a storage
  * trie that is absent locally, and then the A2 walk completes. B's missing storage root must be discovered and no
  * completion declared. The walk executor is manual so the interleaving is deterministic (no sleeps).
  */
class StaleVerificationWalkSpec extends ScalaTestWithActorTestKit() with AnyFlatSpecLike with Matchers with Eventually:

  implicit private val classicSystem: org.apache.pekko.actor.ActorSystem = system.classicSystem
  implicit private val actorTestKit: org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit = testKit

  /** Runs submitted tasks only when told to; the walk Future therefore finishes exactly when the test says so. */
  final private class ManualExecutor extends ExecutionContext:
    private val queue = new java.util.concurrent.ConcurrentLinkedQueue[Runnable]()
    override def execute(r: Runnable): Unit = queue.add(r)
    override def reportFailure(t: Throwable): Unit = throw t
    def pending: Int = queue.size()
    def runPending(): Unit =
      var next = queue.poll()
      while next != null do
        next.run()
        next = queue.poll()

  private def pendingTasks(coordinator: ActorRef[TrieNodeHealingCoordinator.Command]): Int =
    val probe = testKit.createTestProbe[HealingStatistics]()
    coordinator ! TrieNodeHealingCoordinator.HealingGetProgress(probe.ref)
    probe.expectMessageType[HealingStatistics].pendingTasks

  private def accountLeaf(seed: Int, storageRoot: ByteString): LeafNode =
    LeafNode(
      ByteString(Array.fill[Byte](64)((seed % 16).toByte)),
      ByteString(Account(storageRoot = TrieRoot(storageRoot)).toBytes)
    )

  "TrieNodeHealingCoordinator" should
    "not credit a verification walk of a superseded root as verification of the re-pegged root" taggedAs UnitTest in {
      val storage = new TestMptStorage()
      val executor = new ManualExecutor
      val rootA = accountLeaf(1, Account.EmptyStorageRootHash.value)
      val rootA2 = accountLeaf(2, Account.EmptyStorageRootHash.value)
      // Root B: one contract whose storage trie root (the hot system-contract trie) is NOT held locally.
      val absentStorageRoot = kec256(ByteString("hot-system-contract-storage-at-pivot"))
      val rootB = accountLeaf(3, absentStorageRoot)
      Seq(rootA, rootA2, rootB).foreach(storage.putNode)

      val controller = testKit.createTestProbe[SNAPSyncController.Command]()
      val coordinator = HealingTrieFixtures.spawnCoordinator(
        stateRoot = ByteString(rootA.hash),
        networkPeerManager =
          testKit.createTestProbe[com.chipprbots.ethereum.network.NetworkPeerManagerActor.Command]().ref,
        requestTracker = new SNAPRequestTracker()(classicSystem.scheduler),
        mptStorage = storage,
        batchSize = 16,
        snapSyncController = controller.ref,
        healingWriterEcOverride = Some(executor)
      )
      try
        // Re-peg to A2 (present): launches a verification walk of A2, held on the manual executor.
        coordinator ! TrieNodeHealingCoordinator.HealingPivotRefreshed(ByteString(rootA2.hash))
        eventually(timeout(5.seconds), interval(20.millis))(executor.pending shouldBe 1)
        // Re-peg again, to B (present), while the A2 walk is still in flight: verification of B is deferred.
        coordinator ! TrieNodeHealingCoordinator.HealingPivotRefreshed(ByteString(rootB.hash))
        pendingTasks(coordinator) shouldBe 0 // mailbox barrier: the refresh to B has been processed
        // The A2 walk now completes clean. It must be discarded and B walked instead.
        executor.runPending()
        eventually(timeout(5.seconds), interval(20.millis)) {
          executor.runPending() // the fresh walk of B
          pendingTasks(coordinator) shouldBe 1 // B's absent storage root is queued for healing
        }
        controller.expectNoMessage(1.second) // and no StateHealingComplete was declared
      finally testKit.stop(coordinator)
    }
