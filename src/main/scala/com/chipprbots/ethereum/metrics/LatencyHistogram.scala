package com.chipprbots.ethereum.metrics

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.LongAdder

/** A latency histogram with fixed buckets, kept in process (spec 029 FR-038): the count, the sum, the maximum and a
  * count a bucket, from which a percentile is read.
  *
  * The buckets are log-spaced from [[LatencyHistogram.MinNanos]] to [[LatencyHistogram.MaxNanos]], each
  * [[LatencyHistogram.Ratio]] times the one before, with one below the range and one above. A percentile is reported at
  * its bucket's geometric mean, so it is within √ratio − 1 (under 10 %) of the true value inside the range.
  *
  * Recording takes no lock and allocates nothing: it may be called from any thread, on a request's own path.
  */
final class LatencyHistogram:
  import LatencyHistogram.*

  private val counts = new AtomicLongArray(Buckets + 2)
  private val sum = new LongAdder
  private val max = new AtomicLong(0L)

  /** One latency. A negative one (a clock that went back) is counted as nothing elapsed. */
  def record(nanos: Long): Unit =
    val elapsed = nanos.max(0L)
    // The maximum first, and a snapshot reads it last: the maximum a snapshot holds is never under a latency it
    // counted.
    max.accumulateAndGet(elapsed, (a, b) => Math.max(a, b))
    sum.add(elapsed)
    counts.incrementAndGet(bucketOf(elapsed))

  /** The histogram at this instant. The buckets are read one after another while others record, so a snapshot taken
    * under load may be a few recordings short in one bucket; its count is the sum of the buckets it read.
    */
  def snapshot: Snapshot =
    Snapshot(Vector.tabulate(Buckets + 2)(counts.get), sum.sum(), max.get)

object LatencyHistogram:

  /** The buckets between the two ends of the range. */
  val Buckets: Int = 72

  /** The range: 100 µs to 60 s. */
  val MinNanos: Long = 100_000L
  val MaxNanos: Long = 60_000_000_000L

  /** Each bucket's upper bound over its lower: about 1.2. */
  val Ratio: Double = Math.pow(MaxNanos.toDouble / MinNanos.toDouble, 1.0 / Buckets)

  /** The bounds of the buckets in the range: bucket `i` (from 1) holds latencies from `bounds(i - 1)` up to, not
    * including, `bounds(i)`. Bucket 0 is below the range and bucket `Buckets + 1` is at or above its end.
    */
  private val bounds: Array[Long] =
    Array.tabulate(Buckets + 1)(i =>
      if i == Buckets then MaxNanos else Math.round(MinNanos.toDouble * Math.pow(Ratio, i.toDouble))
    )

  /** The bucket of a latency: a binary search over the bounds, so the same latency is always in the same bucket. */
  private[metrics] def bucketOf(nanos: Long): Int =
    if nanos < MinNanos then 0
    else if nanos >= MaxNanos then Buckets + 1
    else
      var low = 0
      var high = Buckets
      // The greatest `low` with bounds(low) <= nanos.
      while high - low > 1 do
        val mid = (low + high) >>> 1
        if bounds(mid) <= nanos then low = mid else high = mid
      low + 1

  /** A histogram's contents.
    *
    * @param counts
    *   the latencies in each bucket: below the range, the range's buckets, at or above its end
    * @param sumNanos
    *   the sum of every latency
    * @param maxNanos
    *   the longest
    */
  final case class Snapshot(counts: Vector[Long], sumNanos: Long, maxNanos: Long):

    /** How many latencies were recorded. */
    val count: Long = counts.sum

    /** The mean, in nanoseconds; None with nothing recorded. */
    def meanNanos: Option[Long] = Option.when(count > 0)(sumNanos / count)

    /** The latency at or under which `p` of the recordings fall (`p` from 0 to 1), in nanoseconds: the geometric mean
      * of its bucket, and never more than the longest recorded. Below the range it is the range's start, and above it
      * the longest recorded: neither end is measured finer than that. None with nothing recorded.
      */
    def percentile(p: Double): Option[Long] =
      Option.when(count > 0) {
        val bucket = bucketAt(p)
        val estimate =
          if bucket == 0 then MinNanos
          else if bucket == Buckets + 1 then maxNanos
          else Math.round(Math.sqrt(bounds(bucket - 1).toDouble * bounds(bucket).toDouble))
        estimate.min(maxNanos)
      }

    /** Whether the latency at `p` is below the range: [[percentile]] then gives the range's start, which the latency is
      * under, and by how much is not known. False with nothing recorded.
      */
    def belowRange(p: Double): Boolean = count > 0 && bucketAt(p) == 0

    /** The bucket the recording of rank `p` is in. */
    private def bucketAt(p: Double): Int =
      val rank = Math.ceil(p.max(0.0).min(1.0) * count).toLong.max(1L)
      var seen = 0L
      var bucket = 0
      while bucket < counts.size - 1 && seen + counts(bucket) < rank do
        seen += counts(bucket)
        bucket += 1
      bucket
