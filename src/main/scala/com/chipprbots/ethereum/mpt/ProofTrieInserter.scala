package com.chipprbots.ethereum.mpt

import org.apache.pekko.util.ByteString

/** Mutable Phase-3 leaf inserter for SNAP range proof verification.
  *
  * Replaces the immutable-functional `doInsertLeaf` (which copies a BranchNode array on every level, O(N×depth)
  * allocations) with go-ethereum's mutable StackTrie algorithm. StackTrie updates `StNode.children(idx)` in-place, so
  * inserting N leaves into a depth-D trie requires O(N) allocations instead of O(N×D).
  *
  * Lifecycle:
  *   1. Construct with the pruned MptNode root from Phase 1+2. 2. Call `insert` for each leaf in sorted key order
  *      (Phase 3). 3. Call `computeHash` to get the reconstructed root hash (Phase 4).
  *
  * Not thread-safe — one instance per verification task.
  */
final class ProofTrieInserter(initialRoot: MptNode) {
  import StackTrie._

  // No-op callback: we only care about the final root hash, not intermediate node writes.
  private val hasher = new StackTrie((_, _, _) => ())

  private var stRoot: StNode = mptToStNode(initialRoot)

  /** Insert a single leaf. keyNibbles must be 64 bytes (nibbles 0–15) for a 32-byte hash key.
    *
    * Uses insertOrUpdateInto rather than insertInto because Phase 1 may have already resolved a boundary leaf into the
    * StNode tree, and Phase 3 re-inserts that same key with the peer's claimed value. go-ethereum's Trie.insert handles
    * this by replacing the existing valueNode; we mirror that here.
    */
  def insert(keyNibbles: Array[Byte], value: Array[Byte]): Unit =
    stRoot = hasher.insertOrUpdateInto(stRoot, keyNibbles, value)

  /** Finalise the tree and return the 32-byte root hash. Destructive — do not call insert after this. */
  def computeHash(): ByteString = hasher.hashExternal(stRoot)

  // ── MptNode → StNode conversion ────────────────────────────────────────────

  private def mptToStNode(node: MptNode): StNode = node match {
    case NullNode =>
      StNode.empty

    case leaf: LeafNode =>
      // LeafNode.key stores pre-decoded nibbles (0–15 per byte) — see MptTraversals.parseMpt.
      // StackTrie.insert also works with nibbles, so we pass them through directly.
      StNode.newLeaf(toNibbleArray(leaf.key), leaf.value.toArray)

    case ext: ExtensionNode =>
      StNode.newExt(toNibbleArray(ext.sharedKey), mptToStNode(ext.next))

    case branch: BranchNode =>
      val st = StNode.newBranch()
      var i = 0
      while (i < 16) {
        st.children(i) = mptToStNode(branch.children(i))
        i += 1
      }
      // branch.terminator is always None in SNAP account tries (keys are fixed 64-nibble hashes).
      st

    case HashNode(hash) =>
      // Preserved from Phase 1+2 — nodes outside the proof range remain as hash references.
      val st = StNode.empty
      st.typ = Hashed
      st.value = hash.clone()
      st
  }

  /** Convert a ByteString whose bytes are nibbles (values 0–15) to a plain Array[Byte]. Matches the internal
    * representation used by LeafNode.key and ExtensionNode.sharedKey.
    */
  private def toNibbleArray(bs: ByteString): Array[Byte] = {
    val arr = new Array[Byte](bs.length)
    var i = 0
    while (i < bs.length) {
      arr(i) = (bs(i) & 0xff).toByte
      i += 1
    }
    arr
  }
}
