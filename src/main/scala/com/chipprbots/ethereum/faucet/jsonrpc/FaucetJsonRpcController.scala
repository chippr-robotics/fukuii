package com.chipprbots.ethereum.faucet.jsonrpc

import monix.eval.Task

import com.chipprbots.ethereum.faucet.jsonrpc.FaucetDomain._
import com.chipprbots.ethereum.jsonrpc.JsonRpcError
import com.chipprbots.ethereum.jsonrpc.JsonRpcRequest
import com.chipprbots.ethereum.jsonrpc.JsonRpcResponse
import com.chipprbots.ethereum.jsonrpc.server.controllers.JsonRpcBaseController
import com.chipprbots.ethereum.jsonrpc.server.controllers.JsonRpcBaseController.JsonRpcConfig
import com.chipprbots.ethereum.utils.Logger

class FaucetJsonRpcController(
    faucetRpcService: FaucetRpcService,
    override val config: JsonRpcConfig
) extends ApisBuilder
    with Logger
    with JsonRpcBaseController {

  import FaucetMethodsImplicits._

  override def enabledApis: Seq[String] = config.apis

  override def apisHandleFns: Map[String, PartialFunction[JsonRpcRequest, Task[JsonRpcResponse]]] = Map(
    Apis.Faucet -> handleRequest
  )

  def handleRequest: PartialFunction[JsonRpcRequest, Task[JsonRpcResponse]] = { case req =>
    val notFoundFn: PartialFunction[JsonRpcRequest, Task[JsonRpcResponse]] = { case _ =>
      Task.now(errorResponse(req, JsonRpcError.MethodNotFound))
    }
    (handleFaucetRequest.orElse(notFoundFn))(req)
  }

  private def handleFaucetRequest: PartialFunction[JsonRpcRequest, Task[JsonRpcResponse]] = {
    case req @ JsonRpcRequest(_, FaucetJsonRpcController.SendFunds, _, _) =>
      handle[SendFundsRequest, SendFundsResponse](faucetRpcService.sendFunds, req)
    case req @ JsonRpcRequest(_, FaucetJsonRpcController.Status, _, _) =>
      handle[StatusRequest, StatusResponse](faucetRpcService.status, req)
  }
}

object FaucetJsonRpcController {
  private val Prefix = "faucet_"

  val SendFunds: String = Prefix + "sendFunds"
  val Status: String = Prefix + "status"

}
