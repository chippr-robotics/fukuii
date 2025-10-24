package com.chipprbots.ethereum.consensus.pow.validators

import com.chipprbots.ethereum.consensus.validators.BlockHeaderError
import com.chipprbots.ethereum.consensus.validators.BlockHeaderValid
import com.chipprbots.ethereum.consensus.validators.BlockHeaderValidatorSkeleton
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.utils.BlockchainConfig

object PoWBlockHeaderValidator extends BlockHeaderValidatorSkeleton {

  /** A hook where even more mining-specific validation can take place.
    * For example, PoW validation is done here.
    */
  override protected[validators] def validateEvenMore(
      blockHeader: BlockHeader
  )(implicit blockchainConfig: BlockchainConfig): Either[BlockHeaderError, BlockHeaderValid] =
    if (isKeccak(blockHeader.number)) KeccakBlockHeaderValidator.validateHeader(blockHeader)
    else EthashBlockHeaderValidator.validateHeader(blockHeader)

  private def isKeccak(currentBlockNumber: BigInt)(implicit blockchainConfig: BlockchainConfig): Boolean =
    blockchainConfig.forkBlockNumbers.ecip1049BlockNumber match {
      case Some(keccakBlock) => currentBlockNumber >= keccakBlock
      case None              => false
    }
}
