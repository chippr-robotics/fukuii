package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.mpt.HexPrefix
import com.chipprbots.ethereum.mpt.LeafNode
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.defaultByteArraySerializable
import com.chipprbots.ethereum.mpt.MptTraversals
import com.chipprbots.ethereum.mpt.byteStringSerializer

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

  /** @param sampledAccountLeaves
    *   account leaves re-read through a root-to-leaf trie lookup after the export (each one matched or the export
    *   threw)
    * @param sampledStorageRoots
    *   storage-trie roots re-read by hash after the export
    */
  final case class Result(
      accountNodes: Long,
      storageNodes: Long,
      sampledAccountLeaves: Int = 0,
      sampledStorageRoots: Int = 0
  )

  private val BatchSize = 10000
  val AccountSampleSize = 1000
  val StorageRootSampleSize = 16

  /** Uniform reservoir sample (Algorithm R) of `(key, rlp)` entries. */
  final private class Reservoir(capacity: Int):
    private val items = scala.collection.mutable.ArrayBuffer.empty[(Array[Byte], Array[Byte])]
    private var seen = 0L
    def offer(key: Array[Byte], rlp: Array[Byte]): Unit =
      seen += 1
      if items.size < capacity then items += ((key, rlp))
      else
        val j = scala.util.Random.nextLong(seen)
        if j < capacity then items(j.toInt) = (key, rlp)
    def result: Seq[(Array[Byte], Array[Byte])] = items.toSeq

  /** Blocking: run it on a worker thread, never on an actor thread (it scans the whole path-keyed trie).
    *
    * @param stateRoot
    *   when given, ~[[AccountSampleSize]] random account leaves are re-read through a root-to-leaf lookup and a few
    *   storage roots by hash after the export; any miss throws.
    */
  def publish(
      pathNodeStorage: PathNodeStorage,
      mptStorage: MptStorage,
      onProgress: (Long, Long) => Unit = (_, _) => (),
      stateRoot: Option[ByteString] = None
  ): Result =
    var account = 0L
    var storage = 0L
    val accountSample = new Reservoir(AccountSampleSize)
    val storageRootSample = new Reservoir(StorageRootSampleSize)

    def toBatch(chunk: Seq[(Array[Byte], Array[Byte])], hasAccountPrefix: Boolean): Vector[(ByteString, Array[Byte])] =
      chunk.iterator.flatMap { case (key, rlp) =>
        val hpKey = if hasAccountPrefix then key.drop(32) else key
        val isRoot = HexPrefix.decode(hpKey)._1.isEmpty
        if isRoot || rlp.length >= 32 then
          if stateRoot.isDefined then
            if hasAccountPrefix then
              if isRoot then storageRootSample.offer(key, rlp)
            else accountSample.offer(key, rlp)
          Some((ByteString(kec256(rlp)), rlp))
        else None
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

    stateRoot match
      case None => Result(account, storage)
      case Some(root) =>
        val sampledLeaves = verifyAccountLeaves(accountSample.result, root, mptStorage)
        val sampledRoots = verifyStorageRoots(storageRootSample.result, mptStorage)
        Result(account, storage, sampledLeaves, sampledRoots)

  /** Re-read sampled account leaves through a full root-to-leaf lookup. Throws on the first miss or mismatch. */
  private def verifyAccountLeaves(
      sample: Seq[(Array[Byte], Array[Byte])],
      root: ByteString,
      mptStorage: MptStorage
  ): Int =
    val trie = MerklePatriciaTrie[ByteString, Array[Byte]](root.toArray, mptStorage)
    var checked = 0
    sample.foreach { case (hpKey, rlp) =>
      MptTraversals.decodeNode(rlp) match
        case leaf: LeafNode =>
          val fullNibbles = HexPrefix.decode(hpKey)._1 ++ leaf.key.toArray
          // Only a whole-byte path is a key; a real account leaf is always 64 nibbles.
          if fullNibbles.length % 2 == 0 then
            val key = ByteString(HexPrefix.nibblesToBytes(fullNibbles))
            val found =
              try trie.get(key)
              catch
                case e: Exception =>
                  throw new IllegalStateException(
                    s"Path-to-hash verification failed: lookup of sampled account ${key.take(8).toArray.map("%02x".format(_)).mkString} " +
                      s"under root ${root.take(4).toArray.map("%02x".format(_)).mkString} threw ${e.getClass.getSimpleName}",
                    e
                  )
            if !found.exists(java.util.Arrays.equals(_, leaf.value.toArray)) then
              throw new IllegalStateException(
                s"Path-to-hash verification failed: sampled account leaf ${key.take(8).toArray.map("%02x".format(_)).mkString} " +
                  "not found (or different) at the state root"
              )
            checked += 1
        case _ => ()
    }
    checked

  private def verifyStorageRoots(sample: Seq[(Array[Byte], Array[Byte])], mptStorage: MptStorage): Int =
    sample.foreach { case (_, rlp) =>
      val hash = kec256(rlp)
      if scala.util.Try(mptStorage.get(hash)).isFailure then
        throw new IllegalStateException(
          s"Path-to-hash verification failed: storage root ${hash.take(4).map("%02x".format(_)).mkString} absent after export"
        )
    }
    sample.size
