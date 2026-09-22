package com.chipprbots.ethereum.ledger

import org.apache.pekko.util.ByteString

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.BlockHelpers
import com.chipprbots.ethereum.Mocks.MockVM
import com.chipprbots.ethereum.Mocks.MockValidatorsAlwaysSucceed
import com.chipprbots.ethereum.consensus.mining.GetBlockHeaderByHash
import com.chipprbots.ethereum.consensus.mining.GetNBlocksBack
import com.chipprbots.ethereum.consensus.mining.TestMining
import com.chipprbots.ethereum.consensus.pow.validators.OmmersValidator
import com.chipprbots.ethereum.consensus.pow.validators.OmmersValidator.OmmersValid
import com.chipprbots.ethereum.consensus.validators.BlockHeaderError
import com.chipprbots.ethereum.consensus.validators.BlockHeaderValid
import com.chipprbots.ethereum.consensus.validators.BlockHeaderValidator
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.ledger.BlockExecutionError.ValidationBeforeExecError
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig

/** Regression cover for the pre-execution validation gap on the bulk import path.
  *
  * `BlockExecution.executeAndValidateBlocks` drives every block through `executeAndValidateBlock(block,
  * alreadyValidated = true)`, and at `BlockExecution.scala:53` that flag skips `validateBlockBeforeExecution` — the
  * ONLY caller of `blockHeaderValidator.validate` and of `ommersValidator.validate` on this path (see
  * `ValidatorsExecutor.validateBlockBeforeExecution`).
  *
  * `executeAndValidateBlocks` has two callers, `ConsensusImpl.importToTop` and `ConsensusImpl.importToNewBranch`, which
  * between them serve bulk p2p import and Engine API `newPayload`. While the flag stands, a peer-supplied block reaches
  * execution with its header entirely unchecked — no PoW, no difficulty, no gasLimit bound, no ommer rules — and is
  * ACCEPTED as long as it executes to the state root it claims. That is what these specs pin: each builds a chain that
  * executes cleanly, marks one block's header (or ommer set) as invalid, and asserts the block is not executed and not
  * persisted.
  *
  * `alreadyValidated = true` was a correct contract in the original Mantis, where blocks were imported one at a time
  * through a path that always validated first. Commits 6ad1dec (bulk `evaluateBranch`) and e168554 (the extends-best
  * skip in `ConsensusAdapter`) removed the guarantee the flag depended on without retiring the flag.
  */
