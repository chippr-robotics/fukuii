package com.chipprbots.ethereum.console

import java.time.Duration
import java.time.Instant

/** The process and the machine at one poll, for the System view's charts (spec 029 FR-044). A figure the JVM could not
  * give is `None`, never 0.
  *
  * @param gcShare
  *   the pausing collectors' share of the time since the point before this one
  * @param gcTotalMillis
  *   their total since the JVM started: two points' totals give the share of the time between them, however far apart
  *   the points are
  */
final case class SystemPoint(
    at: Instant,
    processCpu: Option[Double],
    systemCpu: Option[Double],
    heapUsed: Option[Long],
    nonHeap: Option[Long],
    gcShare: Option[Double],
    gcTotalMillis: Option[Long],
    threads: Option[Int],
    bytesInPerSecond: Option[Double],
    bytesOutPerSecond: Option[Double],
    messagesInPerSecond: Option[Double],
    messagesOutPerSecond: Option[Double]
)

/** What the RLPx connections carried: totals since the node started, and a second's worth over the last few seconds
  * once there are two readings to compare.
  */
final case class TrafficSample(
    bytesIn: Long,
    bytesOut: Long,
    messagesIn: Long,
    messagesOut: Long,
    bytesInPerSecond: Option[Double] = None,
    bytesOutPerSecond: Option[Double] = None,
    messagesInPerSecond: Option[Double] = None,
    messagesOutPerSecond: Option[Double] = None
)

/** The process and the machine (spec 029 FR-044).
  *
  * @param processCpu
  *   the share of the machine's processors this process used over the JVM's last measuring interval, 0 to 1
  * @param systemCpu
  *   the same for the whole machine
  * @param loadAverage
  *   the machine's one-minute load average, where the system has one
  * @param nonHeap
  *   the JVM's memory outside the heap: metaspace, code cache, and the like. Native memory the JVM does not account for
  *   (RocksDB's) is not in it; the Storage view has that
  * @param gcCount
  *   pausing collections since the JVM started
  * @param lastGcPause
  *   how long the last pausing collection took, where the JVM says
  * @param uptime
  *   how long the process has run, by the JVM
  * @param history
  *   a point about every five seconds, oldest first, ten minutes of them
  */
final case class SystemSample(
    processCpu: Option[Double] = None,
    systemCpu: Option[Double] = None,
    loadAverage: Option[Double] = None,
    processors: Int = 0,
    nonHeap: Option[Long] = None,
    gcCount: Option[Long] = None,
    lastGcPause: Option[Duration] = None,
    threads: Option[Int] = None,
    peakThreads: Option[Int] = None,
    uptime: Option[Duration] = None,
    traffic: Option[TrafficSample] = None,
    history: Vector[SystemPoint] = Vector.empty
)

/** One column family as the Storage view lists it.
  *
  * @param name
  *   what the family holds, in a word or two
  * @param sstBytes
  *   the bytes of its files on disk
  * @param keys
  *   the database's estimate of its keys
  */
final case class StoreSize(name: String, sstBytes: Option[Long], keys: Option[Long])

/** The block cache's hits, where the database counts them. */
enum CacheReading:
  /** `db.rocksdb.enable-statistics` is off: nothing is counted. */
  case Off

  /** @param hitRate
    *   hits over lookups between the last two readings; None before there are two, or with no lookup between them
    */
  case On(hits: Long, misses: Long, hitRate: Option[Double])

/** The background audit of the state, as it says of itself. */
final case class AuditSample(
    pass: Long,
    nodes: Long,
    accounts: Long,
    holes: Long,
    repaired: Long,
    unresolved: Long,
    paused: Boolean,
    waitingForNextPass: Boolean,
    elapsed: Duration
)

/** Whether the audit runs, and what it has got to. */
enum AuditReading:
  /** No audit is running on this node now. */
  case NotRunning

  /** One is running and did not answer in time. */
  case NoAnswer
  case Running(audit: AuditSample)

/** The database and its disk (spec 029 FR-043). A figure the database could not give is `None`.
  *
  * @param at
  *   when the database was last read. The read gives way to any write, so under a long write the sample is of a while
  *   ago, and says so
  * @param sstBytes
  *   every file of the database on disk, without its write-ahead log
  * @param liveDataBytes
  *   the database's estimate of what is live in them
  * @param compactionPending
  *   how many column families want a compaction
  * @param pendingCompactionBytes
  *   the bytes compaction has to rewrite to catch up
  * @param writeStopped
  *   the database refuses writes until its flushes and compactions catch up
  * @param blockCacheUsed
  *   the block cache's use, which includes what the memtables reserve in it: `memTables` is part of it, not beside it
  * @param delayedWriteRate
  *   bytes a second the database holds writes to; 0 when it is not slowing them
  * @param markers
  *   the node's own marks of what its state is and what it still owes, each in words
  * @param sizeHistory
  *   the size on disk at each reading, oldest first, ten minutes of them
  */
final case class StorageSample(
    at: Instant,
    sstBytes: Option[Long] = None,
    liveDataBytes: Option[Long] = None,
    stores: Vector[StoreSize] = Vector.empty,
    compactionPending: Option[Long] = None,
    pendingCompactionBytes: Option[Long] = None,
    runningCompactions: Option[Long] = None,
    writeStopped: Option[Boolean] = None,
    delayedWriteRate: Option[Long] = None,
    blockCacheUsed: Long = 0L,
    blockCacheCapacity: Long = 0L,
    memTables: Option[Long] = None,
    tableReaders: Option[Long] = None,
    cache: CacheReading = CacheReading.Off,
    markers: Vector[(String, String)] = Vector.empty,
    audit: AuditReading = AuditReading.NotRunning,
    sizeHistory: Vector[(Instant, Long)] = Vector.empty
):

  /** How fast the database grows, in bytes an hour, over the readings kept: None until they span a minute. A database
    * that compaction just shrank grows at less than nothing, and that is said.
    */
  def growthPerHour: Option[Double] =
    for
      (firstAt, first) <- sizeHistory.headOption
      (lastAt, last) <- sizeHistory.lastOption
      seconds = Duration.between(firstAt, lastAt).getSeconds
      if seconds >= 60
    yield (last - first).toDouble * 3600.0 / seconds
