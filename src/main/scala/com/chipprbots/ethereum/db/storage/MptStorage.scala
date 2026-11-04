package com.chipprbots.ethereum.db.storage

import org.apache.pekko.util.ByteString

import scala.util.control.NonFatal

import com.chipprbots.ethereum.db.dataSource.RocksDbDataSource.RocksDbDataSourceClosedException
import com.chipprbots.ethereum.db.dataSource.RocksDbDataSource.RocksDbDataSourceException
import com.chipprbots.ethereum.db.storage.NodeStorage.NodeEncoded
import com.chipprbots.ethereum.mpt.HashNode
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.CorruptNodeException
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingNodeException
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingRootNodeException
import com.chipprbots.ethereum.mpt.MptNode
import com.chipprbots.ethereum.mpt.MptTraversals
import com.chipprbots.ethereum.mpt.NodesKeyValueStorage
import com.chipprbots.ethereum.mpt.NullNode
import com.chipprbots.ethereum.utils.Logger

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

  /** One [[RawNodeRead]] per hash: the node's RLP as stored, or why not. Unlike [[multiGetNodes]] one entry that cannot
    * be read fails that entry and not the batch, nothing is decoded, and the block cache is not filled. The background
    * audit's read (spec 022 T053). The default is for the stores it never reads.
    */
  def rawNodes(hashes: Seq[ByteString]): Seq[RawNodeRead] =
    hashes.map(hash => RawNodeRead.decoding(RawNodeRead.Bytes(get(hash.toArray).encode)))

  /** Store pre-encoded trie nodes directly by hash, bypassing trie collapse. Used by state healing to insert individual
    * nodes fetched from peers.
    *
    * '''One atomic write per call''' (spec 022 D3): healing passes complete chains (a node with every ancestor its
    * subtree completes) and relies on all of them landing together or none. [[SerializingMptStorage]] makes one
    * `NodesKeyValueStorage.update` call, which under archive pruning (`ArchiveNodeStorage`) and basic pruning
    * (`ReferenceCountNodeStorage`, `FastSyncNodeStorage`) is one `NodeStorage.update`, i.e. one data-source write
    * batch. Under in-memory pruning (`CachedReferenceCountedStorage`) the nodes go to the node cache, not to disk; the
    * cache reaches disk in one batch when persisted, or node by node as entries are evicted.
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

  /** The node stored under `nodeId`. An entry that is not that node (spec 022 T062) throws [[CorruptNodeException]], a
    * missing node, instead of whatever decoding it threw: what is wrong is this store, never the block being executed.
    * The read and the unwrap of the store's own format (basic pruning's reference-count wrapper) are inside the check;
    * a failing database is not, and goes through as it is, since a disk error called corrupt could end as a delete.
    */
  override def get(nodeId: Array[Byte]): MptNode =
    val key = ByteString(nodeId)
    try
      storage.get(key) match
        case None => throw new MissingRootNodeException(key)
        case Some(nodeEncoded) =>
          MptStorage.decodeNode(nodeEncoded, nodeId) match
            // A stored node is a list. The one bare value stored under its own hash is the empty trie's root (0x80
            // under keccak(0x80)), so these two shapes are checked against the key, and only they are: good data never
            // reaches the hash on this hot path. Junk that parses as a list is not caught here.
            case NullNode | _: HashNode if !StateStorage.hashesTo(key, nodeEncoded) =>
              throw SerializingMptStorage.corrupt(key, "decodes to a bare value that does not hash to its key")
            case node => node
    catch
      case e: MissingNodeException             => throw e
      case e: RocksDbDataSourceException       => throw e
      case e: RocksDbDataSourceClosedException => throw e
      case NonFatal(e) => throw SerializingMptStorage.corrupt(key, s"${e.getClass.getSimpleName}: ${e.getMessage}")

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

  override def rawNodes(hashes: Seq[ByteString]): Seq[RawNodeRead] =
    if hashes.isEmpty then Seq.empty else storage.multiGetRaw(hashes)

  override def storeRawNodes(nodes: Seq[(ByteString, Array[Byte])]): Unit =
    storage.update(Seq.empty, nodes)

object SerializingMptStorage extends Logger:

  /** How many corrupt hashes are remembered as already logged. A blocked import retries every 30 s and RPC can read the
    * same account at any rate, so each hash is logged once; past this many the least recently met is forgotten, and at
    * worst logs again.
    */
  private[storage] val MaxLoggedCorrupt: Int = 1024

  private val loggedCorrupt = new java.util.LinkedHashMap[ByteString, java.lang.Boolean](16, 0.75f, true):
    override def removeEldestEntry(eldest: java.util.Map.Entry[ByteString, java.lang.Boolean]): Boolean =
      size() > MaxLoggedCorrupt

  /** True the first time `hash` is met, until it is forgotten. */
  private[storage] def firstReport(hash: ByteString): Boolean =
    loggedCorrupt.synchronized(loggedCorrupt.put(hash, java.lang.Boolean.TRUE) == null)

  private def corrupt(hash: ByteString, detail: String): CorruptNodeException =
    val error = new CorruptNodeException(hash, detail)
    if firstReport(hash) then
      log.warn(
        s"[CORRUPT-NODE] ${error.getMessage}: reads of it fail as a missing node until it is deleted or replaced"
      )
    error

object MptStorage:
  def collapseNode(node: Option[MptNode]): (Option[MptNode], Seq[(ByteString, Array[Byte])]) =
    node.fold[(Option[MptNode], Seq[(ByteString, Array[Byte])])]((None, Seq.empty)) { n =>
      val (hashNode, newNodes) = MptTraversals.collapseTrie(n)
      (Some(hashNode), newNodes)
    }

  def decodeNode(nodeEncoded: NodeEncoded, nodeId: Array[Byte]): MptNode =
    MptTraversals.decodeNode(nodeEncoded).withCachedHash(nodeId).withCachedRlpEncoded(nodeEncoded)
