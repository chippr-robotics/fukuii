package com.chipprbots.ethereum.blockchain.sync.snap

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

import org.apache.pekko.actor.testkit.typed.scaladsl.BehaviorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.FishingOutcomes
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.TestInbox
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe as TypedTestProbe
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.ActorRef as TypedActorRef
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.testkit.TestActor.AutoPilot
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.compiletime.asMatchable
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*

import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.Millis
import org.scalatest.time.Seconds
import org.scalatest.time.Span

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.blockchain.checkpoint.CheckpointImporterSpec
import com.chipprbots.ethereum.blockchain.sync.CacheBasedBlacklist
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
import com.chipprbots.ethereum.blockchain.sync.TestSyncConfig
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.*
import com.chipprbots.ethereum.blockchain.sync.snap.actors.HealingStatistics
import com.chipprbots.ethereum.blockchain.sync.snap.actors.HealingTrieFixtures
import com.chipprbots.ethereum.blockchain.sync.snap.actors.PathHealPresenceFixtures
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator
import com.chipprbots.ethereum.blockchain.sync.snap.actors.healing.HealRequestFixtures
import com.chipprbots.ethereum.blockchain.sync.snap.actors.healing.HealRequestFixtures.Op
import com.chipprbots.ethereum.blockchain.sync.snap.actors.healing.HealRequestFixtures.ScriptedPeer
import com.chipprbots.ethereum.blockchain.sync.snap.controller.ChildFactories
import com.chipprbots.ethereum.blockchain.sync.snap.controller.HealingOrchestrator
import com.chipprbots.ethereum.blockchain.sync.snap.controller.StubHealingOrchestratorState
import com.chipprbots.ethereum.blockchain.sync.snap.heal.HealInvariant
import com.chipprbots.ethereum.blockchain.sync.snap.heal.HealInvariant.AtSnapStart
import com.chipprbots.ethereum.db.components.EphemDataSourceComponent
import com.chipprbots.ethereum.db.components.Storages
import com.chipprbots.ethereum.db.dataSource.DataSource
import com.chipprbots.ethereum.db.dataSource.DataUpdate
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.dataSource.RocksDbConfig
import com.chipprbots.ethereum.db.dataSource.RocksDbDataSource
import com.chipprbots.ethereum.db.storage.BfsQueueStorage
import com.chipprbots.ethereum.db.storage.InMemoryBfsQueueStorage
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.AppStateStorage.Keys
import com.chipprbots.ethereum.db.storage.HealingFrontierStorage
import com.chipprbots.ethereum.db.storage.Namespaces
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.db.storage.pruning.InMemoryPruning
import com.chipprbots.ethereum.db.storage.pruning.PruningMode
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.BlockchainWriter
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.domain.TrieRoot
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.GetHandshakedPeersCmd
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.HandshakedPeers
import com.chipprbots.ethereum.network.PeerEventBusActor
import com.chipprbots.ethereum.network.p2p.messages.SNAP
import com.chipprbots.ethereum.testing.PeerTestHelpers
import com.chipprbots.ethereum.testing.TrieStoreSpy
import com.chipprbots.ethereum.testing.Tags.UnitTest
import com.chipprbots.ethereum.testing.TestMptStorage

/** spec 022 T039, T040, V-N2 (2): the marker that says everything present was written under "present implies complete",
  * and the retirement of the persisted frontier (CF `g`). Each datadir here is a fresh store.
  */
