package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.util.ByteString

import org.json4s.JsonAST.*
import org.json4s.MonadicJValue.jvalueToMonadic
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.jsonrpc.DebugTracingService.*

/** Unit tests for the debug_trace* JSON codecs — specifically the {txHash, result} envelope required by the hive
  * rpc-compat openrpc-tracer.json schema for debug_traceBlockByHash / debug_traceBlockByNumber:
  *
  * {{{
  * "items": {
  *   "anyOf": [
  *     {"title": "Opcode tracer entry", "type": "object", "required": ["txHash", "result"]},
  *     ...
  *   ]
  * }
  * }}}
  *
  * Before this fix, DebugTracingService.traceAllTxsInBlock returned bare tracer.getResult values and the encoder
  * wrapped them in JArray(...) directly — producing an array of bare result objects with no txHash, which fails the
  * schema's `required: [txHash, result]` on every element.
  *
  * debug_traceTransaction / debug_traceCall are explicitly NOT part of this envelope — they return the bare result
  * object per the same schema ("Transaction trace" is a plain object, not an array of entries).
  */
class DebugTracingJsonMethodsImplicitsSpec extends AnyFreeSpec with Matchers:

  private val txHash: ByteString = ByteString(Array.fill(32)(0xab.toByte))
  private val txHashHex = "0x" + ("ab" * 32)
  private val innerResult: JValue = JObject("gas" -> JInt(21000), "failed" -> JBool(false))

  "debug_traceBlockByNumber encoder" - {
    "wraps each per-tx trace as {txHash, result}, per openrpc-tracer.json's required fields" in {
      val response = TraceBlockByNumberResponse(Seq(TxTraceResult(txHash, innerResult)))

      val encoded = DebugTracingJsonMethodsImplicits.debug_traceBlockByNumber.encodeJson(response)

      encoded shouldBe a[JArray]
      val entries = encoded.asInstanceOf[JArray].arr
      entries should have size 1
      (entries.head \ "txHash") shouldBe JString(txHashHex)
      (entries.head \ "result") shouldBe innerResult
    }

    "wraps multiple entries in the tx order given, each with its own hash" in {
      val secondHash = ByteString(Array.fill(32)(0xcd.toByte))
      val secondResult: JValue = JObject("gas" -> JInt(50000), "failed" -> JBool(true))
      val response = TraceBlockByNumberResponse(
        Seq(TxTraceResult(txHash, innerResult), TxTraceResult(secondHash, secondResult))
      )

      val entries =
        DebugTracingJsonMethodsImplicits.debug_traceBlockByNumber.encodeJson(response).asInstanceOf[JArray].arr

      entries should have size 2
      (entries.head \ "txHash") shouldBe JString(txHashHex)
      (entries(1) \ "txHash") shouldBe JString("0x" + ("cd" * 32))
      (entries(1) \ "result") shouldBe secondResult
    }

    "produces an empty array for a block with no transactions" in {
      val encoded =
        DebugTracingJsonMethodsImplicits.debug_traceBlockByNumber.encodeJson(TraceBlockByNumberResponse(Seq.empty))
      encoded shouldBe JArray(Nil)
    }
  }

  "debug_traceBlockByHash encoder" - {
    "wraps each per-tx trace as {txHash, result}, same schema family as debug_traceBlockByNumber" in {
      val response = TraceBlockByHashResponse(Seq(TxTraceResult(txHash, innerResult)))

      val entries =
        DebugTracingJsonMethodsImplicits.debug_traceBlockByHash.encodeJson(response).asInstanceOf[JArray].arr

      entries should have size 1
      (entries.head \ "txHash") shouldBe JString(txHashHex)
      (entries.head \ "result") shouldBe innerResult
    }
  }

  "debug_traceTransaction encoder" - {
    "returns the bare result object, NOT wrapped in a {txHash, result} envelope" in {
      val response = TraceTransactionResponse(innerResult)

      DebugTracingJsonMethodsImplicits.debug_traceTransaction.encodeJson(response) shouldBe innerResult
    }
  }

  "debug_traceCall encoder" - {
    "returns the bare result object, NOT wrapped in a {txHash, result} envelope" in {
      val response = TraceCallResponse(innerResult)

      DebugTracingJsonMethodsImplicits.debug_traceCall.encodeJson(response) shouldBe innerResult
    }
  }

  /** Pins CHASE-QUEUE C7: extractTraceConfig previously read "disableMemory" — a field name no real caller sends — so
    * the unconditional default left memory capture ON. go-ethereum / execution-apis send "enableMemory" (direct sense,
    * default off). Params below are taken verbatim from the .io fixtures under
    * execution-apis/tests/debug_traceBlockByNumber.
    */
  "extractTraceConfig" - {
    "defaults to memory OFF when no config object is sent at all, matching go-ethereum's zero-value Config" in {
      val config = DebugTracingJsonMethodsImplicits.extractTraceConfig(None)
      config shouldBe Right(TraceConfig())
      config.map(_.enableMemory) shouldBe Right(false)
    }

    "reads enableMemory / enableReturnData / disableStack / disableStorage under the exact field names " +
      "sent by trace-block-memory-encoding.io" in {
        val param = Some(
          JObject(
            "disableStack" -> JBool(false),
            "disableStorage" -> JBool(false),
            "enableMemory" -> JBool(true),
            "enableReturnData" -> JBool(true)
          )
        )
        DebugTracingJsonMethodsImplicits.extractTraceConfig(param) shouldBe Right(
          TraceConfig(disableStorage = false, enableMemory = true, disableStack = false, enableReturnData = true)
        )
      }

    "keeps enableMemory=false when a caller explicitly sends enableMemory:false — the exact params " +
      "trace-block-storage-encoding.io and trace-block-storage-snapshot-timing.io send, previously ignored " +
      "in favour of an always-true default" in {
        val param = Some(
          JObject(
            "disableStack" -> JBool(false),
            "disableStorage" -> JBool(false),
            "enableMemory" -> JBool(false),
            "enableReturnData" -> JBool(true)
          )
        )
        DebugTracingJsonMethodsImplicits.extractTraceConfig(param).map(_.enableMemory) shouldBe Right(false)
      }

    "falls back to the legacy disableMemory (inverted) key only when enableMemory itself is absent" in {
      val param = Some(JObject("disableMemory" -> JBool(true)))
      DebugTracingJsonMethodsImplicits.extractTraceConfig(param).map(_.enableMemory) shouldBe Right(false)
    }

    "prefers enableMemory over a conflicting legacy disableMemory when both are present" in {
      val param = Some(JObject("enableMemory" -> JBool(true), "disableMemory" -> JBool(true)))
      DebugTracingJsonMethodsImplicits.extractTraceConfig(param).map(_.enableMemory) shouldBe Right(true)
    }
  }
