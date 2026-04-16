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

/** Tests for EIP-1153: Transient Storage Opcodes (TLOAD 0x5c, TSTORE 0x5d) https://eips.ethereum.org/EIPS/eip-1153
  *
  * TSTORE: pop key and value from stack, store in transient storage scoped to (address, key) TLOAD: pop key from stack,
  * push value from transient storage (or 0 if not set) Transient storage is cleared at end of transaction.
  */
class OlympiaTransientStorageSpec extends AnyWordSpec with Matchers {

  val configPreOlympia: EvmConfig = EvmConfig.SpiralConfigBuilder(blockchainConfig)
  val configOlympia: EvmConfig = EvmConfig.OlympiaConfigBuilder(blockchainConfig)

  object fxt {
    val ownerAddr: Address = Address(0xcafe)
    val callerAddr: Address = Address(0xca11)
    val otherAddr: Address = Address(0xbeef)

    val headerOlympia: BlockHeader =
      BlockFixtures.ValidBlock.header.copy(number = Fixtures.OlympiaBlockNumber)

    val headerPreOlympia: BlockHeader =
      BlockFixtures.ValidBlock.header.copy(number = Fixtures.SpiralBlockNumber)

    // TSTORE value 42 at key 0, then TLOAD key 0, STOP
    // Stack result: value 42 on top
    val codeTstoreTload: Assembly = Assembly(
      PUSH1,
      42, // value
      PUSH1,
      0, // key
      TSTORE, // store 42 at key 0
      PUSH1,
      0, // key
      TLOAD, // load key 0 → should get 42
      STOP
    )

    // TLOAD key 0 without prior TSTORE — should return 0
    val codeTloadUnset: Assembly = Assembly(
      PUSH1,
      0, // key
      TLOAD, // load unset key → should get 0
      STOP
    )

    // TSTORE at key 0, TSTORE at key 1, TLOAD key 0, TLOAD key 1, STOP
    val codeMultipleKeys: Assembly = Assembly(
      PUSH1,
      42, // value
      PUSH1,
      0, // key 0
      TSTORE,
      PUSH1,
      99, // value
      PUSH1,
      1, // key 1
      TSTORE,
      PUSH1,
      0, // key 0
      TLOAD, // → 42
      PUSH1,
      1, // key 1
      TLOAD, // → 99
      STOP
    )

    // TSTORE value, then overwrite with different value at same key
    val codeOverwrite: Assembly = Assembly(
      PUSH1,
      42, // first value
      PUSH1,
      0, // key
      TSTORE,
      PUSH1,
      99, // second value (overwrite)
      PUSH1,
      0, // same key
      TSTORE,
      PUSH1,
      0, // key
      TLOAD, // should get 99 (overwritten)
      STOP
    )

    // TSTORE value 0 (clear) at key that was previously set
    val codeClear: Assembly = Assembly(
      PUSH1,
      42, // value
      PUSH1,
      0, // key
      TSTORE,
      PUSH1,
      0, // value = 0 (clear)
      PUSH1,
      0, // same key
      TSTORE,
      PUSH1,
      0, // key
      TLOAD, // should get 0 (cleared)
      STOP
    )

    // Just TLOAD — for gas measurement
    val codeTloadOnly: Assembly = Assembly(
      PUSH1,
      0,
      TLOAD,
      STOP
    )

    // Just TSTORE — for gas measurement
    val codeTstoreOnly: Assembly = Assembly(
      PUSH1,
      42,
      PUSH1,
      0,
      TSTORE,
      STOP
    )

