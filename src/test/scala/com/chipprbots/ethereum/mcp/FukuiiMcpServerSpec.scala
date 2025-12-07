package com.chipprbots.ethereum.mcp

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.*
import io.circe.parser.*
import io.circe.syntax.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class FukuiiMcpServerSpec extends AnyWordSpec with Matchers {

  import FukuiiMcpServer.*

  "FukuiiMcpServer" should {

    "handle initialize request" in {
      val request = JsonRpcRequest(
        id = Some(Json.fromInt(1)),
        method = "initialize",
        params = Some(Json.obj(
          "protocolVersion" -> "2024-11-05".asJson,
          "capabilities" -> Json.obj(),
          "clientInfo" -> Json.obj(
            "name" -> "TestClient".asJson,
            "version" -> "1.0.0".asJson
          )
        ))
      )

      val response = handleRequest(request).unsafeRunSync()
      
      response.id shouldBe Some(Json.fromInt(1))
      response.error shouldBe None
      response.result shouldBe defined
      
      val result = response.result.get
      result.hcursor.downField("protocolVersion").as[String] shouldBe Right("2024-11-05")
      result.hcursor.downField("serverInfo").downField("name").as[String] shouldBe 
        Right("Fukuii ETC Node MCP Server")
    }

    "handle tools/list request" in {
      val request = JsonRpcRequest(
        id = Some(Json.fromInt(2)),
        method = "tools/list",
        params = Some(Json.obj())
      )

      val response = handleRequest(request).unsafeRunSync()
      
      response.id shouldBe Some(Json.fromInt(2))
      response.error shouldBe None
      response.result shouldBe defined
      
      val tools = response.result.get.hcursor.downField("tools").as[List[Json]]
      tools.isRight shouldBe true
      tools.getOrElse(Nil).length should be > 0
      
      // Check that node_status tool exists
      val toolNames = tools.getOrElse(Nil).flatMap(_.hcursor.downField("name").as[String].toOption)
      toolNames should contain("node_status")
      toolNames should contain("node_info")
      toolNames should contain("blockchain_info")
      toolNames should contain("sync_status")
      toolNames should contain("peer_list")
    }

    "handle tools/call request for node_status" in {
      val request = JsonRpcRequest(
        id = Some(Json.fromInt(3)),
        method = "tools/call",
        params = Some(Json.obj(
          "name" -> "node_status".asJson,
          "arguments" -> Json.obj()
        ))
      )

      val response = handleRequest(request).unsafeRunSync()
      
      response.id shouldBe Some(Json.fromInt(3))
      response.error shouldBe None
      response.result shouldBe defined
      
      val content = response.result.get.hcursor.downField("content").as[List[Json]]
      content.isRight shouldBe true
      content.getOrElse(Nil).length should be > 0
      
      // Verify it contains expected text
      val text = content.getOrElse(Nil).head.hcursor.downField("text").as[String]
      text.isRight shouldBe true
      text.getOrElse("") should include("Node Status")
    }

    "handle resources/list request" in {
      val request = JsonRpcRequest(
        id = Some(Json.fromInt(4)),
        method = "resources/list",
        params = Some(Json.obj())
      )

      val response = handleRequest(request).unsafeRunSync()
      
      response.id shouldBe Some(Json.fromInt(4))
      response.error shouldBe None
      response.result shouldBe defined
      
      val resources = response.result.get.hcursor.downField("resources").as[List[Json]]
      resources.isRight shouldBe true
      resources.getOrElse(Nil).length should be > 0
      
      // Check that expected resources exist
      val resourceUris = resources.getOrElse(Nil).flatMap(_.hcursor.downField("uri").as[String].toOption)
      resourceUris should contain("fukuii://node/status")
      resourceUris should contain("fukuii://node/config")
      resourceUris should contain("fukuii://blockchain/latest")
    }

    "handle resources/read request" in {
      val request = JsonRpcRequest(
        id = Some(Json.fromInt(5)),
        method = "resources/read",
        params = Some(Json.obj(
          "uri" -> "fukuii://node/status".asJson
        ))
      )

      val response = handleRequest(request).unsafeRunSync()
      
      response.id shouldBe Some(Json.fromInt(5))
      response.error shouldBe None
      response.result shouldBe defined
      
      val contents = response.result.get.hcursor.downField("contents").as[List[Json]]
      contents.isRight shouldBe true
      contents.getOrElse(Nil).length should be > 0
      
      val text = contents.getOrElse(Nil).head.hcursor.downField("text").as[String]
      text.isRight shouldBe true
      
      // Parse the JSON content
      val jsonContent = parse(text.getOrElse("{}"))
      jsonContent.isRight shouldBe true
      jsonContent.getOrElse(Json.Null).hcursor.downField("running").as[Boolean] shouldBe Right(true)
    }

    "handle prompts/list request" in {
      val request = JsonRpcRequest(
        id = Some(Json.fromInt(6)),
        method = "prompts/list",
        params = Some(Json.obj())
      )

      val response = handleRequest(request).unsafeRunSync()
      
      response.id shouldBe Some(Json.fromInt(6))
      response.error shouldBe None
      response.result shouldBe defined
      
      val prompts = response.result.get.hcursor.downField("prompts").as[List[Json]]
      prompts.isRight shouldBe true
      prompts.getOrElse(Nil).length should be > 0
      
      // Check that expected prompts exist
      val promptNames = prompts.getOrElse(Nil).flatMap(_.hcursor.downField("name").as[String].toOption)
      promptNames should contain("node_health_check")
      promptNames should contain("sync_troubleshooting")
      promptNames should contain("peer_management")
    }

    "handle prompts/get request" in {
      val request = JsonRpcRequest(
        id = Some(Json.fromInt(7)),
        method = "prompts/get",
        params = Some(Json.obj(
          "name" -> "node_health_check".asJson,
          "arguments" -> Json.obj()
        ))
      )

      val response = handleRequest(request).unsafeRunSync()
      
      response.id shouldBe Some(Json.fromInt(7))
      response.error shouldBe None
      response.result shouldBe defined
      
      val messages = response.result.get.hcursor.downField("messages").as[List[Json]]
      messages.isRight shouldBe true
      messages.getOrElse(Nil).length should be > 0
      
      // Verify the prompt contains expected guidance
      val messageText = messages.getOrElse(Nil).head
        .hcursor.downField("content").downField("text").as[String]
      messageText.isRight shouldBe true
      messageText.getOrElse("") should include("Fukuii ETC node")
    }

    "return error for unknown method" in {
      val request = JsonRpcRequest(
        id = Some(Json.fromInt(99)),
        method = "unknown/method",
        params = Some(Json.obj())
      )

      val response = handleRequest(request).unsafeRunSync()
      
      response.id shouldBe Some(Json.fromInt(99))
      response.result shouldBe None
      response.error shouldBe defined
      
      response.error.get.code shouldBe -32601
      response.error.get.message should include("Method not found")
    }

    "return error for unknown tool" in {
      val request = JsonRpcRequest(
        id = Some(Json.fromInt(100)),
        method = "tools/call",
        params = Some(Json.obj(
          "name" -> "unknown_tool".asJson,
          "arguments" -> Json.obj()
        ))
      )

      val response = handleRequest(request).unsafeRunSync()
      
      response.id shouldBe Some(Json.fromInt(100))
      response.error shouldBe None
      response.result shouldBe defined
      
      // The result contains error text in content
      val content = response.result.get.hcursor.downField("content").as[List[Json]]
      content.isRight shouldBe true
      val text = content.getOrElse(Nil).head.hcursor.downField("text").as[String]
      text.getOrElse("") should include("Unknown tool")
    }
  }
}
