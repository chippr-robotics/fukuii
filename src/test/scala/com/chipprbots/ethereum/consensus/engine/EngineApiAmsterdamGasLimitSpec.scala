package com.chipprbots.ethereum.consensus.engine

import org.apache.pekko.util.ByteString

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.consensus.blocks.GasLimitCalculator
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.domain.GasAmount
import com.chipprbots.ethereum.ledger.EestBlockchainReplay
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config

// scalastyle:off magic.number
/** The gas limit of a payload engine_forkchoiceUpdated builds ([[EngineApiService.enginePayloadGasLimit]]).
  *
  * From Amsterdam, PayloadAttributesV4's `targetGasLimit` drives it as go-ethereum's miner does (miner/worker.go
  * `prepareWork` at 920c077): `GasLimit = core.CalcGasLimit(parent.GasLimit, targetGasLimit)`. The vectors are
  * go-ethereum's own `TestCalcGasLimit` (core/block_validator_test.go) plus three at the 5,000 floor, derived by hand
  * from `CalcGasLimit`, which raises a target below MinGasLimit to 5,000 before stepping. Without a target, and before
  * Amsterdam, the parent's gas limit is kept.
  */
class EngineApiAmsterdamGasLimitSpec extends AnyWordSpec with Matchers:

  private def configFor(network: String): BlockchainConfig =
    EestBlockchainReplay
      .configFor(Config.blockchains.blockchainConfig, network, BigInt(1))
      .fold(err => throw new IllegalStateException(err), identity)

  /** Every fork at genesis: any timestamp is Amsterdam. */
  private val amsterdam = configFor("Amsterdam")

  /** BPO2 at genesis, Amsterdam at 15,000. */
  private val transition = configFor("BPO2ToAmsterdamAtTime15k")

  private def service(config: BlockchainConfig) = new EngineApiService(null, null, null, null, None)(config, null)

  private def parent(gasLimit: BigInt): BlockHeader =
    Fixtures.Blocks.ValidBlock.header.copy(number = BlockNumber(100), gasLimit = GasAmount(gasLimit))

  private def attributes(timestamp: Long, targetGasLimit: Option[BigInt]): PayloadAttributes = PayloadAttributes(
    timestamp = timestamp,
    prevRandao = ByteString(new Array[Byte](32)),
    suggestedFeeRecipient = Address(0),
    withdrawals = Some(Nil),
    parentBeaconBlockRoot = Some(ByteString(new Array[Byte](32))),
    slotNumber = Some(BigInt(1)),
    targetGasLimit = targetGasLimit
  )

  private def gasLimit(config: BlockchainConfig, parentGasLimit: BigInt, timestamp: Long, target: Option[BigInt]) =
    service(config).enginePayloadGasLimit(parent(parentGasLimit), attributes(timestamp, target)).value

  "enginePayloadGasLimit at Amsterdam" should {

    "step toward targetGasLimit as go-ethereum's CalcGasLimit does (TestCalcGasLimit)" taggedAs (
      UnitTest,
      ConsensusTest
    ) in {
      // (parent gas limit, target, child gas limit): per parent, go-ethereum's increase, decrease (target 0), small
      // decrease, small increase and no-change cases. A step is at most parent / 1024 - 1.
      val vectors = Seq[(BigInt, BigInt, BigInt)](
        (20_000_000, 40_000_000, 20_019_530),
        (20_000_000, 0, 19_980_470),
        (20_000_000, 19_999_999, 19_999_999),
        (20_000_000, 20_000_001, 20_000_001),
        (20_000_000, 20_000_000, 20_000_000),
        (40_000_000, 80_000_000, 40_039_061),
        (40_000_000, 0, 39_960_939),
        (40_000_000, 39_999_999, 39_999_999),
        (40_000_000, 40_000_001, 40_000_001),
        (40_000_000, 40_000_000, 40_000_000)
      )
      vectors.foreach { case (parentGasLimit, target, expected) =>
        withClue(s"parent $parentGasLimit, target $target: ") {
          gasLimit(amsterdam, parentGasLimit, 12, Some(target)) shouldBe expected
        }
      }
    }

    "converge a 30M chain on Lighthouse's 60M target block by block" taggedAs (UnitTest, ConsensusTest) in {
      val steps = Iterator.iterate(BigInt(30_000_000))(gasLimit(amsterdam, _, 12, Some(BigInt(60_000_000))))
      steps.take(4).toSeq shouldBe Seq[BigInt](30_000_000, 30_029_295, 30_058_619, 30_087_972)
    }

    "raise a target below 5,000 to 5,000 before stepping, as CalcGasLimit does" taggedAs (
      UnitTest,
      ConsensusTest
    ) in {
      gasLimit(amsterdam, 5_000, 12, Some(0)) shouldBe 5_000
      gasLimit(amsterdam, 5_002, 12, Some(1_000)) shouldBe 5_000
      gasLimit(amsterdam, 6_000, 12, Some(0)) shouldBe 5_996
      // The shared calculator the ETC miner uses has no such floor, and keeps it: it would lead the proposer below the
      // protocol minimum here, so the engine path does not hand it a target under 5,000.
      GasLimitCalculator.calcGasLimit(5_000, 0) shouldBe 4_997
    }

    "keep the parent's gas limit when the attributes carry no target, as the engine path always has" taggedAs (
      UnitTest,
      ConsensusTest
    ) in {
      gasLimit(amsterdam, 30_000_000, 12, None) shouldBe 30_000_000
    }
  }

  "enginePayloadGasLimit before Amsterdam" should {

    "keep the parent's gas limit whatever the attributes carry, and use the target from the first Amsterdam block" taggedAs (
      UnitTest,
      ConsensusTest
    ) in {
      gasLimit(transition, 30_000_000, 14_999, Some(60_000_000)) shouldBe 30_000_000
      gasLimit(transition, 30_000_000, 14_999, None) shouldBe 30_000_000
      gasLimit(transition, 30_000_000, 15_000, Some(60_000_000)) shouldBe 30_029_295
    }
  }
