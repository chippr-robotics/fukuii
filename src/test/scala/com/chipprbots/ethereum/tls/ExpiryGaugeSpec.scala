package com.chipprbots.ethereum.tls

import java.security.SecureRandom
import java.time.Duration
import java.time.Instant

import scala.jdk.CollectionConverters.*

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger as LogbackLogger
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.slf4j.LoggerFactory

import com.chipprbots.ethereum.testing.Tags.*

/** Spec 030 FR-032: `tls_certificate_expiry_seconds{profile, role}` follows the certificate in service, and a WARN is
  * logged once a day while it is within the profile's `expiry-warning`.
  */
class ExpiryGaugeSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  private val dir = TlsTestSupport.tempDir()
  private val pki = new TestPki(dir)
  override protected def afterAll(): Unit = TlsTestSupport.deleteTree(dir)

  private def context(name: String, notAfter: Instant, extra: String = "") =
    val store = pki.keystore(s"$name-server", Seq("rpc" -> pki.leaf(name, notAfter = notAfter)), "pw")
    val config = TlsTestSupport.config(
      TlsTestSupport.profile(name, store, extra = extra),
      s"""tls { enabled = true, profile = "$name" }"""
    )
    TlsServerEngine.rpcHttp(config, new SecureRandom()).fold(e => fail(e.message), identity)

  private def warnings[A](body: => A): List[String] =
    val logger = LoggerFactory.getLogger("com.chipprbots.ethereum.tls.TlsReloader").asInstanceOf[LogbackLogger]
    val level = logger.getLevel
    logger.setLevel(Level.WARN)
    val appender = com.chipprbots.ethereum.testing.LogCapture.open(logger.getName)
    try
      body
      appender.list.asScala.toList.filter(_.getLevel == Level.WARN).map(_.getFormattedMessage)
    finally
      appender.close()
      logger.setLevel(level)

  "The expiry gauge" should "count the seconds to the served certificate's notAfter, and follow a reload" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val in20Days = Instant.now().plus(Duration.ofDays(20))
    val ctx = context("gauge", in20Days)
    new TlsReloader(scheduled = false).register("rpc-http", ctx)
    val seconds = TlsReloadMetrics.expirySeconds("gauge", "server").get
    // the certificate's notAfter has second precision
    seconds shouldBe (Duration.ofDays(20).toSeconds.toDouble +- 5.0)
  }

  "The expiry warning" should "be logged at once within expiry-warning, then once a day" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val start = Instant.now()
    var now = start
    val ctx = context("soon", start.plus(Duration.ofDays(5)))
    val reloader = new TlsReloader(clock = () => now, scheduled = false)
    val first = warnings(reloader.register("rpc-http", ctx))
    first should have size 1
    first.head should startWith("TLS profile soon server certificate expires in 4 days")
    warnings(reloader.poll()) shouldBe empty
    now = start.plus(Duration.ofHours(23))
    warnings(reloader.poll()) shouldBe empty
    now = start.plus(Duration.ofHours(25))
    warnings(reloader.poll()) should have size 1
  }

  it should "stay quiet outside expiry-warning, which a profile can widen" taggedAs (UnitTest, RPCTest) in {
    val in20Days = Instant.now().plus(Duration.ofDays(20))
    warnings(new TlsReloader(scheduled = false).register("rpc-http", context("later", in20Days))) shouldBe empty
    warnings(
      new TlsReloader(scheduled = false)
        .register("rpc-http", context("wide", in20Days, "expiry-warning = 30.days"))
    ) should have size 1
  }
