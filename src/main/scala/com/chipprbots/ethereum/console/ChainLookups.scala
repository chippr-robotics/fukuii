package com.chipprbots.ethereum.console

import java.time.Instant

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.BlobTransaction
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.FailureOutcome
import com.chipprbots.ethereum.domain.HashOutcome
import com.chipprbots.ethereum.domain.LegacyTransaction
import com.chipprbots.ethereum.domain.Receipt
import com.chipprbots.ethereum.domain.SetCodeTransaction
import com.chipprbots.ethereum.domain.SignedTransaction
import com.chipprbots.ethereum.domain.SuccessOutcome
import com.chipprbots.ethereum.domain.Transaction
import com.chipprbots.ethereum.domain.TransactionWithAccessList
import com.chipprbots.ethereum.domain.TransactionWithDynamicFee
import com.chipprbots.ethereum.jsonrpc.CallErrors
import com.chipprbots.ethereum.utils.Logger

/** Answers the explorer's lookups from the node's own stores (spec 029 FR-028): the reads the JSON-RPC block,
  * transaction and account methods make, with no request and no response. It runs on the lookup worker's thread, never
  * on the frame loop's or the probe's (FR-029), and it only reads.
  *
  * Every miss is a sentence: what was looked for, and why the node may not have it.
  *
  * @param txLocation
  *   where the transaction index says a transaction is: its block's hash and its place in it
  * @param accountAt
  *   the account at a block's state; an exception here is state the node does not hold
  * @param codeSize
  *   the size of the code with this hash
  * @param sender
  *   a transaction's sender, recovered from its signature
  */
