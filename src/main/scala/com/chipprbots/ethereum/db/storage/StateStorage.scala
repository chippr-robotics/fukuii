package com.chipprbots.ethereum.db.storage

import java.util.concurrent.TimeUnit

import org.apache.pekko.util.ByteString

import scala.annotation.unused
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.cache.LruCache
import com.chipprbots.ethereum.db.cache.MapCache
import com.chipprbots.ethereum.db.dataSource.DataSource
import com.chipprbots.ethereum.db.dataSource.DataSourceBatchUpdate
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.NodeStorage.NodeEncoded
import com.chipprbots.ethereum.db.storage.NodeStorage.NodeHash
import com.chipprbots.ethereum.db.storage.StateStorage.FlushSituation
import com.chipprbots.ethereum.db.storage.StateStorage.GenesisDataLoad
import com.chipprbots.ethereum.db.storage.StateStorage.SnapSyncFinalization
import com.chipprbots.ethereum.db.storage.pruning.ArchivePruning
import com.chipprbots.ethereum.db.storage.pruning.PruningMode
import com.chipprbots.ethereum.mpt.MptNode
import com.chipprbots.ethereum.utils.NodeCacheConfig

/** The state writes of ONE block under execution, held back until the block is accepted.
  *
  * Execution persists the trie after every transaction, and under basic pruning each persist increments reference
  * counts and writes snapshots and death rows. Applied straight to the database, a block that then fails (a missing
  * node on transaction 5, a state-root mismatch after the last) leaves all of that behind, and the retry applies it a
  * second time: counts skew, and a live node ends at zero references on a death row (devnet-8, block 320603).
  *
  * A staged block writes into a buffer instead. `pending` is the buffer as a batch update the caller commits in the
  * SAME atomic write as the block's header, receipts, chain weight and best-block pointer, so a block's state exists if
  * and only if the block does. A failed block is `discard`ed and leaves nothing. No snapshot is ever rolled back by
  * block number: there is nothing to roll back.
  */
trait StagedBlockState:
  /** The storage block execution reads and writes. */
  def storage: MptStorage

  /** Buffered writes to commit atomically with the block; `None` when this mode writes straight through. */
  def pending: Option[DataSourceBatchUpdate]

  /** A read-only, thread-safe view of the committed (parent) state for the BAL prefetch, or `None` when this mode has
    * none. `budgetBytes` caps what it inserts into the decoded-node cache; `onLoaded` sees each inserted hash.
    */
  def prefetchReader(budgetBytes: Long, onLoaded: ByteString => Unit): Option[PrefetchNodeReader] = None

  /** [[pending]] plus, for the modes that keep one, the block's undo record (see [[StateStorage.onBlocksAbandoned]]),
    * which must be committed in the SAME atomic write as the block it describes. `pending` itself is unchanged.
    */
  def pendingWith(@unused blockNumber: BigInt, @unused blockHash: ByteString): Option[DataSourceBatchUpdate] = pending

  /** Commit this block's state (with `extra`, the block's other writes, in the SAME atomic batch), state ONCE.
    * Execution reads reference counts while it runs and commits ABSOLUTE values, so committing the same block twice
    * (the Engine API `newPayload` path and the regular-sync importer each execute a block the other has not committed
    * yet) applies its count changes twice. A mode that keeps counts overrides this to drop the state half of a second
    * commit; the others write straight through (or have nothing buffered) and just commit.
    */
  def commitWith(blockNumber: BigInt, blockHash: ByteString, extra: Option[DataSourceBatchUpdate]): Unit =
    (pendingWith(blockNumber, blockHash), extra) match
      case (Some(state), Some(other)) => other.and(state).commit()
      case (Some(state), None)        => state.commit()
      case (None, Some(other))        => other.commit()
      case (None, None)               => ()

  /** Commit this block's state apart from the block, for a block executed without being adopted (a payload ahead of the
    * head, a side branch): its state is kept beside the head, and the block itself is stored by its caller. On the
    * modes that write a block's state with no head behind it, that is [[commitWith]] with nothing else in the batch. On
    * the unified layout it is a forward layer beside the state the disk holds (spec 018 C4d part 2a), and the head does
    * not move.
    */
  def commitApart(blockNumber: BigInt, blockHash: ByteString): Unit = commitWith(blockNumber, blockHash, None)

  /** Whether committing this block moves the state on disk to it, in place (the unified layout, before its overlay).
    * Such a commit is only possible in the batch that makes the block the best block, so a block that is executed
    * without being adopted cannot be staged this way: its caller asks, and refuses before the block runs.
    */
  def commitsInPlace: Boolean = false

  /** True when this block's state changes are committed and in force right now (committed, not undone). Only the mode
    * that keeps per-block undo records can say so; the others answer false, which makes callers execute.
    */
  def isBlockApplied(@unused blockHash: ByteString): Boolean = false

  /** Drop the buffered writes and evict what execution read back from them out of the decoded-node cache. */
  def discard(): Unit

