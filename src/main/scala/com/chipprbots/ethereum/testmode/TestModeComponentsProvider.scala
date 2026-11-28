package com.chipprbots.ethereum.testmode

import org.apache.pekko.util.ByteString

import cats.effect.unsafe.IORuntime

import com.chipprbots.ethereum.consensus.ConsensusAdapter
import com.chipprbots.ethereum.consensus.ConsensusImpl
import com.chipprbots.ethereum.consensus.PowForkChoice
import com.chipprbots.ethereum.consensus.mining.MiningConfig
import com.chipprbots.ethereum.crypto
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.domain.BlockchainImpl
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.BlockchainWriter
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.ledger.BlockValidation
import com.chipprbots.ethereum.ledger.VMImpl

/** Provides a ledger or consensus instances with modifiable blockchain config (used in test mode). */
class TestModeComponentsProvider(
    blockchain: BlockchainImpl,
    blockchainReader: BlockchainReader,
    blockchainWriter: BlockchainWriter,
    evmCodeStorage: EvmCodeStorage,
    validationExecutionContext: IORuntime,
    miningConfig: MiningConfig,
    vm: VMImpl,
    node: TestModeNode
):

  def getConsensus(
      preimageCache: collection.concurrent.Map[ByteString, UInt256]
  ): ConsensusAdapter =
    val consensuz = consensus()
    val blockValidation = new BlockValidation(consensuz, blockchainReader, node.blockQueue)
    val blockExecution =
      new TestModeBlockExecution(
        blockchain,
        blockchainReader,
        blockchainWriter,
        evmCodeStorage,
        consensuz.blockPreparator,
        blockValidation,
        (key: UInt256) => preimageCache.put(crypto.kec256(key.bytes), key)
      )

    new ConsensusAdapter(
      new ConsensusImpl(
        blockchainReader,
        blockchainWriter,
        blockExecution,
        reorgState = blockchain,
        // Deterministic, as the tests this mode runs are (forge W3): a tie of total difficulty keeps the head, and no
        // coin is tossed. No ethereum/tests vector depends on how a tie is broken: of the BlockchainTests and
        // LegacyTests cases, none has a tie the lower block would win, and those with a tie at one height (ForkStress,
        // the ChainAtoChainB family, newChainFrom*, lotsOfBranches*, ...) reach the same `lastblockhash` whichever
        // block the tie goes to
        powForkChoice = PowForkChoice.HeavierOnly
      ),
      blockchainReader,
      node.blockQueue,
      blockValidation,
      validationExecutionContext
    )

  /** Clear the internal builder state
    */
  def clearState(): Unit =
    node.blockQueue.clear()

  def consensus(
      blockTimestamp: Long = 0
  ): TestmodeMining =
    new TestmodeMining(
      vm,
      evmCodeStorage,
      blockchain,
      blockchainReader,
      miningConfig,
      node,
      blockTimestamp
    )
