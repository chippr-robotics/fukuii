package com.chipprbots.ethereum.testing

import org.apache.pekko.util.ByteString

import scala.collection.mutable

import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.mpt._

/** Simple in-memory test storage for MPT nodes
  *
  * Provides a minimal MptStorage implementation for unit tests. This implementation stores nodes in memory without any
  * persistence.
  */
class TestMptStorage extends MptStorage {
  private val nodes = mutable.Map[ByteString, MptNode]()

  override def get(key: Array[Byte]): MptNode = {
    val keyStr = ByteString(key)
    nodes
      .get(keyStr)
      .getOrElse {
        throw new MerklePatriciaTrie.MissingNodeException(keyStr)
      }
  }

  def putNode(node: MptNode): Unit = {
    val hash = ByteString(node.hash)
    nodes(hash) = node
  }

  override def updateNodesInStorage(
      newRoot: Option[MptNode],
      toRemove: Seq[MptNode]
  ): Option[MptNode] = {
    newRoot.foreach { root =>
      storeNodeRecursively(root)
    }
    newRoot
  }

  private def storeNodeRecursively(node: MptNode): Unit =
    node match {
      case leaf: LeafNode =>
        putNode(leaf)
      case ext: ExtensionNode =>
        putNode(ext)
        storeNodeRecursively(ext.next)
      case branch: BranchNode =>
        putNode(branch)
        branch.children.foreach(storeNodeRecursively)
      case hash: HashNode =>
        putNode(hash)
      case NullNode =>
      // Nothing to store
    }

  override def persist(): Unit = {
    // No-op for in-memory storage
  }

  override def storeRawNodes(rawNodes: Seq[(ByteString, Array[Byte])]): Unit =
    rawNodes.foreach { case (hash, encoded) =>
      val node = MptTraversals.decodeNode(encoded).withCachedHash(hash.toArray).withCachedRlpEncoded(encoded)
      nodes(hash) = node
    }
}
