package com.chipprbots.ethereum.txExecTest

import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.domain.BlockchainImpl
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.BlockchainStorages
import com.chipprbots.ethereum.domain.BlockchainWriter
import com.chipprbots.ethereum.ledger.VMImpl

trait ScenarioSetup extends EphemBlockchainTestSetup {
  protected val testBlockchainStorages: BlockchainStorages

  override lazy val blockchainReader: BlockchainReader = BlockchainReader(testBlockchainStorages)
  override lazy val blockchainWriter: BlockchainWriter = BlockchainWriter(testBlockchainStorages)
  override lazy val blockchain: BlockchainImpl = BlockchainImpl(testBlockchainStorages, blockchainReader)
  override lazy val vm: VMImpl = new VMImpl
}
