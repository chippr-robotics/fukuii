package com.chipprbots.ethereum.jsonrpc

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import scala.jdk.CollectionConverters.*

import org.json4s.JInt
import org.json4s.JString
import org.scalatest.EitherValues
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.jsonrpc.server.http.MockableJsonRpcController
import com.chipprbots.ethereum.testing.Tags.*

/** The JSON-RPC controller's counting for the dashboard (spec 029 FR-038, FR-042): a success, an error, an exception
  * and a method the node has not each count once, under the right name, timed; and the answer is the same with the
  * counting working, failing, and whatever the transport.
  */
class JsonRpcControllerMetricsSpec extends AnyFlatSpec with Matchers with OptionValues with EitherValues:

  given IORuntime = IORuntime.global

  private val boom = new IllegalStateException("handler failed")

  /** A controller that serves the namespace `t`: `t_ok`, `t_err`, `t_throw`, and any `t_m<n>`. */
  private class Controller(stats: RpcCallStats, transport: String = RpcCallStats.Http)
      extends MockableJsonRpcController:
    override def enabledApis: Seq[String] = Seq("t", "off")
    override def apisHandleFns: Map[String, PartialFunction[JsonRpcRequest, IO[JsonRpcResponse]]] = Map(
      "t" -> {
        case req if req.method == "t_ok" => IO.pure(JsonRpcResponse("2.0", Some(JString("fine")), None, JInt(1)))
        case req if req.method == "t_err" =>
          IO.pure(JsonRpcResponse("2.0", None, Some(JsonRpcError.InvalidParams("no")), JInt(1)))
        case req if req.method == "t_throw"      => IO.raiseError(boom)
        case req if req.method.startsWith("t_m") => IO.pure(JsonRpcResponse("2.0", Some(JInt(0)), None, JInt(1)))
      }
    )
    override def transportName: String = transport
    override protected def callStats: RpcCallStats = stats

  private def request(method: String): JsonRpcRequest = JsonRpcRequest("2.0", method, None, Some(JInt(1)))

  private def run(c: Controller, method: String): Either[Throwable, JsonRpcResponse] =
    c.handleRequest(request(method)).attempt.unsafeRunSync()

  private def counts(stats: RpcCallStats, name: String): RpcCallStats.Counts =
    stats.snapshot.methods.find(_.name == name).value

  "A call that succeeds" should "count once under its method and its transport, and be timed" taggedAs (UnitTest) in {
    val stats = new RpcCallStats
    run(new Controller(stats), "t_ok").value.result shouldBe Some(JString("fine"))
    val ok = counts(stats, "t_ok")
    (ok.calls, ok.errors, ok.latency.count) shouldBe ((1L, 0L, 1L))
    stats.snapshot.transports.find(_.name == RpcCallStats.Http).value.calls shouldBe 1
  }

  "A call answered with an error" should "count as an error under its method, and be timed like a success" taggedAs (
    UnitTest
  ) in {
    val stats = new RpcCallStats
    run(new Controller(stats), "t_err").value.error.map(_.code) shouldBe Some(JsonRpcError.InvalidParams("no").code)
    val err = counts(stats, "t_err")
    (err.calls, err.errors, err.latency.count) shouldBe ((1L, 1L, 1L))
  }

  "A call whose handler fails" should "fail with the same exception, and count as an error, timed" taggedAs (
    UnitTest
  ) in {
    val stats = new RpcCallStats
    (run(new Controller(stats), "t_throw").left.value should be).theSameInstanceAs(boom)
    val thrown = counts(stats, "t_throw")
    (thrown.calls, thrown.errors, thrown.latency.count) shouldBe ((1L, 1L, 1L))
  }

  "A method the node has not, or whose namespace is off" should "count under one name and keep none of the names asked" taggedAs (
    UnitTest
  ) in {
    val stats = new RpcCallStats
    val c = new Controller(stats)
    for name <- Seq("nope_method", "off_anything", "t_unknown") ++ (1 to 300).map(i => s"flood_$i") do
      run(c, name).value.error.map(_.code) shouldBe Some(JsonRpcError.MethodNotFound.code)
    stats.snapshot.methods.map(_.name) shouldBe Seq(RpcCallStats.NotFound)
    val missing = counts(stats, RpcCallStats.NotFound)
    (missing.calls, missing.errors) shouldBe ((303L, 303L))
  }

  "The 257th method" should "count under the name for the rest" taggedAs (UnitTest) in {
    val stats = new RpcCallStats
    val c = new Controller(stats)
    for i <- 1 to 257 do run(c, s"t_m$i").value.error shouldBe None
    stats.snapshot.methods should have size (RpcCallStats.MaxMethods + 1).toLong
    counts(stats, RpcCallStats.Other).calls shouldBe 1
  }

  "Each transport's controller" should "count under its own transport, and answer alike" taggedAs (UnitTest) in {
    val stats = new RpcCallStats
    val answers = RpcCallStats.Transports.map(t => run(new Controller(stats, t), "t_ok"))
    answers.distinct should have size 1
    all(stats.snapshot.transports.map(_.calls)) shouldBe 1
  }

  // FR-042: the counting changes no answer. A stats object whose every note fails is the worst it can do.
  "The answer" should "be the same with the counting working and with it failing at every note" taggedAs (UnitTest) in {
    val working = new Controller(new RpcCallStats)
    val failing = new Controller(new RpcCallStats(clock = () => throw new IllegalStateException("the stats failed")))
    for method <- Seq("t_ok", "t_err", "nope_method", "off_x", "t_m1") do
      withClue(s"$method: ")(run(failing, method) shouldBe run(working, method))
    (run(failing, "t_throw").left.value should be).theSameInstanceAs(boom)
  }

  // The Prometheus series are untouched: the per-method timer is still recorded for a success alone.
  "The existing per-method timer" should "still be recorded only for a call that succeeded" taggedAs (UnitTest) in {
    val c = new Controller(new RpcCallStats)
    Seq("t_ok", "t_err", "t_throw", "nope_method").foreach(run(c, _))
    def timed(method: String): Boolean =
      JsonRpcControllerMetrics.metrics.registry.getMeters.asScala.exists(m =>
        m.getId.getName.contains("json.rpc.methods.timer") && m.getId.getTag("method") == method
      )
    timed("t_ok") shouldBe true
    timed("t_err") shouldBe false
    timed("t_throw") shouldBe false
    timed("nope_method") shouldBe false
  }
