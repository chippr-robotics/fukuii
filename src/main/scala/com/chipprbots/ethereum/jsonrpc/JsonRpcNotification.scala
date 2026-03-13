package com.chipprbots.ethereum.jsonrpc

import org.json4s.JsonAST.JValue
import org.json4s.JsonDSL._
import org.json4s.native.JsonMethods.compact
import org.json4s.native.JsonMethods.render

/** Server-initiated JSON-RPC 2.0 notification (no id field).
  *
  * Used for eth_subscription push notifications over WebSocket.
  * Format: {"jsonrpc":"2.0","method":"eth_subscription","params":{"subscription":"0x...","result":{...}}}
  */
case class JsonRpcNotification(
    jsonrpc: String = "2.0",
    method: String,
    params: JValue
) {

  def toJson: JValue =
    ("jsonrpc" -> jsonrpc) ~
      ("method" -> method) ~
      ("params" -> params)

  def toJsonString: String = compact(render(toJson))
}

object JsonRpcNotification {

  /** Create an eth_subscription notification */
  def subscription(subscriptionId: String, result: JValue): JsonRpcNotification =
    JsonRpcNotification(
      method = "eth_subscription",
      params = ("subscription" -> subscriptionId) ~ ("result" -> result)
    )
}
