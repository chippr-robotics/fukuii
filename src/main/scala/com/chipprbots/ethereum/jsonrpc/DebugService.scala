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
import com.chipprbots.ethereum.vm.tracing.{CallTracer, CallTracerResult, PrestateTracer,
  PrestateTracerResult, StructLogTracer}

object DebugService {
  case class ListPeersInfoRequest()
  case class ListPeersInfoResponse(peers: List[PeerInfo])

  /** Geth-compatible TraceConfig. `tracer` selects the tracer implementation:
    *   - None / "" / "structLogger" → StructLogTracer (default)
    *   - "callTracer" → call-graph tracer (nested call frames)
    *   - "prestateTracer" → account pre-state tracer
    * Other flags tune the default structLogger's output.
    */
  case class TraceConfig(
      disableStack: Boolean = false,
      disableMemory: Boolean = false,
      disableStorage: Boolean = false,
      tracer: Option[String] = None
  )

  case class TraceTransactionRequest(
      txHash: org.apache.pekko.util.ByteString,
      config: TraceConfig = TraceConfig()
  )

  /** Unified trace result — carries either a structLog payload (default), a call-graph
    * tree (callTracer), or a prestate snapshot (prestateTracer). The JSON codec emits
    * the geth-compatible shape for whichever variant is populated.
    */
  case class TraceTransactionResponse(
      gas: BigInt,
      failed: Boolean,
      returnValue: org.apache.pekko.util.ByteString,
      structLogs: List[com.chipprbots.ethereum.vm.tracing.StructLogEntry],
      callTracerResult: Option[CallTracerResult] = None,
      prestateTracerResult: Option[PrestateTracerResult] = None
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
    else doTraceTransaction(req.txHash, req.config)
  }

  /** Trace all transactions in a block. Response is a JSON-encodable Seq matched to each tx. */
  def traceBlockByHash(blockHash: ByteString, config: DebugService.TraceConfig = DebugService.TraceConfig()):
      ServiceResponse[List[DebugService.TraceTransactionResponse]] = IO {
    if (blockchainReader == null || stxLedger == null || blockchainConfig == null)
      Left(JsonRpcError(-32000, "tracing not wired", None))
    else doTraceBlock(blockHash, config)
  }

  def traceBlockByNumber(blockNumber: BigInt, config: DebugService.TraceConfig = DebugService.TraceConfig()):
      ServiceResponse[List[DebugService.TraceTransactionResponse]] = IO {
    if (blockchainReader == null || stxLedger == null || blockchainConfig == null)
      Left(JsonRpcError(-32000, "tracing not wired", None))
    else {
      blockchainReader.getBlockHeaderByNumber(blockNumber) match {
        case Some(h) => doTraceBlock(h.hash, config)
        case None => Left(JsonRpcError(-32000, s"block $blockNumber not found", None))
      }
    }
  }

  private def doTraceTransaction(
      txHash: ByteString,
      config: DebugService.TraceConfig
  ): Either[JsonRpcError, DebugService.TraceTransactionResponse] = {
    transactionMappingStorage.get(txHash) match {
      case None =>
        Left(JsonRpcError(-32000,
          s"transaction not found: 0x${txHash.toArray.map("%02x".format(_)).mkString}", None))
      case Some(TransactionLocation(blockHash, txIndex)) =>
        blockchainReader.getBlockByHash(blockHash) match {
          case None => Left(JsonRpcError(-32000, "block not available", None))
          case Some(Block(header, body)) =>
            val stx = body.transactionList(txIndex)
            val resp = traceSingleTx(stx, header, config)
            Right(resp)
        }
    }
  }

  private def doTraceBlock(
      blockHash: ByteString,
      config: DebugService.TraceConfig
  ): Either[JsonRpcError, List[DebugService.TraceTransactionResponse]] = {
    blockchainReader.getBlockByHash(blockHash) match {
      case None => Left(JsonRpcError(-32000, "block not found", None))
      case Some(Block(header, body)) =>
        Right(body.transactionList.map(stx => traceSingleTx(stx, header, config)).toList)
    }
  }

  /** Trace a single tx against the state at `header.parent`. TraceConfig.tracer selects
    * the tracer implementation: default structLog, callTracer (call-graph), or
    * prestateTracer (account touches).
    */
  private def traceSingleTx(
      stx: SignedTransaction,
      header: com.chipprbots.ethereum.domain.BlockHeader,
      config: DebugService.TraceConfig
  ): DebugService.TraceTransactionResponse = {
    val sender = SignedTransaction.getSender(stx).getOrElse(Address(0))
    val stxWithSender = SignedTransactionWithSender(stx, sender)
    val traceAtHeader =
      blockchainReader.getBlockHeaderByHash(header.parentHash).getOrElse(header)
    config.tracer match {
      case Some("callTracer") =>
        val tracer = new CallTracer()
        val result = stxLedger.simulateTransactionWithTracer(stxWithSender, traceAtHeader, None, Some(tracer))
        DebugService.TraceTransactionResponse(
          gas = result.gasUsed, failed = result.vmError.isDefined,
          returnValue = result.vmReturnData, structLogs = Nil,
          callTracerResult = Some(tracer.result)
        )
      case Some("prestateTracer") =>
        val tracer = new PrestateTracer()
        val result = stxLedger.simulateTransactionWithTracer(stxWithSender, traceAtHeader, None, Some(tracer))
        DebugService.TraceTransactionResponse(
          gas = result.gasUsed, failed = result.vmError.isDefined,
          returnValue = result.vmReturnData, structLogs = Nil,
          prestateTracerResult = Some(tracer.result)
        )
      case _ =>
        val tracer = new StructLogTracer(
          disableStack = config.disableStack,
          disableMemory = config.disableMemory,
          disableStorage = config.disableStorage
        )
        val result = stxLedger.simulateTransactionWithTracer(stxWithSender, traceAtHeader, None, Some(tracer))
        val snap = tracer.result
        DebugService.TraceTransactionResponse(
          gas = snap.gas,
          failed = snap.failed || result.vmError.isDefined,
          returnValue = snap.returnValue,
          structLogs = snap.structLogs
        )
    }
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
