package com.chipprbots.ethereum.faucet

import com.chipprbots.ethereum.faucet.jsonrpc.FaucetServer
import com.chipprbots.ethereum.utils.Logger

object Faucet extends Logger {

  def main(args: Array[String]): Unit =
    (new FaucetServer).start()

}
