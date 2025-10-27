package com.chipprbots.ethereum.testmode

import akka.util.ByteString

import monix.execution.Scheduler

import com.chipprbots.ethereum.consensus.ConsensusAdapter
import com.chipprbots.ethereum.consensus.ConsensusImpl
import com.chipprbots.ethereum.consensus.mining.MiningConfig
import com.chipprbots.ethereum.crypto
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.domain.BlockchainImpl
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.BlockchainWriter
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.ledger.BlockValidation
import com.chipprbots.ethereum.ledger.VMImpl
import com.chipprbots.ethereum.nodebuilder.TestNode

/** Provides a ledger or consensus instances with modifiable blockchain config (used in test mode). */
class TestModeComponentsProvider(
    blockchain: BlockchainImpl,
    blockchainReader: BlockchainReader,
    blockchainWriter: BlockchainWriter,
    evmCodeStorage: EvmCodeStorage,
    validationExecutionContext: Scheduler,
    miningConfig: MiningConfig,
    vm: VMImpl,
    node: TestNode
) {

  def getConsensus(
      preimageCache: collection.concurrent.Map[ByteString, UInt256]
  ): ConsensusAdapter = {
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
        ((key: UInt256)) => preimageCache.put(crypto.kec256(key.bytes), key)
      )

    new ConsensusAdapter(
      new ConsensusImpl(
        blockchain,
        blockchainReader,
        blockchainWriter,
        blockExecution
      ),
      blockchainReader,
      node.blockQueue,
      blockValidation,
      validationExecutionContext
    )
  }

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
}
