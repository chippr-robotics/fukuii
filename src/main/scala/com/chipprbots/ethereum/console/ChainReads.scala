package com.chipprbots.ethereum.console

import java.time.Instant

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex

import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostAmsterdam
import com.chipprbots.ethereum.domain.Transaction

/** The node's blocks as the TUI reads them (spec 029 research §6.1): a header and, where it is stored and asked for,
  * its body, made into a [[BlockSummary]]. Display only: nothing here writes, and nothing decides what the head is.
  */
object ChainReads:

  /** `0x` and the bytes in hexadecimal. */
  def hex(bytes: ByteString): String = "0x" + Hex.toHexString(bytes.toArray)

  /** The bytes of `0x…` or bare hexadecimal; None for anything else. */
  def bytes(hex: String): Option[ByteString] =
    val digits = if hex.startsWith("0x") || hex.startsWith("0X") then hex.drop(2) else hex
    if digits.isEmpty || digits.length % 2 != 0 || !digits.forall(c => Character.digit(c, 16) >= 0) then None
    else Some(ByteString(Hex.decode(digits)))

  /** What each transaction of `body` paid above the base fee, per gas: its priority fee on a chain with a base fee, its
    * gas price on one without.
    */
  def fees(header: BlockHeader, body: BlockBody): Seq[BigInt] =
    val base = header.baseFee
    body.transactionList.map(stx => (Transaction.effectiveGasPrice(stx.tx, base) - base.getOrElse(BigInt(0))).max(0))

  /** `header` as a summary, with what `body` adds when it was read. */
  def summary(header: BlockHeader, body: Option[BlockBody], seenAt: Instant): BlockSummary =
    BlockSummary(
      number = header.number.toLong,
      hash = hex(header.hash.value),
      parentHash = hex(header.parentHash.value),
      timestamp = header.unixTimestamp.toLong,
      gasUsed = header.gasUsed.value,
      gasLimit = header.gasLimit.value,
      miner = hex(header.beneficiary),
      difficulty = header.difficulty.value,
      seenAt = seenAt,
      txCount = body.map(_.transactionList.size),
      sizeBytes = body.map(b => Block.size(Block(header, b))),
      baseFee = header.baseFee,
      slot = header.extraFields match
        case amsterdam: HefPostAmsterdam => Some(amsterdam.slotNumber.toLong)
        case _                           => None
      ,
      fees = body.flatMap(b => FeePercentiles.of(fees(header, b)))
    )

  /** The two reads the head observer makes, over the node's own lookups.
    *
    * @param bestHeader
    *   the head's header
    * @param headerByHash
    *   a header by its hash
    * @param bodyByHash
    *   a body by its block's hash; None where the body is not stored
    */
  final class Source(
      bestHeader: () => Option[BlockHeader],
      headerByHash: BlockHash => Option[BlockHeader],
      bodyByHash: BlockHash => Option[BlockBody]
  ):
    /** The head, with its body. */
    def head(now: Instant): Option[BlockSummary] =
      bestHeader().map(header => summary(header, bodyByHash(header.hash), now))

    /** The block with `hash`, with its body when `withBody` is set. */
    def block(hash: String, withBody: Boolean, now: Instant): Option[BlockSummary] =
      bytes(hash).map(BlockHash(_)).flatMap(headerByHash).map { header =>
        summary(header, if withBody then bodyByHash(header.hash) else None, now)
      }
