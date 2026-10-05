package com.chipprbots.ethereum.db.storage

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.db.storage.NodeStorage.NodeEncoded
import com.chipprbots.ethereum.db.storage.NodeStorage.NodeHash
import com.chipprbots.ethereum.db.storage.pruning.PruneSupport
import com.chipprbots.ethereum.mpt.NodesKeyValueStorage
import com.chipprbots.ethereum.utils.Logger

import encoding.*

/** This class helps to deal with two problems regarding MptNodes storage: 1) Define a way to delete ones that are no
  * longer needed but allow rollbacks to be performed 2) Avoids removal of nodes that can be used in different trie
  * branches because the hash is the same
  *
  * To deal with (1) when a node is no longer needed, block number alongside with a stored node snapshot is saved so it
  * can be restored in case of rollback.
  *
  * In order to solve (2), before saving a node, it's wrapped with the number of references it has.
  *
  * Using this storage will change data to be stored in nodeStorage in two ways (and it will, as consequence, make
  * different pruning mechanisms incompatible):
  *   - Instead of saving KEY -> VALUE, it will store KEY -> STORED_NODE(VALUE, REFERENCE_COUNT, LAST_USED_BY_BLOCK)
  *
  * Also, additional data will be saved in this storage:
  *   - For each block: BLOCK_NUMBER_TAG -> NUMBER_OF_SNAPSHOTS
  *   - For each node changed within a block: (BLOCK_NUMBER_TAG ++ SNAPSHOT_INDEX) -> SNAPSHOT
  *
  * Storing snapshot info this way allows for easy construction of snapshot key (based on a block number and number of
  * snapshots) and therefore, fast access to each snapshot individually.
  */
