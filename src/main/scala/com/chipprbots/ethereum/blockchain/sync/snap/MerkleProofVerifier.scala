package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.mpt.MptNode
import com.chipprbots.ethereum.mpt.MptTraversals
import com.chipprbots.ethereum.mpt.Node
import com.chipprbots.ethereum.mpt.LeafNode
import com.chipprbots.ethereum.mpt.ExtensionNode
import com.chipprbots.ethereum.mpt.BranchNode
import com.chipprbots.ethereum.mpt.HashNode
import com.chipprbots.ethereum.mpt.NullNode
import com.chipprbots.ethereum.utils.Logger
import scala.annotation.unused

/** Merkle proof verifier for SNAP sync.
  *
  * Verifies SNAP range proofs using trie reconstruction (go-ethereum VerifyRangeProof algorithm):
  *   1. Build a partial trie by resolving both boundary key paths from proof nodes. 2. Prune internal nodes between the
  *      boundaries (unsetInternal equivalent). 3. Re-insert all response leaves and hash the result. 4. Compare the
  *      reconstructed root hash with the expected root.
  *
  * @param rootHash
  *   Expected root hash (state root for accounts, storage root for slots).
  */
class MerkleProofVerifier(rootHash: ByteString) extends Logger {

  // ─── Public API ────────────────────────────────────────────────────────────

  def verifyAccountRange(
      accounts: Seq[(ByteString, Account)],
      proof: Seq[ByteString],
      startHash: ByteString,
      endHash: ByteString
  ): Either[String, Unit] = {
    if (proof.isEmpty && accounts.isEmpty) return Right(())
    try {
      val leaves = accounts.map { case (h, a) => h -> ByteString(Account.accountSerializer.toBytes(a)) }
      if (proof.isEmpty) {
        // Nil proof: full trie response, verify by streaming hash (geth: StackTrie path)
        return verifyCompleteRange(leaves)
      }
      val proofMap = buildProofMap(proof)
      verifyRangeProofByReconstruction(startHash, endHash, leaves, proofMap)
    } catch {
      case e: Exception =>
        log.warn(s"Merkle proof verification error: ${e.getMessage}")
        Left(s"Verification error: ${e.getMessage}")
    }
  }

  def verifyStorageRange(
      slots: Seq[(ByteString, ByteString)],
      proof: Seq[ByteString],
      startHash: ByteString,
      endHash: ByteString
  ): Either[String, Unit] = {
    if (proof.isEmpty && slots.isEmpty) return Right(())
    try {
      if (proof.isEmpty) {
        return verifyCompleteRange(slots)
      }
      val proofMap = buildProofMap(proof)
      verifyRangeProofByReconstruction(startHash, endHash, slots, proofMap)
    } catch {
      case e: Exception =>
        log.warn(s"Storage Merkle proof verification error: ${e.getMessage}")
        Left(s"Storage verification error: ${e.getMessage}")
    }
  }

  // ─── Reconstruction algorithm ───────────────────────────────────────────────

  private def verifyCompleteRange(leaves: Seq[(ByteString, ByteString)]): Either[String, Unit] = {
    val snapTrie = new SnapHashTrie(_ => ())
    leaves.foreach { case (k, v) => snapTrie.update(k.toArray, v.toArray) }
    val computed = snapTrie.commit()
    if (computed == rootHash) Right(())
    else Left(s"complete-range hash mismatch")
  }

