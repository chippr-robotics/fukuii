package com.chipprbots.ethereum.jsonrpc.server.http

import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger

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

/** Spec 030 US2 scenarios 1, 2 and 4, SC-002: the RPC HTTP listener with `client-auth = need`, `want` and `none`,
  * against clients with no certificate, a self-signed one, one from another CA, and one from the trusted CA.
  */
class MtlsHttpSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  given system: ActorSystem = ActorSystem("mtls-http-spec")

  private val dir = TlsTestSupport.tempDir()
  private val pki = new TestPki(dir)
  private val serverStore = pki.keystore("server", Seq("rpc" -> pki.leaf()), "srv-pw")
  private val trust = pki.truststore("trust", Seq(pki.ca.cert), "trust-pw")
  private val Wait = 10.seconds

  private val trusted = pki.clientContext(presenting = Some(pki.leaf("app-server")))
  private val anonymous = pki.clientContext()
  private val selfSigned = pki.clientContext(presenting = Some(pki.selfSignedLeaf("mallory")))
  private val otherCa = pki.clientContext(presenting = Some(pki.leaf("intruder", signedBy = pki.otherCa())))

  override protected def afterAll(): Unit =
    Await.result(system.terminate(), Wait)
    TlsTestSupport.deleteTree(dir)

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

  /** Counts the requests that reached a route: a refused handshake must reach none. */
  private val served = new AtomicInteger(0)

  private val healthChecker: JsonRpcHealthChecker = new JsonRpcHealthChecker:
    override def healthCheck: IO[HealthcheckResponse] = IO { served.incrementAndGet(); HealthcheckResponse(Nil) }
    override def readinessCheck(): IO[HealthcheckResponse] = IO.pure(HealthcheckResponse(Nil))

  private def withServer[A](clientAuth: String)(body: Int => A): A =
    val config = TlsTestSupport.config(
      TlsTestSupport.profile("rpc", serverStore, Some(trust)),
      s"""tls { enabled = true, profile = "rpc", client-auth = "$clientAuth" }"""
    )
    val tls = () => TlsServerEngine.rpcHttp(config, new SecureRandom())
    val srv = new SecureJsonRpcHttpServer(new MockableJsonRpcController, healthChecker, serverConfig, tls):
      override protected def callStats: RpcCallStats = new RpcCallStats
    val binding = Await.result(srv.run(), Wait)
    try body(binding.localAddress.getPort)
    finally Await.result(srv.lifecycle.stop(), Wait)

  private def health(ctx: javax.net.ssl.SSLContext, port: Int) = MtlsTestSupport.exchange(ctx, port, path = "/health")

  /** The exchange failed in the handshake, and no request reached a route. */
  private def refused(ctx: javax.net.ssl.SSLContext, port: Int): Unit =
    val before = served.get()
    val result = health(ctx, port)
    withClue(result)(MtlsTestSupport.answered(result) shouldBe false)
    result.left.toOption.get shouldBe a[java.io.IOException]
    served.get() shouldBe before

  "client-auth = need" should "answer a client with a certificate from the trusted CA" taggedAs (UnitTest, RPCTest) in {
    withServer("need") { port =>
      MtlsTestSupport.answered(health(trusted, port)) shouldBe true
    }
  }

  it should "fail the handshake, reading no request, for no certificate, a self-signed one, and one of another CA" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    withServer("need") { port =>
      refused(anonymous, port)
      refused(selfSigned, port)
      refused(otherCa, port)
    }
  }

  "client-auth = want" should "serve a client with no certificate" taggedAs (UnitTest, RPCTest) in {
    withServer("want") { port =>
      MtlsTestSupport.answered(health(anonymous, port)) shouldBe true
      MtlsTestSupport.answered(health(trusted, port)) shouldBe true
    }
  }

  it should "fail the handshake for an untrusted certificate: a presented one is always verified" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    withServer("want") { port =>
      refused(selfSigned, port)
      refused(otherCa, port)
    }
  }

  "client-auth = none" should "serve every client as the legacy https did, asking for no certificate" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    withServer("none") { port =>
      Seq(anonymous, trusted, selfSigned, otherCa).foreach { ctx =>
        MtlsTestSupport.answered(health(ctx, port)) shouldBe true
      }
    }
  }
