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

/** L7 — ETC fork-specific EVM semantics: behavioral execution tests.
  *
  * Complements L2 (opcode availability via opCodes set membership) with tests that execute bytecode and verify the
  * results.
  *
  * Fixtures.blockchainConfig uses chainId=0x3d=61 (ETC mainnet). Fork blocks: Atlantis=0, Agharta=0, Phoenix=600,
  * Mystique=800, Spiral=900, Olympia=1000.
  */
class ETCForkEVMSpec extends AnyWordSpec with Matchers {

  // ── EVM configs at representative block heights ───────────────────────────

  val configAtlantis: EvmConfig = EvmConfig.AtlantisConfigBuilder(blockchainConfig)
  val configAgharta: EvmConfig = EvmConfig.AghartaConfigBuilder(blockchainConfig)
  val configPhoenix: EvmConfig = EvmConfig.PhoenixConfigBuilder(blockchainConfig)
  val configMystique: EvmConfig = EvmConfig.MystiqueConfigBuilder(blockchainConfig)
  val configSpiral: EvmConfig = EvmConfig.SpiralConfigBuilder(blockchainConfig)
  val configOlympia: EvmConfig = EvmConfig.OlympiaConfigBuilder(blockchainConfig)

  // ── Block headers at representative heights ───────────────────────────────

  val hdrAtlantis: BlockHeader = BlockFixtures.ValidBlock.header.copy(number = 0)
  val hdrPhoenix: BlockHeader = BlockFixtures.ValidBlock.header.copy(number = Fixtures.PhoenixBlockNumber)
  val hdrMystique: BlockHeader = BlockFixtures.ValidBlock.header.copy(number = Fixtures.MystiqueBlockNumber)
  val hdrSpiral: BlockHeader = BlockFixtures.ValidBlock.header.copy(number = Fixtures.SpiralBlockNumber)
  val hdrOlympia: BlockHeader =
    BlockFixtures.ValidBlock.header.copy(
      number = Fixtures.OlympiaBlockNumber,
      extraFields = HefPostOlympia(BigInt("1000000000")) // 1 Gwei base fee
    )

  // ── Helpers ───────────────────────────────────────────────────────────────

  val callerAddr: Address = Address(0xca11e4)
  val contractAddr: Address = Address(0xc0de00)

  /** Base world: caller and contract both present with small balance. */
  def baseWorld(contractBalance: UInt256 = UInt256(500)): MockWorldState =
    MockWorldState()
      .saveAccount(callerAddr, Account.empty().increaseBalance(UInt256(1000)))
      .saveAccount(contractAddr, Account.empty().increaseBalance(contractBalance))

  def runCode(
      code: Assembly,
      header: BlockHeader,
      config: EvmConfig,
      startGas: BigInt = 1_000_000,
      contractBalance: UInt256 = UInt256(500)
  ): ProgramResult[MockWorldState, MockStorage] = {
    val world = baseWorld(contractBalance).saveCode(contractAddr, code.code)
    val ctx = ProgramContext(
      callerAddr = callerAddr,
      originAddr = callerAddr,
      recipientAddr = Some(contractAddr),
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
      warmAddresses = Set.empty,
      warmStorage = Set.empty
    )
    new VM[MockWorldState, MockStorage].run(ctx)
  }

  // Encode a 32-byte value into ByteString (big-endian, zero-padded)
  def bytes32(n: BigInt): ByteString = {
    val bytes = n.toByteArray.takeRight(32)
    ByteString(Array.fill(32 - bytes.length)(0.toByte) ++ bytes)
  }

  // ── Atlantis (≈ Byzantium): REVERT ───────────────────────────────────────

