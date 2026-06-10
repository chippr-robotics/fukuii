package com.chipprbots.ethereum.consensus.engine

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.ParallelTestExecution

import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config
import com.chipprbots.ethereum.testing.Tags._

/** EIP-7892 (Blob Parameter Only forks) — verify that fork-aware blob target / max selection picks the active BPO rung
  * instead of the Prague defaults. Without this, post-Osaka Sepolia blocks fail Engine API validation with
  * `INCORRECT_EXCESS_BLOB_GAS` because excess is computed against the 6-blob Prague target rather than the active 8
  * (BPO1) or 12 (BPO2) blob target.
  */
class BlobGasBpoScheduleSpec extends AnyFlatSpec with Matchers with ParallelTestExecution {

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

  "targetBlobGasPerBlock" should "pick CANCUN target before any post-Cancun fork is active" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val cfg = withForks(cancun = Some(0L), prague = Some(pragueTs))
    BlobGasUtils.targetBlobGasPerBlock(pragueTs - 1, cfg) shouldBe BlobGasUtils.CANCUN_TARGET_BLOB_GAS
  }

  it should "pick PRAGUE target after Prague but before BPO1" taggedAs (UnitTest, ConsensusTest) in {
    val cfg = withForks(prague = Some(pragueTs), osaka = Some(osakaTs), bpo1 = Some(bpo1Ts))
    BlobGasUtils.targetBlobGasPerBlock(bpo1Ts - 1, cfg) shouldBe BlobGasUtils.PRAGUE_TARGET_BLOB_GAS
  }

  it should "pick BPO1 target (8 blobs) at and after BPO1 activation" taggedAs (UnitTest, ConsensusTest) in {
    val cfg = withForks(prague = Some(pragueTs), osaka = Some(osakaTs), bpo1 = Some(bpo1Ts), bpo2 = Some(bpo2Ts))
    BlobGasUtils.targetBlobGasPerBlock(bpo1Ts, cfg) shouldBe BlobGasUtils.BPO1_TARGET_BLOB_GAS
    BlobGasUtils.targetBlobGasPerBlock(bpo2Ts - 1, cfg) shouldBe BlobGasUtils.BPO1_TARGET_BLOB_GAS
  }

  it should "pick BPO2 target (12 blobs) at and after BPO2 activation" taggedAs (UnitTest, ConsensusTest) in {
    val cfg = withForks(prague = Some(pragueTs), osaka = Some(osakaTs), bpo1 = Some(bpo1Ts), bpo2 = Some(bpo2Ts))
    BlobGasUtils.targetBlobGasPerBlock(bpo2Ts, cfg) shouldBe BlobGasUtils.BPO2_TARGET_BLOB_GAS
    BlobGasUtils.targetBlobGasPerBlock(bpo2Ts + 7200, cfg) shouldBe BlobGasUtils.BPO2_TARGET_BLOB_GAS
  }

  "maxBlobGasPerBlock" should "follow the same fork-aware ladder for max" taggedAs (UnitTest, ConsensusTest) in {
    val cfg = withForks(prague = Some(pragueTs), osaka = Some(osakaTs), bpo1 = Some(bpo1Ts), bpo2 = Some(bpo2Ts))
    BlobGasUtils.maxBlobGasPerBlock(pragueTs - 1, cfg) shouldBe BlobGasUtils.CANCUN_MAX_BLOB_GAS
    BlobGasUtils.maxBlobGasPerBlock(bpo1Ts - 1, cfg) shouldBe BlobGasUtils.PRAGUE_MAX_BLOB_GAS
    BlobGasUtils.maxBlobGasPerBlock(bpo1Ts, cfg) shouldBe BlobGasUtils.BPO1_MAX_BLOB_GAS
    BlobGasUtils.maxBlobGasPerBlock(bpo2Ts, cfg) shouldBe BlobGasUtils.BPO2_MAX_BLOB_GAS
  }

  "calcExcessBlobGas" should "match Sepolia canonical chain (BPO2)" taggedAs (UnitTest, ConsensusTest) in {
    // Empirical recreate from Sepolia canonical 10817144 → 10817145:
    //   parent.excess = 8912666, parent.used = 1310720
    //   child.excess  = 8388378
    //   target should = (8912666 + 1310720) - 8388378 = 1835008 (14 blobs)
    val parentExcess = BigInt(8912666)
    val parentUsed = BigInt(1310720)
    val expectedChildExcess = BigInt(8388378)
    val computed =
      BlobGasUtils.calcExcessBlobGas(parentExcess, parentUsed, BlobGasUtils.BPO2_TARGET_BLOB_GAS)
    computed shouldBe expectedChildExcess
  }

  it should "match Sepolia canonical chain (BPO1)" taggedAs (UnitTest, ConsensusTest) in {
    // Empirical recreate from Sepolia canonical 0x90FFFF → 0x910000:
    //   parent.excess = 655360, parent.used = 917504
    //   child.excess  = 262144
    //   target should = (655360 + 917504) - 262144 = 1310720 (10 blobs)
    val parentExcess = BigInt(655360)
    val parentUsed = BigInt(917504)
    val expectedChildExcess = BigInt(262144)
    val computed =
      BlobGasUtils.calcExcessBlobGas(parentExcess, parentUsed, BlobGasUtils.BPO1_TARGET_BLOB_GAS)
    computed shouldBe expectedChildExcess
  }

  "expectedExcessBlobGas (EIP-7918, post-Osaka)" should "match Sepolia canonical when reservePrice > blobPrice" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    // Empirical recreate from Sepolia canonical 10817260 → 10817261 (BPO2 active, low blob price):
    //   parent: excess=917504  used=1310720  baseFee=~19  (low fee: reservePrice > blobPrice)
    //   child:  excess=1354410
    //   EIP-7918 alt formula: parent.excess + parent.used * (max - target) / max
    //                        = 917504 + 1310720 * (21 - 14) / 21
    //                        = 917504 + 436906 = 1354410
    val cfg = withForks(prague = Some(pragueTs), osaka = Some(osakaTs), bpo1 = Some(bpo1Ts), bpo2 = Some(bpo2Ts))
    val computed = BlobGasUtils.expectedExcessBlobGas(
      parentExcessBlobGas = BigInt(917504),
      parentBlobGasUsed = BigInt(1310720),
      parentBaseFee = BigInt(19), // low base fee — triggers reserve-price branch
      childTimestamp = bpo2Ts + 100, // after bpo2 activation
      blockchainConfig = cfg
    )
    computed shouldBe BigInt(1354410)
  }

  it should "fall through to original EIP-4844 formula when reservePrice ≤ blobPrice" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    // Empirical from Sepolia canonical 10817144 → 10817145 (BPO2 active, high parent.excess
    // → blobPrice ≥ reservePrice, so original formula applies):
    //   parent: excess=8912666 used=1310720
    //   child:  excess=8388378  (= 8912666 + 1310720 - 1835008 [BPO2 target])
    val cfg = withForks(prague = Some(pragueTs), osaka = Some(osakaTs), bpo1 = Some(bpo1Ts), bpo2 = Some(bpo2Ts))
    val computed = BlobGasUtils.expectedExcessBlobGas(
      parentExcessBlobGas = BigInt(8912666),
      parentBlobGasUsed = BigInt(1310720),
      parentBaseFee = BigInt(20), // baseFee at this height — high parent.excess inflates blobPrice past reserve
      childTimestamp = bpo2Ts + 100,
      blockchainConfig = cfg
    )
    computed shouldBe BigInt(8388378)
  }

  it should "use BPO2 update fraction so high-excess cases still hit the reserve-price branch" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    // Live Sepolia 2026-05-09 BLOB-DIAG sample: high parent.excess (12.7M) where Prague's
    // smaller updateFraction (5_007_716) would compute blobPrice ≈ 12 * 131072 = 1_572_864
    // wei, vs reservePrice = 8192 * 44 = 360_448. Prague fraction → blobPrice > reservePrice
    // → EIP-7918 branch NOT taken → EIP-4844 formula used → 11_971_190 (wrong).
    //
    // Geth uses BPO2's larger updateFraction (11_684_671) where blobPrice ≈ 2 * 131072 =
    // 262_144 < reservePrice → EIP-7918 alt branch IS taken → 13_107_147 (correct).
    //
    // This test pins the BPO2 update fraction selection so a regression to Prague's value
    // would re-introduce the bug seen on live Sepolia post-#1226 merge.
    val cfg = withForks(prague = Some(pragueTs), osaka = Some(osakaTs), bpo1 = Some(bpo1Ts), bpo2 = Some(bpo2Ts))
    val computed = BlobGasUtils.expectedExcessBlobGas(
      parentExcessBlobGas = BigInt(12757622),
      parentBlobGasUsed = BigInt(1048576), // 8 blobs
      parentBaseFee = BigInt(44),
      childTimestamp = bpo2Ts + 100,
      blockchainConfig = cfg
    )
    computed shouldBe BigInt(13107147)
  }

  "BlockchainConfig" should "expose isBpo1Timestamp / isBpo2Timestamp predicates" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val cfg = withForks(bpo1 = Some(bpo1Ts), bpo2 = Some(bpo2Ts))
    cfg.isBpo1Timestamp(bpo1Ts - 1) shouldBe false
    cfg.isBpo1Timestamp(bpo1Ts) shouldBe true
    cfg.isBpo2Timestamp(bpo2Ts - 1) shouldBe false
    cfg.isBpo2Timestamp(bpo2Ts) shouldBe true
  }
}
