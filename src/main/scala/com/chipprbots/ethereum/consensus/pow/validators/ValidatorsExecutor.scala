package com.chipprbots.ethereum.consensus.pow.validators

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.consensus.mining.GetBlockHeaderByHash
import com.chipprbots.ethereum.consensus.mining.GetNBlocksBack
import com.chipprbots.ethereum.consensus.mining.Protocol
import com.chipprbots.ethereum.consensus.validators.BlockHeaderValidator
import com.chipprbots.ethereum.consensus.validators.Validators
import com.chipprbots.ethereum.consensus.validators.std.StdBlockValidator
import com.chipprbots.ethereum.consensus.validators.std.StdSignedTransactionValidator
import com.chipprbots.ethereum.consensus.validators.std.StdValidators
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.Receipt
import com.chipprbots.ethereum.ledger.BlockExecutionError
import com.chipprbots.ethereum.ledger.BlockExecutionError.ValidationBeforeExecError
import com.chipprbots.ethereum.ledger.BlockExecutionSuccess
import com.chipprbots.ethereum.utils.BlockchainConfig

trait ValidatorsExecutor extends Validators {
  def ommersValidator: OmmersValidator

  def validateBlockBeforeExecution(
      block: Block,
      getBlockHeaderByHash: GetBlockHeaderByHash,
      getNBlocksBack: GetNBlocksBack
  )(implicit
      blockchainConfig: BlockchainConfig
  ): Either[BlockExecutionError.ValidationBeforeExecError, BlockExecutionSuccess] =
    ValidatorsExecutor.validateBlockBeforeExecution(
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
  )(implicit
      blockchainConfig: BlockchainConfig
  ): Either[BlockExecutionError, BlockExecutionSuccess] =
    ValidatorsExecutor.validateBlockAfterExecution(
      self = this,
      block = block,
      stateRootHash = stateRootHash,
      receipts = receipts,
      gasUsed = gasUsed
    )
}

object ValidatorsExecutor {
  def apply(protocol: Protocol): ValidatorsExecutor = {
    val blockHeaderValidator: BlockHeaderValidator = protocol match {
      case Protocol.MockedPow     => MockedPowBlockHeaderValidator
      case Protocol.PoW           => PoWBlockHeaderValidator
      case Protocol.RestrictedPoW => RestrictedEthashBlockHeaderValidator
    }

    new StdValidatorsExecutor(
      StdBlockValidator,
      blockHeaderValidator,
      StdSignedTransactionValidator,
      new StdOmmersValidator(blockHeaderValidator)
    )
  }

  // Created only for testing purposes, shouldn't be used in production code.
  // Connected with: https://github.com/ethereum/tests/issues/480
  def apply(blockHeaderValidator: BlockHeaderValidator): ValidatorsExecutor =
    new StdValidatorsExecutor(
      StdBlockValidator,
      blockHeaderValidator,
      StdSignedTransactionValidator,
      new StdOmmersValidator(blockHeaderValidator)
    )

  def validateBlockBeforeExecution(
      self: ValidatorsExecutor,
      block: Block,
      getBlockHeaderByHash: GetBlockHeaderByHash,
      getNBlocksBack: GetNBlocksBack
  )(implicit
      blockchainConfig: BlockchainConfig
  ): Either[BlockExecutionError.ValidationBeforeExecError, BlockExecutionSuccess] = {

    val header = block.header
    val body = block.body

    val result = for {
      _ <- self.blockHeaderValidator.validate(header, getBlockHeaderByHash)
      _ <- self.blockValidator.validateHeaderAndBody(header, body)
      _ <- self.ommersValidator.validate(
        header.parentHash,
        header.number,
        body.uncleNodesList,
        getBlockHeaderByHash,
        getNBlocksBack
      )
    } yield BlockExecutionSuccess

    result.left.map(ValidationBeforeExecError.apply)
  }

  def validateBlockAfterExecution(
      self: ValidatorsExecutor,
      block: Block,
      stateRootHash: ByteString,
      receipts: Seq[Receipt],
      gasUsed: BigInt
  ): Either[BlockExecutionError, BlockExecutionSuccess] =
    StdValidators.validateBlockAfterExecution(
      self = self,
      block = block,
      stateRootHash = stateRootHash,
      receipts = receipts,
      gasUsed = gasUsed
    )
}
