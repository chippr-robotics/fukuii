package com.chipprbots.ethereum.mpt

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.crypto

/** Streaming Merkle Patricia Trie builder, ported from go-ethereum's `trie/stacktrie.go`.
  *
  * The StackTrie is optimised for one specific access pattern: keys arrive in strictly-ascending hex-nibble order and
  * the trie is built in a single left-to-right pass. As each new key is inserted, any unhashed elder sibling on the
  * rightmost path of the trie is finalised (RLP-encoded, hashed if its encoded form is >= 32 bytes) and emitted via the
  * `onTrieNode` callback. The finalised subtree becomes a single `Hashed` placeholder, so the live non-hashed nodes
  * form only the path from root to the most-recent leaf.
  *
  * Memory bound: O(trie depth). For an account trie of 10M accounts the depth is ~6 + extensions, so total live
  * `StNode` count is at most ~16 regardless of how many keys have been inserted.
  *
  * `update` is the only mutating method; `hash` finalises the right boundary and returns the root hash. After `hash`
  * the StackTrie cannot be updated again unless `reset` is called first.
  *
  * The `onTrieNode(path, hash, blob)` callback is invoked once per finalised non-inlined node. Receivers must deep-copy
  * `path` and `blob` if they want to retain them: the StackTrie reuses internal buffers across calls.
  *
  * Boundary handling (left-skip on resume, right-discard on abort, RocksDB batch ownership) is the wrapper's
  * responsibility. This class is strictly write-only: it never reads from any backing storage.
  */
final class StackTrie(onTrieNode: (Array[Byte], ByteString, Array[Byte]) => Unit) {
  import StackTrie._

  private var root: StNode = StNode.empty
  // last hex key seen, for strict-ascending sort enforcement
  private var last: Array[Byte] = Array.emptyByteArray

  /** Insert `value` under `key`. Keys must arrive in strictly ascending order after conversion to hex nibbles. Empty
    * values are rejected (use a real "delete" model if you need removals — SNAP sync never deletes).
    */
  def update(key: Array[Byte], value: Array[Byte]): Unit = {
    require(value.length > 0, "StackTrie does not accept empty values")
    val hex = HexPrefix.bytesToNibbles(key)
    val cmp = byteCompare(last, hex)
    require(cmp < 0, s"StackTrie keys must be strictly ascending: last=${hexStr(last)} >= new=${hexStr(hex)}")
    last = hex
    root = insert(root, hex, value, EmptyPath)
  }

  /** Finalise the right-boundary path and return the trie root hash.
    *
    * After this call, the StackTrie's root is a `Hashed` placeholder. Further `update` calls will fail until `reset()`
    * is invoked.
    */
  def hash(): ByteString = {
    hashNode(root, EmptyPath)
    if (root.typ == Hashed && root.value.length == 32) ByteString(root.value)
    else if (root.typ == Empty) ByteString(crypto.kec256(EmptyTrieRlp))
    else {
      // root is inline (<32B) — its blob is its own RLP. The "root hash" is
      // keccak256 of that blob (callers want a 32-byte root, not the inline blob).
      ByteString(crypto.kec256(root.value))
    }
  }

  /** Clear all state. After reset, the StackTrie can be reused for a new range. */
  def reset(): Unit = {
    root = StNode.empty
    last = Array.emptyByteArray
  }

  // ---- internals ----

