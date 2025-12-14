package com.chipprbots.ethereum.jsonrpc.mcp

import org.json4s.JsonAST._
import org.json4s.JsonDSL._

/**
 * Node health check prompt for MCP.
 * Guides users through a comprehensive health check.
 */
object NodeHealthCheckPrompt {
  val name = "mcp_node_health_check"
  val description = Some("Perform a comprehensive health check of the Fukuii node")
  
  def get(): (String, List[JValue]) = {
    val text = """Please check the health of my Fukuii ETC node. Use the available tools to verify:
      |1. Node status and responsiveness
      |2. Blockchain sync progress
      |3. Peer connectivity
      |4. Recent block production
      |5. Any concerning metrics or errors
      |
      |Provide a comprehensive health assessment.""".stripMargin
    
    val message = ("role" -> "user") ~ ("content" -> (("type" -> "text") ~ ("text" -> text)))
    (s"Prompt: $name", List(message))
  }
}

/**
 * Sync troubleshooting prompt for MCP.
 * Guides users through diagnosing sync issues.
 */
object SyncTroubleshootingPrompt {
  val name = "mcp_sync_troubleshooting"
  val description = Some("Troubleshoot blockchain synchronization issues")
  
  def get(): (String, List[JValue]) = {
    val text = """My Fukuii node seems to be having sync issues. Please diagnose:
      |1. Current sync status and progress
      |2. Peer connectivity quality
      |3. Comparison with network best block
      |4. Identify if sync is stalled or slow
      |5. Check for error patterns
      |6. Recommend specific actions
      |
      |Analyze the node state and provide recommendations.""".stripMargin
    
    val message = ("role" -> "user") ~ ("content" -> (("type" -> "text") ~ ("text" -> text)))
    (s"Prompt: $name", List(message))
  }
}

/**
 * Peer management prompt for MCP.
 * Guides users through managing peer connections.
 */
object PeerManagementPrompt {
  val name = "mcp_peer_management"
  val description = Some("Manage and optimize peer connections")
  
  def get(): (String, List[JValue]) = {
    val text = """Help me manage peer connections for my Fukuii node:
      |1. List currently connected peers
      |2. Analyze peer quality and diversity
      |3. Identify problematic peers
      |4. Check peer diversity
      |5. Recommend optimal peer count
      |6. Suggest strategies to improve connectivity
      |
      |Provide detailed peer analysis and recommendations.""".stripMargin
    
    val message = ("role" -> "user") ~ ("content" -> (("type" -> "text") ~ ("text" -> text)))
    (s"Prompt: $name", List(message))
  }
}

/**
 * Mining operations prompt for MCP.
 * Guides users through validating and controlling mining endpoints.
 */
object MiningOperationsPrompt {
  val name = "mcp_mining_operations"
  val description = Some("Validate Node1 mining RPC endpoints and control the miner")

  def get(): (String, List[JValue]) = {
    val text = """Use the verified mining RPC endpoints on Node1 (http://127.0.0.1:8545):
      |• Read status via eth_mining, eth_hashrate, miner_getStatus
      |• Fetch work with eth_getWork and note the latest pow header hash 0xff69bb2fce4542288b4616d50c220997b527da3f180043283b93e23b5bff2107
      |• Control mining via miner_start and miner_stop (be sure to restore miner_start afterward)
      |• Report coinbase 0x1000000000000000000000000000000000000001 and hashrate submissions (hashRate 0x1, minerId 0x1234…)
      |
      |Confirm each endpoint responds successfully and summarize any anomalies.""".stripMargin

    val message = ("role" -> "user") ~ ("content" -> (("type" -> "text") ~ ("text" -> text)))
    (s"Prompt: $name", List(message))
  }
}

/**
 * Registry of all available MCP prompts.
 * This makes it easy to add/remove prompts and track changes.
 */
object McpPromptRegistry {
  
  /**
   * Get all available prompt definitions.
   * Each prompt is defined in its own object for modularity.
   */
  def getAllPrompts(): List[McpPromptDefinition] = List(
    McpPromptDefinition(NodeHealthCheckPrompt.name, NodeHealthCheckPrompt.description),
    McpPromptDefinition(SyncTroubleshootingPrompt.name, SyncTroubleshootingPrompt.description),
    McpPromptDefinition(PeerManagementPrompt.name, PeerManagementPrompt.description),
    McpPromptDefinition(MiningOperationsPrompt.name, MiningOperationsPrompt.description)
  )
  
  /**
   * Get a prompt by name.
   */
  def getPrompt(promptName: String): (String, List[JValue]) = {
    promptName match {
      case NodeHealthCheckPrompt.name => NodeHealthCheckPrompt.get()
      case SyncTroubleshootingPrompt.name => SyncTroubleshootingPrompt.get()
      case PeerManagementPrompt.name => PeerManagementPrompt.get()
      case MiningOperationsPrompt.name => MiningOperationsPrompt.get()
      case _ => (s"Unknown prompt: $promptName", List.empty)
    }
  }
}

/**
 * Simple prompt definition for registration.
 */
case class McpPromptDefinition(
    name: String,
    description: Option[String]
)
