package com.chipprbots.ethereum.console

import java.lang.management.ManagementFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant

import scala.jdk.CollectionConverters.*
import scala.util.Try

import com.chipprbots.ethereum.db.dataSource.RocksDbCacheHits
import com.chipprbots.ethereum.db.dataSource.RocksDbProperties
import com.chipprbots.ethereum.network.P2pTraffic

/** What the system reader reads of the process and the machine, each read injectable for tests. A read that cannot give
  * a figure gives `None`.
  */
final case class SystemSources(
    processCpu: () => Option[Double] = () => None,
    systemCpu: () => Option[Double] = () => None,
    loadAverage: () => Option[Double] = () => None,
    processors: () => Int = () => 0,
    heapUsed: () => Option[Long] = () => None,
    nonHeap: () => Option[Long] = () => None,
    gcCount: () => Option[Long] = () => None,
    gcMillis: () => Option[Long] = () => None,
    lastGcPause: () => Option[Duration] = () => None,
    threads: () => Option[Int] = () => None,
    peakThreads: () => Option[Int] = () => None,
    uptime: () => Option[Duration] = () => None,
    traffic: () => Option[P2pTraffic.Snapshot] = () => None
)

object SystemSources:

  /** A JVM load figure: a share from 0 to 1, or a negative number where the JVM cannot tell. */
  private def share(value: Double): Option[Double] = Option.when(value >= 0.0 && !value.isNaN)(value.min(1.0))

  /** This JVM and its machine, and the node's RLPx traffic. The CPU figures are the JVM's own
    * (`com.sun.management.OperatingSystemMXBean`), which a JVM that has no such bean does not give.
    */
  def jvm(traffic: () => P2pTraffic.Snapshot): SystemSources =
    val os = ManagementFactory.getOperatingSystemMXBean
    val sun = os match
      case bean: com.sun.management.OperatingSystemMXBean => Some(bean)
      case _                                              => None
    val memory = ManagementFactory.getMemoryMXBean
    val threads = ManagementFactory.getThreadMXBean
    val runtime = ManagementFactory.getRuntimeMXBean
    def pausing = ManagementFactory.getGarbageCollectorMXBeans.asScala.toSeq.filter(b =>
      TuiStatusProbe.LocalSources.pausingCollector(b.getName)
    )
    SystemSources(
      processCpu = () => sun.flatMap(b => share(b.getProcessCpuLoad)),
      systemCpu = () => sun.flatMap(b => share(b.getCpuLoad)),
      loadAverage = () => Option(os.getSystemLoadAverage).filter(_ >= 0.0),
      processors = () => os.getAvailableProcessors,
      heapUsed = () => Some(memory.getHeapMemoryUsage.getUsed),
      nonHeap = () => Some(memory.getNonHeapMemoryUsage.getUsed).filter(_ >= 0),
      gcCount = () => Some(pausing.map(_.getCollectionCount).filter(_ >= 0).sum),
      gcMillis = () => Some(pausing.map(_.getCollectionTime).filter(_ >= 0).sum),
      lastGcPause = () =>
        pausing
          .collect { case b: com.sun.management.GarbageCollectorMXBean => Option(b.getLastGcInfo) }
          .flatten
          .maxByOption(_.getEndTime)
          .map(info => Duration.ofMillis(info.getDuration)),
      threads = () => Some(threads.getThreadCount),
      peakThreads = () => Some(threads.getPeakThreadCount),
      uptime = () => Some(Duration.ofMillis(runtime.getUptime)),
      traffic = () => Some(traffic())
    )

/** Reads the process and the machine once a poll (spec 029 FR-044), and keeps ten minutes of it for the charts.
  *
  * Only the probe's thread calls it: it keeps the last traffic totals and the history without a lock. Each reading that
  * fails is left empty for that poll, and none fails the others.
  */
