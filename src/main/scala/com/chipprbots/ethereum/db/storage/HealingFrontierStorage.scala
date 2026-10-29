package com.chipprbots.ethereum.db.storage

import org.apache.pekko.util.ByteString

import cats.effect.unsafe.IORuntime

import scala.collection.immutable.ArraySeq

import com.chipprbots.ethereum.db.dataSource.DataSource
import com.chipprbots.ethereum.rlp
import com.chipprbots.ethereum.rlp.RLPImplicits.given

/** Crash-durable copy of the post-SNAP healing frontier — the set of still-missing trie nodes the
  * `TrieNodeHealingCoordinator` is healing. Persisting it lets a restart resume in O(frontier) instead of re-walking
  * the full state trie (O(full state) — days on ETC mainnet). See docs/design/healing-frontier-scale.md (Layer 2).
  *
  * Layout: a dedicated RocksDB column family ([[Namespaces.HealingFrontierNamespace]]) keyed by the node hash (32
  * bytes) with the node's `pathset` (the HP-encoded path segments needed to re-issue `GetTrieNodes`) as the value,
  * RLP-encoded. Keying by hash mirrors the coordinator's Hash-scheme heal request key (the node hash) and makes
  * delete-on-heal an O(1) point-delete.
  *
  * Lifecycle (driven by the coordinator):
  *   - write on every new enqueue (`queueNodes`, inline child discovery, pivot reseed),
  *   - delete on heal-flush (when buffered nodes are durably written), NOT on dispatch,
  *   - clear (delete the outstanding set) on force-complete / pivot-refresh.
  */
class HealingFrontierStorage(val dataSource: DataSource)
    extends TransactionalKeyValueStorage[ByteString, Seq[ByteString]]:

  val namespace: IndexedSeq[Byte] = Namespaces.HealingFrontierNamespace

  def keySerializer: ByteString => IndexedSeq[Byte] = identity
  def keyDeserializer: IndexedSeq[Byte] => ByteString = k => ByteString.fromArrayUnsafe(k.toArray)
  def valueSerializer: Seq[ByteString] => IndexedSeq[Byte] = ps => ArraySeq.unsafeWrapArray(rlp.encode(ps))
  def valueDeserializer: IndexedSeq[Byte] => Seq[ByteString] = bytes => rlp.decode[Seq[ByteString]](bytes.toArray)

  /** Drain the entire persisted frontier (hash -> pathset), excluding the completeness sentinel. Called once on
    * `[HEAL-RESTART]` to resume healing without the full-state DFS. Throws if iteration errors or a stored value fails
    * to RLP-decode — the caller treats any failure as a corrupt frontier and falls back to the provably-complete walk.
    */
  def loadAll(): Seq[(ByteString, Seq[ByteString])] =
    storageContent.compile.toList
      .unsafeRunSync()(IORuntime.global)
      .map {
        case Right(entry) => entry
        case Left(err)    => throw new RuntimeException(s"HealingFrontierStorage iteration error: $err")
      }
      .filterNot { case (k, _) =>
        // Exclude the 21-byte completeness sentinel AND every 33-byte subtree-complete record
        // (`SubtreeCompletePrefix ++ hash`, spec 005). Both share CF 'g' with the frontier but are
        // NOT frontier entries, so they must never be reconstructed into the resume frontier.
        k == HealingFrontierStorage.CompleteMarkerKey || HealingFrontierStorage.isSubtreeCompleteKey(k)
      }

  /** Mark the persisted frontier as a COMPLETE snapshot — set only when the frontier-rebuild DFS has walked the entire
    * state. Resume is gated on this: a *partial* frontier (DFS interrupted before completion) must NOT short-circuit
    * the walk, or missing nodes in the un-walked region would be silently skipped. See
    * docs/design/healing-frontier-scale.md.
    */
  def markComplete(): Unit =
    // spec 005 C4/D3/T013: the TERMINAL completeness marker is fsync-durable (`commitSync` → `updateSync`). Losing it
    // on power-loss would force an expensive full re-verification, so it gets the same fsync treatment as SNAP
    // finalization. Per-subtree records (`markSubtreeComplete`) stay cheap WAL-ordered `update` — losing one is safe
    // (the restart simply descends that unrecorded subtree).
    put(HealingFrontierStorage.CompleteMarkerKey, Seq(ByteString(Array[Byte](1)))).commitSync()

  /** Clear the completeness sentinel (the persisted frontier is no longer a trustworthy complete snapshot). */
  def clearComplete(): Unit = remove(HealingFrontierStorage.CompleteMarkerKey).commit()

  /** True iff a complete-snapshot marker is present. */
  def isComplete: Boolean = get(HealingFrontierStorage.CompleteMarkerKey).isDefined

  /** Record that the entire subtree rooted at trie-node `hash` is present and correct on disk (spec 005, C1/D1).
    *
    * This is the durable per-subtree-completeness invariant the descend-and-stop verification consults: a present node
    * whose record exists is treated as a verified frontier leaf and is NOT descended. The record is keyed
    * `SubtreeCompletePrefix ++ hash` (33 bytes — distinct from a bare 32-byte node hash and the 21-byte
    * [[HealingFrontierStorage.CompleteMarkerKey]]) in CF 'g', value `0x01`. Records are additive, monotone, and NEVER
    * cleared: subtree-completeness is ROOT-INDEPENDENT (nodes are content-addressed, so `hash`'s children are fixed by
    * its content), so the fact is eternal and valid across pivot rolls.
    *
    * CRASH-SAFETY (FR-006/D3): the caller MUST write this record ONLY AFTER every node in `hash`'s subtree is in a
    * committed RocksDB WriteBatch (CF 'n'). A crash that loses this record (ordinary WAL-ordered `update`) is safe —
    * the restart simply descends the unrecorded subtree. Writing the record before the subtree's bytes are durable is
    * the only unsound order and the call sites enforce against it.
    */
  def markSubtreeComplete(hash: ByteString): Unit =
    put(HealingFrontierStorage.subtreeCompleteKey(hash), HealingFrontierStorage.SubtreeCompleteValue).commit()

  /** True iff a subtree-complete record exists for `hash` (spec 005). Absent record ⇒ the verification descends. */
  def isSubtreeComplete(hash: ByteString): Boolean =
    get(HealingFrontierStorage.subtreeCompleteKey(hash)).isDefined

  /** Batch variant of [[isSubtreeComplete]] (spec 005, C1): returns the subset of `hashes` that have a subtree-complete
    * record. Mirrors the `multiGetNodes` chunked-`multiGetOptimized` pattern (one JNI `multiGetAsList` per
    * [[HealingFrontierStorage.MultiReadChunkSize]] keys) so a present branch node's up-to-16 children are checked in a
    * single round trip instead of one point-get per child.
    */
  def multiIsSubtreeComplete(hashes: Seq[ByteString]): Set[ByteString] =
    if hashes.isEmpty then Set.empty
    else
      hashes
        .grouped(HealingFrontierStorage.MultiReadChunkSize)
        .flatMap { chunk =>
          val keys = chunk.map(h => HealingFrontierStorage.subtreeCompleteKey(h).toArray)
          dataSource
            .multiGetOptimized(namespace, keys)
            .zip(chunk)
            .collect { case (Some(_), h) => h }
        }
        .toSet

