package com.chipprbots.ethereum.tls

import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

/** Spec 030 contracts/config.md, FR-028, FR-060: identity rules in the pre-bind check. */
class IdentityStartupSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  private val dir = TlsTestSupport.tempDir()
  private val pki = new TestPki(dir)
  private val server = pki.keystore("server", Seq("rpc" -> pki.leaf()), "srv-pw")
  private val trust = pki.truststore("trust", Seq(pki.ca.cert), "trust-pw")
  override protected def afterAll(): Unit = TlsTestSupport.deleteTree(dir)

  private val profiles = TlsTestSupport.profile("rpc", server, Some(trust))
  private val Monitor = """{ name = "monitor", match { san-dns = "monitor.internal" }, apis = "eth,net,web3" }"""
  private val Ops = """{ name = "ops", match { san-dns = "ops.internal" }, apis = "eth,admin,graphql" }"""

  private def config(http: String, rules: String, graphql: Boolean = true, ws: String = "") =
    TlsTestSupport
      .config(profiles, http, ws)
      .withFallback(
        com.typesafe.config.ConfigFactory.parseString(
          s"network.rpc { client-identities = [ $rules ], graphql.enabled = $graphql }"
        )
      )

  private val Need = """tls { enabled = true, profile = "rpc", client-auth = "need" }"""

  "The pre-bind check" should "refuse a rule naming graphql while GraphQL is off" taggedAs (UnitTest, RPCTest) in {
    val refusal = TlsStartupCheck.run(config(Need, Ops, graphql = false)).left.toOption.get
    refusal should startWith("Refusing to start: fukuii.network.rpc.client-identities[0]")
    refusal should include("graphql.enabled = false")
  }

  it should "refuse rules when no enabled listener asks for client certificates" taggedAs (UnitTest, RPCTest) in {
    Seq(
      "",
      """tls { enabled = true, profile = "rpc", client-auth = "none" }""",
      """enabled = false, tls { enabled = true, profile = "rpc", client-auth = "need" }"""
    ).foreach { http =>
      withClue(http) {
        TlsStartupCheck.run(config(http, Monitor)).left.toOption.get should include("no rule could ever match")
      }
    }
    // a want listener on WS alone is enough
    TlsStartupCheck
      .run(
        config("", Monitor, ws = """enabled = true, tls { enabled = true, profile = "rpc", client-auth = "want" }""")
      )
      .isRight shouldBe true
  }

  it should "refuse a rule that does not parse" taggedAs (UnitTest, RPCTest) in {
    TlsStartupCheck
      .run(config(Need, """{ name = "x", match { san-dns = "a" }, apis = "nope" }"""))
      .left
      .toOption
      .get should
      include("unknown namespaces: nope")
  }

  it should "list each rule's grant under the listener that serves it (FR-060)" taggedAs (UnitTest, RPCTest) in {
    val lines = TlsStartupCheck.run(config(Need, s"$Monitor, $Ops")).toOption.get
    lines.flatMap(_.renderAll) shouldBe Seq(
      lines.head.render,
      "TLS listener rpc-http rule monitor: eth,net,web3,rpc",
      "TLS listener rpc-http rule ops: eth,admin,graphql,rpc"
    )
    lines.head.render should endWith("rules=2")
  }

  it should "say nothing of rules for a listener that does not resolve identities, and of no rules" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val lines = TlsStartupCheck.run(config(Need, "")).toOption.get
    lines.flatMap(_.renderAll) shouldBe Seq(lines.head.render)
    (lines.head.render should not).include("rules=")
  }
