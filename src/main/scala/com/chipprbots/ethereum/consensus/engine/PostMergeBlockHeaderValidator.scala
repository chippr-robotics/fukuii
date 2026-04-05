package com.chipprbots.ethereum.consensus.engine

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.consensus.validators.BlockHeaderError
import com.chipprbots.ethereum.consensus.validators.BlockHeaderError._
import com.chipprbots.ethereum.consensus.validators.BlockHeaderValid
import com.chipprbots.ethereum.consensus.validators.BlockHeaderValidatorSkeleton
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields._
import com.chipprbots.ethereum.utils.BlockchainConfig

/** Post-merge block header validator. Skips PoW (Ethash) validation entirely.
  * Enforces: difficulty=0, nonce=0, empty ommers.
  * Validates withdrawalsRoot (Shanghai+) and blob gas fields (Cancun+).
  */
object PostMergeBlockHeaderValidator extends BlockHeaderValidatorSkeleton {

  private val EmptyNonce: ByteString = ByteString(Array.fill[Byte](8)(0))

  override protected def validateEvenMore(
      blockHeader: BlockHeader
  )(implicit blockchainConfig: BlockchainConfig): Either[BlockHeaderError, BlockHeaderValid] =
    for {
      _ <- validatePostMergeDifficulty(blockHeader)
      _ <- validatePostMergeNonce(blockHeader)
      _ <- validatePostMergeOmmers(blockHeader)
      _ <- validateWithdrawalsRoot(blockHeader)
      _ <- validateBlobGasFields(blockHeader)
    } yield BlockHeaderValid

  private def validatePostMergeDifficulty(
      blockHeader: BlockHeader
  ): Either[BlockHeaderError, BlockHeaderValid] =
    if (blockHeader.difficulty == 0) Right(BlockHeaderValid)
    else Left(HeaderDifficultyError)

  private def validatePostMergeNonce(
      blockHeader: BlockHeader
  ): Either[BlockHeaderError, BlockHeaderValid] =
    if (blockHeader.nonce == EmptyNonce) Right(BlockHeaderValid)
    else Left(PostMergeNonceError(blockHeader.nonce))

  private def validatePostMergeOmmers(
      blockHeader: BlockHeader
  ): Either[BlockHeaderError, BlockHeaderValid] =
    if (blockHeader.ommersHash == BlockHeader.EmptyOmmers) Right(BlockHeaderValid)
    else Left(PostMergeOmmersError)

  private def validateWithdrawalsRoot(
      blockHeader: BlockHeader
  )(implicit blockchainConfig: BlockchainConfig): Either[BlockHeaderError, BlockHeaderValid] = {
    val isShanghaiActive = blockchainConfig.isShanghaiTimestamp(blockHeader.unixTimestamp)
    if (isShanghaiActive) {
      blockHeader.withdrawalsRoot match {
        case Some(_) => Right(BlockHeaderValid)
        case None    => Left(MissingWithdrawalsRootError)
      }
    } else {
      Right(BlockHeaderValid)
    }
  }

  private def validateBlobGasFields(
      blockHeader: BlockHeader
  )(implicit blockchainConfig: BlockchainConfig): Either[BlockHeaderError, BlockHeaderValid] = {
    val isCancunActive = blockchainConfig.isCancunTimestamp(blockHeader.unixTimestamp)
    if (isCancunActive) {
      (blockHeader.blobGasUsed, blockHeader.excessBlobGas, blockHeader.parentBeaconBlockRoot) match {
        case (Some(_), Some(_), Some(_)) => Right(BlockHeaderValid)
        case _                           => Left(MissingBlobGasFieldsError)
      }
    } else {
      Right(BlockHeaderValid)
    }
  }
}
