package com.chipprbots.ethereum.db.storage

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.db.dataSource.DataSource
import com.chipprbots.ethereum.db.dataSource.DataSourceBatchUpdate
import com.chipprbots.ethereum.db.dataSource.DataSourceUpdateOptimized

/** SNAP storage-task completion markers: key `"SnapStorageDone/" ++ accountHash ++ storageRoot`, value `0x01`, in the
  * app-state column family.
  *
  * Why the app-state column family and not a new one: RocksDB refuses to open a database unless every column family it
  * has is listed, so a new column family would make a downgrade to an earlier 0.9.x build fail to start. The app-state
  * family is never iterated, and its string keys never start with the binary-suffixed prefix, so the markers cannot be
  * confused with anything else there; `clear` is one range tombstone over exactly the prefix.
  *
  * What a marker guarantees: the previous run downloaded every slot of this account's storage at this storage root
  * (every chunk applied in order, none given up), and ALL of those flat slots are in RocksDB — the marker is written in
  * the same WriteBatch as the account's last flat slots, or in a later one after every batch carrying its slots has
  * committed, so it can never outlive them (the WAL replays as a prefix). Beyond that it depends on the mode:
  *   - `deferredMerkleization = false` (production, sync.conf): additionally, the account's storage trie was built from
  *     those slots, its computed root equalled `storageRoot`, and its nodes were handed to node storage before the
  *     marker was staged. They are on disk under the Path scheme (PathNodeStorage writes through) and under the Hash
  *     scheme with archive/basic pruning. Under Hash + cached ("inmemory") pruning they would sit in an in-memory
  *     cache, so SNAPSyncController does not record markers in that combination.
  *   - `deferredMerkleization = true` (the constructor default): no trie is built during the download and no root is
  *     checked; the marker vouches for the flat slots only. The storage trie nodes come from healing (a resume always
  *     forces the full healing walk), exactly as they would have without a restart.
  * A resume uses the markers to re-queue only the storage tasks that were not finished (see
  * AccountRangeCoordinator.replayCarriedChunk and the accounts-complete recovery stream in SNAPSyncController).
  *
  * Keying on the storage root as well as the account means a marker never matches a task for a different root. Markers
  * are scoped to one SNAP cycle: [[clear]] runs whenever the account phase starts without carried task files and when
  * the storage phase completes, so a later cycle never trusts an earlier cycle's markers.
  *
  * Must share its DataSource with FlatSlotStorage (`DataSourceBatchUpdate.and` requires it).
  */
class SnapStorageDoneStorage(val dataSource: DataSource):
  import SnapStorageDoneStorage.*

  private val namespace: IndexedSeq[Byte] = Namespaces.AppStateNamespace

  /** Batch update writing one marker per `(accountHash, storageRoot)`; to be combined with the flat-slot batch. */
  def markDone(done: Seq[(ByteString, ByteString)]): DataSourceBatchUpdate =
    DataSourceBatchUpdate(
      dataSource,
      Array(DataSourceUpdateOptimized(namespace, Nil, done.map { case (a, r) => key(a, r) -> Marker }))
    )

  def isDone(accountHash: ByteString, storageRoot: ByteString): Boolean =
    dataSource.getOptimized(namespace, key(accountHash, storageRoot)).isDefined

  /** The items whose `(accountHash, storageRoot)` has no marker, in their original order. One multi-get per call. */
  def unfinished[T](items: Seq[T])(accountAndRoot: T => (ByteString, ByteString)): Seq[T] =
    if items.isEmpty then items
    else
      val flags = dataSource.multiGetOptimized(
        namespace,
        items.map { t =>
          val (a, r) = accountAndRoot(t)
          key(a, r)
        }
      )
      items.zip(flags).collect { case (t, None) => t }

  /** Drop every marker (one range tombstone; cheap, safe on an actor thread). */
  def clear(): Unit = dataSource.deleteRange(namespace, LowestKey, AboveHighestKey)

  /** Compact the marker key range so the space a [[clear]] tombstoned (~80 B per marker, ~750 MB for Sepolia's ~9.35M
    * contracts) is reclaimed now rather than by background compaction. BLOCKING — it rewrites the app-state SST files
    * overlapping the prefix (I/O roughly the size of the markers) — so run it off actor threads.
    */
  def compact(): Unit = dataSource.compactRange(namespace, LowestKey, AboveHighestKey)

object SnapStorageDoneStorage:
  private val Marker: Array[Byte] = Array(1.toByte)
  private val Prefix: Array[Byte] = "SnapStorageDone/".getBytes(StorageStringCharset.UTF8Charset)
  // Keys are exactly Prefix + 64 bytes, so [Prefix + 64 x 0x00, Prefix + 65 x 0xff) covers all of them and nothing else.
  private val LowestKey: Array[Byte] = Prefix ++ Array.fill(64)(0.toByte)
  private val AboveHighestKey: Array[Byte] = Prefix ++ Array.fill(65)(0xff.toByte)

  private def key(accountHash: ByteString, storageRoot: ByteString): Array[Byte] =
    val k = new Array[Byte](Prefix.length + 64)
    System.arraycopy(Prefix, 0, k, 0, Prefix.length)
    accountHash.copyToArray(k, Prefix.length, 32)
    storageRoot.copyToArray(k, Prefix.length + 32, 32)
    k
