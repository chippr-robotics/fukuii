package com.chipprbots.ethereum.testmode

import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockchainImpl
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.BlockchainWriter
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.ledger.BlockExecution
import com.chipprbots.ethereum.ledger.BlockPreparator
import com.chipprbots.ethereum.ledger.BlockValidation
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.vm.EvmConfig

class TestModeBlockExecution(
    blockchain: BlockchainImpl,
    blockchainReader: BlockchainReader,
    blockchainWriter: BlockchainWriter,
    evmCodeStorage: EvmCodeStorage,
    blockPreparator: BlockPreparator,
    blockValidation: BlockValidation,
    saveStoragePreimage: (UInt256) => Unit
) extends BlockExecution(
      blockchain,
      blockchainReader,
      blockchainWriter,
      evmCodeStorage,
      blockPreparator,
      blockValidation
    ) {

  override protected def buildInitialWorld(block: Block, parentHeader: BlockHeader)(implicit
      blockchainConfig: BlockchainConfig
  ): InMemoryWorldStateProxy =
    TestModeWorldStateProxy(
      evmCodeStorage = evmCodeStorage,
      nodesKeyValueStorage = blockchain.getBackingMptStorage(block.header.number),
      getBlockHashByNumber = (number: BigInt) => blockchainReader.getBlockHeaderByNumber(number).map(_.hash),
      accountStartNonce = blockchainConfig.accountStartNonce,
      stateRootHash = parentHeader.stateRoot,
      noEmptyAccounts = EvmConfig.forBlock(block.header.number, blockchainConfig).noEmptyAccounts,
      ethCompatibleStorage = blockchainConfig.ethCompatibleStorage,
      saveStoragePreimage = saveStoragePreimage
    )
}
