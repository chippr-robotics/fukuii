package com.chipprbots.ethereum.tls

import java.security.SecureRandom

import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

/** The server context never trusts its own keystore (FR-004); no engine offers a protocol below TLSv1.2 (FR-006); the
  * key alias picks the certificate that is presented.
  */
class TlsContextsSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  private val dir = TlsTestSupport.tempDir()
  private val pki = new TestPki(dir)

  override protected def afterAll(): Unit = TlsTestSupport.deleteTree(dir)

  private def server(profile: TlsProfile, auth: ClientAuth = ClientAuth.None): TlsServerContext =
    val material = TlsMaterialLoader.load(profile, auth, "x").toOption.getOrElse(fail("did not load"))
    TlsContexts.server(profile, material, auth, new SecureRandom())

  private def profileOf(keys: TestPki#Files_, trust: Option[TestPki#Files_] = None, alias: Option[String] = None) =
    TlsProfile("rpc", "fukuii.tls.profiles.rpc", Some(keys.ref("pkcs12")), alias, trust.map(_.ref("pkcs12")), Nil, Nil)

  "A server context" should "trust nothing when the profile has no truststore, never its own keystore" taggedAs (UnitTest) in {
    val ks = pki.keystore("own", Seq("s" -> pki.leaf()))
    val ctx = server(profileOf(ks))
    ctx.material.trustManager.getAcceptedIssuers shouldBe empty
    // the server's own leaf is not accepted as a client certificate
    val own = ctx.material.leaf.get
    an[java.security.cert.CertificateException] should be thrownBy
      ctx.material.trustManager.checkClientTrusted(Array(own), "ECDHE_ECDSA")
  }

  it should "trust the truststore's authorities when there is one" taggedAs (UnitTest) in {
    val ks = pki.keystore("own2", Seq("s" -> pki.leaf()))
    val trust = pki.truststore("clients", Seq(pki.ca.cert))
    server(profileOf(ks, Some(trust)), ClientAuth.None).material.trustManager.getAcceptedIssuers
      .map(_.getSubjectX500Principal.getName) should contain("CN=Test CA")
  }

  it should "give engines TLSv1.2 and above only, whatever the profile says" taggedAs (UnitTest) in {
    val ks = pki.keystore("proto", Seq("s" -> pki.leaf()))
    val ctx = server(profileOf(ks))
    val enabled = TlsServerEngine.newEngine(ctx).getEnabledProtocols.toSet
    enabled should not be empty
    enabled.foreach(p => Set("TLSv1.2", "TLSv1.3") should contain(p))
    val only12 = server(profileOf(ks).copy(protocols = Seq("TLSv1.2")))
    TlsServerEngine.newEngine(only12).getEnabledProtocols.toSeq shouldBe Seq("TLSv1.2")
  }

  it should "request no client certificate under none" taggedAs (UnitTest) in {
    val ks = pki.keystore("none", Seq("s" -> pki.leaf()))
    val engine = TlsServerEngine.newEngine(server(profileOf(ks)))
    engine.getUseClientMode shouldBe false
    engine.getNeedClientAuth shouldBe false
    engine.getWantClientAuth shouldBe false
  }

  it should "present the certificate of the named alias" taggedAs (UnitTest) in {
    val ks = pki.keystore("alias", Seq("a" -> pki.leaf("a"), "b" -> pki.leaf("b")))
    val km = server(profileOf(ks, alias = Some("b"))).material.keyManager.get
    (km.getServerAliases("EC", null) should have).length(1)
    km.getCertificateChain(km.getServerAliases("EC", null).head).head.getSubjectX500Principal.getName shouldBe "CN=b"
  }
