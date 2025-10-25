package com.chipprbots.ethereum.consensus.difficulty

import com.chipprbots.ethereum.consensus.pow.difficulty.EthashDifficultyCalculator
import com.chipprbots.ethereum.consensus.pow.difficulty.TargetTimeDifficultyCalculator
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.utils.BlockchainConfig

trait DifficultyCalculator {
  def calculateDifficulty(blockNumber: BigInt, blockTimestamp: Long, parent: BlockHeader)(implicit
      blockchainConfig: BlockchainConfig
  ): BigInt
}

object DifficultyCalculator extends DifficultyCalculator {

  def calculateDifficulty(blockNumber: BigInt, blockTimestamp: Long, parent: BlockHeader)(implicit
      blockchainConfig: BlockchainConfig
  ): BigInt =
    (blockchainConfig.powTargetTime match {
      case Some(targetTime) => new TargetTimeDifficultyCalculator(targetTime)
      case None             => EthashDifficultyCalculator
    }).calculateDifficulty(blockNumber, blockTimestamp, parent)

  val DifficultyBoundDivision: Int = 2048
  val FrontierTimestampDiffLimit: Int = -99
  val MinimumDifficulty: BigInt = 131072
}
