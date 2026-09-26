package com.chipprbots.ethereum.blockchain.sync.codec

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex

import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.Transaction.TransactionTypeValidator
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.TypedTransaction.*
import com.chipprbots.ethereum.rlp.*
import com.chipprbots.ethereum.rlp.RLPImplicitConversions.*
import com.chipprbots.ethereum.rlp.RLPImplicits.given
import com.chipprbots.ethereum.utils.ByteUtils

/** RLP codecs for Receipt and TxLogEntry (storage and wire format).
  *
  * Moved from ETH63.ReceiptImplicits / ETH63.TxLogEntryImplicits. Lives here (sync/codec) rather than in domain because
  * the decode path imports ETHPackets.TypedTransaction for EIP-2718 typed receipt dispatch, and domain cannot import
  * from the network message layer without creating a circular dependency.
  *
  * Matches the Besu `ethereum/core/encoding/` pattern.
  *
  * Usage: `import com.chipprbots.ethereum.blockchain.sync.codec.ReceiptCodecs._`
  */
object ReceiptCodecs:

  // ── TxLogEntry ───────────────────────────────────────────────────────────────

  extension (logEntry: TxLogEntry)
    def toRLPEncodable: RLPEncodeable =
      import logEntry.*
      val topicsRLP = logTopics.map(t => RLPValue(t.toArray[Byte]))
      RLPList(
        RLPValue(loggerAddress.bytes.toArray[Byte]),
        RLPList(topicsRLP*),
        RLPValue(data.toArray[Byte])
      )

  extension (rlp: RLPEncodeable)
    def toTxLogEntry: TxLogEntry = rlp match
      case RLPList(RLPValue(loggerAddressBytes), logTopics: RLPList, RLPValue(dataBytes)) =>
        TxLogEntry(Address(ByteString(loggerAddressBytes)), fromRlpList[ByteString](logTopics), ByteString(dataBytes))
      case _ => throw new RuntimeException("Cannot decode TransactionLog")

  // ── Receipt ──────────────────────────────────────────────────────────────────

  extension (receipt: Receipt)
    def toRLPEncodable: RLPEncodeable =
      import receipt.*
      val stateHash: RLPEncodeable = postTransactionStateHash match
        case HashOutcome(hash) => RLPValue(hash.toArray[Byte])
        case SuccessOutcome    => 1.toByte
        case _                 => 0.toByte
      val legacyRLPReceipt = RLPList(
        stateHash,
        cumulativeGasUsed,
        RLPValue(logsBloomFilter.toArray),
        RLPList(logs.map(_.toRLPEncodable)*)
      )
      receipt match
        case _: LegacyReceipt      => legacyRLPReceipt
        case _: Type01Receipt      => PrefixedRLPEncodable(Transaction.Type01, legacyRLPReceipt)
        case _: Type02Receipt      => PrefixedRLPEncodable(Transaction.Type02, legacyRLPReceipt)
        case _: Type03Receipt      => PrefixedRLPEncodable(Transaction.Type03, legacyRLPReceipt)
        case _: Type04Receipt      => PrefixedRLPEncodable(Transaction.Type04, legacyRLPReceipt)
        case _: TypedLegacyReceipt => legacyRLPReceipt
    def toBytes: Array[Byte] = encode(receipt.toRLPEncodable)

  extension (receipts: Seq[Receipt])
    def toRLPEncodable: RLPEncodeable = RLPList(receipts.map(_.toRLPEncodable)*)
    def toBytes: Array[Byte] = encode(receipts.toRLPEncodable)

  extension (bytes: Array[Byte])
    def toReceipt: Receipt =
      if bytes.isEmpty then throw new RuntimeException("Cannot decode Receipt: empty byte array")
      val first = bytes(0)
      (first match
        case txType if txType.isValidTransactionType && bytes.length > 1 =>
          PrefixedRLPEncodable(txType, rawDecode(bytes.tail))
        case _ => rawDecode(bytes)
      ).toReceipt

    def toReceipts: Seq[Receipt] = rawDecode(bytes) match
      case RLPList(items*) => items.toTypedRLPEncodables.map(_.toReceipt)
      case other =>
        throw new RuntimeException(s"Cannot decode Receipts: expected RLPList, got ${other.getClass.getSimpleName}")

  extension (rlpEncodeable: RLPEncodeable)
    def toLegacyReceipt: LegacyReceipt = rlpEncodeable match
      // 4-field: ETH68 bloom-inclusive  [stateHash, gasUsed, logsBloom, logs]
      case RLPList(
            postTransactionStateHash,
            RLPValue(cumulativeGasUsedBytes),
            RLPValue(logsBloomFilterBytes),
            logs: RLPList
          ) =>
        val stateHash = postTransactionStateHash match
          case RLPValue(bytes) if bytes.length > 1                     => HashOutcome(ByteString(bytes))
          case RLPValue(bytes) if bytes.length == 1 && bytes.head == 1 => SuccessOutcome
          case _                                                       => FailureOutcome
        LegacyReceipt(
          stateHash,
          ByteUtils.bytesToBigInt(cumulativeGasUsedBytes),
          BloomFilter(ByteString(logsBloomFilterBytes)),
          logs.items.map(_.toTxLogEntry)
        )
      // 3-field [stateHash, gasUsed, logs]: the eth/69 shape fukuii itself used to send, before it followed
      // EIP-7642's [txType, stateHash, gasUsed, logs] — eth/69+ receipts now decode through toEth69Receipt.
      // Bloom stored as 256 zero bytes.
      case RLPList(
            postTransactionStateHash,
            RLPValue(cumulativeGasUsedBytes),
            logs: RLPList
          ) =>
        val stateHash = postTransactionStateHash match
          case RLPValue(bytes) if bytes.length > 1                     => HashOutcome(ByteString(bytes))
          case RLPValue(bytes) if bytes.length == 1 && bytes.head == 1 => SuccessOutcome
          case _                                                       => FailureOutcome
        LegacyReceipt(
          stateHash,
          ByteUtils.bytesToBigInt(cumulativeGasUsedBytes),
          BloomFilter.Empty,
          logs.items.map(_.toTxLogEntry)
        )
      case RLPList(items*) =>
        throw new RuntimeException(s"Cannot decode Receipt: expected 3 or 4 items in RLPList, got ${items.length}")
      case RLPValue(bytes) if bytes.nonEmpty && bytes.head.isValidTransactionType && bytes.length > 1 =>
        rawDecode(bytes.tail).toLegacyReceipt
      case other =>
        throw new RuntimeException(s"Cannot decode Receipt: expected RLPList, got ${other.getClass.getSimpleName}")

    def toReceipt: Receipt =
      def decodeTypedReceiptFromBytes(bytes: Array[Byte]): Receipt =
        val txType = bytes.head
        val payload = rawDecode(bytes.tail)
        txType match
          case Transaction.Type01 => Type01Receipt(payload.toLegacyReceipt)
          case other              => throw new RuntimeException(s"Unsupported typed receipt type: $other")
      rlpEncodeable match
        case PrefixedRLPEncodable(Transaction.Type04, legacyReceipt) => Type04Receipt(legacyReceipt.toLegacyReceipt)
        case PrefixedRLPEncodable(Transaction.Type03, legacyReceipt) => Type03Receipt(legacyReceipt.toLegacyReceipt)
        case PrefixedRLPEncodable(Transaction.Type02, legacyReceipt) => Type02Receipt(legacyReceipt.toLegacyReceipt)
        case PrefixedRLPEncodable(Transaction.Type01, legacyReceipt) => Type01Receipt(legacyReceipt.toLegacyReceipt)
        case RLPValue(bytes) if bytes.nonEmpty && bytes.head.isValidTransactionType && bytes.length > 1 =>
          decodeTypedReceiptFromBytes(bytes)
        case other => other.toLegacyReceipt

    /** Decode one receipt in the eth/69 network form (EIP-7642), which eth/70-72 keep: `[txType, postStateOrStatus,
      * cumulativeGasUsed, logs]`, every receipt a plain four-item list whatever its type. The bloom is not on the wire,
      * so it is recomputed from the logs. Any other shape — the eth/68 bloom form, an EIP-2718-prefixed receipt, an
      * unknown type, a postStateOrStatus that is neither a status nor a 32-byte root — is rejected rather than coerced:
      * read as the eth/68 form, a four-item network receipt decodes without error into a receipt whose type, status,
      * gas and bloom are all wrong.
      */
    def toEth69Receipt: Receipt = rlpEncodeable match
      case RLPList(RLPValue(txType), RLPValue(outcome), RLPValue(cumulativeGasUsed), logs: RLPList) =>
        val postTransactionStateHash =
          if outcome.isEmpty then FailureOutcome
          else if outcome.length == 1 && outcome(0) == 1 then SuccessOutcome
          else if outcome.length == 32 then HashOutcome(ByteString(outcome))
          else
            throw new RuntimeException(
              s"Cannot decode eth/69 receipt: postStateOrStatus is ${Hex.toHexString(outcome)}"
            )
        val logEntries = logs.items.map(_.toTxLogEntry)
        val receipt = LegacyReceipt(
          postTransactionStateHash,
          ByteUtils.bytesToBigInt(cumulativeGasUsed),
          BloomFilter(com.chipprbots.ethereum.ledger.BloomFilter.create(logEntries)),
          logEntries
        )
        if txType.isEmpty then receipt
        else if txType.length == 1 then
          txType(0) match
            case Transaction.Type01 => Type01Receipt(receipt)
            case Transaction.Type02 => Type02Receipt(receipt)
            case Transaction.Type03 => Type03Receipt(receipt)
            case Transaction.Type04 => Type04Receipt(receipt)
            case other              => throw new RuntimeException(s"Cannot decode eth/69 receipt: tx type $other")
        else throw new RuntimeException(s"Cannot decode eth/69 receipt: tx type ${Hex.toHexString(txType)}")
      case other =>
        throw new RuntimeException(
          s"Cannot decode eth/69 receipt: expected [txType, postStateOrStatus, cumulativeGasUsed, logs], got $other"
        )
