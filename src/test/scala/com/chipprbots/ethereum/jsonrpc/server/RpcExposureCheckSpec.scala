package com.chipprbots.ethereum.jsonrpc.server

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.jsonrpc.server.RpcExposureCheck.Transport
import com.chipprbots.ethereum.testing.Tags.*

/** The startup warning for a sensitive namespace on an HTTP or WebSocket endpoint that is not bound to loopback. */
class RpcExposureCheckSpec extends AnyFlatSpec with Matchers:

  private def http(interface: String, apis: Seq[String], enabled: Boolean = true): Transport =
    Transport("HTTP", "http", enabled, interface, apis)

  private def ws(interface: String, apis: Seq[String], enabled: Boolean = true): Transport =
    Transport("WebSocket", "ws", enabled, interface, apis)

  private val ChainApis = Seq("eth", "net", "web3", "fukuii")

  "RpcExposureCheck.isLoopback" should "accept localhost and the loopback addresses" taggedAs (UnitTest, RPCTest) in {
    Seq("localhost", "LOCALHOST", " localhost ", "127.0.0.1", "127.0.0.2", "::1", "[::1]").foreach { interface =>
      withClue(interface)(RpcExposureCheck.isLoopback(interface) shouldBe true)
    }
  }

  it should "refuse wildcard, private and public addresses and an empty value" taggedAs (UnitTest, RPCTest) in {
    Seq("0.0.0.0", "::", "[::]", "192.168.1.10", "10.0.0.5", "203.0.113.7", "2001:db8::1", "", "  ").foreach {
      interface =>
        withClue(s"'$interface'")(RpcExposureCheck.isLoopback(interface) shouldBe false)
    }
  }

  "RpcExposureCheck.warnings" should "warn for each sensitive namespace on a non-loopback endpoint" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    Seq("personal", "debug", "admin", "qa", "miner").foreach { namespace =>
      val warnings = RpcExposureCheck.warnings(Seq(http("0.0.0.0", ChainApis :+ namespace)))
      withClue(namespace) {
        warnings should have size 1
        warnings.head should include("HTTP")
        warnings.head should include("'0.0.0.0'")
        warnings.head should include(namespace)
        warnings.head should include("fukuii.network.rpc.http.apis")
      }
    }
  }

  it should "list every sensitive namespace of the endpoint in one warning, and only those" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val warnings =
      RpcExposureCheck.warnings(Seq(ws("0.0.0.0", ChainApis ++ Seq("admin", "personal", "txpool", "debug"))))
    warnings should have size 1
    warnings.head should include("personal, debug, admin")
    warnings.head should include("fukuii.network.rpc.ws.apis")
    (warnings.head should not).include("txpool")
  }

  it should "warn for HTTP and WebSocket separately" taggedAs (UnitTest, RPCTest) in {
    val warnings = RpcExposureCheck.warnings(
      Seq(http("0.0.0.0", ChainApis :+ "personal"), ws("192.168.1.10", ChainApis :+ "debug"))
    )
    warnings should have size 2
    warnings(0) should (include("HTTP").and(include("personal")))
    warnings(1) should (include("WebSocket").and(include("debug")))
  }

  it should "not warn for the default namespaces on any address" taggedAs (UnitTest, RPCTest) in {
    RpcExposureCheck.warnings(Seq(http("0.0.0.0", ChainApis), ws("::", ChainApis))) shouldBe Seq.empty
    RpcExposureCheck.warnings(Seq(http("0.0.0.0", ChainApis :+ "txpool" :+ "trace"))) shouldBe Seq.empty
  }

  it should "not warn when the endpoint is bound to loopback" taggedAs (UnitTest, RPCTest) in {
    val sensitive = ChainApis ++ Seq("personal", "debug", "admin", "qa", "miner")
    RpcExposureCheck.warnings(Seq(http("localhost", sensitive), ws("127.0.0.1", sensitive))) shouldBe Seq.empty
    RpcExposureCheck.warnings(Seq(http("::1", sensitive))) shouldBe Seq.empty
  }

  it should "not warn for an endpoint that is disabled" taggedAs (UnitTest, RPCTest) in {
    RpcExposureCheck.warnings(Seq(http("0.0.0.0", ChainApis :+ "admin", enabled = false))) shouldBe Seq.empty
  }
