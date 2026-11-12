package com.chipprbots.ethereum.testmode

import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.StagedBlockState
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockchainImpl
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.BlockchainWriter
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.ledger.AncestorBlockHashes
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
    ):

  override protected def buildInitialWorld(
      block: Block,
      parentHeader: BlockHeader,
      isProposer: Boolean = false,
      staged: Option[StagedBlockState] = None
  )(implicit blockchainConfig: BlockchainConfig): InMemoryWorldStateProxy =
    // As BlockExecution.buildInitialWorld: a staged block's storage; else, for a block that is being built on the
    // unified state layout, the parent's state under a layer of the build's own; else the hash store.
    TestModeWorldStateProxy(
      evmCodeStorage = evmCodeStorage,
      nodesKeyValueStorage = staged
        .map(_.storage)
        .orElse(if isProposer then blockchainReader.proposerStateOn(parentHeader) else None)
        .getOrElse(blockchain.getBackingMptStorage(block.header.number.value)),
      getBlockHashByNumber = AncestorBlockHashes.forBlock(block.header, blockchainReader),
      accountStartNonce = blockchainConfig.accountStartNonce,
      stateRootHash = parentHeader.stateRoot.value,
      noEmptyAccounts = EvmConfig.forBlock(block.header.number.value, blockchainConfig).noEmptyAccounts,
      ethCompatibleStorage = blockchainConfig.ethCompatibleStorage,
      saveStoragePreimage = saveStoragePreimage
    )