  private def verifyRangeProofByReconstruction(
      firstKey: ByteString,
      lastKey: ByteString,
      leaves: Seq[(ByteString, ByteString)],
      proofMap: Map[ByteString, MptNode]
  ): Either[String, Unit] = {
    // Validate: monotonically strictly increasing keys, no empty values
    for (i <- 0 until leaves.length - 1)
      if (cmpBytes(leaves(i)._1, leaves(i + 1)._1) >= 0)
        return Left("range is not monotonically increasing")
    if (leaves.exists(_._2.isEmpty))
      return Left("range contains deletion (empty value)")

    // Edge case B: proof present, zero leaves — proof of absence
    if (leaves.isEmpty) {
      val rootNode = proofMap.getOrElse(rootHash, return Left("root node missing from proof"))
      val trie = new PartialProofTrie(rootNode, proofMap)
      trie.resolveEdge(hashToNibbles(firstKey), allowNonExistent = true) match {
        case Left(err) => return Left(err)
        case Right(()) => ()
      }
      return if (hasRightElement(trie.root, hashToNibbles(firstKey)))
        Left("more entries available")
      else Right(())
    }

    // Validate: firstKey <= leaves.head
    if (cmpBytes(firstKey, leaves.head._1) > 0)
      return Left("unexpected key-value pairs preceding the requested range")

    // Special case: single element where firstKey == lastKey (existent proof)
    if (leaves.length == 1 && firstKey == lastKey) {
      val rootNode = proofMap.getOrElse(rootHash, return Left("root node missing from proof"))
      val trie = new PartialProofTrie(rootNode, proofMap)
      trie.resolveEdge(hashToNibbles(firstKey), allowNonExistent = false) match {
        case Left(err) => return Left(err)
        case Right(()) => ()
      }
      trie.insertLeaf(hashToNibbles(leaves.head._1), leaves.head._2)
      val computed = trie.computeHash()
      return if (computed == rootHash) Right(())
      else Left(s"single-element range proof hash mismatch")
    }

    if (cmpBytes(firstKey, lastKey) >= 0) return Left("invalid edge keys")
    if (firstKey.length != lastKey.length) return Left("inconsistent edge key lengths")

    val firstNibbles = hashToNibbles(firstKey)
    val lastNibbles = hashToNibbles(lastKey)
    val rootNode = proofMap.getOrElse(rootHash, return Left("root node missing from proof"))
    val trie = new PartialProofTrie(rootNode, proofMap)

    // Phase 1: resolve both edge paths into the partial trie
    trie.resolveEdge(firstNibbles, allowNonExistent = true) match {
      case Left(err) => return Left(err)
      case Right(()) => ()
    }
    trie.resolveEdge(lastNibbles, allowNonExistent = true) match {
      case Left(err) => return Left(err)
      case Right(()) => ()
    }

    // Phase 2: prune internal nodes between boundaries
    trie.pruneInternals(firstNibbles, lastNibbles) match {
      case Left(err) => return Left(err)
      case Right(()) => ()
    }

    // Phase 3: insert all leaves
    leaves.foreach { case (k, v) => trie.insertLeaf(hashToNibbles(k), v) }

    // Phase 4: verify root hash
    val computed = trie.computeHash()
    if (computed == rootHash) Right(())
    else Left(s"range proof hash mismatch")
  }

  // ─── PartialProofTrie ───────────────────────────────────────────────────────

  /** Mutable-state wrapper around an immutable MptNode tree.
    *
    * Implements the three phases of geth VerifyRangeProof:
    *   - resolveEdge → proofToPath
    *   - pruneInternals → unsetInternal + unset
    *   - insertLeaf → MPT put (without storage)
    */
  private class PartialProofTrie(initialRoot: MptNode, proofMap: Map[ByteString, MptNode]) {
    var root: MptNode = initialRoot

    def resolveEdge(keyNibbles: Seq[Int], allowNonExistent: Boolean): Either[String, Unit] =
      resolveEdgePath(root, keyNibbles, allowNonExistent) match {
        case Right(newRoot) => root = newRoot; Right(())
        case Left(err)      => Left(err)
      }

    def pruneInternals(leftNibbles: Seq[Int], rightNibbles: Seq[Int]): Either[String, Unit] =
      findForkAndPrune(root, leftNibbles, rightNibbles, 0) match {
        case Right(newRoot) => root = newRoot; Right(())
        case Left(err)      => Left(err)
      }

    def insertLeaf(keyNibbles: Seq[Int], value: ByteString): Unit =
      root = doInsertLeaf(root, keyNibbles, value)

    def computeHash(): ByteString = ByteString(root.hash)

    // ── Phase 1: resolveEdgePath ─────────────────────────────────────────────

