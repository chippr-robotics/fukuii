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

  /** go-ethereum's text for a VM error; see [[com.chipprbots.ethereum.vm.ProgramErrorText.vmErrorMessage]]. */
  def vmErrorMessage(error: ProgramError): String = ProgramErrorText.vmErrorMessage(error)

  /** go-ethereum `OpCode.String()`; see [[com.chipprbots.ethereum.vm.ProgramErrorText.opCodeName]]. */
  def opCodeName(code: Byte): String = ProgramErrorText.opCodeName(code)

  /** The address a creation from `sender` at `nonce` deploys to (YP eq. 82; geth `crypto.CreateAddress`). */
  def createAddress(sender: Address, nonce: BigInt): Address =
    Address(kec256(rlp.encode(RLPList(RLPValue(sender.bytes.toArray), toEncodeable(nonce)))))
