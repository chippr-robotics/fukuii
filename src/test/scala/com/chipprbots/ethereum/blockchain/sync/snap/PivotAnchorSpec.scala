package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.pathstate.BlockRef
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.TrieRoot
import com.chipprbots.ethereum.domain.appstate.BlockInfo
import com.chipprbots.ethereum.testing.Tags.*

/** SNAP finalisation's pivot anchor ([[PivotAnchor]]): the persisted pivot against the header the validated header
  * download holds at the pivot's number, found from the download's recorded tip by parent hash. Real Mordor headers.
  */
class PivotAnchorSpec extends AnyFlatSpec with Matchers:

  private val headers: IndexedSeq[BlockHeader] = PowHeaderFixtures.fromGenesis.take(21) // blocks 0..20
  private val otherRoot = TrieRoot(ByteString(Array.fill(32)(0x5a.toByte)))

  private class Chain extends EphemBlockchainTestSetup:
    val appState: AppStateStorage = storagesInstance.storages.appStateStorage

    /** What the header download leaves after storing blocks 1..upTo: headers with their index entries, cursor, tip. */
    def downloaded(upTo: Int, recordTip: Boolean = true): Unit =
      headers.take(upTo + 1).foreach(h => blockchainWriter.storeBlockHeader(h).commit())
      val cursor = appState.putBackfillBestHeader(upTo)
      (if recordTip then cursor.and(appState.putBackfillBestHeaderHash(headers(upTo).hash.value)) else cursor).commit()

    /** A pivot commit (PivotSelector / PivotRefresher): number, state root and hash in one batch. */
    def committed(pivot: BlockHeader, pinHash: Boolean = true): Unit =
      val batch = appState
        .putSnapSyncPivotBlock(pivot.number.value)
        .and(appState.putSnapSyncStateRoot(pivot.stateRoot.value))
      (if pinHash then batch.and(appState.putSnapSyncPivotHash(pivot.hash.value)) else batch).commit()

    def check(pivot: Int): PivotAnchor.Check = PivotAnchor.check(appState, blockchainReader, BigInt(pivot))

  "PivotAnchor.check" should "anchor a pivot that is the validated chain's header at its number" taggedAs UnitTest in new Chain:
    downloaded(20)
    committed(headers(15))
    check(15) shouldBe PivotAnchor.Check.Anchored(headers(15))

  it should "reject a pivot whose hash differs from the validated chain's, even when the number index names it" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new Chain:
    downloaded(20)
    // A header at 15 on the real 14, with another state root, stored with its index entry after the download stored the
    // real 15 (as the pivot bootstrap and the pivot's best-block record do) and committed as the pivot.
    val altered: BlockHeader = headers(15).copy(stateRoot = otherRoot)
    blockchainWriter.storeBlockHeader(altered).commit()
    committed(altered)
    blockchainReader.getBlockHeaderByNumber(15) shouldBe Some(altered)

    check(15) shouldBe PivotAnchor.Check.Diverged(headers(15), Some(altered.hash.value), Some(otherRoot.value))

  it should "reject a pinned hash that differs even when the state root matches" taggedAs UnitTest in new Chain:
    downloaded(20)
    committed(headers(15))
    // Another header at the pivot's height with the same state root (another extra data, say).
    val sibling: BlockHeader = headers(15).copy(extraData = ByteString("other"))
    blockchainWriter.storeBlockHeader(sibling).commit()
    appState.putSnapSyncPivotHash(sibling.hash.value).commit()
    check(15) shouldBe a[PivotAnchor.Check.Diverged]

  it should "compare a pinned hash that names no stored header" taggedAs UnitTest in new Chain:
    downloaded(20)
    committed(headers(15))
    appState.putSnapSyncPivotHash(ByteString(Array.fill(32)(0x44.toByte))).commit()
    check(15) shouldBe a[PivotAnchor.Check.Diverged]

  it should "ignore a stale pin that names a stored header at another height, and let the state root decide" taggedAs UnitTest in new Chain:
    downloaded(20)
    committed(headers(15))
    appState.putSnapSyncPivotHash(headers(14).hash.value).commit()
    check(15) shouldBe PivotAnchor.Check.Anchored(headers(15))

  it should "take the header the download recorded at its target, with no walk back" taggedAs UnitTest in new Chain:
    // Only blocks 0, 15 and 20 are stored: a walk back from the tip at 20 would stop at 19.
    Seq(headers.head, headers(15), headers(20)).foreach(h => blockchainWriter.storeBlockHeader(h).commit())
    appState
      .putBackfillBestHeader(20)
      .and(appState.putBackfillBestHeaderHash(headers(20).hash.value))
      .and(appState.putBackfillTargetHash(headers(15).hash.value))
      .commit()
    committed(headers(15))
    check(15) shouldBe PivotAnchor.Check.Anchored(headers(15))
    check(14) shouldBe a[PivotAnchor.Check.Undecided] // not the target: the walk back finds 19 missing

  it should "not walk back further than its cap" taggedAs UnitTest in new Chain:
    val farTip: BlockHeader =
      headers(20).copy(number = com.chipprbots.ethereum.domain.BlockNumber(15 + PivotAnchor.MaxWalkBack + 1))
    blockchainWriter.storeBlockHeader(farTip).commit()
    appState
      .putBackfillBestHeader(farTip.number.value)
      .and(appState.putBackfillBestHeaderHash(farTip.hash.value))
      .commit()
    committed(headers(15))
    check(15) match
      case PivotAnchor.Check.Undecided(reason) => reason should include("walk > cap")
      case other                               => fail(s"expected undecided, got $other")

  it should "compare the state root alone for a pivot committed without a hash (a datadir from before the key)" taggedAs UnitTest in new Chain:
    downloaded(20)
    committed(headers(15), pinHash = false)
    check(15) shouldBe PivotAnchor.Check.Anchored(headers(15))

    appState.putSnapSyncStateRoot(otherRoot.value).commit()
    check(15) shouldBe PivotAnchor.Check.Diverged(headers(15), None, Some(otherRoot.value))

  it should "not decide while the download is below the pivot" taggedAs UnitTest in new Chain:
    downloaded(10)
    committed(headers(15))
    check(15) shouldBe a[PivotAnchor.Check.Undecided]

  it should "not decide when the download's tip is not recorded (headers stored before the tip hash existed)" taggedAs UnitTest in new Chain:
    downloaded(20, recordTip = false)
    committed(headers(15))
    check(15) shouldBe a[PivotAnchor.Check.Undecided]

  it should "not decide when the recorded tip names a header at another height" taggedAs UnitTest in new Chain:
    downloaded(20)
    appState.putBackfillBestHeaderHash(headers(19).hash.value).commit()
    committed(headers(15))
    check(15) shouldBe a[PivotAnchor.Check.Undecided]

  it should "anchor a genesis pivot with nothing downloaded" taggedAs UnitTest in new Chain:
    blockchainWriter.storeBlockHeader(headers.head).commit()
    committed(headers.head)
    check(0) shouldBe PivotAnchor.Check.Anchored(headers.head)

  "PivotAnchor.pinFor" should "pin the stored header with the in-memory root, and drop a pin it cannot name" taggedAs UnitTest in new Chain:
    downloaded(20)
    appState.putSnapSyncPivotHash(headers(3).hash.value).commit() // a stale pin from an earlier pivot

    PivotAnchor.pinFor(appState, blockchainReader, BigInt(12), headers(12).stateRoot.value).commit()
    appState.getSnapSyncPivotHash() shouldBe Some(headers(12).hash.value)

    PivotAnchor.pinFor(appState, blockchainReader, BigInt(12), otherRoot.value).commit()
    appState.getSnapSyncPivotHash() shouldBe None

  // ── A pivot the consensus layer chose (post-merge chains) ────────────────────────────────────────────────────────

  /** A chain that parts from the downloaded one above block `from`, up to block `to`: each header is the real one with
    * another state root, on the one before it; stored by hash only (as the CL's blocks are), not in the number index.
    */
  private def forkAbove(chain: Chain, from: Int, to: Int): IndexedSeq[BlockHeader] =
    val fork = (from + 1 to to)
      .scanLeft(headers(from)) { (parent, n) =>
        headers(n).copy(parentHash = parent.hash, stateRoot = otherRoot)
      }
      .tail
    fork.foreach(h =>
      chain.blockchainWriter
        .storeBlockByHashOnly(com.chipprbots.ethereum.domain.Block(h, com.chipprbots.ethereum.domain.BlockBody.empty))
        .commit()
    )
    fork

  "PivotAnchor.forkPoint" should "find the highest header the downloaded chain shares with the pinned pivot's ancestry" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new Chain:
    downloaded(20)
    val fork: IndexedSeq[BlockHeader] = forkAbove(this, from = 12, to = 15)
    val pinned = fork.last
    pinned.number.value shouldBe 15
    PivotAnchor.forkPoint(blockchainReader, pinned, headers(15)) shouldBe Some(headers(12))

  it should "find the parent of a pinned pivot one block off the downloaded chain" taggedAs UnitTest in new Chain:
    downloaded(20)
    val sibling: BlockHeader = forkAbove(this, from = 14, to = 15).last
    PivotAnchor.forkPoint(blockchainReader, sibling, headers(15)) shouldBe Some(headers(14))

  it should "not decide when the pinned pivot's stored ancestry ends before a common header" taggedAs UnitTest in new Chain:
    downloaded(20)
    val fork: IndexedSeq[BlockHeader] = forkAbove(this, from = 12, to = 15)
    blockchainWriter.removeBlockByHash(fork(1).hash).commit() // block 14 of the fork is not stored
    PivotAnchor.forkPoint(blockchainReader, fork.last, headers(15)) shouldBe None

  it should "take the pinned header itself when the downloaded chain holds it, and refuse headers at two heights" taggedAs UnitTest in new Chain:
    downloaded(20)
    PivotAnchor.forkPoint(blockchainReader, headers(15), headers(15)) shouldBe Some(headers(15))
    PivotAnchor.forkPoint(blockchainReader, headers(15), headers(16)) shouldBe None

  // The pivot ancestry bootstrap stores the run it fetched from the CL head (PivotHeaderBootstrap.storeAncestry): the pinned
  // pivot's ancestors, by hash. That is what a fork point below the pivot is found in.
  "The stored ancestry run" should "give finalisation a fork point below the pinned pivot" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new Chain:
    downloaded(20)
    // The CL's chain parts from the downloaded one above block 12; its head is 20, the pivot 15. Nothing of it is stored.
    val clChain: IndexedSeq[BlockHeader] = (13 to 20).scanLeft(headers(12)) { (parent, n) =>
      headers(n).copy(parentHash = parent.hash, stateRoot = otherRoot)
    }
    val pinned: BlockHeader = clChain(3) // block 15
    pinned.number.value shouldBe 15
    // With only the pivot stored (by its hash), no fork point is found, so finalisation would download from genesis.
    blockchainWriter.storeBlockHeader(pinned).commit()
    PivotAnchor.forkPoint(blockchainReader, pinned, headers(15)) shouldBe None
    // With the run stored: head 20 down to block 10, the CL chain's ancestors below the pivot included.
    val linked: IndexedSeq[BlockHeader] = (clChain.reverse ++ Seq(headers(11), headers(10))).toIndexedSeq
    com.chipprbots.ethereum.blockchain.sync.PivotHeaderBootstrap
      .storeAncestry(
        blockchainWriter,
        com.chipprbots.ethereum.blockchain.sync.PivotHeaderBootstrap.AncestryRun(pinned, linked)
      )
      .commit()
    PivotAnchor.forkPoint(blockchainReader, pinned, headers(15)) shouldBe Some(headers(12))
    // Only the pivot names its height in the number index; the rest of the run is stored by hash.
    blockchainReader.getBlockHeaderByNumber(15) shouldBe Some(pinned)
    blockchainReader.getBlockHeaderByNumber(14) shouldBe Some(headers(14))
    blockchainReader.getBlockHeaderByNumber(18) shouldBe Some(headers(18))

  "PivotAnchor.storedAncestor" should "walk back from a head by parent hash through headers stored by hash" taggedAs UnitTest in new Chain:
    downloaded(20)
    PivotAnchor.storedAncestor(blockchainReader, headers(20).hash.value, 5) shouldBe Some(headers(15))
    PivotAnchor.storedAncestor(blockchainReader, headers(20).hash.value, 0) shouldBe Some(headers(20))
    val fork: IndexedSeq[BlockHeader] = forkAbove(this, from = 12, to = 15)
    PivotAnchor.storedAncestor(blockchainReader, fork.last.hash.value, 3) shouldBe Some(headers(12))
    // Not through the number index: the fork's ancestors are found by hash although the index names the real ones.
    PivotAnchor.storedAncestor(blockchainReader, fork.last.hash.value, 1) shouldBe Some(fork(1))

  it should "find nothing when a header on the way, or the head, is not stored" taggedAs UnitTest in new Chain:
    downloaded(20)
    PivotAnchor.storedAncestor(blockchainReader, headers(20).hash.value, 21) shouldBe None // below genesis
    PivotAnchor.storedAncestor(blockchainReader, ByteString(Array.fill(32)(0x44.toByte)), 0) shouldBe None
    val fork: IndexedSeq[BlockHeader] = forkAbove(this, from = 12, to = 15)
    blockchainWriter.removeBlockByHash(fork(1).hash).commit()
    PivotAnchor.storedAncestor(blockchainReader, fork.last.hash.value, 3) shouldBe None

  "PivotAnchor.pinForChosenPivot" should "pin the header the best-block record names, never the number index's" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new Chain:
    downloaded(20)
    // The in-memory pivot (block 15, the fork's root) recorded as the best block, as updateBestBlockForPivot records it.
    val chosen: BlockHeader = forkAbove(this, from = 12, to = 15).last
    appState.putBestBlockInfo(com.chipprbots.ethereum.domain.appstate.BlockInfo(chosen.hash.value, 15)).commit()
    // The number index names another header at 15 with the same state root.
    val sameRoot: BlockHeader = headers(15).copy(stateRoot = otherRoot)
    blockchainWriter.storeBlockHeader(sameRoot).commit()

    PivotAnchor.pinForChosenPivot(appState, blockchainReader, BigInt(15), otherRoot.value).commit()
    appState.getSnapSyncPivotHash() shouldBe Some(chosen.hash.value)

  it should "keep a pin that already names the pivot (a resumed pivot), and drop one it cannot confirm" taggedAs UnitTest in new Chain:
    downloaded(20)
    appState.putSnapSyncPivotHash(headers(15).hash.value).commit()
    // The best-block record names another block (a re-peg the resume did not persist).
    appState.putBestBlockInfo(com.chipprbots.ethereum.domain.appstate.BlockInfo(headers(18).hash.value, 18)).commit()

    PivotAnchor.pinForChosenPivot(appState, blockchainReader, BigInt(15), headers(15).stateRoot.value).commit()
    appState.getSnapSyncPivotHash() shouldBe Some(headers(15).hash.value)

    PivotAnchor.pinForChosenPivot(appState, blockchainReader, BigInt(15), otherRoot.value).commit()
    appState.getSnapSyncPivotHash() shouldBe None

  "PivotAnchor.currentPin" should "name the pin only when it can name the pivot" taggedAs UnitTest in new Chain:
    downloaded(20)
    appState.putSnapSyncPivotHash(headers(14).hash.value).commit()
    PivotAnchor.currentPin(appState, blockchainReader, BigInt(15)) shouldBe None // stale: another height
    appState.putSnapSyncPivotHash(headers(15).hash.value).commit()
    PivotAnchor.currentPin(appState, blockchainReader, BigInt(15)) shouldBe Some(headers(15).hash.value)
    appState.removeSnapSyncPivotHash().commit()
    PivotAnchor.currentPin(appState, blockchainReader, BigInt(15)) shouldBe None

  "PivotAnchor.reanchor" should "persist the validated header as the pivot, its index entry and the best block" taggedAs UnitTest in new Chain:
    downloaded(20)
    val altered: BlockHeader = headers(15).copy(stateRoot = otherRoot)
    blockchainWriter.storeBlockHeader(altered).commit()
    committed(altered)

    PivotAnchor.reanchor(appState, blockchainWriter, headers(15))

    appState.getSnapSyncPivotBlock() shouldBe Some(BigInt(15))
    appState.getSnapSyncStateRoot() shouldBe Some(headers(15).stateRoot.value)
    appState.getSnapSyncPivotHash() shouldBe Some(headers(15).hash.value)
    blockchainReader.getBlockHeaderByNumber(15) shouldBe Some(headers(15))
    appState.getBestBlockInfo().hash shouldBe headers(15).hash.value
    check(15) shouldBe PivotAnchor.Check.Anchored(headers(15))

  it should "refuse once the state is held in place: the best block moves only with the state then (spec 018 C4b)" taggedAs
    UnitTest in new Chain:
      downloaded(20)
      committed(headers(10))
      // the anchor of a state held in place, as the batch that gives a datadir one writes it
      val anchor: BlockRef = com.chipprbots.ethereum.db.storage.pathstate
        .BlockRef(headers(10).hash.value, 10, headers(10).stateRoot.value)
      storagesInstance.dataSource.update(
        Seq(
          com.chipprbots.ethereum.db.dataSource.DataSourceUpdate(
            com.chipprbots.ethereum.db.storage.Namespaces.AppStateNamespace,
            Seq.empty,
            Seq(
              com.chipprbots.ethereum.db.storage.pathstate.StateAnchor.Key.getBytes("UTF-8").toIndexedSeq ->
                com.chipprbots.ethereum.db.storage.pathstate.StateAnchor.encode(anchor).getBytes("UTF-8").toIndexedSeq
            )
          )
        )
      )
      val before: (Option[BigInt], BlockInfo) = (appState.getSnapSyncPivotBlock(), appState.getBestBlockInfo())
      (the[IllegalStateException] thrownBy PivotAnchor.reanchor(
        appState,
        blockchainWriter,
        headers(15)
      )).getMessage should
        include("the state is held in place")
      (appState.getSnapSyncPivotBlock(), appState.getBestBlockInfo()) shouldBe before
