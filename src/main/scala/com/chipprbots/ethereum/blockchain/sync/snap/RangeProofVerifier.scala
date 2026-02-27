package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.util.ByteString

import scala.collection.mutable

import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.mpt._
import com.chipprbots.ethereum.utils.Logger

/** Full range proof verification for SNAP sync responses.
  *
  * Implements the core algorithm used by geth (trie.VerifyRangeProof), Nethermind (SnapProviderHelper.CommitRange), and
  * Besu (WorldStateProofProvider.isValidRangeProof):
  *
  *   1. Reconstruct a partial trie skeleton from the proof nodes 2. Unset all interior references between the left and
  *      right boundary keys 3. Insert all received data into the skeleton 4. Verify the resulting root hash matches the
  *      expected root
  *
  * This ensures the delivered data is the COMPLETE set of keys in the proven range — no accounts/slots were skipped.
  */
object RangeProofVerifier extends Logger {

  /** Verify that the given key-value pairs represent the complete data in the trie within the proven range.
    *
    * @param expectedRoot
    *   The expected trie root hash (32 bytes)
    * @param firstKey
    *   First key in the delivered range (32 bytes), or the requested startHash if data is empty
    * @param lastKey
    *   Last key in the delivered range (32 bytes), or firstKey if data is empty
    * @param keys
    *   All delivered keys (32 bytes each, sorted ascending)
    * @param values
    *   Corresponding values (RLP-encoded account bodies or storage slot values)
    * @param proof
    *   RLP-encoded proof nodes (boundary proofs from the peer)
    * @return
    *   Right(true) if the range is the entire trie (no more data exists), Right(false) if more data may follow, or
    *   Left(error) on verification failure
    */
  def verifyRangeProof(
      expectedRoot: Array[Byte],
      firstKey: Array[Byte],
      lastKey: Array[Byte],
      keys: Seq[Array[Byte]],
      values: Seq[Array[Byte]],
      proof: Seq[Array[Byte]]
  ): Either[String, Boolean] = {

    val emptyRoot = MerklePatriciaTrie.EmptyRootHash

    // Case 1: No proof, no data → only valid for empty trie
    if (proof.isEmpty && keys.isEmpty) {
      return if (expectedRoot.sameElements(emptyRoot)) Right(true)
      else Left("No data and no proof for non-empty trie root")
    }

    // Case 2: No proof, has data → entire trie was delivered (origin was 0x00..0)
    if (proof.isEmpty && keys.nonEmpty) {
      return verifyEntireTrie(expectedRoot, keys, values)
    }

    // Case 3: Proof present
    try {
      // Decode proof nodes and build hash → node map
      val proofMap = mutable.Map.empty[ByteString, MptNode]
      proof.foreach { encoded =>
        val node = MptTraversals.decodeNode(encoded)
        proofMap(ByteString(node.hash)) = node
      }

      // The root must be in the proof
      val rootKey = ByteString(expectedRoot)
      if (!proofMap.contains(rootKey)) {
        return Left(
          s"Expected root hash ${rootKey.take(4).toArray.map("%02x".format(_)).mkString}... not found in proof"
        )
      }

      // Reconstruct the proof skeleton: resolve HashNodes that are in the proof, leave others as HashNode references
      val skeleton = reconstructFromProof(proofMap(rootKey), proofMap.toMap)

      // Convert keys to nibble paths for boundary operations
      val firstNibbles = HexPrefix.bytesToNibbles(firstKey)
      val lastNibbles = HexPrefix.bytesToNibbles(lastKey)

      // Unset all interior references between the two boundary paths.
      // After this, the skeleton retains external structure (outside the range)
      // but has NullNodes where the delivered data should be.
      val cleared = if (keys.isEmpty) {
        // Proof-of-absence: verify that no keys exist in the proven range.
        // We still unset the range and verify that the root matches.
        unsetRange(skeleton, firstNibbles, lastNibbles, 0)
      } else {
        unsetRange(skeleton, firstNibbles, lastNibbles, 0)
      }

      // Store the skeleton into in-memory storage, then build a trie and insert all data
      val storage = new InMemoryProofStorage()
      storeNodeRecursive(cleared, storage)

      var trie = MerklePatriciaTrie[ByteString, ByteString](cleared.hash, storage)

      // Insert all delivered data
      var i = 0
      while (i < keys.size) {
        trie = trie.put(ByteString(keys(i)), ByteString(values(i)))
        i += 1
      }

      // Compare root hash
      val computedRoot = trie.getRootHash
      if (computedRoot.sameElements(expectedRoot)) {
        // Check if this was the last range: if the right proof path terminates
        // (all nodes to the right are null), there's no more data.
        val hasMore = hasRightEdge(skeleton, lastNibbles, 0)
        Right(!hasMore)
      } else {
        Left(
          s"Range proof verification failed: computed root ${ByteString(computedRoot).take(4).toArray.map("%02x".format(_)).mkString}... " +
            s"does not match expected ${ByteString(expectedRoot).take(4).toArray.map("%02x".format(_)).mkString}..."
        )
      }

    } catch {
      case e: MerklePatriciaTrie.MPTException =>
        Left(s"Trie error during range proof verification: ${e.getMessage}")
      case e: Exception =>
        Left(s"Range proof verification error: ${e.getMessage}")
    }
  }

