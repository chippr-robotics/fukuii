package com.chipprbots.ethereum.db.storage

import scala.collection.immutable.ArraySeq

import com.chipprbots.ethereum.db.dataSource.DataSource

object FastSyncStateStorage:

  /** The key fast sync stored its progress record under, in [[Namespaces.FastSyncStateNamespace]]. */
  val syncStateKey: IndexedSeq[Byte] =
    ArraySeq.unsafeWrapArray("fast-sync-state".getBytes(StorageStringCharset.UTF8Charset))

/** Read-only view of the progress record that fast sync left behind.
  *
  * Fast sync was removed. A node upgraded while it was still fast-syncing keeps that record; nothing decodes, rewrites
  * or deletes it. [[com.chipprbots.ethereum.blockchain.sync.SyncController]] only asks whether it exists, to tell a
  * node that stopped mid-fast-sync (best block advanced, state behind it incomplete) from one that reached its best
  * block by importing blocks.
  *
  * [[Namespaces.FastSyncStateNamespace]] stays registered because RocksDB has to open every column family an existing
  * database already has.
  */
class FastSyncStateStorage(val dataSource: DataSource):

  /** Whether a fast-sync progress record exists. The record itself is never decoded. */
  def hasSyncState: Boolean =
    dataSource.get(Namespaces.FastSyncStateNamespace, FastSyncStateStorage.syncStateKey).isDefined
