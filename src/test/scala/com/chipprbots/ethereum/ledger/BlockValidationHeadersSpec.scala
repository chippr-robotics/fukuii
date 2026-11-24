package com.chipprbots.ethereum.ledger

import org.apache.pekko.util.ByteString

import scala.collection.mutable

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.Mocks
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.consensus.mining.GetBlockHeaderByHash
import com.chipprbots.ethereum.consensus.validators.BlockHeaderError
import com.chipprbots.ethereum.consensus.validators.BlockHeaderError.HeaderDifficultyError
import com.chipprbots.ethereum.consensus.validators.BlockHeaderError.HeaderParentNotFoundError
import com.chipprbots.ethereum.consensus.validators.BlockHeaderValid
import com.chipprbots.ethereum.consensus.validators.BlockHeaderValidator
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig

/** The headers of a branch validated without executing it (spec 018 C5a part 6b): each by the rule an import validates
  * it by, against a parent found in the branch or in what is stored.
  */
class BlockValidationHeadersSpec extends AnyFlatSpec with Matchers:

  private class Rig(bad: Option[BigInt] = None) extends EphemBlockchainTestSetup:
    val asked: mutable.ArrayBuffer[BigInt] = mutable.ArrayBuffer.empty
    override lazy val validators: Mocks.MockValidatorsAlwaysSucceed = new Mocks.MockValidatorsAlwaysSucceed:
      override val blockHeaderValidator: BlockHeaderValidator = new BlockHeaderValidator:
        override def validate(
            blockHeader: BlockHeader,
            getBlockHeaderByHash: GetBlockHeaderByHash
        )(implicit blockchainConfig: BlockchainConfig): Either[BlockHeaderError, BlockHeaderValid] =
          asked += blockHeader.number.value
          if getBlockHeaderByHash(blockHeader.parentHash.value).isEmpty then Left(HeaderParentNotFoundError)
          else if bad.contains(blockHeader.number.value) then Left(HeaderDifficultyError)
          else Right(BlockHeaderValid)
        override def validateHeaderOnly(blockHeader: BlockHeader)(implicit
            blockchainConfig: BlockchainConfig
        ): Either[BlockHeaderError, BlockHeaderValid] = Right(BlockHeaderValid)
    val validation = new BlockValidation(mining, blockchainReader, blockQueue)
    val fork: BlockHeader = Fixtures.Blocks.ValidBlock.header.copy(number = BlockNumber(4))
    def branch(blocks: Int, on: BlockHash = fork.hash): List[Block] =
      (0 until blocks)
        .foldLeft(List.empty[Block]) { (made, i) =>
          val header = Fixtures.Blocks.ValidBlock.header
            .copy(parentHash = made.headOption.fold(on)(_.header.hash), number = BlockNumber(5 + i))
          Block(header, BlockBody.empty) :: made
        }
        .reverse

  "The headers of a branch" should "each be validated against a parent in the branch or in what is stored" taggedAs UnitTest in {
    val rig = new Rig
    rig.blockchainWriter.storeBlockHeader(rig.fork).commit()
    rig.validation.firstInvalidHeader(rig.branch(3))(using rig.blockchainConfig) shouldBe None
    rig.asked.toList shouldBe List(BigInt(5), BigInt(6), BigInt(7))
  }

  it should "name the first that is not valid, and why" taggedAs UnitTest in {
    val rig = new Rig(bad = Some(6))
    rig.blockchainWriter.storeBlockHeader(rig.fork).commit()
    val found = rig.validation.firstInvalidHeader(rig.branch(3))(using rig.blockchainConfig)
    found.map(_._1.number.value) shouldBe Some(BigInt(6))
    found.map(_._2) shouldBe Some(HeaderDifficultyError.toString)
    // nothing above it is looked at
    rig.asked.toList shouldBe List(BigInt(5), BigInt(6))
  }

  it should "not take a branch that stands on a block this node does not have" taggedAs UnitTest in {
    val rig = new Rig
    val nowhere = BlockHash(ByteString(Array.fill[Byte](32)(9)))
    rig.validation
      .firstInvalidHeader(rig.branch(2, on = nowhere))(using rig.blockchainConfig)
      .map(_._1.number.value) shouldBe
      Some(BigInt(5))
  }

  // forge, B-306: a header is cheap to write with any difficulty and a seal no one mined; a seal is costly to check
  it should "check the rules that cost nothing on every header before the seal of any" taggedAs UnitTest in {
    import com.chipprbots.ethereum.consensus.validators.BlockHeaderValidatorSkeleton
    class Skeleton(badRules: Option[BigInt], badSeal: Option[BigInt]) extends EphemBlockchainTestSetup:
      val checks: mutable.ArrayBuffer[String] = mutable.ArrayBuffer.empty
      override lazy val validators: Mocks.MockValidatorsAlwaysSucceed = new Mocks.MockValidatorsAlwaysSucceed:
        override val blockHeaderValidator: BlockHeaderValidator = new BlockHeaderValidatorSkeleton:
          override def validateParentRules(blockHeader: BlockHeader, parentHeader: BlockHeader)(implicit
              blockchainConfig: BlockchainConfig
          ): Either[BlockHeaderError, BlockHeaderValid] =
            checks += s"rules:${blockHeader.number.value}"
            if badRules.contains(blockHeader.number.value) then Left(HeaderDifficultyError) else Right(BlockHeaderValid)
          override protected def validateEvenMore(blockHeader: BlockHeader)(implicit
              blockchainConfig: BlockchainConfig
          ): Either[BlockHeaderError, BlockHeaderValid] =
            checks += s"seal:${blockHeader.number.value}"
            if badSeal.contains(blockHeader.number.value) then Left(HeaderParentNotFoundError)
            else Right(BlockHeaderValid)
      val validation = new BlockValidation(mining, blockchainReader, blockQueue)
      val fork: BlockHeader = Fixtures.Blocks.ValidBlock.header.copy(number = BlockNumber(4))
      blockchainWriter.storeBlockHeader(fork).commit()
      val branch: List[Block] =
        (0 until 3)
          .foldLeft(List.empty[Block]) { (made, i) =>
            val header = Fixtures.Blocks.ValidBlock.header
              .copy(parentHash = made.headOption.fold(fork.hash)(_.header.hash), number = BlockNumber(5 + i))
            Block(header, BlockBody.empty) :: made
          }
          .reverse
      def found: Option[BigInt] = validation.firstInvalidHeader(branch)(using blockchainConfig).map(_._1.number.value)

    // the last header's difficulty is not what its parent gives: no seal is checked, of any header
    val cheap = new Skeleton(badRules = Some(7), badSeal = None)
    cheap.found shouldBe Some(BigInt(7))
    cheap.checks.toList shouldBe List("rules:5", "rules:6", "rules:7")

    // the rules hold: then each header as an import validates it, the seal with it, up to the first that fails
    val withSeals = new Skeleton(badRules = None, badSeal = Some(6))
    withSeals.found shouldBe Some(BigInt(6))
    withSeals.checks.toList shouldBe List("rules:5", "rules:6", "rules:7", "rules:5", "seal:5", "rules:6", "seal:6")
  }

  // B-140: an orphan announce's header, checked by what it says of itself with the node's real Ethash validator
  "A header checked alone" should "pass with a seal that was mined, and fail with one that was not" taggedAs (
    UnitTest,
    ConsensusTest,
    SlowTest
  ) in {
    import com.chipprbots.ethereum.consensus.pow.validators.PoWBlockHeaderValidator
    import com.chipprbots.ethereum.consensus.pow.validators.PoWBlockHeaderValidatorSpec
    class Real extends EphemBlockchainTestSetup:
      override lazy val validators: Mocks.MockValidatorsAlwaysSucceed = new Mocks.MockValidatorsAlwaysSucceed:
        override val blockHeaderValidator: BlockHeaderValidator = PoWBlockHeaderValidator
      val validation = new BlockValidation(mining, blockchainReader, blockQueue)
    val rig = new Real
    given BlockchainConfig = PoWBlockHeaderValidatorSpec.blockchainConfig
    val mined = PoWBlockHeaderValidatorSpec.validEthashBlockHeader

    rig.validation.headerAloneError(mined) shouldBe None
    rig.validation.headerAloneError(mined.copy(nonce = ByteString(Array.fill[Byte](8)(0)))) shouldBe
      Some(BlockHeaderError.HeaderPoWError.toString)
  }