  /** Verify that keys+values reconstruct the entire trie (no proof needed). */
  private def verifyEntireTrie(
      expectedRoot: Array[Byte],
      keys: Seq[Array[Byte]],
      values: Seq[Array[Byte]]
  ): Either[String, Boolean] = {
    try {
      val storage = new InMemoryProofStorage()
      var trie = MerklePatriciaTrie[ByteString, ByteString](storage)

      var i = 0
      while (i < keys.size) {
        trie = trie.put(ByteString(keys(i)), ByteString(values(i)))
        i += 1
      }

      if (trie.getRootHash.sameElements(expectedRoot)) Right(true)
      else
        Left(
          s"Full trie verification failed: computed root does not match expected root"
        )
    } catch {
      case e: Exception =>
        Left(s"Error verifying entire trie: ${e.getMessage}")
    }
  }

  /** Recursively reconstruct a partial trie from proof nodes.
    *
    * HashNodes that appear in the proof map are resolved (expanded). HashNodes NOT in the proof map are kept as-is
    * (they reference external subtrees outside the proven range).
    */
  private def reconstructFromProof(
      node: MptNode,
      proofMap: Map[ByteString, MptNode]
  ): MptNode =
    node match {
      case h: HashNode =>
        proofMap.get(ByteString(h.hashNode)) match {
          case Some(resolved) => reconstructFromProof(resolved, proofMap)
          case None           => h // External reference — keep as hash
        }

      case b: BranchNode =>
        val newChildren = new Array[MptNode](16)
        var i = 0
        while (i < 16) {
          newChildren(i) = reconstructFromProof(b.children(i), proofMap)
          i += 1
        }
        BranchNode(newChildren, b.terminator)

      case e: ExtensionNode =>
        ExtensionNode(e.sharedKey, reconstructFromProof(e.next, proofMap))

      case other => other // LeafNode, NullNode — keep as-is
    }

