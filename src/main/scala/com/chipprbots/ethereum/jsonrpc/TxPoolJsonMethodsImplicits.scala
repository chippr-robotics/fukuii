package com.chipprbots.ethereum.jsonrpc

import org.json4s.JsonAST
import org.json4s.JsonAST.JArray
import org.json4s.JsonAST.JNull
import org.json4s.JsonAST.JObject
import org.json4s.JsonAST.JString
import org.json4s.JsonAST.JValue
import org.json4s.JsonDSL._

import com.chipprbots.ethereum.jsonrpc.JsonRpcError.InvalidParams
import com.chipprbots.ethereum.jsonrpc.TxPoolService._
import com.chipprbots.ethereum.jsonrpc.serialization.JsonEncoder
import com.chipprbots.ethereum.jsonrpc.serialization.JsonMethodDecoder
import com.chipprbots.ethereum.jsonrpc.serialization.JsonMethodDecoder.NoParamsMethodDecoder

object TxPoolJsonMethodsImplicits extends JsonMethodsImplicits {

  private def encodeTxInfo(info: TxPoolTxInfo): JValue =
    JObject(
      "hash" -> encodeAsHex(info.hash),
      "nonce" -> encodeAsHex(info.nonce),
      "from" -> encodeAsHex(info.from),
      "to" -> info.to.map(encodeAsHex).getOrElse(JNull),
      "value" -> encodeAsHex(info.value),
      "gasPrice" -> encodeAsHex(info.gasPrice),
      "gas" -> encodeAsHex(info.gas),
      "input" -> encodeAsHex(info.input)
    )

  private def encodeTxInfoMap(m: Map[BigInt, TxPoolTxInfo]): JValue =
    JObject(m.toList.sortBy(_._1).map { case (nonce, info) =>
      nonce.toString -> encodeTxInfo(info)
    })

  private def encodeAddressMap(m: Map[org.apache.pekko.util.ByteString, Map[BigInt, TxPoolTxInfo]]): JValue =
    JObject(m.toList.map { case (addr, nonceMap) =>
      encodeAsHex(addr).values -> encodeTxInfoMap(nonceMap)
    })

  private def encodeStringMap(m: Map[BigInt, String]): JValue =
    JObject(m.toList.sortBy(_._1).map { case (nonce, summary) =>
      nonce.toString -> JString(summary)
    })

  private def encodeInspectAddressMap(m: Map[org.apache.pekko.util.ByteString, Map[BigInt, String]]): JValue =
    JObject(m.toList.map { case (addr, nonceMap) =>
      encodeAsHex(addr).values -> encodeStringMap(nonceMap)
    })

  implicit val txpool_status: NoParamsMethodDecoder[TxPoolStatusRequest] with JsonEncoder[TxPoolStatusResponse] =
    new NoParamsMethodDecoder(TxPoolStatusRequest()) with JsonEncoder[TxPoolStatusResponse] {
      override def encodeJson(t: TxPoolStatusResponse): JValue =
        ("pending" -> encodeAsHex(BigInt(t.pending))) ~
          ("queued" -> encodeAsHex(BigInt(t.queued)))
    }

  implicit val txpool_content: NoParamsMethodDecoder[TxPoolContentRequest] with JsonEncoder[TxPoolContentResponse] =
    new NoParamsMethodDecoder(TxPoolContentRequest()) with JsonEncoder[TxPoolContentResponse] {
      override def encodeJson(t: TxPoolContentResponse): JValue =
        ("pending" -> encodeAddressMap(t.pending)) ~
          ("queued" -> encodeAddressMap(t.queued))
    }

  implicit val txpool_inspect: NoParamsMethodDecoder[TxPoolInspectRequest] with JsonEncoder[TxPoolInspectResponse] =
    new NoParamsMethodDecoder(TxPoolInspectRequest()) with JsonEncoder[TxPoolInspectResponse] {
      override def encodeJson(t: TxPoolInspectResponse): JValue =
        ("pending" -> encodeInspectAddressMap(t.pending)) ~
          ("queued" -> encodeInspectAddressMap(t.queued))
    }

  implicit val txpool_contentFrom
      : JsonMethodDecoder[TxPoolContentFromRequest] with JsonEncoder[TxPoolContentFromResponse] =
    new JsonMethodDecoder[TxPoolContentFromRequest] with JsonEncoder[TxPoolContentFromResponse] {
      override def decodeJson(
          params: Option[JsonAST.JArray]
      ): Either[JsonRpcError, TxPoolContentFromRequest] =
        params match {
          case Some(JArray(JString(addressStr) :: Nil)) =>
            extractAddress(addressStr).map(addr => TxPoolContentFromRequest(addr))
          case _ =>
            Left(InvalidParams())
        }

      override def encodeJson(t: TxPoolContentFromResponse): JValue =
        ("pending" -> encodeTxInfoMap(t.pending)) ~
          ("queued" -> encodeTxInfoMap(t.queued))
    }
}
