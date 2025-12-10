package com.chipprbots.ethereum.network.p2p

import scala.util.Try

import com.chipprbots.ethereum.network.p2p.messages.BaseETH6XMessages.SignedTransactions._
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.network.p2p.messages.Codes
import com.chipprbots.ethereum.network.p2p.messages.ETH61.BlockHashesFromNumber._
import com.chipprbots.ethereum.network.p2p.messages.ETH62.BlockBodies._
import com.chipprbots.ethereum.network.p2p.messages.ETH62.BlockHeaders._
import com.chipprbots.ethereum.network.p2p.messages.ETH62.GetBlockBodies._
import com.chipprbots.ethereum.network.p2p.messages.ETH62.GetBlockHeaders._
import com.chipprbots.ethereum.network.p2p.messages.ETH62.NewBlockHashes._
import com.chipprbots.ethereum.network.p2p.messages.ETH63.GetNodeData._
import com.chipprbots.ethereum.network.p2p.messages.ETH63.GetReceipts._
import com.chipprbots.ethereum.network.p2p.messages.ETH63.NodeData._
import com.chipprbots.ethereum.network.p2p.messages.ETH63.Receipts._
import com.chipprbots.ethereum.network.p2p.messages.WireProtocol.Disconnect._
import com.chipprbots.ethereum.network.p2p.messages.WireProtocol.Hello._
import com.chipprbots.ethereum.network.p2p.messages.WireProtocol.Ping._
import com.chipprbots.ethereum.network.p2p.messages.WireProtocol.Pong._
import com.chipprbots.ethereum.network.p2p.messages.WireProtocol._

import MessageDecoder._

object NetworkMessageDecoder extends MessageDecoder {

  override def fromBytes(msgCode: Int, payload: Array[Byte]): Either[DecodingError, Message] =
    msgCode match {
      case Disconnect.code =>
        Try(payload.toDisconnect).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Ping.code =>
        Try(payload.toPing).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Pong.code =>
        Try(payload.toPong).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Hello.code =>
        Try(payload.toHello).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case _ => Left(UnknownMessageTypeError(msgCode, s"Unknown network message type: $msgCode"))
    }

}

object ETH64MessageDecoder extends MessageDecoder {
  import com.chipprbots.ethereum.network.p2p.messages.ETH64.Status._
  import com.chipprbots.ethereum.network.p2p.messages.BaseETH6XMessages.NewBlock._

  def fromBytes(msgCode: Int, payload: Array[Byte]): Either[DecodingError, Message] =
    msgCode match {
      case Codes.GetNodeDataCode =>
        Try(payload.toGetNodeData).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.NodeDataCode =>
        Try(payload.toNodeData).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.GetReceiptsCode =>
        Try(payload.toGetReceipts).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.ReceiptsCode =>
        Try(payload.toReceipts).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.NewBlockHashesCode =>
        Try(payload.toNewBlockHashes).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.GetBlockHeadersCode =>
        Try(payload.toGetBlockHeaders).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.BlockHeadersCode =>
        Try(payload.toBlockHeaders).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.GetBlockBodiesCode =>
        Try(payload.toGetBlockBodies).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.BlockBodiesCode =>
        Try(payload.toBlockBodies).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.BlockHashesFromNumberCode =>
        Try(payload.toBlockHashesFromNumber).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.StatusCode =>
        Try(payload.toStatus).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.NewBlockCode =>
        Try(payload.toNewBlock).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.SignedTransactionsCode =>
        Try(payload.toSignedTransactions).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case _ => Left(UnknownMessageTypeError(msgCode, s"Unknown eth/64 message type: $msgCode"))
    }
}

object ETH63MessageDecoder extends MessageDecoder {
  import com.chipprbots.ethereum.network.p2p.messages.BaseETH6XMessages.Status._
  import com.chipprbots.ethereum.network.p2p.messages.BaseETH6XMessages.NewBlock._

