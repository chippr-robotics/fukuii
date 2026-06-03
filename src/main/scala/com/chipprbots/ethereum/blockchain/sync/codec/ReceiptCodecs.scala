package com.chipprbots.ethereum.blockchain.sync.codec

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.domain._
import com.chipprbots.ethereum.domain.Transaction.ByteArrayTransactionTypeValidator
import com.chipprbots.ethereum.domain.Transaction.TransactionTypeValidator
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.TypedTransaction._
import com.chipprbots.ethereum.rlp.RLPImplicitConversions._
import com.chipprbots.ethereum.rlp.RLPImplicits.given
import com.chipprbots.ethereum.rlp._
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
object ReceiptCodecs {

  // ── TxLogEntry ───────────────────────────────────────────────────────────────

  implicit class TxLogEntryEnc(logEntry: TxLogEntry) extends RLPSerializable {
    override def toRLPEncodable: RLPEncodeable = {
      import logEntry._
      val topicsRLP = logTopics.map(t => RLPValue(t.toArray[Byte]))
      RLPList(
        RLPValue(loggerAddress.bytes.toArray[Byte]),
        RLPList(topicsRLP: _*),
        RLPValue(data.toArray[Byte])
      )
    }
  }

  implicit class TxLogEntryDec(rlp: RLPEncodeable) {
    def toTxLogEntry: TxLogEntry = rlp match {
      case RLPList(RLPValue(loggerAddressBytes), logTopics: RLPList, RLPValue(dataBytes)) =>
        TxLogEntry(Address(ByteString(loggerAddressBytes)), fromRlpList[ByteString](logTopics), ByteString(dataBytes))
      case _ => throw new RuntimeException("Cannot decode TransactionLog")
    }
  }

  // ── Receipt ──────────────────────────────────────────────────────────────────

  implicit class ReceiptEnc(receipt: Receipt) extends RLPSerializable {
    override def toRLPEncodable: RLPEncodeable = {
      import receipt._
      val stateHash: RLPEncodeable = postTransactionStateHash match {
        case HashOutcome(hash) => RLPValue(hash.toArray[Byte])
        case SuccessOutcome    => 1.toByte
        case _                 => 0.toByte
      }
      val legacyRLPReceipt = RLPList(
        stateHash,
        cumulativeGasUsed,
        RLPValue(logsBloomFilter.toArray[Byte]),
        RLPList(logs.map(_.toRLPEncodable): _*)
      )
      receipt match {
        case _: LegacyReceipt      => legacyRLPReceipt
        case _: Type01Receipt      => PrefixedRLPEncodable(Transaction.Type01, legacyRLPReceipt)
        case _: Type02Receipt      => PrefixedRLPEncodable(Transaction.Type02, legacyRLPReceipt)
        case _: Type03Receipt      => PrefixedRLPEncodable(Transaction.Type03, legacyRLPReceipt)
        case _: Type04Receipt      => PrefixedRLPEncodable(Transaction.Type04, legacyRLPReceipt)
        case _: TypedLegacyReceipt => legacyRLPReceipt
      }
    }
  }

  implicit class ReceiptSeqEnc(receipts: Seq[Receipt]) extends RLPSerializable {
    override def toRLPEncodable: RLPEncodeable = RLPList(receipts.map(_.toRLPEncodable): _*)
  }

  implicit class ReceiptDec(val bytes: Array[Byte]) extends AnyVal {
    def toReceipt: Receipt = {
      if (bytes.isEmpty) throw new RuntimeException("Cannot decode Receipt: empty byte array")
      val first = bytes(0)
      (first match {
        case txType if txType.isValidTransactionType && bytes.length > 1 =>
          PrefixedRLPEncodable(txType, rawDecode(bytes.tail))
        case _ => rawDecode(bytes)
      }).toReceipt
    }

    def toReceipts: Seq[Receipt] = rawDecode(bytes) match {
      case RLPList(items @ _*) => items.toTypedRLPEncodables.map(_.toReceipt)
      case other =>
        throw new RuntimeException(s"Cannot decode Receipts: expected RLPList, got ${other.getClass.getSimpleName}")
    }
  }

  implicit class ReceiptRLPEncodableDec(val rlpEncodeable: RLPEncodeable) extends AnyVal {

    private def decodeTypedReceiptFromBytes(bytes: Array[Byte]): Receipt = {
      val txType = bytes.head
      val payload = rawDecode(bytes.tail)
      txType match {
        case Transaction.Type01 => Type01Receipt(payload.toLegacyReceipt)
        case other              => throw new RuntimeException(s"Unsupported typed receipt type: $other")
      }
    }

    def toLegacyReceipt: LegacyReceipt = rlpEncodeable match {
      case RLPList(
            postTransactionStateHash,
            RLPValue(cumulativeGasUsedBytes),
            RLPValue(logsBloomFilterBytes),
            logs: RLPList
          ) =>
        val stateHash = postTransactionStateHash match {
          case RLPValue(bytes) if bytes.length > 1                     => HashOutcome(ByteString(bytes))
          case RLPValue(bytes) if bytes.length == 1 && bytes.head == 1 => SuccessOutcome
          case _                                                       => FailureOutcome
        }
        LegacyReceipt(
          stateHash,
          ByteUtils.bytesToBigInt(cumulativeGasUsedBytes),
          ByteString(logsBloomFilterBytes),
          logs.items.map(_.toTxLogEntry)
        )
      case RLPList(items @ _*) =>
        throw new RuntimeException(s"Cannot decode Receipt: expected 4 items in RLPList, got ${items.length}")
      case RLPValue(bytes) if bytes.nonEmpty && bytes.head.isValidTransactionType && bytes.length > 1 =>
        rawDecode(bytes.tail).toLegacyReceipt
      case other =>
        throw new RuntimeException(s"Cannot decode Receipt: expected RLPList, got ${other.getClass.getSimpleName}")
    }

    def toReceipt: Receipt = rlpEncodeable match {
      case PrefixedRLPEncodable(Transaction.Type04, legacyReceipt) => Type04Receipt(legacyReceipt.toLegacyReceipt)
      case PrefixedRLPEncodable(Transaction.Type03, legacyReceipt) => Type03Receipt(legacyReceipt.toLegacyReceipt)
      case PrefixedRLPEncodable(Transaction.Type02, legacyReceipt) => Type02Receipt(legacyReceipt.toLegacyReceipt)
      case PrefixedRLPEncodable(Transaction.Type01, legacyReceipt) => Type01Receipt(legacyReceipt.toLegacyReceipt)
      case RLPValue(bytes) if bytes.nonEmpty && bytes.head.isValidTransactionType && bytes.length > 1 =>
        decodeTypedReceiptFromBytes(bytes)
      case other => other.toLegacyReceipt
    }
  }
}
