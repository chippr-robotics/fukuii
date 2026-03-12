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

/** Tests for EIP-6780: SELFDESTRUCT only in same transaction
  * https://eips.ethereum.org/EIPS/eip-6780
  *
  * Post-Olympia: SELFDESTRUCT only deletes the contract if it was created in the same transaction.
  * Pre-existing contracts only have their balance transferred to the beneficiary.
  */
class OlympiaSelfDestructSpec extends AnyWordSpec with Matchers {

  val configPreOlympia: EvmConfig = EvmConfig.SpiralConfigBuilder(blockchainConfig)
  val configOlympia: EvmConfig = EvmConfig.OlympiaConfigBuilder(blockchainConfig)

  object fxt {
    val ownerAddr: Address = Address(0xcafe)
    val beneficiaryAddr: Address = Address(0xface)
    val callerAddr: Address = Address(0xca11)

    val headerOlympia: BlockHeader =
      BlockFixtures.ValidBlock.header.copy(number = Fixtures.OlympiaBlockNumber)

    val headerPreOlympia: BlockHeader =
      BlockFixtures.ValidBlock.header.copy(number = Fixtures.SpiralBlockNumber)

    // SELFDESTRUCT sending balance to beneficiary
    val codeSelfDestruct: Assembly = Assembly(
      PUSH20,
      beneficiaryAddr.bytes,
      SELFDESTRUCT
    )

    val ownerBalance: UInt256 = UInt256(1000)
    val beneficiaryBalance: UInt256 = UInt256(500)

    def createContext(
        code: ByteString,
        header: BlockHeader,
        config: EvmConfig,
        world: MockWorldState,
        originalWorld: MockWorldState,
        startGas: BigInt = 1000000
    ): ProgramContext[MockWorldState, MockStorage] =
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
        originalWorld = originalWorld,
        warmAddresses = Set(ownerAddr),
        warmStorage = Set.empty
      )

    // World where owner is a pre-existing account (exists in original world)
    val worldPreExisting: MockWorldState = MockWorldState()
      .saveAccount(ownerAddr, Account(balance = ownerBalance, nonce = 1))
      .saveAccount(beneficiaryAddr, Account(balance = beneficiaryBalance))
      .saveCode(ownerAddr, codeSelfDestruct.code)

    // World where owner was just created (does NOT exist in original world)
    val worldNewContract: MockWorldState = MockWorldState()
      .saveAccount(ownerAddr, Account(balance = ownerBalance, nonce = 1))
      .saveAccount(beneficiaryAddr, Account(balance = beneficiaryBalance))
      .saveCode(ownerAddr, codeSelfDestruct.code)

    // Original world WITHOUT the owner — simulates "created in this tx"
    val originalWorldWithoutOwner: MockWorldState = MockWorldState()
      .saveAccount(beneficiaryAddr, Account(balance = beneficiaryBalance))
  }

  import fxt._

  "EIP-6780 SELFDESTRUCT restrictions" when {

    "Olympia fork (eip6780Enabled = true)" should {

      "have eip6780Enabled flag set" taggedAs (UnitTest, VMTest, OlympiaTest) in {
        configOlympia.eip6780Enabled shouldBe true
      }

      "not have eip6780Enabled pre-Olympia" taggedAs (UnitTest, VMTest, OlympiaTest) in {
        configPreOlympia.eip6780Enabled shouldBe false
      }

      "transfer balance but NOT delete pre-existing contract" taggedAs (UnitTest, VMTest, OlympiaTest) in {
        // Owner exists in original world → pre-existing contract
        val context = createContext(
          codeSelfDestruct.code,
          headerOlympia,
          configOlympia,
          world = worldPreExisting,
          originalWorld = worldPreExisting // owner exists in original
        )

        val vm = new VM[MockWorldState, MockStorage]
        val result = vm.run(context)

        result.error shouldBe None

        // Balance should be transferred
        result.world.getBalance(beneficiaryAddr) shouldEqual UInt256(
          beneficiaryBalance.toBigInt + ownerBalance.toBigInt
        )

        // But contract should NOT be marked for deletion
        result.addressesToDelete should not contain ownerAddr
      }

      "transfer balance AND delete contract created in same transaction" taggedAs (UnitTest, VMTest, OlympiaTest) in {
        // Owner does NOT exist in original world → created in this tx
        val context = createContext(
          codeSelfDestruct.code,
          headerOlympia,
          configOlympia,
          world = worldNewContract,
          originalWorld = originalWorldWithoutOwner // owner does NOT exist in original
        )

        val vm = new VM[MockWorldState, MockStorage]
        val result = vm.run(context)

        result.error shouldBe None

        // Balance should be transferred
        result.world.getBalance(beneficiaryAddr) shouldEqual UInt256(
          beneficiaryBalance.toBigInt + ownerBalance.toBigInt
        )

        // Contract SHOULD be marked for deletion (created in same tx)
        result.addressesToDelete should contain(ownerAddr)
      }
    }

    "pre-Olympia (eip6780Enabled = false)" should {

      "always delete contract regardless of creation time" taggedAs (UnitTest, VMTest, OlympiaTest) in {
        // Pre-existing contract — should still be deleted pre-Olympia
        val context = createContext(
          codeSelfDestruct.code,
          headerPreOlympia,
          configPreOlympia,
          world = worldPreExisting,
          originalWorld = worldPreExisting
        )

        val vm = new VM[MockWorldState, MockStorage]
        val result = vm.run(context)

        result.error shouldBe None
        result.addressesToDelete should contain(ownerAddr)
        result.world.getBalance(beneficiaryAddr) shouldEqual UInt256(
          beneficiaryBalance.toBigInt + ownerBalance.toBigInt
        )
      }
    }

    "edge cases" should {

      "handle self-destruct to self (removes all ether)" taggedAs (UnitTest, VMTest, OlympiaTest) in {
        // SELFDESTRUCT to own address
        val codeSelfDestructToSelf: Assembly = Assembly(
          PUSH20,
          ownerAddr.bytes,
          SELFDESTRUCT
        )

        val world = MockWorldState()
          .saveAccount(ownerAddr, Account(balance = ownerBalance, nonce = 1))
          .saveCode(ownerAddr, codeSelfDestructToSelf.code)

        val context = createContext(
          codeSelfDestructToSelf.code,
          headerOlympia,
          configOlympia,
          world = world,
          originalWorld = world // pre-existing
        )

        val vm = new VM[MockWorldState, MockStorage]
        val result = vm.run(context)

        result.error shouldBe None
        // Pre-existing: balance sent to self, but since same address, removeAllEther is called
        result.world.getBalance(ownerAddr) shouldEqual UInt256.Zero
        // Pre-existing in Olympia: should NOT be deleted
        result.addressesToDelete should not contain ownerAddr
      }

      "not be available in static context" taggedAs (UnitTest, VMTest, OlympiaTest) in {
        val context = createContext(
          codeSelfDestruct.code,
          headerOlympia,
          configOlympia,
          world = worldPreExisting,
          originalWorld = worldPreExisting
        ).copy(staticCtx = true)

        val vm = new VM[MockWorldState, MockStorage]
        val result = vm.run(context)

        result.error shouldBe Some(OpCodeNotAvailableInStaticContext(0xff.toByte))
      }
    }
  }
}
