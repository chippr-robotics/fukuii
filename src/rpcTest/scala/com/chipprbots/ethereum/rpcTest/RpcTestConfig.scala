package com.chipprbots.ethereum.rpcTest

import com.typesafe.config.ConfigFactory

case class RpcTestConfig(fukuiiUrl: String, privateNetDataDir: String, keystoreDir: String)

object RpcTestConfig {
  def apply(confName: String): RpcTestConfig = {
    val config = ConfigFactory.load(confName)
    val fukuiiUrl = config.getString("fukuiiUrl")
    val dataDir = config.getString("privatenetDatadir")
    val keystoreDir = config.getString("keystoreDir")
    new RpcTestConfig(fukuiiUrl, dataDir, keystoreDir)
  }
}