  /** Recursive descent insertion. Returns the (possibly new) node that should replace `node` at this position. Always
    * returns either `node` (mutated in place) or a fresh node; never returns Empty unless `node` was Empty.
    */
  private def insert(node: StNode, key: Array[Byte], value: Array[Byte], path: Array[Byte]): StNode = {
    node.typ match {
      case Empty =>
        StNode.newLeaf(key, value)

      case Leaf =>
        val origKey = node.key
        val diff = diffIndex(origKey, key)
        if (diff >= origKey.length) {
          // Either duplicate key (diff == both lengths) or our key extends past
          // existing leaf's terminator. SNAP sync keys are all 64 hex nibbles
          // (32-byte hashes), so both cases imply a duplicate insert.
          throw new IllegalStateException(
            s"StackTrie: duplicate or extending key at path ${hexStr(path)} (existing key=${hexStr(origKey)}, new key=${hexStr(key)})"
          )
        }
        if (diff == 0) {
          // No shared prefix: convert leaf into a branch with two leaves.
          val branch = StNode.newBranch()
          val origIdx = origKey(0) & 0xff
          val newIdx = key(0) & 0xff
          branch.children(origIdx) = StNode.newLeaf(sliceFrom(origKey, 1), node.value)
          branch.children(newIdx) = StNode.newLeaf(sliceFrom(key, 1), value)
          // Hash the original-side leaf immediately (it can never gain more keys).
          hashNode(branch.children(origIdx), appendNibble(path, origIdx.toByte))
          branch
        } else {
          // Shared prefix of length `diff`: ext over the prefix, branch with two leaves.
          val branch = StNode.newBranch()
          val origIdx = origKey(diff) & 0xff
          val newIdx = key(diff) & 0xff
          branch.children(origIdx) = StNode.newLeaf(sliceFrom(origKey, diff + 1), node.value)
          branch.children(newIdx) = StNode.newLeaf(sliceFrom(key, diff + 1), value)
          val newPath = appendNibbles(path, origKey, 0, diff)
          hashNode(branch.children(origIdx), appendNibble(newPath, origIdx.toByte))
          val ext = StNode.newExt(sliceRange(origKey, 0, diff), branch)
          ext
        }

      case Ext =>
        val extKey = node.key
        val diff = diffIndex(extKey, key)
        if (diff >= extKey.length) {
          // Full ext key consumed — descend into the child.
          val keyTail = sliceFrom(key, extKey.length)
          val newPath = appendNibbles(path, extKey, 0, extKey.length)
          node.children(0) = insert(node.children(0), keyTail, value, newPath)
          node
        } else if (diff == 0) {
          // No shared prefix: ext becomes a branch (or a branch directly if extKey.length == 1).
          val branch = StNode.newBranch()
          val origIdx = extKey(0) & 0xff
          val newIdx = key(0) & 0xff
          // Original side: either the child directly (if ext key was 1 nibble) or a shorter ext over the remaining key.
          val origChild =
            if (extKey.length == 1) node.children(0)
            else StNode.newExt(sliceFrom(extKey, 1), node.children(0))
          branch.children(origIdx) = origChild
          branch.children(newIdx) = StNode.newLeaf(sliceFrom(key, 1), value)
          hashNode(branch.children(origIdx), appendNibble(path, origIdx.toByte))
          branch
        } else {
          // Partial shared prefix.
          val branch = StNode.newBranch()
          val origIdx = extKey(diff) & 0xff
          val newIdx = key(diff) & 0xff
          val origChild =
            if (diff + 1 == extKey.length) node.children(0)
            else StNode.newExt(sliceFrom(extKey, diff + 1), node.children(0))
          branch.children(origIdx) = origChild
          branch.children(newIdx) = StNode.newLeaf(sliceFrom(key, diff + 1), value)
          val newPath = appendNibbles(path, extKey, 0, diff)
          hashNode(branch.children(origIdx), appendNibble(newPath, origIdx.toByte))
          StNode.newExt(sliceRange(extKey, 0, diff), branch)
        }

      case Branch =>
        val idx = key(0) & 0xff
        // Left-finalisation: hash the most-recently-unhashed elder sibling.
        // Invariant: at most one slot < idx holds a non-Hashed node.
        var i = idx - 1
        var done = false
        while (i >= 0 && !done) {
          val sib = node.children(i)
          if (sib != null && sib.typ != Empty) {
            if (sib.typ != Hashed) hashNode(sib, appendNibble(path, i.toByte))
            done = true
          }
          i -= 1
        }
        val keyTail = sliceFrom(key, 1)
        val childPath = appendNibble(path, idx.toByte)
        val existing = node.children(idx)
        if (existing == null || existing.typ == Empty)
          node.children(idx) = StNode.newLeaf(keyTail, value)
        else
          node.children(idx) = insert(existing, keyTail, value, childPath)
        node

      case Hashed =>
        throw new IllegalStateException(
          s"StackTrie: sort violation — attempted insert into already-finalised subtree at path ${hexStr(path)}"
        )
    }
  }

