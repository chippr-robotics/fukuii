package com.chipprbots.ethereum.consensus.pow.blocks

import java.util.function.UnaryOperator

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.consensus.blocks.*
import com.chipprbots.ethereum.consensus.difficulty.DifficultyCalculator
import com.chipprbots.ethereum.consensus.mining.MiningConfig
import com.chipprbots.ethereum.consensus.mining.MiningMetrics
import com.chipprbots.ethereum.consensus.pow.validators.ValidatorsExecutor
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.ledger.BlockPreparator
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy
import com.chipprbots.ethereum.utils.BlockchainConfig

/** Internal API, used for testing (especially mocks) */
trait PoWBlockGenerator extends TestBlockGenerator:
  type X = Ommers

  /** An empty `X` */
  def emptyX: Ommers

  def getPrepared(powHeaderHash: ByteString): Option[PendingBlock]

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
    with PoWBlockGenerator:

  protected def newBlockBody(transactions: Seq[SignedTransaction], x: Ommers): BlockBody =
    BlockBody(transactions, x)

  protected def prepareHeader(
      blockNumber: BigInt,
      parent: Block,
      beneficiary: Address,
      blockTimestamp: Timestamp,
      x: Ommers
  )(implicit blockchainConfig: BlockchainConfig): BlockHeader =
    defaultPrepareHeader(blockNumber, parent, beneficiary, blockTimestamp, x)

  /** An empty `X` */
  def emptyX: Ommers = Nil

  def getPrepared(powHeaderHash: ByteString): Option[PendingBlock] =
    MiningMetrics.MinedBlockEvaluationTimer.record { () =>
      cache
        .getAndUpdate(
          new UnaryOperator[List[PendingBlockAndState]]:
            override def apply(t: List[PendingBlockAndState]): List[PendingBlockAndState] =
              t.filterNot(pbs =>
                ByteString(kec256(BlockHeader.getEncodedWithoutNonce(pbs.pendingBlock.block.header))) == powHeaderHash
              )
        )
        .find { pbs =>
          ByteString(kec256(BlockHeader.getEncodedWithoutNonce(pbs.pendingBlock.block.header))) == powHeaderHash
        }
        .map(_.pendingBlock)
    }

  /** The ommers of `offered` a block on `parentHash` carries: each one in turn, kept where the ommers check import
    * makes passes with it and the ones kept before it, and left out where it fails (B-500). One invalid ommer cost the
    * block every other one before, a set with one already included two slots. core-geth's worker commits the uncles one
    * by one in the same way (`commitUncle`, miner/worker.go): one it refuses is skipped, and the next is tried.
    *
    * That is this node's own choice and no rule of the chain: where it does not hold the ancestors the ommers are
    * checked against (B-283), it takes none.
    */
  protected def validOmmers(parentHash: ByteString, blockNumber: BigInt, offered: Ommers)(implicit
      blockchainConfig: BlockchainConfig
  ): Ommers =
    try
      offered.foldLeft(emptyX) { (kept, ommer) =>
        val withIt = kept :+ ommer
        validators.ommersValidator.validate(parentHash, blockNumber, withIt, blockchainReader) match
          case Left(_)  => kept
          case Right(_) => withIt
      }
    catch case _: com.chipprbots.ethereum.db.storage.pathstate.AncestorNotStored => emptyX

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

      val ommers = validOmmers(parentHash.value, blockNumber.value, x)

      val prepared = prepareBlock(
        evmCodeStorage,
        parent,
        transactions,
        beneficiary,
        blockNumber.value,
        blockPreparator,
        ommers,
        initialWorldStateBeforeExecution
      )

      cache.updateAndGet { (t: List[PendingBlockAndState]) =>
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
