package com.chipprbots.ethereum.vm.tracing

import scala.collection.mutable

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.UInt256

/** Geth-compatible `callTracer`. Captures the call graph (nested CALL/DELEGATECALL/
  * STATICCALL/CALLCODE/CREATE/CREATE2 frames) without logging every opcode. Output is a
  * recursive JSON object describing each frame's from/to/input/output/gas/value/type.
  */
class CallTracer extends Tracer {

  private case class CallFrame(
      callType: String,
      from: Address,
      to: Address,
      value: UInt256,
      input: ByteString,
      gas: BigInt,
      var output: ByteString = ByteString.empty,
      var gasUsed: BigInt = 0,
      var error: Option[String] = None,
      var calls: mutable.ListBuffer[CallFrame] = mutable.ListBuffer.empty
  )

  private val stack: mutable.Stack[CallFrame] = mutable.Stack.empty
  private var topFrame: Option[CallFrame] = None

  override def onTxStart(gasLimit: BigInt, to: Option[Address], from: Address, value: UInt256): Unit = {
    val t = to.getOrElse(Address(0))
    val root = CallFrame(
      callType = if (to.isEmpty) "CREATE" else "CALL",
      from = from,
      to = t,
      value = value,
      input = ByteString.empty,
      gas = gasLimit
    )
    topFrame = Some(root)
    stack.push(root)
  }

  override def onTxEnd(gasUsed: BigInt, returnData: ByteString, error: Option[String]): Unit =
    stack.headOption.foreach { f =>
      f.gasUsed = gasUsed
      f.output = returnData
      f.error = error
    }

  override def onEnter(
      depth: Int,
      callType: String,
      from: Address,
      to: Address,
      input: ByteString,
      gas: BigInt,
      value: UInt256
  ): Unit = {
    val frame = CallFrame(callType, from, to, value, input, gas)
    stack.headOption.foreach(_.calls += frame)
    stack.push(frame)
  }

  override def onExit(depth: Int, gasUsed: BigInt, output: ByteString, error: Option[String]): Unit =
    if (stack.size > 1) {
      val closed = stack.pop()
      closed.gasUsed = gasUsed
      closed.output = output
      closed.error = error
    }

  private def frameToResult(f: CallFrame): CallTracerResult =
    CallTracerResult(
      callType = f.callType,
      from = f.from,
      to = f.to,
      value = f.value,
      input = f.input,
      gas = f.gas,
      gasUsed = f.gasUsed,
      output = f.output,
      error = f.error,
      calls = f.calls.toList.map(frameToResult)
    )

  /** Render the captured graph as a nested data structure the JSON codec can encode. */
  def result: CallTracerResult =
    topFrame.map(frameToResult).getOrElse(
      CallTracerResult("CALL", Address(0), Address(0), UInt256.Zero, ByteString.empty,
        BigInt(0), BigInt(0), ByteString.empty, None, Nil))
}

case class CallTracerResult(
    callType: String,
    from: Address,
    to: Address,
    value: UInt256,
    input: ByteString,
    gas: BigInt,
    gasUsed: BigInt,
    output: ByteString,
    error: Option[String],
    calls: List[CallTracerResult]
)
