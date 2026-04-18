package com.chipprbots.ethereum.vm.tracing

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.UInt256

/** A tracer observes EVM execution for offline analysis (debug_traceTransaction,
  * debug_traceCall, etc.). Each callback corresponds to a lifecycle point the VM
  * notifies. Implementations must be thread-unsafe and used for a single VM run.
  *
  * Modeled after geth's vm.EVMLogger interface so traces can be compared directly.
  */
trait Tracer {

  /** Called once before the top-level call / create begins.  */
  def onTxStart(gasLimit: BigInt, to: Option[Address], from: Address, value: UInt256): Unit = ()

  /** Called once after the top-level call / create finishes.
    *
    * @param gasUsed total gas consumed (gasLimit - gasRemaining)
    * @param returnData returned bytes (or revert data on revert)
    * @param error optional textual error (OutOfGas, StackOverflow, Revert, etc.)
    */
  def onTxEnd(gasUsed: BigInt, returnData: ByteString, error: Option[String]): Unit = ()

  /** Called whenever execution enters a new call frame (CALL/CALLCODE/DELEGATECALL/
    * STATICCALL/CREATE/CREATE2). Depth 0 is the top-level tx.
    */
  def onEnter(
      depth: Int,
      callType: String,
      from: Address,
      to: Address,
      input: ByteString,
      gas: BigInt,
      value: UInt256
  ): Unit = ()

  /** Called whenever execution leaves a call frame. */
  def onExit(depth: Int, gasUsed: BigInt, output: ByteString, error: Option[String]): Unit = ()

  /** Called BEFORE each opcode is executed. Receives the current step's snapshot. */
  def onOpcode(
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
  ): Unit = ()
}

object Tracer {
  /** No-op tracer: the default behavior when no tracing is requested. The VM short-circuits
    * all hooks for this instance via an isEmpty check rather than invoking empty methods.
    */
  object Noop extends Tracer
}
