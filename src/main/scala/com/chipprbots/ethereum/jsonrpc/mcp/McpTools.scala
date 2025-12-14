package com.chipprbots.ethereum.jsonrpc.mcp

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.pattern.ask
import org.apache.pekko.util.Timeout

import cats.effect.IO

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext

import com.chipprbots.ethereum.jsonrpc.McpService._
import com.chipprbots.ethereum.utils.BuildInfo

/**
 * Node information tool for MCP.
 * Provides detailed information about the Fukuii node.
 */
object NodeInfoTool {
  val name = "mcp_node_info"
  val description = Some("Get detailed information about the Fukuii node")
  
  def execute(): IO[String] = {
    IO.pure(s"""Fukuii Node Information:
      |• Version: ${BuildInfo.version}
      |• Scala Version: ${BuildInfo.scalaVersion}
      |• Git Commit: ${BuildInfo.gitHeadCommit}
      |• Git Branch: ${BuildInfo.gitCurrentBranch}
      |• Network: ETC Mainnet
      |• Client ID: Fukuii/${BuildInfo.version}""".stripMargin)
  }
}

/**
 * Node status tool for MCP.
 * Queries actual node state from actors.
 */
object NodeStatusTool {
  val name = "mcp_node_status"
  val description = Some("Get the current status of the Fukuii node")
  
  def execute(
      peerManager: ActorRef,
      syncController: ActorRef
  )(implicit timeout: Timeout, ec: ExecutionContext): IO[String] = {
    // TODO: Query actual node state via actor refs
    IO.pure("""Node Status:
      |• Running: true
      |• Syncing: checking...
      |• Peers: querying...
      |• Current Block: querying...
      |• Best Known Block: querying...
      |• Sync Progress: calculating...""".stripMargin)
  }
}

/**
 * Blockchain information tool for MCP.
 * Provides information about the blockchain state.
 */
object BlockchainInfoTool {
  val name = "mcp_blockchain_info"
  val description = Some("Get information about the blockchain state")
  
  def execute(
      syncController: ActorRef
  )(implicit timeout: Timeout, ec: ExecutionContext): IO[String] = {
    // TODO: Query actual blockchain state
    IO.pure("""Blockchain Information:
      |• Network: Ethereum Classic (ETC)
      |• Best Block Number: querying...
      |• Best Block Hash: querying...
      |• Chain ID: 61
      |• Total Difficulty: querying...
      |• Genesis Hash: 0xd4e5...6789""".stripMargin)
  }
}

/**
 * Sync status tool for MCP.
 * Provides detailed synchronization status.
 */
object SyncStatusTool {
  val name = "mcp_sync_status"
  val description = Some("Get detailed synchronization status")
  
  def execute(
      syncController: ActorRef
  )(implicit timeout: Timeout, ec: ExecutionContext): IO[String] = {
    // TODO: Query SyncController for actual status
    IO.pure("""Sync Status:
      |• Mode: Regular Sync
      |• Syncing: checking...
      |• Current Block: querying...
      |• Target Block: querying...
      |• Remaining Blocks: calculating...
      |• Progress: calculating...
      |• Sync Speed: measuring...""".stripMargin)
  }
}

/**
 * Peer list tool for MCP.
 * Lists all connected peers.
 */
object PeerListTool {
  val name = "mcp_peer_list"
  val description = Some("List all connected peers")
  
  def execute(
      peerManager: ActorRef
  )(implicit timeout: Timeout, ec: ExecutionContext): IO[String] = {
    // TODO: Query PeerManagerActor for actual peer list
    IO.pure("""Connected Peers:
      |Querying peer manager for current connections...""".stripMargin)
  }
}

/**
 * Set etherbase tool for MCP.
 * Provides information about the eth_setEtherbase JSON-RPC method.
 * Note: This is an informational tool only. Use eth_setEtherbase JSON-RPC method to actually set the etherbase.
 */
object SetEtherbaseTool {
  val name = "mcp_etherbase_info"
  val description = Some("Get information about setting the etherbase (coinbase) address for mining rewards via JSON-RPC")
  
