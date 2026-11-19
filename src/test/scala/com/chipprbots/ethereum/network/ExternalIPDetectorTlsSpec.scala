package com.chipprbots.ethereum.network

import java.net.InetAddress

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.server.Directives.*

import scala.concurrent.Await
import scala.concurrent.duration.*

import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.tls.HttpsTestServer
import com.chipprbots.ethereum.tls.OutboundTls
import com.chipprbots.ethereum.tls.TlsTestSupport

/** Spec 030 S8 (T071, FR-044): the external-IP lookup's HTTPS step reads the address on the profile it is given, and
  * reads nothing from a server that profile does not trust.
  */
class ExternalIPDetectorTlsSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  given system: ActorSystem = ActorSystem("external-ip-tls-spec")

  private val dir = TlsTestSupport.tempDir()
  private val server = new HttpsTestServer(dir)

  override protected def afterAll(): Unit =
    Await.result(system.terminate(), 10.seconds)
    TlsTestSupport.deleteTree(dir)

  "The HTTPS step" should "read the address on its profile, and nothing on system" taggedAs (UnitTest, NetworkTest) in {
    server.serve(path("ip")(complete("8.8.8.8\n"))) { port =>
      val urls = List(s"https://localhost:$port/ip")
      ExternalIPDetector.tryHttp(urls, server.context("ca")) shouldBe Some(InetAddress.getByName("8.8.8.8"))
      ExternalIPDetector.tryHttp(urls, server.context(OutboundTls.System)) shouldBe None
    }
  }

  it should "refuse a plain http URL" taggedAs (UnitTest, NetworkTest) in {
    server.serve(path("ip")(complete("8.8.8.8\n"))) { port =>
      ExternalIPDetector.tryHttp(List(s"http://localhost:$port/ip"), server.context("ca")) shouldBe None
    }
  }