  /** Unset (nullify) all trie references that fall strictly between the left and right boundary paths.
    *
    * At each branch node on the boundary:
    *   - Children before the left nibble: keep (they're outside the range, to the left)
    *   - Children after the right nibble: keep (they're outside the range, to the right)
    *   - Children between the left and right nibbles: set to NullNode (inside the range)
    *   - Children at exactly the left or right nibble: recurse
    */
  private def unsetRange(
      node: MptNode,
      leftKey: Array[Byte],
      rightKey: Array[Byte],
      depth: Int
  ): MptNode =
    node match {
      case b: BranchNode =>
        val leftNibble = if (depth < leftKey.length) leftKey(depth) & 0xff else -1
        val rightNibble = if (depth < rightKey.length) rightKey(depth) & 0xff else 16

        val newChildren = new Array[MptNode](16)
        var i = 0
        while (i < 16) {
          if (i > leftNibble && i < rightNibble) {
            // Strictly inside the range — nullify
            newChildren(i) = NullNode
          } else if (i == leftNibble && i == rightNibble) {
            // Both boundaries on same child — recurse with both
            newChildren(i) = unsetRange(b.children(i), leftKey, rightKey, depth + 1)
          } else if (i == leftNibble) {
            // Left boundary — unset everything to the right of the left path
            newChildren(i) = unsetRightOfPath(b.children(i), leftKey, depth + 1)
          } else if (i == rightNibble) {
            // Right boundary — unset everything to the left of the right path
            newChildren(i) = unsetLeftOfPath(b.children(i), rightKey, depth + 1)
          } else {
            // Outside range — keep as-is
            newChildren(i) = b.children(i)
          }
          i += 1
        }

        // Handle terminator (value stored at this branch)
        val newTerminator = if (leftNibble < 0 && rightNibble >= 0) {
          // The empty-suffix key at this depth is inside the range — unset
          None
        } else {
          b.terminator
        }

        BranchNode(newChildren, newTerminator)

      case e: ExtensionNode =>
        val sharedLen = e.sharedKey.length
        if (depth + sharedLen <= leftKey.length && depth + sharedLen <= rightKey.length) {
          // Extension shared key is a prefix of both boundaries — recurse into next
          val leftMatch = leftKey.slice(depth, depth + sharedLen)
          val rightMatch = rightKey.slice(depth, depth + sharedLen)
          val sharedBytes = e.sharedKey.toArray

          if (java.util.Arrays.equals(sharedBytes, leftMatch) && java.util.Arrays.equals(sharedBytes, rightMatch)) {
            // Both boundaries pass through this extension — recurse
            ExtensionNode(e.sharedKey, unsetRange(e.next, leftKey, rightKey, depth + sharedLen))
          } else if (java.util.Arrays.equals(sharedBytes, leftMatch)) {
            // Only left boundary passes through — unset right of left path
            ExtensionNode(e.sharedKey, unsetRightOfPath(e.next, leftKey, depth + sharedLen))
          } else if (java.util.Arrays.equals(sharedBytes, rightMatch)) {
            // Only right boundary passes through — unset left of right path
            ExtensionNode(e.sharedKey, unsetLeftOfPath(e.next, rightKey, depth + sharedLen))
          } else {
            // Extension key diverges from both boundaries — entire subtree is inside range
            NullNode
          }
        } else {
          // Boundary keys are shorter than extension — keep as-is
          node
        }

      case _: LeafNode =>
        // Leaf inside the range — nullify (will be re-inserted from delivered data)
        NullNode

      case _ => node
    }

  /** Unset everything to the RIGHT of the left boundary path. */
  private def unsetRightOfPath(
      node: MptNode,
      leftKey: Array[Byte],
      depth: Int
  ): MptNode =
    node match {
      case b: BranchNode =>
        val leftNibble = if (depth < leftKey.length) leftKey(depth) & 0xff else -1

        val newChildren = new Array[MptNode](16)
        var i = 0
        while (i < 16) {
          if (i > leftNibble) {
            newChildren(i) = NullNode
          } else if (i == leftNibble) {
            newChildren(i) = unsetRightOfPath(b.children(i), leftKey, depth + 1)
          } else {
            newChildren(i) = b.children(i)
          }
          i += 1
        }
        BranchNode(newChildren, b.terminator)

      case e: ExtensionNode =>
        val sharedLen = e.sharedKey.length
        if (depth + sharedLen <= leftKey.length) {
          val leftMatch = leftKey.slice(depth, depth + sharedLen)
          if (java.util.Arrays.equals(e.sharedKey.toArray, leftMatch)) {
            ExtensionNode(e.sharedKey, unsetRightOfPath(e.next, leftKey, depth + sharedLen))
          } else {
            // Extension diverges — compare first differing nibble
            val sharedBytes = e.sharedKey.toArray
            var j = 0
            while (j < sharedLen && sharedBytes(j) == leftMatch(j)) j += 1
            if (j < sharedLen && (sharedBytes(j) & 0xff) > (leftMatch(j) & 0xff)) {
              NullNode // Entire subtree is to the right of left boundary
            } else {
              node // Entire subtree is to the left — keep
            }
          }
        } else {
          node
        }

      case _: LeafNode =>
        // The leaf's key position relative to leftKey determines if it should be unset
        // Since we're recursing from the boundary, this leaf is at or after the boundary
        NullNode

      case _ => node
    }

