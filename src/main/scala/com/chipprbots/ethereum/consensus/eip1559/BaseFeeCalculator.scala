package com.chipprbots.ethereum.consensus.eip1559

import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.utils.BlockchainConfig

/** EIP-1559 baseFee calculation, ported from core-geth consensus/misc/eip1559/eip1559.go.
  *
  * Calculates the baseFee for a block given its parent header. The baseFee adjusts dynamically based on how full the
  * parent block was relative to the gas target (gasLimit / elasticityMultiplier).
  */
object BaseFeeCalculator:
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
  def calcBaseFee(parent: BlockHeader, blockchainConfig: BlockchainConfig): BigInt =
    val isParentOlympia = parent.number.value >= blockchainConfig.forkBlockNumbers.olympiaBlockNumber
    if !isParentOlympia then InitialBaseFee
    else
      val parentBaseFee = parent.baseFee.getOrElse(InitialBaseFee)
      val parentGasTarget = parent.gasLimit / ElasticityMultiplier

      if parent.gasUsed == parentGasTarget then parentBaseFee
      else if parent.gasUsed > parentGasTarget then
        // Parent used more gas than target — baseFee increases
        // max(1, parentBaseFee * gasUsedDelta / parentGasTarget / baseFeeChangeDenominator)
        val gasUsedDelta = parent.gasUsed - parentGasTarget
        val baseFeeDelta =
          (parentBaseFee * gasUsedDelta.value / parentGasTarget.value / BaseFeeChangeDenominator).max(1)
        parentBaseFee + baseFeeDelta
      else
        // Parent used less gas than target — baseFee decreases.
        // go-ethereum CalcBaseFee applies NO min-1 floor to the decrease delta: the raw
        // 1/8 step is used as-is and may integer-floor to 0, which HOLDS the base fee constant
        // (e.g. parentBaseFee=7, delta=7/8=0 -> result stays 7, NOT 6). Only the increase branch
        // gets max(1). Only the FINAL result is floored — to baseFeeFloor from chain config
        // (Big0 for ETH; 1 gwei for ETC/Mordor per ECIP-1111). Do NOT add .max(1) here: that
        // off-by-one over-decrements small base fees and forks the ETH chain (INVALID_BASE_FEE).
        // NOTE: EngineApiService and EthSimulateService carry inline copies of this decrease and
        // already omit the min-1 floor — they must stay in agreement with this method.
        val gasUsedDelta = parentGasTarget - parent.gasUsed
        val baseFeeDelta = parentBaseFee * gasUsedDelta.value / parentGasTarget.value / BaseFeeChangeDenominator
        (parentBaseFee - baseFeeDelta).max(blockchainConfig.baseFeeFloor)
