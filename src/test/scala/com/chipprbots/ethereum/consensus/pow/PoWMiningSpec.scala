package com.chipprbots.ethereum.consensus.pow

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestKit

import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.scalamock.scalatest.MockFactory
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.WithActorSystemShutDown
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.consensus.mining.FullMiningConfig
import com.chipprbots.ethereum.consensus.mining.MiningConfigs
import com.chipprbots.ethereum.consensus.mining.MiningConfigs.ethashConfig
import com.chipprbots.ethereum.consensus.mining.Protocol
import com.chipprbots.ethereum.consensus.mining.Protocol.NoAdditionalPoWData
import com.chipprbots.ethereum.consensus.mining.Protocol.RestrictedPoWMinerData
import com.chipprbots.ethereum.consensus.pow.blocks.PoWBlockGeneratorImpl
import com.chipprbots.ethereum.consensus.pow.blocks.RestrictedPoWBlockGeneratorImpl
import com.chipprbots.ethereum.consensus.pow.validators.ValidatorsExecutor
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.domain.BlockchainImpl
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.nodebuilder.StdNode

class PoWMiningSpec
    extends TestKit(ActorSystem("PoWMiningSpec_System"))
    with AnyFlatSpecLike
    with WithActorSystemShutDown
    with Matchers {

  "PoWMining" should "use NoAdditionalPoWData block generator for PoWBlockGeneratorImpl" in new TestSetup {
    val powMining = PoWMining(
      vm,
      storagesInstance.storages.evmCodeStorage,
      blockchain,
      blockchainReader,
      MiningConfigs.fullMiningConfig,
      validator,
      NoAdditionalPoWData
    )

    powMining.blockGenerator.isInstanceOf[PoWBlockGeneratorImpl] shouldBe true
  }

  it should "use RestrictedPoWBlockGeneratorImpl block generator for RestrictedPoWMinerData" in new TestSetup {
    val key = mock[AsymmetricCipherKeyPair]

    val powMining = PoWMining(
      vm,
      evmCodeStorage,
      blockchain,
      blockchainReader,
      MiningConfigs.fullMiningConfig,
      validator,
      RestrictedPoWMinerData(key)
    )

    powMining.blockGenerator.isInstanceOf[RestrictedPoWBlockGeneratorImpl] shouldBe true
  }

  it should "not start a miner when miningEnabled=false" in new TestSetup {
    val configNoMining = miningConfig.copy(miningEnabled = false)
    val fullMiningConfig = FullMiningConfig(configNoMining, ethashConfig)

    val powMining = PoWMining(
      vm,
      evmCodeStorage,
      blockchain,
      blockchainReader,
      fullMiningConfig,
      validator,
      NoAdditionalPoWData
    )

    powMining.startProtocol(new TestMiningNode())
    powMining.minerCoordinatorRef shouldBe None
    powMining.mockedMinerRef shouldBe None
  }

  it should "start only one mocked miner when miner protocol is MockedPow" in new TestSetup {
    val configNoMining = miningConfig.copy(miningEnabled = true, protocol = Protocol.MockedPow)
    val fullMiningConfig = FullMiningConfig(configNoMining, ethashConfig)

    val powMining = PoWMining(
      vm,
      evmCodeStorage,
      blockchain,
      blockchainReader,
      fullMiningConfig,
      validator,
      NoAdditionalPoWData
    )

    powMining.startProtocol(new TestMiningNode())
    powMining.minerCoordinatorRef shouldBe None
    powMining.mockedMinerRef.isDefined shouldBe true
  }

  it should "start only the normal miner when miner protocol is PoW" in new TestSetup {
    val configNoMining = miningConfig.copy(miningEnabled = true, protocol = Protocol.PoW)
    val fullMiningConfig = FullMiningConfig(configNoMining, ethashConfig)

    val powMining = PoWMining(
      vm,
      evmCodeStorage,
      blockchain,
      blockchainReader,
      fullMiningConfig,
      validator,
      NoAdditionalPoWData
    )

    powMining.startProtocol(new TestMiningNode())
    powMining.mockedMinerRef shouldBe None
    powMining.minerCoordinatorRef.isDefined shouldBe true
  }

  it should "start only the normal miner when miner protocol is RestrictedPoW" in new TestSetup {
    val configNoMining = miningConfig.copy(miningEnabled = true, protocol = Protocol.RestrictedPoW)
    val fullMiningConfig = FullMiningConfig(configNoMining, ethashConfig)

    val powMining = PoWMining(
      vm,
      evmCodeStorage,
      blockchain,
      blockchainReader,
      fullMiningConfig,
      validator,
      NoAdditionalPoWData
    )

    powMining.startProtocol(new TestMiningNode())
    powMining.mockedMinerRef shouldBe None
    powMining.minerCoordinatorRef.isDefined shouldBe true
  }

  trait TestSetup extends EphemBlockchainTestSetup with MockFactory {
    override lazy val blockchainReader: BlockchainReader = mock[BlockchainReader]
    override lazy val blockchain: BlockchainImpl = mock[BlockchainImpl]
    val evmCodeStorage: EvmCodeStorage = mock[EvmCodeStorage]
    val validator: ValidatorsExecutor = successValidators.asInstanceOf[ValidatorsExecutor]
  }

  class TestMiningNode extends StdNode with EphemBlockchainTestSetup
}
