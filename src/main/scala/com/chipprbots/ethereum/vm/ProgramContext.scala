package com.chipprbots.ethereum.vm

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.domain.*

object ProgramContext:

  /** The ordinary entry point. EIP-2780's authorization charges are zero for every transaction that is not a Type-4
    * under Amsterdam, which is every transaction on every ETC path.
    */
  def apply[W <: WorldStateProxy[W, S], S <: Storage[S]](
      stx: SignedTransaction,
      blockHeader: BlockHeader,
      senderAddress: Address,
      world: W,
      evmConfig: EvmConfig
  ): ProgramContext[W, S] =
    apply(stx, blockHeader, senderAddress, world, evmConfig, BigInt(0), BigInt(0))

  def apply[W <: WorldStateProxy[W, S], S <: Storage[S]](
      stx: SignedTransaction,
      blockHeader: BlockHeader,
      senderAddress: Address,
      world: W,
      evmConfig: EvmConfig,
      // EIP-2780 authorization-processing charges, already computed by the caller against the
      // transaction-start world. Zero for every transaction that is not a Type-4 under Amsterdam.
      authExecutionGas: BigInt,
      authStateGas: BigInt
  ): ProgramContext[W, S] =
    import stx.tx
    val accessList = Transaction.accessList(tx)
    val authListSize = tx match
      case sct: SetCodeTransaction => sct.authorizationList.size
      case _                       => 0
    val intrinsicGas = evmConfig.calcTransactionIntrinsicGas(
      tx.payload,
      tx.isContractInit,
      accessList,
      authListSize,
      tx.receivingAddress,
      UInt256(tx.value),
      senderAddress
    )
    val evmGas = tx.gasLimit.value - intrinsicGas

    // EIP-8037 reservoir split. Pre-Amsterdam this collapses to `gasLimit = evmGas, reservoir = 0`,
    // which is exactly the previous behaviour.
    //
    // R-1: every transaction in the reference fixture has `tx.gas <= 1,628,065`, far below
    // TX_MAX_GAS_LIMIT = 2^24, so the reservoir is 0 in all 612 of them. Fixture-green says nothing
    // about reservoir seeding — that needs execution-spec-tests vectors.
    val (gasLimit, stateGasReservoir) =
      if !evmConfig.amsterdamEnabled then (evmGas, BigInt(0))
      else
        // `.max(0)` is defensive only: EIP-8037 makes a transaction whose intrinsic gas exceeds
        // TX_MAX_GAS_LIMIT invalid, so a negative budget should never reach here.
        val executionGasBudget = (AmsterdamGas.TxMaxGasLimit - intrinsicGas).max(0)
        val gl = executionGasBudget.min(evmGas)
        (gl, evmGas - gl)

    // EIP-2780 pre-execution phase: the state-dependent charges, applied after the transaction is already
    // deemed valid but before the first EVM frame is entered. Running out of gas here does NOT invalidate
    // the transaction — it is included, charged for everything consumed, and its state changes reverted.
    val preExecutionStateCharge: BigInt =
      if !evmConfig.amsterdamEnabled then BigInt(0)
      else
        tx.receivingAddress match
          // Contract creation: charge only if the deployment address does not already exist. The address
          // is derived exactly as `VM.create` derives it, from the same world, so the two agree by
          // construction rather than by coincidence.
          case None =>
            if world.isAccountDead(world.createAddress(senderAddress)) then AmsterdamGas.GasNewAccount else BigInt(0)
          // Ordinary call: a value transfer that brings a non-existent recipient into being. Never applies
          // to a self-transfer, whose recipient is the already-existing sender.
          case Some(recipient) =>
            if tx.value > 0 && recipient != senderAddress && world.isAccountDead(recipient) then
              AmsterdamGas.GasNewAccount
            else BigInt(0)

    // ── The pre-execution phase, in EIP-2780's order ──────────────────────
    //
    // 1. authorizations are processed and charged;
    // 2. the frame COMMITS: the baseline moves to the post-authorization reservoir, so a rollback does
    //    not credit back gas that paid for delegations the rollback does not undo (EIP-8037);
    // 3. the recipient / deployment account-creation charge is applied, INSIDE the refillable window.
    //
    // The ordering is the whole of `state_gas_committed`'s purpose. Applied the other way round, a
    // reverting Type-4 transaction would be refunded for delegations that survive the revert.
    val authFromReservoir = authStateGas.min(stateGasReservoir)
    val authFromGasLeft = authStateGas - authFromReservoir
    val reservoirAfterAuth = stateGasReservoir - authFromReservoir
    val gasLeftAfterAuth = gasLimit - authExecutionGas - authFromGasLeft

    val chargeFromReservoir = preExecutionStateCharge.min(reservoirAfterAuth)
    val chargeFromGasLeft = preExecutionStateCharge - chargeFromReservoir

    val blobHashes = tx match
      case blob: BlobTransaction => blob.blobVersionedHashes.map(_.value)
      case _                     => Seq.empty

    ProgramContext(
      callerAddr = senderAddress,
      originAddr = senderAddress,
      recipientAddr = tx.receivingAddress,
      // EIP-1559: the GASPRICE opcode and the sub-call gasPrice plumbing must see
      // the *effective* gas price (min(maxFeePerGas, baseFee + maxPriorityFeePerGas)),
      // not the raw `tx.gasPrice` which for Type-2/3/4 returns maxFeePerGas. Surfaces
      // on BlockchainTests/ValidBlocks/bcEIP1559/burnVerify_Cancun: the contract
      // stores GASPRICE into a slot whose pre-existing value == baseFee, so the SSTORE
      // should be a no-op (100 gas) but was being charged as a fresh-slot reset (2900
      // gas) — the observed +2800 per-tx gas delta.
      gasPrice = UInt256(Transaction.effectiveGasPrice(tx, blockHeader.baseFee)),
      startGas = gasLeftAfterAuth - chargeFromGasLeft,
      stateGasReservoir = reservoirAfterAuth - chargeFromReservoir,
      evmStateGasUsed = authStateGas + preExecutionStateCharge,
      // The committed authorization draw is deliberately NOT part of `stateGasFromGasLeft`: the commit in
      // step 2 above moved it out, which is exactly what stops a rollback crediting it back.
      stateGasFromGasLeft = chargeFromGasLeft,
      stateGasCommitted = authFromGasLeft,
      // EIP-8037: the top-level baseline is taken BEFORE the account-creation charge, so a frame that
      // reverts or halts refills it. Taking it after would leave the sender paying 183,600 of state gas
      // for an account the rollback removed.
      stateGasBaselineOverride = Some(reservoirAfterAuth),
      // Distinct from the baseline on purpose. The baseline is post-authorization, because an in-frame
      // rollback must not refund delegations it does not undo. A failure BEFORE the frame is entered
      // rolls the authorizations back too, so that path restores the start-of-transaction value instead.
      initialStateGasReservoir = stateGasReservoir,
      // If the pre-execution charge cannot be met, the transaction is still valid and still included; it
      // simply consumes everything and reverts. Signalled rather than silently under-charged.
      preExecutionOutOfGas =
        evmConfig.amsterdamEnabled && (gasLeftAfterAuth < 0 || chargeFromGasLeft > gasLeftAfterAuth),
      inputData = tx.payload,
      value = UInt256(tx.value),
      endowment = UInt256(tx.value),
      doTransfer = true,
      blockHeader = blockHeader,
      callDepth = 0,
      world = world,
      initialAddressesToDelete = Set(),
      evmConfig = evmConfig,
      originalWorld = world,
      warmAddresses = accessList.map(_.address).toSet,
      warmStorage = accessList.flatMap(i => i.storageKeys.map(sk => (i.address, sk))).toSet,
      blobVersionedHashes = blobHashes
    )

