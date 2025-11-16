package com.chipprbots.ethereum.consensus.mess

/** Configuration for MESS (Modified Exponential Subjective Scoring).
  *
  * @param enabled
  *   Whether MESS scoring is enabled
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
    decayConstant: Double = 0.0001,
    maxTimeDelta: Long = 2592000L, // 30 days
    minWeightMultiplier: Double = 0.0001
) {
  require(decayConstant >= 0, "decayConstant must be non-negative")
  require(maxTimeDelta > 0, "maxTimeDelta must be positive")
  require(minWeightMultiplier > 0 && minWeightMultiplier <= 1.0, "minWeightMultiplier must be in (0, 1]")
}
