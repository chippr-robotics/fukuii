package com.chipprbots.ethereum.vm

import org.apache.pekko.util.ByteString

import scala.annotation.tailrec

import org.bouncycastle.util.encoders.Hex

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.SetCodeTransaction
import com.chipprbots.ethereum.domain.StorageKey
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.rlp
import com.chipprbots.ethereum.rlp.RLPList
import com.chipprbots.ethereum.rlp.RLPValue
import com.chipprbots.ethereum.rlp.UInt256RLPImplicits.*
import com.chipprbots.ethereum.utils.DebugTrace
import com.chipprbots.ethereum.utils.Logger

class VM[W <: WorldStateProxy[W, S], S <: Storage[S]](
    val tracer: Option[ExecutionTracer] = None
) extends Logger:

  type PC = ProgramContext[W, S]
  type PR = ProgramResult[W, S]
  type PS = ProgramState[W, S]

  /** Executes a top-level program (transaction)
    * @param context
    *   context to be executed
    * @return
    *   result of the execution
    */
  def run(context: ProgramContext[W, S]): ProgramResult[W, S] =
    {
      import context.*
      import org.bouncycastle.util.encoders.Hex
      log.trace(
        s"caller:  $callerAddr | recipient: $recipientAddr | gasPrice: $gasPrice | value: $value | inputData: ${Hex
            .toHexString(inputData.toArray)}"
      )
    }

    // EIP-2780: the pre-execution phase (account-creation state charge) could not be funded. The
    // transaction stays valid and included — it simply skips execution and reverts, exactly as an
    // out-of-gas halt inside a call frame would. Validity was decided by the intrinsic check alone.
    if context.preExecutionOutOfGas then
      ProgramResult[W, S](
        returnData = ByteString.empty,
        gasRemaining = 0,
        world = context.world,
        addressesToDelete = Set.empty,
        logs = Nil,
        internalTxs = Nil,
        gasRefund = 0,
        error = Some(OutOfGas),
        accessedAddresses = Set.empty,
        accessedStorageKeys = Set.empty,
        // EIP-2780: the pre-execution phase is rolled back in full, so the reservoir goes back to the
        // value it had at the start of the transaction and is returned to the sender at settlement.
        // `gas_left` is consumed, as for any exceptional halt.
        stateGasReservoir = context.initialStateGasReservoir,
        evmStateGasUsed = 0,
        stateGasBaseline = context.initialStateGasReservoir
      )
    else
      context.recipientAddr match
        case Some(recipientAddr) =>
          call(context, recipientAddr)

        case None =>
          create(context)._1

  /** Message call - Θ function in YP
    */
  private[vm] def call(context: PC, ownerAddr: Address): PR =
    val isSubCall = context.callDepth > 0
    if isSubCall then
      tracer.foreach(
        _.onCallEnter(
          callTypeName(context),
          context.callerAddr,
          context.recipientAddr.getOrElse(Address(0)),
          context.startGas,
          context.endowment,
          context.inputData
        )
      )
    var exitResult: PR = invalidCallResult(context, Set.empty, Set.empty)
    val result =
      try
        val r =
          if !isValidCall(context) then invalidCallResult(context, Set.empty, Set.empty)
          else
            val recipientAddr = context.recipientAddr.getOrElse(
              throw new IllegalArgumentException("Recipient address must be defined for message call")
            )

            def makeTransfer = context.world.transfer(context.callerAddr, recipientAddr, context.endowment)
            val world1 = if context.doTransfer then makeTransfer else context.world
            val context1: PC = context.copy(world = world1)

            // EIP-7708: a log is issued for any non-zero-value-transferring CALL to a DIFFERENT account,
            // at the time the transfer executes — i.e. here, at frame entry, so it precedes every log the
            // called code emits. DELEGATECALL and CALLCODE carry `doTransfer = false` and STATICCALL a zero
            // endowment, so none of them qualifies.
            //
            // Seeding it into the frame rather than appending it at the call site is deliberate: the log
            // then follows the frame's fate automatically, and a reverted frame drops it along with the
            // transfer it recorded.
            val transferLogs: Seq[com.chipprbots.ethereum.domain.TxLogEntry] =
              if context.evmConfig.amsterdamEnabled && context.doTransfer &&
                context.endowment > UInt256.Zero && context.callerAddr != recipientAddr
              then Seq(AmsterdamGas.transferLog(context.callerAddr, recipientAddr, context.endowment))
              else Nil

            if PrecompiledContracts.isDefinedAt(context1) then
              val precompileResult = PrecompiledContracts.run(context1)
              if transferLogs.isEmpty then precompileResult
              else precompileResult.copy(logs = transferLogs ++ precompileResult.logs)
            else
              val code = resolveCode(world1, recipientAddr)
              val env = ExecEnv(context1, code, ownerAddr)

              // EIP-7702: If code was resolved from a delegation, warm the delegation target
              val delegationTarget =
                try SetCodeTransaction.parseDelegation(world1.getCode(recipientAddr))
                catch case _: Exception => None
              val initialState: PS = ProgramState(this, context1, env).withLogs(transferLogs)
              val warmState = delegationTarget match
                case Some(target) => initialState.addAccessedAddress(target)
                case None         => initialState
              exec(warmState).toResult
        exitResult = r
        r
      finally
        if isSubCall then
          tracer.foreach(
            _.onCallExit(
              context.startGas - exitResult.gasRemaining,
              exitResult.returnData,
              exitResult.error.map(_.toString)
            )
          )
    result

  /** EIP-7702: Resolve delegation code one level deep. If the account has a delegation prefix (0xef0100), load the
    * target's code instead.
    */
  private def resolveCode(world: W, addr: Address): ByteString =
    val code = world.getCode(addr)
    SetCodeTransaction.parseDelegation(code) match
      case Some(target) => world.getCode(target)
      case None         => code

  /** Contract creation - Λ function in YP salt is used to create contract by CREATE2 opcode. See
    * https://github.com/ethereum/EIPs/blob/master/EIPS/eip-1014.md
    */
  private[vm] def create(
      context: PC,
      salt: Option[UInt256] = None
  ): (PR, Address) =
    val isSubCall = context.callDepth > 0
    val opName = if salt.isDefined then "CREATE2" else "CREATE"
    if isSubCall then
      tracer.foreach(
        _.onCallEnter(opName, context.callerAddr, Address(0), context.startGas, context.endowment, context.inputData)
      )
    var exitResult: PR = invalidCallResult(context, Set.empty, Set.empty)
    val (result, newAddress) =
      try
        val pair =
          if !isValidCall(context) || creatorNonceOverflowed(context) then
            (invalidCallResult(context, Set.empty, Set.empty), Address(0))
          else
            require(context.recipientAddr.isEmpty, "recipient address must be empty for contract creation")
            require(context.doTransfer, "contract creation will always transfer funds")

            // EIP-3860: Check initcode size limit — abort arm flows through onCallExit below.
            val maxInitCodeSize = context.evmConfig.maxInitCodeSize
            if context.evmConfig.eip3860Enabled && maxInitCodeSize.exists(max => context.inputData.size > max) then
              (
                invalidCallResult(context, Set.empty, Set.empty)
                  .copy(error = Some(InitCodeSizeLimit), gasRemaining = 0),
                Address(0)
              )
            else

              if DebugTrace.enabledForBlock(context.blockHeader.number.value) then
                val callerAccountNonce = context.world.getAccount(context.callerAddr).map(_.nonce)
                callerAccountNonce.foreach { n =>
                  val nonceForCreate = n - 1
                  // Address must be encoded as a single RLP string (20 bytes), not as a Seq[Byte].
                  val rlpPreimage =
                    rlp.encode(RLPList(RLPValue(context.callerAddr.bytes.toArray), nonceForCreate.toRLPEncodable))
                  val hash = kec256(rlpPreimage)
                  val derived = Address(hash)
                  log.info(
                    s"TRACE_CREATE_ADDR block=${context.blockHeader.number} caller=${context.callerAddr} " +
                      s"callerNonce=$n nonceForCreate=$nonceForCreate rlp=${Hex.toHexString(rlpPreimage.toArray)} " +
                      s"hash=${Hex.toHexString(hash.toArray)} derived=$derived"
                  )
                }

              val contractAddr = salt
                .map(s => context.world.create2Address(context.callerAddr, s, context.inputData))
                .getOrElse(context.world.createAddress(context.callerAddr))

              // EIP-684: revert a CREATE if the target address already has non-empty code/nonce.
              // EIP-7610 (Paris+): additionally revert if the address has non-empty storage.
              // Activation matches the EELS test marker `valid_from("Paris")` — we use
              // BlockHeader.isPoS (difficulty==0 && baseFee set) as the Paris / PoS signal.
              val conflict =
                if context.blockHeader.isPoS then context.world.nonEmptyCodeOrNonceOrStorageAccount(contractAddr)
                else context.world.nonEmptyCodeOrNonceAccount(contractAddr)

              /** Specification of https://eips.ethereum.org/EIPS/eip-1283 states, that `originalValue` should be taken
                * from world which is left after `a reversion happens on the current transaction`, so in current scope
                * `context.originalWorld`.
                *
                * But ets test expects that it should be taken from world after the new account initialisation, which
                * clears account storage. As it seems other implementations encountered similar problems with this
                * ambiguity: ambiguity: https://gist.github.com/holiman/0154f00d5fcec5f89e85894cbb46fcb2 - explanation
                * of geth and parity treating this situation differently. https://github.com/mana-ethereum/mana/pull/579
                * \- elixir eth client dealing with same problem.
                */
              val originInitialisedAccount = context.originalWorld.initialiseAccount(contractAddr)

              val world1: W =
                context.world
                  .initialiseAccount(contractAddr)
                  .transfer(context.callerAddr, contractAddr, context.endowment)

              val code = if conflict then ByteString(INVALID.code) else context.inputData

              val env = ExecEnv(context, code, contractAddr).copy(inputData = ByteString.empty)

              // EIP-6780: `contractAddr` is created *in this transaction*. Record it explicitly — it cannot be
              // inferred from `originalWorld` below, because `originInitialisedAccount` deliberately contains the
              // freshly initialised account (needed for EIP-1283/2200 original-value lookups).
              val initialState: PS =
                ProgramState(
                  this,
                  context.copy(
                    world = world1,
                    originalWorld = originInitialisedAccount,
                    createdAddresses = context.createdAddresses + contractAddr
                  ): PC,
                  env
                )
                  .addAccessedAddress(contractAddr)

              // EIP-7708: a CREATE/CREATE2 endowment is a value transfer to the created account and
              // carries the same log, emitted at the time the transfer executes.
              val endowedState =
                if context.evmConfig.amsterdamEnabled && context.endowment > UInt256.Zero &&
                  context.callerAddr != contractAddr
                then initialState.withLog(AmsterdamGas.transferLog(context.callerAddr, contractAddr, context.endowment))
                else initialState

              val execResult = exec(endowedState).toResult

              val newContractResult = saveNewContract(context, contractAddr, execResult, env.evmConfig)
              (newContractResult, contractAddr)
        exitResult = pair._1
        pair
      finally
        if isSubCall then
          tracer.foreach(
            _.onCallExit(
              context.startGas - exitResult.gasRemaining,
              exitResult.returnData,
              exitResult.error.map(_.toString)
            )
          )
    (result, newAddress)

  @tailrec
  final private[vm] def exec(state: ProgramState[W, S]): ProgramState[W, S] =
    val byte = state.program.getByte(state.pc)
    state.config.byteToOpCode.get(byte) match
      case Some(opCode) =>
        val newState = opCode.execute(state)
        // Per-opcode hook. VM-level `tracer` and the tracer carried in state.env.tracer
        // may both be set (VM ctor vs. per-context wiring); fire both so consumers wired
        // either way observe the step.
        tracer.foreach(_.onStep(opCode, state, newState))
        state.env.tracer.foreach(_.onStep(opCode, state, newState))
        import newState.*
        log.trace(
          "op={} pc={} depth={} gasUsed={} gas={} stack={}",
          opCode,
          pc,
          env.callDepth,
          state.gas - gas,
          gas,
          stack
        )
        // Opcode-level tracing for targeted debugging
        if DebugTrace.enabledForBlock(state.env.blockHeader.number.value) && state.env.callDepth == 0 then
          log.debug("[EVM] pc={} op={} gas={} gasAfter={} depth={}", state.pc, opCode, state.gas, gas, env.callDepth)
        if newState.halted then newState
        else exec(newState)

      case None =>
        state.withError(InvalidOpCode(byte)).halt

  /** Derives the EVM opcode name for a sub-call from its ProgramContext. All four CALL variants reach VM.call() via the
    * same method but differ in staticCtx/doTransfer/endowment (verified against OpCode.scala CallOp.exec() lines
    * ~1125-1143).
    */
  private def callTypeName(context: PC): String =
    if context.staticCtx then "STATICCALL"
    else if !context.doTransfer && context.endowment == UInt256.Zero then "DELEGATECALL"
    else if !context.doTransfer then "CALLCODE"
    else "CALL"

  protected def isValidCall(context: PC): Boolean =
    context.endowment <= context.world.getBalance(context.callerAddr) &&
      context.callDepth <= EvmConfig.MaxCallDepth

  /** EIP-2681: a creation whose creator nonce would overflow uint64 fails before it starts. go-ethereum core/vm/evm.go
    * create() returns ErrNonceUintOverflow with the gas unchanged, after the depth and balance checks and BEFORE
    * bumping the nonce and adding the address to the access list -- the same observable outcome as those two checks,
    * which is why it shares their InvalidCall result (CreateOp then restores the pre-bump world, pushes 0, keeps the
    * address cold, and charges only the CREATE base cost).
    *
    * Unconditional, as in go-ethereum, core-geth and Besu: no fork gate. `context.world` already carries the creator's
    * bumped nonce (CreateOp.exec and the tx-level upfront step both increment before calling create), so overflow shows
    * up as a bumped nonce above 2^64 - 1. At tx level it cannot: StdSignedTransactionValidator rejects tx nonces >=
    * 2^64 - 1, so the bumped sender nonce is at most 2^64 - 1.
    */
  private def creatorNonceOverflowed(context: PC): Boolean =
    context.world.getAccount(context.callerAddr).exists(_.nonce.toBigInt > CreateOp.MaxNonce)

  private def invalidCallResult(
      context: PC,
      accessedAddresses: Set[Address],
      accessedStorageKeys: Set[(Address, StorageKey)]
  ): PR =
    ProgramResult(
      ByteString.empty,
      context.startGas,
      context.world,
      Set(),
      Nil,
      Nil,
      0,
      Some(InvalidCall),
      accessedAddresses,
      accessedStorageKeys,
      // EIP-8037: an operation that is unsuccessful BEFORE entering the call frame charges nothing and
      // returns the reservoir exactly as it was handed over. Returning the defaults (0) instead would
      // silently destroy the parent's reservoir.
      stateGasReservoir = context.stateGasReservoir,
      evmStateGasUsed = context.evmStateGasUsed,
      stateGasBaseline = context.stateGasBaselineOverride.getOrElse(context.stateGasReservoir)
    )

  private def exceedsMaxContractSize(context: PC, config: EvmConfig, contractCode: ByteString): Boolean =
    lazy val maxCodeSizeExceeded = config.maxCodeSize.exists(codeSizeLimit => contractCode.size > codeSizeLimit)
    val currentBlock = context.blockHeader.number.value
    // Max code size was enabled on eip161 block number on eth network, and on atlantis block number on etc
    (currentBlock >= config.blockchainConfig.eip161BlockNumber || currentBlock >= config.blockchainConfig.atlantisBlockNumber) &&
    maxCodeSizeExceeded

  private def saveNewContract(context: PC, address: Address, result: PR, config: EvmConfig): PR =
    val tracing = DebugTrace.enabledForBlock(context.blockHeader.number.value)

    val out: PR =
      if result.error.isDefined then
        if result.error.contains(RevertOccurs) then result else result.copy(gasRemaining = 0)
      else
        val contractCode = result.returnData

        // EIP-8037 splits the code-deposit charge across both dimensions: the durable bytes are state gas
        // at CPSB each, and only the hashing work — 6 per 32-byte word — stays in execution gas. The
        // Amsterdam fee schedule sets G_codedeposit to 0, so the legacy 200/byte term vanishes here rather
        // than being double-counted.
        val codeDepositExecutionCost: BigInt =
          if config.amsterdamEnabled then config.feeSchedule.G_sha3word * wordsForBytes(contractCode.size)
          else config.calcCodeDepositCost(contractCode)
        val codeDepositStateCost: BigInt =
          if config.amsterdamEnabled then AmsterdamGas.Cpsb * contractCode.size else BigInt(0)
        // State gas draws from the reservoir first and from gas_left only for the remainder.
        val stateCostFromGasLeft: BigInt = (codeDepositStateCost - result.stateGasReservoir).max(0)

        val maxCodeSizeExceeded = exceedsMaxContractSize(context, config, contractCode)
        val codeStoreOutOfGas = result.gasRemaining < codeDepositExecutionCost + stateCostFromGasLeft
        // EIP-3541: Reject new contracts starting with 0xEF byte
        val startsWithEF = config.eip3541Enabled && contractCode.nonEmpty && contractCode.head == 0xef.toByte

        if startsWithEF then
          // EIP-3541: Code starting with 0xEF byte causes exceptional abort
          result.copy(error = Some(InvalidCode), gasRemaining = 0).withStateGasRestoredToBaseline
        else if maxCodeSizeExceeded || (codeStoreOutOfGas && config.exceptionalFailedCodeDeposit) then
          // Code size too big or code storage causes out-of-gas with exceptionalFailedCodeDeposit enabled.
          // The frame itself completed normally, so `ProgramState.toResult` did NOT roll its state gas
          // back — this is the one exit where the rollback has to be applied after the fact.
          result.copy(error = Some(OutOfGas), gasRemaining = 0).withStateGasRestoredToBaseline
        else if codeStoreOutOfGas && !config.exceptionalFailedCodeDeposit then
          // Code storage causes out-of-gas with exceptionalFailedCodeDeposit disabled. Pre-Homestead only,
          // and pre-Amsterdam by construction: the frame keeps its gas and its state, and no code is stored.
          //
          // `error` MUST stay `None` here. It is the field every consensus consumer keys on:
          //   - `CreateOp` (OpCode.scala) dispatches the CREATE result on `error` alone — a `Some` makes it
          //     push 0 instead of the new address and revert the child frame's world to the post-endowment
          //     snapshot, discarding every SSTORE/log the init code made.
          //   - `BlockPreparator.calcTotalGasToRefund` dispatches on `error.map(_.useWholeGas)` — a `Some`
          //     drops the accumulated gas-refund counter.
          // go-ethereum reaches the same end state by a different route: `core/vm/evm.go create()` does set
          // `err = ErrCodeStoreOutOfGas` unconditionally, but `core/vm/instructions.go opCreate` then throws
          // it away — "if the ruleset is frontier we must ignore this error and pretend the operation was
          // successful" — so it never reaches the caller's stack value, the child's state, or the top-level
          // `vmerr`. Setting `error` here and relying on downstream consumers to un-set it is the shape that
          // produced a silent ETC consensus divergence on blocks 0-1,149,999 (reverted; see git history).
          //
          // `codeDepositShortfall` carries the fact to gas ESTIMATION only. No consensus path reads it.
          result.copy(codeDepositShortfall = true)
        else
          // Code storage succeeded
          result.copy(
            gasRemaining = result.gasRemaining - codeDepositExecutionCost - stateCostFromGasLeft,
            stateGasReservoir = result.stateGasReservoir - (codeDepositStateCost - stateCostFromGasLeft),
            evmStateGasUsed = result.evmStateGasUsed + codeDepositStateCost,
            stateGasFromGasLeft = result.stateGasFromGasLeft + stateCostFromGasLeft,
            world = result.world.saveCode(address, result.returnData)
          )

    if tracing then
      val contractCodeSize = result.returnData.size
      val codeDepositCost =
        if config.amsterdamEnabled then
          config.feeSchedule.G_sha3word * wordsForBytes(contractCodeSize) + AmsterdamGas.Cpsb * contractCodeSize
        else config.calcCodeDepositCost(result.returnData)
      val maxCodeSizeExceeded = exceedsMaxContractSize(context, config, result.returnData)
      val codeStoreOutOfGas = result.gasRemaining < codeDepositCost
      log.info(
        s"TRACE_CREATE block=${context.blockHeader.number} caller=${context.callerAddr} " +
          s"newAddress=$address initcodeSize=${context.inputData.size} runtimeCodeSize=$contractCodeSize " +
          s"gasBeforeDeposit=${result.gasRemaining} codeDepositCost=$codeDepositCost " +
          s"gasAfter=${out.gasRemaining} maxCodeSizeExceeded=$maxCodeSizeExceeded " +
          s"codeStoreOutOfGas=$codeStoreOutOfGas exceptionalFailedCodeDeposit=${config.exceptionalFailedCodeDeposit} " +
          s"errorBefore=${result.error.map(_.toString)} errorAfter=${out.error.map(_.toString)}"
      )

    out