    private def resolveEdgePath(
        node: MptNode,
        remaining: Seq[Int],
        allowNonExistent: Boolean
    ): Either[String, MptNode] = {
      val resolved = node match {
        case HashNode(bytes) =>
          proofMap.get(ByteString(bytes)) match {
            case Some(n) => n
            case None    => return Left(s"proof node missing: ${bytes.take(4).map("%02x".format(_)).mkString}...")
          }
        case other => other
      }
      resolved match {
        case NullNode =>
          if (allowNonExistent) Right(NullNode)
          else Left("node not in trie (null at boundary)")

        case leaf: LeafNode => Right(leaf)

        case branch: BranchNode if remaining.isEmpty => Right(branch)

        case branch: BranchNode =>
          val nibble = remaining.head
          val child = branch.children(nibble)
          child match {
            case NullNode if allowNonExistent => Right(branch)
            case NullNode                     => Left("node not in trie (null child at boundary path)")
            case _ =>
              resolveEdgePath(child, remaining.tail, allowNonExistent).map { newChild =>
                branch.updateChild(nibble, newChild)
              }
          }

        case ext: ExtensionNode =>
          val sharedNibbles = toNibbleSeq(ext.sharedKey)
          if (remaining.startsWith(sharedNibbles)) {
            resolveEdgePath(ext.next, remaining.drop(sharedNibbles.length), allowNonExistent).map { newNext =>
              ExtensionNode(ext.sharedKey, newNext)
            }
          } else if (allowNonExistent) {
            Right(ext)
          } else {
            Left("extension key mismatch in proof")
          }

        case other => Right(other)
      }
    }

    // ── Phase 2: pruneInternals ──────────────────────────────────────────────

    private def findForkAndPrune(node: MptNode, left: Seq[Int], right: Seq[Int], pos: Int): Either[String, MptNode] =
      node match {
        case ext: ExtensionNode =>
          val sharedNibbles = toNibbleSeq(ext.sharedKey)
          val forkLeft = comparePrefix(left, pos, sharedNibbles)
          val forkRight = comparePrefix(right, pos, sharedNibbles)
          if (forkLeft == 0 && forkRight == 0) {
            findForkAndPrune(ext.next, left, right, pos + sharedNibbles.length).map { newNext =>
              ExtensionNode(ext.sharedKey, newNext)
            }
          } else {
            handleExtensionFork(ext, sharedNibbles, left, right, pos, forkLeft, forkRight)
          }

        case branch: BranchNode =>
          if (left(pos) == right(pos)) {
            val nibble = left(pos)
            findForkAndPrune(branch.children(nibble), left, right, pos + 1).map { newChild =>
              branch.updateChild(nibble, newChild)
            }
          } else {
            handleBranchFork(branch, left, right, pos)
          }

        case other => Right(other)
      }

    private def handleBranchFork(
        branch: BranchNode,
        left: Seq[Int],
        right: Seq[Int],
        pos: Int
    ): Either[String, MptNode] = {
      val leftNibble = left(pos)
      val rightNibble = right(pos)
      // Null out all children strictly between the two boundary nibbles
      var b = branch
      for (i <- leftNibble + 1 until rightNibble)
        b = b.updateChild(i, NullNode)
      for {
        newLeftChild <- pruneOneSide(b.children(leftNibble), left, pos + 1, removeLeft = false)
        b2 = b.updateChild(leftNibble, newLeftChild)
        newRightChild <- pruneOneSide(b2.children(rightNibble), right, pos + 1, removeLeft = true)
      } yield b2.updateChild(rightNibble, newRightChild)
    }

    private def handleExtensionFork(
        ext: ExtensionNode,
        sharedNibbles: Seq[Int],
        left: Seq[Int],
        right: Seq[Int],
        pos: Int,
        forkLeft: Int,
        forkRight: Int
    ): Either[String, MptNode] = {
      val sl = math.signum(forkLeft)
      val sr = math.signum(forkRight)
      (sl, sr) match {
        case (-1, -1) => Left("empty range: both boundaries below extension key")
        case (1, 1)   => Left("empty range: both boundaries above extension key")

        case (fl, fr) if fl != 0 && fr != 0 =>
          Right(NullNode) // extension entirely in range (one side < ext, other side > ext)

        case (0, fr) if fr != 0 =>
          // Left matches extension, right is larger → prune right side of extension's subtree
          ext.next match {
            case _: LeafNode => Right(NullNode)
            case _ =>
              pruneOneSide(ext.next, left, pos + sharedNibbles.length, removeLeft = false).map { newNext =>
                if (newNext.isNull) NullNode else ExtensionNode(ext.sharedKey, newNext)
              }
          }

        case (fl, 0) if fl != 0 =>
          // Right matches extension, left is smaller → prune left side
          ext.next match {
            case _: LeafNode => Right(NullNode)
            case _ =>
              pruneOneSide(ext.next, right, pos + sharedNibbles.length, removeLeft = true).map { newNext =>
                if (newNext.isNull) NullNode else ExtensionNode(ext.sharedKey, newNext)
              }
          }

        case _ => Right(ext)
      }
    }

