package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.utils.Logger

/** Merkle proof verifier for SNAP sync.
  *
  * Delegates to [[RangeProofVerifier]] for full range proof verification, ensuring that the delivered data is the
  * COMPLETE set of keys in the proven range — matching the approach used by geth, Nethermind, and Besu.
  *
  * @param rootHash
  *   Expected root hash to verify against (state root for accounts, storage root for storage slots)
  */
class MerkleProofVerifier(rootHash: ByteString) extends Logger {

  private val emptyRoot = ByteString(MerklePatriciaTrie.EmptyRootHash)

  /** Verify an account range response with Merkle proof.
    *
    * Ensures:
    *   1. All accounts in the range are correct and complete (no accounts were skipped) 2. The proof root matches the
    *      expected state root
    *
    * @param accounts
    *   Accounts to verify (accountHash -> Account), sorted by accountHash ascending
    * @param proof
    *   Merkle proof nodes (RLP-encoded)
    * @param startHash
    *   Starting hash of the requested range
    * @param endHash
    *   Ending hash of the requested range
    * @return
    *   Either error message or Unit on success
    */
  def verifyAccountRange(
      accounts: Seq[(ByteString, Account)],
      proof: Seq[ByteString],
      startHash: ByteString,
      endHash: ByteString
  ): Either[String, Unit] = {

    val keys = accounts.map(_._1.toArray)
    val values = accounts.map { case (_, account) =>
      Account.accountSerializer.toBytes(account)
    }
    val proofBytes = proof.map(_.toArray)

    val firstKey = if (keys.nonEmpty) keys.head else startHash.toArray
    val lastKey = if (keys.nonEmpty) keys.last else startHash.toArray

    RangeProofVerifier.verifyRangeProof(
      expectedRoot = rootHash.toArray,
      firstKey = firstKey,
      lastKey = lastKey,
      keys = keys,
      values = values,
      proof = proofBytes
    ) match {
      case Left(error) => Left(error)
      case Right(_)    => Right(())
    }
  }

  /** Verify a storage range response with Merkle proof.
    *
    * Handles all SNAP/1 storage response scenarios:
    *   - Empty trie (no slots, no proof, root == emptyRoot)
    *   - Full delivery (no proof needed; rebuild and verify root — F6 fix)
    *   - Proof-of-absence (no slots but proof present)
    *   - Partial/complete range with proof
    *
    * @param slots
    *   Storage slots to verify (slotHash -> slotValue), sorted by slotHash ascending
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

    val keys = slots.map(_._1.toArray)
    val values = slots.map(_._2.toArray)
    val proofBytes = proof.map(_.toArray)

    val firstKey = if (keys.nonEmpty) keys.head else startHash.toArray
    val lastKey = if (keys.nonEmpty) keys.last else startHash.toArray

    RangeProofVerifier.verifyRangeProof(
      expectedRoot = rootHash.toArray,
      firstKey = firstKey,
      lastKey = lastKey,
      keys = keys,
      values = values,
      proof = proofBytes
    ) match {
      case Left(error) => Left(error)
      case Right(_)    => Right(())
    }
  }
}

object MerkleProofVerifier {

  /** Create a new verifier for a given root hash.
    *
    * @param rootHash
    *   Root hash (state root or storage root)
    * @return
    *   New verifier instance
    */
  def apply(rootHash: ByteString): MerkleProofVerifier =
    new MerkleProofVerifier(rootHash)
}
