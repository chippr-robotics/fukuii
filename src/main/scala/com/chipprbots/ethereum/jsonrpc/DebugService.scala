package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.util.ByteString
import org.apache.pekko.util.Timeout

import cats.effect.IO
import cats.syntax.traverse._

import scala.concurrent.duration._

import com.chipprbots.ethereum.domain._
import com.chipprbots.ethereum.domain.BlockHeaderImplicits.BlockHeaderEnc
import com.chipprbots.ethereum.db.storage.TransactionMappingStorage
import com.chipprbots.ethereum.jsonrpc.AkkaTaskOps._
import com.chipprbots.ethereum.jsonrpc.DebugService._
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.PeerInfo
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.PeerInfoResponse
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerActor
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.PeerManagerActor
import com.chipprbots.ethereum.network.PeerManagerActor.Peers
import com.chipprbots.ethereum.network.p2p.messages.BaseETH6XMessages.SignedTransactions.SignedTransactionEnc
import com.chipprbots.ethereum.network.p2p.messages.ETH63.ReceiptImplicits.ReceiptEnc
import com.chipprbots.ethereum.rlp
import com.chipprbots.ethereum.rlp.RLPImplicits._
import com.chipprbots.ethereum.rlp.RLPList

object DebugService {
  case class ListPeersInfoRequest()
  case class ListPeersInfoResponse(peers: List[PeerInfo])

  // Raw data methods
  case class GetRawHeaderRequest(blockParam: BlockParam)
  case class GetRawHeaderResponse(headerRlp: Option[ByteString])

  case class GetRawBlockRequest(blockParam: BlockParam)
  case class GetRawBlockResponse(blockRlp: Option[ByteString])

  case class GetRawReceiptsRequest(blockParam: BlockParam)
  case class GetRawReceiptsResponse(receiptsRlp: Option[Seq[ByteString]])

  case class GetRawTransactionRequest(txHash: ByteString)
  case class GetRawTransactionResponse(txRlp: Option[ByteString])

  // Bad blocks
  case class GetBadBlocksRequest()
  case class BadBlock(hash: ByteString, number: BigInt)
  case class GetBadBlocksResponse(badBlocks: Seq[BadBlock])

  // Set head
  case class SetHeadRequest(blockNumber: BigInt)
  case class SetHeadResponse(success: Boolean)

  // Runtime profiling (M-001)
  case class MemStatsRequest()
  case class MemStatsResponse(
      heapUsed: Long,
      heapMax: Long,
      heapCommitted: Long,
      nonHeapUsed: Long,
      nonHeapCommitted: Long,
      totalMemory: Long,
      freeMemory: Long,
      maxMemory: Long
  )

  case class GcStatsRequest()
  case class GcStats(name: String, collectionCount: Long, collectionTimeMs: Long)
  case class GcStatsResponse(collectors: Seq[GcStats])

  case class StacksRequest()
  case class StacksResponse(stacks: String)

  // Log verbosity control (M-002)
  case class SetVerbosityRequest(level: String)
  case class SetVerbosityResponse(success: Boolean)

  case class SetVmoduleRequest(module: String, level: String)
  case class SetVmoduleResponse(success: Boolean)

  case class GetVerbosityRequest()
  case class GetVerbosityResponse(rootLevel: String, modules: Map[String, String])
}

