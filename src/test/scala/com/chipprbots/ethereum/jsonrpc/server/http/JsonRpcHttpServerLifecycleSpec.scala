package com.chipprbots.ethereum.jsonrpc.server.http

import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.cors.scaladsl.model.HttpOriginMatcher
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.server.Directives.*

import cats.effect.IO

import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration.*

import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.healthcheck.HealthcheckResponse
import com.chipprbots.ethereum.jsonrpc.JsonRpcHealthChecker
import com.chipprbots.ethereum.jsonrpc.RpcCallStats
import com.chipprbots.ethereum.jsonrpc.server.http.JsonRpcHttpServer.JsonRpcHttpServerConfig
import com.chipprbots.ethereum.jsonrpc.server.http.JsonRpcHttpServer.RateLimitConfig
import com.chipprbots.ethereum.testing.Tags.*

/** The HTTP JSON-RPC server can be stopped and started again while the node runs (spec 029 FR-052, FR-055): a stopped
  * server refuses connections and shows as stopped to the RPC view, a started one answers again, and a repeated stop or
  * start, or two starts at once, change nothing. Bound on a real loopback port, with a fresh [[RpcCallStats]] of its
  * own; every wait is a future with a timeout, never a sleep.
  */
class JsonRpcHttpServerLifecycleSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  private val system: ActorSystem = ActorSystem("json-rpc-http-lifecycle-spec")
  private given ec: ExecutionContext = system.dispatcher
  private val Wait = 10.seconds

  override protected def afterAll(): Unit =
    Await.result(system.terminate(), Wait)
    ()

  /** A port nothing listens on now. Bound at once by the test, not by the OS picking again on each start. */
  private def freePort(): Int =
    val socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress)
    try socket.getLocalPort
    finally socket.close()

  private def serverConfig(portNumber: Int): JsonRpcHttpServerConfig = new JsonRpcHttpServerConfig:
    override val mode: String = "http"
    override val enabled: Boolean = true
    override val interface: String = "127.0.0.1"
    override val port: Int = portNumber
    override val corsAllowedOrigins: HttpOriginMatcher = HttpOriginMatcher.*
    override val rateLimit: RateLimitConfig = new RateLimitConfig:
      override val enabled: Boolean = false
      override val minRequestInterval: FiniteDuration = 20.millis
      override val latestTimestampCacheSize: Int = 1024

  private val healthChecker: JsonRpcHealthChecker = new JsonRpcHealthChecker:
    override def healthCheck: IO[HealthcheckResponse] = IO.pure(HealthcheckResponse(Nil))
    override def readinessCheck(): IO[HealthcheckResponse] = IO.pure(HealthcheckResponse(Nil))

  private def newServer(portNumber: Int, stats: RpcCallStats): InsecureJsonRpcHttpServer =
    new InsecureJsonRpcHttpServer(new MockableJsonRpcController, healthChecker, serverConfig(portNumber))(using system):
      override protected def callStats: RpcCallStats = stats
      override protected def stopDeadline: FiniteDuration = 1.second

  /** The status of `GET /health`, on a connection of its own that is closed after (no pooled connection to blur
    * "refused" with "reused").
    */
  private def healthStatus(portNumber: Int): Int =
    val connection =
      new URI(s"http://127.0.0.1:$portNumber/health").toURL.openConnection().asInstanceOf[HttpURLConnection]
    try
      connection.setRequestProperty("Connection", "close")
      connection.setConnectTimeout(5000)
      connection.setReadTimeout(5000)
      connection.getResponseCode
    finally connection.disconnect()

  private def refused(portNumber: Int): Boolean =
    try
      new Socket("127.0.0.1", portNumber).close()
      false
    catch case _: ConnectException => true

  "A stopped HTTP server" should "refuse connections and show as stopped, and answer again once started" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val portNumber = freePort()
    val stats = new RpcCallStats
    val server = newServer(portNumber, stats)
    try
      Await.result(server.run(), Wait)
      healthStatus(portNumber) shouldBe 200
      server.lifecycle.isBound shouldBe true
      server.lifecycle.boundAddress.map(_.getPort) shouldBe Some(portNumber)
      stats.snapshot.listening.get(RpcCallStats.Http) shouldBe Some(true)

      Await.result(server.lifecycle.stop(), Wait)
      refused(portNumber) shouldBe true
      server.lifecycle.isBound shouldBe false
      server.lifecycle.boundAddress shouldBe None
      stats.snapshot.stopped should contain(RpcCallStats.Http)
      // Stopped here is not a failure to bind.
      stats.snapshot.listening.get(RpcCallStats.Http) shouldBe None

      Await.result(server.lifecycle.start(), Wait)
      healthStatus(portNumber) shouldBe 200
      stats.snapshot.stopped shouldBe empty
      stats.snapshot.listening.get(RpcCallStats.Http) shouldBe Some(true)
    finally Await.result(server.lifecycle.stop(), Wait)
  }

  "A repeated stop or start" should "be harmless" taggedAs (UnitTest, RPCTest) in {
    val portNumber = freePort()
    val stats = new RpcCallStats
    val server = newServer(portNumber, stats)
    val lifecycle = server.lifecycle
    try
      // Stopping one that never started changes nothing: it was not stopped by the operator.
      Await.result(lifecycle.stop(), Wait)
      stats.snapshot.stopped shouldBe empty

      val first = Await.result(lifecycle.start(), Wait)
      val second = Await.result(lifecycle.start(), Wait)
      second.localAddress shouldBe first.localAddress
      healthStatus(portNumber) shouldBe 200

      Await.result(lifecycle.stop(), Wait)
      Await.result(lifecycle.stop(), Wait)
      Await.result(Future.sequence(Seq(lifecycle.stop(), lifecycle.stop())), Wait)
      refused(portNumber) shouldBe true
      stats.snapshot.stopped should contain(RpcCallStats.Http)

      // And it still starts, twice, after stopping three times.
      Await.result(lifecycle.start(), Wait)
      Await.result(lifecycle.start(), Wait)
      healthStatus(portNumber) shouldBe 200
    finally Await.result(lifecycle.stop(), Wait)
  }

  "Two starts at once" should "be one bind, and both get the binding" taggedAs (UnitTest, RPCTest) in {
    val binds = new AtomicInteger(0)
    val stats = new RpcCallStats
    // The bind is held open until both starts are made, so the second one meets the lifecycle while it is still
    // binding. Bound at once, it could settle first: the second start then finds it bound and answers with the binding
    // in a new future, which is right, but not the case this test is about (B-374).
    val release = Promise[Unit]()
    def countedBind(): Future[Http.ServerBinding] =
      binds.incrementAndGet()
      release.future.flatMap(_ =>
        Http(system)
          .newServerAt("127.0.0.1", 0)
          .bind(org.apache.pekko.http.scaladsl.server.Route.toFunction(complete("ok"))(using system))
      )
    val lifecycle = new ServerLifecycle(RpcCallStats.Http, () => countedBind(), stats, 1.second)
    try
      val first = lifecycle.start()
      val second = lifecycle.start()
      (second should be).theSameInstanceAs(first)
      release.success(())
      val binding = Await.result(first, Wait)
      Await.result(second, Wait).localAddress shouldBe binding.localAddress
      Await.result(lifecycle.start(), Wait).localAddress shouldBe binding.localAddress
      binds.get shouldBe 1
    finally
      // Settle the bind first, so a failed assertion above is reported instead of `stop` refusing a server starting.
      release.trySuccess(())
      Await.ready(lifecycle.start(), Wait)
      Await.result(lifecycle.stop(), Wait)
  }

  "A bind that fails" should "leave the server stopped, not listening, and able to stop and start again" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val stats = new RpcCallStats
    val failing = new AtomicInteger(1)
    def failingOnceBind(): Future[Http.ServerBinding] =
      if failing.getAndSet(0) == 1 then Future.failed(new IllegalStateException("address in use"))
      else
        Http(system)
          .newServerAt("127.0.0.1", 0)
          .bind(org.apache.pekko.http.scaladsl.server.Route.toFunction(complete("ok"))(using system))
    val lifecycle = new ServerLifecycle(RpcCallStats.Http, () => failingOnceBind(), stats, 1.second)
    try
      an[IllegalStateException] should be thrownBy Await.result(lifecycle.start(), Wait)
      lifecycle.isBound shouldBe false
      stats.snapshot.listening.get(RpcCallStats.Http) shouldBe Some(false)
      stats.snapshot.stopped shouldBe empty
      // Nothing to stop, and it is no stop by the operator.
      Await.result(lifecycle.stop(), Wait)
      stats.snapshot.stopped shouldBe empty

      Await.result(lifecycle.start(), Wait)
      lifecycle.isBound shouldBe true
      stats.snapshot.listening.get(RpcCallStats.Http) shouldBe Some(true)
    finally Await.result(lifecycle.stop(), Wait)
  }