  def fromBytes(msgCode: Int, payload: Array[Byte]): Either[DecodingError, Message] =
    msgCode match {
      case Codes.GetNodeDataCode =>
        Try(payload.toGetNodeData).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.NodeDataCode =>
        Try(payload.toNodeData).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.GetReceiptsCode =>
        Try(payload.toGetReceipts).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.ReceiptsCode =>
        Try(payload.toReceipts).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.NewBlockHashesCode =>
        Try(payload.toNewBlockHashes).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.GetBlockHeadersCode =>
        Try(payload.toGetBlockHeaders).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.BlockHeadersCode =>
        Try(payload.toBlockHeaders).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.GetBlockBodiesCode =>
        Try(payload.toGetBlockBodies).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.BlockBodiesCode =>
        Try(payload.toBlockBodies).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.BlockHashesFromNumberCode =>
        Try(payload.toBlockHashesFromNumber).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.StatusCode =>
        Try(payload.toStatus).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.NewBlockCode =>
        Try(payload.toNewBlock).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.SignedTransactionsCode =>
        Try(payload.toSignedTransactions).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case _ => Left(UnknownMessageTypeError(msgCode, s"Unknown eth/63 message type: $msgCode"))
    }
}

object ETH65MessageDecoder extends MessageDecoder {
  import com.chipprbots.ethereum.network.p2p.messages.ETH64.Status._
  import com.chipprbots.ethereum.network.p2p.messages.BaseETH6XMessages.NewBlock._
  import com.chipprbots.ethereum.network.p2p.messages.ETH65.NewPooledTransactionHashes._
  import com.chipprbots.ethereum.network.p2p.messages.ETH65.GetPooledTransactions._
  import com.chipprbots.ethereum.network.p2p.messages.ETH65.PooledTransactions._

  def fromBytes(msgCode: Int, payload: Array[Byte]): Either[DecodingError, Message] =
    msgCode match {
      case Codes.StatusCode =>
        Try(payload.toStatus).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.NewBlockHashesCode =>
        Try(payload.toNewBlockHashes).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.SignedTransactionsCode =>
        Try(payload.toSignedTransactions).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.GetBlockHeadersCode =>
        Try(payload.toGetBlockHeaders).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.BlockHeadersCode =>
        Try(payload.toBlockHeaders).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.GetBlockBodiesCode =>
        Try(payload.toGetBlockBodies).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.BlockBodiesCode =>
        Try(payload.toBlockBodies).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.NewBlockCode =>
        Try(payload.toNewBlock).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.NewPooledTransactionHashesCode =>
        Try(payload.toNewPooledTransactionHashes).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.GetPooledTransactionsCode =>
        Try(payload.toGetPooledTransactions).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.PooledTransactionsCode =>
        Try(payload.toPooledTransactions).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.GetNodeDataCode =>
        Try(payload.toGetNodeData).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.NodeDataCode =>
        Try(payload.toNodeData).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.GetReceiptsCode =>
        Try(payload.toGetReceipts).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.ReceiptsCode =>
        Try(payload.toReceipts).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case _ => Left(UnknownMessageTypeError(msgCode, s"Unknown eth/65 message type: $msgCode"))
    }
}

object ETH66MessageDecoder extends MessageDecoder {
  import com.chipprbots.ethereum.network.p2p.messages.ETH64.Status._
  import com.chipprbots.ethereum.network.p2p.messages.BaseETH6XMessages.NewBlock._
  import com.chipprbots.ethereum.network.p2p.messages.ETH65.{
    NewPooledTransactionHashes => ETH65NewPooledTransactionHashes
  }
  import com.chipprbots.ethereum.network.p2p.messages.ETH66.GetBlockHeaders._
  import com.chipprbots.ethereum.network.p2p.messages.ETH66.BlockHeaders._
  import com.chipprbots.ethereum.network.p2p.messages.ETH66.GetBlockBodies._
  import com.chipprbots.ethereum.network.p2p.messages.ETH66.BlockBodies._
  import com.chipprbots.ethereum.network.p2p.messages.ETH66.GetPooledTransactions._
  import com.chipprbots.ethereum.network.p2p.messages.ETH66.PooledTransactions._
  import com.chipprbots.ethereum.network.p2p.messages.ETH66.GetNodeData._
  import com.chipprbots.ethereum.network.p2p.messages.ETH66.NodeData._
  import com.chipprbots.ethereum.network.p2p.messages.ETH66.GetReceipts._
  import com.chipprbots.ethereum.network.p2p.messages.ETH66.Receipts._

