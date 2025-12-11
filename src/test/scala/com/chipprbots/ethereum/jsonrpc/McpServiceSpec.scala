package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.{TestKit, TestProbe}
import org.apache.pekko.util.Timeout

import cats.effect.unsafe.implicits.global

import scala.concurrent.duration._

import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import com.chipprbots.ethereum.jsonrpc.McpService._

class McpServiceSpec
    extends TestKit(ActorSystem("McpServiceSpec"))
    with AnyWordSpecLike
    with Matchers
    with BeforeAndAfterAll {

  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
  }

  implicit val timeout: Timeout = Timeout(3.seconds)
  implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global

  val peerManagerProbe = TestProbe()
  val syncControllerProbe = TestProbe()

  val service = new McpService(peerManagerProbe.ref, syncControllerProbe.ref)

  "McpService" should {

    "initialize with correct protocol version and server info" in {
      val request = McpInitializeRequest(None)
      val response = service.initialize(request).unsafeRunSync()

      response.isRight shouldBe true
      val result = response.getOrElse(throw new Exception("Expected Right"))
      result.protocolVersion shouldBe "2024-11-05"
      result.serverInfo.name shouldBe "Fukuii ETC Node MCP Server"
      result.capabilities.tools shouldBe defined
      result.capabilities.resources shouldBe defined
      result.capabilities.prompts shouldBe defined
    }

    "list all available tools" in {
      val request = McpToolsListRequest()
      val response = service.toolsList(request).unsafeRunSync()

      response.isRight shouldBe true
      val result = response.getOrElse(throw new Exception("Expected Right"))
      result.tools.length should be >= 5
      result.tools.map(_.name) should contain allOf (
        "mcp_node_status",
        "mcp_node_info",
        "mcp_blockchain_info",
        "mcp_sync_status",
        "mcp_peer_list"
      )
    }

    "execute node_info tool successfully" in {
      val request = McpToolsCallRequest("mcp_node_info", None)
      val response = service.toolsCall(request).unsafeRunSync()

      response.isRight shouldBe true
      val result = response.getOrElse(throw new Exception("Expected Right"))
      result.content should not be empty
      result.content.head.text should include("Fukuii Node Information")
      result.isError shouldBe None
    }

    "return error for unknown tool" in {
      val request = McpToolsCallRequest("unknown_tool", None)
      val response = service.toolsCall(request).unsafeRunSync()

      response.isRight shouldBe true
      val result = response.getOrElse(throw new Exception("Expected Right"))
      result.content.head.text should include("Unknown tool")
    }

    "list all available resources" in {
      val request = McpResourcesListRequest()
      val response = service.resourcesList(request).unsafeRunSync()

      response.isRight shouldBe true
      val result = response.getOrElse(throw new Exception("Expected Right"))
      result.resources.length should be >= 5
      result.resources.map(_.uri) should contain allOf (
        "fukuii://node/status",
        "fukuii://node/config",
        "fukuii://blockchain/latest",
        "fukuii://peers/connected",
        "fukuii://sync/status"
      )
    }

    "read node config resource successfully" in {
      val request = McpResourcesReadRequest("fukuii://node/config")
      val response = service.resourcesRead(request).unsafeRunSync()

      response.isRight shouldBe true
      val result = response.getOrElse(throw new Exception("Expected Right"))
      result.contents should not be empty
      result.contents.head.uri shouldBe "fukuii://node/config"
      result.contents.head.mimeType shouldBe Some("application/json")
      result.contents.head.text shouldBe defined
    }

    "return error for unknown resource URI" in {
      val request = McpResourcesReadRequest("fukuii://unknown/resource")
      val response = service.resourcesRead(request).unsafeRunSync()

      response.isLeft shouldBe true
    }

    "list all available prompts" in {
      val request = McpPromptsListRequest()
      val response = service.promptsList(request).unsafeRunSync()

      response.isRight shouldBe true
      val result = response.getOrElse(throw new Exception("Expected Right"))
      result.prompts.length should be >= 3
      result.prompts.map(_.name) should contain allOf (
        "mcp_node_health_check",
        "mcp_sync_troubleshooting",
        "mcp_peer_management"
      )
    }

    "get node health check prompt successfully" in {
      val request = McpPromptsGetRequest("mcp_node_health_check", None)
      val response = service.promptsGet(request).unsafeRunSync()

      response.isRight shouldBe true
      val result = response.getOrElse(throw new Exception("Expected Right"))
      result.description shouldBe defined
      result.messages should not be empty
    }

    "return error for unknown prompt" in {
      val request = McpPromptsGetRequest("unknown_prompt", None)
      val response = service.promptsGet(request).unsafeRunSync()

      response.isRight shouldBe true
      val result = response.getOrElse(throw new Exception("Expected Right"))
      result.description shouldBe defined
      result.description.get should include("Unknown prompt")
    }
  }
}
