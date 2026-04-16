package com.chipprbots.ethereum.vm

import org.scalatest.funsuite.AnyFunSuite

import com.chipprbots.ethereum.testing.Tags._

class EvmConfigEtcForkSelectionSpec extends AnyFunSuite {

  val allMaxCfg: BlockchainConfigForEvm = BlockchainConfigForEvm(
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
    chainId = 0x3f
  )

  test("EvmConfig.forBlock prefers Atlantis over Byzantium when activated at same height") {
    val cfg = allMaxCfg.copy(byzantiumBlockNumber = 0, atlantisBlockNumber = 0)

    val evmConfig = EvmConfig.forBlock(0, cfg)

    assert(evmConfig.feeSchedule.isInstanceOf[FeeSchedule.AtlantisFeeSchedule])
    assert(evmConfig.opCodeList == EvmConfig.AtlantisOpCodes)
  }

  test("EvmConfig selects OlympiaConfigBuilder at olympia block") {
    val olympiaBlock = 100L
    val cfg = allMaxCfg.copy(
      byzantiumBlockNumber = 0,
      atlantisBlockNumber = 0,
      aghartaBlockNumber = 0,
      constantinopleBlockNumber = 0,
      petersburgBlockNumber = 0,
      phoenixBlockNumber = 0,
      istanbulBlockNumber = 0,
      magnetoBlockNumber = 0,
      berlinBlockNumber = 0,
      mystiqueBlockNumber = 0,
      spiralBlockNumber = 0,
      olympiaBlockNumber = olympiaBlock
    )

    val evmConfig = EvmConfig.forBlock(olympiaBlock, cfg)

    assert(evmConfig.feeSchedule.isInstanceOf[FeeSchedule.OlympiaFeeSchedule])
    assert(evmConfig.opCodeList == EvmConfig.OlympiaOpCodes)
  }

  test("EvmConfig selects SpiralConfigBuilder just before olympia block") {
    val olympiaBlock = 100L
    val cfg = allMaxCfg.copy(
      byzantiumBlockNumber = 0,
      atlantisBlockNumber = 0,
      aghartaBlockNumber = 0,
      constantinopleBlockNumber = 0,
      petersburgBlockNumber = 0,
      phoenixBlockNumber = 0,
      istanbulBlockNumber = 0,
      magnetoBlockNumber = 0,
      berlinBlockNumber = 0,
      mystiqueBlockNumber = 0,
      spiralBlockNumber = 0,
      olympiaBlockNumber = olympiaBlock
    )

    val evmConfig = EvmConfig.forBlock(olympiaBlock - 1, cfg)

    assert(evmConfig.feeSchedule.isInstanceOf[FeeSchedule.MystiqueFeeSchedule])
    // Should NOT be OlympiaFeeSchedule
    assert(!evmConfig.feeSchedule.isInstanceOf[FeeSchedule.OlympiaFeeSchedule])
  }

  test("EtcForks.Olympia is highest enum value") {
    assert(BlockchainConfigForEvm.EtcForks.Olympia > BlockchainConfigForEvm.EtcForks.Spiral)
  }
}
