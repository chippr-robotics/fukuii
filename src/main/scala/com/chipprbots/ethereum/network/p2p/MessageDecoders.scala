package com.chipprbots.ethereum.network.p2p

import scala.util.Try

import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.network.p2p.messages.Codes
import com.chipprbots.ethereum.network.p2p.messages.WireProtocol.*
import com.chipprbots.ethereum.network.p2p.messages.WireProtocol.Disconnect.*
import com.chipprbots.ethereum.network.p2p.messages.WireProtocol.Hello.*
import com.chipprbots.ethereum.network.p2p.messages.WireProtocol.Ping.*
import com.chipprbots.ethereum.network.p2p.messages.WireProtocol.Pong.*

import MessageDecoder.*

object NetworkMessageDecoder extends MessageDecoder:

  override def fromBytes(msgCode: Int, payload: Array[Byte]): Either[DecodingError, Message] =
    msgCode match
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

/** ETH/68 decoder. Imports exclusively from ETHPackets — zero dependency on ETH62-67.
  *
  * Equivalent to: go-ethereum var eth68 = map[uint64]msgHandler{...} (handler.go) Erigon ProtoIds[Protocol_ETH68]
  * (libsentry/protocol.go)
  */
object ETH68MessageDecoder extends MessageDecoder:
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.Status68.Status68.* // toStatus68
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.NewBlockHashes.NewBlockHashes.*
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.SignedTransactions.*
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.NewBlock.*
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.NewPooledTransactionHashes.*
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.GetBlockHeaders.*
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.BlockHeaders.*
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.GetBlockBodies.*
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.BlockBodies.*
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.GetPooledTransactions.*
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.PooledTransactions.*
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.GetReceipts.*
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.Receipts68.*

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
    msgCode match
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

/** ETH/69 decoder. ETH69 adds Status69 (no TD), BlockRangeUpdate, and uses bloom-absent Receipts69.
  *
  * Imports exclusively from ETHPackets — zero dependency on ETH62-67. Key fix: ReceiptsCode uses ETHPackets.Receipts69
  * (bloom-absent) not ETHPackets.Receipts68 (bloom-inclusive).
  */
object ETH69MessageDecoder extends MessageDecoder:
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.Status69.Status69.* // toStatus69
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.NewBlockHashes.NewBlockHashes.*
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.SignedTransactions.*
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.NewBlock.*
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.NewPooledTransactionHashes.*
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.GetBlockHeaders.*
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.BlockHeaders.*
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.GetBlockBodies.*
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.BlockBodies.*
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.GetPooledTransactions.*
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.PooledTransactions.*
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.GetReceipts69.* // distinct ETH69 type → bloom-absent response
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.Receipts69.* // EIP-7642: bloom-absent
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.BlockRangeUpdate.*

  // ETH69 adds BlockRangeUpdate (0x11) = 14 messages total. Explicit set (not delegating to
  // ETH68MessageDecoder) so this decoder stays self-contained if ETH68 is ever retired.
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
    Codes.ReceiptsCode,
    Codes.BlockRangeUpdateCode
  )

  def fromBytes(msgCode: Int, payload: Array[Byte]): Either[DecodingError, Message] =
    msgCode match
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

/** ETH/70 decoder. ETH70 adds partial receipt delivery via firstBlockReceiptIndex (GetReceipts70) and
  * lastBlockIncomplete (Receipts70). All other message types are identical to ETH69.
  *
  * Reference: EIP-7706 / go-ethereum eth/protocols/eth/protocol.go
  */
