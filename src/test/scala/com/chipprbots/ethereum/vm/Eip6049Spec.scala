package com.chipprbots.ethereum.vm

import org.apache.pekko.util.ByteString
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.Fixtures.{Blocks => BlockFixtures}
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.UInt256

import Fixtures.blockchainConfig

/** Tests for EIP-6049: Deprecate SELFDESTRUCT https://eips.ethereum.org/EIPS/eip-6049
  *
  * EIP-6049 is an informational EIP that deprecates SELFDESTRUCT but does NOT change its behavior. The opcode continues
  * to work exactly as before - this EIP only marks it as deprecated.
  */
class Eip6049Spec extends AnyWordSpec with Matchers {

  // Config before EIP-6049 (Mystique fork - no deprecation flag)
  val configPreEip6049: EvmConfig = EvmConfig.MystiqueConfigBuilder(blockchainConfig)

  // Config with EIP-6049 (Spiral fork - deprecation flag enabled)
  val configWithEip6049: EvmConfig = EvmConfig.SpiralConfigBuilder(blockchainConfig)

  object fxt {
    val ownerAddr: Address = Address(0x0123)
    val beneficiaryAddr: Address = Address(0xface)
    val otherAddr: Address = Address(0x9999)

    val fakeHeaderPreEip6049 = BlockFixtures.ValidBlock.header.copy(number = Fixtures.MystiqueBlockNumber)
    val fakeHeaderWithEip6049 = BlockFixtures.ValidBlock.header.copy(number = Fixtures.SpiralBlockNumber)

    // Code that calls SELFDESTRUCT
    val codeWithSelfDestruct: Assembly = Assembly(
      PUSH20,
      beneficiaryAddr.bytes,
      SELFDESTRUCT
    )

    def createContext(
        code: ByteString,
        header: com.chipprbots.ethereum.domain.BlockHeader,
        config: EvmConfig,
        startGas: BigInt = 1000000
    ): ProgramContext[MockWorldState, MockStorage] = {
      val world = MockWorldState()
        .saveAccount(ownerAddr, Account(balance = UInt256(1000), nonce = 1))
        .saveAccount(beneficiaryAddr, Account(balance = UInt256(500)))
        .saveAccount(otherAddr, Account(balance = UInt256(100)))
        .saveCode(ownerAddr, code)

      ProgramContext(
        callerAddr = ownerAddr,
        originAddr = ownerAddr,
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

  "EIP-6049" when {

    "configuration flag" should {

      "be false for pre-Spiral forks" in {
        configPreEip6049.eip6049DeprecationEnabled shouldBe false
      }

      "be true for Spiral fork and later" in {
        configWithEip6049.eip6049DeprecationEnabled shouldBe true
      }
    }

    "helper method isEip6049DeprecationEnabled" should {

      "return false for pre-Spiral forks" in {
        val mystiqueEtcFork = blockchainConfig.etcForkForBlockNumber(Fixtures.MystiqueBlockNumber)
        BlockchainConfigForEvm.isEip6049DeprecationEnabled(mystiqueEtcFork) shouldBe false

        val magnetoEtcFork = blockchainConfig.etcForkForBlockNumber(Fixtures.MagnetoBlockNumber)
        BlockchainConfigForEvm.isEip6049DeprecationEnabled(magnetoEtcFork) shouldBe false

        val phoenixEtcFork = blockchainConfig.etcForkForBlockNumber(Fixtures.PhoenixBlockNumber)
        BlockchainConfigForEvm.isEip6049DeprecationEnabled(phoenixEtcFork) shouldBe false
      }

      "return true for Spiral fork and later" in {
        val spiralEtcFork = blockchainConfig.etcForkForBlockNumber(Fixtures.SpiralBlockNumber)
        BlockchainConfigForEvm.isEip6049DeprecationEnabled(spiralEtcFork) shouldBe true
      }
    }

    "SELFDESTRUCT behavior" should {

      "remain unchanged before EIP-6049" in {
        val context = createContext(
          codeWithSelfDestruct.code,
          fakeHeaderPreEip6049,
          configPreEip6049
        )

        val vm = new VM[MockWorldState, MockStorage]
        val result = vm.run(context)

        // SELFDESTRUCT should work normally
        result.error shouldBe None
        result.addressesToDelete should contain(ownerAddr)
        result.isHalted shouldBe true

        // Balance should be transferred to beneficiary
        val finalWorld = result.world
        finalWorld.getBalance(beneficiaryAddr) shouldEqual UInt256(1500) // 500 + 1000
      }

      "remain unchanged after EIP-6049 (behavior does not change)" in {
        val context = createContext(
          codeWithSelfDestruct.code,
          fakeHeaderWithEip6049,
          configWithEip6049
        )

        val vm = new VM[MockWorldState, MockStorage]
        val result = vm.run(context)

        // SELFDESTRUCT should work EXACTLY the same as before
        result.error shouldBe None
        result.addressesToDelete should contain(ownerAddr)
        result.isHalted shouldBe true

        // Balance should be transferred to beneficiary
        val finalWorld = result.world
        finalWorld.getBalance(beneficiaryAddr) shouldEqual UInt256(1500) // 500 + 1000
      }

      "have identical gas costs before and after EIP-6049" in {
        // Pre-EIP-6049
        val contextPre = createContext(
          codeWithSelfDestruct.code,
          fakeHeaderPreEip6049,
          configPreEip6049
        )
        val vmPre = new VM[MockWorldState, MockStorage]
        val resultPre = vmPre.run(contextPre)

        // With EIP-6049
        val contextWith = createContext(
          codeWithSelfDestruct.code,
          fakeHeaderWithEip6049,
          configWithEip6049
        )
        val vmWith = new VM[MockWorldState, MockStorage]
        val resultWith = vmWith.run(contextWith)

        // Gas usage should be identical
        val gasUsedPre = contextPre.startGas - resultPre.gasRemaining
        val gasUsedWith = contextWith.startGas - resultWith.gasRemaining

        gasUsedPre shouldEqual gasUsedWith
      }

      "have zero refund in both cases (due to EIP-3529 in Mystique fork)" in {
        // Both Mystique and Spiral have EIP-3529, so refund should be 0 in both cases
        configPreEip6049.feeSchedule.R_selfdestruct shouldEqual 0
        configWithEip6049.feeSchedule.R_selfdestruct shouldEqual 0
      }
    }

    "documentation" should {

      "indicate that EIP-6049 is informational only" in {
        // This test verifies that the behavior is unchanged
        // EIP-6049 only deprecates SELFDESTRUCT but does not modify its behavior
        // Future EIPs (like EIP-6780 in Ethereum Cancun) may change behavior

        info("EIP-6049 is purely informational")
        info("SELFDESTRUCT behavior remains unchanged")
        info("Future EIPs may modify SELFDESTRUCT behavior")
        info("Developers should avoid using SELFDESTRUCT in new contracts")

        // No actual assertion needed - this is for documentation
        succeed
      }
    }
  }
}
