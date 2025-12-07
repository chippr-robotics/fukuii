package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.pattern.ask
import org.apache.pekko.util.Timeout

import cats.effect.IO
import cats.implicits._

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext

import org.json4s.JsonAST.JValue

import com.chipprbots.ethereum.blockchain.sync.SyncController
import com.chipprbots.ethereum.network.EtcPeerManagerActor
import com.chipprbots.ethereum.network.PeerManagerActor
import com.chipprbots.ethereum.utils.BuildInfo

object McpService {
  // MCP Initialize
  case class McpInitializeRequest(params: Option[JValue])
  case class McpInitializeResponse(
      protocolVersion: String,
      capabilities: McpCapabilities,
      serverInfo: McpServerInfo
  )
  
  case class McpCapabilities(
      tools: Option[McpToolsCapability] = Some(McpToolsCapability()),
      resources: Option[McpResourcesCapability] = Some(McpResourcesCapability()),
      prompts: Option[McpPromptsCapability] = Some(McpPromptsCapability())
  )
  
  case class McpToolsCapability(listChanged: Option[Boolean] = None)
  case class McpResourcesCapability(subscribe: Option[Boolean] = None, listChanged: Option[Boolean] = None)
  case class McpPromptsCapability(listChanged: Option[Boolean] = None)
  
  case class McpServerInfo(name: String, version: String)
  
  // MCP Tools
  case class McpToolsListRequest()
  case class McpToolsListResponse(tools: List[McpTool])
  
  case class McpTool(
      name: String,
      description: Option[String],
      inputSchema: JValue
  )
  
  case class McpToolsCallRequest(name: String, arguments: Option[JValue])
  case class McpToolsCallResponse(content: List[McpTextContent], isError: Option[Boolean] = None)
  
  case class McpTextContent(`type`: String = "text", text: String)
  
  // MCP Resources
  case class McpResourcesListRequest()
  case class McpResourcesListResponse(resources: List[McpResource])
  
  case class McpResource(
      uri: String,
      name: String,
      description: Option[String] = None,
      mimeType: Option[String] = None
  )
  
  case class McpResourcesReadRequest(uri: String)
  case class McpResourcesReadResponse(contents: List[McpResourceContents])
  
  case class McpResourceContents(
      uri: String,
      mimeType: Option[String] = None,
      text: Option[String] = None
  )
  
  // MCP Prompts
  case class McpPromptsListRequest()
  case class McpPromptsListResponse(prompts: List[McpPrompt])
  
  case class McpPrompt(
      name: String,
      description: Option[String] = None,
      arguments: Option[List[McpPromptArgument]] = None
  )
  
  case class McpPromptArgument(
      name: String,
      description: Option[String] = None,
      required: Option[Boolean] = None
  )
  
  case class McpPromptsGetRequest(name: String, arguments: Option[JValue])
  case class McpPromptsGetResponse(description: Option[String], messages: List[JValue])
}

