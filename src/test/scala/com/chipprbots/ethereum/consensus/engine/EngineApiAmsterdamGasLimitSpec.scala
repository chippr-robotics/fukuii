package com.chipprbots.ethereum.consensus.engine

import org.apache.pekko.util.ByteString

import com.typesafe.config.ConfigFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.consensus.blocks.GasLimitCalculator
import com.chipprbots.ethereum.consensus.mining.MiningConfig
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
  * From Amsterdam it is go-ethereum's (miner/worker.go `prepareWork` at 920c077, lines 292-300): `GasLimit =
  * core.CalcGasLimit(parent.GasLimit, desired)`, where `desired` is PayloadAttributesV4's `targetGasLimit` when present
  * and otherwise the node's gas ceiling (go-ethereum's `GasCeil`, default 60,000,000 at miner/miner.go:57; fukuii's
  * `mining.gas-limit-target`). The vectors are go-ethereum's own `TestCalcGasLimit` (core/block_validator_test.go) plus
  * ones at the 5,000 floor and toward a ceiling, computed with a port of `CalcGasLimit` that reproduces those test
  * values; `CalcGasLimit` raises a desired limit below MinGasLimit to 5,000 before stepping. Before Amsterdam the
  * parent's gas limit is kept.
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

  private def service(config: BlockchainConfig, ceiling: BigInt) =
    new EngineApiService(null, null, null, null, None, builderGasCeil = ceiling)(config, null)

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

  private def gasLimit(
      config: BlockchainConfig,
      parentGasLimit: BigInt,
      timestamp: Long,
      target: Option[BigInt],
      ceiling: BigInt = EngineApiService.DefaultBuilderGasCeil
  ): BigInt =
    service(config, ceiling).enginePayloadGasLimit(parent(parentGasLimit), attributes(timestamp, target)).value

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

    "step toward the node's gas ceiling when the attributes carry no target, as go-ethereum's GasCeil" taggedAs (
      UnitTest,
      ConsensusTest
    ) in {
      // The default ceiling is go-ethereum's, 60,000,000: (parent gas limit, child gas limit).
      EngineApiService.DefaultBuilderGasCeil shouldBe BigInt(60_000_000)
      val towardDefault = Seq[(BigInt, BigInt)](
        (5_000, 5_003),
        (20_000_000, 20_019_530),
        (30_000_000, 30_029_295),
        (45_000_000, 45_043_944),
        (60_000_000, 60_000_000),
        (70_000_000, 69_931_642)
      )
      towardDefault.foreach { case (parentGasLimit, expected) =>
        withClue(s"parent $parentGasLimit: ")(gasLimit(amsterdam, parentGasLimit, 12, None) shouldBe expected)
      }
      // A configured ceiling, from either side.
      val toward36M = Seq[(BigInt, BigInt)](
        (30_000_000, 30_029_295),
        (36_000_000, 36_000_000),
        (40_000_000, 39_960_939),
        (45_000_000, 44_956_056)
      )
      toward36M.foreach { case (parentGasLimit, expected) =>
        withClue(s"parent $parentGasLimit, ceiling 36M: ") {
          gasLimit(amsterdam, parentGasLimit, 12, None, ceiling = 36_000_000) shouldBe expected
        }
      }
      // The attributes' target wins over the ceiling.
      gasLimit(amsterdam, 40_000_000, 12, Some(60_000_000), ceiling = 36_000_000) shouldBe 40_039_061
      // A ceiling below 5,000 is raised to 5,000, as a target is.
      gasLimit(amsterdam, 5_000, 12, None, ceiling = 0) shouldBe 5_000
      gasLimit(amsterdam, 5_002, 12, None, ceiling = 0) shouldBe 5_000
      gasLimit(amsterdam, 6_000, 12, None, ceiling = 0) shouldBe 5_996
    }

    "take the ceiling from mining.gas-limit-target, which ships with go-ethereum's 60,000,000" taggedAs (
      UnitTest,
      ConsensusTest
    ) in {
      // What NodeBuilder hands the service: `mining.config.generic.gasLimitTarget`.
      MiningConfig(Config.config).gasLimitTarget shouldBe EngineApiService.DefaultBuilderGasCeil
      val overridden =
        MiningConfig(ConfigFactory.parseString("mining.gas-limit-target = 36000000").withFallback(Config.config))
      overridden.gasLimitTarget shouldBe BigInt(36_000_000)
      gasLimit(amsterdam, 45_000_000, 12, None, ceiling = overridden.gasLimitTarget) shouldBe 44_956_056
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
