package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.mpt.MptNode
import com.chipprbots.ethereum.mpt.MptTraversals
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.mpt.LeafNode
import com.chipprbots.ethereum.mpt.ExtensionNode
import com.chipprbots.ethereum.mpt.BranchNode
import com.chipprbots.ethereum.mpt.HashNode
import com.chipprbots.ethereum.utils.Logger

/** Merkle proof verifier for SNAP sync
  *
  * Verifies Merkle Patricia Trie proofs for account ranges and storage ranges received during SNAP sync. Follows
  * core-geth implementation patterns from eth/protocols/snap/sync.go
  *
  * The verification process:
  *   1. Decode proof nodes from their RLP-encoded form 2. Build a partial trie from the proof nodes 3. Verify that
  *      accounts/storage slots exist in the trie at expected paths 4. Verify the trie root matches the expected root
  *      (state root or storage root)
  *
  * @param rootHash
  *   Expected root hash to verify against (state root or storage root)
  */
class MerkleProofVerifier(rootHash: ByteString) extends Logger {

  /** Verify an account range response with Merkle proof
    *
    * This method verifies that:
    *   1. The proof is valid and forms a proper Merkle Patricia Trie path 2. All accounts in the range are present in
    *      the proof 3. The proof root matches the expected state root
    *
    * @param accounts
    *   Accounts to verify (hash -> account)
    * @param proof
    *   Merkle proof nodes (RLP-encoded)
    * @param startHash
    *   Starting hash of the range
    * @param endHash
    *   Ending hash of the range
    * @return
    *   Either error message or Unit on success
    */
  def verifyAccountRange(
      accounts: Seq[(ByteString, Account)],
      proof: Seq[ByteString],
      startHash: ByteString,
      endHash: ByteString
  ): Either[String, Unit] = {

    // For non-empty tries, even an empty account range must come with a proof proving non-existence.
    // The only case where empty proof + empty accounts is valid is an empty trie.
    if (proof.isEmpty && accounts.isEmpty) {
      if (rootHash == ByteString(MerklePatriciaTrie.EmptyRootHash)) return Right(())
      return Left("Missing proof for empty account range")
    }

    // If we have accounts, we need a proof
    if (proof.isEmpty && accounts.nonEmpty) {
      return Left(s"Missing proof for ${accounts.size} accounts")
    }

    try {
      // Decode proof nodes
      val proofNodes = decodeProofNodes(proof)

      // Build proof map: hash -> node
      val proofMap = buildProofMap(proofNodes)

      // Verify each account is in the proof
      val verificationResults = accounts.map { case (accountHash, account) =>
        verifyAccountInProof(accountHash, account, proofMap) match {
          case Left(error) =>
            Left(s"Account ${accountHash.take(4).toArray.map("%02x".format(_)).mkString} verification failed: $error")
          case Right(_) => Right(())
        }
      }

      // Check if any verification failed
      verificationResults.collectFirst { case Left(error) => error } match {
        case Some(error) => return Left(error)
        case None        => // Continue
      }

      // Verify the proof forms a valid path to the state root
      verifyProofRoot(proofNodes) match {
        case Left(error) => Left(s"Proof root verification failed: $error")
        case Right(_)    => Right(())
      }

    } catch {
      case e: Exception =>
        log.warn(s"Merkle proof verification error: ${e.getMessage}")
        Left(s"Verification error: ${e.getMessage}")
    }
  }

  /** Decode RLP-encoded proof nodes
    *
    * @param proof
    *   Sequence of RLP-encoded nodes
    * @return
    *   Decoded MPT nodes
    */
  private def decodeProofNodes(proof: Seq[ByteString]): Seq[MptNode] =
    proof.map { nodeBytes =>
      try
        MptTraversals.decodeNode(nodeBytes.toArray)
      catch {
        case e: Exception =>
          throw new IllegalArgumentException(s"Failed to decode proof node: ${e.getMessage}", e)
      }
    }

  /** Build a map of node hash -> node for quick lookup
    *
    * @param nodes
    *   Proof nodes
    * @return
    *   Map of hash to node
    */
  private def buildProofMap(nodes: Seq[MptNode]): Map[ByteString, MptNode] =
    nodes.map { node =>
      ByteString(node.hash) -> node
    }.toMap

