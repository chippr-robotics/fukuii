package com.chipprbots.ethereum.vm

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.StorageKey
import com.chipprbots.ethereum.domain.TxLogEntry

/** Represenation of the result of execution of a contract
  *
  * @param returnData
  *   bytes returned by the executed contract (set by [[RETURN]] opcode)
  * @param gasRemaining
  *   amount of gas remaining after execution
  * @param world
  *   represents changes to the world state
  * @param addressesToDelete
  *   list of addresses of accounts scheduled to be deleted
  * @param internalTxs
  *   list of internal transactions (for debugging/tracing) if enabled in config
  * @param error
  *   defined when the program terminated abnormally
  * @param createdAddresses
  *   addresses of accounts created by CREATE/CREATE2 during this transaction (EIP-6780). Propagated upwards only from
  *   frames that completed without error, so a reverted CREATE does not leak its address.
  */
case class ProgramResult[W <: WorldStateProxy[W, S], S <: Storage[S]](
    returnData: ByteString,
    gasRemaining: BigInt,
    world: W,
    addressesToDelete: Set[Address],
    logs: Seq[TxLogEntry],
    internalTxs: Seq[InternalTransaction],
    gasRefund: BigInt,
    error: Option[ProgramError],
    accessedAddresses: Set[Address],
    accessedStorageKeys: Set[(Address, StorageKey)],
    transientStorage: Map[(Address, StorageKey), BigInt] = Map.empty,
    createdAddresses: Set[Address] = Set.empty,
    // ── EIP-8037 state-gas accounting (Amsterdam) ────────────────────────────
    // Zero on every pre-Amsterdam and every ETC path.
    /** The reservoir as this frame leaves it. The parent adopts it wholesale — the child was given it in full. */
    stateGasReservoir: BigInt = 0,
    /** Transaction-level state gas charged, net of refills. The block's state-gas dimension is built from this. */
    evmStateGasUsed: BigInt = 0,
    /** State gas this frame funded out of `gas` and has not credited back. Always 0 for a frame that reverted or halted
      * exceptionally — see `ProgramState.toResult`.
      */
    stateGasFromGasLeft: BigInt = 0,
    /** The frame's baseline, carried out so a *post-hoc* failure — a create whose code deposit runs out of gas after
      * the frame itself completed normally — can still roll the frame's state gas back. Without it that path would
      * leave the initcode's state charges consumed for state that was never deposited.
      */
    stateGasBaseline: BigInt = 0,
    /** Pre-Homestead (Frontier, `exceptionalFailedCodeDeposit = false`) CREATE only: the init code ran to completion
      * and produced runtime bytes, but the frame could not afford `200 * len(code)` to deposit them, so no code was
      * stored. Frontier treats this as a SUCCESS — gas is kept, state is kept, the caller receives the new address —
      * and `error` is therefore `None`, which is what every consensus consumer keys on.
      *
      * This flag exists solely so gas ESTIMATION can tell the two kinds of success apart: `eth_estimateGas` / GraphQL
      * `estimateGas` must not converge on "enough gas to RUN the init code" and silently omit the code-deposit cost. It
      * is deliberately NOT a [[ProgramError]]: nothing on the block-execution path reads it, so it cannot change a
      * state root, a receipt, or a block hash. See `StxLedger.binarySearchGasEstimation`.
      *
      * Matches go-ethereum, where `ErrCodeStoreOutOfGas` is set inside `create()` but discarded by `opCreate` before it
      * can reach the caller's stack value or the top-level `vmerr`. It is consequently NOT propagated out of a nested
      * CREATE frame either — only a top-level contract-creation result ever carries it.
      */
    codeDepositShortfall: Boolean = false
):

  /** Roll this frame's state gas back to its baseline. See `ProgramState.restoreStateGasToBaseline`; this is the same
    * operation applied to an already-materialised result.
    */
  def withStateGasRestoredToBaseline: ProgramResult[W, S] =
    val netStateGas = stateGasBaseline - stateGasReservoir + stateGasFromGasLeft
    copy(
      evmStateGasUsed = evmStateGasUsed - netStateGas,
      stateGasFromGasLeft = 0,
      stateGasReservoir = stateGasBaseline
    )
