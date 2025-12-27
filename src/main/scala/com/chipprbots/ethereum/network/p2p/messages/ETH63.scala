package com.chipprbots.ethereum.network.p2p.messages

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex

import com.chipprbots.ethereum.domain._
import com.chipprbots.ethereum.mpt.MptNode
import com.chipprbots.ethereum.mpt.MptTraversals
import com.chipprbots.ethereum.network.p2p.Message
import com.chipprbots.ethereum.network.p2p.MessageSerializableImplicit
import com.chipprbots.ethereum.rlp.RLPImplicitConversions._
import com.chipprbots.ethereum.rlp.RLPImplicits._
import com.chipprbots.ethereum.rlp.RLPImplicits.given
import com.chipprbots.ethereum.rlp._
import com.chipprbots.ethereum.utils.ByteUtils

object ETH63 {

  object GetNodeData {
    implicit class GetNodeDataEnc(val underlyingMsg: GetNodeData)
        extends MessageSerializableImplicit[GetNodeData](underlyingMsg)
        with RLPSerializable {
      override def code: Int = Codes.GetNodeDataCode

      override def toRLPEncodable: RLPEncodeable = toRlpList(msg.mptElementsHashes)
    }

    implicit class GetNodeDataDec(val bytes: Array[Byte]) extends AnyVal {
      def toGetNodeData: GetNodeData = rawDecode(bytes) match {
        case rlpList: RLPList => GetNodeData(fromRlpList[ByteString](rlpList))
        case _                => throw new RuntimeException("Cannot decode GetNodeData")
      }
    }
  }

  case class GetNodeData(mptElementsHashes: Seq[ByteString]) extends Message {
    override def code: Int = Codes.GetNodeDataCode

    override def toString: String =
      s"GetNodeData{ hashes: ${mptElementsHashes.map(e => Hex.toHexString(e.toArray[Byte]))} }"

    override def toShortString: String =
      s"GetNodeData{ hashes: <${mptElementsHashes.size} state tree hashes> }"
  }

  object AccountImplicits {
    import UInt256RLPImplicits._
    import RLPImplicits.byteStringEncDec

    implicit class AccountEnc(val account: Account) extends RLPSerializable {
      override def toRLPEncodable: RLPEncodeable = {
        import account._
        RLPList(
          nonce.toRLPEncodable,
          balance.toRLPEncodable,
          byteStringEncDec.encode(storageRoot),
          byteStringEncDec.encode(codeHash)
        )
      }
    }

    implicit class AccountDec(val bytes: Array[Byte]) extends AnyVal {
      def toAccount: Account = rawDecode(bytes) match {
        case RLPList(
              RLPValue(nonceBytes),
              RLPValue(balanceBytes),
              RLPValue(storageRootBytes),
              RLPValue(codeHashBytes)
            ) =>
          Account(
            UInt256(ByteUtils.bytesToBigInt(nonceBytes)),
            UInt256(ByteUtils.bytesToBigInt(balanceBytes)),
            ByteString(storageRootBytes),
            ByteString(codeHashBytes)
          )
        case _ => throw new RuntimeException("Cannot decode Account")
      }
    }
  }

  object MptNodeEncoders {
    val BranchNodeChildLength = 16
    val BranchNodeIndexOfValue = 16
    val ExtensionNodeLength = 2
    val LeafNodeLength = 2
    val MaxNodeValueSize = 31
    val HashLength = 32

    implicit class MptNodeEnc(obj: MptNode) extends RLPSerializable {
      def toRLPEncodable: RLPEncodeable = MptTraversals.encode(obj)
    }

    implicit class MptNodeDec(val bytes: Array[Byte]) extends AnyVal {
      def toMptNode: MptNode = MptTraversals.decodeNode(bytes)
    }

    implicit class MptNodeRLPEncodableDec(val rlp: RLPEncodeable) extends AnyVal {
      def toMptNode: MptNode = rlp match {
        case RLPValue(bytes) => MptTraversals.decodeNode(bytes)
        case _               => throw new RuntimeException("Cannot decode MptNode from non-RLPValue")
      }
    }
  }

