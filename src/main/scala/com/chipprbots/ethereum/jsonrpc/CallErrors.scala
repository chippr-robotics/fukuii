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

  /** A VM error as go-ethereum's `core/vm/errors.go` spells it, for the `error` member of eth_createAccessList.
    *
    * Not used by eth_simulateV1, which keeps its own wording (its output is an rpc-compat oracle; S4).
    */
  def vmErrorMessage(error: ProgramError): String = error match
    case RevertOccurs                         => "execution reverted"
    case OutOfGas                             => "out of gas"
    case InvalidOpCode(code)                  => f"invalid opcode: opcode 0x${code & 0xff}%x not defined"
    case OpCodeNotAvailableInStaticContext(_) => "write protection"
    case InvalidJump(_)                       => "invalid jump destination"
    case StackOverflow                        => "stack limit reached 1024 (1023)"
    case StackUnderflow                       => "stack underflow"
    case ReturnDataOverflow                   => "return data out of bounds"
    case InvalidCode                          => "invalid code: must not begin with 0xef"
    case InitCodeSizeLimit                    => "max initcode size exceeded"
    case other                                => other.toString.toLowerCase

  /** The address a creation from `sender` at `nonce` deploys to (YP eq. 82; geth `crypto.CreateAddress`). */
  def createAddress(sender: Address, nonce: BigInt): Address =
    Address(kec256(rlp.encode(RLPList(RLPValue(sender.bytes.toArray), toEncodeable(nonce)))))
