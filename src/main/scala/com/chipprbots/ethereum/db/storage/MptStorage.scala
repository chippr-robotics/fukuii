package com.chipprbots.ethereum.db.storage

import org.apache.pekko.util.ByteString

import scala.util.control.NonFatal

import com.chipprbots.ethereum.db.dataSource.RocksDbDataSource.RocksDbDataSourceClosedException
import com.chipprbots.ethereum.db.dataSource.RocksDbDataSource.RocksDbDataSourceException
import com.chipprbots.ethereum.db.storage.NodeStorage.NodeEncoded
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.CorruptNodeException
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingNodeException
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingRootNodeException
import com.chipprbots.ethereum.mpt.MptNode
import com.chipprbots.ethereum.mpt.MptTraversals
import com.chipprbots.ethereum.mpt.NodesKeyValueStorage
import com.chipprbots.ethereum.utils.Logger

trait MptStorage:
  def get(nodeId: Array[Byte]): MptNode
  def updateNodesInStorage(newRoot: Option[MptNode], toRemove: Seq[MptNode]): Option[MptNode]
  def persist(): Unit

  /** Batch point-lookup: returns one `Option[MptNode]` per hash. `None` means the node is not locally held (a healing
    * target). The default runs [[get]] per hash and reads every failure as `None`: a corrupt entry, and a failing read,
    * look absent there. [[SerializingMptStorage]] overrides it with one batched raw read
    * ([[NodesKeyValueStorage.multiGetRaw]]: RocksDB's `multiGetAsList`, without filling the block cache) and throws
    * [[CorruptNodeException]] for an entry that is not the node its hash names (H3), naming its key, failing the batch.
    * A caller that must tell a corrupt entry from an absent one, without failing the batch, reads [[rawNodes]] and
    * judges each entry with `NodeFault`.
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

  /** The node store this one serializes onto, for tests that look at what an instance retains. */
  private[storage] def backing: NodesKeyValueStorage = storage

  /** This storage with its state storage's decoded-node cache in front, for BLOCK EXECUTION only. Everything else
    * (SNAP, healing, recovery, presence checks) keeps using the storage itself, which never consults the cache.
    */
  def cachedForExecution: MptStorage =
    executionCache.fold[MptStorage](this)(cache => new CachingMptStorage(this, cache))

  /** The node stored under `nodeId`. An entry that is not that node (spec 022 T062) throws [[CorruptNodeException]], a
    * missing node, instead of whatever decoding it threw: what is wrong is this store, never the block being executed.
    * The read and the unwrap of the store's own format (basic pruning's reference-count wrapper) are inside the check;
    * a failing database is not, and goes through as it is, since a disk error called corrupt could end as a delete.
    *
    * Keccak first (H3), judged by [[NodeFault]] as the walks and the deletes judge it: bytes that do not hash to their
    * key are never decoded. Only a junk value that is no list was caught before; a list under the wrong hash decoded,
    * and execution read a wrong account or slot from it, so an honest block failed its state root and its peer was
    * blamed. Every read that reaches the store pays for one hash: block execution reads through the decoded-node cache
    * ([[cachedForExecution]]), so only its misses do.
    */
  override def get(nodeId: Array[Byte]): MptNode =
    val key = ByteString(nodeId)
    try
      storage.get(key) match
        case None => throw new MissingRootNodeException(key)
        case Some(nodeEncoded) =>
          if NodeFault.provablyBad(key, RawNodeRead.Bytes(nodeEncoded)) then
            throw SerializingMptStorage.corrupt(key, NodeFault.BadKeccak.describe)
          // Bytes that hash to their key and do not decode throw here, a corrupt node with what decoding threw.
          MptStorage.decodeNode(nodeEncoded, nodeId)
    catch
      case e: MissingNodeException             => throw e
      case e: RocksDbDataSourceException       => throw e
      case e: RocksDbDataSourceClosedException => throw e
      case NonFatal(e) =>
        throw SerializingMptStorage.corrupt(key, s"${e.getClass.getSimpleName}: ${e.getMessage}", Some(e))

  override def updateNodesInStorage(newRoot: Option[MptNode], toRemove: Seq[MptNode]): Option[MptNode] =
    val (collapsed, toUpdate) = MptStorage.collapseNode(newRoot)
    val toBeRemoved = toRemove.map(n => ByteString(n.hash))
    storage.update(toBeRemoved, toUpdate)
    collapsed

  override def persist(): Unit =
    storage.persist()

  /** As [[get]], for a batch: `None` for an absent node, the node for a good one. An entry that is not its node (one
    * that does not unwrap, or bytes that fail keccak, H3) throws [[CorruptNodeException]] naming its key, and so does
    * bytes that pass keccak and do not decode; before, a wrapper that did not unwrap threw a raw decode error for the
    * whole batch, with no key, and a list under the wrong hash was returned as a node. Read per entry, without filling
    * the block cache, as the walks read.
    */
  override def multiGetNodes(hashes: Seq[Array[Byte]]): Seq[Option[MptNode]] =
    if hashes.isEmpty then Seq.empty
    else
      val keys = hashes.map(ByteString(_))
      storage.multiGetRaw(keys).lazyZip(keys).lazyZip(hashes).map { (raw, key, nodeId) =>
        raw match
          case RawNodeRead.Absent => None
          case RawNodeRead.Undecodable =>
            throw SerializingMptStorage.corrupt(key, s"stored wrapper ${NodeFault.Undecodable.describe}")
          case RawNodeRead.Bytes(nodeEncoded) =>
            if NodeFault.provablyBad(key, raw) then
              throw SerializingMptStorage.corrupt(key, NodeFault.BadKeccak.describe)
            try Some(MptStorage.decodeNode(nodeEncoded, nodeId))
            catch
              case NonFatal(e) =>
                throw SerializingMptStorage.corrupt(key, s"${e.getClass.getSimpleName}: ${e.getMessage}", Some(e))
      }

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

  /** The exception for a stored entry that is not the node `hash` names, logged the first time that hash is met. At
    * ERROR: a corrupt node on disk is a failure an operator acts on, and this package logs at ERROR only by default
    * (logback.xml), where a WARN never showed (spec 022 T062, live run). `cause` is what reading it threw.
    */
  private[storage] def corrupt(
      hash: ByteString,
      detail: String,
      cause: Option[Throwable] = None
  ): CorruptNodeException =
    val error = new CorruptNodeException(hash, detail, cause = cause)
    if firstReport(hash) then
      log.error(
        s"[CORRUPT-NODE] ${error.getMessage}: reads of it fail as a missing node until it is deleted or replaced",
        error
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
