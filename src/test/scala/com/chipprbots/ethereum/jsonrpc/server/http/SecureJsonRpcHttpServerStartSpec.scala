package com.chipprbots.ethereum.jsonrpc.server.http

import java.nio.file.Files
import java.security.SecureRandom
import javax.net.ssl.SSLContext

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.cors.scaladsl.model.HttpOriginMatcher

import cats.effect.IO

import scala.concurrent.Await
import scala.concurrent.duration.*

import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.healthcheck.HealthcheckResponse
import com.chipprbots.ethereum.jsonrpc.JsonRpcHealthChecker
import com.chipprbots.ethereum.jsonrpc.RpcCallStats
import com.chipprbots.ethereum.jsonrpc.client.RpcClient
import com.chipprbots.ethereum.jsonrpc.server.http.JsonRpcHttpServer.JsonRpcHttpServerConfig
import com.chipprbots.ethereum.jsonrpc.server.http.JsonRpcHttpServer.RateLimitConfig
import com.chipprbots.ethereum.nodebuilder.EngineApiStartupRefusal
import com.chipprbots.ethereum.nodebuilder.StdNode
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.tls.TestPki
import com.chipprbots.ethereum.tls.TlsConfigError
import com.chipprbots.ethereum.tls.TlsServerContext
import com.chipprbots.ethereum.tls.TlsServerEngine
import com.chipprbots.ethereum.tls.TlsTestSupport

/** B-148: a TLS misconfiguration stops the start (spec 030 FR-010..FR-012). The HTTPS server whose SSL context cannot
  * be built, or the client that cannot build its own, never runs on without TLS or on the JDK's default trust; the
  * configuration read is the one passed in (a node's instance configuration), and the plain http path is unchanged.
  */
class SecureJsonRpcHttpServerStartSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  private val system: ActorSystem = ActorSystem("secure-json-rpc-start-spec")
  private val Wait = 10.seconds
  private val dir = Files.createTempDirectory("b148-tls").toFile

  override protected def afterAll(): Unit =
    Await.result(system.terminate(), Wait)
    Option(dir.listFiles()).foreach(_.foreach(_.delete()))
    dir.delete()
    ()

  private val random = new SecureRandom()

  private def serverConfig(modeValue: String): JsonRpcHttpServerConfig = new JsonRpcHttpServerConfig:
    override val mode: String = modeValue
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

  private def secureServer(ssl: () => Either[TlsConfigError, TlsServerContext]): SecureJsonRpcHttpServer =
    new SecureJsonRpcHttpServer(new MockableJsonRpcController, healthChecker, serverConfig("https"), ssl)(using system):
      override protected def callStats: RpcCallStats = new RpcCallStats

  "An HTTPS JSON-RPC server" should "refuse the start, with the key in the message, when its SSL context cannot be built" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val server = secureServer(() => Left(TlsConfigError("fukuii.network.rpc.http.certificate: keystore is missing")))
    val result = StdNode.awaitJsonRpcHttpBind(server.run(), "127.0.0.1", 8545, Wait)
    val message = result.left.getOrElse(fail("the server started without a TLS context"))
    message should include("fukuii.network.rpc.http.certificate")
    message should include("Refusing to start")
    server.lifecycle.isBound shouldBe false
    // the refusal the node's start throws carries that message
    val refusal = intercept[EngineApiStartupRefusal](throw StdNode.startupRefusal(message))
    refusal.getMessage should include("fukuii.network.rpc.http.certificate")
  }

  it should "start when its SSL context is good" taggedAs (UnitTest, RPCTest) in {
    val pki = new TestPki(dir.toPath)
    val keys = pki.keystore("start-spec", Seq("rpc" -> pki.leaf()))
    val config = TlsTestSupport.config(
      TlsTestSupport.profile("rpc", keys),
      """tls { enabled = true, profile = "rpc" }"""
    )
    val server = secureServer(() => TlsServerEngine.rpcHttp(config, random))
    try StdNode.awaitJsonRpcHttpBind(server.run(), "127.0.0.1", 0, Wait).isRight shouldBe true
    finally Await.result(server.lifecycle.stop(), Wait)
  }

  "JsonRpcHttpServer.apply" should "keep the plain http mode free of any TLS lookup" taggedAs (UnitTest, RPCTest) in {
    val server = JsonRpcHttpServer(
      new MockableJsonRpcController,
      healthChecker,
      serverConfig("http"),
      () => fail("the http mode must not ask for a TLS context")
    )(using system)
    server.exists(_.isInstanceOf[InsecureJsonRpcHttpServer]) shouldBe true
  }

  "An RpcClient for an https node" should "refuse to be built when its TLS configuration cannot be built" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val e = intercept[IllegalStateException](
      new TestClient(
        "https://localhost:1",
        () => Left(TlsConfigError("faucet.rpc-client.certificate: keystore is missing"))
      )
    )
    e.getMessage should include("faucet.rpc-client.certificate")
  }

  it should "be built when its TLS configuration is good" taggedAs (UnitTest, RPCTest) in {
    noException should be thrownBy new TestClient("https://localhost:1", () => Right(SSLContext.getDefault))
  }

  "FaucetServer.awaitBind" should "refuse a bind that failed, and pass one that worked" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    import com.chipprbots.ethereum.faucet.jsonrpc.FaucetServer
    FaucetServer
      .awaitBind(scala.concurrent.Future.failed(new IllegalStateException("bad cert")), Wait)
      .left
      .getOrElse(fail("a failed bind was accepted")) should include("bad cert")
    FaucetServer.awaitBind(scala.concurrent.Future.successful(1), Wait) shouldBe Right(1)
  }

  "An RpcClient for an http node" should "not look at the TLS configuration" taggedAs (UnitTest, RPCTest) in {
    noException should be thrownBy new TestClient("http://localhost:1", () => Left(TlsConfigError("ignored")))
  }

  private class TestClient(uri: String, ssl: () => Either[TlsConfigError, SSLContext])
      extends RpcClient(org.apache.pekko.http.scaladsl.model.Uri(uri), 1.second, ssl)(using system, system.dispatcher)
