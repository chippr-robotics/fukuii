package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.util.{ByteString, Timeout}

import cats.effect.IO
import cats.syntax.traverse._

import scala.concurrent.duration._

import com.chipprbots.ethereum.jsonrpc.AkkaTaskOps._
import com.chipprbots.ethereum.jsonrpc.DebugService.ListPeersInfoRequest
import com.chipprbots.ethereum.jsonrpc.DebugService.ListPeersInfoResponse
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.PeerInfo
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.PeerInfoResponse
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerActor
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.PeerManagerActor
import com.chipprbots.ethereum.network.PeerManagerActor.Peers
import com.chipprbots.ethereum.db.storage.TransactionMappingStorage
import com.chipprbots.ethereum.db.storage.TransactionMappingStorage.TransactionLocation
import com.chipprbots.ethereum.domain.{Address, Block, BlockchainReader, SignedTransaction,
  SignedTransactionWithSender}
import com.chipprbots.ethereum.ledger.StxLedger
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.vm.tracing.StructLogTracer

object DebugService {
  case class ListPeersInfoRequest()
  case class ListPeersInfoResponse(peers: List[PeerInfo])

  /** debug_traceTransaction params: [txHash, optional TraceConfig]. */
  case class TraceTransactionRequest(txHash: org.apache.pekko.util.ByteString)

  /** Geth-compatible struct-log response. */
  case class TraceTransactionResponse(
      gas: BigInt,
      failed: Boolean,
      returnValue: org.apache.pekko.util.ByteString,
      structLogs: List[com.chipprbots.ethereum.vm.tracing.StructLogEntry]
  )
}

class DebugService(
    peerManager: ActorRef,
    networkPeerManager: ActorRef,
    blockchainReader: BlockchainReader = null,
    stxLedger: StxLedger = null,
    transactionMappingStorage: TransactionMappingStorage = null
)(implicit blockchainConfig: BlockchainConfig = null) {

  /** Look up a historical tx by hash, re-execute it (from its parent block's state) with a
    * StructLogTracer attached, and return the geth-compatible response.
    */
  def traceTransaction(req: DebugService.TraceTransactionRequest): ServiceResponse[DebugService.TraceTransactionResponse] = IO {
    if (blockchainReader == null || stxLedger == null || transactionMappingStorage == null ||
        blockchainConfig == null)
      Left(JsonRpcError(-32000, "tracing not wired", None))
    else doTraceTransaction(req.txHash)
  }

  /** Trace all transactions in a block. Response is a JSON-encodable Seq matched to each tx. */
  def traceBlockByHash(blockHash: ByteString): ServiceResponse[List[DebugService.TraceTransactionResponse]] = IO {
    if (blockchainReader == null || stxLedger == null || blockchainConfig == null)
      Left(JsonRpcError(-32000, "tracing not wired", None))
    else doTraceBlock(blockHash)
  }

  def traceBlockByNumber(blockNumber: BigInt): ServiceResponse[List[DebugService.TraceTransactionResponse]] = IO {
    if (blockchainReader == null || stxLedger == null || blockchainConfig == null)
      Left(JsonRpcError(-32000, "tracing not wired", None))
    else {
      blockchainReader.getBlockHeaderByNumber(blockNumber) match {
        case Some(h) => doTraceBlock(h.hash)
        case None => Left(JsonRpcError(-32000, s"block $blockNumber not found", None))
      }
    }
  }

  private def doTraceTransaction(txHash: ByteString): Either[JsonRpcError, DebugService.TraceTransactionResponse] = {
    transactionMappingStorage.get(txHash) match {
      case None =>
        Left(JsonRpcError(-32000,
          s"transaction not found: 0x${txHash.toArray.map("%02x".format(_)).mkString}", None))
      case Some(TransactionLocation(blockHash, txIndex)) =>
        blockchainReader.getBlockByHash(blockHash) match {
          case None => Left(JsonRpcError(-32000, "block not available", None))
          case Some(Block(header, body)) =>
            val stx = body.transactionList(txIndex)
            val resp = traceSingleTx(stx, header)
            Right(resp)
        }
    }
  }

  private def doTraceBlock(blockHash: ByteString): Either[JsonRpcError, List[DebugService.TraceTransactionResponse]] = {
    blockchainReader.getBlockByHash(blockHash) match {
      case None => Left(JsonRpcError(-32000, "block not found", None))
      case Some(Block(header, body)) =>
        Right(body.transactionList.map(stx => traceSingleTx(stx, header)).toList)
    }
  }

  /** Trace a single tx against the state at `header.parent`. */
  private def traceSingleTx(
      stx: SignedTransaction,
      header: com.chipprbots.ethereum.domain.BlockHeader
  ): DebugService.TraceTransactionResponse = {
    val sender = SignedTransaction.getSender(stx).getOrElse(Address(0))
    val stxWithSender = SignedTransactionWithSender(stx, sender)
    val traceAtHeader =
      blockchainReader.getBlockHeaderByHash(header.parentHash).getOrElse(header)
    val tracer = new StructLogTracer()
    val result = stxLedger.simulateTransactionWithTracer(stxWithSender, traceAtHeader, None, Some(tracer))
    val snap = tracer.result
    DebugService.TraceTransactionResponse(
      gas = snap.gas,
      failed = snap.failed || result.vmError.isDefined,
      returnValue = snap.returnValue,
      structLogs = snap.structLogs
    )
  }

  def listPeersInfo(getPeersInfoRequest: ListPeersInfoRequest): ServiceResponse[ListPeersInfoResponse] =
    for {
      ids <- getPeerIds
      peers <- ids.traverse(getPeerInfo)
    } yield Right(ListPeersInfoResponse(peers.flatten))

  private def getPeerIds: IO[List[PeerId]] = {
    implicit val timeout: Timeout = Timeout(20.seconds)

    peerManager
      .askFor[Peers](PeerManagerActor.GetPeers)
      .handleError(_ => Peers(Map.empty[Peer, PeerActor.Status]))
      .map(_.peers.keySet.map(_.id).toList)
  }

  private def getPeerInfo(peer: PeerId): IO[Option[PeerInfo]] = {
    implicit val timeout: Timeout = Timeout(20.seconds)

    networkPeerManager
      .askFor[PeerInfoResponse](NetworkPeerManagerActor.PeerInfoRequest(peer))
      .map(resp => resp.peerInfo)
  }
}
