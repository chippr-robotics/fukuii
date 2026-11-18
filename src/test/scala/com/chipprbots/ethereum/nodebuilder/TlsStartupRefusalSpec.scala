package com.chipprbots.ethereum.nodebuilder

import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path

import scala.jdk.CollectionConverters.*

import ch.qos.logback.classic.Logger as LogbackLogger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.typesafe.config.Config
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.slf4j.LoggerFactory

import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.tls.*

/** Spec 030 US1, SC-001: every TLS misconfiguration of the HTTP listener stops the node before any bind, through
  * `startupRefusal`, naming the key and the cause; a valid configuration starts; no log line or refusal holds a secret.
  */
class TlsStartupRefusalSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  private val dir = TlsTestSupport.tempDir()
  private val pki = new TestPki(dir)
  private val Planted = "PLANTED-s3cret-Pw-4242"

  override protected def afterAll(): Unit = TlsTestSupport.deleteTree(dir)

  private val good = pki.keystore("good", Seq("rpc" -> pki.leaf()), Planted)
  private val trust = pki.truststore("trust", Seq(pki.ca.cert), Planted)
  private val Listener = """tls { enabled = true, profile = "rpc" }"""

  private def cfg(profile: String, http: String = Listener): Config = TlsTestSupport.config(profile, http)

  private def goodProfile = TlsTestSupport.profile("rpc", good)

  private def captureLogs[A](body: => A): (A, Seq[String]) =
    val appender = new ListAppender[ILoggingEvent]()
    appender.start()
    val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).asInstanceOf[LogbackLogger]
    root.addAppender(appender)
    try
      val result = body
      (
        result,
        appender.list.asScala.toList.map(e =>
          e.getFormattedMessage + Option(e.getThrowableProxy).map(_.getMessage).getOrElse("")
        )
      )
    finally root.detachAppender(appender)

  private val wrongPwFile: Path =
    val f = dir.resolve("wrong.password")
    Files.writeString(f, "not-the-password-0001")
    f

  /** (what is wrong, the config, the text the refusal must hold) */
  private lazy val misconfigurations: Seq[(String, Config, Seq[String])] = Seq(
    (
      "keystore file missing",
      cfg(TlsTestSupport.profile("rpc", good).replace(good.store.toString, "/no/such/rpc.p12")),
      Seq("fukuii.tls.profiles.rpc.keystore.path: /no/such/rpc.p12 not found")
    ),
    (
      "password file missing",
      cfg(TlsTestSupport.profile("rpc", good).replace(good.passwordFile.toString, "/no/such.password")),
      Seq("fukuii.tls.profiles.rpc.keystore.password-file: /no/such.password not found")
    ),
    (
      "wrong password",
      cfg(TlsTestSupport.profile("rpc", good).replace(good.passwordFile.toString, wrongPwFile.toString)),
      Seq("fukuii.tls.profiles.rpc.keystore.path", "does not open")
    ),
    (
      "expired certificate",
      cfg(TlsTestSupport.profile("rpc", pki.keystore("exp", Seq("a" -> pki.expiredLeaf("old")), Planted))),
      Seq("fukuii.tls.profiles.rpc: certificate CN=old expired")
    ),
    (
      "not yet valid certificate",
      cfg(TlsTestSupport.profile("rpc", pki.keystore("early", Seq("a" -> pki.notYetValidLeaf("early")), Planted))),
      Seq("CN=early", "notBefore")
    ),
    (
      "no private key",
      cfg(TlsTestSupport.profile("rpc", pki.keystore("nokey", Nil, Planted))),
      Seq("fukuii.tls.profiles.rpc.keystore: the keystore has no private-key entry")
    ),
    (
      "two keys, no alias",
      cfg(TlsTestSupport.profile("rpc", pki.keystore("two", Seq("a" -> pki.leaf("a"), "b" -> pki.leaf("b")), Planted))),
      Seq("key-alias")
    ),
    (
      "protocol below TLSv1.2",
      cfg(TlsTestSupport.profile("rpc", good, extra = """protocols = ["TLSv1.1"]""")),
      Seq("fukuii.tls.profiles.rpc.protocols", "TLSv1.1", "below TLSv1.2")
    ),
    (
      "profile does not exist",
      cfg(goodProfile, """tls { enabled = true, profile = "nope" }"""),
      Seq("fukuii.network.rpc.http.tls.profile", "'nope'")
    ),
    (
      "tls enabled without a profile",
      cfg(goodProfile, "tls { enabled = true }"),
      Seq("fukuii.network.rpc.http.tls.profile: required")
    ),
    (
      "client-auth need without a truststore",
      cfg(goodProfile, """tls { enabled = true, profile = "rpc", client-auth = "need" }"""),
      Seq("fukuii.network.rpc.http.tls: client-auth = need, but profile rpc has no truststore")
    ),
    (
      "client-auth not a mode",
      cfg(goodProfile, """tls { enabled = true, profile = "rpc", client-auth = "optional" }"""),
      Seq("fukuii.network.rpc.http.tls.client-auth")
    ),
    (
      "client-auth need is not served by this build",
      cfg(
        TlsTestSupport.profile("rpc", good, Some(trust)),
        """tls { enabled = true, profile = "rpc", client-auth = "need" }"""
      ),
      Seq("fukuii.network.rpc.http.tls.client-auth", "not served")
    ),
    (
      "legacy and new form together",
      cfg(
        goodProfile,
        s"""mode = "https", tls { enabled = true, profile = "rpc" },
           |certificate { keystore-path = "${good.store}", keystore-type = "pkcs12", password-file = "${good.passwordFile}" }""".stripMargin
      ),
      Seq("fukuii.network.rpc.http", "deprecated")
    ),
    (
      "legacy https without a certificate",
      cfg("", """mode = "https""""),
      Seq("fukuii.network.rpc.http.certificate is not set")
    ),
    (
      "profile named system",
      cfg(TlsTestSupport.profile("system", good)),
      Seq("fukuii.tls.profiles.system", "reserved")
    )
  )

  "The pre-bind TLS check" should "refuse each misconfiguration with the key and the cause, through startupRefusal" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    misconfigurations.size should be >= 10
    misconfigurations.foreach { (what, config, expected) =>
      val refusal = TlsStartupCheck.run(config).left.getOrElse(fail(s"$what: the start was not refused"))
      refusal should startWith("Refusing to start: ")
      expected.foreach(text => withClue(s"$what: ")(refusal should include(text)))
      val thrown = intercept[EngineApiStartupRefusal](throw StdNode.startupRefusal(refusal))
      thrown.getMessage shouldBe refusal
    }
  }

  it should "bind nothing while it refuses: the configured port stays free" taggedAs (UnitTest, RPCTest) in {
    val free = new ServerSocket(0)
    val port = free.getLocalPort
    free.close()
    val config = cfg(goodProfile, """tls { enabled = true, profile = "nope" }""")
      .withFallback(com.typesafe.config.ConfigFactory.parseString(s"network.rpc.http.port = $port"))
    TlsStartupCheck.run(config.resolve()).isLeft shouldBe true
    val probe = new ServerSocket(port)
    try probe.isBound shouldBe true
    finally probe.close()
  }

  it should "pass a valid profile, with a start-up line naming scheme, client-auth, profile and certificate" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val lines = TlsStartupCheck.run(cfg(goodProfile)).toOption.get
    lines should have size 1
    val text = lines.head.render
    text should (include("rpc-http https://127.0.0.1:")
      .and(include("client-auth=none"))
      .and(include("profile=rpc"))
      .and(include("CN=localhost"))
      .and(include("notAfter=")))
  }

  it should "say nothing for a plaintext listener and for a disabled one" taggedAs (UnitTest, RPCTest) in {
    TlsStartupCheck.run(cfg(goodProfile, "")) shouldBe Right(Nil)
    // a disabled listener is not validated: its broken profile does not stop the node
    val broken = cfg(goodProfile, """enabled = false, tls { enabled = true, profile = "nope" }""")
    TlsStartupCheck.run(broken) shouldBe Right(Nil)
  }

  it should "map the legacy mode = https to the new keys' behaviour, and flag it for the deprecation warning" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val legacy = cfg(
      "",
      s"""mode = "https", certificate { keystore-path = "${good.store}", keystore-type = "pkcs12", password-file = "${good.passwordFile}" }"""
    )
    val lines = TlsStartupCheck.run(legacy).toOption.get
    lines.map(_.profile) shouldBe Seq(TlsConfig.LegacyProfileName)
    lines.head.clientAuth shouldBe ClientAuth.None
    TlsStartupCheck.usesLegacy(legacy) shouldBe true
    TlsStartupCheck.usesLegacy(cfg(goodProfile)) shouldBe false
  }

  it should "never put a planted password in a refusal or a log line" taggedAs (UnitTest, RPCTest) in {
    val (refusals, logs) = captureLogs {
      val all = misconfigurations.map((_, c, _) => TlsStartupCheck.run(c))
      TlsStartupCheck.run(cfg(goodProfile))
      TlsServerEngine.rpcHttp(cfg(goodProfile, Listener), new java.security.SecureRandom())
      all
    }
    val text = refusals.flatMap(_.left.toOption) ++ logs
    text.foreach { line =>
      (line should not).include(Planted)
      (line should not).include("not-the-password-0001")
    }
    // the planted password really is the keystore's: the good profile loaded with it
    TlsStartupCheck.run(cfg(goodProfile)).isRight shouldBe true
  }
