package com.chipprbots.ethereum.vm

import org.scalacheck.Gen
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.vm.Generators.*

import Fixtures.blockchainConfig

/** The one-copy transitions that the stack-only instructions and JUMP/JUMPI use, against the three-step composition
  * they replace (`withStack`, then `step`/`goto`, then `spendGas`).
  *
  * The per-instruction results themselves are pinned by OpCodeFunSpec (every field but gas) and OpCodeGasSpec (gas),
  * which predate the one-copy path and run unchanged against it.
  */
class StackOnlyTransitionSpec extends AnyFunSuite with Matchers with ScalaCheckPropertyChecks:

  private val gasGen: Gen[BigInt] = getBigIntGen(0, UInt256.MaxValue.toBigInt)

  test("stepWithStack is withStack, then step, then spendGas", UnitTest, VMTest) {
    forAll(getProgramStateGen(), getStackGen(), Gen.choose(0, 33), gasGen) { (state, stack, pcIncrement, gas) =>
      state.stepWithStack(stack, pcIncrement, gas) shouldEqual state.withStack(stack).step(pcIncrement).spendGas(gas)
    }
  }

  test("jumpWithStack is withStack, then goto, then spendGas", UnitTest, VMTest) {
    forAll(getProgramStateGen(), getStackGen(), Gen.choose(0, 1 << 20), gasGen) { (state, stack, dest, gas) =>
      state.jumpWithStack(stack, dest, gas) shouldEqual state.withStack(stack).goto(dest).spendGas(gas)
    }
  }

  // The widest opcode set of each chain: every instruction the one-copy path can be reached with.
  private val configs = Seq(
    "Osaka" -> EvmConfig.PhoenixConfigBuilder(blockchainConfig).copy(opCodeList = EvmConfig.OsakaOpCodes),
    "ETC Olympia" -> EvmConfig.PhoenixConfigBuilder(blockchainConfig).copy(opCodeList = EvmConfig.EtcOlympiaOpCodes)
  )

  for (fork, config) <- configs do
    val stackOnly = config.opCodes.collect { case op: StackOnlyOp => op }

    test(s"$fork: a stack-only instruction changes nothing but the stack, the pc and the gas", UnitTest, VMTest) {
      stackOnly should not be empty
      val stateGen = getProgramStateGen(
        // 17 to 30 of a 1,024-slot stack: deep enough for DUP16/SWAP16, never full, so no instruction here fails
        stackGen = getStackGen(minElems = 17, maxElems = 30, maxSize = Stack.DefaultMaxSize),
        codeGen = getByteStringGen(0, 40),
        evmConfig = config
      )
      for op <- stackOnly do
        forAll(stateGen) { stateIn =>
          val stateOut = op.execute(stateIn)
          stateOut.error shouldBe None
          val pcIncrement = op match
            case push: PushOp => push.i + 2
            case _            => 1
          stateOut.pc shouldEqual stateIn.pc + pcIncrement
          stateOut shouldEqual stateIn.stepWithStack(stateOut.stack, pcIncrement, stateIn.gas - stateOut.gas)
        }
    }
