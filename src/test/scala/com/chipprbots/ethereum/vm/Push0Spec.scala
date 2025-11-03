package com.chipprbots.ethereum.vm

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.vm.Fixtures.blockchainConfig

class Push0Spec extends AnyFunSuite with OpCodeTesting with Matchers with ScalaCheckPropertyChecks {

  import MockWorldState.PS

  // Use Spiral config which includes PUSH0
  override val config: EvmConfig = EvmConfig.SpiralConfigBuilder(blockchainConfig)

  test("PUSH0 opcode is available in Spiral fork") {
    config.byteToOpCode.get(0x5f.toByte) shouldBe Some(PUSH0)
  }

  test("PUSH0 should push zero onto the stack") {
    forAll(Generators.getProgramStateGen()) { stateIn =>
      val stateOut = PUSH0.execute(stateIn)

      // Should not error if stack has room
      if (stateIn.stack.size < stateIn.stack.maxSize) {
        stateOut.error shouldBe None
        stateOut.stack.size shouldEqual stateIn.stack.size + 1
        val (top, _) = stateOut.stack.pop()
        top shouldEqual UInt256.Zero
        stateOut.pc shouldEqual stateIn.pc + 1
      }
    }
  }

  test("PUSH0 should use 2 gas (G_base)") {
    forAll(Generators.getProgramStateGen()) { stateIn =>
      // Only test when we have enough gas
      if (stateIn.gas >= 2 && stateIn.stack.size < stateIn.stack.maxSize) {
        val stateOut = PUSH0.execute(stateIn)
        stateOut.error shouldBe None
        stateOut.gas shouldEqual (stateIn.gas - 2)
      }
    }
  }

  test("PUSH0 should fail with StackOverflow when stack is full") {
    // Create a full stack by pushing 1024 items
    val fullStack = (1 to 1024).foldLeft(Stack.empty(1024))((stack, _) => stack.push(UInt256.One))
    val stateIn = Generators.getProgramStateGen().sample.get.withStack(fullStack)
    val stateOut = PUSH0.execute(stateIn)
    
    stateOut.error shouldBe Some(StackOverflow)
  }

  test("PUSH0 should fail with OutOfGas when not enough gas") {
    val lowGasState = Generators.getProgramStateGen().sample.get.copy(gas = 1)
    val stateOut = PUSH0.execute(lowGasState)
    
    stateOut.error shouldBe Some(OutOfGas)
    stateOut.gas shouldBe 0
  }

  test("PUSH0 multiple times should push multiple zeros") {
    val initialState = Generators.getProgramStateGen().sample.get
    
    val state1 = PUSH0.execute(initialState)
    state1.error shouldBe None
    state1.stack.size shouldEqual initialState.stack.size + 1
    
    val state2 = PUSH0.execute(state1)
    state2.error shouldBe None
    state2.stack.size shouldEqual initialState.stack.size + 2
    
    val (top1, stack1) = state2.stack.pop()
    val (top2, _) = stack1.pop()
    
    top1 shouldEqual UInt256.Zero
    top2 shouldEqual UInt256.Zero
  }

  test("PUSH0 has correct opcode properties") {
    PUSH0.code shouldBe 0x5f.toByte
    PUSH0.delta shouldBe 0  // pops 0 items
    PUSH0.alpha shouldBe 1  // pushes 1 item
  }

  test("PUSH0 should be cheaper than PUSH1 with zero") {
    val initialState = Generators.getProgramStateGen().sample.get
    
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

  test("EIP-3855 test case: single PUSH0 execution") {
    // Test case from EIP-3855: 5F – successful execution, stack consist of a single item, set to zero
    val state = Generators.getProgramStateGen().sample.get
      .withStack(Stack.empty())
      .copy(gas = 1000)
    
    val result = PUSH0.execute(state)
    
    result.error shouldBe None
    result.stack.size shouldBe 1
    val (value, _) = result.stack.pop()
    value shouldEqual UInt256.Zero
  }

  test("EIP-3855 test case: 1024 PUSH0 operations") {
    // Test case from EIP-3855: 5F5F..5F (1024 times) – successful execution, 
    // stack consists of 1024 items, all set to zero
    var state = Generators.getProgramStateGen().sample.get
      .withStack(Stack.empty())
      .copy(gas = 10000)
    
    // Execute PUSH0 1024 times
    (1 to 1024).foreach { _ =>
      state = PUSH0.execute(state)
      state.error shouldBe None
    }
    
    state.stack.size shouldBe 1024
    
    // Verify all items are zero
    var currentStack = state.stack
    (1 to 1024).foreach { _ =>
      val (value, newStack) = currentStack.pop()
      value shouldEqual UInt256.Zero
      currentStack = newStack
    }
  }

  test("EIP-3855 test case: 1025 PUSH0 operations should fail") {
    // Test case from EIP-3855: 5F5F..5F (1025 times) – execution aborts due to out of stack
    var state = Generators.getProgramStateGen().sample.get
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
