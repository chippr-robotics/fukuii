package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.mpt.StackTrie

/** Path-scheme [[StackTrie]] wrapper for SNAP sync.
  *
  * Mirrors go-ethereum `pathTrie` in `eth/protocols/snap/gentrie.go`. Three correctness invariants are enforced on top
  * of the raw [[StackTrie]]:
  *
  *   1. '''Left-boundary skip''' (`skipLeftBoundary = true`): when resuming a range that was interrupted mid-task, the
  *      first nodes emitted by [[StackTrie]] are on the left boundary — they may be incomplete because left-side
  *      siblings from before the resume cursor are absent. These nodes are silently dropped. Additionally, ancestor
  *      stubs at paths above the first emitted node are deleted from path-keyed storage — they are leftovers from the
  *      prior run that would otherwise create structural conflicts with newly-committed nodes.
  *
  * 2. '''Extension-gap cleanup''': [[StackTrie]] may emit an extension node at path P that covers a span of > 1 nibble.
  * This means path slots at depth P+1 .. P+span-1 were skipped. A prior run may have stored nodes at those inner paths;
  * this wrapper deletes them to ensure the trie's path space is uniquely occupied.
  *
  * 3. '''Right-boundary discard''' ([[reset()]]): when a task is abandoned mid-range, the ancestor nodes on the path
  * from the trie root to the last disk-committed node are deleted. Those ancestors reference incomplete subtrees
  * (right-side children were never inserted) and must be rebuilt by state healing. The last-committed node itself is
  * retained — it is a complete, valid node that healing can use as a leaf anchor.
  *
  * ==Callbacks==
  *
  * Integration with [[com.chipprbots.ethereum.db.storage.PathNodeStorage]] is through two callbacks:
  *
  *   - `writePath(nibblePath, nodeHash, rlpBlob)` — stores the node at the given nibble path.
  *   - `deleteExact(nibblePath)` — deletes the single node at exactly that path (point-delete, not prefix-delete).
  *
  * Callers construct these closures from `PathNodeStorage`, routing account-trie vs storage-trie as appropriate.
  *
  * ==Thread safety==
  *
  * Not thread-safe. Caller (a SNAP coordinator actor) serialises all calls via actor-message ordering.
  *
  * @param owner
  *   empty [[ByteString]] for the account trie; 32-byte account hash for a storage trie (informational, for call-site
  *   documentation — routing is via the callbacks).
  * @param skipLeftBoundary
  *   `true` when resuming a mid-range task with a non-empty spec-004 cursor; `false` for a fresh task.
  * @param writePath
  *   `(nibblePath, nodeHash, rlpBlob) => Unit` — writes one committed trie node to path-keyed storage.
  * @param deleteExact
  *   `nibblePath => Unit` — deletes the node at exactly that path from path-keyed storage.
  */
final class SnapPathTrie(
    val owner: ByteString,
    val skipLeftBoundary: Boolean,
    writePath: (Array[Byte], ByteString, Array[Byte]) => Unit,
    deleteExact: Array[Byte] => Unit
) extends SnapTrie {

  // Path of the first node committed by StackTrie; marks the left-boundary anchor. Null until first emission.
  private var first: Array[Byte] = null

  // Path of the most recently committed node; used for extension-gap detection and right-boundary cleanup.
  private var last: Array[Byte] = null

  // Mutable skip-left flag; disabled once the first non-boundary node is encountered.
  private var skipLeft: Boolean = skipLeftBoundary

  private val stackTrie: StackTrie = new StackTrie(onTrieNode)

  // ---- SnapTrie API ----

  override def update(key: Array[Byte], value: Array[Byte]): Unit =
    stackTrie.update(key, value)

  /** Finalise all pending nodes and return the trie root hash.
    *
    * Triggers [[StackTrie]] to commit all remaining right-boundary nodes, each of which passes through [[onTrieNode]]
    * and is written to path-keyed storage. Returns the 32-byte root hash.
    */
  override def commit(): ByteString = stackTrie.hash()

  /** Abandon this trie without finalising; delete right-boundary ancestor stubs.
    *
    * Deletes the ancestor paths of the last disk-committed node (paths at depth 0 through `len(last)−1`). These
    * ancestors reference subtrees that were never fully populated and would mislead state healing. The node at `last`
    * itself is retained — it is a complete, valid leaf or branch that healing can use as an anchor. Discards all
    * in-memory [[StackTrie]] state without triggering further [[onTrieNode]] callbacks.
    */
  override def reset(): Unit = {
    deleteRightBoundary()
    stackTrie.reset()
  }

  // ---- internals ----

  private def onTrieNode(path: Array[Byte], hash: ByteString, blob: Array[Byte]): Unit = {
    // ---- left-boundary filter ----
    // Skip nodes that are on the left boundary of a resumed range.  A node is "on the left boundary" if it is the
    // first node ever emitted (first == null) or if its path is a prefix of (i.e., is an ancestor of) the first node.
    // go-ethereum equivalent: pathTrie.onTrieNode, condition `t.skipLeftBoundary && (t.first == nil || HasPrefix(t.first, path))`
    if (skipLeft) {
      if (first == null) {
        // Record the left-boundary anchor (deep-copy: StackTrie reuses buffers).
        first = path.clone()
        // Delete any stale ancestor stubs at depths 0 .. len-1 left by the prior interrupted run.
        // (Depth len = the first node itself; it's a sibling anchor, not an ancestor stub.)
        var i = 0
        while (i < first.length) {
          deleteExact(first.slice(0, i))
          i += 1
        }
      }
      // Skip writing if `path` is a prefix of `first` (path is an ancestor of first, or IS first).
      if (hasPrefix(first, path)) return
      // This node is not on the left boundary — disable the filter for all subsequent callbacks.
      skipLeft = false
    }

    // ---- extension-gap cleanup ----
    // If `path` is a strict prefix of `last` with a gap > 1 nibble, StackTrie is finalising an extension node that
    // spans that gap. Intermediate path slots at depths (len(path)+1 .. len(last)−1) may hold stale nodes from a
    // prior run; delete them.
    // go-ethereum equivalent: `last != nil && HasPrefix(last, path) && len(last)-len(path) > 1`
    if (last != null && hasPrefix(last, path) && last.length - path.length > 1) {
      var i = path.length + 1
      while (i < last.length) {
        deleteExact(last.slice(0, i))
        i += 1
      }
    }

    // ---- write ----
    // Deep-copy: StackTrie reuses its internal path/blob buffers across callbacks.
    writePath(path.clone(), hash, blob.clone())

    // Update `last` (reuse the existing allocation if lengths match to avoid GC churn).
    last =
      if (last == null || last.length != path.length) path.clone()
      else { System.arraycopy(path, 0, last, 0, path.length); last }
  }

  /** Delete ancestor paths of `last` at depths 0 through `len(last)−1`. */
  private def deleteRightBoundary(): Unit =
    if (last != null) {
      var i = 0
      while (i < last.length) {
        deleteExact(last.slice(0, i))
        i += 1
      }
    }

  /** True iff `array` has `prefix` as a leading byte prefix (i.e. `prefix` is a prefix of `array`). */
  private def hasPrefix(array: Array[Byte], prefix: Array[Byte]): Boolean =
    prefix.length <= array.length &&
      java.util.Arrays.equals(array, 0, prefix.length, prefix, 0, prefix.length)
}
