package com.chipprbots.ethereum.vm

import org.apache.pekko.util.ByteString

import scala.annotation.unused

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.crypto.kec256ZeroPadded
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.SetCodeTransaction
import com.chipprbots.ethereum.domain.StorageKey
import com.chipprbots.ethereum.domain.TxLogEntry
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.domain.UInt256.*
import com.chipprbots.ethereum.utils.ByteStringUtils.Padding
import com.chipprbots.ethereum.vm.BlockchainConfigForEvm.*
import com.chipprbots.ethereum.vm.BlockchainConfigForEvm.EtcForks.EtcFork
import com.chipprbots.ethereum.vm.BlockchainConfigForEvm.EthForks.EthFork

// scalastyle:off magic.number
// scalastyle:off number.of.types
// scalastyle:off method.length
// scalastyle:off file.size.limit
object OpCodes:

  val LogOpCodes: List[OpCode] = List(LOG0, LOG1, LOG2, LOG3, LOG4)

  val SwapOpCodes: List[OpCode] = List(
    SWAP1,
    SWAP2,
    SWAP3,
    SWAP4,
    SWAP5,
    SWAP6,
    SWAP7,
    SWAP8,
    SWAP9,
    SWAP10,
    SWAP11,
    SWAP12,
    SWAP13,
    SWAP14,
    SWAP15,
    SWAP16
  )

  val DupOpCodes: List[OpCode] =
    List(DUP1, DUP2, DUP3, DUP4, DUP5, DUP6, DUP7, DUP8, DUP9, DUP10, DUP11, DUP12, DUP13, DUP14, DUP15, DUP16)

  val PushOpCodes: List[OpCode] = List(
    PUSH1,
    PUSH2,
    PUSH3,
    PUSH4,
    PUSH5,
    PUSH6,
    PUSH7,
    PUSH8,
    PUSH9,
    PUSH10,
    PUSH11,
    PUSH12,
    PUSH13,
    PUSH14,
    PUSH15,
    PUSH16,
    PUSH17,
    PUSH18,
    PUSH19,
    PUSH20,
    PUSH21,
    PUSH22,
    PUSH23,
    PUSH24,
    PUSH25,
    PUSH26,
    PUSH27,
    PUSH28,
    PUSH29,
    PUSH30,
    PUSH31,
    PUSH32
  )

  val FrontierOpCodes: List[OpCode] =
    LogOpCodes ++ SwapOpCodes ++ PushOpCodes ++ DupOpCodes ++ List(
      STOP,
      ADD,
      MUL,
      SUB,
      DIV,
      SDIV,
      MOD,
      SMOD,
      ADDMOD,
      MULMOD,
      EXP,
      SIGNEXTEND,
      LT,
      GT,
      SLT,
      SGT,
      EQ,
      ISZERO,
      AND,
      OR,
      XOR,
      NOT,
      BYTE,
      SHA3,
      ADDRESS,
      BALANCE,
      ORIGIN,
      CALLER,
      CALLVALUE,
      CALLDATALOAD,
      CALLDATASIZE,
      CALLDATACOPY,
      CODESIZE,
      CODECOPY,
      GASPRICE,
      EXTCODESIZE,
      EXTCODECOPY,
      BLOCKHASH,
      COINBASE,
      TIMESTAMP,
      NUMBER,
      DIFFICULTY,
      GASLIMIT,
      POP,
      MLOAD,
      MSTORE,
      MSTORE8,
      SLOAD,
      SSTORE,
      JUMP,
      JUMPI,
      PC,
      MSIZE,
      GAS,
      JUMPDEST,
      CREATE,
      CALL,
      CALLCODE,
      RETURN,
      INVALID,
      SELFDESTRUCT
    )

  val HomesteadOpCodes: List[OpCode] =
    DELEGATECALL +: FrontierOpCodes

  val ByzantiumOpCodes: List[OpCode] =
    List(REVERT, STATICCALL, RETURNDATACOPY, RETURNDATASIZE) ++ HomesteadOpCodes

  val ConstantinopleOpCodes: List[OpCode] =
    List(EXTCODEHASH, CREATE2, SHL, SHR, SAR) ++ ByzantiumOpCodes

  val PhoenixOpCodes: List[OpCode] =
    List(CHAINID, SELFBALANCE) ++ ConstantinopleOpCodes

  val SpiralOpCodes: List[OpCode] =
    PUSH0 +: PhoenixOpCodes

  /** EIP-7939 / ECIP-1121: CLZ included in Olympia for ETC's block-based fork path. ETC uses forBlock() →
    * OlympiaConfigBuilder; Osaka uses forTimestamp() (ETH only). Besu ClassicEVMs.olympiaOperations() confirms CLZ is
    * required here for ETC.
    */
  val OlympiaOpCodes: List[OpCode] =
    CLZ :: (List(BASEFEE, BLOBHASH, BLOBBASEFEE, TLOAD, TSTORE, MCOPY) ++ SpiralOpCodes)

  /** ETC Olympia opcode list (ECIP-1121 via block-based fork). Excludes EIP-4844 (BLOBHASH) and EIP-7516 (BLOBBASEFEE)
    * which are ETH-only: core-geth ETC Olympia config has no EIP4844FBlock or EIP7516FBlock. Besu
    * ClassicEVMs.olympiaOperations() confirms these are absent for ETC.
    */
  val EtcOlympiaOpCodes: List[OpCode] =
    CLZ :: (List(BASEFEE, TLOAD, TSTORE, MCOPY) ++ SpiralOpCodes)

  // ── ETH-only opcode tables (never selected on an ETC chain) ─────────────────────────────────
  //
  // ETH London/Paris are block-gated through `LondonConfigBuilder`, the branch `forBlock()` takes only when
  // `spiralBlockNumber > olympiaBlockNumber` (the ETH fork shape; no shipped ETC config satisfies it). Shanghai onward
  // are applied by the timestamp overlay, which is inert on ETC because ETC configs declare no fork timestamps. None of
  // the ETC tables above (Magneto/Spiral/EtcOlympia) is modified or referenced by these.
  //
  // Each list is exactly the previous ETH fork plus that fork's EIPs, cross-checked against go-ethereum
  // core/vm/jump_table.go (newLondonInstructionSet … newOsakaInstructionSet) and EEST v5.4.0 test_all_opcodes.

  /** ETH London / Paris: Berlin (= Phoenix set) + BASEFEE (EIP-3198). */
  val LondonOpCodes: List[OpCode] = BASEFEE +: PhoenixOpCodes

  /** ETH Shanghai: London + PUSH0 (EIP-3855). */
  val ShanghaiOpCodes: List[OpCode] = PUSH0 +: LondonOpCodes

  /** ETH Cancun / Prague: Shanghai + BLOBHASH (EIP-4844), BLOBBASEFEE (EIP-7516), TLOAD/TSTORE (EIP-1153), MCOPY
    * (EIP-5656). No CLZ — EIP-7939 is Osaka; 0x1e is an undefined opcode here.
    */
  val CancunOpCodes: List[OpCode] = List(BLOBHASH, BLOBBASEFEE, TLOAD, TSTORE, MCOPY) ++ ShanghaiOpCodes

  /** ETH Osaka: Cancun + CLZ (EIP-7939). */
  val OsakaOpCodes: List[OpCode] = CLZ :: CancunOpCodes

object OpCode:

  /** Zero gas, as one shared value: the default state-gas delta and the variable gas of a constant-gas instruction are
    * read for every instruction executed, and `0` converted to BigInt at each read costs a cache lookup.
    */
  private[vm] val NoGas: BigInt = BigInt(0)

  def sliceBytes(bytes: ByteString, offset: UInt256, size: UInt256): ByteString =
    val start = offset.min(bytes.size).toInt
    val end = (offset + size).min(bytes.size).toInt
    bytes.slice(start, end).padToByteString(size.toInt, 0.toByte)

  def addressAccessCost[S <: Storage[S], W <: WorldStateProxy[W, S]](state: ProgramState[W, S], address: Address)(
      preGasFn: FeeSchedule => BigInt,
      postColdGasFn: FeeSchedule => BigInt,
      postWarmGasFn: FeeSchedule => BigInt
  ): BigInt =
    accessCost(state, state.accessedAddresses.contains(address))(preGasFn, postColdGasFn, postWarmGasFn)

  def storageAccessCost[S <: Storage[S], W <: WorldStateProxy[W, S]](
      state: ProgramState[W, S],
      address: Address,
      key: StorageKey
  )(
      preGasFn: FeeSchedule => BigInt,
      postColdGasFn: FeeSchedule => BigInt,
      postWarmGasFn: FeeSchedule => BigInt
  ): BigInt =
    accessCost(state, state.accessedStorageKeys.contains((address, key)))(preGasFn, postColdGasFn, postWarmGasFn)

  def accessCost[S <: Storage[S], W <: WorldStateProxy[W, S]](state: ProgramState[W, S], isWarm: Boolean)(
      preGasFn: FeeSchedule => BigInt,
      postColdGasFn: FeeSchedule => BigInt,
      postWarmGasFn: FeeSchedule => BigInt
  ): BigInt =
    val currentBlockNumber = state.env.blockHeader.number.value
    val etcFork = state.config.blockchainConfig.etcForkForBlockNumber(currentBlockNumber)
    val ethFork = state.config.blockchainConfig.ethForkForBlockNumber(currentBlockNumber)
    val eip2929Enabled = isEip2929Enabled(etcFork, ethFork)
    if eip2929Enabled then
      if isWarm then postWarmGasFn(state.config.feeSchedule)
      else postColdGasFn(state.config.feeSchedule)
    else preGasFn(state.config.feeSchedule)

/** Base class for all the opcodes of the EVM
  *
  * @param code
  *   Opcode byte representation
  * @param delta
  *   number of words to be popped from stack
  * @param alpha
  *   number of words to be pushed to stack
  * @param baseGasFn
  *   function to compute base gas cost from fee schedule
  */
abstract class OpCode(val code: Byte, val delta: Int, val alpha: Int, val baseGasFn: FeeSchedule => BigInt)
    extends Product
    with Serializable:
  def this(code: Int, pop: Int, push: Int, constGasFn: FeeSchedule => BigInt) = this(code.toByte, pop, push, constGasFn)

  def execute[S <: Storage[S], W <: WorldStateProxy[W, S]](state: ProgramState[W, S]): ProgramState[W, S] =
    if !availableInContext(state) then state.withError(OpCodeNotAvailableInStaticContext(code))
    else if state.stack.size < delta then state.withError(StackUnderflow)
    else if state.stack.size - delta + alpha > state.stack.maxSize then state.withError(StackOverflow)
    else
      val gas: BigInt = calcGas(state)
      if gas > state.gas then state.copy(gas = 0).withError(OutOfGas)
      else
        // EIP-8037: state gas is metered at the END of a state-mutating opcode. The DECISION, though, is
        // made from the pre-execution state — the SSTORE table keys on the slot's original, current and
        // new values, all of which are only available before `exec` runs.
        val stateGas: BigInt = stateGasDelta(state)
        val executed = execAndSpendGas(state, gas)
        // signum, not `== 0` / `< 0`: comparing a BigInt with an Int literal boxes the literal and goes through
        // BoxesRunTime's numeric equality on every instruction. Same outcomes.
        if stateGas.signum == 0 then executed
        else if stateGas.signum < 0 then executed.refillStateGas(-stateGas)
        else if executed.stateGasShortfall(stateGas) > executed.gas then
          // A state charge that cannot be met is an EXCEPTIONAL HALT, not a partial charge. This is the
          // measured `tx-emit-*` case: the execution component is affordable, GAS_STORAGE_SET is not, and
          // the transaction consumes its whole limit having written nothing.
          state.copy(gas = 0).withError(OutOfGas)
        else executed.chargeStateGas(stateGas)

  protected def calcGas[S <: Storage[S], W <: WorldStateProxy[W, S]](state: ProgramState[W, S]): BigInt =
    baseGas(state) + varGas(state)

  /** EIP-8037 state-gas charged (positive) or refilled (negative) by this opcode, computed from the state BEFORE
    * execution.
    *
    * Zero for every opcode that does not create or destroy state, and zero for every opcode on every pre-Amsterdam and
    * ETC path — which is what keeps the hook above a no-op outside the fork.
    */
  protected def stateGasDelta[S <: Storage[S], W <: WorldStateProxy[W, S]](@unused state: ProgramState[W, S]): BigInt =
    OpCode.NoGas

  protected def baseGas[S <: Storage[S], W <: WorldStateProxy[W, S]](state: ProgramState[W, S]): BigInt = baseGasFn(
    state.config.feeSchedule
  )

  protected def varGas[S <: Storage[S], W <: WorldStateProxy[W, S]](state: ProgramState[W, S]): BigInt

  protected def exec[S <: Storage[S], W <: WorldStateProxy[W, S]](state: ProgramState[W, S]): ProgramState[W, S]

  /** Runs the instruction and charges its execution gas: `exec(state).spendGas(gas)`.
    *
    * [[StackOnlyOp]] and JUMP/JUMPI override this to build the very same state in one `copy`. The composed form copies
    * the 29-field `ProgramState` once per step — `withStack`, then `step`/`goto`, then `spendGas` — and on the stack
    * and arithmetic instructions those copies were the largest single cost of interpretation.
    */
  protected def execAndSpendGas[S <: Storage[S], W <: WorldStateProxy[W, S]](
      state: ProgramState[W, S],
      gas: BigInt
  ): ProgramState[W, S] =
    exec(state).spendGas(gas)

  protected def availableInContext[S <: Storage[S], W <: WorldStateProxy[W, S]]: ProgramState[W, S] => Boolean = _ =>
    true

