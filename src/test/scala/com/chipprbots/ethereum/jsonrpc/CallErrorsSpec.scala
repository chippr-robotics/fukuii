package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.vm.*

/** `CallErrors.vmErrorMessage`: a VM error as go-ethereum v1.17.9 spells it (core/vm/errors.go), for eth_call's -32000,
  * eth_estimateGas, eth_createAccessList's `error` and eth_simulateV1's `error.message`.
  */
class CallErrorsSpec extends AnyFlatSpec with Matchers:

  import CallErrors.vmErrorMessage

  "vmErrorMessage" should "print a stack error with go-ethereum's counts" taggedAs (UnitTest, RPCTest) in {
    // ErrStackUnderflow.Error(): "stack underflow (%d <=> %d)", stackLen and required
    vmErrorMessage(StackUnderflow(1, 2)) shouldBe "stack underflow (1 <=> 2)" // ADD on one item
    vmErrorMessage(StackUnderflow(0, 7)) shouldBe "stack underflow (0 <=> 7)" // CALL on an empty stack
    vmErrorMessage(StackUnderflow(16, 17)) shouldBe "stack underflow (16 <=> 17)" // DUPN 17 on 16 items
    // ErrStackOverflow.Error(): "stack limit reached %d (%d)", stackLen and limit
    vmErrorMessage(StackOverflow(1024, 1023)) shouldBe "stack limit reached 1024 (1023)" // PUSH1 on a full stack
  }

  it should "name the failed code deposits as go-ethereum does" taggedAs (UnitTest, RPCTest) in {
    vmErrorMessage(MaxCodeSizeExceeded) shouldBe "max code size exceeded" // ErrMaxCodeSizeExceeded
    vmErrorMessage(CodeStoreOutOfGas) shouldBe "contract creation code storage out of gas" // ErrCodeStoreOutOfGas
    vmErrorMessage(InvalidCode) shouldBe "invalid code: must not begin with 0xef" // ErrInvalidCode
  }

  it should "print a failed precompile as the error of its go-ethereum Run" taggedAs (UnitTest, RPCTest) in {
    // core/vm/contracts.go v1.17.9; every precompile and both chains are in vm.PrecompileFailureReasonSpec
    def word(n: Int): ByteString = UInt256(n).bytes
    vmErrorMessage(PreCompiledContractFail(PrecompiledContracts.Bn128Add, word(1) ++ word(1))) shouldBe
      "point is not on curve"
    vmErrorMessage(PreCompiledContractFail(PrecompiledContracts.Bn128Pairing, ByteString(1))) shouldBe
      "bad elliptic curve pairing size"
    vmErrorMessage(
      PreCompiledContractFail(PrecompiledContracts.Blake2bCompress, ByteString(new Array[Byte](212)))
    ) shouldBe
      "invalid input length"
    vmErrorMessage(PreCompiledContractFail(PrecompiledContracts.ModExp, word(1025) ++ word(1) ++ word(1))) shouldBe
      "one or more of base/exponent/modulus length exceeded 1024 bytes"
    vmErrorMessage(PreCompiledContractFail(PrecompiledContracts.BlsMapG1, ByteString(new Array[Byte](63)))) shouldBe
      "invalid input length"
    vmErrorMessage(PreCompiledContractFail(PrecompiledContracts.KzgPointEvaluation, ByteString.empty)) shouldBe
      "invalid input length"
  }

  it should "keep the messages it already had" taggedAs (UnitTest, RPCTest) in {
    vmErrorMessage(OutOfGas) shouldBe "out of gas"
    vmErrorMessage(RevertOccurs) shouldBe "execution reverted"
    vmErrorMessage(InitCodeSizeLimit) shouldBe "max initcode size exceeded"
    vmErrorMessage(ReturnDataOverflow) shouldBe "return data out of bounds"
    vmErrorMessage(InvalidJump(UInt256(5))) shouldBe "invalid jump destination"
    vmErrorMessage(OpCodeNotAvailableInStaticContext(0x55.toByte)) shouldBe "write protection"
    vmErrorMessage(InvalidOpCode(0xfe.toByte)) shouldBe "invalid opcode: INVALID"
  }
