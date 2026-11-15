package com.chipprbots.ethereum.console

import java.time.Duration

import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.db.dataSource.RocksDbCacheHits
import com.chipprbots.ethereum.db.dataSource.RocksDbProperties
import com.chipprbots.ethereum.db.dataSource.RocksDbStoreProperties
import com.chipprbots.ethereum.network.P2pTraffic
import com.chipprbots.ethereum.testing.Tags.*

/** The probe's system and storage readers (spec 029 FR-043, FR-044): what they keep between polls, how often they read,
  * and that a reading that cannot be taken is empty and never nothing.
  */
class ResourceReadersSpec extends AnyFlatSpec with Matchers with OptionValues:

  private val now = TuiFixtures.Now

  // ---- the system reader --------------------------------------------------------------------------------------------

  private class Machine:
    var traffic: P2pTraffic.Snapshot = P2pTraffic.Snapshot(0, 0, 0, 0)
    var gcMillis: Long = 0
    var cpu: Option[Double] = Some(0.25)
    var heap: Long = 1_000
    val sources: SystemSources = SystemSources(
      processCpu = () => cpu,
      systemCpu = () => Some(0.5),
      loadAverage = () => Some(1.5),
      processors = () => 8,
      heapUsed = () => Some(heap),
      nonHeap = () => Some(200L),
      gcCount = () => Some(3L),
      gcMillis = () => Some(gcMillis),
      lastGcPause = () => Some(Duration.ofMillis(12)),
      threads = () => Some(40),
      peakThreads = () => Some(44),
      uptime = () => Some(Duration.ofSeconds(90)),
      traffic = () => Some(traffic)
    )

  "The system reader" should "give the figures as read, and no rate before it has two readings a second apart" taggedAs (
    UnitTest
  ) in {
    val clock = new TuiFixtures.TestClock(now)
    val machine = new Machine
    val reader = new SystemReader(machine.sources, clock)
    val first = reader().value
    first.processCpu shouldBe Some(0.25)
    first.processors shouldBe 8
    first.uptime shouldBe Some(Duration.ofSeconds(90))
    first.traffic.value.bytesInPerSecond shouldBe None
    first.history should have size 1
    // The first point has no interval to take the collector's share over.
    first.history.head.gcShare shouldBe None
  }

  it should "give a second's worth of traffic over the readings of the last five seconds" taggedAs (UnitTest) in {
    val clock = new TuiFixtures.TestClock(now)
    val machine = new Machine
    val reader = new SystemReader(machine.sources, clock)
    reader()
    for second <- 1 to 5 do
      clock.advanceMillis(1_000L)
      machine.traffic = P2pTraffic.Snapshot(second * 4_000L, second * 1_000L, second * 20L, second * 10L)
      reader()
    val sample = reader().value
    sample.traffic.value.bytesInPerSecond.value shouldBe 4_000.0 +- 0.001
    sample.traffic.value.bytesOutPerSecond.value shouldBe 1_000.0 +- 0.001
    sample.traffic.value.messagesInPerSecond.value shouldBe 20.0 +- 0.001
    sample.traffic.value.messagesOutPerSecond.value shouldBe 10.0 +- 0.001
    sample.traffic.value.bytesIn shouldBe 20_000L
    // Then nothing moves: the rate falls to nothing once the window holds only still readings.
    for _ <- 1 to 6 do
      clock.advanceMillis(1_000L)
      reader()
    reader().value.traffic.value.bytesInPerSecond.value shouldBe 0.0
  }

  it should "keep a point every five seconds, ten minutes of them, with the collector's share of each step" taggedAs (
    UnitTest
  ) in {
    val clock = new TuiFixtures.TestClock(now)
    val machine = new Machine
    val reader = new SystemReader(machine.sources, clock)
    reader()
    for _ <- 1 to 4 do
      clock.advanceMillis(1_000L)
      reader().value.history should have size 1
    clock.advanceMillis(1_000L)
    // 500 ms of pauses in the five seconds since the first point.
    machine.gcMillis = 500
    val second = reader().value
    second.history should have size 2
    second.history.last.gcShare.value shouldBe 0.1 +- 0.0001
    second.history.last.at shouldBe now.plusSeconds(5)
    for _ <- 1 to 700 do
      clock.advanceMillis(1_000L)
      reader()
    val late = reader().value
    late.history should have size SystemReader.Points.toLong
    Duration.between(late.history.head.at, late.history.last.at) shouldBe Duration.ofMinutes(10)
  }

  it should "leave a figure the JVM does not give empty, in the sample and in the history" taggedAs (UnitTest) in {
    val clock = new TuiFixtures.TestClock(now)
    val machine = new Machine
    machine.cpu = None
    val sample = new SystemReader(machine.sources, clock)().value
    sample.processCpu shouldBe None
    sample.history.head.processCpu shouldBe None
    sample.systemCpu shouldBe Some(0.5)
  }

  it should "lose one reading that throws and keep the rest" taggedAs (UnitTest) in {
    val clock = new TuiFixtures.TestClock(now)
    val machine = new Machine
    val broken = machine.sources.copy(threads = () => throw new IllegalStateException("no bean"))
    val sample = new SystemReader(broken, clock)().value
    sample.threads shouldBe None
    sample.processCpu shouldBe Some(0.25)
    sample.nonHeap shouldBe Some(200L)
  }

  it should "start its history again when the clock steps back" taggedAs (UnitTest) in {
    val clock = new TuiFixtures.TestClock(now)
    val machine = new Machine
    val reader = new SystemReader(machine.sources, clock)
    reader()
    clock.advanceMillis(10_000L)
    reader().value.history should have size 2
    clock.advanceMillis(-60_000L)
    val after = reader().value
    after.history should have size 1
    after.traffic.value.bytesInPerSecond shouldBe None
  }

  "The JVM's sources" should "read this JVM without failing, and give a share between 0 and 1 or nothing" taggedAs (
    UnitTest
  ) in {
    val sources = SystemSources.jvm(() => P2pTraffic.Snapshot(1, 2, 3, 4))
    sources.processors() should be > 0
    sources.heapUsed().value should be > 0L
    sources.threads().value should be > 0
    sources.uptime().value.isNegative shouldBe false
    sources.gcCount().value should be >= 0L
    sources.processCpu().foreach(share => share should ((be >= 0.0).and(be <= 1.0)))
    sources.systemCpu().foreach(share => share should ((be >= 0.0).and(be <= 1.0)))
    sources.traffic() shouldBe Some(P2pTraffic.Snapshot(1, 2, 3, 4))
  }

  // ---- the storage reader -------------------------------------------------------------------------------------------

  private def ns(name: String): IndexedSeq[Byte] = name.getBytes("UTF-8").toIndexedSeq

  private def properties(
      size: Long,
      estimates: Boolean,
      cache: RocksDbCacheHits
  ): RocksDbProperties =
    RocksDbProperties(
      sstBytes = Some(size),
      liveDataBytes = Option.when(estimates)(size - 100),
      stores = Seq(
        RocksDbStoreProperties(ns("h"), Some(size / 4), Option.when(estimates)(1_000L)),
        RocksDbStoreProperties(ns("zz"), Some(0L), Option.when(estimates)(0L))
      ),
      compactionPending = Some(1L),
      pendingCompactionBytes = Some(2_048L),
      runningCompactions = Some(1L),
      writeStopped = Some(false),
      delayedWriteRate = Some(0L),
      blockCacheUsage = 10,
      blockCacheCapacity = 100,
      memTables = Some(20L),
      tableReaders = None,
      cache = cache
    )

  private class Database:
    var busy: Boolean = false
    var size: Long = 10_000
    var cache: RocksDbCacheHits = RocksDbCacheHits.Off
    var reads: Vector[Boolean] = Vector.empty
    var markerReads: Int = 0
    val sources: StorageSources = StorageSources(
      // As the node gives it: the markers are read inside the reading that found the database free, or not at all.
      read = (withEstimates, withMarkers) =>
        reads :+= withEstimates
        Option.unless(busy) {
          if withMarkers then markerReads += 1
          StorageRead(properties(size, withEstimates, cache), Option.when(withMarkers)(Vector("SNAP sync" -> "done")))
        }
      ,
      storeName = StorageReader.storeName,
      audit = () => AuditReading.NotRunning
    )

  "The storage reader" should "read the database every five seconds, and its estimates and the markers every thirty" taggedAs (
    UnitTest
  ) in {
    val clock = new TuiFixtures.TestClock(now)
    val db = new Database
    val reader = new StorageReader(db.sources, clock)
    val first = reader().value
    first.at shouldBe now
    first.sstBytes shouldBe Some(10_000L)
    first.liveDataBytes shouldBe Some(9_900L)
    first.markers shouldBe Vector("SNAP sync" -> "done")
    first.blockCacheUsed shouldBe 10L
    first.blockCacheCapacity shouldBe 100L
    first.memTables shouldBe Some(20L)
    // A figure the database could not give is not a zero.
    first.tableReaders shouldBe None
    for _ <- 1 to 29 do
      clock.advanceMillis(1_000L)
      reader()
    // Polls 0, 5, …, 25 read; only the first asked for the estimates and read the markers.
    db.reads shouldBe Vector(true, false, false, false, false, false)
    db.markerReads shouldBe 1
    clock.advanceMillis(1_000L)
    val later = reader().value
    db.reads.last shouldBe true
    db.markerReads shouldBe 2
    // The estimates of the last estimating read are kept by the reads between.
    later.liveDataBytes shouldBe Some(9_900L)
    later.stores.find(_.name == "headers").value.keys shouldBe Some(1_000L)
  }

  it should "name a store by what it holds, and one it does not know by its namespace" taggedAs (UnitTest) in {
    val clock = new TuiFixtures.TestClock(now)
    val sample = new StorageReader(new Database().sources, clock)().value
    sample.stores.map(_.name) shouldBe Vector("headers", "zz")
    StorageReader.storeName(ns("default")) shouldBe "default"
    StorageReader.storeName(ns("t")) shouldBe "state trie by path"
  }

  it should "keep the last reading, with its time, while the database is busy, and not read the markers then" taggedAs (
    UnitTest
  ) in {
    val clock = new TuiFixtures.TestClock(now)
    val db = new Database
    val reader = new StorageReader(db.sources, clock)
    reader()
    db.busy = true
    for _ <- 1 to 60 do
      clock.advanceMillis(1_000L)
      reader()
    val held = reader().value
    held.at shouldBe now
    held.sstBytes shouldBe Some(10_000L)
    // Each busy read asked for the estimates (they were due), and none read the markers: they are read only inside
    // a reading that found the database free.
    db.markerReads shouldBe 1
    db.busy = false
    clock.advanceMillis(5_000L)
    val free = reader().value
    free.at shouldBe now.plusSeconds(65)
    db.markerReads shouldBe 2
  }

  it should "say nothing until the database has been read once" taggedAs (UnitTest) in {
    val clock = new TuiFixtures.TestClock(now)
    val db = new Database
    db.busy = true
    new StorageReader(db.sources, clock)() shouldBe None
  }

  it should "take the cache's hit rate between two readings, and none where nothing was looked up or counted" taggedAs (
    UnitTest
  ) in {
    val clock = new TuiFixtures.TestClock(now)
    val db = new Database
    val reader = new StorageReader(db.sources, clock)
    reader().value.cache shouldBe CacheReading.Off
    db.cache = RocksDbCacheHits.On(hit = 100, miss = 100, indexFilterHit = 0, indexFilterMiss = 0)
    clock.advanceMillis(5_000L)
    reader().value.cache shouldBe CacheReading.On(100, 100, None)
    db.cache = RocksDbCacheHits.On(hit = 190, miss = 110, indexFilterHit = 0, indexFilterMiss = 0)
    clock.advanceMillis(5_000L)
    reader().value.cache shouldBe CacheReading.On(190, 110, Some(0.9))
    clock.advanceMillis(5_000L)
    reader().value.cache shouldBe CacheReading.On(190, 110, None)
  }

  "The system reader's rates" should "be taken over the last two readings when the dashboard polls slower than the window" taggedAs (
    UnitTest
  ) in {
    val clock = new TuiFixtures.TestClock(now)
    val machine = new Machine
    val reader = new SystemReader(machine.sources, clock)
    reader()
    clock.advanceMillis(30_000L)
    machine.traffic = P2pTraffic.Snapshot(60_000, 30_000, 300, 150)
    val second = reader().value
    second.traffic.value.bytesInPerSecond.value shouldBe 2_000.0 +- 0.001
    second.traffic.value.messagesOutPerSecond.value shouldBe 5.0 +- 0.001
    clock.advanceMillis(30_000L)
    reader().value.traffic.value.bytesInPerSecond.value shouldBe 0.0
  }

  it should "carry the collector's total in each point, so a share can be taken between any two" taggedAs (UnitTest) in {
    val clock = new TuiFixtures.TestClock(now)
    val machine = new Machine
    val reader = new SystemReader(machine.sources, clock)
    machine.gcMillis = 100
    reader().value.history.last.gcTotalMillis shouldBe Some(100L)
    clock.advanceMillis(5_000L)
    machine.gcMillis = 350
    reader().value.history.last.gcTotalMillis shouldBe Some(350L)
  }

  "The storage reader's history" should "keep ten minutes of the size, and say the growth once the readings span a minute" taggedAs (UnitTest) in {
    val clock = new TuiFixtures.TestClock(now)
    val db = new Database
    val reader = new StorageReader(db.sources, clock)
    reader().value.growthPerHour shouldBe None
    for _ <- 1 to 12 do
      clock.advanceMillis(5_000L)
      db.size += 1_000
      reader()
    val minute = reader().value
    minute.sizeHistory should have size 13
    // 12,000 bytes in a minute.
    minute.growthPerHour.value shouldBe 720_000.0 +- 0.001
    for _ <- 1 to 200 do
      clock.advanceMillis(5_000L)
      reader()
    reader().value.sizeHistory should have size StorageReader.Points.toLong
    // Compaction shrank it: the growth is less than nothing, and is said.
    db.size = 1_000
    clock.advanceMillis(5_000L)
    reader().value.growthPerHour.value should be < 0.0
  }

  it should "say the audit did not answer when asking it throws, and lose nothing else" taggedAs (UnitTest) in {
    val clock = new TuiFixtures.TestClock(now)
    val db = new Database
    val sample =
      new StorageReader(db.sources.copy(audit = () => throw new IllegalStateException("gone")), clock)().value
    sample.audit shouldBe AuditReading.NoAnswer
    sample.sstBytes shouldBe Some(10_000L)
  }
