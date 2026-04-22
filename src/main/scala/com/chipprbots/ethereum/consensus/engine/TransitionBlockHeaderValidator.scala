package com.chipprbots.ethereum.consensus.engine

import com.chipprbots.ethereum.consensus.mining.GetBlockHeaderByHash
import com.chipprbots.ethereum.consensus.pow.validators.MockedPowBlockHeaderValidator
import com.chipprbots.ethereum.consensus.pow.validators.PoWBlockHeaderValidator
import com.chipprbots.ethereum.consensus.validators.BlockHeaderError
import com.chipprbots.ethereum.consensus.validators.BlockHeaderValid
import com.chipprbots.ethereum.consensus.validators.BlockHeaderValidator
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.utils.BlockchainConfig

/** Hybrid validator for Engine API chains that transition from PoW to PoS.
  *
  * Pre-merge blocks (difficulty > 0) are validated with standard PoW rules (Ethash). Post-merge blocks (difficulty ==
  * 0) are validated with post-merge rules (no PoW).
  *
  * This is the correct validator for any chain that starts with PoW mining and transitions to CL-driven consensus at a
  * terminal total difficulty.
  *
  * When `-Dfukuii.mining.skip-pow-validation=true` is set (used by the hive test adapter where pre-merge blocks carry
  * fake Ethash seals), the pre-merge branch falls back to [[MockedPowBlockHeaderValidator]] so synthetic test chains
  * pass the header check without a real Ethash seal.
  */
object TransitionBlockHeaderValidator extends BlockHeaderValidator {

  private def preMergeValidator: BlockHeaderValidator =
    if (java.lang.Boolean.getBoolean("fukuii.mining.skip-pow-validation")) MockedPowBlockHeaderValidator
    else PoWBlockHeaderValidator

  override def validate(
      blockHeader: BlockHeader,
      getBlockHeaderByHash: GetBlockHeaderByHash
  )(implicit blockchainConfig: BlockchainConfig): Either[BlockHeaderError, BlockHeaderValid] =
    if (blockHeader.difficulty == 0)
      PostMergeBlockHeaderValidator.validate(blockHeader, getBlockHeaderByHash)
    else
      preMergeValidator.validate(blockHeader, getBlockHeaderByHash)

  override def validateHeaderOnly(blockHeader: BlockHeader)(implicit
      blockchainConfig: BlockchainConfig
  ): Either[BlockHeaderError, BlockHeaderValid] =
    if (blockHeader.difficulty == 0)
      PostMergeBlockHeaderValidator.validateHeaderOnly(blockHeader)
    else
      preMergeValidator.validateHeaderOnly(blockHeader)
}
