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
  * @param maxUsedGas
  *   go-ethereum's `ExecutionResult.MaxUsedGas` (core/state_transition.go `settleGas`): the gas this transaction had
  *   used at its peak, before any refund — `tx.gas - gas_left - state_gas_reservoir` — raised to the calldata floor
  *   from EIP-7623 on (ETH Prague, ETC Olympia). Never below `gasUsed`. Set by `BlockPreparator.executeTransaction` and
  *   `executeTransactionForSimulation`, and read only by eth_simulateV1, which reports it as `maxUsedGas`. Nothing that
  *   executes, charges, refunds, builds a receipt or computes a root reads it: block import and block building take
  *   `gasUsed`, `executionGasUsed` and `stateGasUsed` and ignore this. 0 on the bare-VM RPC paths
  *   (`StxLedger.simulateTransaction*`), which do no settlement and whose callers do not read it.
  */
case class TxResult(
    worldState: InMemoryWorldStateProxy,
    gasUsed: BigInt,
    logs: Seq[TxLogEntry],
    vmReturnData: ByteString,
    vmError: Option[ProgramError],
    executionGasUsed: BigInt = 0,
    stateGasUsed: BigInt = 0,
    maxUsedGas: BigInt = 0
)