trait AddrAccessGas:
  self: OpCode =>

  private def coldGasFn: FeeSchedule => BigInt = _.G_cold_account_access
  private def warmGasFn: FeeSchedule => BigInt = _.G_warm_storage_read

  override protected def baseGas[S <: Storage[S], W <: WorldStateProxy[W, S]](state: ProgramState[W, S]): BigInt =
    OpCode.addressAccessCost(state, address(state))(baseGasFn, coldGasFn, warmGasFn)

  protected def address[S <: Storage[S], W <: WorldStateProxy[W, S]](state: ProgramState[W, S]): Address

trait StorageAccessGas:
  self: OpCode =>

  private def coldGasFn: FeeSchedule => BigInt = _.G_cold_sload
  private def warmGasFn: FeeSchedule => BigInt = _.G_warm_storage_read

  override protected def baseGas[S <: Storage[S], W <: WorldStateProxy[W, S]](state: ProgramState[W, S]): BigInt =
    val (address, key) = addressAndKey(state)
    OpCode.storageAccessCost(state, address, key)(baseGasFn, coldGasFn, warmGasFn)

  protected def addressAndKey[S <: Storage[S], W <: WorldStateProxy[W, S]](
      state: ProgramState[W, S]
  ): (Address, StorageKey)

sealed trait ConstGas:
  self: OpCode =>
  protected def varGas[S <: Storage[S], W <: WorldStateProxy[W, S]](state: ProgramState[W, S]): BigInt = OpCode.NoGas

  // `baseGas(state) + varGas(state)` with the variable part known to be zero: the same value, without the call, the
  // BigInt addition and its small-value cache lookup on every constant-gas instruction.
  override protected def calcGas[S <: Storage[S], W <: WorldStateProxy[W, S]](state: ProgramState[W, S]): BigInt =
    baseGas(state)

/** An instruction whose whole effect is a new stack and a program-counter advance. It writes no memory, world, return
  * data, log, refund or access list, and never halts.
  *
  * Both transitions are derived from `nextStack` and `pcIncrement`, so they cannot disagree: `exec` is
  * `withStack(nextStack(state)).step(pcIncrement)`, and `execAndSpendGas` is that followed by `spendGas(gas)` — the
  * default composition — built as a single copy. `nextStack` always sees the state from before the instruction, gas
  * included, exactly as `exec` did (GAS pushes the pre-charge gas minus its own cost).
  */
sealed trait StackOnlyOp:
  self: OpCode =>

  /** The stack after this instruction, computed from the state before it. */
  protected def nextStack[S <: Storage[S], W <: WorldStateProxy[W, S]](state: ProgramState[W, S]): Stack

  /** How far the program counter moves: 1, or 1 + the immediate's length for PUSH1..PUSH32. */
  protected def pcIncrement: Int = 1

  protected def exec[S <: Storage[S], W <: WorldStateProxy[W, S]](state: ProgramState[W, S]): ProgramState[W, S] =
    state.withStack(nextStack(state)).step(pcIncrement)

  override protected def execAndSpendGas[S <: Storage[S], W <: WorldStateProxy[W, S]](
      state: ProgramState[W, S],
      gas: BigInt
  ): ProgramState[W, S] =
    state.stepWithStack(nextStack(state), pcIncrement, gas)

case object STOP extends OpCode(0x00, 0, 0, _.G_zero) with ConstGas:
  protected def exec[S <: Storage[S], W <: WorldStateProxy[W, S]](state: ProgramState[W, S]): ProgramState[W, S] =
    state.withReturnData(ByteString.empty).halt

sealed abstract class UnaryOp(code: Int, baseGasFn: FeeSchedule => BigInt)(val f: UInt256 => UInt256)
    extends OpCode(code, 1, 1, baseGasFn)
    with ConstGas
    with StackOnlyOp:

  protected def nextStack[S <: Storage[S], W <: WorldStateProxy[W, S]](state: ProgramState[W, S]): Stack =
    val (a, stack1) = state.stack.pop()
    val res = f(a)
    stack1.push(res)

sealed abstract class BinaryOp(code: Int, baseGasFn: FeeSchedule => BigInt)(val f: (UInt256, UInt256) => UInt256)
    extends OpCode(code.toByte, 2, 1, baseGasFn)
    with StackOnlyOp:

  protected def nextStack[S <: Storage[S], W <: WorldStateProxy[W, S]](state: ProgramState[W, S]): Stack =
    val (Seq(a, b), stack1) = state.stack.pop(2)
    val res = f(a, b)
    stack1.push(res)

sealed abstract class TernaryOp(code: Int, baseGasFn: FeeSchedule => BigInt)(
    val f: (UInt256, UInt256, UInt256) => UInt256
) extends OpCode(code.toByte, 3, 1, baseGasFn)
    with StackOnlyOp:

  protected def nextStack[S <: Storage[S], W <: WorldStateProxy[W, S]](state: ProgramState[W, S]): Stack =
    val (Seq(a, b, c), stack1) = state.stack.pop(3)
    val res = f(a, b, c)
    stack1.push(res)

sealed abstract class ConstOp(code: Int)(
    val f: ProgramState[? <: WorldStateProxy[_, ? <: Storage[_]], ? <: Storage[_]] => UInt256
) extends OpCode(code, 0, 1, _.G_base)
    with ConstGas
    with StackOnlyOp:

  protected def nextStack[S <: Storage[S], W <: WorldStateProxy[W, S]](state: ProgramState[W, S]): Stack =
    state.stack.push(f(state))

sealed abstract class ShiftingOp(code: Int, f: (UInt256, UInt256) => UInt256)
    extends OpCode(code, 2, 1, _.G_verylow)
    with ConstGas
    with StackOnlyOp:
  protected def nextStack[S <: Storage[S], W <: WorldStateProxy[W, S]](state: ProgramState[W, S]): Stack =
    val (Seq(shift: UInt256, value: UInt256), remainingStack) = state.stack.pop(2)
    val result = if shift >= UInt256(256) then Zero else f(value, shift)
    remainingStack.push(result)

case object ADD extends BinaryOp(0x01, _.G_verylow)(_ + _) with ConstGas

case object MUL extends BinaryOp(0x02, _.G_low)(_ * _) with ConstGas

case object SUB extends BinaryOp(0x03, _.G_verylow)(_ - _) with ConstGas

case object DIV extends BinaryOp(0x04, _.G_low)(_ div _) with ConstGas

case object SDIV extends BinaryOp(0x05, _.G_low)(_ sdiv _) with ConstGas

case object MOD extends BinaryOp(0x06, _.G_low)(_ mod _) with ConstGas

case object SMOD extends BinaryOp(0x07, _.G_low)(_ smod _) with ConstGas

case object ADDMOD extends TernaryOp(0x08, _.G_mid)(_.addmod(_, _)) with ConstGas

case object MULMOD extends TernaryOp(0x09, _.G_mid)(_.mulmod(_, _)) with ConstGas

case object EXP extends BinaryOp(0x0a, _.G_exp)(_ ** _):
  protected def varGas[S <: Storage[S], W <: WorldStateProxy[W, S]](state: ProgramState[W, S]): BigInt =
    val (Seq(_, m: UInt256), _) = state.stack.pop(2)
    state.config.feeSchedule.G_expbyte * m.byteSize

case object SIGNEXTEND extends BinaryOp(0x0b, _.G_low)((a, b) => b.signExtend(a)) with ConstGas

case object LT extends BinaryOp(0x10, _.G_verylow)(_ < _) with ConstGas

case object GT extends BinaryOp(0x11, _.G_verylow)(_ > _) with ConstGas

case object SLT extends BinaryOp(0x12, _.G_verylow)(_ slt _) with ConstGas

case object SGT extends BinaryOp(0x13, _.G_verylow)(_ sgt _) with ConstGas

case object EQ extends BinaryOp(0x14, _.G_verylow)(_ == _) with ConstGas

case object ISZERO extends UnaryOp(0x15, _.G_verylow)(_.isZero) with ConstGas

case object AND extends BinaryOp(0x16, _.G_verylow)(_ & _) with ConstGas

case object OR extends BinaryOp(0x17, _.G_verylow)(_ | _) with ConstGas

case object XOR extends BinaryOp(0x18, _.G_verylow)(_ ^ _) with ConstGas

case object NOT extends UnaryOp(0x19, _.G_verylow)(~_) with ConstGas

case object BYTE extends BinaryOp(0x1a, _.G_verylow)((a, b) => b.getByte(a)) with ConstGas

// logical shift left
case object SHL extends ShiftingOp(0x1b, _ << _)

// logical shift right
case object SHR extends ShiftingOp(0x1c, _ >> _)

// arithmetic shift right
case object SAR extends OpCode(0x1d, 2, 1, _.G_verylow) with ConstGas with StackOnlyOp:
  protected def nextStack[S <: Storage[S], W <: WorldStateProxy[W, S]](state: ProgramState[W, S]): Stack =
    val (Seq(shift, value), remainingStack) = state.stack.pop(2)

    val result =
      if shift >= UInt256(256) then if value.toSign >= 0 then Zero else UInt256(-1)
      else value.sshift(shift)

    remainingStack.push(result)

/** EIP-7939: Count Leading Zero bits. Pops one 256-bit value and pushes the count of leading zero bits (0..256). For
  * the zero input, result is 256.
  */
case object CLZ extends UnaryOp(0x1e, _.G_low)(v => UInt256(256 - v.toBigInt.bitLength)) with ConstGas

case object SHA3 extends OpCode(0x20, 2, 1, _.G_sha3):
  protected def exec[S <: Storage[S], W <: WorldStateProxy[W, S]](state: ProgramState[W, S]): ProgramState[W, S] =
    val (Seq(offset, size), stack1) = state.stack.pop(2)
    // Stream the never-written tail of the region into the digest instead of materialising it: a paid-for
    // gigabyte-scale SHA3 (ethereum/tests randomStatetest94) must hash, not exhaust the heap. Same bytes, same hash.
    val (storedPrefix, zeroTail, mem1) = state.memory.loadZeroPadded(offset, size)
    val hash = kec256ZeroPadded(storedPrefix.toArray, zeroTail)
    val ret = UInt256(hash)
    val stack2 = stack1.push(ret)
    state.withStack(stack2).withMemory(mem1).step()

  protected def varGas[S <: Storage[S], W <: WorldStateProxy[W, S]](state: ProgramState[W, S]): BigInt =
    val (Seq(offset, size), _) = state.stack.pop(2)
    val memCost = state.config.calcMemCost(state.memory.size, offset, size)
    val shaCost = state.config.feeSchedule.G_sha3word * wordsForBytes(size)
    memCost + shaCost

case object ADDRESS extends ConstOp(0x30)(_.env.ownerAddr.toUInt256)