  "Atlantis EVM (block 0)" when {

    "REVERT opcode is executed" should {

      "set RevertOccurs error and preserve gas" taggedAs (UnitTest, VMTest) in {
        val code = Assembly(
          PUSH1,
          0, // return size = 0
          PUSH1,
          0, // return offset = 0
          REVERT // revert with empty data
        )
        val result = runCode(code, hdrAtlantis, configAtlantis)
        result.error shouldBe Some(RevertOccurs)
      }

      "leave gas remaining (unlike INVALID which burns all gas)" taggedAs (UnitTest, VMTest) in {
        val code = Assembly(PUSH1, 0, PUSH1, 0, REVERT)
        val result = runCode(code, hdrAtlantis, configAtlantis, startGas = 100_000)
        result.error shouldBe Some(RevertOccurs)
        result.gasRemaining should be > BigInt(0)
      }
    }

    "RETURNDATACOPY is in the opcode set" should {

      "be available at Atlantis" taggedAs (UnitTest, VMTest) in {
        configAtlantis.opCodes should contain(RETURNDATACOPY)
      }
    }
  }

  // ── Agharta (≈ Constantinople): bitwise shift opcodes ────────────────────

  "Agharta EVM (block 0)" when {

    "SHL (shift left) is executed" should {

      "shift 2 left by 3 bits to produce 16" taggedAs (UnitTest, VMTest) in {
        // Stack: push value first, then shift amount (shift is TOS for SHL)
        val code = Assembly(
          PUSH1,
          2, // value
          PUSH1,
          3, // shift amount (TOS)
          SHL,
          PUSH1,
          0,
          MSTORE,
          PUSH1,
          32,
          PUSH1,
          0,
          RETURN
        )
        val result = runCode(code, hdrAtlantis, configAgharta)
        result.error shouldBe None
        result.returnData shouldBe bytes32(BigInt(16))
      }
    }

    "SHR (logical shift right) is executed" should {

      "shift 16 right by 2 bits to produce 4" taggedAs (UnitTest, VMTest) in {
        val code = Assembly(
          PUSH1,
          16, // value
          PUSH1,
          2, // shift amount (TOS)
          SHR,
          PUSH1,
          0,
          MSTORE,
          PUSH1,
          32,
          PUSH1,
          0,
          RETURN
        )
        val result = runCode(code, hdrAtlantis, configAgharta)
        result.error shouldBe None
        result.returnData shouldBe bytes32(BigInt(4))
      }
    }

    "SAR (arithmetic shift right) is executed" should {

      "shift a positive value right by 1 bit (same as SHR for positives)" taggedAs (UnitTest, VMTest) in {
        val code = Assembly(
          PUSH1,
          8, // value
          PUSH1,
          1, // shift (TOS)
          SAR,
          PUSH1,
          0,
          MSTORE,
          PUSH1,
          32,
          PUSH1,
          0,
          RETURN
        )
        val result = runCode(code, hdrAtlantis, configAgharta)
        result.error shouldBe None
        result.returnData shouldBe bytes32(BigInt(4))
      }
    }
  }

  // ── Phoenix (≈ Istanbul): CHAINID and SELFBALANCE ─────────────────────────

  "Phoenix EVM (block 600)" when {

    "CHAINID is executed" should {

      "return ETC mainnet chain ID (61 = 0x3d)" taggedAs (UnitTest, VMTest) in {
        val code = Assembly(
          CHAINID,
          PUSH1,
          0,
          MSTORE,
          PUSH1,
          32,
          PUSH1,
          0,
          RETURN
        )
        val result = runCode(code, hdrPhoenix, configPhoenix)
        result.error shouldBe None
        result.returnData shouldBe bytes32(BigInt(61))
      }
    }

    "SELFBALANCE is executed" should {

      "return the contract's own balance" taggedAs (UnitTest, VMTest) in {
        val contractBalance = UInt256(12345)
        val code = Assembly(
          SELFBALANCE,
          PUSH1,
          0,
          MSTORE,
          PUSH1,
          32,
          PUSH1,
          0,
          RETURN
        )
        val result = runCode(code, hdrPhoenix, configPhoenix, contractBalance = contractBalance)
        result.error shouldBe None
        result.returnData shouldBe bytes32(BigInt(12345))
      }

      "return 0 for a contract with no balance" taggedAs (UnitTest, VMTest) in {
        val code = Assembly(
          SELFBALANCE,
          PUSH1,
          0,
          MSTORE,
          PUSH1,
          32,
          PUSH1,
          0,
          RETURN
        )
        val result = runCode(code, hdrPhoenix, configPhoenix, contractBalance = UInt256.Zero)
        result.error shouldBe None
        result.returnData shouldBe bytes32(BigInt(0))
      }
    }
  }

