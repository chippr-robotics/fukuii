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

  implicit val trace_filter: JsonMethodDecoder[TraceFilterRequest] with JsonEncoder[TraceFilterResponse] =
    new JsonMethodDecoder[TraceFilterRequest] with JsonEncoder[TraceFilterResponse] {
      override def decodeJson(params: Option[JArray]): Either[JsonRpcError, TraceFilterRequest] =
        params match {
          case Some(JArray(JObject(fields) :: _)) =>
            val fromBlock = fields.collectFirst { case ("fromBlock", v) => v }.map(extractBlockParam).map {
              case Right(bp) => bp
              case Left(_)   => BlockParam.Earliest
            }
            val toBlock = fields.collectFirst { case ("toBlock", v) => v }.map(extractBlockParam).map {
              case Right(bp) => bp
              case Left(_)   => BlockParam.Latest
            }
            val fromAddress = fields.collectFirst { case ("fromAddress", JArray(addrs)) =>
              addrs.collect { case JString(a) => extractAddress(a) }.collect { case Right(addr) => addr }
            }
            val toAddress = fields.collectFirst { case ("toAddress", JArray(addrs)) =>
              addrs.collect { case JString(a) => extractAddress(a) }.collect { case Right(addr) => addr }
            }
            val after = fields.collectFirst { case ("after", JInt(n)) => n.toInt }
            val count = fields.collectFirst { case ("count", JInt(n)) => n.toInt }
            Right(TraceFilterRequest(fromBlock, toBlock, fromAddress, toAddress, after, count))
          case _ =>
            Left(JsonRpcError.InvalidParams("Expected filter object parameter"))
        }

      override def encodeJson(t: TraceFilterResponse): JValue =
        JArray(t.traces.map(encodeTrace).toList)
    }

  implicit val trace_replayBlockTransactions
      : JsonMethodDecoder[TraceReplayBlockRequest] with JsonEncoder[TraceReplayBlockResponse] =
    new JsonMethodDecoder[TraceReplayBlockRequest] with JsonEncoder[TraceReplayBlockResponse] {
      override def decodeJson(params: Option[JArray]): Either[JsonRpcError, TraceReplayBlockRequest] =
        params match {
          case Some(JArray((blockParam: JValue) :: JArray(types) :: Nil)) =>
            val traceTypes = types.collect { case JString(t) => t }
            extractBlockParam(blockParam).map(TraceReplayBlockRequest(_, traceTypes))
          case Some(JArray((blockParam: JValue) :: Nil)) =>
            extractBlockParam(blockParam).map(TraceReplayBlockRequest(_, Seq("trace")))
          case _ =>
            Left(JsonRpcError.InvalidParams("Expected block number and trace types"))
        }

      override def encodeJson(t: TraceReplayBlockResponse): JValue =
        JArray(t.results.map(encodeReplayResult).toList)
    }

  implicit val trace_replayTransaction
      : JsonMethodDecoder[TraceReplayTransactionRequest] with JsonEncoder[TraceReplayTransactionResponse] =
    new JsonMethodDecoder[TraceReplayTransactionRequest] with JsonEncoder[TraceReplayTransactionResponse] {
      override def decodeJson(params: Option[JArray]): Either[JsonRpcError, TraceReplayTransactionRequest] =
        params match {
          case Some(JArray(JString(txHashHex) :: JArray(types) :: Nil)) =>
            val traceTypes = types.collect { case JString(t) => t }
            extractHash(txHashHex).map(TraceReplayTransactionRequest(_, traceTypes))
          case Some(JArray(JString(txHashHex) :: Nil)) =>
            extractHash(txHashHex).map(TraceReplayTransactionRequest(_, Seq("trace")))
          case _ =>
            Left(JsonRpcError.InvalidParams("Expected transaction hash and trace types"))
        }

      override def encodeJson(t: TraceReplayTransactionResponse): JValue =
        encodeReplayResult(t.result)
    }

  implicit val trace_get: JsonMethodDecoder[TraceGetRequest] with JsonEncoder[TraceGetResponse] =
    new JsonMethodDecoder[TraceGetRequest] with JsonEncoder[TraceGetResponse] {
      override def decodeJson(params: Option[JArray]): Either[JsonRpcError, TraceGetRequest] =
        params match {
          case Some(JArray(JString(txHashHex) :: JArray(indices) :: Nil)) =>
            val traceIndex = indices.collect { case JInt(n) => n.toInt }
            extractHash(txHashHex).map(TraceGetRequest(_, traceIndex))
          case _ =>
            Left(JsonRpcError.InvalidParams("Expected transaction hash and index array"))
        }

      override def encodeJson(t: TraceGetResponse): JValue =
        t.trace.map(encodeTrace).getOrElse(JNull)
    }

  private def encodeReplayResult(result: ReplayResult): JValue =
    ("transactionHash" -> encodeAsHex(result.transactionHash)) ~
      ("output" -> encodeAsHex(result.output)) ~
      ("trace" -> JArray(result.trace.map(encodeTrace).toList)) ~
      ("stateDiff" -> result.stateDiff.map(JString(_)).getOrElse(JNull)) ~
      ("vmTrace" -> result.vmTrace.map(JString(_)).getOrElse(JNull))

}
