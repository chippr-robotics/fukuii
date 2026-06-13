package com.chipprbots.ethereum.db.storage

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

import scala.collection.mutable

import com.chipprbots.ethereum.db.dataSource.{DataSource, DataSourceUpdateOptimized, RocksDbDataSource}
import com.chipprbots.ethereum.db.dataSource.DataSource.Namespace

/** One decoded entry from the BFS level queue. */
final case class BfsEntry(hash: Array[Byte], pathset: Seq[Array[Byte]], isStorage: Boolean)

/** Streaming BFS level queue — replaces the `ArrayBuffer nextLevel` that OOM-crashes at L7.
  *
  * Entries are keyed by a monotonically-increasing 8-byte big-endian counter so all L(N) entries precede all L(N+1)
  * entries in sort order. `iterateRange(from, to)` fetches only the target window via bulk `multiGetOptimized`;
  * `clear()` at run-start and run-end keeps disk bounded.
  *
  * Two implementations: `RocksDbBfsQueueStorage` (production, CF-backed) and `InMemoryBfsQueueStorage` (tests / default
  * when no DataSource is wired).
  */
trait BfsQueueStorage {

  /** Write `entries` to the queue, assigning sequential keys. Thread-safe via AtomicLong. */
  def enqueueBatch(entries: Seq[(Array[Byte], Seq[Array[Byte]], Boolean)]): Unit

  /** Lazy iterator over chunks of `[from, to)`. Each chunk is fetched with one bulk read. Callers process one chunk at
    * a time — memory is O(chunkSize), not O(to - from).
    */
  def iterateRange(from: Long, to: Long, chunkSize: Int = BfsQueueStorage.DefaultChunkSize): Iterator[Seq[BfsEntry]]

  /** Batch-delete all entries with keys in `[from, to)`. */
  def deleteRange(from: Long, to: Long): Unit

  /** Next key to be assigned (= total entries written since last `clear()`). */
  def counter: Long

  /** Delete all entries and reset the counter to 0. */
  def clear(): Unit
}

object BfsQueueStorage {

  val DefaultChunkSize: Int = 50_000

  def longToBytes(l: Long): Array[Byte] = {
    val buf = ByteBuffer.allocate(8)
    buf.putLong(l)
    buf.array()
  }

  /** Encodes a queue entry to a compact byte representation: `[32 B hash][1 B pathset count][{2 B len, N B data}...][1
    * B isStorage]`
    */
  def encodeEntry(hash: Array[Byte], pathset: Seq[Array[Byte]], isStorage: Boolean): Array[Byte] = {
    val payloadSize = 32 + 1 + pathset.map(p => 2 + p.length).sum + 1
    val buf = ByteBuffer.allocate(payloadSize)
    val hashLen = math.min(hash.length, 32)
    buf.put(hash, 0, hashLen)
    if (hashLen < 32) buf.position(32) // zero-pad if truncated (should not happen)
    buf.put(pathset.length.toByte)
    pathset.foreach { p =>
      buf.putShort(p.length.toShort)
      buf.put(p)
    }
    buf.put(if (isStorage) 1.toByte else 0.toByte)
    buf.array()
  }

  def decodeEntry(bytes: Array[Byte]): BfsEntry = {
    val buf = ByteBuffer.wrap(bytes)
    val hash = new Array[Byte](32)
    buf.get(hash)
    val n = buf.get() & 0xff
    val pathset = (0 until n).map { _ =>
      val len = buf.getShort() & 0xffff
      val p = new Array[Byte](len)
      buf.get(p)
      p
    }
    val isStorage = buf.get() != 0
    BfsEntry(hash, pathset, isStorage)
  }
}

/** RocksDB column-family-backed implementation. The CF (`Namespaces.BfsQueueNamespace`) is auto-opened at DB start like
  * all other namespaces. Memory during BFS = O(chunk_size) ≈ 4 MB.
  */
class RocksDbBfsQueueStorage(dataSource: DataSource, namespace: Namespace) extends BfsQueueStorage {
  import BfsQueueStorage._

  private val writeCounter = new AtomicLong(0L)

  def counter: Long = writeCounter.get()