case object BALANCE extends OpCode(0x31, 1, 1, _.G_balance) with AddrAccessGas with ConstGas:
  protected def exec[S <: Storage[S], W <: WorldStateProxy[W, S]](state: ProgramState[W, S]): ProgramState[W, S] =
    val (accountAddress, stack1) = state.stack.pop()
    val addr = Address(accountAddress)
    val accountBalance = state.world.getBalance(addr)
    val stack2 = stack1.push(accountBalance)
    state.withStack(stack2).addAccessedAddress(addr).step()

  protected def address[S <: Storage[S], W <: WorldStateProxy[W, S]](state: ProgramState[W, S]): Address =
    val (accountAddress, _) = state.stack.pop()
    Address(accountAddress)

case object EXTCODEHASH extends OpCode(0x3f, 1, 1, _.G_balance) with AddrAccessGas with ConstGas:

  protected def exec[S <: Storage[S], W <: WorldStateProxy[W, S]](state: ProgramState[W, S]): ProgramState[W, S] =
    val (accountAddress, stack1) = state.stack.pop()
    val address = Address(accountAddress)

    /** Specification of EIP1052 - https://eips.ethereum.org/EIPS/eip-1052, says that we should return 0 In case the
      * account does not exist 0 is pushed to the stack.
      *
      * But the interpretation is, that account does not exists if:
      *   - it do not exists or,
      *   - is empty according to eip161 rules (account is considered empty when it has no code and zero nonce and zero
      *     balance)
      *
      * Example of existing check in geth:
      * https://github.com/ethereum/go-ethereum/blob/aad3c67a92cd4f3cc3a885fdc514ba2a7fb3e0a3/core/state/statedb.go#L203
      */
    val accountExists = !state.world.isAccountDead(address)

    val codeHash =
      if accountExists then
        val code = state.world.getCode(address)

        if code.isEmpty then UInt256(Account.EmptyCodeHash.value)
        else UInt256(kec256(code))
      else UInt256.Zero

    val stack2 = stack1.push(codeHash)
    state.withStack(stack2).addAccessedAddress(address).step()

  protected def address[S <: Storage[S], W <: WorldStateProxy[W, S]](state: ProgramState[W, S]): Address =
    val (accountAddress, _) = state.stack.pop()
    Address(accountAddress)

case object ORIGIN extends ConstOp(0x32)(_.env.originAddr.toUInt256)

case object CALLER extends ConstOp(0x33)(_.env.callerAddr.toUInt256)

case object CALLVALUE extends ConstOp(0x34)(_.env.value)

case object CALLDATALOAD extends OpCode(0x35, 1, 1, _.G_verylow) with ConstGas:
  protected def exec[S <: Storage[S], W <: WorldStateProxy[W, S]](state: ProgramState[W, S]): ProgramState[W, S] =
    val (offset, stack1) = state.stack.pop()
    val data = OpCode.sliceBytes(state.inputData, offset, 32)
    val stack2 = stack1.push(UInt256(data))
    state.withStack(stack2).step()

case object CALLDATASIZE extends ConstOp(0x36)(_.inputData.size)

case object CALLDATACOPY extends OpCode(0x37, 3, 0, _.G_verylow):
  protected def exec[S <: Storage[S], W <: WorldStateProxy[W, S]](state: ProgramState[W, S]): ProgramState[W, S] =
    val (Seq(memOffset, dataOffset, size), stack1) = state.stack.pop(3)
    val data = OpCode.sliceBytes(state.inputData, dataOffset, size)
    val mem1 = state.memory.store(memOffset, data)
    state.withStack(stack1).withMemory(mem1).step()

  protected def varGas[S <: Storage[S], W <: WorldStateProxy[W, S]](state: ProgramState[W, S]): BigInt =
    val (Seq(offset, _, size), _) = state.stack.pop(3)
    val memCost = state.config.calcMemCost(state.memory.size, offset, size)
    val copyCost = state.config.feeSchedule.G_copy * wordsForBytes(size)
    memCost + copyCost

case object CODESIZE extends ConstOp(0x38)(_.env.program.length)

case object CODECOPY extends OpCode(0x39, 3, 0, _.G_verylow):
  protected def exec[S <: Storage[S], W <: WorldStateProxy[W, S]](state: ProgramState[W, S]): ProgramState[W, S] =
    val (Seq(memOffset, codeOffset, size), stack1) = state.stack.pop(3)
    val bytes = OpCode.sliceBytes(state.program.code, codeOffset, size)
    val mem1 = state.memory.store(memOffset, bytes)
    state.withStack(stack1).withMemory(mem1).step()

  protected def varGas[S <: Storage[S], W <: WorldStateProxy[W, S]](state: ProgramState[W, S]): BigInt =
    val (Seq(offset, _, size), _) = state.stack.pop(3)
    val memCost = state.config.calcMemCost(state.memory.size, offset, size)
    val copyCost = state.config.feeSchedule.G_copy * wordsForBytes(size)
    memCost + copyCost

case object GASPRICE extends ConstOp(0x3a)(_.env.gasPrice)

case object EXTCODESIZE extends OpCode(0x3b, 1, 1, _.G_extcode) with AddrAccessGas with ConstGas:
  protected def exec[S <: Storage[S], W <: WorldStateProxy[W, S]](state: ProgramState[W, S]): ProgramState[W, S] =
    val (addrUint, stack1) = state.stack.pop()
    val addr = Address(addrUint)
    val codeSize = state.world.getCode(addr).size
    val stack2 = stack1.push(UInt256(codeSize))
    state.withStack(stack2).addAccessedAddress(addr).step()

  protected def address[S <: Storage[S], W <: WorldStateProxy[W, S]](state: ProgramState[W, S]): Address =
    val (accountAddress, _) = state.stack.pop()
    Address(accountAddress)

case object EXTCODECOPY extends OpCode(0x3c, 4, 0, _.G_extcode) with AddrAccessGas:

  protected def exec[S <: Storage[S], W <: WorldStateProxy[W, S]](state: ProgramState[W, S]): ProgramState[W, S] =
    val (Seq(address, memOffset, codeOffset, size), stack1) = state.stack.pop(4)
    val addr = Address(address)
    val codeCopy = OpCode.sliceBytes(state.world.getCode(addr), codeOffset, size)
    val mem1 = state.memory.store(memOffset, codeCopy)
    state.withStack(stack1).withMemory(mem1).addAccessedAddress(addr).step()

  override protected def varGas[S <: Storage[S], W <: WorldStateProxy[W, S]](state: ProgramState[W, S]): BigInt =
    val (Seq(_, memOffset, _, size), _) = state.stack.pop(4)
    val memCost = state.config.calcMemCost(state.memory.size, memOffset, size)
    val copyCost = state.config.feeSchedule.G_copy * wordsForBytes(size)
    memCost + copyCost

  protected def address[S <: Storage[S], W <: WorldStateProxy[W, S]](state: ProgramState[W, S]): Address =
    val (Seq(accountAddress, _, _, _), _) = state.stack.pop(4)
    Address(accountAddress)

case object RETURNDATASIZE extends ConstOp(0x3d)(_.returnData.size)

case object RETURNDATACOPY extends OpCode(0x3e, 3, 0, _.G_verylow):
  protected def exec[S <: Storage[S], W <: WorldStateProxy[W, S]](state: ProgramState[W, S]): ProgramState[W, S] =
    val (Seq(memOffset, dataOffset, size), stack1) = state.stack.pop(3)
    if dataOffset.fillingAdd(size) > state.returnData.size then state.withStack(stack1).withError(ReturnDataOverflow)
    else
      val data = OpCode.sliceBytes(state.returnData, dataOffset, size)
      val mem1 = state.memory.store(memOffset, data)
      state.withStack(stack1).withMemory(mem1).step()

  protected def varGas[S <: Storage[S], W <: WorldStateProxy[W, S]](state: ProgramState[W, S]): BigInt =
    val (Seq(offset, _, size), _) = state.stack.pop(3)
    val memCost = state.config.calcMemCost(state.memory.size, offset, size)
    val copyCost = state.config.feeSchedule.G_copy * wordsForBytes(size)
    memCost + copyCost

case object BLOCKHASH extends OpCode(0x40, 1, 1, _.G_blockhash) with ConstGas:
  protected def exec[S <: Storage[S], W <: WorldStateProxy[W, S]](state: ProgramState[W, S]): ProgramState[W, S] =
    val (blockNumber, stack1) = state.stack.pop()

    val outOfLimits =
      state.env.blockHeader.number.value - blockNumber > 256 || blockNumber >= state.env.blockHeader.number.value
    val hash = if outOfLimits then UInt256.Zero else state.world.getBlockHash(blockNumber).getOrElse(UInt256.Zero)

    val stack2 = stack1.push(hash)
    state.withStack(stack2).step()

case object COINBASE extends ConstOp(0x41)(s => UInt256(s.env.blockHeader.beneficiary))

// toUInt256 is the UNSIGNED widening. UInt256(_.toLong) sign-extends, which would push
// 2^256-1 instead of 2^64-1 for a top-half uint64 timestamp — a silent state divergence
// from every reference client for any contract that reads block.timestamp.
case object TIMESTAMP extends ConstOp(0x42)(s => s.env.blockHeader.unixTimestamp.toUInt256)

case object NUMBER extends ConstOp(0x43)(s => UInt256(s.env.blockHeader.number.value))

case object DIFFICULTY
    extends ConstOp(0x44)(s =>
      // EIP-4399: post-merge, opcode 0x44 returns prevRandao (stored in mixHash) instead of difficulty
      if s.env.blockHeader.isPoS then UInt256(s.env.blockHeader.mixHash.value)
      else UInt256(s.env.blockHeader.difficulty.value)
    )

case object GASLIMIT extends ConstOp(0x45)(s => UInt256(s.env.blockHeader.gasLimit.value))

case object POP extends OpCode(0x50, 1, 0, _.G_base) with ConstGas with StackOnlyOp:
  protected def nextStack[S <: Storage[S], W <: WorldStateProxy[W, S]](state: ProgramState[W, S]): Stack =
    val (_, stack1) = state.stack.pop()
    stack1

case object MLOAD extends OpCode(0x51, 1, 1, _.G_verylow):
  protected def exec[S <: Storage[S], W <: WorldStateProxy[W, S]](state: ProgramState[W, S]): ProgramState[W, S] =
    val (offset, stack1) = state.stack.pop()
    val (word, mem1) = state.memory.load(offset)
    val stack2 = stack1.push(word)
    state.withStack(stack2).withMemory(mem1).step()

  protected def varGas[S <: Storage[S], W <: WorldStateProxy[W, S]](state: ProgramState[W, S]): BigInt =
    val (offset, _) = state.stack.pop()
    state.config.calcMemCost(state.memory.size, offset, UInt256.Size)

case object MSTORE extends OpCode(0x52, 2, 0, _.G_verylow):
  protected def exec[S <: Storage[S], W <: WorldStateProxy[W, S]](state: ProgramState[W, S]): ProgramState[W, S] =
    val (Seq(offset, value), stack1) = state.stack.pop(2)
    val updatedMem = state.memory.store(offset, value)
    state.withStack(stack1).withMemory(updatedMem).step()

  protected def varGas[S <: Storage[S], W <: WorldStateProxy[W, S]](state: ProgramState[W, S]): BigInt =
    val (offset, _) = state.stack.pop()
    state.config.calcMemCost(state.memory.size, offset, UInt256.Size)

case object SLOAD extends OpCode(0x54, 1, 1, _.G_sload) with StorageAccessGas with ConstGas:
  protected def exec[S <: Storage[S], W <: WorldStateProxy[W, S]](state: ProgramState[W, S]): ProgramState[W, S] =
    val (offset, stack1) = state.stack.pop()
    val value = state.storage.load(offset)
    val stack2 = stack1.push(UInt256(value))
    state.withStack(stack2).addAccessedStorageKey(state.ownAddress, StorageKey(offset.toBigInt)).step()

  protected def addressAndKey[S <: Storage[S], W <: WorldStateProxy[W, S]](
      state: ProgramState[W, S]
  ): (Address, StorageKey) =
    val (offset, _) = state.stack.pop()
    (state.ownAddress, StorageKey(offset.toBigInt))

