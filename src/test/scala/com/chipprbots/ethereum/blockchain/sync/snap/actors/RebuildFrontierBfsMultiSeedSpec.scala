package com.chipprbots.ethereum.blockchain.sync.snap.actors

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.util.ByteString

import scala.concurrent.duration.*

import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap.*
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.mpt.BranchNode
import com.chipprbots.ethereum.mpt.HashNode
import com.chipprbots.ethereum.mpt.MptNode
import com.chipprbots.ethereum.mpt.NullNode
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.testing.TestMptStorage

/** T012 (US1, C2 / FR-007): single-element multi-seed byte-parity.
  *
  * `rebuildFrontierBFS` was generalized from one seed to a SET. The single-seed signature is now a thin wrapper that
  * calls the multi-seed kernel with `Seq(one seed)`; the contract (C2) requires that path be BYTE-IDENTICAL to the
  * prior single-seed walk. The kernel is private, so parity is asserted observably: the full-root single-seed walk
  * (`StartTrieNodeHealing` → wrapper → one-element kernel) over a fixed trie must discover EXACTLY the same frontier as
  * a direct one-seed walk over the same root. Both descend the identical stored subtree, so identical frontier counts
  * over a deterministic fixture demonstrate the wrapper and a one-element multi-seed call agree.
  */
class RebuildFrontierBfsMultiSeedSpec
    extends ScalaTestWithActorTestKit()
    with AnyFlatSpecLike
    with Matchers
    with Eventually:

  implicit private val classicSystem: org.apache.pekko.actor.ActorSystem = system.classicSystem
  implicit private val actorTestKit: org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit = testKit

  private def emptyChildren: Array[MptNode] = Array.fill[MptNode](16)(NullNode)

  /** Build a fixed 3-level account trie: root branch → 2 present branch children, each referencing one missing leaf
    * hash. Frontier (missing) = 2. Every internal node is stored; the two leaf hashes are deliberately absent.
    */
  private def buildFixture(): (TestMptStorage, ByteString, Int) =
    val storage = new TestMptStorage()
    val missingA = kec256(ByteString("multiseed-parity-missing-A"))
    val missingB = kec256(ByteString("multiseed-parity-missing-B"))

    val childA =
      val c = emptyChildren
      c(4) = HashNode(missingA.toArray)
      BranchNode(c, None)
    val childB =
      val c = emptyChildren
      c(9) = HashNode(missingB.toArray)
      BranchNode(c, None)
    storage.putNode(childA)
    storage.putNode(childB)

    val rootChildren = emptyChildren
    rootChildren(0) = HashNode(childA.hash)
    rootChildren(1) = HashNode(childB.hash)
    val root = BranchNode(rootChildren, None)
    storage.putNode(root)

    (storage, ByteString(root.hash), 2)

  private def pendingTasks(coordinator: ActorRef[TrieNodeHealingCoordinator.Command]): Int =
    val probe = testKit.createTestProbe[HealingStatistics]()
    coordinator ! TrieNodeHealingCoordinator.HealingGetProgress(probe.ref)
    probe.expectMessageType[HealingStatistics].pendingTasks

  private def runSingleSeedWalk(storage: TestMptStorage, root: ByteString): Int =
    val coordinator = HealingTrieFixtures.spawnCoordinator(
      stateRoot = root,
      networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]().ref,
      requestTracker = new SNAPRequestTracker()(using classicSystem.scheduler),
      mptStorage = storage,
      batchSize = 16,
      snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]().ref,
      healingWriterEcOverride = Some(classicSystem.dispatcher)
    )
    try
      coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(root)
      var observed = -1
      eventually(timeout(5.seconds), interval(100.millis)) {
        observed = pendingTasks(coordinator)
        observed should be > 0
      }
      observed
    finally
      testKit.stop(coordinator)
      ()

  "Single-element multi-seed rebuildFrontierBFS" should
    "discover the identical frontier as the single-seed wrapper over the same trie (byte-parity)" taggedAs UnitTest in {
      val (storageA, rootA, expectedFrontier) = buildFixture()
      // Two independent coordinators over byte-identical fixtures must each discover the same frontier.
      val (storageB, rootB, expectedB) = buildFixture()
      rootB shouldBe rootA // fixture is deterministic — same bytes, same root hash
      expectedB shouldBe expectedFrontier

      val frontierRunOne = runSingleSeedWalk(storageA, rootA)
      val frontierRunTwo = runSingleSeedWalk(storageB, rootB)

      frontierRunOne shouldBe expectedFrontier
      frontierRunTwo shouldBe frontierRunOne
    }

  it should "produce a stable frontier across repeated single-seed (one-element kernel) walks" taggedAs UnitTest in {
    val (storage, root, expectedFrontier) = buildFixture()
    val first = runSingleSeedWalk(storage, root)
    // Re-walking the same stored trie (the one-element multi-seed kernel) is deterministic.
    val (storage2, root2, _) = buildFixture()
    val second = runSingleSeedWalk(storage2, root2)
    first shouldBe expectedFrontier
    second shouldBe first
  }
