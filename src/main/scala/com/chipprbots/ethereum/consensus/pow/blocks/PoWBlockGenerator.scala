package com.chipprbots.ethereum.consensus.pow.blocks

import java.util.function.UnaryOperator

import akka.util.ByteString

import com.chipprbots.ethereum.consensus.blocks._
import com.chipprbots.ethereum.consensus.difficulty.DifficultyCalculator
import com.chipprbots.ethereum.consensus.mining.MiningConfig
import com.chipprbots.ethereum.consensus.mining.MiningMetrics
import com.chipprbots.ethereum.consensus.pow.validators.ValidatorsExecutor
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.domain._
import com.chipprbots.ethereum.ledger.BlockPreparator
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy
import com.chipprbots.ethereum.utils.BlockchainConfig

/** Internal API, used for testing (especially mocks) */
trait PoWBlockGenerator extends TestBlockGenerator {
  type X = Ommers

  /** An empty `X` */
  def emptyX: Ommers

  def getPrepared(powHeaderHash: ByteString): Option[PendingBlock]
}

class PoWBlockGeneratorImpl(
    evmCodeStorage: EvmCodeStorage,
    validators: ValidatorsExecutor,
    blockchainReader: BlockchainReader,
    miningConfig: MiningConfig,
    val blockPreparator: BlockPreparator,
    difficultyCalc: DifficultyCalculator,
    blockTimestampProvider: BlockTimestampProvider = DefaultBlockTimestampProvider
) extends BlockGeneratorSkeleton(
      miningConfig,
      difficultyCalc,
      blockTimestampProvider
    )
    with PoWBlockGenerator {

  protected def newBlockBody(transactions: Seq[SignedTransaction], x: Ommers): BlockBody =
    BlockBody(transactions, x)

  protected def prepareHeader(
      blockNumber: BigInt,
      parent: Block,
      beneficiary: Address,
      blockTimestamp: Long,
      x: Ommers
  )(implicit blockchainConfig: BlockchainConfig): BlockHeader =
    defaultPrepareHeader(blockNumber, parent, beneficiary, blockTimestamp, x)

  /** An empty `X` */
  def emptyX: Ommers = Nil

  def getPrepared(powHeaderHash: ByteString): Option[PendingBlock] =
    MiningMetrics.MinedBlockEvaluationTimer.record { () =>
      cache
        .getAndUpdate(new UnaryOperator[List[PendingBlockAndState]] {
          override def apply(t: List[PendingBlockAndState]): List[PendingBlockAndState] =
            t.filterNot(pbs =>
              ByteString(kec256(BlockHeader.getEncodedWithoutNonce(pbs.pendingBlock.block.header))) == powHeaderHash
            )
        })
        .find { pbs =>
          ByteString(kec256(BlockHeader.getEncodedWithoutNonce(pbs.pendingBlock.block.header))) == powHeaderHash
        }
        .map(_.pendingBlock)
    }

  def generateBlock(
      parent: Block,
      transactions: Seq[SignedTransaction],
      beneficiary: Address,
      x: Ommers,
      initialWorldStateBeforeExecution: Option[InMemoryWorldStateProxy]
  )(implicit blockchainConfig: BlockchainConfig): PendingBlockAndState = MiningMetrics.PoWBlockGeneratorTiming.record {
    () =>
      val pHeader = parent.header
      val blockNumber = pHeader.number + 1
      val parentHash = pHeader.hash

      val ommers = validators.ommersValidator.validate(parentHash, blockNumber, x, blockchainReader) match {
        case Left(_)  => emptyX
        case Right(_) => x

      }
      val prepared = prepareBlock(
        evmCodeStorage,
        parent,
        transactions,
        beneficiary,
        blockNumber,
        blockPreparator,
        ommers,
        initialWorldStateBeforeExecution
      )

      cache.updateAndGet { t: List[PendingBlockAndState] =>
        (prepared :: t).take(blockCacheSize)
      }

      prepared
  }

  def withBlockTimestampProvider(blockTimestampProvider: BlockTimestampProvider): PoWBlockGeneratorImpl =
    new PoWBlockGeneratorImpl(
      evmCodeStorage,
      validators,
      blockchainReader,
      miningConfig,
      blockPreparator,
      difficultyCalc,
      blockTimestampProvider
    )
}
