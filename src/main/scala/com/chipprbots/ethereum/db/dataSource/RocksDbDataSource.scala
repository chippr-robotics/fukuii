package com.chipprbots.ethereum.db.dataSource

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantReadWriteLock

import cats.effect.IO
import cats.effect.Resource

import scala.collection.immutable.ArraySeq
import scala.collection.mutable
import scala.util.control.NonFatal

import fs2.Stream
import org.rocksdb.*

import com.chipprbots.ethereum.db.dataSource.DataSource.*
import com.chipprbots.ethereum.db.dataSource.RocksDbDataSource.*
import com.chipprbots.ethereum.utils.Logger
import com.chipprbots.ethereum.utils.TryWithResources.withResources

class RocksDbDataSource(
    private var db: RocksDB,
    private val rocksDbConfig: RocksDbConfig,
    private var readOptions: ReadOptions,
    private var dbOptions: DBOptions,
    private var cfOptions: ColumnFamilyOptions,
    private val nameSpaces: Seq[Namespace],
    private var handles: Map[Namespace, ColumnFamilyHandle],
    private var statistics: Option[Statistics],
    private var memory: MemoryResources
) extends DataSource
    with Logger:

  @volatile
  private var isClosed = false

  /** RocksDB block-cache tickers (spec 002 US2 / FR-005), or `None` when `rocksdb.enable-statistics` is off.
    *
    * Read under the DB read lock: `close()` frees the Statistics handle under the write lock, so an unlocked read could
    * touch a freed native object. The lock is only TRIED (see [[withMetricsReadLock]]): `None` when a write holds it.
    *
    * @return
    *   `(blockCacheHit, blockCacheMiss, indexFilterHit, indexFilterMiss)` where the index/filter components sum the
    *   index- and filter-block tickers. The values are cumulative counts since DB open.
    */
  def cacheStats: Option[(Long, Long, Long, Long)] =
    withMetricsReadLock {
      statistics.map { stats =>
        val hit = stats.getTickerCount(TickerType.BLOCK_CACHE_HIT)
        val miss = stats.getTickerCount(TickerType.BLOCK_CACHE_MISS)
        val idxFilterHit =
          stats.getTickerCount(TickerType.BLOCK_CACHE_INDEX_HIT) +
            stats.getTickerCount(TickerType.BLOCK_CACHE_FILTER_HIT)
        val idxFilterMiss =
          stats.getTickerCount(TickerType.BLOCK_CACHE_INDEX_MISS) +
            stats.getTickerCount(TickerType.BLOCK_CACHE_FILTER_MISS)
        (hit, miss, idxFilterHit, idxFilterMiss)
      }
    }

  /** True when a RocksDB `Statistics` object is attached (`rocksdb.enable-statistics = true`). */
  def statisticsEnabled: Boolean = statistics.isDefined

  /** Native memory held by this DB (spec 015), or `None` once the DataSource is closed.
    *
    * Block-cache figures come from the shared `Cache` handle, NOT from `rocksdb.block-cache-usage`: every column family
    * reports the same shared cache, so the aggregated property would count it once per CF. Memtable and table-reader
    * figures are summed across column families with `getAggregatedLongProperty`.
    *
    * Block-cache usage INCLUDES the memtable reservation charged by the WriteBufferManager, so `blockCacheUsage` and
    * `memTables` overlap; do not add them.
    *
    * Taken under the DB read lock, the same fence every read uses, so it never races `close()`. The lock is only TRIED
    * (see [[withMetricsReadLock]]): `None` when a write holds it, and the metrics sampler keeps its last sample.
    */
  def memoryStats: Option[RocksDbMemoryStats] =
    withMetricsReadLock {
      Some(
        RocksDbMemoryStats(
          blockCacheUsage = memory.cache.getUsage,
          blockCachePinnedUsage = memory.cache.getPinnedUsage,
          blockCacheCapacity = memory.budget.cacheCapacity,
          memTables = db.getAggregatedLongProperty("rocksdb.cur-size-all-mem-tables"),
          memTableBudget = memory.budget.memTableBudget,
          tableReaders = db.getAggregatedLongProperty("rocksdb.estimate-table-readers-mem")
        )
      )
    }

  /** Run a metrics read under the DB read lock, waiting at most [[MetricsLockWaitMs]] for it.
    *
    * `doWrite` holds the process-wide lock exclusively for the whole `db.write`, which can last seconds (a write stall,
    * a large batch). A scrape must not queue behind that and pile up scrape threads, so it gives up and returns `None`.
    * Also `None` once closed, so no freed native handle is ever touched.
    */
  private def withMetricsReadLock[A](read: => Option[A]): Option[A] =
    if !dbLock.readLock().tryLock(MetricsLockWaitMs, TimeUnit.MILLISECONDS) then None
    else
      try if isClosed then None else read
      finally dbLock.readLock().unlock()

  /** What the `--tui` dashboard's Storage view shows of this database (spec 029 FR-043): its size on disk, each column
    * family's, whether compaction is wanted or running and how much waits, whether writes are stopped or slowed, the
    * memory it holds, and the block cache's hits where statistics are on.
    *
    * It never waits and holds nobody up ([[whileFree]]). The read lock is TRIED, in the form that does not queue: it is
    * taken at once unless a write holds the lock, and otherwise this returns `None` at once. (The timed form
    * [[withMetricsReadLock]] uses honours the queue, so it fails for as long as one writer waits behind a long read: a
    * compaction, which is when this is most wanted.) The lock is held for a bounded number of in-memory property reads
    * and no I/O, so a writer or `close()` that arrives meanwhile waits that long and no longer. `None` too once the
    * database is closed: no freed native handle is touched, and none is kept past the lock.
    *
    * A property this RocksDB cannot give is `None` for that figure alone, and is logged the first time; it is never a
    * zero.
    *
    * @param withEstimates
    *   also read each family's estimated key count and the estimated live data size: heavier (the live size goes over
    *   the file list under RocksDB's own mutex), so the caller asks for them less often
    */
  def properties(withEstimates: Boolean = false): Option[RocksDbProperties] =
    whileFree {
      def read(name: String)(value: => Long): Option[Long] =
        try Some(value)
        catch
          case NonFatal(e) =>
            if propertyFailures.add(name) then log.warn("RocksDB property {} could not be read: {}", name, e.toString)
            None
      // Summed over the column families; a database-wide property is read once, or it would be counted a family
      // a time.
      def aggregated(name: String): Option[Long] = read(name)(db.getAggregatedLongProperty(name))
      def databaseWide(name: String): Option[Long] = read(name)(db.getLongProperty(name))
      val stores = nameSpaces.map { namespace =>
        val handle = handles.get(namespace)
        def of(name: String): Option[Long] = handle.flatMap(h => read(name)(db.getLongProperty(h, name)))
        RocksDbStoreProperties(
          namespace,
          // The same measure as the whole database's below, so the families' sizes sum to it.
          sstBytes = of("rocksdb.total-sst-files-size"),
          keys = if withEstimates then of("rocksdb.estimate-num-keys") else None
        )
      }
      val cache =
        // The configuration says whether statistics are on: a database with them on and nothing read yet has
        // counts of zero, which is not the same as having none.
        if !rocksDbConfig.enableStatistics then RocksDbCacheHits.Off
        else
          statistics.fold(RocksDbCacheHits.Off: RocksDbCacheHits) { stats =>
            RocksDbCacheHits.On(
              hit = stats.getTickerCount(TickerType.BLOCK_CACHE_HIT),
              miss = stats.getTickerCount(TickerType.BLOCK_CACHE_MISS),
              indexFilterHit = stats.getTickerCount(TickerType.BLOCK_CACHE_INDEX_HIT) +
                stats.getTickerCount(TickerType.BLOCK_CACHE_FILTER_HIT),
              indexFilterMiss = stats.getTickerCount(TickerType.BLOCK_CACHE_INDEX_MISS) +
                stats.getTickerCount(TickerType.BLOCK_CACHE_FILTER_MISS)
            )
          }
      RocksDbProperties(
        sstBytes = aggregated("rocksdb.total-sst-files-size"),
        liveDataBytes = if withEstimates then aggregated("rocksdb.estimate-live-data-size") else None,
        stores = stores,
        compactionPending = aggregated("rocksdb.compaction-pending"),
        pendingCompactionBytes = aggregated("rocksdb.estimate-pending-compaction-bytes"),
        runningCompactions = databaseWide("rocksdb.num-running-compactions"),
        writeStopped = databaseWide("rocksdb.is-write-stopped").map(_ != 0L),
        delayedWriteRate = databaseWide("rocksdb.actual-delayed-write-rate"),
        blockCacheUsage = memory.cache.getUsage,
        blockCacheCapacity = memory.budget.cacheCapacity,
        memTables = aggregated("rocksdb.cur-size-all-mem-tables"),
        tableReaders = aggregated("rocksdb.estimate-table-readers-mem"),
        cache = cache
      )
    }

  /** Runs `read` only if no write holds the database now, and while none can begin: `None`, at once, when a write holds
    * it or it is closed.
    *
    * The read lock is tried in the form that does not queue, and held while `read` runs. A thread that holds the read
    * lock takes it again without waiting, whatever is queued, so reads of this data source made inside `read` (point
    * gets, [[properties]]) do not wait behind a writer that queued meanwhile: outside it, such a read queues behind a
    * writer that itself waits for a long read to end (a compaction holds the read lock for its whole length), which is
    * minutes. `read` must be short: a writer, or `close()`, waits for it. One that throws is logged once and is `None`.
    */
  def whileFree[A](read: => A): Option[A] =
    val lock = dbLock.readLock()
    if !lock.tryLock() then None
    else
      try if isClosed then None else Some(read)
      catch
        case NonFatal(e) =>
          if propertyFailures.add("read") then log.warn("RocksDB could not be read for the dashboard: {}", e.toString)
          None
      finally lock.unlock()

  /** The properties whose read has failed: each is logged once. */
  private val propertyFailures: java.util.Set[String] = java.util.concurrent.ConcurrentHashMap.newKeySet[String]()

  /** Flush every column family's memtables to SST files and wait for it. Test support: `close()` does not flush
    * WAL-backed memtables, so a test that needs data in SST files (in a given table format) must call this first.
    */
  private[dataSource] def flushAll(): Unit =
    import scala.jdk.CollectionConverters.*
    dbLock.readLock().lock()
    try
      assureNotClosed()
      withResources(new FlushOptions().setWaitForFlush(true)) { opts =>
        db.flush(opts, handles.values.toList.asJava)
      }
    finally dbLock.readLock().unlock()

  /** This function obtains the associated value to a key, if there exists one.
    *
    * @param namespace
    *   which will be searched for the key.
    * @param key
    *   the key retrieve the value.
    * @return
    *   the value associated with the passed key.
    */
  override def get(namespace: Namespace, key: Key): Option[Value] =
    dbLock.readLock().lock()
    try
      assureNotClosed()
      val byteArray = db.get(handles(namespace), readOptions, key.toArray)
      Option(ArraySeq.unsafeWrapArray(byteArray))
    catch
      case error: RocksDbDataSourceClosedException =>
        throw error
      case NonFatal(error) =>
        throw RocksDbDataSourceException(
          s"Not found associated value to a namespace: $namespace and a key: $key",
          error
        )
    finally dbLock.readLock().unlock()

  /** This function obtains the associated value to a key, if there exists one. It assumes that caller already properly
    * serialized key. Useful when caller knows some pattern in data to avoid generic serialization.
    *
    * @param key
    *   the key retrieve the value.
    * @return
    *   the value associated with the passed key.
    */
  override def getOptimized(namespace: Namespace, key: Array[Byte]): Option[Array[Byte]] =
    dbLock.readLock().lock()
    try
      assureNotClosed()
      Option(db.get(handles(namespace), readOptions, key))
    catch
      case error: RocksDbDataSourceClosedException =>
        throw error
      case NonFatal(error) =>
        throw RocksDbDataSourceException(s"Not found associated value to a key: $key", error)
    finally dbLock.readLock().unlock()

  override def getOptimizedNoFill(namespace: Namespace, key: Array[Byte]): Option[Array[Byte]] =
    dbLock.readLock().lock()
    try
      assureNotClosed()
      Option(db.get(handles(namespace), scanReadOptions, key))
    catch
      case error: RocksDbDataSourceClosedException =>
        throw error
      case NonFatal(error) =>
        throw RocksDbDataSourceException(s"Not found associated value to a key: $key", error)
    finally dbLock.readLock().unlock()

  /** Batch point-lookup via a single JNI call. Amortises per-call overhead and bloom-filter evaluation across the
    * batch; up to 16 keys per branch node in the healing DFS. Null entries in the result list indicate a cache miss.
    */
  override def multiGetOptimized(namespace: Namespace, keys: Seq[Array[Byte]]): Seq[Option[Array[Byte]]] =
    if keys.isEmpty then Seq.empty
    else
      import scala.jdk.CollectionConverters.*
      dbLock.readLock().lock()
      try
        assureNotClosed()
        val handle = handles(namespace)
        val cfList = java.util.Collections.nCopies(keys.size, handle)
        val keyList = keys.asJava
        db.multiGetAsList(cfList, keyList).asScala.map(Option(_)).toSeq
      catch
        case error: RocksDbDataSourceClosedException => throw error
        case NonFatal(error) =>
          throw RocksDbDataSourceException(s"multiGetOptimized failed for namespace $namespace", error)
      finally dbLock.readLock().unlock()

  override def multiGetOptimizedNoFill(namespace: Namespace, keys: Seq[Array[Byte]]): Seq[Option[Array[Byte]]] =
    if keys.isEmpty then Seq.empty
    else
      import scala.jdk.CollectionConverters.*
      dbLock.readLock().lock()
      try
        assureNotClosed()
        val handle = handles(namespace)
        val cfList = java.util.Collections.nCopies(keys.size, handle)
        db.multiGetAsList(scanReadOptions, cfList, keys.asJava).asScala.map(Option(_)).toSeq
      catch
        case error: RocksDbDataSourceClosedException => throw error
        case NonFatal(error) =>
          throw RocksDbDataSourceException(s"multiGetOptimizedNoFill failed for namespace $namespace", error)
      finally dbLock.readLock().unlock()

  override def update(dataSourceUpdates: Seq[DataUpdate]): Unit =
    doWrite(dataSourceUpdates, sync = false)

  /** Fsync-backed write: flushes the OS write buffer to disk before returning. Used for critical one-time commits (SNAP
    * finalization) to prevent spurious SNAP-RECOVERY on crash. Slightly slower than [[update]] due to the fsync system
    * call; only use where durability is required and frequency is low.
    */
  override def updateSync(dataSourceUpdates: Seq[DataUpdate]): Unit =
    doWrite(dataSourceUpdates, sync = true)

  override def deleteRange(namespace: Namespace, fromKey: Array[Byte], toKeyExclusive: Array[Byte]): Unit =
    dbLock.writeLock().lock()
    try
      assureNotClosed()
      // One native range tombstone for the whole interval — O(1) write, lazily reclaimed by
      // compaction. Never expand a range into per-key deletes here (see DataSource.deleteRange).
      db.deleteRange(handles(namespace), fromKey, toKeyExclusive)
    catch
      case error: RocksDbDataSourceClosedException =>
        throw error
      case NonFatal(error) =>
        throw RocksDbDataSourceException(s"DataSource error while deleting range", error)
    finally dbLock.writeLock().unlock()

  /** Held for reading by every running manual compaction, for writing by `close()`: the native handles outlive any
    * compaction that holds it. It is not `dbLock`: a compaction holds this for its whole length and `dbLock` only to
    * check the database is open and to read the handle, so a writer never queues behind a compaction (B-281). A
    * compaction runs beside reads and writes inside RocksDB (`exclusiveManualCompaction` is off), and two of them may
    * run at once.
    */
  private val compactionGuard = new ReentrantReadWriteLock()

  /** Set at the start of `close()`: from then no compaction starts, and the running ones are cancelled. */
  @volatile private var closing = false

  /** The options of the compactions now running: `close()` sets each one cancelled, which the native compaction polls.
    */
  private val runningCompactions = java.util.concurrent.ConcurrentHashMap.newKeySet[CompactRangeOptions]()

  /** Test seam: runs after the compaction has registered and before the native call, holding the guard only. */
  @volatile private[dataSource] var beforeNativeCompaction: () => Unit = () => ()

  override def compactRange(namespace: Namespace, fromKey: Array[Byte], toKeyExclusive: Array[Byte]): Unit =
    // Before the lock too: with close() queued for the guard's write lock, a new reader would wait for it.
    if closing then throw RocksDbDataSourceClosedException(s"This ${getClass.getSimpleName} is closing")
    compactionGuard.readLock().lock()
    try
      if closing then throw RocksDbDataSourceClosedException(s"This ${getClass.getSimpleName} is closing")
      // dbLock only for the open check and the handles; released before the native call. close() frees them only after
      // it has the guard's write lock, which this thread's read lock holds off.
      val (database, handle) =
        dbLock.readLock().lock()
        try
          assureNotClosed()
          (db, handles(namespace))
        finally dbLock.readLock().unlock()
      val options = new CompactRangeOptions().setExclusiveManualCompaction(false)
      try
        runningCompactions.add(options)
        // close() may have set `closing` and swept the set just before this add: look again, now that it is in.
        if closing then options.setCanceled(true)
        beforeNativeCompaction()
        try database.compactRange(handle, fromKey, toKeyExclusive, options)
        catch
          // A compaction cancelled by close() ends with an Incomplete status: the data source is closing, not faulty.
          case _: RocksDBException if options.canceled() =>
            throw RocksDbDataSourceClosedException(s"This ${getClass.getSimpleName} closed during a compaction")
      finally
        runningCompactions.remove(options)
        options.close()
    catch
      case error: RocksDbDataSourceClosedException =>
        throw error
      case NonFatal(error) =>
        throw RocksDbDataSourceException(s"DataSource error while compacting range", error)
    finally compactionGuard.readLock().unlock()

  /** Forward range scan via a single seek+next over a bounded `[fromKey, toKeyExclusive)` window. Uses
    * `scanReadOptions` (fillCache=false) so a large queue scan does not evict the hot block cache. Drains the window
    * into a buffer and CLOSES the native iterator before returning, so no `RocksIterator` handle outlives the call —
    * abort-safe by construction (the caller can drop the returned Iterator without leaking). The caller passes a
    * bounded chunk, so peak memory is O(chunk), matching the prior `multiGetOptimized` result list.
    */
  override def scanRange(
      namespace: Namespace,
      fromKey: Array[Byte],
      toKeyExclusive: Array[Byte]
  ): Iterator[(Array[Byte], Array[Byte])] =
    scanRangeLimit(namespace, fromKey, toKeyExclusive, Int.MaxValue)

  override def scanRangeLimit(
      namespace: Namespace,
      fromKey: Array[Byte],
      toKeyExclusive: Array[Byte],
      limit: Int
  ): Iterator[(Array[Byte], Array[Byte])] =
    dbLock.readLock().lock()
    try
      assureNotClosed()
      val it = db.newIterator(handles(namespace), scanReadOptions)
      try
        val buf = scala.collection.mutable.ArrayBuffer.empty[(Array[Byte], Array[Byte])]
        it.seek(fromKey)
        while buf.size < limit && it.isValid && java.util.Arrays.compareUnsigned(it.key(), toKeyExclusive) < 0 do
          buf += ((it.key(), it.value()))
          it.next()
        buf.iterator
      finally it.close()
    catch
      case error: RocksDbDataSourceClosedException => throw error
      case NonFatal(error) =>
        throw RocksDbDataSourceException(s"scanRange failed for namespace $namespace", error)
    finally dbLock.readLock().unlock()

  private def doWrite(dataSourceUpdates: Seq[DataUpdate], sync: Boolean): Unit =
    dbLock.writeLock().lock()
    try
      assureNotClosed()
      withResources(new WriteOptions().setSync(sync)) { writeOptions =>
        withResources(new WriteBatch()) { batch =>
          dataSourceUpdates.foreach {
            case DataSourceUpdate(namespace, toRemove, toUpsert) =>
              toRemove.foreach { key =>
                batch.delete(handles(namespace), key.toArray)
              }
              toUpsert.foreach { case (k, v) => batch.put(handles(namespace), k.toArray, v.toArray) }

            case DataSourceUpdateOptimized(namespace, toRemove, toUpsert) =>
              toRemove.foreach { key =>
                batch.delete(handles(namespace), key)
              }
              toUpsert.foreach { case (k, v) => batch.put(handles(namespace), k, v) }
          }
          db.write(writeOptions, batch)
        }
      }
    catch
      case error: RocksDbDataSourceClosedException =>
        throw error
      case NonFatal(error) =>
        throw RocksDbDataSourceException(s"DataSource not updated", error)
    finally dbLock.writeLock().unlock()

  /** ReadOptions for range scans with fillCache=false to avoid polluting the block cache. Mirrors Besu's
    * BonsaiWorldStateKeyValueStorage tailing iterator behaviour for flat DB walks. Regular point reads use the default
    * readOptions with cache enabled.
    */
  private lazy val scanReadOptions: ReadOptions =
    val opts = new ReadOptions()
    opts.setVerifyChecksums(rocksDbConfig.verifyChecksums)
    opts.setFillCache(false)
    opts

  /** Seek-based range scan starting from startKey (inclusive). Uses fillCache=false to avoid evicting hot data from the
    * block cache during large sequential scans (mirrors Besu's streamFromKey pattern).
    *
    * Returns an fs2 Stream of (key, value) pairs in sorted key order. The iterator is resource-managed and closes when
    * the stream completes.
    */
  def seekFrom(
      namespace: Namespace,
      startKey: Array[Byte]
  ): Stream[IO, Either[IterationError, (Array[Byte], Array[Byte])]] =
    val iterResource = Resource.fromAutoCloseable(
      IO(db.newIterator(handles(namespace), scanReadOptions))
    )
    Stream.resource(iterResource).flatMap { it =>
      Stream
        .eval(IO(it.seek(startKey)))
        .flatMap { _ =>
          Stream.repeatEval(for
            isValid <- IO(it.isValid)
            item <- if isValid then IO(Right((it.key(), it.value()))) else IO.raiseError(IterationFinished)
            _ <- IO(it.next())
          yield item)
        }
        .handleErrorWith {
          case IterationFinished => Stream.empty
          case ex                => Stream.emit(Left(IterationError(ex)))
        }
    }

  /** Synchronous forward scan of values in [fromKey, toKeyExcl) within namespace.
    *
    * Uses scanReadOptions (fillCache=false) — the correct read path for dense sequential keys stored in sorted SST
    * files. Avoids per-key bloom filter evaluation and issues sequential block reads rather than the batch of random
    * point lookups that multiGetAsList performs.
    *
    * Locking (issue #1355): the native `RocksIterator` is opened, drained, and closed in self-contained BATCHES, each
    * batch fully bracketed by `dbLock.readLock()` + `assureNotClosed()` — exactly the `scanRange` idiom. Between
    * batches NO native iterator and NO lock are held: the returned `Iterator` buffers one batch and yields it
    * element-by-element, opening the next native iterator (re-seeking past the last key returned) only once the buffer
    * drains. This keeps memory at O(batch) (the full `[from,toExcl)` range is never materialized, so the consumer's
    * `.grouped(chunkSize)` still pulls lazily), while guaranteeing that a concurrent `close()`/`clear()` — which takes
    * `dbLock.writeLock()` and frees the native CF + db handles — can never race a live native iterator. Before #1355
    * this method drove one long-lived native iterator with no lock and no `assureNotClosed()`, so a SIGTERM-driven
    * `close()` mid heal-walk could free native memory under the iterator (use-after-free / SIGSEGV).
    *
    * Abort-safe by construction: because no native iterator survives a batch boundary, abandoning the returned iterator
    * mid-scan (or the consumer throwing) leaks nothing — the open iterator is always closed in the batch `finally`
    * before control returns to the caller. (Previously the native iterator was closed only when `hasNext` returned
    * false on full drain, leaking it on any early abandon.)
    *
    * The returned iterator is NOT thread-safe (single-consumer).
    *
    * Concurrent-write safety: callers must only scan ranges whose upper bound is fixed before scan creation (i.e., no
    * concurrent writer advances keys into [fromKey, toKeyExcl)).
    */
  def iterateSyncRange(
      namespace: Namespace,
      fromKey: Array[Byte],
      toKeyExcl: Array[Byte]
  ): Iterator[Array[Byte]] =
    new Iterator[Array[Byte]]:
      // Bounds per-batch memory independently of total range size, preserving the O(chunk) laziness contract.
      private val refillBatchSize = 4096
      private val buffer = scala.collection.mutable.ArrayDeque.empty[Array[Byte]]
      // Strict lower bound for the NEXT batch: the last KEY yielded so far. `null` until the first batch is drained;
      // the first batch seeks to `fromKey` (inclusive), later batches re-seek past `lastKey` (exclusive).
      private var lastKey: Array[Byte] = null
      private var exhausted = false

      /** Open one native iterator, drain up to `refillBatchSize` entries of `[seekFrom..toKeyExcl)` into `buffer`, then
        * close it. Mirrors `scanRange`: readLock + assureNotClosed for the whole batch, native iterator closed in a
        * `finally`, read lock released in a `finally`. Sets `exhausted` when the batch ends the range.
        */
      private def refill(): Unit =
        dbLock.readLock().lock()
        try
          assureNotClosed()
          val it = db.newIterator(handles(namespace), scanReadOptions)
          try
            if lastKey == null then it.seek(fromKey)
            else
              // Resume strictly after the last key returned. Keys are unique, so seek+skip is exact.
              it.seek(lastKey)
              if it.isValid && java.util.Arrays.equals(it.key(), lastKey) then it.next()
            var taken = 0
            while taken < refillBatchSize && it.isValid && java.util.Arrays.compareUnsigned(it.key(), toKeyExcl) < 0
            do
              lastKey = it.key()
              buffer += it.value()
              it.next()
              taken += 1
            // End of range reached within this batch (either no more valid keys or the next key is >= toKeyExcl).
            if taken < refillBatchSize then exhausted = true
          finally it.close()
        catch
          case error: RocksDbDataSourceClosedException => throw error
          case NonFatal(error) =>
            throw RocksDbDataSourceException(s"iterateSyncRange failed for namespace $namespace", error)
        finally dbLock.readLock().unlock()

      def hasNext: Boolean =
        if buffer.isEmpty && !exhausted then refill()
        buffer.nonEmpty

      def next(): Array[Byte] =
        if !hasNext then throw new NoSuchElementException("iterateSyncRange exhausted")
        buffer.removeHead()

  private def dbIterator: Resource[IO, RocksIterator] =
    Resource.fromAutoCloseable(IO(db.newIterator()))

  private def namespaceIterator(namespace: Namespace): Resource[IO, RocksIterator] =
    Resource.fromAutoCloseable(IO(db.newIterator(handles(namespace))))

  private def moveIterator(it: RocksIterator): Stream[IO, Either[IterationError, (Array[Byte], Array[Byte])]] =
    Stream
      .eval(IO(it.seekToFirst()))
      .flatMap { _ =>
        Stream.repeatEval(for
          isValid <- IO(it.isValid)
          item <- if isValid then IO(Right((it.key(), it.value()))) else IO.raiseError(IterationFinished)
          _ <- IO(it.next())
        yield item)
      }
      .handleErrorWith {
        case IterationFinished => Stream.empty
        case ex                => Stream.emit(Left(IterationError(ex)))
      }

  def iterate(): Stream[IO, Either[IterationError, (Array[Byte], Array[Byte])]] =
    Stream.resource(dbIterator).flatMap(it => moveIterator(it))

  def iterate(namespace: Namespace): Stream[IO, Either[IterationError, (Array[Byte], Array[Byte])]] =
    Stream.resource(namespaceIterator(namespace)).flatMap(it => moveIterator(it))

  /** This function is used only for tests. This function updates the DataSource by deleting all the (key-value) pairs
    * in it.
    */
  override def clear(): Unit =
    // Test support: the handles are swapped here without the write lock, so nothing may read this data source (the
    // dashboard's `properties` included) while a test clears it.
    destroy()
    log.debug(s"About to create new DataSource for path: ${rocksDbConfig.path}")
    val opened = createDB(rocksDbConfig, nameSpaces.tail)

    assert(nameSpaces.size == opened.handles.size)

    this.db = opened.db
    this.readOptions = opened.readOptions
    this.handles = nameSpaces.zip(opened.handles.toList).toMap
    this.dbOptions = opened.dbOptions
    this.cfOptions = opened.cfOptions
    this.statistics = opened.statistics
    this.memory = opened.memory
    this.isClosed = false

  def approximateKeyCount(namespace: Namespace): Long =
    handles
      .get(namespace)
      .flatMap { handle =>
        scala.util.Try(db.getLongProperty(handle, "rocksdb.estimate-num-keys")).toOption
      }
      .getOrElse(0L)

  /** This function closes the DataSource, without deleting the files used by it.
    *
    * No in-memory LRU cache invalidation is performed here — by design. The overlay caches (`LruCache`, `MapCache`)
    * live in `db/storage/` (e.g. `CachedNodeStorage`, `CachedReferenceCountedStateStorage`) and are owned by
    * `DefaultStorages`, one abstraction tier above `RocksDbDataSource`. This class has no reference to those caches:
    * adding `cache.invalidateAll()` here would invert the layering (DataSource knowing about the storage layer above
    * it). Cache invalidation on teardown is the responsibility of the component that owns both the cache and the
    * DataSource — specifically `DefaultStorages` or any test fixture that calls `dataSource.clear()` while holding a
    * `CachedNodeStorage` over the same source. See M4 note in `storage-rocksdb.md` for the full rationale.
    */
  override def close(): Unit =
    log.info(s"About to close DataSource in path: ${rocksDbConfig.path}")
    // Cancel the running compactions and wait for them to leave the native call (B-281): the guard's write lock comes
    // first, then dbLock's, as in compactRange, which never takes them the other way round. A compaction runs to its
    // next cancellation check, which RocksDB makes between its input blocks.
    closing = true
    runningCompactions.forEach(_.setCanceled(true))
    compactionGuard.writeLock().lock()
    dbLock.writeLock().lock()
    try
      assureNotClosed()
      isClosed = true
      // There is specific order for closing rocksdb with column families descibed in
      // https://github.com/facebook/rocksdb/wiki/RocksJava-Basics#opening-a-database-with-column-families
      try
        // 1. Free all column families handles
        handles.values.foreach(_.close())
        // 2. Free db
        db.close()
      finally
        // Runs even when db.close() throws, so the options, statistics and memory-budget objects never leak.
        // 3. Free db options and column families options
        readOptions.close()
        dbOptions.close()
        cfOptions.close()
        // 4. Free the Statistics handle (spec 002 US2), if statistics were enabled.
        statistics.foreach(_.close())
        statistics = None
        // 5. Free the memory-budget objects (spec 015): the WriteBufferManager first (it holds a reservation handle into
        //    the cache), then the table-config filter, then the shared cache.
        memory.close()
      log.info(s"DataSource closed successfully in the path: ${rocksDbConfig.path}")
    catch
      case error: RocksDbDataSourceClosedException =>
        throw error
      case NonFatal(error) =>
        throw RocksDbDataSourceException(s"Not closed the DataSource properly", error)
    finally
      dbLock.writeLock().unlock()
      compactionGuard.writeLock().unlock()

  /** This function is used only for tests. This function closes the DataSource, if it is not yet closed, and deletes
    * all the files used by it.
    */
  override def destroy(): Unit =
    try
      if !isClosed then close()
    finally destroyDB()

  protected def destroyDB(): Unit =
    try
      import rocksDbConfig.*
      // destroyDB only needs the options to locate and delete files; a tiny cache is enough. Every native object
      // created here is closed before returning (the previous version leaked a full-size ClockCache per call).
      withResources(new LRUCache(1L * 1024 * 1024)) { cache =>
        withResources(new BloomFilter(10, false)) { filter =>
          val tableCfg = new BlockBasedTableConfig()
            .setBlockSize(blockSize)
            .setBlockCache(cache)
            .setCacheIndexAndFilterBlocks(true)
            .setPinL0FilterAndIndexBlocksInCache(true)
            .setFilterPolicy(filter)

          withResources(
            new Options()
              .setCreateIfMissing(createIfMissing)
              .setParanoidChecks(paranoidChecks)
              .setCompressionType(CompressionType.LZ4_COMPRESSION)
              .setBottommostCompressionType(CompressionType.ZSTD_COMPRESSION)
              .setLevelCompactionDynamicLevelBytes(levelCompaction)
              .setMaxOpenFiles(maxOpenFiles)
              .setIncreaseParallelism(maxThreads)
              .setTableFormatConfig(tableCfg)
          ) { options =>
            log.debug(s"About to destroy DataSource in path: $path")
            RocksDB.destroyDB(path, options)
          }
        }
      }
    catch
      case NonFatal(error) =>
        throw RocksDbDataSourceException(s"Not destroyed the DataSource properly", error)

  private def assureNotClosed(): Unit =
    if isClosed then throw RocksDbDataSourceClosedException(s"This ${getClass.getSimpleName} has been closed")

