package com.chipprbots.ethereum.jsonrpc.server.http

import org.apache.pekko.actor.testkit.typed.scaladsl.FishingOutcomes
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.http.scaladsl.model.HttpRequest
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.model.headers.HttpOrigin
import org.apache.pekko.http.scaladsl.model.headers.Origin
import org.apache.pekko.http.scaladsl.model.ws.Message
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.apache.pekko.stream.scaladsl.Flow
import org.apache.pekko.stream.scaladsl.Sink
import org.apache.pekko.stream.scaladsl.Source

import scala.concurrent.duration.*

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.jsonrpc.SubscriptionManager
import com.chipprbots.ethereum.jsonrpc.server.http.JsonRpcWsServer.JsonRpcWsServerConfig
import com.chipprbots.ethereum.testing.Tags.*

/** The WebSocket upgrade is checked against the `Origin` allow-list before the connection is accepted: a request from a
  * page on a disallowed origin gets 403 and is never registered with the SubscriptionManager, while a request with no
  * `Origin` header (a CLI or library, not a browser) is upgraded as before.
  */
class JsonRpcWsServerSpec extends AnyFlatSpec with Matchers with ScalatestRouteTest:

  private def wsConfig(origins: Seq[String]): JsonRpcWsServerConfig = new JsonRpcWsServerConfig:
    override val enabled: Boolean = true
    override val interface: String = "localhost"
    override val port: Int = 0
    override val allowedOrigins: Seq[String] = origins

  private def originHeader(origin: String): Origin = Origin(HttpOrigin(origin))

  /** The client half of the handshake: reads nothing, sends nothing, and stays open. */
  private def idleClient: Flow[Message, Message, ?] = Flow.fromSinkAndSource(Sink.ignore, Source.maybe[Message])

  private class Fixture(origins: Seq[String]):
    val subscriptions: TestProbe[SubscriptionManager.Command] =
      TestProbe[SubscriptionManager.Command]()(using system.toTyped)
    private val server =
      new JsonRpcWsServer(new MockableJsonRpcController, subscriptions.ref, wsConfig(origins))(using system)
    val route: Route = Route.seal(server.route)

    /** Opens a WebSocket upgrade on `path`, with the given `Origin` header values (none for a non-browser client). */
    def upgradeRequest(path: String, originHeaders: String*): HttpRequest =
      originHeaders.foldLeft(WS(path, idleClient))((req, origin) => req.addHeader(originHeader(origin)))

    def expectAccepted(request: HttpRequest): Unit =
      request ~> route ~> check {
        withClue(s"headers ${request.headers}: ") {
          status shouldEqual StatusCodes.SwitchingProtocols
          isWebSocketUpgrade shouldBe true
        }
      }
      // other messages (a ConnectionClosed from an earlier request) may be interleaved; wait for this connection's
      subscriptions.fishForMessage(3.seconds) {
        case _: SubscriptionManager.RegisterConnection => FishingOutcomes.complete
        case _                                         => FishingOutcomes.continueAndIgnore
      }

    def expectRefused(request: HttpRequest): Unit =
      request ~> route ~> check {
        withClue(s"headers ${request.headers}: ") {
          status shouldEqual StatusCodes.Forbidden
          isWebSocketUpgrade shouldBe false
        }
      }
      subscriptions.expectNoMessage(100.millis)

  "JsonRpcWsServer" should "refuse an upgrade whose Origin is not allowed, with 403 and no registered connection" taggedAs (
    UnitTest,
    RPCTest
  ) in new Fixture(Seq.empty):
    expectRefused(upgradeRequest("/", "http://evil.example"))
    expectRefused(upgradeRequest("/ws", "https://evil.example:8545"))
    expectRefused(upgradeRequest("/", "http://localhost.evil.example"))
    expectRefused(upgradeRequest("/", "http://127.0.0.1.evil.example:8080"))

  it should "refuse an Origin of null (a sandboxed page or a local file) by default" taggedAs (
    UnitTest,
    RPCTest
  ) in new Fixture(
    Seq.empty
  ):
    expectRefused(WS("/", idleClient).addHeader(Origin(Nil)))

  it should "answer 403 to a plain request from a disallowed origin too" taggedAs (UnitTest, RPCTest) in new Fixture(
    Seq.empty
  ):
    Get("/").addHeader(originHeader("http://evil.example")) ~> route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
    subscriptions.expectNoMessage(100.millis)

  it should "upgrade a request from a localhost origin by default" taggedAs (UnitTest, RPCTest) in new Fixture(
    Seq.empty
  ):
    expectAccepted(upgradeRequest("/", "http://localhost:3000"))
    expectAccepted(upgradeRequest("/ws", "http://127.0.0.1:8080"))
    expectAccepted(upgradeRequest("/", "http://[::1]:5173"))
    expectAccepted(upgradeRequest("/", "https://localhost"))

  it should "upgrade a request that sends no Origin header" taggedAs (UnitTest, RPCTest) in new Fixture(Seq.empty):
    expectAccepted(upgradeRequest("/"))
    expectAccepted(upgradeRequest("/ws"))

  it should "upgrade a request that sends no Origin header when origins are configured too" taggedAs (
    UnitTest,
    RPCTest
  ) in new Fixture(Seq("https://dapp.example")):
    expectAccepted(upgradeRequest("/"))

  it should "refuse a request when any of its Origin headers is not allowed" taggedAs (
    UnitTest,
    RPCTest
  ) in new Fixture(
    Seq.empty
  ):
    expectRefused(upgradeRequest("/", "http://localhost:3000", "http://evil.example"))
    expectRefused(upgradeRequest("/", "http://evil.example", "http://localhost:3000"))

  it should "allow any origin when allowed-origins is *" taggedAs (UnitTest, RPCTest) in new Fixture(Seq("*")):
    expectAccepted(upgradeRequest("/", "http://evil.example"))
    expectAccepted(upgradeRequest("/ws", "https://anywhere.example:9999"))
    expectAccepted(WS("/", idleClient).addHeader(Origin(Nil)))
    expectAccepted(upgradeRequest("/"))

  it should "use the configured origins instead of the localhost default" taggedAs (UnitTest, RPCTest) in new Fixture(
    Seq("https://dapp.example", "http://app.example:3000")
  ):
    expectRefused(upgradeRequest("/", "http://app.example:3001"))
    expectRefused(upgradeRequest("/", "http://dapp.example"))
    expectRefused(upgradeRequest("/", "http://localhost:3000"))
    expectAccepted(upgradeRequest("/", "https://dapp.example"))
    expectAccepted(upgradeRequest("/", "https://DAPP.example:8443"))
    expectAccepted(upgradeRequest("/", "http://app.example:3000"))
