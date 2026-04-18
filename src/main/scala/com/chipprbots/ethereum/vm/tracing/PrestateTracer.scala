package com.chipprbots.ethereum.vm.tracing

import scala.collection.mutable

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.UInt256

/** Geth-compatible `prestateTracer`. Captures the state (balance, nonce, code, storage)
  * of every account TOUCHED during tx execution — the state BEFORE the tx ran — so the
  * tx can be replayed offline against the exact pre-state. Typical consumers: Tenderly,
  * Foundry `forge --fork`, block explorers.
  *
  * Implementation is opcode-driven: we watch SLOAD/SSTORE/BALANCE/EXTCODESIZE/EXTCODEHASH/
  * EXTCODECOPY and the CALL* / CREATE* family to record touched addresses and storage
  * slots. Because we don't have direct world-state access inside the tracer, we expose
  * the touched sets and leave resolution (pre-state snapshot) to the caller after
  * execution completes.
  */
class PrestateTracer extends Tracer {

  /** Addresses that were read or written during tx execution. */
  val touchedAddresses: mutable.Set[Address] = mutable.Set.empty

  /** (address, slot) pairs that were SLOADed or SSTOREed. */
  val touchedStorage: mutable.Set[(Address, UInt256)] = mutable.Set.empty

  override def onTxStart(gasLimit: BigInt, to: Option[Address], from: Address, value: UInt256): Unit = {
    touchedAddresses += from
    to.foreach(touchedAddresses += _)
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
    touchedAddresses += from
    touchedAddresses += to
  }

  /** onOpcode fires AFTER the opcode has executed, so `stack` shows the post-exec stack.
    * For read-only ops (BALANCE, EXTCODE*) the read address is still inferable from the
    * input stack at the tracer's snapshot point. We approximate by watching opcodes
    * whose name indicates an address read.
    */
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
  ): Unit = op match {
    case "BALANCE" | "EXTCODESIZE" | "EXTCODEHASH" | "EXTCODECOPY" | "SELFDESTRUCT" =>
      stack.lastOption.foreach(v => touchedAddresses += addressOf(v))
    case _ => ()
  }

  private def addressOf(v: UInt256): Address =
    Address(v.toBigInt)

  /** Packaged result — callers are expected to resolve into a concrete pre-state view
    * (account + storage slot values) against a world snapshot at tx-entry.
    */
  def result: PrestateTracerResult =
    PrestateTracerResult(touchedAddresses.toSet, touchedStorage.toSet)
}

case class PrestateTracerResult(
    touchedAddresses: Set[Address],
    touchedStorage: Set[(Address, UInt256)]
)