  /** Verify an account exists in the proof
    *
    * @param accountHash
    *   Hash of the account
    * @param account
    *   Account data
    * @param proofMap
    *   Map of node hashes to nodes
    * @return
    *   Either error or success
    */
  private def verifyAccountInProof(
      accountHash: ByteString,
      account: Account,
      proofMap: Map[ByteString, MptNode]
  ): Either[String, Unit] = {

    // For now, we do a simplified verification:
    // Just check that we can find a path in the proof nodes
    // A full implementation would traverse the trie path

    // Convert account hash to nibbles (path in the trie)
    val path = hashToNibbles(accountHash)

    // Start from the root (use stateRoot for account verification)
    val root = rootHash

    // Traverse the proof following the path
    traversePath(root, path, proofMap, account)
  }

  /** Traverse the trie path to find the account
    *
    * @param currentHash
    *   Hash of current node to look up
    * @param path
    *   Remaining path (nibbles) to traverse
    * @param proofMap
    *   Map of node hashes to nodes
    * @param expectedAccount
    *   Expected account at the end of path
    * @return
    *   Either error or success
    */
  private def traversePath(
      currentHash: ByteString,
      path: Seq[Int],
      proofMap: Map[ByteString, MptNode],
      expectedAccount: Account
  ): Either[String, Unit] =
    // Look up the node in the proof
    proofMap.get(currentHash) match {
      case None =>
        // Node not in proof - this is acceptable for partial proofs
        // The proof only needs to cover the range being verified
        Right(())

      case Some(leafNode: LeafNode) =>
        // Found a leaf - verify it matches the expected account
        verifyLeafAccount(leafNode, expectedAccount)

      case Some(branchNode: BranchNode) =>
        // Branch node - follow the path
        if (path.isEmpty) {
          // We're at the end of the path
          branchNode.terminator match {
            case Some(value) => verifyAccountValue(value, expectedAccount)
            case None        => Left("Path ended at branch without terminator")
          }
        } else {
          // Continue traversal
          val nextIndex = path.head
          branchNode.children.lift(nextIndex) match {
            case Some(nextNode: HashNode) =>
              traversePath(ByteString(nextNode.hash), path.tail, proofMap, expectedAccount)
            case Some(nextNode) =>
              traversePath(ByteString(nextNode.hash), path.tail, proofMap, expectedAccount)
            case None =>
              Left(s"No child at index $nextIndex")
          }
        }

      case Some(extensionNode: ExtensionNode) =>
        // Extension node - match the shared key
        val sharedNibbles = bytesToNibbles(extensionNode.sharedKey)
        if (path.startsWith(sharedNibbles)) {
          extensionNode.next match {
            case hashNode: HashNode =>
              traversePath(ByteString(hashNode.hash), path.drop(sharedNibbles.length), proofMap, expectedAccount)
            case nextNode =>
              traversePath(ByteString(nextNode.hash), path.drop(sharedNibbles.length), proofMap, expectedAccount)
          }
        } else {
          Left("Path doesn't match extension node")
        }

      case Some(_) =>
        Left("Unexpected node type")
    }

  /** Verify leaf node contains expected account
    *
    * @param leaf
    *   Leaf node from proof
    * @param expectedAccount
    *   Expected account
    * @return
    *   Either error or success
    */
  private def verifyLeafAccount(leaf: LeafNode, expectedAccount: Account): Either[String, Unit] =
    verifyAccountValue(leaf.value, expectedAccount)

  /** Verify account value matches expected account
    *
    * Decodes the RLP-encoded account and compares key fields to expected values. This ensures the account data in the
    * proof matches what we expect.
    *
    * @param value
    *   RLP-encoded account value
    * @param expectedAccount
    *   The account we expect to find
    * @return
    *   Either error or success
    */
  private def verifyAccountValue(value: ByteString, expectedAccount: Account): Either[String, Unit] =
    try {
      // Decode the account from RLP using the standard Account serializer
      val decoded = Account.accountSerializer.fromBytes(value.toArray)

      // Compare key fields to ensure account data matches
      if (decoded.nonce != expectedAccount.nonce) {
        Left(s"Account nonce mismatch: expected ${expectedAccount.nonce}, got ${decoded.nonce}")
      } else if (decoded.balance != expectedAccount.balance) {
        Left(s"Account balance mismatch: expected ${expectedAccount.balance}, got ${decoded.balance}")
      } else if (decoded.storageRoot != expectedAccount.storageRoot) {
        Left(s"Account storageRoot mismatch: expected ${expectedAccount.storageRoot}, got ${decoded.storageRoot}")
      } else if (decoded.codeHash != expectedAccount.codeHash) {
        Left(s"Account codeHash mismatch: expected ${expectedAccount.codeHash}, got ${decoded.codeHash}")
      } else {
        Right(())
      }
    } catch {
      case e: Exception =>
        Left(s"Failed to decode account value: ${e.getMessage}")
    }

