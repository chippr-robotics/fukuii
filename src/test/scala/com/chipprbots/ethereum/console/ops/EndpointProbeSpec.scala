package com.chipprbots.ethereum.console.ops

import scala.collection.mutable

import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.console.ops.EndpointProbe.Endpoint
import com.chipprbots.ethereum.console.ops.EndpointProbe.Kind
import com.chipprbots.ethereum.testing.Tags.*

/** `test-rpc` (spec 029 FR-054, T097; contracts/actions.md): a line for each enabled endpoint, what it says when none
  * is enabled, where a wildcard address is dialled, and what an answer means. The calls themselves are a function of
  * the test's: no socket is opened here.
  */
class EndpointProbeSpec extends AnyFlatSpec with Matchers with OptionValues:

  private val request = ActionRequest(Actions.TestRpc.name)

  private val http = Endpoint(Kind.Http, "127.0.0.1", 8546)
  private val socket = Endpoint(Kind.Ws, "0.0.0.0", 8552)
  private val ipc = Endpoint(Kind.Ipc, "", 0, "/var/lib/fukuii/fukuii.ipc")
  private val engine = Endpoint(Kind.Engine, "127.0.0.1", 8551)

  private val Counted = Vector(
    "",
    "Each line is one call made by this dashboard from this machine: it is counted on the RPC view like any other,",
    "and the Engine API line is one failed authentication on its counter. No token was read or sent."
  )

  "test-rpc" should "say what it does before it does it, and call nothing for that" taggedAs (UnitTest) in {
    var called = 0
    val handler = EndpointProbe.handler(
      () => Seq(http),
      _ =>
        called += 1
        "HTTP"
    )
    handler.preview(request) shouldBe Preview(Vector("Calls each enabled RPC endpoint on this machine."))
    called shouldBe 0
  }

  it should "call each enabled endpoint once, in order, and give a line for each" taggedAs (UnitTest) in {
    val called = mutable.Buffer.empty[Endpoint]
    val progress = mutable.Buffer.empty[String]
    val handler = EndpointProbe.handler(
      () => Seq(http, socket, ipc, engine),
      e =>
        called += e
        s"${e.kind} answered"
    )
    handler.run(request, progress += _).toOption.value.split('\n').toVector shouldBe
      Vector("Http answered", "Ws answered", "Ipc answered", "Engine answered") ++ Counted
    called shouldBe Seq(http, socket, ipc, engine)
    // How far it is: the endpoint it is calling.
    progress shouldBe Seq("calling Http", "calling Ws", "calling Ipc", "calling Engine")
  }

  it should "give a line for an endpoint that failed, and go on to the next" taggedAs (UnitTest) in {
    val handler = EndpointProbe.handler(
      () => Seq(http, engine),
      e => if e == http then "HTTP        127.0.0.1:8546  FAILED: nothing is listening there" else "Engine API  ok"
    )
    handler.run(request, _ => ()).toOption.value.split('\n').toVector.take(2) shouldBe
      Vector("HTTP        127.0.0.1:8546  FAILED: nothing is listening there", "Engine API  ok")
  }

  it should "say that there is nothing to call when no transport is enabled" taggedAs (UnitTest) in {
    var called = 0
    val progress = mutable.Buffer.empty[String]
    val handler = EndpointProbe.handler(
      () => Nil,
      _ =>
        called += 1
        ""
    )
    handler.run(request, progress += _) shouldBe
      Right("No RPC transport is enabled on this node: there is nothing to call.")
    called shouldBe 0
    progress shouldBe empty
  }

  it should "ask the node for its endpoints each time it runs" taggedAs (UnitTest) in {
    var enabled = Seq(http)
    val handler = EndpointProbe.handler(() => enabled, e => e.kind.toString)
    handler.run(request, _ => ()).toOption.value.split('\n').toVector.head shouldBe "Http"
    enabled = Seq(socket, engine)
    handler.run(request, _ => ()).toOption.value.split('\n').toVector.take(2) shouldBe Vector("Ws", "Engine")
  }

  // ---- where an endpoint is dialled ---------------------------------------------------------------------------------

  "An endpoint bound to every interface" should "be dialled on loopback" taggedAs (UnitTest) in {
    EndpointProbe.dialled("0.0.0.0") shouldBe "127.0.0.1"
    EndpointProbe.dialled("") shouldBe "127.0.0.1"
    EndpointProbe.dialled("::") shouldBe "[::1]"
    EndpointProbe.dialled("[::]") shouldBe "[::1]"
    EndpointProbe.dialled("0:0:0:0:0:0:0:0") shouldBe "[::1]"
  }

  "An endpoint bound to one address" should "be dialled there, an IPv6 address in brackets" taggedAs (UnitTest) in {
    EndpointProbe.dialled("127.0.0.1") shouldBe "127.0.0.1"
    EndpointProbe.dialled("192.0.2.10") shouldBe "192.0.2.10"
    EndpointProbe.dialled("localhost") shouldBe "localhost"
    EndpointProbe.dialled("::1") shouldBe "[::1]"
    EndpointProbe.dialled("2001:db8::7") shouldBe "[2001:db8::7]"
    EndpointProbe.dialled("[2001:db8::7]") shouldBe "[2001:db8::7]"
  }

  // ---- what an answer means -----------------------------------------------------------------------------------------

  "An answer" should "be said in words: the client's version, an error, or what else came back" taggedAs (UnitTest) in {
    EndpointProbe.verdict(200, """{"jsonrpc":"2.0","id":1,"result":"fukuii/v0.11.0/linux/java-25"}""") shouldBe
      "answered fukuii/v0.11.0/linux/java-25"
    EndpointProbe.verdict(200, """{ "jsonrpc": "2.0", "result" : "fukuii/v0.11.0", "id": 1 }""") shouldBe
      "answered fukuii/v0.11.0"
    // The namespace is not served on this transport: the transport answers all the same.
    EndpointProbe.verdict(
      200,
      """{"jsonrpc":"2.0","id":1,"error":{"code":-32601,"message":"Method not found"}}"""
    ) shouldBe
      "answers, and web3 is not served on this transport"
    EndpointProbe.verdict(200, """{"jsonrpc":"2.0","id":1,"error":{"code":-32000,"message":"busy"}}""") shouldBe
      "answered with error -32000"
    EndpointProbe.verdict(503, "Service Unavailable") shouldBe "answered HTTP 503"
    EndpointProbe.verdict(200, "<html><body>It works</body></html>") shouldBe "answered, and not with JSON-RPC"
    EndpointProbe.verdict(200, "") shouldBe "answered, and not with JSON-RPC"
  }

  it should "be said to be rate limited when the server refused it as too soon, whatever its body" taggedAs (UnitTest) in {
    val Limited = "rate limited: the server refused this request as too soon after the last"
    EndpointProbe.verdict(429, "") shouldBe Limited
    EndpointProbe.verdict(
      429,
      """{"jsonrpc":"2.0","id":1,"error":{"code":-32005,"message":"slow down"}}"""
    ) shouldBe Limited
  }

  "The time limits" should "be short: the endpoints are this machine's own" taggedAs (UnitTest) in {
    EndpointProbe.ConnectTimeout.toSeconds shouldBe 2L
    EndpointProbe.AnswerTimeout.toSeconds shouldBe 3L
  }
