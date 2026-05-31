package com.chipprbots.ethereum.blockchain.sync

import org.apache.pekko.util.ByteString

import org.scalamock.scalatest.MockFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.db.dataSource.{ DataSourceBatchUpdate, EphemDataSource }
import com.chipprbots.ethereum.domain.{ BlockchainReader, BlockchainWriter, BlockHeader, ChainWeight }
import com.chipprbots.ethereum.testing.Tags._

/** Tests for ChainWeightRepair — the self-healing scan that corrects ETH68_BOOTSTRAP inflation.
  *
  * ETH68_BOOTSTRAP stored a peer's best-block TD (at block N) as the SNAP pivot TD (at block N-64),
  * inflating every subsequent block's chain weight by a constant offset. ChainWeightRepair.repairAll
  * walks stored headers from genesis, recomputes expected TD from scratch (anchor = 0), and overwrites
  * any value that deviates by more than 5 × block_difficulty.
  */
class ChainWeightRepairSpec extends AnyFlatSpec with Matchers with MockFactory {

  private val D         = BigInt("2500000000000000")   // representative ETC block difficulty
  private val inflation = D * 3352                     // observed live inflation at fix time

  /** Minimal BlockHeader with a given block number and difficulty.
    * Each block gets a unique parentHash based on its number to ensure unique hashes.
    */
  private def header(number: BigInt, difficulty: BigInt = D): BlockHeader =
    BlockHeader(
      parentHash       = ByteString(Array.fill(31)(0.toByte) ++ Array(number.toByte)),
      ommersHash       = ByteString(Array.fill(32)(0.toByte)),
      beneficiary      = ByteString(Array.fill(20)(0.toByte)),
      stateRoot        = ByteString(Array.fill(32)(0.toByte)),
      transactionsRoot = ByteString(Array.fill(32)(0.toByte)),
      receiptsRoot     = ByteString(Array.fill(32)(0.toByte)),
      logsBloom        = ByteString(Array.fill(256)(0.toByte)),
      difficulty       = difficulty,
      number           = number,
      gasLimit         = BigInt(8_000_000),
      gasUsed          = BigInt(0),
      unixTimestamp    = number.toLong,
      extraData        = ByteString.empty,
      mixHash          = ByteString(Array.fill(32)(0.toByte)),
      nonce            = ByteString(Array.fill(8)(0.toByte))
    )

  /** A no-op DataSourceBatchUpdate (backed by ephemeral data source; commit() is safe). */
  private def nopBatch: DataSourceBatchUpdate = DataSourceBatchUpdate(EphemDataSource())

  it should "repairAll: correct the one inflated block and leave the rest untouched" taggedAs UnitTest in {
    val slog   = org.slf4j.LoggerFactory.getLogger(getClass)
    val reader = mock[BlockchainReader]
    val writer = mock[BlockchainWriter]

    // Build headers ahead of time so we can reference their real hashes in expectations
    val N        = 10L   // small range for speed
    val headers  = (1L to N).map(n => BigInt(n) -> header(BigInt(n))).toMap

    val inflatedAt  = BigInt(N)
    val correctTD   = inflatedAt * D
    val inflatedTD  = correctTD + inflation

    (reader.getBestBlockNumber _).expects().returning(BigInt(N))

    headers.foreach { case (n, h) =>
      (reader.getBlockHeaderByNumber _).expects(n).returning(Some(h))
      val storedTD = if (n == BigInt(N)) inflatedTD else n * D
      (reader.getChainWeightByHash _)
        .expects(h.hash)
        .returning(Some(ChainWeight.totalDifficultyOnly(storedTD)))
    }

    // Only the inflated block should be written
    (writer.storeChainWeight _)
      .expects(headers(inflatedAt).hash, ChainWeight.totalDifficultyOnly(correctTD))
      .returning(nopBatch)
      .once()

    val result = ChainWeightRepair.repairAll(reader, writer, slog)

    result.walked        shouldBe N
    result.mismatches    shouldBe 1L
    result.corrected     shouldBe 1L
    result.hadMismatches shouldBe true
  }

  it should "repairAll: no writes when all chain weights are canonical" taggedAs UnitTest in {
    val slog   = org.slf4j.LoggerFactory.getLogger(getClass)
    val reader = mock[BlockchainReader]
    val writer = mock[BlockchainWriter]

    val N       = 10L
    val headers = (1L to N).map(n => BigInt(n) -> header(BigInt(n))).toMap

    (reader.getBestBlockNumber _).expects().returning(BigInt(N))

    headers.foreach { case (n, h) =>
      (reader.getBlockHeaderByNumber _).expects(n).returning(Some(h))
      (reader.getChainWeightByHash _)
        .expects(h.hash)
        .returning(Some(ChainWeight.totalDifficultyOnly(n * D)))
    }

    (writer.storeChainWeight _).expects(*, *).never()

    val result = ChainWeightRepair.repairAll(reader, writer, slog)

    result.walked       shouldBe N
    result.mismatches   shouldBe 0L
    result.corrected    shouldBe 0L
    result.hadInflation shouldBe false
  }

  it should "repairFromAnchor: uses storedTD at (anchor-1) as baseline and corrects inflated blocks" taggedAs UnitTest in {
    val slog   = org.slf4j.LoggerFactory.getLogger(getClass)
    val reader = mock[BlockchainReader]
    val writer = mock[BlockchainWriter]

    // Scan blocks 10, 11, 12 (anchor=10, end=12)
    val anchor  = BigInt(10)
    val endBlock = anchor + 2
    val scanBlocks = Seq(anchor, anchor + 1, anchor + 2)

    val anchorMinus1Header = header(anchor - 1)
    val scanHeaders = scanBlocks.map(n => n -> header(n)).toMap

    (reader.getBestBlockNumber _).expects().returning(endBlock)

    // Anchor lookup: stored TD at (anchor - 1) = canonical (no inflation before the pivot)
    (reader.getBlockHeaderByNumber _).expects(anchor - 1).returning(Some(anchorMinus1Header))
    (reader.getChainWeightByHash _)
      .expects(anchorMinus1Header.hash)
      .returning(Some(ChainWeight.totalDifficultyOnly((anchor - 1) * D)))

    // Scan blocks 10–12: all inflated
    scanBlocks.foreach { n =>
      (reader.getBlockHeaderByNumber _).expects(n).returning(Some(scanHeaders(n)))
      (reader.getChainWeightByHash _)
        .expects(scanHeaders(n).hash)
        .returning(Some(ChainWeight.totalDifficultyOnly(n * D + inflation)))
    }

    // All 3 inflated blocks corrected to canonical
    scanBlocks.foreach { n =>
      (writer.storeChainWeight _)
        .expects(scanHeaders(n).hash, ChainWeight.totalDifficultyOnly(n * D))
        .returning(nopBatch)
        .once()
    }

    val result = ChainWeightRepair.repairFromAnchor(anchor, reader, writer, slog)

    result.walked        shouldBe 3L
    result.mismatches    shouldBe 3L
    result.hadMismatches shouldBe true
  }
}
