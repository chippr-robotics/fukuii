package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.util.ByteString

import org.json4s.JsonAST.{JArray, JBool, JInt, JObject, JString, JValue}

import com.chipprbots.ethereum.jsonrpc.DebugService.ListPeersInfoRequest
import com.chipprbots.ethereum.jsonrpc.DebugService.ListPeersInfoResponse
import com.chipprbots.ethereum.jsonrpc.DebugService.TraceTransactionRequest
import com.chipprbots.ethereum.jsonrpc.DebugService.TraceTransactionResponse
import com.chipprbots.ethereum.jsonrpc.serialization.JsonEncoder
import com.chipprbots.ethereum.jsonrpc.serialization.JsonMethodCodec
import com.chipprbots.ethereum.jsonrpc.serialization.JsonMethodDecoder
import com.chipprbots.ethereum.jsonrpc.serialization.JsonMethodDecoder.NoParamsMethodDecoder
import com.chipprbots.ethereum.vm.tracing.StructLogEntry

object DebugJsonMethodsImplicits extends JsonMethodsImplicits {

  implicit val debug_listPeersInfo: JsonMethodCodec[ListPeersInfoRequest, ListPeersInfoResponse] =
    new NoParamsMethodDecoder(ListPeersInfoRequest()) with JsonEncoder[ListPeersInfoResponse] {
      def encodeJson(t: ListPeersInfoResponse): JValue =
        JArray(t.peers.map(a => JString(a.toString)))
    }

  private def hex0x(bs: ByteString): String =
    "0x" + org.bouncycastle.util.encoders.Hex.toHexString(bs.toArray)

  private def encodeStructLog(e: StructLogEntry): JObject = {
    var fields: List[(String, JValue)] = List(
      "pc" -> JInt(e.pc),
      "op" -> JString(e.op),
      "gas" -> JInt(e.gas),
      "gasCost" -> JInt(e.gasCost),
      "depth" -> JInt(e.depth)
    )
    e.stack.foreach(st => fields = fields :+ ("stack" ->
      JArray(st.map(v => JString("0x" + v.toBigInt.toString(16))))))
    e.memory.foreach(m => fields = fields :+ ("memory" ->
      JArray(m.grouped(32).map(w => JString(hex0x(w))).toList)))
    e.storage.foreach(s => fields = fields :+ ("storage" ->
      JObject(s.toList.map { case (k, v) =>
        ("0x" + k.toBigInt.toString(16)) -> JString("0x" + v.toBigInt.toString(16))
      })))
    e.error.foreach(err => fields = fields :+ ("error" -> JString(err)))
    JObject(fields)
  }

  private def encodeTraceResponse(t: TraceTransactionResponse): JValue = JObject(
    "gas" -> JInt(t.gas),
    "failed" -> JBool(t.failed),
    "returnValue" -> JString(
      org.bouncycastle.util.encoders.Hex.toHexString(t.returnValue.toArray)),
    "structLogs" -> JArray(t.structLogs.map(encodeStructLog))
  )

  implicit val debug_traceTransaction: JsonMethodCodec[TraceTransactionRequest, TraceTransactionResponse] =
    new JsonMethodDecoder[TraceTransactionRequest] with JsonEncoder[TraceTransactionResponse] {
      def decodeJson(params: Option[JArray]): Either[JsonRpcError, TraceTransactionRequest] =
        params match {
          case Some(JArray(JString(hash) :: _)) =>
            scala.util.Try(ByteString(org.bouncycastle.util.encoders.Hex.decode(hash.stripPrefix("0x"))))
              .toEither.left.map(_ => JsonRpcError.InvalidParams())
              .map(TraceTransactionRequest.apply)
          case _ => Left(JsonRpcError.InvalidParams("debug_traceTransaction expects [txHash, ...]"))
        }
      def encodeJson(t: TraceTransactionResponse): JValue = encodeTraceResponse(t)
    }

  case class TraceBlockByNumberRequest(blockNumber: BigInt)
  case class TraceBlockByHashRequest(blockHash: ByteString)
  case class TraceBlockResponse(results: List[TraceTransactionResponse])

  implicit val debug_traceBlockByNumber: JsonMethodCodec[TraceBlockByNumberRequest, TraceBlockResponse] =
    new JsonMethodDecoder[TraceBlockByNumberRequest] with JsonEncoder[TraceBlockResponse] {
      def decodeJson(params: Option[JArray]): Either[JsonRpcError, TraceBlockByNumberRequest] =
        params match {
          case Some(JArray(JString(s) :: _)) =>
            scala.util.Try(BigInt(s.stripPrefix("0x"), 16))
              .toEither.left.map(_ => JsonRpcError.InvalidParams())
              .map(TraceBlockByNumberRequest.apply)
          case _ => Left(JsonRpcError.InvalidParams("debug_traceBlockByNumber expects [blockNumber, ...]"))
        }
      def encodeJson(t: TraceBlockResponse): JValue =
        JArray(t.results.map(encodeTraceResponse))
    }

  implicit val debug_traceBlockByHash: JsonMethodCodec[TraceBlockByHashRequest, TraceBlockResponse] =
    new JsonMethodDecoder[TraceBlockByHashRequest] with JsonEncoder[TraceBlockResponse] {
      def decodeJson(params: Option[JArray]): Either[JsonRpcError, TraceBlockByHashRequest] =
        params match {
          case Some(JArray(JString(h) :: _)) =>
            scala.util.Try(ByteString(org.bouncycastle.util.encoders.Hex.decode(h.stripPrefix("0x"))))
              .toEither.left.map(_ => JsonRpcError.InvalidParams())
              .map(TraceBlockByHashRequest.apply)
          case _ => Left(JsonRpcError.InvalidParams("debug_traceBlockByHash expects [blockHash, ...]"))
        }
      def encodeJson(t: TraceBlockResponse): JValue =
        JArray(t.results.map(encodeTraceResponse))
    }
}
