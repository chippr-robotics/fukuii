package com.chipprbots.ethereum.jsonrpc

import org.json4s.JsonAST._
import org.json4s.JsonDSL._

import com.chipprbots.ethereum.jsonrpc.EthJsonMethodsImplicits.extractCall
import com.chipprbots.ethereum.jsonrpc.TraceService._
import com.chipprbots.ethereum.jsonrpc.serialization.JsonEncoder
import com.chipprbots.ethereum.jsonrpc.serialization.JsonMethodCodec

/** JSON-RPC codecs for the trace_* family of methods (Parity/OpenEthereum format).
  *
  * Besu reference: ethereum/api/.../methods/
  *   - TraceTransaction.java               — trace_transaction
  *   - TraceBlock.java                     — trace_block
  *   - TraceReplayTransaction.java         — trace_replayTransaction
  *   - TraceReplayBlockTransactions.java   — trace_replayBlockTransactions
  *   - TraceCall.java                      — trace_call
  *   - TraceCallMany.java                  — trace_callMany
  *
  * core-geth reference: eth/tracers/api.go
  *   - TraceCall(), TraceCallMany(), TraceBlock(), etc.
  *
  * Parameter format:
  *   trace_transaction(txHash)
  *   trace_block(blockParam)
  *   trace_replayTransaction(txHash, traceOptions)
  *   trace_replayBlockTransactions(blockParam, traceOptions)
  *   trace_call(callObj, traceOptions, blockParam)
  *   trace_callMany([[callObj, traceOptions], ...], blockParam)
  *
  * traceOptions is an array of strings, e.g. ["trace"], ["trace","vmTrace"], ["trace","stateDiff"]
  */
object TraceJsonMethodsImplicits extends JsonMethodsImplicits {

  implicit val trace_transaction: JsonMethodCodec[TraceTransactionRequest, TraceTransactionResponse] =
    new JsonMethodCodec[TraceTransactionRequest, TraceTransactionResponse] {
      override def decodeJson(params: Option[JArray]): Either[JsonRpcError, TraceTransactionRequest] =
        params match {
          case Some(JArray(JString(hash) :: _)) =>
            extractBytes(hash).map(TraceTransactionRequest.apply)
          case _ =>
            Left(JsonRpcError.InvalidParams())
        }

      override def encodeJson(t: TraceTransactionResponse): JValue =
        JArray(t.traces.toList)
    }

  implicit val trace_block: JsonMethodCodec[TraceBlockRequest, TraceBlockResponse] =
    new JsonMethodCodec[TraceBlockRequest, TraceBlockResponse] {
      override def decodeJson(params: Option[JArray]): Either[JsonRpcError, TraceBlockRequest] =
        params match {
          case Some(JArray(blockParam :: _)) =>
            extractBlockParam(blockParam).map(TraceBlockRequest.apply)
          case _ =>
            Left(JsonRpcError.InvalidParams())
        }

      override def encodeJson(t: TraceBlockResponse): JValue =
        JArray(t.traces.toList)
    }

  implicit val trace_replayTransaction: JsonMethodCodec[TraceReplayTransactionRequest, TraceReplayTransactionResponse] =
    new JsonMethodCodec[TraceReplayTransactionRequest, TraceReplayTransactionResponse] {
      override def decodeJson(params: Option[JArray]): Either[JsonRpcError, TraceReplayTransactionRequest] =
        params match {
          case Some(JArray(JString(hash) :: JArray(opts) :: _)) =>
            for {
              txHash  <- extractBytes(hash)
              options <- extractTraceOptions(opts)
            } yield TraceReplayTransactionRequest(txHash, options)
          case _ =>
            Left(JsonRpcError.InvalidParams())
        }

      override def encodeJson(t: TraceReplayTransactionResponse): JValue = t.result
    }

  implicit val trace_replayBlockTransactions: JsonMethodCodec[TraceReplayBlockTransactionsRequest, TraceReplayBlockTransactionsResponse] =
    new JsonMethodCodec[TraceReplayBlockTransactionsRequest, TraceReplayBlockTransactionsResponse] {
      override def decodeJson(params: Option[JArray]): Either[JsonRpcError, TraceReplayBlockTransactionsRequest] =
        params match {
          case Some(JArray(blockParam :: JArray(opts) :: _)) =>
            for {
              block   <- extractBlockParam(blockParam)
              options <- extractTraceOptions(opts)
            } yield TraceReplayBlockTransactionsRequest(block, options)
          case _ =>
            Left(JsonRpcError.InvalidParams())
        }

      override def encodeJson(t: TraceReplayBlockTransactionsResponse): JValue =
        JArray(t.results.toList)
    }

  implicit val trace_call: JsonMethodCodec[TraceCallRequest, TraceCallResponse] =
    new JsonMethodCodec[TraceCallRequest, TraceCallResponse] {
      override def decodeJson(params: Option[JArray]): Either[JsonRpcError, TraceCallRequest] =
        params match {
          case Some(JArray((txObj: JObject) :: JArray(opts) :: blockParam :: _)) =>
            for {
              tx      <- extractCall(txObj)
              options <- extractTraceOptions(opts)
              block   <- extractBlockParam(blockParam)
            } yield TraceCallRequest(tx, options, block)
          case Some(JArray((txObj: JObject) :: JArray(opts) :: Nil)) =>
            for {
              tx      <- extractCall(txObj)
              options <- extractTraceOptions(opts)
            } yield TraceCallRequest(tx, options, BlockParam.Latest)
          case _ =>
            Left(JsonRpcError.InvalidParams())
        }

      override def encodeJson(t: TraceCallResponse): JValue = t.result
    }

  implicit val trace_callMany: JsonMethodCodec[TraceCallManyRequest, TraceCallManyResponse] =
    new JsonMethodCodec[TraceCallManyRequest, TraceCallManyResponse] {
      override def decodeJson(params: Option[JArray]): Either[JsonRpcError, TraceCallManyRequest] =
        params match {
          case Some(JArray(JArray(callList) :: blockParam :: _)) =>
            for {
              block <- extractBlockParam(blockParam)
              calls <- {
                val decoded = callList.map {
                  case JArray((txObj: JObject) :: JArray(opts) :: _) =>
                    for {
                      tx      <- extractCall(txObj)
                      options <- extractTraceOptions(opts)
                    } yield (tx, options)
                  case _ =>
                    Left(JsonRpcError.InvalidParams("Each call must be [callObj, traceOptions]"))
                }
                decoded.foldRight[Either[JsonRpcError, List[(EthInfoService.CallTx, TraceOptions)]]](Right(Nil)) {
                  (e, acc) => for { h <- e; t <- acc } yield h :: t
                }
              }
            } yield TraceCallManyRequest(calls, block)
          case _ =>
            Left(JsonRpcError.InvalidParams())
        }

      override def encodeJson(t: TraceCallManyResponse): JValue =
        JArray(t.results.toList)
    }

  // ─── Helpers ──────────────────────────────────────────────────────────────────

  /** Decodes a trace options array: ["trace"], ["trace","vmTrace"], etc.
    *
    * Besu reference: TraceTypeParameter.java — parses an array of trace type strings.
    * Valid values: "trace", "vmTrace", "stateDiff"
    */
  def extractTraceOptions(opts: List[JValue]): Either[JsonRpcError, TraceOptions] = {
    val strs = opts.collect { case JString(s) => s.toLowerCase }
    Right(TraceOptions(
      trace     = strs.contains("trace"),
      vmTrace   = strs.contains("vmtrace"),
      stateDiff = strs.contains("statediff")
    ))
  }
}