class ReferenceCountNodeStorage(nodeStorage: NodesStorage, bn: BigInt) extends NodesKeyValueStorage:

  import ReferenceCountNodeStorage.*

  /** Net reference-count change this instance has applied per node (the sum over its `update` calls). A staged block
    * owns one instance for its whole execution, so this is exactly what the block did to the counts; it becomes the
    * block's undo record (see [[ReferenceCountNodeStorage.abandonRecordWrites]]).
    */
  private val netDeltaByNode = scala.collection.mutable.LinkedHashMap.empty[NodeHash, Int]

  /** Non-zero net reference-count changes applied so far, in first-touched order. */
  def netDeltas: Seq[(NodeHash, Int)] = netDeltaByNode.iterator.filter(_._2 != 0).toSeq

  def get(key: ByteString): Option[NodeEncoded] =
    nodeStorage.get(key).map(node => storedNodeFromBytes(node).nodeEncoded.toArray)

  /** Batched read: the trait default is `keys.map(get)`, which on basic pruning degrades to N serial point lookups
    * (~50K per chunk during the post-SNAP healing walk). Route through the inner `nodeStorage.multiGet` (a single
    * `dataSource.multiGetOptimized` JNI call) and apply the SAME ref-count unwrap as `get`, so the result is
    * byte-identical element-for-element (including `None` for absent keys). Mirrors `ArchiveNodeStorage.multiGet`.
    * (spec 002 US7 / FR-021, FR-022)
    */
  override def multiGet(keys: Seq[NodeHash]): Seq[Option[NodeEncoded]] =
    nodeStorage.multiGet(keys).map(_.map(node => storedNodeFromBytes(node).nodeEncoded.toArray))

  def update(toRemove: Seq[NodeHash], toUpsert: Seq[(NodeHash, NodeEncoded)]): ReferenceCountNodeStorage =

    val deathRowKey = drRowKey(bn)

    var currentDeathRow = getDeathRow(deathRowKey, nodeStorage)
    // Process upsert changes. As the same node might be changed twice within the same update, we need to keep changes
    // within a map. There is also stored the snapshot version before changes
    val upsertChanges = prepareUpsertChanges(toUpsert, bn)
    val changes = prepareRemovalChanges(toRemove, upsertChanges, bn)

    changes.foreach { case (key, (storedNode, snapshot)) =>
      val delta = storedNode.references - snapshot.storedNode.fold(0)(_.references)
      if delta != 0 then netDeltaByNode.update(key, netDeltaByNode.getOrElse(key, 0) + delta)
    }

    val (toUpsertUpdated, snapshots) =
      // Use List prepend (O(1)) instead of Seq append (O(n)) to avoid O(n²) for large node sets
      val (upsertRevAcc, snapshotRevAcc) =
        changes.foldLeft((List.empty[(NodeHash, NodeEncoded)], List.empty[StoredNodeSnapshot])) {
          case ((upsertAcc, snapshotAcc), (key, (storedNode, theSnapshot))) =>
            // if after update number references drop to zero mark node as possible for deletion after x blocks
            if storedNode.references == 0 then currentDeathRow = currentDeathRow ++ key

            ((key -> storedNodeToBytes(storedNode)) :: upsertAcc, theSnapshot :: snapshotAcc)
        }
      (upsertRevAcc.reverse, snapshotRevAcc.reverse)

    val snapshotToSave: Seq[(NodeHash, Array[Byte])] = getSnapshotsToSave(bn, snapshots)

    val deathRow =
      if currentDeathRow.nonEmpty then Seq(deathRowKey -> currentDeathRow.toArray[Byte])
      else Seq()

    nodeStorage.updateCond(Nil, deathRow ++ toUpsertUpdated ++ snapshotToSave, inMemory = true)
    this

  override def persist(): Unit = {}

  private def prepareUpsertChanges(toUpsert: Seq[(NodeHash, NodeEncoded)], blockNumber: BigInt): Changes =
    toUpsert.foldLeft(Map.empty[NodeHash, (StoredNode, StoredNodeSnapshot)]) { (storedNodes, toUpsertItem) =>
      val (nodeKey, nodeEncoded) = toUpsertItem
      val (storedNode, snapshot) = getFromChangesOrStorage(nodeKey, storedNodes)
        .getOrElse(
          StoredNode.withoutReferences(nodeEncoded) -> StoredNodeSnapshot(nodeKey, None)
        ) // if it's new, return an empty stored node

      storedNodes + (nodeKey -> ((storedNode.incrementReferences(1, blockNumber), snapshot)))
    }

  private def prepareRemovalChanges(
      toRemove: Seq[NodeHash],
      changes: Map[NodeHash, (StoredNode, StoredNodeSnapshot)],
      blockNumber: BigInt
  ): Changes =
    toRemove.foldLeft(changes) { (storedNodes, nodeKey) =>
      val maybeStoredNode: Option[(StoredNode, StoredNodeSnapshot)] = getFromChangesOrStorage(nodeKey, storedNodes)

      maybeStoredNode.fold(storedNodes) { case (storedNode, snapshot) =>
        storedNodes + (nodeKey -> ((storedNode.decrementReferences(1, blockNumber), snapshot)))
      }
    }

  private def getSnapshotsToSave(
      blockNumber: BigInt,
      snapshots: Seq[StoredNodeSnapshot]
  ): Seq[(NodeHash, Array[Byte])] =
    if snapshots.nonEmpty then
      // If not empty, snapshots will be stored indexed by block number and index
      val snapshotCountKey = getSnapshotsCountKey(blockNumber)
      val getSnapshotKeyFn = getSnapshotKey(blockNumber)(_)
      val blockNumberSnapshotsCount: BigInt =
        nodeStorage.get(snapshotCountKey).map(snapshotsCountFromBytes).getOrElse(0)
      val snapshotsToSave = snapshots.zipWithIndex.map { case (snapshot, index) =>
        getSnapshotKeyFn(blockNumberSnapshotsCount + index) -> snapshotToBytes(snapshot)
      }
      // Save snapshots and latest snapshot index
      (snapshotCountKey -> snapshotsCountToBytes(blockNumberSnapshotsCount + snapshotsToSave.size)) +: snapshotsToSave
    else Nil

  private def getFromChangesOrStorage(
      nodeKey: NodeHash,
      storedNodes: Changes
  ): Option[(StoredNode, StoredNodeSnapshot)] =
    storedNodes
      .get(nodeKey)
      .orElse(nodeStorage.get(nodeKey).map(storedNodeFromBytes).map(sn => sn -> StoredNodeSnapshot(nodeKey, Some(sn))))

