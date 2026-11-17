package com.chipprbots.ethereum.jsonrpc

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.rlp
import com.chipprbots.ethereum.rlp.RLPImplicitConversions.toEncodeable
import com.chipprbots.ethereum.rlp.RLPImplicits.given
import com.chipprbots.ethereum.rlp.RLPList
import com.chipprbots.ethereum.rlp.RLPValue
import com.chipprbots.ethereum.vm.*

/** Helpers shared by the message-call endpoints. */
object CallErrors:

  /** A VM error as go-ethereum's `core/vm/errors.go` spells it: eth_call's -32000 message, eth_estimateGas's for a
    * non-gas failure at the ceiling, the `error` member of eth_createAccessList, and the `error.message` of an
    * eth_simulateV1 call (#1727; it printed a sign-extended opcode byte and the lower-cased case-class name before).
    *
    * A stack error carries go-ethereum's counts and a failed code deposit its own name (#1727 PR 7a). A failed
    * precompile is the error its go-ethereum `Run` returns ("invalid input length", "point is not on curve", ...),
    * which the precompile states for its input (`PrecompiledContract.failureReason`, #1727 PR 7b). `InvalidCall` still
    * falls to the case-class name: it carries no reason, and stays a case object (see `vm.ProgramError`).
    */
  def vmErrorMessage(error: ProgramError): String = error match
    case RevertOccurs                             => "execution reverted"
    case OutOfGas                                 => "out of gas"
    case PreCompiledContractFail(contract, input) => contract.failureReason(input)
    // geth `ErrInvalidOpCode`: "invalid opcode: " + `OpCode.String()`. Not the operand form ("DUPN (operand: 0x..)")
    // geth prints for a bad EIP-8024 immediate: InvalidOpCode does not carry the operand.
    case InvalidOpCode(code)                  => s"invalid opcode: ${opCodeName(code)}"
    case OpCodeNotAvailableInStaticContext(_) => "write protection"
    case InvalidJump(_)                       => "invalid jump destination"
    case StackOverflow(len, limit)            => s"stack limit reached $len ($limit)"
    case StackUnderflow(len, required)        => s"stack underflow ($len <=> $required)"
    case ReturnDataOverflow                   => "return data out of bounds"
    case InvalidCode                          => "invalid code: must not begin with 0xef"
    case InitCodeSizeLimit                    => "max initcode size exceeded"
    case MaxCodeSizeExceeded                  => "max code size exceeded"
    case CodeStoreOutOfGas                    => "contract creation code storage out of gas"
    case other                                => other.toString.toLowerCase

  /** go-ethereum `OpCode.String()` (`core/vm/opcodes.go`): the name in geth's `opCodeToString`, whether or not the
    * block's fork enables the opcode, else `opcode 0x.. not defined`. So 0xFE is `INVALID` and PUSH0 before Shanghai is
    * `PUSH0`. geth's names, not fukuii's: 0x20 is KECCAK256 (fukuii's SHA3), 0x44 DIFFICULTY.
    */
  def opCodeName(code: Byte): String =
    val op = code & 0xff
    GethOpCodeNames.getOrElse(op, f"opcode 0x$op%x not defined")

  private val GethOpCodeNames: Map[Int, String] =
    val named = Map(
      // 0x00 - 0x1e: arithmetic, comparison, bit ops
      0x00 -> "STOP",
      0x01 -> "ADD",
      0x02 -> "MUL",
      0x03 -> "SUB",
      0x04 -> "DIV",
      0x05 -> "SDIV",
      0x06 -> "MOD",
      0x07 -> "SMOD",
      0x08 -> "ADDMOD",
      0x09 -> "MULMOD",
      0x0a -> "EXP",
      0x0b -> "SIGNEXTEND",
      0x10 -> "LT",
      0x11 -> "GT",
      0x12 -> "SLT",
      0x13 -> "SGT",
      0x14 -> "EQ",
      0x15 -> "ISZERO",
      0x16 -> "AND",
      0x17 -> "OR",
      0x18 -> "XOR",
      0x19 -> "NOT",
      0x1a -> "BYTE",
      0x1b -> "SHL",
      0x1c -> "SHR",
      0x1d -> "SAR",
      0x1e -> "CLZ",
      0x20 -> "KECCAK256",
      // 0x30 - 0x3f: closure state
      0x30 -> "ADDRESS",
      0x31 -> "BALANCE",
      0x32 -> "ORIGIN",
      0x33 -> "CALLER",
      0x34 -> "CALLVALUE",
      0x35 -> "CALLDATALOAD",
      0x36 -> "CALLDATASIZE",
      0x37 -> "CALLDATACOPY",
      0x38 -> "CODESIZE",
      0x39 -> "CODECOPY",
      0x3a -> "GASPRICE",
      0x3b -> "EXTCODESIZE",
      0x3c -> "EXTCODECOPY",
      0x3d -> "RETURNDATASIZE",
      0x3e -> "RETURNDATACOPY",
      0x3f -> "EXTCODEHASH",
      // 0x40 - 0x4b: block
      0x40 -> "BLOCKHASH",
      0x41 -> "COINBASE",
      0x42 -> "TIMESTAMP",
      0x43 -> "NUMBER",
      0x44 -> "DIFFICULTY",
      0x45 -> "GASLIMIT",
      0x46 -> "CHAINID",
      0x47 -> "SELFBALANCE",
      0x48 -> "BASEFEE",
      0x49 -> "BLOBHASH",
      0x4a -> "BLOBBASEFEE",
      0x4b -> "SLOTNUM",
      // 0x50 - 0x5f: storage and execution
      0x50 -> "POP",
      0x51 -> "MLOAD",
      0x52 -> "MSTORE",
      0x53 -> "MSTORE8",
      0x54 -> "SLOAD",
      0x55 -> "SSTORE",
      0x56 -> "JUMP",
      0x57 -> "JUMPI",
      0x58 -> "PC",
      0x59 -> "MSIZE",
      0x5a -> "GAS",
      0x5b -> "JUMPDEST",
      0x5c -> "TLOAD",
      0x5d -> "TSTORE",
      0x5e -> "MCOPY",
      0x5f -> "PUSH0",
      // 0xd0 - 0xee: EOF and EIP-8024 (geth names them whether or not a fork enables them)
      0xd0 -> "DATALOAD",
      0xd1 -> "DATALOADN",
      0xd2 -> "DATASIZE",
      0xd3 -> "DATACOPY",
      0xe0 -> "RJUMP",
      0xe1 -> "RJUMPI",
      0xe2 -> "RJUMPV",
      0xe3 -> "CALLF",
      0xe4 -> "RETF",
      0xe5 -> "JUMPF",
      0xe6 -> "DUPN",
      0xe7 -> "SWAPN",
      0xe8 -> "EXCHANGE",
      0xec -> "EOFCREATE",
      0xee -> "RETURNCONTRACT",
      // 0xf0 - 0xff: closures
      0xf0 -> "CREATE",
      0xf1 -> "CALL",
      0xf2 -> "CALLCODE",
      0xf3 -> "RETURN",
      0xf4 -> "DELEGATECALL",
      0xf5 -> "CREATE2",
      0xf7 -> "RETURNDATALOAD",
      0xf8 -> "EXTCALL",
      0xf9 -> "EXTDELEGATECALL",
      0xfa -> "STATICCALL",
      0xfb -> "EXTSTATICCALL",
      0xfd -> "REVERT",
      0xfe -> "INVALID",
      0xff -> "SELFDESTRUCT"
    )
    named ++
      (1 to 32).map(n => (0x5f + n) -> s"PUSH$n") ++
      (1 to 16).map(n => (0x7f + n) -> s"DUP$n") ++
      (1 to 16).map(n => (0x8f + n) -> s"SWAP$n") ++
      (0 to 4).map(n => (0xa0 + n) -> s"LOG$n")

  /** The address a creation from `sender` at `nonce` deploys to (YP eq. 82; geth `crypto.CreateAddress`). */
  def createAddress(sender: Address, nonce: BigInt): Address =
    Address(kec256(rlp.encode(RLPList(RLPValue(sender.bytes.toArray), toEncodeable(nonce)))))
