package com.chipprbots.ethereum.nodebuilder

import java.util.concurrent.atomic.AtomicReference

import cats.effect.unsafe.IORuntime

import com.chipprbots.ethereum.jsonrpc.TestService
import com.chipprbots.ethereum.testmode.SealEngineType
import com.chipprbots.ethereum.testmode.TestEthBlockServiceWrapper
import com.chipprbots.ethereum.testmode.TestModeComponentsProvider
import com.chipprbots.ethereum.testmode.TestmodeMining
import com.chipprbots.ethereum.utils.BlockchainConfig

class TestNode extends BaseNode {

  override lazy val ioRuntime: IORuntime = IORuntime.global

  lazy val testModeComponentsProvider: TestModeComponentsProvider =
    new TestModeComponentsProvider(
      blockchain,
      blockchainReader,
      blockchainWriter,
      storagesInstance.storages.evmCodeStorage,
      ioRuntime,
      miningConfig,
      vm,
      this
    )

  override lazy val ethBlocksService =
    new TestEthBlockServiceWrapper(blockchain, blockchainReader, mining, blockQueue)

  override lazy val mining = new TestmodeMining(
    vm,
    storagesInstance.storages.evmCodeStorage,
    blockchain,
    blockchainReader,
    miningConfig,
    this
  )

  override lazy val testService: Option[TestService] =
    Some(
      new TestService(
        blockchain,
        blockchainReader,
        blockchainWriter,
        storagesInstance.storages.stateStorage,
        storagesInstance.storages.evmCodeStorage,
        pendingTransactionsManager,
        miningConfig,
        testModeComponentsProvider,
        storagesInstance.storages.transactionMappingStorage,
        this
      )(ioRuntime)
    )

  lazy val currentBlockchainConfig: AtomicReference[BlockchainConfig] = new AtomicReference(initBlockchainConfig)
  implicit override def blockchainConfig: BlockchainConfig = currentBlockchainConfig.get()

  val currentSealEngine: AtomicReference[SealEngineType] = new AtomicReference(SealEngineType.NoReward)
  def sealEngine: SealEngineType = currentSealEngine.get()

}