  /** Finalise `node` recursively: hash any unhashed children, RLP-encode this node, then either store the encoded blob
    * in `node.value` (if < 32 bytes and not at the root) or compute keccak256 and store the hash (emitting via
    * `onTrieNode`).
    *
    * No-op if `node` is already `Hashed` or `Empty`.
    */
  private def hashNode(node: StNode, path: Array[Byte]): Unit = {
    if (node == null || node.typ == Hashed || node.typ == Empty) return

    node.typ match {
      case Leaf =>
        val blob = encodeLeaf(node)
        finalise(node, blob, path)

      case Ext =>
        // Hash the single child first.
        val childPath = appendNibbles(path, node.key, 0, node.key.length)
        hashNode(node.children(0), childPath)
        val blob = encodeExt(node)
        finalise(node, blob, path)

      case Branch =>
        // Hash all non-hashed children in slot order.
        var i = 0
        while (i < 16) {
          val c = node.children(i)
          if (c != null && c.typ != Empty && c.typ != Hashed)
            hashNode(c, appendNibble(path, i.toByte))
          i += 1
        }
        val blob = encodeBranch(node)
        finalise(node, blob, path)

      case _ => // unreachable
    }
  }

  /** Apply the inline-or-hash rule to a finalised node's encoded blob.
    *
    * The root (empty `path`) is always hashed even if its encoded blob is smaller than 32 bytes, because callers depend
    * on a 32-byte root hash.
    */
  private def finalise(node: StNode, blob: Array[Byte], path: Array[Byte]): Unit = {
    if (blob.length < 32 && path.length > 0) {
      // Inline: the parent will splice these bytes directly into its own RLP.
      node.value = blob
    } else {
      val h = crypto.kec256(blob)
      onTrieNode(path, ByteString(h), blob)
      node.value = h
    }
    node.typ = Hashed
    node.key = Array.emptyByteArray
    // Release child references for GC.
    var i = 0
    while (i < 16) { node.children(i) = null; i += 1 }
  }

  // ---- RLP encoding helpers (purpose-built for StNode) ----

  /** Encode a Leaf as `RLP([HP-encoded(key, isLeaf=true), value])`. */
  private def encodeLeaf(node: StNode): Array[Byte] = {
    val hp = HexPrefix.encode(node.key, isLeaf = true)
    encodeListOf2(encodeBytes(hp), encodeBytes(node.value))
  }

  /** Encode an Extension as `RLP([HP-encoded(key, isLeaf=false), childRef])`. */
  private def encodeExt(node: StNode): Array[Byte] = {
    val hp = HexPrefix.encode(node.key, isLeaf = false)
    val childRef = encodeChildRef(node.children(0))
    encodeListOf2(encodeBytes(hp), childRef)
  }

  /** Encode a Branch as `RLP([c0, c1, ..., c15, terminator])`. SNAP sync branches never carry a terminator value (keys
    * are fixed-length 32-byte hashes), so slot 17 is always the empty string.
    */
  private def encodeBranch(node: StNode): Array[Byte] = {
    val refs = new Array[Array[Byte]](17)
    var i = 0
    while (i < 16) {
      refs(i) = encodeChildRef(node.children(i))
      i += 1
    }
    refs(16) = EmptyBytesRlp // terminator value (empty string)
    var total = 0
    i = 0
    while (i < 17) { total += refs(i).length; i += 1 }
    val header = listHeader(total)
    val out = new Array[Byte](header.length + total)
    var pos = 0
    System.arraycopy(header, 0, out, pos, header.length); pos += header.length
    i = 0
    while (i < 17) {
      System.arraycopy(refs(i), 0, out, pos, refs(i).length)
      pos += refs(i).length
      i += 1
    }
    out
  }

