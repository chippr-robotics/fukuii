package com.chipprbots.ethereum.vm

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.testing.Tags.*

/** #1727 PR 7a: the errors that gained fields or names keep the shape the rest of the client relies on.
  *
  *   - `toString` is what the tracers print (structLog and callTracer `error`, the system-call failure text) and what
  *     `TraceGoldenSpec` pins, so a stack error prints its bare name, not its counts.
  *   - Consensus code compares four errors by identity (`CreateOp`, `CallOp`, the estimator): those must stay case
  *     objects, since `error == X` against a case class's companion compiles and is always false.
  *   - Every error but a revert consumes the frame's gas.
  */
class ProgramErrorToStringSpec extends AnyFlatSpec with Matchers:

  "A ProgramError's toString" should "be the bare name the tracers print, whatever it carries" taggedAs (
    UnitTest,
    VMTest
  ) in {
    StackUnderflow(3, 7).toString shouldBe "StackUnderflow"
    StackUnderflow(0, 1).toString shouldBe "StackUnderflow"
    StackOverflow(1024, 1023).toString shouldBe "StackOverflow"
    MaxCodeSizeExceeded.toString shouldBe "MaxCodeSizeExceeded"
    CodeStoreOutOfGas.toString shouldBe "CodeStoreOutOfGas"
    // unchanged
    OutOfGas.toString shouldBe "OutOfGas"
    InvalidCall.toString shouldBe "InvalidCall"
    RevertOccurs.toString shouldBe "RevertOccurs"
    InvalidCode.toString shouldBe "InvalidCode"
    InitCodeSizeLimit.toString shouldBe "InitCodeSizeLimit"
    ReturnDataOverflow.toString shouldBe "ReturnDataOverflow"
    PreCompiledContractFail(
      PrecompiledContracts.Bn128Add,
      ByteString(1, 2, 3)
    ).toString shouldBe "PreCompiledContractFail"
    PreCompiledContractFail(
      PrecompiledContracts.BlsPairing,
      ByteString.empty
    ).toString shouldBe "PreCompiledContractFail"
    InvalidOpCode(0xfe.toByte).toString shouldBe "InvalidOpCode(0xfe)"
    OpCodeNotAvailableInStaticContext(0x55.toByte).toString shouldBe "OpCodeNotAvailableInStaticContext(0x55)"
    InvalidJump(UInt256(5)).toString shouldBe s"InvalidJump(${UInt256(5).toHexString})"
  }

  "The errors consensus code compares by identity" should "stay case objects" taggedAs (UnitTest, VMTest) in {
    // `val e: ProgramError = InvalidCall` compiles only while InvalidCall IS a ProgramError, not a companion object
    val byIdentity: Seq[ProgramError & Product] = Seq(InvalidCall, RevertOccurs, OutOfGas, InvalidCode)
    byIdentity.foreach(e => withClue(s"$e: ")(e.productArity shouldBe 0))
    val e: ProgramError = InvalidCall
    (e == InvalidCall) shouldBe true
  }

  "Every error but a revert" should "consume the frame's gas" taggedAs (UnitTest, VMTest) in {
    val wholeGas: Seq[ProgramError] = Seq(
      StackUnderflow(0, 1),
      StackOverflow(1024, 1023),
      MaxCodeSizeExceeded,
      CodeStoreOutOfGas,
      OutOfGas,
      InvalidCall,
      InvalidCode,
      InitCodeSizeLimit,
      ReturnDataOverflow,
      PreCompiledContractFail(PrecompiledContracts.Bn128Add, ByteString.empty),
      InvalidOpCode(0xfe.toByte),
      InvalidJump(UInt256(5))
    )
    wholeGas.foreach(e => withClue(s"$e: ")(e.useWholeGas shouldBe true))
    RevertOccurs.useWholeGas shouldBe false
  }

  "Stack errors" should "be equal exactly when their counts are" taggedAs (UnitTest, VMTest) in {
    StackUnderflow(1, 2) shouldBe StackUnderflow(1, 2)
    StackUnderflow(1, 2) should not be StackUnderflow(1, 3)
    StackOverflow(1024, 1023) should not be StackOverflow(1024, 1024)
  }