case object MSTORE8 extends OpCode(0x53, 2, 0, _.G_verylow):
  protected def exec[S <: Storage[S], W <: WorldStateProxy[W, S]](state: ProgramState[W, S]): ProgramState[W, S] =
    val (Seq(offset, value), stack1) = state.stack.pop(2)
    val valueToByte = value.mod(256).toByte
    val updatedMem = state.memory.store(offset, valueToByte)
    state.withStack(stack1).withMemory(updatedMem).step()

  protected def varGas[S <: Storage[S], W <: WorldStateProxy[W, S]](state: ProgramState[W, S]): BigInt =
    val (offset, _) = state.stack.pop()
    state.config.calcMemCost(state.memory.size, offset, 1)

case object SSTORE extends OpCode(0x55, 2, 0, _.G_zero):
  protected def exec[S <: Storage[S], W <: WorldStateProxy[W, S]](state: ProgramState[W, S]): ProgramState[W, S] =
    val currentBlockNumber = state.env.blockHeader.number.value
    val etcFork = state.config.blockchainConfig.etcForkForBlockNumber(currentBlockNumber)
    val ethFork = state.config.blockchainConfig.ethForkForBlockNumber(currentBlockNumber)

    val eip2200Enabled = isEip2200Enabled(etcFork, ethFork)
    val eip1283Enabled = isEip1283Enabled(ethFork)

    val (Seq(offset, newValue), stack1) = state.stack.pop(2)
    val currentValue = state.storage.load(offset)

    val refund: BigInt =
      if state.config.amsterdamEnabled then amsterdamRefund(state, offset, newValue, currentValue)
      else if eip2200Enabled || eip1283Enabled then
        val originalValue = state.originalWorld.getStorage(state.ownAddress).load(offset)
        if currentValue != newValue.toBigInt then
          if originalValue == currentValue then // fresh slot
            if originalValue != 0 && newValue.isZero then state.config.feeSchedule.R_sclear
            else 0
          else // dirty slot
            val clear =
              if originalValue != 0 then
                if currentValue == 0 then -state.config.feeSchedule.R_sclear
                else if newValue.isZero then state.config.feeSchedule.R_sclear
                else BigInt(0)
              else BigInt(0)

            val reset =
              if originalValue == newValue.toBigInt then
                if UInt256(originalValue).isZero then state.config.feeSchedule.G_sset - state.config.feeSchedule.G_sload
                else state.config.feeSchedule.G_sreset - state.config.feeSchedule.G_sload
              else BigInt(0)
            clear + reset
        else BigInt(0)
      else if newValue.isZero && !UInt256(currentValue).isZero then state.config.feeSchedule.R_sclear
      else 0
    val updatedStorage = state.storage.store(offset, newValue)
    state
      .addAccessedStorageKey(state.ownAddress, StorageKey(offset.toBigInt))
      .withStack(stack1)
      .withStorage(updatedStorage)
      .refundGas(refund)
      .step()

  protected def varGas[S <: Storage[S], W <: WorldStateProxy[W, S]](state: ProgramState[W, S]): BigInt =
    val (Seq(offset, newValue), _) = state.stack.pop(2)
    val currentValue = state.storage.load(offset)

    val currentBlockNumber = state.env.blockHeader.number.value
    val etcFork = state.config.blockchainConfig.etcForkForBlockNumber(currentBlockNumber)
    val ethFork = state.config.blockchainConfig.ethForkForBlockNumber(currentBlockNumber)

    val eip2200Enabled = isEip2200Enabled(etcFork, ethFork)
    val eip1283Enabled = isEip1283Enabled(ethFork)

    if state.config.amsterdamEnabled then amsterdamVarGas(state, offset, newValue, currentValue)
    else amsterdamFreeVarGas(state, offset, newValue, currentValue, eip2200Enabled, eip1283Enabled)

  /** EIP-8038 SSTORE pricing: three independent components, of which only the first two are execution gas.
    *
    *   - **Access**: COLD_STORAGE_ACCESS (2,100) or WARM_ACCESS (100), on the EIP-2929 rules, unchanged.
    *   - **Write**: STORAGE_WRITE (10,000) when the write moves the slot AWAY from its transaction-start value — `new
    *     != current && current == original`. Net-metered, so a slot returned to its original value gets it back through
    *     the refund counter rather than never paying it.
    *   - **State creation**: GAS_STORAGE_SET, in the state dimension only. See `stateGasDelta`.
    *
    * This replaces the EIP-2200 `G_sset`/`G_sreset`/no-op ladder outright rather than adjusting it; the two models do
    * not compose. The EIP-7928 GAS_CALL_STIPEND pre-state check is retained and, per EIP-8037, is measured against
    * `gas_left` alone, excluding the reservoir.
    */
  private def amsterdamVarGas[S <: Storage[S], W <: WorldStateProxy[W, S]](
      state: ProgramState[W, S],
      offset: UInt256,
      newValue: UInt256,
      currentValue: BigInt
  ): BigInt =
    if state.gas <= state.config.feeSchedule.G_callstipend then
      state.config.feeSchedule.G_callstipend + 1 // forces the OutOfGas branch in OpCode.execute
    else
      val originalValue = state.originalWorld.getStorage(state.ownAddress).load(offset)
      val writeCost: BigInt =
        if newValue.toBigInt != currentValue && currentValue == originalValue then AmsterdamGas.StorageWrite
        else BigInt(0)
      val accessCost = OpCode.storageAccessCost(state, state.ownAddress, StorageKey(offset.toBigInt))(
        _ => 0,
        _.G_cold_sload,
        _.G_warm_storage_read
      )
      accessCost + writeCost

  /** EIP-8038 SSTORE refund rules, evaluated on every SSTORE.
    *
    *   1. +STORAGE_CLEAR_REFUND when a slot non-zero at transaction start is cleared. 2. -STORAGE_CLEAR_REFUND when
    *      such a slot, already cleared in this transaction, is set non-zero again — so a clear-then-reset round trip is
    *      never net-profitable. 3. +STORAGE_WRITE when the slot is returned to its transaction-start value, undoing the
    *      write charge.
    *
    * All three are adjustments to the transaction refund counter and stay subject to the 20% cap.
    */
  private def amsterdamRefund[S <: Storage[S], W <: WorldStateProxy[W, S]](
      state: ProgramState[W, S],
      offset: UInt256,
      newValue: UInt256,
      currentValue: BigInt
  ): BigInt =
    val originalValue = state.originalWorld.getStorage(state.ownAddress).load(offset)
    val newIsZero = newValue.isZero
    val currentIsZero = currentValue == 0
    val originalIsZero = originalValue == 0

    val clearRefund: BigInt =
      if !originalIsZero && !currentIsZero && newIsZero then AmsterdamGas.StorageClearRefund
      else if !originalIsZero && currentIsZero && !newIsZero then -AmsterdamGas.StorageClearRefund
      else BigInt(0)

    val writeRefund: BigInt =
      if newValue.toBigInt == originalValue && newValue.toBigInt != currentValue then AmsterdamGas.StorageWrite
      else BigInt(0)

    clearRefund + writeRefund

  /** EIP-8037 state-gas component of SSTORE.
    *
    *   - charged when the write creates a slot: original 0, current 0, new non-zero.
    *   - refilled when a slot created in THIS transaction is cleared: original 0, current non-zero, new 0. That is a
    *     refill, not a refund — the state was never durably created, so it is netted out of `evm_state_gas_used`
    *     regardless of EIP-7778.
    *
    * Every other transition leaves the state dimension alone, including clearing a slot that pre-existed the
    * transaction: the leaf was already paid for.
    */
  override protected def stateGasDelta[S <: Storage[S], W <: WorldStateProxy[W, S]](
      state: ProgramState[W, S]
  ): BigInt =
    if !state.config.amsterdamEnabled then BigInt(0)
    else
      val (Seq(offset, newValue), _) = state.stack.pop(2)
      val currentValue = state.storage.load(offset)
      val originalValue = state.originalWorld.getStorage(state.ownAddress).load(offset)
      if originalValue == 0 && currentValue == 0 && !newValue.isZero then AmsterdamGas.GasStorageSet
      else if originalValue == 0 && currentValue != 0 && newValue.isZero then -AmsterdamGas.GasStorageSet
      else BigInt(0)

  private def amsterdamFreeVarGas[S <: Storage[S], W <: WorldStateProxy[W, S]](
      state: ProgramState[W, S],
      offset: UInt256,
      newValue: UInt256,
      currentValue: BigInt,
      eip2200Enabled: Boolean,
      eip1283Enabled: Boolean
  ): BigInt =
    val originalCharge: BigInt =
      if eip2200Enabled && state.gas <= state.config.feeSchedule.G_callstipend then
        state.config.feeSchedule.G_callstipend + 1 // Out of gas error
      else if eip2200Enabled || eip1283Enabled then
        if currentValue == newValue.toBigInt then // no-op
          state.config.feeSchedule.G_sload
        else
          val originalValue = state.originalWorld.getStorage(state.ownAddress).load(offset)
          if originalValue == currentValue then // fresh slot
            if originalValue == 0 then state.config.feeSchedule.G_sset
            else state.config.feeSchedule.G_sreset
          else
            // dirty slot
            state.config.feeSchedule.G_sload
      else if UInt256(currentValue).isZero && !newValue.isZero then state.config.feeSchedule.G_sset
      else state.config.feeSchedule.G_sreset

    originalCharge + OpCode.storageAccessCost(state, state.ownAddress, StorageKey(offset.toBigInt))(
      _ => 0,
      _.G_cold_sload,
      _ => 0
    )

  override protected def availableInContext[S <: Storage[S], W <: WorldStateProxy[W, S]]
      : ProgramState[W, S] => Boolean = !_.staticCtx

  // https://eips.ethereum.org/EIPS/eip-1283
  private def isEip1283Enabled(ethFork: EthFork): Boolean = ethFork == EthForks.Constantinople

  // https://eips.ethereum.org/EIPS/eip-2200
  private def isEip2200Enabled(etcFork: EtcFork, ethFork: EthFork): Boolean =
    ethFork >= EthForks.Istanbul || etcFork >= EtcForks.Phoenix

case object JUMP extends OpCode(0x56, 1, 0, _.G_mid) with ConstGas:
  // The transition is written once, in execAndSpendGas; exec is its zero-charge case.
  protected def exec[S <: Storage[S], W <: WorldStateProxy[W, S]](state: ProgramState[W, S]): ProgramState[W, S] =
    execAndSpendGas(state, BigInt(0))

  override protected def execAndSpendGas[S <: Storage[S], W <: WorldStateProxy[W, S]](
      state: ProgramState[W, S],
      gas: BigInt
  ): ProgramState[W, S] =
    val (pos, stack1) = state.stack.pop()
    val dest = pos.toInt // fail with InvalidJump if conversion to Int is lossy

    if pos == UInt256(dest) && state.program.validJumpDestinations.contains(dest) then
      state.jumpWithStack(stack1, dest, gas)
    else state.withError(InvalidJump(pos)).spendGas(gas)

case object JUMPI extends OpCode(0x57, 2, 0, _.G_high) with ConstGas:
  // The transition is written once, in execAndSpendGas; exec is its zero-charge case.
  protected def exec[S <: Storage[S], W <: WorldStateProxy[W, S]](state: ProgramState[W, S]): ProgramState[W, S] =
    execAndSpendGas(state, BigInt(0))

  override protected def execAndSpendGas[S <: Storage[S], W <: WorldStateProxy[W, S]](
      state: ProgramState[W, S],
      gas: BigInt
  ): ProgramState[W, S] =
    val (Seq(pos, cond), stack1) = state.stack.pop(2)
    val dest = pos.toInt // fail with InvalidJump if conversion to Int is lossy

    if cond.isZero then state.stepWithStack(stack1, 1, gas)
    else if pos == UInt256(dest) && state.program.validJumpDestinations.contains(dest) then
      state.jumpWithStack(stack1, dest, gas)
    else state.withError(InvalidJump(pos)).spendGas(gas)

case object PC extends ConstOp(0x58)(_.pc)

case object MSIZE extends ConstOp(0x59)(s => (UInt256.Size * wordsForBytes(s.memory.size)).toUInt256)