object StagedBlockState:
  /** Write-through, for the modes that never skewed (archive, in-memory pruning): nothing to hold back. */
  def direct(backing: MptStorage): StagedBlockState = new StagedBlockState:
    override def storage: MptStorage = backing
    override def pending: Option[DataSourceBatchUpdate] = None
    override def discard(): Unit = ()

/** What a conditional repair write did. */
enum RepairOutcome:
  case Written

  /** Valid bytes were already there, and they stay as they are. */
  case AlreadyValid

  /** The bytes offered do not hash to the key: nothing was written. */
  case Rejected

/** What a delete of a provably bad entry did. */
enum BadEntryDelete:
  case Deleted

  /** A read now finds the entry good, or gone. */
  case NoLongerBad

  /** The bytes hash to the key, though not in the format this store reads: another pruning mode's, say. They are the
    * node, so they stay, and this store still cannot use them.
    */
  case PassesKeccakElsewhere

  /** Still bad, and the current root no longer needs it there: it stays. */
  case NotReached

// scalastyle:off
trait StateStorage:
  def getBackingStorage(bn: BigInt): MptStorage

  /** Storage for loading a whole, already-final state at height `bn` in bulk: the Path publish, which streams every
    * node of the state through one instance. Reference counts are written exactly as [[getBackingStorage]] writes them.
    * A mode that keeps per-node undo rows for a block it may roll back by number leaves them out here, since nothing
    * rolls a bulk load back and a row per trie node is what a prune of that height could not hold in memory. Any other
    * mode has no such rows and gives the backing storage.
    */
  def getBulkLoadStorage(bn: BigInt): MptStorage = getBackingStorage(bn)

  /** Storage for executing block `bn` whose writes are held back until the caller commits them. */
  def stageBlock(bn: BigInt): StagedBlockState = StagedBlockState.direct(getBackingStorage(bn))
  def getReadOnlyStorage: MptStorage

  def onBlockSave(bn: BigInt, currentBestSavedBlock: BigInt)(updateBestBlocksData: () => Unit): Unit
  def onBlockRollback(bn: BigInt, currentBestSavedBlock: BigInt)(updateBestBlocksData: () => Unit): Unit

  /** Blocks that were executed and committed, and then stopped being canonical (a reorganisation chose a sibling
    * branch). A mode that counts references takes their reference-count changes back out, so a node only they replaced
    * is not left at zero references while the new canonical chain still uses it. Modes that never skewed ignore it.
    */
  def onBlocksAbandoned(blocks: Seq[(BigInt, ByteString)]): Unit = ()

  /** Blocks that became canonical again without being executed again (the already-executed parent of a new branch): the
    * inverse of [[onBlocksAbandoned]].
    */
  def onBlocksReadopted(blocks: Seq[(BigInt, ByteString)]): Unit = ()

  def saveNode(nodeHash: NodeHash, nodeEncoded: NodeEncoded, bn: BigInt): Unit

  /** The node stored under `nodeHash`, read as every other trie read is (`SerializingMptStorage.get`: keccak first,
    * through this mode's own format): `None` when it is absent, or when what is stored is not that node, which is
    * logged once as a corrupt node. For the chain reader's `getMptNodeByHash`, which once decoded the bytes itself,
    * with no hash check (H3, CHASE row 45). The GetNodeData server, its other caller, is gone (spec 018 Track C, C2b).
    */
  def getNode(nodeHash: NodeHash): Option[MptNode] =
    try Some(getReadOnlyStorage.get(nodeHash.toArray))
    catch case _: com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingNodeException => None
  def forcePersist(reason: FlushSituation): Boolean

  /** Whether [[repairNode]] works on this storage (spec 022 T053). */
  def canRepairNodes: Boolean = false

  /** The background audit's write (spec 022 decision 1): `nodeEncoded` goes in only if `nodeHash` is absent or its
    * entry fails its hash, judged at write time, so it can never overwrite a node a block commit has since written or
    * counted. Unlike [[saveNode]], a blind upsert at one reference, this never lowers a count it finds. A store's lock
    * covers block commits and prunes and not [[saveNode]], which takes none: import's on-demand fetch can still land
    * either side of this write, with the same good bytes, and its upsert can set this count back to one, as it can any
    * node's.
    */
  def repairNode(nodeHash: NodeHash, nodeEncoded: NodeEncoded, bn: BigInt): RepairOutcome =
    throw new UnsupportedOperationException(s"${getClass.getSimpleName} cannot repair nodes")

  /** The background audit's last resort for an entry provably bad (spec 022 decision 15): one that does not unwrap, or
    * bytes that fail their hash. Judged here from a read of its own, so a transient read fault behind the audit's
    * verdict cannot delete a good node, then `stillReached` asks whether the current root still needs this hash at the
    * position it was found, then a read again, since [[saveNode]] takes no lock and can land the good node during that
    * walk. A node that passes keccak is never deleted. Absence throws at execution, where bad bytes could go unnoticed,
    * so the trade is a stall for a wrong answer.
    */
  def deleteBadNode(nodeHash: NodeHash, stillReached: () => Boolean): BadEntryDelete =
    throw new UnsupportedOperationException(s"${getClass.getSimpleName} cannot delete nodes")

