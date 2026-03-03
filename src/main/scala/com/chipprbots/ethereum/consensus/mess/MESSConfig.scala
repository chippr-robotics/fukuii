package com.chipprbots.ethereum.consensus.mess

/** Configuration for MESS (Modified Exponential Subjective Scoring).
  *
  * ECBP-1100 defines block-based activation windows per network:
  *   - Mordor: activate=2,380,000 deactivate=10,400,000
  *   - ETC Mainnet: activate=11,380,000 deactivate=19,250,000
  *
  * @param enabled
  *   Master switch for MESS scoring. Both enabled=true AND the block must be within
  *   the activation window for MESS to apply.
  * @param activationBlock
  *   Block number at which MESS activates (inclusive). None means no block-based activation.
  * @param deactivationBlock
  *   Block number at which MESS deactivates (inclusive). None means never deactivates.
  * @param decayConstant
  *   Lambda parameter in the exponential decay function (per second). Higher values mean stronger penalties for delayed
  *   blocks. Default: 0.0001 per second
  * @param maxTimeDelta
  *   Maximum time difference to consider (in seconds). Blocks older than this are treated as having this age. Default:
  *   30 days = 2,592,000 seconds
  * @param minWeightMultiplier
  *   Minimum multiplier to prevent weights from going to zero. Default: 0.0001 (0.01%)
  */
case class MESSConfig(
    enabled: Boolean = false,
    activationBlock: Option[BigInt] = None,
    deactivationBlock: Option[BigInt] = None,
    decayConstant: Double = 0.0001,
    maxTimeDelta: Long = 2592000L, // 30 days
    minWeightMultiplier: Double = 0.0001
) {
  require(decayConstant >= 0, "decayConstant must be non-negative")
  require(maxTimeDelta > 0, "maxTimeDelta must be positive")
  require(minWeightMultiplier > 0 && minWeightMultiplier <= 1.0, "minWeightMultiplier must be in (0, 1]")

  /** Check if MESS is active at the given block number.
    * Requires both enabled=true and the block to be within [activationBlock, deactivationBlock).
    */
  def isActiveAtBlock(blockNumber: BigInt): Boolean =
    enabled &&
      activationBlock.forall(blockNumber >= _) &&
      deactivationBlock.forall(blockNumber < _)
}
