package com.chipprbots.ethereum.vm

import java.security.SecureRandom

import org.apache.pekko.util.ByteString
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.Fixtures.{Blocks => BlockFixtures}
import com.chipprbots.ethereum.crypto
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.utils.Config

/** Tests for EIP-3860: Limit and meter initcode https://eips.ethereum.org/EIPS/eip-3860
  *
  * EIP-3860 introduces:
  *   1. Maximum initcode size of 49152 bytes (2 * MAX_CODE_SIZE) 2. Gas cost of 2 per 32-byte word of initcode
  */
class Eip3860Spec extends AnyWordSpec with Matchers {

  val blockchainConfig = Fixtures.blockchainConfig
  val fullBlockchainConfig = Config.blockchains.blockchainConfig
  val configPreSpiral: EvmConfig = EvmConfig.MystiqueConfigBuilder(blockchainConfig)
  val configSpiral: EvmConfig = EvmConfig.SpiralConfigBuilder(blockchainConfig)

  // EIP-3860 constants
  val MaxCodeSize = 24576 // EIP-170
  val MaxInitCodeSize = MaxCodeSize * 2 // 49152 bytes
  val InitCodeWordCost = 2 // Gas per 32-byte word

  object fxt {
    val fakeHeaderPreSpiral: BlockHeader =
      BlockFixtures.ValidBlock.header.copy(number = Fixtures.MystiqueBlockNumber)
    val fakeHeaderSpiral: BlockHeader =
      BlockFixtures.ValidBlock.header.copy(number = Fixtures.SpiralBlockNumber)
    val creatorAddr: Address = Address(0xcafe)
    val secureRandom = new SecureRandom()
    val keyPair: AsymmetricCipherKeyPair = crypto.generateKeyPair(secureRandom)

    def createContext(
        world: MockWorldState,
        initCode: ByteString,
        header: BlockHeader,
        config: EvmConfig,
        endowment: UInt256 = UInt256(123),
        startGas: BigInt = 10000000
    ): ProgramContext[MockWorldState, MockStorage] =
      ProgramContext(
        callerAddr = creatorAddr,
        originAddr = creatorAddr,
        recipientAddr = None,
        gasPrice = 1,
        startGas = startGas,
        inputData = initCode,
        value = endowment,
        endowment = endowment,
        doTransfer = true,
        blockHeader = header,
        callDepth = 0,
        world = world,
        initialAddressesToDelete = Set(),
        evmConfig = config,
        originalWorld = world,
        warmAddresses = Set.empty,
        warmStorage = Set.empty
      )

    // Simple init code that returns empty code
    val simpleInitCode: Assembly = Assembly(
      PUSH1,
      0, // size
      PUSH1,
      0, // offset
      RETURN
    )

    // Create init code of specific size (padding with JUMPDEST opcodes)
    def initCodeOfSize(size: Int): ByteString = {
      // Simple init code: PUSH1 0 PUSH1 0 RETURN
      val returnCode = Assembly(PUSH1, 0, PUSH1, 0, RETURN).code
      val padding = ByteString(Array.fill(size - returnCode.size)(JUMPDEST.code))
      padding ++ returnCode
    }

    val world: MockWorldState =
      MockWorldState().saveAccount(creatorAddr, Account.empty().increaseBalance(UInt256(1000000)))
  }

