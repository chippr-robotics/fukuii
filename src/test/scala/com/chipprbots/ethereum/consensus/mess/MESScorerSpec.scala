package com.chipprbots.ethereum.consensus.mess

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags._

class MESSConfigSpec extends AnyFlatSpec with Matchers {

  "MESSConfig" should "have valid default values" taggedAs (UnitTest, ConsensusTest) in {
    val config = MESSConfig()

    config.enabled shouldBe false
    config.activationBlock shouldBe None
    config.deactivationBlock shouldBe None
  }

  it should "have None activation/deactivation blocks by default" taggedAs (UnitTest, ConsensusTest) in {
    val config = MESSConfig()
    config.activationBlock shouldBe None
    config.deactivationBlock shouldBe None
  }

  // ECBP-1100 block-based activation window tests

  it should "report inactive when enabled=false regardless of block number" taggedAs (UnitTest, ConsensusTest) in {
    val config = MESSConfig(enabled = false, activationBlock = Some(100), deactivationBlock = Some(200))
    config.isActiveAtBlock(150) shouldBe false
  }

  it should "report active when enabled=true and within activation window" taggedAs (UnitTest, ConsensusTest) in {
    val config = MESSConfig(enabled = true, activationBlock = Some(100), deactivationBlock = Some(200))
    config.isActiveAtBlock(100) shouldBe true  // activation is inclusive
    config.isActiveAtBlock(150) shouldBe true
    config.isActiveAtBlock(199) shouldBe true
  }

  it should "report inactive before activation block" taggedAs (UnitTest, ConsensusTest) in {
    val config = MESSConfig(enabled = true, activationBlock = Some(100), deactivationBlock = Some(200))
    config.isActiveAtBlock(99) shouldBe false
    config.isActiveAtBlock(0) shouldBe false
  }

  it should "report inactive at and after deactivation block" taggedAs (UnitTest, ConsensusTest) in {
    val config = MESSConfig(enabled = true, activationBlock = Some(100), deactivationBlock = Some(200))
    config.isActiveAtBlock(200) shouldBe false  // deactivation is exclusive
    config.isActiveAtBlock(201) shouldBe false
  }

  it should "report active with no deactivation block (never deactivates)" taggedAs (UnitTest, ConsensusTest) in {
    val config = MESSConfig(enabled = true, activationBlock = Some(100))
    config.isActiveAtBlock(100) shouldBe true
    config.isActiveAtBlock(BigInt("99999999999")) shouldBe true
  }

  it should "report active with no activation block (always active)" taggedAs (UnitTest, ConsensusTest) in {
    val config = MESSConfig(enabled = true, deactivationBlock = Some(200))
    config.isActiveAtBlock(0) shouldBe true
    config.isActiveAtBlock(199) shouldBe true
    config.isActiveAtBlock(200) shouldBe false
  }

  it should "match Mordor ECBP-1100 activation window" taggedAs (UnitTest, ConsensusTest) in {
    val config = MESSConfig(enabled = true, activationBlock = Some(2380000), deactivationBlock = Some(10400000))
    config.isActiveAtBlock(2379999) shouldBe false
    config.isActiveAtBlock(2380000) shouldBe true
    config.isActiveAtBlock(5000000) shouldBe true
    config.isActiveAtBlock(10399999) shouldBe true
    config.isActiveAtBlock(10400000) shouldBe false
  }

  it should "match ETC mainnet ECBP-1100 activation window" taggedAs (UnitTest, ConsensusTest) in {
    val config = MESSConfig(enabled = true, activationBlock = Some(11380000), deactivationBlock = Some(19250000))
    config.isActiveAtBlock(11379999) shouldBe false
    config.isActiveAtBlock(11380000) shouldBe true
    config.isActiveAtBlock(15000000) shouldBe true
    config.isActiveAtBlock(19249999) shouldBe true
    config.isActiveAtBlock(19250000) shouldBe false
  }
}

/** Tests for ArtificialFinality (ECIP-1100 cubic polynomial).
  *
  * Verifies the polynomial curve and reorg rejection logic against the ECIP-1100 specification
  * and cross-client reference implementations (core-geth, Besu).
  */
class ArtificialFinalitySpec extends AnyFlatSpec with Matchers {

  // Constants from ECIP-1100 spec
  private val Denominator = BigInt(128)
  private val Xcap = BigInt(25132) // floor(8000 * pi)

  "ArtificialFinality.polynomialV" should "return DENOMINATOR (128) for timeDelta=0" taggedAs (UnitTest, ConsensusTest) in {
    ArtificialFinality.polynomialV(BigInt(0)) shouldBe Denominator
  }

  it should "return DENOMINATOR (128) for very small timeDelta" taggedAs (UnitTest, ConsensusTest) in {
    val result = ArtificialFinality.polynomialV(BigInt(1))
    result shouldBe >=(Denominator)
    result shouldBe <=(Denominator + 1)
  }

  it should "increase monotonically up to xcap" taggedAs (UnitTest, ConsensusTest) in {
    val points = Seq(0, 100, 500, 1000, 2000, 5000, 10000, 15000, 20000, 25132)
    val values = points.map(x => ArtificialFinality.polynomialV(BigInt(x)))

    // Each value should be >= the previous
    values.zip(values.tail).foreach { case (a, b) =>
      b should be >= a
    }
  }

  it should "cap at xcap=25132 (~7 hours)" taggedAs (UnitTest, ConsensusTest) in {
    val atXcap = ArtificialFinality.polynomialV(Xcap)
    val beyondXcap = ArtificialFinality.polynomialV(Xcap + 10000)

    atXcap shouldBe beyondXcap
  }

