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
  * RLP-encoded. Keying by hash mirrors the coordinator's `pendingHashSet` de-dup key and makes delete-on-heal an O(1)
  * point-delete.
  *
  * Lifecycle (driven by the coordinator):
  *   - write on every new enqueue (`queueNodes`, inline child discovery, pivot reseed),
  *   - delete on heal-flush (when buffered nodes are durably written), NOT on dispatch,
  *   - clear (delete the outstanding set) on force-complete / pivot-refresh.
  */
class HealingFrontierStorage(val dataSource: DataSource)
    extends TransactionalKeyValueStorage[ByteString, Seq[ByteString]] {

  val namespace: IndexedSeq[Byte] = Namespaces.HealingFrontierNamespace

  def keySerializer: ByteString => IndexedSeq[Byte] = identity
  def keyDeserializer: IndexedSeq[Byte] => ByteString = k => ByteString.fromArrayUnsafe(k.toArray)
  def valueSerializer: Seq[ByteString] => IndexedSeq[Byte] = ps => ArraySeq.unsafeWrapArray(rlp.encode(ps))
  def valueDeserializer: IndexedSeq[Byte] => Seq[ByteString] = bytes => rlp.decode[Seq[ByteString]](bytes.toArray)

  /** Drain the entire persisted frontier (hash -> pathset). Called once on `[HEAL-RESTART]` to resume healing without
    * the full-state DFS. Throws if iteration errors or a stored value fails to RLP-decode — the caller treats any
    * failure as a corrupt frontier and falls back to the provably-complete full-state walk.
    */
  def loadAll(): Seq[(ByteString, Seq[ByteString])] =
    storageContent.compile.toList
      .unsafeRunSync()(IORuntime.global)
      .map {
        case Right(entry) => entry
        case Left(err)    => throw new RuntimeException(s"HealingFrontierStorage iteration error: $err")
      }
}
