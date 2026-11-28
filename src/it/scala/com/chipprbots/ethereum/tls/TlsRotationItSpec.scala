package com.chipprbots.ethereum.tls

import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLSocket

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.http.cors.scaladsl.model.HttpOriginMatcher
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

import cats.effect.IO

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration.*

import org.json4s.*
import org.json4s.native.JsonMethods.parse
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.Seconds
import org.scalatest.time.Span

import com.chipprbots.ethereum.healthcheck.HealthcheckResponse
import com.chipprbots.ethereum.jsonrpc.JsonRpcHealthChecker
import com.chipprbots.ethereum.jsonrpc.RpcCallStats
import com.chipprbots.ethereum.jsonrpc.SubscriptionManager
import com.chipprbots.ethereum.jsonrpc.server.http.IdentityTestSupport
import com.chipprbots.ethereum.jsonrpc.server.http.JsonRpcHttpServer.JsonRpcHttpServerConfig
import com.chipprbots.ethereum.jsonrpc.server.http.JsonRpcHttpServer.RateLimitConfig
import com.chipprbots.ethereum.jsonrpc.server.http.JsonRpcWsServer
import com.chipprbots.ethereum.jsonrpc.server.http.JsonRpcWsServer.JsonRpcWsServerConfig
import com.chipprbots.ethereum.jsonrpc.server.http.NamespaceEchoController
import com.chipprbots.ethereum.jsonrpc.server.http.RpcCallers
import com.chipprbots.ethereum.jsonrpc.server.http.SecureJsonRpcHttpServer
import com.chipprbots.ethereum.testing.Tags.*

/** Spec 030 SC-003, FR-030, FR-033, on the node's own reload loop (scheduled, one second):
  *   - certificate A is replaced by B on disk while a keep-alive connection sends requests back to back: new handshakes
  *     see B within the interval, and not one request on the open connection fails;
  *   - the identity-rules file is replaced: new HTTP requests take the new grant, a WebSocket opened before keeps the
  *     identity of its upgrade, and one opened after takes the new one.
  */
class TlsRotationItSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll with Eventually:

  given system: ActorSystem = ActorSystem("tls-rotation-it")
  implicit override val patienceConfig: PatienceConfig = PatienceConfig(timeout = Span(15, Seconds))

  private val dir = TlsTestSupport.tempDir("tls-rotation")
  private val pki = new TestPki(dir)
  private val trust = pki.truststore("trust", Seq(pki.ca.cert), "trust-pw")

  override protected def afterAll(): Unit =
    Await.result(system.terminate(), 10.seconds)
    TlsTestSupport.deleteTree(dir)

  private def profile(name: String, store: TestPki#Files_) =
    TlsTestSupport.profile(name, store, Some(trust), "reload.interval = 1.second")

  "Certificate rotation" should "serve B to new handshakes within the interval and fail no request on an open connection" taggedAs (
    IntegrationTest,
    RPCTest
  ) in {
    val store = pki.keystore("rotate-server", Seq("rpc" -> pki.leaf("node-a")), "pw")
    val config = TlsTestSupport.config(profile("rotate", store), """tls { enabled = true, profile = "rotate" }""")
    val ctx = TlsServerEngine.rpcHttp(config, new SecureRandom()).fold(e => fail(e.message), identity)
    val reloader = new TlsReloader()
    reloader.register("rpc-http", ctx)
    try
      MtlsTestSupport.withServerContext(ctx) { port =>
        val socket = pki.clientContext().getSocketFactory.createSocket("localhost", port).asInstanceOf[SSLSocket]
        socket.setSoTimeout(10000)
        val in = new BufferedReader(new InputStreamReader(socket.getInputStream, StandardCharsets.US_ASCII))
        val stop = new AtomicBoolean(false)
        val ok = new AtomicInteger(0)
        val failed = new AtomicInteger(0)
        val loop = Future {
          while !stop.get do
            try
              socket.getOutputStream.write(
                "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.US_ASCII)
              )
              socket.getOutputStream.flush()
              val status = in.readLine()
              Iterator.continually(in.readLine()).takeWhile(l => l != null && l.nonEmpty).foreach(_ => ())
              in.read(new Array[Char](2))
              if status != null && status.startsWith("HTTP/1.1 200") then ok.incrementAndGet()
              else failed.incrementAndGet()
            catch case _: java.io.IOException => failed.incrementAndGet()
        }(using system.dispatcher)
        try
          eventually(ok.get should be > 10)
          pki.keystore("rotate-server", Seq("rpc" -> pki.leaf("node-b")), "pw")
          eventually(MtlsTestSupport.serverSubject(pki.clientContext(), port) shouldBe "CN=node-b")
          val atSwap = ok.get
          eventually(ok.get should be > atSwap + 10)
        finally
          stop.set(true)
          Await.result(loop, 10.seconds)
          socket.close()
        failed.get shouldBe 0
      }
    finally reloader.shutdown()
  }

  "An identity-rules file change" should "apply to new HTTP requests and new WebSockets, not to an open WebSocket" taggedAs (
    IntegrationTest,
    RPCTest
  ) in {
    val store = pki.keystore("rules-server", Seq("rpc" -> pki.leaf("node")), "pw")
    val tlsConfig = TlsTestSupport.config(
      profile("rules", store),
      """tls { enabled = true, profile = "rules", client-auth = "want" }""",
      """enabled = true, tls { enabled = true, profile = "rules", client-auth = "want" }"""
    )
    val file = dir.resolve("identities.conf")
    def write(apis: String): Unit =
      Files.writeString(
        file,
        s"""client-identities = [ { name = "ops", match { san-dns = "ops.internal" }, apis = "$apis" } ]"""
      )
    write("eth")
    val config = com.typesafe.config.ConfigFactory
      .parseString(s"""network.rpc.client-identities-file = "$file"""")
      .withFallback(tlsConfig)
    def parseRules() = IdentityRules.parse(config, TlsStartupCheck.KnownApis)
    val transport = Seq("net")
    val httpCallers = RpcCallers(
      parseRules().fold(e => fail(e.message), identity),
      transport,
      (apis, label) => new NamespaceEchoController(apis, Some(label)),
      true
    )
    val wsCallers = RpcCallers(
      parseRules().fold(e => fail(e.message), identity),
      transport,
      (apis, label) => new NamespaceEchoController(apis, Some(label), "ws"),
      true
    )
    val reloader = new TlsReloader()
    reloader.registerRules(
      file,
      1.second,
      () => parseRules(),
      r =>
        httpCallers.update(r); wsCallers.update(r)
    )

    val ops = pki.clientContext(presenting = Some(pki.leaf("ops", sanDns = Seq("ops.internal"))))
    val http = new SecureJsonRpcHttpServer(
      new NamespaceEchoController(transport, None),
      healthChecker,
      httpConfig,
      () => TlsServerEngine.rpcHttp(tlsConfig, new SecureRandom()),
      None,
      Some(httpCallers)
    ):
      override protected def callStats: RpcCallStats = new RpcCallStats
    val ws = new JsonRpcWsServer(
      new NamespaceEchoController(transport, None, "ws"),
      TestProbe[SubscriptionManager.Command]()(using system.toTyped).ref,
      wsConfig,
      () => TlsServerEngine.rpcWs(tlsConfig, new SecureRandom()),
      Some(wsCallers)
    ):
      override protected def callStats: RpcCallStats = new RpcCallStats
    val httpPort = Await.result(http.run(), 10.seconds).localAddress.getPort
    val wsPort = Await.result(ws.lifecycle.start(), 10.seconds).localAddress.getPort
    try
      def httpAdmin() = IdentityTestSupport.errorCode(
        IdentityTestSupport.post(ops, httpPort, IdentityTestSupport.request("admin_peers"))._2
      )
      httpAdmin() shouldBe Some(BigInt(-32601))
      val before = connect(ops, wsPort)
      before.call("admin_peers") shouldBe Some(BigInt(-32601))

      write("eth,admin")
      eventually(httpAdmin() shouldBe None)

      // opened before the change: the identity of its upgrade
      before.call("admin_peers") shouldBe Some(BigInt(-32601))
      // opened after: the new grant
      connect(ops, wsPort).call("admin_peers") shouldBe None

      // a change that does not validate keeps the rules in service
      val failures = TlsReloadMetrics.failures(TlsReloader.RulesLabel).count()
      Files.writeString(file, "client-identities = [ {")
      eventually(TlsReloadMetrics.failures(TlsReloader.RulesLabel).count() shouldBe failures + 1)
      httpAdmin() shouldBe None
    finally
      reloader.shutdown()
      Await.result(http.lifecycle.stop(), 10.seconds)
      Await.result(ws.lifecycle.stop(), 10.seconds)
  }

  private val healthChecker: JsonRpcHealthChecker = new JsonRpcHealthChecker:
    override def healthCheck: IO[HealthcheckResponse] = IO.pure(HealthcheckResponse(Nil))
    override def readinessCheck(): IO[HealthcheckResponse] = IO.pure(HealthcheckResponse(Nil))

  private val httpConfig: JsonRpcHttpServerConfig = new JsonRpcHttpServerConfig:
    override val mode: String = "http"
    override val tlsBlockEnabled: Boolean = true
    override val enabled: Boolean = true
    override val interface: String = "127.0.0.1"
    override val port: Int = 0
    override val corsAllowedOrigins: HttpOriginMatcher = HttpOriginMatcher.*
    override val rateLimit: RateLimitConfig = new RateLimitConfig:
      override val enabled: Boolean = false
      override val minRequestInterval: FiniteDuration = 20.millis
      override val latestTimestampCacheSize: Int = 1024

  private val wsConfig: JsonRpcWsServerConfig = new JsonRpcWsServerConfig:
    override val enabled: Boolean = true
    override val interface: String = "127.0.0.1"
    override val port: Int = 0
    override val tlsEnabled: Boolean = true

  /** An open WebSocket that answers one call at a time with the call's error code, if any. */
  final private class WsClient(
      outbound: org.apache.pekko.stream.scaladsl.SourceQueueWithComplete[Message],
      received: org.apache.pekko.stream.scaladsl.SinkQueueWithCancel[Message]
  ):
    def call(method: String): Option[BigInt] =
      outbound.offer(TextMessage(IdentityTestSupport.request(method)))
      Await.result(received.pull(), 10.seconds) match
        case Some(TextMessage.Strict(text)) => IdentityTestSupport.errorCode(parse(text))
        case other                          => fail(s"expected a text message, got $other")

  private def connect(ctx: javax.net.ssl.SSLContext, port: Int): WsClient =
    val (outbound, source) = Source.queue[Message](16, OverflowStrategy.fail).preMaterialize()
    val (received, sink) = Sink.queue[Message]().preMaterialize()
    val (upgrade, _) = Http().singleWebSocketRequest(
      WebSocketRequest(s"wss://localhost:$port/"),
      Flow.fromSinkAndSourceMat(sink, source)(Keep.none),
      ConnectionContext.httpsClient(ctx)
    )
    Await.result(upgrade, 10.seconds).response.status.intValue shouldBe 101
    new WsClient(outbound, received)
