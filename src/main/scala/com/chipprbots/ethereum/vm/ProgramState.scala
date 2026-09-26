package com.chipprbots.ethereum.vm

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.StorageKey
import com.chipprbots.ethereum.domain.TxLogEntry
import com.chipprbots.ethereum.domain.UInt256

object ProgramState:
  def apply[W <: WorldStateProxy[W, S], S <: Storage[S]](
      vm: VM[W, S],
      context: ProgramContext[W, S],
      env: ExecEnv
  ): ProgramState[W, S] =
    // EIP-3651: Mark COINBASE address as warm at transaction start
    val coinbaseAddress: Set[Address] =
      if context.evmConfig.eip3651Enabled then Set(Address(context.blockHeader.beneficiary))
      else Set.empty[Address]

    ProgramState(
      vm = vm,
      env = env,
      gas = env.startGas,
      world = context.world,
      staticCtx = context.staticCtx,
      addressesToDelete = context.initialAddressesToDelete,
      originalWorld = context.originalWorld,
      accessedAddresses = PrecompiledContracts.getContracts(context).keySet ++ Set(
        context.originAddr,
        context.recipientAddr.getOrElse(context.callerAddr)
      ) ++ context.warmAddresses ++ coinbaseAddress,
      accessedStorageKeys = context.warmStorage,
      transientStorage = context.transientStorage,
      createdAddresses = context.createdAddresses,
      // EIP-8037: the reservoir is passed to a child frame IN FULL — the 63/64 rule applies to
      // gas_left only, and the parent keeps none of it while the child runs. `evmStateGasUsed` is a
      // transaction-level total, so it threads down and back up unchanged.
      stateGasReservoir = context.stateGasReservoir,
      evmStateGasUsed = context.evmStateGasUsed,
      stateGasFromGasLeft = context.stateGasFromGasLeft,
      stateGasCommitted = context.stateGasCommitted,
      // The snapshot a rollback restores to. For every sub-frame that is the reservoir at entry; only the
      // top-level frame overrides it, so EIP-2780's pre-execution account-creation charge stays refillable.
      stateGasBaseline = context.stateGasBaselineOverride.getOrElse(context.stateGasReservoir)
    )

/** Intermediate state updated with execution of each opcode in the program
  *
  * @param vm
  *   the VM
  * @param env
  *   program constants
  * @param gas
  *   current gas for the execution
  * @param world
  *   world state
  * @param addressesToDelete
  *   list of addresses of accounts scheduled to be deleted
  * @param stack
  *   current stack
  * @param memory
  *   current memory
  * @param pc
  *   program counter - an index of the opcode in the program to be executed
  * @param returnData
  *   data to be returned from the program execution
  * @param gasRefund
  *   the amount of gas to be refunded after execution (not sure if a separate field is required)
  * @param internalTxs
  *   list of internal transactions (for debugging/tracing)
  * @param halted
  *   a flag to indicate program termination
  * @param staticCtx
  *   a flag to indicate static context (EIP-214)
  * @param error
  *   indicates whether the program terminated abnormally
  * @param originalWorld
  *   state of the world at the beginning og the current transaction, read-only,
  * @param accessedAddresses
  *   set of addresses which have already been accessed in this transaction (EIP-2929)
  * @param accessedStorageKeys
  *   set of storage slots which have already been accessed in this transaction (EIP-2929) needed for
  *   https://eips.ethereum.org/EIPS/eip-1283
  */