  def enqueueBatch(entries: Seq[(Array[Byte], Seq[Array[Byte]], Boolean)]): Unit = {
    if (entries.isEmpty) return
    val upserts = entries.map { case (hash, pathset, isStorage) =>
      val key = longToBytes(writeCounter.getAndIncrement())
      val value = encodeEntry(hash, pathset, isStorage)
      (key, value)
    }
    dataSource.update(Seq(DataSourceUpdateOptimized(namespace, toRemove = Seq.empty, toUpsert = upserts)))
  }

  def iterateRange(from: Long, to: Long, chunkSize: Int = DefaultChunkSize): Iterator[Seq[BfsEntry]] =
    dataSource match {
      case rdb: RocksDbDataSource =>
        // Forward iterator scan: O(1) seek + sequential block reads. Keys are dense big-endian
        // longs in sorted SST files — the ideal case for a range scan vs batch point-lookups.
        // Concurrent enqueueBatch writes land at keys ≥ `to`; the [from, to) range is read-only.
        rdb.iterateSyncRange(namespace, longToBytes(from), longToBytes(to))
          .map(decodeEntry)
          .grouped(chunkSize)
      case _ =>
        // Fallback for InMemoryBfsQueueStorage tests: retain multiGetOptimized behaviour.
        val rangeTo = to
        new Iterator[Seq[BfsEntry]] {
          private var pos: Long = from
          def hasNext: Boolean = pos < rangeTo
          def next(): Seq[BfsEntry] = {
            val end = math.min(rangeTo, pos + chunkSize)
            val keys = (pos until end).map(longToBytes)
            val values = dataSource.multiGetOptimized(namespace, keys)
            pos = end
            values.flatten.map(decodeEntry)
          }
        }
    }

  def deleteRange(from: Long, to: Long): Unit =
    // Single native range tombstone — O(1) regardless of (to - from). The previous
    // implementation expanded the range into 10K-key point-delete batches; clearing the
    // ~140M-entry queue after a full ETC-mainnet walk wrote ~140M tombstones over ~30 minutes
    // at full CPU (observed live 2026-06-12) while the next walk waited.
    if (from < to) dataSource.deleteRange(namespace, longToBytes(from), longToBytes(to))

  def clear(): Unit = {
    // Tombstone the ENTIRE keyspace, not just [0, counter): the counter is in-memory only, so
    // after a crash-restart it reads 0 while the column family still holds the dead walk's
    // entries. The old `if (counter > 0)` guard skipped deletion entirely in that state,
    // leaving the garbage on disk forever. Long.MaxValue exceeds any key ever assigned.
    dataSource.deleteRange(namespace, longToBytes(0L), longToBytes(Long.MaxValue))
    writeCounter.set(0L)
  }
}

/** In-memory implementation for tests and the default (no DataSource wired). */
class InMemoryBfsQueueStorage extends BfsQueueStorage {
  import BfsQueueStorage._

  private val data = mutable.LongMap[Array[Byte]]()
  private val writeCounter = new AtomicLong(0L)

  def counter: Long = writeCounter.get()

  def enqueueBatch(entries: Seq[(Array[Byte], Seq[Array[Byte]], Boolean)]): Unit =
    entries.foreach { case (hash, pathset, isStorage) =>
      val key = writeCounter.getAndIncrement()
      data(key) = encodeEntry(hash, pathset, isStorage)
    }

  def iterateRange(from: Long, to: Long, chunkSize: Int = DefaultChunkSize): Iterator[Seq[BfsEntry]] = {
    val rangeFrom = from
    val rangeTo = to
    new Iterator[Seq[BfsEntry]] {
      private var pos: Long = rangeFrom
      def hasNext: Boolean = pos < rangeTo
      def next(): Seq[BfsEntry] = {
        val end = math.min(rangeTo, pos + chunkSize)
        val entries = (pos until end).flatMap(k => data.get(k).map(decodeEntry))
        pos = end
        entries
      }
    }
  }

  def deleteRange(from: Long, to: Long): Unit =
    (from until to).foreach(data.remove)

  def clear(): Unit = {
    data.clear()
    writeCounter.set(0L)
  }
}
