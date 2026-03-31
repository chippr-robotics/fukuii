package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.util.ByteString

import org.json4s.JsonAST._
import org.json4s.JsonDSL._

import com.chipprbots.ethereum.jsonrpc.DebugTracingService._
import com.chipprbots.ethereum.jsonrpc.JsonRpcError.InvalidParams
import com.chipprbots.ethereum.jsonrpc.serialization.JsonEncoder
import com.chipprbots.ethereum.jsonrpc.serialization.JsonMethodDecoder
import com.chipprbots.ethereum.vm.StructLog

object DebugTracingJsonMethodsImplicits extends JsonMethodsImplicits {

  private def extractTraceConfig(obj: JValue): TraceConfig = obj match {
    case JObject(fields) =>
      val fieldMap = fields.toMap
      TraceConfig(
        disableStack = fieldMap.get("disableStack").collect { case JBool(v) => v }.getOrElse(false),
        disableStorage = fieldMap.get("disableStorage").collect { case JBool(v) => v }.getOrElse(true),
        enableMemory = fieldMap.get("enableMemory").collect { case JBool(v) => v }.getOrElse(false),
        enableReturnData = fieldMap.get("enableReturnData").collect { case JBool(v) => v }.getOrElse(false),
        limit = fieldMap.get("limit").collect { case JInt(v) => v.toInt }.getOrElse(0),
        tracer = fieldMap.get("tracer").collect { case JString(v) => v },
        tracerConfig = fieldMap.get("tracerConfig").collect { case o: JObject => o }
      )
    case _ => TraceConfig()
  }

  private def encodeStructLog(sl: StructLog): JValue = {
    val base: JObject =
      ("pc" -> sl.pc) ~
        ("op" -> sl.op) ~
        ("gas" -> encodeAsHex(sl.gas)) ~
        ("gasCost" -> encodeAsHex(sl.gasCost)) ~
        ("depth" -> sl.depth)

    val withStack = if (sl.stack.nonEmpty) {
      base ~ ("stack" -> JArray(sl.stack.toList.map(v => JString(encodeQuantity(v)))))
    } else {
      base ~ ("stack" -> JArray(Nil))
    }

    val withMemory = sl.memory match {
      case Some(mem) => withStack ~ ("memory" -> JArray(mem.toList.map(JString(_))))
      case None => withStack
    }

    val withStorage = sl.storage match {
      case Some(stor) =>
        withMemory ~ ("storage" -> JObject(stor.toList.map { case (k, v) => JField(k, JString(v)) }))
      case None => withMemory
    }

    sl.error match {
      case Some(err) => withStorage ~ ("error" -> JString(err))
      case None => withStorage
    }
  }

  private def encodeTraceResponse(gas: BigInt, failed: Boolean, returnValue: ByteString, structLogs: Seq[StructLog]): JValue =
    ("gas" -> encodeAsHex(gas)) ~
      ("failed" -> failed) ~
      ("returnValue" -> encodeAsHex(returnValue)) ~
      ("structLogs" -> JArray(structLogs.toList.map(encodeStructLog)))

  private def encodeQuantity(v: BigInt): String =
    "0x" + v.toString(16)

  implicit val debug_traceTransaction
      : JsonMethodDecoder[DebugTraceTransactionRequest] with JsonEncoder[DebugTraceTransactionResponse] =
    new JsonMethodDecoder[DebugTraceTransactionRequest] with JsonEncoder[DebugTraceTransactionResponse] {
      override def decodeJson(params: Option[JArray]): Either[JsonRpcError, DebugTraceTransactionRequest] =
        params match {
          case Some(JArray(JString(txHash) :: configObj :: Nil)) =>
            extractHash(txHash).map(h => DebugTraceTransactionRequest(h, extractTraceConfig(configObj)))
          case Some(JArray(JString(txHash) :: Nil)) =>
            extractHash(txHash).map(h => DebugTraceTransactionRequest(h))
          case _ => Left(InvalidParams())
        }

      override def encodeJson(t: DebugTraceTransactionResponse): JValue =
        t.nativeResult.getOrElse(encodeTraceResponse(t.gas, t.failed, t.returnValue, t.structLogs))
    }

