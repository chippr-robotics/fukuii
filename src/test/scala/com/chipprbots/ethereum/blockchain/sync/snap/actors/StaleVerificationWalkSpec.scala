package com.chipprbots.ethereum.blockchain.sync.snap.actors

import org.apache.pekko.actor.testkit.typed.scaladsl.FishingOutcomes
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.util.ByteString

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*

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
class StaleVerificationWalkSpec extends ScalaTestWithActorTestKit() with AnyFlatSpecLike with Matchers:

  implicit private val classicSystem: org.apache.pekko.actor.ActorSystem = system.classicSystem
  implicit private val actorTestKit: org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit = testKit

  /** Runs submitted tasks only when told to; the walk Future therefore finishes exactly when the test says so. */
  final private class ManualExecutor extends ExecutionContext:
    private val queue = new java.util.concurrent.ConcurrentLinkedQueue[Runnable]()
    override def execute(r: Runnable): Unit = queue.add(r)
    override def reportFailure(t: Throwable): Unit = throw t
    def pending: Int = queue.size()

    /** Runs exactly the tasks queued when called. A task's completion message makes the actor launch the next walk on
      * this executor from its own thread while this loop is still running; draining "until empty" would pick that
      * follow-up walk up and run it in the same call, nondeterministically skipping the interleaving under test. The
      * follow-up stays queued for the next explicit `runPending()`.
      */
    def runPending(): Unit =
      var remaining = queue.size()
      while remaining > 0 do
        val next = queue.poll()
        if next != null then next.run()
        remaining -= 1

  private def pendingTasks(coordinator: ActorRef[TrieNodeHealingCoordinator.Command]): Int =
    val probe = testKit.createTestProbe[HealingStatistics]()
    coordinator ! TrieNodeHealingCoordinator.HealingGetProgress(probe.ref)
    probe.expectMessageType[HealingStatistics].pendingTasks

  /** Deterministic replacement for a wall-clock `eventually` on "a verification walk is queued on the manual executor".
    * The coordinator launches a walk either synchronously in the handler, or one self-sent message later (the
    * `HealingCheckCompletion` gate). Each `pendingTasks` call is a full mailbox round trip, so every message already
    * enqueued (including self-sends made while handling earlier ones) has been processed when it returns. We therefore
    * barrier until the walk appears, bounded by a message-hop count, never by elapsed time.
    */
  private def awaitQueuedWalk(
      coordinator: ActorRef[TrieNodeHealingCoordinator.Command],
      executor: ManualExecutor
  ): Unit =
    var hops = 0
    while executor.pending == 0 && hops < MaxMailboxHops do
      pendingTasks(coordinator)
      hops += 1
    executor.pending shouldBe 1

  private val MaxMailboxHops = 10

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
        awaitQueuedWalk(coordinator, executor)
        // Re-peg again, to B (present), while the A2 walk is still in flight: verification of B is deferred.
        coordinator ! TrieNodeHealingCoordinator.HealingPivotRefreshed(ByteString(rootB.hash))
        pendingTasks(coordinator) shouldBe 0 // mailbox barrier: the refresh to B has been processed
        // The A2 walk now completes clean. It must be discarded and B walked instead.
        executor.runPending()
        awaitQueuedWalk(coordinator, executor) // the stale completion was processed and the fresh walk of B queued
        executor.runPending() // the fresh walk of B
        pendingTasks(coordinator) shouldBe 1 // B's absent storage root is queued for healing (mailbox barrier)
        controller.expectNoMessage(1.second) // and no StateHealingComplete was declared
      finally testKit.stop(coordinator)
    }

  it should "hand off to lazy healing, never declare clean, once re-pegs keep superseding the verification walk" taggedAs UnitTest in {
    val storage = new TestMptStorage()
    val executor = new ManualExecutor
    val roots = (1 to 6).map(accountLeaf(_, Account.EmptyStorageRootHash.value))
    roots.foreach(storage.putNode)
    val controller = testKit.createTestProbe[SNAPSyncController.Command]()
    val coordinator = HealingTrieFixtures.spawnCoordinator(
      stateRoot = ByteString(roots.head.hash),
      networkPeerManager =
        testKit.createTestProbe[com.chipprbots.ethereum.network.NetworkPeerManagerActor.Command]().ref,
      requestTracker = new SNAPRequestTracker()(classicSystem.scheduler),
      mptStorage = storage,
      batchSize = 16,
      snapSyncController = controller.ref,
      healingWriterEcOverride = Some(executor)
    )
    try
      // First walk, on roots(1). Every later walk is the re-verification of the previous discard.
      coordinator ! TrieNodeHealingCoordinator.HealingPivotRefreshed(ByteString(roots(1).hash))
      awaitQueuedWalk(coordinator, executor)
      // Each re-peg lands while a walk is in flight, so that walk's completion is stale. The first three are
      // re-verified; the fourth exceeds the cap.
      (2 to 5).foreach { i =>
        coordinator ! TrieNodeHealingCoordinator.HealingPivotRefreshed(ByteString(roots(i).hash))
        pendingTasks(coordinator) shouldBe 0 // mailbox barrier
        executor.runPending()
        if i < 5 then awaitQueuedWalk(coordinator, executor)
      }
      controller.fishForMessage(5.seconds) {
        case SNAPSyncController.StateHealingAbandoned => FishingOutcomes.complete
        case SNAPSyncController.StateHealingComplete =>
          fail("declared a verified completion on a root that was superseded under the walk")
        case _ => FishingOutcomes.continueAndIgnore
      }
    finally testKit.stop(coordinator)
  }
