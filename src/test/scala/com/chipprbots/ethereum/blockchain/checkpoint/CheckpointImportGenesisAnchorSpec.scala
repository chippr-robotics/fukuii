package com.chipprbots.ethereum.blockchain.checkpoint

import org.apache.pekko.util.ByteString

import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.blockchain.checkpoint.CheckpointUnifiedKit.*
import com.chipprbots.ethereum.blockchain.checkpoint.CheckpointV2ImportKit.positionsOf
import com.chipprbots.ethereum.blockchain.checkpoint.CheckpointV2ImportKit.trustOf
import com.chipprbots.ethereum.blockchain.sync.snap.actors.healing.HealRequestFixtures.Op
import com.chipprbots.ethereum.db.storage.AppStateStorage.Keys
import com.chipprbots.ethereum.db.storage.Namespaces
import com.chipprbots.ethereum.db.storage.pathstate.BlockRef
import com.chipprbots.ethereum.db.storage.pathstate.StateResetPending
import com.chipprbots.ethereum.domain.appstate.BlockInfo
import com.chipprbots.ethereum.testing.Tags.UnitTest

/** The write order of a checkpoint import on the unified layout (spec 018 C5c PR3, T225; data-model E.1, "Atomicity and
  * failure order"), and what a kill at each write leaves.
  */
