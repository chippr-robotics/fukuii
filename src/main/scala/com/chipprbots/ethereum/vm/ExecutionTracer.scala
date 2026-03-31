package com.chipprbots.ethereum.vm

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.domain.Address

/** Pluggable execution tracer interface, mirroring go-ethereum's Tracer hooks.
  *
  * Implementations:
  *   - StructLogTracer: opcode-by-opcode trace (default, geth structLog format)
  *   - CallTracer: nested call tree (geth native callTracer)
  *   - PrestateTracer: pre-transaction state snapshot (geth native prestateTracer)
  *
  * All methods have default no-op implementations so each tracer only overrides what it needs.
  */
trait ExecutionTracer {

  /** CaptureState — called after each opcode execution in the VM exec loop. */
  def onStep[W <: WorldStateProxy[W, S], S <: Storage[S]](
      opCode: OpCode,
      prevState: ProgramState[W, S],
      nextState: ProgramState[W, S]
  ): Unit = ()

  /** CaptureStart — called once for the top-level transaction before execution begins.
    * @param from
    *   sender address
    * @param to
    *   recipient address (None for contract creation)
    * @param gas
    *   gas allocated
    * @param value
    *   ETH value transferred
    * @param input
    *   call data or init code
    */
  def onTxStart(from: Address, to: Option[Address], gas: BigInt, value: BigInt, input: ByteString): Unit = ()

  /** CaptureEnd — called once when the top-level transaction returns. */
  def onTxEnd(gasUsed: BigInt, output: ByteString, error: Option[String]): Unit = ()

  /** CaptureEnter — called when entering an internal CALL/CALLCODE/DELEGATECALL/STATICCALL/CREATE/CREATE2. Only fires
    * for internal calls (callDepth > 0), not the top-level transaction.
    * @param opCode
    *   the opcode name: "CALL", "STATICCALL", "DELEGATECALL", "CALLCODE", "CREATE", "CREATE2"
    * @param from
    *   calling address
    * @param to
    *   called address (for CREATE/CREATE2, the newly computed address)
    * @param gas
    *   gas allocated to the sub-call
    * @param value
    *   ETH value transferred
    * @param input
    *   call data or init code
    */
  def onCallEnter(
      opCode: String,
      from: Address,
      to: Address,
      gas: BigInt,
      value: BigInt,
      input: ByteString
  ): Unit = ()

  /** CaptureExit — called when an internal CALL/CREATE returns. */
  def onCallExit(gasUsed: BigInt, output: ByteString, error: Option[String]): Unit = ()

  /** Get the tracer-specific result as a JSON value for the RPC response. */
  def getResult: org.json4s.JValue
}
