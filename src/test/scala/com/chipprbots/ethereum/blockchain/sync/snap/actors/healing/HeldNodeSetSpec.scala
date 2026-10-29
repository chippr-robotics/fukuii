package com.chipprbots.ethereum.blockchain.sync.snap.actors.healing

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.FishingOutcomes
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.util.ByteString

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap.SNAPRequestTracker
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController
import com.chipprbots.ethereum.blockchain.sync.snap.StorageScheme
import com.chipprbots.ethereum.blockchain.sync.snap.actors.HealingTrieFixtures
import com.chipprbots.ethereum.blockchain.sync.snap.actors.PathHealPresenceFixtures
import com.chipprbots.ethereum.blockchain.sync.snap.actors.PathHealPresenceFixtures.Placed
import com.chipprbots.ethereum.blockchain.sync.snap.actors.PathHealPresenceFixtures.Trie
import com.chipprbots.ethereum.blockchain.sync.snap.actors.PathHealPresenceFixtures.readAtPath
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.network.p2p.messages.SNAP
import com.chipprbots.ethereum.testing.PeerTestHelpers
import com.chipprbots.ethereum.testing.Tags.UnitTest
import com.chipprbots.ethereum.testing.TestMptStorage

/** Spec 022 slice 2 (FR-001 to FR-004, plan D1 and D3): a healed node reaches disk only after its whole subtree is
  * present.
  *
  * T017. The bug: a served node was written (Path) or buffered for the next flush (Hash) as soon as it arrived, and its
  * children were requested after. A re-peg, a force-complete or a crash in between left a node on disk whose subtree
  * was not, so "present" stopped meaning "complete" and only a full-trie walk could find the hole. Method: a real
  * coordinator heals `PathHealPresenceFixtures.accountTrieOld` against a peer the test answers level by level. While a
  * node waits on a child, it must not be on disk, before or after a re-peg, and the coordinator must not report
  * completion; once the new root's nodes arrive, every one of them is stored.
  */
