package com.chipprbots.ethereum.tls

import java.nio.file.Files

import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

/** Spec 030 FR-001..FR-003, FR-007: profiles and listener blocks parse, `client-auth` is exactly none/want/need, and
  * the legacy `mode = "https"` maps to an implicit profile.
  */
class TlsConfigSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  private val dir = TlsTestSupport.tempDir()
  private val pki = new TestPki(dir)
  private val server = pki.keystore("server", Seq("server" -> pki.leaf()))
  private val trust = pki.truststore("trust", Seq(pki.ca.cert))

  override protected def afterAll(): Unit = TlsTestSupport.deleteTree(dir)

  private val rpc =
    TlsTestSupport.profile("rpc", server, Some(trust), """key-alias = "server", protocols = ["TLSv1.3"]""")

  "TlsConfig.profiles" should "parse a profile's stores, alias, protocols and suites" taggedAs (UnitTest) in {
    val parsed = TlsConfig.profiles(TlsTestSupport.config(rpc)).toOption.get
    val p = parsed("rpc")
    p.keystore.map(_.path) shouldBe Some(server.store)
    p.truststore.map(_.path) shouldBe Some(trust.store)
    p.keyAlias shouldBe Some("server")
    p.protocols shouldBe Seq("TLSv1.3")
    p.cipherSuites shouldBe Nil
  }

  it should "reserve the name system" taggedAs (UnitTest) in {
    val cfg = TlsTestSupport.config(TlsTestSupport.profile("system", server))
    TlsConfig.profiles(cfg).left.toOption.get.message should (include("fukuii.tls.profiles.system").and(
      include("reserved")
    ))
  }

  it should "be empty when no profile is defined" taggedAs (UnitTest) in {
    TlsConfig.profiles(ConfigFactory.empty()) shouldBe Right(Map.empty)
  }

  "TlsConfig.rpcHttp" should "be plaintext when tls is off" taggedAs (UnitTest) in {
    TlsConfig.rpcHttp(TlsTestSupport.config(rpc)) shouldBe Right(None)
  }

  it should "resolve a tls block to its profile" taggedAs (UnitTest) in {
    val cfg = TlsTestSupport.config(rpc, """tls { enabled = true, profile = "rpc", client-auth = "none" }""")
    val resolved = TlsConfig.rpcHttp(cfg).toOption.get.get
    resolved.profile.name shouldBe "rpc"
    resolved.listener.clientAuth shouldBe ClientAuth.None
    resolved.legacy shouldBe false
  }

  it should "default client-auth to none" taggedAs (UnitTest) in {
    val cfg = TlsTestSupport.config(rpc, """tls { enabled = true, profile = "rpc" }""")
    TlsConfig.rpcHttp(cfg).toOption.get.get.listener.clientAuth shouldBe ClientAuth.None
  }

  it should "accept exactly none, want and need" taggedAs (UnitTest) in {
    Seq("none" -> ClientAuth.None, "want" -> ClientAuth.Want, "need" -> ClientAuth.Need).foreach { (text, expected) =>
      val cfg = TlsTestSupport.config(rpc, s"""tls { enabled = true, profile = "rpc", client-auth = "$text" }""")
      TlsConfig.rpcHttp(cfg).toOption.get.get.listener.clientAuth shouldBe expected
    }
    Seq("NEED", "optional", "required", "true", "").foreach { text =>
      val cfg = TlsTestSupport.config(rpc, s"""tls { enabled = true, profile = "rpc", client-auth = "$text" }""")
      TlsConfig.rpcHttp(cfg).left.toOption.get.message should
        (include("fukuii.network.rpc.http.tls.client-auth").and(include("none, want or need")))
    }
  }

  it should "need a profile when enabled, and a profile that exists" taggedAs (UnitTest) in {
    val none = TlsTestSupport.config(rpc, "tls { enabled = true }")
    TlsConfig.rpcHttp(none).left.toOption.get.message should include("fukuii.network.rpc.http.tls.profile")
    val unknown = TlsTestSupport.config(rpc, """tls { enabled = true, profile = "nope" }""")
    TlsConfig.rpcHttp(unknown).left.toOption.get.message should (include("tls.profile").and(include("'nope'")))
  }

  it should "map the legacy mode = https and certificate to an implicit profile without a truststore" taggedAs (UnitTest) in {
    val cfg = TlsTestSupport.config(
      http =
        s"""mode = "https", certificate { keystore-path = "${server.store}", keystore-type = "pkcs12", password-file = "${server.passwordFile}" }"""
    )
    val resolved = TlsConfig.rpcHttp(cfg).toOption.get.get
    resolved.legacy shouldBe true
    resolved.listener.clientAuth shouldBe ClientAuth.None
    resolved.profile.truststore shouldBe None
    resolved.profile.keystore.map(_.path) shouldBe Some(server.store)
    TlsStartupCheck.usesLegacy(cfg) shouldBe true
    TlsConfig.LegacyDeprecation should (include("tls { enabled, profile, client-auth }").and(include("0.13.0")))
  }

  it should "refuse both forms on one listener" taggedAs (UnitTest) in {
    val cfg = TlsTestSupport.config(
      rpc,
      s"""mode = "https", tls { enabled = true, profile = "rpc" },
         |certificate { keystore-path = "${server.store}", keystore-type = "pkcs12", password-file = "${server.passwordFile}" }""".stripMargin
    )
    TlsConfig.rpcHttp(cfg).left.toOption.get.message should (include("tls.enabled").and(include("mode")))
    Files.exists(server.store) shouldBe true
  }
