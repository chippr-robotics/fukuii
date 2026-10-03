package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.mpt.BranchNode
import com.chipprbots.ethereum.mpt.ExtensionNode
import com.chipprbots.ethereum.mpt.HashNode
import com.chipprbots.ethereum.mpt.LeafNode
import com.chipprbots.ethereum.mpt.MptNode
import com.chipprbots.ethereum.mpt.MptTraversals
import com.chipprbots.ethereum.mpt.Node
import com.chipprbots.ethereum.mpt.NullNode
import com.chipprbots.ethereum.mpt.ProofTrieInserter
import com.chipprbots.ethereum.utils.Logger

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
class MerkleProofVerifier(rootHash: ByteString) extends Logger:

  // ─── Public API ────────────────────────────────────────────────────────────

  /** Emit the invalid-proof counter for any verification failure surfaced to callers. */
  private def counted(result: Either[String, Unit]): Either[String, Unit] =
    if result.isLeft then SNAPSyncMetrics.incrementInvalidProof()
    result

  def verifyAccountRange(
      accounts: Seq[(ByteString, Account)],
      proof: Seq[ByteString],
      startHash: ByteString,
      endHash: ByteString
  ): Either[String, Unit] =
    if proof.isEmpty && accounts.isEmpty then Right(())
    else
      try
        val leaves = accounts.map { case (h, a) => h -> ByteString(Account.accountSerializer.toBytes(a)) }
        if proof.isEmpty then
          // Nil proof: full trie response, verify by streaming hash (geth: StackTrie path)
          counted(verifyCompleteRange(leaves))
        else
          val proofRawMap = buildProofRawMap(proof)
          counted(verifyRangeProofByReconstruction(startHash, endHash, leaves, proofRawMap))
      catch
        case e: Throwable =>
          // Catch Throwable (not just Exception) — StackOverflowError from deep recursive insertion
          // must be surfaced as a verification failure rather than silently hanging the caller.
          log.warn(s"Merkle proof verification error: ${e.getClass.getSimpleName}: ${e.getMessage}")
          counted(Left(s"Verification error: ${e.getClass.getSimpleName}: ${e.getMessage}"))

  def verifyStorageRange(
      slots: Seq[(ByteString, ByteString)],
      proof: Seq[ByteString],
      startHash: ByteString,
      endHash: ByteString
  ): Either[String, Unit] =
    if proof.isEmpty && slots.isEmpty then Right(())
    else
      try
        if proof.isEmpty then counted(verifyCompleteRange(slots))
        else
          val proofRawMap = buildProofRawMap(proof)
          counted(verifyRangeProofByReconstruction(startHash, endHash, slots, proofRawMap))
      catch
        case e: Throwable =>
          log.warn(s"Storage Merkle proof verification error: ${e.getClass.getSimpleName}: ${e.getMessage}")
          counted(Left(s"Storage verification error: ${e.getClass.getSimpleName}: ${e.getMessage}"))

  // ─── Reconstruction algorithm ───────────────────────────────────────────────

  private def verifyCompleteRange(leaves: Seq[(ByteString, ByteString)]): Either[String, Unit] =
    // Explicit pre-validation, mirroring the checks verifyRangeProofByReconstruction already
    // applies on the proof-present path below. Without this, a single peer response with
    // non-ascending keys (or an empty/deletion value) would reach SnapHashTrie.update ->
    // StackTrie.update and rely on that method's `require` throwing to be rejected. The throw is
    // still caught by verifyStorageRange/verifyAccountRange's surrounding try/catch — it can never
    // escape to the caller — but validating explicitly here makes peer-data rejection an
    // intentional, expected outcome instead of exception-driven control flow, and avoids building
    // a doomed temporary trie first. Never weakens StackTrie's own `require`; this only stops
    // clearly-invalid input from reaching it.
    if (0 until leaves.length - 1).exists(i => cmpBytes(leaves(i)._1, leaves(i + 1)._1) >= 0) then
      Left("range is not monotonically increasing")
    else if leaves.exists(_._2.isEmpty) then Left("range contains deletion (empty value)")
    else
      val snapTrie = new SnapHashTrie(_ => ())
      leaves.foreach { case (k, v) => snapTrie.update(k.toArray, v.toArray) }
      val computed = snapTrie.commit()
      if computed == rootHash then Right(())
      else Left(s"complete-range hash mismatch")

  private def verifyRangeProofByReconstruction(
      firstKey: ByteString,
      lastKey: ByteString,
      leaves: Seq[(ByteString, ByteString)],
      proofRawMap: Map[ByteString, Array[Byte]]
  ): Either[String, Unit] =
    // Validate: monotonically strictly increasing keys, no empty values
    if (0 until leaves.length - 1).exists(i => cmpBytes(leaves(i)._1, leaves(i + 1)._1) >= 0) then
      Left("range is not monotonically increasing")
    else if leaves.exists(_._2.isEmpty) then Left("range contains deletion (empty value)")
    // Edge case B: proof present, zero leaves — proof of absence
    else if leaves.isEmpty then
      decodeProofNode(proofRawMap, rootHash).toRight("root node missing from proof").flatMap { rootNode =>
        val trie = new PartialProofTrie(rootNode, proofRawMap)
        trie.resolveEdge(hashToNibbles(firstKey), allowNonExistent = true).flatMap { _ =>
          if hasRightElement(trie.root, hashToNibbles(firstKey)) then Left("more entries available")
          else Right(())
        }
      }
    // Validate: firstKey <= leaves.head
    else if cmpBytes(firstKey, leaves.head._1) > 0 then Left("unexpected key-value pairs preceding the requested range")
    // Special case: single element where firstKey == lastKey (existent proof)
    else if leaves.length == 1 && firstKey == lastKey then
      decodeProofNode(proofRawMap, rootHash).toRight("root node missing from proof").flatMap { rootNode =>
        val trie = new PartialProofTrie(rootNode, proofRawMap)
        trie.resolveEdge(hashToNibbles(firstKey), allowNonExistent = false).flatMap { _ =>
          trie.insertLeaf(hashToNibbles(leaves.head._1), leaves.head._2)
          val computed = trie.computeHash()
          if computed == rootHash then Right(())
          else Left(s"single-element range proof hash mismatch")
        }
      }
    else if cmpBytes(firstKey, lastKey) >= 0 then Left("invalid edge keys")
    else if firstKey.length != lastKey.length then Left("inconsistent edge key lengths")
    else
      val firstNibbles = hashToNibbles(firstKey)
      val lastNibbles = hashToNibbles(lastKey)
      decodeProofNode(proofRawMap, rootHash).toRight("root node missing from proof").flatMap { rootNode =>
        val trie = new PartialProofTrie(rootNode, proofRawMap)
        // Phase 1: resolve both edge paths into the partial trie
        log.debug(s"[PROOF] Phase 1: resolving edge paths (${leaves.size} leaves, ${proofRawMap.size} proof nodes)")
        for
          _ <- trie.resolveEdge(firstNibbles, allowNonExistent = true)
          _ <- trie.resolveEdge(lastNibbles, allowNonExistent = true)
          // Phase 2: prune internal nodes between boundaries
          _ = log.debug(s"[PROOF] Phase 2: pruning internal nodes between boundaries")
          _ <- trie.pruneInternals(firstNibbles, lastNibbles)
          // Phase 3: insert all leaves (mutable StackTrie-based, O(N) allocations — see ProofTrieInserter)
          _ = log.debug(s"[PROOF] Phase 3: inserting ${leaves.size} leaves")
          _ = leaves.foreach { case (k, v) => trie.insertLeaf(hashToNibbles(k), v) }
          // Phase 4: verify root hash
          _ = log.debug(s"[PROOF] Phase 4: computing root hash")
          computed = trie.computeHash()
          result: Either[String, Unit] = if computed == rootHash then Right(()) else Left(s"range proof hash mismatch")
          _ <- result
        yield ()
      }

  // ─── PartialProofTrie ───────────────────────────────────────────────────────

  /** Mutable-state wrapper around an immutable MptNode tree.
    *
    * Implements the three phases of geth VerifyRangeProof:
    *   - resolveEdge → proofToPath
    *   - pruneInternals → unsetInternal + unset
    *   - insertLeaf → MPT put (without storage)
    */
  // Decode a proof node fresh from raw bytes — mirrors go-ethereum's resolveNode which calls
  // decodeNode(hash, buf) and returns a new allocation each time. Never returns a cached object.
  private def decodeProofNode(proofRawMap: Map[ByteString, Array[Byte]], hash: ByteString): Option[MptNode] =
    proofRawMap.get(hash).map { rawBytes =>
      try MptTraversals.decodeNode(rawBytes)
      catch case e: Exception => throw new IllegalArgumentException(s"Failed to decode proof node: ${e.getMessage}", e)
    }

  private class PartialProofTrie(initialRoot: MptNode, proofRawMap: Map[ByteString, Array[Byte]]):
    var root: MptNode = initialRoot

    def resolveEdge(keyNibbles: Seq[Int], allowNonExistent: Boolean): Either[String, Unit] =
      resolveEdgePath(root, keyNibbles, allowNonExistent) match
        case Right(newRoot) => root = newRoot; Right(())
        case Left(err)      => Left(err)

    def pruneInternals(leftNibbles: Seq[Int], rightNibbles: Seq[Int]): Either[String, Unit] =
      findForkAndPrune(root, leftNibbles, rightNibbles, 0) match
        case Right(newRoot) => root = newRoot; Right(())
        case Left(err)      => Left(err)

    // Lazily initialised after Phase 1+2 have finalised `root` — converts the pruned MptNode tree to StackTrie's
    // mutable StNode representation for O(N) Phase 3 insertion (vs O(N×depth) with the old doInsertLeaf).
    private lazy val inserter: ProofTrieInserter = new ProofTrieInserter(root)

    def insertLeaf(keyNibbles: Seq[Int], value: ByteString): Unit =
      inserter.insert(keyNibbles.map(_.toByte).toArray, value.toArray)

    def computeHash(): ByteString = inserter.computeHash()

    // ── Phase 1: resolveEdgePath ─────────────────────────────────────────────

    private def resolveEdgePath(
        node: MptNode,
        remaining: Seq[Int],
        allowNonExistent: Boolean
    ): Either[String, MptNode] =
      val resolvedEither: Either[String, MptNode] = node match
        case HashNode(bytes) =>
          // Decode fresh on every lookup — mirrors go-ethereum's resolveNode which calls
          // decodeNode(hash, buf) and returns a new Go struct each time. This ensures
          // both boundary paths (firstKey, lastKey) get INDEPENDENT node objects even
          // when they share a proof node by hash. Without fresh decoding, both paths
          // reference the same cached MptNode object, allowing a child pointer to become
          // an ancestor reference — forming a cycle that Phase 3 traverses forever.
          proofRawMap.get(ByteString(bytes)) match
            case Some(rawBytes) =>
              try Right(MptTraversals.decodeNode(rawBytes))
              catch
                case e: Exception =>
                  Left(s"Failed to decode proof node ${bytes.take(4).map("%02x".format(_)).mkString}: ${e.getMessage}")
            case None => Left(s"proof node missing: ${bytes.take(4).map("%02x".format(_)).mkString}...")
        case other => Right(other)
      resolvedEither.flatMap { resolved =>
        resolved match
          case NullNode =>
            if allowNonExistent then Right(NullNode)
            else Left("node not in trie (null at boundary)")

          case leaf: LeafNode => Right(leaf)

          case branch: BranchNode if remaining.isEmpty => Right(branch)

          case branch: BranchNode =>
            val nibble = remaining.head
            val child = branch.children(nibble)
            child match
              case NullNode if allowNonExistent => Right(branch)
              case NullNode                     => Left("node not in trie (null child at boundary path)")
              case _ =>
                resolveEdgePath(child, remaining.tail, allowNonExistent).map { newChild =>
                  branch.updateChild(nibble, newChild)
                }

          case ext: ExtensionNode =>
            val sharedNibbles = toNibbleSeq(ext.sharedKey)
            if remaining.startsWith(sharedNibbles) then
              resolveEdgePath(ext.next, remaining.drop(sharedNibbles.length), allowNonExistent).map { newNext =>
                ExtensionNode(ext.sharedKey, newNext)
              }
            else if allowNonExistent then Right(ext)
            else Left("extension key mismatch in proof")

          case other => Right(other)
      }

    // ── Phase 2: pruneInternals ──────────────────────────────────────────────

    private def findForkAndPrune(node: MptNode, left: Seq[Int], right: Seq[Int], pos: Int): Either[String, MptNode] =
      node match
        case ext: ExtensionNode =>
          val sharedNibbles = toNibbleSeq(ext.sharedKey)
          val forkLeft = comparePrefix(left, pos, sharedNibbles)
          val forkRight = comparePrefix(right, pos, sharedNibbles)
          if forkLeft == 0 && forkRight == 0 then
            findForkAndPrune(ext.next, left, right, pos + sharedNibbles.length).map { newNext =>
              ExtensionNode(ext.sharedKey, newNext)
            }
          else handleExtensionFork(ext, sharedNibbles, left, right, pos, forkLeft, forkRight)

        case branch: BranchNode =>
          if left(pos) == right(pos) then
            val nibble = left(pos)
            findForkAndPrune(branch.children(nibble), left, right, pos + 1).map { newChild =>
              branch.updateChild(nibble, newChild)
            }
          else handleBranchFork(branch, left, right, pos)

        case other => Right(other)

    private def handleBranchFork(
        branch: BranchNode,
        left: Seq[Int],
        right: Seq[Int],
        pos: Int
    ): Either[String, MptNode] =
      val leftNibble = left(pos)
      val rightNibble = right(pos)
      // Null out all children strictly between the two boundary nibbles
      var b = branch
      for i <- leftNibble + 1 until rightNibble do b = b.updateChild(i, NullNode)
      for
        newLeftChild <- pruneOneSide(b.children(leftNibble), left, pos + 1, removeLeft = false)
        b2 = b.updateChild(leftNibble, newLeftChild)
        newRightChild <- pruneOneSide(b2.children(rightNibble), right, pos + 1, removeLeft = true)
      yield b2.updateChild(rightNibble, newRightChild)

    private def handleExtensionFork(
        ext: ExtensionNode,
        sharedNibbles: Seq[Int],
        left: Seq[Int],
        right: Seq[Int],
        pos: Int,
        forkLeft: Int,
        forkRight: Int
    ): Either[String, MptNode] =
      val sl = math.signum(forkLeft)
      val sr = math.signum(forkRight)
      (sl, sr) match
        case (-1, -1) => Left("empty range: both boundaries below extension key")
        case (1, 1)   => Left("empty range: both boundaries above extension key")

        case (fl, fr) if fl != 0 && fr != 0 =>
          Right(NullNode) // extension entirely in range (one side < ext, other side > ext)

        case (0, fr) if fr != 0 =>
          // Left matches extension, right is larger → prune right side of extension's subtree
          ext.next match
            case _: LeafNode => Right(NullNode)
            case _ =>
              pruneOneSide(ext.next, left, pos + sharedNibbles.length, removeLeft = false).map { newNext =>
                if newNext.isNull then NullNode else ExtensionNode(ext.sharedKey, newNext)
              }

        case (fl, 0) if fl != 0 =>
          // Right matches extension, left is smaller → prune left side
          ext.next match
            case _: LeafNode => Right(NullNode)
            case _ =>
              pruneOneSide(ext.next, right, pos + sharedNibbles.length, removeLeft = true).map { newNext =>
                if newNext.isNull then NullNode else ExtensionNode(ext.sharedKey, newNext)
              }

        case _ => Right(ext)

    // Prune one side of a boundary path. Returns the updated subtree, or NullNode to remove it.
    // removeLeft=false: remove subtrees to the RIGHT of the key (used for left boundary).
    // removeLeft=true:  remove subtrees to the LEFT  of the key (used for right boundary).
    private def pruneOneSide(child: MptNode, key: Seq[Int], pos: Int, removeLeft: Boolean): Either[String, MptNode] =
      child match
        case branch: BranchNode =>
          // go-ethereum unset() equivalent: clear children on one side then recurse.
          // Bounds check: pos must be within the key (64 nibbles). At pos >= key.length
          // we've consumed the full path — no more children to prune.
          if pos >= key.length then Right(branch)
          else
            // Null children on the side being removed
            var b = branch
            if removeLeft then for i <- 0 until key(pos) do b = b.updateChild(i, NullNode)
            else for i <- key(pos) + 1 until 16 do b = b.updateChild(i, NullNode)
            pruneOneSide(b.children(key(pos)), key, pos + 1, removeLeft).map { newKeyChild =>
              b.updateChild(key(pos), newKeyChild)
            }

        case ext: ExtensionNode =>
          val sharedNibbles = toNibbleSeq(ext.sharedKey)
          val keySlice = key.drop(pos).take(sharedNibbles.length)
          if keySlice != sharedNibbles then
            // Extension's path diverges from boundary key: sibling check
            val cmp = compareNibbleSeqs(sharedNibbles, keySlice)
            Right(if (removeLeft && cmp < 0) || (!removeLeft && cmp > 0) then NullNode else child)
          else
            pruneOneSide(ext.next, key, pos + sharedNibbles.length, removeLeft).map { newNext =>
              if newNext.isNull then NullNode else ExtensionNode(ext.sharedKey, newNext)
            }

        case leaf: LeafNode =>
          // Either the boundary leaf itself (remove → will be re-inserted) or a sibling
          val leafNibbles = toNibbleSeq(leaf.key)
          val remaining = key.drop(pos)
          if leafNibbles == remaining then Right(NullNode) // boundary leaf: remove and re-insert in phase 3
          else
            val cmp = compareNibbleSeqs(leafNibbles, remaining)
            Right(if (removeLeft && cmp < 0) || (!removeLeft && cmp > 0) then NullNode else child)

        case NullNode => Right(NullNode)
        case _        => Right(child)

    // ── Phase 3: insertLeaf ─────────────────────────────────────────────────

  // ─── hasRightElement ────────────────────────────────────────────────────────

  // Port of go-ethereum hasRightElement: true if any trie element exists lexicographically
  // after keyNibbles in the resolved partial trie.
  private def hasRightElement(node: MptNode, keyNibbles: Seq[Int]): Boolean =
    var current = node
    var remaining = keyNibbles
    var found = false
    while current != null && !found do
      current match
        case branch: BranchNode =>
          if remaining.nonEmpty then
            val n = remaining.head
            if (n + 1 until 16).exists(!branch.children(_).isNull) then found = true
            else
              current = branch.children(n)
              remaining = remaining.tail
          else current = null

        case ext: ExtensionNode =>
          val sharedNibbles = toNibbleSeq(ext.sharedKey)
          if !remaining.startsWith(sharedNibbles) then
            found = compareNibbleSeqs(sharedNibbles, remaining.take(sharedNibbles.length)) > 0
            current = null
          else
            current = ext.next
            remaining = remaining.drop(sharedNibbles.length)

        case _: LeafNode => current = null

        case _ => current = null
    found

  // ─── Shared utilities ────────────────────────────────────────────────────────

  private def toNibbleSeq(bs: ByteString): Seq[Int] =
    bs.toArray.toSeq.map(_ & 0xff)

  private def hashToNibbles(hash: ByteString): Seq[Int] =
    hash.flatMap(byte => Seq((byte >> 4) & 0x0f, byte & 0x0f)).map(_.toInt)

  // comparePrefix: compare key.drop(pos).take(len) vs ref nibbles.
  // Returns 0 if equal, negative if key-slice < ref or too short, positive if key-slice > ref.
  private def comparePrefix(key: Seq[Int], pos: Int, ref: Seq[Int]): Int =
    val slice = key.drop(pos).take(ref.length)
    if slice.length < ref.length then
      // Truncated slice — compare what we have then treat as smaller
      val partial = compareNibbleSeqs(slice, ref.take(slice.length))
      if partial != 0 then partial else -1
    else compareNibbleSeqs(slice, ref)

  private def compareNibbleSeqs(a: Seq[Int], b: Seq[Int]): Int =
    val len = math.min(a.length, b.length)
    var i = 0
    var result = 0
    while i < len && result == 0 do
      result = a(i) - b(i)
      i += 1
    if result != 0 then result else a.length - b.length

  private def cmpBytes(a: ByteString, b: ByteString): Int =
    val aa = a.toArray
    val bb = b.toArray
    var i = 0
    var result = 0
    while i < math.min(aa.length, bb.length) && result == 0 do
      result = (aa(i) & 0xff) - (bb(i) & 0xff)
      i += 1
    if result != 0 then result else aa.length - bb.length

  // Store raw bytes keyed by hash — matches go-ethereum's proof db (read-only, decode-per-lookup).
  // Never stores decoded MptNode objects: both boundary paths must get independent allocations.
  private def buildProofRawMap(proof: Seq[ByteString]): Map[ByteString, Array[Byte]] =
    proof.map { nodeBytes =>
      val key = ByteString(Node.hashFn(nodeBytes.toArray))
      key -> nodeBytes.toArray
    }.toMap

object MerkleProofVerifier:
  def apply(rootHash: ByteString): MerkleProofVerifier = new MerkleProofVerifier(rootHash)