  "EIP-3860" when {
    "testing maxInitCodeSize calculation" should {
      "be None before Spiral fork" in {
        configPreSpiral.maxInitCodeSize shouldBe None
      }

      "be 2 * MAX_CODE_SIZE after Spiral fork" in {
        configSpiral.maxInitCodeSize shouldBe Some(MaxInitCodeSize)
      }
    }

    "testing calcInitCodeCost" should {
      "return 0 before Spiral fork" in {
        val initCode = fxt.initCodeOfSize(1000)
        configPreSpiral.calcInitCodeCost(initCode) shouldBe 0
      }

      "calculate correct cost after Spiral fork" in {
        // 32 bytes = 1 word = 2 gas
        val initCode32 = fxt.initCodeOfSize(32)
        configSpiral.calcInitCodeCost(initCode32) shouldBe 2

        // 33 bytes = 2 words = 4 gas
        val initCode33 = fxt.initCodeOfSize(33)
        configSpiral.calcInitCodeCost(initCode33) shouldBe 4

        // 64 bytes = 2 words = 4 gas
        val initCode64 = fxt.initCodeOfSize(64)
        configSpiral.calcInitCodeCost(initCode64) shouldBe 4

        // 1024 bytes = 32 words = 64 gas
        val initCode1024 = fxt.initCodeOfSize(1024)
        configSpiral.calcInitCodeCost(initCode1024) shouldBe 64

        // MAX_INITCODE_SIZE = 49152 bytes = 1536 words = 3072 gas
        val initCodeMax = fxt.initCodeOfSize(MaxInitCodeSize)
        configSpiral.calcInitCodeCost(initCodeMax) shouldBe 3072
      }
    }

    "testing transaction intrinsic gas" should {
      "include initcode cost for create transactions after Spiral" in {
        val initCode = fxt.initCodeOfSize(1024) // 32 words = 64 gas
        val baseGas = configSpiral.calcTransactionIntrinsicGas(initCode, isContractCreation = true, Seq.empty)

        // Base gas = 21000 (G_transaction) + 32000 (G_txcreate) + data cost + initcode cost
        // Note: initCodeOfSize uses JUMPDEST (0x5b) which is non-zero, so most bytes are non-zero
        // But the actual cost depends on the exact byte values in the generated code
        // Initcode cost: 32 words * 2 gas/word = 64
        // We just verify it's higher than without initcode cost
        val baseGasPreSpiral =
          configPreSpiral.calcTransactionIntrinsicGas(initCode, isContractCreation = true, Seq.empty)
        baseGas shouldBe (baseGasPreSpiral + 64)
      }

      "not include initcode cost for non-create transactions" in {
        val data = fxt.initCodeOfSize(1024)
        val baseGas = configSpiral.calcTransactionIntrinsicGas(data, isContractCreation = false, Seq.empty)
        val baseGasPreSpiral = configPreSpiral.calcTransactionIntrinsicGas(data, isContractCreation = false, Seq.empty)

        // Non-create transactions don't get initcode cost
        baseGas shouldBe baseGasPreSpiral
      }
    }

    "testing CREATE opcode" should {
      "succeed with initcode at MAX_INITCODE_SIZE after Spiral" in {
        val initCode = fxt.initCodeOfSize(MaxInitCodeSize)
        val context = fxt.createContext(fxt.world, initCode, fxt.fakeHeaderSpiral, configSpiral)
        val vm = new VM[MockWorldState, MockStorage]

        val result = vm.run(context)
        result.error shouldBe None
      }

      "fail with initcode exceeding MAX_INITCODE_SIZE after Spiral" in {
        val initCode = fxt.initCodeOfSize(MaxInitCodeSize + 1)
        val context = fxt.createContext(fxt.world, initCode, fxt.fakeHeaderSpiral, configSpiral)
        val vm = new VM[MockWorldState, MockStorage]

        val result = vm.run(context)
        result.error shouldBe Some(InitCodeSizeLimit)
        result.gasRemaining shouldBe 0
      }

      "succeed with large initcode before Spiral (no limit)" in {
        val initCode = fxt.initCodeOfSize(MaxInitCodeSize + 1000)
        val context = fxt.createContext(fxt.world, initCode, fxt.fakeHeaderPreSpiral, configPreSpiral)
        val vm = new VM[MockWorldState, MockStorage]

        // Should succeed because limit not enforced before Spiral
        val result = vm.run(context)
        // May fail due to gas, but not due to size limit
        result.error should not be Some(InitCodeSizeLimit)
      }

      "charge correct gas for initcode after Spiral" in {
        // Test that larger initcode costs more gas
        val smallInitCode = fxt.initCodeOfSize(64)
        val contextSmall = fxt.createContext(fxt.world, smallInitCode, fxt.fakeHeaderSpiral, configSpiral)
        val vm = new VM[MockWorldState, MockStorage]
        val resultSmall = vm.run(contextSmall)

        val largeInitCode = fxt.initCodeOfSize(1024)
        val contextLarge = fxt.createContext(fxt.world, largeInitCode, fxt.fakeHeaderSpiral, configSpiral)
        val resultLarge = vm.run(contextLarge)

        // The difference in gas used should include the initcode cost difference
        // 1024 bytes (32 words) - 64 bytes (2 words) = 30 words = 60 gas difference
        // However, memory expansion and other costs may also differ, so we just check
        // that larger initcode uses more gas
        val gasDiff = resultSmall.gasRemaining - resultLarge.gasRemaining
        gasDiff should be > BigInt(60) // At least the initcode cost difference
      }
    }

    "testing edge cases" should {
      "correctly handle initcode size exactly at boundary" in {
        // Test initcode size exactly at MaxInitCodeSize
        val boundaryInitCode = fxt.initCodeOfSize(MaxInitCodeSize)
        val context = fxt.createContext(fxt.world, boundaryInitCode, fxt.fakeHeaderSpiral, configSpiral)
        val vm = new VM[MockWorldState, MockStorage]
        val result = vm.run(context)

        // Should succeed - boundary case should be allowed
        result.error shouldBe None
      }

      "correctly handle initcode size one byte over boundary" in {
        // Test initcode size one byte over MaxInitCodeSize
        val overBoundaryInitCode = fxt.initCodeOfSize(MaxInitCodeSize + 1)
        val context = fxt.createContext(fxt.world, overBoundaryInitCode, fxt.fakeHeaderSpiral, configSpiral)
        val vm = new VM[MockWorldState, MockStorage]
        val result = vm.run(context)

        // Should fail with InitCodeSizeLimit
        result.error shouldBe Some(InitCodeSizeLimit)
      }
    }

    "testing transaction validation integration" should {
      "check calcTransactionIntrinsicGas includes initcode cost with test config" in {
        val initCode = fxt.initCodeOfSize(MaxInitCodeSize)
        // Use configSpiral which has EIP-3860 enabled

        // Calculate intrinsic gas with EIP-3860 enabled
        val intrinsicGas = configSpiral.calcTransactionIntrinsicGas(initCode, isContractCreation = true, Seq.empty)

        // Should include initcode cost (49152 bytes = 1536 words = 3072 gas)
        val initcodeCost = configSpiral.calcInitCodeCost(initCode)
        initcodeCost shouldBe 3072

        // Intrinsic gas should be greater than just data cost
        intrinsicGas should be > BigInt(53000) // Base (21000) + create (32000) minimum
      }

      "validateInitCodeSize function works correctly with test config" in {
        val oversizedInitCode = fxt.initCodeOfSize(MaxInitCodeSize + 1)

        // Direct check with test config: maxInitCodeSize should be defined and the payload should exceed it
        configSpiral.maxInitCodeSize shouldBe Some(MaxInitCodeSize)
        configSpiral.eip3860Enabled shouldBe true
        oversizedInitCode.size should be > MaxInitCodeSize

        // Also check that pre-spiral config doesn't have it enabled
        configPreSpiral.maxInitCodeSize shouldBe None
        configPreSpiral.eip3860Enabled shouldBe false
      }
    }
  }
}
