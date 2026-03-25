package com.chipprbots.ethereum.jsonrpc

import org.json4s.JsonAST._
import org.json4s.JsonDSL._

import com.chipprbots.ethereum.jsonrpc.TraceService._
import com.chipprbots.ethereum.jsonrpc.serialization.JsonEncoder
import com.chipprbots.ethereum.jsonrpc.serialization.JsonMethodDecoder

object TraceJsonMethodsImplicits extends JsonMethodsImplicits {

  implicit val trace_block: JsonMethodDecoder[TraceBlockRequest] with JsonEncoder[TraceBlockResponse] =
    new JsonMethodDecoder[TraceBlockRequest] with JsonEncoder[TraceBlockResponse] {
      override def decodeJson(params: Option[JArray]): Either[JsonRpcError, TraceBlockRequest] =
        params match {
          case Some(JArray((blockParam: JValue) :: Nil)) =>
            extractBlockParam(blockParam).map(TraceBlockRequest.apply)
          case Some(JArray((blockParam: JValue) :: _ :: Nil)) =>
            // trace_block may have a second param (trace types) — ignored for now
            extractBlockParam(blockParam).map(TraceBlockRequest.apply)
          case _ =>
            Left(JsonRpcError.InvalidParams("Expected block number parameter"))
        }

      override def encodeJson(t: TraceBlockResponse): JValue =
        JArray(t.traces.flatMap(_.traces).map(encodeTrace).toList)
    }

  implicit val trace_transaction: JsonMethodDecoder[TraceTransactionRequest] with JsonEncoder[TraceTransactionResponse] =
    new JsonMethodDecoder[TraceTransactionRequest] with JsonEncoder[TraceTransactionResponse] {
      override def decodeJson(params: Option[JArray]): Either[JsonRpcError, TraceTransactionRequest] =
        params match {
          case Some(JArray(JString(txHashHex) :: _)) =>
            extractHash(txHashHex).map(hash => TraceTransactionRequest(hash))
          case _ =>
            Left(JsonRpcError.InvalidParams("Expected transaction hash parameter"))
        }

      override def encodeJson(t: TraceTransactionResponse): JValue =
        JArray(t.traces.map(encodeTrace).toList)
    }

  private def encodeTrace(trace: Trace): JValue = {
    val action = encodeAction(trace.action, trace.traceType)
    val result = trace.result.map(encodeResult(_, trace.traceType))
    val base: JObject =
      ("action" -> action) ~
        ("blockHash" -> encodeAsHex(trace.blockHash)) ~
        ("blockNumber" -> trace.blockNumber) ~
        ("subtraces" -> trace.subtraces) ~
        ("traceAddress" -> JArray(trace.traceAddress.map(JInt(_)).toList)) ~
        ("transactionHash" -> trace.transactionHash.map(encodeAsHex)) ~
        ("transactionPosition" -> trace.transactionPosition.map(JInt(_))) ~
        ("type" -> trace.traceType)

    val withResult = trace.error match {
      case Some(err) => base ~ ("error" -> err) ~ ("result" -> JNull)
      case None      => base ~ ("result" -> result)
    }
    withResult
  }

  private def encodeAction(action: TraceAction, traceType: String): JValue = traceType match {
    case "create" =>
      ("from" -> encodeAsHex(action.from.bytes)) ~
        ("gas" -> encodeAsHex(action.gas)) ~
        ("init" -> encodeAsHex(action.input)) ~
        ("value" -> encodeAsHex(action.value))
    case _ =>
      ("callType" -> action.callType) ~
        ("from" -> encodeAsHex(action.from.bytes)) ~
        ("to" -> action.to.map(a => encodeAsHex(a.bytes))) ~
        ("gas" -> encodeAsHex(action.gas)) ~
        ("input" -> encodeAsHex(action.input)) ~
        ("value" -> encodeAsHex(action.value))
  }

  private def encodeResult(result: TraceResult, traceType: String): JValue = traceType match {
    case "create" =>
      ("address" -> result.address.map(a => encodeAsHex(a.bytes))) ~
        ("code" -> result.code.map(encodeAsHex)) ~
        ("gasUsed" -> encodeAsHex(result.gasUsed))
    case _ =>
      ("gasUsed" -> encodeAsHex(result.gasUsed)) ~
        ("output" -> encodeAsHex(result.output))
  }

}