    // Prune one side of a boundary path. Returns the updated subtree, or NullNode to remove it.
    // removeLeft=false: remove subtrees to the RIGHT of the key (used for left boundary).
    // removeLeft=true:  remove subtrees to the LEFT  of the key (used for right boundary).
    private def pruneOneSide(child: MptNode, key: Seq[Int], pos: Int, removeLeft: Boolean): Either[String, MptNode] =
      child match {
        case branch: BranchNode =>
          // Null children on the side being removed
          var b = branch
          if (removeLeft) {
            for (i <- 0 until key(pos)) b = b.updateChild(i, NullNode)
          } else {
            for (i <- key(pos) + 1 until 16) b = b.updateChild(i, NullNode)
          }
          pruneOneSide(b.children(key(pos)), key, pos + 1, removeLeft).map { newKeyChild =>
            b.updateChild(key(pos), newKeyChild)
          }

        case ext: ExtensionNode =>
          val sharedNibbles = toNibbleSeq(ext.sharedKey)
          val keySlice = key.drop(pos).take(sharedNibbles.length)
          if (keySlice != sharedNibbles) {
            // Extension's path diverges from boundary key: sibling check
            val cmp = compareNibbleSeqs(sharedNibbles, keySlice)
            Right(if ((removeLeft && cmp < 0) || (!removeLeft && cmp > 0)) NullNode else child)
          } else {
            pruneOneSide(ext.next, key, pos + sharedNibbles.length, removeLeft).map { newNext =>
              if (newNext.isNull) NullNode else ExtensionNode(ext.sharedKey, newNext)
            }
          }

        case leaf: LeafNode =>
          // Either the boundary leaf itself (remove → will be re-inserted) or a sibling
          val leafNibbles = toNibbleSeq(leaf.key)
          val remaining = key.drop(pos)
          if (leafNibbles == remaining) {
            Right(NullNode) // boundary leaf: remove and re-insert in phase 3
          } else {
            val cmp = compareNibbleSeqs(leafNibbles, remaining)
            Right(if ((removeLeft && cmp < 0) || (!removeLeft && cmp > 0)) NullNode else child)
          }

        case NullNode => Right(NullNode)
        case _        => Right(child)
      }

    // ── Phase 3: insertLeaf ─────────────────────────────────────────────────

    private def doInsertLeaf(node: MptNode, keyNibbles: Seq[Int], value: ByteString): MptNode =
      node match {
        case NullNode =>
          LeafNode(ByteString(keyNibbles.map(_.toByte).toArray), value)

        case leaf: LeafNode =>
          insertIntoLeaf(leaf, keyNibbles, value)

        case branch: BranchNode =>
          if (keyNibbles.isEmpty) {
            BranchNode(branch.children.clone(), Some(value))
          } else {
            val nibble = keyNibbles.head
            val newChild = doInsertLeaf(branch.children(nibble), keyNibbles.tail, value)
            branch.updateChild(nibble, newChild)
          }

        case ext: ExtensionNode =>
          insertIntoExtension(ext, keyNibbles, value)

        case HashNode(_) =>
          throw new IllegalStateException("HashNode in range during leaf insertion — pruneInternals incomplete")

        case _ => node
      }

