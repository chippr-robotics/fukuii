package com.chipprbots.ethereum.consensus.validators.std

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.consensus.pow.blocks.OmmersSeqEnc
import com.chipprbots.ethereum.consensus.validators.BlockValidator
import com.chipprbots.ethereum.crypto.*
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.Receipt
import com.chipprbots.ethereum.domain.SignedTransaction
import com.chipprbots.ethereum.domain.Withdrawal
import com.chipprbots.ethereum.domain.Withdrawal.WithdrawalBytesDec
import com.chipprbots.ethereum.domain.Withdrawal.WithdrawalEnc
import com.chipprbots.ethereum.domain.BloomFilter
import com.chipprbots.ethereum.mpt.ByteArraySerializable
import com.chipprbots.ethereum.rlp.encode as rlpEncode
import com.chipprbots.ethereum.utils.ByteUtils.or

object StdBlockValidator extends BlockValidator:

  /** EIP-7934 MAX_RLP_BLOCK_SIZE: the cap on an RLP-encoded block, 8 MiB.
    *
    * This is the EIP's own value, not an ETC-specific reduction of it. EIP-7934 defines MAX_BLOCK_SIZE = 10 MiB and
    * SAFETY_MARGIN = 2 MiB, and MAX_RLP_BLOCK_SIZE is their difference -- 10,485,760 - 2,097,152 = 8,388,608. An
    * earlier version of this comment described 8 MiB as ETC adapting Ethereum's "10 MiB cap" down for lower gas limits;
    * that reading is wrong and would mislead anyone tempted to raise it to 10 MiB for ETH. Both chains want exactly
    * this number.
    *
    * Applied unconditionally, which matches the EIP: its specification says only "Any RLP-encoded block exceeding
    * MAX_RLP_BLOCK_SIZE must be considered invalid", at both block creation and validation, with no activation keyed to
    * a named fork or timestamp. go-ethereum gates the equivalent check on Osaka, but that is a deployment choice for
    * mainnet activation rather than something the spec requires.
    *
    * Reachability, which is why unconditional is safe rather than merely spec-permitted: the binding constraint is
    * calldata. Pre-Prague, at 4 gas per zero byte, a 30M-gas block tops out near 7.15 MiB; from Prague, EIP-7623's
    * floor of 10 gas per token puts a 45M-gas block near 4.3 MiB. Real blocks run 2-3 MiB. No chain fukuii syncs
    * produces a block that reaches this cap on either side of any fork.
    *
    * Note this now runs on the p2p import path for ETH as well as ETC: validateBlockBefore Execution reaches it there
    * since BlockExecution stopped passing alreadyValidated = true.
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
  private def validateTransactionRoot(block: Block): Either[BlockError, BlockValid] =
    val isValid = MptListValidator.isValid[SignedTransaction](
      block.header.transactionsRoot.toArray,
      block.body.transactionList,
      SignedTransaction.byteArraySerializable
    )
    if isValid then Right(BlockValid)
    else Left(BlockTransactionsHashError)

  /** Validates [[BlockBody.uncleNodesList]] against [[com.chipprbots.ethereum.domain.BlockHeader.ommersHash]] based on
    * validations stated in section 4.4.2 of http://paper.gavwood.com/
    *
    * @param block
    *   Block to validate
    * @return
    *   Block if valid, a Some otherwise
    */
  private def validateOmmersHash(block: Block): Either[BlockError, BlockValid] =
    val encodedOmmers: Array[Byte] = block.body.uncleNodesList.toBytes
    if kec256(encodedOmmers).sameElements(block.header.ommersHash.value) then Right(BlockValid)
    else Left(BlockOmmersHashError)

  /** Validates [[Receipt]] against [[com.chipprbots.ethereum.domain.BlockHeader.receiptsRoot]] based on validations
    * stated in section 4.4.2 of http://paper.gavwood.com/
    *
    * @param blockHeader
    *   Block header to validate
    * @param receipts
    *   Receipts to use
    * @return
    */
  private def validateReceipts(blockHeader: BlockHeader, receipts: Seq[Receipt]): Either[BlockError, BlockValid] =

    val isValid =
      MptListValidator.isValid[Receipt](blockHeader.receiptsRoot.toArray, receipts, Receipt.byteArraySerializable)
    if isValid then Right(BlockValid)
    else Left(BlockReceiptsHashError)

  /** Validates [[com.chipprbots.ethereum.domain.BlockHeader.logsBloom]] against [[Receipt.logsBloomFilter]] based on
    * validations stated in section 4.4.2 of http://paper.gavwood.com/
    *
    * @param blockHeader
    *   Block header to validate
    * @param receipts
    *   Receipts to use
    * @return
    */
  private def validateLogBloom(blockHeader: BlockHeader, receipts: Seq[Receipt]): Either[BlockError, BlockValid] =
    val logsBloomOr: BloomFilter =
      if receipts.isEmpty then BloomFilter.Empty
      else BloomFilter(ByteString(or(receipts.map(_.logsBloomFilter.toArray)*)))
    if logsBloomOr == blockHeader.logsBloom then Right(BlockValid)
    else Left(BlockLogBloomError)

  /** EIP-7934: Validates that the RLP-encoded block size does not exceed the cap.
    *
    * @param block
    *   Block to validate
    * @return
    *   Block if valid, BlockRLPSizeError otherwise
    */
  private def validateBlockRLPSize(block: Block): Either[BlockError, BlockValid] =
    val size = Block.size(block)
    if size <= BlockRLPSizeCap then Right(BlockValid)
    else Left(BlockRLPSizeError(size, BlockRLPSizeCap))

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
    for
      _ <- validateHeaderAndBody(block.header, block.body)
      _ <- validateBlockAndReceipts(block.header, receipts)
    yield BlockValid

  /** This method allows validate that a BlockHeader matches a BlockBody.
    *
    * @param blockHeader
    *   to validate
    * @param blockBody
    *   to validate
    * @return
    *   The block if the header matched the body, error otherwise
    */
  def validateHeaderAndBody(blockHeader: BlockHeader, blockBody: BlockBody): Either[BlockError, BlockValid] =
    val block = Block(blockHeader, blockBody)
    for
      _ <- validateTransactionRoot(block)
      _ <- validateOmmersHash(block)
      _ <- validateBlockRLPSize(block)
      _ <- validateWithdrawalsPresence(block)
      _ <- validateWithdrawalsRoot(block)
    yield BlockValid

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
    (block.header.withdrawalsRoot, block.body.withdrawals) match
      case (None, Some(_)) => Left(BlockWithdrawalsOrphanedError)
      case _               => Right(BlockValid)

  /** EIP-4895: if the header declares a withdrawalsRoot, it must equal the trie root computed from
    * block.body.withdrawals (indexed like transactions/receipts). Pre-Shanghai headers have no withdrawalsRoot and no
    * withdrawals in the body — no-op.
    */
  private def validateWithdrawalsRoot(block: Block): Either[BlockError, BlockValid] =
    block.header.withdrawalsRoot match
      case None => Right(BlockValid)
      case Some(expectedRoot) =>
        val withdrawals = block.body.withdrawals.getOrElse(Seq.empty)
        val computedRoot = computeWithdrawalsRoot(withdrawals)
        if computedRoot == expectedRoot then Right(BlockValid)
        else Left(BlockWithdrawalsRootError)

  private def computeWithdrawalsRoot(withdrawals: Seq[Withdrawal]): ByteString =
    if withdrawals.isEmpty then BlockHeader.EmptyMpt
    else
      val serializable = new ByteArraySerializable[Withdrawal]:
        override def fromBytes(bytes: Array[Byte]): Withdrawal = WithdrawalBytesDec(bytes).toWithdrawal
        override def toBytes(input: Withdrawal): Array[Byte] = rlpEncode(WithdrawalEnc(input).toRLPEncodable)
      val stateStorage = com.chipprbots.ethereum.db.storage.StateStorage.getReadOnlyStorage(
        com.chipprbots.ethereum.db.dataSource.EphemDataSource()
      )
      val trie = com.chipprbots.ethereum.mpt.MerklePatriciaTrie[Int, Withdrawal](
        source = stateStorage
      )(MptListValidator.intByteArraySerializable, serializable)
      val root = withdrawals.zipWithIndex.foldLeft(trie)((t, r) => t.put(r._2, r._1)).getRootHash
      ByteString(root)

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
    for
      _ <- validateReceipts(blockHeader, receipts)
      _ <- validateLogBloom(blockHeader, receipts)
    yield BlockValid

  sealed trait BlockError

  case object BlockTransactionsHashError extends BlockError

  case object BlockOmmersHashError extends BlockError

  case object BlockReceiptsHashError extends BlockError

  case object BlockLogBloomError extends BlockError

  case object BlockWithdrawalsRootError extends BlockError

  /** EIP-4895: body carries withdrawals but the header did not reserve a withdrawalsRoot (pre-Shanghai header). */
  case object BlockWithdrawalsOrphanedError extends BlockError

  case class BlockRLPSizeError(size: Long, cap: Long) extends BlockError

  sealed trait BlockValid

  case object BlockValid extends BlockValid
