package com.chipprbots.ethereum.blockchain.checkpoint

import org.apache.pekko.util.ByteString

import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.blockchain.checkpoint.CheckpointImporter.AlreadyRefused
import com.chipprbots.ethereum.blockchain.checkpoint.CheckpointImporter.BadFormat
import com.chipprbots.ethereum.blockchain.checkpoint.CheckpointUnifiedKit.*
import com.chipprbots.ethereum.blockchain.checkpoint.CheckpointV2ImportKit.importInto
import com.chipprbots.ethereum.blockchain.checkpoint.CheckpointV2ImportKit.positionsOf
import com.chipprbots.ethereum.blockchain.checkpoint.CheckpointV2ImportKit.trustOf
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.pathstate.BlockRef
import com.chipprbots.ethereum.domain.appstate.BlockInfo
import com.chipprbots.ethereum.testing.Tags.UnitTest

/** A refused or killed checkpoint import is recorded by its archive's id and not run again (spec 018 C5c PR3, T291; the
  * user's decision 4): the rows are cleared, the node syncs by SNAP from a clean state, and only a new archive or the
  * operator's flag imports.
  */
class CheckpointImportRetrySpec extends AnyWordSpec with Matchers with EitherValues:
  import CheckpointV2Fixtures.*

  private val (chainA, tipA) = streamAChain()
  private val bytesA: Array[Byte] = CheckpointV2GoldenStreamSpec.golden("a")
  private val trustA = trustOf(chainA, 61, tipA)
  private val genesisA = chainA.reader.genesisHeader
  private val tipRef = BlockRef(tipA.hash.value, tipA.number.value, tipA.stateRoot.value)
  private val idA: ByteString = CheckpointArchive.archiveIdOf(decode(bytesA)._1)

  /** The archive with a node spoiled: a content fault, found after the nodes above it were written. (A failed checksum
    * is not one: B-384, [[CheckpointImportNoRetryContentSpec]].)
    */
  private def badCrc: Array[Byte] =
    val (header, entries) = decode(bytesA)
    var nodes = 0
    val changed = entries.map {
      case CheckpointArchive.NodeEntry(b) =>
        nodes += 1
        if nodes == 2 then CheckpointArchive.NodeEntry(b.updated(b.length - 1, (b.last ^ 1).toByte))
        else CheckpointArchive.NodeEntry(b)
      case other => other
    }
    CheckpointV2ImportKit.Raw.rebuild(header, changed)

  private def fresh(batch: Int = CheckpointImporter.NodeBatchSize): Datadir =
    val d = new Datadir(new Faulty, genesisA, batch)
    d.commitGenesis()
    d.source.writes.clear()
    d

  private val SnapProgress = "SnapSyncProgress"

  "A refused archive" should {

    "have its rows cleared through the reset mark, be recorded by its id, and leave the datadir clean for SNAP" taggedAs UnitTest in {
      val d = fresh()
      // the archive's own id, which the exporter's header block names: a spoiled node is the archive's fault
      d.importBytes(badCrc, trustA).left.value shouldBe a[BadFormat]
      d.appState.getRefusedCheckpointArchives() shouldBe Seq(idA)
      d.rows.isEmpty shouldBe true
      d.state.anchor shouldBe None
      d.appState.isStateResetPending() shouldBe false
      d.appState.getCheckpointImportInProgress() shouldBe None
      d.appState.isSnapSyncDone() shouldBe false
      d.appState.getBestBlockInfo() shouldBe BlockInfo(d.genesisRef.hash, 0)
    }

    "not be imported again on a restart, and a SNAP run in progress is not wiped" taggedAs UnitTest in {
      val d = fresh()
      d.importBytes(badCrc, trustA).isLeft shouldBe true
      // SNAP starts from the clean state, and has written some rows and its progress when the node restarts
      d.rows.writeAccountNode(Array[Byte](4), Array[Byte](1, 2, 3))
      d.appState.dataSource.update(
        Seq(
          com.chipprbots.ethereum.db.dataSource.DataSourceUpdate(
            AppNs,
            Nil,
            Seq(keyOf(SnapProgress) -> keyOf("some progress"))
          )
        )
      )
      val restarted = new Datadir(d.source, genesisA)
      CheckpointImporter.recoverInterrupted(restarted.appState, Some(restarted.target)) shouldBe false
      val before = snapshot(d.source)
      val writes = d.source.writes.size

      // the same archive, now valid, as the node reads it again at its start
      restarted.importBytes(bytesA, trustA).left.value shouldBe AlreadyRefused(idA)
      snapshot(d.source).keySet shouldBe before.keySet
      d.source.writes.size shouldBe writes
      restarted.rows.readAccountNode(Array[Byte](4)).map(_.toSeq) shouldBe Some(Seq[Byte](1, 2, 3))
      restarted.appState.getBestBlockInfo() shouldBe BlockInfo(d.genesisRef.hash, 0)
    }

    "be imported when the operator's flag is set, and be forgotten once it works" taggedAs UnitTest in {
      val d = fresh()
      d.importBytes(badCrc, trustA).isLeft shouldBe true
      d.importBytes(bytesA, trustA).left.value shouldBe AlreadyRefused(idA)
      d.importBytes(bytesA, trustA, retryRefused = true).value.blockNumber shouldBe tipA.number.value
      d.state.anchor shouldBe Some(tipRef)
      d.appState.getRefusedCheckpointArchives() shouldBe empty
      d.stored shouldBe positionsOf(chainA, tipA.stateRoot.value)
    }

    "be recorded again, not cleared, when the retry fails too" taggedAs UnitTest in {
      val d = fresh()
      d.importBytes(badCrc, trustA).isLeft shouldBe true
      d.importBytes(badCrc, trustA, retryRefused = true).left.value shouldBe a[BadFormat]
      d.appState.getRefusedCheckpointArchives() shouldBe Seq(idA)
      d.rows.isEmpty shouldBe true
    }

    "not stop a new archive: only the refused one is skipped" taggedAs UnitTest in {
      val (chainB, tipB) = streamBChain()
      val d = new Datadir(new Faulty, chainB.reader.genesisHeader)
      d.commitGenesis()
      d.appState.recordRefusedCheckpoint(idA).commitSync()
      d.importBytes(CheckpointV2GoldenStreamSpec.golden("b"), trustOf(chainB, StreamBChainId, tipB, Some(0))).value
      d.state.anchor.map(_.number) shouldBe Some(tipB.number.value)
      d.appState.getRefusedCheckpointArchives() shouldBe Seq(idA)
    }

    "be recorded on the hash layout too, and not retried there" taggedAs UnitTest in {
      val (source, tip) = streamAChain()
      val target = new Chain
      val trust = trustOf(source, 61, tip)
      importInto(target, badCrc, trust).left.value shouldBe a[BadFormat]
      target.storages.storages.appStateStorage.getRefusedCheckpointArchives() shouldBe Seq(idA)
      importInto(target, bytesA, trust).left.value shouldBe AlreadyRefused(idA)
      target.storages.storages.appStateStorage.isSnapSyncDone() shouldBe false
    }
  }

  "A killed import" should {

    "be recorded as refused at the next start, its rows cleared, and not run again" taggedAs UnitTest in {
      val d = fresh(batch = 2)
      // die before the second batch of rows
      val probe = fresh(batch = 2)
      probe.importBytes(bytesA, trustA, smallBatches = true).value
      val secondRows =
        probe.source.writes.zipWithIndex.filter(w => w._1._2.exists(_.namespace == StateRows)).map(_._2)(1)
      d.source.killBefore = Some(secondRows)
      intercept[Killed](d.importBytes(bytesA, trustA, smallBatches = true))
      d.source.killBefore = None
      withClue("rows are on the disk, with the key naming the archive: ") {
        d.rows.isEmpty shouldBe false
        d.appState.getCheckpointImportArchive() shouldBe Some(idA)
        d.appState.getRefusedCheckpointArchives() shouldBe empty
      }

      val restarted = new Datadir(d.source, genesisA)
      CheckpointImporter.recoverInterrupted(restarted.appState, Some(restarted.target)) shouldBe true
      restarted.appState.getRefusedCheckpointArchives() shouldBe Seq(idA)
      restarted.rows.isEmpty shouldBe true
      restarted.appState.getCheckpointImportInProgress() shouldBe None
      restarted.state.anchor shouldBe None
      restarted.appState.isStateResetPending() shouldBe false
      restarted.appState.getBestBlockInfo() shouldBe BlockInfo(d.genesisRef.hash, 0)
      // a second start finds nothing to do
      CheckpointImporter.recoverInterrupted(restarted.appState, Some(restarted.target)) shouldBe false
      restarted.importBytes(bytesA, trustA).left.value shouldBe AlreadyRefused(idA)
    }

    "keep its key naming the archive on the hash layout, and record it" taggedAs UnitTest in {
      val target = new Chain
      val s = target.storages.storages
      s.appStateStorage.beginCheckpointImport(ByteString(Array.fill[Byte](32)(1)), 5L, Some(idA)).commitSync()
      CheckpointImporter.recoverInterrupted(s.appStateStorage, None) shouldBe true
      s.appStateStorage.getRefusedCheckpointArchives() shouldBe Seq(idA)
      s.appStateStorage.getCheckpointImportInProgress() shouldBe defined
    }

    "record nothing when the key is one an earlier release wrote, which names no archive" taggedAs UnitTest in {
      val d = fresh()
      d.appState.beginCheckpointImport(ByteString(Array.fill[Byte](32)(1)), 5L).commitSync()
      d.appState.getCheckpointImportArchive() shouldBe None
      CheckpointImporter.recoverInterrupted(d.appState, Some(d.target)) shouldBe true
      d.appState.getRefusedCheckpointArchives() shouldBe empty
      d.appState.getCheckpointImportInProgress() shouldBe None
    }
  }

  "The record of refused archives" should {
    "keep the last 32, and forget one" taggedAs UnitTest in {
      val d = fresh()
      def id(n: Int): ByteString = ByteString(Array.fill[Byte](32)(n.toByte))
      (1 to 40).foreach(n => d.appState.recordRefusedCheckpoint(id(n)).commitSync())
      val kept = d.appState.getRefusedCheckpointArchives()
      kept.size shouldBe AppStateStorage.MaxRefusedCheckpointArchives
      kept.head shouldBe id(9)
      kept.last shouldBe id(40)
      d.appState.forgetRefusedCheckpoint(id(40)).commitSync()
      d.appState.isCheckpointArchiveRefused(id(40)) shouldBe false
      d.appState.isCheckpointArchiveRefused(id(39)) shouldBe true
    }

    "survive the clear that follows a refusal" taggedAs UnitTest in {
      val d = fresh()
      d.appState.recordRefusedCheckpoint(idA).commitSync()
      d.target.clearForImport(force = true).value
      d.appState.isCheckpointArchiveRefused(idA) shouldBe true
    }

  }
