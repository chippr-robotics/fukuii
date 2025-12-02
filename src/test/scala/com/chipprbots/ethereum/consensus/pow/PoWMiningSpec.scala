package com.chipprbots.ethereum.consensus.pow

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestKit

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
import com.chipprbots.ethereum.testing.Tags._

class PoWMiningSpec
    extends TestKit(ActorSystem("PoWMiningSpec_System"))
    with AnyFlatSpecLike
    with WithActorSystemShutDown
    with Matchers
    with org.scalamock.scalatest.MockFactory {

  "PoWMining" should "use NoAdditionalPoWData block generator for PoWBlockGeneratorImpl" taggedAs (UnitTest, ConsensusTest, SlowTest) in new TestSetup {
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

  it should "use RestrictedPoWBlockGeneratorImpl block generator for RestrictedPoWMinerData" taggedAs (UnitTest, ConsensusTest, SlowTest) in new TestSetup {
    // MIGRATION: Can't mock Java classes in Scala 3 - use real instance instead
    val key = com.chipprbots.ethereum.crypto.generateKeyPair(new java.security.SecureRandom)

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

  it should "not start a miner when miningEnabled=false" taggedAs (UnitTest, ConsensusTest, SlowTest) in new TestSetup {
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

  it should "start only one mocked miner when miner protocol is MockedPow" taggedAs (UnitTest, ConsensusTest, SlowTest) in new TestSetup {
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

  it should "start only the normal miner when miner protocol is PoW" taggedAs (UnitTest, ConsensusTest, SlowTest) in new TestSetup {
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

  it should "start only the normal miner when miner protocol is RestrictedPoW" taggedAs (UnitTest, ConsensusTest, SlowTest) in new TestSetup {
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

  trait TestSetup extends EphemBlockchainTestSetup {
    override lazy val blockchainReader: BlockchainReader = mock[BlockchainReader]
    override lazy val blockchain: BlockchainImpl = mock[BlockchainImpl]
    val evmCodeStorage: EvmCodeStorage = mock[EvmCodeStorage]
    val validator: ValidatorsExecutor = successValidators.asInstanceOf[ValidatorsExecutor]
  }

  class TestMiningNode extends StdNode with EphemBlockchainTestSetup {
    // SCALA 3 MIGRATION: Override ioRuntime with public access to satisfy Node trait
    override lazy val ioRuntime: cats.effect.unsafe.IORuntime = cats.effect.unsafe.IORuntime.global
  }
}