case object GAS extends ConstOp(0x5a)(state => (state.gas - state.config.feeSchedule.G_base).toUInt256)

case object JUMPDEST extends OpCode(0x5b, 0, 0, _.G_jumpdest) with ConstGas with StackOnlyOp:
  protected def nextStack[S <: Storage[S], W <: WorldStateProxy[W, S]](state: ProgramState[W, S]): Stack =
    state.stack

case object PUSH0 extends OpCode(0x5f, 0, 1, _.G_base) with ConstGas with StackOnlyOp:
  protected def nextStack[S <: Storage[S], W <: WorldStateProxy[W, S]](state: ProgramState[W, S]): Stack =
    state.stack.push(UInt256.Zero)

sealed abstract class PushOp(code: Int) extends OpCode(code, 0, 1, _.G_verylow) with ConstGas with StackOnlyOp:
  val i: Int = code - 0x60

  // the opcode byte plus its i + 1 immediate bytes
  override protected def pcIncrement: Int = i + 2

  protected def nextStack[S <: Storage[S], W <: WorldStateProxy[W, S]](state: ProgramState[W, S]): Stack =
    val n = i + 1
    val word = state.program.immediate(state.pc + 1, n)
    state.stack.push(word)

case object PUSH1 extends PushOp(0x60)
case object PUSH2 extends PushOp(0x61)
case object PUSH3 extends PushOp(0x62)
case object PUSH4 extends PushOp(0x63)
case object PUSH5 extends PushOp(0x64)
case object PUSH6 extends PushOp(0x65)
case object PUSH7 extends PushOp(0x66)
case object PUSH8 extends PushOp(0x67)
case object PUSH9 extends PushOp(0x68)
case object PUSH10 extends PushOp(0x69)
case object PUSH11 extends PushOp(0x6a)
case object PUSH12 extends PushOp(0x6b)
case object PUSH13 extends PushOp(0x6c)
case object PUSH14 extends PushOp(0x6d)
case object PUSH15 extends PushOp(0x6e)
case object PUSH16 extends PushOp(0x6f)
case object PUSH17 extends PushOp(0x70)
case object PUSH18 extends PushOp(0x71)
case object PUSH19 extends PushOp(0x72)
case object PUSH20 extends PushOp(0x73)
case object PUSH21 extends PushOp(0x74)
case object PUSH22 extends PushOp(0x75)
case object PUSH23 extends PushOp(0x76)
case object PUSH24 extends PushOp(0x77)
case object PUSH25 extends PushOp(0x78)
case object PUSH26 extends PushOp(0x79)
case object PUSH27 extends PushOp(0x7a)
case object PUSH28 extends PushOp(0x7b)
case object PUSH29 extends PushOp(0x7c)
case object PUSH30 extends PushOp(0x7d)
case object PUSH31 extends PushOp(0x7e)
case object PUSH32 extends PushOp(0x7f)

sealed abstract class DupOp private (code: Int, val i: Int)
    extends OpCode(code, i + 1, i + 2, _.G_verylow)
    with ConstGas
    with StackOnlyOp:
  def this(code: Int) = this(code, code - 0x80)

  protected def nextStack[S <: Storage[S], W <: WorldStateProxy[W, S]](state: ProgramState[W, S]): Stack =
    state.stack.dup(i)

case object DUP1 extends DupOp(0x80)
case object DUP2 extends DupOp(0x81)
case object DUP3 extends DupOp(0x82)
case object DUP4 extends DupOp(0x83)
case object DUP5 extends DupOp(0x84)
case object DUP6 extends DupOp(0x85)
case object DUP7 extends DupOp(0x86)
case object DUP8 extends DupOp(0x87)
case object DUP9 extends DupOp(0x88)
case object DUP10 extends DupOp(0x89)
case object DUP11 extends DupOp(0x8a)
case object DUP12 extends DupOp(0x8b)
case object DUP13 extends DupOp(0x8c)
case object DUP14 extends DupOp(0x8d)
case object DUP15 extends DupOp(0x8e)
case object DUP16 extends DupOp(0x8f)

sealed abstract class SwapOp(code: Int, val i: Int)
    extends OpCode(code, i + 2, i + 2, _.G_verylow)
    with ConstGas
    with StackOnlyOp:
  def this(code: Int) = this(code, code - 0x90)

  protected def nextStack[S <: Storage[S], W <: WorldStateProxy[W, S]](state: ProgramState[W, S]): Stack =
    state.stack.swap(i + 1)

case object SWAP1 extends SwapOp(0x90)
case object SWAP2 extends SwapOp(0x91)
case object SWAP3 extends SwapOp(0x92)
case object SWAP4 extends SwapOp(0x93)
case object SWAP5 extends SwapOp(0x94)
case object SWAP6 extends SwapOp(0x95)
case object SWAP7 extends SwapOp(0x96)
case object SWAP8 extends SwapOp(0x97)
case object SWAP9 extends SwapOp(0x98)
case object SWAP10 extends SwapOp(0x99)
case object SWAP11 extends SwapOp(0x9a)
case object SWAP12 extends SwapOp(0x9b)
case object SWAP13 extends SwapOp(0x9c)
case object SWAP14 extends SwapOp(0x9d)
case object SWAP15 extends SwapOp(0x9e)
case object SWAP16 extends SwapOp(0x9f)

sealed abstract class LogOp(code: Int, val i: Int) extends OpCode(code, i + 2, 0, _.G_log):
  def this(code: Int) = this(code, code - 0xa0)

  protected def exec[S <: Storage[S], W <: WorldStateProxy[W, S]](state: ProgramState[W, S]): ProgramState[W, S] =
    val (stack1Items, stack1) = state.stack.pop(delta: Int)
    val (offset +: size +: topics) = stack1Items: @unchecked
    val (data, memory) = state.memory.load(offset, size)
    val logEntry = TxLogEntry(state.env.ownerAddr, topics.map(_.bytes), data)

    state.withStack(stack1).withMemory(memory).withLog(logEntry).step()

  protected def varGas[S <: Storage[S], W <: WorldStateProxy[W, S]](state: ProgramState[W, S]): BigInt =
    val (stack1Items, _) = state.stack.pop(delta: Int)
    val (offset +: size +: _) = stack1Items: @unchecked
    val memCost = state.config.calcMemCost(state.memory.size, offset, size)
    val logCost = state.config.feeSchedule.G_logdata * size + i * state.config.feeSchedule.G_logtopic
    memCost + logCost

  override protected def availableInContext[S <: Storage[S], W <: WorldStateProxy[W, S]]
      : ProgramState[W, S] => Boolean = !_.staticCtx

case object LOG0 extends LogOp(0xa0)
case object LOG1 extends LogOp(0xa1)
case object LOG2 extends LogOp(0xa2)
case object LOG3 extends LogOp(0xa3)
case object LOG4 extends LogOp(0xa4)

object CreateOp:
  /** EIP-2681 nonce cap. A creation whose creator nonce would overflow it fails in VM.create (creatorNonceOverflowed)
    * on every fork, as in go-ethereum. Also one of EIP-7928's pre-checks inside the Amsterdam charge decision below:
    * such a creation never reads the destination and is never charged for a new account.
    */
  val MaxNonce: BigInt = BigInt(2).pow(64) - 1

abstract class CreateOp(code: Int, delta: Int) extends OpCode(code, delta, 1, _.G_create):
  // Override execute() to pass the pre-computed gas cost to exec() via state.opcodeGasCost,
  // avoiding the duplicate gas calculation that CreateOp.exec() previously performed (EC-243).
  override def execute[S <: Storage[S], W <: WorldStateProxy[W, S]](state: ProgramState[W, S]): ProgramState[W, S] =
    if !availableInContext(state) then state.withError(OpCodeNotAvailableInStaticContext(code.toByte))
    else if state.stack.size < delta then state.withError(StackUnderflow)
    else if state.stack.size - delta + alpha > state.stack.maxSize then state.withError(StackOverflow)
    else
      val gas: BigInt = calcGas(state)
      if gas > state.gas then state.copy(gas = 0).withError(OutOfGas)
      else exec(state.copy(opcodeGasCost = gas)).spendGas(gas)

  protected def exec[S <: Storage[S], W <: WorldStateProxy[W, S]](state0: ProgramState[W, S]): ProgramState[W, S] =
    val (Seq(endowment, inOffset, inSize), stack1) = state0.stack.pop(3)

    // EIP-3860: Check initcode size limit
    val maxInitCodeSize = state0.config.maxInitCodeSize
    if state0.config.eip3860Enabled && maxInitCodeSize.exists(max => inSize.toBigInt > max) then
      // Exceptional abort: initcode too large
      state0.withStack(stack1.push(UInt256.Zero)).withError(InitCodeSizeLimit).step()
    else

      // Gas cost already computed by OpCode.execute() and stored in state.opcodeGasCost
      val availableGas = state0.gas - state0.opcodeGasCost
      val (initCode, memory1) = state0.memory.load(inOffset, inSize)
      val world1 = state0.world.increaseNonce(state0.ownAddress)

      // ── EIP-8037 account-creation state charge, in EIP-7928's ordering ──────
      //
      // EIP-7928 defers reading the computed destination until creation has passed the checks that
      // precede any state access: sender balance for the endowment, nonce overflow, and the call-depth
      // limit. A creation failing those never reads the destination, and no state gas is charged.
      //
      // Without that ordering, EIP-8037's CREATE charge gives wrong answers for creations that fail their
      // pre-checks, which is why this lands here rather than with the rest of EIP-7928 in slice C.
      //
      // The address is derived from `world1` with exactly the call `VM.create` makes on the same world, so
      // the two agree by construction; and the charge is decided by EIP-161 EXISTENCE alone, independently
      // of the EIP-684 collision outcome — a colliding destination is existent, so it is never charged.
      val newAccountStateGas: BigInt =
        if !state0.config.amsterdamEnabled then BigInt(0)
        else
          val senderCanFund = endowment <= state0.ownBalance
          val depthOk = state0.env.callDepth + 1 <= EvmConfig.MaxCallDepth
          val nonceOk = world1.getAccount(state0.ownAddress).forall(_.nonce.toBigInt <= CreateOp.MaxNonce)
          if !(senderCanFund && depthOk && nonceOk) then BigInt(0)
          else
            val destination = this match
              case CREATE2 =>
                val (Seq(salt), _) = stack1.pop(1)
                world1.create2Address(state0.ownAddress, salt, initCode)
              case _ => world1.createAddress(state0.ownAddress)
            if world1.isAccountDead(destination) then AmsterdamGas.GasNewAccount else BigInt(0)

      val stateGasShortfall = (newAccountStateGas - state0.stateGasReservoir).max(0)
      if stateGasShortfall > availableGas then
        // EIP-8037: "a failed charge exceptionally halts the creating frame — exactly as for CALL*".
        state0.copy(gas = 0).withError(OutOfGas)
      else
        // The charge lands in the CREATING frame, before 63/64 of the remaining gas is forwarded, so the
        // portion drawn from gas_left reduces what the create frame receives.
        val state = state0.chargeStateGas(newAccountStateGas)
        val startGas = state.config.gasCap(state.gas - state.opcodeGasCost)

        val context: ProgramContext[W, S] = ProgramContext(
          callerAddr = state.env.ownerAddr,
          originAddr = state.env.originAddr,
          recipientAddr = None,
          gasPrice = state.env.gasPrice,
          startGas = startGas,
          inputData = initCode,
          value = endowment,
          endowment = endowment,
          doTransfer = true,
          blockHeader = state.env.blockHeader,
          callDepth = state.env.callDepth + 1,
          world = world1,
          initialAddressesToDelete = state.addressesToDelete,
          evmConfig = state.config,
          originalWorld = state.originalWorld,
          createdAddresses = state.createdAddresses,
          warmAddresses = state.accessedAddresses,
          warmStorage = state.accessedStorageKeys,
          transientStorage = state.transientStorage,
          precompileRelocations = state.env.precompileRelocations,
          blobVersionedHashes = state.env.blobVersionedHashes,
          traceTransfers = state.env.traceTransfers,
          // The reservoir passes to the child IN FULL; the 63/64 rule above applies to gas_left only.
          stateGasReservoir = state.stateGasReservoir,
          evmStateGasUsed = state.evmStateGasUsed
        )

        val ((result, newAddress), stack2) = this match
          case CREATE => (state.vm.create(context), stack1)
          case CREATE2 =>
            val (Seq(salt), stack2) = stack1.pop(1)
            (state.vm.create(context, Some(salt)), stack2)

        result.error match
          case Some(error) =>
            val world2 = if error == InvalidCall then state.world else world1
            val resultStack = stack2.push(UInt256.Zero)
            val returnData = if error == RevertOccurs then result.returnData else ByteString.empty
            state
              .spendGas(startGas - result.gasRemaining)
              // The create frame rolled itself back to the baseline it was handed; the account-creation
              // charge was made HERE, so it is refilled HERE, in LIFO order.
              .absorbFailedChildStateGas(result.stateGasReservoir, result.evmStateGasUsed)
              .refillStateGas(newAccountStateGas)
              .withWorld(world2)
              .withStack(resultStack)
              .withReturnData(returnData)
              .addAccessedAddresses(if error == InvalidCall then Set.empty else Set(newAddress))
              .step()

          case None =>
            val resultStack = stack2.push(newAddress.toUInt256)
            val internalTx =
              InternalTransaction(
                CREATE,
                context.callerAddr,
                None,
                context.startGas,
                context.inputData,
                context.endowment
              )

            state
              .spendGas(startGas - result.gasRemaining)
              .mergeSuccessfulChildStateGas(
                result.stateGasReservoir,
                result.evmStateGasUsed,
                result.stateGasFromGasLeft
              )
              .withWorld(result.world)
              .refundGas(result.gasRefund)
              .withStack(resultStack)
              .withAddressesToDelete(result.addressesToDelete)
              .withCreatedAddresses(result.createdAddresses)
              .withLogs(result.logs)
              .withMemory(memory1)
              .withInternalTxs(internalTx +: result.internalTxs)
              .withReturnData(ByteString.empty)
              .addAccessedStorageKeys(result.accessedStorageKeys)
              .addAccessedAddresses(result.accessedAddresses + newAddress)
              .copy(transientStorage = result.transientStorage)
              .step()

  override protected def availableInContext[S <: Storage[S], W <: WorldStateProxy[W, S]]
      : ProgramState[W, S] => Boolean = !_.staticCtx

