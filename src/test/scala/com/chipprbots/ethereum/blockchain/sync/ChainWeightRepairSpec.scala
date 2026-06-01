package com.chipprbots.ethereum.blockchain.sync

import org.apache.pekko.util.ByteString

import org.scalamock.scalatest.MockFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.db.dataSource.DataSourceBatchUpdate
import com.chipprbots.ethereum.domain.{BlockHeader, BlockchainReader, BlockchainWriter, ChainWeight => CW}
import com.chipprbots.ethereum.testing.Tags._

class ChainWeightRepairSpec extends AnyFlatSpec with Matchers with MockFactory {

  // ── helpers ────────────────────────────────────────────────────────────────

  private def fakeHash(n: Int): ByteString =
    ByteString(Array.fill(31)(0.toByte) :+ n.toByte)

  private def hdr(number: BigInt, difficulty: BigInt): BlockHeader =
    BlockHeader(
      parentHash = fakeHash((number - 1).toInt.max(0)),
      ommersHash = ByteString(Array.fill(32)(0.toByte)),
      beneficiary = ByteString(Array.fill(20)(0.toByte)),
      stateRoot = ByteString(Array.fill(32)(0.toByte)),
      transactionsRoot = ByteString(Array.fill(32)(0.toByte)),
      receiptsRoot = ByteString(Array.fill(32)(0.toByte)),
      logsBloom = ByteString(Array.fill(256)(0.toByte)),
      difficulty = difficulty,
      number = number,
      gasLimit = 8000000L,
      gasUsed = 0L,
      unixTimestamp = number.toLong * 13L,
      extraData = ByteString.empty,
      mixHash = ByteString(Array.fill(32)(0.toByte)),
      nonce = ByteString(Array.fill(8)(0.toByte))
    )

  // Stub batch that silently accepts commit() calls
  private def batch(): DataSourceBatchUpdate = stub[DataSourceBatchUpdate]

  // ── tests ──────────────────────────────────────────────────────────────────

  "ChainWeightRepair" should "correct inflated pivot and subsequent block TDs via forward walk" taggedAs UnitTest in {
    val diff = BigInt(100)
    val hdrs = (0 to 4).map(i => hdr(i, diff))

    val reader = mock[BlockchainReader]
    val writer = mock[BlockchainWriter]

    // Pre-pivot (block 1) correctly stored with TD = 200
    (reader.getBlockHeaderByNumber _).expects(BigInt(1)).returning(Some(hdrs(1)))
    (reader.getChainWeightByHash _).expects(hdrs(1).hash).returning(Some(CW.totalDifficultyOnly(diff * 2)))

    // Walk blocks 2, 3, 4
    (reader.getBlockHeaderByNumber _).expects(BigInt(2)).returning(Some(hdrs(2)))
    (reader.getBlockHeaderByNumber _).expects(BigInt(3)).returning(Some(hdrs(3)))
    (reader.getBlockHeaderByNumber _).expects(BigInt(4)).returning(Some(hdrs(4)))

    // Corrected TDs: 300, 400, 500
    (writer.storeChainWeight _).expects(hdrs(2).hash, CW.totalDifficultyOnly(diff * 3)).returning(batch())
    (writer.storeChainWeight _).expects(hdrs(3).hash, CW.totalDifficultyOnly(diff * 4)).returning(batch())
    (writer.storeChainWeight _).expects(hdrs(4).hash, CW.totalDifficultyOnly(diff * 5)).returning(batch())

    new ChainWeightRepair(reader, writer).repairFrom(pivotBlock = 2, endBlock = 4) shouldBe Right(3)
  }

  it should "return Right(0) when pivot is genesis (block 0)" taggedAs UnitTest in {
    new ChainWeightRepair(mock[BlockchainReader], mock[BlockchainWriter])
      .repairFrom(pivotBlock = 0, endBlock = 0) shouldBe Right(0)
  }

  it should "return Left when pre-pivot header is absent (backfill not yet reached pivot)" taggedAs UnitTest in {
    val reader = mock[BlockchainReader]
    (reader.getBlockHeaderByNumber _).expects(BigInt(4)).returning(None)

    new ChainWeightRepair(reader, mock[BlockchainWriter])
      .repairFrom(pivotBlock = 5, endBlock = 10) shouldBe a[Left[_, _]]
  }

  it should "return Left when pre-pivot chain weight is not stored" taggedAs UnitTest in {
    val reader = mock[BlockchainReader]
    val prePivot = hdr(4, BigInt(100))
    (reader.getBlockHeaderByNumber _).expects(BigInt(4)).returning(Some(prePivot))
    (reader.getChainWeightByHash _).expects(prePivot.hash).returning(None)

    new ChainWeightRepair(reader, mock[BlockchainWriter])
      .repairFrom(pivotBlock = 5, endBlock = 10) shouldBe a[Left[_, _]]
  }

  it should "handle a single-block correction (pivot == endBlock)" taggedAs UnitTest in {
    val diff = BigInt(200)
    val hdrs = (0 to 1).map(i => hdr(i, diff))
    val reader = mock[BlockchainReader]
    val writer = mock[BlockchainWriter]

    (reader.getBlockHeaderByNumber _).expects(BigInt(0)).returning(Some(hdrs(0)))
    (reader.getChainWeightByHash _).expects(hdrs(0).hash).returning(Some(CW.totalDifficultyOnly(diff)))
    (reader.getBlockHeaderByNumber _).expects(BigInt(1)).returning(Some(hdrs(1)))
    (writer.storeChainWeight _).expects(hdrs(1).hash, CW.totalDifficultyOnly(diff * 2)).returning(batch())

    new ChainWeightRepair(reader, writer).repairFrom(pivotBlock = 1, endBlock = 1) shouldBe Right(1)
  }
}
