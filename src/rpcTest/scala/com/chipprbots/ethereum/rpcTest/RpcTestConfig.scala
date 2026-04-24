package com.chipprbots.ethereum.rpcTest

import com.typesafe.config.ConfigFactory

case class RpcTestConfig(
    fukuiiUrl: String,
    privateNetDataDir: String,
    keystoreDir: String,
    mordorOlympiaForkBlock: Long,
    mordorOlympiaTreasuryAddress: String,
    etcOlympiaForkBlock: Long,
    etcOlympiaTreasuryAddress: String
)

object RpcTestConfig {
  def apply(confName: String): RpcTestConfig = {
    val config = ConfigFactory.load(confName)
    val fukuiiUrl = config.getString("fukuiiUrl")
    val dataDir = config.getString("privatenetDatadir")
    val keystoreDir = config.getString("keystoreDir")
    val mordorOlympiaForkBlock = config.getLong("mordor-olympia-fork-block")
    val mordorOlympiaTreasuryAddress = config.getString("mordor-olympia-treasury-address")
    val etcOlympiaForkBlock = config.getLong("etc-olympia-fork-block")
    val etcOlympiaTreasuryAddress = config.getString("etc-olympia-treasury-address")
    new RpcTestConfig(
      fukuiiUrl,
      dataDir,
      keystoreDir,
      mordorOlympiaForkBlock,
      mordorOlympiaTreasuryAddress,
      etcOlympiaForkBlock,
      etcOlympiaTreasuryAddress
    )
  }
}