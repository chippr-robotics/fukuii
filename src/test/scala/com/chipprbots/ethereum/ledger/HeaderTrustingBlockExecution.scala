package com.chipprbots.ethereum.ledger

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.StagedBlockState
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockAccessList
import com.chipprbots.ethereum.domain.BlockchainImpl
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.BlockchainWriter
import com.chipprbots.ethereum.domain.Receipt
import com.chipprbots.ethereum.utils.BlockchainConfig

/** The import path's block execution, for a test chain whose headers cannot pass the check made before execution (the
  * execution-apis chain's first blocks carry fake Ethash seals). Everything after that check is the production
  * path's: execution, the checks made after it, the staging and the one commit.
  */
class HeaderTrustingBlockExecution(
    blockchain: BlockchainImpl,
    blockchainReader: BlockchainReader,
    blockchainWriter: BlockchainWriter,
    evmCodeStorage: EvmCodeStorage,
    blockPreparator: BlockPreparator,
    blockValidation: BlockValidation
) extends BlockExecution(blockchain, blockchainReader, blockchainWriter, evmCodeStorage, blockPreparator, blockValidation):

  override protected[ledger] def executeAndValidateStaged(
      block: Block,
      alreadyValidated: Boolean,
      staged: StagedBlockState
  )(implicit
      blockchainConfig: BlockchainConfig
  ): Either[BlockExecutionError, (Seq[Receipt], Seq[ByteString], Option[BlockAccessList])] =
    super.executeAndValidateStaged(block, alreadyValidated = true, staged)
