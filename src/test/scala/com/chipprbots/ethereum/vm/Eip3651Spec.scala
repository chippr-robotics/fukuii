package com.chipprbots.ethereum.vm

import org.apache.pekko.util.ByteString
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.Fixtures.{Blocks => BlockFixtures}
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.testing.Tags._

import Fixtures.blockchainConfig

/** Tests for EIP-3651: Warm COINBASE https://eips.ethereum.org/EIPS/eip-3651
  */
class Eip3651Spec extends AnyWordSpec with Matchers {

  // Config without EIP-3651 (using Mystique as base)
  val configPreEip3651: EvmConfig = EvmConfig.MystiqueConfigBuilder(blockchainConfig)

  // Config with EIP-3651 enabled
  val configWithEip3651: EvmConfig = configPreEip3651.copy(eip3651Enabled = true)

  object fxt {
    val coinbaseAddr: Address = Address(0xc014ba5e) // COINBASE address
    val callerAddr: Address = Address(0xca11e4)
    val otherAddr: Address = Address(0x0de4)

    val fakeHeaderPreEip3651: BlockHeader =
      BlockFixtures.ValidBlock.header.copy(
        number = Fixtures.MystiqueBlockNumber,
        beneficiary = coinbaseAddr.bytes
      )

    // Separate variable for semantic clarity - represents same block header used with EIP-3651 enabled config
    // This makes test intent clearer even though block header itself is identical
    val fakeHeaderWithEip3651: BlockHeader = fakeHeaderPreEip3651.copy()

    def createContext(
        code: ByteString,
        header: BlockHeader,
        config: EvmConfig,
        startGas: BigInt = 1000000,
        warmAddresses: Set[Address] = Set.empty
    ): ProgramContext[MockWorldState, MockStorage] = {
      val world = createWorld(code)
      ProgramContext(
        callerAddr = callerAddr,
        originAddr = callerAddr,
        recipientAddr = Some(callerAddr),
        gasPrice = 1,
        startGas = startGas,
        inputData = ByteString.empty,
        value = UInt256.Zero,
        endowment = UInt256.Zero,
        doTransfer = false,
        blockHeader = header,
        callDepth = 0,
        world = world,
        initialAddressesToDelete = Set(),
        evmConfig = config,
        originalWorld = world,
        warmAddresses = warmAddresses,
        warmStorage = Set.empty
      )
    }

    // Code that reads COINBASE address and checks its balance
    // COINBASE BALANCE
    val codeReadCoinbaseBalance: Assembly = Assembly(
      COINBASE, // Push coinbase address to stack
      BALANCE, // Check balance (this triggers warm/cold access)
      STOP
    )

    def createWorld(code: ByteString = codeReadCoinbaseBalance.code): MockWorldState = {
      val world = MockWorldState()
        .saveAccount(coinbaseAddr, Account(balance = UInt256(1000)))
        .saveAccount(callerAddr, Account(balance = UInt256(1000), nonce = 1))
        .saveAccount(otherAddr, Account(balance = UInt256(1000)))
        .saveCode(callerAddr, code)
      world
    }

    // Code that calls EXTCODESIZE on COINBASE
    val codeReadCoinbaseCodeSize: Assembly = Assembly(
      COINBASE,
      EXTCODESIZE,
      STOP
    )

    // Code that calls EXTCODEHASH on COINBASE
    val codeReadCoinbaseCodeHash: Assembly = Assembly(
      COINBASE,
      EXTCODEHASH,
      STOP
    )

    // Code that reads balance of a different address (should still be cold)
    val codeReadOtherBalance: Assembly = Assembly(
      PUSH20,
      otherAddr.bytes,
      BALANCE,
      STOP
    )
  }

  import fxt._

