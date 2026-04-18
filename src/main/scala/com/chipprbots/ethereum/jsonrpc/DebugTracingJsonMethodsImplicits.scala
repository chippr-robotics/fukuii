package com.chipprbots.ethereum.jsonrpc

import org.json4s.JsonAST._
import org.json4s.JsonDSL._

import com.chipprbots.ethereum.jsonrpc.DebugTracingService._
import com.chipprbots.ethereum.jsonrpc.EthJsonMethodsImplicits.extractCall
import com.chipprbots.ethereum.jsonrpc.serialization.JsonEncoder
import com.chipprbots.ethereum.jsonrpc.serialization.JsonMethodCodec

/** JSON-RPC codecs for the debug_trace* family of methods.
  *
  * Besu reference: ethereum/api/src/main/java/org/hyperledger/besu/ethereum/api/jsonrpc/internal/methods/
  *   - DebugTraceTransaction.java — debug_traceTransaction
  *   - DebugTraceBlock.java — debug_traceBlockByHash
  *   - DebugTraceBlockByNumber.java — debug_traceBlockByNumber
  *   - DebugTraceCall.java — debug_traceCall
  *
  * core-geth reference: internal/ethapi/api.go (TraceCallMany), eth/tracers/api.go
  *
  * Parameter format: debug_traceTransaction(txHash [, traceConfig]) debug_traceCall(callObj, blockParam [,
  * traceConfig]) debug_traceCallMany([{callObj, traceConfig},...], blockParam) debug_traceBlockByHash(blockHash [,
  * traceConfig]) debug_traceBlockByNumber(blockParam [, traceConfig])
  */
object DebugTracingJsonMethodsImplicits extends JsonMethodsImplicits {

  implicit val debug_traceTransaction: JsonMethodCodec[TraceTransactionRequest, TraceTransactionResponse] =
    new JsonMethodCodec[TraceTransactionRequest, TraceTransactionResponse] {
      override def decodeJson(params: Option[JArray]): Either[JsonRpcError, TraceTransactionRequest] =
        params match {
          case Some(JArray(JString(hash) :: rest)) =>
            for {
              txHash <- extractBytes(hash)
              config <- extractTraceConfig(rest.headOption)
            } yield TraceTransactionRequest(txHash, config)
          case _ =>
            Left(JsonRpcError.InvalidParams())
        }

      override def encodeJson(t: TraceTransactionResponse): JValue = t.result
    }

  implicit val debug_traceCall: JsonMethodCodec[TraceCallRequest, TraceCallResponse] =
    new JsonMethodCodec[TraceCallRequest, TraceCallResponse] {
      override def decodeJson(params: Option[JArray]): Either[JsonRpcError, TraceCallRequest] =
        params match {
          case Some(JArray((txObj: JObject) :: blockParam :: rest)) =>
            for {
              tx <- extractCall(txObj)
              block <- extractBlockParam(blockParam)
              config <- extractTraceConfig(rest.headOption)
            } yield TraceCallRequest(tx, block, config)
          case Some(JArray((txObj: JObject) :: Nil)) =>
            for {
              tx <- extractCall(txObj)
            } yield TraceCallRequest(tx, BlockParam.Latest)
          case _ =>
            Left(JsonRpcError.InvalidParams())
        }

      override def encodeJson(t: TraceCallResponse): JValue = t.result
    }

  implicit val debug_traceCallMany: JsonMethodCodec[TraceCallManyRequest, TraceCallManyResponse] =
    new JsonMethodCodec[TraceCallManyRequest, TraceCallManyResponse] {
      override def decodeJson(params: Option[JArray]): Either[JsonRpcError, TraceCallManyRequest] =
        params match {
          case Some(JArray(JArray(callList) :: blockParam :: _)) =>
            for {
              block <- extractBlockParam(blockParam)
              calls <- {
                val decoded = callList.map {
                  case JArray((txObj: JObject) :: rest) =>
                    for {
                      tx <- extractCall(txObj)
                      config <- extractTraceConfig(rest.headOption)
                    } yield (tx, config)
                  case _ =>
                    Left(JsonRpcError.InvalidParams("Each call must be [callObj, traceConfig]"))
                }
                decoded.foldRight[Either[JsonRpcError, List[(EthInfoService.CallTx, TraceConfig)]]](Right(Nil)) {
                  (e, acc) => for { h <- e; t <- acc } yield h :: t
                }
              }
            } yield TraceCallManyRequest(calls, block)
          case _ =>
            Left(JsonRpcError.InvalidParams())
        }

      override def encodeJson(t: TraceCallManyResponse): JValue = JArray(t.results.toList)
    }

