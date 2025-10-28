package com.chipprbots.ethereum.testmode

import akka.util.ByteString

import cats.effect.IO

import com.chipprbots.ethereum.consensus.blocks.BlockTimestampProvider
import com.chipprbots.ethereum.consensus.blocks.NoOmmersBlockGenerator
import com.chipprbots.ethereum.consensus.blocks.TestBlockGenerator
import com.chipprbots.ethereum.consensus.difficulty.DifficultyCalculator
import com.chipprbots.ethereum.consensus.mining.FullMiningConfig
import com.chipprbots.ethereum.consensus.mining.GetBlockHeaderByHash
import com.chipprbots.ethereum.consensus.mining.GetNBlocksBack
import com.chipprbots.ethereum.consensus.mining.Mining
import com.chipprbots.ethereum.consensus.mining.MiningConfig
import com.chipprbots.ethereum.consensus.mining.Protocol
import com.chipprbots.ethereum.consensus.pow.miners.MinerProtocol
import com.chipprbots.ethereum.consensus.pow.miners.MockedMiner.MockedMinerProtocol
import com.chipprbots.ethereum.consensus.pow.miners.MockedMiner.MockedMinerResponse
import com.chipprbots.ethereum.consensus.pow.miners.MockedMiner.MockedMinerResponses.MinerNotExist
import com.chipprbots.ethereum.consensus.pow.validators.ValidatorsExecutor
import com.chipprbots.ethereum.consensus.validators._
import com.chipprbots.ethereum.consensus.validators.std.StdBlockValidator
import com.chipprbots.ethereum.consensus.validators.std.StdSignedTransactionValidator
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockchainImpl
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.Receipt
import com.chipprbots.ethereum.ledger.BlockExecutionError
import com.chipprbots.ethereum.ledger.BlockExecutionSuccess
import com.chipprbots.ethereum.ledger.BlockPreparator
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy
import com.chipprbots.ethereum.ledger.VMImpl
import com.chipprbots.ethereum.nodebuilder._
import com.chipprbots.ethereum.utils.BlockchainConfig

class TestmodeMining(
    override val vm: VMImpl,
    evmCodeStorage: EvmCodeStorage,
    blockchain: BlockchainImpl,
    blockchainReader: BlockchainReader,
    miningConfig: MiningConfig,
    node: TestNode,
    blockTimestamp: Long = 0
) // var, because it can be modified by test_ RPC endpoints
    extends Mining {

  override type Config = AnyRef
  override def protocol: Protocol = Protocol.PoW
  override def config: FullMiningConfig[AnyRef] = FullMiningConfig[AnyRef](miningConfig, "")

  override def difficultyCalculator: DifficultyCalculator = DifficultyCalculator

  class TestValidators extends Validators {
    override def blockHeaderValidator: BlockHeaderValidator = new BlockHeaderValidator {
      override def validate(
          blockHeader: BlockHeader,
          getBlockHeaderByHash: GetBlockHeaderByHash
      )(implicit blockchainConfig: BlockchainConfig): Either[BlockHeaderError, BlockHeaderValid] = Right(
        BlockHeaderValid
      )

      override def validateHeaderOnly(blockHeader: BlockHeader)(implicit
          blockchainConfig: BlockchainConfig
      ): Either[BlockHeaderError, BlockHeaderValid] =
        Right(BlockHeaderValid)
    }
    override def signedTransactionValidator: SignedTransactionValidator = StdSignedTransactionValidator
    override def validateBlockBeforeExecution(
        block: Block,
        getBlockHeaderByHash: GetBlockHeaderByHash,
        getNBlocksBack: GetNBlocksBack
    )(implicit
        blockchainConfig: BlockchainConfig
    ): Either[BlockExecutionError.ValidationBeforeExecError, BlockExecutionSuccess] = Right(BlockExecutionSuccess)
    override def validateBlockAfterExecution(
        block: Block,
        stateRootHash: ByteString,
        receipts: Seq[Receipt],
        gasUsed: BigInt
    )(implicit blockchainConfig: BlockchainConfig): Either[BlockExecutionError, BlockExecutionSuccess] = Right(
      BlockExecutionSuccess
    )
    override def blockValidator: BlockValidator = new BlockValidator {
      override def validateBlockAndReceipts(
          blockHeader: BlockHeader,
          receipts: Seq[Receipt]
      ): Either[StdBlockValidator.BlockError, StdBlockValidator.BlockValid] = Right(StdBlockValidator.BlockValid)
      override def validateHeaderAndBody(
          blockHeader: BlockHeader,
          blockBody: BlockBody
      ): Either[StdBlockValidator.BlockError, StdBlockValidator.BlockValid] = Right(StdBlockValidator.BlockValid)
    }
  }

  override def validators: Validators = ValidatorsExecutor.apply(Protocol.MockedPow)

  override def blockPreparator: BlockPreparator = new BlockPreparator(
    vm = vm,
    signedTxValidator = validators.signedTransactionValidator,
    blockchain = blockchain,
    blockchainReader = blockchainReader
  ) {
    override def payBlockReward(block: Block, worldStateProxy: InMemoryWorldStateProxy)(implicit
        blockchainConfig: BlockchainConfig
    ): InMemoryWorldStateProxy =
      node.sealEngine match {
        case SealEngineType.NoProof =>
          super.payBlockReward(block, worldStateProxy)
        case SealEngineType.NoReward =>
          worldStateProxy
      }
  }

  override def blockGenerator: NoOmmersBlockGenerator =
    new NoOmmersBlockGenerator(
      evmCodeStorage,
      miningConfig,
      blockPreparator,
      difficultyCalculator,
      new BlockTimestampProvider {
        override def getEpochSecond: Long = blockTimestamp
      }
    ) {
      override def withBlockTimestampProvider(blockTimestampProvider: BlockTimestampProvider): TestBlockGenerator = this

    }

  override def startProtocol(node: Node): Unit = {}
  override def stopProtocol(): Unit = {}

  /** Sends msg to the internal miner and waits for the response
    */
  override def askMiner(msg: MockedMinerProtocol): IO[MockedMinerResponse] = IO.pure(MinerNotExist)

  /** Sends msg to the internal miner
    */
  override def sendMiner(msg: MinerProtocol): Unit = {}
}
