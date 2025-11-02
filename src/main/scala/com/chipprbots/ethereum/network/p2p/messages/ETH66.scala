package com.chipprbots.ethereum.network.p2p.messages

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex

import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockBody._
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockHeaderImplicits._
import com.chipprbots.ethereum.domain.SignedTransaction
import com.chipprbots.ethereum.network.p2p.Message
import com.chipprbots.ethereum.network.p2p.MessageSerializableImplicit
import com.chipprbots.ethereum.rlp.RLPImplicitConversions._
import com.chipprbots.ethereum.rlp.RLPImplicits._
import com.chipprbots.ethereum.rlp.RLPImplicits.given
import com.chipprbots.ethereum.rlp._

/** ETH66 protocol messages - adds request-id to all request/response pairs See
  * https://github.com/ethereum/devp2p/blob/master/caps/eth.md#eth66
  */
object ETH66 {

  object GetBlockHeaders {
    implicit class GetBlockHeadersEnc(val underlyingMsg: GetBlockHeaders)
        extends MessageSerializableImplicit[GetBlockHeaders](underlyingMsg)
        with RLPSerializable {

      override def code: Int = Codes.GetBlockHeadersCode

      override def toRLPEncodable: RLPEncodeable = {
        import msg._
        val blockQuery = block match {
          case Left(blockNumber) => RLPList(blockNumber, maxHeaders, skip, if (reverse) 1 else 0)
          case Right(blockHash)  => RLPList(RLPValue(blockHash.toArray[Byte]), maxHeaders, skip, if (reverse) 1 else 0)
        }
        RLPList(RLPValue(requestId.toByteArray), blockQuery)
      }
    }

    implicit class GetBlockHeadersDec(val bytes: Array[Byte]) extends AnyVal {
      def toGetBlockHeaders: GetBlockHeaders = rawDecode(bytes) match {
        case RLPList(
              RLPValue(requestIdBytes),
              RLPList((block: RLPValue), RLPValue(maxHeadersBytes), RLPValue(skipBytes), RLPValue(reverseBytes))
            ) if block.bytes.length < 32 =>
          GetBlockHeaders(
            BigInt(1, requestIdBytes),
            Left(BigInt(1, block.bytes)),
            BigInt(1, maxHeadersBytes),
            BigInt(1, skipBytes),
            BigInt(1, reverseBytes).toInt == 1
          )

        case RLPList(
              RLPValue(requestIdBytes),
              RLPList((block: RLPValue), RLPValue(maxHeadersBytes), RLPValue(skipBytes), RLPValue(reverseBytes))
            ) =>
          GetBlockHeaders(
            BigInt(1, requestIdBytes),
            Right(ByteString(block.bytes)),
            BigInt(1, maxHeadersBytes),
            BigInt(1, skipBytes),
            BigInt(1, reverseBytes).toInt == 1
          )

        case _ => throw new RuntimeException("Cannot decode GetBlockHeaders")
      }
    }
  }

  case class GetBlockHeaders(
      requestId: BigInt,
      block: Either[BigInt, ByteString],
      maxHeaders: BigInt,
      skip: BigInt,
      reverse: Boolean
  ) extends Message {
    override def code: Int = Codes.GetBlockHeadersCode

    override def toString: String =
      s"GetBlockHeaders{ " +
        s"requestId: $requestId, " +
        s"block: ${block.fold(a => a, b => Hex.toHexString(b.toArray[Byte]))} " +
        s"maxHeaders: $maxHeaders " +
        s"skip: $skip " +
        s"reverse: $reverse " +
        s"}"

    override def toShortString: String = toString
  }

  object BlockHeaders {
    implicit class BlockHeadersEnc(val underlyingMsg: BlockHeaders)
        extends MessageSerializableImplicit[BlockHeaders](underlyingMsg)
        with RLPSerializable {

      override def code: Int = Codes.BlockHeadersCode

      override def toRLPEncodable: RLPEncodeable =
        RLPList(RLPValue(msg.requestId.toByteArray), RLPList(msg.headers.map(_.toRLPEncodable): _*))
    }

    implicit class BlockHeadersDec(val bytes: Array[Byte]) extends AnyVal {
      def toBlockHeaders: BlockHeaders = rawDecode(bytes) match {
        case RLPList(RLPValue(requestIdBytes), rlpList: RLPList) =>
          BlockHeaders(BigInt(1, requestIdBytes), rlpList.items.map(_.toBlockHeader))
        case _ => throw new RuntimeException("Cannot decode BlockHeaders")
      }
    }
  }

  case class BlockHeaders(requestId: BigInt, headers: Seq[BlockHeader]) extends Message {
    val code: Int = Codes.BlockHeadersCode
    override def toShortString: String =
      s"BlockHeaders { requestId: $requestId, count: ${headers.size} }"
  }

  object GetBlockBodies {
    implicit class GetBlockBodiesEnc(val underlyingMsg: GetBlockBodies)
        extends MessageSerializableImplicit[GetBlockBodies](underlyingMsg)
        with RLPSerializable {

      override def code: Int = Codes.GetBlockBodiesCode

      override def toRLPEncodable: RLPEncodeable = RLPList(RLPValue(msg.requestId.toByteArray), toRlpList(msg.hashes))
    }

