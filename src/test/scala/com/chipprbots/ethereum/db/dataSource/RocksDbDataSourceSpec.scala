package com.chipprbots.ethereum.db.dataSource

import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files

import scala.collection.immutable.ArraySeq
import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.*

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
      val closed = Future(ds.close())(ExecutionContext.global)
      Await.result(closed, 10.seconds)

      // Prove no native handle / OS-level lock survives: a fresh DataSource can reopen the same
      // path immediately and read back data written before the close (a leaked RocksDB LOCK
      // file would make `apply`/open throw instead).
      val reopened = RocksDbDataSource(config(dbPath, statisticsEnabled = false), Namespaces.nsSeq)
      try reopened.get(Ns, ArraySeq.unsafeWrapArray(intKey(0))) should not be empty
      finally reopened.destroy()
    }
  }