  // ── Spiral (≈ Shanghai): PUSH0 ────────────────────────────────────────────

  "Spiral EVM (block 900)" when {

    "PUSH0 is executed" should {

      "push 0 onto the stack" taggedAs (UnitTest, VMTest) in {
        val code = Assembly(
          PUSH0,
          PUSH1,
          0,
          MSTORE,
          PUSH1,
          32,
          PUSH1,
          0,
          RETURN
        )
        val result = runCode(code, hdrSpiral, configSpiral)
        result.error shouldBe None
        result.returnData shouldBe bytes32(BigInt(0))
      }

      "be usable in place of PUSH1 0 for stack-zeroing" taggedAs (UnitTest, VMTest) in {
        // PUSH0 costs G_base; PUSH1 0 costs G_verylow — PUSH0 is cheaper
        val codePush0 = Assembly(PUSH0, STOP)
        val codePush1 = Assembly(PUSH1, 0, STOP)
        val r0 = runCode(codePush0, hdrSpiral, configSpiral)
        val r1 = runCode(codePush1, hdrSpiral, configSpiral)
        r0.error shouldBe None
        r1.error shouldBe None
        // PUSH0 is cheaper (G_base=2 vs G_verylow=3 for PUSH1)
        r0.gasRemaining should be > r1.gasRemaining
      }
    }

    "PUSH0 is NOT in the opcode set before Spiral" should {

      "be absent at Mystique (block 800)" taggedAs (UnitTest, VMTest) in {
        configMystique.opCodes should not contain PUSH0
      }
    }
  }

  // ── Olympia: BASEFEE opcode ───────────────────────────────────────────────

  "Olympia EVM (block 1000)" when {

    "BASEFEE opcode availability" should {

      "be present in opCodes at Olympia" taggedAs (UnitTest, VMTest) in {
        configOlympia.opCodes should contain(BASEFEE)
      }

      "be absent in opCodes pre-Olympia (Spiral block 900)" taggedAs (UnitTest, VMTest) in {
        configSpiral.opCodes should not contain BASEFEE
      }
    }

    "BASEFEE is executed with a post-Olympia header" should {

      "return the block's base fee (1 Gwei = 1_000_000_000)" taggedAs (UnitTest, VMTest) in {
        val code = Assembly(
          BASEFEE,
          PUSH1,
          0,
          MSTORE,
          PUSH1,
          32,
          PUSH1,
          0,
          RETURN
        )
        val result = runCode(code, hdrOlympia, configOlympia)
        result.error shouldBe None
        result.returnData shouldBe bytes32(BigInt("1000000000"))
      }

      "return 0 when header has no baseFee field (pre-Olympia header at Olympia config)" taggedAs (
        UnitTest,
        VMTest
      ) in {
        // Legacy header has no extraFields → baseFee = None → BASEFEE opcode returns 0
        val code = Assembly(BASEFEE, PUSH1, 0, MSTORE, PUSH1, 32, PUSH1, 0, RETURN)
        val result = runCode(code, hdrSpiral, configOlympia) // Spiral header, Olympia config
        result.error shouldBe None
        result.returnData shouldBe bytes32(BigInt(0))
      }
    }
  }
}
// scalastyle:on magic.number