class ArchiveStateStorage(private val nodeStorage: NodeStorage) extends StateStorage:

  override def forcePersist(reason: FlushSituation): Boolean = true

  override def onBlockSave(bn: BigInt, currentBestSavedBlock: BigInt)(updateBestBlocksData: () => Unit): Unit =
    updateBestBlocksData()

  override def onBlockRollback(bn: BigInt, currentBestSavedBlock: BigInt)(updateBestBlocksData: () => Unit): Unit =
    updateBestBlocksData()

  override def getReadOnlyStorage: MptStorage =
    new SerializingMptStorage(ReadOnlyNodeStorage(new ArchiveNodeStorage(nodeStorage)))

  override def getBackingStorage(bn: BigInt): MptStorage =
    new SerializingMptStorage(new ArchiveNodeStorage(nodeStorage))

  /** Write-through as before; adds the prefetch reader (an archive node keeps no decoded-node cache, so the reader only
    * warms the database's own caches).
    */
  override def stageBlock(bn: BigInt): StagedBlockState =
    val backing = getBackingStorage(bn)
    new StagedBlockState:
      override def storage: MptStorage = backing
      override def pending: Option[DataSourceBatchUpdate] = None
      override def prefetchReader(budgetBytes: Long, onLoaded: ByteString => Unit): Option[PrefetchNodeReader] =
        Some(
          new PrefetchNodeReader(
            new SerializingMptStorage(new ArchiveNodeStorage(nodeStorage)),
            None,
            budgetBytes,
            onLoaded
          )
        )
      override def discard(): Unit = ()

  override def saveNode(nodeHash: NodeHash, nodeEncoded: NodeEncoded, bn: BigInt): Unit =
    nodeStorage.put(nodeHash, nodeEncoded)

  override def canRepairNodes: Boolean = true

  /** No lock: an archive store keeps no counts, and two writers of one hash write the same bytes. */
  override def repairNode(nodeHash: NodeHash, nodeEncoded: NodeEncoded, bn: BigInt): RepairOutcome =
    if !StateStorage.hashesTo(nodeHash, nodeEncoded) then RepairOutcome.Rejected
    else if nodeStorage.get(nodeHash).exists(StateStorage.hashesTo(nodeHash, _)) then RepairOutcome.AlreadyValid
    else
      nodeStorage.put(nodeHash, nodeEncoded)
      RepairOutcome.Written

  /** No lock to take: nothing here commits under one. So the bytes are read again after `stillReached`, the slow part:
    * a write of this very hash in the last instant before the delete leaves a hole import fetches.
    */
  override def deleteBadNode(nodeHash: NodeHash, stillReached: () => Boolean): BadEntryDelete =
    def kept: Option[BadEntryDelete] = nodeStorage.get(nodeHash) match
      case None => Some(BadEntryDelete.NoLongerBad)
      case Some(raw) if !NodeFault.provablyBad(nodeHash, RawNodeRead.Bytes(raw)) =>
        Some(BadEntryDelete.NoLongerBad)
      case Some(raw) if StateStorage.wrapsNode(nodeHash, raw) => Some(BadEntryDelete.PassesKeccakElsewhere)
      case _                                                  => None
    StateStorage.deleteUnless(kept, stillReached) { () =>
      nodeStorage.update(Seq(nodeHash), Nil)
    }

