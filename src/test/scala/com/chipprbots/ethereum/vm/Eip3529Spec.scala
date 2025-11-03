package com.chipprbots.ethereum.vm

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import Fixtures.blockchainConfig

/** Tests for EIP-3529: Reduction in refunds
  * https://eips.ethereum.org/EIPS/eip-3529
  */
class Eip3529SpecPostMystique extends Eip3529Spec {
  override val config: EvmConfig = EvmConfig.MystiqueConfigBuilder(blockchainConfig)
  override val forkBlockHeight = Fixtures.MystiqueBlockNumber
}

trait Eip3529Spec extends AnyFunSuite with Matchers {

  protected[this] def forkBlockHeight: Int
  protected[this] def config: EvmConfig

  test("EIP-3529: R_sclear should be 4800") {
    config.feeSchedule.R_sclear shouldBe 4800
  }

  test("EIP-3529: R_selfdestruct should be 0") {
    config.feeSchedule.R_selfdestruct shouldBe 0
  }



  test("EIP-3529: isEip3529Enabled should return true for Mystique fork") {
    val etcFork = blockchainConfig.etcForkForBlockNumber(forkBlockHeight)
    BlockchainConfigForEvm.isEip3529Enabled(etcFork) shouldBe true
  }

  test("EIP-3529: isEip3529Enabled should return false for pre-Mystique forks") {
    val magnetoFork = blockchainConfig.etcForkForBlockNumber(Fixtures.MagnetoBlockNumber)
    BlockchainConfigForEvm.isEip3529Enabled(magnetoFork) shouldBe false

    val phoenixFork = blockchainConfig.etcForkForBlockNumber(Fixtures.PhoenixBlockNumber)
    BlockchainConfigForEvm.isEip3529Enabled(phoenixFork) shouldBe false
  }
}