  it should "reach approximately 31x multiplier at xcap" taggedAs (UnitTest, ConsensusTest) in {
    // At xcap: DENOMINATOR + HEIGHT = 128 + 3840 = 3968
    // 3968 / 128 = 31x multiplier
    val atXcap = ArtificialFinality.polynomialV(Xcap)
    atXcap shouldBe BigInt(3968)
  }

  it should "match Python reference values from ECIP-1100 spec" taggedAs (UnitTest, ConsensusTest) in {
    // Python: get_curve_function_numerator(0) = 128
    ArtificialFinality.polynomialV(BigInt(0)) shouldBe BigInt(128)

    // Python: get_curve_function_numerator(25132) = 128 + 3840 = 3968
    ArtificialFinality.polynomialV(BigInt(25132)) shouldBe BigInt(3968)

    // Python: get_curve_function_numerator(50000) = 3968 (capped at xcap)
    ArtificialFinality.polynomialV(BigInt(50000)) shouldBe BigInt(3968)
  }

  it should "be near 1x multiplier for timeDelta under ~200s" taggedAs (UnitTest, ConsensusTest) in {
    val at200 = ArtificialFinality.polynomialV(BigInt(200))
    at200 should be >= BigInt(128)
    at200 should be <= BigInt(135)
  }

  "ArtificialFinality.shouldRejectReorg" should "not reject when proposed TD equals local TD (timeDelta=0)" taggedAs (UnitTest, ConsensusTest) in {
    val result = ArtificialFinality.shouldRejectReorg(
      timeDeltaSeconds = 0,
      localSubchainTD = BigInt(1000),
      proposedSubchainTD = BigInt(1000)
    )
    result shouldBe false
  }

  it should "not reject when proposed TD exceeds local TD (timeDelta=0)" taggedAs (UnitTest, ConsensusTest) in {
    val result = ArtificialFinality.shouldRejectReorg(
      timeDeltaSeconds = 0,
      localSubchainTD = BigInt(1000),
      proposedSubchainTD = BigInt(1001)
    )
    result shouldBe false
  }

  it should "reject when proposed TD is less than local TD (timeDelta=0)" taggedAs (UnitTest, ConsensusTest) in {
    val result = ArtificialFinality.shouldRejectReorg(
      timeDeltaSeconds = 0,
      localSubchainTD = BigInt(1000),
      proposedSubchainTD = BigInt(999)
    )
    result shouldBe true
  }

  it should "require higher TD for longer time deltas" taggedAs (UnitTest, ConsensusTest) in {
    val localTD = BigInt(1000)

    // At 7 hours (~25132 seconds), polynomial reaches ~31x
    // So proposed needs >31x the local TD to not be rejected
    val rejectAt7Hours = ArtificialFinality.shouldRejectReorg(
      timeDeltaSeconds = 25132,
      localSubchainTD = localTD,
      proposedSubchainTD = localTD * 30 // 30x is not enough for 31x requirement
    )
    rejectAt7Hours shouldBe true

    // 31x should be enough
    val acceptAt7Hours = ArtificialFinality.shouldRejectReorg(
      timeDeltaSeconds = 25132,
      localSubchainTD = localTD,
      proposedSubchainTD = localTD * 31
    )
    acceptAt7Hours shouldBe false
  }

  it should "not require extra TD for very short time deltas" taggedAs (UnitTest, ConsensusTest) in {
    val result = ArtificialFinality.shouldRejectReorg(
      timeDeltaSeconds = 100,
      localSubchainTD = BigInt(1000),
      proposedSubchainTD = BigInt(1001)
    )
    result shouldBe false
  }

  it should "handle zero local TD" taggedAs (UnitTest, ConsensusTest) in {
    val result = ArtificialFinality.shouldRejectReorg(
      timeDeltaSeconds = 10000,
      localSubchainTD = BigInt(0),
      proposedSubchainTD = BigInt(1)
    )
    result shouldBe false
  }

  it should "handle large time deltas (capped at xcap)" taggedAs (UnitTest, ConsensusTest) in {
    val reject1 = ArtificialFinality.shouldRejectReorg(
      timeDeltaSeconds = 30000,
      localSubchainTD = BigInt(1000),
      proposedSubchainTD = BigInt(1000) * 30
    )
    val reject2 = ArtificialFinality.shouldRejectReorg(
      timeDeltaSeconds = 100000,
      localSubchainTD = BigInt(1000),
      proposedSubchainTD = BigInt(1000) * 30
    )

    reject1 shouldBe reject2
  }

  it should "match core-geth/Besu behavior for 1-hour test vector" taggedAs (UnitTest, ConsensusTest) in {
    val poly1h = ArtificialFinality.polynomialV(BigInt(3600))
    poly1h should be >= BigInt(300)
    poly1h should be <= BigInt(400)

    // At ~2.66x multiplier, 2x proposed should be rejected
    val rejectUnder3x = ArtificialFinality.shouldRejectReorg(
      timeDeltaSeconds = 3600,
      localSubchainTD = BigInt(1000),
      proposedSubchainTD = BigInt(2000)
    )
    rejectUnder3x shouldBe true

    // 3x proposed should be accepted
    val acceptOver3x = ArtificialFinality.shouldRejectReorg(
      timeDeltaSeconds = 3600,
      localSubchainTD = BigInt(1000),
      proposedSubchainTD = BigInt(3000)
    )
    acceptOver3x shouldBe false
  }
}
