package com.chipprbots.ethereum.tls

import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException

import org.apache.pekko.actor.ActorSystem

import scala.concurrent.Await
import scala.jdk.CollectionConverters.*

import ch.qos.logback.classic.Logger as LogbackLogger
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.Seconds
import org.scalatest.time.Span
import org.slf4j.LoggerFactory

import com.chipprbots.ethereum.testing.Tags.*

/** Spec 030 FR-017: each `reason` of `tls_handshake_failures_total` from a real refused handshake, and the WARN line's
  * limit of one per remote address per minute with a suppressed count.
  */
class HandshakeFailureClassifierSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll with Eventually:

  given system: ActorSystem = ActorSystem("handshake-failure-classifier-spec")
  implicit override val patienceConfig: PatienceConfig = PatienceConfig(timeout = Span(10, Seconds))

  private val dir = TlsTestSupport.tempDir()
  private val pki = new TestPki(dir)
  private val server = pki.keystore("server", Seq("rpc" -> pki.leaf()), "srv-pw")
  private val trust = pki.truststore("trust", Seq(pki.ca.cert), "trust-pw")

  override protected def afterAll(): Unit =
    Await.result(system.terminate(), MtlsTestSupport.Wait)
    TlsTestSupport.deleteTree(dir)

  private def config(clientAuth: String, extra: String = "") =
    TlsTestSupport.config(
      TlsTestSupport.profile("rpc", server, Some(trust), extra),
      s"""tls { enabled = true, profile = "rpc", client-auth = "$clientAuth" }"""
    )

  private def count(reason: HandshakeFailureReason): Double =
    TlsHandshakeMetrics.counter(ListenerId.RpcHttp, reason).count()

  /** Runs one exchange against a fresh server and returns the reason the server counted. */
  private def refusedAs(
      serverConfig: com.typesafe.config.Config,
      client: javax.net.ssl.SSLContext,
      protocols: Array[String] = Array("TLSv1.2", "TLSv1.3")
  ): HandshakeFailureReason =
    val before = HandshakeFailureReason.values.map(r => r -> count(r)).toMap
    MtlsTestSupport.withServer(serverConfig) { port =>
      MtlsTestSupport.answered(MtlsTestSupport.exchange(client, port, protocols)) shouldBe false
      eventually {
        val moved = HandshakeFailureReason.values.filter(r => count(r) > before(r))
        moved should have size 1
        moved.head
      }
    }

  "A refused handshake" should "count no_client_cert when need gets no certificate, under TLS 1.2 and 1.3" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    Seq("TLSv1.2", "TLSv1.3").foreach { protocol =>
      withClue(protocol) {
        refusedAs(config("need"), pki.clientContext(), Array(protocol)) shouldBe HandshakeFailureReason.NoClientCert
      }
    }
  }

  it should "count untrusted_client_cert for a self-signed and for an other-CA certificate" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val other = pki.leaf("intruder", signedBy = pki.otherCa())
    Seq(pki.selfSignedLeaf(), other).foreach { id =>
      refusedAs(config("need"), pki.clientContext(presenting = Some(id))) shouldBe
        HandshakeFailureReason.UntrustedClientCert
    }
  }

  it should "count untrusted_client_cert under want too: a presented certificate is always verified" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    refusedAs(config("want"), pki.clientContext(presenting = Some(pki.selfSignedLeaf()))) shouldBe
      HandshakeFailureReason.UntrustedClientCert
  }

  it should "count expired_client_cert for an expired and for a not-yet-valid certificate of the trusted CA" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    Seq(pki.expiredLeaf("old-client"), pki.notYetValidLeaf("early-client")).foreach { id =>
      refusedAs(config("need"), pki.clientContext(presenting = Some(id))) shouldBe
        HandshakeFailureReason.ExpiredClientCert
    }
  }

  it should "count protocol for a client below the profile's protocols" taggedAs (UnitTest, RPCTest) in {
    refusedAs(
      config("need", """protocols = ["TLSv1.3"]"""),
      pki.clientContext(presenting = Some(pki.leaf("client"))),
      Array("TLSv1.2")
    ) shouldBe HandshakeFailureReason.Protocol
  }

  it should "count other for a client that refuses the server's certificate" taggedAs (UnitTest, RPCTest) in {
    refusedAs(
      config("need"),
      pki.clientContext(trusted = Seq(pki.otherCa().cert), presenting = Some(pki.leaf("client")))
    ) shouldBe HandshakeFailureReason.Other
  }

  it should "count nothing for a trusted client" taggedAs (UnitTest, RPCTest) in {
    val before = HandshakeFailureReason.values.map(count).sum
    MtlsTestSupport.withServer(config("need")) { port =>
      MtlsTestSupport.answered(
        MtlsTestSupport.exchange(pki.clientContext(presenting = Some(pki.leaf("client"))), port)
      ) shouldBe true
    }
    HandshakeFailureReason.values.map(count).sum shouldBe before
  }

  it should "be logged once per address per minute, naming the listener, the address and the reason" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val logger = LoggerFactory.getLogger("com.chipprbots.ethereum.tls.TlsHandshake").asInstanceOf[LogbackLogger]
    // the tests' root logger is at ERROR
    val level = logger.getLevel
    logger.setLevel(ch.qos.logback.classic.Level.WARN)
    val appender = com.chipprbots.ethereum.testing.LogCapture.open(logger.getName)
    try
      val failureLog = new HandshakeFailureLog()
      val before = count(HandshakeFailureReason.NoClientCert)
      MtlsTestSupport.withServer(config("need"), failureLog = failureLog) { port =>
        (1 to 3).foreach(_ => MtlsTestSupport.exchange(pki.clientContext(), port))
        eventually(count(HandshakeFailureReason.NoClientCert) shouldBe before + 3)
      }
      val lines = appender.list.asScala.toList.map(_.getFormattedMessage)
      lines should have size 1
      lines.head should startWith("TLS handshake refused on rpc-http from 127.0.0.1: no_client_cert")
    finally
      appender.close()
      logger.setLevel(level)
  }

  "The classifier" should "read the trust manager's verdict through the handshake exception's causes" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val refused = new ClientCertRefused(HandshakeFailureReason.ExpiredClientCert, new CertificateException("x"))
    val handshake = new SSLHandshakeException("PKIX path validation failed")
    handshake.initCause(refused)
    HandshakeFailureClassifier.classify(handshake) shouldBe HandshakeFailureReason.ExpiredClientCert
    HandshakeFailureClassifier.classify(new SSLHandshakeException("Empty client certificate chain")) shouldBe
      HandshakeFailureReason.NoClientCert
    HandshakeFailureClassifier.classify(new SSLHandshakeException("Unsupported or unrecognized SSL message")) shouldBe
      HandshakeFailureReason.Protocol
    HandshakeFailureClassifier.classify(new RuntimeException("connection reset")) shouldBe HandshakeFailureReason.Other
  }

  "The failure log" should "log the first refusal of an address, suppress the rest of the minute, then count them" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    var now = 1000000L
    val failureLog = new HandshakeFailureLog(60000L, () => now)
    val reason = HandshakeFailureReason.UntrustedClientCert
    failureLog.record("rpc-http", "10.0.0.1", reason, "d") shouldBe
      Some("TLS handshake refused on rpc-http from 10.0.0.1: untrusted_client_cert (d)")
    failureLog.record("rpc-http", "10.0.0.1", reason, "d") shouldBe None
    failureLog.record("rpc-ws", "10.0.0.1", reason, "d") shouldBe None
    // another address has its own minute
    failureLog.record("rpc-http", "10.0.0.2", reason, "d") should not be empty
    now += 60000L
    failureLog.record("rpc-http", "10.0.0.1", reason, "d").get should endWith("(2 suppressed)")
  }

  it should "forget addresses two windows after their last line, so a scan cannot grow it" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    var now = 0L
    val failureLog = new HandshakeFailureLog(60000L, () => now)
    (1 to 1000).foreach(i =>
      failureLog.record("rpc-http", s"10.0.${i / 256}.${i % 256}", HandshakeFailureReason.Other, "")
    )
    failureLog.tracked shouldBe 1000
    now += 3 * 60000L
    failureLog.record("rpc-http", "10.9.9.9", HandshakeFailureReason.Other, "")
    failureLog.tracked shouldBe 1
  }