case object CREATE extends CreateOp(0xf0, 3):
  protected def varGas[S <: Storage[S], W <: WorldStateProxy[W, S]](state: ProgramState[W, S]): BigInt =
    val (Seq(_, inOffset, inSize), _) = state.stack.pop(3)
    val memCost = state.config.calcMemCost(state.memory.size, inOffset, inSize)
    val initCodeGasCost: BigInt = if state.config.eip3860Enabled then
      val words = wordsForBytes(inSize)
      state.config.feeSchedule.G_initcode_word * words
    else BigInt(0)
    memCost + initCodeGasCost

case object CREATE2 extends CreateOp(0xf5, 4):
  protected def varGas[S <: Storage[S], W <: WorldStateProxy[W, S]](state: ProgramState[W, S]): BigInt =
    val (Seq(_, inOffset, inSize), _) = state.stack.pop(3)
    val memCost = state.config.calcMemCost(state.memory.size, inOffset, inSize)
    val hashCost = state.config.feeSchedule.G_sha3word * wordsForBytes(inSize)
    val initCodeGasCost: BigInt = if state.config.eip3860Enabled then
      val words = wordsForBytes(inSize)
      state.config.feeSchedule.G_initcode_word * words
    else BigInt(0)
    memCost + hashCost + initCodeGasCost

abstract class CallOp(code: Int, delta: Int, alpha: Int) extends OpCode(code, delta, alpha, _.G_zero):

  /** EIP-8037: the account-creation state charge a CALL incurs when it is about to bring a non-existent account into
    * being. Applied right before entering the child frame, and only for CALL — CALLCODE's recipient is the calling
    * account, which already exists, and DELEGATECALL/STATICCALL transfer nothing.
    *
    * This mirrors the condition the pre-Amsterdam schedule used for `G_newaccount`; under Amsterdam that fee-schedule
    * slot is 0 and the whole charge has moved to the state dimension, so the two never both fire.
    */
  private def newAccountStateGas[S <: Storage[S], W <: WorldStateProxy[W, S]](
      state: ProgramState[W, S],
      to: Address,
      endowment: UInt256
  ): BigInt =
    if state.config.amsterdamEnabled && this == CALL && endowment > UInt256.Zero && state.world.isAccountDead(to) then
      AmsterdamGas.GasNewAccount
    else BigInt(0)

  private def callTarget[S <: Storage[S], W <: WorldStateProxy[W, S]](state: ProgramState[W, S]): (Address, UInt256) =
    val (Seq(_, to, callValue, _, _, _, _), _) = getParams(state)
    val endowment = if this == DELEGATECALL || this == STATICCALL then UInt256.Zero else callValue
    (Address(to), endowment)

  override def execute[S <: Storage[S], W <: WorldStateProxy[W, S]](state0: ProgramState[W, S]): ProgramState[W, S] =
    if !availableInContext(state0) then state0.withError(OpCodeNotAvailableInStaticContext(code.toByte))
    else if state0.stack.size < delta then state0.withError(StackUnderflow)
    else if state0.stack.size - delta + alpha > state0.stack.maxSize then state0.withError(StackOverflow)
    else
      val executionCost: BigInt = calcGas(state0)
      if executionCost > state0.gas then state0.copy(gas = 0).withError(OutOfGas)
      else
        val (to, endowment) = callTarget(state0)
        val stateGas = newAccountStateGas(state0, to, endowment)
        if (stateGas - state0.stateGasReservoir).max(0) > state0.gas - executionCost then
          // EIP-8037: a failed state charge exceptionally halts the calling frame.
          state0.copy(gas = 0).withError(OutOfGas)
        else
          // Charge BEFORE the 63/64 split so the portion drawn from gas_left reduces what the child
          // receives. `calcGas` is recomputed on the charged state because `calcStartGas` inside `exec`
          // derives the forwarded gas from the same `state.gas` — computing the two from different states
          // is precisely how a gas-cap inconsistency gets introduced.
          val state = state0.chargeStateGas(stateGas)
          val gas: BigInt = calcGas(state)
          if gas > state.gas then state.copy(gas = 0).withError(OutOfGas)
          else exec(state).spendGas(gas)

  protected def exec[S <: Storage[S], W <: WorldStateProxy[W, S]](state: ProgramState[W, S]): ProgramState[W, S] =
    val (params @ Seq(_, to, callValue, inOffset, inSize, outOffset, outSize), stack1) = getParams(state)

    val toAddr = Address(to)
    val (inputData, mem1) = state.memory.load(inOffset, inSize)
    val (owner, caller, value, endowment, doTransfer, static) = this match
      case CALL =>
        (toAddr, state.ownAddress, callValue, callValue, true, state.staticCtx)

      case STATICCALL =>
        /** We return `doTransfer = true` for STATICCALL as it should `functions equivalently to a CALL` (spec) Note
          * that we won't transfer any founds during later transfer, as `value` and `endowment` are equal to Zero. One
          * thing that will change though is that both - recipient and sender addresses will be added to touched
          * accounts Set. And if empty they will be deleted at the end of transaction. Link to clarification about this
          * behaviour in yp: https://github.com/ethereum/EIPs/pull/214#issuecomment-288697580
          */
        (toAddr, state.ownAddress, UInt256.Zero, UInt256.Zero, true, true)

      case CALLCODE =>
        (state.ownAddress, state.ownAddress, callValue, callValue, false, state.staticCtx)

      case DELEGATECALL =>
        (state.ownAddress, state.env.callerAddr, callValue, UInt256.Zero, false, state.staticCtx)
    val startGas = calcStartGas(state, params, endowment)

    // EIP-7702: Warm the delegation target address if applicable (only once EIP-7702 is active)
    val stateWithDelegationWarming =
      if !state.config.eip7702Enabled then state
      else
        val code = state.world.getCode(toAddr)
        SetCodeTransaction.parseDelegation(code) match
          case Some(target) => state.addAccessedAddress(target)
          case None         => state

    val context: ProgramContext[W, S] = ProgramContext(
      callerAddr = caller,
      originAddr = state.env.originAddr,
      recipientAddr = Some(toAddr),
      gasPrice = state.env.gasPrice,
      startGas = startGas,
      inputData = inputData,
      value = value,
      endowment = endowment,
      doTransfer = doTransfer,
      blockHeader = state.env.blockHeader,
      callDepth = state.env.callDepth + 1,
      world = state.world,
      initialAddressesToDelete = state.addressesToDelete,
      evmConfig = state.config,
      staticCtx = static,
      originalWorld = state.originalWorld,
      createdAddresses = state.createdAddresses,
      warmAddresses = stateWithDelegationWarming.accessedAddresses,
      warmStorage = state.accessedStorageKeys,
      transientStorage = state.transientStorage,
      precompileRelocations = state.env.precompileRelocations,
      blobVersionedHashes = state.env.blobVersionedHashes,
      traceTransfers = state.env.traceTransfers,
      // The reservoir passes to the child IN FULL; the 63/64 rule applies to gas_left only.
      stateGasReservoir = state.stateGasReservoir,
      evmStateGasUsed = state.evmStateGasUsed
    )

    // Recomputed rather than threaded: the world is unchanged since `execute` decided it, so this is the
    // same number, and recomputing avoids widening `exec`'s signature on a hot path.
    val chargedNewAccountStateGas = newAccountStateGas(state, toAddr, endowment)

    val result = state.vm.call(context, owner)

    lazy val sizeCap = outSize.min(result.returnData.size).toInt
    lazy val output = result.returnData.take(sizeCap)
    lazy val mem2 = mem1.store(outOffset, output).expand(outOffset, outSize)

    result.error match
      case Some(error) =>
        val stack2 = stack1.push(UInt256.Zero)
        val world1 = state.world.keepPrecompileTouched(result.world)
        val gasAdjustment =
          if error == InvalidCall then -startGas else if error == RevertOccurs then -result.gasRemaining else BigInt(0)
        val memoryAdjustment = if error == RevertOccurs then mem2 else mem1.expand(outOffset, outSize)

        // EIP-7702: the delegation target was warmed by this CALL's gas charge, in the CALLER's frame (go-ethereum
        // `makeCallVariantGasCallEIP7702` adds it before `evm.Call` takes its snapshot), so a failing child does not
        // un-warm it. Building on `state` here dropped it, and the next call to the same pointer paid 2,600 again
        // (EEST `test_pointer_reverts[*first_revert_True*]`: +2,500).
        stateWithDelegationWarming
          .withStack(stack2)
          .withMemory(memoryAdjustment)
          .withWorld(world1)
          .spendGas(gasAdjustment)
          // EIP-8037: when the child reverts or halts exceptionally the account creation is undone, so the
          // charge made here is refilled here, in LIFO order.
          .absorbFailedChildStateGas(result.stateGasReservoir, result.evmStateGasUsed)
          .refillStateGas(chargedNewAccountStateGas)
          .withReturnData(result.returnData)
          .addAccessedAddress(toAddr)
          .step()

      case None =>
        val stack2 = stack1.push(UInt256.One)
        val internalTx = internalTransaction(state.env, to, startGas, inputData, endowment)

        // Emit synthetic Transfer log for traceTransfers (ETH value transfers)
        val transferLogs = if state.env.traceTransfers && endowment > UInt256.Zero && doTransfer then
          val transferTopic = ByteString(
            com.chipprbots.ethereum.crypto.kec256("Transfer(address,address,uint256)".getBytes)
          )
          val ethAddr = com.chipprbots.ethereum.domain.Address("0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee")
          val fromPadded = ByteString(new Array[Byte](12) ++ caller.bytes.padTo(20, 0.toByte).takeRight(20))
          val toPadded = ByteString(new Array[Byte](12) ++ toAddr.bytes.padTo(20, 0.toByte).takeRight(20))
          val valueBytes = endowment.bytes
          val data = ByteString(new Array[Byte](32 - valueBytes.length) ++ valueBytes.toArray)
          Seq(com.chipprbots.ethereum.domain.TxLogEntry(ethAddr, Seq(transferTopic, fromPadded, toPadded), data))
        else Seq.empty

        state
          .spendGas(-result.gasRemaining)
          .mergeSuccessfulChildStateGas(
            result.stateGasReservoir,
            result.evmStateGasUsed,
            result.stateGasFromGasLeft
          )
          .refundGas(result.gasRefund)
          .withStack(stack2)
          .withMemory(mem2)
          .withWorld(result.world)
          .withAddressesToDelete(result.addressesToDelete)
          .withCreatedAddresses(result.createdAddresses)
          .withInternalTxs(internalTx +: result.internalTxs)
          .withLogs(result.logs ++ transferLogs)
          .withReturnData(result.returnData)
          .addAccessedStorageKeys(result.accessedStorageKeys)
          .addAccessedAddresses(result.accessedAddresses + toAddr)
          .copy(transientStorage = result.transientStorage)
          .step()

  protected def internalTransaction(
      env: ExecEnv,
      callee: UInt256,
      startGas: BigInt,
      inputData: ByteString,
      endowment: UInt256
  ): InternalTransaction =
    val from = env.ownerAddr
    val to = if this == CALL then Address(callee) else env.ownerAddr
    InternalTransaction(this, from, Some(to), startGas, inputData, endowment)

  protected def varGas[S <: Storage[S], W <: WorldStateProxy[W, S]](state: ProgramState[W, S]): BigInt =
    val (Seq(gas, to, callValue, inOffset, inSize, outOffset, outSize), _) = getParams(state)
    val endowment = if this == DELEGATECALL || this == STATICCALL then UInt256.Zero else callValue

    val memCost = calcMemCost(state, inOffset, inSize, outOffset, outSize)

    val delegationCost: BigInt = delegationAccessCost(state, Address(to))

    val gExtra: BigInt = gasExtra(state, endowment, Address(to))
    val gCap: BigInt = gasCap(state, gas, gExtra + memCost + delegationCost)
    memCost + gCap + gExtra + delegationCost

  protected def calcMemCost[S <: Storage[S], W <: WorldStateProxy[W, S]](
      state: ProgramState[W, S],
      inOffset: UInt256,
      inSize: UInt256,
      outOffset: UInt256,
      outSize: UInt256
  ): BigInt =

    val memCostIn = state.config.calcMemCost(state.memory.size, inOffset, inSize)
    val memCostOut = state.config.calcMemCost(state.memory.size, outOffset, outSize)
    memCostIn.max(memCostOut)

  protected def getParams[S <: Storage[S], W <: WorldStateProxy[W, S]](
      state: ProgramState[W, S]
  ): (Seq[UInt256], Stack) =
    val (Seq(gas, to), stack1) = state.stack.pop(2)
    val (value, stack2) = if this == DELEGATECALL || this == STATICCALL then (state.env.value, stack1) else stack1.pop()
    val (Seq(inOffset, inSize, outOffset, outSize), stack3) = stack2.pop(4)
    Seq(gas, to, value, inOffset, inSize, outOffset, outSize) -> stack3

  protected def calcStartGas[S <: Storage[S], W <: WorldStateProxy[W, S]](
      state: ProgramState[W, S],
      params: Seq[UInt256],
      endowment: UInt256
  ): BigInt =
    val Seq(gas, to, _, inOffset, inSize, outOffset, outSize) = params
    val memCost = calcMemCost(state, inOffset, inSize, outOffset, outSize)
    val gExtra = gasExtra(state, endowment, Address(to))
    // The delegation charge must reduce the gas the 63/64 rule splits, exactly as it does in `varGas`: go-ethereum
    // `makeCallVariantGasCallEIP7702` spends it from `contract.Gas` BEFORE `callGas` runs. Leaving it out here
    // forwarded more gas to the child than the caller was charged, and the child's unused gas then came back as a
    // net credit (EEST `test_nonce_validity[nonce=2**64-2]`: 2,559 short).
    val gCap = gasCap(state, gas, gExtra + memCost + delegationAccessCost(state, Address(to)))
    if endowment.isZero then gCap else gCap + state.config.feeSchedule.G_callstipend

  /** EIP-7702: the extra access charge for calling an account whose code is a delegation indicator — the delegation
    * TARGET's EIP-2929 access cost, warm (100) or cold (2,600). go-ethereum `makeCallVariantGasCallEIP7702` charges
    * `WarmStorageReadCostEIP2929` when the target is already in the access list, not zero; a precompile target is
    * always warm, so it always costs 100 (EEST `test_set_code_to_precompile`, `test_account_warming`).
    */
  protected def delegationAccessCost[S <: Storage[S], W <: WorldStateProxy[W, S]](
      state: ProgramState[W, S],
      addr: Address
  ): BigInt =
    // Before EIP-7702 (ETH pre-Prague, ETC pre-Olympia) 0xef0100-prefixed code is not a designator and costs nothing.
    if !state.config.eip7702Enabled then BigInt(0)
    else
      SetCodeTransaction.parseDelegation(state.world.getCode(addr)) match
        case Some(target) =>
          // `addr` itself is warmed (and charged) before its delegation is inspected, so a self-delegation's target
          // is always warm here (EEST `test_self_set_code_cost[pre_authorized_True]`: 2,700, not 5,200).
          if target == addr || state.accessedAddresses.contains(target) then
            state.config.feeSchedule.G_warm_storage_read
          else state.config.feeSchedule.G_cold_account_access
        case None => BigInt(0)

  private def gasCap[S <: Storage[S], W <: WorldStateProxy[W, S]](
      state: ProgramState[W, S],
      g: BigInt,
      consumedGas: BigInt
  ): BigInt =
    if state.config.subGasCapDivisor.isDefined && state.gas >= consumedGas then
      g.min(state.config.gasCap(state.gas - consumedGas))
    else g

  private def gasExtra[S <: Storage[S], W <: WorldStateProxy[W, S]](
      state: ProgramState[W, S],
      endowment: UInt256,
      to: Address
  ): BigInt =

    val isValueTransfer = endowment > 0

    def postEip161CostCondition: Boolean =
      state.world.isAccountDead(to) && this == CALL && isValueTransfer

    def preEip161CostCondition: Boolean =
      !state.world.accountExists(to) && this == CALL

    val c_new: BigInt =
      if state.config.noEmptyAccounts && postEip161CostCondition || !state.config.noEmptyAccounts && preEip161CostCondition
      then state.config.feeSchedule.G_newaccount
      else 0

    val c_xfer: BigInt = if endowment.isZero then 0 else state.config.feeSchedule.G_callvalue

    val callCost: BigInt = OpCode.addressAccessCost(state, to)(_.G_call, _.G_cold_account_access, _.G_warm_storage_read)
    callCost + c_xfer + c_new

