package com.chipprbots.ethereum.jsonrpc.server.http

import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLSession

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
import org.apache.pekko.stream.scaladsl.SinkQueueWithCancel
import org.apache.pekko.stream.scaladsl.Source

import scala.concurrent.Await
import scala.concurrent.duration.*

import com.typesafe.config.ConfigFactory
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

import IdentityTestSupport.*

/** Spec 030 FR-026: on WSS the identity is resolved once, at the upgrade, and serves every message of the connection:
  * an `ops` client is granted `admin` beyond the transport's apis for each call; an anonymous one is refused it and its
  * `eth_subscribe` follows the transport's set.
  */
class IdentityRulesWsSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  given system: ActorSystem = ActorSystem("identity-rules-ws-spec")

  private val dir = TlsTestSupport.tempDir()
  private val pki = new TestPki(dir)
  private val serverStore = pki.keystore("server", Seq("rpc" -> pki.leaf()), "srv-pw")
  private val trust = pki.truststore("trust", Seq(pki.ca.cert), "trust-pw")
  private val Wait = 10.seconds

  override protected def afterAll(): Unit =
    Await.result(system.terminate(), Wait)
    TlsTestSupport.deleteTree(dir)

  private val rules = IdentityRules
    .parse(
      ConfigFactory.parseString(
        """network.rpc.client-identities = [
          |  { name = "ops", match { san-dns = "ops.internal" }, apis = "eth,net,web3,admin" }
          |]""".stripMargin
      ),
      TlsStartupCheck.KnownApis
    )
    .fold(e => fail(e.message), identity)

  private val wsConfig: JsonRpcWsServerConfig = new JsonRpcWsServerConfig:
    override val enabled: Boolean = true
    override val interface: String = "127.0.0.1"
    override val port: Int = 0
    override val tlsEnabled: Boolean = true

  private val tlsConfig = TlsTestSupport.config(
    TlsTestSupport.profile("rpc", serverStore, Some(trust)),
    ws = """enabled = true, tls { enabled = true, profile = "rpc", client-auth = "want" }"""
  )

  /** Counts resolutions: one per connection, however many messages it carries. */
  private val resolutions = new AtomicInteger(0)

  private def withServer[A](body: Int => A): A =
    val subscriptions = TestProbe[SubscriptionManager.Command]()(using system.toTyped)
    val inner =
      RpcCallers(rules, Seq("net", "web3"), (apis, label) => new NamespaceEchoController(apis, Some(label), "ws"), true)
    val counting = new RpcCallers:
      override def rules: IdentityRules = inner.rules
      override def forSession(session: Option[SSLSession]): RpcCaller =
        resolutions.incrementAndGet()
        inner.forSession(session)
    val server = new JsonRpcWsServer(
      new NamespaceEchoController(Seq("net", "web3"), None, "ws"),
      subscriptions.ref,
      wsConfig,
      () => TlsServerEngine.rpcWs(tlsConfig, new SecureRandom()),
      Some(counting)
    ):
      override protected def callStats: RpcCallStats = new RpcCallStats
    val binding = Await.result(server.lifecycle.start(), Wait)
    try body(binding.localAddress.getPort)
    finally Await.result(server.lifecycle.stop(), Wait)

  private def connect(ctx: javax.net.ssl.SSLContext, port: Int) =
    val (outbound, source) = Source.queue[Message](16, OverflowStrategy.fail).preMaterialize()
    val (received, sink) = Sink.queue[Message]().preMaterialize()
    val (upgrade, _) = Http().singleWebSocketRequest(
      WebSocketRequest(s"wss://localhost:$port/"),
      Flow.fromSinkAndSourceMat(sink, source)(Keep.none),
      ConnectionContext.httpsClient(ctx)
    )
    Await.result(upgrade, Wait).response.status.intValue shouldBe 101
    (outbound, received)

  private def call(
      outbound: org.apache.pekko.stream.scaladsl.SourceQueueWithComplete[Message],
      received: SinkQueueWithCancel[Message],
      method: String
  ): JValue =
    outbound.offer(TextMessage(request(method)))
    Await.result(received.pull(), Wait) match
      case Some(TextMessage.Strict(text)) => parse(text)
      case other                          => fail(s"expected a text message, got $other")

  "A WSS connection" should "keep the identity of its upgrade for every message" taggedAs (UnitTest, RPCTest) in {
    withServer { port =>
      val before = resolutions.get()
      val (out, in) = connect(pki.clientContext(presenting = Some(pki.leaf("ops", sanDns = Seq("ops.internal")))), port)
      (1 to 3).foreach(_ => (call(out, in, "admin_peers") \ "result") shouldBe JString("admin_peers"))
      modules(call(out, in, "rpc_modules")) shouldBe Set("eth", "net", "web3", "admin", "rpc")
      resolutions.get() shouldBe before + 1
      out.complete()
    }
  }

  it should "serve an anonymous caller the transport's set, eth_subscribe included" taggedAs (UnitTest, RPCTest) in {
    withServer { port =>
      val (out, in) = connect(pki.clientContext(), port)
      errorCode(call(out, in, "admin_peers")) shouldBe Some(BigInt(-32601))
      // the transport here serves no eth, so eth_subscribe is refused as on any WS endpoint without eth
      errorCode(call(out, in, "eth_subscribe")) shouldBe Some(BigInt(-32601))
      (call(out, in, "net_version") \ "result") shouldBe JString("net_version")
      out.complete()
    }
  }