trait RocksDbConfig:
  val createIfMissing: Boolean
  val paranoidChecks: Boolean
  val path: String
  val maxThreads: Int
  val maxOpenFiles: Int
  val verifyChecksums: Boolean
  val levelCompaction: Boolean
  val blockSize: Long
  // Bytes of the shared cache kept for blocks (data, index, filter) when memtables are at their budget. spec 015.
  val blockCacheSize: Long
  // Memtable budget (bytes) summed across ALL column families. spec 015: enforced by a WriteBufferManager that charges
  // memtable memory to the shared block cache and stalls writes when the budget is exceeded. Concrete with a default
  // so existing implementors (tests, alternate configs) need no change; the production config overrides it via
  // InstanceConfig.
  val dbWriteBufferSize: Long = 512L * 1024 * 1024
  // spec 015: total RocksDB cache budget in bytes (blocks + memtables). `None` = blockCacheSize + dbWriteBufferSize.
  // A `def` (not a `val`) so the default reads the subclass's vals after they are initialised.
  def memoryBudget: Option[Long] = None
  // spec 015: stall writes while memtables exceed their budget (hard cap). Opt-in: doWrite holds the process-wide
  // dbLock exclusively around db.write, so a stall freezes ALL reads (Engine API included) until a flush completes.
  // Off (default): the WriteBufferManager still triggers flushes at its budget; memtables can overshoot briefly.
  def writeBufferAllowStall: Boolean = false
  // spec 015: opt-in two-level (partitioned) index + partitioned filters for newly written SST files, so index/filter
  // memory is cached in metadata-block-size pieces instead of whole per-file blocks.
  def partitionIndexAndFilters: Boolean = false
  // spec 015: size of one index/filter partition.
  def metadataBlockSize: Long = 4096
  // Ceiling (bytes) on total live WAL across column families.
  val maxTotalWalSize: Long = 512L * 1024 * 1024
  // When true, attach a RocksDB `Statistics` object (StatsLevel.EXCEPT_DETAILED_TIMERS) to the DB so
  // block-cache hit/miss tickers become observable (spec 002 US2 / FR-005). Defaulted to false so all
  // existing implementors (tests, alternate configs) compile unchanged; statistics add ~1-2% read overhead
  // and are only worth enabling to diagnose a slow heal walk.
  val enableStatistics: Boolean = false

