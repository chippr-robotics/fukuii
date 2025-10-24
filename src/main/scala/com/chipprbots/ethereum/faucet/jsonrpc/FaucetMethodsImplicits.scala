package com.chipprbots.ethereum.faucet.jsonrpc

import org.json4s.JsonAST.JArray
import org.json4s.JsonAST.JObject
import org.json4s.JsonAST.JString

import com.chipprbots.ethereum.faucet.jsonrpc.FaucetDomain.SendFundsRequest
import com.chipprbots.ethereum.faucet.jsonrpc.FaucetDomain.SendFundsResponse
import com.chipprbots.ethereum.faucet.jsonrpc.FaucetDomain.StatusRequest
import com.chipprbots.ethereum.faucet.jsonrpc.FaucetDomain.StatusResponse
import com.chipprbots.ethereum.jsonrpc.JsonMethodsImplicits
import com.chipprbots.ethereum.jsonrpc.JsonRpcError.InvalidParams
import com.chipprbots.ethereum.jsonrpc.serialization.JsonEncoder
import com.chipprbots.ethereum.jsonrpc.serialization.JsonMethodDecoder
import com.chipprbots.ethereum.jsonrpc.serialization.JsonMethodDecoder.NoParamsMethodDecoder

object FaucetMethodsImplicits extends JsonMethodsImplicits {

  implicit val sendFundsRequestDecoder: JsonMethodDecoder[SendFundsRequest] = {
    case Some(JArray((input: JString) :: Nil)) => extractAddress(input).map(SendFundsRequest)
    case _                                     => Left(InvalidParams())
  }

  implicit val sendFundsResponseEncoder: JsonEncoder[SendFundsResponse] = (t: SendFundsResponse) => encodeAsHex(t.txId)

  implicit val statusRequestDecoder: JsonMethodDecoder[StatusRequest] = new NoParamsMethodDecoder(StatusRequest())

  implicit val statusEncoder: JsonEncoder[StatusResponse] = (t: StatusResponse) =>
    JObject(
      "status" -> JString(t.status.toString)
    )
}