object ReferenceCountNodeStorage extends PruneSupport with Logger:

  val nodeKeyLength = 32

  def drRowKey(bn: BigInt): ByteString =
    ByteString("dr".getBytes()) ++ ByteString(bn.toByteArray)

  def getDeathRow(key: ByteString, nodeStorage: NodesStorage): ByteString =
    ByteString(nodeStorage.get(key).getOrElse(Array[Byte]()))

  type Changes = Map[NodeHash, (StoredNode, StoredNodeSnapshot)]

  /** Fetches snapshots stored in the DB for the given block number and deletes the stored nodes, referred to by these
    * snapshots, that meet criteria for deletion (see `getNodesToBeRemovedInPruning` for details).
    *
    * All snapshots for this block are removed, which means state can no longer be rolled back to this point.
    *
    * @param blockNumber
    *   BlockNumber to prune
    * @param nodeStorage
    *   NodeStorage
    */
  override def prune(blockNumber: BigInt, nodeStorage: NodesStorage, inMemory: Boolean): Unit =
    val _ = pruneReporting(blockNumber, nodeStorage, inMemory)

  /** [[prune]], returning the hashes of the trie nodes it deleted, so a cache of decoded nodes can drop them. */
  def pruneReporting(blockNumber: BigInt, nodeStorage: NodesStorage, inMemory: Boolean): Seq[NodeHash] =
    log.debug(s"Pruning block $blockNumber")

    var removed: Seq[NodeHash] = Nil
    withSnapshotCount(blockNumber, nodeStorage) { (snapshotsCountKey, snapshotCount) =>
      val deathRowKey = drRowKey(blockNumber)
      val snapshotKeys: Seq[NodeHash] = snapshotKeysUpTo(blockNumber, snapshotCount)
      val toBeRemoved = getNodesToBeRemovedInPruning(blockNumber, deathRowKey, nodeStorage)
      nodeStorage.updateCond((deathRowKey +: snapshotsCountKey +: snapshotKeys) ++ toBeRemoved, Nil, inMemory)
      removed = toBeRemoved
    }

    pruneAbandonRecords(blockNumber, nodeStorage, inMemory)

    log.debug(s"Pruned block $blockNumber")
    removed

  /** Looks for the StoredNode snapshots based on block number and saves (or deletes) them
    *
    * @param blockNumber
    *   BlockNumber to rollback
    * @param nodeStorage
    *   NodeStorage
    */
  override def rollback(blockNumber: BigInt, nodeStorage: NodesStorage, inMemory: Boolean): Unit =
    val _ = rollbackReporting(blockNumber, nodeStorage, inMemory)

  /** [[rollback]], returning the hashes of the trie nodes it removed, so a cache of decoded nodes can drop them.
    *
    * A block's snapshots are appended in write order, and one block can write the same node more than once (every
    * transaction persists the trie, so a hot node is rewritten per transaction). Only the FIRST snapshot of a hash
    * holds its value from before the block; the later ones are intermediate states the block itself produced. They are
    * coalesced to that first value so each hash gets exactly one action: restored, or deleted when it did not exist
    * before the block. Replaying them all emitted both a delete and an upsert for such a hash and the upsert won,
    * leaving a node the block had created behind with a reference count.
    *
    * The individual snapshot keys go too, as [[pruneReporting]] does, so a rolled-back block leaves nothing behind.
    *
    * NOT a general undo. Snapshots hold absolute values, so restoring one overwrites any LATER writer of the same node.
    * It is only correct for the tip of a chain whose later blocks have been undone first; the import path does not use
    * it (a failed block stages nothing, see [[com.chipprbots.ethereum.db.storage.StagedBlockState]]).
    */
  def rollbackReporting(blockNumber: BigInt, nodeStorage: NodesStorage, inMemory: Boolean): Seq[NodeHash] =
    var removed: Seq[NodeHash] = Nil
    withSnapshotCount(blockNumber, nodeStorage) { (snapshotsCountKey, snapshotCount) =>
      val snapshotKeys = snapshotKeysUpTo(blockNumber, snapshotCount)
      // Snapshots in write order; keep the earliest per node: that is the value from before the block.
      val earliest = snapshotKeys
        .flatMap(key => nodeStorage.get(key).map(snapshotFromBytes))
        .foldLeft(Vector.empty[StoredNodeSnapshot] -> Set.empty[NodeHash]) { case ((kept, seen), snapshot) =>
          if seen.contains(snapshot.nodeKey) then (kept, seen) else (kept :+ snapshot, seen + snapshot.nodeKey)
        }
        ._1
      // We need to delete deathrow for rollbacked block
      val deathRowKey = drRowKey(blockNumber)
      // Transform them to db operations: disjoint by construction, one per node
      val toRemove = earliest.collect { case StoredNodeSnapshot(nodeHash, None) => nodeHash }
      val toUpsert = earliest.collect { case StoredNodeSnapshot(nodeHash, Some(sn)) =>
        nodeHash -> storedNodeToBytes(sn)
      }
      // also remove the snapshots as we have done a rollback
      nodeStorage.updateCond(toRemove ++ snapshotKeys :+ snapshotsCountKey :+ deathRowKey, toUpsert, inMemory)
      removed = toRemove ++ toUpsert.map(_._1) // upserted nodes get their old reference counts back; drop them too
    }
    removed

  // ---- Undo records for blocks that stop being canonical (execute-first reorganisation) ----
  //
  // Snapshots, `sck` and `dr` are keyed by block NUMBER and hold absolute pre-images: two siblings at one height write
  // into the same rows and nothing separates one block's effect from the other's, so a block that is abandoned by a
  // reorg cannot be undone by number (see [[rollbackReporting]]). Yet its reference-count decrements stay applied, and
  // a node only that block replaced (the old coinbase leaf) is still live in the sibling that won, at refs 0 on
  // `dr<number>`: pruned `history` blocks later, found missing much later.
  //
  // So each staged block also stores its NET per-node reference-count change, keyed by block HASH, and an abandoned
  // block is undone RELATIVELY (refs -= delta), which commutes with every other block's writes. The record carries an
  // applied flag so the undo is idempotent and reversible: a side block that is adopted again (as the parent of a new
  // branch) has its delta put back.

  private val abandonRecordPrefix = ByteString("bd".getBytes)
  private val abandonIndexPrefix = ByteString("bdh".getBytes)
  private val deltaEntryLength = nodeKeyLength + 4

  /** `bd<blockHash>` -> flag byte (1 applied, 0 undone) ++ (32-byte node hash ++ 4-byte big-endian delta)*. */
  def abandonRecordKey(blockHash: ByteString): ByteString = abandonRecordPrefix ++ blockHash

  /** `bdh<blockNumber>` -> the 32-byte hashes of the blocks at that height that have a record; lets prune delete them.
    */
  def abandonIndexKey(bn: BigInt): ByteString = abandonIndexPrefix ++ ByteString(bn.toByteArray)

  private def encodeAbandonRecord(applied: Boolean, deltas: Seq[(NodeHash, Int)]): Array[Byte] =
    val buf = java.nio.ByteBuffer.allocate(1 + deltas.size * deltaEntryLength)
    buf.put(if applied then 1.toByte else 0.toByte)
    deltas.foreach { case (key, delta) => buf.put(key.toArray).putInt(delta) }
    buf.array()

  private def decodeAbandonRecord(bytes: Array[Byte]): (Boolean, Seq[(NodeHash, Int)]) =
    val buf = java.nio.ByteBuffer.wrap(bytes)
    val applied = buf.get() == 1.toByte
    val entries = (0 until (bytes.length - 1) / deltaEntryLength).map { _ =>
      val key = new Array[Byte](nodeKeyLength)
      buf.get(key)
      ByteString.fromArrayUnsafe(key) -> buf.getInt()
    }
    (applied, entries)

  /** The writes that store a block's undo record next to the block: the record itself (applied) and its entry in the
    * per-height index. Empty when the block changed no reference count.
    */
  def abandonRecordWrites(
      bn: BigInt,
      blockHash: ByteString,
      deltas: Seq[(NodeHash, Int)],
      nodeStorage: NodesStorage
  ): Seq[(NodeHash, Array[Byte])] =
    if deltas.isEmpty then Nil
    else
      val indexKey = abandonIndexKey(bn)
      val index = ByteString(nodeStorage.get(indexKey).getOrElse(Array.emptyByteArray))
      val known = index.grouped(nodeKeyLength).contains(blockHash)
      val indexWrite = if known then Nil else Seq(indexKey -> (index ++ blockHash).toArray)
      (abandonRecordKey(blockHash) -> encodeAbandonRecord(applied = true, deltas)) +: indexWrite

  /** Take the reference-count changes of the block `blockHash` (at height `bn`) back out, because it is no longer
    * canonical. No-op when there is no record (pruned, written before undo records existed, or already undone).
    */
  def undoBlock(bn: BigInt, blockHash: ByteString, nodeStorage: NodesStorage, inMemory: Boolean): Unit =
    setBlockApplied(bn, blockHash, applied = false, nodeStorage, inMemory)

  /** The inverse of [[undoBlock]]: the block is canonical again without being executed again. */
  def redoBlock(bn: BigInt, blockHash: ByteString, nodeStorage: NodesStorage, inMemory: Boolean): Unit =
    setBlockApplied(bn, blockHash, applied = true, nodeStorage, inMemory)

  private def setBlockApplied(
      bn: BigInt,
      blockHash: ByteString,
      applied: Boolean,
      nodeStorage: NodesStorage,
      inMemory: Boolean
  ): Unit =
    val recordKey = abandonRecordKey(blockHash)
    nodeStorage.get(recordKey).map(decodeAbandonRecord).foreach { case (currentlyApplied, deltas) =>
      if currentlyApplied != applied then
        val sign = if applied then 1 else -1
        val deathRowKey = drRowKey(bn)
        var deathRow = getDeathRow(deathRowKey, nodeStorage)
        val upserts = deltas.flatMap { case (key, delta) =>
          nodeStorage.get(key).map(storedNodeFromBytes).map { node =>
            val updated = node.copy(references = node.references + sign * delta)
            // A node left unreferenced must be on a death row, or it is never deleted: nothing else will list it.
            if updated.references == 0 && !deathRow.grouped(nodeKeyLength).contains(key) then deathRow = deathRow ++ key
            key -> storedNodeToBytes(updated)
          }
        }
        val deathRowWrite = if deathRow.nonEmpty then Seq(deathRowKey -> deathRow.toArray[Byte]) else Nil
        nodeStorage.updateCond(
          Nil,
          upserts ++ deathRowWrite :+ (recordKey -> encodeAbandonRecord(applied, deltas)),
          inMemory
        )
    }

  private def pruneAbandonRecords(blockNumber: BigInt, nodeStorage: NodesStorage, inMemory: Boolean): Unit =
    val indexKey = abandonIndexKey(blockNumber)
    nodeStorage.get(indexKey).foreach { index =>
      val hashes = ByteString(index).grouped(nodeKeyLength).toSeq
      nodeStorage.updateCond(indexKey +: hashes.map(abandonRecordKey), Nil, inMemory)
    }

  private def withSnapshotCount(blockNumber: BigInt, nodeStorage: NodesStorage)(
      f: (ByteString, BigInt) => Unit
  ): Unit =
    val snapshotsCountKey = getSnapshotsCountKey(blockNumber)
    // Look for snapshot count for given block number
    val maybeSnapshotCount = nodeStorage.get(snapshotsCountKey).map(snapshotsCountFromBytes)
    maybeSnapshotCount match
      case Some(snapshotCount) => f(snapshotsCountKey, snapshotCount)
      case None                => ()

  private def snapshotKeysUpTo(blockNumber: BigInt, snapshotCount: BigInt): Seq[ByteString] =
    val getSnapshotKeyFn = getSnapshotKey(blockNumber)(_)
    (BigInt(0) until snapshotCount).map(snapshotIndex => getSnapshotKeyFn(snapshotIndex))

  /** Within death row of this block, it looks for Nodes that are not longer being used in order to remove them from DB.
    * To do so, it checks if nodes marked in death row have still reference count equal to 0 and are not used by future
    * blocks.
    * @param blockNumber
    * @param deadRowKey
    * @param nodeStorage
    * @return
    */
  private def getNodesToBeRemovedInPruning(
      blockNumber: BigInt,
      deadRowKey: ByteString,
      nodeStorage: NodesStorage
  ): Seq[NodeHash] =
    var nodesToRemove = List.empty[NodeHash]
    val deathRow = getDeathRow(deadRowKey, nodeStorage).grouped(nodeKeyLength)

    deathRow.foreach { key =>
      for
        node <- nodeStorage.get(key).map(storedNodeFromBytes)
        if node.references == 0 && node.lastUsedByBlock <= blockNumber
      yield nodesToRemove = key :: nodesToRemove
    }

    nodesToRemove

  /** Wrapper of MptNode in order to store number of references it has.
    *
    * @param nodeEncoded
    *   Encoded Mpt Node to be used in MerklePatriciaTrie
    * @param references
    *   Number of references the node has. Each time it's updated references are increased and everytime it's deleted,
    *   decreased
    * @param lastUsedByBlock
    *   Block Number where this node was last used
    */
  case class StoredNode(nodeEncoded: ByteString, references: Int, lastUsedByBlock: BigInt):
    def incrementReferences(amount: Int, blockNumber: BigInt): StoredNode =
      copy(references = references + amount, lastUsedByBlock = blockNumber)

    def decrementReferences(amount: Int, blockNumber: BigInt): StoredNode =
      copy(references = references - amount, lastUsedByBlock = blockNumber)

  object StoredNode:
    def withoutReferences(nodeEncoded: Array[Byte]): StoredNode = new StoredNode(ByteString(nodeEncoded), 0, 0)

  /** Key to be used to store BlockNumber -> Snapshots Count
    *
    * @param blockNumber
    *   Block Number Tag
    * @return
    *   Key
    */
  private def getSnapshotsCountKey(blockNumber: BigInt): ByteString = ByteString(
    "sck".getBytes ++ blockNumber.toByteArray
  )

  /** Returns a snapshot key given a block number and a snapshot index
    * @param blockNumber
    *   Block Number Ta
    * @param index
    *   Snapshot Index
    * @return
    */
  private def getSnapshotKey(blockNumber: BigInt)(index: BigInt): ByteString = ByteString(
    ("sk".getBytes ++ blockNumber.toByteArray) ++ index.toByteArray
  )

  /** Used to store a node snapshot in the db. This will be used to rollback a transaction.
    * @param nodeKey
    *   Node's key
    * @param storedNode
    *   Stored node that can be rolledback. If None, it means that node wasn't previously in the DB
    */
  case class StoredNodeSnapshot(nodeKey: NodeHash, storedNode: Option[StoredNode])
