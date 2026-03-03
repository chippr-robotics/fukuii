package com.chipprbots.ethereum.consensus.validators

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.consensus.difficulty.DifficultyCalculator
import com.chipprbots.ethereum.consensus.validators.BlockHeaderError._
import com.chipprbots.ethereum.consensus.validators.BlockHeaderValidator._
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.ForkBlockNumbers
import com.chipprbots.ethereum.utils.MonetaryPolicyConfig
import com.chipprbots.ethereum.testing.Tags._

// scalastyle:off magic.number
/** Validates gas limit boundary enforcement in the block header validator.
  *
  * Tests cover the ±parent/1024 bound, MinGasLimit (5000), MaxGasLimit (EIP-106),
  * and ETC-realistic 8M gas limit scenarios.
  *
  * Reference: Besu implicit gas limit tests + fukuii validateGasLimit() at
  * BlockHeaderValidatorSkeleton.scala:204-217
  */
class GasLimitValidationSpec extends AnyFlatSpec with Matchers {

  // Use a validator that mocks PoW and difficulty so we can test gas limit in isolation
  private object GasLimitTestValidator extends BlockHeaderValidatorSkeleton() {
    // Always return parent's difficulty so validateDifficulty passes
    override protected def difficulty: DifficultyCalculator = new DifficultyCalculator {
      def calculateDifficulty(blockNumber: BigInt, blockTimestamp: Long, parent: BlockHeader)(implicit
          blockchainConfig: BlockchainConfig
      ): BigInt = parent.difficulty
    }

    override protected def validateEvenMore(blockHeader: BlockHeader)(implicit
        blockchainConfig: BlockchainConfig
    ): Either[BlockHeaderError, BlockHeaderValid] =
      Right(BlockHeaderValid)
  }

  private implicit val blockchainConfig: BlockchainConfig = BlockchainConfig(
    forkBlockNumbers = ForkBlockNumbers.Empty.copy(
      frontierBlockNumber = 0,
      homesteadBlockNumber = 0,
      eip106BlockNumber = 0,
      difficultyBombRemovalBlockNumber = 0
    ),
    daoForkConfig = None,
    maxCodeSize = None,
    chainId = 0x3d,
    networkId = 1,
    monetaryPolicyConfig = MonetaryPolicyConfig(5000000, 0.2, 5000000000000000000L, 3000000000000000000L),
    customGenesisFileOpt = None,
    customGenesisJsonOpt = None,
    accountStartNonce = UInt256.Zero,
    bootstrapNodes = Set(),
    gasTieBreaker = false,
    ethCompatibleStorage = true,
    treasuryAddress = Address(0)
  )

  // Minimal valid parent/child pair — only fields relevant to gas limit validation
  private val parentHeader = BlockHeader(
    parentHash = ByteString(Hex.decode("00" * 32)),
    ommersHash = ByteString(Hex.decode("1dcc4de8dec75d7aab85b567b6ccd41ad312451b948a7413f0a142fd40d49347")),
    beneficiary = ByteString(Hex.decode("00" * 20)),
    stateRoot = ByteString(Hex.decode("00" * 32)),
    transactionsRoot = ByteString(Hex.decode("56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421")),
    receiptsRoot = ByteString(Hex.decode("56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421")),
    logsBloom = ByteString(Hex.decode("00" * 256)),
    difficulty = 1000,
    number = 100,
    gasLimit = 1024000, // 1024 * 1000 — easy math for bound calculations
    gasUsed = 0,
    unixTimestamp = 1000000,
    extraData = ByteString.empty,
    mixHash = ByteString(Hex.decode("00" * 32)),
    nonce = ByteString(Hex.decode("00" * 8))
  )

  /** Create a child header with the given gas limit. Difficulty is set to match parent
    * exactly (difficulty calculator returns parent difficulty for small block numbers
    * with difficulty bomb removed), and timestamp is parent+13 to get a 0 adjustment.
    */
  private def childWithGasLimit(gasLimit: BigInt): BlockHeader =
    parentHeader.copy(
      parentHash = parentHeader.hash,
      number = parentHeader.number + 1,
      gasLimit = gasLimit,
      unixTimestamp = parentHeader.unixTimestamp + 13,
      difficulty = parentHeader.difficulty
    )

  private def validate(child: BlockHeader): Either[BlockHeaderError, BlockHeaderValid] =
    GasLimitTestValidator.validate(child, parentHeader)

  // Parent gasLimit = 1024000, so bound = 1024000/1024 = 1000
  // Valid range: [1024000 - 1000 + 1, 1024000 + 1000 - 1] = [1023001, 1024999]
  // (strict inequality: gasLimitDiff < gasLimitDiffLimit)

  // ===== Gas Limit Within Bounds =====