  /** Encode a single child reference for inclusion in a parent's RLP list.
    *
    *   - null / Empty → RLP empty string (`0x80`)
    *   - Hashed with 32-byte hash → RLP string of 32 bytes (`0xa0 || hash`)
    *   - Hashed with inline <32B blob → the blob bytes spliced in raw (the blob IS its own RLP encoding)
    */
  private def encodeChildRef(child: StNode): Array[Byte] =
    if (child == null || child.typ == Empty) {
      EmptyBytesRlp
    } else if (child.typ == Hashed) {
      if (child.value.length == 32) encodeBytes(child.value)
      else child.value // inline RLP — splice raw
    } else {
      throw new IllegalStateException(
        s"StackTrie: encoding non-Hashed/non-Empty child (typ=${typeName(child.typ)})"
      )
    }

  /** RLP-encode a single byte string. Standard RLP rules. */
  private def encodeBytes(b: Array[Byte]): Array[Byte] = {
    val len = b.length
    if (len == 1 && (b(0) & 0xff) < 0x80) {
      // Single byte under 0x80 encodes as itself.
      val out = new Array[Byte](1)
      out(0) = b(0)
      out
    } else if (len < 56) {
      val out = new Array[Byte](1 + len)
      out(0) = (0x80 + len).toByte
      System.arraycopy(b, 0, out, 1, len)
      out
    } else {
      val lenBytes = lengthAsBytes(len)
      val out = new Array[Byte](1 + lenBytes.length + len)
      out(0) = (0xb7 + lenBytes.length).toByte
      System.arraycopy(lenBytes, 0, out, 1, lenBytes.length)
      System.arraycopy(b, 0, out, 1 + lenBytes.length, len)
      out
    }
  }

  private def encodeListOf2(a: Array[Byte], b: Array[Byte]): Array[Byte] = {
    val total = a.length + b.length
    val header = listHeader(total)
    val out = new Array[Byte](header.length + total)
    System.arraycopy(header, 0, out, 0, header.length)
    System.arraycopy(a, 0, out, header.length, a.length)
    System.arraycopy(b, 0, out, header.length + a.length, b.length)
    out
  }

  private def listHeader(contentLen: Int): Array[Byte] =
    if (contentLen < 56) {
      val out = new Array[Byte](1)
      out(0) = (0xc0 + contentLen).toByte
      out
    } else {
      val lenBytes = lengthAsBytes(contentLen)
      val out = new Array[Byte](1 + lenBytes.length)
      out(0) = (0xf7 + lenBytes.length).toByte
      System.arraycopy(lenBytes, 0, out, 1, lenBytes.length)
      out
    }

  /** Big-endian, minimum-length encoding of a non-negative length. */
  private def lengthAsBytes(n: Int): Array[Byte] = {
    if (n == 0) return Array.emptyByteArray
    val byteCount = (32 - Integer.numberOfLeadingZeros(n) + 7) / 8
    val out = new Array[Byte](byteCount)
    var i = byteCount - 1
    var v = n
    while (i >= 0) {
      out(i) = (v & 0xff).toByte
      v >>>= 8
      i -= 1
    }
    out
  }
}

object StackTrie {

  // Node-type tags. Sealed-trait-free representation is deliberate: an Int tag
  // lets the dispatch be a tight match without allocating type witnesses.
  type NodeType = Int
  final val Empty: NodeType = 0
  final val Branch: NodeType = 1
  final val Ext: NodeType = 2
  final val Leaf: NodeType = 3
  final val Hashed: NodeType = 4

  /** A node in the StackTrie. Mutable by design — geth's `stNode` is the same.
    *
    * Field meanings depend on `typ`:
    *   - Empty: all fields ignored.
    *   - Leaf: `key` holds the remaining hex-nibble suffix; `value` holds the raw account/slot value.
    *   - Ext: `key` holds the shared hex-nibble prefix; `children(0)` is the single child.
    *   - Branch: `children(0..15)` are the 16 nibble slots; `key` is empty.
    *   - Hashed: `value` is either a 32-byte hash (parent encodes as `RLPValue(hash)`) or a < 32 byte RLP blob (parent
    *     splices raw); `key` and `children` are cleared for GC.
    */
  final class StNode(
      var typ: NodeType,
      var key: Array[Byte],
      var value: Array[Byte],
      val children: Array[StNode]
  )

