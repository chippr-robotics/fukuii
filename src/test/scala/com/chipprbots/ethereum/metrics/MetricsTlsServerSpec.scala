package com.chipprbots.ethereum.metrics

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.SecureRandom
import javax.net.ssl.SSLContext

import org.apache.pekko.actor.ActorSystem

import scala.concurrent.Await
import scala.concurrent.duration.*

import io.prometheus.metrics.core.metrics.Counter
import io.prometheus.metrics.exporter.httpserver.HTTPServer
import io.prometheus.metrics.model.registry.PrometheusRegistry
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.Seconds
import org.scalatest.time.Span

import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.tls.*

/** Spec 030 FR-016, FR-017, US6 scenario 2: `/metrics` over TLS with `client-auth = need` serves a trusted scraper and
  * refuses the rest at the handshake, counted with its reason; the body is what the plaintext Prometheus server answers
  * for the same registry.
  */
class MetricsTlsServerSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll with Eventually:

  given system: ActorSystem = ActorSystem("metrics-tls-server-spec")
  implicit override val patienceConfig: PatienceConfig = PatienceConfig(timeout = Span(10, Seconds))

  private val dir = TlsTestSupport.tempDir()
  private val pki = new TestPki(dir)
  private val store = pki.keystore("metrics", Seq("rpc" -> pki.leaf()), "pw")
  private val trust = pki.truststore("scrapers", Seq(pki.ca.cert), "trust-pw")

  // a registry of its own, holding a fixed value, so two scrapes of it are equal
  private val registry = new PrometheusRegistry()
  Counter.builder().name("fukuii_test_scrapes").help("a fixed count").register(registry).inc(7)

  override protected def afterAll(): Unit =
    Await.result(system.terminate(), 10.seconds)
    TlsTestSupport.deleteTree(dir)

  private val config = TlsTestSupport
    .config(TlsTestSupport.profile("metrics", store, Some(trust)))
    .withFallback(
      com.typesafe.config.ConfigFactory.parseString(
        """metrics { enabled = true, port = 0, interface = "127.0.0.1",
          |  tls { enabled = true, profile = "metrics", client-auth = "need" } }""".stripMargin
      )
    )

  private def withServer[A](body: Int => A): A =
    val ctx = TlsServerEngine.metrics(config, new SecureRandom()).fold(e => fail(e.message), _.get)
    val binding = Await.result(MetricsTlsServer.bind("127.0.0.1", 0, ctx, registry), 10.seconds)
    try body(binding.localAddress.getPort)
    finally Await.result(binding.terminate(1.second), 10.seconds)

  private def scrape(client: HttpClient, url: String): (Int, String, String) =
    val response = client.send(
      HttpRequest.newBuilder(URI.create(url)).header("Accept", "text/plain; version=0.0.4").GET().build(),
      HttpResponse.BodyHandlers.ofString()
    )
    (response.statusCode(), response.headers().firstValue("Content-Type").orElse(""), response.body())

  private def tls(ctx: SSLContext) = HttpClient.newBuilder().sslContext(ctx).build()

  "The metrics endpoint over TLS with need" should "serve a trusted scraper what the plaintext server serves" taggedAs (
    UnitTest
  ) in {
    val plain = HTTPServer.builder().hostname("127.0.0.1").port(0).registry(registry).buildAndStart()
    try
      val expected = scrape(HttpClient.newHttpClient(), s"http://127.0.0.1:${plain.getPort}/metrics")
      expected._3 should include("fukuii_test_scrapes_total 7.0")
      withServer { port =>
        val got =
          scrape(tls(pki.clientContext(presenting = Some(pki.leaf("prometheus")))), s"https://localhost:$port/metrics")
        got._1 shouldBe 200
        got._3 shouldBe expected._3
        got._2.toLowerCase.replace(" ", "") shouldBe expected._2.toLowerCase.replace(" ", "")
      }
    finally plain.close()
  }

  it should "refuse a scraper without a certificate at the handshake, counted as no_client_cert" taggedAs UnitTest in {
    withServer { port =>
      val before = TlsHandshakeMetrics.counter(ListenerId.Metrics, HandshakeFailureReason.NoClientCert).count()
      // one handshake per attempt: the JDK HttpClient retries a failed connection, so a raw socket is used
      MtlsTestSupport.answered(MtlsTestSupport.exchange(pki.clientContext(), port, path = "/metrics")) shouldBe false
      eventually(
        TlsHandshakeMetrics.counter(ListenerId.Metrics, HandshakeFailureReason.NoClientCert).count() shouldBe before + 1
      )
    }
  }

  it should "refuse a scraper of another CA, counted as untrusted_client_cert" taggedAs UnitTest in {
    withServer { port =>
      val before = TlsHandshakeMetrics.counter(ListenerId.Metrics, HandshakeFailureReason.UntrustedClientCert).count()
      val other = pki.clientContext(presenting = Some(pki.leaf("intruder", signedBy = pki.otherCa())))
      MtlsTestSupport.answered(MtlsTestSupport.exchange(other, port, path = "/metrics")) shouldBe false
      eventually(
        TlsHandshakeMetrics.counter(ListenerId.Metrics, HandshakeFailureReason.UntrustedClientCert).count() shouldBe
          before + 1
      )
    }
  }
