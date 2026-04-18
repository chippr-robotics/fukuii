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

  private def encodeAddr(a: com.chipprbots.ethereum.domain.Address): JString =
    JString("0x" + org.bouncycastle.util.encoders.Hex.toHexString(a.bytes.toArray))

  private def encodeCallFrame(r: com.chipprbots.ethereum.vm.tracing.CallTracerResult): JObject = {
    var fields: List[(String, JValue)] = List(
      "type" -> JString(r.callType),
      "from" -> encodeAddr(r.from),
      "to" -> encodeAddr(r.to),
      "value" -> JString("0x" + r.value.toBigInt.toString(16)),
      "gas" -> JString("0x" + r.gas.toString(16)),
      "gasUsed" -> JString("0x" + r.gasUsed.toString(16)),
      "input" -> JString(hex0x(r.input)),
      "output" -> JString(hex0x(r.output))
    )
    r.error.foreach(e => fields = fields :+ ("error" -> JString(e)))
    if (r.calls.nonEmpty) fields = fields :+ ("calls" -> JArray(r.calls.map(encodeCallFrame)))
    JObject(fields)
  }

  private def encodePrestate(r: com.chipprbots.ethereum.vm.tracing.PrestateTracerResult): JObject =
    JObject(r.touchedAddresses.toList.map { a =>
      val storageKeys = r.touchedStorage.collect { case (addr, slot) if addr == a => slot }
      val fields: List[(String, JValue)] =
        if (storageKeys.nonEmpty)
          List("storage" -> JObject(storageKeys.toList.map(s =>
            ("0x" + s.toBigInt.toString(16)) -> JString("0x0"))))
        else Nil
      ("0x" + org.bouncycastle.util.encoders.Hex.toHexString(a.bytes.toArray)) -> JObject(fields)
    })

  private def encodeTraceResponse(t: TraceTransactionResponse): JValue =
    (t.callTracerResult, t.prestateTracerResult) match {
      case (Some(call), _) => encodeCallFrame(call)
      case (_, Some(pre)) => encodePrestate(pre)
      case _ => JObject(
        "gas" -> JInt(t.gas),
        "failed" -> JBool(t.failed),
        "returnValue" -> JString(
          org.bouncycastle.util.encoders.Hex.toHexString(t.returnValue.toArray)),
        "structLogs" -> JArray(t.structLogs.map(encodeStructLog))
      )
    }

  /** Parse geth-compatible TraceConfig from the optional second RPC param. */
  private def parseTraceConfig(obj: JValue): DebugService.TraceConfig = obj match {
    case JObject(fs) =>
      def b(name: String): Boolean =
        fs.collectFirst { case (k, JBool(v)) if k == name => v }.getOrElse(false)
      val tracer = fs.collectFirst { case ("tracer", JString(s)) => s }
      DebugService.TraceConfig(
        disableStack = b("disableStack"),
        disableMemory = b("disableMemory"),
        disableStorage = b("disableStorage"),
        tracer = tracer
      )
    case _ => DebugService.TraceConfig()
  }

  implicit val debug_traceTransaction: JsonMethodCodec[TraceTransactionRequest, TraceTransactionResponse] =
    new JsonMethodDecoder[TraceTransactionRequest] with JsonEncoder[TraceTransactionResponse] {
      def decodeJson(params: Option[JArray]): Either[JsonRpcError, TraceTransactionRequest] =
        params match {
          case Some(JArray(JString(hash) :: rest)) =>
            val cfg = rest.headOption.map(parseTraceConfig).getOrElse(DebugService.TraceConfig())
            scala.util.Try(ByteString(org.bouncycastle.util.encoders.Hex.decode(hash.stripPrefix("0x"))))
              .toEither.left.map(_ => JsonRpcError.InvalidParams())
              .map(h => TraceTransactionRequest(h, cfg))
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