// scalastyle:off magic.number
class BlockExecutionPreValidationSpec
    extends AnyWordSpec
    with Matchers
    with org.scalatest.OptionValues
    with org.scalamock.scalatest.MockFactory:

  /** Rejects exactly one block number at the header check; everything else succeeds. */
  private class FailHeaderAt(number: BigInt) extends MockValidatorsAlwaysSucceed:
    override val blockHeaderValidator: BlockHeaderValidator = new BlockHeaderValidator:
      private def check(h: BlockHeader): Either[BlockHeaderError, BlockHeaderValid] =
        if h.number.value == number then Left(BlockHeaderError.HeaderDifficultyError) else Right(BlockHeaderValid)

      override def validate(
          blockHeader: BlockHeader,
          getBlockHeaderByHash: GetBlockHeaderByHash
      )(implicit blockchainConfig: BlockchainConfig): Either[BlockHeaderError, BlockHeaderValid] = check(blockHeader)

      override def validateHeaderOnly(
          blockHeader: BlockHeader
      )(implicit blockchainConfig: BlockchainConfig): Either[BlockHeaderError, BlockHeaderValid] = check(blockHeader)

    // Post-execution validation is stubbed to succeed so the ONLY thing these specs
    // can discriminate on is whether the BEFORE-execution check ran. Without this,
    // the generated chain fails StdValidators' state-root check and every block is
    // rejected for the wrong reason.
    override def validateBlockAfterExecution(
        block: Block,
        stateRootHash: ByteString,
        receipts: Seq[Receipt],
        gasUsed: BigInt
    )(implicit blockchainConfig: BlockchainConfig): Either[BlockExecutionError, BlockExecutionSuccess] =
      Right(BlockExecutionSuccess)

  /** Rejects exactly one block number at the ommer check; everything else succeeds. */
  private class FailOmmersAt(number: BigInt) extends MockValidatorsAlwaysSucceed:
    override val ommersValidator: OmmersValidator = new OmmersValidator:
      def validate(
          parentHash: ByteString,
          blockNumber: BigInt,
          ommers: Seq[BlockHeader],
          getBlockByHash: GetBlockHeaderByHash,
          getNBlocksBack: GetNBlocksBack
      )(implicit blockchainConfig: BlockchainConfig): Either[OmmersValidator.OmmersError, OmmersValid] =
        if blockNumber == number then Left(OmmersValidator.OmmersError.OmmersLengthError)
        else Right(OmmersValid)

    // Post-execution validation is stubbed to succeed so the ONLY thing these specs
    // can discriminate on is whether the BEFORE-execution check ran. Without this,
    // the generated chain fails StdValidators' state-root check and every block is
    // rejected for the wrong reason.
    override def validateBlockAfterExecution(
        block: Block,
        stateRootHash: ByteString,
        receipts: Seq[Receipt],
        gasUsed: BigInt
    )(implicit blockchainConfig: BlockchainConfig): Either[BlockExecutionError, BlockExecutionSuccess] =
      Right(BlockExecutionSuccess)

  "BlockExecution.executeAndValidateBlocks" should {

    "reject a block whose header the configured validator rejects, without executing it" taggedAs
      (UnitTest, ConsensusTest) in new BlockchainSetup:
        // A chain that executes cleanly end to end under MockValidatorsAlwaysSucceed —
        // the same recipe BlockExecutionSpec uses for its long-branch case. Without a
        // pre-execution header check every one of these blocks is accepted.
        val chain: List[Block] = BlockHelpers.generateChain(3, validBlockParentBlock)
        val invalidAt: Block = chain(1)

        val mockVm = new MockVM(c =>
          createResult(
            context = c,
            gasUsed = UInt256(0),
            gasLimit = UInt256(defaultGasLimit),
            gasRefund = UInt256.Zero,
            logs = defaultLogs,
            addressesToDelete = defaultAddressesToDelete
          )
        )
        val newMining: TestMining =
          mining.withVM(mockVm).withValidators(new FailHeaderAt(invalidAt.number.value))
        override lazy val blockValidation =
          new BlockValidation(newMining, blockchainReader, BlockQueue(blockchainReader, syncConfig))
        override lazy val blockExecution =
          new BlockExecution(
            blockchain,
            blockchainReader,
            blockchainWriter,
            blockchainStorages.evmCodeStorage,
            newMining.blockPreparator,
            blockValidation
          )

        val (blocks, error) = blockExecution.executeAndValidateBlocks(chain, defaultChainWeight)

        withClue(
          s"executed ${blocks.size} of ${chain.size} blocks, error=$error — execution must stop at the header-invalid block: "
        ) {
          blocks.map(_.block) shouldBe chain.take(1)
        }
        error.value shouldBe a[ValidationBeforeExecError]
        withClue("a header-invalid block must not be persisted: ") {
          blockchainReader.getBlockByHash(invalidAt.hash) shouldBe None
        }

    "reject a block whose ommers the configured validator rejects, without executing it" taggedAs
      (UnitTest, ConsensusTest) in new BlockchainSetup:
        val chain: List[Block] = BlockHelpers.generateChain(3, validBlockParentBlock)
        val invalidAt: Block = chain(1)

        val mockVm = new MockVM(c =>
          createResult(
            context = c,
            gasUsed = UInt256(0),
            gasLimit = UInt256(defaultGasLimit),
            gasRefund = UInt256.Zero,
            logs = defaultLogs,
            addressesToDelete = defaultAddressesToDelete
          )
        )
        val newMining: TestMining =
          mining.withVM(mockVm).withValidators(new FailOmmersAt(invalidAt.number.value))
        override lazy val blockValidation =
          new BlockValidation(newMining, blockchainReader, BlockQueue(blockchainReader, syncConfig))
        override lazy val blockExecution =
          new BlockExecution(
            blockchain,
            blockchainReader,
            blockchainWriter,
            blockchainStorages.evmCodeStorage,
            newMining.blockPreparator,
            blockValidation
          )

        val (blocks, error) = blockExecution.executeAndValidateBlocks(chain, defaultChainWeight)

        withClue(
          s"executed ${blocks.size} of ${chain.size} blocks, error=$error — execution must stop at the ommer-invalid block: "
        ) {
          blocks.map(_.block) shouldBe chain.take(1)
        }
        error.value shouldBe a[ValidationBeforeExecError]
  }
