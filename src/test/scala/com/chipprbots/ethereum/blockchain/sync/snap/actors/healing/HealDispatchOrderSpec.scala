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
import com.chipprbots.ethereum.blockchain.sync.snap.actors.HealingTrieFixtures
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator
import com.chipprbots.ethereum.blockchain.sync.snap.heal.HealRequest
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.network.p2p.messages.SNAP
import com.chipprbots.ethereum.testing.PeerTestHelpers
import com.chipprbots.ethereum.testing.Tags.UnitTest
import com.chipprbots.ethereum.testing.TestMptStorage

/** Spec 022 slice 2, T020 (plan D1): heal requests are dispatched deepest first.
  *
  * The bug: `pendingTasks` was a FIFO, so a request went out in the order it was queued. With nodes held until their
  * subtree is present (D3), FIFO keeps almost every internal node held until the bottom level drains, and the held set
  * approaches the size of the trie. geth dispatches by `len(path)`, deepest first.
  *
  * Method: a real coordinator, with no peer yet, is handed missing nodes at mixed depths, account and storage trie
  * alike, in an order that is NOT deepest first. A peer then becomes available and the coordinator dispatches all of
  * them; the test reads the paths of every `GetTrieNodes` in the order they were sent. The oracle: their depths never
  * increase. Under both storage schemes (the queue is shared; the request key differs).
  */
class HealDispatchOrderSpec extends ScalaTestWithActorTestKit() with AnyFlatSpecLike with Matchers:

  implicit private val classicSystem: org.apache.pekko.actor.ActorSystem = system.classicSystem
  implicit private val actorTestKit: ActorTestKit = testKit

  import HealRequestFixtures.*

  /** A storage-trie pathset: the 32-byte owner, then the compact storage path. */
  private def storagePathset(owner: Int, ns: Int*): Seq[ByteString] =
    Seq(kec256(ByteString(s"s022/order/owner/$owner")), compact(nibbles(ns*)))

  // Depths, in the order queued: 2, 64, 0, 5, 67, 1, 3, 65, 4. Deepest first is 67, 65, 64, 5, 4, 3, 2, 1, 0.
  private val queued: Seq[Seq[ByteString]] = Seq(
    accountPathset(1, 2),
    storagePathset(1),
    accountPathset(),
    accountPathset(3, 4, 5, 6, 7),
    storagePathset(2, 1, 2, 3),
    accountPathset(8),
    accountPathset(9, 10, 11),
    storagePathset(3, 4),
    accountPathset(12, 13, 14, 15)
  )

  private def dispatchOrder(scheme: StorageScheme): Seq[Int] =
    val peer = new ScriptedPeer(testKit)
    val pool = Executors.newSingleThreadExecutor()
    val controller = createTestProbe[SNAPSyncController.Command]()
    val coordinator = HealingTrieFixtures.spawnCoordinator(
      stateRoot = kec256(ByteString("s022/order/root")),
      networkPeerManager = peer.ref,
      requestTracker = new SNAPRequestTracker()(classicSystem.scheduler),
      mptStorage = new TestMptStorage(),
      // Two paths per request, so the order is visible across requests as well as within one.
      batchSize = 2,
      snapSyncController = controller.ref,
      healingWriterEcOverride = Some(ExecutionContext.fromExecutorService(pool)),
      storageScheme = scheme,
      pathNodeStorageOpt = Option.when(scheme == StorageScheme.Path)(new PathNodeStorage(EphemDataSource()))
    )
    peer.coordinator.set(coordinator)
    try
      // No peer is known yet, so everything stays queued.
      coordinator ! TrieNodeHealingCoordinator.QueueMissingNodes(
        queued.zipWithIndex.map { case (ps, i) => ps -> kec256(ByteString(s"s022/order/node/$i")) }
      )
      coordinator ! TrieNodeHealingCoordinator.UpdateMaxInFlightPerPeer(16)
      coordinator ! TrieNodeHealingCoordinator.HealingPeerAvailable(
        PeerTestHelpers.createTestPeer("order-peer", createTestProbe[Any]().ref.toClassic)
      )
      // Nine paths in requests of two: five requests, none answered.
      val sent = (1 to 5).map(_ => peer.manual.expectMessageType[SNAP.GetTrieNodes])
      sent.flatMap(_.paths).map(HealRequest.depthOf)
    finally
      testKit.stop(coordinator)
      testKit.stop(peer.ref)
      pool.shutdown()
      pool.awaitTermination(5, TimeUnit.SECONDS)

  "Heal dispatch" should "send the deepest pending request first, under the Path scheme (T020)" taggedAs UnitTest in {
    val depths = dispatchOrder(StorageScheme.Path)
    depths should have size queued.size.toLong
    withClue(s"dispatched depths $depths: ") {
      depths shouldBe depths.sorted(Ordering[Int].reverse)
    }
  }

  it should "send the deepest pending request first, under the Hash scheme (T020)" taggedAs UnitTest in {
    val depths = dispatchOrder(StorageScheme.Hash)
    depths should have size queued.size.toLong
    withClue(s"dispatched depths $depths: ") {
      depths shouldBe depths.sorted(Ordering[Int].reverse)
    }
  }