  def fromBytes(msgCode: Int, payload: Array[Byte]): Either[DecodingError, Message] =
    msgCode match {
      case Codes.StatusCode =>
        Try(payload.toStatus).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.NewBlockHashesCode =>
        Try(payload.toNewBlockHashes).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.SignedTransactionsCode =>
        Try(payload.toSignedTransactions).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.GetBlockHeadersCode =>
        Try(payload.toGetBlockHeaders).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.BlockHeadersCode =>
        Try(payload.toBlockHeaders).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.GetBlockBodiesCode =>
        Try(payload.toGetBlockBodies).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.BlockBodiesCode =>
        Try(payload.toBlockBodies).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.NewBlockCode =>
        Try(payload.toNewBlock).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.NewPooledTransactionHashesCode =>
        Try(
          ETH65NewPooledTransactionHashes.NewPooledTransactionHashesDec(payload).toNewPooledTransactionHashes
        ).toEither.left.map(ex => MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex)))
      case Codes.GetPooledTransactionsCode =>
        Try(payload.toGetPooledTransactions).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.PooledTransactionsCode =>
        Try(payload.toPooledTransactions).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.GetNodeDataCode =>
        Try(payload.toGetNodeData).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.NodeDataCode =>
        Try(payload.toNodeData).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.GetReceiptsCode =>
        Try(payload.toGetReceipts).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.ReceiptsCode =>
        Try(payload.toReceipts).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case _ => Left(UnknownMessageTypeError(msgCode, s"Unknown eth/66 message type: $msgCode"))
    }
}

object ETH67MessageDecoder extends MessageDecoder {
  import com.chipprbots.ethereum.network.p2p.messages.ETH64.Status._
  import com.chipprbots.ethereum.network.p2p.messages.BaseETH6XMessages.NewBlock._
  import com.chipprbots.ethereum.network.p2p.messages.ETH67.NewPooledTransactionHashes._
  import com.chipprbots.ethereum.network.p2p.messages.ETH66.GetBlockHeaders._
  import com.chipprbots.ethereum.network.p2p.messages.ETH66.BlockHeaders._
  import com.chipprbots.ethereum.network.p2p.messages.ETH66.GetBlockBodies._
  import com.chipprbots.ethereum.network.p2p.messages.ETH66.BlockBodies._
  import com.chipprbots.ethereum.network.p2p.messages.ETH66.GetPooledTransactions._
  import com.chipprbots.ethereum.network.p2p.messages.ETH66.PooledTransactions._
  import com.chipprbots.ethereum.network.p2p.messages.ETH66.GetNodeData._
  import com.chipprbots.ethereum.network.p2p.messages.ETH66.NodeData._
  import com.chipprbots.ethereum.network.p2p.messages.ETH66.GetReceipts._
  import com.chipprbots.ethereum.network.p2p.messages.ETH66.Receipts._

  def fromBytes(msgCode: Int, payload: Array[Byte]): Either[DecodingError, Message] =
    msgCode match {
      case Codes.StatusCode =>
        Try(payload.toStatus).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.NewBlockHashesCode =>
        Try(payload.toNewBlockHashes).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.SignedTransactionsCode =>
        Try(payload.toSignedTransactions).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.GetBlockHeadersCode =>
        Try(payload.toGetBlockHeaders).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.BlockHeadersCode =>
        Try(payload.toBlockHeaders).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.GetBlockBodiesCode =>
        Try(payload.toGetBlockBodies).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.BlockBodiesCode =>
        Try(payload.toBlockBodies).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.NewBlockCode =>
        Try(payload.toNewBlock).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.NewPooledTransactionHashesCode =>
        Try(payload.toNewPooledTransactionHashes).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.GetPooledTransactionsCode =>
        Try(payload.toGetPooledTransactions).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.PooledTransactionsCode =>
        Try(payload.toPooledTransactions).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.GetNodeDataCode =>
        Try(payload.toGetNodeData).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.NodeDataCode =>
        Try(payload.toNodeData).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.GetReceiptsCode =>
        Try(payload.toGetReceipts).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.ReceiptsCode =>
        Try(payload.toReceipts).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case _ => Left(UnknownMessageTypeError(msgCode, s"Unknown eth/67 message type: $msgCode"))
    }
}

object ETH68MessageDecoder extends MessageDecoder {
  import com.chipprbots.ethereum.network.p2p.messages.ETH64.Status._
  import com.chipprbots.ethereum.network.p2p.messages.BaseETH6XMessages.NewBlock._
  import com.chipprbots.ethereum.network.p2p.messages.ETH67.NewPooledTransactionHashes._
  import com.chipprbots.ethereum.network.p2p.messages.ETH66.GetBlockHeaders._
  import com.chipprbots.ethereum.network.p2p.messages.ETH66.BlockHeaders._
  import com.chipprbots.ethereum.network.p2p.messages.ETH66.GetBlockBodies._
  import com.chipprbots.ethereum.network.p2p.messages.ETH66.BlockBodies._
  import com.chipprbots.ethereum.network.p2p.messages.ETH66.GetPooledTransactions._
  import com.chipprbots.ethereum.network.p2p.messages.ETH66.PooledTransactions._
  import com.chipprbots.ethereum.network.p2p.messages.ETH66.GetReceipts._
  import com.chipprbots.ethereum.network.p2p.messages.ETH66.Receipts._

