package com.chipprbots.ethereum.consensus.mining

import com.chipprbots.ethereum.consensus.mining.Protocol.AdditionalPoWProtocolData
import com.chipprbots.ethereum.consensus.mining.Protocol.NoAdditionalPoWData
import com.chipprbots.ethereum.consensus.mining.Protocol.RestrictedPoWMinerData
import com.chipprbots.ethereum.consensus.pow.EthashConfig
import com.chipprbots.ethereum.consensus.pow.PoWMining
import com.chipprbots.ethereum.consensus.pow.validators.ValidatorsExecutor
import com.chipprbots.ethereum.nodebuilder.BlockchainBuilder
import com.chipprbots.ethereum.nodebuilder.BlockchainConfigBuilder
import com.chipprbots.ethereum.nodebuilder.NodeKeyBuilder
import com.chipprbots.ethereum.nodebuilder.StorageBuilder
import com.chipprbots.ethereum.nodebuilder.VmBuilder
import com.chipprbots.ethereum.utils.Config
import com.chipprbots.ethereum.utils.Logger

trait MiningBuilder {
  def mining: Mining
}

/** A mining builder is responsible to instantiate the consensus protocol. This is done dynamically when Mantis boots,
  * based on its configuration.
  *
  * @see
  *   [[Mining]], [[com.chipprbots.ethereum.consensus.pow.PoWMining PoWConsensus]],
  */
trait StdMiningBuilder extends MiningBuilder {
  self: VmBuilder
    with StorageBuilder
    with BlockchainBuilder
    with BlockchainConfigBuilder
    with MiningConfigBuilder
    with NodeKeyBuilder
    with Logger =>

  private lazy val mantisConfig = Config.config

  private def newConfig[C <: AnyRef](c: C): FullMiningConfig[C] =
    FullMiningConfig(miningConfig, c)

  // TODO [ETCM-397] refactor configs to avoid possibility of running mocked or
  // restricted-pow mining on real network like ETC or Mordor
  protected def buildPoWMining(): PoWMining = {
    val specificConfig = EthashConfig(mantisConfig)

    val fullConfig = newConfig(specificConfig)

    val validators = ValidatorsExecutor(miningConfig.protocol)

    val additionalPoWData: AdditionalPoWProtocolData = miningConfig.protocol match {
      case Protocol.PoW | Protocol.MockedPow => NoAdditionalPoWData
      case Protocol.RestrictedPoW            => RestrictedPoWMinerData(nodeKey)
    }

    val mining =
      PoWMining(
        vm,
        storagesInstance.storages.evmCodeStorage,
        blockchain,
        blockchainReader,
        fullConfig,
        validators,
        additionalPoWData
      )

    mining
  }

  protected def buildMining(): Mining = {
    val config = miningConfig
    val protocol = config.protocol

    val mining =
      config.protocol match {
        case Protocol.PoW | Protocol.MockedPow | Protocol.RestrictedPoW => buildPoWMining()
      }

    log.info(s"Using '${protocol.name}' mining protocol [${mining.getClass.getName}]")

    mining
  }

  lazy val mining: Mining = buildMining()
}
