package com.chipprbots.ethereum.vm

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.domain.Address

/** Pluggable execution tracer interface — Fukuii's equivalent of Besu's OperationTracer.
  *
  * Besu reference: evm/src/main/java/org/hyperledger/besu/evm/tracing/OperationTracer.java
  *
  * Mapping to Besu hooks:
  *   - onStep       → tracePostExecution (after each opcode; prevState gives pre-execution view)
  *   - onTxStart    → traceStartTransaction
  *   - onTxEnd      → traceEndTransaction
  *   - onCallEnter  → traceContextEnter (entering a sub-call frame)
  *   - onCallExit   → traceContextExit  (exiting a sub-call frame)
  *
  * Divergence from Besu: Besu separates tracePreExecution/tracePostExecution; Fukuii uses a
  * single post-execution hook with both prevState and nextState available. Semantically
  * equivalent — prevState provides the pre-execution view.
  *
  * Implementations:
  *   - StructLogTracer: opcode-by-opcode trace (go-ethereum structLog format)
  *   - CallTracer: nested call tree (go-ethereum native callTracer)
  *   - PrestateTracer: pre-transaction state snapshot (go-ethereum native prestateTracer)
  *   - VmTracer: Parity/OpenEthereum vmTrace format
  *
  * All methods have default no-op implementations so each tracer only overrides what it needs.
  */
trait ExecutionTracer {

  /** Called after each opcode execution in the VM exec loop.
    * Corresponds to Besu's tracePostExecution. prevState gives the pre-execution view.
    */
  def onStep[W <: WorldStateProxy[W, S], S <: Storage[S]](
      opCode: OpCode,
      prevState: ProgramState[W, S],
      nextState: ProgramState[W, S]
  ): Unit = ()

  /** Called once for the top-level transaction before execution begins.
    * Corresponds to Besu's traceStartTransaction.
    */
  def onTxStart(from: Address, to: Option[Address], gas: BigInt, value: BigInt, input: ByteString): Unit = ()

  /** Called once when the top-level transaction returns.
    * Corresponds to Besu's traceEndTransaction.
    */
  def onTxEnd(gasUsed: BigInt, output: ByteString, error: Option[String]): Unit = ()

  /** Called when entering an internal CALL/CALLCODE/DELEGATECALL/STATICCALL/CREATE/CREATE2.
    * Only fires for internal sub-calls (callDepth > 0), not the top-level transaction.
    * Corresponds to Besu's traceContextEnter.
    *
    * @param opCode the opcode name: "CALL", "STATICCALL", "DELEGATECALL", "CALLCODE", "CREATE", "CREATE2"
    * @param from   calling address
    * @param to     called address (for CREATE/CREATE2, the newly computed address)
    * @param gas    gas allocated to the sub-call
    * @param value  ETH value transferred
    * @param input  call data or init code
    */
  def onCallEnter(
      opCode: String,
      from: Address,
      to: Address,
      gas: BigInt,
      value: BigInt,
      input: ByteString
  ): Unit = ()

  /** Called when an internal CALL/CREATE returns.
    * Corresponds to Besu's traceContextExit.
    */
  def onCallExit(gasUsed: BigInt, output: ByteString, error: Option[String]): Unit = ()

  /** Returns the tracer-specific result as a JSON value for the RPC response. */
  def getResult: org.json4s.JValue
}
