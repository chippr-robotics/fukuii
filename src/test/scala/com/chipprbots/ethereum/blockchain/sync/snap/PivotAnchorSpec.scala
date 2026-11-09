package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.TrieRoot
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