    // Port of MerklePatriciaTrie.putInLeafNode (stripped of NodeInsertResult / storage)
    private def insertIntoLeaf(leaf: LeafNode, keyNibbles: Seq[Int], value: ByteString): MptNode = {
      val existingNibbles = toNibbleSeq(leaf.key)
      val ml = matchingLength(existingNibbles, keyNibbles)

      if (ml == existingNibbles.length && ml == keyNibbles.length) {
        // Exact key match: replace value
        LeafNode(leaf.key, value)

      } else if (ml == 0) {
        // No common prefix: create branch with both leaves under their first nibbles
        val b0 =
          if (existingNibbles.isEmpty) BranchNode.withValueOnly(leaf.value.toArray)
          else {
            val existingLeaf = LeafNode(ByteString(existingNibbles.tail.map(_.toByte).toArray), leaf.value)
            BranchNode.withSingleChild(existingNibbles.head.toByte, existingLeaf, None)
          }
        doInsertLeaf(b0, keyNibbles, value)

      } else {
        // Common prefix of length ml: wrap in extension → branch
        val prefix = keyNibbles.take(ml)
        val existingSuffix = existingNibbles.drop(ml)
        val newKeySuffix = keyNibbles.drop(ml)
        val b0 =
          if (existingSuffix.isEmpty) BranchNode.withValueOnly(leaf.value.toArray)
          else {
            val existingLeaf = LeafNode(ByteString(existingSuffix.tail.map(_.toByte).toArray), leaf.value)
            BranchNode.withSingleChild(existingSuffix.head.toByte, existingLeaf, None)
          }
        val b1 = doInsertLeaf(b0, newKeySuffix, value)
        ExtensionNode(ByteString(prefix.map(_.toByte).toArray), b1)
      }
    }

    // Port of MerklePatriciaTrie.putInExtensionNode (stripped of NodeInsertResult / storage)
    private def insertIntoExtension(ext: ExtensionNode, keyNibbles: Seq[Int], value: ByteString): MptNode = {
      val sharedNibbles = toNibbleSeq(ext.sharedKey)
      val ml = matchingLength(sharedNibbles, keyNibbles)

      if (ml == 0) {
        // No common prefix: convert extension to branch
        val sharedHead = sharedNibbles.head
        val extChild =
          if (sharedNibbles.length == 1) ext.next
          else ExtensionNode(ByteString(sharedNibbles.tail.map(_.toByte).toArray), ext.next)
        val b0 = BranchNode.withSingleChild(sharedHead.toByte, extChild, None)
        doInsertLeaf(b0, keyNibbles, value)

      } else if (ml == sharedNibbles.length) {
        // Extension key fully matches: recurse into next
        ExtensionNode(ext.sharedKey, doInsertLeaf(ext.next, keyNibbles.drop(ml), value))

      } else {
        // Partial match at ml: split extension into prefix + branch
        val commonPrefix = sharedNibbles.take(ml)
        val extSuffix = sharedNibbles.drop(ml)
        val newKeySuffix = keyNibbles.drop(ml)
        val extTailChild =
          if (extSuffix.length == 1) ext.next
          else ExtensionNode(ByteString(extSuffix.tail.map(_.toByte).toArray), ext.next)
        val b0 = BranchNode.withSingleChild(extSuffix.head.toByte, extTailChild, None)
        val b1 = doInsertLeaf(b0, newKeySuffix, value)
        ExtensionNode(ByteString(commonPrefix.map(_.toByte).toArray), b1)
      }
    }
  }

  // ─── hasRightElement ────────────────────────────────────────────────────────

  // Port of go-ethereum hasRightElement: true if any trie element exists lexicographically
  // after keyNibbles in the resolved partial trie.
  private def hasRightElement(node: MptNode, keyNibbles: Seq[Int]): Boolean = {
    var current = node
    var remaining = keyNibbles
    var found = false
    while (current != null && !found)
      current match {
        case branch: BranchNode =>
          if (remaining.nonEmpty) {
            val n = remaining.head
            if ((n + 1 until 16).exists(!branch.children(_).isNull)) {
              found = true
            } else {
              current = branch.children(n)
              remaining = remaining.tail
            }
          } else {
            current = null
          }

        case ext: ExtensionNode =>
          val sharedNibbles = toNibbleSeq(ext.sharedKey)
          if (!remaining.startsWith(sharedNibbles)) {
            found = compareNibbleSeqs(sharedNibbles, remaining.take(sharedNibbles.length)) > 0
            current = null
          } else {
            current = ext.next
            remaining = remaining.drop(sharedNibbles.length)
          }

        case _: LeafNode => current = null

        case _ => current = null
      }
    found
  }

