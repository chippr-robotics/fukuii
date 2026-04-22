package com.chipprbots.ethereum.consensus
package validators

import com.chipprbots.ethereum.consensus.mining.GetBlockHeaderByHash
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields
import com.chipprbots.ethereum.utils.BlockchainConfig

/** Validates a [[com.chipprbots.ethereum.domain.BlockHeader BlockHeader]].
  */
trait BlockHeaderValidator {
  def validate(
      blockHeader: BlockHeader,
      getBlockHeaderByHash: GetBlockHeaderByHash
  )(implicit blockchainConfig: BlockchainConfig): Either[BlockHeaderError, BlockHeaderValid]

  def validateHeaderOnly(blockHeader: BlockHeader)(implicit
      blockchainConfig: BlockchainConfig
  ): Either[BlockHeaderError, BlockHeaderValid]
}

object BlockHeaderValidator {
  val MaxExtraDataSize: Int = 32
  val GasLimitBoundDivisor: Int = 1024
  val MinGasLimit: BigInt =
    5000 // Although the paper states this value is 125000, on the different clients 5000 is used
  val MaxGasLimit: Long = Long.MaxValue // max gasLimit is equal 2^63-1 according to EIP106
}

sealed trait BlockHeaderError

object BlockHeaderError {
  // toString values include canonical EEST exception names so the hive consume-engine
  // test framework's exception mapper can match them.
  case object HeaderParentNotFoundError extends BlockHeaderError {
    override def toString: String = "UNKNOWN_PARENT: parent header not found"
  }
  case object HeaderExtraDataError extends BlockHeaderError {
    override def toString: String = "EXTRA_DATA_TOO_BIG: header extra data exceeds limit"
  }
  case object RestrictedPoWHeaderExtraDataError extends BlockHeaderError
  case object DaoHeaderExtraDataError extends BlockHeaderError
  case object HeaderTimestampError extends BlockHeaderError {
    override def toString: String = "TIMESTAMP_OLDER_THAN_PARENT: header timestamp must be greater than parent"
  }
  case object HeaderFutureTimestampError extends BlockHeaderError
  case object HeaderDifficultyError extends BlockHeaderError
  case object HeaderGasUsedError extends BlockHeaderError {
    override def toString: String = "GAS_USED_EXCEEDS_GAS_LIMIT: header gas used exceeds gas limit"
  }
  case object HeaderGasLimitError extends BlockHeaderError {
    override def toString: String = "INVALID_GAS_LIMIT: header gas limit out of bounds"
  }
  case object HeaderNumberError extends BlockHeaderError {
    override def toString: String = "INVALID_BLOCK_NUMBER: header number must be parent+1"
  }
  case object HeaderPoWError extends BlockHeaderError
  case class HeaderExtraFieldsError(extraFields: HeaderExtraFields) extends BlockHeaderError
  case class HeaderBaseFeeError(msg: String) extends BlockHeaderError {
    override def toString: String = s"INVALID_BASE_FEE_PER_GAS: $msg"
  }
  case class HeaderBlobGasError(msg: String) extends BlockHeaderError {
    override def toString: String = s"INVALID_BLOB_GAS: $msg"
  }
  case class HeaderUnexpectedError(msg: String) extends BlockHeaderError
  // Post-merge validation errors
  case class PostMergeNonceError(nonce: org.apache.pekko.util.ByteString) extends BlockHeaderError
  case object PostMergeOmmersError extends BlockHeaderError
  case object MissingWithdrawalsRootError extends BlockHeaderError
  case object MissingBlobGasFieldsError extends BlockHeaderError
}

sealed trait BlockHeaderValid
case object BlockHeaderValid extends BlockHeaderValid