class HeldNodeSetSpec extends ScalaTestWithActorTestKit() with AnyFlatSpecLike with Matchers:

  import HealRequestFixtures.ScriptedPeer

  implicit private val classicSystem: org.apache.pekko.actor.ActorSystem = system.classicSystem
  implicit private val actorTestKit: ActorTestKit = testKit

  /** Where a heal stores nodes, under one scheme: whether a node of a trie is on disk. */
  private trait Disk:
    def holds(n: Placed): Boolean

  private def pathDisk(pns: PathNodeStorage): Disk = (n: Placed) =>
    readAtPath(pns, n).map(ByteString(_)).contains(n.encoded)

  private def hashDisk(mpt: TestMptStorage): Disk = (n: Placed) => scala.util.Try(mpt.get(n.hash.toArray)).isSuccess

  private def withCoordinator[A](scheme: StorageScheme, root: ByteString, peer: ScriptedPeer)(
      body: (ActorRef[TrieNodeHealingCoordinator.Command], TestProbe[SNAPSyncController.Command], Disk) => A
  ): A =
    val pool = Executors.newSingleThreadExecutor()
    val controller = createTestProbe[SNAPSyncController.Command]()
    val mpt = new TestMptStorage()
    val pns = new PathNodeStorage(EphemDataSource())
    val coordinator = HealingTrieFixtures.spawnCoordinator(
      stateRoot = root,
      networkPeerManager = peer.ref,
      requestTracker = new SNAPRequestTracker()(classicSystem.scheduler),
      mptStorage = mpt,
      batchSize = 16,
      snapSyncController = controller.ref,
      healingWriterEcOverride = Some(ExecutionContext.fromExecutorService(pool)),
      storageScheme = scheme,
      pathNodeStorageOpt = Option.when(scheme == StorageScheme.Path)(pns),
      movingRootDeltaHeal = true
    )
    peer.coordinator.set(coordinator)
    val disk = if scheme == StorageScheme.Path then pathDisk(pns) else hashDisk(mpt)
    try body(coordinator, controller, disk)
    finally
      testKit.stop(coordinator)
      testKit.stop(peer.ref)
      pool.shutdown()
      pool.awaitTermination(5, TimeUnit.SECONDS)

  private def peerAvailable(coordinator: ActorRef[TrieNodeHealingCoordinator.Command]): Unit =
    coordinator ! TrieNodeHealingCoordinator.HealingPeerAvailable(
      PeerTestHelpers.createTestPeer("held-peer", createTestProbe[Any]().ref.toClassic)
    )

  /** Answer `req` with the nodes of `trie` at the paths it names whose label is in `labels`, omitting the rest. */
  private def answerOnly(peer: ScriptedPeer, req: SNAP.GetTrieNodes, trie: Trie, labels: Set[String]): Unit =
    val served = trie.nodes.filter(n => labels(n.label)).map(n => n.pathset -> n.encoded).toMap
    peer.answerFrom(req, served)

  private def heldAcrossRepeg(scheme: StorageScheme): Unit =
    val before = PathHealPresenceFixtures.accountTrieOld()
    val after = PathHealPresenceFixtures.accountTrieNew()
    val peer = new ScriptedPeer(testKit)
    withCoordinator(scheme, before.root.hash, peer) { (coordinator, controller, disk) =>
      coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(before.root.hash)
      peerAvailable(coordinator)

      // The root arrives: both its children are missing.
      peer.answerFrom(peer.manual.expectMessageType[SNAP.GetTrieNodes], before.served)
      // ext and brY arrive: brX (below ext), leafY0 and leafY1 (below brY) are missing.
      val level1 = peer.manual.expectMessageType[SNAP.GetTrieNodes]
      level1.paths.toSet shouldBe Set(before("ext").pathset, before("brY").pathset)
      peer.answerFrom(level1, before.served)
      // brY's leaves arrive, brX does not: the peer omits it, and it is requested again (and never answered).
      val level2 = peer.manual.expectMessageType[SNAP.GetTrieNodes]
      level2.paths.toSet shouldBe Set(before("brX").pathset, before("leafY0").pathset, before("leafY1").pathset)
      answerOnly(peer, level2, before, Set("leafY0", "leafY1"))
      peer.manual.expectMessageType[SNAP.GetTrieNodes].paths shouldBe Seq(before("brX").pathset)

      // root and ext wait on brX: neither may be on disk, and healing is not complete.
      withClue("root before the re-peg: ")(disk.holds(before("root")) shouldBe false)
      withClue("ext before the re-peg: ")(disk.holds(before("ext")) shouldBe false)
      coordinator ! TrieNodeHealingCoordinator.HealingCheckCompletion
      controller.expectNoMessage(300.millis)

      // The pivot moves. What was held is dropped, never written.
      peer.serveFrom.set(Some(after.served))
      coordinator ! TrieNodeHealingCoordinator.HealingPivotRefreshed(after.root.hash)
      withClue("old root after the re-peg: ")(disk.holds(before("root")) shouldBe false)
      withClue("old ext after the re-peg: ")(disk.holds(before("ext")) shouldBe false)

      // The new root's nodes arrive: healing completes with all of them stored.
      peerAvailable(coordinator)
      controller.fishForMessage(30.seconds) {
        case SNAPSyncController.StateHealingComplete => FishingOutcomes.complete
        case _                                       => FishingOutcomes.continueAndIgnore
      }
      after.nodes.foreach(n => withClue(s"${n.label} after healing: ")(disk.holds(n) shouldBe true))
    }

  "Healing" should "keep a node off disk while it waits on a child, across a re-peg, under the Path scheme (T017)" taggedAs UnitTest in {
    heldAcrossRepeg(StorageScheme.Path)
  }

  it should "keep a node off disk while it waits on a child, across a re-peg, under the Hash scheme (T017)" taggedAs UnitTest in {
    heldAcrossRepeg(StorageScheme.Hash)
  }
