package com.chipprbots.ethereum.consensus.mess

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.domain.{BlockHeader, ChainWeight}

/** Integration test for MESS (ECIP-1100: Modified Exponential Subjective Scoring).
  *
  * Tests the polynomial antigravity curve and reorg rejection logic using realistic
  * blockchain scenarios. Verifies cross-client consistency with core-geth and Besu.
  */
class MESSIntegrationSpec extends AnyFlatSpec with Matchers {

  def createHeader(
      number: BigInt,
      difficulty: BigInt,
      timestamp: Long,
      hash: ByteString = ByteString.empty
  ): BlockHeader =
    BlockHeader(
      parentHash = ByteString.empty,
      ommersHash = ByteString.empty,
      beneficiary = ByteString.empty,
      stateRoot = ByteString.empty,
      transactionsRoot = ByteString.empty,
      receiptsRoot = ByteString.empty,
      logsBloom = ByteString.empty,
      difficulty = difficulty,
      number = number,
      gasLimit = 0,
      gasUsed = 0,
      unixTimestamp = timestamp,
      extraData = ByteString.empty,
      mixHash = ByteString.empty,
      nonce = ByteString.empty
    )

  "MESS polynomial" should "not reject short reorgs with equal difficulty" in {
    // A reorg happening within ~200 seconds with equal TD should not be rejected
    // because the polynomial is essentially 1x at short time deltas
    val result = ArtificialFinality.shouldRejectReorg(
      timeDeltaSeconds = 100,
      localSubchainTD = BigInt(3000),
      proposedSubchainTD = BigInt(3000)
    )
    result shouldBe false
  }

  it should "reject long-range reorgs with insufficient TD advantage" in {
    // A 7-hour-old reorg requires ~31x the local TD
    // Local has 3000 TD, proposed has 10000 TD (~3.3x) - should be rejected
    val result = ArtificialFinality.shouldRejectReorg(
      timeDeltaSeconds = 25132, // xcap = ~7 hours
      localSubchainTD = BigInt(3000),
      proposedSubchainTD = BigInt(10000)
    )
    result shouldBe true
  }

  it should "accept long-range reorgs with sufficient TD advantage" in {
    // Local has 3000 TD, proposed has 100000 TD (~33x) - should be accepted
    val result = ArtificialFinality.shouldRejectReorg(
      timeDeltaSeconds = 25132,
      localSubchainTD = BigInt(3000),
      proposedSubchainTD = BigInt(100000)
    )
    result shouldBe false
  }

  it should "correctly model the 51% attack scenario" in {
    // Attacker with 5% more hashrate builds a private chain for 1 hour
    // Local chain: 100 blocks * difficulty 1000 = TD 100000
    // Attack chain: 100 blocks * difficulty 1050 = TD 105000 (5% more)
    // Time delta: 1 hour = 3600 seconds
    // Polynomial at 3600s ≈ 341, multiplier ≈ 2.66x
    // Want: 341 * 100000 = 34,100,000
    // Got: 105000 * 128 = 13,440,000
    // 13,440,000 < 34,100,000 → REJECT

    val result = ArtificialFinality.shouldRejectReorg(
      timeDeltaSeconds = 3600,
      localSubchainTD = BigInt(100000),
      proposedSubchainTD = BigInt(105000)
    )
    result shouldBe true
  }

  it should "allow legitimate reorg with much higher TD" in {
    // Legitimate chain with 3x the difficulty (e.g., major mining pool joins)
    // Time delta: 1 hour, local TD: 100000, proposed TD: 300000
    // Polynomial at 3600s ≈ 341, multiplier ≈ 2.66x
    // Want: 341 * 100000 = 34,100,000
    // Got: 300000 * 128 = 38,400,000
    // 38,400,000 >= 34,100,000 → ACCEPT

    val result = ArtificialFinality.shouldRejectReorg(
      timeDeltaSeconds = 3600,
      localSubchainTD = BigInt(100000),
      proposedSubchainTD = BigInt(300000)
    )
    result shouldBe false
  }

  it should "handle the activation window correctly" in {
    val config = MESSConfig(
      enabled = true,
      activationBlock = Some(11380000),
      deactivationBlock = Some(19250000)
    )

    // Before activation
    config.isActiveAtBlock(11379999) shouldBe false

    // Within window
    config.isActiveAtBlock(11380000) shouldBe true
    config.isActiveAtBlock(15000000) shouldBe true

    // After deactivation (Spiral)
    config.isActiveAtBlock(19250000) shouldBe false
  }

  it should "match the polynomial curve shape from ECIP-1100" in {
    // Verify key points on the polynomial curve
    // The curve is a smooth S-curve from 128 (1x) to 3968 (31x)

    // Near zero: ~1x multiplier
    val at0 = ArtificialFinality.polynomialV(BigInt(0))
    at0 shouldBe BigInt(128)

    // At midpoint (~12566s ≈ 3.5 hours): should be roughly halfway
    val atMid = ArtificialFinality.polynomialV(BigInt(12566))
    // Halfway between 128 and 3968 = 2048
    atMid should be >= BigInt(1500)
    atMid should be <= BigInt(2500)

    // At xcap: 31x
    val atXcap = ArtificialFinality.polynomialV(BigInt(25132))
    atXcap shouldBe BigInt(3968)
  }

  it should "produce identical results to core-geth for boundary values" in {
    // core-geth test: ecbp1100PolynomialV(0) = 128
    ArtificialFinality.polynomialV(BigInt(0)) shouldBe BigInt(128)

    // core-geth test: ecbp1100PolynomialV(25132) = 3968
    ArtificialFinality.polynomialV(BigInt(25132)) shouldBe BigInt(3968)

    // core-geth test: ecbp1100PolynomialV(99999) = 3968 (capped)
    ArtificialFinality.polynomialV(BigInt(99999)) shouldBe BigInt(3968)

    // Negative-equivalent: polynomial should handle zero gracefully
    ArtificialFinality.polynomialV(BigInt(0)) shouldBe BigInt(128)
  }
}
