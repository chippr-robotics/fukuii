package com.chipprbots.ethereum.network

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

/** The P2P traffic counters (spec 029 FR-044). How a connection moves them is in `RLPxConnectionHandlerSpec`. */
class P2pTrafficSpec extends AnyFlatSpec with Matchers:

  "New counters" should "be nothing" taggedAs (UnitTest) in {
    new P2pTraffic().snapshot shouldBe P2pTraffic.Snapshot(0, 0, 0, 0)
  }

  "Each event" should "move its own total, and no other" taggedAs (UnitTest) in {
    val t = new P2pTraffic
    t.received(100)
    t.snapshot shouldBe P2pTraffic.Snapshot(100, 0, 0, 0)
    t.sent(40)
    t.snapshot shouldBe P2pTraffic.Snapshot(100, 40, 0, 0)
    t.messageReceived()
    t.messageSent()
    t.messageSent()
    t.snapshot shouldBe P2pTraffic.Snapshot(100, 40, 1, 2)
  }

  "A read or a write of nothing, or of less" should "count nothing: a total only grows" taggedAs (UnitTest) in {
    val t = new P2pTraffic
    t.received(0)
    t.sent(0)
    t.received(-5)
    t.sent(Int.MinValue)
    t.snapshot shouldBe P2pTraffic.Snapshot(0, 0, 0, 0)
  }

  "The totals" should "hold more than an Int of bytes" taggedAs (UnitTest) in {
    val t = new P2pTraffic
    for _ <- 1 to 4 do t.received(Int.MaxValue)
    t.snapshot.bytesIn shouldBe 4L * Int.MaxValue
  }

  "Four connections adding at once" should "lose nothing" taggedAs (UnitTest) in {
    val t = new P2pTraffic
    val each = 100_000
    val start = new java.util.concurrent.CountDownLatch(1)
    val threads = (1 to 4).map { n =>
      val thread = new Thread(() =>
        start.await()
        var i = 0
        while i < each do
          t.received(n)
          t.sent(2 * n)
          t.messageReceived()
          t.messageSent()
          i += 1
      )
      thread.start()
      thread
    }
    start.countDown()
    threads.foreach(_.join())
    t.snapshot shouldBe P2pTraffic.Snapshot(10L * each, 20L * each, 4L * each, 4L * each)
  }

  "Two sets of counters" should "count apart: a test's connection never moves the node's" taggedAs (UnitTest) in {
    val a = new P2pTraffic
    val b = new P2pTraffic
    a.received(9)
    b.snapshot shouldBe P2pTraffic.Snapshot(0, 0, 0, 0)
  }