  implicit val debug_traceCall
      : JsonMethodDecoder[DebugTraceCallRequest] with JsonEncoder[DebugTraceCallResponse] =
    new JsonMethodDecoder[DebugTraceCallRequest] with JsonEncoder[DebugTraceCallResponse] {
      override def decodeJson(params: Option[JArray]): Either[JsonRpcError, DebugTraceCallRequest] =
        params match {
          case Some(JArray((txObj: JObject) :: (blockValue: JValue) :: configObj :: Nil)) =>
            for {
              tx <- EthJsonMethodsImplicits.extractCall(txObj)
              blockParam <- extractBlockParam(blockValue)
            } yield DebugTraceCallRequest(tx, blockParam, extractTraceConfig(configObj))
          case Some(JArray((txObj: JObject) :: (blockValue: JValue) :: Nil)) =>
            for {
              tx <- EthJsonMethodsImplicits.extractCall(txObj)
              blockParam <- extractBlockParam(blockValue)
            } yield DebugTraceCallRequest(tx, blockParam)
          case _ => Left(InvalidParams())
        }

      override def encodeJson(t: DebugTraceCallResponse): JValue =
        t.nativeResult.getOrElse(encodeTraceResponse(t.gas, t.failed, t.returnValue, t.structLogs))
    }

  implicit val debugTraceBlockResponseEncoder: JsonEncoder[DebugTraceBlockResponse] =
    new JsonEncoder[DebugTraceBlockResponse] {
      override def encodeJson(t: DebugTraceBlockResponse): JValue =
        JArray(t.results.toList.map { txResult =>
          val resultJson = txResult.result.nativeResult.getOrElse(
            encodeTraceResponse(
              txResult.result.gas,
              txResult.result.failed,
              txResult.result.returnValue,
              txResult.result.structLogs
            )
          )
          ("txHash" -> encodeAsHex(txResult.txHash)) ~
            ("result" -> resultJson)
        })
    }

  implicit val debug_traceBlockByNumber: JsonMethodDecoder[DebugTraceBlockByNumberRequest] =
    new JsonMethodDecoder[DebugTraceBlockByNumberRequest] {
      override def decodeJson(params: Option[JArray]): Either[JsonRpcError, DebugTraceBlockByNumberRequest] =
        params match {
          case Some(JArray(blockValue :: configObj :: Nil)) =>
            extractBlockParam(blockValue).map(bp => DebugTraceBlockByNumberRequest(bp, extractTraceConfig(configObj)))
          case Some(JArray(blockValue :: Nil)) =>
            extractBlockParam(blockValue).map(bp => DebugTraceBlockByNumberRequest(bp))
          case _ => Left(InvalidParams())
        }
    }

  implicit val debug_traceBlockByHash: JsonMethodDecoder[DebugTraceBlockByHashRequest] =
    new JsonMethodDecoder[DebugTraceBlockByHashRequest] {
      override def decodeJson(params: Option[JArray]): Either[JsonRpcError, DebugTraceBlockByHashRequest] =
        params match {
          case Some(JArray(JString(hash) :: configObj :: Nil)) =>
            extractHash(hash).map(h => DebugTraceBlockByHashRequest(h, extractTraceConfig(configObj)))
          case Some(JArray(JString(hash) :: Nil)) =>
            extractHash(hash).map(h => DebugTraceBlockByHashRequest(h))
          case _ => Left(InvalidParams())
        }
    }

  implicit val debug_traceBlock: JsonMethodDecoder[DebugTraceBlockRequest] =
    new JsonMethodDecoder[DebugTraceBlockRequest] {
      override def decodeJson(params: Option[JArray]): Either[JsonRpcError, DebugTraceBlockRequest] =
        params match {
          case Some(JArray(blockValue :: configObj :: Nil)) =>
            extractBlockParam(blockValue).map(bp => DebugTraceBlockRequest(bp, extractTraceConfig(configObj)))
          case Some(JArray(blockValue :: Nil)) =>
            extractBlockParam(blockValue).map(bp => DebugTraceBlockRequest(bp))
          case _ => Left(InvalidParams())
        }
    }
}
