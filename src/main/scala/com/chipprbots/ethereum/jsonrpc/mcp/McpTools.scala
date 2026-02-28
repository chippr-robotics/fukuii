package com.chipprbots.ethereum.jsonrpc.mcp

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.util.Timeout

import cats.effect.IO

import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.ExecutionContext
import org.json4s.JsonAST._

import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
import com.chipprbots.ethereum.consensus.mining.Mining
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.jsonrpc.AkkaTaskOps._
import com.chipprbots.ethereum.network.PeerManagerActor
import com.chipprbots.ethereum.utils.{BlockchainConfig, BuildInfo, NodeStatus}
import com.chipprbots.ethereum.utils.ServerStatus

object NodeInfoTool {
  val name = "mcp_node_info"
  val description = Some("Get detailed information about the Fukuii node including version, network, and chain configuration")

  def execute(
      blockchainConfig: BlockchainConfig
  ): IO[String] = {
    val networkName = blockchainConfig.chainId match {
      case id if id == 1  => "Ethereum Classic Mainnet"
      case id if id == 63 => "Mordor Testnet"
      case id if id == 6  => "Kotti Testnet"
      case id             => s"Chain $id"
    }
    IO.pure(s"""Fukuii Node Information:
      |• Version: ${BuildInfo.version}
      |• Scala Version: ${BuildInfo.scalaVersion}
      |• Git Commit: ${BuildInfo.gitHeadCommit}
      |• Git Branch: ${BuildInfo.gitCurrentBranch}
      |• Network: $networkName
      |• Chain ID: ${blockchainConfig.chainId}
      |• Network ID: ${blockchainConfig.networkId}
      |• Client ID: Fukuii/${BuildInfo.version}""".stripMargin)
  }
}

object NodeStatusTool {
  val name = "mcp_node_status"
  val description = Some("Get the current operational status of the Fukuii node including sync state, peer count, and best block")

  def execute(
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

      val syncInfo = syncStatus match {
        case SyncProtocol.Status.Syncing(start, blocks, stateNodes) =>
          val progress = if (blocks.target > 0) {
            f"${(blocks.current.toDouble / blocks.target.toDouble * 100)}%.1f%%"
          } else "calculating..."
          s"""• Syncing: true
            |• Sync Start Block: $start
            |• Current Block: ${blocks.current}
            |• Target Block: ${blocks.target}
            |• Sync Progress: $progress""".stripMargin
        case SyncProtocol.Status.SyncDone =>
          s"• Syncing: false (sync complete)"
        case SyncProtocol.Status.NotSyncing =>
          s"• Syncing: false"
      }

      s"""Node Status:
        |• Running: true
        |• Listening: $listening
        |• Peers: $peerCount
        |• Best Block: $bestBlockNum
        |$syncInfo""".stripMargin
    }
  }
}

object BlockchainInfoTool {
  val name = "mcp_blockchain_info"
  val description = Some("Get information about the blockchain state including best block, chain ID, and total difficulty")

  def execute(
      blockchainReader: BlockchainReader,
      blockchainConfig: BlockchainConfig
  ): IO[String] = IO {
    val bestBlockNum = blockchainReader.getBestBlockNumber()
    val genesisHeader = blockchainReader.genesisHeader
    val genesisHash = s"0x${genesisHeader.hashAsHexString}"

    val bestBlockInfo = blockchainReader.getBestBlock() match {
      case Some(block) =>
        val hash = s"0x${block.header.hashAsHexString}"
        val weight = blockchainReader.getChainWeightByHash(block.header.hash)
        val td = weight.map(_.totalDifficulty.toString).getOrElse("unknown")
        s"""• Best Block Number: $bestBlockNum
          |• Best Block Hash: $hash
          |• Total Difficulty: $td""".stripMargin
      case None =>
        s"• Best Block Number: $bestBlockNum"
    }

    val networkName = blockchainConfig.chainId match {
      case id if id == 1  => "Ethereum Classic (ETC)"
      case id if id == 63 => "Mordor Testnet"
      case id             => s"Chain $id"
    }

    s"""Blockchain Information:
      |• Network: $networkName
      |$bestBlockInfo
      |• Chain ID: ${blockchainConfig.chainId}
      |• Genesis Hash: $genesisHash""".stripMargin
  }
}

object SyncStatusTool {
  val name = "mcp_sync_status"
  val description = Some("Get detailed synchronization status including mode, progress, and block counts")

  def execute(
      syncController: ActorRef,
      blockchainReader: BlockchainReader
  )(implicit timeout: Timeout, ec: ExecutionContext): IO[String] = {
    syncController
      .askFor[SyncProtocol.Status](SyncProtocol.GetStatus)
      .map {
        case SyncProtocol.Status.Syncing(startBlock, blocksProgress, stateNodesProgress) =>
          val remaining = blocksProgress.target - blocksProgress.current
          val progress = if (blocksProgress.target > 0) {
            f"${(blocksProgress.current.toDouble / blocksProgress.target.toDouble * 100)}%.2f%%"
          } else "0.00%"

          val stateInfo = stateNodesProgress match {
            case Some(sp) if sp.nonEmpty =>
              s"""|• State Nodes Downloaded: ${sp.current}
                  |• State Nodes Target: ${sp.target}""".stripMargin
            case _ => ""
          }

          s"""Sync Status:
            |• Syncing: true
            |• Starting Block: $startBlock
            |• Current Block: ${blocksProgress.current}
            |• Target Block: ${blocksProgress.target}
            |• Remaining Blocks: $remaining
            |• Progress: $progress$stateInfo""".stripMargin

        case SyncProtocol.Status.SyncDone =>
          val bestBlock = blockchainReader.getBestBlockNumber()
          s"""Sync Status:
            |• Syncing: false
            |• Status: Sync complete
            |• Best Block: $bestBlock""".stripMargin

        case SyncProtocol.Status.NotSyncing =>
          val bestBlock = blockchainReader.getBestBlockNumber()
          s"""Sync Status:
            |• Syncing: false
            |• Status: Not syncing
            |• Best Block: $bestBlock""".stripMargin
      }
      .handleErrorWith { err =>
        IO.pure(s"""Sync Status:
          |• Error querying sync controller: ${err.getMessage}""".stripMargin)
      }
  }
}

