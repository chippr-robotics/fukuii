package com.chipprbots.ethereum.ledger

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.domain.TxLogEntry
import com.chipprbots.ethereum.vm.ProgramError

/** @param gasUsed
  *   EIP-8037 `tx_gas_used` — post-refund, post-floor. This is what the sender pays and what the receipt's
  *   `cumulativeGasUsed` accumulates.
  * @param executionGasUsed
  *   EIP-8037 `tx_execution_gas` — this transaction's contribution to the block's EXECUTION dimension. Under EIP-7778
  *   it is computed before refunds: `max(tx_gas_used_before_refund - tx_state_gas, calldata_floor_gas_cost)`. Equal to
  *   `gasUsed` on every pre-Amsterdam path.
  * @param stateGasUsed
  *   EIP-8037 `tx_state_gas` — this transaction's contribution to the block's STATE dimension. Zero on every
  *   pre-Amsterdam path.
  */
case class TxResult(
    worldState: InMemoryWorldStateProxy,
    gasUsed: BigInt,
    logs: Seq[TxLogEntry],
    vmReturnData: ByteString,
    vmError: Option[ProgramError],
    executionGasUsed: BigInt = 0,
    stateGasUsed: BigInt = 0
)
