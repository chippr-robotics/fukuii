package com.chipprbots.ethereum.consensus.validators

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.consensus.difficulty.DifficultyCalculator
import com.chipprbots.ethereum.consensus.eip1559.BaseFeeCalculator
import com.chipprbots.ethereum.consensus.validators.BlockHeaderError._
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields._
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.ForkBlockNumbers
import com.chipprbots.ethereum.utils.MonetaryPolicyConfig
import com.chipprbots.ethereum.testing.Tags._

// scalastyle:off magic.number
/** Validates block header acceptance/rejection at the Olympia fork boundary (block N-1, N, N+1).
  *
  * Tests the interaction between validateExtraFields() and validateBaseFee() in
  * BlockHeaderValidatorSkeleton for the EIP-1559 activation transition.
  *
  * Reference: All 5 reference clients (go-ethereum, core-geth, Besu, Erigon, Nethermind)
  * enforce baseFee presence/absence at fork boundaries. Fukuii H-014 backlog item.
  */
class OlympiaForkBoundarySpec extends AnyFlatSpec with Matchers {

  private val OlympiaBlock: BigInt = 100

  // Validator that skips PoW but validates everything else
  private object ForkBoundaryValidator extends BlockHeaderValidatorSkeleton() {
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

  implicit private val blockchainConfig: BlockchainConfig = BlockchainConfig(
    forkBlockNumbers = ForkBlockNumbers.Empty.copy(
      frontierBlockNumber = 0,
      homesteadBlockNumber = 0,
      eip106BlockNumber = 0,
      difficultyBombRemovalBlockNumber = 0,
      olympiaBlockNumber = OlympiaBlock
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
    ethCompatibleStorage = true
  )

  private val GasLimit: BigInt = 8000000
  private val GasUsed: BigInt = 4000000 // 50% of gas limit = exactly at target

  // Pre-Olympia parent (block N-2): no baseFee, HefEmpty
  private val preOlympiaParent = BlockHeader(
    parentHash = ByteString(Hex.decode("00" * 32)),
    ommersHash = ByteString(Hex.decode("1dcc4de8dec75d7aab85b567b6ccd41ad312451b948a7413f0a142fd40d49347")),
    beneficiary = ByteString(Hex.decode("00" * 20)),
    stateRoot = ByteString(Hex.decode("00" * 32)),
    transactionsRoot = ByteString(Hex.decode("56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421")),
    receiptsRoot = ByteString(Hex.decode("56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421")),
    logsBloom = ByteString(Hex.decode("00" * 256)),
    difficulty = 1000,
    number = OlympiaBlock - 2,
    gasLimit = GasLimit,
    gasUsed = GasUsed,
    unixTimestamp = 1000000,
    extraData = ByteString.empty,
    mixHash = ByteString(Hex.decode("00" * 32)),
    nonce = ByteString(Hex.decode("00" * 8)),
    extraFields = HefEmpty
  )

  private def makeChild(
      parent: BlockHeader,
      baseFee: Option[BigInt] = None,
      gasLimit: BigInt = GasLimit,
      gasUsed: BigInt = GasUsed
  ): BlockHeader = {
    val extraFields = baseFee match {
      case Some(fee) => HefPostOlympia(fee)
      case None      => HefEmpty
    }
    parent.copy(
      parentHash = parent.hash,
      number = parent.number + 1,
      gasLimit = gasLimit,
      gasUsed = gasUsed,
      unixTimestamp = parent.unixTimestamp + 13,
      difficulty = parent.difficulty,
      extraFields = extraFields
    )
  }

  private def validate(child: BlockHeader, parent: BlockHeader): Either[BlockHeaderError, BlockHeaderValid] =
    ForkBoundaryValidator.validate(child, parent)

  // ===== Pre-Olympia Blocks (before fork) =====

  "OlympiaForkBoundary" should "accept pre-Olympia block without baseFee (N-1)" taggedAs (
    UnitTest,
    OlympiaTest,
    ConsensusTest
  ) in {
    val preOlympiaChild = makeChild(preOlympiaParent)
    preOlympiaChild.number shouldBe OlympiaBlock - 1
    preOlympiaChild.extraFields shouldBe HefEmpty

    validate(preOlympiaChild, preOlympiaParent) shouldBe Right(BlockHeaderValid)
  }

  it should "reject pre-Olympia block WITH baseFee (HefPostOlympia before activation)" taggedAs (
    UnitTest,
    OlympiaTest,
    ConsensusTest
  ) in {
    val invalidChild = makeChild(preOlympiaParent, baseFee = Some(1000000000))
    invalidChild.number shouldBe OlympiaBlock - 1

    val result = validate(invalidChild, preOlympiaParent)
    result shouldBe a[Left[_, _]]
    result.left.getOrElse(null) shouldBe a[HeaderExtraFieldsError]
  }

  // ===== Fork Block (block N) =====

  it should "accept fork block with correct initial baseFee (1 gwei)" taggedAs (
    UnitTest,
    OlympiaTest,
    ConsensusTest
  ) in {
    // Parent is block N-1 (pre-Olympia, no baseFee)
    val lastPreOlympia = makeChild(preOlympiaParent)
    lastPreOlympia.number shouldBe OlympiaBlock - 1

    // Fork block N must have baseFee = InitialBaseFee (1 gwei)
    val forkBlock = makeChild(lastPreOlympia, baseFee = Some(BaseFeeCalculator.InitialBaseFee))
    forkBlock.number shouldBe OlympiaBlock

    validate(forkBlock, lastPreOlympia) shouldBe Right(BlockHeaderValid)
  }

  it should "reject fork block without baseFee (HefEmpty at activation)" taggedAs (
    UnitTest,
    OlympiaTest,
    ConsensusTest
  ) in {
    val lastPreOlympia = makeChild(preOlympiaParent)
    val invalidForkBlock = makeChild(lastPreOlympia, baseFee = None)
    invalidForkBlock.number shouldBe OlympiaBlock

    val result = validate(invalidForkBlock, lastPreOlympia)
    result shouldBe a[Left[_, _]]
    result.left.getOrElse(null) shouldBe a[HeaderExtraFieldsError]
  }

  it should "reject fork block with wrong baseFee (not InitialBaseFee)" taggedAs (
    UnitTest,
    OlympiaTest,
    ConsensusTest
  ) in {
    val lastPreOlympia = makeChild(preOlympiaParent)
    // Wrong baseFee: 2 gwei instead of 1 gwei
    val invalidForkBlock = makeChild(lastPreOlympia, baseFee = Some(2000000000))
    invalidForkBlock.number shouldBe OlympiaBlock

    val result = validate(invalidForkBlock, lastPreOlympia)
    result shouldBe a[Left[_, _]]
    result.left.getOrElse(null) shouldBe a[HeaderBaseFeeError]
  }

  // ===== Post-Olympia Blocks (after fork) =====

  it should "accept post-Olympia block with correctly calculated baseFee (N+1)" taggedAs (
    UnitTest,
    OlympiaTest,
    ConsensusTest
  ) in {
    val lastPreOlympia = makeChild(preOlympiaParent)
    val forkBlock = makeChild(lastPreOlympia, baseFee = Some(BaseFeeCalculator.InitialBaseFee))

    // Block N+1: baseFee calculated from fork block's gas usage
    val expectedBaseFee = BaseFeeCalculator.calcBaseFee(forkBlock, blockchainConfig)
    val postForkBlock = makeChild(forkBlock, baseFee = Some(expectedBaseFee))
    postForkBlock.number shouldBe OlympiaBlock + 1

    validate(postForkBlock, forkBlock) shouldBe Right(BlockHeaderValid)
  }

  it should "reject post-Olympia block without baseFee" taggedAs (
    UnitTest,
    OlympiaTest,
    ConsensusTest
  ) in {
    val lastPreOlympia = makeChild(preOlympiaParent)
    val forkBlock = makeChild(lastPreOlympia, baseFee = Some(BaseFeeCalculator.InitialBaseFee))
    val invalidPostFork = makeChild(forkBlock, baseFee = None)
    invalidPostFork.number shouldBe OlympiaBlock + 1

    val result = validate(invalidPostFork, forkBlock)
    result shouldBe a[Left[_, _]]
    result.left.getOrElse(null) shouldBe a[HeaderExtraFieldsError]
  }

  it should "reject post-Olympia block with wrong baseFee" taggedAs (
    UnitTest,
    OlympiaTest,
    ConsensusTest
  ) in {
    val lastPreOlympia = makeChild(preOlympiaParent)
    val forkBlock = makeChild(lastPreOlympia, baseFee = Some(BaseFeeCalculator.InitialBaseFee))
    // Wrong baseFee
    val invalidPostFork = makeChild(forkBlock, baseFee = Some(999999999))
    invalidPostFork.number shouldBe OlympiaBlock + 1

    val result = validate(invalidPostFork, forkBlock)
    result shouldBe a[Left[_, _]]
    result.left.getOrElse(null) shouldBe a[HeaderBaseFeeError]
  }

  // ===== BaseFee Dynamic Adjustment =====

  it should "increase baseFee when parent gasUsed exceeds target" taggedAs (
    UnitTest,
    OlympiaTest,
    ConsensusTest
  ) in {
    val lastPreOlympia = makeChild(preOlympiaParent)
    // Fork block at target (50% gas used) → baseFee stays 1 gwei
    val forkBlock = makeChild(lastPreOlympia, baseFee = Some(BaseFeeCalculator.InitialBaseFee))

    // Block N+1: parent (fork block) used 50% = exactly at target → baseFee unchanged
    val expectedBaseFee = BaseFeeCalculator.calcBaseFee(forkBlock, blockchainConfig)
    expectedBaseFee shouldBe BaseFeeCalculator.InitialBaseFee // at target = no change

    // Now create a high-gas block (100% gas used = 2x target)
    val highGasBlock = makeChild(
      forkBlock,
      baseFee = Some(expectedBaseFee),
      gasUsed = GasLimit // 100% = double the target
    )

    // Block N+2: parent used 100% → baseFee increases
    val nextExpectedBaseFee = BaseFeeCalculator.calcBaseFee(highGasBlock, blockchainConfig)
    nextExpectedBaseFee should be > expectedBaseFee

    val nextBlock = makeChild(highGasBlock, baseFee = Some(nextExpectedBaseFee))
    validate(nextBlock, highGasBlock) shouldBe Right(BlockHeaderValid)
  }

  it should "decrease baseFee when parent gasUsed is below target" taggedAs (
    UnitTest,
    OlympiaTest,
    ConsensusTest
  ) in {
    val lastPreOlympia = makeChild(preOlympiaParent)
    val forkBlock = makeChild(lastPreOlympia, baseFee = Some(BaseFeeCalculator.InitialBaseFee))

    // Block at target → baseFee = 1 gwei
    val normalBlock = makeChild(forkBlock, baseFee = Some(BaseFeeCalculator.InitialBaseFee))

    // Block N+2 with 0 gas used → baseFee decreases
    val emptyBlock = makeChild(normalBlock, baseFee = Some(BaseFeeCalculator.InitialBaseFee), gasUsed = 0)
    val nextExpectedBaseFee = BaseFeeCalculator.calcBaseFee(emptyBlock, blockchainConfig)
    nextExpectedBaseFee should be < BaseFeeCalculator.InitialBaseFee

    val nextBlock = makeChild(emptyBlock, baseFee = Some(nextExpectedBaseFee))
    validate(nextBlock, emptyBlock) shouldBe Right(BlockHeaderValid)
  }

  // ===== RLP Encoding Symmetry at Fork Boundary =====

  it should "encode and decode pre-Olympia header correctly (15 RLP fields)" taggedAs (
    UnitTest,
    OlympiaTest
  ) in {
    import com.chipprbots.ethereum.domain.BlockHeaderImplicits._
    val original = makeChild(preOlympiaParent)
    original.extraFields shouldBe HefEmpty

    val encoded = original.toBytes
    val decoded = encoded.toBlockHeader

    decoded.number shouldBe original.number
    decoded.extraFields shouldBe HefEmpty
    decoded.baseFee shouldBe None
    decoded.hash shouldBe original.hash
  }

  it should "encode and decode post-Olympia header correctly (16 RLP fields)" taggedAs (
    UnitTest,
    OlympiaTest
  ) in {
    import com.chipprbots.ethereum.domain.BlockHeaderImplicits._
    val lastPreOlympia = makeChild(preOlympiaParent)
    val forkBlock = makeChild(lastPreOlympia, baseFee = Some(BaseFeeCalculator.InitialBaseFee))
    forkBlock.extraFields shouldBe HefPostOlympia(BaseFeeCalculator.InitialBaseFee)

    val encoded = forkBlock.toBytes
    val decoded = encoded.toBlockHeader

    decoded.number shouldBe forkBlock.number
    decoded.extraFields shouldBe HefPostOlympia(BaseFeeCalculator.InitialBaseFee)
    decoded.baseFee shouldBe Some(BaseFeeCalculator.InitialBaseFee)
    decoded.hash shouldBe forkBlock.hash
  }
}
