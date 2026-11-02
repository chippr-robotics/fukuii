package com.chipprbots.ethereum.blockchain.sync.snap.actors

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap.*
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.mpt.LeafNode
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.testing.TestMptStorage

/** spec 022 D6: a restart re-seeds from the current root. Since slice 6c that holds by construction: the coordinator's
  * spawn takes no handle to CF `g`, so what an older binary left there (a persisted frontier, its completeness marker,
  * subtree-complete records) cannot reach it, and only the one-time clear (`HealInvariant.retireFrontierOnce`) touches
  * the family. What is left to check is the behaviour: a restart over a present root walks from it, and a re-peg seeds
  * the new root.
  */
class HealingFrontierResumeSpec extends ScalaTestWithActorTestKit() with AnyFlatSpecLike with Matchers:

  implicit private val classicSystem: org.apache.pekko.actor.ActorSystem = system.classicSystem
  implicit private val actorTestKit: org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit = testKit

  /** A trivially-present root node so `StartTrieNodeHealing` takes the restart branch. A childless leaf ⇒ the walk
    * discovers nothing ⇒ pendingTasks stays 0.
    */
  private def storedRoot(storage: TestMptStorage): ByteString =
    val leaf = LeafNode(ByteString(1), ByteString(1))
    storage.putNode(leaf)
    ByteString(leaf.hash)

  private def pendingTasks(coordinator: ActorRef[TrieNodeHealingCoordinator.Command]): Int =
    // Dedicated probe per query: the shared ImplicitSender inbox steals replies across tests when
    // the suite runs with test parallelism — one test's awaitAssert can consume another test's
    // HealingStatistics (observed as a deterministic-looking "0 was not equal to 7").
    val probe = testKit.createTestProbe[HealingStatistics]()
    coordinator ! TrieNodeHealingCoordinator.HealingGetProgress(probe.ref)
    probe.expectMessageType[HealingStatistics].pendingTasks

  "A restart" should "walk from the current root, and a re-peg seed the new root" taggedAs UnitTest in {
    val controller = testKit.createTestProbe[SNAPSyncController.Command]()
    val storage = new TestMptStorage()
    val root = storedRoot(storage)
    val coordinator = HealingTrieFixtures.spawnCoordinator(
      stateRoot = root,
      networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]().ref,
      requestTracker = new SNAPRequestTracker()(classicSystem.scheduler),
      mptStorage = storage,
      batchSize = 16,
      snapSyncController = controller.ref
    )
    try
      coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(root)
      controller.expectMessage(SNAPSyncController.StateHealingComplete)
      pendingTasks(coordinator) shouldBe 0
      coordinator ! TrieNodeHealingCoordinator.HealingPivotRefreshed(kec256(ByteString("a-genuinely-different-root")))
      pendingTasks(coordinator) shouldBe 1
    finally testKit.stop(coordinator)
  }
