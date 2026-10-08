package com.chipprbots.ethereum.db.dataSource

import java.io.File
import java.nio.file.Files

import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigValueFactory

import scala.collection.immutable.ArraySeq

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.db.dataSource.DataSource.Key
import com.chipprbots.ethereum.db.dataSource.DataSource.Value
import com.chipprbots.ethereum.db.storage.Namespaces
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.InstanceConfig

/** spec 015: one bounded RocksDB memory budget and the memory gauges that report it.
  *
  *   - [[RocksDbMemoryBudget.resolve]]: the budget formula documented in base/db.conf.
  *   - [[RocksDbDataSource.memoryStats]]: reports the configured capacity and memtable budget, sees memtable growth,
  *     and is `None` once closed (never touches freed native handles).
  *   - Partitioned index/filters: a DB written with whole-file index/filters stays readable after the switch, and back.
  *   - [[RocksDbCacheMetrics.MemorySampler]]: re-reads at most once per interval and never throws.
  */
class RocksDbMemoryBudgetSpec extends AnyFlatSpec with Matchers:

  private val MiB = 1024L * 1024
  private val Ns: DataSource.Namespace = Namespaces.NodeNamespace

  private def config(
      dbPath: String = "/unused",
      cache: Long = 32 * MiB,
      writeBuffers: Long = 16 * MiB,
      budget: Option[Long] = None,
      partitioned: Boolean = false
  ): RocksDbConfig =
    new RocksDbConfig:
      override val createIfMissing: Boolean = true
      override val paranoidChecks: Boolean = true
      override val path: String = dbPath
      override val maxThreads: Int = 1
      override val maxOpenFiles: Int = 32
      override val verifyChecksums: Boolean = true
      override val levelCompaction: Boolean = true
      override val blockSize: Long = 16384
      override val blockCacheSize: Long = cache
      override val dbWriteBufferSize: Long = writeBuffers
      override def memoryBudget: Option[Long] = budget
      override def partitionIndexAndFilters: Boolean = partitioned

  private def key(i: Int): Key = ArraySeq.unsafeWrapArray(Array.fill(32)(i.toByte) ++ BigInt(i).toByteArray)
  private def value(i: Int): Value = ArraySeq.unsafeWrapArray(Array.fill(256)((i % 251).toByte))
  private val N = 2000

  private def deleteRecursively(f: File): Unit =
    Option(f.listFiles()).foreach(_.foreach(deleteRecursively))
    val _ = f.delete()

  private def withTempDir(test: String => Unit): Unit =
    val dbPath = Files.createTempDirectory("rocksdb-budget-test").toAbsolutePath.toString
    try test(dbPath)
    finally deleteRecursively(new File(dbPath))

  private def sstFiles(dbPath: String): Set[String] =
    Option(new File(dbPath).listFiles()).toList.flatten.map(_.getName).filter(_.endsWith(".sst")).toSet

  private def shippedRocksDbConfig(overrides: (String, AnyRef)*): RocksDbConfig =
    val base = ConfigFactory.load().getConfig("fukuii")
    val cfg = overrides.foldLeft(base) { case (c, (k, v)) => c.withValue(k, ConfigValueFactory.fromAnyRef(v)) }
    new InstanceConfig(cfg, "rocksdb-budget-test").Db.RocksDb

  "The shipped db.conf" should "keep the write stall and partitioned index/filters opt-in (default off)" taggedAs (
    UnitTest,
    DatabaseTest
  ) in {
    val rocks = shippedRocksDbConfig()
    rocks.writeBufferAllowStall shouldBe false
    rocks.partitionIndexAndFilters shouldBe false
    rocks.memoryBudget shouldBe None
    rocks.metadataBlockSize shouldBe 4096L
    val budget = RocksDbMemoryBudget.resolve(rocks)
    budget.cacheCapacity shouldBe 1024 * MiB
    budget.memTableBudget shouldBe 512 * MiB
  }

  it should "parse the opt-in overrides" taggedAs (UnitTest, DatabaseTest) in {
    val rocks = shippedRocksDbConfig(
      "db.rocksdb.write-buffer-allow-stall" -> java.lang.Boolean.TRUE,
      "db.rocksdb.partition-index-and-filters" -> java.lang.Boolean.TRUE,
      "db.rocksdb.memory-budget" -> java.lang.Long.valueOf(768 * MiB)
    )
    rocks.writeBufferAllowStall shouldBe true
    rocks.partitionIndexAndFilters shouldBe true
    rocks.memoryBudget shouldBe Some(768 * MiB)
  }

  "RocksDbMemoryBudget.resolve" should "default to block-cache-size + db-write-buffer-size" taggedAs (
    UnitTest,
    DatabaseTest
  ) in {
    val b = RocksDbMemoryBudget.resolve(config(cache = 512 * MiB, writeBuffers = 512 * MiB))
    b.cacheCapacity shouldBe 1024 * MiB
    b.memTableBudget shouldBe 512 * MiB
    b.minBlockBytes shouldBe 512 * MiB
  }

  it should "use memory-budget as the capacity and cap memtables at half of it" taggedAs (UnitTest, DatabaseTest) in {
    val roomy = RocksDbMemoryBudget.resolve(config(writeBuffers = 512 * MiB, budget = Some(2048 * MiB)))
    roomy.cacheCapacity shouldBe 2048 * MiB
    roomy.memTableBudget shouldBe 512 * MiB

    val tight = RocksDbMemoryBudget.resolve(config(writeBuffers = 512 * MiB, budget = Some(256 * MiB)))
    tight.cacheCapacity shouldBe 256 * MiB
    tight.memTableBudget shouldBe 128 * MiB
    tight.minBlockBytes shouldBe 128 * MiB
  }

  it should "never resolve to a zero-sized cache or memtable budget" taggedAs (UnitTest, DatabaseTest) in {
    val zero = RocksDbMemoryBudget.resolve(config(cache = 0L, writeBuffers = 0L))
    zero.memTableBudget shouldBe MiB
    zero.cacheCapacity shouldBe 2 * MiB

    val zeroBudget = RocksDbMemoryBudget.resolve(config(budget = Some(0L)))
    zeroBudget.cacheCapacity shouldBe 2 * MiB
    zeroBudget.memTableBudget shouldBe MiB
  }

  "RocksDbDataSource.memoryStats" should "report the budget, see memtable growth, and be None after close" taggedAs (
    UnitTest,
    DatabaseTest
  ) in withTempDir { dbPath =>
    val ds = RocksDbDataSource(config(dbPath), Namespaces.nsSeq)
    try
      val before = ds.memoryStats.get
      before.blockCacheCapacity shouldBe 48 * MiB
      before.memTableBudget shouldBe 16 * MiB

      ds.update(Seq(DataSourceUpdate(Ns, Nil, (0 until N).map(i => key(i) -> value(i)))))
      val after = ds.memoryStats.get
      after.memTables should be > before.memTables
      // The WriteBufferManager charges memtable memory to the shared cache.
      after.blockCacheUsage should be > 0L
      after.tableReaders should be >= 0L

      ds.close()
      ds.memoryStats shouldBe None
    finally ds.destroy()
  }

  "Partitioned index/filters" should "read SST files written with and without partitioning in both directions" taggedAs (
    UnitTest,
    DatabaseTest
  ) in withTempDir { dbPath =>
    // close() does NOT flush WAL-backed memtables; the data would be replayed from the WAL on the next open and
    // written in THAT open's table format. Each phase therefore flushes explicitly and checks that SST files exist,
    // so the next phase really reads files written in the previous phase's format.

    // 1) Whole-file index/filters (the format every existing node has on disk).
    val legacy = RocksDbDataSource(config(dbPath, partitioned = false), Namespaces.nsSeq)
    try
      legacy.update(Seq(DataSourceUpdate(Ns, Nil, (0 until N).map(i => key(i) -> value(i)))))
      legacy.flushAll()
    finally legacy.close()
    val legacySsts = sstFiles(dbPath)
    legacySsts should not be empty

    // 2) Reopen partitioned: legacy SSTs are read as-is; the new writes land in a partitioned SST.
    val partitioned = RocksDbDataSource(config(dbPath, partitioned = true), Namespaces.nsSeq)
    try
      (0 until N).foreach(i => partitioned.get(Ns, key(i)) shouldBe Some(value(i)))
      partitioned.update(Seq(DataSourceUpdate(Ns, Nil, (N until 2 * N).map(i => key(i) -> value(i)))))
      partitioned.flushAll()
    finally partitioned.close()
    val partitionedSsts = sstFiles(dbPath) -- legacySsts
    partitionedSsts should not be empty

    // 3) Reopen with partitioning off again (a rollback): both kinds of SST stay readable.
    val rolledBack = RocksDbDataSource(config(dbPath, partitioned = false), Namespaces.nsSeq)
    try
      (0 until 2 * N).foreach(i => rolledBack.get(Ns, key(i)) shouldBe Some(value(i)))
      rolledBack.get(Ns, key(-1)) shouldBe None
    finally rolledBack.destroy()
  }

  "RocksDbCacheMetrics.MemorySampler" should "re-read at most once per interval" taggedAs (UnitTest) in {
    var clock = 0L
    var reads = 0
    def read(): Option[RocksDbMemoryStats] =
      reads += 1
      Some(RocksDbMemoryStats(reads.toLong, 0L, 0L, 0L, 0L, 0L))
    val sampler = new RocksDbCacheMetrics.MemorySampler(() => read(), intervalMs = 30000L, now = () => clock)
    sampler.current.blockCacheUsage shouldBe 1L
    clock = 29999L
    sampler.current.blockCacheUsage shouldBe 1L
    reads shouldBe 1
    clock = 30000L
    sampler.current.blockCacheUsage shouldBe 2L
    reads shouldBe 2
  }

  it should "keep the last good sample when the DataSource is closed or the read fails" taggedAs (UnitTest) in {
    var clock = 0L
    var result: () => Option[RocksDbMemoryStats] = () => Some(RocksDbMemoryStats(7L, 0L, 0L, 0L, 0L, 0L))
    val sampler = new RocksDbCacheMetrics.MemorySampler(() => result(), intervalMs = 10L, now = () => clock)
    sampler.current.blockCacheUsage shouldBe 7L

    result = () => None
    clock = 10L
    sampler.current.blockCacheUsage shouldBe 7L

    result = () => throw new IllegalStateException("closed")
    clock = 20L
    noException should be thrownBy sampler.current
    sampler.current.blockCacheUsage shouldBe 7L
  }