object ETH70MessageDecoder extends MessageDecoder:
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.Status70.Status70.* // ETH70-owned Status type
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.NewBlockHashes.NewBlockHashes.*
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.SignedTransactions.*
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.NewBlock.*
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.NewPooledTransactionHashes.*
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.GetBlockHeaders.*
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.BlockHeaders.*
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.GetBlockBodies.*
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.BlockBodies.*
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.GetPooledTransactions.*
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.PooledTransactions.*
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.GetReceipts70.* // ETH70: partial receipt resume
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.Receipts70.* // ETH70: lastBlockIncomplete flag
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.BlockRangeUpdate.* // introduced in ETH69, still present

  // Explicit set — no cross-decoder delegation. Self-contained if ETH68 or ETH69 decoders are retired.
  // ETH70 message set = ETH68 base (13) + BlockRangeUpdate (ETH69 addition) = 14 messages.
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
    Codes.ReceiptsCode,
    Codes.BlockRangeUpdateCode
  )

  def fromBytes(msgCode: Int, payload: Array[Byte]): Either[DecodingError, Message] =
    msgCode match
      case Codes.StatusCode =>
        Try(payload.toStatus70).toEither.left.map(ex =>
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
      case Codes.GetNodeDataCode => Left(MalformedMessageError("GetNodeData (0x0d) not supported in eth/70 (EIP-4938)"))
      case Codes.NodeDataCode    => Left(MalformedMessageError("NodeData (0x0e) not supported in eth/70 (EIP-4938)"))
      case Codes.GetReceiptsCode =>
        Try(payload.toGetReceipts70).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.ReceiptsCode =>
        Try(payload.toReceipts70).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.BlockRangeUpdateCode =>
        Try(payload.toBlockRangeUpdate).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case _ => Left(UnknownMessageTypeError(msgCode, s"Unknown eth/70 message type: $msgCode"))

/** ETH/71 decoder. ETH71 adds block access list exchange (GetBlockAccessLists/BlockAccessLists, EIP-8159) on top of
  * ETH70's message set. Status and receipts are unchanged from ETH70 (reuses Status70/GetReceipts70/Receipts70 — see
  * ETHPackets.scala's Status70 doc comment for why there is no separate Status71 type).
  *
  * Reference: EIP-8159 / go-ethereum eth/protocols/eth/protocol.go (protocolLengths(ETH71) = 20), handler.go's `eth71`
  * map (routes GetReceiptsMsg/ReceiptsMsg to the SAME handleGetReceipts70/handleReceipts70 as ETH70).
  */
object ETH71MessageDecoder extends MessageDecoder:
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.Status70.Status70.* // reused unmodified — see ETHPackets.scala
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.NewBlockHashes.NewBlockHashes.*
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.SignedTransactions.*
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.NewBlock.*
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.NewPooledTransactionHashes.*
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.GetBlockHeaders.*
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.BlockHeaders.*
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.GetBlockBodies.*
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.BlockBodies.*
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.GetPooledTransactions.*
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.PooledTransactions.*
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.GetReceipts70.*
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.Receipts70.*
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.BlockRangeUpdate.*
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.GetBlockAccessLists.*
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.BlockAccessLists.*

  // ETH71 message set = ETH70 (14) + GetBlockAccessLists + BlockAccessLists = 16 messages.
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
    Codes.ReceiptsCode,
    Codes.BlockRangeUpdateCode,
    Codes.GetBlockAccessListsCode,
    Codes.BlockAccessListsCode
  )

  def fromBytes(msgCode: Int, payload: Array[Byte]): Either[DecodingError, Message] =
    msgCode match
      case Codes.StatusCode =>
        Try(payload.toStatus70).toEither.left.map(ex =>
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
      case Codes.GetNodeDataCode => Left(MalformedMessageError("GetNodeData (0x0d) not supported in eth/71 (EIP-4938)"))
      case Codes.NodeDataCode    => Left(MalformedMessageError("NodeData (0x0e) not supported in eth/71 (EIP-4938)"))
      case Codes.GetReceiptsCode =>
        Try(payload.toGetReceipts70).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.ReceiptsCode =>
        Try(payload.toReceipts70).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.BlockRangeUpdateCode =>
        Try(payload.toBlockRangeUpdate).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.GetBlockAccessListsCode =>
        Try(payload.toGetBlockAccessLists).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.BlockAccessListsCode =>
        Try(payload.toBlockAccessLists).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case _ => Left(UnknownMessageTypeError(msgCode, s"Unknown eth/71 message type: $msgCode"))

/** ETH/72 decoder. ETH72 adds cell exchange (GetCells/Cells, EIP-8070) on top of ETH71's message set, AND changes
  * NewPooledTransactionHashes to the 4-field form (adds a PeerDAS custody Mask) — same wire code, different shape,
  * selected purely by negotiated version. Status and receipts are unchanged (reuses Status70/GetReceipts70/Receipts70,
  * same as ETH71).
  *
  * Reference: EIP-8070 / go-ethereum eth/protocols/eth/protocol.go (protocolLengths(ETH72) = 22), handler.go's `eth72`
  * map (routes NewPooledTransactionHashesMsg to handleNewPooledTransactionHashes72, GetReceiptsMsg/ReceiptsMsg still to
  * handleGetReceipts70/handleReceipts70).
  */
object ETH72MessageDecoder extends MessageDecoder:
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.Status70.Status70.* // reused unmodified — see ETHPackets.scala
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.NewBlockHashes.NewBlockHashes.*
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.SignedTransactions.*
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.NewBlock.*
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.NewPooledTransactionHashes72.*
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.GetBlockHeaders.*
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.BlockHeaders.*
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.GetBlockBodies.*
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.BlockBodies.*
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.GetPooledTransactions.*
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.PooledTransactions.*
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.GetReceipts70.*
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.Receipts70.*
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.BlockRangeUpdate.*
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.GetBlockAccessLists.*
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.BlockAccessLists.*
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.GetCells.*
  import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.Cells.*

  // ETH72 message set = ETH71 (16) + GetCells + Cells = 18 messages.
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
    Codes.ReceiptsCode,
    Codes.BlockRangeUpdateCode,
    Codes.GetBlockAccessListsCode,
    Codes.BlockAccessListsCode,
    Codes.GetCellsCode,
    Codes.CellsCode
  )

  def fromBytes(msgCode: Int, payload: Array[Byte]): Either[DecodingError, Message] =
    msgCode match
      case Codes.StatusCode =>
        Try(payload.toStatus70).toEither.left.map(ex =>
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
      // ETH72-only: strict 4-field decode, no legacy 3-field fallback — see
      // ETHPackets.NewPooledTransactionHashes72's doc comment.
      case Codes.NewPooledTransactionHashesCode =>
        Try(payload.toNewPooledTransactionHashes72).toEither.left.map(ex =>
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
      case Codes.GetNodeDataCode => Left(MalformedMessageError("GetNodeData (0x0d) not supported in eth/72 (EIP-4938)"))
      case Codes.NodeDataCode    => Left(MalformedMessageError("NodeData (0x0e) not supported in eth/72 (EIP-4938)"))
      case Codes.GetReceiptsCode =>
        Try(payload.toGetReceipts70).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.ReceiptsCode =>
        Try(payload.toReceipts70).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.BlockRangeUpdateCode =>
        Try(payload.toBlockRangeUpdate).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.GetBlockAccessListsCode =>
        Try(payload.toGetBlockAccessLists).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.BlockAccessListsCode =>
        Try(payload.toBlockAccessLists).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.GetCellsCode =>
        Try(payload.toGetCells).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case Codes.CellsCode =>
        Try(payload.toCells).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case _ => Left(UnknownMessageTypeError(msgCode, s"Unknown eth/72 message type: $msgCode"))

// scalastyle:off
object EthereumMessageDecoder:
  def ethMessageDecoder(protocolVersion: Capability): MessageDecoder =
    protocolVersion match
      case Capability.ETH68 => ETH68MessageDecoder
      case Capability.ETH69 => ETH69MessageDecoder
      case Capability.ETH70 => ETH70MessageDecoder
      case Capability.ETH71 => ETH71MessageDecoder
      case Capability.ETH72 => ETH72MessageDecoder
      case Capability.SNAP1 => SNAPMessageDecoder
      case Capability.SNAP2 => SNAP2MessageDecoder
      case unsupported      => throw new IllegalArgumentException(s"Unsupported protocol version: $unsupported")

/** SNAP/1 protocol message decoder
  *
  * Decodes SNAP/1 protocol messages (satellite protocol for state sync). SNAP is used alongside ETH protocol, not as a
  * replacement.
  */
object SNAPMessageDecoder extends MessageDecoder:
  import com.chipprbots.ethereum.network.p2p.messages.SNAP.*
  import com.chipprbots.ethereum.network.p2p.messages.SNAP.Codes.*
  import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetAccountRange.*
  import com.chipprbots.ethereum.network.p2p.messages.SNAP.AccountRange.*
  import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetStorageRanges.*
  import com.chipprbots.ethereum.network.p2p.messages.SNAP.StorageRanges.*
  import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetByteCodes.*
  import com.chipprbots.ethereum.network.p2p.messages.SNAP.ByteCodes.*
  import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetTrieNodes.*
  import com.chipprbots.ethereum.network.p2p.messages.SNAP.TrieNodes.*

  def fromBytes(msgCode: Int, payload: Array[Byte]): Either[DecodingError, Message] =
    msgCode match
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

/** SNAP/2 protocol message decoder (EIP-8189).
  *
  * Keeps GetAccountRange/AccountRange/GetStorageRanges/StorageRanges/GetByteCodes/ByteCodes unchanged from snap/1 (same
  * wire shape — go-ethereum's `snap2` handler map in eth/protocols/snap/handler.go routes them to the identical
  * handlers as `snap1`) and adds GetAccessLists/AccessLists (0x08/0x09).
  *
  * snap/2 REMOVES GetTrieNodes/TrieNodes (0x06/0x07) — go-ethereum's `snap2` map has no entry for either code, so an
  * arriving one falls to `errInvalidMsgCode` and tears the connection down (HandleMessage's `default` case).
  * GetTrieNodesCode/TrieNodesCode therefore get explicit `MalformedMessageError` cases below — same "known code,
  * explicitly retired in this version" idiom already used for GetNodeData/NodeData in the ETH68+ decoders (that
  * disconnects via RLPxConnectionHandler's generic `Left(ex) =>` branch) rather than falling through to the
  * lenient-skip `UnknownMessageTypeError` catch-all, which is reserved for codes this codebase has never heard of.
  */
object SNAP2MessageDecoder extends MessageDecoder:
  import com.chipprbots.ethereum.network.p2p.messages.SNAP.*
  import com.chipprbots.ethereum.network.p2p.messages.SNAP.Codes.*
  import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetAccountRange.*
  import com.chipprbots.ethereum.network.p2p.messages.SNAP.AccountRange.*
  import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetStorageRanges.*
  import com.chipprbots.ethereum.network.p2p.messages.SNAP.StorageRanges.*
  import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetByteCodes.*
  import com.chipprbots.ethereum.network.p2p.messages.SNAP.ByteCodes.*
  import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetAccessLists.*
  import com.chipprbots.ethereum.network.p2p.messages.SNAP.AccessLists.*

  def fromBytes(msgCode: Int, payload: Array[Byte]): Either[DecodingError, Message] =
    msgCode match
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
        Left(MalformedMessageError("GetTrieNodes (0x06) not supported in snap/2 (EIP-8189 removes trie-node healing)"))
      case TrieNodesCode =>
        Left(MalformedMessageError("TrieNodes (0x07) not supported in snap/2 (EIP-8189 removes trie-node healing)"))
      case GetAccessListsCode =>
        Try(payload.toGetAccessLists).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case AccessListsCode =>
        Try(payload.toAccessLists).toEither.left.map(ex =>
          MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        )
      case _ => Left(UnknownMessageTypeError(msgCode, s"Unknown snap/2 message type: $msgCode"))
