package com.chipprbots.ethereum.ledger

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.domain.Receipt

/** The outcome of executing a block's transaction list.
  *
  * **`gasUsed` is derived, not supplied.** EIP-8037 (Amsterdam) meters execution gas and state gas as two independent
  * dimensions, and the header's `gasUsed` reports the *bottleneck* — the larger of the two — while receipts keep
  * summing the per-transaction total. On an Amsterdam block those two numbers legitimately disagree: block 41 of the
  * reference chain carries header `gasUsed` 183,600 and receipt `cumulativeGasUsed` 326,947 simultaneously.
  *
  * Making `gasUsed` a `val` in the body rather than a constructor parameter is deliberate: it removes any way for a
  * call site to supply a value inconsistent with the two counters it is supposed to summarise. An implementation that
  * carries one scalar for both fails one of them and passes every weaker test.
  *
  * Pre-Amsterdam — and on every ETC path — `stateGasUsed` stays 0 and `executionGasUsed` accumulates exactly what the
  * single old counter did, so `gasUsed` keeps its previous meaning to the gas unit.
  */
case class BlockResult(
    worldState: InMemoryWorldStateProxy,
    /** EIP-8037 `block_execution_gas_used`: the sum of `max(tx_gas_used_before_refund - tx_state_gas, floor)`. */
    executionGasUsed: BigInt = 0,
    /** EIP-8037 `block_state_gas_used`: the sum of each transaction's `evm_state_gas_used`. */
    stateGasUsed: BigInt = 0,
    receipts: Seq[Receipt] = Nil,
    // EIP-7685 typed requests (type_byte || data), in canonical order:
    // deposits (0x00) → withdrawals (0x01) → consolidations (0x02). Present only
    // when the block is post-Prague AND the request list is non-empty.
    executionRequests: Seq[ByteString] = Nil
):

  /** The header's `gasUsed` field: `max(block_execution_gas_used, block_state_gas_used)` (EIP-8037). */
  val gasUsed: BigInt = executionGasUsed.max(stateGasUsed)
