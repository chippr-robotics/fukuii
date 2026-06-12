package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.util.ByteString

/** Common interface for SNAP sync trie builders.
  *
  * SNAP sync assembles merkle-patricia trie state from ordered `(key, value)` pairs delivered by remote peers. Two
  * storage schemes require different implementations:
  *
  *   - [[SnapHashTrie]] — nodes are stored keyed by their keccak256 hash (HashScheme, ETC default). Content-addressed
  *     storage means partial re-runs are harmless — duplicate writes are no-ops. No boundary logic required.
  *
  *   - [[SnapPathTrie]] — nodes are stored keyed by their nibble path (PathScheme, ETH full nodes). Exactly one node
  *     occupies each path, so prior-run stale nodes must be cleaned up. [[SnapPathTrie]] handles left-boundary skip,
  *     extension-gap cleanup, and right-boundary discard.
  *
  * Callers in the SNAP coordinator actors ([[actors.AccountRangeCoordinator]], [[actors.StorageRangeCoordinator]])
  * interact only through this trait, so scheme selection is a configuration concern isolated to the constructor site.
  */
trait SnapTrie {

  /** Insert `value` under `key`. Keys must arrive in strictly-ascending nibble order. */
  def update(key: Array[Byte], value: Array[Byte]): Unit

  /** Finalise all pending nodes, flush to storage, and return the 32-byte trie root hash. */
  def commit(): ByteString

  /** Abandon this trie without finalising. Implementations must clean up any partially-written boundary state so that
    * state healing is not disrupted.
    */
  def reset(): Unit
}
