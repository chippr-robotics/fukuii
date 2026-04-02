package com.chipprbots.ethereum.vm

import org.apache.pekko.util.ByteString

import org.json4s.JsonAST._
import org.json4s.JsonDSL._

/** A single step in EVM execution, matching go-ethereum's structLog format. */
case class StructLog(
    pc: Int,
    op: String,
    gas: BigInt,
    gasCost: BigInt,
    depth: Int,
    stack: Seq[BigInt],
    memory: Option[Seq[String]],
    storage: Option[Map[String, String]],
    error: Option[String]
)

/** Collects opcode-by-opcode execution trace in go-ethereum structLog format.
  *
  * This tracer is injected into the VM execution loop and called after each
  * opcode. It produces the same output format as go-ethereum's debug_traceTransaction.
  *
  * @param enableMemory include memory snapshot per step (expensive)
  * @param enableStorage include storage diff per step (expensive)
  * @param limit maximum number of steps to capture (0 = unlimited)
  */
class StructLogTracer(
    enableMemory: Boolean = false,
    enableStorage: Boolean = false,
    limit: Int = 0
) extends ExecutionTracer {
  private val steps = scala.collection.mutable.ArrayBuffer[StructLog]()
  private var _gas: BigInt = 0
  private var _failed: Boolean = false
  private var _returnValue: ByteString = ByteString.empty

  override def onStep[W <: WorldStateProxy[W, S], S <: Storage[S]](
      opCode: OpCode,
      prevState: ProgramState[W, S],
      nextState: ProgramState[W, S]
  ): Unit = {
    if (limit > 0 && steps.size >= limit) return

    val gasCost = prevState.gas - nextState.gas

    val memorySnapshot = if (enableMemory) {
      val mem = prevState.memory
      if (mem.size > 0) {
        // Format as 32-byte hex words
        val words = (0 until mem.size by 32).map { offset =>
          val word = mem.load(offset, 32)._1
          word.toArray.map("%02x".format(_)).mkString
        }
        Some(words.toSeq)
      } else Some(Seq.empty)
    } else None

    val storageSnapshot = if (enableStorage) {
      opCode match {
        case SLOAD if prevState.stack.size >= 1 =>
          val slot = prevState.stack.peek(0).toBigInt
          val value = nextState.stack.peek(0).toBigInt
          val k = "0x" + slot.toString(16).reverse.padTo(64, '0').reverse
          val v = "0x" + value.toString(16).reverse.padTo(64, '0').reverse
          Some(Map(k -> v))
        case SSTORE if prevState.stack.size >= 2 =>
          val slot = prevState.stack.peek(0).toBigInt
          val value = prevState.stack.peek(1).toBigInt
          val k = "0x" + slot.toString(16).reverse.padTo(64, '0').reverse
          val v = "0x" + value.toString(16).reverse.padTo(64, '0').reverse
          Some(Map(k -> v))
        case _ => None
      }
    } else None

    val error = nextState.error.map(_.toString)

    steps += StructLog(
      pc = prevState.pc,
      op = opCode.toString,
      gas = prevState.gas,
      gasCost = gasCost,
      depth = prevState.env.callDepth + 1, // go-ethereum uses 1-based depth
      stack = prevState.stack.toSeq.map(_.toBigInt),
      memory = memorySnapshot,
      storage = storageSnapshot,
      error = error
    )
  }

  def setResult(gas: BigInt, returnValue: ByteString, failed: Boolean): Unit = {
    _gas = gas
    _returnValue = returnValue
    _failed = failed
  }

  def getSteps: Seq[StructLog] = steps.toSeq
  def gas: BigInt = _gas
  def failed: Boolean = _failed
  def returnValue: ByteString = _returnValue

  /** Not used for StructLogTracer — response is built by DebugTracingJsonMethodsImplicits using getSteps/gas/failed/returnValue.
    * This exists to satisfy the ExecutionTracer trait for native tracers (callTracer, prestateTracer).
    */
  override def getResult: JValue = JNothing
}
