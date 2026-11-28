package com.chipprbots.ethereum.consensus.validators.std

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.consensus.mining.GetBlockHeaderByHash
import com.chipprbots.ethereum.consensus.mining.GetNBlocksBack
import com.chipprbots.ethereum.consensus.pow.blocks.OmmersSeqEnc
import com.chipprbots.ethereum.consensus.pow.validators.OmmersValidator
import com.chipprbots.ethereum.consensus.pow.validators.OmmersValidator.OmmersError
import com.chipprbots.ethereum.consensus.pow.validators.OmmersValidator.OmmersValid
import com.chipprbots.ethereum.consensus.pow.validators.ValidatorsExecutor
import com.chipprbots.ethereum.consensus.validators.BlockHeaderError
import com.chipprbots.ethereum.consensus.validators.BlockHeaderValid
import com.chipprbots.ethereum.consensus.validators.BlockHeaderValidator
import com.chipprbots.ethereum.consensus.validators.Validators
import com.chipprbots.ethereum.consensus.validators.std.StdBlockValidator.*
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.Timestamp
import com.chipprbots.ethereum.ledger.BlockExecutionError.ValidationBeforeExecError
import com.chipprbots.ethereum.ledger.BlockExecutionSuccess
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config
import com.chipprbots.ethereum.utils.ForkTimestamps
import com.chipprbots.ethereum.utils.NetworkType

/** EIP-7934 MAX_RLP_BLOCK_SIZE (8,388,608 bytes): invalid from ETH's Osaka fork on, at the block's own timestamp, and
  * never on ETC.
  *
  * B-431: the cap used to run unconditionally inside `validateHeaderAndBody`, so fukuii rejected the VALID first block
  * of EEST tests@v21.0.0 `fork_transition_block_rlp_limit[...-at_fork_*]` (PragueToOsakaAtTime15k): 8,388,609 bytes at
  * timestamp 14,999, one second before Osaka. go-ethereum: `IsOsaka(number, time) && block.Size() > MaxBlockSize` in
  * `BlockValidator.ValidateBody`; execution-specs: only in `osaka/fork.py` and later.
  *
  * The blocks here are block 3,125,369 with one ommer whose extraData pads the block to an exact RLP size, and the
  * header's ommersHash recomputed so the body still matches the header. Header and ommer validation are stubbed to
  * accept: the size rule is the only thing under test.
  */
