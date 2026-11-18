package com.chipprbots.ethereum.db.dataSource

import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files

import scala.collection.immutable.ArraySeq
import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.*

import org.rocksdb.RocksDB
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.db.dataSource.DataSource.Key
import com.chipprbots.ethereum.db.dataSource.DataSource.Value
import com.chipprbots.ethereum.db.dataSource.RocksDbDataSource.RocksDbDataSourceClosedException
import com.chipprbots.ethereum.db.storage.Namespaces
import com.chipprbots.ethereum.testing.Tags.*

/** spec 002 US2 (T016): RocksDB block-cache statistics wiring on [[RocksDbDataSource]].
  *
  *   - With `enable-statistics = true`, `cacheStats` is `Some` and the block-cache tickers advance once reads are
  *     served from SST files through the block cache.
  *   - With the flag off (the default), `cacheStats` is `None`.
  *   - `close()` releases the Statistics handle without error.
  *
  * To exercise the block cache deterministically (rather than memtable reads, which bypass it), the test writes data
  * with one datasource, closes it — which flushes the memtable to SST on shutdown — then reopens a fresh datasource
  * over the same path so every read is served from SST through the block cache.
  */
class RocksDbDataSourceSpec extends AnyFlatSpec with Matchers:

  private val Ns: DataSource.Namespace = Namespaces.NodeNamespace

  private def config(dbPath: String, statisticsEnabled: Boolean): RocksDbConfig =
    new RocksDbConfig:
      override val createIfMissing: Boolean = true
      override val paranoidChecks: Boolean = true
      override val path: String = dbPath
      override val maxThreads: Int = 1
      override val maxOpenFiles: Int = 32
      override val verifyChecksums: Boolean = true
      override val levelCompaction: Boolean = true
      override val blockSize: Long = 16384
      override val blockCacheSize: Long = 33554432
      override val enableStatistics: Boolean = statisticsEnabled

  private def key(i: Int): Key = ArraySeq.unsafeWrapArray(Array.fill(32)(i.toByte))
  // 256-byte values so that 2000 entries (~512KB) exceed the 16KB block size and span many data blocks.
  private def value(i: Int): Value = ArraySeq.unsafeWrapArray(Array.fill(256)((i % 251).toByte))

  private val N = 2000

  private def cacheActivity(stats: (Long, Long, Long, Long)): Long =
    val (hit, miss, idxHit, idxMiss) = stats
    hit + miss + idxHit + idxMiss

  private def withTempDir(test: String => Unit): Unit =
    val dbPath = Files.createTempDirectory("rocksdb-stats-test").toAbsolutePath.toString
    try test(dbPath)
    finally
      val dir = new File(dbPath)
      !dir.exists() || dir.delete()

  "RocksDbDataSource with statistics enabled" should "advance the block-cache tickers on SST-served reads" taggedAs (
    UnitTest,
    DatabaseTest
  ) in withTempDir { dbPath =>
    // 1) Write the dataset, then close — closing flushes the memtable to SST files.
    val writer = RocksDbDataSource(config(dbPath, statisticsEnabled = true), Namespaces.nsSeq)
    writer.update(Seq(DataSourceUpdate(Ns, Nil, (0 until N).map(i => key(i) -> value(i)))))
    writer.close()

    // 2) Reopen fresh: every read is now served from SST through the block cache.
    val reader = RocksDbDataSource(config(dbPath, statisticsEnabled = true), Namespaces.nsSeq)
    try
      reader.cacheStats should not be empty
      val before = reader.cacheStats.get

      (0 until N).foreach(i => reader.get(Ns, key(i)) should not be empty) // cold: misses
      (0 until N).foreach(i => reader.get(Ns, key(i))) // warm: hits on cached blocks

      val after = reader.cacheStats.get
      val (hit, miss, _, _) = after
      // Total ticker activity strictly increased, and BOTH a hit and a miss were observed.
      cacheActivity(after) should be > cacheActivity(before)
      miss should be > 0L
      hit should be > 0L
    finally reader.destroy()
  }

  "RocksDbDataSource with statistics disabled (default)" should "report cacheStats == None" taggedAs (
    UnitTest,
    DatabaseTest
  ) in withTempDir { dbPath =>
    val ds = RocksDbDataSource(config(dbPath, statisticsEnabled = false), Namespaces.nsSeq)
    try
      ds.cacheStats shouldBe None
      ds.update(Seq(DataSourceUpdate(Ns, Nil, Seq(key(1) -> value(1)))))
      ds.get(Ns, key(1)) should not be empty
      ds.cacheStats shouldBe None
    finally ds.destroy()
  }

  it should "close() cleanly with statistics enabled (handle released, no error)" taggedAs (
    UnitTest,
    DatabaseTest
  ) in withTempDir { dbPath =>
    val ds = RocksDbDataSource(config(dbPath, statisticsEnabled = true), Namespaces.nsSeq)
    ds.cacheStats should not be empty
    noException should be thrownBy ds.close()
    // After close the statistics handle is released; cacheStats reads None rather than touching a freed handle.
    ds.cacheStats shouldBe None
  }

  // spec 022 T053: a long walk reads each node once, and its reads must not push import's working set out of the cache.
  "RocksDbDataSource.multiGetOptimizedNoFill" should "answer as multiGetOptimized does, and leave the block cache cold" taggedAs (
    UnitTest,
    DatabaseTest
  ) in withTempDir { dbPath =>
    val writer = RocksDbDataSource(config(dbPath, statisticsEnabled = true), Namespaces.nsSeq)
    writer.update(Seq(DataSourceUpdate(Ns, Nil, (0 until N).map(i => key(i) -> value(i)))))
    writer.close()

    val reader = RocksDbDataSource(config(dbPath, statisticsEnabled = true), Namespaces.nsSeq)
    def misses: Long = reader.cacheStats.get._2
    val keys = (0 until N).map(i => key(i).toArray) :+ (Array.fill[Byte](31)(1) :+ 2.toByte)
    try
      val noFill = reader.multiGetOptimizedNoFill(Ns, keys)
      noFill.last shouldBe None

      val beforeSecondPass = misses
      reader.multiGetOptimizedNoFill(Ns, keys)
      withClue("nothing was kept, so a second pass misses again: ")(misses should be > beforeSecondPass)

      // The control: the filling read answers the same, and its second pass misses nothing.
      noFill.map(_.map(_.toSeq)) shouldBe reader.multiGetOptimized(Ns, keys).map(_.map(_.toSeq))
      val filled = misses
      reader.multiGetOptimized(Ns, keys)
      misses shouldBe filled
    finally reader.destroy()
  }

  // ---------------------------------------------------------------------------------------
  // issue #1355 — iterateSyncRange: self-contained refill batches, locked + closed per batch.
  // The fix shipped in 92fc8f6b5 / v0.8.0 (e16855453); these are the two regression tests the
  // issue asked for and that never got written.
  // ---------------------------------------------------------------------------------------

  // Mirrors the private `refillBatchSize` constant in RocksDbDataSource.iterateSyncRange.
  private val RefillBatchSize = 4096

  private def intKey(i: Int): Array[Byte] =
    val buf = ByteBuffer.allocate(4)
    buf.putInt(i)
    buf.array()

  private def keyToInt(bytes: Array[Byte]): Int =
    ByteBuffer.wrap(bytes).getInt()

  // spec 022 review (vault): the bound is the override's, not the trait's filter over a full scan.
  "RocksDbDataSource.scanRangeLimit" should "stop at the limit, in key order, inside [from, to)" taggedAs (
    UnitTest,
    DatabaseTest
  ) in {
    val dir = Files.createTempDirectory("rocks-scan-limit").toAbsolutePath.toString
    val ds = RocksDbDataSource(config(dir, statisticsEnabled = false), Namespaces.nsSeq)
    try
      ds.update(Seq(DataSourceUpdateOptimized(Ns, Nil, (1 to 9).map(i => Array[Byte](1, i.toByte) -> Array(i.toByte)))))
      ds.update(
        Seq(
          DataSourceUpdateOptimized(
            Ns,
            Nil,
            Seq(Array[Byte](0, 5) -> Array[Byte](0), Array[Byte](2, 1) -> Array[Byte](0))
          )
        )
      )
      def scan(from: Array[Byte], to: Array[Byte], limit: Int): Seq[List[Byte]] =
        ds.scanRangeLimit(Ns, from, to, limit).map(_._1.toList).toSeq
      scan(Array[Byte](1), Array[Byte](2), 3) shouldBe Seq(List[Byte](1, 1), List[Byte](1, 2), List[Byte](1, 3))
      withClue("from is inclusive, to exclusive: ") {
        scan(Array[Byte](1, 3), Array[Byte](1, 5), 10) shouldBe Seq(List[Byte](1, 3), List[Byte](1, 4))
      }
      withClue("a limit past the range ends at the range: ") {
        scan(Array[Byte](1), Array[Byte](2), 100) should have size 9
      }
      withClue("an empty range, and a zero limit: ") {
        scan(Array[Byte](1, 20), Array[Byte](2), 5) shouldBe empty
        scan(Array[Byte](1), Array[Byte](2), 0) shouldBe empty
      }
    finally ds.destroy()
  }

  "RocksDbDataSource.iterateSyncRange" should "yield exactly the keys in [fromKey, toKeyExcl) in order, once each, across multiple refill batches, and handle an inclusive fromKey and empty ranges" taggedAs (
    UnitTest,
    DatabaseTest
  ) in withTempDir { dbPath =>
    val ds = RocksDbDataSource(config(dbPath, statisticsEnabled = false), Namespaces.nsSeq)
    try
      // A contiguous span of ints as 4-byte big-endian keys, value == key (so the returned bytes
      // decode straight back to the int, no separate lookup table needed). Covers:
      //   [0, 1000)     -- outside the query range, before fromKey
      //   [1000, 6000)  -- inside [fromKey, toKeyExcl) -- 5000 keys, > RefillBatchSize(4096),
      //                    forcing the iterator to re-seek past lastKey into a second batch
      //   6000          -- exactly toKeyExcl -- present in the DB, must be excluded
      //   [6001, 6500)  -- outside the query range, after toKeyExcl
      val upserts = (0 until 6500).map(i => intKey(i) -> intKey(i))
      ds.update(Seq(DataSourceUpdateOptimized(Ns, Nil, upserts)))

      val fromKey = intKey(1000)
      val toKeyExcl = intKey(6000)
      val results = ds.iterateSyncRange(Ns, fromKey, toKeyExcl).toList

      // Content + order + "once each" in one shot: a single list-equality check against the
      // expected contiguous sequence catches duplicates, gaps, and misordering alike.
      results.map(keyToInt) shouldBe (1000 until 6000).toList
      results.length should be > RefillBatchSize // confirms more than one refill batch actually ran

      // toKeyExcl really is present in the DB -- exclusion is the range contract at work, not a
      // coincidence of the key never having existed.
      ds.get(Ns, ArraySeq.unsafeWrapArray(toKeyExcl)) should not be empty
      results.map(keyToInt) should not contain 6000
      results.map(keyToInt) should not contain 999

      // Empty range: fromKey == toKeyExcl, on a key that DOES exist elsewhere in the DB.
      ds.iterateSyncRange(Ns, intKey(2000), intKey(2000)).toList shouldBe empty

      // Empty range: a window where no key was ever written.
      ds.iterateSyncRange(Ns, intKey(50000), intKey(50010)).toList shouldBe empty

      // A range whose first key is fromKey itself: the lower bound is inclusive.
      ds.iterateSyncRange(Ns, intKey(1000), intKey(1003)).toList.map(keyToInt) shouldBe List(1000, 1001, 1002)
    finally ds.destroy()
  }

  it should "recover cleanly from close() landing mid-iteration between next() calls, and never block or leak when an iterator is abandoned mid-scan" taggedAs (
    UnitTest,
    DatabaseTest
  ) in {
    // > 2 * RefillBatchSize: still mid-scan (batch 1 of 3, not yet exhausted) after only the
    // first refill, so closing there is a genuine partial-drain, not an edge-of-range fluke.
    val total = 10000
    val upserts = (0 until total).map(i => intKey(i) -> intKey(i))

    // --- close() landing between next() calls, mid first batch -----------------------------
    withTempDir { dbPath =>
      val ds = RocksDbDataSource(config(dbPath, statisticsEnabled = false), Namespaces.nsSeq)
      try
        ds.update(Seq(DataSourceUpdateOptimized(Ns, Nil, upserts)))
        val scan = ds.iterateSyncRange(Ns, intKey(0), intKey(total))

        // Consume part of the first refill batch. By the time next() returns here, refill()'s
        // native RocksIterator and dbLock.readLock() have already been closed/released in its
        // `finally` -- nothing native or locked survives past this call.
        val firstTen = (0 until 10).map(_ => scan.next())
        firstTen.map(keyToInt) shouldBe (0 until 10).toList

        // close() from the SAME thread, between next() calls -- exactly the interleaving the
        // batch design guarantees is safe.
        noException should be thrownBy ds.close()

        // The rest of the already-buffered first batch (indices 10..4095) lives in the
        // Scala-heap buffer, not behind the now-closed native DB, so it must still drain.
        val restOfFirstBatch = (10 until RefillBatchSize).map(_ => scan.next())
        restOfFirstBatch.map(keyToInt) shouldBe (10 until RefillBatchSize).toList

        // Buffer is now empty and the range was NOT exhausted (total > RefillBatchSize), so the
        // next call must trigger a refill() -- which calls assureNotClosed() BEFORE touching the
        // freed native db/handles, failing cleanly instead of touching freed native memory.
        a[RocksDbDataSourceClosedException] should be thrownBy scan.next()
        // Deterministic and repeatable, not a one-shot fluke -- no garbage leaks through either.
        a[RocksDbDataSourceClosedException] should be thrownBy scan.hasNext
      finally ds.destroy()
    }

    // --- abandoning an iterator mid-scan leaks nothing and does not block close() -----------
    withTempDir { dbPath =>
      val ds = RocksDbDataSource(config(dbPath, statisticsEnabled = false), Namespaces.nsSeq)
      ds.update(Seq(DataSourceUpdateOptimized(Ns, Nil, upserts)))
      val abandoned = ds.iterateSyncRange(Ns, intKey(0), intKey(total))
      abandoned.next() // triggers exactly one refill(); its native iterator + read lock are
      // released in `finally` before this call returns -- then we walk away.

      // close() must return promptly. This bounds worst-case wall-clock rather than racing
      // anything: the pass path is fast and deterministic every time; only a genuine
      // lock/resource leak would time out, and we want that to fail loudly here rather than
      // hang this shared, resource-constrained test JVM indefinitely.
      val closed = Future(ds.close())(using ExecutionContext.global)
      Await.result(closed, 10.seconds)

      // Prove no native handle / OS-level lock survives: a fresh DataSource can reopen the same
      // path immediately and read back data written before the close (a leaked RocksDB LOCK
      // file would make `apply`/open throw instead).
      val reopened = RocksDbDataSource(config(dbPath, statisticsEnabled = false), Namespaces.nsSeq)
      try reopened.get(Ns, ArraySeq.unsafeWrapArray(intKey(0))) should not be empty
      finally reopened.destroy()
    }
  }

  // ---- Spec 029 FR-043 (T083): what the dashboard's Storage view reads -----------------------------------------------
  // vault: the read lock is tried in the form that does not queue, so the read never waits, and holds the lock for a
  // bounded number of in-memory property reads.

  private def filled(dbPath: String, statisticsEnabled: Boolean = false): RocksDbDataSource =
    val ds = RocksDbDataSource(config(dbPath, statisticsEnabled), Namespaces.nsSeq)
    ds.update(Seq(DataSourceUpdate(Ns, Nil, (0 until N).map(i => key(i) -> value(i)))))
    ds.flushAll()
    ds

  /** `body` on a thread of its own, and its result: a call that blocked fails here instead of hanging the suite. */
  private def onAnotherThread[A](body: => A): A =
    Await.result(Future(body)(using ExecutionContext.global), 30.seconds)

  private def lockIsFree(): Unit =
    RocksDbDataSource.dbLock.getReadLockCount shouldBe 0
    RocksDbDataSource.dbLock.isWriteLocked shouldBe false

  "RocksDbDataSource.properties" should "give the size on disk, each column family's, and the compaction and stall state" taggedAs (
    UnitTest,
    DatabaseTest
  ) in withTempDir { dbPath =>
    val ds = filled(dbPath)
    try
      val p = ds.properties().get
      // Every column family, the default one too.
      p.stores.map(
        _.namespace
      ) should contain theSameElementsAs (RocksDB.DEFAULT_COLUMN_FAMILY.toIndexedSeq +: Namespaces.nsSeq)
      val written = p.stores.find(_.namespace == Ns).get
      written.sstBytes.get should be > 1_000L // 2,000 repetitive values, compressed on disk
      // A family nothing was written to holds nothing, which is a figure and not an absence.
      all(p.stores.filterNot(_.namespace == Ns).map(_.sstBytes)) shouldBe Some(0L)
      // One measure for the whole and for each family, so the families' sizes sum to the database's.
      p.sstBytes.get shouldBe p.stores.flatMap(_.sstBytes).sum
      // Database-wide figures are read once, not once a column family: nothing is compacting, nothing is stopped.
      p.runningCompactions shouldBe Some(0L)
      p.writeStopped shouldBe Some(false)
      p.delayedWriteRate.get should be >= 0L
      p.compactionPending.get should be <= p.stores.size.toLong
      p.pendingCompactionBytes.get should be >= 0L
      p.blockCacheCapacity should be > 0L
      p.memTables shouldBe defined
      p.tableReaders shouldBe defined
      // The estimates are read only when asked for.
      (p.liveDataBytes, written.keys) shouldBe ((None, None))
      val estimated = ds.properties(withEstimates = true).get
      estimated.liveDataBytes.get should be > 0L
      // The keys are a byte repeated, so the 2,000 writes are of 256 distinct keys: RocksDB estimates those.
      estimated.stores.find(_.namespace == Ns).get.keys.get shouldBe 256L +- 26L
      all(estimated.stores.map(_.keys.isDefined)) shouldBe true
    finally ds.close()
    lockIsFree()
  }

  it should "say the cache statistics are off, or give them, a count of nothing included" taggedAs (
    UnitTest,
    DatabaseTest
  ) in withTempDir { dbPath =>
    val off = filled(dbPath)
    try off.properties().get.cache shouldBe RocksDbCacheHits.Off
    finally off.close()
    val on = RocksDbDataSource(config(dbPath, statisticsEnabled = true), Namespaces.nsSeq)
    try
      // On, and nothing read through the cache yet: counts, not an absence.
      on.properties().get.cache shouldBe a[RocksDbCacheHits.On]
      (0 until N).foreach(i => on.get(Ns, key(i)))
      on.properties().get.cache match
        case RocksDbCacheHits.On(hit, miss, indexFilterHit, indexFilterMiss) =>
          (hit + miss + indexFilterHit + indexFilterMiss) should be > 0L
        case other => fail(s"statistics are on, but the cache read $other")
    finally on.close()
  }

  it should "be nothing once the database is closed, with no error" taggedAs (UnitTest, DatabaseTest) in withTempDir {
    dbPath =>
      val ds = filled(dbPath)
      ds.properties() should not be empty
      ds.close()
      noException should be thrownBy ds.properties()
      ds.properties() shouldBe None
      ds.properties(withEstimates = true) shouldBe None
      lockIsFree()
  }

  it should "return nothing at once while a write holds the lock, and wait for nobody" taggedAs (
    UnitTest,
    DatabaseTest
  ) in withTempDir { dbPath =>
    val ds = filled(dbPath)
    try
      // A write in progress holds the lock for writing. The read is made on another thread: this one, holding the
      // write lock, could take the read lock too.
      RocksDbDataSource.dbLock.writeLock().lock()
      val during =
        try onAnotherThread(ds.properties())
        finally RocksDbDataSource.dbLock.writeLock().unlock()
      during shouldBe None
      // The write over, the next read answers.
      onAnotherThread(ds.properties()) should not be empty
    finally ds.close()
    lockIsFree()
  }

  it should "still answer while a writer waits behind a long read, and hold that writer up no longer than it runs" taggedAs (
    UnitTest,
    DatabaseTest
  ) in withTempDir { dbPath =>
    val ds = filled(dbPath)
    try
      // A long read holds the lock for reading, as a compaction does (B-029).
      RocksDbDataSource.dbLock.readLock().lock()
      val writer =
        try
          val writing = Future(ds.update(Seq(DataSourceUpdate(Ns, Nil, Seq(key(250) -> value(7))))))(using
            ExecutionContext.global
          )
          // The writer is queued behind the read: from here a reader that queues would wait for it.
          val deadline = System.nanoTime() + 30.seconds.toNanos
          while !RocksDbDataSource.dbLock.hasQueuedThreads && System.nanoTime() < deadline do Thread.onSpinWait()
          RocksDbDataSource.dbLock.hasQueuedThreads shouldBe true
          // The properties read does not queue: it answers at once, compaction or not.
          onAnotherThread(ds.properties()) should not be empty
          writing.isCompleted shouldBe false
          writing
        finally RocksDbDataSource.dbLock.readLock().unlock()
      // The long read over, the write goes through: it was held up by that read, not by the properties.
      Await.result(writer, 30.seconds)
      ds.get(Ns, key(250)) shouldBe Some(value(7))
    finally ds.close()
    lockIsFree()
  }

  "RocksDbDataSource.whileFree" should "let a point read through while a writer waits behind a long read" taggedAs (
    UnitTest,
    DatabaseTest
  ) in withTempDir { dbPath =>
    val ds = filled(dbPath)
    try
      ds.update(Seq(DataSourceUpdate(Ns, Nil, Seq(key(251) -> value(9)))))
      // A long read holds the lock for reading, as a compaction does for its whole length.
      RocksDbDataSource.dbLock.readLock().lock()
      val writer =
        try
          val writing = Future(ds.update(Seq(DataSourceUpdate(Ns, Nil, Seq(key(250) -> value(7))))))(using
            ExecutionContext.global
          )
          val deadline = System.nanoTime() + 30.seconds.toNanos
          while !RocksDbDataSource.dbLock.hasQueuedThreads && System.nanoTime() < deadline do Thread.onSpinWait()
          RocksDbDataSource.dbLock.hasQueuedThreads shouldBe true
          // Outside `whileFree` this `get` would queue behind that writer until the long read ends: the dashboard's
          // marker reads would stop its probe for as long as a compaction runs (vault). Inside, the thread already
          // holds the read lock, and takes it again at once.
          onAnotherThread(ds.whileFree((ds.properties().isDefined, ds.get(Ns, key(251))))) shouldBe Some(
            (true, Some(value(9)))
          )
          writing.isCompleted shouldBe false
          writing
        finally RocksDbDataSource.dbLock.readLock().unlock()
      Await.result(writer, 30.seconds)
    finally ds.close()
    lockIsFree()
  }

  it should "run nothing while a write holds the lock, or once the database is closed, and say why with None" taggedAs (
    UnitTest,
    DatabaseTest
  ) in withTempDir { dbPath =>
    val ds = filled(dbPath)
    var ran = 0
    RocksDbDataSource.dbLock.writeLock().lock()
    val during =
      try onAnotherThread(ds.whileFree { ran += 1; 1 })
      finally RocksDbDataSource.dbLock.writeLock().unlock()
    during shouldBe None
    onAnotherThread(ds.whileFree { ran += 1; 2 }) shouldBe Some(2)
    // A read that throws is none, and leaves the lock free.
    ds.whileFree[Int](throw new IllegalStateException("boom")) shouldBe None
    lockIsFree()
    ds.close()
    ds.whileFree { ran += 1; 3 } shouldBe None
    ran shouldBe 1
    lockIsFree()
  }

  "RocksDbDataSource.properties under a close" should "give nothing or the whole of it while another thread closes the database, never an error" taggedAs (
    UnitTest,
    DatabaseTest
  ) in withTempDir { dbPath =>
    val ds = filled(dbPath)
    val reading = Future {
      // Read until the close is seen: every answer is whole, or none.
      var answers = 0
      var last: Option[RocksDbProperties] = ds.properties()
      while last.isDefined do
        last.get.stores should have size (Namespaces.nsSeq.size + 1).toLong
        answers += 1
        last = ds.properties()
      answers
    }(using ExecutionContext.global)
    ds.close()
    noException should be thrownBy Await.result(reading, 30.seconds)
    ds.properties() shouldBe None
    lockIsFree()
  }

  "RocksDbDataSource" should "set the WAL recovery mode explicitly to point-in-time (B-078)" taggedAs (
    UnitTest,
    DatabaseTest
  ) in {
    RocksDbDataSource.WalRecoveryMode shouldBe org.rocksdb.WALRecoveryMode.PointInTimeRecovery
    val dbPath = Files.createTempDirectory("rocksdb-wal-mode-test").toAbsolutePath.toString
    val ds = RocksDbDataSource(config(dbPath, statisticsEnabled = false), Namespaces.nsSeq)
    try ds.walRecoveryMode() shouldBe org.rocksdb.WALRecoveryMode.PointInTimeRecovery
    finally
      ds.close()
      def rm(f: File): Unit =
        Option(f.listFiles()).foreach(_.foreach(rm))
        val _ = f.delete()
      rm(new File(dbPath))
  }
