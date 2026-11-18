package com.chipprbots.ethereum.jsonrpc.server.http

import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.KeyStore
import java.security.SecureRandom
import javax.net.ssl.SSLContext

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.cors.scaladsl.model.HttpOriginMatcher

import cats.effect.IO

import scala.concurrent.Await
import scala.concurrent.duration.*

import com.typesafe.config.ConfigFactory
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
import com.chipprbots.ethereum.security.SSLContextBuilder
import com.chipprbots.ethereum.security.SSLError
import com.chipprbots.ethereum.testing.Tags.*

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

  /** An (empty) PKCS12 keystore protected by `password`, and a password file holding `filePassword`. */
  private def keystore(password: String, filePassword: String): (File, File) =
    val ks = KeyStore.getInstance("pkcs12")
    ks.load(null, password.toCharArray)
    val keystoreFile = File.createTempFile("keystore", ".p12", dir)
    val out = new FileOutputStream(keystoreFile)
    try ks.store(out, password.toCharArray)
    finally out.close()
    val passwordFile = File.createTempFile("password", ".txt", dir)
    Files.write(passwordFile.toPath, filePassword.getBytes(StandardCharsets.UTF_8))
    (keystoreFile, passwordFile)

  private def hocon(keystorePath: String, passwordFile: String) =
    ConfigFactory.parseString(s"""
      network.rpc.http.certificate {
        keystore-path = "${keystorePath.replace("\\", "/")}"
        keystore-type = "pkcs12"
        password-file = "${passwordFile.replace("\\", "/")}"
      }""")

  private val random = new SecureRandom()

  "SSLContextBuilder.build" should "build the context of a good configuration" taggedAs (UnitTest, RPCTest) in {
    val (ks, pw) = keystore("secret", "secret")
    SSLContextBuilder.build(hocon(ks.getPath, pw.getPath), "network.rpc.http", random, "fukuii").isRight shouldBe true
  }

  it should "name the key when the keystore path is bad" taggedAs (UnitTest, RPCTest) in {
    val (_, pw) = keystore("secret", "secret")
    val result =
      SSLContextBuilder.build(hocon("/no/such/keystore.p12", pw.getPath), "network.rpc.http", random, "fukuii")
    val reason = result.left.getOrElse(fail("a missing keystore was accepted")).reason
    reason should include("fukuii.network.rpc.http.certificate")
    reason should include("/no/such/keystore.p12")
  }

  it should "name the key when the password is wrong" taggedAs (UnitTest, RPCTest) in {
    val (ks, pw) = keystore("secret", "not-the-password")
    val result = SSLContextBuilder.build(hocon(ks.getPath, pw.getPath), "network.rpc.http", random, "fukuii")
    result.left.getOrElse(fail("a wrong password was accepted")).reason should include(
      "fukuii.network.rpc.http.certificate"
    )
  }

  it should "name the key when the section is missing or null" taggedAs (UnitTest, RPCTest) in {
    val missing = SSLContextBuilder.build(ConfigFactory.empty(), "network.rpc.http", random, "fukuii")
    missing.left.getOrElse(fail("no section was accepted")).reason should include("fukuii.network.rpc.http.certificate")
    val nulled =
      SSLContextBuilder.build(
        ConfigFactory.parseString("network.rpc.http.certificate = null"),
        "network.rpc.http",
        random
      )
    nulled.left.getOrElse(fail("a null section was accepted")).reason should include("network.rpc.http.certificate")
  }

  it should "read the configuration it is given, not the global one" taggedAs (UnitTest, RPCTest) in {
    val (ks, pw) = keystore("secret", "secret")
    val instanceA = hocon(ks.getPath, pw.getPath)
    val instanceB = hocon("/no/such/keystore.p12", pw.getPath)
    // same key, two instances, two answers: the global ConfigFactory.load() has neither keystore
    SSLContextBuilder.build(instanceA, "network.rpc.http", random).isRight shouldBe true
    SSLContextBuilder.build(instanceB, "network.rpc.http", random).isLeft shouldBe true
  }

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

  private def secureServer(ssl: () => Either[SSLError, SSLContext]): SecureJsonRpcHttpServer =
    new SecureJsonRpcHttpServer(new MockableJsonRpcController, healthChecker, serverConfig("https"), ssl)(using system):
      override protected def callStats: RpcCallStats = new RpcCallStats

  "An HTTPS JSON-RPC server" should "refuse the start, with the key in the message, when its SSL context cannot be built" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val server = secureServer(() => Left(SSLError("fukuii.network.rpc.http.certificate: keystore is missing")))
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
    val server = secureServer(() => Right(SSLContext.getDefault))
    try StdNode.awaitJsonRpcHttpBind(server.run(), "127.0.0.1", 0, Wait).isRight shouldBe true
    finally Await.result(server.lifecycle.stop(), Wait)
  }

  "JsonRpcHttpServer.apply" should "keep the plain http mode free of any TLS lookup" taggedAs (UnitTest, RPCTest) in {
    val server = JsonRpcHttpServer(
      new MockableJsonRpcController,
      healthChecker,
      serverConfig("http"),
      () => fail("the http mode must not ask for an SSL context")
    )(using system)
    server.exists(_.isInstanceOf[InsecureJsonRpcHttpServer]) shouldBe true
  }

  "An RpcClient for an https node" should "refuse to be built when its TLS configuration cannot be built" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val e = intercept[IllegalStateException](
      new TestClient("https://localhost:1", () => Left(SSLError("faucet.rpc-client.certificate: keystore is missing")))
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
    noException should be thrownBy new TestClient("http://localhost:1", () => Left(SSLError("ignored")))
  }

  private class TestClient(uri: String, ssl: () => Either[SSLError, SSLContext])
      extends RpcClient(org.apache.pekko.http.scaladsl.model.Uri(uri), 1.second, ssl)(using system, system.dispatcher)
