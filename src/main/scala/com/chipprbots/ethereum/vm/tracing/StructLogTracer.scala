package com.chipprbots.ethereum.vm.tracing

import scala.collection.mutable

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.UInt256

/** Geth's default `structLog` tracer. Records one entry per executed opcode capturing the
  * program counter, opcode mnemonic, gas remaining BEFORE the op, gas cost, depth, stack,
  * memory, storage slots accessed, and any error.
  *
  * Wire format matches geth's `debug_traceTransaction` struct-log response.
  */
class StructLogTracer(
    disableStack: Boolean = false,
    disableMemory: Boolean = false,
    disableStorage: Boolean = false
) extends Tracer {

  /** One entry per executed opcode, in order. */
  val logs: mutable.ListBuffer[StructLogEntry] = mutable.ListBuffer.empty

  @volatile private var gasLimit: BigInt = 0
  @volatile private var totalGasUsed: BigInt = 0
  @volatile private var finalReturn: ByteString = ByteString.empty
  @volatile private var finalError: Option[String] = None

  override def onTxStart(gasLimit: BigInt, to: Option[Address], from: Address, value: UInt256): Unit = {
    this.gasLimit = gasLimit
  }

  override def onTxEnd(gasUsed: BigInt, returnData: ByteString, error: Option[String]): Unit = {
    totalGasUsed = gasUsed
    finalReturn = returnData
    finalError = error
  }

  override def onOpcode(
      pc: Int,
      op: String,
      gas: BigInt,
      gasCost: BigInt,
      depth: Int,
      stack: Seq[UInt256],
      memory: ByteString,
      storage: Map[UInt256, UInt256],
      returnData: ByteString,
      error: Option[String]
  ): Unit = {
    logs += StructLogEntry(
      pc = pc,
      op = op,
      gas = gas,
      gasCost = gasCost,
      depth = depth + 1, // geth uses 1-based depth
      stack = if (disableStack) None else Some(stack.toList),
      memory = if (disableMemory) None else Some(memory),
      storage = if (disableStorage) None else Some(storage),
      error = error
    )
  }

  /** Snapshot of the final tracer state, suitable for JSON serialisation in the
    * geth-compatible `debug_traceTransaction` response shape.
    */
  def result: StructLogResult = StructLogResult(
    gas = totalGasUsed,
    failed = finalError.isDefined,
    returnValue = finalReturn,
    structLogs = logs.toList
  )
}

case class StructLogEntry(
    pc: Int,
    op: String,
    gas: BigInt,
    gasCost: BigInt,
    depth: Int,
    stack: Option[List[UInt256]],
    memory: Option[ByteString],
    storage: Option[Map[UInt256, UInt256]],
    error: Option[String]
)

case class StructLogResult(
    gas: BigInt,
    failed: Boolean,
    returnValue: ByteString,
    structLogs: List[StructLogEntry]
)
