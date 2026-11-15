package com.chipprbots.ethereum.console.ops

import scala.collection.mutable

import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

/** `rpc stop` and `rpc start` (spec 029 FR-055, T097; contracts/actions.md): the address, what is served, the open
  * connections that drop, the warning when a sensitive namespace would be served to other hosts, and the refusals: a
  * transport that was not enabled at the start, a server already stopped, a server already listening.
  */
class RpcServerActionsSpec extends AnyFlatSpec with Matchers with OptionValues:

  /** A server, what it answers, and every call made of it. */
  private class Fake(
      label: String = "HTTP",
      address: String = "127.0.0.1:8546",
      namespaces: Seq[String] = Seq("eth", "web3", "net"),
      exposed: Seq[String] = Nil,
      tls: Boolean = false,
      extras: Seq[String] = Seq("/health", "/readiness"),
      open: Option[Int] = None,
      listening: Boolean = true,
      answer: Either[String, Unit] = Right(())
  ):
    val calls: mutable.Buffer[String] = mutable.Buffer.empty
    private def called(what: String): Either[String, Unit] =
      calls += what
      answer
    val server: RpcServerActions.Server = RpcServerActions.Server(
      label,
      address,
      namespaces,
      exposed,
      tls,
      extras,
      openConnections = () => open,
      listening = () => listening,
      stop = () => called("stop"),
      start = () => called("start")
    )

  private def ws(open: Option[Int] = Some(3), listening: Boolean = true): Fake =
    new Fake("WebSocket", "127.0.0.1:8552", Seq("eth", "net"), extras = Nil, open = open, listening = listening)

  private def handlers(servers: (String, Fake)*): Map[String, ActionHandler] =
    RpcServerActions.handlers(servers.map((key, fake) => key -> fake.server).toMap)

  private def stop(transport: String): ActionRequest =
    ActionRequest(Actions.RpcStop.name, Vector("transport" -> transport))
  private def start(transport: String): ActionRequest =
    ActionRequest(Actions.RpcStart.name, Vector("transport" -> transport))

  private def preview(r: ActionRequest, servers: (String, Fake)*): Preview = handlers(servers*)(r.action).preview(r)
  private def run(r: ActionRequest, servers: (String, Fake)*): Either[String, String] =
    handlers(servers*)(r.action).run(r, _ => ())

  private val HttpOff =
    "the HTTP server was not enabled when the node started (fukuii.network.rpc.http.enabled); " +
      "the dashboard enables nothing: change the configuration and restart"
  private val WsOff =
    "the WebSocket server was not enabled when the node started (fukuii.network.rpc.ws.enabled); " +
      "the dashboard enables nothing: change the configuration and restart"

  "The RPC server actions" should "be the two of the registry" taggedAs (UnitTest) in {
    handlers().keySet shouldBe Set("rpc stop", "rpc start")
  }

  // ---- rpc stop -----------------------------------------------------------------------------------------------------

  "rpc stop" should "say the address, what is served, and that the probes on that port will fail" taggedAs (UnitTest) in {
    preview(stop("http"), "http" -> new Fake()) shouldBe Preview(
      Vector(
        "Stops the HTTP server listening on 127.0.0.1:8546.",
        "It serves eth, web3, net, /health, /readiness.",
        "Its open connections are not counted by this server: in-flight requests finish, then every connection is closed.",
        "Readiness and health probes on this port will fail, and anything supervising the node by that port may restart it.",
        "The Engine API is on its own port and is not affected."
      )
    )
  }

  it should "say that the server speaks TLS where it does" taggedAs (UnitTest) in {
    preview(stop("http"), "http" -> new Fake(tls = true)).lines.head shouldBe
      "Stops the HTTP server listening on 127.0.0.1:8546 (TLS)."
  }

  it should "count the open connections that drop, where the server counts them" taggedAs (UnitTest) in {
    preview(stop("ws"), "ws" -> ws(Some(3))) shouldBe Preview(
      Vector(
        "Stops the WebSocket server listening on 127.0.0.1:8552.",
        "It serves eth, net.",
        "3 open connections are closed at once and their subscriptions are dropped; clients must reconnect and resubscribe.",
        // Nothing else rides on the WebSocket port: no probe fails with it.
        "The Engine API is on its own port and is not affected."
      )
    )
    preview(stop("ws"), "ws" -> ws(Some(1))).lines(2) shouldBe
      "1 open connection is closed at once and its subscriptions are dropped; clients must reconnect and resubscribe."
    preview(stop("ws"), "ws" -> ws(Some(0))).lines(2) shouldBe
      "0 open connections are closed at once and their subscriptions are dropped; clients must reconnect and resubscribe."
    preview(stop("ws"), "ws" -> ws(None)).lines(2) should startWith(
      "Its open connections are not counted by this server"
    )
  }

  it should "be refused for a transport that was not enabled when the node started" taggedAs (UnitTest) in {
    preview(stop("ws"), "http" -> new Fake()) shouldBe Preview(Vector.empty, Some(WsOff))
    preview(stop("http"), "ws" -> ws()) shouldBe Preview(Vector.empty, Some(HttpOff))
    preview(stop("http")) shouldBe Preview(Vector.empty, Some(HttpOff))
  }

  it should "be refused for a server that is not listening" taggedAs (UnitTest) in {
    preview(stop("http"), "http" -> new Fake(listening = false)) shouldBe
      Preview(Vector.empty, Some("the HTTP server is not listening now"))
    preview(stop("ws"), "ws" -> ws(listening = false)) shouldBe
      Preview(Vector.empty, Some("the WebSocket server is not listening now"))
  }

  it should "stop the server it names and no other, and hand its failure back unchanged" taggedAs (UnitTest) in {
    val http = new Fake()
    val socket = ws()
    run(stop("http"), "http" -> http, "ws" -> socket) shouldBe Right("the HTTP server on 127.0.0.1:8546 is stopped")
    http.calls shouldBe Seq("stop")
    socket.calls shouldBe empty
    run(stop("ws"), "http" -> http, "ws" -> socket) shouldBe Right("the WebSocket server on 127.0.0.1:8552 is stopped")
    socket.calls shouldBe Seq("stop")
    val failing = new Fake(answer = Left("the stop did not end within 8 s"))
    run(stop("http"), "http" -> failing) shouldBe Left("the stop did not end within 8 s")
    // A transport that was never enabled has nothing to stop.
    run(stop("ws"), "http" -> http) shouldBe Left(WsOff)
  }

  // ---- rpc start ----------------------------------------------------------------------------------------------------

  "rpc start" should "say the address it binds and what it serves" taggedAs (UnitTest) in {
    preview(start("http"), "http" -> new Fake(listening = false)) shouldBe Preview(
      Vector(
        "Binds 127.0.0.1:8546 again: the HTTP server listens.",
        "It serves eth, web3, net, /health, /readiness."
      )
    )
    preview(start("http"), "http" -> new Fake(listening = false, tls = true)).lines.head shouldBe
      "Binds 127.0.0.1:8546 again (TLS): the HTTP server listens."
    preview(start("ws"), "ws" -> ws(listening = false)).lines shouldBe Vector(
      "Binds 127.0.0.1:8552 again: the WebSocket server listens.",
      "It serves eth, net."
    )
  }

  it should "warn when a sensitive namespace would be served to other hosts" taggedAs (UnitTest) in {
    val exposed = new Fake(
      address = "0.0.0.0:8546",
      namespaces = Seq("eth", "admin", "personal"),
      exposed = Seq("admin", "personal"),
      listening = false
    )
    val p = preview(start("http"), "http" -> exposed)
    p.refusal shouldBe None
    p.lines.last shouldBe
      "WARNING: it serves admin, personal to other hosts: 0.0.0.0:8546 is not a loopback address."
    p.lines should have size 3
  }

  it should "be refused for a transport that was not enabled when the node started" taggedAs (UnitTest) in {
    preview(start("ws"), "http" -> new Fake(listening = false)) shouldBe Preview(Vector.empty, Some(WsOff))
    preview(start("http")) shouldBe Preview(Vector.empty, Some(HttpOff))
  }

  it should "be refused for a server that is already listening" taggedAs (UnitTest) in {
    preview(start("http"), "http" -> new Fake()) shouldBe
      Preview(Vector.empty, Some("the HTTP server is already listening on 127.0.0.1:8546"))
    preview(start("ws"), "ws" -> ws()) shouldBe
      Preview(Vector.empty, Some("the WebSocket server is already listening on 127.0.0.1:8552"))
  }

  it should "start the server it names and no other, and hand its failure back unchanged" taggedAs (UnitTest) in {
    val http = new Fake(listening = false)
    val socket = ws(listening = false)
    run(start("ws"), "http" -> http, "ws" -> socket) shouldBe Right("the WebSocket server listens on 127.0.0.1:8552")
    socket.calls shouldBe Seq("start")
    http.calls shouldBe empty
    run(start("http"), "http" -> http, "ws" -> socket) shouldBe Right("the HTTP server listens on 127.0.0.1:8546")
    http.calls shouldBe Seq("start")
    val failing = new Fake(listening = false, answer = Left("Address already in use"))
    run(start("http"), "http" -> failing) shouldBe Left("Address already in use")
    run(start("ws"), "http" -> http) shouldBe Left(WsOff)
  }
