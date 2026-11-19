package com.chipprbots.ethereum.consensus.engine

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets.UTF_8
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLContext

import cats.effect.IO

import scala.concurrent.Await
import scala.concurrent.duration.*

import org.json4s.*
import org.json4s.native.JsonMethods.parse
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.jsonrpc.JsonRpcError
import com.chipprbots.ethereum.jsonrpc.JsonRpcRequest
import com.chipprbots.ethereum.jsonrpc.JsonRpcResponse
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.tls.*

/** Spec 030 FR-015, US6 scenarios 1 and 3: the Engine API over server TLS. JWT is checked as it is without TLS: a call
  * with a valid token is served, one without is answered 401, and the TLS listener never asks for a client certificate.
  * A `client-auth` key under `engine-api.tls` refuses the start.
  */
class EngineApiTlsSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  private val dir = TlsTestSupport.tempDir()
  private val pki = new TestPki(dir)
  private val store = pki.keystore("engine", Seq("rpc" -> pki.leaf()), "pw")

  override protected def afterAll(): Unit = TlsTestSupport.deleteTree(dir)

  private val secretHex = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"
  private val secret: Array[Byte] = secretHex.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray

  private def b64(text: String): String = Base64.getUrlEncoder.withoutPadding.encodeToString(text.getBytes(UTF_8))

  private def token: String =
    val input = s"${b64("""{"alg":"HS256","typ":"JWT"}""")}.${b64(s"""{"iat":${Instant.now().getEpochSecond}}""")}"
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(secret, "HmacSHA256"))
    s"$input.${Base64.getUrlEncoder.withoutPadding.encodeToString(mac.doFinal(input.getBytes(UTF_8)))}"

  private val controller: EngineApiController =
    new EngineApiController(new EngineApiService(null, null, null, null, None)(using null, null) {}):
      override def handleRequest(request: JsonRpcRequest): IO[JsonRpcResponse] =
        val id = request.id.getOrElse(JNull)
        request.method match
          case "engine_exchangeCapabilities" =>
            IO.pure(JsonRpcResponse("2.0", Some(JArray(List(JString("engine_newPayloadV4")))), None, id))
          case _ => IO.pure(JsonRpcResponse("2.0", None, Some(JsonRpcError.MethodNotFound), id))

  private def engineConfig(tls: String) = TlsTestSupport
    .config(TlsTestSupport.profile("engine", store))
    .withFallback(
      com.typesafe.config.ConfigFactory.parseString(
        s"""network.engine-api { enabled = true, interface = "127.0.0.1", port = 0, $tls }"""
      )
    )

  private def withServer[A](body: Int => A): A =
    val config = engineConfig("""tls { enabled = true, profile = "engine" }""")
    val server = new EngineApiHttpServer(
      controller,
      new JwtAuthenticator(secretHex),
      EngineApiHttpServer.Config(enabled = true, interface = "127.0.0.1", port = 0),
      new EngineCallLog(enabled = false),
      () => TlsServerEngine.engineApi(config, new SecureRandom())
    )
    try body(Await.result(server.start(), 10.seconds).localAddress.getPort)
    finally server.stopSync()

  private def post(ctx: SSLContext, port: Int, bearer: Option[String]): (Int, String) =
    val builder = HttpRequest
      .newBuilder(URI.create(s"https://localhost:$port/"))
      .header("Content-Type", "application/json")
      .POST(
        HttpRequest.BodyPublishers
          .ofString("""{"jsonrpc":"2.0","id":1,"method":"engine_exchangeCapabilities","params":[[]]}""")
      )
    bearer.foreach(t => builder.header("Authorization", s"Bearer $t"))
    val response =
      HttpClient.newBuilder().sslContext(ctx).build().send(builder.build(), HttpResponse.BodyHandlers.ofString())
    (response.statusCode(), response.body())

  "The Engine API over TLS" should "serve a JWT-bearing call and answer 401 without one, as without TLS" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    withServer { port =>
      val (status, body) = post(pki.clientContext(), port, Some(token))
      status shouldBe 200
      (parse(body) \ "result") shouldBe JArray(List(JString("engine_newPayloadV4")))
      post(pki.clientContext(), port, None)._1 shouldBe 401
    }
  }

  it should "ask for no client certificate: a client presenting an untrusted one is still served" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    withServer { port =>
      post(pki.clientContext(presenting = Some(pki.selfSignedLeaf("anyone"))), port, Some(token))._1 shouldBe 200
    }
  }

  it should "refuse a client-auth key under engine-api.tls at start, with the contract's text" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val config = engineConfig("""tls { enabled = true, profile = "engine", client-auth = "need" }""")
    TlsStartupCheck.run(config).left.toOption.get shouldBe
      "Refusing to start: fukuii.network.engine-api.tls.client-auth is not supported: the Engine API takes server " +
      "TLS only; JWT authenticates the consensus client"
  }

  it should "say it in the start-up line, with client-auth=none" taggedAs (UnitTest, RPCTest) in {
    val lines = TlsStartupCheck.run(engineConfig("""tls { enabled = true, profile = "engine" }""")).toOption.get
    lines.map(_.listener) should contain(ListenerId.EngineApi)
    lines.find(_.listener == ListenerId.EngineApi).get.render should include(
      "engine-api https://127.0.0.1:0 client-auth=none"
    )
  }