/** One column family as the dashboard's Storage view shows it (spec 029 FR-043).
  *
  * @param sstBytes
  *   the bytes of its SST files, obsolete ones not yet deleted among them, as the whole database's are counted; `None`
  *   where RocksDB could not say
  * @param keys
  *   RocksDB's estimate of its keys, read only when estimates were asked for; an estimate, and wrong after a range
  *   delete
  */
final case class RocksDbStoreProperties(namespace: Namespace, sstBytes: Option[Long], keys: Option[Long])

/** The block cache's hits and misses since the database was opened, where statistics are on. */
sealed trait RocksDbCacheHits
object RocksDbCacheHits:

  /** `db.rocksdb.enable-statistics` is off: nothing is counted. */
  case object Off extends RocksDbCacheHits

  /** Counted: a count of zero is a count. */
  final case class On(hit: Long, miss: Long, indexFilterHit: Long, indexFilterMiss: Long) extends RocksDbCacheHits

/** The database as the dashboard's Storage view shows it (spec 029 FR-043): see [[RocksDbDataSource.properties]]. A
  * figure RocksDB could not give is `None`.
  *
  * @param sstBytes
  *   every SST file still held, obsolete ones not yet deleted among them: the database's size on disk, without its
  *   write-ahead log
  * @param liveDataBytes
  *   RocksDB's estimate of the live data, read only when estimates were asked for
  * @param compactionPending
  *   how many column families want a compaction
  * @param pendingCompactionBytes
  *   the bytes compaction has to rewrite to catch up
  * @param writeStopped
  *   RocksDB is refusing writes until compaction catches up
  * @param delayedWriteRate
  *   bytes a second RocksDB is holding writes to; 0 when it is not slowing them
  */