object ReferenceCountedStateStorage extends com.chipprbots.ethereum.utils.Logger:
  /** One block commit (and one prune) at a time: the duplicate check reads the record the previous commit writes. */
  private[storage] val commitLock = new Object
  private[storage] def warnDuplicate(msg: String): Unit = log.warn(msg)
  private[storage] def warnTrustUnreadable(e: Throwable): Unit =
    log.error("Could not tell whether reference counts can be trusted; this prune keeps every trie node (#1692)", e)
  private[storage] def sayKeepingNodes(): Unit =
    log.info(
      "Pruning keeps every trie node: this datadir's reference counts are not proven exact (SNAP, a checkpoint import, " +
        "an old fast sync or an older binary filled it), and a node one position still uses could be deleted by a count " +
        "that is too low (#1692). Death rows, snapshot rows and undo records are still pruned. Dead nodes are kept " +
        "until the datadir is re-synced; db.pruning.retained.bytes counts them."
    )

/** @param nodesMayBeDeleted
  *   asked at each prune whether it may delete trie nodes (#1692, see [[RefCountTrust]]). The default says no: a caller
  *   that vouches for the counts of its store passes `() => true`. A throw is read as no.
  */
class ReferenceCountedStateStorage(
    private val nodeStorage: NodeStorage,
    private val pruningHistory: BigInt,
    decodedNodeCacheBytes: Long = com.chipprbots.ethereum.utils.StateReadCacheConfig.decodedNodeCacheBytes,
    nodesMayBeDeleted: () => Boolean = () => false
) extends StateStorage:
  override def forcePersist(reason: FlushSituation): Boolean = true

  @volatile private var saidKeepingNodes: Boolean = false

  /** Whether a prune or a rollback may delete trie nodes right now. Read each time, not once: SNAP can start in this
    * process and take the answer away. A read that fails answers no, and says so.
    */
  private def deletionAllowed(): Boolean =
    val allowed =
      try nodesMayBeDeleted()
      catch
        case NonFatal(e) =>
          ReferenceCountedStateStorage.warnTrustUnreadable(e)
          false
    if !allowed && !saidKeepingNodes then
      saidKeepingNodes = true
      ReferenceCountedStateStorage.sayKeepingNodes()
    allowed

  /** Decoded trie nodes for block execution (see [[DecodedNodeCache]]); owned here because this is where nodes are
    * deleted, and the deletions evict from it.
    */
  private val decodedNodes: Option[DecodedNodeCache] =
    DecodedNodeCache.forStorage(decodedNodeCacheBytes > 0)

  /** Highest block number pruned by this instance (not persisted: after a restart the first save prunes one block, as
    * it always did). Used to catch up after a prune was deferred because the canonical head lagged the saved block.
    */
  @volatile private var lastPruned: Option[BigInt] = None

  /** Prunes the death row of `min(bn, canonicalBest + 1) - pruningHistory`, never `bn - pruningHistory` blindly.
    *
    * `bn` is the number of the block just SAVED, which is not necessarily canonical: execute-first reorganisation
    * (`ConsensusImpl.reorganise`) saves a whole branch before it is adopted, and a branch that fails midway is retried.
    * Pruning on `bn` alone let such a branch walk the prune cursor `bn - history` past the canonical head and delete
    * the nodes of the head's own state, which the branch's blocks had replaced. A plain `canonicalBest + 1` ceiling is
    * the next block extending the head (the only non-canonical-looking save on a linear chain, since the best block is
    * only advanced after the executed batch), so single-block linear import prunes exactly as before; larger batches
    * are deferred and caught up by the next save.
    */
  override def onBlockSave(bn: BigInt, currentBestSavedBlock: BigInt)(updateBestBlocksData: () => Unit): Unit =
    ReferenceCountedStateStorage.commitLock.synchronized {
      pruneLocked(bn, currentBestSavedBlock)
    }
    updateBestBlocksData()

  private def pruneLocked(bn: BigInt, currentBestSavedBlock: BigInt): Unit =
    val target = bn.min(currentBestSavedBlock + 1) - pruningHistory
    val from = lastPruned match
      case Some(done) if done < target => done + 1
      case _                           => target
    var blockToPrune = from
    // Asked once for the whole catch-up, and only when there is a height to prune.
    lazy val deleteNodes = deletionAllowed()
    while blockToPrune <= target do
      val removed =
        ReferenceCountNodeStorage.pruneReporting(
          blockToPrune,
          nodeStorage,
          inMemory = blockToPrune > currentBestSavedBlock,
          deleteNodes = deleteNodes
        )
      // Evict AFTER the delete. A concurrent reader (Engine API newPayload runs without the regular-sync importer's
      // serialisation) may still re-read a node just before the delete; DecodedNodeCache.putIfCurrent drops such an
      // insert, because evict bumps the generation the reader took before reading.
      decodedNodes.foreach(_.evict(removed))
      blockToPrune += 1
    if lastPruned.forall(_ < target) then lastPruned = Some(target)

  override def onBlockRollback(bn: BigInt, currentBestSavedBlock: BigInt)(updateBestBlocksData: () => Unit): Unit =
    val removed = ReferenceCountNodeStorage.rollbackReporting(
      bn,
      nodeStorage,
      inMemory = bn > currentBestSavedBlock,
      deleteNodes = deletionAllowed()
    )
    // As in onBlockSave: evict after the rollback has deleted; racing inserts are dropped by putIfCurrent.
    decodedNodes.foreach(_.evict(removed))
    updateBestBlocksData()

  override def getBackingStorage(bn: BigInt): MptStorage =
    new SerializingMptStorage(new ReferenceCountNodeStorage(nodeStorage, bn), decodedNodes)

  override def getBulkLoadStorage(bn: BigInt): MptStorage =
    new SerializingMptStorage(new ReferenceCountNodeStorage(nodeStorage, bn, recordSnapshots = false), decodedNodes)

  override def onBlocksAbandoned(blocks: Seq[(BigInt, ByteString)]): Unit =
    ReferenceCountedStateStorage.commitLock.synchronized {
      blocks.foreach { case (bn, hash) => ReferenceCountNodeStorage.undoBlock(bn, hash, nodeStorage, inMemory = false) }
    }

  override def onBlocksReadopted(blocks: Seq[(BigInt, ByteString)]): Unit =
    ReferenceCountedStateStorage.commitLock.synchronized {
      blocks.foreach { case (bn, hash) => ReferenceCountNodeStorage.redoBlock(bn, hash, nodeStorage, inMemory = false) }
    }

  override def stageBlock(bn: BigInt): StagedBlockState =
    val buffered = new BufferedNodeStorage(nodeStorage)
    val counted = new ReferenceCountNodeStorage(buffered, bn, trackNetDeltas = true)
    new StagedBlockState:
      override val storage: MptStorage = new SerializingMptStorage(counted, decodedNodes)
      override def pending: Option[DataSourceBatchUpdate] = Option.when(!buffered.isEmpty)(buffered.pending)
      override def pendingWith(blockNumber: BigInt, blockHash: ByteString): Option[DataSourceBatchUpdate] =
        val writes = ReferenceCountNodeStorage.abandonRecordWrites(blockNumber, blockHash, counted.netDeltas, buffered)
        if writes.nonEmpty then buffered.update(Nil, writes)
        pending
      override def isBlockApplied(blockHash: ByteString): Boolean =
        ReferenceCountNodeStorage.isBlockApplied(blockHash, nodeStorage)
      // Serialised and idempotent per block hash: the check and the write are one critical section, and the first
      // commit's `bd<hash>` record (written atomically with its counts) is what the second one sees.
      override def commitWith(
          blockNumber: BigInt,
          blockHash: ByteString,
          extra: Option[DataSourceBatchUpdate]
      ): Unit =
        ReferenceCountedStateStorage.commitLock.synchronized {
          if ReferenceCountNodeStorage.isBlockApplied(blockHash, nodeStorage) then
            ReferenceCountedStateStorage.warnDuplicate(
              s"Block $blockNumber ${blockHash.take(4).toArray.map("%02x".format(_)).mkString} is already committed; " +
                "dropping the duplicate state commit (it would apply its reference-count changes twice)"
            )
            extra.foreach(_.commit())
            decodedNodes.foreach(_.evict(buffered.touchedKeys))
          else
            (pendingWith(blockNumber, blockHash), extra) match
              case (Some(state), Some(other)) => other.and(state).commit()
              case (Some(state), None)        => state.commit()
              case (None, Some(other))        => other.commit()
              case (None, None)               => ()
        }
      override def prefetchReader(budgetBytes: Long, onLoaded: ByteString => Unit): Option[PrefetchNodeReader] =
        Some(
          new PrefetchNodeReader(
            new SerializingMptStorage(new ReferenceCountNodeStorage(nodeStorage, bn)),
            decodedNodes,
            budgetBytes,
            onLoaded
          )
        )
      override def discard(): Unit =
        // Execution read nodes back out of the buffer through the decoded-node cache; they are not in the database, so
        // the cache must forget them (a cached node must never outlive its presence in the database).
        decodedNodes.foreach(_.evict(buffered.touchedKeys))

  override def getReadOnlyStorage: MptStorage =
    new SerializingMptStorage(ReadOnlyNodeStorage(new FastSyncNodeStorage(nodeStorage, 0)))

  override def saveNode(nodeHash: NodeHash, nodeEncoded: NodeEncoded, bn: BigInt): Unit =
    new FastSyncNodeStorage(nodeStorage, bn).update(Nil, Seq(nodeHash -> nodeEncoded))

  override def canRepairNodes: Boolean = true

  /** Under [[ReferenceCountedStateStorage.commitLock]], so no block commit changes a count between this re-check and
    * this write: a node a commit wrote after the audit's proof that it was absent keeps the count it has. That lock is
    * not [[saveNode]]'s, as the trait says. The eviction comes after the write, as in [[onBlockSave]], for a bad node a
    * block may have read into the cache.
    */
  override def repairNode(nodeHash: NodeHash, nodeEncoded: NodeEncoded, bn: BigInt): RepairOutcome =
    if !StateStorage.hashesTo(nodeHash, nodeEncoded) then RepairOutcome.Rejected
    else
      ReferenceCountedStateStorage.commitLock.synchronized {
        val existing =
          nodeStorage.get(nodeHash).flatMap(raw => scala.util.Try(encoding.storedNodeFromBytes(raw)).toOption)
        if existing.exists(node => StateStorage.hashesTo(nodeHash, node.nodeEncoded.toArray)) then
          RepairOutcome.AlreadyValid
        else
          val references =
            existing.fold(ReferenceCountNodeStorage.RepairedNodeReferences)(
              _.references.max(ReferenceCountNodeStorage.RepairedNodeReferences)
            )
          val repaired = ReferenceCountNodeStorage.StoredNode(ByteString(nodeEncoded), references, bn)
          nodeStorage.put(nodeHash, encoding.storedNodeToBytes(repaired))
          decodedNodes.foreach(_.evict(Seq(nodeHash)))
          RepairOutcome.Written
      }

  /** Under [[ReferenceCountedStateStorage.commitLock]], so no block commits between the first read, `stillReached`'s
    * look from the best root, and the delete. That lock is not [[saveNode]]'s, so the bytes are read again after the
    * look, as the trait says. The eviction comes after the delete, as in [[onBlockSave]].
    */
  override def deleteBadNode(nodeHash: NodeHash, stillReached: () => Boolean): BadEntryDelete =
    def kept: Option[BadEntryDelete] = nodeStorage.get(nodeHash) match
      case None => Some(BadEntryDelete.NoLongerBad)
      case Some(raw) if !NodeFault.provablyBad(nodeHash, StateStorage.unwrapped(raw)) =>
        Some(BadEntryDelete.NoLongerBad)
      case Some(raw) if StateStorage.hashesTo(nodeHash, raw) || StateStorage.wrapsNode(nodeHash, raw) =>
        Some(BadEntryDelete.PassesKeccakElsewhere)
      case _ => None
    ReferenceCountedStateStorage.commitLock.synchronized {
      StateStorage.deleteUnless(kept, stillReached) { () =>
        nodeStorage.update(Seq(nodeHash), Nil)
        decodedNodes.foreach(_.evict(Seq(nodeHash)))
      }
    }

