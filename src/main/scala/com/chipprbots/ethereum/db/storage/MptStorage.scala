package com.chipprbots.ethereum.db.storage

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.db.storage.NodeStorage.NodeEncoded
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingRootNodeException
import com.chipprbots.ethereum.mpt.MptNode
import com.chipprbots.ethereum.mpt.MptTraversals
import com.chipprbots.ethereum.mpt.NodesKeyValueStorage

trait MptStorage {
  def get(nodeId: Array[Byte]): MptNode
  def updateNodesInStorage(newRoot: Option[MptNode], toRemove: Seq[MptNode]): Option[MptNode]
  def persist(): Unit

  /** Store pre-encoded trie nodes directly by hash, bypassing trie collapse.
    * Used by state healing to insert individual nodes fetched from peers.
    */
  def storeRawNodes(nodes: Seq[(ByteString, Array[Byte])]): Unit =
    throw new UnsupportedOperationException("Raw node storage not supported by this implementation")
}

class SerializingMptStorage(storage: NodesKeyValueStorage) extends MptStorage {
  override def get(nodeId: Array[Byte]): MptNode = {
    val key = ByteString(nodeId)
    storage
      .get(key)
      .map(nodeEncoded => MptStorage.decodeNode(nodeEncoded, nodeId))
      .getOrElse(throw new MissingRootNodeException(ByteString(nodeId)))
  }

  override def updateNodesInStorage(newRoot: Option[MptNode], toRemove: Seq[MptNode]): Option[MptNode] = {
    val (collapsed, toUpdate) = MptStorage.collapseNode(newRoot)
    val toBeRemoved = toRemove.map(n => ByteString(n.hash))
    storage.update(toBeRemoved, toUpdate)
    collapsed
  }

  override def persist(): Unit =
    storage.persist()

  override def storeRawNodes(nodes: Seq[(ByteString, Array[Byte])]): Unit =
    storage.update(Seq.empty, nodes)
}

object MptStorage {
  def collapseNode(node: Option[MptNode]): (Option[MptNode], List[(ByteString, Array[Byte])]) =
    node.fold[(Option[MptNode], List[(ByteString, Array[Byte])])]((None, List.empty)) { n =>
      val (hashNode, newNodes) = MptTraversals.collapseTrie(n)
      (Some(hashNode), newNodes)
    }

  def decodeNode(nodeEncoded: NodeEncoded, nodeId: Array[Byte]): MptNode =
    MptTraversals.decodeNode(nodeEncoded).withCachedHash(nodeId).withCachedRlpEncoded(nodeEncoded)
}
