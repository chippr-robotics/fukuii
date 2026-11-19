package com.chipprbots.ethereum.tls

import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.SecureRandom
import java.time.Duration
import javax.net.ssl.SSLContext

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.HttpRequest as PekkoRequest
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Directives.*

import scala.concurrent.Await
import scala.concurrent.duration.*
import scala.util.Try

import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

/** Spec 030 FR-040–FR-043, US5 scenarios 1–3: every kind of outbound client the factory makes (Pekko, the JDK
  * `HttpClient`, `HttpsURLConnection`) against a local HTTPS server of the test CA: a profile that trusts the CA
  * connects, `system` does not, a server whose certificate names another host is refused, a server that needs a client
  * certificate is served only to a profile with a keystore, and a redirect from https to http is not followed.
  */
class OutboundTlsSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  given system: ActorSystem = ActorSystem("outbound-tls-spec")

  private val dir = TlsTestSupport.tempDir()
  private val pki = new TestPki(dir)
  private val good = pki.keystore("server", Seq("rpc" -> pki.leaf()), "srv-pw")
  private val wrongHost =
    pki.keystore("wrong-host", Seq("rpc" -> pki.leaf("elsewhere", sanDns = Seq("elsewhere.example"))), "srv-pw")
  private val trust = pki.truststore("trust", Seq(pki.ca.cert), "trust-pw")
  private val clientKey = pki.keystore("client", Seq("me" -> pki.leaf("node-client")), "cli-pw")

  override protected def afterAll(): Unit =
    Await.result(system.terminate(), 10.seconds)
    TlsTestSupport.deleteTree(dir)

  private val config = TlsTestSupport.config(
    Seq(
      TlsTestSupport.profile("srv", good, Some(trust)),
      TlsTestSupport.profile("wrong", wrongHost),
      s"ca { truststore = ${TlsTestSupport.storeHocon(trust)} }",
      TlsTestSupport.profile("keyed", clientKey, Some(trust))
    ).mkString("\n")
  )

  private def ctx(profile: String): SSLContext =
    OutboundTls.context(config, profile, new SecureRandom()).fold(e => fail(e.message), identity)

  /** A server on profile `serverProfile` with `client-auth`; `body` gets its port. */
  private def withServer[A](serverProfile: String, clientAuth: String = "none")(body: Int => A): A =
    val listener = TlsTestSupport.config(
      Seq(TlsTestSupport.profile("srv", good, Some(trust)), TlsTestSupport.profile("wrong", wrongHost, Some(trust)))
        .mkString("\n"),
      s"""tls { enabled = true, profile = "$serverProfile", client-auth = "$clientAuth" }"""
    )
    val server = TlsServerEngine.rpcHttp(listener, new SecureRandom()).fold(e => fail(e.message), identity)
    val route = path("ok")(complete("ok")) ~
      path("down")(redirect("http://localhost:1/ok", StatusCodes.Found)) ~
      path("up")(redirect("/ok", StatusCodes.Found))
    val binding = Await.result(
      Http().newServerAt("127.0.0.1", 0).enableHttps(TlsServerEngine.connectionContext(server)).bind(route),
      10.seconds
    )
    try body(binding.localAddress.getPort)
    finally Await.result(binding.terminate(1.second), 10.seconds)

  // ---- the three kinds of client, each answering "served" or not ----------------------------------------------

  private def viaPekko(c: SSLContext, port: Int): Boolean =
    Try(
      Await.result(
        Http().singleRequest(PekkoRequest(uri = s"https://localhost:$port/ok"), OutboundTls.pekkoHttpsContext(c)),
        10.seconds
      )
    ).toOption.exists { r =>
      r.discardEntityBytes(); r.status == StatusCodes.OK
    }

  private def viaJdk(c: SSLContext, port: Int, path: String = "ok"): Option[Int] =
    Try(
      OutboundTls
        .jdkHttpClient(c, RedirectPolicy.HttpsOnly, Duration.ofSeconds(10))
        .send(
          HttpRequest.newBuilder(URI.create(s"https://localhost:$port/$path")).GET().build(),
          HttpResponse.BodyHandlers.ofString()
        )
        .statusCode()
    ).toOption

  private def viaUrl(c: SSLContext, port: Int, path: String = "ok"): Either[String, String] =
    OutboundTls
      .openHttpsStream(s"https://localhost:$port/$path", c)
      .map(in =>
        try new String(in.readAllBytes())
        finally in.close()
      )

  private def served(c: SSLContext, port: Int): Seq[Boolean] =
    Seq(viaPekko(c, port), viaJdk(c, port).contains(200), viaUrl(c, port) == Right("ok"))

  "A profile that trusts the server's CA" should "connect with every kind of client" taggedAs (UnitTest, RPCTest) in {
    withServer("srv")(port => served(ctx("ca"), port) shouldBe Seq(true, true, true))
  }

  "The system profile" should "refuse a server of a private CA with every kind of client" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    withServer("srv")(port => served(ctx(OutboundTls.System), port) shouldBe Seq(false, false, false))
  }

  "A server whose certificate names another host" should "be refused by every kind of client (FR-041)" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    withServer("wrong")(port => served(ctx("ca"), port) shouldBe Seq(false, false, false))
  }

  "A server that needs a client certificate" should "serve a profile with a keystore only (FR-042)" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    withServer("srv", "need") { port =>
      served(ctx("keyed"), port) shouldBe Seq(true, true, true)
      served(ctx("ca"), port) shouldBe Seq(false, false, false)
    }
  }

  "A redirect" should "be followed to https and never from https to http (FR-043)" taggedAs (UnitTest, RPCTest) in {
    withServer("srv") { port =>
      viaJdk(ctx("ca"), port, "up") shouldBe Some(200)
      viaUrl(ctx("ca"), port, "up") shouldBe Right("ok")
      // the JDK client answers the redirect itself rather than follow it to http
      viaJdk(ctx("ca"), port, "down") shouldBe Some(302)
      viaUrl(ctx("ca"), port, "down").left.toOption.get should include("is not an https URL")
    }
  }

  "A profile name" should "be system where a site names none, and a load failure name the site's key" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val site = config.withFallback(com.typesafe.config.ConfigFactory.parseString("""a.tls-profile = "nope""""))
    OutboundTls.profileAt(site, "b.tls-profile") shouldBe OutboundTls.System
    OutboundTls.forSite(site, "a.tls-profile", new SecureRandom()).left.toOption.get.message shouldBe
      "fukuii.a.tls-profile = nope: there is no profile 'nope' under fukuii.tls.profiles"
  }
