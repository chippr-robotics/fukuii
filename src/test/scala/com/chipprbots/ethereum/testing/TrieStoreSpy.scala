package com.chipprbots.ethereum.testing

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.mpt.MptNode

/** A trie store that counts what is written through it (spec 022 T043, T050a). What makes the invariant marker hold on
  * the Path scheme under `pruning.mode = inmemory` is that a Path write never reaches this store, which is the node
  * cache under that mode. `seed` does not count.
  */
class TrieStoreSpy extends TestMptStorage:
  private val written = new java.util.concurrent.atomic.AtomicInteger(0)
  def writes: Int = written.get()
  def seed(nodes: Seq[(ByteString, Array[Byte])]): Unit = super.storeRawNodes(nodes)
  override def storeRawNodes(rawNodes: Seq[(ByteString, Array[Byte])]): Unit =
    written.incrementAndGet()
    super.storeRawNodes(rawNodes)
  override def updateNodesInStorage(newRoot: Option[MptNode], toRemove: Seq[MptNode]): Option[MptNode] =
    written.incrementAndGet()
    super.updateNodesInStorage(newRoot, toRemove)
