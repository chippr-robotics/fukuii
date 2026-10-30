package com.chipprbots.ethereum.blockchain.checkpoint

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicReference

import org.apache.pekko.actor.testkit.typed.scaladsl.FishingOutcomes
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.concurrent.duration.*

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPRequestTracker
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController
import com.chipprbots.ethereum.blockchain.sync.snap.actors.HealingTrieFixtures
import com.chipprbots.ethereum.blockchain.sync.snap.actors.PathHealPresenceFixtures
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator
import com.chipprbots.ethereum.blockchain.sync.snap.actors.healing.HealRequestFixtures.Op
import com.chipprbots.ethereum.blockchain.sync.snap.actors.healing.HealRequestFixtures.ScriptedPeer
import com.chipprbots.ethereum.blockchain.sync.snap.heal.HealInvariant
import com.chipprbots.ethereum.blockchain.sync.snap.heal.HealInvariant.AtSnapStart
import com.chipprbots.ethereum.db.components.Storages
import com.chipprbots.ethereum.db.dataSource.DataUpdate
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.AppStateStorage.Keys
import com.chipprbots.ethereum.db.storage.Namespaces
import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.db.storage.pruning.ArchivePruning
import com.chipprbots.ethereum.domain.BlockchainWriter
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.domain.TrieRoot
import com.chipprbots.ethereum.testing.PeerTestHelpers
import com.chipprbots.ethereum.testing.Tags.UnitTest

/** spec 022 T041: an interrupted checkpoint import falls through to SNAP on the same datadir (`SyncController`). The
  * importer writes parent-first and checks the CRC only at the end, so what it leaves is a present root over absent
  * children, and nothing but the `checkpoint.import.inProgress` key says so. That key, and what SNAP does with it, is
  * what these cases are about.
  */
