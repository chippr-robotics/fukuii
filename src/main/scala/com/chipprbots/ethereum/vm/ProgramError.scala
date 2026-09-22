package com.chipprbots.ethereum.vm

import com.chipprbots.ethereum.domain.UInt256

/** Marker trait for errors that may occur during program execution
  */
sealed trait ProgramError:
  val useWholeGas = true

  /** Whether a top-level transaction that ends in this error should roll its world state back to the
    * pre-execution checkpoint. True for every error except [[CodeStoreOutOfGasPreHomestead]] — see that case for
    * why.
    */
  val rollbackOnError: Boolean = true
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

sealed trait StackError extends ProgramError
case object StackOverflow extends StackError
case object StackUnderflow extends StackError

case object InvalidCall extends ProgramError
case object PreCompiledContractFail extends ProgramError

case object RevertOccurs extends ProgramError:
  override val useWholeGas: Boolean = false

case object ReturnDataOverflow extends ProgramError

case object InvalidCode extends ProgramError

case object InitCodeSizeLimit extends ProgramError

/** Pre-Homestead (EIP-2) CREATE: the init code ran to completion but there wasn't enough gas left to pay the
  * per-byte deposit for the runtime code, so no code was stored. Frontier's `exceptionalFailedCodeDeposit = false`
  * deliberately does NOT roll back the world or burn the remaining gas for this case (go-ethereum
  * `core/vm/evm.go` `create()`: `if err != nil && (evm.chainRules.IsHomestead || err != ErrCodeStoreOutOfGas)`
  * gates the revert on `IsHomestead`, not on whether `err` is set) — but `err` itself is unconditionally set
  * regardless of the Homestead flag, so a caller checking success via `Result.Failed()` (`eth_estimateGas`,
  * `eth_call`, GraphQL `estimateGas`/`call`) sees this as a failure on every fork, including pre-Homestead. Only
  * a transaction that actually lands in a block gets the Frontier leniency of keeping its partial state change
  * (nonce bump, endowment transfer) with no code stored.
  */
case object CodeStoreOutOfGasPreHomestead extends ProgramError:
  override val useWholeGas: Boolean = false
  override val rollbackOnError: Boolean = false