case class ProgramState[W <: WorldStateProxy[W, S], S <: Storage[S]](
    vm: VM[W, S],
    env: ExecEnv,
    gas: BigInt,
    world: W,
    addressesToDelete: Set[Address],
    stack: Stack = Stack.empty(),
    memory: Memory = Memory.empty,
    pc: Int = 0,
    returnData: ByteString = ByteString.empty,
    gasRefund: BigInt = 0,
    internalTxs: Vector[InternalTransaction] = Vector.empty,
    logs: Vector[TxLogEntry] = Vector.empty,
    halted: Boolean = false,
    staticCtx: Boolean = false,
    error: Option[ProgramError] = None,
    originalWorld: W,
    accessedAddresses: Set[Address],
    accessedStorageKeys: Set[(Address, StorageKey)],
    transientStorage: Map[(Address, StorageKey), BigInt] = Map.empty,
    opcodeGasCost: BigInt = 0,
    createdAddresses: Set[Address] = Set.empty,
    // ── EIP-8037 state-gas accounting (Amsterdam) ────────────────────────────
    // All five stay 0 on every pre-Amsterdam and every ETC path: nothing charges state gas unless
    // `config.amsterdamEnabled`, and the reservoir is only ever seeded from ProgramContext.
    /** Remaining separate state-gas allowance. Gas above EIP-7825's TX_MAX_GAS_LIMIT lands here. */
    stateGasReservoir: BigInt = 0,
    /** Transaction-level total of state gas charged so far, net of refills. Threaded through every frame. */
    evmStateGasUsed: BigInt = 0,
    /** Frame-local: state gas this frame had to draw from `gas` once the reservoir emptied, not yet credited back. */
    stateGasFromGasLeft: BigInt = 0,
    /** Frame-local: the reservoir value a rollback restores to. Frame entry, except for the top-level frame after
      * EIP-7702 authorization processing commits.
      */
    stateGasBaseline: BigInt = 0,
    /** Frame-local: the part of `stateGasFromGasLeft` a rollback must NOT credit back, because the state it paid for is
      * not rolled back (EIP-7702 authorizations applied in the pre-execution phase). Only the top-level frame ever has
      * a non-zero value.
      */
    stateGasCommitted: BigInt = 0
):

  def config: EvmConfig = env.evmConfig

  def ownAddress: Address = env.ownerAddr

  def ownBalance: UInt256 = world.getBalance(ownAddress)

  def storage: S = world.getStorage(ownAddress)

  def gasUsed: BigInt = env.startGas - gas

  def withWorld(updated: W): ProgramState[W, S] =
    copy(world = updated)

  def withStorage(updated: S): ProgramState[W, S] =
    withWorld(world.saveStorage(ownAddress, updated))

  def program: Program = env.program

  def inputData: ByteString = env.inputData

  def spendGas(amount: BigInt): ProgramState[W, S] =
    copy(gas = gas - amount)

  // ── EIP-8037 state-gas channel ──────────────────────────────────────────────

  /** How much of a state-gas charge of `amount` would have to come out of `gas` rather than the reservoir.
    *
    * Callers use this to decide affordability BEFORE charging, because a state charge that cannot be met is an
    * exceptional halt, not a partial charge.
    */
  def stateGasShortfall(amount: BigInt): BigInt = (amount - stateGasReservoir).max(0)

  /** Charge `amount` of state gas: reservoir first, then `gas`.
    *
    * The caller must already have established that `stateGasShortfall(amount) <= gas`; this method does not check,
    * because the halt it would otherwise have to produce differs per opcode.
    */
  def chargeStateGas(amount: BigInt): ProgramState[W, S] =
    if amount <= 0 then this
    else
      val fromReservoir = amount.min(stateGasReservoir)
      val fromGasLeft = amount - fromReservoir
      copy(
        stateGasReservoir = stateGasReservoir - fromReservoir,
        gas = gas - fromGasLeft,
        stateGasFromGasLeft = stateGasFromGasLeft + fromGasLeft,
        evmStateGasUsed = evmStateGasUsed + amount
      )

  /** Refill `amount` of state gas in LIFO order: charges drew from the reservoir first and `gas` last, so refills
    * credit `gas` first and the reservoir with any remainder (EIP-8037).
    *
    * Used when a state creation is undone — an SSTORE that resets a slot it set earlier in the same transaction, or a
    * CALL/CREATE whose account-creation charge the child's rollback reversed.
    */
  def refillStateGas(amount: BigInt): ProgramState[W, S] =
    if amount <= 0 then this
    else
      val toGasLeft = amount.min(stateGasFromGasLeft)
      copy(
        gas = gas + toGasLeft,
        stateGasFromGasLeft = stateGasFromGasLeft - toGasLeft,
        stateGasReservoir = stateGasReservoir + (amount - toGasLeft),
        evmStateGasUsed = evmStateGasUsed - amount
      )

  /** Restore this frame's state gas to its baseline, per EIP-8037 "Gas accounting for halts and reverts".
    *
    * This is NOT a refill: a rollback undoes the frame's state changes wholesale, so the net is computed against the
    * baseline rather than per charge. `netStateGas` is negative for a frame that refilled more than it charged, and
    * `evmStateGasUsed` correctly *increases* in that case — the rollback also restores the state whose removal the
    * refill credited.
    *
    * The `stateGasCommitted` portion is deliberately not credited back to `gas`: it paid for EIP-7702 delegations that
    * the top-level frame's rollback does not undo.
    *
    * This is the step that makes the measured `tx-emit-*` case come out at `max(100,000, 0)` rather than `max(100,000,
    * something)`. It is load-bearing, not bookkeeping.
    */
  def restoreStateGasToBaseline: ProgramState[W, S] =
    val netStateGas = stateGasBaseline - stateGasReservoir + stateGasFromGasLeft
    copy(
      evmStateGasUsed = evmStateGasUsed - netStateGas,
      gas = gas + stateGasFromGasLeft,
      stateGasFromGasLeft = 0,
      stateGasReservoir = stateGasBaseline
    )

  /** Absorb a SUCCESSFUL child frame's state-gas counters, then move state gas back to `gas` up to the amount this
    * frame still has outstanding (EIP-8037's merge step).
    *
    * The move matters because a refill can happen in a different frame from the matching charge — `original value` in
    * the SSTORE table is the value at transaction start, not frame entry, so a frame may clear a slot an earlier frame
    * allocated. Without the merge, that credit would be stranded in the reservoir instead of returning to the gas the
    * charge actually drew from.
    */
  def mergeSuccessfulChildStateGas(
      childReservoir: BigInt,
      childEvmStateGasUsed: BigInt,
      childStateGasFromGasLeft: BigInt
  ): ProgramState[W, S] =
    val merged = copy(
      stateGasReservoir = childReservoir,
      evmStateGasUsed = childEvmStateGasUsed,
      stateGasFromGasLeft = stateGasFromGasLeft + childStateGasFromGasLeft
    )
    val d = merged.stateGasReservoir.min(merged.stateGasFromGasLeft)
    merged.copy(
      gas = merged.gas + d,
      stateGasReservoir = merged.stateGasReservoir - d,
      stateGasFromGasLeft = merged.stateGasFromGasLeft - d
    )

  /** Absorb a FAILED child frame's state-gas counters. The child already restored itself to the baseline this frame
    * handed it, so there is nothing to merge and no gas to move — only the transaction-level total and the reservoir
    * (now back at the value passed in) come back.
    */
  def absorbFailedChildStateGas(childReservoir: BigInt, childEvmStateGasUsed: BigInt): ProgramState[W, S] =
    copy(stateGasReservoir = childReservoir, evmStateGasUsed = childEvmStateGasUsed)

  def refundGas(amount: BigInt): ProgramState[W, S] =
    copy(gasRefund = gasRefund + amount)

  def step(i: Int = 1): ProgramState[W, S] =
    copy(pc = pc + i)

  def goto(i: Int): ProgramState[W, S] =
    copy(pc = i)

  /** `withStack(stack).step(pcIncrement).spendGas(gasSpent)`, built as one copy instead of three: the whole transition
    * of an instruction that only moves the stack and the program counter (see `StackOnlyOp` in OpCode.scala).
    */
  def stepWithStack(stack: Stack, pcIncrement: Int, gasSpent: BigInt): ProgramState[W, S] =
    copy(stack = stack, pc = pc + pcIncrement, gas = gas - gasSpent)

  /** `withStack(stack).goto(dest).spendGas(gasSpent)`, built as one copy: a JUMP or JUMPI that is taken. */
  def jumpWithStack(stack: Stack, dest: Int, gasSpent: BigInt): ProgramState[W, S] =
    copy(stack = stack, pc = dest, gas = gas - gasSpent)

  def withStack(stack: Stack): ProgramState[W, S] =
    copy(stack = stack)

  def withMemory(memory: Memory): ProgramState[W, S] =
    copy(memory = memory)

  def withError(error: ProgramError): ProgramState[W, S] =
    copy(error = Some(error), returnData = ByteString.empty, halted = true)

  def withReturnData(data: ByteString): ProgramState[W, S] =
    copy(returnData = data)

  def withAddressToDelete(addr: Address): ProgramState[W, S] =
    copy(addressesToDelete = addressesToDelete + addr)

  def withAddressesToDelete(addresses: Set[Address]): ProgramState[W, S] =
    copy(addressesToDelete = addressesToDelete ++ addresses)

  /** EIP-6780: record addresses created by CREATE/CREATE2 in this transaction. */
  def withCreatedAddresses(addresses: Set[Address]): ProgramState[W, S] =
    copy(createdAddresses = createdAddresses ++ addresses)

  def withLog(log: TxLogEntry): ProgramState[W, S] =
    copy(logs = logs :+ log)

  def withLogs(log: Seq[TxLogEntry]): ProgramState[W, S] =
    copy(logs = logs ++ log)

  def withInternalTxs(txs: Seq[InternalTransaction]): ProgramState[W, S] =
    if config.traceInternalTransactions then copy(internalTxs = internalTxs ++ txs) else this

  def halt: ProgramState[W, S] =
    copy(halted = true)

  def revert(data: ByteString): ProgramState[W, S] =
    copy(error = Some(RevertOccurs), returnData = data, halted = true)

  def addAccessedAddress(addr: Address): ProgramState[W, S] =
    copy(accessedAddresses = accessedAddresses + addr)

  def addAccessedStorageKey(addr: Address, key: StorageKey): ProgramState[W, S] =
    copy(accessedStorageKeys = accessedStorageKeys + ((addr, key)))

  def addAccessedAddresses(addresses: Set[Address]): ProgramState[W, S] =
    copy(accessedAddresses = accessedAddresses ++ addresses)

  def addAccessedStorageKeys(storageKeys: Set[(Address, StorageKey)]): ProgramState[W, S] =
    copy(accessedStorageKeys = accessedStorageKeys ++ storageKeys)

  def toResult: ProgramResult[W, S] =
    // EIP-8037: a frame's state changes are rolled back on BOTH reverts and exceptional halts, and in both
    // cases its state gas is restored to the baseline rather than refilled. Doing it here — at the single
    // point every frame becomes a result — is what guarantees no exit path is missed.
    val settled = if error.isDefined then restoreStateGasToBaseline else this
    ProgramResult[W, S](
      settled.returnData,
      if settled.error.exists(_.useWholeGas) then 0 else settled.gas,
      settled.world,
      settled.addressesToDelete,
      settled.logs,
      settled.internalTxs,
      settled.gasRefund,
      settled.error,
      settled.accessedAddresses,
      settled.accessedStorageKeys,
      settled.transientStorage,
      settled.createdAddresses,
      settled.stateGasReservoir,
      settled.evmStateGasUsed,
      // A frame that reverted or halted returns NO state gas to its parent: the restore above already
      // zeroed this, which is precisely what stops the parent merging gas for a slot the rollback restored.
      settled.stateGasFromGasLeft,
      settled.stateGasBaseline
    )
