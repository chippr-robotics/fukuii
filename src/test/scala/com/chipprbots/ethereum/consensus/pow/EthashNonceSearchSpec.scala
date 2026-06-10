package com.chipprbots.ethereum.consensus.pow

import org.apache.pekko.util.ByteString
import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags._

/** SlowTest: verifies Ethash nonce search and PoW verification using the light-client cache.
  *
  * Cache generation is a shared lazy val (~1-5s); individual hashimotoLight calls are fast. All tests operate on epoch
  * 0 (Mordor genesis epoch) to avoid generating multiple caches.
  */
class EthashNonceSearchSpec extends AnyFlatSpec with Matchers {

  import EthashUtils._

  private val ecip1099Block: Long = 2_520_000L
  private val epoch0: Long = EthashUtils.epoch(0L, ecip1099Block)
  private val epoch0Seed = EthashUtils.seed(0L, ecip1099Block)
  private val epoch0FullSize: Long = EthashUtils.dagSize(epoch0)
  private lazy val epoch0Cache = EthashUtils.makeCache(epoch0, epoch0Seed)

  private val testHeaderHash: Array[Byte] = Array.fill[Byte](32)(0)
  private lazy val searchResult = searchNonce(testHeaderHash, 0x20000L, maxIterations = 2_000_000)

  private def searchNonce(
      headerHash: Array[Byte],
      difficulty: Long,
      maxIterations: Int = 500_000
  ): Option[(ByteString, ByteString)] =
    (0L until maxIterations.toLong).iterator
      .map { nonce =>
        val nb = java.nio.ByteBuffer.allocate(8).putLong(nonce).array()
        val pow = EthashUtils.hashimotoLight(headerHash, nb, epoch0FullSize, epoch0Cache)
        if (EthashUtils.checkDifficulty(difficulty, pow)) Some((pow.mixHash, ByteString(nb))) else None
      }
      .collectFirst { case Some(x) => x }

  "EthashNonceSearch" should "find a valid nonce at Mordor genesis difficulty" taggedAs SlowTest in {
    searchResult shouldBe defined
  }

  it should "produce a 32-byte mixHash" taggedAs SlowTest in {
    searchResult.get._1.length shouldBe 32
  }

  it should "produce an 8-byte nonce" taggedAs SlowTest in {
    searchResult.get._2.length shouldBe 8
  }

  it should "produce a nonce that re-verifies with checkDifficulty" taggedAs SlowTest in {
    val (_, nonceBytes) = searchResult.get
    val pow = EthashUtils.hashimotoLight(testHeaderHash, nonceBytes.toArray, epoch0FullSize, epoch0Cache)
    EthashUtils.checkDifficulty(0x20000L, pow) shouldBe true
  }

  it should "return None for impossibly high difficulty" taggedAs SlowTest in {
    searchNonce(testHeaderHash, Long.MaxValue, maxIterations = 100) shouldBe None
  }

  it should "cross-verify block 100 test vector with known nonce" taggedAs SlowTest in {
    val hash = Hex.decode("41944a94a42695180b1ca231720a87825f17d36475112b659c23dea1542e0977")
    val nonce = Hex.decode("37129c7f29a9364b")
    val pow = EthashUtils.hashimotoLight(hash, nonce, epoch0FullSize, epoch0Cache)
    pow.mixHash shouldBe ByteString(Hex.decode("5bb43c0772e58084b221c8e0c859a45950c103c712c5b8f11d9566ee078a4501"))
  }
}