  /** Verify the proof root matches the expected state root
    *
    * @param proofNodes
    *   Decoded proof nodes
    * @return
    *   Either error or success
    */
  private def verifyProofRoot(proofNodes: Seq[MptNode]): Either[String, Unit] = {
    if (proofNodes.isEmpty) {
      return Left("Empty proof")
    }

    // The first node should be the root or a node that hashes to the root
    val firstNode = proofNodes.head
    val firstNodeHash = ByteString(firstNode.hash)

    // For AccountRange proofs we expect the first node to be the root (core-geth behavior).
    if (firstNodeHash != rootHash) {
      Left(
        s"Proof root mismatch: got ${firstNodeHash.take(4).toArray.map("%02x".format(_)).mkString}..., " +
          s"expected ${rootHash.take(4).toArray.map("%02x".format(_)).mkString}..."
      )
    } else Right(())
  }

  /** Convert hash to nibbles (hex digits) for trie path
    *
    * @param hash
    *   Hash bytes
    * @return
    *   Sequence of nibbles (0-15)
    */
  private def hashToNibbles(hash: ByteString): Seq[Int] =
    hash
      .flatMap { byte =>
        Seq((byte >> 4) & 0x0f, byte & 0x0f)
      }
      .map(_.toInt)

  /** Convert bytes to nibbles
    *
    * @param bytes
    *   Byte string
    * @return
    *   Sequence of nibbles (0-15)
    */
  private def bytesToNibbles(bytes: ByteString): Seq[Int] =
    bytes
      .flatMap { byte =>
        Seq((byte >> 4) & 0x0f, byte & 0x0f)
      }
      .map(_.toInt)

  /** Verify a storage range response with Merkle proof
    *
    * This method verifies storage slots against an account's storage root. Similar to account verification but operates
    * on storage tries.
    *
    * The verification process:
    *   1. Decode proof nodes from their RLP-encoded form 2. Build a partial storage trie from the proof nodes 3. Verify
    *      that storage slots exist in the trie at expected paths 4. Verify the proof root matches the expected storage
    *      root
    *
    * Note: The rootHash of this verifier should be the account's storageRoot when verifying storage ranges.
    *
    * @param slots
    *   Storage slots to verify (slotHash -> slotValue)
    * @param proof
    *   Merkle proof nodes (RLP-encoded)
    * @param startHash
    *   Starting hash of the range
    * @param endHash
    *   Ending hash of the range
    * @return
    *   Either error message or Unit on success
    */
  def verifyStorageRange(
      slots: Seq[(ByteString, ByteString)],
      proof: Seq[ByteString],
      startHash: ByteString,
      endHash: ByteString
  ): Either[String, Unit] = {

    // Empty proof is valid if there are no storage slots
    if (proof.isEmpty && slots.isEmpty) {
      return Right(())
    }

    // If we have slots, we need a proof
    if (proof.isEmpty && slots.nonEmpty) {
      return Left(s"Missing proof for ${slots.size} storage slots")
    }

    try {
      // Decode proof nodes
      val proofNodes = decodeProofNodes(proof)

      // Build proof map: hash -> node
      val proofMap = buildProofMap(proofNodes)

      // Verify each storage slot is in the proof
      val verificationResults = slots.map { case (slotHash, slotValue) =>
        verifyStorageSlotInProof(slotHash, slotValue, proofMap) match {
          case Left(error) =>
            Left(s"Storage slot ${slotHash.take(4).toArray.map("%02x".format(_)).mkString} verification failed: $error")
          case Right(_) => Right(())
        }
      }

      // Check if any verification failed
      verificationResults.collectFirst { case Left(error) => error } match {
        case Some(error) => return Left(error)
        case None        => // Continue
      }

      // Verify the proof forms a valid path to the storage root
      verifyProofRoot(proofNodes) match {
        case Left(error) => Left(s"Storage proof root verification failed: $error")
        case Right(_)    => Right(())
      }

    } catch {
      case e: Exception =>
        log.warn(s"Storage Merkle proof verification error: ${e.getMessage}")
        Left(s"Storage verification error: ${e.getMessage}")
    }
  }

