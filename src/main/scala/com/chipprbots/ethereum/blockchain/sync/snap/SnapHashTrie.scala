package com.chipprbots.ethereum.blockchain.sync.snap

import scala.collection.mutable

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.mpt.StackTrie

/** Hash-scheme RocksDB-batching wrapper around [[StackTrie]] for SNAP sync.
  *
  * The StackTrie itself is strictly write-only and emits finalised nodes via a
  * callback. This wrapper owns the batching policy:
  *
  *   - each emitted `(hash, RLP blob)` pair is appended to an in-memory buffer;
  *   - when the buffer crosses `batchSizeThreshold` total bytes, the entire
  *     buffer is handed to the `writeBatch` callback (a single batched RocksDB
  *     write) and cleared;
  *   - `commit()` finalises the right-boundary path and flushes the remainder.
  *
  * The `writeBatch` callback is the integration boundary. Callers pass in a
  * function adapted from their storage layer — typically `mptStorage.storeRawNodes`
  * for the SNAP path, which goes through `FastSyncNodeStorage` and picks up the
  * pivot-block-number tag for pruning automatically.
  *
  * == Why this is so simple ==
  *
  * geth's equivalent (`pathTrie` / `hashTrie` in `eth/protocols/snap/gentrie.go`)
  * carries non-trivial boundary logic — left-boundary skip on resume, ancestor
  * deletion, right-boundary discard. Almost all of that complexity is
  * path-scheme-specific: in path scheme, exactly one node exists at each trie
  * path, so stale partial state from a prior session must be cleaned up to
  * avoid structural conflicts.
  *
  * Fukuii uses hash scheme — nodes are keyed by their keccak256 hash, so two
  * nodes with different contents can never collide. Re-emitting an already-
  * written node is a no-op (RocksDB just overwrites the same key with the same
  * value). The boundary problem dissolves.
  *
  * If we later need to recover bytes wasted by re-emitting already-committed
  * nodes on resume, a `skipLeftBoundary` mode can be added — but it's a perf
  * optimisation, not a correctness requirement.
  *
  * == Concurrency ==
  *
  * Not thread-safe. The expected usage is one wrapper instance per
  * `AccountTask` (or per per-contract storage trie), with all `update` and
  * `commit` calls dispatched from a single actor or thread.
  *
  * @param writeBatch        called with `(nodeHash, rlpBlob)` pairs when the
  *                          batch threshold is reached or on `commit()`. The
  *                          caller owns the actual disk write semantics
  *                          (synchronous vs queued, fsync vs not, etc.).
  * @param batchSizeThreshold flush the batch when the accumulated blob bytes
  *                           reach this size. 8 MiB matches geth's
  *                           `batchSizeThreshold` in `eth/protocols/snap/sync.go`.
  */
final class SnapHashTrie(
    writeBatch: Seq[(ByteString, Array[Byte])] => Unit,
    val batchSizeThreshold: Int = SnapHashTrie.DefaultBatchSizeBytes
) {

  private val pending = mutable.ArrayBuffer.empty[(ByteString, Array[Byte])]
  private var pendingBytes: Long = 0L

  private val stackTrie = new StackTrie((_, hash, blob) => emit(hash, blob))

  /** Insert `value` under `key`. Keys must arrive in strictly-ascending
    * hex-nibble order (after `HexPrefix.bytesToNibbles`). The underlying
    * StackTrie enforces this; violations throw.
    */
  def update(key: Array[Byte], value: Array[Byte]): Unit =
    stackTrie.update(key, value)

  /** Finalise the right-boundary path and flush any pending batch.
    *
    * Returns the 32-byte trie root hash. After commit, the wrapper must be
    * `reset()` before any further `update` calls.
    */
  def commit(): ByteString = {
    val root = stackTrie.hash()
    flush()
    root
  }

  /** Discard any in-memory state without flushing. Used when a range is being
    * abandoned (pivot roll, abort, shutdown).
    *
    * Note: emissions already flushed to disk during the doomed run are NOT
    * rolled back — they're still valid hash-content-addressed trie nodes and
    * will simply be unreferenced until pruning cleans them up.
    */
  def reset(): Unit = {
    stackTrie.reset()
    pending.clear()
    pendingBytes = 0L
  }

  /** Bytes currently buffered in the pending batch (not yet flushed). */
  def pendingBatchBytes: Long = pendingBytes

  /** Number of `(hash, blob)` pairs currently buffered. */
  def pendingBatchCount: Int = pending.size

  // ---- internals ----

  private def emit(hash: ByteString, blob: Array[Byte]): Unit = {
    // StackTrie reuses internal buffers across emissions — deep-copy.
    pending += hash -> blob.clone()
    pendingBytes += blob.length
    if (pendingBytes >= batchSizeThreshold) flush()
  }

  private def flush(): Unit = {
    if (pending.nonEmpty) {
      // toSeq creates an immutable snapshot before clearing.
      writeBatch(pending.toSeq)
      pending.clear()
      pendingBytes = 0L
    }
  }
}

object SnapHashTrie {

  /** 8 MiB, matching geth's `batchSizeThreshold` in `eth/protocols/snap/sync.go`.
    *
    * Sized so that the 16 active account tasks plus their per-contract storage
    * tries cumulatively bound in-flight buffered state to ~128-256 MiB worst
    * case — large enough to amortise RocksDB write overhead, small enough to
    * keep heap pressure manageable.
    */
  val DefaultBatchSizeBytes: Int = 8 * 1024 * 1024
}
