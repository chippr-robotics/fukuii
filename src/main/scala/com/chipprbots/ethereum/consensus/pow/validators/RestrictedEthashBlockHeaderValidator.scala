package com.chipprbots.ethereum.consensus.pow.validators

import akka.util.ByteString

import com.chipprbots.ethereum.consensus.pow.RestrictedPoWSigner
import com.chipprbots.ethereum.consensus.validators.BlockHeaderError
import com.chipprbots.ethereum.consensus.validators.BlockHeaderError.RestrictedPoWHeaderExtraDataError
import com.chipprbots.ethereum.consensus.validators.BlockHeaderValid
import com.chipprbots.ethereum.consensus.validators.BlockHeaderValidator
import com.chipprbots.ethereum.consensus.validators.BlockHeaderValidatorSkeleton
import com.chipprbots.ethereum.crypto.ECDSASignature
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.utils.BlockchainConfig

object RestrictedEthashBlockHeaderValidator extends BlockHeaderValidatorSkeleton {

  override protected def validateEvenMore(blockHeader: BlockHeader)(implicit
      blockchainConfig: BlockchainConfig
  ): Either[BlockHeaderError, BlockHeaderValid] =
    PoWBlockHeaderValidator.validateEvenMore(blockHeader)

  val ExtraDataMaxSize: Int = BlockHeaderValidator.MaxExtraDataSize + ECDSASignature.EncodedLength

  private def validateSignatureAgainstAllowedMiners(
      blockHeader: BlockHeader,
      allowedMiners: Set[ByteString]
  ): Either[BlockHeaderError, BlockHeaderValid] = {
    val emptyOrValid = allowedMiners.isEmpty || RestrictedPoWSigner.validateSignature(blockHeader, allowedMiners)
    Either.cond(emptyOrValid, BlockHeaderValid, RestrictedPoWHeaderExtraDataError)
  }

  override protected def validateExtraData(
      blockHeader: BlockHeader
  )(implicit blockchainConfig: BlockchainConfig): Either[BlockHeaderError, BlockHeaderValid] = {
    val tooLargeExtraData = blockHeader.extraData.length > ExtraDataMaxSize

    if (tooLargeExtraData) {
      Left(RestrictedPoWHeaderExtraDataError)
    } else {
      validateSignatureAgainstAllowedMiners(blockHeader, blockchainConfig.allowedMinersPublicKeys)
    }
  }
}
