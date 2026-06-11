package com.chipprbots.ethereum.consensus.pow

import org.apache.pekko.util.ByteString
import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags._

/** SlowTest: verifies Ethash cache and DAG parameter generation for Mordor (ECIP-1099).
  *
  * Cache generation is a shared lazy val (~1-5s). Tests cover known epoch 0 sizes, cache word count, dataset item
  * determinism, ECIP-1099 epoch halving at the Mordor boundary, and end-to-end cache correctness via block 100 cross-
  * verification.
  */
class DAGGenerationSpec extends AnyFlatSpec with Matchers {

  import EthashUtils._

  private val ecip1099Block: Long = 2_520_000L
  private val epoch0Seed = EthashUtils.seed(0L, ecip1099Block)
  private lazy val epoch0Cache = EthashUtils.makeCache(0L, epoch0Seed)

  "DAGGeneration" should "report known prime-aligned cacheSize for epoch 0" taggedAs SlowTest in {
    EthashUtils.cacheSize(0L) shouldBe 16_776_896L
  }

  it should "report known prime-aligned dagSize for epoch 0" taggedAs SlowTest in {
    EthashUtils.dagSize(0L) shouldBe 1_073_739_904L
  }

  it should "produce an epoch 0 cache with the correct word count" taggedAs SlowTest in {
    epoch0Cache.length shouldBe (EthashUtils.cacheSize(0L) / WORD_BYTES).toInt
  }

  it should "produce deterministic dataset items for the same index" taggedAs SlowTest in {
    EthashUtils.calcDatasetItem(epoch0Cache, 0) shouldEqual EthashUtils.calcDatasetItem(epoch0Cache, 0)
  }

  it should "produce different dataset items for different indices" taggedAs SlowTest in {
    (EthashUtils.calcDatasetItem(epoch0Cache, 0) should not).equal(EthashUtils.calcDatasetItem(epoch0Cache, 1))
  }

  it should "halve the epoch number at the Mordor ECIP-1099 boundary" taggedAs SlowTest in {
    EthashUtils.epoch(2_519_999L, ecip1099Block) shouldBe 83L
    EthashUtils.epoch(2_520_000L, ecip1099Block) shouldBe 42L
  }

  it should "produce a different cache for epoch 1 than epoch 0" taggedAs SlowTest in {
    val epoch1Seed = EthashUtils.seed(30_000L, ecip1099Block)
    val epoch1Cache = EthashUtils.makeCache(1L, epoch1Seed)
    (epoch0Cache(0) should not).equal(epoch1Cache(0))
  }

  it should "cross-verify block 100 with known mixHash using the generated cache" taggedAs SlowTest in {
    val hash = Hex.decode("41944a94a42695180b1ca231720a87825f17d36475112b659c23dea1542e0977")
    val nonce = Hex.decode("37129c7f29a9364b")
    val fullSize = EthashUtils.dagSize(0L)
    val pow = EthashUtils.hashimotoLight(hash, nonce, fullSize, epoch0Cache)
    pow.mixHash shouldBe ByteString(Hex.decode("5bb43c0772e58084b221c8e0c859a45950c103c712c5b8f11d9566ee078a4501"))
  }
}