  object StNode {
    def empty: StNode = new StNode(Empty, Array.emptyByteArray, Array.emptyByteArray, new Array[StNode](16))
    def newLeaf(key: Array[Byte], value: Array[Byte]): StNode =
      new StNode(Leaf, key, value, new Array[StNode](16))
    def newBranch(): StNode =
      new StNode(Branch, Array.emptyByteArray, Array.emptyByteArray, new Array[StNode](16))
    def newExt(key: Array[Byte], child: StNode): StNode = {
      val n = new StNode(Ext, key, Array.emptyByteArray, new Array[StNode](16))
      n.children(0) = child
      n
    }
  }

  // The RLP encoding of an empty trie (an empty string).
  private val EmptyTrieRlp: Array[Byte] = Array(0x80.toByte)
  // RLP encoding of an empty string, used for absent branch slots / terminator.
  private val EmptyBytesRlp: Array[Byte] = Array(0x80.toByte)
  // Reusable empty hex-path buffer.
  private val EmptyPath: Array[Byte] = Array.emptyByteArray

  // ---- small utilities (package-private for tests) ----

  /** Index of the first differing nibble between `a` and `b`, capped at the shorter length. If one is a prefix of the
    * other, returns the shorter length.
    */
  private[mpt] def diffIndex(a: Array[Byte], b: Array[Byte]): Int = {
    val n = math.min(a.length, b.length)
    var i = 0
    while (i < n && a(i) == b(i)) i += 1
    i
  }

  /** Unsigned big-endian byte-array compare. Returns -1/0/1. */
  private[mpt] def byteCompare(a: Array[Byte], b: Array[Byte]): Int = {
    val n = math.min(a.length, b.length)
    var i = 0
    while (i < n) {
      val ai = a(i) & 0xff
      val bi = b(i) & 0xff
      if (ai != bi) return if (ai < bi) -1 else 1
      i += 1
    }
    Integer.compare(a.length, b.length)
  }

  /** Allocate a slice `arr(from .. arr.length)`. */
  private[mpt] def sliceFrom(arr: Array[Byte], from: Int): Array[Byte] = {
    val n = arr.length - from
    if (n <= 0) Array.emptyByteArray
    else {
      val out = new Array[Byte](n)
      System.arraycopy(arr, from, out, 0, n)
      out
    }
  }

  /** Allocate a slice `arr(from until until)`. */
  private[mpt] def sliceRange(arr: Array[Byte], from: Int, until: Int): Array[Byte] = {
    val n = until - from
    if (n <= 0) Array.emptyByteArray
    else {
      val out = new Array[Byte](n)
      System.arraycopy(arr, from, out, 0, n)
      out
    }
  }

  /** Append a single nibble to a path. Allocates a new array. */
  private[mpt] def appendNibble(path: Array[Byte], nibble: Byte): Array[Byte] = {
    val out = new Array[Byte](path.length + 1)
    if (path.length > 0) System.arraycopy(path, 0, out, 0, path.length)
    out(path.length) = nibble
    out
  }

  /** Append the slice `nibbles(from until from+count)` to a path. */
  private[mpt] def appendNibbles(path: Array[Byte], nibbles: Array[Byte], from: Int, count: Int): Array[Byte] =
    if (count <= 0) {
      if (path.length == 0) EmptyPath else path
    } else {
      val out = new Array[Byte](path.length + count)
      if (path.length > 0) System.arraycopy(path, 0, out, 0, path.length)
      System.arraycopy(nibbles, from, out, path.length, count)
      out
    }

  private[mpt] def hexStr(arr: Array[Byte]): String = {
    val sb = new StringBuilder(arr.length)
    var i = 0
    while (i < arr.length) {
      val c = arr(i) & 0x0f
      sb.append(if (c < 10) ('0' + c).toChar else ('a' + (c - 10)).toChar)
      i += 1
    }
    sb.toString
  }

  private def typeName(t: NodeType): String = t match {
    case Empty  => "Empty"
    case Branch => "Branch"
    case Ext    => "Ext"
    case Leaf   => "Leaf"
    case Hashed => "Hashed"
    case _      => s"Unknown($t)"
  }
}