final class ChainLookups(
    bestHeader: () => Option[BlockHeader],
    headerByNumber: BigInt => Option[BlockHeader],
    headerByHash: BlockHash => Option[BlockHeader],
    bodyByHash: BlockHash => Option[BlockBody],
    receiptsByHash: BlockHash => Option[Seq[Receipt]],
    txLocation: ByteString => Option[(ByteString, Int)],
    accountAt: (BlockHeader, Address) => Option[Account],
    codeSize: ByteString => Option[Int],
    sender: SignedTransaction => Option[Address],
    now: () => Instant = () => Instant.now()
) extends (LookupQuery => Either[String, LookupResult])
    with Logger:

  import ChainReads.hex

  def apply(query: LookupQuery): Either[String, LookupResult] = query match
    case LookupQuery.LatestBlock => bestHeader().toRight("the node has no head block yet").flatMap(block)
    case LookupQuery.BlockByNumber(n) =>
      headerByNumber(BigInt(n)).toRight(s"the node has no block ${TuiText.formatNumber(n)}").flatMap(block)
    case LookupQuery.BlockByHash(hash) =>
      hash32(hash, "a block hash").flatMap { bytes =>
        headerByHash(BlockHash(bytes)).toRight("the node has no block with this hash").flatMap(block)
      }
    case LookupQuery.Tx(hash)      => located(hash).map((header, stx, index) => tx(header, stx, index))
    case LookupQuery.Receipt(hash) => located(hash).flatMap((header, stx, index) => receipt(header, stx, index))
    case LookupQuery.Account(address) =>
      for
        bytes <- ChainReads.bytes(address).filter(_.length == 20).toRight("an address is 0x and 40 hexadecimal digits")
        head <- bestHeader().toRight("the node has no head block yet")
        found <- scala.util.Try(accountAt(head, Address(bytes))).toEither.left.map { cause =>
          // The cause goes to the log; the operator gets what it means.
          log.debug("TUI account lookup at block {} failed: {}", head.number.toLong, cause.toString)
          s"the node does not hold the state of its head, block ${TuiText.formatNumber(head.number.toLong)}"
        }
      yield
        val account = found.getOrElse(Account())
        val code = Option.when(account.codeHash != Account.EmptyCodeHash)(codeSize(account.codeHash.value)).flatten
        LookupResult.Account(hex(bytes), head.number.toLong, account.balance.toBigInt, account.nonce.toBigInt, code)

  private def hash32(text: String, what: String): Either[String, ByteString] =
    ChainReads.bytes(text).filter(_.length == 32).toRight(s"$what is 0x and 64 hexadecimal digits")

  private def block(header: BlockHeader): Either[String, LookupResult] =
    bodyByHash(header.hash) match
      // A header with no body: a block before a SNAP pivot, or one whose body has not arrived.
      case None =>
        Right(LookupResult.Block(ChainReads.summary(header, None, now()), Vector.empty, 0, None))
      case Some(body) =>
        val lines = body.transactionList.zipWithIndex.map((stx, i) => line(header, stx, i)).toVector
        Right(
          LookupResult.Block(
            ChainReads.summary(header, Some(body), now()),
            lines,
            body.uncleNodesList.size,
            body.withdrawals.map(_.size)
          )
        )

  private def kind(t: Transaction): String = t match
    case _: LegacyTransaction         => "legacy"
    case _: TransactionWithAccessList => "access list (type 1)"
    case _: TransactionWithDynamicFee => "dynamic fee (type 2)"
    case _: BlobTransaction           => "blob (type 3)"
    case _: SetCodeTransaction        => "set code (type 4)"

  private def line(header: BlockHeader, stx: SignedTransaction, index: Int): TxLine =
    TxLine(
      index,
      hex(stx.hash.value),
      sender(stx).map(a => hex(a.bytes)),
      stx.tx.receivingAddress.map(a => hex(a.bytes)),
      stx.tx.value,
      Transaction.effectiveGasPrice(stx.tx, header.baseFee),
      stx.tx.gasLimit.value,
      kind(stx.tx),
      caps(stx.tx)
    )

  /** A fee-market transaction's two caps, the fee and the tip; none for one that names a gas price. */
  private def caps(t: Transaction): Option[(BigInt, BigInt)] = t match
    case d: TransactionWithDynamicFee => Some((d.maxFeePerGas, d.maxPriorityFeePerGas))
    case b: BlobTransaction           => Some((b.maxFeePerGas, b.maxPriorityFeePerGas))
    case s: SetCodeTransaction        => Some((s.maxFeePerGas, s.maxPriorityFeePerGas))
    case _                            => None

  /** The transaction with `hash`, with its block: found through the transaction index. */
  private def located(hash: String): Either[String, (BlockHeader, SignedTransaction, Int)] =
    for
      bytes <- hash32(hash, "a transaction hash")
      (blockHash, index) <- txLocation(bytes).toRight(
        "no transaction with this hash is in the node's index: it is not in a block the node imported, or is pending"
      )
      header <- headerByHash(BlockHash(blockHash)).toRight("the transaction's block is no longer in the node")
      body <- bodyByHash(BlockHash(blockHash)).toRight("the body of the transaction's block is not stored")
      stx <- body.transactionList.lift(index).toRight("the transaction index points past its block's transactions")
    yield (header, stx, index)

  private def tx(header: BlockHeader, stx: SignedTransaction, index: Int): LookupResult =
    LookupResult.Tx(
      line(header, stx, index),
      header.number.toLong,
      hex(header.hash.value),
      stx.tx.nonce,
      stx.tx.payload.size
    )

  private def receipt(header: BlockHeader, stx: SignedTransaction, index: Int): Either[String, LookupResult] =
    for
      receipts <- receiptsByHash(header.hash).toRight(
        s"the receipts of block ${TuiText.formatNumber(header.number.toLong)} are not stored"
      )
      found <- receipts.lift(index).toRight("the block has fewer receipts than transactions")
    yield
      val before = if index == 0 then BigInt(0) else receipts(index - 1).cumulativeGasUsed
      val status = found.postTransactionStateHash match
        case SuccessOutcome => "success"
        case FailureOutcome => "failed"
        // A receipt older than the status code carries the state's root instead. Short enough, with its 64 digits,
        // to stay on one row of an 80-column screen.
        case HashOutcome(root) => s"root ${hex(root)}"
      val from = sender(stx)
      // A creation's address is not in the receipt as stored: it follows from the sender and the nonce, as the
      // JSON-RPC receipt computes it.
      val contract =
        if stx.tx.receivingAddress.isDefined then None
        else from.map(a => hex(CallErrors.createAddress(a, stx.tx.nonce).bytes))
      LookupResult.Receipt(
        hex(stx.hash.value),
        header.number.toLong,
        index,
        status,
        found.cumulativeGasUsed - before,
        found.cumulativeGasUsed,
        found.logs.map(l => EventLog(hex(l.loggerAddress.bytes), l.logTopics.size, l.data.size)).toVector,
        from.map(a => hex(a.bytes)),
        stx.tx.receivingAddress.map(a => hex(a.bytes)),
        contract
      )
