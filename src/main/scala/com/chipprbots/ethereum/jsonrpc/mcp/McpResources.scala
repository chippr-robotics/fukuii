package com.chipprbots.ethereum.jsonrpc.mcp

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.util.Timeout

import cats.effect.IO

import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.ExecutionContext

import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
import com.chipprbots.ethereum.consensus.mining.Mining
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.jsonrpc.AkkaTaskOps._
import com.chipprbots.ethereum.network.PeerManagerActor
import com.chipprbots.ethereum.utils.{BlockchainConfig, BuildInfo, ByteStringUtils, NodeStatus}
import com.chipprbots.ethereum.utils.ServerStatus

object NodeStatusResource {
  val uri = "fukuii://node/status"
  val name = "Node Status"
  val description = Some("Current status of the Fukuii node as JSON")
  val mimeType = Some("application/json")

  def read(
      peerManager: ActorRef,
      syncController: ActorRef,
      blockchainReader: BlockchainReader,
      nodeStatusHolder: AtomicReference[NodeStatus]
  )(implicit timeout: Timeout, ec: ExecutionContext): IO[String] = {
    val syncStatusIO = syncController
      .askFor[SyncProtocol.Status](SyncProtocol.GetStatus)
      .handleErrorWith(_ => IO.pure(SyncProtocol.Status.NotSyncing: SyncProtocol.Status))

    val peersIO = peerManager
      .askFor[PeerManagerActor.Peers](PeerManagerActor.GetPeers)
      .handleErrorWith(_ => IO.pure(PeerManagerActor.Peers(Map.empty)))

    for {
      syncStatus <- syncStatusIO
      peers <- peersIO
    } yield {
      val bestBlockNum = blockchainReader.getBestBlockNumber()
      val peerCount = peers.handshaked.size
      val nodeStatus = nodeStatusHolder.get()
      val listening = nodeStatus.serverStatus match {
        case _: ServerStatus.Listening => true
        case ServerStatus.NotListening => false
      }
      val syncing = syncStatus.syncing

      s"""{
        |  "running": true,
        |  "listening": $listening,
        |  "syncing": $syncing,
        |  "peerCount": $peerCount,
        |  "bestBlockNumber": $bestBlockNum
        |}""".stripMargin
    }
  }
}

object NodeConfigResource {
  val uri = "fukuii://node/config"
  val name = "Node Configuration"
  val description = Some("Current node configuration including network and chain settings")
  val mimeType = Some("application/json")

  def read(
      blockchainConfig: BlockchainConfig
  ): IO[String] = {
    val networkName = blockchainConfig.chainId match {
      case id if id == 1  => "etc"
      case id if id == 63 => "mordor"
      case id             => s"chain-$id"
    }
    IO.pure(s"""{
      |  "network": "$networkName",
      |  "chainId": ${blockchainConfig.chainId},
      |  "networkId": ${blockchainConfig.networkId},
      |  "version": "${BuildInfo.version}",
      |  "scalaVersion": "${BuildInfo.scalaVersion}"
      |}""".stripMargin)
  }
}

object LatestBlockResource {
  val uri = "fukuii://blockchain/latest"
  val name = "Latest Block"
  val description = Some("Information about the latest known block")
  val mimeType = Some("application/json")

  def read(
      blockchainReader: BlockchainReader
  ): IO[String] = IO {
    blockchainReader.getBestBlock() match {
      case Some(block) =>
        val h = block.header
        val txCount = block.body.transactionList.size
        s"""{
          |  "number": ${h.number},
          |  "hash": "0x${h.hashAsHexString}",
          |  "parentHash": "0x${ByteStringUtils.hash2string(h.parentHash)}",
          |  "timestamp": ${h.unixTimestamp},
          |  "difficulty": "${h.difficulty}",
          |  "gasLimit": ${h.gasLimit},
          |  "gasUsed": ${h.gasUsed},
          |  "transactionCount": $txCount
          |}""".stripMargin
      case None =>
        val bestNum = blockchainReader.getBestBlockNumber()
        s"""{"number": $bestNum, "error": "block data not available"}"""
    }
  }
}

object ConnectedPeersResource {
  val uri = "fukuii://peers/connected"
  val name = "Connected Peers"
  val description = Some("List of currently connected peers as JSON array")
  val mimeType = Some("application/json")

  def read(
      peerManager: ActorRef
  )(implicit timeout: Timeout, ec: ExecutionContext): IO[String] = {
    peerManager
      .askFor[PeerManagerActor.Peers](PeerManagerActor.GetPeers)
      .map { peers =>
        val handshaked = peers.handshaked
        val peerJsons = handshaked.map { peer =>
          val direction = if (peer.incomingConnection) "inbound" else "outbound"
          val addr = peer.remoteAddress.toString
          s"""    {"id": "${peer.id.value}", "address": "$addr", "direction": "$direction"}"""
        }
        s"""{
          |  "count": ${handshaked.size},
          |  "peers": [
          |${peerJsons.mkString(",\n")}
          |  ]
          |}""".stripMargin
      }
      .handleErrorWith { _ =>
        IO.pure("""{"count": 0, "peers": [], "error": "timeout querying peer manager"}""")
      }
  }
}

