package com.chipprbots.ethereum.tls

import java.nio.file.Files
import java.nio.file.Paths

import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

/** One case per row of data-model.md's validation table, each asserting `<config path>: <cause>`; a planted password
  * never appears in a message (spec 030 FR-006, FR-010).
  */
class TlsMaterialLoaderSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  private val dir = TlsTestSupport.tempDir()
  private val pki = new TestPki(dir)
  private val Planted = "PLANTED-s3cret-Pw-7731"
  private val Wrong = "WRONG-pw-xyz-1190"

  override protected def afterAll(): Unit = TlsTestSupport.deleteTree(dir)

  private def profile(
      files: TestPki#Files_,
      trust: Option[TestPki#Files_] = None,
      alias: Option[String] = None,
      protocols: Seq[String] = Nil,
      storeType: String = "pkcs12"
  ): TlsProfile =
    TlsProfile(
      "rpc",
      "fukuii.tls.profiles.rpc",
      Some(files.ref(storeType)),
      alias,
      trust.map(_.ref("pkcs12")),
      protocols,
      Nil
    )

  private def loadErr(p: TlsProfile, auth: ClientAuth = ClientAuth.None): String =
    TlsMaterialLoader.load(p, auth, "fukuii.network.rpc.http.tls").left.toOption.getOrElse(fail("was accepted")).message

  private val good = pki.keystore("good", Seq("server" -> pki.leaf()), Planted)

  "TlsMaterialLoader" should "load a good profile, a trailing newline in the password file or not" taggedAs (UnitTest) in {
    TlsMaterialLoader.load(profile(good), ClientAuth.None, "x").isRight shouldBe true
    val nl = pki.keystore("nl", Seq("server" -> pki.leaf()), Planted, trailingNewline = true)
    TlsMaterialLoader.load(profile(nl), ClientAuth.None, "x").isRight shouldBe true
    val jks = pki.keystore("jks", Seq("server" -> pki.leaf()), Planted, storeType = "jks")
    TlsMaterialLoader.load(profile(jks, storeType = "jks"), ClientAuth.None, "x").isRight shouldBe true
  }

  it should "name a missing keystore, with the file" taggedAs (UnitTest) in {
    val p = profile(good).copy(keystore = Some(good.ref("pkcs12").copy(path = Paths.get("/no/such/rpc.p12"))))
    loadErr(p) shouldBe "fukuii.tls.profiles.rpc.keystore.path: /no/such/rpc.p12 not found"
  }

  it should "name a missing password file and a directory given as a file" taggedAs (UnitTest) in {
    val missing = profile(good).copy(keystore = Some(good.ref("pkcs12").copy(passwordFile = Paths.get("/no/pw"))))
    loadErr(missing) shouldBe "fukuii.tls.profiles.rpc.keystore.password-file: /no/pw not found"
    val asDir = profile(good).copy(keystore = Some(good.ref("pkcs12").copy(path = dir)))
    loadErr(asDir) should include("is not a regular file")
  }

  it should "refuse a wrong password without printing either password" taggedAs (UnitTest) in {
    val wrongFile = pki.truststore("wrongpw", Seq(pki.ca.cert), Wrong)
    val bad = good.copy(passwordFile = wrongFile.passwordFile)
    val msg = loadErr(profile(bad))
    msg should (include("fukuii.tls.profiles.rpc.keystore.path").and(include("does not open")))
    (msg should not).include(Wrong)
    (msg should not).include(Planted)
  }

  it should "refuse a keystore without a private key, with several and no alias, and an alias that names none" taggedAs (
    UnitTest
  ) in {
    val empty = pki.keystore("empty", Nil, Planted)
    loadErr(profile(empty)) should include("fukuii.tls.profiles.rpc.keystore: the keystore has no private-key entry")
    val two = pki.keystore("two", Seq("a" -> pki.leaf("a"), "b" -> pki.leaf("b")), Planted)
    loadErr(profile(two)) should (include("2 private keys").and(include("key-alias")))
    loadErr(profile(two, alias = Some("c"))) should include("key-alias c is not a private-key entry")
  }

  it should "select the named alias" taggedAs (UnitTest) in {
    val two = pki.keystore("two2", Seq("a" -> pki.leaf("a"), "b" -> pki.leaf("b")), Planted)
    val loaded = TlsMaterialLoader.load(profile(two, alias = Some("b")), ClientAuth.None, "x").toOption.get
    loaded.leaf.get.getSubjectX500Principal.getName shouldBe "CN=b"
  }

  it should "refuse an expired leaf and a not-yet-valid one, naming the subject and the date" taggedAs (UnitTest) in {
    val expired = pki.keystore("expired", Seq("s" -> pki.expiredLeaf("old")), Planted)
    loadErr(profile(expired)) should (include("fukuii.tls.profiles.rpc: certificate CN=old expired").and(
      include("(notAfter)")
    ))
    val early = pki.keystore("early", Seq("s" -> pki.notYetValidLeaf("early")), Planted)
    loadErr(profile(early)) should (include("CN=early").and(include("(notBefore)")))
  }

  it should "need a truststore under want and need, and a non-empty one" taggedAs (UnitTest) in {
    loadErr(profile(good), ClientAuth.Need) shouldBe
      "fukuii.network.rpc.http.tls: client-auth = need, but profile rpc has no truststore"
    loadErr(profile(good), ClientAuth.Want) should include("client-auth = want")
    val emptyTrust = pki.truststore("emptytrust", Nil)
    loadErr(profile(good, Some(emptyTrust)), ClientAuth.Need) should
      include("fukuii.tls.profiles.rpc.truststore: the truststore holds no certificate")
  }

  it should "refuse protocols below TLSv1.2 and ones the JDK does not know" taggedAs (UnitTest) in {
    loadErr(profile(good, protocols = Seq("TLSv1.1"))) should (include("rpc.protocols").and(include("below TLSv1.2")))
    loadErr(profile(good, protocols = Seq("TLSv1.3", "SSLv3"))) should include("below TLSv1.2")
    loadErr(profile(good, protocols = Seq("TLSv9"))) should include("not a protocol this JDK supports")
    TlsMaterialLoader.load(profile(good, protocols = Seq("TLSv1.2")), ClientAuth.None, "x").isRight shouldBe true
  }

  it should "refuse a profile with no keystore, naming the listener" taggedAs (UnitTest) in {
    loadErr(profile(good).copy(keystore = None)) should
      (include("fukuii.network.rpc.http.tls").and(include("has no keystore")))
  }

  it should "refuse an unknown store type" taggedAs (UnitTest) in {
    loadErr(profile(good, storeType = "bks")) should include("fukuii.tls.profiles.rpc.keystore.type")
  }

  it should "name legacy keys in the form the operator wrote them" taggedAs (UnitTest) in {
    val legacy = profile(good).copy(
      configPath = "fukuii.network.rpc.http.certificate",
      legacy = true,
      keystore = Some(good.ref("pkcs12").copy(path = Paths.get("/no/legacy.p12")))
    )
    loadErr(legacy) shouldBe "fukuii.network.rpc.http.certificate.keystore-path: /no/legacy.p12 not found"
    Files.exists(good.store) shouldBe true
  }
