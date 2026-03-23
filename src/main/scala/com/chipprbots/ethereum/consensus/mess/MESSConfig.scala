package com.chipprbots.ethereum.consensus.mess

/** Configuration for MESS (Modified Exponential Subjective Scoring).
  *
  * ECBP-1100 defines block-based activation windows per network:
  *   - Mordor: activate=2,380,000 deactivate=10,400,000
  *   - ETC Mainnet: activate=11,380,000 deactivate=19,250,000
  *
  * @param enabled
  *   Master switch for MESS scoring. Both enabled=true AND the block must be within the activation window for MESS to
  *   apply.
  * @param activationBlock
  *   Block number at which MESS activates (inclusive). None means no block-based activation.
  * @param deactivationBlock
  *   Block number at which MESS deactivates (inclusive). None means never deactivates.
  */
case class MESSConfig(
    enabled: Boolean = false,
    activationBlock: Option[BigInt] = None,
    deactivationBlock: Option[BigInt] = None
) {

  /** Check if MESS is active at the given block number. Requires both enabled=true and the block to be within
    * [activationBlock, deactivationBlock).
    */
  def isActiveAtBlock(blockNumber: BigInt): Boolean =
    enabled &&
      activationBlock.forall(blockNumber >= _) &&
      deactivationBlock.forall(blockNumber < _)
}