class CachedReferenceCountedStateStorage(
    private val nodeStorage: NodeStorage,
    private val pruningHistory: Int,
    private val lruCache: LruCache[NodeHash, HeapEntry]
) extends StateStorage:

  private val changeLog = new ChangeLog(nodeStorage)

  override def forcePersist(reason: FlushSituation): Boolean =
    reason match
      case GenesisDataLoad | SnapSyncFinalization =>
        CachedReferenceCountedStorage.persistCache(lruCache, nodeStorage, forced = true)

  override def onBlockSave(bn: BigInt, currentBestSavedBlock: BigInt)(updateBestBlocksData: () => Unit): Unit =
    val blockToPrune = bn - pruningHistory
    changeLog.persistChangeLog(bn)
    changeLog.getDeathRowFromStorage(blockToPrune).foreach { deathRow =>
      CachedReferenceCountedStorage.prune(deathRow, lruCache, blockToPrune)
    }
    if CachedReferenceCountedStorage.persistCache(lruCache, nodeStorage) then updateBestBlocksData()
    changeLog.removeBlockMetaData(blockToPrune)

  override def onBlockRollback(bn: BigInt, currentBestSavedBlock: BigInt)(updateBestBlocksData: () => Unit): Unit =
    changeLog.getChangeLogFromStorage(bn).foreach { changeLog =>
      CachedReferenceCountedStorage.rollback(lruCache, nodeStorage, changeLog, bn)
    }
    changeLog.removeBlockMetaData(bn)

  override def getReadOnlyStorage: MptStorage =
    new SerializingMptStorage(ReadOnlyNodeStorage(new NoHistoryCachedReferenceCountedStorage(nodeStorage, lruCache, 0)))

  override def getBackingStorage(bn: BigInt): MptStorage =
    new SerializingMptStorage(new CachedReferenceCountedStorage(nodeStorage, lruCache, changeLog, bn))

  override def saveNode(nodeHash: NodeHash, nodeEncoded: NodeEncoded, bn: BigInt): Unit =
    nodeStorage.put(nodeHash, HeapEntry.toBytes(HeapEntry(nodeEncoded, 1, bn)))