case object CALL extends CallOp(0xf1, 7, 1):
  override protected def availableInContext[S <: Storage[S], W <: WorldStateProxy[W, S]]
      : ProgramState[W, S] => Boolean = state =>
    !state.staticCtx || {
      val (Seq(_, _, callValue), _) = state.stack.pop(3)
      callValue.isZero
    }
case object STATICCALL extends CallOp(0xfa, 6, 1)
case object CALLCODE extends CallOp(0xf2, 7, 1)
case object DELEGATECALL extends CallOp(0xf4, 6, 1)

case object RETURN extends OpCode(0xf3, 2, 0, _.G_zero):
  protected def exec[S <: Storage[S], W <: WorldStateProxy[W, S]](state: ProgramState[W, S]): ProgramState[W, S] =
    val (Seq(offset, size), stack1) = state.stack.pop(2)
    val (ret, mem1) = state.memory.load(offset, size)
    state.withStack(stack1).withReturnData(ret).withMemory(mem1).halt

  protected def varGas[S <: Storage[S], W <: WorldStateProxy[W, S]](state: ProgramState[W, S]): BigInt =
    val (Seq(offset, size), _) = state.stack.pop(2)
    state.config.calcMemCost(state.memory.size, offset, size)

case object REVERT extends OpCode(0xfd, 2, 0, _.G_zero):
  protected def exec[S <: Storage[S], W <: WorldStateProxy[W, S]](state: ProgramState[W, S]): ProgramState[W, S] =
    val (Seq(memory_offset, memory_length), stack1) = state.stack.pop(2)
    val (ret, mem1) = state.memory.load(memory_offset, memory_length)
    state.withStack(stack1).withMemory(mem1).revert(ret)

  protected def varGas[S <: Storage[S], W <: WorldStateProxy[W, S]](state: ProgramState[W, S]): BigInt =
    val (Seq(memory_offset, memory_length), _) = state.stack.pop(2)
    state.config.calcMemCost(state.memory.size, memory_offset, memory_length)

case object INVALID extends OpCode(0xfe, 0, 0, _.G_zero) with ConstGas:
  protected def exec[S <: Storage[S], W <: WorldStateProxy[W, S]](state: ProgramState[W, S]): ProgramState[W, S] =
    state.withError(InvalidOpCode(code))

/** SELFDESTRUCT opcode (0xff)
  *
  * @deprecated
  *   As of EIP-6049 (Spiral fork), SELFDESTRUCT is officially deprecated. The behavior remains unchanged for now, but
  *   developers should avoid using this opcode in new contracts as future EIPs may change or remove its functionality.
  *
  * See: https://eips.ethereum.org/EIPS/eip-6049 Activated with Spiral fork (ECIP-1109):
  *   - Block 19,250,000 on Ethereum Classic mainnet
  *   - Block 9,957,000 on Mordor testnet
  *
  * Note: EIP-3529 (Mystique fork) already removed the gas refund for SELFDESTRUCT, setting R_selfdestruct to 0.
  * EIP-6049 does not change behavior further.
  */
