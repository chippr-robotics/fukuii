package com.chipprbots.ethereum.db.storage

import scala.collection.immutable.ArraySeq

import com.chipprbots.ethereum.db.dataSource.DataSource
import com.chipprbots.ethereum.db.dataSource.DataSourceBatchUpdate
import com.chipprbots.ethereum.db.dataSource.DataSourceUpdate

object FastSyncStateStorage:

  /** The key fast sync stored its progress record under, in [[Namespaces.FastSyncStateNamespace]]. */
  val syncStateKey: IndexedSeq[Byte] =
    ArraySeq.unsafeWrapArray("fast-sync-state".getBytes(StorageStringCharset.UTF8Charset))

/** The progress record that fast sync left behind.
  *
  * Fast sync was removed. A node upgraded while it was still fast-syncing, or one where SNAP once fell back to fast
  * sync, still has the record; nothing decodes it. [[com.chipprbots.ethereum.blockchain.sync.SyncController]] checks
  * once at startup whether it exists, to tell a node that stopped mid-fast-sync (best block advanced, state behind it
  * incomplete) from one that reached its best block another way, and then deletes it.
  *
  * [[Namespaces.FastSyncStateNamespace]] stays registered because RocksDB has to open every column family an existing
  * database already has.
  */
class FastSyncStateStorage(val dataSource: DataSource):

  /** Whether a fast-sync progress record exists. The record itself is never decoded. */
  def hasSyncState: Boolean =
    dataSource.get(Namespaces.FastSyncStateNamespace, FastSyncStateStorage.syncStateKey).isDefined

  /** Delete the record, as part of a batch (the caller commits it together with what it decided from the record). */
  def removeSyncState(): DataSourceBatchUpdate =
    DataSourceBatchUpdate(
      dataSource,
      Array(DataSourceUpdate(Namespaces.FastSyncStateNamespace, Seq(FastSyncStateStorage.syncStateKey), Nil))
    )