class InvariantMarkerSpec extends ScalaTestWithActorTestKit() with AnyFlatSpecLike with Matchers:

  implicit private val classicSystem: org.apache.pekko.actor.ActorSystem = system.classicSystem
  implicit private val actorTestKit: org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit = testKit

  private val root = ByteString(Array.fill[Byte](32)(3))
  private val otherRoot = ByteString(Array.fill[Byte](32)(4))
  private val AppNs = Namespaces.AppStateNamespace
  private val FrontierNs = Namespaces.HealingFrontierNamespace

  /** An ephemeral data source that records every write: each `update` and `updateSync` as one batch, and each range
    * delete. Faults can be switched on, to stand for the crash that ends the process at that point.
    */
  final private class Recording extends EphemDataSource(Map.empty):
    val batches: mutable.ArrayBuffer[(Boolean, Seq[Op])] = mutable.ArrayBuffer.empty
    val rangeDeletes: mutable.ArrayBuffer[IndexedSeq[Byte]] = mutable.ArrayBuffer.empty
    var failSynced: Boolean = false
    var failRangeDelete: Boolean = false

    override def update(updates: Seq[DataUpdate]): Unit =
      batches.synchronized(batches += ((false, updates.flatMap(Op.of))))
      super.update(updates)

    override def updateSync(updates: Seq[DataUpdate]): Unit =
      if failSynced then throw new RuntimeException("crash before this write")
      batches.synchronized(batches += ((true, updates.flatMap(Op.of))))
      super.update(updates)

    override def deleteRange(namespace: DataSource.Namespace, fromKey: Array[Byte], toKeyExclusive: Array[Byte]): Unit =
      if failRangeDelete then throw new RuntimeException("crash before this write")
      batches.synchronized(rangeDeletes += namespace)
      super.deleteRange(namespace, fromKey, toKeyExclusive)

    def frontierWrites: Seq[String] = batches.synchronized {
      batches.toSeq.flatMap(_._2).filter(_.namespace == FrontierNs).map(_.toString) ++
        rangeDeletes.toSeq.filter(_ == FrontierNs).map(_ => "deleteRange")
    }

    def forget(): Unit = batches.synchronized { batches.clear(); rangeDeletes.clear() }

  private def key(k: String): ByteString = ByteString(k.getBytes("UTF-8"))

  /** A datadir whose genesis this binary stored, and on which nothing else has happened. */
  private def created(): (Recording, AppStateStorage) =
    val source = new Recording
    val appState = new AppStateStorage(source)
    appState.markDatadirCreatedUnderInvariant().commit()
    source.forget()
    (source, appState)

  // ---- at a SNAP start -------------------------------------------------------------------------------------------

  "HealInvariant at a SNAP start" should "stamp a datadir created under the invariant on which nothing has happened, synced" taggedAs UnitTest in {
    val (source, appState) = created()
    HealInvariant.atSnapStart(appState, None, canHold = true) shouldBe AtSnapStart.StampedEmpty
    appState.hasSnapHealInvariant() shouldBe true
    source.batches.toSeq.map(_._1) shouldBe Seq(true)
    withClue("a second start finds it standing and writes nothing: ") {
      source.forget()
      HealInvariant.atSnapStart(appState, None, canHold = true) shouldBe AtSnapStart.Stands
      source.batches shouldBe empty
    }
  }

  it should "not stamp a datadir an older binary created, even with nothing in it to say a sync ran" taggedAs UnitTest in {
    val appState = new AppStateStorage(new Recording)
    HealInvariant.atSnapStart(appState, None, canHold = true) shouldBe a[AtSnapStart.PreInvariant]
    appState.hasSnapHealInvariant() shouldBe false
  }

  // Each line is a key a sync writes before it writes any trie node, or that says one has run (T039).
  private val progress: Seq[(String, AppStateStorage => Unit)] = Seq(
    "a best block" -> (_.putBestBlockNumber(BigInt(7)).commit()),
    "SNAP done" -> (_.snapSyncDone().commit()),
    "fast sync done" -> (_.fastSyncDone().commit()),
    "a pivot" -> (_.putSnapSyncPivotBlock(BigInt(7)).commit()),
    "a state root" -> (_.putSnapSyncStateRoot(root).commit()),
    "a finalized root" -> (_.putSnapSyncFinalizedRoot(root).commit()),
    "a pivot floor" -> (_.putSnapSyncMinPivotBlock(BigInt(7)).commit()),
    "a bootstrap target" -> (_.putSnapSyncBootstrapTarget(BigInt(7)).commit()),
    "accounts complete" -> (_.putSnapSyncAccountsComplete(true).commit()),
    "storage complete" -> (_.putSnapSyncStorageComplete(true).commit()),
    "bytecode complete" -> (_.putSnapSyncBytecodeComplete(true).commit()),
    "a storage task file" -> (_.putSnapSyncStorageFilePath("/tmp/tasks.bin").commit()),
    "a code-hash file" -> (_.putSnapSyncCodeHashesPath("/tmp/code.bin").commit())
  )

  progress.foreach { case (what, write) =>
    it should s"not stamp a datadir with $what, and treat it as pre-invariant" taggedAs UnitTest in {
      val (_, appState) = created()
      write(appState)
      HealInvariant.atSnapStart(appState, None, canHold = true) shouldBe a[AtSnapStart.PreInvariant]
      appState.hasSnapHealInvariant() shouldBe false
    }
  }

  it should "not stamp when a Path column family holds a node, whichever one" taggedAs UnitTest in {
    Seq[PathNodeStorage => Unit](
      _.writeAccountNode(Array[Byte](1), Array[Byte](1, 2)),
      _.writeStorageNode(root, Array[Byte](1), Array[Byte](1, 2))
    ).foreach { write =>
      val (source, appState) = created()
      val pathNodes = new PathNodeStorage(source)
      withClue("nothing there yet: ")(pathNodes.isEmpty shouldBe true)
      write(pathNodes)
      HealInvariant.atSnapStart(appState, Some(pathNodes), canHold = true) shouldBe a[AtSnapStart.PreInvariant]
      appState.hasSnapHealInvariant() shouldBe false
    }
  }

  it should "let a checkpoint import in progress outrank everything: no stamp, and a marker found goes, synced" taggedAs UnitTest in {
    val (source, appState) = created()
    appState.beginCheckpointImport(root, 1L).commit()
    HealInvariant.atSnapStart(appState, None, canHold = true) shouldBe a[AtSnapStart.PreInvariant]
    appState.hasSnapHealInvariant() shouldBe false

    withClue("both found together (only a bug or a hand could do it): the key wins: ") {
      appState.put(Keys.SnapHealInvariant, AppStateStorage.SnapHealInvariantVersion).commit()
      source.forget()
      HealInvariant.atSnapStart(appState, None, canHold = true) shouldBe a[AtSnapStart.PreInvariant]
      appState.hasSnapHealInvariant() shouldBe false
      source.batches.toSeq.map(_._1) shouldBe Seq(true)
      appState.getCheckpointImportInProgress() shouldBe defined
    }
  }

  // spec 022 review vault F3: nothing vouches for what this binary wrote under another version's rule, so removal goes by
  // the key being there, and only `Stands` looks at the version.
  private def markedOther(): (Recording, AppStateStorage) =
    val source = new Recording
    val appState = new AppStateStorage(source)
    appState.put(Keys.SnapHealInvariant, "3").commit()
    source.forget()
    (source, appState)

  it should "not take a marker of another version as standing, and remove it, synced" taggedAs UnitTest in {
    val (source, appState) = markedOther()
    appState.hasSnapHealInvariant() shouldBe false
    HealInvariant.atSnapStart(appState, None, canHold = true) shouldBe a[AtSnapStart.PreInvariant]
    appState.get(Keys.SnapHealInvariant) shouldBe None
    source.batches.toSeq.map(_._1) shouldBe Seq(true)
    source.batches.head._2 should contain(Op(AppNs, delete = true, key(Keys.SnapHealInvariant)))
  }

  // spec 022 T050a, beacon's crash class: a Path account phase over existing nodes takes the marker back.
  private val invariantMarkerDelete = Op(AppNs, delete = true, key(Keys.SnapHealInvariant))

  /** What a write that replaces the state takes with it for the background audit (spec 022 T053). */
  private val auditForgotten = Set(
    Op(AppNs, delete = true, key(Keys.SnapAuditCleanAnchor)),
    Op(Namespaces.SnapSyncProgressNamespace, delete = true, key("snap.heal.auditCursors")),
    Op(Namespaces.SnapSyncProgressNamespace, delete = true, key("snap.heal.auditBadEntries"))
  )

  private def stamped(): (Recording, AppStateStorage, PathNodeStorage) =
    val (source, appState) = created()
    val pathNodes = new PathNodeStorage(source)
    HealInvariant.atSnapStart(appState, Some(pathNodes), canHold = true) shouldBe AtSnapStart.StampedEmpty
    source.forget()
    (source, appState, pathNodes)

  private def withdrawal(
      appState: AppStateStorage,
      pathNodes: PathNodeStorage,
      scheme: StorageScheme = StorageScheme.Path,
      resumedCursors: Boolean = false
  ): Boolean = HealInvariant.withdrawForPathAccountPhase(appState, Some(pathNodes), scheme, resumedCursors)

  "A Path account phase" should "withdraw the marker when it resumes cursors, in one synced write" taggedAs UnitTest in {
    val (source, appState, pathNodes) = stamped()
    withdrawal(appState, pathNodes, resumedCursors = true) shouldBe true
    appState.isSnapHealInvariantPresent() shouldBe false
    source.batches.toSeq shouldBe Seq((true, Seq(invariantMarkerDelete)))
  }

  it should "withdraw the marker when it starts fresh over a Path family holding a node, whichever one" taggedAs UnitTest in {
    Seq[PathNodeStorage => Unit](
      _.writeAccountNode(Array[Byte](1), Array[Byte](1, 2)),
      _.writeStorageNode(root, Array[Byte](1), Array[Byte](1, 2))
    ).foreach { write =>
      val (source, appState, pathNodes) = stamped()
      write(pathNodes)
      source.forget()
      withdrawal(appState, pathNodes) shouldBe true
      appState.isSnapHealInvariantPresent() shouldBe false
      source.batches.toSeq shouldBe Seq((true, Seq(invariantMarkerDelete)))
    }
  }

  it should "withdraw a marker of another version too: the removals go by the key being there" taggedAs UnitTest in {
    val (_, appState) = markedOther()
    val pathNodes = new PathNodeStorage(EphemDataSource())
    withdrawal(appState, pathNodes, resumedCursors = true) shouldBe true
    appState.get(Keys.SnapHealInvariant) shouldBe None
  }

  it should "keep the marker on a first sync: fresh, over empty families" taggedAs UnitTest in {
    val (source, appState, pathNodes) = stamped()
    withdrawal(appState, pathNodes) shouldBe false
    appState.hasSnapHealInvariant() shouldBe true
    source.batches shouldBe empty
  }

  it should "write nothing when there is no marker to withdraw" taggedAs UnitTest in {
    val (source, appState) = created()
    val pathNodes = new PathNodeStorage(source)
    withdrawal(appState, pathNodes, resumedCursors = true) shouldBe false
    source.batches shouldBe empty
  }

  "A Hash account phase" should "keep the marker, resuming or over any node: nodes are content-addressed" taggedAs UnitTest in {
    val (source, appState, pathNodes) = stamped()
    pathNodes.writeAccountNode(Array[Byte](1), Array[Byte](1, 2))
    source.forget()
    withdrawal(appState, pathNodes, StorageScheme.Hash, resumedCursors = true) shouldBe false
    withdrawal(appState, pathNodes, StorageScheme.Hash) shouldBe false
    appState.hasSnapHealInvariant() shouldBe true
    source.batches shouldBe empty
  }

  // spec 022 T050a (hunter W1.3): a restarted storage coordinator with no done markers cannot sweep, since its file would
  // name every account. That is safe only if nothing trusts a marker there.
  "A datadir with no done markers" should "be one where the invariant marker cannot hold, whatever the settings" taggedAs UnitTest in {
    for
      scheme <- Seq(StorageScheme.Hash, StorageScheme.Path)
      deferred <- Seq(true, false)
      cached <- Seq(true, false)
    do
      withClue(s"$scheme, deferred=$deferred, cached=$cached: ") {
        val markers = HealInvariant.doneMarkersHold(scheme, deferred, cached)
        val canHold = HealInvariant.canHold(scheme, deferred, cached)
        if !markers then canHold shouldBe false
        markers shouldBe (scheme == StorageScheme.Path || deferred || !cached)
      }
  }

  // ---- spec 022 T050a, the user's decision of 2026-10-31: a run that knows it lost track -------------------------------

  private val codeScanOwedPut = Op(AppNs, delete = false, key(Keys.SnapCodeScanOwed))
  private val nopLog = org.slf4j.helpers.NOPLogger.NOP_LOGGER

  "A run that lost track" should "withdraw the marker and owe the code scan, in one synced write" taggedAs UnitTest in {
    val (source, appState, _) = stamped()
    HealInvariant.lostTrack(appState, "a sweep hung", nopLog) shouldBe true
    appState.isSnapHealInvariantPresent() shouldBe false
    appState.getSnapCodeScanOwed() shouldBe Some("a sweep hung")
    source.batches.toSeq.map(_._1) shouldBe Seq(true)
    source.batches.head._2.toSet shouldBe Set(invariantMarkerDelete, codeScanOwedPut)
  }

  it should "say so at WARN, with the reason, in the logger it is handed" taggedAs UnitTest in {
    val (_, appState, _) = stamped()
    val logger = org.slf4j.LoggerFactory
      .getLogger("spec022.lostTrack")
      .asInstanceOf[ch.qos.logback.classic.Logger]
    val heard = new ch.qos.logback.core.read.ListAppender[ch.qos.logback.classic.spi.ILoggingEvent]
    heard.start()
    logger.addAppender(heard)
    logger.setLevel(ch.qos.logback.classic.Level.WARN)
    logger.setAdditive(false)
    HealInvariant.lostTrack(appState, "no task file", logger) shouldBe true
    val line = heard.list.get(0)
    line.getLevel shouldBe ch.qos.logback.classic.Level.WARN
    line.getFormattedMessage should include("no task file")
  }

  it should "still owe the scan with no marker to withdraw: the code does not depend on the marker" taggedAs UnitTest in {
    val (source, appState) = created()
    HealInvariant.lostTrack(appState, "where it cannot hold", nopLog) shouldBe true
    appState.getSnapCodeScanOwed() shouldBe Some("where it cannot hold")
    source.batches.toSeq.map(_._1) shouldBe Seq(true)
  }

  it should "claim nothing when the write fails, so that the caller holds what it was about to end" taggedAs UnitTest in {
    val (source, appState, _) = stamped()
    source.failSynced = true
    HealInvariant.lostTrack(appState, "a sweep hung", nopLog) shouldBe false
    appState.hasSnapHealInvariant() shouldBe true
    appState.getSnapCodeScanOwed() shouldBe None
  }

  it should "keep owing the scan after a clean walk stamps the marker again, and across a restart that finds the stamp" taggedAs UnitTest in {
    val (source, appState, pathNodes) = stamped()
    HealInvariant.lostTrack(appState, "a sweep hung", nopLog) shouldBe true
    appState.stampSnapHealInvariant().commitSync()
    source.forget()
    HealInvariant.atSnapStart(appState, Some(pathNodes), canHold = true) shouldBe AtSnapStart.Stands
    appState.getSnapCodeScanOwed() shouldBe Some("a sweep hung")
    source.batches shouldBe empty
  }

  // The user's decision, 2026-10-31: a repair run withholds bytecodeRecoveryDone, so the scan runs once at the next start.
  "A SNAP start" should "owe the code scan on a repair run, in one synced write with the marker's removal" taggedAs UnitTest in {
    val (source, appState) = markedOther()
    HealInvariant.atSnapStart(appState, None, canHold = true) shouldBe a[AtSnapStart.PreInvariant]
    appState.getSnapCodeScanOwed() shouldBe Some("no marker, and not an empty datadir")
    source.batches.toSeq.map(_._1) shouldBe Seq(true)
    source.batches.head._2.toSet shouldBe Set(invariantMarkerDelete, codeScanOwedPut)
  }

  it should "owe the code scan under an unfinished checkpoint import, and say which" taggedAs UnitTest in {
    val (_, appState) = created()
    appState.beginCheckpointImport(root, 1L).commit()
    HealInvariant.atSnapStart(appState, None, canHold = true) shouldBe a[AtSnapStart.PreInvariant]
    appState.getSnapCodeScanOwed().get should include("checkpoint import")
  }

  it should "write nothing at a later start of the same repair run: the debt is already durable" taggedAs UnitTest in {
    val (source, appState) = markedOther()
    HealInvariant.atSnapStart(appState, None, canHold = true) shouldBe a[AtSnapStart.PreInvariant]
    source.forget()
    HealInvariant.atSnapStart(appState, None, canHold = true) shouldBe a[AtSnapStart.PreInvariant]
    source.batches shouldBe empty
    appState.getSnapCodeScanOwed() shouldBe defined
  }

  it should "owe nothing on an empty datadir, under a standing marker, or where the marker cannot hold" taggedAs UnitTest in {
    val (_, empty) = created()
    HealInvariant.atSnapStart(empty, None, canHold = true) shouldBe AtSnapStart.StampedEmpty
    empty.getSnapCodeScanOwed() shouldBe None
    HealInvariant.atSnapStart(empty, None, canHold = true) shouldBe AtSnapStart.Stands
    empty.getSnapCodeScanOwed() shouldBe None
    val (_, inMemory) = created()
    HealInvariant.atSnapStart(inMemory, None, canHold = false) shouldBe AtSnapStart.CannotHold(clearedOne = false)
    inMemory.getSnapCodeScanOwed() shouldBe None
  }

  "HealInvariant at a SNAP start" should "remove a marker of another version where the marker cannot hold, and say so" taggedAs UnitTest in {
    val (_, appState) = markedOther()
    HealInvariant.atSnapStart(appState, None, canHold = false) shouldBe AtSnapStart.CannotHold(clearedOne = true)
    appState.get(Keys.SnapHealInvariant) shouldBe None
  }

  it should "remove a marker of another version under an unfinished checkpoint import" taggedAs UnitTest in {
    val (_, appState) = markedOther()
    appState.put(Keys.CheckpointImportInProgress, "a:1").commit()
    HealInvariant.atSnapStart(appState, None, canHold = true) shouldBe a[AtSnapStart.PreInvariant]
    appState.get(Keys.SnapHealInvariant) shouldBe None
  }

  // Version 2 stamped an empty datadir and then downloaded with H0 and H1 open: published in the v0.10.20 pre-release.
  it should "not trust a marker from before the download's gaps were closed" taggedAs UnitTest in {
    val source = new Recording
    val appState = new AppStateStorage(source)
    appState.put(Keys.SnapHealInvariant, "2").commit()
    appState.hasSnapHealInvariant() shouldBe false
    HealInvariant.atSnapStart(appState, None, canHold = true) shouldBe a[AtSnapStart.PreInvariant]
    appState.get(Keys.SnapHealInvariant) shouldBe None
  }

  it should "stamp over a marker of another version on an otherwise empty datadir" taggedAs UnitTest in {
    val (_, appState) = created()
    appState.put(Keys.SnapHealInvariant, "3").commit()
    HealInvariant.atSnapStart(appState, None, canHold = true) shouldBe AtSnapStart.StampedEmpty
    appState.hasSnapHealInvariant() shouldBe true
  }

  // ---- pruning.mode = inmemory (V-N2 (2), (4), (5)) ---------------------------------------------------------------

  it should "know the marker cannot hold on Hash under in-memory pruning, and that it can on Path, which writes round the cache" taggedAs UnitTest in {
    HealInvariant.canHold(
      StorageScheme.Hash,
      deferredMerkleization = false,
      trieNodesCachedInMemory = true
    ) shouldBe false
    HealInvariant.canHold(
      StorageScheme.Hash,
      deferredMerkleization = false,
      trieNodesCachedInMemory = false
    ) shouldBe true
    HealInvariant.canHold(
      StorageScheme.Path,
      deferredMerkleization = false,
      trieNodesCachedInMemory = true
    ) shouldBe true
    HealInvariant.canHold(
      StorageScheme.Path,
      deferredMerkleization = false,
      trieNodesCachedInMemory = false
    ) shouldBe true
  }

  // spec 022 5d: no trie is built under deferred merkleization, and a lone absence still gets a done marker (H1).
  it should "know the marker cannot hold under deferred merkleization, whichever the scheme and pruning mode" taggedAs UnitTest in {
    for
      scheme <- Seq(StorageScheme.Hash, StorageScheme.Path)
      cached <- Seq(true, false)
    do
      withClue(s"$scheme, cached=$cached: ") {
        HealInvariant.canHold(scheme, deferredMerkleization = true, trieNodesCachedInMemory = cached) shouldBe false
      }
  }

  // spec 022 5d: a marker from before this version may have been standing when its run lost track, and this is the first
  // version that skips a walk on its word.
  it should "not take a version 3 marker as standing" taggedAs UnitTest in {
    val (_, appState) = created()
    appState.put(Keys.SnapHealInvariant, "3").commit()
    appState.hasSnapHealInvariant() shouldBe false
    appState.isSnapHealInvariantPresent() shouldBe true
  }

  it should "keep what says who created the datadir at its own version, which the marker's move did not touch" taggedAs UnitTest in {
    AppStateStorage.SnapHealInvariantVersion shouldBe "4"
    AppStateStorage.SnapHealDatadirCreatedVersion shouldBe "3"
    val (_, appState) = created()
    appState.get(Keys.SnapHealDatadirCreated) shouldBe Some("3")
    appState.isDatadirCreatedUnderInvariant() shouldBe true
    appState.put(Keys.SnapHealDatadirCreated, "2").commit()
    withClue("a datadir with an older value is not empty: ") {
      appState.isDatadirCreatedUnderInvariant() shouldBe false
    }
  }

  it should "where it cannot hold: never stamp a datadir that would otherwise count as empty, at any start" taggedAs UnitTest in {
    val (source, appState) = created()
    (1 to 2).foreach { _ =>
      HealInvariant.atSnapStart(appState, None, canHold = false) shouldBe AtSnapStart.CannotHold(clearedOne = false)
    }
    appState.hasSnapHealInvariant() shouldBe false
    source.batches shouldBe empty
  }

  it should "where it cannot hold: not trust a marker it finds, and clear it, synced" taggedAs UnitTest in {
    val (source, appState) = created()
    appState.stampSnapHealInvariant().commit()
    source.forget()
    HealInvariant.atSnapStart(appState, None, canHold = false) shouldBe AtSnapStart.CannotHold(clearedOne = true)
    appState.hasSnapHealInvariant() shouldBe false
    source.batches.toSeq shouldBe Seq((true, Seq(Op(AppNs, delete = true, key(Keys.SnapHealInvariant)))))
  }

  it should "stamp at a clean walk nowhere it cannot hold" taggedAs UnitTest in {
    val h = new Harness(SNAPSyncConfig(deferredMerkleization = false, storageScheme = StorageScheme.Hash))
    h.module.trieNodesCachedInMemory = true
    h.ready()
    h.module.healingCleanWalkRoot.set(root)
    h.call(_.stateHealingArms(TrieWalkComplete(0)))
    h.module.validations shouldBe 1
    h.module.appStateStorage.hasSnapHealInvariant() shouldBe false
  }

  it should "stamp at a clean walk on Path under in-memory pruning: its nodes go to the Path families, not the cache" taggedAs UnitTest in {
    val h = new Harness(SNAPSyncConfig(deferredMerkleization = false, storageScheme = StorageScheme.Path))
    h.module.trieNodesCachedInMemory = true
    h.ready()
    h.module.healingCleanWalkRoot.set(root)
    h.call(_.completeHealingWalkClean())
    h.module.appStateStorage.hasSnapHealInvariant() shouldBe true
  }

  it should "stamp at no clean walk under deferred merkleization, on either scheme: the marker neither stands nor is stamped there" taggedAs UnitTest in {
    Seq(StorageScheme.Hash, StorageScheme.Path).foreach { scheme =>
      val h = new Harness(SNAPSyncConfig(deferredMerkleization = true, storageScheme = scheme))
      h.ready()
      h.module.healingCleanWalkRoot.set(root)
      h.call(_.completeHealingWalkClean())
      withClue(s"$scheme: ")(h.module.appStateStorage.hasSnapHealInvariant() shouldBe false)
    }
  }

  // ---- clearDoneOnStart, and every other re-entry ----------------------------------------------------------------

  it should "let clearDoneOnStart take the marker with the done flag, in one synced write (T039)" taggedAs UnitTest in {
    val (source, appState) = created()
    appState.stampSnapHealInvariant().and(appState.snapSyncDone()).commit()
    source.forget()
    HealInvariant.clearDoneOnStart(appState) shouldBe true
    appState.isSnapSyncDone() shouldBe false
    appState.hasSnapHealInvariant() shouldBe false
    source.batches.toSeq.map(_._1) shouldBe Seq(true)
    source.batches.head._2.toSet shouldBe Set(
      Op(AppNs, delete = true, key(Keys.SnapSyncDone)),
      Op(AppNs, delete = true, key(Keys.SnapHealInvariant))
    ) ++ auditForgotten
    withClue("the SNAP start that follows finds a pre-invariant datadir: ") {
      appState.putSnapSyncAccountsComplete(true).commit()
      HealInvariant.atSnapStart(appState, None, canHold = true) shouldBe a[AtSnapStart.PreInvariant]
    }
  }

  it should "let clearDoneOnStart take the marker of a datadir not yet done, too" taggedAs UnitTest in {
    val (_, appState) = created()
    appState.stampSnapHealInvariant().commit()
    HealInvariant.clearDoneOnStart(appState) shouldBe false
    appState.hasSnapHealInvariant() shouldBe false
  }

  it should "take the marker wherever SNAP's done flag is cleared: a stuck import, a failed hand-off" taggedAs UnitTest in {
    val (_, appState) = created()
    appState.stampSnapHealInvariant().and(appState.snapSyncDone()).commit()
    appState.clearSnapSyncDone().commit()
    appState.hasSnapHealInvariant() shouldBe false
  }

  // ---- at a clean walk: the stamp sites, on the module's stub -------------------------------------------------------

  /** The orchestrator on its stub, as in HealingOrchestratorSpec. */
  final private class Harness(config: SNAPSyncConfig = SNAPSyncConfig(deferredMerkleization = false)):
    private val factories = new ChildFactories {}
    private var action: () => Unit = () => ()
    private var captured: StubHealingOrchestratorState & HealingOrchestrator = null
    val kit: BehaviorTestKit[Command] = BehaviorTestKit(Behaviors.setup[Command] { ctx =>
      Behaviors.withTimers { timers =>
        captured = new StubHealingOrchestratorState(
          ctx,
          timers,
          config,
          TestInbox[SyncProtocol.SyncControllerReply]().ref,
          TestInbox[NetworkPeerManagerActor.Command]().ref,
          factories,
          system.classicSystem.scheduler
        ) with HealingOrchestrator
        Behaviors.receiveMessage { _ =>
          action()
          Behaviors.same
        }
      }
    })
    val module: StubHealingOrchestratorState & HealingOrchestrator = captured

    def ready(): Unit =
      module.pivotBlock = Some(BigInt(1000))
      module.stateRoot = Some(TrieRoot(root))

    def call(f: StubHealingOrchestratorState & HealingOrchestrator => Unit): Unit =
      action = () => f(module)
      kit.run(PollHandshakedPeers)

  "The healing orchestrator" should "stamp a datadir with no marker once the coordinator's clean full walk of this root is what the controller's clean walk completes on (Hash, T039)" taggedAs UnitTest in {
    val h = new Harness()
    h.ready()
    h.module.healingCleanWalkRoot.set(root)
    h.module.appStateStorage.hasSnapHealInvariant() shouldBe false
    h.call(_.stateHealingArms(TrieWalkComplete(0)))
    h.module.appStateStorage.hasSnapHealInvariant() shouldBe true
  }

  it should "stamp at the Path completion on the coordinator's clean full walk of this root (T039)" taggedAs UnitTest in {
    val h = new Harness(SNAPSyncConfig(deferredMerkleization = false, storageScheme = StorageScheme.Path))
    h.ready()
    h.module.healingCleanWalkRoot.set(root)
    h.call(_.completeHealingWalkClean())
    h.module.appStateStorage.hasSnapHealInvariant() shouldBe true
  }

  it should "stamp on a clean walk that repairs an interrupted checkpoint import, and take its key in the same write (T039)" taggedAs UnitTest in {
    val h = new Harness()
    h.ready()
    h.module.appStateStorage.beginCheckpointImport(otherRoot, 1L).commit()
    h.module.healingCleanWalkRoot.set(root)
    h.call(_.stateHealingArms(TrieWalkComplete(0)))
    h.module.appStateStorage.hasSnapHealInvariant() shouldBe true
    h.module.appStateStorage.getCheckpointImportInProgress() shouldBe None
  }

  it should "not stamp on the controller's trie walk alone: it stops at present hash nodes, so it is no repair pass" taggedAs UnitTest in {
    val h = new Harness()
    h.ready()
    h.call(_.stateHealingArms(TrieWalkComplete(0)))
    withClue("healing completes as it does today: ")(h.module.validations shouldBe 1)
    h.module.appStateStorage.hasSnapHealInvariant() shouldBe false
  }

  it should "not stamp when the coordinator's clean walk was of another root" taggedAs UnitTest in {
    val h = new Harness()
    h.ready()
    h.module.healingCleanWalkRoot.set(otherRoot)
    h.call(_.stateHealingArms(TrieWalkComplete(0)))
    h.module.appStateStorage.hasSnapHealInvariant() shouldBe false
  }

  it should "not stamp after a later look at the trie found something missing, though an earlier walk was clean" taggedAs UnitTest in {
    val h = new Harness()
    h.ready()
    h.module.healingCleanWalkRoot.set(root)
    h.call(_.stateHealingArms(TrieWalkComplete(3)))
    h.call(_.stateHealingArms(TrieWalkComplete(0)))
    h.module.appStateStorage.hasSnapHealInvariant() shouldBe false
  }

  it should "not stamp after validation found something missing: that route's walk completes on nothing more" taggedAs UnitTest in {
    val h = new Harness()
    h.ready()
    h.module.healingCleanWalkRoot.set(root)
    h.call(_.triggerHealingForMissingNodes(Seq(root)))
    h.module.healingCleanWalkRoot.get() shouldBe null
    h.call(_.stateHealingArms(TrieWalkResult(Seq.empty)))
    h.module.appStateStorage.hasSnapHealInvariant() shouldBe false
  }

  // spec 022 review vault F2: validation walks every node, so it can contradict a stamp, from a stamped start too.
  Seq(AppStateStorage.SnapHealInvariantVersion, "3").foreach { version =>
    it should s"take a stamped marker (version $version), synced, when validation finds something missing" taggedAs UnitTest in {
      val h = new Harness()
      h.ready()
      h.module.appStateStorage.put(Keys.SnapHealInvariant, version).commit()
      h.module.healingCleanWalkRoot.set(root)
      h.module.syncedRemovals.clear()
      h.call(_.triggerHealingForMissingNodes(Seq(root)))
      h.module.appStateStorage.get(Keys.SnapHealInvariant) shouldBe None
      h.module.syncedRemovals.toSeq should contain(key(Keys.SnapHealInvariant))
    }
  }

  it should "forget an earlier coordinator's clean walk when it spawns a new one" taggedAs UnitTest in {
    val h = new Harness()
    h.ready()
    h.module.healingCleanWalkRoot.set(root)
    h.call(_.startStateHealing())
    h.module.healingCleanWalkRoot.get() shouldBe null
  }

  Seq[(String, Command)](
    "StateHealingAbandoned" -> StateHealingAbandoned,
    "HealingRootUnservable" -> HealingRootUnservable(root)
  ).foreach { case (name, abandon) =>
    it should s"never stamp at a lazy hand-off ($name)" taggedAs UnitTest in {
      val h = new Harness()
      h.ready()
      h.call(_.stateHealingArms(abandon))
      h.module.completions shouldBe 1
      h.module.appStateStorage.hasSnapHealInvariant() shouldBe false
    }
  }

  it should "stamp after the anchor, in a synced write of its own" taggedAs UnitTest in {
    val h = new Harness()
    h.ready()
    h.module.healingCleanWalkRoot.set(root)
    h.module.syncedWrites.clear()
    h.call(_.stateHealingArms(TrieWalkComplete(0)))
    val marker = key(Keys.SnapHealInvariant)
    val writes = h.module.syncedWrites.toSeq
    val stamp = writes.indexWhere(_._2.contains(marker))
    val anchor = writes.indexWhere(_._2.contains(key(Keys.SnapSyncStateRoot)))
    withClue(s"writes: $writes: ") {
      anchor should be >= 0
      stamp should be > anchor
      writes(stamp) shouldBe ((true, Seq(marker)))
    }
  }

  it should "go on to validation when the stamp's write fails" taggedAs UnitTest in {
    val h = new Harness()
    h.ready()
    h.module.healingCleanWalkRoot.set(root)
    // A store that fails only on the marker's key: the anchors go through.
    h.module.failSyncedWritesOf = Some(Keys.SnapHealInvariant)
    h.call(_.stateHealingArms(TrieWalkComplete(0)))
    h.module.validations shouldBe 1
    h.module.appStateStorage.hasSnapHealInvariant() shouldBe false
  }

  // ---- the coordinator says which root its clean full walk covered ---------------------------------------------------

  private def spawnHealer(
      trie: PathHealPresenceFixtures.Trie,
      stored: Set[String],
      peer: ScriptedPeer,
      cleanRoot: AtomicReference[ByteString],
      frontier: Option[HealingFrontierStorage] = None,
      queue: Option[BfsQueueStorage] = None,
      flushed: Option[CountDownLatch] = None
  ): (ActorRef[TrieNodeHealingCoordinator.Command], org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe[Command]) =
    val controller = createTestProbe[Command]()
    val mpt = new TestMptStorage():
      override def storeRawNodes(rawNodes: Seq[(ByteString, Array[Byte])]): Unit =
        super.storeRawNodes(rawNodes)
        flushed.foreach(_.countDown())
    trie.nodes.filter(n => stored(n.label)).foreach(n => mpt.storeRawNodes(Seq(n.hash -> n.encoded.toArray)))
    val coordinator = HealingTrieFixtures.spawnCoordinator(
      stateRoot = trie.root.hash,
      networkPeerManager = peer.ref,
      requestTracker = new SNAPRequestTracker()(classicSystem.scheduler),
      mptStorage = mpt,
      batchSize = 16,
      snapSyncController = controller.ref,
      healingFrontierStorage = frontier,
      bfsQueueStorageOpt = queue,
      movingRootDeltaHeal = true,
      cleanWalkRoot = Some(cleanRoot)
    )
    peer.coordinator.set(coordinator)
    (coordinator, controller)

  private def awaitComplete(controller: org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe[Command]): Unit =
    controller.fishForMessage(30.seconds) {
      case StateHealingComplete => FishingOutcomes.complete
      case _                    => FishingOutcomes.continueAndIgnore
    }
    ()

  "The healing coordinator" should "say which root its clean full walk covered as it reports completion" taggedAs UnitTest in {
    val trie = PathHealPresenceFixtures.accountTrieOld()
    val peer = new ScriptedPeer(testKit)
    val cleanRoot = new AtomicReference[ByteString]()
    val (coordinator, controller) = spawnHealer(trie, trie.nodes.map(_.label).toSet, peer, cleanRoot)
    coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(trie.root.hash)
    awaitComplete(controller)
    cleanRoot.get() shouldBe trie.root.hash
    testKit.stop(coordinator)
  }

  // Spec 022 closure review, beacon: a counted skip on valid Path state would stop every stamp on an ETH chain in silence.
  it should "have a clean Path walk over an account with storage earn the proof" taggedAs UnitTest in {
    val trie = PathHealPresenceFixtures.storageTrieOld()
    val pns = new PathNodeStorage(EphemDataSource())
    PathHealPresenceFixtures.seedPath(pns, trie.nodes)
    val peer = new ScriptedPeer(testKit)
    val controller = createTestProbe[Command]()
    val cleanRoot = new AtomicReference[ByteString]()
    val coordinator = HealingTrieFixtures.spawnCoordinator(
      stateRoot = trie.root.hash,
      networkPeerManager = peer.ref,
      requestTracker = new SNAPRequestTracker()(classicSystem.scheduler),
      mptStorage = new TestMptStorage(),
      batchSize = 16,
      snapSyncController = controller.ref,
      storageScheme = StorageScheme.Path,
      pathNodeStorageOpt = Some(pns),
      movingRootDeltaHeal = true,
      cleanWalkRoot = Some(cleanRoot)
    )
    peer.coordinator.set(coordinator)
    coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(trie.root.hash)
    awaitComplete(controller)
    cleanRoot.get() shouldBe trie.root.hash
    testKit.stop(coordinator)
  }

  it should "say nothing while a walk has found a hole, and say it only after a clean walk over the healed node" taggedAs UnitTest in {
    val trie = PathHealPresenceFixtures.accountTrieOld()
    val peer = new ScriptedPeer(testKit)
    val cleanRoot = new AtomicReference[ByteString]()
    val hole = "leafY0"
    val (coordinator, controller) = spawnHealer(trie, trie.nodes.map(_.label).toSet - hole, peer, cleanRoot)
    coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(trie.root.hash)
    coordinator ! TrieNodeHealingCoordinator.HealingPeerAvailable(
      PeerTestHelpers.createTestPeer("marker-peer", createTestProbe[Any]().ref.toClassic)
    )
    val req = peer.manual.expectMessageType[SNAP.GetTrieNodes](30.seconds)
    req.paths shouldBe Seq(trie(hole).pathset)
    cleanRoot.get() shouldBe null
    peer.answerFrom(req, trie.served)
    awaitComplete(controller)
    cleanRoot.get() shouldBe trie.root.hash
    testKit.stop(coordinator)
  }

  // CI caught this as a timeout of the test above, once in 7,076 tests: a walk reports a missing node at the end of its
  // level, and still has a level and a queue to finish. If the answer and its flush land in that gap, their completion
  // checks find the walk flagged running, and on Hash nothing asked again until the dead-pulse watchdog, minutes on. So
  // this holds the first walk at its last act, where the real one is slow, and lets the answer and the flush go first.
  it should "go on when the answer and its flush land before the walk that found the hole has ended" taggedAs UnitTest in {
    val trie = PathHealPresenceFixtures.accountTrieOld()
    val peer = new ScriptedPeer(testKit)
    val cleanRoot = new AtomicReference[ByteString]()
    val hole = "leafY0"
    val walkEnding = new CountDownLatch(1)
    val letWalkEnd = new CountDownLatch(1)
    val clears = new AtomicInteger(0)
    // A walk clears its queue as it starts and again as it ends, just before it reports.
    val queue = new InMemoryBfsQueueStorage:
      override def clear(): Unit =
        if clears.incrementAndGet() == 2 then
          walkEnding.countDown()
          letWalkEnd.await(25, TimeUnit.SECONDS): Unit
        super.clear()
    // Counted down by the seeding of what is stored, then by the flush: one write per stored node, and one more.
    val flushed = new CountDownLatch(trie.nodes.count(_.label != hole) + 1)
    val (coordinator, controller) =
      spawnHealer(
        trie,
        trie.nodes.map(_.label).toSet - hole,
        peer,
        cleanRoot,
        queue = Some(queue),
        flushed = Some(flushed)
      )
    val progress = createTestProbe[HealingStatistics]()
    coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(trie.root.hash)
    coordinator ! TrieNodeHealingCoordinator.HealingPeerAvailable(
      PeerTestHelpers.createTestPeer("marker-peer", createTestProbe[Any]().ref.toClassic)
    )
    val req = peer.manual.expectMessageType[SNAP.GetTrieNodes](10.seconds)
    walkEnding.await(10, TimeUnit.SECONDS) shouldBe true
    peer.answerFrom(req, trie.served)
    flushed.await(10, TimeUnit.SECONDS) shouldBe true
    // Two round trips: the flush's end reaches the coordinator just after its write, and it re-checks from there.
    coordinator ! TrieNodeHealingCoordinator.HealingGetProgress(progress.ref)
    progress.receiveMessage(10.seconds)
    coordinator ! TrieNodeHealingCoordinator.HealingGetProgress(progress.ref)
    progress.receiveMessage(10.seconds)
    cleanRoot.get() shouldBe null
    letWalkEnd.countDown()
    awaitComplete(controller)
    cleanRoot.get() shouldBe trie.root.hash
    testKit.stop(coordinator)
  }

  // spec 022 review beacon 2: real account leaves always decode; one that does not has a storage trie nobody looked at.
  it should "complete as before, but prove nothing, after a walk that could not read an account leaf" taggedAs UnitTest in {
    val unreadable = HealRequestFixtures.plainLeaf(Array[Byte](0x0c), "s022/review/beacon-2-unreadable")
    val trie = PathHealPresenceFixtures.Trie(
      Seq(
        PathHealPresenceFixtures.Placed("root", None, Array.empty[Byte], HealRequestFixtures.branch(0 -> unreadable)),
        PathHealPresenceFixtures.Placed("leaf", None, Array[Byte](0), unreadable)
      )
    )
    val cleanRoot = new AtomicReference[ByteString](otherRoot)
    val (coordinator, controller) =
      spawnHealer(trie, trie.nodes.map(_.label).toSet, new ScriptedPeer(testKit), cleanRoot)
    coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(trie.root.hash)
    awaitComplete(controller)
    withClue("no proof, and an earlier one does not stand either: ")(cleanRoot.get() shouldBe null)
    testKit.stop(coordinator)
  }

  it should "say nothing at a force-complete, which walks nothing" taggedAs UnitTest in {
    val trie = PathHealPresenceFixtures.accountTrieOld()
    val peer = new ScriptedPeer(testKit)
    val cleanRoot = new AtomicReference[ByteString]()
    val (coordinator, controller) = spawnHealer(trie, Set.empty, peer, cleanRoot)
    coordinator ! TrieNodeHealingCoordinator.HealingForceComplete
    // spec 022 T047: it abandons now on Hash too, since the controller's walk it relied on no longer runs.
    controller.fishForMessage(30.seconds) {
      case StateHealingAbandoned => FishingOutcomes.complete
      case _                     => FishingOutcomes.continueAndIgnore
    }
    cleanRoot.get() shouldBe null
  }

  // ---- T040: CF `g` is cleared once, and nothing writes to it after that ------------------------------------------------

  private def entry(seed: Int): ByteString = ByteString(Array.fill[Byte](32)(seed.toByte))

  private def filled(source: DataSource): HealingFrontierStorage =
    val store = new HealingFrontierStorage(source)
    store.update(Nil, Seq(entry(1) -> Seq(root), entry(0xff) -> Seq(root))).commit()
    store.markSubtreeComplete(entry(2))
    store.markSubtreeComplete(ByteString(Array.fill[Byte](32)(0xff.toByte)))
    store.markComplete()
    store

  private def holdsNothing(store: HealingFrontierStorage): Unit =
    store.loadAll() shouldBe empty
    store.isComplete shouldBe false
    store.isSubtreeComplete(entry(2)) shouldBe false
    store.isSubtreeComplete(ByteString(Array.fill[Byte](32)(0xff.toByte))) shouldBe false

  "The persisted frontier" should "be cleared with one range delete on the first start of the new version, and not again" taggedAs UnitTest in {
    val source = new Recording
    val appState = new AppStateStorage(source)
    val store = filled(source)
    source.forget()

    HealInvariant.retireFrontierOnce(appState, store) shouldBe true
    source.rangeDeletes.toSeq shouldBe Seq(FrontierNs)
    holdsNothing(store)
    appState.isHealingFrontierRetired() shouldBe true
    withClue("every other write is the synced key: ") {
      source.batches.toSeq shouldBe Seq((true, Seq(Op(AppNs, delete = false, key(Keys.SnapHealFrontierRetired)))))
    }

    source.forget()
    HealInvariant.retireFrontierOnce(appState, store) shouldBe false
    source.rangeDeletes shouldBe empty
    source.batches shouldBe empty
  }

  it should "be cleared again by the next start after a crash before the key was written" taggedAs UnitTest in {
    val source = new Recording
    val appState = new AppStateStorage(source)
    val store = filled(source)
    source.failSynced = true
    intercept[RuntimeException](HealInvariant.retireFrontierOnce(appState, store))
    appState.isHealingFrontierRetired() shouldBe false

    source.failSynced = false
    source.forget()
    HealInvariant.retireFrontierOnce(appState, store) shouldBe true
    source.rangeDeletes.toSeq shouldBe Seq(FrontierNs)
    holdsNothing(store)
    appState.isHealingFrontierRetired() shouldBe true
  }

  it should "stay unmarked if the clear itself fails, so the key never says more than happened" taggedAs UnitTest in {
    val source = new Recording
    val appState = new AppStateStorage(source)
    val store = filled(source)
    source.failRangeDelete = true
    intercept[RuntimeException](HealInvariant.retireFrontierOnce(appState, store))
    appState.isHealingFrontierRetired() shouldBe false
    store.isComplete shouldBe true
  }

  private def rocksConfig(dbPath: String): RocksDbConfig = new RocksDbConfig:
    override val createIfMissing: Boolean = true
    override val paranoidChecks: Boolean = true
    override val path: String = dbPath
    override val maxThreads: Int = 1
    override val maxOpenFiles: Int = 32
    override val verifyChecksums: Boolean = true
    override val levelCompaction: Boolean = true
    override val blockSize: Long = 16384
    override val blockCacheSize: Long = 33554432

  it should "be cleared, and stay cleared across a reopen, on real RocksDB: every key kind, high bytes included, and no other family" taggedAs UnitTest in {
    val dbPath = Files.createTempDirectory("heal-invariant-rocksdb").toAbsolutePath.toString
    def open(): RocksDbDataSource = RocksDbDataSource(rocksConfig(dbPath), Namespaces.nsSeq)
    val first = open()
    try
      val appState = new AppStateStorage(first)
      appState.putSnapSyncPivotBlock(BigInt(7)).commit()
      val store = filled(first)
      store.loadAll().map(_._1).toSet shouldBe Set(entry(1), entry(0xff))
      HealInvariant.retireFrontierOnce(appState, store) shouldBe true
      holdsNothing(store)
    finally first.close()
    val second = open()
    try
      val appState = new AppStateStorage(second)
      holdsNothing(new HealingFrontierStorage(second))
      appState.isHealingFrontierRetired() shouldBe true
      appState.getSnapSyncPivotBlock() shouldBe Some(BigInt(7))
    finally second.destroy()
  }

  it should "keep `g` listed, since RocksDB will not open a database with an unlisted column family" taggedAs UnitTest in {
    Namespaces.nsSeq should contain(FrontierNs)
  }

  /** A whole heal through the production spawn, every setting of the retired persistence on: a walk finds a hole, it is
    * healed and flushed, a clean walk ends it, and a re-peg follows. None of that may reach CF `g`.
    */
  private def healWritesNothingToFrontier(scheme: StorageScheme): Unit =
    val source = new Recording
    val store = new HealingFrontierStorage(source)
    val trie = PathHealPresenceFixtures.accountTrieOld()
    val after = PathHealPresenceFixtures.accountTrieNew()
    val peer = new ScriptedPeer(testKit)
    val controller = createTestProbe[Command]()
    val mpt = new TrieStoreSpy
    val pns = new PathNodeStorage(new EphemDataSource(Map.empty))
    val hole = "leafY0"
    trie.nodes.filterNot(_.label == hole).foreach { n =>
      if scheme == StorageScheme.Hash then mpt.seed(Seq(n.hash -> n.encoded.toArray))
      else pns.writeNodesWithDeletes(Seq((n.accountHash, n.nibbles, n.encoded.toArray)), Nil)
    }
    val ingest = HealingTrieFixtures.healingIngest(
      Some(store),
      pathNodeStore = Option.when(scheme == StorageScheme.Path)(pns)
    )
    val coordinator = testKit.spawn(
      new ChildFactories {}.trieNodeHealingCoordinator(
        stateRoot = trie.root.hash,
        networkPeerManager = peer.ref,
        requestTracker = new SNAPRequestTracker()(classicSystem.scheduler),
        mptStorage = mpt,
        batchSize = 16,
        snapSyncController = controller.ref,
        stateIngest = ingest,
        storageScheme = scheme,
        movingRootDeltaHeal = true,
        scopedHealVerification = true,
        prunedHealVerification = true,
        frontierPersistenceEnabled = true
      )
    )
    peer.coordinator.set(coordinator)
    source.forget()
    coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(trie.root.hash)
    coordinator ! TrieNodeHealingCoordinator.HealingPeerAvailable(
      PeerTestHelpers.createTestPeer("frontier-peer", createTestProbe[Any]().ref.toClassic)
    )
    peer.answerFrom(peer.manual.expectMessageType[SNAP.GetTrieNodes](30.seconds), trie.served)
    awaitComplete(controller)
    coordinator ! TrieNodeHealingCoordinator.HealingPivotRefreshed(after.root.hash)
    val probe = createTestProbe[actors.HealingStatistics]()
    coordinator ! TrieNodeHealingCoordinator.HealingGetProgress(probe.ref)
    probe.receiveMessage(30.seconds)
    source.frontierWrites shouldBe empty
    // spec 022 review beacon: `HealInvariant.canHold` says the Path scheme holds under in-memory pruning because its heal
    // writes round the node cache, which is what this store is under that mode.
    if scheme == StorageScheme.Path then
      withClue("a Path heal writes nothing through the trie store: ")(mpt.writes shouldBe 0)
    testKit.stop(coordinator)

  // spec 022 T050a, hunter N3: what the old incarnation held was never on disk, so top-down discovery from the root finds
  // it again as absent, and nothing it wrote can be taken for what it did not.
  private def restartedOverHeldNodes(scheme: StorageScheme): Unit =
    val trie = PathHealPresenceFixtures.accountTrieOld()
    val peer = new ScriptedPeer(testKit)
    val controller = createTestProbe[Command]()
    val mpt = new TestMptStorage()
    val pns = new PathNodeStorage(new EphemDataSource(Map.empty))
    val coordinator = testKit.spawn(
      Behaviors
        .supervise(
          new ChildFactories {}.trieNodeHealingCoordinator(
            stateRoot = trie.root.hash,
            networkPeerManager = peer.ref,
            requestTracker = new SNAPRequestTracker()(classicSystem.scheduler),
            mptStorage = mpt,
            batchSize = 16,
            snapSyncController = controller.ref,
            stateIngest = HealingTrieFixtures.healingIngest(
              None,
              pathNodeStore = Option.when(scheme == StorageScheme.Path)(pns)
            ),
            storageScheme = scheme,
            movingRootDeltaHeal = true,
            currentRoot = Some(new AtomicReference(trie.root.hash))
          )
        )
        .onFailure[Throwable](org.apache.pekko.actor.typed.SupervisorStrategy.restart)
    )
    peer.coordinator.set(coordinator)
    val p = PeerTestHelpers.createTestPeer("n3-held-peer", createTestProbe[Any]().ref.toClassic)
    coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(trie.root.hash)
    coordinator ! TrieNodeHealingCoordinator.HealingPeerAvailable(p)
    val rootRequest = peer.manual.expectMessageType[SNAP.GetTrieNodes](30.seconds)
    rootRequest.paths shouldBe Seq(trie("root").pathset)
    peer.answerFrom(rootRequest, trie.served) // the root is held now, waiting on its children
    peer.manual.expectMessageType[SNAP.GetTrieNodes](30.seconds) // and they are out, unanswered

    peer.serveFrom.set(Some(trie.served))
    coordinator ! TrieNodeHealingCoordinator.QueueMissingNodes(null) // the arm throws: a supervisor restart
    coordinator ! TrieNodeHealingCoordinator.HealingPeerAvailable(p) // what the 1-second tick sends the new incarnation
    awaitComplete(controller)
    withClue("the new incarnation asked for the root again: ") {
      peer.autoRequests.flatten should contain(trie("root").pathset)
    }
    trie.nodes.foreach { n =>
      withClue(s"${n.label} is on disk: ") {
        if scheme == StorageScheme.Hash then mpt.get(n.hash.toArray).hash.toSeq shouldBe n.hash.toSeq
        else pns.readAccountNode(n.nibbles).map(ByteString(_)) shouldBe Some(ByteString(n.encoded.toArray))
      }
    }
    testKit.stop(coordinator)

  "A restarted healing coordinator" should "find again what the old incarnation held, and complete, under the Hash scheme (N3)" taggedAs UnitTest in {
    restartedOverHeldNodes(StorageScheme.Hash)
  }

  it should "find again what the old incarnation held, and complete, under the Path scheme (N3)" taggedAs UnitTest in {
    restartedOverHeldNodes(StorageScheme.Path)
  }

  it should "get no write from a whole heal, under the Hash scheme" taggedAs UnitTest in {
    healWritesNothingToFrontier(StorageScheme.Hash)
  }

  it should "get no write from a whole heal, under the Path scheme" taggedAs UnitTest in {
    healWritesNothingToFrontier(StorageScheme.Path)
  }

  it should "get no write from a coordinator spawn under in-memory pruning (the old spawn-time clear)" taggedAs UnitTest in {
    val h = new Harness()
    h.module.trieNodesCachedInMemory = true
    h.ready()
    h.module.frontierWrites.clear()
    h.call(_.startStateHealing())
    h.module.trieNodeHealingCoordinator shouldBe defined
    h.module.frontierWrites shouldBe empty
  }

  // ---- the stamp and the import key: every step of a crash ---------------------------------------------------------

  it should "not be what a crash can tear: stamp and key are one batch, as are the import's start and the marker's removal" taggedAs UnitTest in {
    val (source, appState) = created()
    appState.beginCheckpointImport(root, 1L).commitSync()
    source.batches.toSeq.map(_._1) shouldBe Seq(true)
    source.batches.head._2.toSet shouldBe Set(
      Op(AppNs, delete = true, key(Keys.SnapHealInvariant)),
      Op(AppNs, delete = false, key(Keys.CheckpointImportInProgress))
    ) ++ auditForgotten
    source.forget()
    appState.stampSnapHealInvariant().commitSync()
    source.batches.toSeq.map(_._1) shouldBe Seq(true)
    source.batches.head._2.toSet shouldBe Set(
      Op(AppNs, delete = true, key(Keys.CheckpointImportInProgress)),
      Op(AppNs, delete = false, key(Keys.SnapHealInvariant))
    )
  }

  it should "survive a reopen as it was committed, on real RocksDB, at each step of an import that starts on a marked datadir" taggedAs UnitTest in {
    val dbPath = Files.createTempDirectory("heal-invariant-steps").toAbsolutePath.toString
    def open(): RocksDbDataSource = RocksDbDataSource(rocksConfig(dbPath), Namespaces.nsSeq)
    def step(check: AppStateStorage => Unit)(act: AppStateStorage => Unit): Unit =
      val source = open()
      try
        val appState = new AppStateStorage(source)
        act(appState)
      finally source.close()
      val reopened = open()
      try check(new AppStateStorage(reopened))
      finally reopened.close()

    step { a =>
      a.hasSnapHealInvariant() shouldBe true
      a.getCheckpointImportInProgress() shouldBe None
    }(_.stampSnapHealInvariant().commitSync())
    step { a =>
      a.hasSnapHealInvariant() shouldBe false
      a.getCheckpointImportInProgress() shouldBe defined
    }(_.beginCheckpointImport(root, 5L).commitSync())
    step { a =>
      a.hasSnapHealInvariant() shouldBe true
      a.getCheckpointImportInProgress() shouldBe None
    }(_.stampSnapHealInvariant().commitSync())
    val last = open()
    last.destroy()
  }

