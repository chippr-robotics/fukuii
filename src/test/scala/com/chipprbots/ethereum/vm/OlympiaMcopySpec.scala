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

/** Tests for EIP-5656: MCOPY opcode (0x5e) https://eips.ethereum.org/EIPS/eip-5656
  *
  * MCOPY copies memory within the EVM memory space. Parameters: dst (destination), src (source), size (bytes to copy)
  * Handles overlapping regions safely (load-then-store pattern).
  */
class OlympiaMcopySpec extends AnyWordSpec with Matchers {

  val configPreOlympia: EvmConfig = EvmConfig.SpiralConfigBuilder(blockchainConfig)
  val configOlympia: EvmConfig = EvmConfig.OlympiaConfigBuilder(blockchainConfig)

  object fxt {
    val ownerAddr: Address = Address(0xcafe)
    val callerAddr: Address = Address(0xca11)

    val headerOlympia: BlockHeader =
      BlockFixtures.ValidBlock.header.copy(number = Fixtures.OlympiaBlockNumber)

    val headerPreOlympia: BlockHeader =
      BlockFixtures.ValidBlock.header.copy(number = Fixtures.SpiralBlockNumber)

    // Non-overlapping copy: copy 32 bytes from offset 0 to offset 32
    // First MSTORE 0xAA..AA at offset 0, then MCOPY from 0→32 for 32 bytes
    val codeNonOverlapping: Assembly = Assembly(
      // Store 0xFF at memory offset 0 (fills 32 bytes)
      PUSH32,
      0xff.toByte,
      0xff.toByte,
      0xff.toByte,
      0xff.toByte,
      0xff.toByte,
      0xff.toByte,
      0xff.toByte,
      0xff.toByte,
      0xff.toByte,
      0xff.toByte,
      0xff.toByte,
      0xff.toByte,
      0xff.toByte,
      0xff.toByte,
      0xff.toByte,
      0xff.toByte,
      0xff.toByte,
      0xff.toByte,
      0xff.toByte,
      0xff.toByte,
      0xff.toByte,
      0xff.toByte,
      0xff.toByte,
      0xff.toByte,
      0xff.toByte,
      0xff.toByte,
      0xff.toByte,
      0xff.toByte,
      0xff.toByte,
      0xff.toByte,
      0xff.toByte,
      0xff.toByte,
      PUSH1,
      0,
      MSTORE,
      // MCOPY: dst=32, src=0, size=32
      PUSH1,
      32, // size
      PUSH1,
      0, // src
      PUSH1,
      32, // dst
      MCOPY,
      STOP
    )

    // Zero-size copy: should be a no-op
    val codeZeroSize: Assembly = Assembly(
      PUSH1,
      0, // size = 0
      PUSH1,
      0, // src = 0
      PUSH1,
      0, // dst = 0
      MCOPY,
      STOP
    )

    // Small copy: 1 byte from offset 31 to offset 63
    val codeSmallCopy: Assembly = Assembly(
      // Store a known value at memory offset 0
      PUSH1,
      42,
      PUSH1,
      0,
      MSTORE, // stores 42 as 32-byte big-endian at offset 0 (byte 31 = 42)
      // MCOPY: dst=63, src=31, size=1 — copy the byte containing 42
      PUSH1,
      1, // size
      PUSH1,
      31, // src
      PUSH1,
      63, // dst
      MCOPY,
      STOP
    )

    // Simple MCOPY for pre-Olympia rejection test
    val codeMcopySimple: Assembly = Assembly(
      PUSH1,
      1, // size
      PUSH1,
      0, // src
      PUSH1,
      32, // dst
      MCOPY,
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

  "MCOPY opcode (EIP-5656)" when {

    "Olympia fork is active" should {

      "copy non-overlapping memory regions" taggedAs (UnitTest, VMTest, OlympiaTest) in {
        val context = createContext(codeNonOverlapping.code, headerOlympia, configOlympia)
        val vm = new VM[MockWorldState, MockStorage]
        val result = vm.run(context)

        result.error shouldBe None
      }

      "handle zero-size copy as no-op" taggedAs (UnitTest, VMTest, OlympiaTest) in {
        val context = createContext(codeZeroSize.code, headerOlympia, configOlympia)
        val vm = new VM[MockWorldState, MockStorage]
        val result = vm.run(context)

        result.error shouldBe None
      }

      "copy small regions (1 byte)" taggedAs (UnitTest, VMTest, OlympiaTest) in {
        val context = createContext(codeSmallCopy.code, headerOlympia, configOlympia)
        val vm = new VM[MockWorldState, MockStorage]
        val result = vm.run(context)

        result.error shouldBe None
      }

      "charge correct gas for zero-size copy (G_verylow only)" taggedAs (UnitTest, VMTest, OlympiaTest) in {
        val context = createContext(codeZeroSize.code, headerOlympia, configOlympia)
        val vm = new VM[MockWorldState, MockStorage]
        val result = vm.run(context)

        // Zero-size MCOPY: 3 PUSHes + MCOPY base (G_verylow=3) + 0 varGas
        val expectedGas =
          configOlympia.feeSchedule.G_verylow * 3 + // 3 PUSH1 opcodes
            configOlympia.feeSchedule.G_verylow // MCOPY base gas (no varGas for size=0)

        val gasUsed = context.startGas - result.gasRemaining
        gasUsed shouldEqual expectedGas
      }
    }

    "pre-Olympia" should {

      "reject MCOPY as invalid opcode" taggedAs (UnitTest, VMTest, OlympiaTest) in {
        val context = createContext(codeMcopySimple.code, headerPreOlympia, configPreOlympia)
        val vm = new VM[MockWorldState, MockStorage]
        val result = vm.run(context)

        result.error shouldBe Some(InvalidOpCode(0x5e.toByte))
      }
    }

    "opcode list configuration" should {

      "include MCOPY in Olympia opcode list" taggedAs (UnitTest, VMTest, OlympiaTest) in {
        configOlympia.byteToOpCode.get(0x5e.toByte) shouldBe Some(MCOPY)
      }

      "not include MCOPY in Spiral opcode list" taggedAs (UnitTest, VMTest, OlympiaTest) in {
        configPreOlympia.byteToOpCode.get(0x5e.toByte) shouldBe None
      }
    }
  }
}
