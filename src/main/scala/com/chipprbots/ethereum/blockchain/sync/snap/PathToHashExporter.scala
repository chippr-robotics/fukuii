package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.domain.Account
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
  * scheme. It hashes what it writes, so nothing about the export itself can be wrong by keccak: the sample after it
  * asks the other question, whether a root's lookups reach what they should through the hash store. A sample is not a
  * root recompute, and nothing here claims to be (spec 022 decision 11).
  */
object PathToHashExporter:

  /** @param sampledAccountLeaves
    *   account leaves re-read through a root-to-leaf trie lookup after the export (each one matched or the export
    *   threw)
    * @param sampledStorageRoots
    *   storage-trie roots re-read by hash after the export
    * @param sampledLargeTrieLeaves
    *   leaves of the largest storage tries found by a lookup from the state root, through the account's leaf
    * @param sampledEmbedded
    *   the same for leaves embedded in their parent, which are not stored by hash at all
    * @param sampledStale
    *   of those two, positions the state root does not have: left in the path store by another root, which is harmless
    * @param sampledMissing
    *   of those two, lookups that met an absent node on the way down: a real hole, which the audit will find
    * @param sampledCode
    *   code hashes of the sampled account leaves that were looked for
    * @param sampledCodeAbsent
    *   of those, absent. A hand-off can leave code to be fetched on demand (spec 022 D4), so this is news and not a
    *   fault
    * @param exportMillis
    *   the linear scan and its writes
    * @param sampleMillis
    *   the checks above, on their own, so the sample's size can be weighed against what it costs (T054)
    * @param sampledAccountMisses
    *   of the account leaves, those not at the state root, different there, or with a node absent on the way. A few are
    *   stale leaves from another root; past [[tooManyMisses]] the export threw
    */
  final case class Result(
      accountNodes: Long,
      storageNodes: Long,
      sampledAccountLeaves: Int = 0,
      sampledStorageRoots: Int = 0,
      sampledLargeTrieLeaves: Int = 0,
      sampledEmbedded: Int = 0,
      sampledStale: Int = 0,
      sampledMissing: Int = 0,
      sampledCode: Int = 0,
      sampledCodeAbsent: Int = 0,
      exportMillis: Long = 0L,
      sampleMillis: Long = 0L,
      sampledAccountMisses: Int = 0
  )

  private val BatchSize = 10000

  /** Whether sampled account leaves missed too often for that to be stale ones: more than 1% of them, and at least 16.
    * A few are what the path store leaves behind from other roots, and the hand-off must not restart SNAP for those.
    */
  def tooManyMisses(misses: Int, sampled: Int): Boolean =
    misses >= 16 && misses * 100L > sampled

  // The sample at the hand-off (spec 022 T054, decision 11). Strata, so a size can't hide a thin region: per first nibble
  // of the path or the account, the largest storage tries, and embedded leaves, which the export drops and only their
  // parent can answer for. Every lookup is a root-to-leaf walk of about ten reads, so this is some 6,000 lookups: seconds
  // on an SSD. Sized to measure on a Mordor Path run, not settled. `sampleMillis` says what it cost; change these if it
  // says so.
  val AccountSamplePerNibble = 256
  val StorageRootSamplePerNibble = 16
  val EmbeddedSamplePerNibble = 16
  val LargeStorageTries = 16
  val LargeStorageSamplePerTrie = 64

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

  /** A reservoir of each of the `tries` storage tries with the most nodes. Keys arrive in order, so an account's nodes
    * are one run, and only the current run's reservoir and the largest finished ones are held.
    */
  final private class LargestTries(tries: Int, perTrie: Int):
    private var current: Array[Byte] = Array.emptyByteArray
    private var count = 0L
    private var reservoir = new Reservoir(perTrie)
    private var largest = Vector.empty[(Long, Reservoir)]

    def offer(key: Array[Byte], rlp: Array[Byte]): Unit =
      if !java.util.Arrays.equals(key, 0, 32, current, 0, math.min(32, current.length)) then
        finishRun()
        current = key.take(32)
      count += 1
      reservoir.offer(key, rlp)

    private def finishRun(): Unit =
      if count > 0 then
        largest = ((count, reservoir) +: largest).sortBy(-_._1).take(tries)
        count = 0
        reservoir = new Reservoir(perTrie)

    def result: Seq[(Array[Byte], Array[Byte])] =
      finishRun()
      largest.flatMap(_._2.result)

  private def strata(perNibble: Int): Array[Reservoir] = Array.fill(16)(new Reservoir(perNibble))

  /** Blocking: run it on a worker thread, never on an actor thread (it scans the whole path-keyed trie).
    *
    * @param stateRoot
    *   when given, the sample above is checked after the export. A root that is absent throws, as does a storage root,
    *   and account leaves past [[tooManyMisses]]. What else the sample finds is reported in the [[Result]] and does not
    *   stop the hand-off (spec 022 decision 11: a gate here is what the spec removes, and the audit looks at all of it
    *   later).
    * @param codePresent
    *   whether a code hash is in the code store
    */
  def publish(
      pathNodeStorage: PathNodeStorage,
      mptStorage: MptStorage,
      onProgress: (Long, Long) => Unit = (_, _) => (),
      stateRoot: Option[ByteString] = None,
      codePresent: ByteString => Boolean = _ => true
  ): Result =
    val started = System.nanoTime()
    var account = 0L
    var storage = 0L
    val accountSample = strata(AccountSamplePerNibble)
    val storageRootSample = strata(StorageRootSamplePerNibble)
    val embeddedSample = strata(EmbeddedSamplePerNibble)
    val largestTries = new LargestTries(LargeStorageTries, LargeStorageSamplePerTrie)
    val sampling = stateRoot.isDefined

    def toBatch(chunk: Seq[(Array[Byte], Array[Byte])], hasAccountPrefix: Boolean): Vector[(ByteString, Array[Byte])] =
      chunk.iterator.flatMap { case (key, rlp) =>
        val hpKey = if hasAccountPrefix then key.drop(32) else key
        val nibbles = HexPrefix.decode(hpKey)._1
        val isRoot = nibbles.isEmpty
        val embedded = !isRoot && rlp.length < 32
        if sampling then
          if hasAccountPrefix then
            val stratum = (key(0) >> 4) & 0xf
            if isRoot then storageRootSample(stratum).offer(key, rlp)
            else
              largestTries.offer(key, rlp)
              if embedded then embeddedSample(stratum).offer(key, rlp)
          else if !embedded then accountSample(nibbles.headOption.fold(0)(_.toInt)).offer(key, rlp)
        if embedded then None else Some((ByteString(kec256(rlp)), rlp))
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
    val exported = System.nanoTime()
    val exportMillis = (exported - started) / 1000000L

    stateRoot match
      case None => Result(account, storage, exportMillis = exportMillis)
      case Some(root) =>
        requireRoot(root, mptStorage)
        val leaves = lookUpAccountLeaves(accountSample.toSeq.flatMap(_.result), root, mptStorage)
        val sampledRoots = requireStorageRoots(storageRootSample.toSeq.flatMap(_.result), mptStorage)
        val large = lookUpStorageLeaves(largestTries.result, root, mptStorage)
        val embedded = lookUpStorageLeaves(embeddedSample.toSeq.flatMap(_.result), root, mptStorage)
        val distinctCode = leaves.codeHashes.distinct
        Result(
          account,
          storage,
          leaves.sampled,
          sampledRoots,
          large.found,
          embedded.found,
          large.stale + embedded.stale,
          large.missing + embedded.missing,
          distinctCode.size,
          distinctCode.count(h => !codePresent(h)),
          exportMillis,
          (System.nanoTime() - exported) / 1000000L,
          leaves.misses
        )

  private def hexOf(bytes: Array[Byte]): String = bytes.map("%02x".format(_)).mkString

  /** A root that is absent is no stale leaf. Nothing under it can be read, so it throws whatever the rate would say. */
  private def requireRoot(root: ByteString, mptStorage: MptStorage): Unit =
    if !java.util.Arrays.equals(root.toArray, MerklePatriciaTrie.EmptyRootHash) then
      try mptStorage.get(root.toArray)
      catch
        case e: Exception =>
          throw new IllegalStateException(
            s"Path-to-hash verification failed: state root ${hexOf(root.take(4).toArray)} absent after export",
            e
          )

  final private case class AccountLeaves(sampled: Int, misses: Int, codeHashes: Seq[ByteString])

  /** Re-read sampled account leaves through a full root-to-leaf lookup. A miss is counted: the path store keeps what
    * other roots wrote, and [[lookUpStorageLeaves]] counts the same condition as harmless. A throw here is
    * `PathPublishFailed`, `HealingImpossible` and a SNAP restart, so that waits for [[tooManyMisses]]. Gives how many
    * it looked at, how many missed, and the code hashes of those that matched. A store that fails throws.
    */
  private def lookUpAccountLeaves(
      sample: Seq[(Array[Byte], Array[Byte])],
      root: ByteString,
      mptStorage: MptStorage
  ): AccountLeaves =
    val trie = MerklePatriciaTrie[ByteString, Array[Byte]](root.toArray, mptStorage)
    var sampled = 0
    var misses = 0
    val codeHashes = Seq.newBuilder[ByteString]
    sample.foreach { case (hpKey, rlp) =>
      MptTraversals.decodeNode(rlp) match
        case leaf: LeafNode =>
          val fullNibbles = HexPrefix.decode(hpKey)._1 ++ leaf.key.toArray
          // Only a whole-byte path is a key; a real account leaf is always 64 nibbles.
          if fullNibbles.length % 2 == 0 then
            val key = ByteString(HexPrefix.nibblesToBytes(fullNibbles))
            sampled += 1
            val found =
              try trie.get(key)
              catch case _: MerklePatriciaTrie.MPTException => None
            if found.exists(java.util.Arrays.equals(_, leaf.value.toArray)) then
              Account(leaf.value).toOption
                .map(_.codeHash)
                .filter(_ != Account.EmptyCodeHash)
                .foreach(code => codeHashes += code.value)
            else misses += 1
        case _ => ()
    }
    if tooManyMisses(misses, sampled) then
      throw new IllegalStateException(
        s"Path-to-hash verification failed: $misses of $sampled sampled account leaves not found (or different) at " +
          s"the state root ${hexOf(root.take(4).toArray)}"
      )
    AccountLeaves(sampled, misses, codeHashes.result())

  /** That each sampled storage root is in the hash store. It re-reads what the export just wrote, so its success says
    * present and no more.
    */
  private def requireStorageRoots(sample: Seq[(Array[Byte], Array[Byte])], mptStorage: MptStorage): Int =
    sample.foreach { case (_, rlp) =>
      val hash = kec256(rlp)
      if scala.util.Try(mptStorage.get(hash)).isFailure then
        throw new IllegalStateException(
          s"Path-to-hash verification failed: storage root ${hexOf(hash.take(4))} absent after export"
        )
    }
    sample.size

  final private case class Looked(found: Int = 0, stale: Int = 0, missing: Int = 0)

  /** Each sampled storage leaf looked up from the state root, by way of its account's leaf: what the hash store would
    * give block execution. A leaf the state root has no place for is stale (the path store keeps what another root
    * wrote). An absent node on the way is a real hole, and a lazy hand-off may leave those.
    */
  private def lookUpStorageLeaves(
      sample: Seq[(Array[Byte], Array[Byte])],
      root: ByteString,
      mptStorage: MptStorage
  ): Looked =
    val accounts = MerklePatriciaTrie[ByteString, Array[Byte]](root.toArray, mptStorage)
    sample.foldLeft(Looked()) { case (seen, (key, rlp)) =>
      MptTraversals.decodeNode(rlp) match
        case leaf: LeafNode =>
          val fullNibbles = HexPrefix.decode(key.drop(32))._1 ++ leaf.key.toArray
          if fullNibbles.length != 64 then seen
          else
            val slot = ByteString(HexPrefix.nibblesToBytes(fullNibbles))
            try
              val value = accounts
                .get(ByteString(key.take(32)))
                .flatMap(bytes => Account(ByteString(bytes)).toOption)
                .filter(_.storageRoot != Account.EmptyStorageRootHash)
                .flatMap(account =>
                  MerklePatriciaTrie[ByteString, Array[Byte]](account.storageRoot.value.toArray, mptStorage).get(slot)
                )
              if value.exists(java.util.Arrays.equals(_, leaf.value.toArray)) then seen.copy(found = seen.found + 1)
              else seen.copy(stale = seen.stale + 1)
            catch case _: MerklePatriciaTrie.MPTException => seen.copy(missing = seen.missing + 1)
        case _ => seen // a branch or an extension has no key of its own to look up
    }
