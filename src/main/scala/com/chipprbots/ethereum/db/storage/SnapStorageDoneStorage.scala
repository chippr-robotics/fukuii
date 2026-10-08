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
  * A marker means "the storage of this account at this storage root was fully downloaded in this SNAP cycle, and its
  * flat slots and trie nodes are on disk". It is written by StorageRangeCoordinator in the SAME RocksDB write batch as
  * the account's last flat slots, and only after every earlier flat-slot batch has committed — so a marker can never
  * outlive the data it vouches for (one atomic WriteBatch; the WAL is replayed as a prefix). A resume uses the markers
  * to re-queue only the storage tasks that were not finished (see AccountRangeCoordinator.replayCarriedChunk and the
  * accounts-complete recovery stream in SNAPSyncController).
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

  /** Drop every marker (one range tombstone). */
  def clear(): Unit = dataSource.deleteRange(namespace, LowestKey, AboveHighestKey)

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