  implicit val debug_traceBlockByHash: JsonMethodCodec[TraceBlockByHashRequest, TraceBlockByHashResponse] =
    new JsonMethodCodec[TraceBlockByHashRequest, TraceBlockByHashResponse] {
      override def decodeJson(params: Option[JArray]): Either[JsonRpcError, TraceBlockByHashRequest] =
        params match {
          case Some(JArray(JString(hash) :: rest)) =>
            for {
              blockHash <- extractBytes(hash)
              config <- extractTraceConfig(rest.headOption)
            } yield TraceBlockByHashRequest(blockHash, config)
          case _ =>
            Left(JsonRpcError.InvalidParams())
        }

      override def encodeJson(t: TraceBlockByHashResponse): JValue =
        JArray(t.results.toList)
    }

  implicit val debug_traceBlockByNumber: JsonMethodCodec[TraceBlockByNumberRequest, TraceBlockByNumberResponse] =
    new JsonMethodCodec[TraceBlockByNumberRequest, TraceBlockByNumberResponse] {
      override def decodeJson(params: Option[JArray]): Either[JsonRpcError, TraceBlockByNumberRequest] =
        params match {
          case Some(JArray(blockParam :: rest)) =>
            for {
              block <- extractBlockParam(blockParam)
              config <- extractTraceConfig(rest.headOption)
            } yield TraceBlockByNumberRequest(block, config)
          case _ =>
            Left(JsonRpcError.InvalidParams())
        }

      override def encodeJson(t: TraceBlockByNumberResponse): JValue =
        JArray(t.results.toList)
    }

  implicit val debug_intermediateRoots: JsonMethodCodec[IntermediateRootsRequest, IntermediateRootsResponse] =
    new JsonMethodCodec[IntermediateRootsRequest, IntermediateRootsResponse] {
      override def decodeJson(params: Option[JArray]): Either[JsonRpcError, IntermediateRootsRequest] =
        params match {
          case Some(JArray(JString(hash) :: _)) =>
            extractBytes(hash).map(IntermediateRootsRequest(_))
          case _ =>
            Left(JsonRpcError.InvalidParams())
        }

      override def encodeJson(t: IntermediateRootsResponse): JValue =
        JArray(t.roots.map(h => JString("0x" + org.bouncycastle.util.encoders.Hex.toHexString(h.toArray))).toList)
    }

  implicit val debug_traceChain: JsonMethodCodec[TraceChainRequest, Seq[TraceChainBlockResult]] =
    new JsonMethodCodec[TraceChainRequest, Seq[TraceChainBlockResult]] {
      override def decodeJson(params: Option[JArray]): Either[JsonRpcError, TraceChainRequest] =
        params match {
          case Some(JArray(fromParam :: toParam :: rest)) =>
            for {
              from <- extractBlockParam(fromParam)
              to <- extractBlockParam(toParam)
              config <- extractTraceConfig(rest.headOption)
            } yield TraceChainRequest(from, to, config)
          case _ =>
            Left(JsonRpcError.InvalidParams())
        }

      override def encodeJson(t: Seq[TraceChainBlockResult]): JValue =
        JArray(t.map { r =>
          JObject(
            "block" -> JString("0x" + r.block.toString(16)),
            "blockHash" -> JString("0x" + org.bouncycastle.util.encoders.Hex.toHexString(r.blockHash.toArray)),
            "traces" -> JArray(r.traces.toList)
          )
        }.toList)
    }

  // ─── Helpers ──────────────────────────────────────────────────────────────────

  /** Decodes an optional TraceConfig from a JSON object parameter. Absent or JNull → default TraceConfig().
    *
    * Besu/core-geth traceConfig fields: tracer: string (named tracer, e.g. "callTracer") disableStorage: boolean
    * disableMemory: boolean disableStack: boolean
    */
  def extractTraceConfig(param: Option[JValue]): Either[JsonRpcError, TraceConfig] =
    param match {
      case None | Some(JNull) | Some(JNothing) =>
        Right(TraceConfig())
      case Some(JObject(fields)) =>
        val map = fields.toMap
        val tracer = map.get("tracer").collect { case JString(s) => s }
        val disableStorage = map.get("disableStorage").collect { case JBool(b) => b }.getOrElse(false)
        val disableMemory = map.get("disableMemory").collect { case JBool(b) => b }.getOrElse(false)
        val disableStack = map.get("disableStack").collect { case JBool(b) => b }.getOrElse(false)
        Right(TraceConfig(tracer, disableStorage, disableMemory, disableStack))
      case _ =>
        Right(TraceConfig()) // ignore unknown forms
    }
}