/** spec 022 T039, T043, end to end on a real controller: what a SNAP start and a finalisation do with the marker. */
class InvariantMarkerControllerSpec
    extends ScalaTestWithActorTestKit()
    with AnyFlatSpecLike
    with Matchers
    with Eventually:

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = scaled(Span(15, Seconds)), interval = scaled(Span(50, Millis)))

  private val pivot = BigInt(10_000)

  private class Fixture(pruning: Option[PruningMode] = None) extends EphemBlockchainTestSetup with TestSyncConfig:
    implicit override lazy val classicSystem: org.apache.pekko.actor.ActorSystem =
      InvariantMarkerControllerSpec.this.system.classicSystem

    private val stores = pruning match
      case None => storagesInstance
      case Some(mode) =>
        new EphemDataSourceComponent
          with LocalPruningConfigBuilder
          with CheckpointImporterSpec.FixedPruningMode(mode)
          with Storages.DefaultStorages
          with com.chipprbots.ethereum.TestInstanceConfigProvider
    val appState: AppStateStorage = stores.storages.appStateStorage
    private val reader = BlockchainReader(stores.storages)
    private val writer = BlockchainWriter(stores.storages)
    val parent: TypedTestProbe[SyncProtocol.SyncControllerReply] =
      testKit.createTestProbe[SyncProtocol.SyncControllerReply]()

    private val polled = new AtomicInteger(0)
    private val networkPeerManager: TestProbe = TestProbe()
    networkPeerManager.setAutoPilot(
      new AutoPilot:
        override def run(sender: org.apache.pekko.actor.ActorRef, msg: Any): AutoPilot =
          msg.asMatchable match
            case GetHandshakedPeersCmd(replyTo) =>
              replyTo ! HandshakedPeers(Map.empty)
              polled.incrementAndGet()
            case _ => ()
          this
    )

    def storeState(trie: PathHealPresenceFixtures.Trie): Unit =
      stores.storages.stateStorage
        .getBackingStorage(pivot)
        .storeRawNodes(trie.nodes.map(n => n.hash -> n.encoded.toArray))

    /** Everything a run left behind before it reached healing, on `root`. */
    def resumableAt(root: ByteString): Unit =
      val genesis = Fixtures.Blocks.Genesis.header
      writer.save(
        Block(genesis, BlockBody.empty),
        Nil,
        ChainWeight.totalDifficultyOnly(genesis.difficulty.value),
        saveAsBestBlock = true
      )
      val header = genesis.copy(number = BlockNumber(pivot), stateRoot = TrieRoot(root))
      writer.storeBlock(Block(header, BlockBody.empty)).commit()
      appState
        .putSnapSyncAccountsComplete(true)
        .and(appState.putSnapSyncStorageComplete(true))
        .and(appState.putSnapSyncBytecodeComplete(true))
        .and(appState.putSnapSyncPivotBlock(pivot))
        .and(appState.putSnapSyncStateRoot(root))
        .commit()

    def spawn(): TypedActorRef[SNAPSyncController.Command] =
      given ExecutionContext = system.executionContext
      val snap = testKit.spawn(
        SNAPSyncController(
          reader,
          writer,
          appState,
          stores.storages.stateStorage,
          stores.storages.evmCodeStorage,
          stores.storages.flatSlotStorage,
          networkPeerManager.ref.toTyped[NetworkPeerManagerActor.Command],
          testKit.spawn(PeerEventBusActor.behavior()),
          syncConfig,
          SNAPSyncConfig(deferredMerkleization = false, movingRootDeltaHeal = true),
          system.classicSystem.scheduler,
          CacheBasedBlacklist.empty(100),
          parent.ref
        )
      )
      eventually(polled.get() should be >= 1)
      snap

    def awaitFinalized(): Unit =
      parent.fishForMessage(30.seconds) {
        case SNAPSyncController.SnapSyncFinalized(p) if p == pivot => FishingOutcomes.complete
        case SyncProtocol.HealingImpossible                        => FishingOutcomes.fail("finalisation aborted")
        case _                                                     => FishingOutcomes.continueAndIgnore
      }
      ()

  "A SNAP start" should "stamp a datadir this binary created, on which nothing has happened" taggedAs UnitTest in new Fixture:
    appState.markDatadirCreatedUnderInvariant().commit()
    spawn()
    appState.hasSnapHealInvariant() shouldBe true

  it should "not stamp a datadir with heal progress: only the repair pass may" taggedAs UnitTest in new Fixture:
    appState.markDatadirCreatedUnderInvariant().commit()
    resumableAt(ByteString(Array.fill[Byte](32)(0x11)))
    spawn()
    appState.hasSnapHealInvariant() shouldBe false

  it should "clear a marker it finds under in-memory pruning, on the Hash scheme, and stamp nothing" taggedAs UnitTest in new Fixture(
    Some(InMemoryPruning(10))
  ):
    appState.markDatadirCreatedUnderInvariant().and(appState.stampSnapHealInvariant()).commit()
    spawn()
    appState.hasSnapHealInvariant() shouldBe false

  "A clean heal" should "repair a pre-invariant datadir once and leave the marker: walk, stamp, finalise (T039)" taggedAs UnitTest in new Fixture:
    val trie = PathHealPresenceFixtures.accountTrieOld()
    storeState(trie)
    resumableAt(trie.root.hash)
    val snap = spawn()
    appState.hasSnapHealInvariant() shouldBe false
    snap ! SNAPSyncController.Start
    awaitFinalized()
    appState.isSnapSyncDone() shouldBe true
    appState.hasSnapHealInvariant() shouldBe true

  "A lazy hand-off" should "take away a marker it finds: what regular sync fetches on demand breaks the rule (T043)" taggedAs UnitTest in new Fixture:
    val root = ByteString(Array.fill[Byte](32)(0x11))
    appState.stampSnapHealInvariant().commit()
    resumableAt(root)
    val snap = spawn()
    appState.hasSnapHealInvariant() shouldBe true
    snap ! SNAPSyncController.Start
    snap ! SNAPSyncController.HealingRootUnservable(root)
    awaitFinalized()
    appState.isSnapSyncDone() shouldBe true
    appState.hasSnapHealInvariant() shouldBe false