final class SystemReader(sources: SystemSources, clock: Clock = Clock.systemUTC()) extends (() => Option[SystemSample]):
  import SystemReader.*

  private var history: Vector[SystemPoint] = Vector.empty
  private var traffic: Vector[(Instant, P2pTraffic.Snapshot)] = Vector.empty
  private var gc: Option[(Instant, Long)] = None

  private def read[A](f: => Option[A]): Option[A] = Try(f).toOption.flatten

  def apply(): Option[SystemSample] =
    val at = clock.instant()
    // A clock that stepped back says nothing of what came before it.
    if history.lastOption.exists(_.at.isAfter(at)) || traffic.lastOption.exists(_._1.isAfter(at)) then
      history = Vector.empty
      traffic = Vector.empty
      gc = None
    val totals = read(sources.traffic())
    totals.foreach { t =>
      traffic = traffic :+ (at -> t)
      // The readings of the last few seconds, and never fewer than two: a dashboard that polls slower than the window
      // still has a rate, over its last two polls.
      while traffic.size > 2 && Duration.between(traffic.head._1, at).compareTo(RateWindow) > 0 do
        traffic = traffic.tail
    }
    // A second's worth over the readings of the last few seconds: one poll's difference alone jumps with the poll.
    def perSecond(of: P2pTraffic.Snapshot => Long): Option[Double] =
      for
        (firstAt, first) <- traffic.headOption
        (lastAt, last) <- traffic.lastOption
        millis = Duration.between(firstAt, lastAt).toMillis
        if millis >= 1_000
      yield (of(last) - of(first)).max(0L).toDouble * 1000.0 / millis
    val sample = totals.map(t =>
      TrafficSample(
        t.bytesIn,
        t.bytesOut,
        t.messagesIn,
        t.messagesOut,
        perSecond(_.bytesIn),
        perSecond(_.bytesOut),
        perSecond(_.messagesIn),
        perSecond(_.messagesOut)
      )
    )
    val processCpu = read(sources.processCpu())
    val systemCpu = read(sources.systemCpu())
    val threads = read(sources.threads())
    val nonHeap = read(sources.nonHeap())
    val gcMillis = read(sources.gcMillis())
    val gcShare = for
      total <- gcMillis
      (before, millis) <- gc
      wall = Duration.between(before, at).toMillis
      if wall > 0
    yield ((total - millis).max(0L).toDouble / wall).min(1.0)
    if history.lastOption.forall(last => Duration.between(last.at, at).compareTo(Step) >= 0) then
      gc = gcMillis.map(at -> _)
      history = (history :+ SystemPoint(
        at,
        processCpu,
        systemCpu,
        read(sources.heapUsed()),
        nonHeap,
        gcShare,
        gcMillis,
        threads,
        sample.flatMap(_.bytesInPerSecond),
        sample.flatMap(_.bytesOutPerSecond),
        sample.flatMap(_.messagesInPerSecond),
        sample.flatMap(_.messagesOutPerSecond)
      )).takeRight(Points)
    Some(
      SystemSample(
        processCpu = processCpu,
        systemCpu = systemCpu,
        loadAverage = read(sources.loadAverage()),
        processors = Try(sources.processors()).getOrElse(0),
        nonHeap = nonHeap,
        gcCount = read(sources.gcCount()),
        lastGcPause = read(sources.lastGcPause()),
        threads = threads,
        peakThreads = read(sources.peakThreads()),
        uptime = read(sources.uptime()),
        traffic = sample,
        history = history
      )
    )

object SystemReader:

  /** A point of history every five seconds, and ten minutes of them. */
  val Step: Duration = Duration.ofSeconds(5)
  val Points: Int = 121

  /** The readings a rate is taken over. */
  val RateWindow: Duration = Duration.ofSeconds(5)

/** One reading of the database: its own figures, and the node's markers when they were asked for. */
final case class StorageRead(properties: RocksDbProperties, markers: Option[Vector[(String, String)]])

/** What the storage reader reads, each read injectable for tests.
  *
  * @param read
  *   the database's own figures, with its estimates when the first argument asks, and the node's markers when the
  *   second does; None while a write holds the database or once it is closed. The markers are point reads, which
  *   outside the same hold of the database would queue behind a writer for as long as a compaction runs: whoever gives
  *   this makes both readings in one `RocksDbDataSource.whileFree`
  * @param storeName
  *   what a column family holds, in words, by its namespace
  * @param audit
  *   the background audit, asked what it has got to
  */
final case class StorageSources(
    read: (Boolean, Boolean) => Option[StorageRead] = (_, _) => None,
    storeName: IndexedSeq[Byte] => String = ns => new String(ns.toArray, java.nio.charset.StandardCharsets.UTF_8),
    audit: () => AuditReading = () => AuditReading.NotRunning
)

/** Reads the database every few polls (spec 029 FR-043), its estimates and the node's markers less often, and keeps ten
  * minutes of its size.
  *
  * The database's read gives way to any write, so a poll that finds it busy keeps the last sample, which says when it
  * was read. Only the probe's thread calls it.
  */
