package com.chipprbots.ethereum.consensus

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.ParallelTestExecution

import com.chipprbots.ethereum.vm.BlockchainConfigForEvm
import com.chipprbots.ethereum.vm.BlockchainConfigForEvm.EtcForks
import com.chipprbots.ethereum.vm.EvmConfig
import com.chipprbots.ethereum.vm.FeeSchedule
import com.chipprbots.ethereum.vm.PUSH0
import com.chipprbots.ethereum.testing.Tags._

// scalastyle:off magic.number
/** Verifies that each pre-Olympia ETC fork selects the correct EVM configuration (fee schedule, opcode list, and EIP
  * feature flags) via EvmConfig.forBlock().
  *
  * Extends the 1-test EvmConfigEtcForkSelectionSpec to cover all forks.
  *
  * Reference: Besu ClassicProtocolSpecsTest (15 tests validating fork dispatch)
  */
class PreOlympiaForkComplianceSpec extends AnyFlatSpec with Matchers with ParallelTestExecution {

  /** Helper: create a BlockchainConfigForEvm with all forks at Long.MaxValue (inactive), then selectively activate
    * specific forks.
    */
  private def cfgWith(overrides: BlockchainConfigForEvm => BlockchainConfigForEvm): BlockchainConfigForEvm =
    overrides(
      BlockchainConfigForEvm(
        frontierBlockNumber = Long.MaxValue,
        homesteadBlockNumber = Long.MaxValue,
        eip150BlockNumber = Long.MaxValue,
        eip160BlockNumber = Long.MaxValue,
        eip161BlockNumber = Long.MaxValue,
        byzantiumBlockNumber = Long.MaxValue,
        constantinopleBlockNumber = Long.MaxValue,
        istanbulBlockNumber = Long.MaxValue,
        maxCodeSize = None,
        accountStartNonce = 0,
        atlantisBlockNumber = Long.MaxValue,
        aghartaBlockNumber = Long.MaxValue,
        petersburgBlockNumber = Long.MaxValue,
        phoenixBlockNumber = Long.MaxValue,
        magnetoBlockNumber = Long.MaxValue,
        berlinBlockNumber = Long.MaxValue,
        mystiqueBlockNumber = Long.MaxValue,
        spiralBlockNumber = Long.MaxValue,
        olympiaBlockNumber = Long.MaxValue,
        chainId = 0x3d
      )
    )

  private case class ForkCase(
      label: String,
      blockNumber: Long,
      configFn: BlockchainConfigForEvm => BlockchainConfigForEvm,
      assertFn: EvmConfig => Unit
  )

  // ===== Fork Dispatch Table =====

  private val forkCases: Seq[ForkCase] = Seq(
    ForkCase(
      label = "select FrontierFeeSchedule for Frontier blocks",
      blockNumber = 0,
      configFn = _.copy(frontierBlockNumber = 0),
      assertFn = evm => {
        evm.feeSchedule shouldBe a[FeeSchedule.FrontierFeeSchedule]
        evm.opCodeList shouldBe EvmConfig.FrontierOpCodes
      }
    ),
    ForkCase(
      label = "select HomesteadFeeSchedule for Homestead blocks",
      blockNumber = 10,
      configFn = _.copy(frontierBlockNumber = 0, homesteadBlockNumber = 10),
      assertFn = evm => {
        evm.feeSchedule shouldBe a[FeeSchedule.HomesteadFeeSchedule]
        evm.opCodeList shouldBe EvmConfig.HomesteadOpCodes
        evm.exceptionalFailedCodeDeposit shouldBe true
      }
    ),
    ForkCase(
      label = "select AtlantisFeeSchedule for Atlantis blocks",
      blockNumber = 100,
      configFn = _.copy(
        frontierBlockNumber = 0,
        homesteadBlockNumber = 10,
        eip150BlockNumber = 20,
        eip160BlockNumber = 30,
        atlantisBlockNumber = 100
      ),
      assertFn = evm => {
        evm.feeSchedule shouldBe a[FeeSchedule.AtlantisFeeSchedule]
        evm.opCodeList shouldBe EvmConfig.AtlantisOpCodes
        evm.noEmptyAccounts shouldBe true
      }
    ),
    ForkCase(
      label = "prefer Atlantis over Byzantium when both activated at same height",
      blockNumber = 0,
      configFn = _.copy(
        frontierBlockNumber = 0,
        byzantiumBlockNumber = 0,
        atlantisBlockNumber = 0
      ),
      assertFn = evm => {
        evm.feeSchedule shouldBe a[FeeSchedule.AtlantisFeeSchedule]
        evm.opCodeList shouldBe EvmConfig.AtlantisOpCodes
      }
    ),
    ForkCase(
      label = "select ConstantionopleFeeSchedule for Agharta blocks",
      blockNumber = 100,
      configFn = _.copy(
        frontierBlockNumber = 0,
        atlantisBlockNumber = 10,
        aghartaBlockNumber = 100
      ),
      assertFn = evm => {
        evm.feeSchedule shouldBe a[FeeSchedule.ConstantionopleFeeSchedule]
        evm.opCodeList shouldBe EvmConfig.AghartaOpCodes
      }
    ),
    ForkCase(
      label = "select PhoenixFeeSchedule for Phoenix blocks",
      blockNumber = 100,
      configFn = _.copy(
        frontierBlockNumber = 0,
        atlantisBlockNumber = 10,
        aghartaBlockNumber = 20,
        phoenixBlockNumber = 100
      ),
      assertFn = evm => {
        evm.feeSchedule shouldBe a[FeeSchedule.PhoenixFeeSchedule]
        evm.opCodeList shouldBe EvmConfig.PhoenixOpCodes
      }
    ),
    ForkCase(
      label = "select MagnetoFeeSchedule for Magneto blocks",
      blockNumber = 100,
      configFn = _.copy(
        frontierBlockNumber = 0,
        atlantisBlockNumber = 10,
        aghartaBlockNumber = 20,
        phoenixBlockNumber = 30,
        magnetoBlockNumber = 100
      ),
      assertFn = evm => {
        evm.feeSchedule shouldBe a[FeeSchedule.MagnetoFeeSchedule]
        evm.opCodeList shouldBe EvmConfig.MagnetoOpCodes
      }
    ),
    ForkCase(
      label = "select MystiqueFeeSchedule for Mystique blocks",
      blockNumber = 100,
      configFn = _.copy(
        frontierBlockNumber = 0,
        atlantisBlockNumber = 10,
        aghartaBlockNumber = 20,
        phoenixBlockNumber = 30,
        magnetoBlockNumber = 40,
        mystiqueBlockNumber = 100
      ),
      assertFn = evm => {
        evm.feeSchedule shouldBe a[FeeSchedule.MystiqueFeeSchedule]
        evm.eip3541Enabled shouldBe true
      }
    ),
    ForkCase(
      label = "select MystiqueFeeSchedule with Spiral opcodes for Spiral blocks",
      blockNumber = 100,
      configFn = _.copy(
        frontierBlockNumber = 0,
        atlantisBlockNumber = 10,
        aghartaBlockNumber = 20,
        phoenixBlockNumber = 30,
        magnetoBlockNumber = 40,
        mystiqueBlockNumber = 50,
        spiralBlockNumber = 100
      ),
      assertFn = evm => {
        // Spiral uses MystiqueFeeSchedule (no new fee schedule class)
        evm.feeSchedule shouldBe a[FeeSchedule.MystiqueFeeSchedule]
        evm.opCodeList shouldBe EvmConfig.SpiralOpCodes
        evm.eip3651Enabled shouldBe true
        evm.eip3860Enabled shouldBe true
        evm.eip6049DeprecationEnabled shouldBe true
      }
    )
  )

