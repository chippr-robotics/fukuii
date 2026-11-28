package com.chipprbots.ethereum.blockchain.sync

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.db.storage.pathstate.BlockRef
import com.chipprbots.ethereum.db.storage.pathstate.DirectRowMptStorage
import com.chipprbots.ethereum.db.storage.pathstate.PositionRepairs
import com.chipprbots.ethereum.mpt.NibblePath

/** What the recovery scans (`CombinedRecoveryScanner`, `StorageRecoveryActor`, `BytecodeRecoveryActor`) read the state
  * through (spec 018 C5b, T219).
  *
  * On the unified layout (`positions` is defined) the state is the anchor's rows, read directly by position and judged
  * against each parent ([[DirectRowMptStorage]]); the scan's root is the anchor's, since no other state has rows to
  * read. Off it (the Hash scheme, or the guard off) nothing changes: the scans read the hash store through
  * `getBackingStorage(number)`, as they did.
  */
object RecoveryScanStores:

  /** The store a scan of the state at `root` (block `number`) reads. */
  def forRoot(
      stateStorage: StateStorage,
      positions: Option[PositionRepairs],
      root: ByteString,
      number: BigInt
  ): MptStorage =
    positions.fold(stateStorage.getBackingStorage(number))(DirectRowMptStorage(_, root))

  /** The state a recovery scans, on the unified layout: the anchor's. None off the layout, or with no anchor. */
  def anchorOf(positions: Option[PositionRepairs]): Option[BlockRef] = positions.flatMap(_.anchor)

  /** Whether a scan of `root` can read the state: its root is held (one raw read) and, on the unified layout, is the
    * anchor's.
    */
  def readable(
      stateStorage: StateStorage,
      positions: Option[PositionRepairs],
      root: ByteString,
      number: BigInt
  ): Boolean =
    positions match
      case None => stateStorage.getBackingStorage(number).holdsNode(root)
      case Some(rows) =>
        rows.anchor.exists(_.stateRoot == root) &&
        scala.util
          .Try(DirectRowMptStorage(rows, root).forOwner(None).holdsNodeAt(NibblePath.Empty, root))
          .getOrElse(false)
