package com.chipprbots.ethereum.metrics

import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

/** The latency histogram (spec 029 FR-038, SC-010): percentiles within 10 % of the truth, and nothing lost when several
  * threads record at once.
  */
class LatencyHistogramSpec extends AnyFlatSpec with Matchers with OptionValues:

  private val Micro = 1_000L
  private val Milli = 1_000_000L
  private val Second = 1_000_000_000L

  /** The true percentile of `sorted`: the value at or under which `p` of them fall. */
  private def truth(sorted: IndexedSeq[Long], p: Double): Long =
    sorted((Math.ceil(p * sorted.size).toInt - 1).max(0))

  private def histogramOf(values: Iterable[Long]): LatencyHistogram.Snapshot =
    val h = new LatencyHistogram
    values.foreach(h.record)
    h.snapshot

  "An empty histogram" should "have no percentile and no mean: nothing was measured" taggedAs (UnitTest) in {
    val empty = new LatencyHistogram().snapshot
    empty.count shouldBe 0
    empty.percentile(0.5) shouldBe None
    empty.meanNanos shouldBe None
    empty.maxNanos shouldBe 0
  }

  "A percentile" should "be within 10 % of the true one for latencies spread over the whole range" taggedAs (
    UnitTest
  ) in {
    // Three shapes: evenly spaced, log-spaced over five decades, and a fast crowd with a slow tail.
    val linear = (1 to 10_000).map(i => i * 100 * Micro)
    val log = (0 until 10_000).map(i => Math.round(200 * Micro * Math.pow(10.0, i * 5.0 / 10_000)))
    val tailed = (1 to 9_500).map(i => 300 * Micro + i * Micro) ++ (1 to 500).map(i => 2 * Second + i * 10 * Milli)
    for (name, values) <- Seq("linear" -> linear, "log" -> log, "tailed" -> tailed); p <- Seq(0.5, 0.9, 0.95, 0.99) do
      val sorted = values.sorted.toIndexedSeq
      val exact = truth(sorted, p)
      val read = histogramOf(values).percentile(p).value
      withClue(s"$name p$p: read $read, true $exact: ") {
        Math.abs(read - exact).toDouble / exact should be <= 0.10
      }
  }

  it should "be within 10 % at every point of the range, a bucket at a time" taggedAs (UnitTest) in {
    // A latency with a far longer one beside it, so that the estimate is the bucket's own and not the maximum's:
    // alone, a latency is the longest recorded and an estimate above it is clamped back onto it, which hides the
    // worst case, a latency at the bottom of its bucket.
    var nanos = LatencyHistogram.MinNanos.toDouble
    var worstOver = 0.0
    var worstUnder = 0.0
    while nanos < LatencyHistogram.MaxNanos / 2 do
      val value = Math.round(nanos)
      val read = histogramOf(Seq(value, value, LatencyHistogram.MaxNanos - 1)).percentile(0.5).value
      val off = (read - value).toDouble / value
      worstOver = worstOver.max(off)
      worstUnder = worstUnder.min(off)
      withClue(s"$value ns read as $read: ")(Math.abs(off) should be <= 0.10)
      nanos *= 1.013
    // Both sides are reached: an estimate above a latency at its bucket's bottom, and below one at its top.
    worstOver should be > 0.08
    worstUnder should be < -0.07
  }

  "A percentile below the range" should "be said to be below it, and one in the range not" taggedAs (UnitTest) in {
    val fast = histogramOf(Seq(5 * Micro, 20 * Micro, 90 * Micro, 30 * Milli))
    fast.belowRange(0.5) shouldBe true
    fast.belowRange(0.75) shouldBe true
    fast.belowRange(1.0) shouldBe false
    fast.percentile(0.5).value shouldBe 100 * Micro // the most it can be
    histogramOf(Seq(100 * Micro)).belowRange(0.5) shouldBe false
    new LatencyHistogram().snapshot.belowRange(0.5) shouldBe false
  }

  "A snapshot taken while a latency is being recorded" should "never hold a maximum under what it counted" taggedAs (
    UnitTest
  ) in {
    val h = new LatencyHistogram
    val stop = new java.util.concurrent.atomic.AtomicBoolean(false)
    val writer = new Thread(() =>
      var i = 0L
      while !stop.get do
        h.record(Milli + (i % 1_000) * Micro)
        i += 1
    )
    writer.start()
    try
      for _ <- 1 to 20_000 do
        val s = h.snapshot
        if s.count > 0 then
          withClue(s"count ${s.count}, max ${s.maxNanos}: ")(s.maxNanos should be >= Milli)
          s.percentile(0.5).value should be >= (Milli * 9 / 10)
    finally
      stop.set(true)
      writer.join()
  }

  it should "never be more than the longest latency recorded" taggedAs (UnitTest) in {
    // 101 µs is in a bucket whose middle is above it: the estimate is the latency itself, not the middle.
    histogramOf(Seq(101 * Micro)).percentile(0.99).value shouldBe 101 * Micro
    val h = histogramOf(Seq(5 * Milli, 5 * Milli, 5 * Milli))
    h.percentile(1.0).value should be <= 5 * Milli
  }

  it should "say the range's start for what is below it, and the longest recorded for what is above" taggedAs (
    UnitTest
  ) in {
    val fast = histogramOf(Seq(3 * Micro, 20 * Micro, 150 * Micro))
    fast.percentile(0.5).value shouldBe 100 * Micro // not finer than the range
    histogramOf(Seq(3 * Micro)).percentile(0.5).value shouldBe 3 * Micro // and never above what was recorded
    val slow = histogramOf(Seq(Second, 90 * Second, 400 * Second))
    slow.percentile(0.99).value shouldBe 400 * Second
    slow.maxNanos shouldBe 400 * Second
  }

  it should "take a rank, not interpolate: p of nothing is the first, and out-of-range p is clamped" taggedAs (
    UnitTest
  ) in {
    val h = histogramOf(Seq(Milli, 10 * Milli, 100 * Milli, Second))
    h.percentile(0.0).value shouldBe h.percentile(0.25).value
    h.percentile(-1.0).value shouldBe h.percentile(0.0).value
    h.percentile(7.0).value shouldBe h.percentile(1.0).value
    h.percentile(0.5).value should be < h.percentile(0.75).value
  }

  "A recording" should "add to the count, the sum and the maximum, and a negative one count as nothing elapsed" taggedAs (
    UnitTest
  ) in {
    val h = new LatencyHistogram
    Seq(2 * Milli, 4 * Milli, -5L).foreach(h.record)
    val s = h.snapshot
    (s.count, s.sumNanos, s.maxNanos) shouldBe (3L, 6 * Milli, 4 * Milli)
    s.meanNanos.value shouldBe 2 * Milli
    s.counts.head shouldBe 1 // the negative one, below the range
  }

  "The buckets" should "cover every latency once: each bound starts its own bucket" taggedAs (UnitTest) in {
    LatencyHistogram.bucketOf(0) shouldBe 0
    LatencyHistogram.bucketOf(LatencyHistogram.MinNanos - 1) shouldBe 0
    LatencyHistogram.bucketOf(LatencyHistogram.MinNanos) shouldBe 1
    LatencyHistogram.bucketOf(LatencyHistogram.MaxNanos - 1) shouldBe LatencyHistogram.Buckets
    LatencyHistogram.bucketOf(LatencyHistogram.MaxNanos) shouldBe LatencyHistogram.Buckets + 1
    LatencyHistogram.bucketOf(Long.MaxValue) shouldBe LatencyHistogram.Buckets + 1
    // The bucket never goes down as the latency goes up, and no bucket in the range is skipped.
    val seen =
      Iterator.iterate(50_000.0)(_ * 1.01).takeWhile(_ < 9e10).map(n => LatencyHistogram.bucketOf(n.toLong)).toVector
    seen shouldBe seen.sorted
    seen.distinct shouldBe (0 to LatencyHistogram.Buckets + 1)
    // A bucket is narrow enough for its middle to be within 10 % of either end.
    Math.sqrt(LatencyHistogram.Ratio) - 1 should be < 0.10
  }

  "Recordings from four threads at once" should "lose nothing" taggedAs (UnitTest) in {
    val h = new LatencyHistogram
    val each = 100_000
    val start = new java.util.concurrent.CountDownLatch(1)
    val threads = (0 until 4).map { t =>
      val thread = new Thread(() =>
        start.await()
        var i = 0
        while i < each do
          h.record((t + 1) * Milli + i)
          i += 1
      )
      thread.start()
      thread
    }
    start.countDown()
    threads.foreach(_.join())
    val s = h.snapshot
    s.count shouldBe 4L * each
    s.sumNanos shouldBe (1 to 4).map(t => t * Milli * each + each.toLong * (each - 1) / 2).sum
    s.maxNanos shouldBe 4 * Milli + each - 1
  }