/** Input parameters to a program executed on the EVM. Apart from the code itself it should have all (interfaces to) the
  * data accessible from the EVM.
  *
  * Execution constants, see section 9.3 in Yellow Paper for more detail.
  *
  * @param callerAddr
  *   I_s: address of the account which caused the code to be executing
  * @param originAddr
  *   I_o: sender address of the transaction that originated this execution
  * @param gasPrice
  *   I_p
  * @param inputData
  *   I_d
  * @param value
  *   I_v
  * @param blockHeader
  *   I_H
  * @param callDepth
  *   I_e
  *
  * Additional parameters:
  * @param recipientAddr
  *   recipient of the call, empty if contract creation
  * @param endowment
  *   value that appears to be transferred between accounts, if CALLCODE - equal to callValue (but is not really
  *   transferred) if DELEGATECALL - always zero if STATICCALL - always zero otherwise - equal to value
  * @param doTransfer
  *   false for CALLCODE/DELEGATECALL/STATICCALL, true otherwise
  * @param startGas
  *   initial gas for the execution
  * @param world
  *   provides interactions with world state
  * @param initialAddressesToDelete
  *   contains initial set of addresses to delete (from lower depth calls)
  * @param evmConfig
  *   evm config
  * @param staticCtx
  *   a flag to indicate static context (EIP-214)
  * @param originalWorld
  *   state of the world at the beginning of the current transaction, read-only, needed for
  *   https://eips.ethereum.org/EIPS/eip-1283
  * @param createdAddresses
  *   addresses created by CREATE/CREATE2 so far in this transaction (EIP-6780). NOTE: this cannot be derived from
  *   `originalWorld` because `VM.create` deliberately replaces the create frame's `originalWorld` with
  *   `originalWorld.initialiseAccount(contractAddr)` for EIP-1283/2200 original-value lookups, which makes the new
  *   address *exist* there.
  */
