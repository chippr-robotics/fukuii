package com.chipprbots.ethereum.faucet.jsonrpc

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.faucet.FaucetStatus

object FaucetDomain {

  case class SendFundsRequest(address: Address)
  case class SendFundsResponse(txId: ByteString)
  case class StatusRequest()
  case class StatusResponse(status: FaucetStatus)

}
