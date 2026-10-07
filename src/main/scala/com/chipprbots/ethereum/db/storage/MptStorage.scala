package com.chipprbots.ethereum.db.storage

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.db.storage.NodeStorage.NodeEncoded
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingRootNodeException
import com.chipprbots.ethereum.mpt.MptNode
import com.chipprbots.ethereum.mpt.MptTraversals
import com.chipprbots.ethereum.mpt.NodesKeyValueStorage

trait MptStorage:
  def get(nodeId: Array[Byte]): MptNode
  def updateNodesInStorage(newRoot: Option[MptNode], toRemove: Seq[MptNode]): Option[MptNode]
  def persist(): Unit

  /** Batch point-lookup: returns one `Option[MptNode]` per hash. `None` means the node is not locally held (a healing
    * target). The default delegates to sequential [[get]] calls; [[SerializingMptStorage]] overrides via
    * [[NodesKeyValueStorage.multiGet]] which in turn hits RocksDB `multiGetAsList` when backed by
    * [[RocksDbDataSource]].
    */
  def multiGetNodes(hashes: Seq[Array[Byte]]): Seq[Option[MptNode]] =
    hashes.map(h => scala.util.Try(get(h)).toOption)

  /** Store pre-encoded trie nodes directly by hash, bypassing trie collapse. Used by state healing to insert individual
    * nodes fetched from peers.
    */
  def storeRawNodes(nodes: Seq[(ByteString, Array[Byte])]): Unit =
    throw new UnsupportedOperationException("Raw node storage not supported by this implementation")

class SerializingMptStorage(storage: NodesKeyValueStorage, val executionCache: Option[DecodedNodeCache] = None)
    extends MptStorage:

  /** This storage with its state storage's decoded-node cache in front, for BLOCK EXECUTION only. Everything else
    * (SNAP, healing, recovery, presence checks) keeps using the storage itself, which never consults the cache.
    */
  def cachedForExecution: MptStorage =
    executionCache.fold[MptStorage](this)(cache => new CachingMptStorage(this, cache))

  override def get(nodeId: Array[Byte]): MptNode =
    val key = ByteString(nodeId)
    storage
      .get(key)
      .map(nodeEncoded => MptStorage.decodeNode(nodeEncoded, nodeId))
      .getOrElse(throw new MissingRootNodeException(ByteString(nodeId)))

  override def updateNodesInStorage(newRoot: Option[MptNode], toRemove: Seq[MptNode]): Option[MptNode] =
    val (collapsed, toUpdate) = MptStorage.collapseNode(newRoot)
    val toBeRemoved = toRemove.map(n => ByteString(n.hash))
    storage.update(toBeRemoved, toUpdate)
    collapsed

  override def persist(): Unit =
    storage.persist()

  override def multiGetNodes(hashes: Seq[Array[Byte]]): Seq[Option[MptNode]] =
    if hashes.isEmpty then return Seq.empty
    storage
      .multiGet(hashes.map(ByteString(_)))
      .zip(hashes)
      .map { case (rawOpt, hash) => rawOpt.map(enc => MptStorage.decodeNode(enc, hash)) }

  override def storeRawNodes(nodes: Seq[(ByteString, Array[Byte])]): Unit =
    storage.update(Seq.empty, nodes)

object MptStorage:
  def collapseNode(node: Option[MptNode]): (Option[MptNode], Seq[(ByteString, Array[Byte])]) =
    node.fold[(Option[MptNode], Seq[(ByteString, Array[Byte])])]((None, Seq.empty)) { n =>
      val (hashNode, newNodes) = MptTraversals.collapseTrie(n)
      (Some(hashNode), newNodes)
    }

  def decodeNode(nodeEncoded: NodeEncoded, nodeId: Array[Byte]): MptNode =
    MptTraversals.decodeNode(nodeEncoded).withCachedHash(nodeId).withCachedRlpEncoded(nodeEncoded)
