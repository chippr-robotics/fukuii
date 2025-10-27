package com.chipprbots.ethereum.blockchain.sync.fast

import akka.util.ByteString

import com.chipprbots.ethereum.consensus.validators.Validators
import com.chipprbots.ethereum.consensus.validators.std.StdBlockValidator.BlockError
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.Receipt

trait ReceiptsValidator {

  import ReceiptsValidator._
  import ReceiptsValidationResult._

  def blockchainReader: BlockchainReader
  def validators: Validators

  /** Validates whether the received receipts match the block headers stored on the blockchain, returning the valid
    * receipts
    *
    * @param requestedHashes
    *   hash of the blocks to which the requested receipts should belong
    * @param receipts
    *   received by the peer
    * @return
    *   the valid receipts or the error encountered while validating them
    */
  def validateReceipts(requestedHashes: Seq[ByteString], receipts: Seq[Seq[Receipt]]): ReceiptsValidationResult = {
    val blockHashesWithReceipts = requestedHashes.zip(receipts)
    val blockHeadersWithReceipts = blockHashesWithReceipts.map { case (hash, blockReceipts) =>
      blockchainReader.getBlockHeaderByHash(hash) -> blockReceipts
    }

    val errorIterator = blockHeadersWithReceipts.iterator.map {
      case (Some(header), receipt) =>
        validators.blockValidator.validateBlockAndReceipts(header, receipt) match {
          case Left(err) => Some(Invalid(err))
          case _         => None
        }
      case (None, _) => Some(DbError)
    }

    val receiptsValidationError = errorIterator.collectFirst { case Some(error) =>
      error
    }

    receiptsValidationError.getOrElse(Valid(blockHashesWithReceipts))
  }

}

object ReceiptsValidator {
  sealed trait ReceiptsValidationResult
  object ReceiptsValidationResult {
    case class Valid(blockHashesAndReceipts: Seq[(ByteString, Seq[Receipt])]) extends ReceiptsValidationResult
    case class Invalid(error: BlockError) extends ReceiptsValidationResult
    case object DbError extends ReceiptsValidationResult
  }
}