class McpService(
    peerManager: ActorRef,
    syncController: ActorRef
)(implicit val executionContext: ExecutionContext) {
  
  import McpService._
  
  implicit val timeout: Timeout = Timeout(10.seconds)
  
  def initialize(request: McpInitializeRequest): ServiceResponse[McpInitializeResponse] = {
    IO.pure(Right(McpInitializeResponse(
      protocolVersion = "2024-11-05",
      capabilities = McpCapabilities(),
      serverInfo = McpServerInfo(
        name = "Fukuii ETC Node MCP Server",
        version = BuildInfo.version
      )
    )))
  }
  
  def toolsList(request: McpToolsListRequest): ServiceResponse[McpToolsListResponse] = {
    import org.json4s.JsonDSL._
    
    val tools = List(
      McpTool(
        name = "mcp_node_status",
        description = Some("Get the current status of the Fukuii node"),
        inputSchema = ("type" -> "object") ~ ("properties" -> Map.empty[String, JValue]) ~ ("required" -> List.empty[String])
      ),
      McpTool(
        name = "mcp_node_info",
        description = Some("Get detailed information about the Fukuii node"),
        inputSchema = ("type" -> "object") ~ ("properties" -> Map.empty[String, JValue]) ~ ("required" -> List.empty[String])
      ),
      McpTool(
        name = "mcp_blockchain_info",
        description = Some("Get information about the blockchain state"),
        inputSchema = ("type" -> "object") ~ ("properties" -> Map.empty[String, JValue]) ~ ("required" -> List.empty[String])
      ),
      McpTool(
        name = "mcp_sync_status",
        description = Some("Get detailed synchronization status"),
        inputSchema = ("type" -> "object") ~ ("properties" -> Map.empty[String, JValue]) ~ ("required" -> List.empty[String])
      ),
      McpTool(
        name = "mcp_peer_list",
        description = Some("List all connected peers"),
        inputSchema = ("type" -> "object") ~ ("properties" -> Map.empty[String, JValue]) ~ ("required" -> List.empty[String])
      )
    )
    
    IO.pure(Right(McpToolsListResponse(tools)))
  }
  
  def toolsCall(request: McpToolsCallRequest): ServiceResponse[McpToolsCallResponse] = {
    request.name match {
      case "mcp_node_status" => getNodeStatus()
      case "mcp_node_info" => getNodeInfo()
      case "mcp_blockchain_info" => getBlockchainInfo()
      case "mcp_sync_status" => getSyncStatus()
      case "mcp_peer_list" => getPeerList()
      case _ => IO.pure(Right(McpToolsCallResponse(
        content = List(McpTextContent(text = s"Unknown tool: ${request.name}")),
        isError = Some(true)
      )))
    }
  }
  
  def resourcesList(request: McpResourcesListRequest): ServiceResponse[McpResourcesListResponse] = {
    val resources = List(
      McpResource(
        uri = "fukuii://node/status",
        name = "Node Status",
        description = Some("Current status of the Fukuii node"),
        mimeType = Some("application/json")
      ),
      McpResource(
        uri = "fukuii://node/config",
        name = "Node Configuration",
        description = Some("Current node configuration"),
        mimeType = Some("application/json")
      ),
      McpResource(
        uri = "fukuii://blockchain/latest",
        name = "Latest Block",
        description = Some("Information about the latest block"),
        mimeType = Some("application/json")
      ),
      McpResource(
        uri = "fukuii://peers/connected",
        name = "Connected Peers",
        description = Some("List of currently connected peers"),
        mimeType = Some("application/json")
      ),
      McpResource(
        uri = "fukuii://sync/status",
        name = "Sync Status",
        description = Some("Current blockchain synchronization status"),
        mimeType = Some("application/json")
      )
    )
    
    IO.pure(Right(McpResourcesListResponse(resources)))
  }
  
  def resourcesRead(request: McpResourcesReadRequest): ServiceResponse[McpResourcesReadResponse] = {
    request.uri match {
      case "fukuii://node/status" => getNodeStatusResource()
      case "fukuii://node/config" => getNodeConfigResource()
      case "fukuii://blockchain/latest" => getLatestBlockResource()
      case "fukuii://peers/connected" => getConnectedPeersResource()
      case "fukuii://sync/status" => getSyncStatusResource()
      case _ => IO.pure(Left(JsonRpcError.InvalidParams(s"Unknown resource URI: ${request.uri}")))
    }
  }
  
  def promptsList(request: McpPromptsListRequest): ServiceResponse[McpPromptsListResponse] = {
    val prompts = List(
      McpPrompt(
        name = "mcp_node_health_check",
        description = Some("Perform a comprehensive health check of the Fukuii node")
      ),
      McpPrompt(
        name = "mcp_sync_troubleshooting",
        description = Some("Troubleshoot blockchain synchronization issues")
      ),
      McpPrompt(
        name = "mcp_peer_management",
        description = Some("Manage and optimize peer connections")
      )
    )
    
    IO.pure(Right(McpPromptsListResponse(prompts)))
  }
  
  def promptsGet(request: McpPromptsGetRequest): ServiceResponse[McpPromptsGetResponse] = {
    import org.json4s.JsonDSL._
    
    val promptText = request.name match {
      case "mcp_node_health_check" =>
        """Please check the health of my Fukuii ETC node. Use the available tools to verify:
          |1. Node status and responsiveness
          |2. Blockchain sync progress
          |3. Peer connectivity
          |4. Recent block production
          |5. Any concerning metrics or errors
          |
          |Provide a comprehensive health assessment.""".stripMargin
      case "mcp_sync_troubleshooting" =>
        """My Fukuii node seems to be having sync issues. Please diagnose:
          |1. Current sync status and progress
          |2. Peer connectivity quality
          |3. Comparison with network best block
          |4. Identify if sync is stalled or slow
          |5. Check for error patterns
          |6. Recommend specific actions
          |
          |Analyze the node state and provide recommendations.""".stripMargin
      case "mcp_peer_management" =>
        """Help me manage peer connections for my Fukuii node:
          |1. List currently connected peers
          |2. Analyze peer quality and diversity
          |3. Identify problematic peers
          |4. Check peer diversity
          |5. Recommend optimal peer count
          |6. Suggest strategies to improve connectivity
          |
          |Provide detailed peer analysis and recommendations.""".stripMargin
      case _ => s"Unknown prompt: ${request.name}"
    }
    
    val message = ("role" -> "user") ~ ("content" -> (("type" -> "text") ~ ("text" -> promptText)))
    
    IO.pure(Right(McpPromptsGetResponse(
      description = Some(s"Prompt: ${request.name}"),
      messages = List(message)
    )))
  }
  
  // Private helper methods that will query actual node state
  
  private def getNodeStatus(): ServiceResponse[McpToolsCallResponse] = {
    // TODO: Query actual node state via actor refs
    val text = """Node Status:
      |• Running: true
      |• Syncing: checking...
      |• Peers: querying...
      |• Current Block: querying...
      |• Best Known Block: querying...
      |• Sync Progress: calculating...""".stripMargin
    
    IO.pure(Right(McpToolsCallResponse(
      content = List(McpTextContent(text = text))
    )))
  }
  
  private def getNodeInfo(): ServiceResponse[McpToolsCallResponse] = {
    val text = s"""Fukuii Node Information:
      |• Version: ${BuildInfo.version}
      |• Scala Version: ${BuildInfo.scalaVersion}
      |• Git Commit: ${BuildInfo.gitHeadCommit}
      |• Git Branch: ${BuildInfo.gitCurrentBranch}
      |• Network: ETC Mainnet
      |• Client ID: Fukuii/${BuildInfo.version}""".stripMargin
    
    IO.pure(Right(McpToolsCallResponse(
      content = List(McpTextContent(text = text))
    )))
  }
  
  private def getBlockchainInfo(): ServiceResponse[McpToolsCallResponse] = {
    // TODO: Query actual blockchain state
    val text = """Blockchain Information:
      |• Network: Ethereum Classic (ETC)
      |• Best Block Number: querying...
      |• Best Block Hash: querying...
      |• Chain ID: 61
      |• Total Difficulty: querying...
      |• Genesis Hash: 0xd4e5...6789""".stripMargin
    
    IO.pure(Right(McpToolsCallResponse(
      content = List(McpTextContent(text = text))
    )))
  }
  
  private def getSyncStatus(): ServiceResponse[McpToolsCallResponse] = {
    // TODO: Query SyncController
    val text = """Sync Status:
      |• Mode: Regular Sync
      |• Syncing: checking...
      |• Current Block: querying...
      |• Target Block: querying...
      |• Remaining Blocks: calculating...
      |• Progress: calculating...
      |• Sync Speed: measuring...""".stripMargin
    
    IO.pure(Right(McpToolsCallResponse(
      content = List(McpTextContent(text = text))
    )))
  }
  
  private def getPeerList(): ServiceResponse[McpToolsCallResponse] = {
    // TODO: Query PeerManagerActor
    val text = """Connected Peers:
      |Querying peer manager for current connections...""".stripMargin
    
    IO.pure(Right(McpToolsCallResponse(
      content = List(McpTextContent(text = text))
    )))
  }
  
  // Resource implementations
  
  private def getNodeStatusResource(): ServiceResponse[McpResourcesReadResponse] = {
    val content = """{
      |  "running": true,
      |  "syncing": "querying",
      |  "peerCount": "querying",
      |  "blockNumber": "querying",
      |  "bestKnownBlock": "querying",
      |  "networkId": 61,
      |  "chainId": 61
      |}""".stripMargin
    
    IO.pure(Right(McpResourcesReadResponse(
      contents = List(McpResourceContents(
        uri = "fukuii://node/status",
        mimeType = Some("application/json"),
        text = Some(content)
      ))
    )))
  }
  
  private def getNodeConfigResource(): ServiceResponse[McpResourcesReadResponse] = {
    val content = """{
      |  "network": "etc",
      |  "datadir": "~/.fukuii/datadir",
      |  "rpc": {
      |    "enabled": true,
      |    "port": 8545,
      |    "interface": "localhost"
      |  },
      |  "discovery": {
      |    "enabled": true,
      |    "port": 30303
      |  }
      |}""".stripMargin
    
    IO.pure(Right(McpResourcesReadResponse(
      contents = List(McpResourceContents(
        uri = "fukuii://node/config",
        mimeType = Some("application/json"),
        text = Some(content)
      ))
    )))
  }
  
  private def getLatestBlockResource(): ServiceResponse[McpResourcesReadResponse] = {
    val content = """{
      |  "number": "querying",
      |  "hash": "querying",
      |  "timestamp": 1700000000,
      |  "difficulty": "querying",
      |  "gasLimit": 8000000,
      |  "gasUsed": "querying",
      |  "transactionCount": "querying"
      |}""".stripMargin
    
    IO.pure(Right(McpResourcesReadResponse(
      contents = List(McpResourceContents(
        uri = "fukuii://blockchain/latest",
        mimeType = Some("application/json"),
        text = Some(content)
      ))
    )))
  }
  
  private def getConnectedPeersResource(): ServiceResponse[McpResourcesReadResponse] = {
    val content = """{
      |  "count": "querying",
      |  "peers": []
      |}""".stripMargin
    
    IO.pure(Right(McpResourcesReadResponse(
      contents = List(McpResourceContents(
        uri = "fukuii://peers/connected",
        mimeType = Some("application/json"),
        text = Some(content)
      ))
    )))
  }
  
  private def getSyncStatusResource(): ServiceResponse[McpResourcesReadResponse] = {
    val content = """{
      |  "syncing": "querying",
      |  "mode": "regular",
      |  "currentBlock": "querying",
      |  "targetBlock": "querying",
      |  "remainingBlocks": "calculating",
      |  "progress": "calculating"
      |}""".stripMargin
    
    IO.pure(Right(McpResourcesReadResponse(
      contents = List(McpResourceContents(
        uri = "fukuii://sync/status",
        mimeType = Some("application/json"),
        text = Some(content)
      ))
    )))
  }
}