  object NodeData {
    implicit class NodeDataEnc(val underlyingMsg: NodeData)
        extends MessageSerializableImplicit[NodeData](underlyingMsg)
        with RLPSerializable {

      import MptNodeEncoders._

      override def code: Int = Codes.NodeDataCode
      override def toRLPEncodable: RLPEncodeable = RLPList(msg.values.map(v => RLPValue(v.toArray[Byte])): _*)

      @throws[RLPException]
      def getMptNode(index: Int): MptNode = msg.values(index).toArray[Byte].toMptNode
    }

    implicit class NodeDataDec(val bytes: Array[Byte]) extends AnyVal {
      def toNodeData: NodeData = rawDecode(bytes) match {
        case rlpList: RLPList =>
          NodeData(rlpList.items.map {
            case RLPValue(bytes) => ByteString(bytes)
            case _               => throw new RuntimeException("Cannot decode NodeData item")
          })
        case _ => throw new RuntimeException("Cannot decode NodeData")
      }
    }
  }

  case class NodeData(values: Seq[ByteString]) extends Message {

    override def code: Int = Codes.NodeDataCode

    override def toString: String =
      s"NodeData{ values: ${values.map(b => Hex.toHexString(b.toArray[Byte]))} }"

    override def toShortString: String =
      s"NodeData{ values: <${values.size} state tree values> }"
  }

  object GetReceipts {
    implicit class GetReceiptsEnc(val underlyingMsg: GetReceipts)
        extends MessageSerializableImplicit[GetReceipts](underlyingMsg)
        with RLPSerializable {
      override def code: Int = Codes.GetReceiptsCode

      override def toRLPEncodable: RLPEncodeable = msg.blockHashes: RLPList
    }

    implicit class GetReceiptsDec(val bytes: Array[Byte]) extends AnyVal {
      def toGetReceipts: GetReceipts = rawDecode(bytes) match {
        case rlpList: RLPList => GetReceipts(fromRlpList[ByteString](rlpList))
        case _                => throw new RuntimeException("Cannot decode GetReceipts")
      }
    }
  }

  case class GetReceipts(blockHashes: Seq[ByteString]) extends Message {
    override def code: Int = Codes.GetReceiptsCode

    override def toString: String =
      s"GetReceipts{ blockHashes: ${blockHashes.map(e => Hex.toHexString(e.toArray[Byte]))} } "

    override def toShortString: String = toString
  }

  object TxLogEntryImplicits {

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
  }

  object ReceiptImplicits {
    import TxLogEntryImplicits._

    implicit class ReceiptEnc(receipt: Receipt) extends RLPSerializable {
      override def toRLPEncodable: RLPEncodeable = {
        import receipt._
        val stateHash: RLPEncodeable = postTransactionStateHash match {
          case HashOutcome(hash) => RLPValue(hash.toArray[Byte])
          case SuccessOutcome    => 1.toByte
          case _                 => 0.toByte
        }
        val legacyRLPReceipt =
          RLPList(
            stateHash,
            cumulativeGasUsed,
            RLPValue(logsBloomFilter.toArray[Byte]),
            RLPList(logs.map(_.toRLPEncodable): _*)
          )
        receipt match {
          case _: LegacyReceipt      => legacyRLPReceipt
          case _: Type01Receipt      => PrefixedRLPEncodable(Transaction.Type01, legacyRLPReceipt)
          case _: TypedLegacyReceipt => legacyRLPReceipt // Handle typed legacy receipts
        }
      }
    }

    implicit class ReceiptSeqEnc(receipts: Seq[Receipt]) extends RLPSerializable {
      override def toRLPEncodable: RLPEncodeable = RLPList(receipts.map(_.toRLPEncodable): _*)
    }

