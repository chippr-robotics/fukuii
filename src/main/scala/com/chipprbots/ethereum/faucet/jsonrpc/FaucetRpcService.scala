package com.chipprbots.ethereum.faucet.jsonrpc

import akka.actor.ActorSystem
import akka.pattern.RetrySupport
import akka.util.Timeout

import com.chipprbots.ethereum.faucet.FaucetConfig
import com.chipprbots.ethereum.faucet.FaucetConfigBuilder
import com.chipprbots.ethereum.faucet.FaucetHandler.FaucetHandlerMsg
import com.chipprbots.ethereum.faucet.FaucetHandler.FaucetHandlerResponse
import com.chipprbots.ethereum.faucet.jsonrpc.FaucetDomain.SendFundsRequest
import com.chipprbots.ethereum.faucet.jsonrpc.FaucetDomain.SendFundsResponse
import com.chipprbots.ethereum.faucet.jsonrpc.FaucetDomain.StatusRequest
import com.chipprbots.ethereum.faucet.jsonrpc.FaucetDomain.StatusResponse
import com.chipprbots.ethereum.jsonrpc.AkkaTaskOps._
import com.chipprbots.ethereum.jsonrpc.JsonRpcError
import com.chipprbots.ethereum.jsonrpc.ServiceResponse
import com.chipprbots.ethereum.utils.Logger

class FaucetRpcService(config: FaucetConfig)(implicit system: ActorSystem)
    extends FaucetConfigBuilder
    with RetrySupport
    with FaucetHandlerSelector
    with Logger {

  implicit lazy val actorTimeout: Timeout = Timeout(config.actorCommunicationMargin + config.rpcClient.timeout)

  def sendFunds(sendFundsRequest: SendFundsRequest): ServiceResponse[SendFundsResponse] =
    selectFaucetHandler()
      .flatMap(handler =>
        handler
          .askFor[Any](FaucetHandlerMsg.SendFunds(sendFundsRequest.address))
          .map(handleSendFundsResponse.orElse(handleErrors))
      )
      .recover(handleErrors)

  def status(statusRequest: StatusRequest): ServiceResponse[StatusResponse] =
    selectFaucetHandler()
      .flatMap(handler => handler.askFor[Any](FaucetHandlerMsg.Status))
      .map(handleStatusResponse.orElse(handleErrors))
      .recover(handleErrors)

  private def handleSendFundsResponse: PartialFunction[Any, Either[JsonRpcError, SendFundsResponse]] = {
    case FaucetHandlerResponse.TransactionSent(txHash) =>
      Right(SendFundsResponse(txHash))
  }

  private def handleStatusResponse: PartialFunction[Any, Either[JsonRpcError, StatusResponse]] = {
    case FaucetHandlerResponse.StatusResponse(status) =>
      Right(StatusResponse(status))
  }

  private def handleErrors[T]: PartialFunction[Any, Either[JsonRpcError, T]] = {
    case FaucetHandlerResponse.FaucetIsUnavailable =>
      Left(JsonRpcError.LogicError("Faucet is unavailable: Please try again in a few more seconds"))
    case FaucetHandlerResponse.WalletRpcClientError(error) =>
      Left(JsonRpcError.LogicError(s"Faucet error: $error"))
    case other =>
      log.error(s"process failure: $other")
      Left(JsonRpcError.InternalError)
  }

}
