package com.chipprbots.ethereum.testing

import org.apache.pekko.util.ByteString

import scala.collection.mutable

import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.mpt.*

/** Simple in-memory test storage for MPT nodes
  *
  * Provides a minimal MptStorage implementation for unit tests. This implementation stores nodes in memory without any
  * persistence.
  */
class TestMptStorage extends MptStorage:
  private val nodes = mutable.Map[ByteString, MptNode]()

  override def get(key: Array[Byte]): MptNode =
    val keyStr = ByteString(key)
    nodes
      .get(keyStr)
      .getOrElse {
        throw new MerklePatriciaTrie.MissingNodeException(keyStr)
      }

  def putNode(node: MptNode): Unit =
    val hash = ByteString(node.hash)
    nodes(hash) = node

  /** As [[com.chipprbots.ethereum.db.storage.SerializingMptStorage]] does it: the new nodes are stored, each decoded
    * with its hash and encoding cached, and the root comes back collapsed (B-206). Returning the new root as built, as
    * this used to, handed the trie back a tree of nodes with no cached hash, so each later put hashed the whole trie
    * again: StackTrieSpec's 10,000-key reference trie took 2 minutes. Removed nodes stay, as nothing here reads them.
    */
  override def updateNodesInStorage(
      newRoot: Option[MptNode],
      toRemove: Seq[MptNode]
  ): Option[MptNode] =
    val (collapsed, newNodes) = com.chipprbots.ethereum.db.storage.MptStorage.collapseNode(newRoot)
    storeRawNodes(newNodes)
    collapsed

  override def persist(): Unit = {
    // No-op for in-memory storage
  }

  override def storeRawNodes(rawNodes: Seq[(ByteString, Array[Byte])]): Unit =
    rawNodes.foreach { case (hash, encoded) =>
      val node = MptTraversals.decodeNode(encoded).withCachedHash(hash.toArray).withCachedRlpEncoded(encoded)
      nodes(hash) = node
    }
