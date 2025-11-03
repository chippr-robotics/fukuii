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
      case Disconnect.code => Try(payload.toDisconnect).toEither
      case Ping.code       => Try(payload.toPing).toEither
      case Pong.code       => Try(payload.toPong).toEither
      case Hello.code      => Try(payload.toHello).toEither
      case _               => Left(new RuntimeException(s"Unknown network message type: $msgCode"))
    }

}

object ETC64MessageDecoder extends MessageDecoder {
  import com.chipprbots.ethereum.network.p2p.messages.ETC64.Status._
  import com.chipprbots.ethereum.network.p2p.messages.ETC64.NewBlock._

  def fromBytes(msgCode: Int, payload: Array[Byte]): Either[DecodingError, Message] =
    msgCode match {
      case Codes.StatusCode                => Try(payload.toStatus).toEither
      case Codes.NewBlockCode              => Try(payload.toNewBlock).toEither
      case Codes.GetNodeDataCode           => Try(payload.toGetNodeData).toEither
      case Codes.NodeDataCode              => Try(payload.toNodeData).toEither
      case Codes.GetReceiptsCode           => Try(payload.toGetReceipts).toEither
      case Codes.ReceiptsCode              => Try(payload.toReceipts).toEither
      case Codes.NewBlockHashesCode        => Try(payload.toNewBlockHashes).toEither
      case Codes.GetBlockHeadersCode       => Try(payload.toGetBlockHeaders).toEither
      case Codes.BlockHeadersCode          => Try(payload.toBlockHeaders).toEither
      case Codes.GetBlockBodiesCode        => Try(payload.toGetBlockBodies).toEither
      case Codes.BlockBodiesCode           => Try(payload.toBlockBodies).toEither
      case Codes.BlockHashesFromNumberCode => Try(payload.toBlockHashesFromNumber).toEither
      case Codes.SignedTransactionsCode    => Try(payload.toSignedTransactions).toEither
      case _                               => Left(new RuntimeException(s"Unknown etc/64 message type: $msgCode"))
    }
}

object ETH64MessageDecoder extends MessageDecoder {
  import com.chipprbots.ethereum.network.p2p.messages.ETH64.Status._
  import com.chipprbots.ethereum.network.p2p.messages.BaseETH6XMessages.NewBlock._

  def fromBytes(msgCode: Int, payload: Array[Byte]): Either[DecodingError, Message] =
    msgCode match {
      case Codes.GetNodeDataCode           => Try(payload.toGetNodeData).toEither
      case Codes.NodeDataCode              => Try(payload.toNodeData).toEither
      case Codes.GetReceiptsCode           => Try(payload.toGetReceipts).toEither
      case Codes.ReceiptsCode              => Try(payload.toReceipts).toEither
      case Codes.NewBlockHashesCode        => Try(payload.toNewBlockHashes).toEither
      case Codes.GetBlockHeadersCode       => Try(payload.toGetBlockHeaders).toEither
      case Codes.BlockHeadersCode          => Try(payload.toBlockHeaders).toEither
      case Codes.GetBlockBodiesCode        => Try(payload.toGetBlockBodies).toEither
      case Codes.BlockBodiesCode           => Try(payload.toBlockBodies).toEither
      case Codes.BlockHashesFromNumberCode => Try(payload.toBlockHashesFromNumber).toEither
      case Codes.StatusCode                => Try(payload.toStatus).toEither
      case Codes.NewBlockCode              => Try(payload.toNewBlock).toEither
      case Codes.SignedTransactionsCode    => Try(payload.toSignedTransactions).toEither
      case _                               => Left(new RuntimeException(s"Unknown eth/64 message type: $msgCode"))
    }
}

object ETH63MessageDecoder extends MessageDecoder {
  import com.chipprbots.ethereum.network.p2p.messages.BaseETH6XMessages.Status._
  import com.chipprbots.ethereum.network.p2p.messages.BaseETH6XMessages.NewBlock._

