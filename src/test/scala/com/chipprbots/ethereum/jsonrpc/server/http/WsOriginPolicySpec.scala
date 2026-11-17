package com.chipprbots.ethereum.jsonrpc.server.http

import com.typesafe.config.ConfigFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.jsonrpc.server.http.JsonRpcWsServer.JsonRpcWsServerConfig
import com.chipprbots.ethereum.testing.Tags.*

/** The WebSocket `Origin` allow-list: which origins are accepted, and how entries and header values are read. The
  * matching rules follow go-ethereum's `--ws.origins` (`rpc/websocket.go`, `wsHandshakeValidator`).
  */
class WsOriginPolicySpec extends AnyFlatSpec with Matchers:

  private def allowedBy(entries: Seq[String], origins: String*): Unit =
    val policy = WsOriginPolicy(entries)
    origins.foreach(o => withClue(s"$o under $entries: ")(policy.allows(o) shouldBe true))

  private def refusedBy(entries: Seq[String], origins: String*): Unit =
    val policy = WsOriginPolicy(entries)
    origins.foreach(o => withClue(s"$o under $entries: ")(policy.allows(o) shouldBe false))

  // ---- default (no entries) ----

  "WsOriginPolicy with no entries" should "allow the loopback origins on any port, over http or https" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    allowedBy(
      Seq.empty,
      "http://localhost",
      "http://localhost:3000",
      "https://localhost:8443",
      "http://127.0.0.1",
      "http://127.0.0.1:8080",
      "http://[::1]",
      "http://[::1]:3000",
      "HTTP://LOCALHOST:3000"
    )
  }

  it should "refuse every other origin" taggedAs (UnitTest, RPCTest) in {
    refusedBy(
      Seq.empty,
      "http://evil.example",
      "https://evil.example:8545",
      "http://localhost.evil.example",
      "http://127.0.0.1.evil.example",
      "http://evil.example/localhost",
      "http://localhost@evil.example",
      "http://localhost:80@evil.example",
      "http://notlocalhost",
      "http://192.168.1.10:3000",
      "ftp://localhost",
      "null",
      "",
      "localhost",
      "http://localhost:abc",
      "http://localhost:123456"
    )
  }

  it should "treat blank entries as no entries" taggedAs (UnitTest, RPCTest) in {
    allowedBy(Seq("", "  "), "http://localhost:3000")
    refusedBy(Seq("", "  "), "http://evil.example")
  }

  // ---- wildcard ----

  "WsOriginPolicy with *" should "allow any origin, including a missing or unparsable one" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    allowedBy(Seq("*"), "http://evil.example", "null", "", "not an origin at all", "http://localhost:3000")
    allowedBy(Seq("http://a.example", " * "), "http://evil.example")
  }

  // ---- explicit entries ----

  "WsOriginPolicy with entries" should "replace the default rather than add to it" taggedAs (UnitTest, RPCTest) in {
    val entries = Seq("https://dapp.example")
    allowedBy(entries, "https://dapp.example", "https://dapp.example:8443")
    refusedBy(entries, "http://localhost", "http://localhost:3000", "http://dapp.example", "https://other.example")
  }

  it should "allow the default again when it is listed too" taggedAs (UnitTest, RPCTest) in {
    val entries = Seq("https://dapp.example", "http://localhost")
    allowedBy(entries, "https://dapp.example", "http://localhost:3000")
    refusedBy(entries, "http://127.0.0.1:3000")
  }

  it should "match a port only when the entry names one, and compare it as written" taggedAs (UnitTest, RPCTest) in {
    val entries = Seq("http://app.example:3000")
    allowedBy(entries, "http://app.example:3000")
    refusedBy(entries, "http://app.example:3001", "http://app.example", "http://app.example:30000")
    // geth does not normalise default ports either
    refusedBy(Seq("http://app.example:80"), "http://app.example")
    refusedBy(Seq("http://app.example"), "http://other.example:80")
  }

  it should "match a scheme only when the entry names one" taggedAs (UnitTest, RPCTest) in {
    allowedBy(Seq("http://app.example"), "http://app.example:1")
    refusedBy(Seq("http://app.example"), "https://app.example")
    // a scheme-less entry allows any scheme
    allowedBy(Seq("app.example"), "http://app.example", "https://app.example:9", "ws://app.example")
    allowedBy(Seq("app.example:3000"), "https://app.example:3000")
    refusedBy(Seq("app.example:3000"), "https://app.example:3001", "https://app.example")
  }

  it should "ignore case in both the entry and the origin" taggedAs (UnitTest, RPCTest) in {
    allowedBy(Seq("HTTP://App.Example:3000"), "http://app.example:3000", "HTTP://APP.EXAMPLE:3000")
    allowedBy(Seq("http://app.example"), "Http://APP.example")
  }

  it should "compare the host exactly: no suffix, no prefix, no subdomain match" taggedAs (UnitTest, RPCTest) in {
    val entries = Seq("http://app.example")
    refusedBy(
      entries,
      "http://sub.app.example",
      "http://app.example.evil.test",
      "http://xapp.example",
      "http://app.example."
    )
  }

  it should "accept several entries, a trailing slash, and surrounding blanks" taggedAs (UnitTest, RPCTest) in {
    val entries = Seq(" http://a.example/ ", "https://b.example:8443", "")
    allowedBy(entries, "http://a.example", "http://a.example:81", "https://b.example:8443")
    refusedBy(entries, "https://b.example", "http://c.example")
  }

  it should "read bracketed IPv6 hosts" taggedAs (UnitTest, RPCTest) in {
    allowedBy(Seq("http://[::1]:8545"), "http://[::1]:8545")
    refusedBy(Seq("http://[::1]:8545"), "http://[::1]:8546", "http://[::1]", "http://[::2]:8545")
    allowedBy(Seq("http://[2001:db8::1]"), "http://[2001:db8::1]:3000")
    allowedBy(Seq("[::1]"), "http://[::1]:3000", "https://[::1]")
  }

  it should "ignore an entry that is not an origin, instead of letting it match everything" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    // geth would let `http://` match every http host; here it matches nothing
    refusedBy(Seq("http://"), "http://evil.example", "http://localhost", "http://")
    refusedBy(Seq("://app.example"), "http://app.example")
    refusedBy(Seq("http://[::1"), "http://[::1]")
    refusedBy(Seq("http://a b.example"), "http://a b.example")
    refusedBy(Seq(":3000"), "http://app.example:3000")
    // a bad entry does not disturb a good one next to it
    allowedBy(Seq("http://", "http://app.example"), "http://app.example")
    refusedBy(Seq("http://", "http://app.example"), "http://evil.example")
  }

  // ---- whole-request decision ----

  "WsOriginPolicy.permits" should "allow a request that carries no Origin header" taggedAs (UnitTest, RPCTest) in {
    WsOriginPolicy(Seq.empty).permits(Nil) shouldBe true
    WsOriginPolicy(Seq("https://dapp.example")).permits(Nil) shouldBe true
  }

  it should "decide a request with an Origin header by the allow-list" taggedAs (UnitTest, RPCTest) in {
    val policy = WsOriginPolicy(Seq.empty)
    policy.permits(Seq("http://localhost:3000")) shouldBe true
    policy.permits(Seq("http://evil.example")) shouldBe false
    // an empty header value is a header that is present, not a missing one
    policy.permits(Seq("")) shouldBe false
  }

  it should "require every Origin header of the request to be allowed" taggedAs (UnitTest, RPCTest) in {
    val policy = WsOriginPolicy(Seq.empty)
    policy.permits(Seq("http://localhost", "http://127.0.0.1:1")) shouldBe true
    policy.permits(Seq("http://localhost", "http://evil.example")) shouldBe false
    policy.permits(Seq("http://evil.example", "http://localhost")) shouldBe false
  }

  // ---- parsing ----

  "WsOriginPolicy.parse" should "split scheme, host and port" taggedAs (UnitTest, RPCTest) in {
    import WsOriginPolicy.OriginSpec
    WsOriginPolicy.parse("http://Localhost:8545") shouldBe Some(OriginSpec("http", "localhost", "8545"))
    WsOriginPolicy.parse("https://example.org") shouldBe Some(OriginSpec("https", "example.org", ""))
    WsOriginPolicy.parse("http://[::1]:3000") shouldBe Some(OriginSpec("http", "::1", "3000"))
    WsOriginPolicy.parse("localhost:3000") shouldBe Some(OriginSpec("", "localhost", "3000"))
    WsOriginPolicy.parse("localhost") shouldBe Some(OriginSpec("", "localhost", ""))
    WsOriginPolicy.parse("http://localhost:") shouldBe Some(OriginSpec("http", "localhost", ""))
    WsOriginPolicy.parse("  http://a.example/path?q#f  ") shouldBe Some(OriginSpec("http", "a.example", ""))
  }

  it should "reject what is not an origin" taggedAs (UnitTest, RPCTest) in {
    val bad = Seq(
      "http://a:1:2",
      "http://a:b",
      "http://[::1",
      "http://[::1]x",
      "http://[zz]",
      "http://[localhost]",
      "http://user@host",
      "http://ho st",
      "http://ho%73t",
      "://host",
      "1http://host",
      "http://host:1234567"
    )
    bad.foreach(s => withClue(s)(WsOriginPolicy.parse(s) shouldBe None))
  }

  "WsOriginPolicy.sanitize" should "bound the length and replace control characters" taggedAs (UnitTest, RPCTest) in {
    WsOriginPolicy.sanitize("http://a\r\nb\u0000c\u007fd") shouldBe "http://a??b?c?d"
    WsOriginPolicy.sanitize("x" * 500).length shouldBe 120
  }

  // ---- config key ----

  "JsonRpcWsServerConfig" should "read allowed-origins as a list, as a comma-separated string, or leave it empty" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    def wsBlock(extra: String) = ConfigFactory.parseString(s"""
      network.rpc.ws {
        enabled = true
        interface = "localhost"
        port = 8552
        $extra
      }""")

    JsonRpcWsServerConfig(wsBlock("")).allowedOrigins shouldBe Seq.empty
    JsonRpcWsServerConfig(wsBlock("allowed-origins = null")).allowedOrigins shouldBe Seq.empty
    JsonRpcWsServerConfig(wsBlock("allowed-origins = []")).allowedOrigins shouldBe Seq.empty
    JsonRpcWsServerConfig(
      wsBlock("""allowed-origins = ["http://a.example", "https://b.example:8443"]""")
    ).allowedOrigins shouldBe Seq("http://a.example", "https://b.example:8443")
    JsonRpcWsServerConfig(
      wsBlock("""allowed-origins = "http://a.example, https://b.example:8443"""")
    ).allowedOrigins shouldBe Seq("http://a.example", " https://b.example:8443")
    JsonRpcWsServerConfig(wsBlock("""allowed-origins = "*"""")).allowedOrigins shouldBe Seq("*")

    // the comma-separated form reaches the policy without the blank
    WsOriginPolicy(Seq("http://a.example", " https://b.example:8443")).allows("https://b.example:8443") shouldBe true
  }
