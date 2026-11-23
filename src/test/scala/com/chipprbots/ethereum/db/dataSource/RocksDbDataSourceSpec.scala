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
    finally com.chipprbots.ethereum.testing.TempDirs.deleteRecursively(new File(dbPath).toPath) // B-305

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

  "RocksDbDataSource.compactRange" should "not hold the database lock while the native compaction runs (B-281)" taggedAs (
    UnitTest,
    DatabaseTest
  ) in withTempDir { dbPath =>
    val ds = filled(dbPath)
    try
      var writtenDuringCompaction = false
      var readDuringCompaction: Option[Value] = None
      // The hook runs where the native call would: the compaction holds its own guard and no database lock.
      ds.beforeNativeCompaction = () =>
        onAnotherThread {
          ds.update(Seq(DataSourceUpdate(Ns, Nil, Seq(key(250) -> value(7)))))
          readDuringCompaction = ds.get(Ns, key(250))
        }
        writtenDuringCompaction = true
      ds.compactRange(Ns, key(0).toArray, key(255).toArray)
      writtenDuringCompaction shouldBe true
      readDuringCompaction shouldBe Some(value(7))
    finally ds.close()
    lockIsFree()
  }

  it should "make close() wait for a running compaction, cancel it, and never free the handle under it (B-281)" taggedAs (
    UnitTest,
    DatabaseTest
  ) in withTempDir { dbPath =>
    val ds = filled(dbPath)
    val inCompaction = new java.util.concurrent.CountDownLatch(1)
    val closeMayEnd = new java.util.concurrent.CountDownLatch(1)
    // Only the first compaction is held. The probes below may start before close() has (a loaded machine runs the
    // closing future late): they must run as plain compactions, not wait on a latch only this thread can open.
    val first = new java.util.concurrent.atomic.AtomicBoolean(true)
    ds.beforeNativeCompaction = () =>
      if first.getAndSet(false) then
        inCompaction.countDown()
        closeMayEnd.await(30, java.util.concurrent.TimeUnit.SECONDS) shouldBe true
    val compacting = Future(ds.compactRange(Ns, key(0).toArray, key(255).toArray))(using ExecutionContext.global)
    inCompaction.await(30, java.util.concurrent.TimeUnit.SECONDS) shouldBe true

    val closing = Future(ds.close())(using ExecutionContext.global)
    // close() has begun (new compactions are refused) but cannot finish: the compaction still holds the guard.
    val deadline = System.nanoTime() + 30.seconds.toNanos
    var refused = false
    while !refused && System.nanoTime() < deadline do
      try ds.compactRange(Ns, key(0).toArray, key(255).toArray)
      catch case _: RocksDbDataSourceClosedException => refused = true
    refused shouldBe true
    closing.isCompleted shouldBe false

    closeMayEnd.countDown()
    // The compaction ends cancelled, as a closed data source, and only then does close() finish.
    an[RocksDbDataSourceClosedException] should be thrownBy Await.result(compacting, 30.seconds)
    Await.result(closing, 30.seconds)
    an[RocksDbDataSourceClosedException] should be thrownBy ds.get(Ns, key(1))
    lockIsFree()
  }

  "RocksDbDataSource.compactAll" should "compact every column family, the one with the most SST bytes first (B-371)" taggedAs (
    UnitTest,
    DatabaseTest
  ) in withTempDir { dbPath =>
    val ds = RocksDbDataSource(config(dbPath, statisticsEnabled = false), Namespaces.nsSeq)
    try
      // Three families of three sizes, the smallest the first in the family list and the largest not the last.
      def put(ns: DataSource.Namespace, n: Int): Unit =
        // Distinct keys, and values that do not compress, so the SST bytes follow the count.
        val random = new java.util.Random(n)
        def distinct(i: Int): Key = ArraySeq.unsafeWrapArray(ByteBuffer.allocate(4).putInt(i).array())
        def noise: Value = ArraySeq.unsafeWrapArray(Array.fill(256)(random.nextInt().toByte))
        ds.update(Seq(DataSourceUpdate(ns, Nil, (0 until n).map(i => distinct(i) -> noise))))
      put(Namespaces.ReceiptsNamespace, 20)
      put(Namespaces.NodeNamespace, 2000)
      put(Namespaces.BodyNamespace, 400)
      ds.flushAll()
      val told = scala.collection.mutable.Buffer.empty[(DataSource.Namespace, Int, Int)]
      ds.compactAll((ns, at, of) => told += ((ns, at, of))) match
        case RocksDbDataSource.CompactAllOutcome.Finished(compacted, notReached, cancelled) =>
          cancelled shouldBe false
          notReached shouldBe empty
          compacted.map(_.namespace).take(3) shouldBe Seq(
            Namespaces.NodeNamespace,
            Namespaces.BodyNamespace,
            Namespaces.ReceiptsNamespace
          )
          // Every family, once, and each told before it started, from 1.
          compacted.size shouldBe ds.familyCount
          Namespaces.nsSeq.toSet.diff(compacted.map(_.namespace).toSet) shouldBe empty
          told.map(_._1) shouldBe compacted.map(_.namespace)
          told.map(_._2) shouldBe (1 to ds.familyCount)
          told.map(_._3).distinct shouldBe Seq(ds.familyCount)
          compacted.head.sstBytesBefore.exists(_ > 0L) shouldBe true
          compacted.head.sstBytesAfter should not be empty
        case other => fail(s"expected a finished compaction, got $other")
      // The data is intact: the last of the 2000 keys is still there.
      ds.get(Ns, ArraySeq.unsafeWrapArray(ByteBuffer.allocate(4).putInt(1999).array())) should not be empty
    finally ds.close()
    lockIsFree()
  }

  it should "put the largest first, and a family of unknown size with the empty ones" taggedAs (UnitTest) in {
    val a = IndexedSeq[Byte]('a'.toByte)
    val b = IndexedSeq[Byte]('b'.toByte)
    val c = IndexedSeq[Byte]('c'.toByte)
    val d = IndexedSeq[Byte]('d'.toByte)
    RocksDbDataSource.largestFirst(Seq(a -> Some(5L), b -> None, c -> Some(50L), d -> Some(0L))).map(_._1) shouldBe
      Seq(c, a, b, d)
  }

  it should "stop between families when close() comes, and say which it did and which it left (B-371)" taggedAs (
    UnitTest,
    DatabaseTest
  ) in withTempDir { dbPath =>
    val ds = filled(dbPath)
    // The callback runs between families, with no guard and no lock held: close() here cancels the rest.
    val outcome = ds.compactAll((_, at, _) => if at == 2 then ds.close())
    outcome match
      case RocksDbDataSource.CompactAllOutcome.Finished(compacted, notReached, cancelled) =>
        cancelled shouldBe true
        compacted.map(_.namespace) shouldBe Seq(Namespaces.NodeNamespace)
        notReached.size shouldBe ds.familyCount - 1
        notReached should not contain Namespaces.NodeNamespace
      case other => fail(s"expected a cancelled compaction, got $other")
    an[RocksDbDataSourceClosedException] should be thrownBy ds.get(Ns, key(1))
    // The guard is let go: a later request is not "already running" (it finds the database closed instead).
    ds.compactAll() should not be RocksDbDataSource.CompactAllOutcome.AlreadyRunning
    lockIsFree()
  }

  it should "answer 'already running' to a second request while one runs, and accept one after (B-371)" taggedAs (
    UnitTest,
    DatabaseTest
  ) in withTempDir { dbPath =>
    val ds = filled(dbPath)
    try
      var second: Option[RocksDbDataSource.CompactAllOutcome] = None
      // Inside the first family's native call the first request is running: this is the second, on the same thread
      // and on another.
      val hook = () =>
        if second.isEmpty then
          second = Some(ds.compactAll())
          onAnotherThread(ds.compactAll()) shouldBe RocksDbDataSource.CompactAllOutcome.AlreadyRunning
      ds.beforeNativeCompaction = hook
      ds.compactAll() shouldBe a[RocksDbDataSource.CompactAllOutcome.Finished]
      second shouldBe Some(RocksDbDataSource.CompactAllOutcome.AlreadyRunning)
      // It ended, so the flag is free.
      ds.beforeNativeCompaction = () => ()
      ds.compactAll() shouldBe a[RocksDbDataSource.CompactAllOutcome.Finished]
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