    implicit class GetBlockBodiesDec(val bytes: Array[Byte]) extends AnyVal {
      def toGetBlockBodies: GetBlockBodies = rawDecode(bytes) match {
        case RLPList(RLPValue(requestIdBytes), rlpList: RLPList) =>
          GetBlockBodies(BigInt(1, requestIdBytes), fromRlpList[ByteString](rlpList))
        case _ => throw new RuntimeException("Cannot decode GetBlockBodies")
      }
    }
  }

  case class GetBlockBodies(requestId: BigInt, hashes: Seq[ByteString]) extends Message {
    override def code: Int = Codes.GetBlockBodiesCode

    override def toString: String =
      s"GetBlockBodies{ " +
        s"requestId: $requestId, " +
        s"hashes: ${hashes.map(h => Hex.toHexString(h.toArray[Byte]))} " +
        s"}"

    override def toShortString: String =
      s"GetBlockBodies { requestId: $requestId, count: ${hashes.size} }"
  }

  object BlockBodies {
    implicit class BlockBodiesEnc(val underlyingMsg: BlockBodies)
        extends MessageSerializableImplicit[BlockBodies](underlyingMsg)
        with RLPSerializable {
      override def code: Int = Codes.BlockBodiesCode

      override def toRLPEncodable: RLPEncodeable =
        RLPList(RLPValue(msg.requestId.toByteArray), RLPList(msg.bodies.map(_.toRLPEncodable): _*))
    }

    implicit class BlockBodiesDec(val bytes: Array[Byte]) extends AnyVal {
      def toBlockBodies: BlockBodies = rawDecode(bytes) match {
        case RLPList(RLPValue(requestIdBytes), rlpList: RLPList) =>
          BlockBodies(BigInt(1, requestIdBytes), rlpList.items.map(_.toBlockBody))
        case _ => throw new RuntimeException("Cannot decode BlockBodies")
      }
    }
  }

  case class BlockBodies(requestId: BigInt, bodies: Seq[BlockBody]) extends Message {
    val code: Int = Codes.BlockBodiesCode
    override def toShortString: String =
      s"BlockBodies { requestId: $requestId, count: ${bodies.size} }"
  }

  object GetPooledTransactions {
    implicit class GetPooledTransactionsEnc(val underlyingMsg: GetPooledTransactions)
        extends MessageSerializableImplicit[GetPooledTransactions](underlyingMsg)
        with RLPSerializable {
      override def code: Int = Codes.GetPooledTransactionsCode

      override def toRLPEncodable: RLPEncodeable = RLPList(RLPValue(msg.requestId.toByteArray), toRlpList(msg.txHashes))
    }

    implicit class GetPooledTransactionsDec(val bytes: Array[Byte]) extends AnyVal {
      def toGetPooledTransactions: GetPooledTransactions = rawDecode(bytes) match {
        case RLPList(RLPValue(requestIdBytes), rlpList: RLPList) =>
          GetPooledTransactions(BigInt(1, requestIdBytes), fromRlpList[ByteString](rlpList))
        case _ => throw new RuntimeException("Cannot decode GetPooledTransactions")
      }
    }
  }

  case class GetPooledTransactions(requestId: BigInt, txHashes: Seq[ByteString]) extends Message {
    override def code: Int = Codes.GetPooledTransactionsCode

    override def toString: String =
      s"GetPooledTransactions { " +
        s"requestId: $requestId, " +
        s"txHashes: ${txHashes.map(h => Hex.toHexString(h.toArray[Byte])).mkString(", ")} " +
        s"}"

    override def toShortString: String =
      s"GetPooledTransactions { requestId: $requestId, count: ${txHashes.size} }"
  }

  object PooledTransactions {
    implicit class PooledTransactionsEnc(val underlyingMsg: PooledTransactions)
        extends MessageSerializableImplicit[PooledTransactions](underlyingMsg)
        with RLPSerializable {
      import BaseETH6XMessages.SignedTransactions._

      override def code: Int = Codes.PooledTransactionsCode

      override def toRLPEncodable: RLPEncodeable =
        RLPList(RLPValue(msg.requestId.toByteArray), RLPList(msg.txs.map(_.toRLPEncodable): _*))
    }

    implicit class PooledTransactionsDec(val bytes: Array[Byte]) extends AnyVal {
      import BaseETH6XMessages.SignedTransactions._
      import BaseETH6XMessages.TypedTransaction._

      def toPooledTransactions: PooledTransactions = rawDecode(bytes) match {
        case RLPList(RLPValue(requestIdBytes), rlpList: RLPList) =>
          PooledTransactions(BigInt(1, requestIdBytes), rlpList.items.toTypedRLPEncodables.map(_.toSignedTransaction))
        case _ => throw new RuntimeException("Cannot decode PooledTransactions")
      }
    }
  }

