package com.chipprbots.ethereum.nodebuilder

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.tls.*

/** Spec 030 FR-018, SC-008, US8 scenarios 1 and 2: each of the four listeners on each non-loopback interface, without
  * TLS, refuses the start before any port binds, with the text of contracts/observability.md; with TLS on, or with
  * `allow-insecure-bind = true` (and its WARN line), it starts; loopback starts with neither.
  */
class BindPolicyStartupSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  private val dir = TlsTestSupport.tempDir()
  private val pki = new TestPki(dir)
  private val store = pki.keystore("server", Seq("rpc" -> pki.leaf()), "pw")
  override protected def afterAll(): Unit = TlsTestSupport.deleteTree(dir)

  /** The listeners: name in the refusal, its config section, and how it is enabled. */
  private val listeners = Seq(
    ("network.rpc.http", "enabled = true"),
    ("network.rpc.ws", "enabled = true"),
    ("network.engine-api", "enabled = true"),
    ("metrics", "enabled = true, port = 0")
  )

  /** Every listener off but `key`, which binds `interface` with `extra`. */
  private def config(key: String, interface: String, extra: String = ""): Config =
    val others = listeners.filterNot(_._1 == key).map((k, _) => s"$k.enabled = false").mkString("\n")
    val enable = listeners.find(_._1 == key).get._2
    ConfigFactory
      .parseString(s"""$key { $enable, interface = "$interface", port = 0, $extra }
                      |$others
                      |tls.profiles { ${TlsTestSupport.profile("p", store)} }""".stripMargin)
      .withFallback(
        ConfigFactory.parseString("network.rpc.http { mode = \"http\", interface = \"127.0.0.1\", port = 0 }")
      )

  private val nonLoopback = Seq("0.0.0.0", "::", "192.0.2.10")

  "A plaintext listener on a non-loopback interface" should "refuse the start, naming both keys (12 cases)" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    for
      (key, _) <- listeners
      interface <- nonLoopback
    do
      withClue(s"$key on $interface: ") {
        TlsStartupCheck.run(config(key, interface)) shouldBe Left(
          s"Refusing to start: fukuii.$key binds $interface (not loopback) without TLS. Set fukuii.$key.tls.enabled = " +
            s"true with a profile, or fukuii.$key.allow-insecure-bind = true to accept plaintext on that interface " +
            "(docs/runbooks/tls-operations.md#migration)"
        )
      }
  }

  it should "start with allow-insecure-bind, and say so in a WARN line at every start" taggedAs (UnitTest, RPCTest) in {
    for
      (key, _) <- listeners
      interface <- nonLoopback
    do
      val c = config(key, interface, "allow-insecure-bind = true")
      withClue(s"$key on $interface: ")(TlsStartupCheck.run(c).isRight shouldBe true)
      val warnings = BindPolicy.insecureWarnings(c)
      warnings should have size 1
      warnings.head should include(s"://$interface:0 allow-insecure-bind=true")
  }

  it should "start with TLS on, and log no warning" taggedAs (UnitTest, RPCTest) in {
    for (key, _) <- listeners if key != "network.engine-api" do
      val c = config(key, "0.0.0.0", """tls { enabled = true, profile = "p" }""")
      withClue(key)(TlsStartupCheck.run(c).isRight shouldBe true)
      BindPolicy.insecureWarnings(c) shouldBe empty
    TlsStartupCheck
      .run(config("network.engine-api", "0.0.0.0", """tls { enabled = true, profile = "p" }"""))
      .isRight shouldBe
      true
  }

  "A loopback listener" should "start with neither TLS nor the opt-out" taggedAs (UnitTest, RPCTest) in {
    for
      (key, _) <- listeners
      interface <- Seq("localhost", "127.0.0.1", "::1")
    do withClue(s"$key on $interface: ")(TlsStartupCheck.run(config(key, interface)) shouldBe Right(Nil))
  }

  "A disabled listener" should "not be checked" taggedAs (UnitTest, RPCTest) in {
    val c = ConfigFactory
      .parseString("""metrics { enabled = false, interface = "0.0.0.0" }""")
      .withFallback(config("network.rpc.http", "127.0.0.1"))
    TlsStartupCheck.run(c) shouldBe Right(Nil)
  }

  "The deprecated mode = https" should "count as TLS for the policy" taggedAs (UnitTest, RPCTest) in {
    val legacy = config(
      "network.rpc.http",
      "0.0.0.0",
      s"""mode = "https", certificate { keystore-path = "${store.store}", keystore-type = "pkcs12", password-file = "${store.passwordFile}" }"""
    )
    TlsStartupCheck.run(legacy).isRight shouldBe true
  }
