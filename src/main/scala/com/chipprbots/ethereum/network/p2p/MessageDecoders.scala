package com.chipprbots.ethereum.network.p2p

import scala.util.Try

import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.network.p2p.messages.Codes
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

/** ETH/68 decoder. Imports exclusively from ETHPackets — zero dependency on ETH62-67.
  *
  * Equivalent to: go-ethereum var eth68 = map[uint64]msgHandler{...} (handler.go) Erigon ProtoIds[Protocol_ETH68]
  * (libsentry/protocol.go)
  */
object ETH68MessageDecoder extends MessageDecoder {
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.Status68.Status68._ // toStatus68
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.NewBlockHashes.NewBlockHashes._
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.SignedTransactions._
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.NewBlock._
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.NewPooledTransactionHashes._
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.GetBlockHeaders._
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.BlockHeaders._
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.GetBlockBodies._
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.BlockBodies._
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.GetPooledTransactions._
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.PooledTransactions._
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.GetReceipts._
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.Receipts68._

  // Explicit positive allowlist — equivalent of go-ethereum's handler map keys
  // and Erigon's ProtoIds[ETH68] set. 13 messages.
  val supportedMessages: Set[Int] = Set(
    Codes.StatusCode,
    Codes.NewBlockHashesCode,
    Codes.SignedTransactionsCode,
    Codes.GetBlockHeadersCode,
    Codes.BlockHeadersCode,
    Codes.GetBlockBodiesCode,
    Codes.BlockBodiesCode,
    Codes.NewBlockCode,
    Codes.NewPooledTransactionHashesCode,
    Codes.GetPooledTransactionsCode,
    Codes.PooledTransactionsCode,
    Codes.GetReceiptsCode,
    Codes.ReceiptsCode
  )

  def fromBytes(msgCode: Int, payload: Array[Byte]): Either[DecodingError, Message] =
    msgCode match {
      case Codes.StatusCode =>
        Try(payload.toStatus68).toEither.left.map(ex =>
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
      case Codes.GetNodeDataCode => Left(MalformedMessageError("GetNodeData (0x0d) not supported in eth/68 (EIP-4938)"))
      case Codes.NodeDataCode    => Left(MalformedMessageError("NodeData (0x0e) not supported in eth/68 (EIP-4938)"))
      case Codes.GetReceiptsCode =>
        Try(payload.toGetReceipts).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.ReceiptsCode =>
        Try(payload.toReceipts68).toEither.left.map(ex => // bloom-inclusive
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case _ => Left(UnknownMessageTypeError(msgCode, s"Unknown eth/68 message type: $msgCode"))
    }
}

/** ETH/69 decoder. ETH69 adds Status69 (no TD), BlockRangeUpdate, and uses bloom-absent Receipts69.
  *
  * Imports exclusively from ETHPackets — zero dependency on ETH62-67. Key fix: ReceiptsCode uses ETHPackets.Receipts69
  * (bloom-absent) not ETHPackets.Receipts68 (bloom-inclusive).
  */
object ETH69MessageDecoder extends MessageDecoder {
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.Status69.Status69._ // toStatus69
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.NewBlockHashes.NewBlockHashes._
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.SignedTransactions._
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.NewBlock._
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.NewPooledTransactionHashes._
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.GetBlockHeaders._
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.BlockHeaders._
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.GetBlockBodies._
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.BlockBodies._
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.GetPooledTransactions._
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.PooledTransactions._
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.GetReceipts69._ // distinct ETH69 type → bloom-absent response
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.Receipts69._ // EIP-7642: bloom-absent
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.BlockRangeUpdate._

  // ETH69 adds BlockRangeUpdate (0x11) over ETH68's 13 messages = 14 total.
  val supportedMessages: Set[Int] =
    ETH68MessageDecoder.supportedMessages + Codes.BlockRangeUpdateCode

  def fromBytes(msgCode: Int, payload: Array[Byte]): Either[DecodingError, Message] =
    msgCode match {
      case Codes.StatusCode =>
        Try(payload.toStatus69).toEither.left.map(ex =>
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
      case Codes.GetNodeDataCode => Left(MalformedMessageError("GetNodeData (0x0d) not supported in eth/69 (EIP-4938)"))
      case Codes.NodeDataCode    => Left(MalformedMessageError("NodeData (0x0e) not supported in eth/69 (EIP-4938)"))
      case Codes.GetReceiptsCode =>
        Try(payload.toGetReceipts69).toEither.left.map(ex => // ETH69 type → bloom-absent serving
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.ReceiptsCode =>
        Try(payload.toReceipts69).toEither.left.map(ex => // bloom-absent per EIP-7642
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.BlockRangeUpdateCode =>
        Try(payload.toBlockRangeUpdate).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case _ => Left(UnknownMessageTypeError(msgCode, s"Unknown eth/69 message type: $msgCode"))
    }
}

// scalastyle:off
object EthereumMessageDecoder {
  def ethMessageDecoder(protocolVersion: Capability): MessageDecoder =
    protocolVersion match {
      case Capability.ETH68 => ETH68MessageDecoder
      case Capability.ETH69 => ETH69MessageDecoder
      case Capability.SNAP1 => SNAPMessageDecoder
      case unsupported      => throw new IllegalArgumentException(s"Unsupported protocol version: $unsupported")
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
