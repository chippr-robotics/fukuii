package com.chipprbots.ethereum.blockchain.sync.snap.actors.healing

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.FishingOutcomes
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe
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
import com.chipprbots.ethereum.blockchain.sync.snap.actors.PathHealPresenceFixtures.readAtPath
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.dataSource.DataUpdate
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.HealingFrontierStorage
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.testing.PeerTestHelpers
import com.chipprbots.ethereum.testing.Tags.UnitTest
import com.chipprbots.ethereum.testing.TestMptStorage

/** A trie store whose next `failures` node writes throw before storing anything. */
final private class FailingWritesMptStorage(failures: Int) extends TestMptStorage:
  val remaining: AtomicInteger = new AtomicInteger(failures)
  override def storeRawNodes(rawNodes: Seq[(ByteString, Array[Byte])]): Unit =
    if remaining.getAndDecrement() > 0 then throw new RuntimeException("injected node write failure")
    super.storeRawNodes(rawNodes)

/** A data source whose next `failures` write batches throw before applying anything. */
final private class FailingWritesDataSource(failures: Int) extends EphemDataSource(Map.empty):
  val remaining: AtomicInteger = new AtomicInteger(failures)
  override def update(dataSourceUpdates: Seq[DataUpdate]): Unit =
    if remaining.getAndDecrement() > 0 then throw new RuntimeException("injected write batch failure")
    super.update(dataSourceUpdates)

/** Spec 022 slice 2, T018 (#1549 on the heal path, plan D3): a node write that throws loses nothing.
  *
  * The bug: `FrontierPersistence.flushRawNodesAsync` cleared the raw-node buffer BEFORE its Future wrote it. A throw in
  * the write therefore dropped the nodes, and since `FlushComplete` was never sent, `flushing` stayed true forever: no
  * later flush ran, and the completion gate (`!flushing`) never opened again. Under the Path scheme the node write ran
  * on the actor thread, so a throw stopped the coordinator.
  *
  * Method: the module test drives `flushRawNodesAsync` directly over a store that throws once. The coordinator tests
  * heal a small trie (`PathHealPresenceFixtures.accountTrieOld`, eight nodes, an extension included) through a real
  * coordinator whose store throws on its first write; the oracle is that healing still completes and every node ends up
  * stored.
  */
class HealFlushFailureSpec extends ScalaTestWithActorTestKit() with AnyFlatSpecLike with Matchers:

  implicit private val classicSystem: org.apache.pekko.actor.ActorSystem = system.classicSystem
  implicit private val actorTestKit: ActorTestKit = testKit

  import HealRequestFixtures.*

  private def node(i: Int): (ByteString, Array[Byte]) =
    val bytes = Array.fill[Byte](8)(i.toByte)
    kec256(ByteString(bytes)) -> bytes

  "The asynchronous healed-node flush" should "keep every buffered node and staged record when the write throws (T018)" taggedAs UnitTest in {
    val frontier = Some(new HealingFrontierStorage(EphemDataSource()))
    val s = new StubFrontierPersistenceState(
      frontier,
      frontierPersistenceEnabled = false,
      prunedEnabled = true,
      healingWriterEc = ExecutionContext.parasitic
    ) with FrontierPersistence
    val nodes = (1 to 3).map(node)
    s.rawNodeBuffer ++= nodes
    s.pendingSubtreeRecords += nodes.head._1
    s.mptStorage.failWrites = 1

    s.flushRawNodesAsync() // the Future runs inline (parasitic) and fails

    s.mptStorage.stored shouldBe empty
    // Nothing was written, so nothing may be dropped: the nodes stay buffered for the next flush and the staged spec
    // 005 record stays staged (it is written only after its node is durable).
    s.rawNodeBuffer.map(_._1).toSeq shouldBe nodes.map(_._1)
    s.pendingSubtreeRecords should contain(nodes.head._1)
    s.selfInbox.receiveAll().collect { case m: TrieNodeHealingCoordinator.FlushComplete => m } shouldBe empty
  }

  private def awaitHealingComplete(controller: TestProbe[SNAPSyncController.Command]): Unit =
    controller.fishForMessage(30.seconds) {
      case SNAPSyncController.StateHealingComplete => FishingOutcomes.complete
      case _                                       => FishingOutcomes.continueAndIgnore
    }

  private def heal(scheme: StorageScheme, mpt: TestMptStorage, pns: Option[PathNodeStorage]): Unit =
    val trie = PathHealPresenceFixtures.accountTrieOld()
    val peer = new ScriptedPeer(testKit)
    peer.serveFrom.set(Some(trie.served))
    val pool = Executors.newSingleThreadExecutor()
    val controller = createTestProbe[SNAPSyncController.Command]()
    val coordinator = HealingTrieFixtures.spawnCoordinator(
      stateRoot = trie.root.hash,
      networkPeerManager = peer.ref,
      requestTracker = new SNAPRequestTracker()(classicSystem.scheduler),
      mptStorage = mpt,
      batchSize = 16,
      snapSyncController = controller.ref,
      healingWriterEcOverride = Some(ExecutionContext.fromExecutorService(pool)),
      storageScheme = scheme,
      pathNodeStorageOpt = pns,
      movingRootDeltaHeal = true
    )
    peer.coordinator.set(coordinator)
    try
      coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(trie.root.hash)
      coordinator ! TrieNodeHealingCoordinator.HealingPeerAvailable(
        PeerTestHelpers.createTestPeer("flush-failure-peer", createTestProbe[Any]().ref.toClassic)
      )
      awaitHealingComplete(controller)
    finally
      testKit.stop(coordinator)
      testKit.stop(peer.ref)
      pool.shutdown()
      pool.awaitTermination(5, TimeUnit.SECONDS)

  "A healing coordinator" should "complete and store every node after a node write throws, under the Hash scheme (T018)" taggedAs UnitTest in {
    val trie = PathHealPresenceFixtures.accountTrieOld()
    val mpt = new FailingWritesMptStorage(failures = 1)
    heal(StorageScheme.Hash, mpt, None)
    mpt.remaining.get() should be <= 0
    trie.nodes.foreach { n =>
      withClue(s"${n.label}: ") {
        noException should be thrownBy mpt.get(n.hash.toArray)
      }
    }
  }

  it should "complete and place every node after a write batch throws, under the Path scheme (T018)" taggedAs UnitTest in {
    val trie = PathHealPresenceFixtures.accountTrieOld()
    val source = new FailingWritesDataSource(failures = 1)
    val pns = new PathNodeStorage(source)
    heal(StorageScheme.Path, new TestMptStorage(), Some(pns))
    source.remaining.get() should be <= 0
    trie.nodes.foreach { n =>
      withClue(s"${n.label}: ") {
        readAtPath(pns, n).map(ByteString(_)) shouldBe Some(n.encoded)
      }
    }
  }