  // ─── Shared utilities ────────────────────────────────────────────────────────

  private def toNibbleSeq(bs: ByteString): Seq[Int] =
    bs.toArray.toSeq.map(_ & 0xff)

  private def hashToNibbles(hash: ByteString): Seq[Int] =
    hash.flatMap(byte => Seq((byte >> 4) & 0x0f, byte & 0x0f)).map(_.toInt)

  // comparePrefix: compare key.drop(pos).take(len) vs ref nibbles.
  // Returns 0 if equal, negative if key-slice < ref or too short, positive if key-slice > ref.
  private def comparePrefix(key: Seq[Int], pos: Int, ref: Seq[Int]): Int = {
    val slice = key.drop(pos).take(ref.length)
    if (slice.length < ref.length) {
      // Truncated slice — compare what we have then treat as smaller
      val partial = compareNibbleSeqs(slice, ref.take(slice.length))
      if (partial != 0) partial else -1
    } else {
      compareNibbleSeqs(slice, ref)
    }
  }

  private def compareNibbleSeqs(a: Seq[Int], b: Seq[Int]): Int = {
    val len = math.min(a.length, b.length)
    var i = 0
    while (i < len) {
      val d = a(i) - b(i)
      if (d != 0) return d
      i += 1
    }
    a.length - b.length
  }

  private def matchingLength(a: Seq[Int], b: Seq[Int]): Int = {
    var i = 0
    while (i < math.min(a.length, b.length) && a(i) == b(i)) i += 1
    i
  }

  private def cmpBytes(a: ByteString, b: ByteString): Int = {
    val aa = a.toArray
    val bb = b.toArray
    var i = 0
    while (i < math.min(aa.length, bb.length)) {
      val d = (aa(i) & 0xff) - (bb(i) & 0xff)
      if (d != 0) return d
      i += 1
    }
    aa.length - bb.length
  }

  private def buildProofMap(proof: Seq[ByteString]): Map[ByteString, MptNode] =
    proof.map { nodeBytes =>
      try {
        val key = ByteString(Node.hashFn(nodeBytes.toArray))
        val node = MptTraversals.decodeNode(nodeBytes.toArray)
        key -> node
      } catch {
        case e: Exception =>
          throw new IllegalArgumentException(s"Failed to decode proof node: ${e.getMessage}", e)
      }
    }.toMap

  // ─── Legacy traversal methods (dead code — kept for reference, not called) ──

  @unused private def verifyAccountInProof(
      accountHash: ByteString,
      account: Account,
      proofMap: Map[ByteString, MptNode]
  ): Either[String, Unit] =
    traversePath(rootHash, hashToNibbles(accountHash), proofMap, account)

  @unused private def traversePath(
      currentHash: ByteString,
      path: Seq[Int],
      proofMap: Map[ByteString, MptNode],
      expectedAccount: Account
  ): Either[String, Unit] =
    proofMap.get(currentHash) match {
      case None                     => Right(())
      case Some(leafNode: LeafNode) => verifyLeafAccount(leafNode, expectedAccount)
      case Some(branchNode: BranchNode) =>
        if (path.isEmpty) {
          branchNode.terminator match {
            case Some(value) => verifyAccountValue(value, expectedAccount)
            case None        => Left("Path ended at branch without terminator")
          }
        } else {
          val nextIndex = path.head
          branchNode.children.lift(nextIndex) match {
            case Some(nextNode: HashNode) =>
              traversePath(ByteString(nextNode.hash), path.tail, proofMap, expectedAccount)
            case Some(nextNode) =>
              traversePath(ByteString(nextNode.hash), path.tail, proofMap, expectedAccount)
            case None => Left(s"No child at index $nextIndex")
          }
        }
      case Some(extensionNode: ExtensionNode) =>
        val sharedNibbles = extensionNode.sharedKey.map(_.toInt)
        if (path.startsWith(sharedNibbles)) {
          extensionNode.next match {
            case hashNode: HashNode =>
              traversePath(ByteString(hashNode.hash), path.drop(sharedNibbles.length), proofMap, expectedAccount)
            case nextNode =>
              traversePath(ByteString(nextNode.hash), path.drop(sharedNibbles.length), proofMap, expectedAccount)
          }
        } else Left("Path doesn't match extension node")
      case Some(_) => Left("Unexpected node type")
    }

