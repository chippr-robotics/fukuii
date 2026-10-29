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
import com.chipprbots.ethereum.blockchain.sync.snap.actors.PathHealPresenceFixtures.Trie
import com.chipprbots.ethereum.blockchain.sync.snap.actors.PathHealPresenceFixtures.readAtPath
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.network.p2p.messages.SNAP
import com.chipprbots.ethereum.testing.PeerTestHelpers
import com.chipprbots.ethereum.testing.Tags.UnitTest
import com.chipprbots.ethereum.testing.TestMptStorage

/** Spec 022 slice 1, T006 and T007 (FR-007, Path scheme): when one node is needed at more than one position, every
  * position is recorded, filled when the node arrives, and re-discovered after a pivot move.
  *
  * The bug: inline child discovery de-duplicated by hash. The account-leaf storage-root seed skipped a storage root
  * whose hash was already pending (`HealResponseProcessing`, the `pendingHashSet` check on `account.storageRoot`), so
  * the second of two accounts with an identical storage root lost its whole storage trie to healing. Only a later
  * full-trie verification walk found it, and fetched the same nodes again. Aliases recorded before a re-peg were
  * dropped with it (`pathAliases.clear()`).
  *
  * Method: a real coordinator over a `PathNodeStorage` heals the twin-storage trie (two accounts, one storage trie of
  * three nodes at six positions) against a fake peer. The oracle is that every node hash is fetched exactly once and
  * that all positions end up holding their node. On the code before the fix the second account's storage trie is
  * fetched a second time, after the verification walk finds it missing.
  */
class HealAliasSpec extends ScalaTestWithActorTestKit() with AnyFlatSpecLike with Matchers:

  implicit private val classicSystem: org.apache.pekko.actor.ActorSystem = system.classicSystem
  implicit private val actorTestKit: ActorTestKit = testKit

  import HealRequestFixtures.*

  private def awaitHealingComplete(controller: TestProbe[SNAPSyncController.Command]): Unit =
    controller.fishForMessage(60.seconds) {
      case SNAPSyncController.StateHealingComplete => FishingOutcomes.complete
      case _                                       => FishingOutcomes.continueAndIgnore
    }

  /** A Path-scheme coordinator healing towards `root` from an empty-or-seeded `pns`, the moving-root seed on (an absent
    * root is fetched, not handed off), wired to `peer`. Runs `body` and stops everything afterwards.
    */
  private def withPathCoordinator[A](root: ByteString, pns: PathNodeStorage, peer: ScriptedPeer)(
      body: (ActorRef[TrieNodeHealingCoordinator.Command], TestProbe[SNAPSyncController.Command]) => A
  ): A =
    val pool = Executors.newSingleThreadExecutor()
    val controller = createTestProbe[SNAPSyncController.Command]()
    val coordinator = HealingTrieFixtures.spawnCoordinator(
      stateRoot = root,
      networkPeerManager = peer.ref,
      requestTracker = new SNAPRequestTracker()(classicSystem.scheduler),
      mptStorage = new TestMptStorage(),
      batchSize = 16,
      snapSyncController = controller.ref,
      healingWriterEcOverride = Some(ExecutionContext.fromExecutorService(pool)),
      storageScheme = StorageScheme.Path,
      pathNodeStorageOpt = Some(pns),
      movingRootDeltaHeal = true
    )
    peer.coordinator.set(coordinator)
    try body(coordinator, controller)
    finally
      testKit.stop(coordinator)
      testKit.stop(peer.ref)
      pool.shutdown()
      pool.awaitTermination(5, TimeUnit.SECONDS)

  private def peerAvailable(coordinator: ActorRef[TrieNodeHealingCoordinator.Command], name: String): Unit =
    coordinator ! TrieNodeHealingCoordinator.HealingPeerAvailable(
      PeerTestHelpers.createTestPeer(name, createTestProbe[Any]().ref.toClassic)
    )

  private def assertAllPlaced(pns: PathNodeStorage, trie: Trie): Unit =
    trie.nodes.foreach { n =>
      withClue(s"${n.label}: ") {
        readAtPath(pns, n).map(ByteString(_)) shouldBe Some(n.encoded)
      }
    }

  "Path-scheme healing" should "heal the whole storage trie of both accounts that share a storage root, fetching each node once (T006)" taggedAs UnitTest in {
    val trie = twinStorageTrie(nonce = 1)
    trie("s0").hash shouldBe trie("s1").hash // one storage root …
    trie("s0").pathset should not be trie("s1").pathset // … at two positions

    val pns = new PathNodeStorage(EphemDataSource())
    val peer = new ScriptedPeer(testKit)
    peer.serveFrom.set(Some(trie.served))
    withPathCoordinator(trie.root.hash, pns, peer) { (coordinator, controller) =>
      // Nothing is stored: the root is seeded and fetched, and every node below it comes from inline discovery.
      coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(trie.root.hash)
      peerAvailable(coordinator, "t006-peer")
      awaitHealingComplete(controller)
    }

    assertAllPlaced(pns, trie)
    // Six node hashes (root, two account leaves, S, slotA, slotB), nine positions: every hash fetched exactly once. The
    // storage nodes are written at both accounts' positions when they arrive.
    fetchedMoreThanOnce(trie, peer.requests) shouldBe empty
    requestedHashes(trie, peer.requests).toSet shouldBe trie.nodes.map(_.hash).toSet
  }

  it should "re-discover from the new root an alias pending at a re-peg, and fill both positions (T007)" taggedAs UnitTest in {
    // Two builds of the twin trie: the account leaves (hence the root) differ, the storage trie is the same.
    val before = twinStorageTrie(nonce = 1)
    val after = twinStorageTrie(nonce = 2)
    before.root.hash should not be after.root.hash
    after("s0").hash shouldBe before("s0").hash

    val pns = new PathNodeStorage(EphemDataSource())
    val peer = new ScriptedPeer(testKit)
    withPathCoordinator(before.root.hash, pns, peer) { (coordinator, controller) =>
      coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(before.root.hash)
      peerAvailable(coordinator, "t007-peer")

      // Root, then both account leaves: discovery now needs S at (A0, []) and at (A1, []).
      peer.answerFrom(peer.manual.expectMessageType[SNAP.GetTrieNodes], before.served)
      val accounts = peer.manual.expectMessageType[SNAP.GetTrieNodes]
      accounts.paths.toSet shouldBe Set(before("acct0").pathset, before("acct1").pathset)
      peer.answerFrom(accounts, before.served)
      // The storage-root request goes out and is never answered: S is pending at both positions when the pivot moves.
      val pendingStorage = peer.manual.expectMessageType[SNAP.GetTrieNodes]
      pendingStorage.paths.toSet.subsetOf(Set(before("s0").pathset, before("s1").pathset)) shouldBe true

      // The re-peg: from here on the peer serves the new trie.
      peer.serveFrom.set(Some(after.served))
      coordinator ! TrieNodeHealingCoordinator.HealingPivotRefreshed(after.root.hash)
      // The re-peg queues the new root but does not dispatch; in production the controller's 1 s HealingPeerAvailable
      // tick does.
      peerAvailable(coordinator, "t007-peer")
      awaitHealingComplete(controller)
    }

    assertAllPlaced(pns, after)
    // After the re-peg every node hash is fetched at most once, S and its two slots exactly once.
    fetchedMoreThanOnce(after, peer.autoRequests) shouldBe empty
    val fetched = requestedHashes(after, peer.autoRequests)
    Seq("s0", "s0A", "s0B").foreach(label => fetched.count(_ == after(label).hash) shouldBe 1)
  }
