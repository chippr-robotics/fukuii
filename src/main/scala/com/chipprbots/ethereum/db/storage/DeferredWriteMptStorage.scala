package com.chipprbots.ethereum.db.storage

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.mpt.MptNode

/** An MptStorage wrapper that defers all collapse and write operations for bulk trie insertion.
  *
  * During SNAP sync account download, each MerklePatriciaTrie.put() normally calls
  * updateNodesInStorage() which collapses the trie (RLP-encodes + Keccak-256 hashes every node on
  * the modified path) and writes to RocksDB. This is extremely expensive — O(log n) encoding,
  * hashing, and DB I/O per account.
  *
  * DeferredWriteMptStorage makes updateNodesInStorage() a no-op, returning the full in-memory node
  * so subsequent puts traverse in-memory without any DB I/O. When ready to persist, call flush() to
  * collapse and write everything in one batch.
  */
class DeferredWriteMptStorage(val backing: MptStorage) extends MptStorage {

  // Track the latest root node passed through updateNodesInStorage
  private var currentRoot: Option[MptNode] = None

  override def get(nodeId: Array[Byte]): MptNode = backing.get(nodeId)

  override def updateNodesInStorage(
      newRoot: Option[MptNode],
      toRemove: Seq[MptNode]
  ): Option[MptNode] = {
    currentRoot = newRoot
    newRoot // Return FULL in-memory node — skip collapse/encode/hash/write
  }

  override def persist(): Unit = () // No-op during bulk insertion

  override def storeRawNodes(nodes: Seq[(ByteString, Array[Byte])]): Unit =
    backing.storeRawNodes(nodes)

  /** Flush all deferred trie nodes to backing storage in one batch.
    *
    * Calls backing.updateNodesInStorage() which triggers collapseNode() on the entire in-memory
    * tree, encoding/hashing all nodes at once, then batch-writing to RocksDB.
    *
    * @return
    *   Root hash if nodes were flushed, None if nothing to flush
    */
  def flush(): Option[Array[Byte]] =
    currentRoot match {
      case Some(root) =>
        val collapsed = backing.updateNodesInStorage(Some(root), Nil)
        backing.persist()
        currentRoot = None
        collapsed.map(_.hash)
      case None => None
    }
}