  @unused private def verifyLeafAccount(leaf: LeafNode, expectedAccount: Account): Either[String, Unit] =
    verifyAccountValue(leaf.value, expectedAccount)

  @unused private def verifyAccountValue(value: ByteString, expectedAccount: Account): Either[String, Unit] =
    try {
      val decoded = Account.accountSerializer.fromBytes(value.toArray)
      if (decoded.nonce != expectedAccount.nonce) Left(s"Account nonce mismatch")
      else if (decoded.balance != expectedAccount.balance) Left(s"Account balance mismatch")
      else if (decoded.storageRoot != expectedAccount.storageRoot) Left(s"Account storageRoot mismatch")
      else if (decoded.codeHash != expectedAccount.codeHash) Left(s"Account codeHash mismatch")
      else Right(())
    } catch {
      case e: Exception => Left(s"Failed to decode account value: ${e.getMessage}")
    }

  @unused private def verifyProofRoot(proofNodes: Seq[MptNode]): Either[String, Unit] = {
    if (proofNodes.isEmpty) return Left("Empty proof")
    val firstNodeHash = ByteString(proofNodes.head.hash)
    if (firstNodeHash != rootHash)
      Left(
        s"Proof root mismatch: got ${firstNodeHash.take(4).toArray.map("%02x".format(_)).mkString}... expected ${rootHash.take(4).toArray.map("%02x".format(_)).mkString}..."
      )
    else Right(())
  }

  @unused private def verifyStorageSlotInProof(
      slotHash: ByteString,
      slotValue: ByteString,
      proofMap: Map[ByteString, MptNode]
  ): Either[String, Unit] =
    traverseStoragePath(rootHash, hashToNibbles(slotHash), proofMap, slotValue)

  @unused private def traverseStoragePath(
      currentHash: ByteString,
      path: Seq[Int],
      proofMap: Map[ByteString, MptNode],
      expectedValue: ByteString
  ): Either[String, Unit] =
    proofMap.get(currentHash) match {
      case None =>
        Right(())
      case Some(leafNode: LeafNode) =>
        if (leafNode.value == expectedValue) Right(())
        else Left(s"Storage value mismatch")
      case Some(branchNode: BranchNode) =>
        if (path.isEmpty) {
          branchNode.terminator match {
            case Some(value) =>
              if (value == expectedValue) Right(()) else Left("Storage value mismatch at branch terminator")
            case None => Left("Path ended at branch without terminator")
          }
        } else {
          val nextIndex = path.head
          branchNode.children.lift(nextIndex) match {
            case Some(nextNode: HashNode) =>
              traverseStoragePath(ByteString(nextNode.hash), path.tail, proofMap, expectedValue)
            case Some(nextNode) =>
              traverseStoragePath(ByteString(nextNode.hash), path.tail, proofMap, expectedValue)
            case None => Left(s"No child at index $nextIndex")
          }
        }
      case Some(extensionNode: ExtensionNode) =>
        val sharedNibbles = extensionNode.sharedKey.map(_.toInt)
        if (path.startsWith(sharedNibbles)) {
          extensionNode.next match {
            case hashNode: HashNode =>
              traverseStoragePath(ByteString(hashNode.hash), path.drop(sharedNibbles.length), proofMap, expectedValue)
            case nextNode =>
              traverseStoragePath(ByteString(nextNode.hash), path.drop(sharedNibbles.length), proofMap, expectedValue)
          }
        } else Left("Path doesn't match extension node")
      case Some(_) => Left("Unexpected node type")
    }

  @unused private def validateStorageSlotsBasic(
      slots: Seq[(ByteString, ByteString)],
      @unused startHash: ByteString,
      @unused endHash: ByteString
  ): Either[String, Unit] = {
    var i = 1
    while (i < slots.size) {
      if (cmpBytes(slots(i - 1)._1, slots(i)._1) >= 0) return Left("Storage slots not monotonically increasing")
      i += 1
    }
    Right(())
  }
}

object MerkleProofVerifier {
  def apply(rootHash: ByteString): MerkleProofVerifier = new MerkleProofVerifier(rootHash)
}