object PeerListTool {
  val name = "mcp_peer_list"
  val description = Some("List all connected peers with their addresses and connection direction")

  def execute(
      peerManager: ActorRef
  )(implicit timeout: Timeout, ec: ExecutionContext): IO[String] = {
    peerManager
      .askFor[PeerManagerActor.Peers](PeerManagerActor.GetPeers)
      .map { peers =>
        val handshaked = peers.handshaked
        if (handshaked.isEmpty) {
          "Connected Peers: 0\nNo peers currently connected."
        } else {
          val peerLines = handshaked.zipWithIndex.map { case (peer, idx) =>
            val direction = if (peer.incomingConnection) "inbound" else "outbound"
            val addr = peer.remoteAddress.toString
            val nodeIdStr = peer.nodeId.map(id => s"0x${id.take(8).map("%02x".format(_)).mkString}...").getOrElse("unknown")
            s"  ${idx + 1}. $addr ($direction) node=$nodeIdStr"
          }
          s"""Connected Peers: ${handshaked.size}
            |${peerLines.mkString("\n")}""".stripMargin
        }
      }
      .handleErrorWith { err =>
        IO.pure(s"Connected Peers: error querying peer manager (${err.getMessage})")
      }
  }
}

object SetEtherbaseTool {
  val name = "mcp_etherbase_info"
  val description = Some("Get information about the etherbase (coinbase) address configuration for mining rewards")

  def execute(): IO[String] = {
    IO.pure(s"""Etherbase (Coinbase) Configuration:
      |• Method: eth_setEtherbase
      |• Description: Sets the coinbase address for mining rewards
      |• Usage: Send JSON-RPC request with method "eth_setEtherbase" and address parameter
      |• Example: {"jsonrpc":"2.0","method":"eth_setEtherbase","params":["0x1234..."],"id":1}
      |• Related Methods:
      |  - eth_coinbase: Get current coinbase address
      |  - miner_start: Start mining
      |  - miner_stop: Stop mining
      |  - miner_getStatus: Get mining status
      |• Note: Changes take effect immediately for newly generated blocks""".stripMargin)
  }
}

object MiningRpcSummaryTool {
  val name = "mcp_mining_rpc_summary"
  val description = Some("List available mining RPC methods and their descriptions")

  def execute(): IO[String] = {
    IO.pure(s"""Mining RPC Methods:
      |• eth_mining — Returns whether the node is actively mining
      |• eth_hashrate — Returns the current hashrate (H/s)
      |• eth_getWork — Returns current mining work: [powHash, seedHash, target]
      |• eth_submitWork — Submit a PoW solution: [nonce, powHash, mixHash]
      |• eth_submitHashrate — Report external miner hashrate: [hashrate, minerId]
      |• eth_coinbase — Returns the current coinbase address
      |• eth_setEtherbase — Set the coinbase address for mining rewards
      |• miner_start — Start the CPU miner
      |• miner_stop — Stop the CPU miner
      |• miner_getStatus — Returns mining state: {isMining, coinbase, hashRate}
      |
      |Note: Use eth_mining and miner_getStatus to check current mining state.
      |All methods are available via JSON-RPC on the node's HTTP endpoint.""".stripMargin)
  }
}

object McpToolRegistry {

  def getAllTools(): List[McpToolDefinition] = List(
    McpToolDefinition(NodeStatusTool.name, NodeStatusTool.description),
    McpToolDefinition(NodeInfoTool.name, NodeInfoTool.description),
    McpToolDefinition(BlockchainInfoTool.name, BlockchainInfoTool.description),
    McpToolDefinition(SyncStatusTool.name, SyncStatusTool.description),
    McpToolDefinition(PeerListTool.name, PeerListTool.description),
    McpToolDefinition(SetEtherbaseTool.name, SetEtherbaseTool.description),
    McpToolDefinition(MiningRpcSummaryTool.name, MiningRpcSummaryTool.description)
  )

  def executeTool(
      toolName: String,
      arguments: Option[JValue],
      peerManager: ActorRef,
      syncController: ActorRef,
      blockchainReader: BlockchainReader,
      blockchainConfig: BlockchainConfig,
      mining: Mining,
      nodeStatusHolder: AtomicReference[NodeStatus]
  )(implicit timeout: Timeout, ec: ExecutionContext): IO[String] = {
    toolName match {
      case NodeStatusTool.name =>
        NodeStatusTool.execute(peerManager, syncController, blockchainReader, nodeStatusHolder)
      case NodeInfoTool.name =>
        NodeInfoTool.execute(blockchainConfig)
      case BlockchainInfoTool.name =>
        BlockchainInfoTool.execute(blockchainReader, blockchainConfig)
      case SyncStatusTool.name =>
        SyncStatusTool.execute(syncController, blockchainReader)
      case PeerListTool.name =>
        PeerListTool.execute(peerManager)
      case SetEtherbaseTool.name =>
        SetEtherbaseTool.execute()
      case MiningRpcSummaryTool.name =>
        MiningRpcSummaryTool.execute()
      case _ =>
        IO.pure(s"Unknown tool: $toolName. Use tools/list to see available tools.")
    }
  }
}

case class McpToolDefinition(
    name: String,
    description: Option[String]
)
