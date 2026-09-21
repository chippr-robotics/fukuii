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
import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.domain.Difficulty
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.Timestamp
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefEmpty
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostOlympia
import com.chipprbots.ethereum.domain.GasAmount
import com.chipprbots.ethereum.nodebuilder.BlockchainConfigBuilder
import com.chipprbots.ethereum.testing.Tags.*
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
    with com.chipprbots.ethereum.TestInstanceConfigProvider:

  private val olympiaBlock: BigInt = BigInt(100)

  // olympiaGasLimitElasticity pinned to None ON PURPOSE rather than inherited from the parse
  // default: this suite asserts ETC gas-limit behaviour, where the standard +/-1/1024 window
  // applies at EVERY block including the Olympia activation block. Some(2) would move the
  // window to be centred on 2x the parent at that one block.
  implicit val config: BlockchainConfig = blockchainConfig.withUpdatedForkBlocks(
    _.copy(olympiaBlockNumber = olympiaBlock, olympiaGasLimitElasticity = None)
  )

  // ETH / Hive regime: baseFeeFloor = 0 (Big0). Under this floor the EIP-1559 decrease-branch
  // off-by-one becomes observable end-to-end through header validation.
  private val configFloorZero: BlockchainConfig = blockchainConfig
    .withUpdatedForkBlocks(_.copy(olympiaBlockNumber = olympiaBlock, olympiaGasLimitElasticity = None))
    .copy(baseFeeFloor = BigInt(0))

  private val InitialBaseFee: BigInt = BaseFeeCalculator.InitialBaseFee
  private val baseExtraData: ByteString = ByteString("test".getBytes)

  // Standard 1/1024 step from 8M toward 60M target (= 8M + 8M/1024 - 1 = 8,007,811)
  private val OneStepFrom8M: BigInt = BigInt(8_007_811)
  // Standard 1/1024 step from OneStepFrom8M (= 8,007,811 + 8,007,811/1024 - 1 = 8,015,630)
  private val TwoStepsFrom8M: BigInt = BigInt(8_015_630)

  private def preOlympiaHeader(number: BigInt, timestamp: Long = 1000L): BlockHeader =
    Fixtures.Blocks.ValidBlock.header.copy(
      number = BlockNumber(number),
      gasLimit = GasAmount(BigInt(8_000_000)),
      gasUsed = GasAmount.Zero,
      unixTimestamp = Timestamp(timestamp),
      difficulty = Difficulty.Zero,
      extraData = baseExtraData,
      extraFields = HefEmpty
    )

  private def firstOlympiaHeader(timestamp: Long, baseFee: BigInt): BlockHeader =
    Fixtures.Blocks.ValidBlock.header.copy(
      parentHash = preOlympiaHeader(olympiaBlock - 1).hash,
      number = BlockNumber(olympiaBlock),
      gasLimit = GasAmount(OneStepFrom8M),
      gasUsed = GasAmount.Zero,
      unixTimestamp = Timestamp(timestamp),
      difficulty = Difficulty.Zero,
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
          gasLimit = GasAmount(BigInt(8_000_000))
        )
        validate(child, parent) shouldBe Right(BlockHeaderValid)
      }

      "reject a header with HefPostOlympia (extraFields mismatch)" taggedAs (UnitTest, OlympiaTest, ConsensusTest) in {
        val parent = preOlympiaHeader(olympiaBlock - 2, timestamp = 1000L)
        val wrongChild = preOlympiaHeader(olympiaBlock - 1, timestamp = 2000L).copy(
          parentHash = parent.hash,
          gasLimit = GasAmount(BigInt(8_000_000)),
          extraFields = HefPostOlympia(InitialBaseFee)
        )
        val result = validate(wrongChild, parent)
        result shouldBe a[Left[?, ?]]
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
        result shouldBe a[Left[?, ?]]
        result.left.toOption.get shouldBe a[HeaderBaseFeeError]
      }

      "reject a gasLimit that violates the 1/1024 bound (no large jump allowed)" taggedAs (
        UnitTest,
        OlympiaTest,
        ConsensusTest
      ) in {
        val parent = preOlympiaHeader(olympiaBlock - 1, timestamp = 1000L)
        val bigJump = firstOlympiaHeader(timestamp = 2000L, baseFee = InitialBaseFee).copy(
          gasLimit = GasAmount(BigInt(16_000_000))
        )
        val result = validate(bigJump, parent)
        result shouldBe Left(HeaderGasLimitError)
      }

      "reject HefEmpty (missing baseFee at Olympia activation)" taggedAs (UnitTest, OlympiaTest, ConsensusTest) in {
        val parent = preOlympiaHeader(olympiaBlock - 1, timestamp = 1000L)
        val noFeeChild = preOlympiaHeader(olympiaBlock, timestamp = 2000L).copy(
          parentHash = parent.hash,
          gasLimit = GasAmount(OneStepFrom8M),
          extraFields = HefEmpty
        )
        val result = validate(noFeeChild, parent)
        result shouldBe a[Left[?, ?]]
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
          number = BlockNumber(olympiaBlock + 1),
          gasLimit = GasAmount(TwoStepsFrom8M),
          gasUsed = GasAmount.Zero,
          unixTimestamp = Timestamp(2000L),
          difficulty = Difficulty.Zero,
          extraData = baseExtraData,
          extraFields = HefPostOlympia(expectedBaseFee)
        )
        validate(secondBlock, firstBlock) shouldBe Right(BlockHeaderValid)
      }

      "reject HefEmpty after Olympia (missing baseFee)" taggedAs (UnitTest, OlympiaTest, ConsensusTest) in {
        val firstBlock = firstOlympiaHeader(timestamp = 1000L, baseFee = InitialBaseFee)
        val missingFee = Fixtures.Blocks.ValidBlock.header.copy(
          parentHash = firstBlock.hash,
          number = BlockNumber(olympiaBlock + 1),
          gasLimit = GasAmount(TwoStepsFrom8M),
          gasUsed = GasAmount.Zero,
          unixTimestamp = Timestamp(2000L),
          difficulty = Difficulty.Zero,
          extraData = baseExtraData,
          extraFields = HefEmpty
        )
        val result = validate(missingFee, firstBlock)
        result shouldBe a[Left[?, ?]]
        result.left.toOption.get shouldBe a[HeaderExtraFieldsError]
      }
    }

    // End-to-end reproduction of the EIP-1559 decrease-branch off-by-one (block-146 / consume-rlp
    // block-1 rejection). Under baseFeeFloor = 0 an empty parent block (gasUsed = 0) holds its tiny
    // base fee: the raw 1/8 delta integer-floors to 0, so the child must declare the SAME base fee.
    // The historical bug applied .max(1) on the decrease branch, expecting 6 instead of 7 and
    // rejecting the valid child with INVALID_BASE_FEE_PER_GAS (have 7, want 6, parentGasUsed 0).
    "block is post-Olympia under baseFeeFloor = 0 (ETH/Hive regime)" should {
      "accept a child holding baseFee = 7 when its empty parent's baseFee = 7 (decrease holds, no off-by-one)" taggedAs (
        UnitTest,
        OlympiaTest,
        ConsensusTest
      ) in {
        val hiveGasLimit = BigInt(37699104) // gasTarget = 18_849_552
        val emptyParent = Fixtures.Blocks.ValidBlock.header.copy(
          number = BlockNumber(olympiaBlock),
          gasLimit = GasAmount(hiveGasLimit),
          gasUsed = GasAmount.Zero,
          unixTimestamp = Timestamp(1000L),
          difficulty = Difficulty.Zero,
          extraData = baseExtraData,
          extraFields = HefPostOlympia(BigInt(7))
        )
        // Sanity: the held base fee the calculator derives for the child is exactly 7 (not 6).
        BaseFeeCalculator.calcBaseFee(emptyParent, configFloorZero) shouldBe BigInt(7)

        val child = Fixtures.Blocks.ValidBlock.header.copy(
          parentHash = emptyParent.hash,
          number = BlockNumber(olympiaBlock + 1),
          gasLimit = GasAmount(hiveGasLimit), // constant gasLimit: |diff| = 0 < parent/1024, valid
          gasUsed = GasAmount.Zero,
          unixTimestamp = Timestamp(2000L),
          difficulty = Difficulty.Zero,
          extraData = baseExtraData,
          extraFields = HefPostOlympia(BigInt(7))
        )
        MockedPowBlockHeaderValidator.validate(child, emptyParent)(configFloorZero) shouldBe Right(BlockHeaderValid)
      }
    }
  }
// scalastyle:on magic.number