final case class RocksDbProperties(
    sstBytes: Option[Long],
    liveDataBytes: Option[Long],
    stores: Seq[RocksDbStoreProperties],
    compactionPending: Option[Long],
    pendingCompactionBytes: Option[Long],
    runningCompactions: Option[Long],
    writeStopped: Option[Boolean],
    delayedWriteRate: Option[Long],
    blockCacheUsage: Long,
    blockCacheCapacity: Long,
    memTables: Option[Long],
    tableReaders: Option[Long],
    cache: RocksDbCacheHits
)

/** Native memory held by one RocksDB instance (spec 015). All values are bytes.
  *
  * `blockCacheUsage` includes the memtable reservation charged by the WriteBufferManager, so it overlaps `memTables`.
  */
final case class RocksDbMemoryStats(
    blockCacheUsage: Long,
    blockCachePinnedUsage: Long,
    blockCacheCapacity: Long,
    memTables: Long,
    memTableBudget: Long,
    tableReaders: Long
)

/** The resolved RocksDB memory budget (spec 015).
  *
  * @param cacheCapacity
  *   capacity of the one LRU cache shared by every column family. Holds data, index and filter blocks AND the memtable
  *   reservation.
  * @param memTableBudget
  *   WriteBufferManager buffer size: the most memtable memory (all column families together) before writes stall.
  */