  def fromBytes(msgCode: Int, payload: Array[Byte]): Either[DecodingError, Message] =
    msgCode match {
      case Codes.GetNodeDataCode           => Try(payload.toGetNodeData).toEither
      case Codes.NodeDataCode              => Try(payload.toNodeData).toEither
      case Codes.GetReceiptsCode           => Try(payload.toGetReceipts).toEither
      case Codes.ReceiptsCode              => Try(payload.toReceipts).toEither
      case Codes.NewBlockHashesCode        => Try(payload.toNewBlockHashes).toEither
      case Codes.GetBlockHeadersCode       => Try(payload.toGetBlockHeaders).toEither
      case Codes.BlockHeadersCode          => Try(payload.toBlockHeaders).toEither
      case Codes.GetBlockBodiesCode        => Try(payload.toGetBlockBodies).toEither
      case Codes.BlockBodiesCode           => Try(payload.toBlockBodies).toEither
      case Codes.BlockHashesFromNumberCode => Try(payload.toBlockHashesFromNumber).toEither
      case Codes.StatusCode                => Try(payload.toStatus).toEither
      case Codes.NewBlockCode              => Try(payload.toNewBlock).toEither
      case Codes.SignedTransactionsCode    => Try(payload.toSignedTransactions).toEither
      case _                               => Left(new RuntimeException(s"Unknown eth/63 message type: $msgCode"))
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
      case Codes.StatusCode                     => Try(payload.toStatus).toEither
      case Codes.NewBlockHashesCode             => Try(payload.toNewBlockHashes).toEither
      case Codes.SignedTransactionsCode         => Try(payload.toSignedTransactions).toEither
      case Codes.GetBlockHeadersCode            => Try(payload.toGetBlockHeaders).toEither
      case Codes.BlockHeadersCode               => Try(payload.toBlockHeaders).toEither
      case Codes.GetBlockBodiesCode             => Try(payload.toGetBlockBodies).toEither
      case Codes.BlockBodiesCode                => Try(payload.toBlockBodies).toEither
      case Codes.NewBlockCode                   => Try(payload.toNewBlock).toEither
      case Codes.NewPooledTransactionHashesCode => Try(payload.toNewPooledTransactionHashes).toEither
      case Codes.GetPooledTransactionsCode      => Try(payload.toGetPooledTransactions).toEither
      case Codes.PooledTransactionsCode         => Try(payload.toPooledTransactions).toEither
      case Codes.GetNodeDataCode                => Try(payload.toGetNodeData).toEither
      case Codes.NodeDataCode                   => Try(payload.toNodeData).toEither
      case Codes.GetReceiptsCode                => Try(payload.toGetReceipts).toEither
      case Codes.ReceiptsCode                   => Try(payload.toReceipts).toEither
      case _                                    => Left(new RuntimeException(s"Unknown eth/65 message type: $msgCode"))
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
      case Codes.StatusCode             => Try(payload.toStatus).toEither
      case Codes.NewBlockHashesCode     => Try(payload.toNewBlockHashes).toEither
      case Codes.SignedTransactionsCode => Try(payload.toSignedTransactions).toEither
      case Codes.GetBlockHeadersCode    => Try(payload.toGetBlockHeaders).toEither
      case Codes.BlockHeadersCode       => Try(payload.toBlockHeaders).toEither
      case Codes.GetBlockBodiesCode     => Try(payload.toGetBlockBodies).toEither
      case Codes.BlockBodiesCode        => Try(payload.toBlockBodies).toEither
      case Codes.NewBlockCode           => Try(payload.toNewBlock).toEither
      case Codes.NewPooledTransactionHashesCode =>
        Try(
          ETH65NewPooledTransactionHashes.NewPooledTransactionHashesDec(payload).toNewPooledTransactionHashes
        ).toEither
      case Codes.GetPooledTransactionsCode => Try(payload.toGetPooledTransactions).toEither
      case Codes.PooledTransactionsCode    => Try(payload.toPooledTransactions).toEither
      case Codes.GetNodeDataCode           => Try(payload.toGetNodeData).toEither
      case Codes.NodeDataCode              => Try(payload.toNodeData).toEither
      case Codes.GetReceiptsCode           => Try(payload.toGetReceipts).toEither
      case Codes.ReceiptsCode              => Try(payload.toReceipts).toEither
      case _                               => Left(new RuntimeException(s"Unknown eth/66 message type: $msgCode"))
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
      case Codes.StatusCode                     => Try(payload.toStatus).toEither
      case Codes.NewBlockHashesCode             => Try(payload.toNewBlockHashes).toEither
      case Codes.SignedTransactionsCode         => Try(payload.toSignedTransactions).toEither
      case Codes.GetBlockHeadersCode            => Try(payload.toGetBlockHeaders).toEither
      case Codes.BlockHeadersCode               => Try(payload.toBlockHeaders).toEither
      case Codes.GetBlockBodiesCode             => Try(payload.toGetBlockBodies).toEither
      case Codes.BlockBodiesCode                => Try(payload.toBlockBodies).toEither
      case Codes.NewBlockCode                   => Try(payload.toNewBlock).toEither
      case Codes.NewPooledTransactionHashesCode => Try(payload.toNewPooledTransactionHashes).toEither
      case Codes.GetPooledTransactionsCode      => Try(payload.toGetPooledTransactions).toEither
      case Codes.PooledTransactionsCode         => Try(payload.toPooledTransactions).toEither
      case Codes.GetNodeDataCode                => Try(payload.toGetNodeData).toEither
      case Codes.NodeDataCode                   => Try(payload.toNodeData).toEither
      case Codes.GetReceiptsCode                => Try(payload.toGetReceipts).toEither
      case Codes.ReceiptsCode                   => Try(payload.toReceipts).toEither
      case _                                    => Left(new RuntimeException(s"Unknown eth/67 message type: $msgCode"))
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
      case Codes.StatusCode                     => Try(payload.toStatus).toEither
      case Codes.NewBlockHashesCode             => Try(payload.toNewBlockHashes).toEither
      case Codes.SignedTransactionsCode         => Try(payload.toSignedTransactions).toEither
      case Codes.GetBlockHeadersCode            => Try(payload.toGetBlockHeaders).toEither
      case Codes.BlockHeadersCode               => Try(payload.toBlockHeaders).toEither
      case Codes.GetBlockBodiesCode             => Try(payload.toGetBlockBodies).toEither
      case Codes.BlockBodiesCode                => Try(payload.toBlockBodies).toEither
      case Codes.NewBlockCode                   => Try(payload.toNewBlock).toEither
      case Codes.NewPooledTransactionHashesCode => Try(payload.toNewPooledTransactionHashes).toEither
      case Codes.GetPooledTransactionsCode      => Try(payload.toGetPooledTransactions).toEither
      case Codes.PooledTransactionsCode         => Try(payload.toPooledTransactions).toEither
      // GetNodeData and NodeData are explicitly removed in ETH68
      case Codes.GetNodeDataCode => Left(new RuntimeException("GetNodeData (0x0d) is not supported in eth/68"))
      case Codes.NodeDataCode    => Left(new RuntimeException("NodeData (0x0e) is not supported in eth/68"))
      case Codes.GetReceiptsCode => Try(payload.toGetReceipts).toEither
      case Codes.ReceiptsCode    => Try(payload.toReceipts).toEither
      case _                     => Left(new RuntimeException(s"Unknown eth/68 message type: $msgCode"))
    }
}

// scalastyle:off
object EthereumMessageDecoder {
  def ethMessageDecoder(protocolVersion: Capability): MessageDecoder =
    protocolVersion match {
      case Capability.ETC64 => ETC64MessageDecoder.orElse(NetworkMessageDecoder)
      case Capability.ETH63 => ETH63MessageDecoder.orElse(NetworkMessageDecoder)
      case Capability.ETH64 => ETH64MessageDecoder.orElse(NetworkMessageDecoder)
      case Capability.ETH65 => ETH65MessageDecoder.orElse(NetworkMessageDecoder)
      case Capability.ETH66 => ETH66MessageDecoder.orElse(NetworkMessageDecoder)
      case Capability.ETH67 => ETH67MessageDecoder.orElse(NetworkMessageDecoder)
      case Capability.ETH68 => ETH68MessageDecoder.orElse(NetworkMessageDecoder)
    }
}
