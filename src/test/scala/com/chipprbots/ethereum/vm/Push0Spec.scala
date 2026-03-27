package com.chipprbots.ethereum.vm

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.vm.Fixtures.blockchainConfig

class Push0Spec extends AnyFunSuite with OpCodeTesting with Matchers with ScalaCheckPropertyChecks {

  import MockWorldState.PS

  // Use Spiral config which includes PUSH0
  override val config: EvmConfig = EvmConfig.SpiralConfigBuilder(blockchainConfig)

  test("PUSH0 opcode is available in Spiral fork", UnitTest, VMTest) {
    config.byteToOpCode.get(0x5f.toByte) shouldBe Some(PUSH0)
  }

  test("PUSH0 should push zero onto the stack", UnitTest, VMTest) {
    forAll(Generators.getProgramStateGen()) { stateIn =>
      val initialSize = stateIn.stack.size
      val initialPc = stateIn.pc
      val hasRoom = initialSize < stateIn.stack.maxSize
      val stateOut = PUSH0.execute(stateIn)

      // Should not error if stack has room
      if (hasRoom) {
        stateOut.error shouldBe None
        stateOut.stack.size shouldEqual initialSize + 1
        val top = stateOut.stack.pop()
        top shouldEqual UInt256.Zero
        stateOut.pc shouldEqual initialPc + 1
      }
    }
  }

  test("PUSH0 should use 2 gas (G_base)", UnitTest, VMTest) {
    forAll(Generators.getProgramStateGen()) { stateIn =>
      val initialGas = stateIn.gas
      val hasRoom = stateIn.stack.size < stateIn.stack.maxSize
      // Only test when we have enough gas
      if (initialGas >= 2 && hasRoom) {
        val stateOut = PUSH0.execute(stateIn)
        stateOut.error shouldBe None
        stateOut.gas shouldEqual (initialGas - 2)
      }
    }
  }

  test("PUSH0 should fail with StackOverflow when stack is full", UnitTest, VMTest) {
    // Create a full stack by pushing 1024 items
    val fullStack = Stack.empty(1024)
    (1 to 1024).foreach(_ => fullStack.push(UInt256.One))
    val stateIn = Generators.getProgramStateGen().sample.get.withStack(fullStack)
    val stateOut = PUSH0.execute(stateIn)

    stateOut.error shouldBe Some(StackOverflow)
  }

  test("PUSH0 should fail with OutOfGas when not enough gas", UnitTest, VMTest) {
    // Create state with gas=1 and ensure stack has room (to test gas check, not stack overflow)
    val lowGasState = Generators
      .getProgramStateGen()
      .sample
      .get
      .withStack(Stack.empty()) // Ensure stack has room
      .copy(gas = 1)
    val stateOut = PUSH0.execute(lowGasState)

    stateOut.error shouldBe Some(OutOfGas)
    stateOut.gas shouldBe 0
  }

  test("PUSH0 multiple times should push multiple zeros", UnitTest, VMTest) {
    // Create state with an empty stack to ensure room for multiple pushes
    val initialState = Generators
      .getProgramStateGen()
      .sample
      .get
      .withStack(Stack.empty())
      .copy(gas = 1000)

    val size0 = initialState.stack.size
    val state1 = PUSH0.execute(initialState)
    state1.error shouldBe None
    state1.stack.size shouldEqual size0 + 1

    val size1 = state1.stack.size
    val state2 = PUSH0.execute(state1)
    state2.error shouldBe None
    state2.stack.size shouldEqual size1 + 1

    val top1 = state2.stack.pop()
    val top2 = state2.stack.pop()

    top1 shouldEqual UInt256.Zero
    top2 shouldEqual UInt256.Zero
  }

  test("PUSH0 has correct opcode properties", UnitTest, VMTest) {
    PUSH0.code shouldBe 0x5f.toByte
    PUSH0.delta shouldBe 0 // pops 0 items
    PUSH0.alpha shouldBe 1 // pushes 1 item
  }

  test("PUSH0 should be cheaper than PUSH1 with zero", UnitTest, VMTest) {
    // Create state with empty stack and sufficient gas to ensure consistent execution
    val initialState = Generators
      .getProgramStateGen()
      .sample
      .get
      .withStack(Stack.empty())
      .copy(gas = 1000)

    // PUSH0 uses G_base (2 gas)
    val push0State = PUSH0.execute(initialState)
    val push0GasUsed = initialState.gas - push0State.gas

    // PUSH1 uses G_verylow (3 gas)
    val push1State = PUSH1.execute(initialState)
    val push1GasUsed = initialState.gas - push1State.gas

    push0GasUsed shouldBe 2
    push1GasUsed shouldBe 3
    push0GasUsed should be < push1GasUsed
  }

  test("EIP-3855 test case: single PUSH0 execution", UnitTest, VMTest) {
    // Test case from EIP-3855: 5F – successful execution, stack consist of a single item, set to zero
    val state = Generators
      .getProgramStateGen()
      .sample
      .get
      .withStack(Stack.empty())
      .copy(gas = 1000)

    val result = PUSH0.execute(state)

    result.error shouldBe None
    result.stack.size shouldBe 1
    val value = result.stack.pop()
    value shouldEqual UInt256.Zero
  }

  test("EIP-3855 test case: 1024 PUSH0 operations", UnitTest, VMTest) {
    // Test case from EIP-3855: 5F5F..5F (1024 times) – successful execution,
    // stack consists of 1024 items, all set to zero
    var state = Generators
      .getProgramStateGen()
      .sample
      .get
      .withStack(Stack.empty())
      .copy(gas = 10000)

    // Execute PUSH0 1024 times
    (1 to 1024).foreach { _ =>
      state = PUSH0.execute(state)
      state.error shouldBe None
    }

    state.stack.size shouldBe 1024

    // Verify all items are zero
    (1 to 1024).foreach { _ =>
      val value = state.stack.pop()
      value shouldEqual UInt256.Zero
    }
  }

  test("EIP-3855 test case: 1025 PUSH0 operations should fail", UnitTest, VMTest) {
    // Test case from EIP-3855: 5F5F..5F (1025 times) – execution aborts due to out of stack
    var state = Generators
      .getProgramStateGen()
      .sample
      .get
      .withStack(Stack.empty())
      .copy(gas = 10000)

    // Execute PUSH0 1024 times successfully
    (1 to 1024).foreach { _ =>
      state = PUSH0.execute(state)
      state.error shouldBe None
    }

    state.stack.size shouldBe 1024

    // The 1025th PUSH0 should fail with StackOverflow
    val finalState = PUSH0.execute(state)
    finalState.error shouldBe Some(StackOverflow)
  }
}