final case class RocksDbMemoryBudget(cacheCapacity: Long, memTableBudget: Long):
  /** Bytes left for blocks when the memtables use their whole budget. */
  def minBlockBytes: Long = cacheCapacity - memTableBudget

object RocksDbMemoryBudget:
  private val MiB: Long = 1024L * 1024

  /** Resolve the budget from config.
    *
    *   - Without `memory-budget`: capacity = block-cache-size + db-write-buffer-size. Blocks keep at least
    *     block-cache-size, which is what they had before memtables were charged to the cache.
    *   - With `memory-budget`: capacity = memory-budget, and the memtable budget is db-write-buffer-size capped at half
    *     of it, so blocks always keep at least half the cache.
    *
    * Both parts are floored at 1 MiB so a mistyped config cannot produce a zero-capacity cache.
    */
  def resolve(config: RocksDbConfig): RocksDbMemoryBudget =
    config.memoryBudget match
      case Some(total) =>
        val capacity = math.max(total, 2 * MiB)
        RocksDbMemoryBudget(capacity, math.max(MiB, math.min(config.dbWriteBufferSize, capacity / 2)))
      case None =>
        val memTables = math.max(MiB, config.dbWriteBufferSize)
        RocksDbMemoryBudget(math.max(MiB, config.blockCacheSize) + memTables, memTables)

