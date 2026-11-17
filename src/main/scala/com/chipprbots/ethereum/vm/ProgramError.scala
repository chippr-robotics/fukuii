package com.chipprbots.ethereum.vm

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.domain.UInt256

/** Marker trait for errors that may occur during program execution.
  *
  * Every error but [[RevertOccurs]] consumes the frame's gas (`useWholeGas`). Consensus code tells the errors apart in
  * two ways only: by `useWholeGas` (`ProgramState.toResult`, `BlockPreparator.calcTotalGasToRefund`) and by identity
  * against [[InvalidCall]] and [[RevertOccurs]] (`CreateOp`, `CallOp`). So those two, and [[OutOfGas]] and
  * [[InvalidCode]], which callers compare the same way, MUST stay case objects: without `strictEquality`, `error == X`
  * against a case class's companion still compiles, and is always false. Only an error no consensus code names may
  * carry fields, for the RPC's message (`jsonrpc.CallErrors.vmErrorMessage`).
  */
sealed trait ProgramError:
  val useWholeGas = true
case class InvalidOpCode(code: Byte) extends ProgramError:
  override def toString: String =
    f"${getClass.getSimpleName}(0x${code.toInt & 0xff}%02x)"
case class OpCodeNotAvailableInStaticContext(code: Byte) extends ProgramError:
  override def toString: String =
    f"${getClass.getSimpleName}(0x${code.toInt & 0xff}%02x)"
case object OutOfGas extends ProgramError
case class InvalidJump(dest: UInt256) extends ProgramError:
  override def toString: String =
    f"${getClass.getSimpleName}(${dest.toHexString})"

/** A stack too shallow or too deep for an instruction, with go-ethereum's counts (core/vm/errors.go
  * `ErrStackUnderflow{stackLen, required}`, `ErrStackOverflow{stackLen, limit}`). Only the RPC message reads them.
  *
  * `toString` stays the bare name: the tracers print it (`StructLogTracer`, the `onTxEnd` / `onCallExit` hooks) and the
  * trace goldens pin it (`TraceGoldenSpec`).
  */
sealed trait StackError extends ProgramError

/** @param stackLen
  *   the stack's depth when the instruction was tried
  * @param required
  *   the depth it needs: the items it pops (`OpCode.delta`, go-ethereum's `minStack`), or for EIP-8024's DUPN, SWAPN
  *   and EXCHANGE the depth their immediate reaches
  */
case class StackUnderflow(stackLen: Int, required: Int) extends StackError:
  override def toString: String = "StackUnderflow"

/** @param stackLen
  *   the stack's depth when the instruction was tried
  * @param limit
  *   the deepest stack it can run on: `maxSize + delta - alpha`, go-ethereum's `maxStack(pops, push)`
  */
case class StackOverflow(stackLen: Int, limit: Int) extends StackError:
  override def toString: String = "StackOverflow"

case object InvalidCall extends ProgramError

/** A precompile that rejected its input once its price was paid: go-ethereum's `RunPrecompiledContract` returning the
  * error of the precompile's `Run` (core/vm/contracts.go). It consumes the frame's gas like every error but a revert.
  *
  * It carries the contract and its input only so that the RPC can say why, in go-ethereum's words:
  * `jsonrpc.CallErrors.vmErrorMessage` asks [[PrecompiledContracts.PrecompiledContract.failureReason]]. Nothing else
  * reads them, and block import never computes a reason (#1727 PR 7b).
  *
  * `toString` stays the bare name: the tracers print it.
  */
case class PreCompiledContractFail(contract: PrecompiledContracts.PrecompiledContract, input: ByteString)
    extends ProgramError:
  override def toString: String = "PreCompiledContractFail"

case object RevertOccurs extends ProgramError:
  override val useWholeGas: Boolean = false

case object ReturnDataOverflow extends ProgramError

case object InvalidCode extends ProgramError

case object InitCodeSizeLimit extends ProgramError

/** A creation whose returned code is longer than the code size limit (EIP-170; on ETC from Atlantis). go-ethereum
  * `ErrMaxCodeSizeExceeded`. The frame halts exceptionally as for any other error here (`VM.saveNewContract`); it was
  * reported as [[OutOfGas]] before it had a name of its own.
  */
case object MaxCodeSizeExceeded extends ProgramError

/** A creation that cannot pay its code deposit, from Homestead on (`EvmConfig.exceptionalFailedCodeDeposit`).
  * go-ethereum `ErrCodeStoreOutOfGas`. The frame halts exceptionally as for any other error here
  * (`VM.saveNewContract`); it was reported as [[OutOfGas]] before it had a name of its own.
  *
  * Before Homestead the same shortfall is NOT an error: the creation succeeds with no code, and only
  * `ProgramResult.codeDepositShortfall` records it.
  */
case object CodeStoreOutOfGas extends ProgramError
