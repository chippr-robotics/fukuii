package com.chipprbots.ethereum.db.storage

import java.util.concurrent.atomic.AtomicLong

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.mpt.MptNode

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
  * Why inserting is safe against pruning (task #74, evict-after-delete): nodes are deleted and evicted only on the
  * import thread, in `onBlockSave` / `onBlockRollback`, after the block that triggered them has executed; the prefetch
  * of a block is cancelled and drained before its execution returns, so no prefetch read is in flight when a delete and
  * its eviction run, and none can re-insert a node the delete just removed.
  */
final class PrefetchNodeReader(
    underlying: MptStorage,
    cache: Option[DecodedNodeCache],
    budgetBytes: Long,
    onLoaded: ByteString => Unit = _ => ()
) extends MptStorage:

  private val spent = new AtomicLong
  private val loaded = new AtomicLong

  /** True when the cache's share for this block's prefetch is used up. A reader with no cache never is. */
  def exhausted: Boolean = cache.isDefined && spent.get >= budgetBytes

  /** Nodes this reader read from the database (cache hits are not counted). */
  def nodesLoaded: Long = loaded.get

  override def get(nodeId: Array[Byte]): MptNode =
    val key = ByteString.fromArrayUnsafe(nodeId)
    val hit = cache.flatMap(c => Option(c.get(key)))
    hit.getOrElse {
      val node = underlying.get(nodeId)
      loaded.incrementAndGet()
      cache.foreach { c =>
        if spent.addAndGet(DecodedNodeCache.weigh(node)) <= budgetBytes then
          val compact = key.compact
          c.put(compact, node)
          onLoaded(compact)
      }
      node
    }

  override def updateNodesInStorage(newRoot: Option[MptNode], toRemove: Seq[MptNode]): Option[MptNode] =
    throw new UnsupportedOperationException("the prefetch reader is read-only")

  override def persist(): Unit = ()
