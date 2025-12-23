package com.chipprbots.ethereum.vm

import org.scalatest.funsuite.AnyFunSuite

class EvmConfigEtcForkSelectionSpec extends AnyFunSuite {

  test("EvmConfig.forBlock prefers Atlantis over Byzantium when activated at same height") {
    val cfg = BlockchainConfigForEvm(
      frontierBlockNumber = Long.MaxValue,
      homesteadBlockNumber = Long.MaxValue,
      eip150BlockNumber = Long.MaxValue,
      eip160BlockNumber = Long.MaxValue,
      eip161BlockNumber = Long.MaxValue,
      byzantiumBlockNumber = 0,
      constantinopleBlockNumber = Long.MaxValue,
      istanbulBlockNumber = Long.MaxValue,
      maxCodeSize = None,
      accountStartNonce = 0,
      atlantisBlockNumber = 0,
      aghartaBlockNumber = Long.MaxValue,
      petersburgBlockNumber = Long.MaxValue,
      phoenixBlockNumber = Long.MaxValue,
      magnetoBlockNumber = Long.MaxValue,
      berlinBlockNumber = Long.MaxValue,
      mystiqueBlockNumber = Long.MaxValue,
      spiralBlockNumber = Long.MaxValue,
      chainId = 0x3f
    )

    val evmConfig = EvmConfig.forBlock(0, cfg)

    assert(evmConfig.feeSchedule.isInstanceOf[FeeSchedule.AtlantisFeeSchedule])
    assert(evmConfig.opCodeList == EvmConfig.AtlantisOpCodes)
  }
}
