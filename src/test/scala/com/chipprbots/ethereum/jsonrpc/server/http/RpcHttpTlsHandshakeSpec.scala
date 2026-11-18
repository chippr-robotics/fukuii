package com.chipprbots.ethereum.jsonrpc.server.http

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.SecureRandom
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters

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
import com.chipprbots.ethereum.jsonrpc.server.http.JsonRpcHttpServer.JsonRpcHttpServerConfig
import com.chipprbots.ethereum.jsonrpc.server.http.JsonRpcHttpServer.RateLimitConfig
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.tls.*

/** Spec 030 US1/FR-014 on a real handshake: the RPC HTTP listener served from a profile (`client-auth = none`), from
  * the legacy `mode = "https"` mapping too, with a generated PKI in a temp directory.
  */
class RpcHttpTlsHandshakeSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  private val system: ActorSystem = ActorSystem("rpc-http-tls-handshake-spec")
  private val dir = TlsTestSupport.tempDir()
  private val pki = new TestPki(dir)
  private val server = pki.keystore("server", Seq("rpc" -> pki.leaf()), "srv-pw")
  private val Wait = 10.seconds

  override protected def afterAll(): Unit =
    Await.result(system.terminate(), Wait)
    TlsTestSupport.deleteTree(dir)

  private def serverConfig(tlsOn: Boolean): JsonRpcHttpServerConfig = new JsonRpcHttpServerConfig:
    override val mode: String = "http"
    override val tlsBlockEnabled: Boolean = tlsOn
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

  /** Runs `body` against a live HTTPS server built from `hocon`'s HTTP listener; returns what `body` returns. */
  private def withServer[A](hocon: com.typesafe.config.Config)(body: Int => A): A =
    val tls = () => TlsServerEngine.rpcHttp(hocon, new SecureRandom())
    val srv = new SecureJsonRpcHttpServer(new MockableJsonRpcController, healthChecker, serverConfig(true), tls)(using
      system
    ):
      override protected def callStats: RpcCallStats = new RpcCallStats
    val binding = Await.result(srv.run(), Wait)
    try body(binding.localAddress.getPort)
    finally Await.result(srv.lifecycle.stop(), Wait)

  private def get(ctx: SSLContext, port: Int, protocols: Array[String] = Array("TLSv1.2", "TLSv1.3")): Int =
    val params = new SSLParameters()
    params.setProtocols(protocols)
    val client = HttpClient.newBuilder().sslContext(ctx).sslParameters(params).build()
    client
      .send(
        HttpRequest.newBuilder(URI.create(s"https://localhost:$port/health")).GET().build(),
        HttpResponse.BodyHandlers.ofString()
      )
      .statusCode()

  private val profiles = TlsTestSupport.profile("rpc", server)
  private val tlsHttp = """tls { enabled = true, profile = "rpc", client-auth = "none" }"""

  "The RPC HTTP listener on a profile" should "answer a client that trusts the CA, with no client certificate" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    withServer(TlsTestSupport.config(profiles, tlsHttp))(port => get(pki.clientContext(), port) shouldBe 200)
  }

  it should "fail the handshake for a client that does not trust the server's CA" taggedAs (UnitTest, RPCTest) in {
    withServer(TlsTestSupport.config(profiles, tlsHttp)) { port =>
      an[javax.net.ssl.SSLHandshakeException] should be thrownBy
        get(pki.clientContext(Seq(pki.otherCa().cert)), port)
    }
  }

  it should "not answer plaintext on the TLS port" taggedAs (UnitTest, RPCTest) in {
    withServer(TlsTestSupport.config(profiles, tlsHttp)) { port =>
      val plain = HttpClient.newHttpClient()
      an[java.io.IOException] should be thrownBy
        plain.send(
          HttpRequest.newBuilder(URI.create(s"http://localhost:$port/health")).GET().build(),
          HttpResponse.BodyHandlers.ofString()
        )
    }
  }

  it should "refuse a protocol the profile does not allow" taggedAs (UnitTest, RPCTest) in {
    val only13 = TlsTestSupport.profile("rpc", server, extra = """protocols = ["TLSv1.3"]""")
    withServer(TlsTestSupport.config(only13, tlsHttp)) { port =>
      get(pki.clientContext(), port, Array("TLSv1.3")) shouldBe 200
      an[javax.net.ssl.SSLException] should be thrownBy get(pki.clientContext(), port, Array("TLSv1.2"))
    }
  }

  "The legacy mode = https mapping" should "serve the same listener from the certificate block" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val legacy = TlsTestSupport.config(
      http =
        s"""mode = "https", certificate { keystore-path = "${server.store}", keystore-type = "pkcs12", password-file = "${server.passwordFile}" }"""
    )
    withServer(legacy)(port => get(pki.clientContext(), port) shouldBe 200)
  }