/** The native objects behind the memory budget. They must outlive the DB and are closed after it. */
final class MemoryResources(
    val budget: RocksDbMemoryBudget,
    val cache: Cache,
    val writeBufferManager: WriteBufferManager,
    val filter: Filter
) extends AutoCloseable:
  override def close(): Unit =
    writeBufferManager.close()
    filter.close()
    cache.close()

object RocksDbDataSource extends Logger:
  case object IterationFinished extends RuntimeException
  case class IterationError(ex: Throwable)

  case class RocksDbDataSourceClosedException(message: String) extends IllegalStateException(message)
  case class RocksDbDataSourceException(message: String, cause: Throwable) extends RuntimeException(message, cause)

  // Helper to create exception without cause
  object RocksDbDataSourceException:
    def apply(message: String): RocksDbDataSourceException =
      new RocksDbDataSourceException(message, null)

  // Load RocksDB native library once per JVM
  private lazy val libraryLoaded: Unit =
    try RocksDB.loadLibrary()
    catch
      case NonFatal(error) =>
        throw RocksDbDataSourceException(
          s"Failed to load RocksDB native library. Ensure rocksdbjni is in classpath and native libraries are accessible: ${error.getMessage}",
          error
        )

  /** The rocksdb implementation acquires a lock from the operating system to prevent misuse
    */
  private[dataSource] val dbLock = new ReentrantReadWriteLock()

  /** Longest a metrics read waits for the DB read lock before giving up (spec 015). */
  private[dataSource] val MetricsLockWaitMs: Long = 100L

  /** Close `resources` in reverse order, adding any failure to `primary` as suppressed. Used on open failure. */
  private def closeAllQuietly(resources: Iterable[AutoCloseable], primary: Throwable): Unit =
    resources.toList.reverse.foreach { r =>
      try r.close()
      catch case NonFatal(e) => primary.addSuppressed(e)
    }

  final private case class OpenedDb(
      db: RocksDB,
      handles: mutable.Buffer[ColumnFamilyHandle],
      readOptions: ReadOptions,
      dbOptions: DBOptions,
      cfOptions: ColumnFamilyOptions,
      statistics: Option[Statistics],
      memory: MemoryResources
  )

  /** Build the shared cache, the WriteBufferManager charged to it, and the block-based table config (spec 015).
    *
    * The cache is an LRUCache WITHOUT a strict capacity limit. With a strict limit RocksDB fails the insert when the
    * cache is full of pinned entries, and that failure reaches the caller as a `MemoryLimit` error on a read (and on
    * the memtable reservation). Memtables are bounded by the WriteBufferManager stall instead.
    */
  private def memoryResources(config: RocksDbConfig): (MemoryResources, BlockBasedTableConfig) =
    val budget = RocksDbMemoryBudget.resolve(config)
    // -1 = let RocksDB pick the shard count; 0.5 = up to half the cache is the high-priority pool, where index and
    // filter blocks live (cacheIndexAndFilterBlocksWithHighPriority) so data blocks cannot evict them first.
    val acquired = mutable.ArrayBuffer.empty[AutoCloseable]
    try
      val cache = new LRUCache(budget.cacheCapacity, -1, false, 0.5)
      acquired += cache
      val wbm = new WriteBufferManager(budget.memTableBudget, cache, config.writeBufferAllowStall)
      acquired += wbm
      val filter = new BloomFilter(10, false)
      acquired += filter
      val tableCfg = new BlockBasedTableConfig()
        .setBlockSize(config.blockSize)
        .setBlockCache(cache)
        .setCacheIndexAndFilterBlocks(true)
        .setCacheIndexAndFilterBlocksWithHighPriority(true)
        .setPinL0FilterAndIndexBlocksInCache(true)
        .setFilterPolicy(filter)
      if config.partitionIndexAndFilters then
        // Opt-in. Partitioned index + filters for NEW SST files; existing files stay readable as they are. Each file's
        // top-level index/filter block is pinned (roughly one entry per partition, so it grows with file size and file
        // count; L0 files additionally stay pinned as before). The partitions themselves are cached and evicted like
        // data blocks, so a DB with thousands of SST files does not need all its filters in memory at once.
        // optimizeFiltersForMemory (sizes filters to allocator-friendly lengths, fewer bytes per filter) rides on the
        // same flag so the default table format is exactly what nodes write today.
        val _ = tableCfg
          .setIndexType(IndexType.kTwoLevelIndexSearch)
          .setPartitionFilters(true)
          .setPinTopLevelIndexAndFilter(true)
          .setMetadataBlockSize(config.metadataBlockSize)
          .setOptimizeFiltersForMemory(true)
      (new MemoryResources(budget, cache, wbm, filter), tableCfg)
    catch
      case NonFatal(error) =>
        closeAllQuietly(acquired, error)
        throw error

  // scalastyle:off method.length
  private def createDB(
      rocksDbConfig: RocksDbConfig,
      namespaces: Seq[Namespace]
  ): OpenedDb =
    import rocksDbConfig.*
    import scala.jdk.CollectionConverters.*
    import java.nio.file.{Files, Paths, Path as JPath}

    // Ensure native RocksDB library is loaded (only happens once per JVM)
    libraryLoaded

    // Native objects created so far; closed (in reverse) if opening fails, so a failed open leaks nothing.
    val acquired = mutable.ArrayBuffer.empty[AutoCloseable]
    RocksDbDataSource.dbLock.writeLock().lock()
    try
      // Validate and prepare database path
      val dbPath: JPath = Paths.get(path)
      val pathExists = Files.exists(dbPath)

      log.debug(s"Initializing RocksDB at path: $path (exists: $pathExists, createIfMissing: $createIfMissing)")

      // Validate path before attempting to open database
      if !pathExists && !createIfMissing then
        throw RocksDbDataSourceException(
          s"Database path does not exist and createIfMissing is false: $path"
        )

      // Create directory if needed
      if !pathExists && createIfMissing then
        try
          Files.createDirectories(dbPath)
          log.debug(s"Created database directory: $path")
        catch
          case NonFatal(error) =>
            throw RocksDbDataSourceException(
              s"Failed to create database directory at $path: ${error.getMessage}",
              error
            )

      val readOptions = new ReadOptions().setVerifyChecksums(rocksDbConfig.verifyChecksums)
      acquired += readOptions

      val (memory, tableCfg) = memoryResources(rocksDbConfig)
      acquired += memory
      log.info(
        s"RocksDB memory budget at $path: cache ${memory.budget.cacheCapacity / (1024 * 1024)} MiB shared by all " +
          s"column families, of which memtables <= ${memory.budget.memTableBudget / (1024 * 1024)} MiB " +
          s"(write stall: ${rocksDbConfig.writeBufferAllowStall}); partitioned index/filters: " +
          s"${rocksDbConfig.partitionIndexAndFilters}"
      )

      val options = new DBOptions()
        .setCreateIfMissing(createIfMissing)
        .setParanoidChecks(paranoidChecks)
        .setMaxOpenFiles(maxOpenFiles)
        .setIncreaseParallelism(maxThreads)
        .setCreateMissingColumnFamilies(true)
        // spec 015: one ceiling on the SUM of all column-family memtables. Each of the ~21 column families may hold
        // write_buffer_size x max_write_buffer_number (64 MiB x 2) on its own, ~2.7 GiB with no shared cap. The
        // WriteBufferManager bounds the total, charges it to the shared block cache (so blocks + memtables share one
        // budget) and, with allowStall, blocks writers until a flush frees memory instead of letting it grow.
        // It supersedes db_write_buffer_size, which is therefore not set.
        .setWriteBufferManager(memory.writeBufferManager)
        // Cap total live WAL across CFs; also forces a flush of the laggard CF so the
        // memtable pinning the oldest WAL is released rather than accumulating.
        .setMaxTotalWalSize(maxTotalWalSize)
      acquired += options

      // spec 002 US2 (FR-005): optionally attach a Statistics object so block-cache hit/miss tickers
      // become observable. Off by default (~1-2% read overhead). The handle is returned so close()
      // can release it. EXCEPT_DETAILED_TIMERS keeps the cheaper tickers without the per-op histograms.
      val statistics: Option[Statistics] =
        if rocksDbConfig.enableStatistics then
          val stats = new Statistics()
          stats.setStatsLevel(StatsLevel.EXCEPT_DETAILED_TIMERS)
          acquired += stats
          options.setStatistics(stats)
          Some(stats)
        else None

      val cfOpts =
        new ColumnFamilyOptions()
          .setCompressionType(CompressionType.LZ4_COMPRESSION)
          .setBottommostCompressionType(CompressionType.ZSTD_COMPRESSION)
          .setLevelCompactionDynamicLevelBytes(levelCompaction)
          .setTableFormatConfig(tableCfg)
      acquired += cfOpts

      val cfDescriptors = List(new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, cfOpts)) ++ namespaces.map {
        namespace =>
          new ColumnFamilyDescriptor(namespace.toArray, cfOpts)
      }

      val columnFamilyHandleList = mutable.Buffer.empty[ColumnFamilyHandle]

      log.debug(s"Opening RocksDB with ${cfDescriptors.size} column families at path: $path")

      val db =
        try RocksDB.open(options, path, cfDescriptors.asJava, columnFamilyHandleList.asJava)
        catch
          case error: RocksDBException =>
            throw RocksDbDataSourceException(
              s"RocksDB failed to open database at path: $path - ${error.getMessage}",
              error
            )
          case NonFatal(error) =>
            throw RocksDbDataSourceException(
              s"Unexpected error opening RocksDB at path: $path - ${error.getMessage}",
              error
            )

      log.info(s"Successfully opened RocksDB at path: $path with ${columnFamilyHandleList.size} column family handles")

      OpenedDb(db, columnFamilyHandleList, readOptions, options, cfOpts, statistics, memory)
    catch
      case error: RocksDbDataSourceException =>
        closeAllQuietly(acquired, error)
        // Re-throw our exception without additional logging (caller will log if needed)
        throw error
      case NonFatal(error) =>
        closeAllQuietly(acquired, error)
        val errorMsg = s"Unexpected error creating RocksDB DataSource at path: $path - ${error.getMessage}"
        log.error(errorMsg, error)
        throw RocksDbDataSourceException(errorMsg, error)
    finally RocksDbDataSource.dbLock.writeLock().unlock()

  def apply(rocksDbConfig: RocksDbConfig, namespaces: Seq[Namespace]): RocksDbDataSource =
    val allNameSpaces = Seq(RocksDB.DEFAULT_COLUMN_FAMILY.toIndexedSeq) ++ namespaces
    val opened = createDB(rocksDbConfig, namespaces)
    assert(allNameSpaces.size == opened.handles.size)
    val handlesMap = allNameSpaces.zip(opened.handles.toList).toMap
    // This assert ensures that we do not have duplicated namespaces
    assert(handlesMap.size == opened.handles.size)
    new RocksDbDataSource(
      opened.db,
      rocksDbConfig,
      opened.readOptions,
      opened.dbOptions,
      opened.cfOptions,
      allNameSpaces,
      handlesMap,
      opened.statistics,
      opened.memory
    )
