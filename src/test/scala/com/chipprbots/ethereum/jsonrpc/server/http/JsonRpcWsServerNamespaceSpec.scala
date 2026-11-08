package com.chipprbots.ethereum.jsonrpc.server.http

import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.http.scaladsl.model.ws.Message
import org.apache.pekko.http.scaladsl.model.ws.TextMessage
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.apache.pekko.stream.scaladsl.Flow
import org.apache.pekko.stream.scaladsl.Sink
import org.apache.pekko.stream.scaladsl.Source

import scala.concurrent.Await
import scala.concurrent.Promise
import scala.concurrent.duration.*

import org.json4s.*
import org.json4s.native.JsonMethods.parse
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.jsonrpc.SubscriptionManager
import com.chipprbots.ethereum.jsonrpc.server.http.JsonRpcWsServer.JsonRpcWsServerConfig
import com.chipprbots.ethereum.testing.Tags.*

/** `eth_subscribe` and `eth_unsubscribe` belong to the `eth` namespace, as in go-ethereum. The WebSocket server
  * answers them from the SubscriptionManager before the controller runs, so it checks the controller's enabled
  * namespaces itself: without `eth` the reply is -32601 and nothing reaches the SubscriptionManager.
  */
class JsonRpcWsServerNamespaceSpec extends AnyFlatSpec with Matchers with ScalatestRouteTest:

  private val wsConfig: JsonRpcWsServerConfig = new JsonRpcWsServerConfig:
    override val enabled: Boolean = true
    override val interface: String = "localhost"
    override val port: Int = 0

  /** A controller that serves the namespaces `apis` and answers no method itself. */
  private class NamespaceController(apis: Seq[String]) extends MockableJsonRpcController:
    override def enabledApis: Seq[String] = apis

  private class Fixture(apis: Seq[String]):
    val subscriptions: TestProbe[SubscriptionManager.Command] =
      TestProbe[SubscriptionManager.Command]()(using system.toTyped)
    private val server =
      new JsonRpcWsServer(new NamespaceController(apis), subscriptions.ref, wsConfig)(using system)
    private val route: Route = Route.seal(server.route)

    /** Opens a WebSocket, sends `text`, and returns the future of the first reply. The connection stays open. */
    def send(text: String): Promise[String] =
      val reply = Promise[String]()
      val client: Flow[Message, Message, ?] = Flow.fromSinkAndSource(
        Sink.foreach[Message] {
          case TextMessage.Strict(received) => reply.trySuccess(received)
          case _                            => ()
        },
        Source.single[Message](TextMessage(text)).concat(Source.maybe[Message])
      )
      WS("/", client) ~> route ~> check {
        isWebSocketUpgrade shouldBe true
      }
      subscriptions.expectMessageType[SubscriptionManager.RegisterConnection]
      reply

    def await(reply: Promise[String]): JValue = parse(Await.result(reply.future, 5.seconds))

  private val subscribe = """{"jsonrpc":"2.0","id":1,"method":"eth_subscribe","params":["newHeads"]}"""
  private val unsubscribe = """{"jsonrpc":"2.0","id":2,"method":"eth_unsubscribe","params":["0x7"]}"""

  "JsonRpcWsServer without the eth namespace" should "answer eth_subscribe with -32601 and not subscribe" taggedAs (
    UnitTest,
    RPCTest
  ) in new Fixture(Seq("net", "web3", "rpc")):
    val reply: JValue = await(send(subscribe))
    reply \ "error" \ "code" shouldBe JInt(-32601)
    reply \ "error" \ "message" shouldBe JString("Method not found")
    reply \ "id" shouldBe JInt(1)
    // the reply is already here, so a Subscribe that was sent would be too
    subscriptions.expectNoMessage(100.millis)

  it should "answer eth_unsubscribe with -32601 and not unsubscribe" taggedAs (UnitTest, RPCTest) in new Fixture(
    Seq("net", "web3", "rpc")
  ):
    val reply: JValue = await(send(unsubscribe))
    reply \ "error" \ "code" shouldBe JInt(-32601)
    reply \ "id" shouldBe JInt(2)
    subscriptions.expectNoMessage(100.millis)

  it should "answer both with -32601 when the namespace list is empty" taggedAs (UnitTest, RPCTest) in new Fixture(
    Seq.empty
  ):
    await(send(subscribe)) \ "error" \ "code" shouldBe JInt(-32601)
    await(send(unsubscribe)) \ "error" \ "code" shouldBe JInt(-32601)

  "JsonRpcWsServer with the eth namespace" should "subscribe through the SubscriptionManager" taggedAs (
    UnitTest,
    RPCTest
  ) in new Fixture(Seq("eth", "rpc")):
    val reply: Promise[String] = send(subscribe)
    val request: SubscriptionManager.Subscribe = subscriptions.expectMessageType[SubscriptionManager.Subscribe]
    request.subType shouldBe "newHeads"
    request.replyTo ! SubscriptionManager.SubscribeResponse(Right(7L))
    val json: JValue = await(reply)
    json \ "result" shouldBe JString("0x7")
    json \ "error" shouldBe JNothing

  it should "unsubscribe through the SubscriptionManager" taggedAs (UnitTest, RPCTest) in new Fixture(
    Seq("eth", "rpc")
  ):
    val reply: Promise[String] = send(unsubscribe)
    val request: SubscriptionManager.Unsubscribe = subscriptions.expectMessageType[SubscriptionManager.Unsubscribe]
    request.subId shouldBe 7L
    request.replyTo ! SubscriptionManager.UnsubscribeResponse(true)
    val json: JValue = await(reply)
    json \ "result" shouldBe JBool(true)
    json \ "error" shouldBe JNothing
