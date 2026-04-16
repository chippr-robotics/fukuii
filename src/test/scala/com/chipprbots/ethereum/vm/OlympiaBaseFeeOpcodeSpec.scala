package com.chipprbots.ethereum.vm

import org.apache.pekko.util.ByteString
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.Fixtures.{Blocks => BlockFixtures}
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostOlympia
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.testing.Tags._

import Fixtures.blockchainConfig

/** Tests for EIP-3198: BASEFEE opcode (0x48) https://eips.ethereum.org/EIPS/eip-3198
  *
  * BASEFEE pushes the current block's baseFee onto the stack. Returns 0 if baseFee is not set (pre-Olympia blocks).
  */
class OlympiaBaseFeeOpcodeSpec extends AnyWordSpec with Matchers {

  val configPreOlympia: EvmConfig = EvmConfig.SpiralConfigBuilder(blockchainConfig)
  val configOlympia: EvmConfig = EvmConfig.OlympiaConfigBuilder(blockchainConfig)

  object fxt {
    val ownerAddr: Address = Address(0xcafe)
    val callerAddr: Address = Address(0xca11)

    val headerPreOlympia: BlockHeader =
      BlockFixtures.ValidBlock.header.copy(number = Fixtures.SpiralBlockNumber)

    val baseFeeValue: BigInt = BigInt(7)

    val headerWithBaseFee: BlockHeader =
      BlockFixtures.ValidBlock.header.copy(
        number = Fixtures.OlympiaBlockNumber,
        extraFields = HefPostOlympia(baseFeeValue)
      )

    val headerWithLargeBaseFee: BlockHeader =
      BlockFixtures.ValidBlock.header.copy(
        number = Fixtures.OlympiaBlockNumber,
        extraFields = HefPostOlympia(BigInt("1000000000")) // 1 Gwei
      )

    val headerWithZeroBaseFee: BlockHeader =
      BlockFixtures.ValidBlock.header.copy(
        number = Fixtures.OlympiaBlockNumber,
        extraFields = HefPostOlympia(BigInt(0))
      )

    // BASEFEE followed by STOP — pushes baseFee to stack
    val codeBaseFee: Assembly = Assembly(BASEFEE, STOP)

    // BASEFEE, BASEFEE, ADD, STOP — pushes baseFee twice and adds them
    val codeBaseFeeDouble: Assembly = Assembly(BASEFEE, BASEFEE, ADD, STOP)

    // BASEFEE, PUSH1 0, MSTORE, STOP — store baseFee in memory
    val codeBaseFeeToMemory: Assembly = Assembly(
      BASEFEE,
      PUSH1,
      0,
      MSTORE,
      STOP
    )

    def createContext(
        code: ByteString,
        header: BlockHeader,
        config: EvmConfig,
        startGas: BigInt = 1000000
    ): ProgramContext[MockWorldState, MockStorage] = {
      val world = MockWorldState()
        .saveAccount(ownerAddr, Account(balance = UInt256(1000), nonce = 1))
        .saveCode(ownerAddr, code)

      ProgramContext(
        callerAddr = callerAddr,
        originAddr = callerAddr,
        recipientAddr = Some(ownerAddr),
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
        warmAddresses = Set(ownerAddr),
        warmStorage = Set.empty
      )
    }
  }

  import fxt._

  "BASEFEE opcode" when {

    "Olympia fork is active" should {

      "push baseFee value onto the stack" taggedAs (UnitTest, VMTest, OlympiaTest) in {
        val context = createContext(codeBaseFee.code, headerWithBaseFee, configOlympia)
        val vm = new VM[MockWorldState, MockStorage]
        val result = vm.run(context)

        result.error shouldBe None
        result.returnData shouldBe empty
      }

      "return correct baseFee value (7 wei)" taggedAs (UnitTest, VMTest, OlympiaTest) in {
        val context = createContext(codeBaseFeeToMemory.code, headerWithBaseFee, configOlympia)
        val vm = new VM[MockWorldState, MockStorage]
        val result = vm.run(context)

        result.error shouldBe None
      }

      "return zero when baseFee is zero" taggedAs (UnitTest, VMTest, OlympiaTest) in {
        val context = createContext(codeBaseFeeToMemory.code, headerWithZeroBaseFee, configOlympia)
        val vm = new VM[MockWorldState, MockStorage]
        val result = vm.run(context)

        result.error shouldBe None
      }

      "handle large baseFee values (1 Gwei)" taggedAs (UnitTest, VMTest, OlympiaTest) in {
        val context = createContext(codeBaseFee.code, headerWithLargeBaseFee, configOlympia)
        val vm = new VM[MockWorldState, MockStorage]
        val result = vm.run(context)

        result.error shouldBe None
      }

      "be usable in arithmetic operations" taggedAs (UnitTest, VMTest, OlympiaTest) in {
        val context = createContext(codeBaseFeeDouble.code, headerWithBaseFee, configOlympia)
        val vm = new VM[MockWorldState, MockStorage]
        val result = vm.run(context)

        result.error shouldBe None
      }

      "cost G_base gas (2)" taggedAs (UnitTest, VMTest, OlympiaTest) in {
        val context = createContext(codeBaseFee.code, headerWithBaseFee, configOlympia)
        val vm = new VM[MockWorldState, MockStorage]
        val result = vm.run(context)

        val expectedGas = configOlympia.feeSchedule.G_base // BASEFEE = G_base
        val gasUsed = context.startGas - result.gasRemaining

        gasUsed shouldEqual expectedGas
      }
    }

    "pre-Olympia (baseFee not in header)" should {

      "not be recognized as valid opcode" taggedAs (UnitTest, VMTest, OlympiaTest) in {
        // Pre-Olympia config does NOT include BASEFEE in the opcode list
        val context = createContext(codeBaseFee.code, headerPreOlympia, configPreOlympia)
        val vm = new VM[MockWorldState, MockStorage]
        val result = vm.run(context)

        // BASEFEE (0x48) should be treated as invalid opcode pre-Olympia
        result.error shouldBe Some(InvalidOpCode(0x48.toByte))
      }
    }

    "opcode list configuration" should {

      "include BASEFEE in Olympia opcode list" taggedAs (UnitTest, VMTest, OlympiaTest) in {
        configOlympia.byteToOpCode.get(0x48.toByte) shouldBe Some(BASEFEE)
      }

      "not include BASEFEE in Spiral opcode list" taggedAs (UnitTest, VMTest, OlympiaTest) in {
        configPreOlympia.byteToOpCode.get(0x48.toByte) shouldBe None
      }
    }
  }
}
