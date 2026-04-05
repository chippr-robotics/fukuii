package com.chipprbots.ethereum.db.dataSource

import java.util.concurrent.locks.ReentrantReadWriteLock

import cats.effect.IO
import cats.effect.Resource

import scala.collection.immutable.ArraySeq
import scala.collection.mutable
import scala.util.control.NonFatal

import fs2.Stream
import org.rocksdb._

import com.chipprbots.ethereum.db.dataSource.DataSource._
import com.chipprbots.ethereum.db.dataSource.RocksDbDataSource._
import com.chipprbots.ethereum.utils.Logger
import com.chipprbots.ethereum.utils.TryWithResources.withResources

class RocksDbDataSource(
    private var db: RocksDB,
    private val rocksDbConfig: RocksDbConfig,
    private var readOptions: ReadOptions,
    private var dbOptions: DBOptions,
    private var cfOptions: ColumnFamilyOptions,
    private val nameSpaces: Seq[Namespace],
    private var handles: Map[Namespace, ColumnFamilyHandle]
) extends DataSource
    with Logger {

  @volatile
  private var isClosed = false

  /** This function obtains the associated value to a key, if there exists one.
    *
    * @param namespace
    *   which will be searched for the key.
    * @param key
    *   the key retrieve the value.
    * @return
    *   the value associated with the passed key.
    */
  override def get(namespace: Namespace, key: Key): Option[Value] = {
    dbLock.readLock().lock()
    try {
      assureNotClosed()
      val byteArray = db.get(handles(namespace), readOptions, key.toArray)
      Option(ArraySeq.unsafeWrapArray(byteArray))
    } catch {
      case error: RocksDbDataSourceClosedException =>
        throw error
      case NonFatal(error) =>
        throw RocksDbDataSourceException(
          s"Not found associated value to a namespace: $namespace and a key: $key",
          error
        )
    } finally dbLock.readLock().unlock()
  }

  /** This function obtains the associated value to a key, if there exists one. It assumes that caller already properly
    * serialized key. Useful when caller knows some pattern in data to avoid generic serialization.
    *
    * @param key
    *   the key retrieve the value.
    * @return
    *   the value associated with the passed key.
    */
  override def getOptimized(namespace: Namespace, key: Array[Byte]): Option[Array[Byte]] = {
    dbLock.readLock().lock()
    try {
      assureNotClosed()
      Option(db.get(handles(namespace), readOptions, key))
    } catch {
      case error: RocksDbDataSourceClosedException =>
        throw error
      case NonFatal(error) =>
        throw RocksDbDataSourceException(s"Not found associated value to a key: $key", error)
    } finally dbLock.readLock().unlock()
  }

  override def update(dataSourceUpdates: Seq[DataUpdate]): Unit = {
    dbLock.writeLock().lock()
    try {
      assureNotClosed()
      withResources(new WriteOptions()) { writeOptions =>
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
    } catch {
      case error: RocksDbDataSourceClosedException =>
        throw error
      case NonFatal(error) =>
        throw RocksDbDataSourceException(s"DataSource not updated", error)
    } finally dbLock.writeLock().unlock()
  }

  private def dbIterator: Resource[IO, RocksIterator] =
    Resource.fromAutoCloseable(IO(db.newIterator()))

  private def namespaceIterator(namespace: Namespace): Resource[IO, RocksIterator] =
    Resource.fromAutoCloseable(IO(db.newIterator(handles(namespace))))

  private def moveIterator(it: RocksIterator): Stream[IO, Either[IterationError, (Array[Byte], Array[Byte])]] =
    Stream
      .eval(IO(it.seekToFirst()))
      .flatMap { _ =>
        Stream.repeatEval(for {
          isValid <- IO(it.isValid)
          item <- if (isValid) IO(Right((it.key(), it.value()))) else IO.raiseError(IterationFinished)
          _ <- IO(it.next())
        } yield item)
      }
      .handleErrorWith {
        case IterationFinished => Stream.empty
        case ex                => Stream.emit(Left(IterationError(ex)))
      }

  def iterate(): Stream[IO, Either[IterationError, (Array[Byte], Array[Byte])]] =
    Stream.resource(dbIterator).flatMap(it => moveIterator(it))

  def iterate(namespace: Namespace): Stream[IO, Either[IterationError, (Array[Byte], Array[Byte])]] =
    Stream.resource(namespaceIterator(namespace)).flatMap(it => moveIterator(it))

  /** ReadOptions for range scans with fillCache=false to avoid polluting the block cache.
    * Regular point reads continue using the default readOptions with cache enabled.
    */
  private lazy val scanReadOptions: ReadOptions = {
    val opts = new ReadOptions()
    opts.setVerifyChecksums(rocksDbConfig.verifyChecksums)
    opts.setFillCache(false)
    opts
  }

  /** ReadOptions for trie walks: fillCache=false to avoid evicting hot data from the 512MB block
    * cache, and verifyChecksums=false to skip CRC32 per read (the walk is a single-pass scan
    * over 145M single-use nodes — caching is wasteful, checksum is redundant).
    */
  private lazy val trieWalkReadOptions: ReadOptions = {
    new ReadOptions()
      .setVerifyChecksums(false)
      .setFillCache(false)
  }

  override def getMultipleForWalk(namespace: DataSource.Namespace, keys: Seq[Array[Byte]]): Seq[Option[Array[Byte]]] = {
    if (keys.isEmpty) return Seq.empty
    dbLock.readLock().lock()
    try {
      assureNotClosed()
      import scala.jdk.CollectionConverters._
      val handle = handles(namespace)
      val handleList = java.util.Collections.nCopies(keys.size, handle)
      val values = db.multiGetAsList(trieWalkReadOptions, handleList, keys.asJava)
      values.asScala.map(v => Option(v)).toSeq
    } catch {
      case error: RocksDbDataSourceClosedException => throw error
      case NonFatal(_)                              =>
        // Fall back to sequential reads on any multiGet failure
        keys.map(k => Option(db.get(handles(namespace), trieWalkReadOptions, k)))
    } finally dbLock.readLock().unlock()
  }

  override def getOptimizedForWalk(namespace: DataSource.Namespace, key: Array[Byte]): Option[Array[Byte]] = {
    dbLock.readLock().lock()
    try {
      assureNotClosed()
      Option(db.get(handles(namespace), trieWalkReadOptions, key))
    } catch {
      case error: RocksDbDataSourceClosedException =>
        throw error
      case NonFatal(error) =>
        throw RocksDbDataSourceException(s"Not found associated value to a key: $key", error)
    } finally dbLock.readLock().unlock()
  }

  /** Seek-based range iterator starting from the given key within a namespace.
    * Uses fillCache=false to avoid evicting hot trie nodes from the block cache
    * during large range scans (safeguard P-1 from SNAP server plan).
    *
    * Returns an fs2 Stream of (key, value) pairs in sorted key order,
    * starting from the first key >= startKey. The iterator is resource-managed
    * and automatically closed when the stream completes or errors.
    */
  def seekFrom(
      namespace: Namespace,
      startKey: Array[Byte]
  ): Stream[IO, Either[IterationError, (Array[Byte], Array[Byte])]] = {
    val iterResource = Resource.fromAutoCloseable(
      IO(db.newIterator(handles(namespace), scanReadOptions))
    )
    Stream.resource(iterResource).flatMap { it =>
      Stream
        .eval(IO(it.seek(startKey)))
        .flatMap { _ =>
          Stream.repeatEval(for {
            isValid <- IO(it.isValid)
            item <- if (isValid) IO(Right((it.key(), it.value()))) else IO.raiseError(IterationFinished)
            _ <- IO(it.next())
          } yield item)
        }
        .handleErrorWith {
          case IterationFinished => Stream.empty
          case ex                => Stream.emit(Left(IterationError(ex)))
        }
    }
  }

  /** This function is used only for tests. This function updates the DataSource by deleting all the (key-value) pairs
    * in it.
    */
  override def clear(): Unit = {
    destroy()
    log.debug(s"About to create new DataSource for path: ${rocksDbConfig.path}")
    val (newDb, handles, readOptions, dbOptions, cfOptions) = createDB(rocksDbConfig, nameSpaces.tail)

    assert(nameSpaces.size == handles.size)

    this.db = newDb
    this.readOptions = readOptions
    this.handles = nameSpaces.zip(handles.toList).toMap
    this.dbOptions = dbOptions
    this.cfOptions = cfOptions
    this.isClosed = false
  }

  /** Enable write-optimized RocksDB settings for bulk sync (fast sync / SNAP sync).
    * Larger write buffers reduce flush frequency, and relaxed L0 triggers reduce write stalls.
    * Call disableBulkSyncMode() when transitioning to tip-following (regular sync at chain head).
    */
  def enableBulkSyncMode(): Unit = {
    dbLock.readLock().lock()
    try {
      assureNotClosed()
      val bulkOptions = MutableColumnFamilyOptions.builder()
        .setWriteBufferSize(256L * 1024 * 1024) // 256MB (default ~64MB)
        .setMaxWriteBufferNumber(6) // default 3
        .setLevel0SlowdownWritesTrigger(40) // default 20
        .setLevel0StopWritesTrigger(56) // default 36
        .build()
      handles.values.foreach { handle =>
        db.setOptions(handle, bulkOptions.asInstanceOf[MutableColumnFamilyOptions])
      }
      log.info("RocksDB bulk sync mode enabled (larger write buffers, relaxed compaction triggers)")
    } catch {
      case _: RocksDbDataSourceClosedException => // ignore if closed
      case NonFatal(error) =>
        log.warn("Failed to enable bulk sync mode: {}", error.getMessage)
    } finally dbLock.readLock().unlock()
  }

  /** Revert to default RocksDB settings for normal operation (tip-following).
    */
  def disableBulkSyncMode(): Unit = {
    dbLock.readLock().lock()
    try {
      assureNotClosed()
      val normalOptions = MutableColumnFamilyOptions.builder()
        .setWriteBufferSize(64L * 1024 * 1024) // 64MB default
        .setMaxWriteBufferNumber(3)
        .setLevel0SlowdownWritesTrigger(20)
        .setLevel0StopWritesTrigger(36)
        .build()
      handles.values.foreach { handle =>
        db.setOptions(handle, normalOptions.asInstanceOf[MutableColumnFamilyOptions])
      }
      log.info("RocksDB bulk sync mode disabled (default write settings restored)")
    } catch {
      case _: RocksDbDataSourceClosedException => // ignore if closed
      case NonFatal(error) =>
        log.warn("Failed to disable bulk sync mode: {}", error.getMessage)
    } finally dbLock.readLock().unlock()
  }

  /** This function closes the DataSource, without deleting the files used by it.
    */
  override def close(): Unit = {
    log.info(s"About to close DataSource in path: ${rocksDbConfig.path}")
    dbLock.writeLock().lock()
    try {
      assureNotClosed()
      isClosed = true
      // There is specific order for closing rocksdb with column families descibed in
      // https://github.com/facebook/rocksdb/wiki/RocksJava-Basics#opening-a-database-with-column-families
      // 1. Free all column families handles
      handles.values.foreach(_.close())
      // 2. Free db and db options
      db.close()
      readOptions.close()
      dbOptions.close()
      // 3. Free column families options
      cfOptions.close()
      log.info(s"DataSource closed successfully in the path: ${rocksDbConfig.path}")
    } catch {
      case error: RocksDbDataSourceClosedException =>
        throw error
      case NonFatal(error) =>
        throw RocksDbDataSourceException(s"Not closed the DataSource properly", error)
    } finally dbLock.writeLock().unlock()
  }

  /** This function is used only for tests. This function closes the DataSource, if it is not yet closed, and deletes
    * all the files used by it.
    */
  override def destroy(): Unit =
    try
      if (!isClosed) {
        close()
      }
    finally destroyDB()

  protected def destroyDB(): Unit =
    try {
      import rocksDbConfig._
      val tableCfg = new BlockBasedTableConfig()
        .setBlockSize(blockSize)
        .setBlockCache(new LRUCache(blockCacheSize))
        .setCacheIndexAndFilterBlocks(true)
        .setPinL0FilterAndIndexBlocksInCache(true)
        .setFilterPolicy(new BloomFilter(10, false))

      val options = new Options()
        .setCreateIfMissing(createIfMissing)
        .setParanoidChecks(paranoidChecks)
        .setCompressionType(CompressionType.LZ4_COMPRESSION)
        .setBottommostCompressionType(CompressionType.ZSTD_COMPRESSION)
        .setLevelCompactionDynamicLevelBytes(levelCompaction)
        .setMaxOpenFiles(maxOpenFiles)
        .setIncreaseParallelism(maxThreads)
        .setTableFormatConfig(tableCfg)

      log.debug(s"About to destroy DataSource in path: $path")
      RocksDB.destroyDB(path, options)
      options.close()
    } catch {
      case NonFatal(error) =>
        throw RocksDbDataSourceException(s"Not destroyed the DataSource properly", error)
    }

  private def assureNotClosed(): Unit =
    if (isClosed) {
      throw RocksDbDataSourceClosedException(s"This ${getClass.getSimpleName} has been closed")
    }

}

trait RocksDbConfig {
  val createIfMissing: Boolean
  val paranoidChecks: Boolean
  val path: String
  val maxThreads: Int
  val maxOpenFiles: Int
  val verifyChecksums: Boolean
  val levelCompaction: Boolean
  val blockSize: Long
  val blockCacheSize: Long
}

object RocksDbDataSource extends Logger {
  case object IterationFinished extends RuntimeException
  case class IterationError(ex: Throwable)

  case class RocksDbDataSourceClosedException(message: String) extends IllegalStateException(message)
  case class RocksDbDataSourceException(message: String, cause: Throwable) extends RuntimeException(message, cause)

  // Helper to create exception without cause
  object RocksDbDataSourceException {
    def apply(message: String): RocksDbDataSourceException =
      new RocksDbDataSourceException(message, null)
  }

  // Load RocksDB native library once per JVM
  private lazy val libraryLoaded: Unit =
    try
      RocksDB.loadLibrary()
    catch {
      case NonFatal(error) =>
        throw RocksDbDataSourceException(
          s"Failed to load RocksDB native library. Ensure rocksdbjni is in classpath and native libraries are accessible: ${error.getMessage}",
          error
        )
    }

  /** The rocksdb implementation acquires a lock from the operating system to prevent misuse
    */
  private val dbLock = new ReentrantReadWriteLock()

  // scalastyle:off method.length
  private def createDB(
      rocksDbConfig: RocksDbConfig,
      namespaces: Seq[Namespace]
  ): (RocksDB, mutable.Buffer[ColumnFamilyHandle], ReadOptions, DBOptions, ColumnFamilyOptions) = {
    import rocksDbConfig._
    import scala.jdk.CollectionConverters._
    import java.nio.file.{Files, Paths, Path => JPath}

    // Ensure native RocksDB library is loaded (only happens once per JVM)
    libraryLoaded

    RocksDbDataSource.dbLock.writeLock().lock()
    try {
      // Validate and prepare database path
      val dbPath: JPath = Paths.get(path)
      val pathExists = Files.exists(dbPath)

      log.debug(s"Initializing RocksDB at path: $path (exists: $pathExists, createIfMissing: $createIfMissing)")

      // Validate path before attempting to open database
      if (!pathExists && !createIfMissing) {
        throw RocksDbDataSourceException(
          s"Database path does not exist and createIfMissing is false: $path"
        )
      }

      // Create directory if needed
      if (!pathExists && createIfMissing) {
        try {
          Files.createDirectories(dbPath)
          log.debug(s"Created database directory: $path")
        } catch {
          case NonFatal(error) =>
            throw RocksDbDataSourceException(
              s"Failed to create database directory at $path: ${error.getMessage}",
              error
            )
        }
      }

      val readOptions = new ReadOptions().setVerifyChecksums(rocksDbConfig.verifyChecksums)

      // LRUCache replaces deprecated ClockCache (removed in RocksDB 8.x) — eliminates
      // memory fragmentation and over-allocation observed during SNAP sync.
      val cache = new LRUCache(blockCacheSize)
      // WriteBufferManager enforces a shared memory ceiling across block cache + all
      // memtable write buffers across all 14 column families. Without this, each CF's
      // memtables grow independently during write-heavy SNAP sync, causing unbounded
      // RSS growth observed during ETC mainnet sync.
      val writeBufferManager = new WriteBufferManager(blockCacheSize, cache)

      val tableCfg = new BlockBasedTableConfig()
        .setBlockSize(blockSize)
        .setBlockCache(cache)
        .setCacheIndexAndFilterBlocks(true)
        .setPinL0FilterAndIndexBlocksInCache(true)
        .setFilterPolicy(new BloomFilter(10, false))

      val options = new DBOptions()
        .setCreateIfMissing(createIfMissing)
        .setParanoidChecks(paranoidChecks)
        .setMaxOpenFiles(maxOpenFiles)
        .setIncreaseParallelism(maxThreads)
        .setCreateMissingColumnFamilies(true)
        .setWriteBufferManager(writeBufferManager)

      val cfOpts =
        new ColumnFamilyOptions()
          .setCompressionType(CompressionType.LZ4_COMPRESSION)
          .setBottommostCompressionType(CompressionType.ZSTD_COMPRESSION)
          .setLevelCompactionDynamicLevelBytes(levelCompaction)
          .setMaxWriteBufferNumber(2)
          .setTableFormatConfig(tableCfg)

      val cfDescriptors = List(new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, cfOpts)) ++ namespaces.map {
        namespace =>
          new ColumnFamilyDescriptor(namespace.toArray, cfOpts)
      }

      val columnFamilyHandleList = mutable.Buffer.empty[ColumnFamilyHandle]

      log.debug(s"Opening RocksDB with ${cfDescriptors.size} column families at path: $path")

      val db =
        try
          RocksDB.open(options, path, cfDescriptors.asJava, columnFamilyHandleList.asJava)
        catch {
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
        }

      log.info(s"Successfully opened RocksDB at path: $path with ${columnFamilyHandleList.size} column family handles")

      (
        db,
        columnFamilyHandleList,
        readOptions,
        options,
        cfOpts
      )
    } catch {
      case error: RocksDbDataSourceException =>
        // Re-throw our exception without additional logging (caller will log if needed)
        throw error
      case NonFatal(error) =>
        val errorMsg = s"Unexpected error creating RocksDB DataSource at path: $path - ${error.getMessage}"
        log.error(errorMsg, error)
        throw RocksDbDataSourceException(errorMsg, error)
    } finally RocksDbDataSource.dbLock.writeLock().unlock()
  }

  def apply(rocksDbConfig: RocksDbConfig, namespaces: Seq[Namespace]): RocksDbDataSource = {
    val allNameSpaces = Seq(RocksDB.DEFAULT_COLUMN_FAMILY.toIndexedSeq) ++ namespaces
    val (db, handles, readOptions, dbOptions, cfOptions) = createDB(rocksDbConfig, namespaces)
    assert(allNameSpaces.size == handles.size)
    val handlesMap = allNameSpaces.zip(handles.toList).toMap
    // This assert ensures that we do not have duplicated namespaces
    assert(handlesMap.size == handles.size)
    new RocksDbDataSource(db, rocksDbConfig, readOptions, dbOptions, cfOptions, allNameSpaces, handlesMap)
  }
}
