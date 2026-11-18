package com.chipprbots.ethereum.tls

import com.typesafe.config.ConfigFactory
import org.scalacheck.Gen
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import com.chipprbots.ethereum.testing.Tags.*

/** Spec 030 FR-020–FR-023, FR-028 (data-model.md "Identity"): parsing of `network.rpc.client-identities`, and the
  * matching of a rule against a certificate.
  */
class IdentityRulesSpec extends AnyFlatSpec with Matchers with ScalaCheckPropertyChecks with BeforeAndAfterAll:

  private val dir = TlsTestSupport.tempDir()
  private val pki = new TestPki(dir)
  override protected def afterAll(): Unit = TlsTestSupport.deleteTree(dir)

  private val Apis = TlsStartupCheck.KnownApis

  private def parse(rules: String, graphql: Boolean = true) =
    IdentityRules.parse(
      ConfigFactory.parseString(s"network.rpc { client-identities = [ $rules ], graphql.enabled = $graphql }"),
      Apis
    )

  private def error(rules: String, graphql: Boolean = true): String =
    parse(rules, graphql).left.toOption.getOrElse(fail(s"accepted: $rules")).message

  "IdentityRules.parse" should "read rules in order, with apis as a string or a list" taggedAs (UnitTest, RPCTest) in {
    val rules = parse(
      """{ name = "monitor", match { san-dns = "monitor.internal" }, apis = "eth, net,WEB3" },
        |{ name = "ops", match { subject-cn = "ops", sha256 = "AB:cd:EF:0123456789abcdef0123456789abcdef0123456789abcdef0123456789" }, apis = ["eth", "admin", "graphql"] }""".stripMargin
    ).toOption.get
    rules.rules.map(_.name) shouldBe Vector("monitor", "ops")
    rules.rules.head.apis shouldBe Seq("eth", "net", "web3")
    rules.rules(1).matchOn.sha256 shouldBe Some("abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789")
    rules.rules(1).grantsGraphQl shouldBe true
    rules.rules(1).rpcApis shouldBe Seq("eth", "admin")
  }

  it should "read an absent or empty list as no rules" taggedAs (UnitTest, RPCTest) in {
    IdentityRules.parse(ConfigFactory.empty(), Apis) shouldBe Right(IdentityRules.Empty)
    parse("").map(_.isEmpty) shouldBe Right(true)
  }

  it should "refuse a rule list that cannot be served as written, naming the key" taggedAs (UnitTest, RPCTest) in {
    val m = """match { san-dns = "a" }"""
    Seq(
      s"""{ $m, apis = "eth" }""" -> "client-identities[0].name",
      s"""{ name = "Ops", $m, apis = "eth" }""" -> "metric label",
      s"""{ name = "${"x" * 33}", $m, apis = "eth" }""" -> "metric label",
      s"""{ name = "anonymous", $m, apis = "eth" }""" -> "reserved",
      s"""{ name = "unmatched", $m, apis = "eth" }""" -> "reserved",
      s"""{ name = "a", $m, apis = "eth" }, { name = "a", $m, apis = "net" }""" -> "client-identities[1]: name 'a' is used",
      """{ name = "a", apis = "eth" }""" -> "client-identities[0].match",
      """{ name = "a", match {}, apis = "eth" }""" -> "needs at least one of",
      """{ name = "a", match { san-dns = "a", subject = "b" }, apis = "eth" }""" -> "unknown keys subject",
      """{ name = "a", match { sha256 = "abc" }, apis = "eth" }""" -> "not a SHA-256 fingerprint",
      """{ name = "a", match { san-dns = " " }, apis = "eth" }""" -> "san-dns: empty",
      s"""{ name = "a", $m }""" -> "client-identities[0].apis",
      s"""{ name = "a", $m, apis = "" }""" -> "grants nothing",
      s"""{ name = "a", $m, apis = "eth,root" }""" -> "unknown namespaces: root"
    ).foreach { (rules, expected) =>
      withClue(rules)(error(rules) should include(expected))
    }
  }

  it should "refuse graphql in a rule while GraphQL is off (FR-028)" taggedAs (UnitTest, RPCTest) in {
    error("""{ name = "a", match { san-dns = "a" }, apis = "eth,graphql" }""", graphql = false) should include(
      "graphql.enabled = false"
    )
    parse("""{ name = "a", match { san-dns = "a" }, apis = "eth" }""", graphql = false).isRight shouldBe true
  }

  "A rule" should "match on each key, and on all of its keys together" taggedAs (UnitTest, RPCTest) in {
    val cert = pki.leaf("monitor", sanDns = Seq("monitor.internal"), sanUri = Seq("spiffe://corp/monitor")).cert
    val sha = CertMatch.fingerprint(cert)
    CertMatch(sanDns = Some("MONITOR.internal")).matches(cert) shouldBe true
    CertMatch(sanUri = Some("spiffe://corp/monitor")).matches(cert) shouldBe true
    CertMatch(sanUri = Some("SPIFFE://corp/monitor")).matches(cert) shouldBe false
    CertMatch(subjectCn = Some("monitor")).matches(cert) shouldBe true
    CertMatch(subjectCn = Some("Monitor")).matches(cert) shouldBe false
    CertMatch(sha256 = CertMatch.normaliseFingerprint(sha.toUpperCase.grouped(2).mkString(":"))).matches(cert) shouldBe
      true
    CertMatch(sanDns = Some("monitor.internal"), subjectCn = Some("monitor")).matches(cert) shouldBe true
    CertMatch(sanDns = Some("monitor.internal"), subjectCn = Some("ops")).matches(cert) shouldBe false
    // exact only: no wildcard
    CertMatch(sanDns = Some("*.internal")).matches(cert) shouldBe false
  }

  "IdentityRules.resolve" should "take the first rule that matches, and tell unmatched from anonymous" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val rules = parse(
      """{ name = "first", match { subject-cn = "ops" }, apis = "eth" },
        |{ name = "second", match { subject-cn = "ops" }, apis = "admin" }""".stripMargin
    ).toOption.get
    rules.resolve(Some(pki.leaf("ops").cert)) shouldBe Caller.Matched(rules.rules.head)
    rules.resolve(Some(pki.leaf("someone").cert)) shouldBe a[Caller.Unmatched]
    rules.resolve(None) shouldBe Caller.Anonymous
    Caller.Anonymous.label shouldBe "anonymous"
    Caller.Unmatched("CN=x").label shouldBe "unmatched"
    Caller.Matched(rules.rules.head).label shouldBe "first"
  }

  "effectiveApis and graphQlAllowed" should "grant a matched rule its own set, and others the transport's" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val rule = IdentityRule("ops", CertMatch(subjectCn = Some("ops")), Seq("eth", "admin", "graphql"))
    val monitor = IdentityRule("monitor", CertMatch(subjectCn = Some("m")), Seq("eth"))
    val transport = Seq("eth", "net", "web3")
    IdentityRules.effectiveApis(Caller.Matched(rule), transport) shouldBe Seq("eth", "admin")
    IdentityRules.effectiveApis(Caller.Anonymous, transport) shouldBe transport
    IdentityRules.effectiveApis(Caller.Unmatched("CN=x"), transport) shouldBe transport
    IdentityRules.graphQlAllowed(Caller.Matched(rule), graphQlEnabled = true) shouldBe true
    IdentityRules.graphQlAllowed(Caller.Matched(monitor), graphQlEnabled = true) shouldBe false
    IdentityRules.graphQlAllowed(Caller.Anonymous, graphQlEnabled = true) shouldBe true
    IdentityRules.graphQlAllowed(Caller.Unmatched("CN=x"), graphQlEnabled = false) shouldBe false
  }

  "A rule" should "never match a certificate that does not carry its values" taggedAs (UnitTest, RPCTest) in {
    val label = Gen.nonEmptyListOf(Gen.alphaLowerChar).map(_.mkString.take(12))
    // one certificate per run is enough: the property is over the rule's values, which the certificate lacks
    val cert = pki.leaf("holder", sanDns = Seq("holder.internal"), sanUri = Seq("spiffe://corp/holder")).cert
    forAll(label, label, label) { (dns, uri, cn) =>
      whenever(dns != "holder" && cn != "holder") {
        CertMatch(sanDns = Some(s"$dns.example")).matches(cert) shouldBe false
        CertMatch(sanUri = Some(s"spiffe://corp/$uri-x")).matches(cert) shouldBe false
        CertMatch(subjectCn = Some(cn)).matches(cert) shouldBe false
        // and a key the certificate carries does not rescue one it does not (AND)
        CertMatch(sanDns = Some("holder.internal"), subjectCn = Some(cn)).matches(cert) shouldBe false
      }
    }
  }