  /** Unset everything to the LEFT of the right boundary path. */
  private def unsetLeftOfPath(
      node: MptNode,
      rightKey: Array[Byte],
      depth: Int
  ): MptNode =
    node match {
      case b: BranchNode =>
        val rightNibble = if (depth < rightKey.length) rightKey(depth) & 0xff else 16

        val newChildren = new Array[MptNode](16)
        var i = 0
        while (i < 16) {
          if (i < rightNibble) {
            newChildren(i) = NullNode
          } else if (i == rightNibble) {
            newChildren(i) = unsetLeftOfPath(b.children(i), rightKey, depth + 1)
          } else {
            newChildren(i) = b.children(i)
          }
          i += 1
        }
        // Terminator is at the empty-suffix key, which sorts before all children — unset it
        BranchNode(newChildren, None)

      case e: ExtensionNode =>
        val sharedLen = e.sharedKey.length
        if (depth + sharedLen <= rightKey.length) {
          val rightMatch = rightKey.slice(depth, depth + sharedLen)
          if (java.util.Arrays.equals(e.sharedKey.toArray, rightMatch)) {
            ExtensionNode(e.sharedKey, unsetLeftOfPath(e.next, rightKey, depth + sharedLen))
          } else {
            val sharedBytes = e.sharedKey.toArray
            var j = 0
            while (j < sharedLen && sharedBytes(j) == rightMatch(j)) j += 1
            if (j < sharedLen && (sharedBytes(j) & 0xff) < (rightMatch(j) & 0xff)) {
              NullNode // Entire subtree is to the left of right boundary
            } else {
              node // Entire subtree is to the right — keep
            }
          }
        } else {
          node
        }

      case _: LeafNode =>
        NullNode

      case _ => node
    }

  /** Check if there are any nodes to the right of the given key path (indicating more data exists). */
  private def hasRightEdge(node: MptNode, key: Array[Byte], depth: Int): Boolean =
    node match {
      case b: BranchNode =>
        val nibble = if (depth < key.length) key(depth) & 0xff else 16
        // Check if any child after the key's nibble is non-null
        var i = nibble + 1
        while (i < 16) {
          if (!b.children(i).isNull) return true
          i += 1
        }
        // Recurse into the boundary child
        if (nibble < 16 && !b.children(nibble).isNull) {
          hasRightEdge(b.children(nibble), key, depth + 1)
        } else {
          false
        }

      case e: ExtensionNode =>
        val sharedLen = e.sharedKey.length
        if (depth + sharedLen <= key.length) {
          val keyMatch = key.slice(depth, depth + sharedLen)
          if (java.util.Arrays.equals(e.sharedKey.toArray, keyMatch)) {
            hasRightEdge(e.next, key, depth + sharedLen)
          } else {
            // Extension diverges
            val sharedBytes = e.sharedKey.toArray
            var j = 0
            while (j < sharedLen && sharedBytes(j) == keyMatch(j)) j += 1
            j < sharedLen && (sharedBytes(j) & 0xff) > (keyMatch(j) & 0xff)
          }
        } else {
          true // Extension goes deeper than key
        }

      case _: LeafNode => false
      case _: HashNode => true // External reference exists to the right
      case _           => false
    }

  /** Store a node tree recursively into the in-memory storage. */
  private def storeNodeRecursive(node: MptNode, storage: InMemoryProofStorage): Unit =
    node match {
      case l: LeafNode =>
        storage.storeNode(l)
      case e: ExtensionNode =>
        storage.storeNode(e)
        storeNodeRecursive(e.next, storage)
      case b: BranchNode =>
        storage.storeNode(b)
        b.children.foreach(c => storeNodeRecursive(c, storage))
      case _: HashNode =>
        // Do NOT store external HashNode references. They are opaque pointers to
        // subtrees outside the proof range and should never be resolved during
        // data insertion. If the trie accidentally hits one, a MissingNodeException
        // is a better fail-safe than an infinite loop.
        ()
      case NullNode =>
      // Nothing to store
    }

  /** In-memory MptStorage for proof verification. */
  private[snap] class InMemoryProofStorage extends MptStorage {
    val nodes: mutable.Map[ByteString, MptNode] = mutable.Map.empty

    override def get(nodeId: Array[Byte]): MptNode = {
      val key = ByteString(nodeId)
      nodes.getOrElse(key, throw new MerklePatriciaTrie.MissingNodeException(key))
    }

    override def updateNodesInStorage(
        newRoot: Option[MptNode],
        toRemove: Seq[MptNode]
    ): Option[MptNode] = {
      newRoot.foreach(storeNodeRecursive(_, this))
      newRoot
    }

    override def persist(): Unit = {}

    def storeNode(node: MptNode): Unit = {
      if (!node.isNull) {
        nodes(ByteString(node.hash)) = node
      }
    }
  }
}