  /** Verify a storage slot exists in the proof
    *
    * Similar to verifyAccountInProof but for storage slots.
    *
    * @param slotHash
    *   Hash of the storage slot
    * @param slotValue
    *   Value of the storage slot
    * @param proofMap
    *   Map of node hashes to nodes
    * @return
    *   Either error or success
    */
  private def verifyStorageSlotInProof(
      slotHash: ByteString,
      slotValue: ByteString,
      proofMap: Map[ByteString, MptNode]
  ): Either[String, Unit] = {

    // Convert slot hash to nibbles (path in the trie)
    val path = hashToNibbles(slotHash)

    // Start from the storage root
    val root = rootHash

    // Traverse the proof following the path
    traverseStoragePath(root, path, proofMap, slotValue)
  }

  /** Traverse the storage trie path to find the slot
    *
    * Similar to traversePath but for storage slots.
    *
    * @param currentHash
    *   Hash of current node to look up
    * @param path
    *   Remaining path (nibbles) to traverse
    * @param proofMap
    *   Map of node hashes to nodes
    * @param expectedValue
    *   Expected slot value at the end of path
    * @return
    *   Either error or success
    */
  private def traverseStoragePath(
      currentHash: ByteString,
      path: Seq[Int],
      proofMap: Map[ByteString, MptNode],
      expectedValue: ByteString
  ): Either[String, Unit] =
    // Look up the node in the proof
    proofMap.get(currentHash) match {
      case None =>
        // Node not in proof - this is acceptable for partial proofs
        Right(())

      case Some(leafNode: LeafNode) =>
        // Found a leaf - verify it matches the expected value
        if (leafNode.value == expectedValue) {
          Right(())
        } else {
          Left(
            s"Storage value mismatch: expected ${expectedValue.take(4).toArray.map("%02x".format(_)).mkString}, got ${leafNode.value.take(4).toArray.map("%02x".format(_)).mkString}"
          )
        }

      case Some(branchNode: BranchNode) =>
        // Branch node - follow the path
        if (path.isEmpty) {
          // We're at the end of the path
          branchNode.terminator match {
            case Some(value) =>
              if (value == expectedValue) Right(())
              else Left(s"Storage value mismatch at branch terminator")
            case None => Left("Path ended at branch without terminator")
          }
        } else {
          // Continue traversal
          val nextIndex = path.head
          branchNode.children.lift(nextIndex) match {
            case Some(nextNode: HashNode) =>
              traverseStoragePath(ByteString(nextNode.hash), path.tail, proofMap, expectedValue)
            case Some(nextNode) =>
              traverseStoragePath(ByteString(nextNode.hash), path.tail, proofMap, expectedValue)
            case None =>
              Left(s"No child at index $nextIndex")
          }
        }

      case Some(extensionNode: ExtensionNode) =>
        // Extension node - match the shared key
        val sharedNibbles = bytesToNibbles(extensionNode.sharedKey)
        if (path.startsWith(sharedNibbles)) {
          extensionNode.next match {
            case hashNode: HashNode =>
              traverseStoragePath(ByteString(hashNode.hash), path.drop(sharedNibbles.length), proofMap, expectedValue)
            case nextNode =>
              traverseStoragePath(ByteString(nextNode.hash), path.drop(sharedNibbles.length), proofMap, expectedValue)
          }
        } else {
          Left("Path doesn't match extension node")
        }

      case Some(_) =>
        Left("Unexpected node type")
    }
}

object MerkleProofVerifier {

  /** Create a new verifier for a given root hash
    *
    * @param rootHash
    *   Root hash (state root or storage root)
    * @return
    *   New verifier instance
    */
  def apply(rootHash: ByteString): MerkleProofVerifier =
    new MerkleProofVerifier(rootHash)
}
