package com.chipprbots.ethereum.consensus.eip1559

import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.utils.BlockchainConfig

/** EIP-1559 baseFee calculation, ported from core-geth consensus/misc/eip1559/eip1559.go.
  *
  * Calculates the baseFee for a block given its parent header. The baseFee adjusts dynamically based on how full the
  * parent block was relative to the gas target (gasLimit / elasticityMultiplier).
  */
object BaseFeeCalculator {
  val InitialBaseFee: BigInt = 1000000000 // 1 gwei
  val ElasticityMultiplier: Int = 2
  val BaseFeeChangeDenominator: Int = 8

  /** Calculate the expected baseFee for a block given its parent.
    *
    * @param parent
    *   the parent block header
    * @param blockchainConfig
    *   blockchain configuration (for fork activation blocks)
    * @return
    *   the expected baseFee for the child block
    */
  def calcBaseFee(parent: BlockHeader, blockchainConfig: BlockchainConfig): BigInt = {
    val isParentOlympia = parent.number >= blockchainConfig.forkBlockNumbers.olympiaBlockNumber
    if (!isParentOlympia) return InitialBaseFee

    val parentBaseFee = parent.baseFee.getOrElse(
      throw new IllegalStateException(
        s"Post-Olympia parent block ${parent.number} (${parent.hashAsHexString}) is missing baseFee. " +
          "This indicates a corrupt or invalid block header."
      )
    )
    val parentGasTarget = parent.gasLimit / ElasticityMultiplier

    if (parent.gasUsed == parentGasTarget) {
      parentBaseFee
    } else if (parent.gasUsed > parentGasTarget) {
      // Parent used more gas than target — baseFee increases
      // max(1, parentBaseFee * gasUsedDelta / parentGasTarget / baseFeeChangeDenominator)
      val gasUsedDelta = parent.gasUsed - parentGasTarget
      val baseFeeDelta = (parentBaseFee * gasUsedDelta / parentGasTarget / BaseFeeChangeDenominator).max(1)
      parentBaseFee + baseFeeDelta
    } else {
      // Parent used less gas than target — baseFee decreases
      // max(0, parentBaseFee - parentBaseFee * gasUsedDelta / parentGasTarget / baseFeeChangeDenominator)
      val gasUsedDelta = parentGasTarget - parent.gasUsed
      val baseFeeDelta = parentBaseFee * gasUsedDelta / parentGasTarget / BaseFeeChangeDenominator
      (parentBaseFee - baseFeeDelta).max(0)
    }
  }
}
