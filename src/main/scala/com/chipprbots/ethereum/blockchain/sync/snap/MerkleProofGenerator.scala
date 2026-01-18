package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.util.ByteString

import scala.collection.mutable

import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.mpt.BranchNode
import com.chipprbots.ethereum.mpt.ExtensionNode
import com.chipprbots.ethereum.mpt.HashNode
import com.chipprbots.ethereum.mpt.LeafNode
import com.chipprbots.ethereum.mpt.MptNode
import com.chipprbots.ethereum.mpt.NullNode
import com.chipprbots.ethereum.utils.Logger

/** Generates Merkle Patricia Trie proofs for SNAP/1 protocol responses.
  *
  * The SNAP protocol requires boundary proofs that prove:
  *   1. The first key in a range is the actual first key >= startHash
  *   2. The last key in a range is the actual last key <= limitHash (or the range continues)
  *   3. All keys between first and last are consecutive with no gaps
  *
  * Boundary proofs include all trie nodes on the path from root to the first and last keys, allowing clients to verify
  * the range is complete.
  *
  * @param stateStorage
  *   Storage for retrieving trie nodes
  */
class MerkleProofGenerator(stateStorage: StateStorage) extends Logger {

  /** Mask for extracting nibble values (4 bits) */
  private val NibbleMask = 0x0f

  /** Generate boundary proofs for an account range
    *
    * @param rootHash
    *   State root to generate proofs from
    * @param firstKey
    *   Hash of first account in range (or startHash if proving absence)
    * @param lastKey
    *   Hash of last account in range (or limitHash if proving absence)
    * @return
    *   Sequence of RLP-encoded proof nodes, or empty if root not found
    */
  def generateAccountRangeProof(
      rootHash: ByteString,
      firstKey: Option[ByteString],
      lastKey: Option[ByteString]
  ): Seq[ByteString] = {
    // Use LinkedHashSet to maintain insertion order and avoid duplicates
    val proofNodes = mutable.LinkedHashSet.empty[ByteString]

    stateStorage.getNode(rootHash) match {
      case Some(rootNode) =>
        // Generate proof for first key boundary
        firstKey.foreach { key =>
          collectProofPath(rootNode, hashToNibbles(key), proofNodes)
        }

        // Generate proof for last key boundary (if different from first)
        lastKey.foreach { key =>
          if (firstKey.isEmpty || firstKey.get != key) {
            collectProofPath(rootNode, hashToNibbles(key), proofNodes)
          }
        }

      case None =>
        log.debug(s"Root node not found for proof generation: ${rootHash.take(8).toArray.map("%02x".format(_)).mkString}")
    }

    proofNodes.toSeq
  }

  /** Generate boundary proofs for a storage range
    *
    * @param storageRoot
    *   Storage root to generate proofs from
    * @param firstKey
    *   Hash of first storage slot in range
    * @param lastKey
    *   Hash of last storage slot in range
    * @return
    *   Sequence of RLP-encoded proof nodes
    */
  def generateStorageRangeProof(
      storageRoot: ByteString,
      firstKey: Option[ByteString],
      lastKey: Option[ByteString]
  ): Seq[ByteString] =
    // Storage proofs follow the same pattern as account proofs
    generateAccountRangeProof(storageRoot, firstKey, lastKey)

  /** Collect all nodes on the path to a key
    *
    * @param node
    *   Current node to traverse
    * @param path
    *   Remaining nibbles to follow
    * @param proofNodes
    *   Set to collect proof nodes (RLP-encoded)
    */
  private def collectProofPath(
      node: MptNode,
      path: Seq[Int],
      proofNodes: mutable.LinkedHashSet[ByteString]
  ): Unit = {
    // Add current node to proof (RLP-encoded)
    val encodedNode = ByteString(node.encode)
    if (encodedNode.nonEmpty) {
      proofNodes += encodedNode
    }

    node match {
      case _: LeafNode =>
      // Leaf node - end of path, nothing more to collect

      case branch: BranchNode =>
        if (path.nonEmpty) {
          val nextIndex = path.head
          branch.children.lift(nextIndex) match {
            case Some(child: HashNode) =>
              // Resolve hash node and continue
              stateStorage.getNode(ByteString(child.hash)) match {
                case Some(resolvedNode) =>
                  collectProofPath(resolvedNode, path.tail, proofNodes)
                case None =>
                  log.debug(s"Could not resolve hash node during proof generation")
              }
            case Some(child) if !child.isNull =>
              collectProofPath(child, path.tail, proofNodes)
            case _ =>
            // No child at this index - end of path
          }
        }

      case ext: ExtensionNode =>
        // Extension node - match shared key and continue
        val sharedNibbles = ext.sharedKey.map(_.toInt & NibbleMask)
        if (path.startsWith(sharedNibbles)) {
          ext.next match {
            case hashNode: HashNode =>
              stateStorage.getNode(ByteString(hashNode.hash)) match {
                case Some(resolvedNode) =>
                  collectProofPath(resolvedNode, path.drop(sharedNibbles.length), proofNodes)
                case None =>
                  log.debug(s"Could not resolve extension next node during proof generation")
              }
            case nextNode =>
              collectProofPath(nextNode, path.drop(sharedNibbles.length), proofNodes)
          }
        }

      case hashNode: HashNode =>
        // Resolve and continue
        stateStorage.getNode(ByteString(hashNode.hash)) match {
          case Some(resolvedNode) =>
            collectProofPath(resolvedNode, path, proofNodes)
          case None =>
            log.debug(s"Could not resolve hash node during proof generation")
        }

      case NullNode =>
      // Empty node - nothing to add
    }
  }

  /** Convert a hash to a sequence of nibbles (4-bit values)
    *
    * @param hash
    *   32-byte hash
    * @return
    *   64 nibbles (0-15 values)
    */
  private def hashToNibbles(hash: ByteString): Seq[Int] =
    hash.flatMap { byte =>
      Seq((byte >> 4) & NibbleMask, byte & NibbleMask)
    }
}

object MerkleProofGenerator {

  /** Create a new proof generator
    *
    * @param stateStorage
    *   Storage for retrieving trie nodes
    * @return
    *   New generator instance
    */
  def apply(stateStorage: StateStorage): MerkleProofGenerator =
    new MerkleProofGenerator(stateStorage)
}