object StateStorage:

  /** Whether `encoded` is the node stored under `hash`. */
  private[storage] def hashesTo(hash: NodeHash, encoded: Array[Byte]): Boolean =
    java.util.Arrays.equals(kec256(encoded), hash.toArray)

  /** What basic pruning's reads make of the wrapper `stored`: the node's bytes, or [[RawNodeRead.Undecodable]] when it
    * does not unwrap, as `ReferenceCountNodeStorage.multiGetRaw` reads it.
    */
  private[storage] def unwrapped(stored: Array[Byte]): RawNodeRead =
    scala.util
      .Try(encoding.storedNodeFromBytes(stored))
      .fold(_ => RawNodeRead.Undecodable, node => RawNodeRead.Bytes(node.nodeEncoded.toArray))

  /** Whether `stored` holds the node stored under `hash`, in a wrapper whose counters may be past reading. Not a whole
    * wrapper a store can use, but those bytes pass keccak, so they are never deleted (the user's rule, 2026-11-01).
    */
  private[storage] def wrapsNode(hash: NodeHash, stored: Array[Byte]): Boolean =
    encoding.nodeBytesInWrapper(stored).exists(hashesTo(hash, _))

  /** The shape of every audit delete (spec 022 decision 15): `kept` reads the entry and says why it stays, if it does.
    * Asked before the look from the root and again after it, since nothing holds off an on-demand write in between.
    */
  private[storage] def deleteUnless(kept: => Option[BadEntryDelete], stillReached: () => Boolean)(
      delete: () => Unit
  ): BadEntryDelete =
    kept.getOrElse {
      if !stillReached() then BadEntryDelete.NotReached
      else
        kept.getOrElse {
          delete()
          BadEntryDelete.Deleted
        }
    }

  /** @param nodesMayBeDeleted
    *   under basic pruning, asked at each prune whether it may delete trie nodes (see [[RefCountTrust]]). The default
    *   says no.
    */
  def apply(
      pruningMode: PruningMode,
      nodeStorage: NodeStorage,
      lruCache: LruCache[NodeHash, HeapEntry],
      nodesMayBeDeleted: () => Boolean = () => false
  ): StateStorage =
    pruningMode match
      case ArchivePruning => new ArchiveStateStorage(nodeStorage)
      case pruning.BasicPruning(history) =>
        new ReferenceCountedStateStorage(nodeStorage, history, nodesMayBeDeleted = nodesMayBeDeleted)
      case pruning.InMemoryPruning(history) => new CachedReferenceCountedStateStorage(nodeStorage, history, lruCache)

  def getReadOnlyStorage(source: EphemDataSource): MptStorage =
    mptStorageFromNodeStorage(new NodeStorage(source))

  def mptStorageFromNodeStorage(storage: NodeStorage): SerializingMptStorage =
    new SerializingMptStorage(new ArchiveNodeStorage(storage))

  def createTestStateStorage(
      source: DataSource,
      pruningMode: PruningMode = ArchivePruning
  ): (StateStorage, NodeStorage, CachedNodeStorage) =
    val testCacheSize = 10000
    val testCacheConfig = new NodeCacheConfig:
      override val maxSize: Long = 10000
      override val maxHoldTime: FiniteDuration = FiniteDuration(10, TimeUnit.MINUTES)
    val nodeStorage = new NodeStorage(source)
    val cachedNodeStorage = new CachedNodeStorage(nodeStorage, MapCache.createTestCache(testCacheSize))

    (
      // A test store holds only what the test wrote, by emission: its counts are exact, so it deletes by them.
      StateStorage(pruningMode, nodeStorage, new LruCache[NodeHash, HeapEntry](testCacheConfig), () => true),
      nodeStorage,
      cachedNodeStorage
    )

  sealed abstract class FlushSituation
  case object GenesisDataLoad extends FlushSituation

  /** SNAP finalization, just before the completion flags are written: the ingested state must be in the data source. */
  case object SnapSyncFinalization extends FlushSituation
