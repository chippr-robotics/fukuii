package com.chipprbots.ethereum.consensus.validators.std

import org.apache.pekko.util.ByteString

import scala.annotation.unused

import org.bouncycastle.util.encoders.Hex

import com.chipprbots.ethereum.consensus.mining.GetBlockHeaderByHash
import com.chipprbots.ethereum.consensus.mining.GetNBlocksBack
import com.chipprbots.ethereum.consensus.validators.*
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.Receipt
import com.chipprbots.ethereum.ledger.BlockExecutionError
import com.chipprbots.ethereum.ledger.BlockExecutionError.ValidationAfterExecError
import com.chipprbots.ethereum.ledger.BlockExecutionError.ValidationBeforeExecError
import com.chipprbots.ethereum.ledger.BlockExecutionSuccess
import com.chipprbots.ethereum.utils.BlockchainConfig

/** Implements validators that adhere to the original
  * [[com.chipprbots.ethereum.consensus.validators.Validators Validators]] interface.
  *
  * @see
  *   [[com.chipprbots.ethereum.consensus.pow.validators.StdValidatorsExecutor StdEthashValidators]] for the
  *   PoW-specific counterpart.
  */
final class StdValidators(
    val blockValidator: BlockValidator,
    val blockHeaderValidator: BlockHeaderValidator,
    val signedTransactionValidator: SignedTransactionValidator
) extends Validators:

  def validateBlockBeforeExecution(
      block: Block,
      getBlockHeaderByHash: GetBlockHeaderByHash,
      getNBlocksBack: GetNBlocksBack
  )(implicit blockchainConfig: BlockchainConfig): Either[ValidationBeforeExecError, BlockExecutionSuccess] =
    StdValidators.validateBlockBeforeExecution(
      self = this,
      block = block,
      getBlockHeaderByHash = getBlockHeaderByHash,
      getNBlocksBack = getNBlocksBack
    )

  def validateBlockAfterExecution(
      block: Block,
      stateRootHash: ByteString,
      receipts: Seq[Receipt],
      gasUsed: BigInt
  )(implicit blockchainConfig: BlockchainConfig): Either[BlockExecutionError, BlockExecutionSuccess] =
    StdValidators.validateBlockAfterExecution(
      self = this,
      block = block,
      stateRootHash = stateRootHash,
      receipts = receipts,
      gasUsed = gasUsed
    )

object StdValidators:
  def validateBlockBeforeExecution(
      self: Validators,
      block: Block,
      getBlockHeaderByHash: GetBlockHeaderByHash,
      @unused getNBlocksBack: GetNBlocksBack
  )(implicit blockchainConfig: BlockchainConfig): Either[ValidationBeforeExecError, BlockExecutionSuccess] =

    val header = block.header
    val body = block.body

    val result = for
      _ <- self.blockHeaderValidator.validate(header, getBlockHeaderByHash)
      _ <- self.blockValidator.validateHeaderAndBody(header, body)
    yield BlockExecutionSuccess

    result.left.map(ValidationBeforeExecError.apply)

  def validateBlockAfterExecution(
      self: Validators,
      block: Block,
      stateRootHash: ByteString,
      receipts: Seq[Receipt],
      gasUsed: BigInt
  ): Either[BlockExecutionError, BlockExecutionSuccess] =

    val header = block.header
    val blockAndReceiptsValidation = self.blockValidator.validateBlockAndReceipts(header, receipts)

    // DO NOT REORDER THE gasUsed AND stateRoot CHECKS BELOW. The gasUsed check MUST come first and MUST return
    // early. `InvalidChainReporter.provesConsensusInvalid` reports a state-root mismatch to the Engine API as a
    // proven consensus failure, and the ONLY reason that is safe is this ordering: reaching the state-root branch
    // proves gasUsed already matched, which proves execution was complete. A node missing contract code executes a
    // contract call as a plain transfer (`InMemoryWorldStateProxy.getCode` returns ByteString.empty rather than
    // throwing), which perturbs gas and would trip the FIRST branch — where the mismatch stays ambiguous and is
    // disambiguated by `BlockImporter.findMissingContractCode`. Swap these two and a partially-synced node starts
    // permanently marking honest blocks invalid through the state-root branch, and refuses its own canonical chain.
    if header.gasUsed.value != gasUsed then
      Left(ValidationAfterExecError(s"Block has invalid gas used, expected ${header.gasUsed} but got $gasUsed"))
    else if header.stateRoot.value != stateRootHash then
      Left(ValidationAfterExecError(s"Block has invalid state root hash, expected ${Hex
          .toHexString(header.stateRoot.toArray)} but got ${Hex.toHexString(stateRootHash.toArray)}"))
    else
      blockAndReceiptsValidation match
        case Left(err) => Left(ValidationAfterExecError(err.toString))
        case _         => Right(BlockExecutionSuccess)