  case class PooledTransactions(requestId: BigInt, txs: Seq[SignedTransaction]) extends Message {
    override def code: Int = Codes.PooledTransactionsCode

    override def toString: String =
      s"PooledTransactions { " +
        s"requestId: $requestId, " +
        s"txs: ${txs.mkString(", ")} " +
        s"}"

    override def toShortString: String =
      s"PooledTransactions { requestId: $requestId, count: ${txs.size} }"
  }

  object GetNodeData {
    implicit class GetNodeDataEnc(val underlyingMsg: GetNodeData)
        extends MessageSerializableImplicit[GetNodeData](underlyingMsg)
        with RLPSerializable {
      override def code: Int = Codes.GetNodeDataCode

      override def toRLPEncodable: RLPEncodeable =
        RLPList(RLPValue(msg.requestId.toByteArray), toRlpList(msg.mptElementsHashes))
    }

    implicit class GetNodeDataDec(val bytes: Array[Byte]) extends AnyVal {
      def toGetNodeData: GetNodeData = rawDecode(bytes) match {
        case RLPList(RLPValue(requestIdBytes), rlpList: RLPList) =>
          GetNodeData(BigInt(1, requestIdBytes), fromRlpList[ByteString](rlpList))
        case _ => throw new RuntimeException("Cannot decode GetNodeData")
      }
    }
  }

  case class GetNodeData(requestId: BigInt, mptElementsHashes: Seq[ByteString]) extends Message {
    override def code: Int = Codes.GetNodeDataCode

    override def toString: String =
      s"GetNodeData{ requestId: $requestId, hashes: ${mptElementsHashes.map(e => Hex.toHexString(e.toArray[Byte]))} }"

    override def toShortString: String =
      s"GetNodeData{ requestId: $requestId, count: ${mptElementsHashes.size} }"
  }

  object NodeData {
    implicit class NodeDataEnc(val underlyingMsg: NodeData)
        extends MessageSerializableImplicit[NodeData](underlyingMsg)
        with RLPSerializable {

      override def code: Int = Codes.NodeDataCode
      override def toRLPEncodable: RLPEncodeable = RLPList(RLPValue(msg.requestId.toByteArray), msg.values)
    }

    implicit class NodeDataDec(val bytes: Array[Byte]) extends AnyVal {
      def toNodeData: NodeData = rawDecode(bytes) match {
        case RLPList(RLPValue(requestIdBytes), (rlpList: RLPList)) =>
          NodeData(BigInt(1, requestIdBytes), rlpList)
        case _ => throw new RuntimeException("Cannot decode NodeData")
      }
    }
  }

  case class NodeData(requestId: BigInt, values: RLPList) extends Message {
    override def code: Int = Codes.NodeDataCode

    override def toString: String =
      s"NodeData { requestId: $requestId, values: <${values.items.size} nodes> }"

    override def toShortString: String = toString
  }

  object GetReceipts {
    implicit class GetReceiptsEnc(val underlyingMsg: GetReceipts)
        extends MessageSerializableImplicit[GetReceipts](underlyingMsg)
        with RLPSerializable {
      override def code: Int = Codes.GetReceiptsCode

      override def toRLPEncodable: RLPEncodeable =
        RLPList(RLPValue(msg.requestId.toByteArray), toRlpList(msg.blockHashes))
    }

    implicit class GetReceiptsDec(val bytes: Array[Byte]) extends AnyVal {
      def toGetReceipts: GetReceipts = rawDecode(bytes) match {
        case RLPList(RLPValue(requestIdBytes), rlpList: RLPList) =>
          GetReceipts(BigInt(1, requestIdBytes), fromRlpList[ByteString](rlpList))
        case _ => throw new RuntimeException("Cannot decode GetReceipts")
      }
    }
  }

  case class GetReceipts(requestId: BigInt, blockHashes: Seq[ByteString]) extends Message {
    override def code: Int = Codes.GetReceiptsCode

    override def toString: String =
      s"GetReceipts { " +
        s"requestId: $requestId, " +
        s"blockHashes: ${blockHashes.map(h => Hex.toHexString(h.toArray[Byte])).mkString(", ")} " +
        s"}"

    override def toShortString: String =
      s"GetReceipts { requestId: $requestId, count: ${blockHashes.size} }"
  }

  object Receipts {
    implicit class ReceiptsEnc(val underlyingMsg: Receipts)
        extends MessageSerializableImplicit[Receipts](underlyingMsg)
        with RLPSerializable {
      override def code: Int = Codes.ReceiptsCode

      override def toRLPEncodable: RLPEncodeable = RLPList(RLPValue(msg.requestId.toByteArray), msg.receiptsForBlocks)
    }

    implicit class ReceiptsDec(val bytes: Array[Byte]) extends AnyVal {
      def toReceipts: Receipts = rawDecode(bytes) match {
        case RLPList(RLPValue(requestIdBytes), (rlpList: RLPList)) =>
          Receipts(BigInt(1, requestIdBytes), rlpList)
        case _ => throw new RuntimeException("Cannot decode Receipts")
      }
    }
  }

  case class Receipts(requestId: BigInt, receiptsForBlocks: RLPList) extends Message {
    override def code: Int = Codes.ReceiptsCode

    override def toShortString: String =
      s"Receipts { requestId: $requestId, count: ${receiptsForBlocks.items.size} }"
  }
}