    def createContext(
        code: ByteString,
        header: BlockHeader,
        config: EvmConfig,
        startGas: BigInt = 1000000,
        staticCtx: Boolean = false,
        transientStorage: Map[(Address, BigInt), BigInt] = Map.empty
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
        staticCtx = staticCtx,
        originalWorld = world,
        warmAddresses = Set(ownerAddr),
        warmStorage = Set.empty,
        transientStorage = transientStorage
      )
    }
  }

  import fxt._

  "EIP-1153 Transient Storage" when {

    "TLOAD and TSTORE are used together" should {

      "store and retrieve a value" taggedAs (UnitTest, VMTest, OlympiaTest) in {
        val context = createContext(codeTstoreTload.code, headerOlympia, configOlympia)
        val vm = new VM[MockWorldState, MockStorage]
        val result = vm.run(context)

        result.error shouldBe None
      }

      "return 0 for unset keys" taggedAs (UnitTest, VMTest, OlympiaTest) in {
        val context = createContext(codeTloadUnset.code, headerOlympia, configOlympia)
        val vm = new VM[MockWorldState, MockStorage]
        val result = vm.run(context)

        result.error shouldBe None
      }

      "store and retrieve multiple keys independently" taggedAs (UnitTest, VMTest, OlympiaTest) in {
        val context = createContext(codeMultipleKeys.code, headerOlympia, configOlympia)
        val vm = new VM[MockWorldState, MockStorage]
        val result = vm.run(context)

        result.error shouldBe None
      }

      "overwrite existing value at same key" taggedAs (UnitTest, VMTest, OlympiaTest) in {
        val context = createContext(codeOverwrite.code, headerOlympia, configOlympia)
        val vm = new VM[MockWorldState, MockStorage]
        val result = vm.run(context)

        result.error shouldBe None
      }

      "clear value by storing zero" taggedAs (UnitTest, VMTest, OlympiaTest) in {
        val context = createContext(codeClear.code, headerOlympia, configOlympia)
        val vm = new VM[MockWorldState, MockStorage]
        val result = vm.run(context)

        result.error shouldBe None
      }
    }

    "transient storage isolation" should {

      "be scoped to (address, key) — different addresses don't interfere" taggedAs (UnitTest, VMTest, OlympiaTest) in {
        // Pre-populate transient storage from a different address
        val preExisting = Map[(Address, BigInt), BigInt](
          (otherAddr, BigInt(0)) -> BigInt(999)
        )
        val context = createContext(
          codeTloadUnset.code,
          headerOlympia,
          configOlympia,
          transientStorage = preExisting
        )
        val vm = new VM[MockWorldState, MockStorage]
        val result = vm.run(context)

        result.error shouldBe None
        // ownerAddr's transient storage at key 0 should be 0, not 999
        // (because otherAddr's storage is separate)
      }

      "preserve transient storage in result for downstream calls" taggedAs (UnitTest, VMTest, OlympiaTest) in {
        val context = createContext(codeTstoreOnly.code, headerOlympia, configOlympia)
        val vm = new VM[MockWorldState, MockStorage]
        val result = vm.run(context)

        result.error shouldBe None
        // Transient storage should contain the stored value
        (result.transientStorage should contain).key((ownerAddr, BigInt(0)))
        result.transientStorage((ownerAddr, BigInt(0))) shouldEqual BigInt(42)
      }

      "not affect persistent storage (world state unchanged)" taggedAs (UnitTest, VMTest, OlympiaTest) in {
        val context = createContext(codeTstoreOnly.code, headerOlympia, configOlympia)
        val vm = new VM[MockWorldState, MockStorage]
        val result = vm.run(context)

        result.error shouldBe None
        // Persistent storage should be unchanged
        result.world.getStorage(ownerAddr) shouldEqual MockStorage.Empty
      }
    }

    "gas costs" should {

      "charge G_warm_storage_read (100) for TLOAD" taggedAs (UnitTest, VMTest, OlympiaTest) in {
        val context = createContext(codeTloadOnly.code, headerOlympia, configOlympia)
        val vm = new VM[MockWorldState, MockStorage]
        val result = vm.run(context)

        val expectedGas = configOlympia.feeSchedule.G_verylow + // PUSH1
          configOlympia.feeSchedule.G_warm_storage_read // TLOAD

        val gasUsed = context.startGas - result.gasRemaining
        gasUsed shouldEqual expectedGas
      }

      "charge G_warm_storage_read (100) for TSTORE" taggedAs (UnitTest, VMTest, OlympiaTest) in {
        val context = createContext(codeTstoreOnly.code, headerOlympia, configOlympia)
        val vm = new VM[MockWorldState, MockStorage]
        val result = vm.run(context)

        val expectedGas = configOlympia.feeSchedule.G_verylow + // PUSH1 (value)
          configOlympia.feeSchedule.G_verylow + // PUSH1 (key)
          configOlympia.feeSchedule.G_warm_storage_read // TSTORE

        val gasUsed = context.startGas - result.gasRemaining
        gasUsed shouldEqual expectedGas
      }
    }

    "static context" should {

      "allow TLOAD in static context" taggedAs (UnitTest, VMTest, OlympiaTest) in {
        val context = createContext(codeTloadOnly.code, headerOlympia, configOlympia, staticCtx = true)
        val vm = new VM[MockWorldState, MockStorage]
        val result = vm.run(context)

        result.error shouldBe None
      }

      "reject TSTORE in static context" taggedAs (UnitTest, VMTest, OlympiaTest) in {
        val context = createContext(codeTstoreOnly.code, headerOlympia, configOlympia, staticCtx = true)
        val vm = new VM[MockWorldState, MockStorage]
        val result = vm.run(context)

        result.error shouldBe Some(OpCodeNotAvailableInStaticContext(0x5d.toByte))
      }
    }

    "pre-Olympia" should {

      "reject TLOAD as invalid opcode" taggedAs (UnitTest, VMTest, OlympiaTest) in {
        val context = createContext(codeTloadOnly.code, headerPreOlympia, configPreOlympia)
        val vm = new VM[MockWorldState, MockStorage]
        val result = vm.run(context)

        result.error shouldBe Some(InvalidOpCode(0x5c.toByte))
      }

      "reject TSTORE as invalid opcode" taggedAs (UnitTest, VMTest, OlympiaTest) in {
        val context = createContext(codeTstoreOnly.code, headerPreOlympia, configPreOlympia)
        val vm = new VM[MockWorldState, MockStorage]
        val result = vm.run(context)

        result.error shouldBe Some(InvalidOpCode(0x5d.toByte))
      }
    }

    "opcode list configuration" should {

      "include TLOAD in Olympia opcode list" taggedAs (UnitTest, VMTest, OlympiaTest) in {
        configOlympia.byteToOpCode.get(0x5c.toByte) shouldBe Some(TLOAD)
      }

      "include TSTORE in Olympia opcode list" taggedAs (UnitTest, VMTest, OlympiaTest) in {
        configOlympia.byteToOpCode.get(0x5d.toByte) shouldBe Some(TSTORE)
      }

      "not include TLOAD in Spiral opcode list" taggedAs (UnitTest, VMTest, OlympiaTest) in {
        configPreOlympia.byteToOpCode.get(0x5c.toByte) shouldBe None
      }

      "not include TSTORE in Spiral opcode list" taggedAs (UnitTest, VMTest, OlympiaTest) in {
        configPreOlympia.byteToOpCode.get(0x5d.toByte) shouldBe None
      }
    }
  }
}