  def fromBytes(msgCode: Int, payload: Array[Byte]): Either[DecodingError, Message] =
    msgCode match {
      case Codes.StatusCode =>
        Try(payload.toStatus).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.NewBlockHashesCode =>
        Try(payload.toNewBlockHashes).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.SignedTransactionsCode =>
        Try(payload.toSignedTransactions).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.GetBlockHeadersCode =>
        Try(payload.toGetBlockHeaders).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.BlockHeadersCode =>
        Try(payload.toBlockHeaders).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.GetBlockBodiesCode =>
        Try(payload.toGetBlockBodies).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.BlockBodiesCode =>
        Try(payload.toBlockBodies).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.NewBlockCode =>
        Try(payload.toNewBlock).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.NewPooledTransactionHashesCode =>
        Try(payload.toNewPooledTransactionHashes).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.GetPooledTransactionsCode =>
        Try(payload.toGetPooledTransactions).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.PooledTransactionsCode =>
        Try(payload.toPooledTransactions).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      // GetNodeData and NodeData are explicitly removed in ETH68
      case Codes.GetNodeDataCode => Left(MalformedMessageError("GetNodeData (0x0d) is not supported in eth/68"))
      case Codes.NodeDataCode    => Left(MalformedMessageError("NodeData (0x0e) is not supported in eth/68"))
      case Codes.GetReceiptsCode =>
        Try(payload.toGetReceipts).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.ReceiptsCode =>
        Try(payload.toReceipts).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case _ => Left(UnknownMessageTypeError(msgCode, s"Unknown eth/68 message type: $msgCode"))
    }
}

// scalastyle:off
object EthereumMessageDecoder {
  def ethMessageDecoder(protocolVersion: Capability): MessageDecoder =
    protocolVersion match {
      case Capability.ETH63 => ETH63MessageDecoder
      case Capability.ETH64 => ETH64MessageDecoder
      case Capability.ETH65 => ETH65MessageDecoder
      case Capability.ETH66 => ETH66MessageDecoder
      case Capability.ETH67 => ETH67MessageDecoder
      case Capability.ETH68 => ETH68MessageDecoder
      case Capability.SNAP1 => SNAPMessageDecoder
    }
}

/** SNAP/1 protocol message decoder
  *
  * Decodes SNAP/1 protocol messages (satellite protocol for state sync). SNAP is used alongside ETH protocol, not as a
  * replacement.
  */
object SNAPMessageDecoder extends MessageDecoder {
  import com.chipprbots.ethereum.network.p2p.messages.SNAP._
  import com.chipprbots.ethereum.network.p2p.messages.SNAP.Codes._
  import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetAccountRange.GetAccountRangeDec
  import com.chipprbots.ethereum.network.p2p.messages.SNAP.AccountRange.AccountRangeDec
  import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetStorageRanges.GetStorageRangesDec
  import com.chipprbots.ethereum.network.p2p.messages.SNAP.StorageRanges.StorageRangesDec
  import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetByteCodes.GetByteCodesDec
  import com.chipprbots.ethereum.network.p2p.messages.SNAP.ByteCodes.ByteCodesDec
  import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetTrieNodes.GetTrieNodesDec
  import com.chipprbots.ethereum.network.p2p.messages.SNAP.TrieNodes.TrieNodesDec

  def fromBytes(msgCode: Int, payload: Array[Byte]): Either[DecodingError, Message] =
    msgCode match {
      case GetAccountRangeCode =>
        Try(payload.toGetAccountRange).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case AccountRangeCode =>
        Try(payload.toAccountRange).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case GetStorageRangesCode =>
        Try(payload.toGetStorageRanges).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case StorageRangesCode =>
        Try(payload.toStorageRanges).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case GetByteCodesCode =>
        Try(payload.toGetByteCodes).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case ByteCodesCode =>
        Try(payload.toByteCodes).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case GetTrieNodesCode =>
        Try(payload.toGetTrieNodes).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case TrieNodesCode =>
        Try(payload.toTrieNodes).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case _ => Left(UnknownMessageTypeError(msgCode, s"Unknown snap/1 message type: $msgCode"))
    }
}