class CheckpointImportGenesisAnchorSpec extends AnyWordSpec with Matchers with EitherValues:
  import CheckpointV2Fixtures.*

  private val (chainA, tipA) = streamAChain()
  private val bytesA: Array[Byte] = CheckpointV2GoldenStreamSpec.golden("a")
  private val trustA = trustOf(chainA, 61, tipA)
  private val genesisA = chainA.reader.genesisHeader
  private val expectedA = positionsOf(chainA, tipA.stateRoot.value)
  private val tipRef = BlockRef(tipA.hash.value, tipA.number.value, tipA.stateRoot.value)

  private def fresh(batch: Int = CheckpointImporter.NodeBatchSize): Datadir =
    val d = new Datadir(new Faulty, genesisA, batch)
    d.commitGenesis()
    d.source.writes.clear()
    d

  private def put(key: String) = Op(AppNs, delete = false, keyOf(key))
  private def del(key: String) = Op(AppNs, delete = true, keyOf(key))
  private def indexOfWrite(d: Datadir)(p: ((String, Seq[Op])) => Boolean): Int = d.source.writes.indexWhere(p)
  private def writing(op: Op)(w: (String, Seq[Op])): Boolean = w._2.contains(op)
  private def writingRows(w: (String, Seq[Op])): Boolean = w._2.exists(o => o.namespace == StateRows && !o.delete)

  "A checkpoint import on the unified layout" should {

    "leave the datadir untouched on every header-level refusal, but for the record of the archive" taggedAs UnitTest in {
      val wrongChain = trustA.copy(chainId = 62)
      val wrongGenesis = trustA.copy(genesisHash = ByteString(Array.fill[Byte](32)(7)))
      val wrongBlock = trustA.copy(expectedBlockHash = Some(ByteString(Array.fill[Byte](32)(9))))
      val streamC = CheckpointV2GoldenStreamSpec.golden("c")
      Seq(("chain id", bytesA, wrongChain), ("genesis", bytesA, wrongGenesis), ("expected block", bytesA, wrongBlock))
        .appended(("window", streamC, trustA))
        .foreach { (what, bytes, trust) =>
          withClue(s"$what: ") {
            val d = fresh()
            val before = snapshot(d.source)
            d.importBytes(bytes, trust).isLeft shouldBe true
            val after = snapshot(d.source)
            val changed =
              (after.keySet ++ before.keySet).filter(k => before.get(k).map(_.toSeq) != after.get(k).map(_.toSeq))
            // B-384: a wrong expected hash may be the operator's config, so it is the one refusal here not recorded
            withClue("only the record of the refused archive is new, and none for the expected hash: ") {
              changed.map(k => new String(k.array(), "UTF-8")).toSeq.map(_.drop(1)) shouldBe
                (if what == "expected block" then Seq.empty else Seq(Keys.CheckpointImportRefused))
            }
            d.state.anchor shouldBe Some(d.genesisRef)
            d.rows.isEmpty shouldBe true
          }
        }
    }

    "record nothing and write nothing when no block hash is expected: a configuration error, not the archive's" taggedAs UnitTest in {
      val d = fresh()
      val before = snapshot(d.source)
      d.importBytes(bytesA, trustA.copy(expectedBlockHash = None))
        .left
        .value shouldBe a[CheckpointImporter.NoExpectedBlockHash]
      snapshot(d.source).keySet shouldBe before.keySet
      d.appState.getRefusedCheckpointArchives() shouldBe empty
      d.source.writes shouldBe empty
    }

    "clear a genesis anchor through the reset mark before the import key is set (T225)" taggedAs UnitTest in {
      val d = fresh()
      d.state.anchor shouldBe Some(d.genesisRef)
      d.importBytes(bytesA, trustA).value
      val resetSet = indexOfWrite(d)(writing(put(StateResetPending.Key)))
      val resetEnd = indexOfWrite(d)(writing(del(StateResetPending.Key)))
      val begin = indexOfWrite(d)(writing(put(Keys.CheckpointImportInProgress)))
      withClue(s"writes: ${d.source.writes.map(w => w._1 -> w._2.take(3))}: ") {
        resetSet should be >= 0
        resetEnd should be > resetSet
        begin should be > resetEnd
      }
    }

    "clear a genesis anchor once: a second clear is no write" taggedAs UnitTest in {
      val d = fresh()
      d.target.clearForImport(force = false) shouldBe Right(())
      d.state.anchor shouldBe None
      d.appState.getBestBlockInfo() shouldBe BlockInfo(d.genesisRef.hash, 0)
      val writes = d.source.writes.size
      writes should be > 0
      d.target.clearForImport(force = false) shouldBe Right(())
      d.source.writes.size shouldBe writes
    }

    "refuse a datadir that holds the state of a block above the genesis, and clear nothing" taggedAs UnitTest in {
      val d = fresh()
      val first = d.importBytes(bytesA, trustA).value
      first.blockNumber shouldBe tipA.number.value
      val before = snapshot(d.source)
      d.target.clearForImport(force = true) shouldBe Left(tipRef)
      snapshot(d.source).keySet shouldBe before.keySet
      d.importBytes(bytesA, trustA, retryRefused = true).left.value shouldBe a[CheckpointImporter.DatadirHoldsState]
      d.state.anchor shouldBe Some(tipRef)
    }

    "clear rows with no anchor, from an earlier run, through the mark before the key is set" taggedAs UnitTest in {
      val d = fresh()
      d.target.clearForImport(force = false).value
      d.rows.writeAccountNode(Array[Byte](1, 2), Array[Byte](9, 9, 9))
      d.rows.writeStorageNode(ByteString(Array.fill[Byte](32)(5)), Array[Byte](3), Array[Byte](8))
      d.source.writes.clear()
      d.importBytes(bytesA, trustA).value
      val resetSet = indexOfWrite(d)(writing(put(StateResetPending.Key)))
      val begin = indexOfWrite(d)(writing(put(Keys.CheckpointImportInProgress)))
      resetSet should be >= 0
      begin should be > resetSet
      d.stored shouldBe expectedA
    }

    "set the import key, synced, before the first row, and write the rows with the log" taggedAs UnitTest in {
      val d = fresh(batch = 2)
      d.importBytes(bytesA, trustA, smallBatches = true).value
      val begin = indexOfWrite(d)(writing(put(Keys.CheckpointImportInProgress)))
      val firstRow = indexOfWrite(d)(writingRows)
      begin should be >= 0
      firstRow should be > begin
      d.source.writes(begin)._1 shouldBe "sync"
      val rowWrites = d.source.writes.filter(writingRows)
      withClue(
        "several batches of at most two rows, none beside the log (the data source has no other way to write): "
      ) {
        rowWrites.size should be > 1
        rowWrites.foreach(w => w._2.count(o => o.namespace == StateRows || o.namespace == StorageRows) should be <= 2)
        rowWrites.foreach(_._1 shouldBe "plain")
      }
    }

    "end in one synced batch through commitHandoff: the anchor, state.origin, the window, the weight, the best block, the end of the key" taggedAs UnitTest in {
      val d = fresh()
      d.importBytes(bytesA, trustA).value
      val last = d.source.writes.last
      last._1 shouldBe "sync"
      val ops = last._2.toSet
      withClue(s"final batch: ${last._2}: ") {
        ops should contain(put(Keys.StateAnchor))
        ops should contain(put(Keys.StateOrigin))
        ops should contain(put(Keys.SnapSyncDone))
        ops should contain(del(Keys.CheckpointImportInProgress))
        ops.exists(o => o.namespace == Namespaces.HeaderNamespace && !o.delete) shouldBe true
        ops.exists(o => o.namespace == Namespaces.BodyNamespace && !o.delete) shouldBe true
        ops.exists(o => o.namespace == Namespaces.ChainWeightNamespace && !o.delete) shouldBe true
        ops.exists(o => o.namespace == AppNs && o.key == keyOf(Keys.BestBlockNumber)) shouldBe true
      }
    }
    "leave the state of the archive, at the checkpoint: state.origin, the anchor, the best block, every row" taggedAs UnitTest in {
      val d = fresh()
      d.importBytes(bytesA, trustA).value
      d.state.anchor shouldBe Some(tipRef)
      d.appState.getStateOrigin() shouldBe Some(tipRef)
      d.appState.getBestBlockInfo() shouldBe BlockInfo(tipA.hash.value, tipA.number.value)
      d.appState.isSnapSyncDone() shouldBe true
      d.appState.getCheckpointImportInProgress() shouldBe None
      d.appState.isStateResetPending() shouldBe false
      d.stored shouldBe expectedA
      withClue("two contracts share one storage root: each owner has the whole trie: ") {
        val owners = d.stored.keySet.flatten
        owners.size shouldBe 2
        owners.toSeq.map(o => d.stored(Some(o))).distinct.size shouldBe 1
      }
    }

    "import golden B, chain id wider than 32 bits, onto the layout the same way" taggedAs UnitTest in {
      val (chainB, tipB) = streamBChain()
      val d = new Datadir(new Faulty, chainB.reader.genesisHeader)
      d.commitGenesis()
      d.importBytes(
        CheckpointV2GoldenStreamSpec.golden("b"),
        trustOf(chainB, StreamBChainId, tipB, Some(0))
      ).value
      d.stored shouldBe positionsOf(chainB, tipB.stateRoot.value)
      d.appState.getStateOrigin().map(_.number) shouldBe Some(tipB.number.value)
    }

    "never leave an anchor over part of the rows, whichever write the process dies before (kill at each step)" taggedAs UnitTest in {
      // a complete run, to count its writes
      val whole = fresh(batch = 2)
      whole.importBytes(bytesA, trustA, smallBatches = true).value
      val total = whole.source.writes.size
      total should be > 6
      (0 until total).foreach { k =>
        val d = fresh(batch = 2)
        d.source.killBefore = Some(k)
        withClue(s"killed before write $k (${d.source.writes.size} so far): ") {
          intercept[Killed](d.importBytes(bytesA, trustA, smallBatches = true))
          d.source.killBefore = None
          d.source.writes.clear()

          // what the disk holds at the moment of the kill, before the restart does anything
          val atKill = new Datadir(d.source, genesisA, 2)
          def consistent(label: String, dd: Datadir): Unit =
            withClue(s"$label: ") {
              dd.appState.getStateAnchor() match
                case Some(anchor) if anchor == tipRef =>
                  dd.stored shouldBe expectedA
                  dd.appState.getStateOrigin() shouldBe Some(tipRef)
                  dd.appState.isSnapSyncDone() shouldBe true
                  dd.appState.getCheckpointImportInProgress() shouldBe None
                case Some(anchor) =>
                  anchor shouldBe dd.genesisRef
                  dd.rows.isEmpty shouldBe true
                case None =>
                  if !dd.rows.isEmpty then
                    withClue("rows with no anchor say why: ") {
                      (dd.appState
                        .isStateResetPending() || dd.appState.getCheckpointImportInProgress().isDefined) shouldBe true
                    }
            }
          consistent("at the kill", atKill)

          // the restart, as a node makes it
          val restarted = new Datadir(d.source, genesisA, 2)
          restarted.state.finishPendingClear(Some(genesisA.hash.value))
          CheckpointImporter.recoverInterrupted(restarted.appState, Some(restarted.target))
          consistent("after the restart", restarted)
          if restarted.appState.getStateAnchor().contains(tipRef) then restarted.appState.isSnapSyncDone() shouldBe true
          else
            withClue("a datadir with no state of the archive holds no row, no key and no mark: ") {
              restarted.rows.isEmpty shouldBe true
              restarted.appState.getCheckpointImportInProgress() shouldBe None
              restarted.appState.isStateResetPending() shouldBe false
              restarted.appState.isSnapSyncDone() shouldBe false
            }
        }
      }
    }
  }
