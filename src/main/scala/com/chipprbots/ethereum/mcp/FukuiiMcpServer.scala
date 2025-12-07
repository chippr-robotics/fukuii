package com.chipprbots.ethereum.mcp

import cats.effect.{IO, IOApp, ExitCode}
import cats.implicits.*
import fs2.{Stream, Pipe}
import fs2.io.stdin
import fs2.io.stdout
import io.circe.*
import io.circe.syntax.*
import io.circe.generic.auto.*
import io.circe.parser.*

import com.chipprbots.ethereum.utils.BuildInfo

/**
 * Simplified MCP Server for Fukuii
 * 
 * Implements Model Context Protocol over stdio using JSON-RPC 2.0
 * Provides agentic control over the Fukuii node
 */
object FukuiiMcpServer extends IOApp:

  // MCP Protocol Types
  case class JsonRpcRequest(
    jsonrpc: String = "2.0",
    id: Option[Json],
    method: String,
    params: Option[Json]
  )

  case class JsonRpcResponse(
    jsonrpc: String = "2.0",
    id: Option[Json],
    result: Option[Json] = None,
    error: Option[JsonRpcError] = None
  )

  case class JsonRpcError(
    code: Int,
    message: String,
    data: Option[Json] = None
  )

  case class ServerInfo(
    name: String,
    version: String
  )

  case class ServerCapabilities(
    tools: Option[ToolsCapability] = Some(ToolsCapability()),
    resources: Option[ResourcesCapability] = Some(ResourcesCapability()),
    prompts: Option[PromptsCapability] = Some(PromptsCapability())
  )

  case class ToolsCapability(listChanged: Option[Boolean] = None)
  case class ResourcesCapability(subscribe: Option[Boolean] = None, listChanged: Option[Boolean] = None)
  case class PromptsCapability(listChanged: Option[Boolean] = None)

  case class InitializeResult(
    protocolVersion: String = "2024-11-05",
    capabilities: ServerCapabilities,
    serverInfo: ServerInfo
  )

  case class Tool(
    name: String,
    description: Option[String],
    inputSchema: Json
  )

  case class ToolsList(
    tools: List[Tool]
  )

  case class Resource(
    uri: String,
    name: String,
    description: Option[String] = None,
    mimeType: Option[String] = None
  )

  case class ResourcesList(
    resources: List[Resource]
  )

  case class Prompt(
    name: String,
    description: Option[String] = None,
    arguments: Option[List[PromptArgument]] = None
  )

  case class PromptArgument(
    name: String,
    description: Option[String] = None,
    required: Option[Boolean] = None
  )

  case class PromptsList(
    prompts: List[Prompt]
  )

  case class TextContent(
    `type`: String = "text",
    text: String
  )

  case class CallToolResult(
    content: List[TextContent],
    isError: Option[Boolean] = None
  )

  case class ReadResourceResult(
    contents: List[ResourceContents]
  )

  case class ResourceContents(
    uri: String,
    mimeType: Option[String] = None,
    text: Option[String] = None
  )

  /**
   * Handle MCP requests
   */
  def handleRequest(request: JsonRpcRequest): IO[JsonRpcResponse] =
    request.method match
      case "initialize" => handleInitialize(request)
      case "tools/list" => handleToolsList(request)
      case "tools/call" => handleToolCall(request)
      case "resources/list" => handleResourcesList(request)
      case "resources/read" => handleResourceRead(request)
      case "prompts/list" => handlePromptsList(request)
      case "prompts/get" => handlePromptGet(request)
      case _ => IO.pure(JsonRpcResponse(
        id = request.id,
        error = Some(JsonRpcError(-32601, s"Method not found: ${request.method}"))
      ))

  def handleInitialize(request: JsonRpcRequest): IO[JsonRpcResponse] =
    val result = InitializeResult(
      capabilities = ServerCapabilities(),
      serverInfo = ServerInfo(
        name = "Fukuii ETC Node MCP Server",
        version = BuildInfo.version
      )
    )
    IO.pure(JsonRpcResponse(
      id = request.id,
      result = Some(result.asJson)
    ))

  def handleToolsList(request: JsonRpcRequest): IO[JsonRpcResponse] =
    val tools = List(
      Tool(
        name = "node_status",
        description = Some("Get the current status of the Fukuii node"),
        inputSchema = Json.obj(
          "type" -> "object".asJson,
          "properties" -> Json.obj(),
          "required" -> Json.arr()
        )
      ),
      Tool(
        name = "node_info",
        description = Some("Get detailed information about the Fukuii node"),
        inputSchema = Json.obj(
          "type" -> "object".asJson,
          "properties" -> Json.obj(),
          "required" -> Json.arr()
        )
      ),
      Tool(
        name = "blockchain_info",
        description = Some("Get information about the blockchain state"),
        inputSchema = Json.obj(
          "type" -> "object".asJson,
          "properties" -> Json.obj(),
          "required" -> Json.arr()
        )
      ),
      Tool(
        name = "sync_status",
        description = Some("Get detailed synchronization status"),
        inputSchema = Json.obj(
          "type" -> "object".asJson,
          "properties" -> Json.obj(),
          "required" -> Json.arr()
        )
      ),
      Tool(
        name = "peer_list",
        description = Some("List all connected peers"),
        inputSchema = Json.obj(
          "type" -> "object".asJson,
          "properties" -> Json.obj(),
          "required" -> Json.arr()
        )
      )
    )
    
    IO.pure(JsonRpcResponse(
      id = request.id,
      result = Some(ToolsList(tools).asJson)
    ))

  def handleToolCall(request: JsonRpcRequest): IO[JsonRpcResponse] =
    val toolName = request.params
      .flatMap(_.hcursor.downField("name").as[String].toOption)
      .getOrElse("")
    
    val resultText = toolName match
      case "node_status" => generateNodeStatus()
      case "node_info" => generateNodeInfo()
      case "blockchain_info" => generateBlockchainInfo()
      case "sync_status" => generateSyncStatus()
      case "peer_list" => generatePeerList()
      case _ => s"Unknown tool: $toolName"
    
    val result = CallToolResult(
      content = List(TextContent(text = resultText))
    )
    
    IO.pure(JsonRpcResponse(
      id = request.id,
      result = Some(result.asJson)
    ))

  def handleResourcesList(request: JsonRpcRequest): IO[JsonRpcResponse] =
    val resources = List(
      Resource(
        uri = "fukuii://node/status",
        name = "Node Status",
        description = Some("Current status of the Fukuii node"),
        mimeType = Some("application/json")
      ),
      Resource(
        uri = "fukuii://node/config",
        name = "Node Configuration",
        description = Some("Current node configuration"),
        mimeType = Some("application/json")
      ),
      Resource(
        uri = "fukuii://blockchain/latest",
        name = "Latest Block",
        description = Some("Information about the latest block"),
        mimeType = Some("application/json")
      ),
      Resource(
        uri = "fukuii://peers/connected",
        name = "Connected Peers",
        description = Some("List of currently connected peers"),
        mimeType = Some("application/json")
      ),
      Resource(
        uri = "fukuii://sync/status",
        name = "Sync Status",
        description = Some("Current blockchain synchronization status"),
        mimeType = Some("application/json")
      )
    )
    
    IO.pure(JsonRpcResponse(
      id = request.id,
      result = Some(ResourcesList(resources).asJson)
    ))

  def handleResourceRead(request: JsonRpcRequest): IO[JsonRpcResponse] =
    val uri = request.params
      .flatMap(_.hcursor.downField("uri").as[String].toOption)
      .getOrElse("")
    
    val content = uri match
      case "fukuii://node/status" => generateNodeStatusJson()
      case "fukuii://node/config" => generateNodeConfigJson()
      case "fukuii://blockchain/latest" => generateLatestBlockJson()
      case "fukuii://peers/connected" => generatePeersJson()
      case "fukuii://sync/status" => generateSyncStatusJson()
      case _ => s"""{"error": "Unknown resource: $uri"}"""
    
    val result = ReadResourceResult(
      contents = List(ResourceContents(
        uri = uri,
        mimeType = Some("application/json"),
        text = Some(content)
      ))
    )
    
    IO.pure(JsonRpcResponse(
      id = request.id,
      result = Some(result.asJson)
    ))

  def handlePromptsList(request: JsonRpcRequest): IO[JsonRpcResponse] =
    val prompts = List(
      Prompt(
        name = "node_health_check",
        description = Some("Perform a comprehensive health check of the Fukuii node")
      ),
      Prompt(
        name = "sync_troubleshooting",
        description = Some("Troubleshoot blockchain synchronization issues")
      ),
      Prompt(
        name = "peer_management",
        description = Some("Manage and optimize peer connections")
      )
    )
    
    IO.pure(JsonRpcResponse(
      id = request.id,
      result = Some(PromptsList(prompts).asJson)
    ))

  def handlePromptGet(request: JsonRpcRequest): IO[JsonRpcResponse] =
    val promptName = request.params
      .flatMap(_.hcursor.downField("name").as[String].toOption)
      .getOrElse("")
    
    val promptText = promptName match
      case "node_health_check" =>
        """Please check the health of my Fukuii ETC node. Use the available tools to verify:
          |1. Node status and responsiveness
          |2. Blockchain sync progress
          |3. Peer connectivity
          |4. Recent block production
          |5. Any concerning metrics or errors
          |
          |Provide a comprehensive health assessment.""".stripMargin
      case "sync_troubleshooting" =>
        """My Fukuii node seems to be having sync issues. Please diagnose:
          |1. Current sync status and progress
          |2. Peer connectivity quality
          |3. Comparison with network best block
          |4. Identify if sync is stalled or slow
          |5. Check for error patterns
          |6. Recommend specific actions
          |
          |Analyze the node state and provide recommendations.""".stripMargin
      case "peer_management" =>
        """Help me manage peer connections for my Fukuii node:
          |1. List currently connected peers
          |2. Analyze peer quality and diversity
          |3. Identify problematic peers
          |4. Check peer diversity
          |5. Recommend optimal peer count
          |6. Suggest strategies to improve connectivity
          |
          |Provide detailed peer analysis and recommendations.""".stripMargin
      case _ => s"Unknown prompt: $promptName"
    
    val result = Json.obj(
      "description" -> s"Prompt: $promptName".asJson,
      "messages" -> Json.arr(
        Json.obj(
          "role" -> "user".asJson,
          "content" -> Json.obj(
            "type" -> "text".asJson,
            "text" -> promptText.asJson
          )
        )
      )
    )
    
    IO.pure(JsonRpcResponse(
      id = request.id,
      result = Some(result)
    ))

  // Data generation methods (placeholders for actual node integration)
  def generateNodeStatus(): String =
    """Node Status:
      |• Running: true
      |• Syncing: true
      |• Peers: 5
      |• Current Block: 12345678
      |• Best Known Block: 12345700
      |• Sync Progress: 99.98%""".stripMargin

  def generateNodeInfo(): String =
    s"""Fukuii Node Information:
      |• Version: ${BuildInfo.version}
      |• Scala Version: ${BuildInfo.scalaVersion}
      |• Git Commit: ${BuildInfo.gitHeadCommit}
      |• Git Branch: ${BuildInfo.gitCurrentBranch}
      |• Network: ETC Mainnet
      |• Client ID: Fukuii/${BuildInfo.version}""".stripMargin

  def generateBlockchainInfo(): String =
    """Blockchain Information:
      |• Network: Ethereum Classic (ETC)
      |• Best Block Number: 12345678
      |• Best Block Hash: 0x1234...5678
      |• Chain ID: 61
      |• Total Difficulty: 123456789012345
      |• Genesis Hash: 0xd4e5...6789""".stripMargin

  def generateSyncStatus(): String =
    """Sync Status:
      |• Mode: Regular Sync
      |• Syncing: true
      |• Current Block: 12345678
      |• Target Block: 12345700
      |• Remaining Blocks: 22
      |• Progress: 99.98%
      |• Sync Speed: ~5 blocks/sec
      |• Estimated Time: ~4 seconds""".stripMargin

  def generatePeerList(): String =
    """Connected Peers (5):
      |
      |1. Peer: 52.12.123.45:30303
      |   • Node ID: enode://abc123...def456
      |   • Client: Geth/v1.10.26
      |   • Capabilities: eth/66, eth/67
      |   • Best Block: 12345700
      |
      |2. Peer: 104.23.45.67:30303
      |   • Node ID: enode://def789...abc012
      |   • Client: OpenEthereum/v3.3.5
      |   • Capabilities: eth/66
      |   • Best Block: 12345699
      |
      |(+ 3 more peers)""".stripMargin

  def generateNodeStatusJson(): String =
    """{
      |  "running": true,
      |  "syncing": true,
      |  "peerCount": 5,
      |  "blockNumber": 12345678,
      |  "bestKnownBlock": 12345700,
      |  "networkId": 61,
      |  "chainId": 61
      |}""".stripMargin

  def generateNodeConfigJson(): String =
    """{
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

  def generateLatestBlockJson(): String =
    s"""{
      |  "number": 12345678,
      |  "hash": "0x1234567890abcdef",
      |  "timestamp": ${System.currentTimeMillis() / 1000},
      |  "difficulty": 123456789,
      |  "gasLimit": 8000000,
      |  "gasUsed": 7500000,
      |  "transactionCount": 150
      |}""".stripMargin

  def generatePeersJson(): String =
    """{
      |  "count": 5,
      |  "peers": [
      |    {
      |      "id": "enode://abc123...def456",
      |      "address": "52.12.123.45:30303",
      |      "client": "Geth/v1.10.26",
      |      "capabilities": ["eth/66", "eth/67"],
      |      "bestBlock": 12345700
      |    }
      |  ]
      |}""".stripMargin

  def generateSyncStatusJson(): String =
    """{
      |  "syncing": true,
      |  "mode": "regular",
      |  "currentBlock": 12345678,
      |  "targetBlock": 12345700,
      |  "remainingBlocks": 22,
      |  "progress": 99.98
      |}""".stripMargin

  /**
   * Process JSON-RPC messages from stdin
   */
  val processMessages: Pipe[IO, String, String] = _.evalMap { line =>
    IO(System.err.println(s"Received: $line")) >>
    decode[JsonRpcRequest](line).fold(
      error => IO.pure(s"""{"jsonrpc":"2.0","id":null,"error":{"code":-32700,"message":"Parse error: ${error.getMessage}"}}"""),
      request => handleRequest(request).map(_.asJson.noSpaces)
    )
  }

  /**
   * Main server loop - read from stdin, process, write to stdout
   */
  override def run(args: List[String]): IO[ExitCode] =
    val stream = stdin[IO](8192)
      .through(fs2.text.utf8.decode)
      .through(fs2.text.lines)
      .filter(_.trim.nonEmpty)
      .through(processMessages)
      .intersperse("\n")
      .through(fs2.text.utf8.encode)
      .through(stdout[IO])

    IO(System.err.println("Fukuii MCP Server started")) >>
    stream.compile.drain.as(ExitCode.Success)

end FukuiiMcpServer
