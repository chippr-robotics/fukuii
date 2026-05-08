package com.chipprbots.ethereum.consensus.engine

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config

/** EIP-7892 (Blob Parameter Only forks) — verify that fork-aware blob target / max selection picks the active BPO rung
  * instead of the Prague defaults. Without this, post-Osaka Sepolia blocks fail Engine API validation with
  * `INCORRECT_EXCESS_BLOB_GAS` because excess is computed against the 6-blob Prague target rather than the active 8
  * (BPO1) or 12 (BPO2) blob target.
  */
class BlobGasBpoScheduleSpec extends AnyFlatSpec with Matchers {

  private val baseConfig: BlockchainConfig = Config.blockchains.blockchainConfig

  private def withForks(
      cancun: Option[Long] = None,
      prague: Option[Long] = None,
      osaka: Option[Long] = None,
      bpo1: Option[Long] = None,
      bpo2: Option[Long] = None
  ): BlockchainConfig =
    baseConfig.copy(forkTimestamps =
      baseConfig.forkTimestamps.copy(
        cancunTimestamp = cancun,
        pragueTimestamp = prague,
        osakaTimestamp = osaka,
        bpo1Timestamp = bpo1,
        bpo2Timestamp = bpo2
      )
    )

  // Sepolia BPO timestamps (mirrors src/main/resources/conf/base/chains/sepolia-chain.conf).
  private val osakaTs: Long = 1760427360L
  private val bpo1Ts: Long = 1761017184L
  private val bpo2Ts: Long = 1761607008L
  private val pragueTs: Long = 1741159776L

  "targetBlobGasPerBlock" should "pick CANCUN target before any post-Cancun fork is active" in {
    val cfg = withForks(cancun = Some(0L), prague = Some(pragueTs))
    BlobGasUtils.targetBlobGasPerBlock(pragueTs - 1, cfg) shouldBe BlobGasUtils.CANCUN_TARGET_BLOB_GAS
  }

  it should "pick PRAGUE target after Prague but before BPO1" in {
    val cfg = withForks(prague = Some(pragueTs), osaka = Some(osakaTs), bpo1 = Some(bpo1Ts))
    BlobGasUtils.targetBlobGasPerBlock(bpo1Ts - 1, cfg) shouldBe BlobGasUtils.PRAGUE_TARGET_BLOB_GAS
  }

  it should "pick BPO1 target (8 blobs) at and after BPO1 activation" in {
    val cfg = withForks(prague = Some(pragueTs), osaka = Some(osakaTs), bpo1 = Some(bpo1Ts), bpo2 = Some(bpo2Ts))
    BlobGasUtils.targetBlobGasPerBlock(bpo1Ts, cfg) shouldBe BlobGasUtils.BPO1_TARGET_BLOB_GAS
    BlobGasUtils.targetBlobGasPerBlock(bpo2Ts - 1, cfg) shouldBe BlobGasUtils.BPO1_TARGET_BLOB_GAS
  }

  it should "pick BPO2 target (12 blobs) at and after BPO2 activation" in {
    val cfg = withForks(prague = Some(pragueTs), osaka = Some(osakaTs), bpo1 = Some(bpo1Ts), bpo2 = Some(bpo2Ts))
    BlobGasUtils.targetBlobGasPerBlock(bpo2Ts, cfg) shouldBe BlobGasUtils.BPO2_TARGET_BLOB_GAS
    BlobGasUtils.targetBlobGasPerBlock(bpo2Ts + 7200, cfg) shouldBe BlobGasUtils.BPO2_TARGET_BLOB_GAS
  }

  "maxBlobGasPerBlock" should "follow the same fork-aware ladder for max" in {
    val cfg = withForks(prague = Some(pragueTs), osaka = Some(osakaTs), bpo1 = Some(bpo1Ts), bpo2 = Some(bpo2Ts))
    BlobGasUtils.maxBlobGasPerBlock(pragueTs - 1, cfg) shouldBe BlobGasUtils.CANCUN_MAX_BLOB_GAS
    BlobGasUtils.maxBlobGasPerBlock(bpo1Ts - 1, cfg) shouldBe BlobGasUtils.PRAGUE_MAX_BLOB_GAS
    BlobGasUtils.maxBlobGasPerBlock(bpo1Ts, cfg) shouldBe BlobGasUtils.BPO1_MAX_BLOB_GAS
    BlobGasUtils.maxBlobGasPerBlock(bpo2Ts, cfg) shouldBe BlobGasUtils.BPO2_MAX_BLOB_GAS
  }

  "calcExcessBlobGas" should "produce different excess depending on the active BPO target" in {
    // Recreates the symptom: parent.excess + parent.used = 21932597 + targetBpo2 (1572864) = 23505461.
    // With Prague target  (786432):  excess = 23505461 - 786432  = 22719029
    // With BPO2  target (1572864):  excess = 23505461 - 1572864 = 21932597  (matches geth)
    val parentExcess = BigInt(21932597)
    val parentUsed = BlobGasUtils.BPO2_TARGET_BLOB_GAS
    val excessAtPrague = BlobGasUtils.calcExcessBlobGas(parentExcess, parentUsed, BlobGasUtils.PRAGUE_TARGET_BLOB_GAS)
    val excessAtBpo2 = BlobGasUtils.calcExcessBlobGas(parentExcess, parentUsed, BlobGasUtils.BPO2_TARGET_BLOB_GAS)
    excessAtBpo2 should be < excessAtPrague
    excessAtPrague - excessAtBpo2 shouldBe (BlobGasUtils.BPO2_TARGET_BLOB_GAS - BlobGasUtils.PRAGUE_TARGET_BLOB_GAS)
  }

  "BlockchainConfig" should "expose isBpo1Timestamp / isBpo2Timestamp predicates" in {
    val cfg = withForks(bpo1 = Some(bpo1Ts), bpo2 = Some(bpo2Ts))
    cfg.isBpo1Timestamp(bpo1Ts - 1) shouldBe false
    cfg.isBpo1Timestamp(bpo1Ts) shouldBe true
    cfg.isBpo2Timestamp(bpo2Ts - 1) shouldBe false
    cfg.isBpo2Timestamp(bpo2Ts) shouldBe true
  }
}