  forkCases.foreach { fc =>
    "Pre-Olympia fork compliance" should fc.label taggedAs (UnitTest, ConsensusTest) in {
      val cfg = cfgWith(fc.configFn)
      val evm = EvmConfig.forBlock(fc.blockNumber, cfg)
      fc.assertFn(evm)
    }
  }

  // ===== PUSH0 Gating at Spiral =====

  it should "not include PUSH0 opcode before Spiral" taggedAs (UnitTest, ConsensusTest) in {
    val cfg = cfgWith(
      _.copy(
        frontierBlockNumber = 0,
        atlantisBlockNumber = 10,
        aghartaBlockNumber = 20,
        phoenixBlockNumber = 30,
        magnetoBlockNumber = 40,
        mystiqueBlockNumber = 50,
        spiralBlockNumber = Long.MaxValue // Spiral not yet active
      )
    )
    val evm = EvmConfig.forBlock(50, cfg)

    evm.opCodeList.byteToOpCode.get(PUSH0.code) shouldBe None
  }

  it should "include PUSH0 opcode at and after Spiral" taggedAs (UnitTest, ConsensusTest) in {
    val cfg = cfgWith(
      _.copy(
        frontierBlockNumber = 0,
        atlantisBlockNumber = 10,
        aghartaBlockNumber = 20,
        phoenixBlockNumber = 30,
        magnetoBlockNumber = 40,
        mystiqueBlockNumber = 50,
        spiralBlockNumber = 100
      )
    )
    val evm = EvmConfig.forBlock(100, cfg)

    evm.opCodeList.byteToOpCode.get(PUSH0.code) shouldBe Some(PUSH0)
  }

  // ===== EtcForks Enum Ordering =====

  it should "have EtcForks enum values in strictly increasing order" taggedAs (UnitTest, ConsensusTest) in {
    val forks = EtcForks.values.toList
    forks.sliding(2).foreach {
      case List(a, b) =>
        withClue(s"$a (${a.id}) should be less than $b (${b.id}): ") {
          a.id should be < b.id
        }
      case _ => ()
    }
  }

  // ===== EIP Feature Flag Helpers =====

  it should "report EIP-2929 enabled for Magneto+ (ETC) and Berlin+ (ETH)" taggedAs (UnitTest, ConsensusTest) in {
    BlockchainConfigForEvm.isEip2929Enabled(EtcForks.Phoenix, BlockchainConfigForEvm.EthForks.Istanbul) shouldBe false
    BlockchainConfigForEvm.isEip2929Enabled(EtcForks.Magneto, BlockchainConfigForEvm.EthForks.Istanbul) shouldBe true
    BlockchainConfigForEvm.isEip2929Enabled(EtcForks.Phoenix, BlockchainConfigForEvm.EthForks.Berlin) shouldBe true
  }

  it should "report EIP-3855 (PUSH0) enabled only at Spiral+" taggedAs (UnitTest, ConsensusTest) in {
    BlockchainConfigForEvm.isEip3855Enabled(EtcForks.Mystique) shouldBe false
    BlockchainConfigForEvm.isEip3855Enabled(EtcForks.Spiral) shouldBe true
  }
}
// scalastyle:on magic.number
