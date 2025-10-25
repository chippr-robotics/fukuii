package com.chipprbots.ethereum.consensus.mining

import com.chipprbots.ethereum.utils.Config

trait MiningConfigBuilder {
  protected def buildMiningConfig(): MiningConfig = MiningConfig(Config.config)

  lazy val miningConfig: MiningConfig = buildMiningConfig()
}
