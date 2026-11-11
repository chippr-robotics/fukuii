package com.chipprbots.ethereum.db.storage

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.db.storage.pathstate.StateFault
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingNodeException
import com.chipprbots.ethereum.mpt.MptNode
import com.chipprbots.ethereum.mpt.NibblePath

/** A read-only view of the PARENT state's trie nodes for the BAL prefetch (`BalPrefetcher`), safe to use from many
  * threads while the block executes.
  *
  * It never touches the block's staged storage (`BufferedNodeStorage` is an unsynchronised map that execution writes to
  * after every transaction); it reads the committed nodes straight from the database. A node is content-addressed, so
  * the committed bytes under a hash are the bytes the staged view would return for it, and what the prefetch decodes is
  * what execution would decode.
  *
  * Nodes it reads go into the same [[DecodedNodeCache]] execution reads through, so the execution's walk finds them.
  * The cache is a shared LRU: warming more than it can hold would evict the first nodes before the block reaches them,
  * so the reader stops inserting once it has spent `budgetBytes` (by the cache's own weights) and reports
  * [[exhausted]], at which point the prefetch stops issuing state reads for this block.
  *
  * Why inserting is safe against pruning (task #74, evict-after-delete): inserts go through
  * [[DecodedNodeCache.putIfCurrent]], which drops a node read before an eviction that has since run, so a read that
  * raced a prune's delete cannot leave the pruned node cached.
  */
final class PrefetchNodeReader private (
    protected val underlying: MptStorage,
    cache: Option[DecodedNodeCache],
    budgetBytes: Long,
    onLoaded: ByteString => Unit,
    spent: AtomicLong,
    loaded: AtomicLong,
    faulted: Option[AtomicBoolean]
) extends MptStorageDecorator:

  def this(
      underlying: MptStorage,
      cache: Option[DecodedNodeCache],
      budgetBytes: Long,
      onLoaded: ByteString => Unit = _ => ()
  ) = this(underlying, cache, budgetBytes, onLoaded, new AtomicLong, new AtomicLong, None)

  /** One trie's view of the parent state, on the SAME budget, the same counts and the same stop: a block's prefetch is
    * one budget, however many tries it reads, and a fault met in one trie ends it in all of them.
    */
  override protected def decorate(scoped: MptStorage): MptStorage =
    new PrefetchNodeReader(scoped, cache, budgetBytes, onLoaded, spent, loaded, faulted)

  /** True when this block's prefetch should read no more: the cache's share for it is used up (a reader with no cache
    * never uses it up), or, for a reader that stops on a fault, a read met one.
    */
  def exhausted: Boolean = faulted.exists(_.get) || (cache.isDefined && spent.get >= budgetBytes)

  /** Nodes this reader read from the database (cache hits are not counted). */
  def nodesLoaded: Long = loaded.get

  override protected def read(nodeId: Array[Byte], path: NibblePath | Null): MptNode =
    val key = ByteString.fromArrayUnsafe(nodeId)
    val hit = cache.flatMap(c => Option(c.get(key)))
    hit.getOrElse {
      val generation = cache.fold(0L)(_.generation) // before the read: see DecodedNodeCache.putIfCurrent
      val node =
        try super.read(nodeId, path)
        catch
          case fault: (MissingNodeException | StateFault) =>
            // The fault is the caller's to count, as before. A reader that stops on one reads nothing after it.
            faulted.foreach(_.set(true))
            throw fault
      loaded.incrementAndGet()
      cache.foreach { c =>
        if spent.addAndGet(DecodedNodeCache.weigh(node)) <= budgetBytes then
          val compact = key.compact
          c.putIfCurrent(compact, node, generation)
          onLoaded(compact)
      }
      node
    }

  private def readOnly: Nothing = throw new UnsupportedOperationException("the prefetch reader is read-only")

  override protected def update(newRoot: Option[MptNode], removals: MptStorageDecorator.Removals): Option[MptNode] =
    readOnly

  override def resetOwner(owner: ByteString): Unit = readOnly

  override def storeRawNodes(nodes: Seq[(ByteString, Array[Byte])]): Unit = readOnly

  override def persist(): Unit = ()

object PrefetchNodeReader:

  /** A reader that ends its block's prefetch at the first node it cannot have: a missing or corrupt node, or a
    * [[com.chipprbots.ethereum.db.storage.pathstate.StateFault]] of the storage it reads. For a parent state read
    * through a view (spec 018 data-model 6.3): once a view is stale every further read of it is wasted, and a prefetch
    * only warms caches. The node that faulted was never put in the cache: only a node its storage returned is.
    */
  def stoppingOnFault(
      underlying: MptStorage,
      cache: Option[DecodedNodeCache],
      budgetBytes: Long,
      onLoaded: ByteString => Unit
  ): PrefetchNodeReader =
    new PrefetchNodeReader(
      underlying,
      cache,
      budgetBytes,
      onLoaded,
      new AtomicLong,
      new AtomicLong,
      Some(new AtomicBoolean)
    )