  "GasLimitValidation" should "accept gas limit equal to parent (no change)" taggedAs (UnitTest, ConsensusTest) in {
    validate(childWithGasLimit(1024000)) shouldBe Right(BlockHeaderValid)
  }

  it should "accept gas limit within valid increase range" taggedAs (UnitTest, ConsensusTest) in {
    // parent + 500 (well within +999 bound)
    validate(childWithGasLimit(1024500)) shouldBe Right(BlockHeaderValid)
  }

  it should "accept gas limit within valid decrease range" taggedAs (UnitTest, ConsensusTest) in {
    // parent - 500 (well within -999 bound)
    validate(childWithGasLimit(1023500)) shouldBe Right(BlockHeaderValid)
  }

  // ===== Gas Limit at Exact Boundaries =====

  it should "accept gas limit at upper bound (parent + parent/1024 - 1)" taggedAs (UnitTest, ConsensusTest) in {
    // Upper bound: 1024000 + 1000 - 1 = 1024999
    validate(childWithGasLimit(1024999)) shouldBe Right(BlockHeaderValid)
  }

  it should "reject gas limit exceeding upper bound (parent + parent/1024)" taggedAs (UnitTest, ConsensusTest) in {
    // One above upper: 1024000 + 1000 = 1025000
    validate(childWithGasLimit(1025000)) shouldBe Left(HeaderGasLimitError)
  }

  it should "accept gas limit at lower bound (parent - parent/1024 + 1)" taggedAs (UnitTest, ConsensusTest) in {
    // Lower bound: 1024000 - 1000 + 1 = 1023001
    validate(childWithGasLimit(1023001)) shouldBe Right(BlockHeaderValid)
  }

  it should "reject gas limit below lower bound (parent - parent/1024)" taggedAs (UnitTest, ConsensusTest) in {
    // One below lower: 1024000 - 1000 = 1023000
    validate(childWithGasLimit(1023000)) shouldBe Left(HeaderGasLimitError)
  }

  // ===== MinGasLimit Enforcement =====

  it should "reject gas limit below MinGasLimit (5000)" taggedAs (UnitTest, ConsensusTest) in {
    // Even if within parent bounds, must be >= MinGasLimit
    val smallParent = parentHeader.copy(gasLimit = 5100, number = 100)
    val child = smallParent.copy(
      parentHash = smallParent.hash,
      number = 101,
      gasLimit = 4999,
      unixTimestamp = smallParent.unixTimestamp + 13,
      difficulty = smallParent.difficulty
    )
    GasLimitTestValidator.validate(child, smallParent) shouldBe Left(HeaderGasLimitError)
  }

  it should "accept gas limit at exactly MinGasLimit (5000)" taggedAs (UnitTest, ConsensusTest) in {
    // Parent at 5001, bound = 5001/1024 = 4, valid range = [4998, 5004]
    // gasLimit 5000 is within range AND >= MinGasLimit
    val smallParent = parentHeader.copy(gasLimit = 5001, number = 100)
    val child = smallParent.copy(
      parentHash = smallParent.hash,
      number = 101,
      gasLimit = 5000,
      unixTimestamp = smallParent.unixTimestamp + 13,
      difficulty = smallParent.difficulty
    )
    GasLimitTestValidator.validate(child, smallParent) shouldBe Right(BlockHeaderValid)
  }

  // ===== ETC-Realistic Gas Limit (8M target) =====

  it should "accept ETC-realistic gas limit change (parent=8M, within 7812 range)" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    // ETC mainnet targets 8M gas limit. Bound = 8000000/1024 = 7812
    val etcParent = parentHeader.copy(gasLimit = 8000000, number = 13000000)
    val child = etcParent.copy(
      parentHash = etcParent.hash,
      number = 13000001,
      gasLimit = 8007000, // within +7812 bound
      unixTimestamp = etcParent.unixTimestamp + 13,
      difficulty = etcParent.difficulty
    )
    GasLimitTestValidator.validate(child, etcParent) shouldBe Right(BlockHeaderValid)
  }

  // ===== MaxGasLimit (EIP-106) =====

  it should "reject gas limit above Long.MaxValue when EIP-106 is active" taggedAs (UnitTest, ConsensusTest) in {
    val largeParent = parentHeader.copy(gasLimit = Long.MaxValue, number = 100)
    val child = largeParent.copy(
      parentHash = largeParent.hash,
      number = 101,
      gasLimit = BigInt(Long.MaxValue) + 1,
      unixTimestamp = largeParent.unixTimestamp + 13,
      difficulty = largeParent.difficulty
    )
    GasLimitTestValidator.validate(child, largeParent) shouldBe Left(HeaderGasLimitError)
  }
}
// scalastyle:on magic.number