object MiningRpcResource {
  val uri = "fukuii://mining/rpc"
  val name = "Mining RPC Methods"
  val description = Some("Available mining JSON-RPC methods and their descriptions")
  val mimeType = Some("application/json")

  def read(): IO[String] = {
    IO.pure("""{
      |  "methods": [
      |    {"method": "eth_mining", "description": "Returns whether the node is actively mining"},
      |    {"method": "eth_hashrate", "description": "Returns the current hashrate"},
      |    {"method": "eth_getWork", "description": "Returns current mining work parameters"},
      |    {"method": "eth_submitWork", "description": "Submit a proof-of-work solution"},
      |    {"method": "eth_submitHashrate", "description": "Report external miner hashrate"},
      |    {"method": "eth_coinbase", "description": "Returns the current coinbase address"},
      |    {"method": "eth_setEtherbase", "description": "Set the coinbase address"},
      |    {"method": "miner_start", "description": "Start the CPU miner"},
      |    {"method": "miner_stop", "description": "Stop the CPU miner"},
      |    {"method": "miner_getStatus", "description": "Returns current mining state"}
      |  ]
      |}""".stripMargin)
  }
}

object SyncStatusResource {
  val uri = "fukuii://sync/status"
  val name = "Sync Status"
  val description = Some("Current blockchain synchronization status as JSON")
  val mimeType = Some("application/json")

  def read(
      syncController: ActorRef,
      blockchainReader: BlockchainReader
  )(implicit timeout: Timeout, ec: ExecutionContext): IO[String] = {
    syncController
      .askFor[SyncProtocol.Status](SyncProtocol.GetStatus)
      .map {
        case SyncProtocol.Status.Syncing(startBlock, blocksProgress, stateNodesProgress) =>
          val progress = if (blocksProgress.target > 0) {
            f"${(blocksProgress.current.toDouble / blocksProgress.target.toDouble * 100)}%.2f"
          } else "0.00"
          s"""{
            |  "syncing": true,
            |  "startingBlock": $startBlock,
            |  "currentBlock": ${blocksProgress.current},
            |  "targetBlock": ${blocksProgress.target},
            |  "remainingBlocks": ${blocksProgress.target - blocksProgress.current},
            |  "progress": $progress
            |}""".stripMargin
        case SyncProtocol.Status.SyncDone =>
          val best = blockchainReader.getBestBlockNumber()
          s"""{"syncing": false, "status": "complete", "bestBlock": $best}"""
        case SyncProtocol.Status.NotSyncing =>
          val best = blockchainReader.getBestBlockNumber()
          s"""{"syncing": false, "status": "not_syncing", "bestBlock": $best}"""
      }
      .handleErrorWith { err =>
        IO.pure(s"""{"syncing": false, "error": "${err.getMessage}"}""")
      }
  }
}

object McpResourceRegistry {

  def getAllResources(): List[McpResourceDefinition] = List(
    McpResourceDefinition(NodeStatusResource.uri, NodeStatusResource.name,
      NodeStatusResource.description, NodeStatusResource.mimeType),
    McpResourceDefinition(NodeConfigResource.uri, NodeConfigResource.name,
      NodeConfigResource.description, NodeConfigResource.mimeType),
    McpResourceDefinition(LatestBlockResource.uri, LatestBlockResource.name,
      LatestBlockResource.description, LatestBlockResource.mimeType),
    McpResourceDefinition(ConnectedPeersResource.uri, ConnectedPeersResource.name,
      ConnectedPeersResource.description, ConnectedPeersResource.mimeType),
    McpResourceDefinition(MiningRpcResource.uri, MiningRpcResource.name,
      MiningRpcResource.description, MiningRpcResource.mimeType),
    McpResourceDefinition(SyncStatusResource.uri, SyncStatusResource.name,
      SyncStatusResource.description, SyncStatusResource.mimeType)
  )

  def readResource(
      uri: String,
      peerManager: ActorRef,
      syncController: ActorRef,
      blockchainReader: BlockchainReader,
      blockchainConfig: BlockchainConfig,
      mining: Mining,
      nodeStatusHolder: AtomicReference[NodeStatus]
  )(implicit timeout: Timeout, ec: ExecutionContext): Either[String, IO[String]] = {
    uri match {
      case NodeStatusResource.uri =>
        Right(NodeStatusResource.read(peerManager, syncController, blockchainReader, nodeStatusHolder))
      case NodeConfigResource.uri =>
        Right(NodeConfigResource.read(blockchainConfig))
      case LatestBlockResource.uri =>
        Right(LatestBlockResource.read(blockchainReader))
      case ConnectedPeersResource.uri =>
        Right(ConnectedPeersResource.read(peerManager))
      case MiningRpcResource.uri =>
        Right(MiningRpcResource.read())
      case SyncStatusResource.uri =>
        Right(SyncStatusResource.read(syncController, blockchainReader))
      case _ =>
        Left(s"Unknown resource: $uri. Use resources/list to see available resources.")
    }
  }
}

case class McpResourceDefinition(
    uri: String,
    name: String,
    description: Option[String],
    mimeType: Option[String]
)
