package com.chipprbots.ethereum.db.storage

import java.util.concurrent.TimeUnit

import scala.concurrent.duration.FiniteDuration

import com.chipprbots.ethereum.blockchain.sync.codec.MptNodeCodecs.*
import com.chipprbots.ethereum.db.cache.LruCache
import com.chipprbots.ethereum.db.cache.MapCache
import com.chipprbots.ethereum.db.dataSource.DataSource
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.NodeStorage.NodeEncoded
import com.chipprbots.ethereum.db.storage.NodeStorage.NodeHash
import com.chipprbots.ethereum.db.storage.StateStorage.FlushSituation
import com.chipprbots.ethereum.db.storage.StateStorage.GenesisDataLoad
import com.chipprbots.ethereum.db.storage.pruning.ArchivePruning
import com.chipprbots.ethereum.db.storage.pruning.PruningMode
import com.chipprbots.ethereum.mpt.MptNode
import com.chipprbots.ethereum.utils.NodeCacheConfig

// scalastyle:off
trait StateStorage:
  def getBackingStorage(bn: BigInt): MptStorage
  def getReadOnlyStorage: MptStorage

  def onBlockSave(bn: BigInt, currentBestSavedBlock: BigInt)(updateBestBlocksData: () => Unit): Unit
  def onBlockRollback(bn: BigInt, currentBestSavedBlock: BigInt)(updateBestBlocksData: () => Unit): Unit

  def saveNode(nodeHash: NodeHash, nodeEncoded: NodeEncoded, bn: BigInt): Unit
  def getNode(nodeHash: NodeHash): Option[MptNode]
  def forcePersist(reason: FlushSituation): Boolean

  /** Undo the state application of block `bn`, which was executed but never adopted (a failed or interrupted batch).
    *
    * Reference counts, death rows and snapshots are applied per block as it executes, while the best block only
    * advances when the batch is adopted; re-executing the block without undoing it first applies its node changes twice
    * and leaves live nodes at refs == 0, which a later prune deletes. Only meaningful for reference-counted pruning; a
    * no-op elsewhere. Callers must guarantee `bn > best` (no canonical state lives at that number).
    */
  def rollbackUnadoptedBlock(bn: BigInt, currentBestSavedBlock: BigInt): Unit = ()

  /** Startup sweep: undo every block in (best, best + window] that still holds applied updates, highest first. Returns
    * how many blocks were rolled back. A no-op (0) unless reference-counted pruning.
    */
  def rollbackUnadoptedAbove(currentBestSavedBlock: BigInt, window: Int): Int = 0

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

  override def rollbackUnadoptedBlock(bn: BigInt, currentBestSavedBlock: BigInt): Unit =
    if bn > currentBestSavedBlock then
      val removed = ReferenceCountNodeStorage.rollbackReporting(bn, nodeStorage, inMemory = true)
      // A rolled-back node that stayed cached would be served instead of raising MissingNodeException.
      decodedNodes.foreach(_.evict(removed))

  override def rollbackUnadoptedAbove(currentBestSavedBlock: BigInt, window: Int): Int =
    (window to 1 by -1).foldLeft(0) { (count, offset) =>
      val bn = currentBestSavedBlock + offset
      if ReferenceCountNodeStorage.hasSnapshots(bn, nodeStorage) then
        val removed = ReferenceCountNodeStorage.rollbackReporting(bn, nodeStorage, inMemory = true)
        decodedNodes.foreach(_.evict(removed))
        count + 1
      else count
    }

  override def getBackingStorage(bn: BigInt): MptStorage =
    new SerializingMptStorage(new ReferenceCountNodeStorage(nodeStorage, bn), decodedNodes)

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
