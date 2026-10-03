package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.mpt.HexPrefix

/** Publishes a Path-scheme SNAP trie to the hash-keyed node store that block execution reads.
  *
  * Under the Path scheme SNAP sync and healing write trie nodes ONLY into the path-keyed column families (see
  * [[com.chipprbots.ethereum.db.storage.PathNodeStorage]]). Block execution (`StateStorage.getBackingStorage` ->
  * [[MptStorage]]) resolves every child reference by keccak256 hash and never reads those column families, so right
  * after a clean Path SNAP completion the pivot state root itself is "missing" (MissingAccountNodeException on the
  * first imported block). This exporter closes that gap: every stored node is written to the hash-keyed store under
  * kec256(rlp), which is byte-identical to what the Hash scheme (`SnapHashTrie` via `storeRawNodes`) writes.
  *
  * Layout matches `MptStorage.collapseNode`: a node is stored by hash only when its RLP is at least 32 bytes (smaller
  * nodes are embedded in their parent), except a trie root (empty path), which is always referenced by hash. Extra or
  * stale nodes in the path column families are harmless: storage is content-addressed, so they can only ever be found
  * by a hash that commits to exactly their bytes.
  *
  * The export is a linear scan (no trie walk, no per-node random read) and idempotent. It never runs for the Hash
  * scheme.
  */
object PathToHashExporter:

  final case class Result(accountNodes: Long, storageNodes: Long)

  private val BatchSize = 10000

  def publish(
      pathNodeStorage: PathNodeStorage,
      mptStorage: MptStorage,
      onProgress: (Long, Long) => Unit = (_, _) => ()
  ): Result =
    var account = 0L
    var storage = 0L

    def toBatch(chunk: Seq[(Array[Byte], Array[Byte])], hasAccountPrefix: Boolean): Vector[(ByteString, Array[Byte])] =
      chunk.iterator.flatMap { case (key, rlp) =>
        val hpKey = if hasAccountPrefix then key.drop(32) else key
        val isRoot = HexPrefix.decode(hpKey)._1.isEmpty
        if isRoot || rlp.length >= 32 then Some((ByteString(kec256(rlp)), rlp)) else None
      }.toVector

    pathNodeStorage.foreachAccountNodeChunk(BatchSize) { chunk =>
      val nodes = toBatch(chunk, hasAccountPrefix = false)
      if nodes.nonEmpty then mptStorage.storeRawNodes(nodes)
      account += nodes.size
      onProgress(account, storage)
    }
    pathNodeStorage.foreachStorageNodeChunk(BatchSize) { chunk =>
      val nodes = toBatch(chunk, hasAccountPrefix = true)
      if nodes.nonEmpty then mptStorage.storeRawNodes(nodes)
      storage += nodes.size
      onProgress(account, storage)
    }
    mptStorage.persist()
    Result(account, storage)
