package com.chipprbots.ethereum.jsonrpc.server.controllers

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import scala.concurrent.ExecutionContext

import org.json4s.JInt
import org.json4s.JString
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.jsonrpc.JsonRpcRequest
import com.chipprbots.ethereum.jsonrpc.JsonRpcResponse
import com.chipprbots.ethereum.jsonrpc.ShippedRpcConfig
import com.chipprbots.ethereum.jsonrpc.server.controllers.JsonRpcBaseController.JsonRpcConfig
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.Logger

/** Which namespace answers a method that two enabled namespaces both claim. The node has one such method,
  * `miner_setEtherbase`, answered by `miner` (the node's mining) and by `test` (the retesteth harness); `test` is a
  * tie-breaker, so it owns the method whenever it is enabled, whatever the order of the `apis` list. The stub below
  * claims the same methods the same way, so the rule is checked without a node.
  */
class JsonRpcDispatchOrderSpec extends AnyFlatSpec with Matchers:

  implicit val runtime: IORuntime = IORuntime.global

  private class StubController(enabled: Seq[String], override val tieBreakerApis: Set[String] = Set.empty)
      extends JsonRpcBaseController
      with ApisBase
      with Logger:

    override val config: JsonRpcConfig = ShippedRpcConfig.jsonRpcConfig()
    implicit override def executionContext: ExecutionContext = ExecutionContext.global
    override def available: List[String] = List("eth", "miner", "test")
    override def enabledApis: Seq[String] = enabled

    private def answers(
        namespace: String,
        methods: Set[String]
    ): PartialFunction[JsonRpcRequest, IO[JsonRpcResponse]] = {
      case req if methods.contains(req.method) =>
        IO.pure(JsonRpcResponse("2.0", Some(JString(namespace)), None, JInt(1)))
    }

    override def apisHandleFns: Map[String, PartialFunction[JsonRpcRequest, IO[JsonRpcResponse]]] = Map(
      "eth" -> answers("eth", Set("eth_chainId")),
      "miner" -> answers("miner", Set("miner_start", "miner_setEtherbase")),
      "test" -> answers("test", Set("test_mineBlocks", "miner_setEtherbase"))
    )

  /** The namespace that answered, or None when the method was not found. */
  private def ownerOf(controller: StubController, method: String): Option[String] =
    controller.handleRequest(JsonRpcRequest("2.0", method, None, Some(JInt(1)))).unsafeRunSync() match
      case JsonRpcResponse(_, Some(JString(owner)), None, _) => Some(owner)
      case _                                                 => None

  "Without a tie-breaker, the namespace listed last" should "answer a method two namespaces claim" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    ownerOf(new StubController(Seq("eth", "miner", "test")), "miner_setEtherbase") shouldBe Some("test")
    ownerOf(new StubController(Seq("eth", "test", "miner")), "miner_setEtherbase") shouldBe Some("miner")
  }

  "A tie-breaker namespace" should "answer a method two namespaces claim, whatever the order of the list" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    Seq(
      Seq("eth", "miner", "test"),
      Seq("eth", "test", "miner"),
      Seq("test", "miner", "eth"),
      Seq("miner", "test"),
      Seq("test", "miner")
    ).foreach { enabled =>
      withClue(enabled.mkString(",") + ": ") {
        ownerOf(new StubController(enabled, Set("test")), "miner_setEtherbase") shouldBe Some("test")
      }
    }
  }

  it should "leave the methods only one namespace claims with that namespace" taggedAs (UnitTest, RPCTest) in {
    val both = new StubController(Seq("eth", "miner", "test"), Set("test"))
    ownerOf(both, "miner_start") shouldBe Some("miner")
    ownerOf(both, "test_mineBlocks") shouldBe Some("test")
    ownerOf(both, "eth_chainId") shouldBe Some("eth")
    ownerOf(both, "nosuch_method") shouldBe None
  }

  it should "not matter when the namespace is not enabled" taggedAs (UnitTest, RPCTest) in {
    val minerOnly = new StubController(Seq("eth", "miner"), Set("test"))
    ownerOf(minerOnly, "miner_setEtherbase") shouldBe Some("miner")
    ownerOf(minerOnly, "test_mineBlocks") shouldBe None

    val testOnly = new StubController(Seq("eth", "test"), Set("test"))
    ownerOf(testOnly, "miner_setEtherbase") shouldBe Some("test")
    ownerOf(testOnly, "miner_start") shouldBe None
  }