    implicit class ReceiptDec(val bytes: Array[Byte]) extends AnyVal {
      import BaseETH6XMessages.TypedTransaction._

      def toReceipt: Receipt = {
        if (bytes.isEmpty) {
          throw new RuntimeException("Cannot decode Receipt: empty byte array")
        }
        val first = bytes(0)
        (first match {
          case Transaction.Type01 => PrefixedRLPEncodable(Transaction.Type01, rawDecode(bytes.tail))
          case _                  => rawDecode(bytes)
        }).toReceipt
      }

      def toReceipts: Seq[Receipt] = rawDecode(bytes) match {
        case RLPList(items @ _*) => items.toTypedRLPEncodables.map(_.toReceipt)
        case other =>
          throw new RuntimeException(s"Cannot decode Receipts: expected RLPList, got ${other.getClass.getSimpleName}")
      }
    }

    implicit class ReceiptRLPEncodableDec(val rlpEncodeable: RLPEncodeable) extends AnyVal {

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
        case other =>
          throw new RuntimeException(s"Cannot decode Receipt: expected RLPList, got ${other.getClass.getSimpleName}")
      }

      def toReceipt: Receipt = rlpEncodeable match {
        case PrefixedRLPEncodable(Transaction.Type01, legacyReceipt) => Type01Receipt(legacyReceipt.toLegacyReceipt)
        case other                                                   => other.toLegacyReceipt
      }
    }
  }

  object Receipts {
    implicit class ReceiptsEnc(val underlyingMsg: Receipts)
        extends MessageSerializableImplicit[Receipts](underlyingMsg)
        with RLPSerializable {
      import ReceiptImplicits._

      override def code: Int = Codes.ReceiptsCode

      override def toRLPEncodable: RLPEncodeable = RLPList(
        msg.receiptsForBlocks.map((rs: Seq[Receipt]) => RLPList(rs.map((r: Receipt) => r.toRLPEncodable): _*)): _*
      )
    }

    implicit class ReceiptsDec(val bytes: Array[Byte]) extends AnyVal {
      import ReceiptImplicits._
      import BaseETH6XMessages.TypedTransaction._

      /** Helper to expand typed receipts that come as RLPValue(typeByte || rlp(payload)) into the format expected by
        * toTypedRLPEncodables: Seq(RLPValue(typeByte), RLPList(payload))
        */
      private def expandTypedReceipts(items: Seq[RLPEncodeable]): Seq[RLPEncodeable] =
        items.flatMap {
          case v: RLPValue =>
            val receiptBytes = v.bytes
            if (receiptBytes.isEmpty) {
              throw new RuntimeException("Cannot decode Receipt: empty RLPValue")
            }
            val first = receiptBytes(0)
            // Check if this is a typed receipt (transaction type byte < 0x7f)
            if ((first & 0xff) < 0x7f && (first & 0xff) >= 0 && receiptBytes.length > 1) {
              // Typed receipt in wire format: RLPValue(typeByte || rlp(payload))
              // Expand it to Seq(RLPValue(typeByte), RLPList) for toTypedRLPEncodables
              try {
                Seq(RLPValue(Array(first)), rawDecode(receiptBytes.tail))
              } catch {
                case e: Exception =>
                  // If expansion fails, keep as-is (might be legacy receipt)
                  Seq(v)
              }
            } else {
              // Legacy receipt or invalid - keep as-is
              Seq(v)
            }
          case other => Seq(other)
        }

      def toReceipts: Receipts = rawDecode(bytes) match {
        case rlpList: RLPList =>
          Receipts(rlpList.items.collect { case r: RLPList =>
            expandTypedReceipts(r.items).toTypedRLPEncodables.map(_.toReceipt)
          })
        case other =>
          throw new RuntimeException(s"Cannot decode Receipts: expected RLPList, got ${other.getClass.getSimpleName}")
      }
    }
  }

  case class Receipts(receiptsForBlocks: Seq[Seq[Receipt]]) extends Message {
    override def code: Int = Codes.ReceiptsCode
    override def toShortString: String =
      s"Receipts { receiptsForBlocks: <${receiptsForBlocks.map(_.size).sum} receipts across ${receiptsForBlocks.size} blocks> }"
  }
}