class DebugService(
    peerManager: ActorRef,
    networkPeerManager: ActorRef,
    blockchainReader: BlockchainReader,
    appStateStorage: com.chipprbots.ethereum.db.storage.AppStateStorage,
    transactionMappingStorage: TransactionMappingStorage
) {

  def listPeersInfo(getPeersInfoRequest: ListPeersInfoRequest): ServiceResponse[ListPeersInfoResponse] =
    for {
      ids <- getPeerIds
      peers <- ids.traverse(getPeerInfo)
    } yield Right(ListPeersInfoResponse(peers.flatten))

  def getRawHeader(req: GetRawHeaderRequest): ServiceResponse[GetRawHeaderResponse] = IO {
    val headerOpt = resolveBlockHeader(req.blockParam)
    val rlpOpt = headerOpt.map(h => ByteString(rlp.encode(h.toRLPEncodable)))
    Right(GetRawHeaderResponse(rlpOpt))
  }

  def getRawBlock(req: GetRawBlockRequest): ServiceResponse[GetRawBlockResponse] = IO {
    val blockOpt = resolveBlock(req.blockParam)
    val rlpOpt = blockOpt.map { block =>
      val headerRlp = block.header.toRLPEncodable
      val txListRlp = RLPList(block.body.transactionList.map(_.toRLPEncodable): _*)
      val uncleListRlp = RLPList(block.body.uncleNodesList.map(_.toRLPEncodable): _*)
      ByteString(rlp.encode(RLPList(headerRlp, txListRlp, uncleListRlp)))
    }
    Right(GetRawBlockResponse(rlpOpt))
  }

  def getRawReceipts(req: GetRawReceiptsRequest): ServiceResponse[GetRawReceiptsResponse] = IO {
    val blockOpt = resolveBlock(req.blockParam)
    val receiptsOpt = blockOpt.flatMap(block => blockchainReader.getReceiptsByHash(block.header.hash))
    val rlpOpt = receiptsOpt.map(_.map(r => ByteString(rlp.encode(r.toRLPEncodable))))
    Right(GetRawReceiptsResponse(rlpOpt))
  }

  def getRawTransaction(req: GetRawTransactionRequest): ServiceResponse[GetRawTransactionResponse] = IO {
    val txOpt = transactionMappingStorage.get(req.txHash).flatMap { loc =>
      blockchainReader.getBlockByHash(loc.blockHash).flatMap { block =>
        block.body.transactionList.lift(loc.txIndex)
      }
    }
    val rlpOpt = txOpt.map(tx => ByteString(rlp.encode(tx.toRLPEncodable)))
    Right(GetRawTransactionResponse(rlpOpt))
  }

  def getBadBlocks(req: GetBadBlocksRequest): ServiceResponse[GetBadBlocksResponse] = IO {
    // Fukuii tracks bad blocks via blacklist, but doesn't store a separate bad block cache.
    // Return empty list for now — matches clients that have no bad blocks.
    Right(GetBadBlocksResponse(Seq.empty))
  }

  def memStats(req: MemStatsRequest): ServiceResponse[MemStatsResponse] = IO {
    import java.lang.management.ManagementFactory
    val memMx = ManagementFactory.getMemoryMXBean
    val heap = memMx.getHeapMemoryUsage
    val nonHeap = memMx.getNonHeapMemoryUsage
    val rt = Runtime.getRuntime
    Right(MemStatsResponse(
      heapUsed = heap.getUsed,
      heapMax = heap.getMax,
      heapCommitted = heap.getCommitted,
      nonHeapUsed = nonHeap.getUsed,
      nonHeapCommitted = nonHeap.getCommitted,
      totalMemory = rt.totalMemory(),
      freeMemory = rt.freeMemory(),
      maxMemory = rt.maxMemory()
    ))
  }

  def gcStats(req: GcStatsRequest): ServiceResponse[GcStatsResponse] = IO {
    import java.lang.management.ManagementFactory
    import scala.jdk.CollectionConverters._
    val collectors = ManagementFactory.getGarbageCollectorMXBeans.asScala.toSeq.map { gc =>
      GcStats(gc.getName, gc.getCollectionCount, gc.getCollectionTime)
    }
    Right(GcStatsResponse(collectors))
  }

  def stacks(req: StacksRequest): ServiceResponse[StacksResponse] = IO {
    val sb = new StringBuilder
    Thread.getAllStackTraces.forEach { (thread, traces) =>
      sb.append(s"\"${thread.getName}\" #${thread.threadId()} ${thread.getState}\n")
      traces.foreach(t => sb.append(s"  at $t\n"))
      sb.append("\n")
    }
    Right(StacksResponse(sb.toString))
  }

  def setHead(req: SetHeadRequest): ServiceResponse[SetHeadResponse] = IO {
    // Rewind best block number to the requested block
    blockchainReader.getBlockHeaderByNumber(req.blockNumber) match {
      case Some(header) =>
        appStateStorage.putBestBlockNumber(req.blockNumber).commit()
        Right(SetHeadResponse(true))
      case None =>
        Left(JsonRpcError.InvalidParams(s"Block ${req.blockNumber} not found"))
    }
  }

  def setVerbosity(req: SetVerbosityRequest): ServiceResponse[SetVerbosityResponse] = IO {
    import ch.qos.logback.classic.{Level, LoggerContext}
    import org.slf4j.LoggerFactory
    val level = Level.toLevel(req.level, null)
    if (level == null) Left(JsonRpcError.InvalidParams(s"Unknown log level: ${req.level}"))
    else {
      val ctx = LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext]
      ctx.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).setLevel(level)
      Right(SetVerbosityResponse(true))
    }
  }

  def setVmodule(req: SetVmoduleRequest): ServiceResponse[SetVmoduleResponse] = IO {
    import ch.qos.logback.classic.{Level, LoggerContext}
    import org.slf4j.LoggerFactory
    val level = Level.toLevel(req.level, null)
    if (level == null) Left(JsonRpcError.InvalidParams(s"Unknown log level: ${req.level}"))
    else {
      val ctx = LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext]
      // Support both short module names and full package paths
      val loggerName = if (req.module.contains(".")) req.module
        else s"com.chipprbots.ethereum.${req.module}"
      ctx.getLogger(loggerName).setLevel(level)
      Right(SetVmoduleResponse(true))
    }
  }

  def getVerbosity(req: GetVerbosityRequest): ServiceResponse[GetVerbosityResponse] = IO {
    import ch.qos.logback.classic.LoggerContext
    import org.slf4j.LoggerFactory
    import scala.jdk.CollectionConverters._
    val ctx = LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext]
    val rootLevel = ctx.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).getLevel.toString
    val modules = ctx.getLoggerList.asScala
      .filter(l => l.getLevel != null && l.getName != org.slf4j.Logger.ROOT_LOGGER_NAME)
      .map(l => l.getName -> l.getLevel.toString)
      .toMap
    Right(GetVerbosityResponse(rootLevel, modules))
  }

  private def resolveBlockHeader(blockParam: BlockParam): Option[BlockHeader] = blockParam match {
    case BlockParam.WithNumber(n) => blockchainReader.getBlockHeaderByNumber(n)
    case BlockParam.Latest        => blockchainReader.getBlockHeaderByNumber(blockchainReader.getBestBlockNumber())
    case BlockParam.Earliest      => blockchainReader.getBlockHeaderByNumber(0)
    case BlockParam.Pending       => blockchainReader.getBlockHeaderByNumber(blockchainReader.getBestBlockNumber())
  }

  private def resolveBlock(blockParam: BlockParam): Option[Block] = blockParam match {
    case BlockParam.WithNumber(n) =>
      blockchainReader.getBlockByNumber(blockchainReader.getBestBranch(), n)
    case BlockParam.Latest =>
      blockchainReader.getBlockByNumber(blockchainReader.getBestBranch(), blockchainReader.getBestBlockNumber())
    case BlockParam.Earliest =>
      blockchainReader.getBlockByNumber(blockchainReader.getBestBranch(), 0)
    case BlockParam.Pending =>
      blockchainReader.getBlockByNumber(blockchainReader.getBestBranch(), blockchainReader.getBestBlockNumber())
  }

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