case class ProgramContext[W <: WorldStateProxy[W, S], S <: Storage[S]](
    callerAddr: Address,
    originAddr: Address,
    recipientAddr: Option[Address],
    gasPrice: UInt256,
    startGas: BigInt,
    inputData: ByteString,
    value: UInt256,
    endowment: UInt256,
    doTransfer: Boolean,
    blockHeader: BlockHeader,
    callDepth: Int,
    world: W,
    initialAddressesToDelete: Set[Address],
    evmConfig: EvmConfig,
    staticCtx: Boolean = false,
    originalWorld: W,
    warmAddresses: Set[Address],
    warmStorage: Set[(Address, StorageKey)],
    transientStorage: Map[(Address, StorageKey), BigInt] = Map.empty,
    createdAddresses: Set[Address] = Set.empty,
    precompileRelocations: Map[Address, Address] = Map.empty,
    blobVersionedHashes: Seq[ByteString] = Seq.empty,
    traceTransfers: Boolean = false,
    // ── EIP-8037 state-gas accounting (Amsterdam) ────────────────────────────
    /** The reservoir handed to this frame. Passed to a child IN FULL: the 63/64 rule applies to `startGas` only. */
    stateGasReservoir: BigInt = 0,
    /** Transaction-level state gas charged so far. Threads down into the frame and back out of its result. */
    evmStateGasUsed: BigInt = 0,
    /** State gas already drawn from this frame's `startGas`. Non-zero only for the top-level frame, which inherits
      * EIP-2780's pre-execution account-creation charge.
      */
    stateGasFromGasLeft: BigInt = 0,
    /** Overrides the frame-entry baseline. `None` — every sub-frame — means "the reservoir at entry". The top-level
      * frame sets it explicitly so the pre-execution account-creation charge is inside the refillable window.
      */
    stateGasBaselineOverride: Option[BigInt] = None,
    /** The reservoir as it stood at the START of the transaction, before EIP-2780's pre-execution phase charged
      * anything. Used only when that phase itself fails: EIP-2780 rolls the whole phase back, authorizations included,
      * so the sender gets the entire reservoir returned.
      */
    initialStateGasReservoir: BigInt = 0,
    /** EIP-8037: the part of `stateGasFromGasLeft` a rollback must NOT credit back, because it paid for EIP-7702
      * delegations that the top-level frame's rollback does not undo. Only the top-level frame is ever non-zero.
      */
    stateGasCommitted: BigInt = 0,
    /** EIP-2780: the pre-execution phase itself ran out of gas. The transaction stays valid and included; execution is
      * skipped and the pre-execution state changes are reverted.
      */
    preExecutionOutOfGas: Boolean = false,
    // Optional opcode-level tracer (debug_trace*). None is the fast default; Some
    // enables per-step capture in the VM exec loop.
    tracer: Option[ExecutionTracer] = None
)
