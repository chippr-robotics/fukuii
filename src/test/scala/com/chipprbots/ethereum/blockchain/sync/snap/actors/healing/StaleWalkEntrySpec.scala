package com.chipprbots.ethereum.blockchain.sync.snap.actors.healing

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.FishingOutcomes
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.util.ByteString

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap.SNAPRequestTracker
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController
import com.chipprbots.ethereum.blockchain.sync.snap.StorageScheme
import com.chipprbots.ethereum.blockchain.sync.snap.actors.HealingStatistics
import com.chipprbots.ethereum.blockchain.sync.snap.actors.HealingTrieFixtures
import com.chipprbots.ethereum.blockchain.sync.snap.actors.PathHealPresenceFixtures.*
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator.FrontierRebuilt
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator.HealingEntry
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.testing.PeerTestHelpers
import com.chipprbots.ethereum.testing.Tags.UnitTest
import com.chipprbots.ethereum.testing.TestMptStorage

/** Spec 022 slice 1 review, beacon W1 (Path scheme): a frontier walk of a superseded root must not shadow the current
  * root's request for the same position.
  *
  * A re-peg does not cancel a running frontier walk, and the walk keeps emitting `FrontierRebuilt` batches for the root
  * it was launched on. Under the Path scheme a request is keyed by position alone, so a stale entry `(P, H_old)`
  * recorded after the re-peg made the current root's discovery of `(P, H_new)` join it: `H_new` was never fetched, and
  * the fetch of `H_old` at P could never succeed against the new root.
  *
  * Method: a real Path-scheme coordinator over a store holding the whole old trie re-pegs to the new one (they differ
  * along leafX1's spine), then receives a walk batch of the OLD root naming leafX1's position with its OLD hash. The
  * batch must be dropped (only the new root is queued), and leafX1's position must be fetched once, for its new node.
  */
class StaleWalkEntrySpec extends ScalaTestWithActorTestKit() with AnyFlatSpecLike with Matchers:

  implicit private val classicSystem: org.apache.pekko.actor.ActorSystem = system.classicSystem
  implicit private val actorTestKit: ActorTestKit = testKit

  import HealRequestFixtures.ScriptedPeer

  "Path-scheme healing" should "drop a walk batch of a superseded root, so the current root's request for its position is the one fetched (W1)" taggedAs UnitTest in {
    val oldTrie = accountTrieOld()
    val newTrie = accountTrieNew()
    val stale = oldTrie("leafX1")
    stale.pathset shouldBe newTrie("leafX1").pathset
    stale.hash should not be newTrie("leafX1").hash

    val pns = new PathNodeStorage(EphemDataSource())
    seedPath(pns, oldTrie.nodes) // the old root is complete locally
    val peer = new ScriptedPeer(testKit)
    val pool = Executors.newSingleThreadExecutor()
    val controller = createTestProbe[SNAPSyncController.Command]()
    val coordinator = HealingTrieFixtures.spawnCoordinator(
      stateRoot = oldTrie.root.hash,
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

    def stats(): HealingStatistics =
      val probe = createTestProbe[HealingStatistics]()
      coordinator ! TrieNodeHealingCoordinator.HealingGetProgress(probe.ref)
      probe.expectMessageType[HealingStatistics]

    try
      coordinator ! TrieNodeHealingCoordinator.HealingPivotRefreshed(newTrie.root.hash) // queues the new root
      // A batch of a walk launched on the OLD root, landing after the re-peg.
      coordinator ! FrontierRebuilt(Seq(HealingEntry(stale.pathset, stale.hash)), walkRoot = oldTrie.root.hash)
      stats().pendingTasks shouldBe 1 // only the new root: the stale entry is not queued

      peer.serveFrom.set(Some(newTrie.served))
      coordinator ! TrieNodeHealingCoordinator.HealingPeerAvailable(
        PeerTestHelpers.createTestPeer("w1-peer", createTestProbe[Any]().ref.toClassic)
      )
      controller.fishForMessage(60.seconds) {
        case SNAPSyncController.StateHealingComplete => FishingOutcomes.complete
        case _                                       => FishingOutcomes.continueAndIgnore
      }
    finally
      testKit.stop(coordinator)
      testKit.stop(peer.ref)
      pool.shutdown()
      pool.awaitTermination(5, TimeUnit.SECONDS)

    peer.requests.flatten.count(_ == stale.pathset) shouldBe 1 // leafX1's position, fetched once
    readAtPath(pns, newTrie("leafX1")).map(ByteString(_)) shouldBe Some(newTrie("leafX1").encoded)
    newTrie.nodes.foreach(n => readAtPath(pns, n).map(ByteString(_)) shouldBe Some(n.encoded))
  }
