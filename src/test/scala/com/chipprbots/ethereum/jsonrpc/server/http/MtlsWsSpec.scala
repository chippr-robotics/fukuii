package com.chipprbots.ethereum.jsonrpc.server.http

import java.security.SecureRandom

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.http.scaladsl.ConnectionContext
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.ws.Message
import org.apache.pekko.http.scaladsl.model.ws.TextMessage
import org.apache.pekko.http.scaladsl.model.ws.WebSocketRequest
import org.apache.pekko.stream.OverflowStrategy
import org.apache.pekko.stream.scaladsl.Flow
import org.apache.pekko.stream.scaladsl.Keep
import org.apache.pekko.stream.scaladsl.Sink
import org.apache.pekko.stream.scaladsl.Source

import scala.concurrent.Await
import scala.concurrent.duration.*

import org.json4s.*
import org.json4s.native.JsonMethods.parse
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.jsonrpc.RpcCallStats
import com.chipprbots.ethereum.jsonrpc.SubscriptionManager
import com.chipprbots.ethereum.jsonrpc.server.http.JsonRpcWsServer.JsonRpcWsServerConfig
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.tls.*

/** Spec 030 US2 scenario 3, FR-014: the WebSocket listener over TLS with `client-auth = need`. A trusted client
  * subscribes and keeps receiving on the same connection; a client without a certificate never upgrades.
  */
class MtlsWsSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  given system: ActorSystem = ActorSystem("mtls-ws-spec")

  private val dir = TlsTestSupport.tempDir()
  private val pki = new TestPki(dir)
  private val serverStore = pki.keystore("server", Seq("rpc" -> pki.leaf()), "srv-pw")
  private val trust = pki.truststore("trust", Seq(pki.ca.cert), "trust-pw")
  private val Wait = 10.seconds

  override protected def afterAll(): Unit =
    Await.result(system.terminate(), Wait)
    TlsTestSupport.deleteTree(dir)

  private val wsConfig: JsonRpcWsServerConfig = new JsonRpcWsServerConfig:
    override val enabled: Boolean = true
    override val interface: String = "127.0.0.1"
    override val port: Int = 0
    override val tlsEnabled: Boolean = true

  private val tlsConfig = TlsTestSupport.config(
    TlsTestSupport.profile("rpc", serverStore, Some(trust)),
    ws = """enabled = true, tls { enabled = true, profile = "rpc", client-auth = "need" }"""
  )

  private class EthController extends MockableJsonRpcController:
    override def enabledApis: Seq[String] = Seq("eth", "rpc")

  private def withServer[A](body: (Int, TestProbe[SubscriptionManager.Command]) => A): A =
    val subscriptions = TestProbe[SubscriptionManager.Command]()(using system.toTyped)
    val server = new JsonRpcWsServer(
      new EthController,
      subscriptions.ref,
      wsConfig,
      () => TlsServerEngine.rpcWs(tlsConfig, new SecureRandom())
    ):
      override protected def callStats: RpcCallStats = new RpcCallStats
    val binding = Await.result(server.lifecycle.start(), Wait)
    try body(binding.localAddress.getPort, subscriptions)
    finally Await.result(server.lifecycle.stop(), Wait)

  /** A client connection: what it sends, what it receives, and how its upgrade ended. */
  private def connect(ctx: javax.net.ssl.SSLContext, port: Int) =
    val (outbound, source) = Source.queue[Message](16, OverflowStrategy.fail).preMaterialize()
    val (received, sink) = Sink.queue[Message]().preMaterialize()
    val (upgrade, _) = Http().singleWebSocketRequest(
      WebSocketRequest(s"wss://localhost:$port/"),
      Flow.fromSinkAndSourceMat(sink, source)(Keep.none),
      ConnectionContext.httpsClient(ctx)
    )
    (outbound, received, upgrade)

  private def next(received: org.apache.pekko.stream.scaladsl.SinkQueueWithCancel[Message]): JValue =
    Await.result(received.pull(), Wait) match
      case Some(TextMessage.Strict(text)) => parse(text)
      case other                          => fail(s"expected a text message, got $other")

  "A WSS listener with client-auth = need" should "let a trusted client subscribe and keep receiving on the connection" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    withServer { (port, subscriptions) =>
      val (outbound, received, upgrade) = connect(pki.clientContext(presenting = Some(pki.leaf("dapp"))), port)
      Await.result(upgrade, Wait).response.status.intValue shouldBe 101
      val registered = subscriptions.expectMessageType[SubscriptionManager.RegisterConnection]

      outbound.offer(TextMessage("""{"jsonrpc":"2.0","id":1,"method":"eth_subscribe","params":["newHeads"]}"""))
      val request = subscriptions.expectMessageType[SubscriptionManager.Subscribe]
      request.connId shouldBe registered.connId
      request.replyTo ! SubscriptionManager.SubscribeResponse(Right(7L))
      next(received) \ "result" shouldBe JString("0x7")

      // notifications for the life of the connection, on the session the handshake established
      (1 to 3).foreach { n =>
        registered.queue.offer(s"""{"jsonrpc":"2.0","method":"eth_subscription","params":{"subscription":"0x7","result":$n}}""")
        next(received) \ "params" \ "result" shouldBe JInt(n)
      }
      outbound.complete()
    }
  }

  it should "never upgrade a client without a certificate, and never register a connection" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    withServer { (port, subscriptions) =>
      val (_, _, upgrade) = connect(pki.clientContext(), port)
      an[Exception] should be thrownBy Await.result(upgrade, Wait)
      subscriptions.expectNoMessage(200.millis)
    }
  }

  it should "never upgrade a client whose certificate another CA issued" taggedAs (UnitTest, RPCTest) in {
    withServer { (port, subscriptions) =>
      val other = pki.clientContext(presenting = Some(pki.leaf("intruder", signedBy = pki.otherCa())))
      val (_, _, upgrade) = connect(other, port)
      an[Exception] should be thrownBy Await.result(upgrade, Wait)
      subscriptions.expectNoMessage(200.millis)
    }
  }

  "A WS listener whose TLS material cannot load" should "fail its bind, never listen in plaintext" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val subscriptions = TestProbe[SubscriptionManager.Command]()(using system.toTyped)
    val broken = new JsonRpcWsServer(
      new EthController,
      subscriptions.ref,
      wsConfig,
      () => Left(TlsConfigError("fukuii.tls.profiles.rpc.keystore.path: /nope.p12 not found"))
    ):
      override protected def callStats: RpcCallStats = new RpcCallStats
    val failure = intercept[IllegalStateException](Await.result(broken.lifecycle.start(), Wait))
    failure.getMessage should include("/nope.p12 not found")
    broken.lifecycle.isBound shouldBe false
  }
