package com.chipprbots.ethereum.consensus.pow.blocks

import org.bouncycastle.crypto.AsymmetricCipherKeyPair

import com.chipprbots.ethereum.consensus.blocks.BlockTimestampProvider
import com.chipprbots.ethereum.consensus.blocks.DefaultBlockTimestampProvider
import com.chipprbots.ethereum.consensus.blocks.PendingBlockAndState
import com.chipprbots.ethereum.consensus.difficulty.DifficultyCalculator
import com.chipprbots.ethereum.consensus.mining.MiningConfig
import com.chipprbots.ethereum.consensus.mining.MiningMetrics
import com.chipprbots.ethereum.consensus.pow.RestrictedPoWSigner
import com.chipprbots.ethereum.consensus.pow.validators.ValidatorsExecutor
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.SignedTransaction
import com.chipprbots.ethereum.ledger.BlockPreparator
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy
import com.chipprbots.ethereum.utils.BlockchainConfig

class RestrictedPoWBlockGeneratorImpl(
    evmCodeStorage: EvmCodeStorage,
    validators: ValidatorsExecutor,
    blockchainReader: BlockchainReader,
    miningConfig: MiningConfig,
    override val blockPreparator: BlockPreparator,
    difficultyCalc: DifficultyCalculator,
    minerKeyPair: AsymmetricCipherKeyPair,
    blockTimestampProvider: BlockTimestampProvider = DefaultBlockTimestampProvider
) extends PoWBlockGeneratorImpl(
      evmCodeStorage,
      validators,
      blockchainReader,
      miningConfig,
      blockPreparator,
      difficultyCalc,
      blockTimestampProvider
    ) {

  override def generateBlock(
      parent: Block,
      transactions: Seq[SignedTransaction],
      beneficiary: Address,
      ommers: Ommers,
      initialWorldStateBeforeExecution: Option[InMemoryWorldStateProxy]
  )(implicit blockchainConfig: BlockchainConfig): PendingBlockAndState =
    MiningMetrics.RestrictedPoWBlockGeneratorTiming.record { () =>
      val pHeader = parent.header
      val blockNumber = pHeader.number + 1
      val parentHash = pHeader.hash

      val validatedOmmers =
        validators.ommersValidator.validate(parentHash, blockNumber, ommers, blockchainReader) match {
          case Left(_)  => emptyX
          case Right(_) => ommers
        }
      val prepared = prepareBlock(
        evmCodeStorage,
        parent,
        transactions,
        beneficiary,
        blockNumber,
        blockPreparator,
        validatedOmmers,
        initialWorldStateBeforeExecution
      )
      val preparedHeader = prepared.pendingBlock.block.header
      val headerWithAdditionalExtraData = RestrictedPoWSigner.signHeader(preparedHeader, minerKeyPair)
      val modifiedPrepared = prepared.copy(pendingBlock =
        prepared.pendingBlock.copy(block = prepared.pendingBlock.block.copy(header = headerWithAdditionalExtraData))
      )

      cache.updateAndGet { t: List[PendingBlockAndState] =>
        (modifiedPrepared :: t).take(blockCacheSize)
      }

      modifiedPrepared
    }

}
