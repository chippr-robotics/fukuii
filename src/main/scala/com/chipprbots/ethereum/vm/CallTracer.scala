package com.chipprbots.ethereum.vm

import scala.collection.mutable

import org.apache.pekko.util.ByteString

import org.json4s.JsonAST._
import org.json4s.JsonDSL._

import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.utils.Hex

/** Native callTracer matching go-ethereum's eth/tracers/native/call.go.
  *
  * Produces a nested call tree for debug_traceTransaction/debug_traceCall:
  * {{{
  * {
  *   "type": "CALL",
  *   "from": "0x...",
  *   "to": "0x...",
  *   "gas": 123456,
  *   "gasUsed": 45678,
  *   "value": "0x0",
  *   "input": "0x...",
  *   "output": "0x...",
  *   "error": "execution reverted",
  *   "revertReason": "ERC20: ...",
  *   "calls": [ ... ]
  * }
  * }}}
  *
  * Besu reference: FlatTraceGenerator produces OpenEthereum flat trace format (different schema). This tracer matches
  * go-ethereum's callTracer nested call tree format, which is the standard for debug_traceTransaction with {tracer:
  * "callTracer"}.
  *
  * @param onlyTopCall
  *   when true, only capture the top-level call (skip sub-calls)
  */
class CallTracer(onlyTopCall: Boolean = false) extends ExecutionTracer {

  private case class CallFrame(
      opCode: String,
      from: Address,
      to: Address,
      gas: BigInt,
      value: BigInt,
      input: ByteString,
      var gasUsed: BigInt = 0,
      var output: ByteString = ByteString.empty,
      var error: Option[String] = None,
      var revertReason: Option[String] = None,
      calls: mutable.ArrayBuffer[CallFrame] = mutable.ArrayBuffer.empty
  )

  private val callStack = mutable.Stack[CallFrame]()
  private var rootFrame: Option[CallFrame] = None

  override def onTxStart(from: Address, to: Option[Address], gas: BigInt, value: BigInt, input: ByteString): Unit = {
    val opCode = if (to.isDefined) "CALL" else "CREATE"
    val frame = CallFrame(
      opCode = opCode,
      from = from,
      to = to.getOrElse(Address(0)),
      gas = gas,
      value = value,
      input = input
    )
    callStack.push(frame)
    rootFrame = Some(frame)
  }

  override def onTxEnd(gasUsed: BigInt, output: ByteString, error: Option[String]): Unit =
    if (callStack.nonEmpty) {
      val frame = callStack.pop()
      frame.gasUsed = gasUsed
      frame.output = output
      frame.error = error
      if (error.exists(_.contains("execution reverted")) && output.length >= 4) {
        frame.revertReason = parseRevertReason(output)
      }
    }

  override def onCallEnter(
      opCode: String,
      from: Address,
      to: Address,
      gas: BigInt,
      value: BigInt,
      input: ByteString
  ): Unit = {
    if (onlyTopCall) return

    val frame = CallFrame(
      opCode = opCode,
      from = from,
      to = to,
      gas = gas,
      value = value,
      input = input
    )
    callStack.push(frame)
  }

  override def onCallExit(gasUsed: BigInt, output: ByteString, error: Option[String]): Unit = {
    if (onlyTopCall) return
    if (callStack.size <= 1) return // don't pop the root frame

    val frame = callStack.pop()
    frame.gasUsed = gasUsed
    frame.output = output
    frame.error = error
    if (error.exists(_.contains("execution reverted")) && output.length >= 4) {
      frame.revertReason = parseRevertReason(output)
    }

    if (callStack.nonEmpty) {
      callStack.top.calls += frame
    }
  }

  override def getResult: JValue = rootFrame match {
    case Some(frame) => encodeFrame(frame)
    case None        => JNull
  }

  private def encodeFrame(frame: CallFrame): JValue = {
    var obj: JObject = ("type" -> frame.opCode) ~
      ("from" -> encodeAddress(frame.from)) ~
      ("to" -> encodeAddress(frame.to)) ~
      ("gas" -> JString("0x" + frame.gas.toString(16))) ~
      ("gasUsed" -> JString("0x" + frame.gasUsed.toString(16)))

    if (frame.opCode == "CALL" || frame.opCode == "CREATE" || frame.opCode == "CREATE2") {
      obj = obj ~ ("value" -> encodeHex(frame.value))
    }

    obj = obj ~
      ("input" -> encodeHexBytes(frame.input)) ~
      ("output" -> encodeHexBytes(frame.output))

    frame.error.foreach(e => obj = obj ~ ("error" -> JString(e)))
    frame.revertReason.foreach(r => obj = obj ~ ("revertReason" -> JString(r)))

    if (frame.calls.nonEmpty) {
      obj = obj ~ ("calls" -> JArray(frame.calls.toList.map(encodeFrame)))
    }

    obj
  }

  private def encodeAddress(addr: Address): JString =
    JString("0x" + Hex.toHexString(addr.bytes.toArray))

  private def encodeHex(value: BigInt): JString =
    JString("0x" + value.toString(16))

  private def encodeHexBytes(bs: ByteString): JString =
    if (bs.isEmpty) JString("0x")
    else JString("0x" + Hex.toHexString(bs.toArray))

  /** Parse Solidity revert reason from ABI-encoded error data. Format: 0x08c379a0 + offset + length + utf8 string
    */
  private def parseRevertReason(data: ByteString): Option[String] = {
    if (data.length < 68) return None
    val selector = data.take(4)
    if (selector != ByteString(0x08, 0xc3, 0x79, 0xa0)) return None
    try {
      val offset = BigInt(1, data.slice(4, 36).toArray).toInt
      val length = BigInt(1, data.slice(36 + offset, 68 + offset).toArray).toInt
      if (data.length < 68 + offset + length) return None
      Some(new String(data.slice(68 + offset, 68 + offset + length).toArray, "UTF-8"))
    } catch {
      case _: Exception => None
    }
  }
}