object HealingFrontierStorage:

  /** Reserved sentinel key for the completeness marker. 21 bytes — deliberately NOT 32, so it can never collide with a
    * keccak-256 node hash, and `loadAll` filters it out of the frontier.
    */
  val CompleteMarkerKey: ByteString = ByteString("__frontier_complete__")

  /** One-byte prefix for a subtree-complete record key (spec 005, C1/D1). A record key is `SubtreeCompletePrefix ++
    * hash` = 33 bytes — deliberately neither 32 (a bare keccak node hash) nor 21 ([[CompleteMarkerKey]]), so it can
    * never collide with either. `0x01` is the prefix byte; the value is the single byte `0x01`.
    */
  val SubtreeCompletePrefix: Byte = 0x01.toByte

  /** Single-byte value stored under every subtree-complete record key (presence is the only signal). */
  val SubtreeCompleteValue: Seq[ByteString] = Seq(ByteString(Array[Byte](1)))

  /** Maximum number of record keys per `multiGetOptimized` JNI call in
    * [[HealingFrontierStorage.multiIsSubtreeComplete]] — mirrors the `multiGetNodes` chunking rationale (keep each Java
    * list bounded). A verification BFS chunk is up to `BfsChunkSize` (50K) hashes; this bounds the per-call list the
    * same way.
    */
  val MultiReadChunkSize: Int = 50_000

  /** Single-element [[ByteString]] holding the prefix byte, for `++` concatenation that yields a [[ByteString]]. */
  private val SubtreeCompletePrefixBytes: ByteString = ByteString(Array[Byte](SubtreeCompletePrefix))

  /** Build the 33-byte record key `SubtreeCompletePrefix ++ hash` for a bare keccak node hash. */
  def subtreeCompleteKey(hash: ByteString): ByteString = SubtreeCompletePrefixBytes ++ hash

  /** True iff `key` is a subtree-complete record key (33 bytes, leading [[SubtreeCompletePrefix]]). Used by `loadAll`
    * to exclude records from the reconstructed resume frontier.
    */
  def isSubtreeCompleteKey(key: ByteString): Boolean =
    key.length == 33 && key.head == SubtreeCompletePrefix