class CheckpointImportInterruptSpec extends ScalaTestWithActorTestKit() with AnyFlatSpecLike with Matchers:

  implicit private val actorTestKit: org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit = testKit

  private val AppNs = Namespaces.AppStateNamespace
  private val NodeNs = Namespaces.NodeNamespace

  private def key(k: String): ByteString = ByteString(k.getBytes("UTF-8"))

  final private class Recording extends EphemDataSource(Map.empty):
    val batches: mutable.ArrayBuffer[(Boolean, Seq[Op])] = mutable.ArrayBuffer.empty
    override def update(updates: Seq[DataUpdate]): Unit =
      batches += ((false, updates.flatMap(Op.of)))
      super.update(updates)
    override def updateSync(updates: Seq[DataUpdate]): Unit =
      batches += ((true, updates.flatMap(Op.of)))
      super.update(updates)

  /** A datadir this binary created, over a recording source, under archive pruning (nodes write straight through). */
  private class Datadir extends EphemBlockchainTestSetup:
    val source = new Recording
    val store = new com.chipprbots.ethereum.db.components.EphemDataSourceComponent
      with CheckpointImporterSpec.FixedDataSource(source)
      with LocalPruningConfigBuilder
      with CheckpointImporterSpec.FixedPruningMode(ArchivePruning)
      with Storages.DefaultStorages
      with com.chipprbots.ethereum.TestInstanceConfigProvider
    val appState: AppStateStorage = store.storages.appStateStorage
    val stateTries: StateStorage = store.storages.stateStorage
    appState.markDatadirCreatedUnderInvariant().commit()
    source.batches.clear()

    val importer = new CheckpointImporter(
      BlockchainWriter(store.storages),
      stateTries,
      store.storages.evmCodeStorage,
      appState
    )

    def holds(hash: ByteString): Boolean =
      try
        stateTries.getBackingStorage(0).get(hash.toArray)
        true
      catch case _: Exception => false

  private val trie = PathHealPresenceFixtures.accountTrieOld()
  private val chainId = 61L
  private val header = CheckpointArchive.Header(
    chainId = chainId,
    blockHeader = Fixtures.Blocks.Block3125369.header.copy(stateRoot = TrieRoot(trie.root.hash)),
    chainWeight = ChainWeight.totalDifficultyOnly(BigInt(1))
  )

  // Parent-first, as the exporter writes it: each node before anything below it.
  private val parentFirst = Seq("root", "ext", "brY", "brX", "leafY0", "leafY1", "leafX1", "leafX2")

  private def archive: Array[Byte] =
    val out = new ByteArrayOutputStream()
    val w = new CheckpointArchive.Writer(out)
    w.writeHeader(header)
    parentFirst.foreach(l => w.writeNode(trie(l).hash, trie(l).encoded.toArray))
    w.finish()
    out.toByteArray

  /** The archive cut off part of the way through its entries, as a connection or a process dies. */
  private def truncated: Array[Byte] =
    val whole = archive
    val tail = parentFirst.drop(3).map(l => trie(l).encoded.length + 32).sum
    whole.take(whole.length - tail)

  private def importFrom(d: Datadir, bytes: Array[Byte]) =
    d.importer.importFromStream(new ByteArrayInputStream(bytes), Some(chainId))

  "CheckpointImporter" should "set the import key, synced, before it writes the first node" taggedAs UnitTest in new Datadir:
    importFrom(this, truncated).isLeft shouldBe true
    val writes = source.batches.toSeq
    val firstNode = writes.indexWhere(_._2.exists(_.namespace == NodeNs))
    val keyWrite = writes.indexWhere(_._2.contains(Op(AppNs, delete = false, key(Keys.CheckpointImportInProgress))))
    withClue(s"writes: $writes: ") {
      firstNode should be >= 0
      keyWrite should be >= 0
      keyWrite should be < firstNode
      writes(keyWrite)._1 shouldBe true
    }

  it should "leave the key, naming the root, when the stream is cut off, over a partially written root" taggedAs UnitTest in new Datadir:
    importFrom(this, truncated).isLeft shouldBe true
    appState.getCheckpointImportInProgress().map(_.takeWhile(_ != ':')) shouldBe
      Some(trie.root.hash.toArray.map("%02x".format(_)).mkString)
    withClue("what it wrote stays, a present root over absent children: ") {
      holds(trie.root.hash) shouldBe true
      holds(trie("leafX2").hash) shouldBe false
    }
    appState.isSnapSyncDone() shouldBe false

  it should "leave the key when the CRC does not check" taggedAs UnitTest in new Datadir:
    val bad = archive
    bad(bad.length - 1) = (bad(bad.length - 1) ^ 0xff).toByte
    importFrom(this, bad).isLeft shouldBe true
    appState.getCheckpointImportInProgress() shouldBe defined
    appState.isSnapSyncDone() shouldBe false

  it should "clear the key on CRC success, in the one synced batch that writes the done flags" taggedAs UnitTest in new Datadir:
    importFrom(this, archive).isRight shouldBe true
    appState.getCheckpointImportInProgress() shouldBe None
    appState.isSnapSyncDone() shouldBe true
    val flags = source.batches.toSeq.filter(_._2.contains(Op(AppNs, delete = false, key(Keys.SnapSyncDone))))
    flags.size shouldBe 1
    flags.head._1 shouldBe true
    flags.head._2 should contain(Op(AppNs, delete = true, key(Keys.CheckpointImportInProgress)))

  it should "take a marker away in the write that sets the key: an import on a marked datadir leaves it pre-invariant, however it ends" taggedAs UnitTest in new Datadir:
    appState.stampSnapHealInvariant().commit()
    source.batches.clear()
    importFrom(this, truncated).isLeft shouldBe true
    val keyWrite =
      source.batches.toSeq.find(_._2.contains(Op(AppNs, delete = false, key(Keys.CheckpointImportInProgress))))
    keyWrite.map(_._2.toSet) shouldBe Some(
      Set(
        Op(AppNs, delete = false, key(Keys.CheckpointImportInProgress)),
        Op(AppNs, delete = true, key(Keys.SnapHealInvariant))
      )
    )
    appState.hasSnapHealInvariant() shouldBe false

    withClue("and it stays away when the retry succeeds: the done flags vouch for that root, not for this datadir: ") {
      importFrom(this, archive).isRight shouldBe true
      appState.hasSnapHealInvariant() shouldBe false
      appState.getCheckpointImportInProgress() shouldBe None
    }

  it should "keep a marker when nothing was going to be written: a bad header, or another chain's archive" taggedAs UnitTest in new Datadir:
    appState.stampSnapHealInvariant().commit()
    importFrom(this, Array[Byte](1, 2, 3)).isLeft shouldBe true
    importer.importFromStream(new ByteArrayInputStream(archive), Some(chainId + 1)).isLeft shouldBe true
    appState.hasSnapHealInvariant() shouldBe true
    appState.getCheckpointImportInProgress() shouldBe None

  "SNAP after an interrupted import" should "count the datadir as pre-invariant, with nothing else in it to say so (T041)" taggedAs UnitTest in new Datadir:
    importFrom(this, truncated).isLeft shouldBe true
    withClue("no sync key was written: only the import key tells this datadir from an empty one: ") {
      appState.getSnapSyncPivotBlock() shouldBe None
      appState.getBestBlockNumber() shouldBe 0
    }
    HealInvariant.atSnapStart(appState, None, canHold = true) shouldBe a[AtSnapStart.PreInvariant]
    appState.hasSnapHealInvariant() shouldBe false

  it should "not trust the partially written root: a walk finds what is under it, fetches it, and only then completes (T041)" taggedAs UnitTest in new Datadir:
    importFrom(this, truncated).isLeft shouldBe true
    holds(trie.root.hash) shouldBe true
    val peer = new ScriptedPeer(testKit)
    peer.serveFrom.set(Some(trie.served))
    val controller = createTestProbe[SNAPSyncController.Command]()
    val cleanRoot = new AtomicReference[ByteString]()
    val coordinator = HealingTrieFixtures.spawnCoordinator(
      stateRoot = trie.root.hash,
      networkPeerManager = peer.ref,
      requestTracker = new SNAPRequestTracker()(CheckpointImportInterruptSpec.this.system.classicSystem.scheduler),
      mptStorage = stateTries.getBackingStorage(header.blockHeader.number.value),
      batchSize = 16,
      snapSyncController = controller.ref,
      movingRootDeltaHeal = true,
      cleanWalkRoot = Some(cleanRoot)
    )
    peer.coordinator.set(coordinator)
    coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(trie.root.hash)
    coordinator ! TrieNodeHealingCoordinator.HealingPeerAvailable(
      PeerTestHelpers.createTestPeer("import-peer", createTestProbe[Any]().ref.toClassic)
    )
    controller.fishForMessage(30.seconds) {
      case SNAPSyncController.StateHealingComplete => FishingOutcomes.complete
      case _                                       => FishingOutcomes.continueAndIgnore
    }
    withClue("it asked for what the import never wrote: ")(peer.autoRequests should not be empty)
    trie.nodes.foreach(n => withClue(s"${n.label}: ")(holds(n.hash) shouldBe true))
    withClue("that clean walk is what may stamp the marker: ")(cleanRoot.get() shouldBe trie.root.hash)
    testKit.stop(coordinator)
    testKit.stop(peer.ref)
