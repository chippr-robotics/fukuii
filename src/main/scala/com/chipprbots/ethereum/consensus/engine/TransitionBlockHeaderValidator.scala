package com.chipprbots.ethereum.consensus.engine

import com.chipprbots.ethereum.consensus.mining.GetBlockHeaderByHash
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
  */
object TransitionBlockHeaderValidator extends BlockHeaderValidator {

  override def validate(
      blockHeader: BlockHeader,
      getBlockHeaderByHash: GetBlockHeaderByHash
  )(implicit blockchainConfig: BlockchainConfig): Either[BlockHeaderError, BlockHeaderValid] =
    if (blockHeader.difficulty == 0)
      PostMergeBlockHeaderValidator.validate(blockHeader, getBlockHeaderByHash)
    else
      PoWBlockHeaderValidator.validate(blockHeader, getBlockHeaderByHash)

  override def validateHeaderOnly(blockHeader: BlockHeader)(implicit
      blockchainConfig: BlockchainConfig
  ): Either[BlockHeaderError, BlockHeaderValid] =
    if (blockHeader.difficulty == 0)
      PostMergeBlockHeaderValidator.validateHeaderOnly(blockHeader)
    else
      PoWBlockHeaderValidator.validateHeaderOnly(blockHeader)
}