// scalastyle:off magic.number
class BlockRLPSizeCapSpec extends AnyFlatSpec with Matchers:

  private val Cap: Long = 8_388_608L
  private val OsakaTs: Long = 15_000L

  private val ethOsakaAt15k: BlockchainConfig = Config.blockchains.blockchainConfig.copy(
    networkType = NetworkType.ETH,
    forkTimestamps = ForkTimestamps(
      shanghaiTimestamp = Some(0L),
      cancunTimestamp = Some(0L),
      pragueTimestamp = Some(0L),
      osakaTimestamp = Some(OsakaTs)
    )
  )

  private val etc: BlockchainConfig = Config.blockchains.blockchainConfig.copy(
    networkType = NetworkType.ETC,
    forkTimestamps = ForkTimestamps()
  )

  private object AcceptingHeaders extends BlockHeaderValidator:
    override def validate(blockHeader: BlockHeader, getBlockHeaderByHash: GetBlockHeaderByHash)(implicit
        blockchainConfig: BlockchainConfig
    ): Either[BlockHeaderError, BlockHeaderValid] = Right(BlockHeaderValid)
    override def validateHeaderOnly(blockHeader: BlockHeader)(implicit
        blockchainConfig: BlockchainConfig
    ): Either[BlockHeaderError, BlockHeaderValid] = Right(BlockHeaderValid)

  private object AcceptingOmmers extends OmmersValidator:
    override def validate(
        parentHash: ByteString,
        blockNumber: BigInt,
        ommers: Seq[BlockHeader],
        getBlockByHash: GetBlockHeaderByHash,
        getNBlocksBack: GetNBlocksBack
    )(implicit blockchainConfig: BlockchainConfig): Either[OmmersError, OmmersValid] = Right(OmmersValid)

  private val stdValidators = new StdValidators(StdBlockValidator, AcceptingHeaders, StdSignedTransactionValidator)
  private val executorValidators = ValidatorsExecutor(AcceptingHeaders, AcceptingOmmers)

  /** Both production implementations of validateBlockBeforeExecution: StdValidators and ValidatorsExecutor (the one
    * every mining protocol builds, EngineApi included).
    */
  private val bothPaths: Seq[(String, Validators)] =
    Seq("StdValidators" -> stdValidators, "ValidatorsExecutor" -> executorValidators)

  private def beforeExecution(validators: Validators, block: Block, config: BlockchainConfig) =
    validators.validateBlockBeforeExecution(block, _ => None, (_, _) => Nil)(using config)

  private val base: Block = Fixtures.Blocks.ValidBlock.block

  private def withPadding(timestamp: Long, padding: Int): Block =
    val ommer = base.header.copy(extraData = ByteString(new Array[Byte](padding)))
    val ommers = Seq(ommer)
    val header = base.header.copy(
      unixTimestamp = Timestamp(timestamp),
      ommersHash = BlockHash(ByteString(kec256(ommers.toBytes: Array[Byte])))
    )
    Block(header, base.body.copy(uncleNodesList = ommers))

  /** The block at `timestamp` whose RLP is exactly `size` bytes. Past 64 KiB every RLP length prefix involved is a
    * fixed width, so the size moves byte-for-byte with the padding and two corrections land on it.
    */
  private def blockOfSize(timestamp: Long, size: Long): Block =
    val block = Iterator
      .iterate(withPadding(timestamp, 0))(b =>
        withPadding(timestamp, b.body.uncleNodesList.head.extraData.length + (size - Block.size(b)).toInt)
      )
      .drop(3)
      .next()
    Block.size(block) shouldBe size
    block

  "BlockRLPSizeCap" should "be EIP-7934's MAX_RLP_BLOCK_SIZE, 10 MiB minus a 2 MiB safety margin" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    StdBlockValidator.BlockRLPSizeCap shouldBe Cap
    StdBlockValidator.BlockRLPSizeCap shouldBe 10L * 1024 * 1024 - 2L * 1024 * 1024
  }

  "validateBlockBeforeExecution on an ETH chain (Osaka at 15,000)" should
    "accept an 8,388,609-byte block one second before Osaka (EEST fork_transition_block_rlp_limit block 1)" taggedAs (
      UnitTest,
      ConsensusTest
    ) in {
      val block = blockOfSize(OsakaTs - 1, Cap + 1)
      bothPaths.foreach { (name, validators) =>
        withClue(s"$name: ") {
          beforeExecution(validators, block, ethOsakaAt15k) shouldBe Right(BlockExecutionSuccess)
        }
      }
    }

  it should "reject an 8,388,609-byte block at the Osaka timestamp with BlockRLPSizeError" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val block = blockOfSize(OsakaTs, Cap + 1)
    bothPaths.foreach { (name, validators) =>
      withClue(s"$name: ") {
        beforeExecution(validators, block, ethOsakaAt15k) shouldBe
          Left(ValidationBeforeExecError(BlockRLPSizeError(Cap + 1, Cap)))
      }
    }
  }

  it should "accept a block of exactly 8,388,608 bytes at the Osaka timestamp" taggedAs (UnitTest, ConsensusTest) in {
    val block = blockOfSize(OsakaTs, Cap)
    bothPaths.foreach { (name, validators) =>
      withClue(s"$name: ") {
        beforeExecution(validators, block, ethOsakaAt15k) shouldBe Right(BlockExecutionSuccess)
      }
    }
  }

  "validateBlockBeforeExecution on an ETC chain" should "apply no size cap (core-geth has none, no ECIP adopts it)" taggedAs (
    UnitTest,
    ConsensusTest,
    OlympiaTest
  ) in {
    etc.isOsakaTimestamp(Timestamp(Long.MaxValue)) shouldBe false
    val block = blockOfSize(base.header.unixTimestamp.toLong, Cap + 1)
    bothPaths.foreach { (name, validators) =>
      withClue(s"$name: ") {
        beforeExecution(validators, block, etc) shouldBe Right(BlockExecutionSuccess)
      }
    }
  }

  "validateHeaderAndBody" should "match an over-cap body to its header: it is not where the size rule lives" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    // Sync and export callers use it to ask "does this body belong to this header"; they must not apply fork rules.
    val block = blockOfSize(OsakaTs, Cap + 1)
    StdBlockValidator.validateHeaderAndBody(block.header, block.body) shouldBe Right(BlockValid)
  }

  it should "pass for normal blocks" taggedAs (UnitTest, ConsensusTest) in {
    StdBlockValidator.validateHeaderAndBody(base.header, base.body) shouldBe Right(BlockValid)
  }

  "validateBlockRLPSize" should "be the Osaka-gated rule on its own" taggedAs (UnitTest, ConsensusTest) in {
    val over = blockOfSize(OsakaTs, Cap + 1)
    StdBlockValidator.validateBlockRLPSize(over)(using ethOsakaAt15k) shouldBe Left(BlockRLPSizeError(Cap + 1, Cap))
    StdBlockValidator.validateBlockRLPSize(over)(using etc) shouldBe Right(BlockValid)
    val preOsaka = over.copy(header = over.header.copy(unixTimestamp = Timestamp(OsakaTs - 1)))
    StdBlockValidator.validateBlockRLPSize(preOsaka)(using ethOsakaAt15k) shouldBe Right(BlockValid)
  }

  "BlockRLPSizeError" should "contain both size and cap" taggedAs (UnitTest, ConsensusTest) in {
    val error = BlockRLPSizeError(9_000_000L, 8_388_608L)
    error.size shouldBe 9_000_000L
    error.cap shouldBe 8_388_608L
  }
