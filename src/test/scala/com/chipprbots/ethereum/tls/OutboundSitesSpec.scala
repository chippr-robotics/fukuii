package com.chipprbots.ethereum.tls

import java.io.File
import java.security.SecureRandom

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.util.ByteString

import scala.concurrent.Await
import scala.concurrent.duration.*
import scala.util.Try

import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.BootstrapDownload
import com.chipprbots.ethereum.consensus.pow.WorkNotifier
import com.chipprbots.ethereum.console.ops.EndpointProbe
import com.chipprbots.ethereum.testing.Tags.*

/** Spec 030 S8 (T071, FR-044, FR-046): the work notifier, the bootstrap download and the dashboard's endpoint probe
  * each reach an HTTPS server of a private CA on the profile they are given, and not on `system`.
  */
class OutboundSitesSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  given system: ActorSystem = ActorSystem("outbound-sites-spec")

  private val dir = TlsTestSupport.tempDir()
  private val server = new HttpsTestServer(dir)

  override protected def afterAll(): Unit =
    Await.result(system.terminate(), 10.seconds)
    TlsTestSupport.deleteTree(dir)

  private val work = WorkNotifier.WorkPackage(ByteString(1), ByteString(2), ByteString(3), BigInt(7))

  "The work notifier" should "post to an https URL on its profile, and not on system" taggedAs (UnitTest, RPCTest) in {
    server.serve(post(complete("ok"))) { port =>
      val url = s"https://localhost:$port/"
      def sent(profile: String) =
        Try(
          Await.result(WorkNotifier.send(url, work, OutboundTls.pekkoHttpsContext(server.context(profile))), 10.seconds)
        )
      sent("ca").toOption shouldBe Some(200)
      sent(OutboundTls.System).isFailure shouldBe true
    }
  }

  "The bootstrap download" should "fetch over https on its profile, and refuse system and a plain URL" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val out = File.createTempFile("bootstrap", ".zip", dir.toFile)
    server.serve(path("file.zip")(complete("zip-bytes"))) { port =>
      val url = s"https://localhost:$port/file.zip"
      (BootstrapDownload.downloadFile(url, out, server.context("ca")) should have).length(128)
      Try(BootstrapDownload.downloadFile(url, out, server.context(OutboundTls.System))).isFailure shouldBe true
      (the[IllegalArgumentException] thrownBy BootstrapDownload.downloadFile(
        s"http://localhost:$port/file.zip",
        out,
        server.context("ca")
      ) should have)
        .message(s"Bootstrap download refused: refused: http://localhost:$port/file.zip is not an https URL")
    }
  }

  private val rpc = post(complete("""{"jsonrpc":"2.0","id":1,"result":"fukuii/test"}"""))

  "The endpoint probe" should "present the listener's own keystore to a need listener (FR-046)" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val need = server.config("""tls { enabled = true, profile = "srv", client-auth = "need" }""")
    val tls = EndpointProbe.tlsFor(need, new SecureRandom())
    tls should matchPattern { case EndpointProbe.ProbeTls.Unverified(Some(_)) => }
    server.serve(rpc, "need") { port =>
      val e = EndpointProbe.Endpoint(EndpointProbe.Kind.Https, "localhost", port)
      EndpointProbe.probe(e.copy(tls = tls)) should include("answered fukuii/test")
      EndpointProbe.probe(e.copy(tls = tls)) should include("certificate not verified by this test")
      EndpointProbe.probe(e) should include("FAILED")
    }
  }

  it should "stay unverified with no key for a listener that does not need one" taggedAs (UnitTest, RPCTest) in {
    val want = server.config("""tls { enabled = true, profile = "srv", client-auth = "want" }""")
    EndpointProbe.tlsFor(want, new SecureRandom()) shouldBe EndpointProbe.ProbeTls.Unverified(None)
  }

  it should "verify on the profile tui.probe.tls-profile names" taggedAs (UnitTest, RPCTest) in {
    val named = server.config(
      """tls { enabled = true, profile = "srv", client-auth = "none" }""",
      """tui.probe.tls-profile = "ca""""
    )
    val tls = EndpointProbe.tlsFor(named, new SecureRandom())
    tls should matchPattern { case EndpointProbe.ProbeTls.Verified("ca", _) => }
    server.serve(rpc) { port =>
      EndpointProbe.probe(EndpointProbe.Endpoint(EndpointProbe.Kind.Https, "localhost", port, tls = tls)) should
        include("certificate verified on profile ca")
    }
    val missing = server.config("", """tui.probe.tls-profile = "nope"""")
    EndpointProbe.tlsFor(missing, new SecureRandom()) should matchPattern {
      case EndpointProbe.ProbeTls.Unavailable(_) =>
    }
  }