  "EIP-3651" when {

    "disabled (pre-fork)" should {

      "treat COINBASE address as cold on first access" taggedAs (UnitTest, VMTest) in {
        val context = createContext(
          codeReadCoinbaseBalance.code,
          fakeHeaderPreEip3651,
          configPreEip3651
        )

        val vm = new VM[MockWorldState, MockStorage]
        val result = vm.run(context)

        // Without EIP-3651, COINBASE is cold, so BALANCE costs G_cold_account_access
        val expectedGas = configPreEip3651.feeSchedule.G_base + // COINBASE opcode
          configPreEip3651.feeSchedule.G_cold_account_access // BALANCE (cold)

        result.gasRemaining shouldEqual (context.startGas - expectedGas)
        result.error shouldBe None
      }

      "not include COINBASE in initial accessed addresses" taggedAs (UnitTest, VMTest) in {
        val context = createContext(
          codeReadCoinbaseBalance.code,
          fakeHeaderPreEip3651,
          configPreEip3651
        )

        val env = ExecEnv(context, context.inputData, context.recipientAddr.get)
        val initialState = ProgramState(new VM[MockWorldState, MockStorage], context, env)

        // COINBASE should not be in accessedAddresses initially
        initialState.accessedAddresses should not contain coinbaseAddr
      }
    }

    "enabled (post-fork)" should {

      "treat COINBASE address as warm on first access" taggedAs (UnitTest, VMTest) in {
        val context = createContext(
          codeReadCoinbaseBalance.code,
          fakeHeaderWithEip3651,
          configWithEip3651
        )

        val vm = new VM[MockWorldState, MockStorage]
        val result = vm.run(context)

        // With EIP-3651, COINBASE is warm, so BALANCE costs G_warm_storage_read
        val expectedGas = configWithEip3651.feeSchedule.G_base + // COINBASE opcode
          configWithEip3651.feeSchedule.G_warm_storage_read // BALANCE (warm)

        result.gasRemaining shouldEqual (context.startGas - expectedGas)
        result.error shouldBe None
      }

      "include COINBASE in initial accessed addresses" taggedAs (UnitTest, VMTest) in {
        val context = createContext(
          codeReadCoinbaseBalance.code,
          fakeHeaderWithEip3651,
          configWithEip3651
        )

        val env = ExecEnv(context, context.inputData, context.recipientAddr.get)
        val initialState = ProgramState(new VM[MockWorldState, MockStorage], context, env)

        // COINBASE should be in accessedAddresses initially
        initialState.accessedAddresses should contain(coinbaseAddr)
      }

      "save 2500 gas compared to cold access" taggedAs (UnitTest, VMTest) in {
        // Without EIP-3651
        val contextPreEip = createContext(
          codeReadCoinbaseBalance.code,
          fakeHeaderPreEip3651,
          configPreEip3651
        )
        val vmPre = new VM[MockWorldState, MockStorage]
        val resultPre = vmPre.run(contextPreEip)

        // With EIP-3651
        val contextWithEip = createContext(
          codeReadCoinbaseBalance.code,
          fakeHeaderWithEip3651,
          configWithEip3651
        )
        val vmWith = new VM[MockWorldState, MockStorage]
        val resultWith = vmWith.run(contextWithEip)

        // Gas savings should be the difference between cold and warm access
        val gasSavings = (contextPreEip.startGas - resultPre.gasRemaining) -
          (contextWithEip.startGas - resultWith.gasRemaining)

        val expectedSavings = configWithEip3651.feeSchedule.G_cold_account_access -
          configWithEip3651.feeSchedule.G_warm_storage_read

        gasSavings shouldEqual expectedSavings
        gasSavings shouldEqual 2500 // Standard EIP-2929 difference
      }

      "work with EXTCODESIZE opcode" taggedAs (UnitTest, VMTest) in {
        val context = createContext(
          codeReadCoinbaseCodeSize.code,
          fakeHeaderWithEip3651,
          configWithEip3651
        )

        val vm = new VM[MockWorldState, MockStorage]
        val result = vm.run(context)

        val expectedGas = configWithEip3651.feeSchedule.G_base + // COINBASE opcode
          configWithEip3651.feeSchedule.G_warm_storage_read // EXTCODESIZE (warm)

        result.gasRemaining shouldEqual (context.startGas - expectedGas)
        result.error shouldBe None
      }

      "work with EXTCODEHASH opcode" taggedAs (UnitTest, VMTest) in {
        val context = createContext(
          codeReadCoinbaseCodeHash.code,
          fakeHeaderWithEip3651,
          configWithEip3651
        )

        val vm = new VM[MockWorldState, MockStorage]
        val result = vm.run(context)

        val expectedGas = configWithEip3651.feeSchedule.G_base + // COINBASE opcode
          configWithEip3651.feeSchedule.G_warm_storage_read // EXTCODEHASH (warm)

        result.gasRemaining shouldEqual (context.startGas - expectedGas)
        result.error shouldBe None
      }

      "not affect other addresses (they remain cold)" taggedAs (UnitTest, VMTest) in {
        val context = createContext(
          codeReadOtherBalance.code,
          fakeHeaderWithEip3651,
          configWithEip3651
        )

        val vm = new VM[MockWorldState, MockStorage]
        val result = vm.run(context)

        // Other addresses should still be cold
        val expectedGas = configWithEip3651.feeSchedule.G_verylow + // PUSH20
          configWithEip3651.feeSchedule.G_cold_account_access // BALANCE (cold)

        result.gasRemaining shouldEqual (context.startGas - expectedGas)
        result.error shouldBe None
      }

      "preserve COINBASE in accessed addresses after transaction" taggedAs (UnitTest, VMTest) in {
        val context = createContext(
          codeReadCoinbaseBalance.code,
          fakeHeaderWithEip3651,
          configWithEip3651
        )

        val vm = new VM[MockWorldState, MockStorage]
        val result = vm.run(context)

        // COINBASE should remain in accessed addresses after execution
        result.accessedAddresses should contain(coinbaseAddr)
      }
    }

    "interaction with access lists" should {

      "work when COINBASE is also in transaction access list" taggedAs (UnitTest, VMTest) in {
        val context = createContext(
          codeReadCoinbaseBalance.code,
          fakeHeaderWithEip3651,
          configWithEip3651,
          warmAddresses = Set(coinbaseAddr) // COINBASE also in access list
        )

        val vm = new VM[MockWorldState, MockStorage]
        val result = vm.run(context)

        // Should still be warm (no change in behavior)
        val expectedGas = configWithEip3651.feeSchedule.G_base + // COINBASE opcode
          configWithEip3651.feeSchedule.G_warm_storage_read // BALANCE (warm)

        result.gasRemaining shouldEqual (context.startGas - expectedGas)
        result.error shouldBe None
      }

      "work when other addresses are in access list" taggedAs (UnitTest, VMTest) in {
        val context = createContext(
          codeReadCoinbaseBalance.code,
          fakeHeaderWithEip3651,
          configWithEip3651,
          warmAddresses = Set(otherAddr) // Other address in access list
        )

        val vm = new VM[MockWorldState, MockStorage]
        val result = vm.run(context)

        // COINBASE should still be warm due to EIP-3651
        val expectedGas = configWithEip3651.feeSchedule.G_base + // COINBASE opcode
          configWithEip3651.feeSchedule.G_warm_storage_read // BALANCE (warm)

        result.gasRemaining shouldEqual (context.startGas - expectedGas)
        result.error shouldBe None
      }
    }
  }
}
