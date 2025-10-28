package com.chipprbots.ethereum.faucet.jsonrpc

import cats.effect.IO

import com.chipprbots.ethereum.faucet.jsonrpc.FaucetDomain.StatusRequest
import com.chipprbots.ethereum.healthcheck.HealthcheckResponse
import com.chipprbots.ethereum.jsonrpc.JsonRpcHealthChecker
import com.chipprbots.ethereum.jsonrpc.JsonRpcHealthcheck

class FaucetJsonRpcHealthCheck(faucetRpcService: FaucetRpcService) extends JsonRpcHealthChecker {

  protected def mainService: String = "faucet health"

  final val statusHC: IO[JsonRpcHealthcheck[FaucetDomain.StatusResponse]] =
    JsonRpcHealthcheck.fromServiceResponse("status", faucetRpcService.status(StatusRequest()))

  override def healthCheck(): IO[HealthcheckResponse] = {
    val statusF = statusHC.map(_.toResult)
    val responseF = statusF.map(check => HealthcheckResponse(List(check)))

    handleResponse(responseF)
  }
}
