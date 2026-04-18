package com.chipprbots.ethereum.consensus.validators.std

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.consensus.pow.blocks.OmmersSeqEnc
import com.chipprbots.ethereum.consensus.validators.BlockValidator
import com.chipprbots.ethereum.crypto._
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.Receipt
import com.chipprbots.ethereum.domain.SignedTransaction
import com.chipprbots.ethereum.ledger.BloomFilter
import com.chipprbots.ethereum.utils.ByteUtils.or

object StdBlockValidator extends BlockValidator {

  /** ECIP adaptation of EIP-7934: Max RLP-encoded block size (8 MiB = 10 MiB - 2 MiB). Activates at Olympia.
    * ETC adapts the Ethereum 10 MiB cap down to 8 MiB to match ETC's lower gas limits.
    * Pre-Olympia chains never produce blocks near this cap in practice, so leaving unconditional is safe.
    */
  val BlockRLPSizeCap: Long = 8L * 1024 * 1024 // 8,388,608

  /** Validates [[com.chipprbots.ethereum.domain.BlockHeader.transactionsRoot]] matches [[BlockBody.transactionList]]
    * based on validations stated in section 4.4.2 of http://paper.gavwood.com/
    *
    * @param block
    *   Block to validate
    * @return
    *   Block if valid, a Some otherwise
    */
  private def validateTransactionRoot(block: Block): Either[BlockError, BlockValid] = {
    val isValid = MptListValidator.isValid[SignedTransaction](
      block.header.transactionsRoot.toArray[Byte],
      block.body.transactionList,
      SignedTransaction.byteArraySerializable
    )
    if (isValid) Right(BlockValid)
    else Left(BlockTransactionsHashError)
  }

  /** Validates [[BlockBody.uncleNodesList]] against [[com.chipprbots.ethereum.domain.BlockHeader.ommersHash]] based on
    * validations stated in section 4.4.2 of http://paper.gavwood.com/
    *
    * @param block
    *   Block to validate
    * @return
    *   Block if valid, a Some otherwise
    */
  private def validateOmmersHash(block: Block): Either[BlockError, BlockValid] = {
    val encodedOmmers: Array[Byte] = block.body.uncleNodesList.toBytes
    if (kec256(encodedOmmers).sameElements(block.header.ommersHash)) Right(BlockValid)
    else Left(BlockOmmersHashError)
  }

  /** Validates [[Receipt]] against [[com.chipprbots.ethereum.domain.BlockHeader.receiptsRoot]] based on validations
    * stated in section 4.4.2 of http://paper.gavwood.com/
    *
    * @param blockHeader
    *   Block header to validate
    * @param receipts
    *   Receipts to use
    * @return
    */
  private def validateReceipts(blockHeader: BlockHeader, receipts: Seq[Receipt]): Either[BlockError, BlockValid] = {

    val isValid =
      MptListValidator.isValid[Receipt](blockHeader.receiptsRoot.toArray[Byte], receipts, Receipt.byteArraySerializable)
    if (isValid) Right(BlockValid)
    else Left(BlockReceiptsHashError)
  }

  /** Validates [[com.chipprbots.ethereum.domain.BlockHeader.logsBloom]] against [[Receipt.logsBloomFilter]] based on
    * validations stated in section 4.4.2 of http://paper.gavwood.com/
    *
    * @param blockHeader
    *   Block header to validate
    * @param receipts
    *   Receipts to use
    * @return
    */
  private def validateLogBloom(blockHeader: BlockHeader, receipts: Seq[Receipt]): Either[BlockError, BlockValid] = {
    val logsBloomOr =
      if (receipts.isEmpty) BloomFilter.EmptyBloomFilter
      else ByteString(or(receipts.map(_.logsBloomFilter.toArray): _*))
    if (logsBloomOr == blockHeader.logsBloom) Right(BlockValid)
    else Left(BlockLogBloomError)
  }

  /** EIP-7934: Validates that the RLP-encoded block size does not exceed the cap.
    *
    * @param block
    *   Block to validate
    * @return
    *   Block if valid, BlockRLPSizeError otherwise
    */
  private def validateBlockRLPSize(block: Block): Either[BlockError, BlockValid] = {
    val size = Block.size(block)
    if (size <= BlockRLPSizeCap) Right(BlockValid)
    else Left(BlockRLPSizeError(size, BlockRLPSizeCap))
  }

  /** This method allows validate a Block. It only performs the following validations (stated on section 4.4.2 of
    * http://paper.gavwood.com/):
    *   - BlockValidator.validateTransactionRoot
    *   - BlockValidator.validateOmmersHash
    *   - BlockValidator.validateBlockRLPSize (EIP-7934)
    *   - BlockValidator.validateReceipts
    *   - BlockValidator.validateLogBloom
    *
    * @param block
    *   Block to validate
    * @param receipts
    *   Receipts to be in validation process
    * @return
    *   The block if validations are ok, error otherwise
    */
  def validate(block: Block, receipts: Seq[Receipt]): Either[BlockError, BlockValid] =
    for {
      _ <- validateHeaderAndBody(block.header, block.body)
      _ <- validateBlockAndReceipts(block.header, receipts)
    } yield BlockValid

  /** This method allows validate that a BlockHeader matches a BlockBody.
    *
    * @param blockHeader
    *   to validate
    * @param blockBody
    *   to validate
    * @return
    *   The block if the header matched the body, error otherwise
    */
  def validateHeaderAndBody(blockHeader: BlockHeader, blockBody: BlockBody): Either[BlockError, BlockValid] = {
    val block = Block(blockHeader, blockBody)
    for {
      _ <- validateTransactionRoot(block)
      _ <- validateOmmersHash(block)
      _ <- validateBlockRLPSize(block)
    } yield BlockValid
  }

  /** This method allows validations of the block with its associated receipts. It only perfoms the following
    * validations (stated on section 4.4.2 of http://paper.gavwood.com/):
    *   - BlockValidator.validateReceipts
    *   - BlockValidator.validateLogBloom
    *
    * @param blockHeader
    *   Block header to validate
    * @param receipts
    *   Receipts to be in validation process
    * @return
    *   The block if validations are ok, error otherwise
    */
  def validateBlockAndReceipts(blockHeader: BlockHeader, receipts: Seq[Receipt]): Either[BlockError, BlockValid] =
    for {
      _ <- validateReceipts(blockHeader, receipts)
      _ <- validateLogBloom(blockHeader, receipts)
    } yield BlockValid

  sealed trait BlockError

  case object BlockTransactionsHashError extends BlockError

  case object BlockOmmersHashError extends BlockError

  case object BlockReceiptsHashError extends BlockError

  case object BlockLogBloomError extends BlockError

  case class BlockRLPSizeError(size: Long, cap: Long) extends BlockError

  sealed trait BlockValid

  case object BlockValid extends BlockValid
}
