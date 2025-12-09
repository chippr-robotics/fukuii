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
import com.chipprbots.ethereum.jsonrpc.mcp.{McpToolRegistry, McpResourceRegistry, McpPromptRegistry}
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
    
    // Use the modular tool registry
    val tools = McpToolRegistry.getAllTools().map { toolDef =>
      McpTool(
        name = toolDef.name,
        description = toolDef.description,
        inputSchema = ("type" -> "object") ~ ("properties" -> Map.empty[String, JValue]) ~ ("required" -> List.empty[String])
      )
    }
    
    IO.pure(Right(McpToolsListResponse(tools)))
  }
  
  def toolsCall(request: McpToolsCallRequest): ServiceResponse[McpToolsCallResponse] = {
    // Use the modular tool registry for execution
    McpToolRegistry.executeTool(request.name, peerManager, syncController).map { text =>
      Right(McpToolsCallResponse(
        content = List(McpTextContent(text = text))
      ))
    }
  }
  
  def resourcesList(request: McpResourcesListRequest): ServiceResponse[McpResourcesListResponse] = {
    // Use the modular resource registry
    val resources = McpResourceRegistry.getAllResources().map { resDef =>
      McpResource(
        uri = resDef.uri,
        name = resDef.name,
        description = resDef.description,
        mimeType = resDef.mimeType
      )
    }
    
    IO.pure(Right(McpResourcesListResponse(resources)))
  }
  
  def resourcesRead(request: McpResourcesReadRequest): ServiceResponse[McpResourcesReadResponse] = {
    // Use the modular resource registry for reading
    McpResourceRegistry.readResource(request.uri, peerManager, syncController) match {
      case Right(contentIO) =>
        contentIO.map { content =>
          Right(McpResourcesReadResponse(
            contents = List(McpResourceContents(
              uri = request.uri,
              mimeType = Some("application/json"),
              text = Some(content)
            ))
          ))
        }
      case Left(error) =>
        IO.pure(Left(JsonRpcError.InvalidParams(error)))
    }
  }
  
  def promptsList(request: McpPromptsListRequest): ServiceResponse[McpPromptsListResponse] = {
    // Use the modular prompt registry
    val prompts = McpPromptRegistry.getAllPrompts().map { promptDef =>
      McpPrompt(
        name = promptDef.name,
        description = promptDef.description,
        arguments = None
      )
    }
    
    IO.pure(Right(McpPromptsListResponse(prompts)))
  }
  
  def promptsGet(request: McpPromptsGetRequest): ServiceResponse[McpPromptsGetResponse] = {
    // Use the modular prompt registry for retrieval
    val (description, messages) = McpPromptRegistry.getPrompt(request.name)
    
    IO.pure(Right(McpPromptsGetResponse(
      description = Some(description),
      messages = messages
    )))
  }
}
