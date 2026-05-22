package com.chipprbots.ethereum.consensus.validators

import org.apache.pekko.util.ByteString

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.consensus.eip1559.BaseFeeCalculator
import com.chipprbots.ethereum.consensus.pow.validators.MockedPowBlockHeaderValidator
import com.chipprbots.ethereum.consensus.validators.BlockHeaderError.HeaderBaseFeeError
import com.chipprbots.ethereum.consensus.validators.BlockHeaderError.HeaderExtraFieldsError
import com.chipprbots.ethereum.consensus.validators.BlockHeaderError.HeaderGasLimitError
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefEmpty
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostOlympia
import com.chipprbots.ethereum.nodebuilder.BlockchainConfigBuilder
import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.utils.BlockchainConfig

/** Tests that BlockHeaderValidatorSkeleton enforces extraFields and baseFee at the Olympia fork boundary.
  *
  * Uses MockedPowBlockHeaderValidator (skips PoW) with difficulty=0 headers (skips difficulty validation) to isolate
  * the fork-gating logic.
  *
  * Gas limit: standard ±1/1024 per block applies at ALL blocks including the Olympia activation. ETC Olympia converges
  * 8M → 60M gradually over ~2,055 blocks; there is no one-shot doubling at activation.
  */
// scalastyle:off magic.number
class OlympiaBlockHeaderValidationSpec
    extends AnyWordSpec
    with Matchers
    with BlockchainConfigBuilder
    with com.chipprbots.ethereum.TestInstanceConfigProvider {

  private val olympiaBlock: BigInt = BigInt(100)

  implicit val config: BlockchainConfig = blockchainConfig.withUpdatedForkBlocks(
    _.copy(olympiaBlockNumber = olympiaBlock)
  )

  private val InitialBaseFee: BigInt = BaseFeeCalculator.InitialBaseFee
  private val baseExtraData: ByteString = ByteString("test".getBytes)

  // Standard 1/1024 step from 8M toward 60M target (= 8M + 8M/1024 - 1 = 8,007,811)
  private val OneStepFrom8M: BigInt = BigInt(8_007_811)
  // Standard 1/1024 step from OneStepFrom8M (= 8,007,811 + 8,007,811/1024 - 1 = 8,015,630)
  private val TwoStepsFrom8M: BigInt = BigInt(8_015_630)

  /** Minimal valid pre-Olympia header; difficulty=0 bypasses difficulty validation. */
  private def preOlympiaHeader(number: BigInt, timestamp: Long = 1000L): BlockHeader =
    Fixtures.Blocks.ValidBlock.header.copy(
      number = number,
      gasLimit = BigInt(8_000_000),
      gasUsed = 0,
      unixTimestamp = timestamp,
      difficulty = 0,
      extraData = baseExtraData,
      extraFields = HefEmpty
    )

  /** First Olympia block: one ±1/1024 step from parent (8M), baseFee = InitialBaseFee. */
  private def firstOlympiaHeader(timestamp: Long, baseFee: BigInt): BlockHeader =
    Fixtures.Blocks.ValidBlock.header.copy(
      parentHash = preOlympiaHeader(olympiaBlock - 1).hash,
      number = olympiaBlock,
      gasLimit = OneStepFrom8M,
      gasUsed = 0,
      unixTimestamp = timestamp,
      difficulty = 0,
      extraData = baseExtraData,
      extraFields = HefPostOlympia(baseFee)
    )

  private def validate(header: BlockHeader, parent: BlockHeader) =
    MockedPowBlockHeaderValidator.validate(header, parent)

  "OlympiaBlockHeaderValidation" when {

    "block is pre-Olympia" should {
      "accept a header with HefEmpty" taggedAs (UnitTest, OlympiaTest, ConsensusTest) in {
        val parent = preOlympiaHeader(olympiaBlock - 2, timestamp = 1000L)
        val child = preOlympiaHeader(olympiaBlock - 1, timestamp = 2000L).copy(
          parentHash = parent.hash,
          gasLimit = BigInt(8_000_000)
        )
        validate(child, parent) shouldBe Right(BlockHeaderValid)
      }

      "reject a header with HefPostOlympia (extraFields mismatch)" taggedAs (UnitTest, OlympiaTest, ConsensusTest) in {
        val parent = preOlympiaHeader(olympiaBlock - 2, timestamp = 1000L)
        val wrongChild = preOlympiaHeader(olympiaBlock - 1, timestamp = 2000L).copy(
          parentHash = parent.hash,
          gasLimit = BigInt(8_000_000),
          extraFields = HefPostOlympia(InitialBaseFee)
        )
        val result = validate(wrongChild, parent)
        result shouldBe a[Left[_, _]]
        result.left.toOption.get shouldBe a[HeaderExtraFieldsError]
      }
    }

    "block is the first Olympia block" should {
      "accept HefPostOlympia with correct InitialBaseFee and 1/1024 gas step" taggedAs (
        UnitTest,
        OlympiaTest,
        ConsensusTest
      ) in {
        val parent = preOlympiaHeader(olympiaBlock - 1, timestamp = 1000L)
        val firstBlock = firstOlympiaHeader(timestamp = 2000L, baseFee = InitialBaseFee)
        validate(firstBlock, parent) shouldBe Right(BlockHeaderValid)
      }

      "reject HefPostOlympia with wrong baseFee" taggedAs (UnitTest, OlympiaTest, ConsensusTest) in {
        val parent = preOlympiaHeader(olympiaBlock - 1, timestamp = 1000L)
        val wrongFee = firstOlympiaHeader(timestamp = 2000L, baseFee = InitialBaseFee + 1)
        val result = validate(wrongFee, parent)
        result shouldBe a[Left[_, _]]
        result.left.toOption.get shouldBe a[HeaderBaseFeeError]
      }

      "reject a gasLimit that violates the 1/1024 bound (no large jump allowed)" taggedAs (
        UnitTest,
        OlympiaTest,
        ConsensusTest
      ) in {
        val parent = preOlympiaHeader(olympiaBlock - 1, timestamp = 1000L)
        // 16M is 2× parent — valid under the old ETH-London logic but not under standard 1/1024
        val bigJump = firstOlympiaHeader(timestamp = 2000L, baseFee = InitialBaseFee).copy(
          gasLimit = BigInt(16_000_000)
        )
        val result = validate(bigJump, parent)
        result shouldBe Left(HeaderGasLimitError)
      }

      "reject HefEmpty (missing baseFee at Olympia activation)" taggedAs (UnitTest, OlympiaTest, ConsensusTest) in {
        val parent = preOlympiaHeader(olympiaBlock - 1, timestamp = 1000L)
        val noFeeChild = preOlympiaHeader(olympiaBlock, timestamp = 2000L).copy(
          parentHash = parent.hash,
          gasLimit = OneStepFrom8M,
          extraFields = HefEmpty
        )
        val result = validate(noFeeChild, parent)
        result shouldBe a[Left[_, _]]
        // HefEmpty at Olympia block → extraFields gate fires before baseFee gate
        result.left.toOption.get shouldBe a[HeaderExtraFieldsError]
      }
    }

    "block is post-Olympia (Olympia→Olympia transition)" should {
      "accept HefPostOlympia with correctly derived baseFee and 1/1024 gas step" taggedAs (
        UnitTest,
        OlympiaTest,
        ConsensusTest
      ) in {
        val firstBlock = firstOlympiaHeader(timestamp = 1000L, baseFee = InitialBaseFee)
        val expectedBaseFee = BaseFeeCalculator.calcBaseFee(firstBlock, config)
        val secondBlock = Fixtures.Blocks.ValidBlock.header.copy(
          parentHash = firstBlock.hash,
          number = olympiaBlock + 1,
          gasLimit = TwoStepsFrom8M,
          gasUsed = 0,
          unixTimestamp = 2000L,
          difficulty = 0,
          extraData = baseExtraData,
          extraFields = HefPostOlympia(expectedBaseFee)
        )
        validate(secondBlock, firstBlock) shouldBe Right(BlockHeaderValid)
      }

      "reject HefEmpty after Olympia (missing baseFee)" taggedAs (UnitTest, OlympiaTest, ConsensusTest) in {
        val firstBlock = firstOlympiaHeader(timestamp = 1000L, baseFee = InitialBaseFee)
        val missingFee = Fixtures.Blocks.ValidBlock.header.copy(
          parentHash = firstBlock.hash,
          number = olympiaBlock + 1,
          gasLimit = TwoStepsFrom8M,
          gasUsed = 0,
          unixTimestamp = 2000L,
          difficulty = 0,
          extraData = baseExtraData,
          extraFields = HefEmpty
        )
        val result = validate(missingFee, firstBlock)
        result shouldBe a[Left[_, _]]
        result.left.toOption.get shouldBe a[HeaderExtraFieldsError]
      }
    }
  }
}
// scalastyle:on magic.number
