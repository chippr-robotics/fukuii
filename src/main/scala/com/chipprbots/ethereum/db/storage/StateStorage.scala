package com.chipprbots.ethereum.db.storage

import java.util.concurrent.TimeUnit

import org.apache.pekko.util.ByteString

import scala.annotation.unused
import scala.concurrent.duration.FiniteDuration

import com.chipprbots.ethereum.blockchain.sync.codec.MptNodeCodecs.*
import com.chipprbots.ethereum.db.cache.LruCache
import com.chipprbots.ethereum.db.cache.MapCache
import com.chipprbots.ethereum.db.dataSource.DataSource
import com.chipprbots.ethereum.db.dataSource.DataSourceBatchUpdate
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.NodeStorage.NodeEncoded
import com.chipprbots.ethereum.db.storage.NodeStorage.NodeHash
import com.chipprbots.ethereum.db.storage.StateStorage.FlushSituation
import com.chipprbots.ethereum.db.storage.StateStorage.GenesisDataLoad
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

  /** Drop the buffered writes and evict what execution read back from them out of the decoded-node cache. */
  def discard(): Unit

object StagedBlockState:
  /** Write-through, for the modes that never skewed (archive, in-memory pruning): nothing to hold back. */
  def direct(backing: MptStorage): StagedBlockState = new StagedBlockState:
    override def storage: MptStorage = backing
    override def pending: Option[DataSourceBatchUpdate] = None
    override def discard(): Unit = ()

// scalastyle:off
trait StateStorage:
  def getBackingStorage(bn: BigInt): MptStorage

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
  def getNode(nodeHash: NodeHash): Option[MptNode]
  def forcePersist(reason: FlushSituation): Boolean

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

  override def getNode(nodeHash: NodeHash): Option[MptNode] =
    nodeStorage.get(nodeHash).map(_.toMptNode)

class ReferenceCountedStateStorage(
    private val nodeStorage: NodeStorage,
    private val pruningHistory: BigInt,
    decodedNodeCacheBytes: Long = com.chipprbots.ethereum.utils.StateReadCacheConfig.decodedNodeCacheBytes
) extends StateStorage:
  override def forcePersist(reason: FlushSituation): Boolean = true

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
    val target = bn.min(currentBestSavedBlock + 1) - pruningHistory
    val from = lastPruned match
      case Some(done) if done < target => done + 1
      case _                           => target
    var blockToPrune = from
    while blockToPrune <= target do
      val removed =
        ReferenceCountNodeStorage.pruneReporting(
          blockToPrune,
          nodeStorage,
          inMemory = blockToPrune > currentBestSavedBlock
        )
      // Evict AFTER the delete. Safe because blocks are imported, and state pruned, on one thread: no reader can
      // re-insert a just-pruned node between the delete and this eviction. A concurrent block executor would need
      // evict-before-delete plus a re-check on insert (a hit on a node the database no longer holds).
      decodedNodes.foreach(_.evict(removed))
      blockToPrune += 1
    if lastPruned.forall(_ < target) then lastPruned = Some(target)
    updateBestBlocksData()

  override def onBlockRollback(bn: BigInt, currentBestSavedBlock: BigInt)(updateBestBlocksData: () => Unit): Unit =
    val removed = ReferenceCountNodeStorage.rollbackReporting(bn, nodeStorage, inMemory = bn > currentBestSavedBlock)
    // Same single-import-thread assumption as in onBlockSave: evict after the rollback has deleted.
    decodedNodes.foreach(_.evict(removed))
    updateBestBlocksData()

  override def getBackingStorage(bn: BigInt): MptStorage =
    new SerializingMptStorage(new ReferenceCountNodeStorage(nodeStorage, bn), decodedNodes)

  override def onBlocksAbandoned(blocks: Seq[(BigInt, ByteString)]): Unit =
    blocks.foreach { case (bn, hash) => ReferenceCountNodeStorage.undoBlock(bn, hash, nodeStorage, inMemory = false) }

  override def onBlocksReadopted(blocks: Seq[(BigInt, ByteString)]): Unit =
    blocks.foreach { case (bn, hash) => ReferenceCountNodeStorage.redoBlock(bn, hash, nodeStorage, inMemory = false) }

  override def stageBlock(bn: BigInt): StagedBlockState =
    val buffered = new BufferedNodeStorage(nodeStorage)
    val counted = new ReferenceCountNodeStorage(buffered, bn)
    new StagedBlockState:
      override val storage: MptStorage = new SerializingMptStorage(counted, decodedNodes)
      override def pending: Option[DataSourceBatchUpdate] = Option.when(!buffered.isEmpty)(buffered.pending)
      override def pendingWith(blockNumber: BigInt, blockHash: ByteString): Option[DataSourceBatchUpdate] =
        val writes = ReferenceCountNodeStorage.abandonRecordWrites(blockNumber, blockHash, counted.netDeltas, buffered)
        if writes.nonEmpty then buffered.update(Nil, writes)
        pending
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

  override def getNode(nodeHash: NodeHash): Option[MptNode] =
    new FastSyncNodeStorage(nodeStorage, 0).get(nodeHash).map(_.toMptNode)

class CachedReferenceCountedStateStorage(
    private val nodeStorage: NodeStorage,
    private val pruningHistory: Int,
    private val lruCache: LruCache[NodeHash, HeapEntry]
) extends StateStorage:

  private val changeLog = new ChangeLog(nodeStorage)

  override def forcePersist(reason: FlushSituation): Boolean =
    reason match
      case GenesisDataLoad => CachedReferenceCountedStorage.persistCache(lruCache, nodeStorage, forced = true)

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

  override def getNode(nodeHash: NodeHash): Option[MptNode] =
    lruCache
      .get(nodeHash)
      .map(_.nodeEncoded.toMptNode)
      .orElse(
        nodeStorage
          .get(nodeHash)
          .map(enc => HeapEntry.fromBytes(enc).nodeEncoded.toMptNode)
      )

object StateStorage:
  def apply(
      pruningMode: PruningMode,
      nodeStorage: NodeStorage,
      lruCache: LruCache[NodeHash, HeapEntry]
  ): StateStorage =
    pruningMode match
      case ArchivePruning                   => new ArchiveStateStorage(nodeStorage)
      case pruning.BasicPruning(history)    => new ReferenceCountedStateStorage(nodeStorage, history)
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
      StateStorage(pruningMode, nodeStorage, new LruCache[NodeHash, HeapEntry](testCacheConfig)),
      nodeStorage,
      cachedNodeStorage
    )

  sealed abstract class FlushSituation
  case object GenesisDataLoad extends FlushSituation
