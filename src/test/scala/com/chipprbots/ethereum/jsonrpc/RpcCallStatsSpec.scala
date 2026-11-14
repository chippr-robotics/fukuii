package com.chipprbots.ethereum.jsonrpc

import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

/** The JSON-RPC call stats (spec 029 FR-038, FR-039): what is counted under which name, that the names kept are bounded
  * whatever a client asks for, and that nothing it is handed can make it throw.
  */
class RpcCallStatsSpec extends AnyFlatSpec with Matchers with OptionValues:

  private val Milli = 1_000_000L

  private def method(s: RpcCallStats.Snapshot, name: String): RpcCallStats.Counts = s.methods.find(_.name == name).value
  private def transport(s: RpcCallStats.Snapshot, name: String): RpcCallStats.Counts =
    s.transports.find(_.name == name).value

  "A call" should "count under its method and its transport, with its latency and when it ended" taggedAs (UnitTest) in {
    var now = 5_000L
    val stats = new RpcCallStats(clock = () => now)
    stats.record(RpcCallStats.Http, "eth_blockNumber", known = true, 2 * Milli, failed = false)
    now = 6_000L
    stats.record(RpcCallStats.Ws, "eth_blockNumber", known = true, 4 * Milli, failed = false)
    stats.record(RpcCallStats.Http, "eth_call", known = true, 30 * Milli, failed = true)
    val s = stats.snapshot
    val blockNumber = method(s, "eth_blockNumber")
    (blockNumber.calls, blockNumber.errors, blockNumber.lastAtMillis) shouldBe ((2L, 0L, 6_000L))
    blockNumber.latency.count shouldBe 2
    blockNumber.latency.maxNanos shouldBe 4 * Milli
    // A failed call is timed like any other.
    val call = method(s, "eth_call")
    (call.calls, call.errors, call.latency.count, call.latency.maxNanos) shouldBe ((1L, 1L, 1L, 30 * Milli))
    (transport(s, RpcCallStats.Http).calls, transport(s, RpcCallStats.Http).errors) shouldBe ((2L, 1L))
    (transport(s, RpcCallStats.Ws).calls, transport(s, RpcCallStats.Ipc).calls) shouldBe ((1L, 0L))
    // The most called first.
    s.methods.map(_.name) shouldBe Seq("eth_blockNumber", "eth_call")
    s.rateLimited shouldBe 0
  }

  "The transports" should "be the five known ones, always, and any other name count under the last" taggedAs (
    UnitTest
  ) in {
    val stats = new RpcCallStats
    stats.snapshot.transports.map(_.name) shouldBe RpcCallStats.Transports
    stats.record("carrier-pigeon", "eth_chainId", known = true, Milli, failed = false)
    stats.record(null, "eth_chainId", known = true, Milli, failed = false)
    transport(stats.snapshot, RpcCallStats.OtherTransport).calls shouldBe 2
    stats.snapshot.transports should have size RpcCallStats.Transports.size.toLong
  }

  "A method the node does not have" should "count under one name, whatever it was called: it takes no method's place" taggedAs (
    UnitTest
  ) in {
    val stats = new RpcCallStats(maxMethods = 4)
    for i <- 1 to 10_000 do stats.record(RpcCallStats.Http, s"evil_$i", known = false, Milli, failed = true)
    stats.record(RpcCallStats.Http, "eth_chainId", known = true, Milli, failed = false)
    val s = stats.snapshot
    s.methods.map(_.name) shouldBe Seq("eth_chainId", RpcCallStats.NotFound)
    (method(s, RpcCallStats.NotFound).calls, method(s, RpcCallStats.NotFound).errors) shouldBe ((10_000L, 10_000L))
  }

  "The 257th method name" should "count under the name for the rest, and the first 256 keep their own" taggedAs (
    UnitTest
  ) in {
    val stats = new RpcCallStats
    for i <- 1 to 300 do stats.record(RpcCallStats.Http, s"ns_method$i", known = true, Milli, failed = false)
    // A name already kept still counts under itself once the places are gone.
    stats.record(RpcCallStats.Http, "ns_method7", known = true, Milli, failed = false)
    val s = stats.snapshot
    s.methods should have size (RpcCallStats.MaxMethods + 1).toLong
    method(s, RpcCallStats.Other).calls shouldBe 44
    method(s, "ns_method7").calls shouldBe 2
    method(s, "ns_method256").calls shouldBe 1
    s.methods.exists(_.name == "ns_method257") shouldBe false
    s.methods.map(_.calls).sum shouldBe 301
  }

  "A method's name" should "be kept no longer than 64 characters" taggedAs (UnitTest) in {
    val stats = new RpcCallStats
    val long = "eth_" + "x" * 10_000
    stats.record(RpcCallStats.Http, long, known = true, Milli, failed = false)
    stats.record(RpcCallStats.Http, long + "y", known = true, Milli, failed = false)
    val s = stats.snapshot
    s.methods.map(_.name) shouldBe Seq(long.take(RpcCallStats.MaxNameChars))
    s.methods.head.calls shouldBe 2
  }

  "A request refused by the rate limit" should "be counted, and be no call" taggedAs (UnitTest) in {
    val stats = new RpcCallStats
    stats.rateLimited()
    stats.rateLimited()
    val s = stats.snapshot
    s.rateLimited shouldBe 2
    s.methods shouldBe empty
    all(s.transports.map(_.calls)) shouldBe 0
  }

  "Recording" should "never throw, whatever it is handed and whatever its clock does" taggedAs (UnitTest) in {
    val broken = new RpcCallStats(clock = () => throw new IllegalStateException("no clock"))
    noException should be thrownBy broken.record(RpcCallStats.Http, "eth_call", known = true, Milli, failed = false)
    noException should be thrownBy broken.record(null, null, known = true, -1L, failed = true)
    noException should be thrownBy broken.rateLimited()
    val stats = new RpcCallStats
    noException should be thrownBy stats.record(RpcCallStats.Http, null, known = true, Milli, failed = false)
    noException should be thrownBy stats.record(RpcCallStats.Http, "", known = true, Long.MinValue, failed = false)
    stats.snapshot.methods.map(_.name) shouldBe Seq("")
  }

  it should "lose nothing from four threads at once, and keep the names within their bound" taggedAs (UnitTest) in {
    val stats = new RpcCallStats(maxMethods = 8)
    val each = 20_000
    val start = new java.util.concurrent.CountDownLatch(1)
    val threads = (0 until 4).map { t =>
      val thread = new Thread(() =>
        start.await()
        var i = 0
        while i < each do
          // Twenty names race for eight places.
          stats.record(RpcCallStats.Http, s"m${(i + t) % 20}", known = true, Milli, failed = i % 10 == 0)
          i += 1
      )
      thread.start()
      thread
    }
    start.countDown()
    threads.foreach(_.join())
    val s = stats.snapshot
    s.methods.map(_.calls).sum shouldBe 4L * each
    s.methods.map(_.errors).sum shouldBe 4L * each / 10
    s.methods.count(m => m.name != RpcCallStats.Other) should be <= 8
    transport(s, RpcCallStats.Http).calls shouldBe 4L * each
  }

  "Making one" should "touch no registry and read no configuration: the node makes its own before any request" taggedAs (
    UnitTest
  ) in {
    // The node's own exists from class initialisation, and is empty of anything a registry would add.
    RpcCallStats.Node.snapshot.transports.map(_.name) shouldBe RpcCallStats.Transports
    classOf[RpcCallStats].getInterfaces.map(_.getName) should not contain
      "com.chipprbots.ethereum.metrics.MetricsContainer"
  }