final class StorageReader(sources: StorageSources, clock: Clock = Clock.systemUTC())
    extends (() => Option[StorageSample]):
  import StorageReader.*

  private var last: Option[StorageSample] = None
  private var readAt: Option[Instant] = None
  private var estimatedAt: Option[Instant] = None
  private var estimates: (Option[Long], Map[String, Long]) = (None, Map.empty)
  private var markers: Vector[(String, String)] = Vector.empty
  private var hits: Option[(Long, Long)] = None

  private def due(since: Option[Instant], every: Duration, at: Instant): Boolean =
    since.forall(before => before.isAfter(at) || Duration.between(before, at).compareTo(every) >= 0)

  def apply(): Option[StorageSample] =
    val at = clock.instant()
    if due(readAt, Every, at) then
      readAt = Some(at)
      val withEstimates = due(estimatedAt, EstimatesEvery, at)
      // The estimates and the markers are read together, and less often.
      Try(sources.read(withEstimates, withEstimates)).toOption.flatten.foreach { reading =>
        val p = reading.properties
        if withEstimates then
          estimatedAt = Some(at)
          estimates = (p.liveDataBytes, p.stores.flatMap(s => s.keys.map(sources.storeName(s.namespace) -> _)).toMap)
        reading.markers.foreach(read => markers = read)
        val cache = p.cache match
          case RocksDbCacheHits.Off => CacheReading.Off
          case RocksDbCacheHits.On(hit, miss, _, _) =>
            val rate = hits.flatMap { (h, m) =>
              val lookups = (hit - h) + (miss - m)
              Option.when(hit >= h && miss >= m && lookups > 0)((hit - h).toDouble / lookups)
            }
            hits = Some((hit, miss))
            CacheReading.On(hit, miss, rate)
        val history = last.fold(Vector.empty[(Instant, Long)])(_.sizeHistory)
        last = Some(
          StorageSample(
            at = at,
            sstBytes = p.sstBytes,
            liveDataBytes = estimates._1,
            stores = p.stores.map { s =>
              val name = sources.storeName(s.namespace)
              StoreSize(name, s.sstBytes, estimates._2.get(name))
            }.toVector,
            compactionPending = p.compactionPending,
            pendingCompactionBytes = p.pendingCompactionBytes,
            runningCompactions = p.runningCompactions,
            writeStopped = p.writeStopped,
            delayedWriteRate = p.delayedWriteRate,
            blockCacheUsed = p.blockCacheUsage,
            blockCacheCapacity = p.blockCacheCapacity,
            memTables = p.memTables,
            tableReaders = p.tableReaders,
            cache = cache,
            markers = markers,
            audit = Try(sources.audit()).getOrElse(AuditReading.NoAnswer),
            sizeHistory = p.sstBytes.fold(history)(size => (history :+ (at -> size)).takeRight(Points))
          )
        )
      }
    last

object StorageReader:

  /** The database is read this often, and its estimates and the node's markers this often. */
  val Every: Duration = Duration.ofSeconds(5)
  val EstimatesEvery: Duration = Duration.ofSeconds(30)

  /** Ten minutes of sizes. */
  val Points: Int = 121

  /** What each column family holds, by its namespace's letter (`db/storage/Namespaces.scala`). */
  val StoreNames: Map[String, String] = Map(
    "default" -> "default",
    "r" -> "receipts",
    "h" -> "headers",
    "b" -> "bodies",
    "n" -> "trie nodes by hash",
    "c" -> "contract code",
    "w" -> "chain weights",
    "s" -> "node state",
    "k" -> "known peers",
    "i" -> "canonical hash by number",
    "f" -> "fast sync state",
    "l" -> "transaction index",
    "m" -> "block first seen",
    "d" -> "flat storage slots",
    "a" -> "flat accounts",
    "g" -> "healing frontier (retired)",
    "q" -> "frontier rebuild queue (legacy)",
    "p" -> "SNAP progress",
    "t" -> "state trie by path",
    "u" -> "storage tries by path",
    "j" -> "block access lists",
    "v" -> "state changesets"
  )

  /** A column family's name in words; a family this list does not know is named by its namespace. */
  def storeName(namespace: IndexedSeq[Byte]): String =
    val raw = new String(namespace.toArray, java.nio.charset.StandardCharsets.UTF_8)
    StoreNames.getOrElse(raw, raw)
