package com.chipprbots.ethereum.ledger

import com.chipprbots.ethereum.domain.Address

/** eth_simulateV1's per-call execution overrides, as `EthSimulateService` hands them to
  * [[BlockPreparator.executeTransactionForSimulation]]:
  *   - `precompileRelocations`: the `movePrecompileToAddress` state overrides, source address to new address;
  *   - `traceTransfers`: every value transfer also emits a synthetic Transfer log;
  *   - `blobBaseFeeOverride`: `blockOverrides.blobBaseFee`, the blob gas price the sender is charged;
  *   - `skipExecutionGasCap`: `validation` is off, so on an Amsterdam block the whole gas limit is execution gas, not
  *     capped at EIP-8037's TX_MAX_GAS_LIMIT with the rest moved to the state-gas reservoir (go-ethereum
  *     `Message.SkipExecutionGasCapCheck`; execution-apis eth_simulateV1 notes, "Gas after Amsterdam").
  *
  * None of them is a consensus rule. A node shares one [[BlockPreparator]] between block import, eth_simulateV1 and
  * `StxLedger` (eth_call, eth_estimateGas, the debug and trace calls), and these run concurrently. So the overrides are
  * an immutable value that travels down the simulate call's own stack, never state on the shared instance. Every other
  * path executes with [[SimulationOverrides.Empty]]. Block import's entry points,
  * [[BlockPreparator.executeTransactions]] and [[BlockPreparator.executeTransaction]], have no parameter that could
  * take any other value.
  */
final private[ledger] case class SimulationOverrides(
    precompileRelocations: Map[Address, Address],
    traceTransfers: Boolean,
    blobBaseFeeOverride: Option[BigInt],
    skipExecutionGasCap: Boolean = false
)

private[ledger] object SimulationOverrides:

  /** No override: execution exactly as consensus defines it. */
  val Empty: SimulationOverrides =
    SimulationOverrides(
      precompileRelocations = Map.empty,
      traceTransfers = false,
      blobBaseFeeOverride = None,
      skipExecutionGasCap = false
    )