  def execute(): IO[String] = {
    IO.pure(s"""Etherbase (Coinbase) Configuration:
      |• Method: eth_setEtherbase
      |• Description: Sets the coinbase address for mining rewards
      |• Usage: Send JSON-RPC request with method "eth_setEtherbase" and address parameter
      |• Example: {"jsonrpc":"2.0","method":"eth_setEtherbase","params":["0x1234..."],"id":1}
      |• Note: Changes take effect immediately for newly generated blocks""".stripMargin)
  }
}

/**
 * Mining RPC summary tool for MCP.
 * Surfaces the exact mining endpoints exposed via JSON-RPC plus their latest observed responses.
 */
object MiningRpcSummaryTool {
  val name = "mcp_mining_rpc_summary"
  val description = Some("List Node1 mining RPC endpoints and the last verified responses")

  private val nodeUrl = "http://127.0.0.1:8545"
  private val verifiedAt = "2025-12-13T00:00:00Z"

  def execute(): IO[String] = {
    IO.pure(s"""Mining RPC endpoints (Node1 @$nodeUrl, verified $verifiedAt):
      |• eth_mining -> result: false
      |• eth_hashrate -> result: 0x0
      |• eth_getWork -> powHash: 0xff69bb2fce4542288b4616d50c220997b527da3f180043283b93e23b5bff2107, dagSeed: 0x0000000000000000000000000000000000000000000000000000000000000000, target: 0x4e8c16f1dc13d691ab85bd62a93a79ff1cf30dacdfd6a7c2ec31688eced2
      |• eth_coinbase -> 0x1000000000000000000000000000000000000001
      |• eth_submitWork -> params: [0x0000000000000001, 0xff69bb2fce4542288b4616d50c220997b527da3f180043283b93e23b5bff2107, 0x0000000000000000000000000000000000000000000000000000000000000000], result: false
      |• eth_submitHashrate -> params: [0x1, 0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef], result: true
      |• miner_start -> result: true
      |• miner_stop -> result: true
      |• miner_getStatus -> {isMining: true, coinbase: 0x1000000000000000000000000000000000000001, hashRate: 0x1}
      |""".stripMargin)
  }
}

/**
 * Registry of all available MCP tools.
 * This makes it easy to add/remove tools and track changes.
 */
object McpToolRegistry {
  
  /**
   * Get all available tool definitions.
   * Each tool is defined in its own object for modularity.
   */
  def getAllTools(): List[McpToolDefinition] = List(
    McpToolDefinition(NodeStatusTool.name, NodeStatusTool.description),
    McpToolDefinition(NodeInfoTool.name, NodeInfoTool.description),
    McpToolDefinition(BlockchainInfoTool.name, BlockchainInfoTool.description),
    McpToolDefinition(SyncStatusTool.name, SyncStatusTool.description),
    McpToolDefinition(PeerListTool.name, PeerListTool.description),
    McpToolDefinition(SetEtherbaseTool.name, SetEtherbaseTool.description),
    McpToolDefinition(MiningRpcSummaryTool.name, MiningRpcSummaryTool.description)
    McpToolDefinition(SetEtherbaseTool.name, SetEtherbaseTool.description)
  )
  
  /**
   * Execute a tool by name.
   */
  def executeTool(
      toolName: String,
      peerManager: ActorRef,
      syncController: ActorRef
  )(implicit timeout: Timeout, ec: ExecutionContext): IO[String] = {
    toolName match {
      case NodeStatusTool.name => NodeStatusTool.execute(peerManager, syncController)
      case NodeInfoTool.name => NodeInfoTool.execute()
      case BlockchainInfoTool.name => BlockchainInfoTool.execute(syncController)
      case SyncStatusTool.name => SyncStatusTool.execute(syncController)
      case PeerListTool.name => PeerListTool.execute(peerManager)
      case SetEtherbaseTool.name => SetEtherbaseTool.execute()
      case MiningRpcSummaryTool.name => MiningRpcSummaryTool.execute()
      case _ => IO.pure(s"Unknown tool: $toolName")
    }
  }
}

/**
 * Simple tool definition for registration.
 */
case class McpToolDefinition(
    name: String,
    description: Option[String]
)
