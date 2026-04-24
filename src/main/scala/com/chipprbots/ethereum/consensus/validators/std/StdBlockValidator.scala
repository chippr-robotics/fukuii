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
import com.chipprbots.ethereum.domain.Withdrawal
import com.chipprbots.ethereum.domain.Withdrawal.WithdrawalBytesDec
import com.chipprbots.ethereum.domain.Withdrawal.WithdrawalEnc
import com.chipprbots.ethereum.ledger.BloomFilter
import com.chipprbots.ethereum.mpt.ByteArraySerializable
import com.chipprbots.ethereum.rlp.{encode => rlpEncode}
import com.chipprbots.ethereum.utils.ByteUtils.or

object StdBlockValidator extends BlockValidator {

  /** EIP-7934: Max RLP-encoded block size (10 MiB exact per spec). Activates at Osaka. Pre-Osaka chains never produce
    * blocks near this cap in practice, so leaving unconditional is safe.
    */
  val BlockRLPSizeCap: Long = 10L * 1024 * 1024 // 10,485,760

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
      _ <- validateWithdrawalsPresence(block)
      _ <- validateWithdrawalsOrdering(block)
      _ <- validateWithdrawalsRoot(block)
    } yield BlockValid
  }

  /** EIP-4895: pre-Shanghai blocks (header.withdrawalsRoot = None) MUST NOT attach a withdrawals field to the body.
    *
    * Note this rejects `Some(Seq.empty)` as well as `Some(nonEmpty)` — `Block.BlockEnc` serialises `body.withdrawals`
    * by *presence*, so `Some(Seq.empty)` still produces a 4-item block RLP that's structurally a Shanghai-era encoding
    * even though no withdrawals are carried. A pre-Shanghai block must RLP-encode as a 3-item list, which means
    * `body.withdrawals = None`.
    *
    * The reverse case (Shanghai header + no withdrawals field in body) is caught during RLP decoding in
    * `Block.BlockDec.toBlock`.
    */
  private def validateWithdrawalsPresence(block: Block): Either[BlockError, BlockValid] =
    (block.header.withdrawalsRoot, block.body.withdrawals) match {
      case (None, Some(_)) => Left(BlockWithdrawalsOrphanedError)
      case _               => Right(BlockValid)
    }

  /** EIP-4895: withdrawals within a block must have strictly increasing `index` values. The beacon chain assigns
    * indices globally and they drain in order, so a block that reorders or duplicates indices is invalid regardless of
    * whether the declared `withdrawalsRoot` happens to match the (mis-ordered) trie.
    *
    * Without this check a malicious producer can pair any out-of-order withdrawal list with its own trie root and the
    * block-level root comparison in [[validateWithdrawalsRoot]] will still pass — the root is computed over the same
    * bad order, so it matches itself.
    */
  private def validateWithdrawalsOrdering(block: Block): Either[BlockError, BlockValid] =
    block.body.withdrawals match {
      case None                                              => Right(BlockValid)
      case Some(ws) if ws.size < 2                           => Right(BlockValid)
      case Some(ws) if isStrictlyIncreasing(ws.map(_.index)) => Right(BlockValid)
      case Some(_)                                           => Left(BlockWithdrawalsIndexError)
    }

  private def isStrictlyIncreasing(values: Seq[BigInt]): Boolean =
    values.zip(values.tail).forall { case (a, b) => a < b }

  /** EIP-4895: if the header declares a withdrawalsRoot, it must equal the trie root computed from
    * block.body.withdrawals (indexed like transactions/receipts). Pre-Shanghai headers have no withdrawalsRoot and no
    * withdrawals in the body — no-op.
    */
  private def validateWithdrawalsRoot(block: Block): Either[BlockError, BlockValid] =
    block.header.withdrawalsRoot match {
      case None => Right(BlockValid)
      case Some(expectedRoot) =>
        val withdrawals = block.body.withdrawals.getOrElse(Seq.empty)
        val computedRoot = computeWithdrawalsRoot(withdrawals)
        if (computedRoot == expectedRoot) Right(BlockValid)
        else Left(BlockWithdrawalsRootError)
    }

  private def computeWithdrawalsRoot(withdrawals: Seq[Withdrawal]): ByteString =
    if (withdrawals.isEmpty) {
      BlockHeader.EmptyMpt
    } else {
      val serializable = new ByteArraySerializable[Withdrawal] {
        override def fromBytes(bytes: Array[Byte]): Withdrawal = WithdrawalBytesDec(bytes).toWithdrawal
        override def toBytes(input: Withdrawal): Array[Byte] = rlpEncode(WithdrawalEnc(input).toRLPEncodable)
      }
      val stateStorage = com.chipprbots.ethereum.db.storage.StateStorage.getReadOnlyStorage(
        com.chipprbots.ethereum.db.dataSource.EphemDataSource()
      )
      val trie = com.chipprbots.ethereum.mpt.MerklePatriciaTrie[Int, Withdrawal](
        source = stateStorage
      )(MptListValidator.intByteArraySerializable, serializable)
      val root = withdrawals.zipWithIndex.foldLeft(trie)((t, r) => t.put(r._2, r._1)).getRootHash
      ByteString(root)
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

  case object BlockWithdrawalsRootError extends BlockError

  /** EIP-4895: body carries withdrawals but the header did not reserve a withdrawalsRoot (pre-Shanghai header). */
  case object BlockWithdrawalsOrphanedError extends BlockError

  /** EIP-4895: within-block withdrawal indices must be strictly increasing. */
  case object BlockWithdrawalsIndexError extends BlockError

  case class BlockRLPSizeError(size: Long, cap: Long) extends BlockError

  sealed trait BlockValid

  case object BlockValid extends BlockValid
}
