package com.chipprbots.ethereum.consensus.blocks

import com.chipprbots.ethereum.consensus.difficulty.DifficultyCalculator
import com.chipprbots.ethereum.consensus.mining.MiningConfig
import com.chipprbots.ethereum.consensus.mining.MiningMetrics
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.domain._
import com.chipprbots.ethereum.ledger.BlockPreparator
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy
import com.chipprbots.ethereum.utils.BlockchainConfig

abstract class NoOmmersBlockGenerator(
    evmCodeStorage: EvmCodeStorage,
    miningConfig: MiningConfig,
    blockPreparator: BlockPreparator,
    difficultyCalc: DifficultyCalculator,
    blockTimestampProvider: BlockTimestampProvider = DefaultBlockTimestampProvider
) extends BlockGeneratorSkeleton(
      miningConfig,
      difficultyCalc,
      blockTimestampProvider
    ) {

  type X = Nil.type

  protected def newBlockBody(transactions: Seq[SignedTransaction], x: Nil.type): BlockBody =
    BlockBody(transactions, x)

  protected def prepareHeader(
      blockNumber: BigInt,
      parent: Block,
      beneficiary: Address,
      blockTimestamp: Long,
      x: Nil.type
  )(implicit blockchainConfig: BlockchainConfig): BlockHeader =
    defaultPrepareHeader(blockNumber, parent, beneficiary, blockTimestamp, x)

  /** An empty `X` */
  def emptyX: Nil.type = Nil

  def generateBlock(
      parent: Block,
      transactions: Seq[SignedTransaction],
      beneficiary: Address,
      x: Nil.type,
      initialWorldStateBeforeExecution: Option[InMemoryWorldStateProxy]
  )(implicit blockchainConfig: BlockchainConfig): PendingBlockAndState =
    MiningMetrics.NoOmmersBlockGeneratorTiming.record { () =>
      val pHeader = parent.header
      val blockNumber = pHeader.number + 1

      val prepared =
        prepareBlock(
          evmCodeStorage,
          parent,
          transactions,
          beneficiary,
          blockNumber,
          blockPreparator,
          x,
          initialWorldStateBeforeExecution
        )
      cache.updateAndGet(((t: List[PendingBlockAndState])) => (prepared :: t).take(blockCacheSize))

      prepared
    }
}
