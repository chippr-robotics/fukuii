package com.chipprbots.ethereum.jsonrpc.server.http

import java.security.SecureRandom

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.cors.scaladsl.model.HttpOriginMatcher

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*

import com.typesafe.config.ConfigFactory
import org.json4s.*
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.healthcheck.HealthcheckResponse
import com.chipprbots.ethereum.jsonrpc.JsonRpcHealthChecker
import com.chipprbots.ethereum.jsonrpc.RpcCallStats
import com.chipprbots.ethereum.jsonrpc.RpcNamespaceDenials
import com.chipprbots.ethereum.jsonrpc.graphql.GraphQLService
import com.chipprbots.ethereum.jsonrpc.server.http.JsonRpcHttpServer.JsonRpcHttpServerConfig
import com.chipprbots.ethereum.jsonrpc.server.http.JsonRpcHttpServer.RateLimitConfig
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.tls.*

import IdentityTestSupport.*

/** Spec 030 US3 scenarios 1–5 over HTTPS (FR-021–FR-025, FR-028): a listener with `client-auth = want` and the
  * transport's `apis = eth,net,web3`, with two rules: `monitor` (eth, net, web3) and `ops` (eth, net, web3, admin,
  * graphql).
  */
class IdentityRulesRouteSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  given system: ActorSystem = ActorSystem("identity-rules-route-spec")

  private val dir = TlsTestSupport.tempDir()
  private val pki = new TestPki(dir)
  private val serverStore = pki.keystore("server", Seq("rpc" -> pki.leaf()), "srv-pw")
  private val trust = pki.truststore("trust", Seq(pki.ca.cert), "trust-pw")
  private val Wait = 10.seconds

  override protected def afterAll(): Unit =
    Await.result(system.terminate(), Wait)
    TlsTestSupport.deleteTree(dir)

  private val Transport = Seq("eth", "net", "web3")

  private val rules = IdentityRules
    .parse(
      ConfigFactory.parseString(
        """network.rpc.client-identities = [
          |  { name = "monitor", match { san-dns = "monitor.internal" }, apis = "eth,net,web3" },
          |  { name = "ops", match { san-dns = "ops.internal" }, apis = "eth,net,web3,admin,graphql" }
          |]""".stripMargin
      ),
      TlsStartupCheck.KnownApis
    )
    .fold(e => fail(e.message), identity)

  private val monitor = pki.clientContext(presenting = Some(pki.leaf("monitor", sanDns = Seq("monitor.internal"))))
  private val ops = pki.clientContext(presenting = Some(pki.leaf("ops", sanDns = Seq("ops.internal"))))
  private val unmatched = pki.clientContext(presenting = Some(pki.leaf("someone", sanDns = Seq("someone.internal"))))
  private val anonymous = pki.clientContext()

  private val serverConfig: JsonRpcHttpServerConfig = new JsonRpcHttpServerConfig:
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

  private val healthChecker: JsonRpcHealthChecker = new JsonRpcHealthChecker:
    override def healthCheck: IO[HealthcheckResponse] = IO.pure(HealthcheckResponse(Nil))
    override def readinessCheck(): IO[HealthcheckResponse] = IO.pure(HealthcheckResponse(Nil))

  // A GraphQL service whose context is never read: every body these tests post fails to parse before execution, so a
  // 400 says the request reached the service and a 404 says it was answered as GraphQL-off.
  @SuppressWarnings(Array("scalafix:DisableSyntax.null"))
  private val graphQl: GraphQLService =
    given ExecutionContext = system.dispatcher
    given IORuntime = IORuntime.global
    new GraphQLService(null)

  private def withServer[A](body: Int => A): A =
    val config = TlsTestSupport.config(
      TlsTestSupport.profile("rpc", serverStore, Some(trust)),
      """tls { enabled = true, profile = "rpc", client-auth = "want" }"""
    )
    val callers = RpcCallers(rules, Transport, (apis, label) => new NamespaceEchoController(apis, Some(label)), true)
    val srv = new SecureJsonRpcHttpServer(
      new NamespaceEchoController(Transport, None),
      healthChecker,
      serverConfig,
      () => TlsServerEngine.rpcHttp(config, new SecureRandom()),
      Some(graphQl),
      Some(callers)
    ):
      override protected def callStats: RpcCallStats = new RpcCallStats
    val binding = Await.result(srv.run(), Wait)
    try body(binding.localAddress.getPort)
    finally Await.result(srv.lifecycle.stop(), Wait)

  private def denials(rule: String, namespace: String): Double = RpcNamespaceDenials.counter(rule, namespace).count()

  "A matched caller" should "get -32601 for a namespace its rule does not grant, counted under the rule (scenario 1)" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    withServer { port =>
      val before = denials("monitor", "admin")
      val (status, json) = post(monitor, port, request("admin_peers"))
      status shouldBe 200
      errorCode(json) shouldBe Some(BigInt(-32601))
      denials("monitor", "admin") shouldBe before + 1
      // a method it is granted is served
      (post(monitor, port, request("eth_blockNumber"))._2 \ "result") shouldBe JString("eth_blockNumber")
    }
  }

  it should "be granted a namespace beyond the transport's apis (scenario 4, FR-023)" taggedAs (UnitTest, RPCTest) in {
    withServer { port =>
      (post(ops, port, request("admin_peers"))._2 \ "result") shouldBe JString("admin_peers")
    }
  }

  "Unmatched and anonymous callers" should "be served the transport's apis and refused the rest (scenarios 2, 4)" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    withServer { port =>
      Seq(unmatched -> "unmatched", anonymous -> "anonymous").foreach { (ctx, label) =>
        withClue(label) {
          (post(ctx, port, request("net_version"))._2 \ "result") shouldBe JString("net_version")
          val before = denials(label, "admin")
          errorCode(post(ctx, port, request("admin_peers"))._2) shouldBe Some(BigInt(-32601))
          denials(label, "admin") shouldBe before + 1
        }
      }
    }
  }

  "An unknown method" should "be -32601 without counting a denial" taggedAs (UnitTest, RPCTest) in {
    withServer { port =>
      val before = denials("monitor", "nosuch")
      errorCode(post(monitor, port, request("nosuch_method"))._2) shouldBe Some(BigInt(-32601))
      denials("monitor", "nosuch") shouldBe before
    }
  }

  "A batch" should "answer each element on its own, with the caller's set (scenario 3)" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    withServer { port =>
      val (_, json) = post(monitor, port, s"[${request("eth_chainId", 1)},${request("admin_peers", 2)}]")
      val answers = batch(json)
      answers should have size 2
      (answers.head \ "result") shouldBe JString("eth_chainId")
      errorCode(answers(1)) shouldBe Some(BigInt(-32601))
    }
  }

  "rpc_modules" should "list the caller's own set (FR-025)" taggedAs (UnitTest, RPCTest) in {
    withServer { port =>
      modules(post(ops, port, request("rpc_modules"))._2) shouldBe Set("eth", "net", "web3", "admin", "rpc")
      modules(post(monitor, port, request("rpc_modules"))._2) shouldBe Set("eth", "net", "web3", "rpc")
      modules(post(anonymous, port, request("rpc_modules"))._2) shouldBe Set("eth", "net", "web3", "rpc")
    }
  }

  "GraphQL" should "be answered as off for a rule that does not list graphql, and counted (scenario 5, FR-028)" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    withServer { port =>
      val before = denials("monitor", "graphql")
      post(monitor, port, "not json", "/graphql")._1 shouldBe 404
      denials("monitor", "graphql") shouldBe before + 1
      // a rule that lists it, and anonymous and unmatched callers, reach the service
      post(ops, port, "not json", "/graphql")._1 shouldBe 400
      post(anonymous, port, "not json", "/graphql")._1 shouldBe 400
      post(unmatched, port, "not json", "/graphql")._1 shouldBe 400
    }
  }