case object SELFDESTRUCT extends OpCode(0xff, 1, 0, _.G_selfdestruct):
  protected def exec[S <: Storage[S], W <: WorldStateProxy[W, S]](state: ProgramState[W, S]): ProgramState[W, S] =
    val (refund, stack1) = state.stack.pop()
    val refundAddr: Address = Address(refund)
    val gasRefund: BigInt =
      if state.addressesToDelete contains state.ownAddress then 0 else state.config.feeSchedule.R_selfdestruct

    // EIP-6780: Post-Cancun (ETH) / post-Olympia (ETC), SELFDESTRUCT only destroys contracts created in the same
    // transaction. Pre-existing contracts only have their balance transferred.
    //
    // `createdAddresses` is authoritative: it is populated by VM.create for the address it is creating and propagated
    // down into sub-frames. The `originalWorld` fallback is retained for callers that construct a ProgramContext by
    // hand (and for EIP-7702-delegated code running on an authority absent at tx start); it is NOT sufficient on its
    // own, because VM.create sets the create frame's `originalWorld` to
    // `originalWorld.initialiseAccount(contractAddr)` — required by EIP-1283/2200 original-value lookups — which makes
    // the newly created address *exist* there. Relying on it alone left a CREATE'd + self-destructed child in the trie
    // with nonce=1 (hive devp2p block 8, hivechain `tx-calltree`): identical gas, divergent state root.
    // Reference: go-ethereum StateDB.SelfDestruct6780 keys off the `newContract` flag set by CreateContract.
    val createdInThisTx =
      state.createdAddresses.contains(state.ownAddress) || !state.originalWorld.accountExists(state.ownAddress)
    val shouldDelete = !state.config.eip6780Enabled || createdInThisTx

    // Self-transfer ether handling differs by deletion outcome:
    //  - shouldDelete: account is wiped at end-of-tx (deleteAccounts), so the balance is destroyed
    //    regardless. Pre-EIP-6780 always reached here; matched the historical "transfer-to-self
    //    burns ether" Subtlety. We zero now so intermediate reads (BALANCE within the same tx)
    //    see 0, matching geth/besu.
    //  - !shouldDelete (Cancun+ pre-existing contract): balance must be preserved. The "transfer
    //    self → self" is a no-op in any rational accounting. Required by bcValidBlockTest/
    //    reentrencySuicide, which calls SELFDESTRUCT(self) and expects the balance to remain.
    val world =
      if state.ownAddress == refundAddr then
        if shouldDelete then state.world.removeAllEther(state.ownAddress)
        else state.world.touchAccounts(state.ownAddress)
      else state.world.transfer(state.ownAddress, refundAddr, state.ownBalance)

    // Emit synthetic Transfer log for traceTransfers (SELFDESTRUCT value transfers)
    val transferLogs =
      if state.env.traceTransfers && state.ownBalance > UInt256.Zero && state.ownAddress != refundAddr then
        val transferTopic = ByteString(
          com.chipprbots.ethereum.crypto.kec256("Transfer(address,address,uint256)".getBytes)
        )
        val ethAddr = com.chipprbots.ethereum.domain.Address("0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee")
        val fromPadded = ByteString(new Array[Byte](12) ++ state.ownAddress.bytes.toArray)
        val toPadded = ByteString(new Array[Byte](12) ++ refundAddr.bytes.toArray)
        val valueBytes = state.ownBalance.bytes
        val data = ByteString(new Array[Byte](32 - valueBytes.length) ++ valueBytes.toArray)
        Seq(com.chipprbots.ethereum.domain.TxLogEntry(ethAddr, Seq(transferTopic, fromPadded, toPadded), data))
      else Seq.empty

    // EIP-7708: a non-zero-value-transferring SELFDESTRUCT to a DIFFERENT account emits the protocol
    // transfer log, at the time the transfer executes. Distinct from the `traceTransfers` log above,
    // which is a simulation-only affordance emitted from 0xeee… and is not consensus data.
    val eip7708Logs =
      if state.config.amsterdamEnabled && state.ownBalance > UInt256.Zero && state.ownAddress != refundAddr then
        Seq(AmsterdamGas.transferLog(state.ownAddress, refundAddr, state.ownBalance))
      else Seq.empty

    val state1 = state
      .withWorld(world)
      .refundGas(gasRefund)
      .addAccessedAddress(refundAddr)
      .withStack(stack1)
      .withReturnData(ByteString.empty)
      .withLogs(transferLogs ++ eip7708Logs)

    if shouldDelete then state1.withAddressToDelete(state.ownAddress).halt
    else state1.halt

  protected def varGas[S <: Storage[S], W <: WorldStateProxy[W, S]](state: ProgramState[W, S]): BigInt =
    val isValueTransfer = state.ownBalance > 0

    val (refundAddr, _) = state.stack.pop()
    val refundAddress = Address(refundAddr)

    def postEip161CostCondition: Boolean =
      state.config.chargeSelfDestructForNewAccount &&
        isValueTransfer &&
        state.world.isAccountDead(refundAddress)

    def preEip161CostCondition: Boolean =
      state.config.chargeSelfDestructForNewAccount && !state.world.accountExists(refundAddress)

    val baseCharge: BigInt =
      if state.config.noEmptyAccounts && postEip161CostCondition || !state.config.noEmptyAccounts && preEip161CostCondition
      then state.config.feeSchedule.G_newaccount
      else 0

    // EIP-8038: a positive balance sent to a dead account additionally pays ACCOUNT_WRITE — the write
    // component of creating the beneficiary's leaf. Under Amsterdam `G_newaccount` above is 0, because its
    // state-creation component moved wholly to the state dimension (see `stateGasDelta`).
    val accountWriteCharge: BigInt =
      if state.config.amsterdamEnabled && isValueTransfer && state.world.isAccountDead(refundAddress) then
        AmsterdamGas.AccountWrite
      else BigInt(0)

    // Note: SELFDESTRUCT does not charge a WARM_STORAGE_READ_COST in case the recipient is already warm
    val addressAccessCharge = OpCode.addressAccessCost(state, refundAddress)(_ => 0, _.G_cold_account_access, _ => 0)
    baseCharge + accountWriteCharge + addressAccessCharge

  /** EIP-8037: SELFDESTRUCT charges STATE_BYTES_PER_NEW_ACCOUNT x CPSB when the balance transfer creates a new account
    * leaf.
    *
    * There is deliberately no *refill* here. Destroying an account created in the same transaction produces no state
    * growth, but EIP-8037 is explicit that it produces no state-gas refill either — and a pre-existing account is not
    * removed at all under EIP-6780, so there is nothing to credit back. That non-refill is what lets block 41's 183,600
    * stand even though its CREATE'd child selfdestructs in the same transaction.
    */
  override protected def stateGasDelta[S <: Storage[S], W <: WorldStateProxy[W, S]](
      state: ProgramState[W, S]
  ): BigInt =
    if !state.config.amsterdamEnabled then BigInt(0)
    else
      val (refundAddr, _) = state.stack.pop()
      if state.ownBalance > UInt256.Zero && state.world.isAccountDead(Address(refundAddr)) then
        AmsterdamGas.GasNewAccount
      else BigInt(0)

  override protected def availableInContext[S <: Storage[S], W <: WorldStateProxy[W, S]]
      : ProgramState[W, S] => Boolean = !_.staticCtx

case object CHAINID extends ConstOp(0x46)(state => UInt256(state.env.evmConfig.blockchainConfig.chainId.value))

case object SELFBALANCE extends OpCode(0x47, 0, 1, _.G_low) with ConstGas:
  protected def exec[S <: Storage[S], W <: WorldStateProxy[W, S]](state: ProgramState[W, S]): ProgramState[W, S] =
    val stack2 = state.stack.push(state.ownBalance)
    state.withStack(stack2).step()

/** EIP-3198: BASEFEE opcode — pushes the block's baseFee onto the stack. Returns 0 for pre-Olympia blocks where baseFee
  * is not set.
  */
case object BASEFEE extends OpCode(0x48, 0, 1, _.G_base) with ConstGas:
  protected def exec[S <: Storage[S], W <: WorldStateProxy[W, S]](state: ProgramState[W, S]): ProgramState[W, S] =
    val baseFee = state.env.blockHeader.baseFee.getOrElse(BigInt(0))
    val stack1 = state.stack.push(UInt256(baseFee))
    state.withStack(stack1).step()

/** EIP-4844: BLOBHASH opcode — returns the versioned hash at the given index from the current transaction's
  * blob_versioned_hashes, or 0 if index is out of bounds.
  */
case object BLOBHASH extends OpCode(0x49, 1, 1, _.G_verylow) with ConstGas:
  protected def exec[S <: Storage[S], W <: WorldStateProxy[W, S]](state: ProgramState[W, S]): ProgramState[W, S] =
    val (index, stack1) = state.stack.pop()
    val hashes = state.env.blobVersionedHashes
    val result =
      if index.toBigInt < hashes.size && index.toBigInt >= 0 then UInt256(hashes(index.toInt))
      else UInt256.Zero
    state.withStack(stack1.push(result)).step()

/** EIP-7516: BLOBBASEFEE opcode — returns the current block's blob base fee per EIP-4844 CalcBlobFee. Fork-aware update
  * fraction matching BlobGasUtils.updateFractionFor: BPO2 (11684671) → BPO1 (8346193) → Prague (5007716) → Cancun
  * (3338477). Formula inlined from BlobGasUtils.fakeExponential to avoid a vm→consensus.engine import cycle.
  */
case object BLOBBASEFEE extends OpCode(0x4a, 0, 1, _.G_base) with ConstGas:
  private val MinBlobBaseFee: BigInt = BigInt(1)
  private val CancunUpdateFraction: BigInt = BigInt(3338477)
  private val PragueUpdateFraction: BigInt = BigInt(5007716)
  private val Bpo1UpdateFraction: BigInt = BigInt(8346193)
  private val Bpo2UpdateFraction: BigInt = BigInt(11684671)

  // EIP-4844 fake_exponential: factor * e^(numerator/denominator) via Taylor series.
  private def calcBlobFee(excessBlobGas: BigInt, updateFraction: BigInt): BigInt =
    var i = BigInt(1)
    var output = BigInt(0)
    var accum = MinBlobBaseFee * updateFraction
    while accum > 0 do
      output += accum
      accum = (accum * excessBlobGas) / (updateFraction * i)
      i += 1
    output / updateFraction

  protected def exec[S <: Storage[S], W <: WorldStateProxy[W, S]](state: ProgramState[W, S]): ProgramState[W, S] =
    val timestamp = state.env.blockHeader.unixTimestamp
    val bcConfig = state.env.evmConfig.blockchainConfig
    val excessBlobGas = state.env.blockHeader.excessBlobGas.getOrElse(BigInt(0))
    val fraction =
      if bcConfig.isBpo2Timestamp(timestamp) then Bpo2UpdateFraction
      else if bcConfig.isBpo1Timestamp(timestamp) then Bpo1UpdateFraction
      else if bcConfig.isPragueTimestamp(timestamp) then PragueUpdateFraction
      else CancunUpdateFraction
    val fee = calcBlobFee(excessBlobGas, fraction)
    state.withStack(state.stack.push(UInt256(fee))).step()

/** EIP-1153: TLOAD — load from transient storage. Gas: G_warm_storage_read (100). */
case object TLOAD extends OpCode(0x5c, 1, 1, _.G_warm_storage_read) with ConstGas:
  protected def exec[S <: Storage[S], W <: WorldStateProxy[W, S]](state: ProgramState[W, S]): ProgramState[W, S] =
    val (offset, stack1) = state.stack.pop()
    val value = state.transientStorage.getOrElse((state.ownAddress, StorageKey(offset.toBigInt)), BigInt(0))
    val stack2 = stack1.push(UInt256(value))
    state.withStack(stack2).step()

/** EIP-1153: TSTORE — store to transient storage. Gas: G_warm_storage_read (100). Not available in static context.
  */
case object TSTORE extends OpCode(0x5d, 2, 0, _.G_warm_storage_read) with ConstGas:
  protected def exec[S <: Storage[S], W <: WorldStateProxy[W, S]](state: ProgramState[W, S]): ProgramState[W, S] =
    val (Seq(offset, value), stack1) = state.stack.pop(2)
    val updatedTransient = state.transientStorage.updated(
      (state.ownAddress, StorageKey(offset.toBigInt)),
      value.toBigInt
    )
    state.copy(transientStorage = updatedTransient).withStack(stack1).step()

  override protected def availableInContext[S <: Storage[S], W <: WorldStateProxy[W, S]]
      : ProgramState[W, S] => Boolean = !_.staticCtx

/** EIP-5656: MCOPY — memory-to-memory copy with proper overlap handling. Gas: G_verylow (3) + 3 * ceil(size/32) +
  * memory expansion cost.
  */
case object MCOPY extends OpCode(0x5e, 3, 0, _.G_verylow):
  protected def exec[S <: Storage[S], W <: WorldStateProxy[W, S]](state: ProgramState[W, S]): ProgramState[W, S] =
    val (Seq(dst, src, size), stack1) = state.stack.pop(3)
    if size.isZero then state.withStack(stack1).step()
    else
      // Load source data, then store at destination — Memory handles expansion
      val (data, mem1) = state.memory.load(src, size)
      val mem2 = mem1.store(dst, data)
      state.withStack(stack1).withMemory(mem2).step()

  protected def varGas[S <: Storage[S], W <: WorldStateProxy[W, S]](state: ProgramState[W, S]): BigInt =
    val (Seq(dst, src, size), _) = state.stack.pop(3)
    if size.isZero then 0
    else
      // Word copy cost: G_copy (3) * ceil(size / 32)
      val copyCost = state.config.feeSchedule.G_copy * wordsForBytes(size)
      // Memory expansion to max(src, dst) + size, computed in unbounded BigInt. `src + size` on UInt256 wraps
      // modulo 2^256: MCOPY(dst = 0, src = 2^256 - 1, size = 1) then priced as an expansion to 1 byte, the frame
      // did not run out of gas, and `memory.load` was handed an offset no Int can hold (EEST
      // `MCOPY_memory_expansion_cost[huge_*]`). go-ethereum: `memoryMcopy` → calcMemSize64(max(dst, src), size),
      // whose overflow is ErrGasUintOverflow — an out-of-gas halt, which `calcMemCost`'s MaxMemory cap reproduces.
      val srcEnd = src.toBigInt + size.toBigInt
      val dstEnd = dst.toBigInt + size.toBigInt
      val maxEnd = srcEnd.max(dstEnd)
      val memCost = state.config.calcMemCost(state.memory.size, BigInt(0), maxEnd)
      copyCost + memCost
