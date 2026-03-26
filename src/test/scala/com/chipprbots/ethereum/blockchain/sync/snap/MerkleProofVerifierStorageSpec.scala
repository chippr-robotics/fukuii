package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.testing.Tags._

/** Extended tests for MerkleProofVerifier focusing on storage range verification
  * and edge cases discovered during snap sync development (Bugs 17, 20).
  */
class MerkleProofVerifierStorageSpec extends AnyFlatSpec with Matchers {

  // ========================================
  // Empty Storage Root
  // ========================================

  "MerkleProofVerifier storage" should "accept empty slots and proof for empty storage root" taggedAs UnitTest in {
    val emptyRoot = ByteString(MerklePatriciaTrie.EmptyRootHash)
    val verifier = MerkleProofVerifier(emptyRoot)

    val result = verifier.verifyStorageRange(
      slots = Seq.empty,
      proof = Seq.empty,
      startHash = ByteString.empty,
      endHash = ByteString.fromArray(Array.fill(32)(0xff.toByte))
    )

    result shouldBe Right(())
  }

  it should "reject empty response for non-empty storage root" taggedAs UnitTest in {
    val nonEmptyRoot = kec256(ByteString("storage-root"))
    val verifier = MerkleProofVerifier(nonEmptyRoot)

    val result = verifier.verifyStorageRange(
      slots = Seq.empty,
      proof = Seq.empty,
      startHash = ByteString.empty,
      endHash = ByteString.fromArray(Array.fill(32)(0xff.toByte))
    )

    result shouldBe a[Left[_, _]]
    result.left.get should include("Empty StorageRanges response")
  }

  // ========================================
  // Proofless Validation (Basic Invariants)
  // ========================================

  it should "accept monotonically increasing slots without proof" taggedAs UnitTest in {
    val storageRoot = kec256(ByteString("storage-root"))
    val verifier = MerkleProofVerifier(storageRoot)

    // Slots with no proof (full response that doesn't need boundary proofs)
    val slots = Seq(
      (ByteString(Array.fill(31)(0.toByte) ++ Array(1.toByte)), ByteString("val1")),
      (ByteString(Array.fill(31)(0.toByte) ++ Array(2.toByte)), ByteString("val2")),
      (ByteString(Array.fill(31)(0.toByte) ++ Array(3.toByte)), ByteString("val3"))
    )

    val result = verifier.verifyStorageRange(
      slots = slots,
      proof = Seq.empty,
      startHash = ByteString(Array.fill(32)(0.toByte)),
      endHash = ByteString(Array.fill(32)(0xff.toByte))
    )

    result shouldBe Right(())
  }

  it should "reject non-monotonic storage slots without proof" taggedAs UnitTest in {
    val storageRoot = kec256(ByteString("storage-root"))
    val verifier = MerkleProofVerifier(storageRoot)

    val slots = Seq(
      (ByteString(Array.fill(31)(0.toByte) ++ Array(3.toByte)), ByteString("val3")),
      (ByteString(Array.fill(31)(0.toByte) ++ Array(1.toByte)), ByteString("val1"))
    )

    val result = verifier.verifyStorageRange(
      slots = slots,
      proof = Seq.empty,
      startHash = ByteString(Array.fill(32)(0.toByte)),
      endHash = ByteString(Array.fill(32)(0xff.toByte))
    )

    result shouldBe a[Left[_, _]]
    result.left.get should include("not monotonically increasing")
  }

  it should "reject duplicate storage slot keys" taggedAs UnitTest in {
    val storageRoot = kec256(ByteString("storage-root"))
    val verifier = MerkleProofVerifier(storageRoot)

    val slotHash = ByteString(Array.fill(31)(0.toByte) ++ Array(1.toByte))
    val slots = Seq(
      (slotHash, ByteString("val1")),
      (slotHash, ByteString("val2"))
    )

    val result = verifier.verifyStorageRange(
      slots = slots,
      proof = Seq.empty,
      startHash = ByteString(Array.fill(32)(0.toByte)),
      endHash = ByteString(Array.fill(32)(0xff.toByte))
    )

    result shouldBe a[Left[_, _]]
    result.left.get should include("not monotonically increasing")
  }

  it should "reject slot before start hash" taggedAs UnitTest in {
    val storageRoot = kec256(ByteString("storage-root"))
    val verifier = MerkleProofVerifier(storageRoot)

    val startHash = ByteString(Array.fill(31)(0.toByte) ++ Array(5.toByte))
    val slots = Seq(
      (ByteString(Array.fill(31)(0.toByte) ++ Array(3.toByte)), ByteString("val1"))
    )

    val result = verifier.verifyStorageRange(
      slots = slots,
      proof = Seq.empty,
      startHash = startHash,
      endHash = ByteString(Array.fill(32)(0xff.toByte))
    )

    result shouldBe a[Left[_, _]]
    result.left.get should include("before requested start")
  }

  it should "reject slot at or after end hash" taggedAs UnitTest in {
    val storageRoot = kec256(ByteString("storage-root"))
    val verifier = MerkleProofVerifier(storageRoot)

    val endHash = ByteString(Array.fill(31)(0.toByte) ++ Array(5.toByte))
    val slots = Seq(
      (endHash, ByteString("val-at-end"))
    )

    val result = verifier.verifyStorageRange(
      slots = slots,
      proof = Seq.empty,
      startHash = ByteString(Array.fill(32)(0.toByte)),
      endHash = endHash
    )

    result shouldBe a[Left[_, _]]
    result.left.get should include("at/after requested limit")
  }

  it should "accept single slot in valid range without proof" taggedAs UnitTest in {
    val storageRoot = kec256(ByteString("storage-root"))
    val verifier = MerkleProofVerifier(storageRoot)

    val slots = Seq(
      (ByteString(Array.fill(31)(0.toByte) ++ Array(5.toByte)), ByteString("val1"))
    )

    val result = verifier.verifyStorageRange(
      slots = slots,
      proof = Seq.empty,
      startHash = ByteString(Array.fill(32)(0.toByte)),
      endHash = ByteString(Array.fill(32)(0xff.toByte))
    )

    result shouldBe Right(())
  }

  // ========================================
  // Malformed Proof Handling
  // ========================================

  it should "handle malformed storage proof gracefully" taggedAs UnitTest in {
    val storageRoot = kec256(ByteString("storage-root"))
    val verifier = MerkleProofVerifier(storageRoot)

    val slots = Seq(
      (ByteString(Array.fill(31)(0.toByte) ++ Array(1.toByte)), ByteString("val1"))
    )
    val malformedProof = Seq(ByteString("not-valid-rlp"))

    val result = verifier.verifyStorageRange(
      slots = slots,
      proof = malformedProof,
      startHash = ByteString(Array.fill(32)(0.toByte)),
      endHash = ByteString(Array.fill(32)(0xff.toByte))
    )

    result shouldBe a[Left[_, _]]
  }

  it should "handle proof-only response (proof of absence)" taggedAs UnitTest in {
    val storageRoot = kec256(ByteString("storage-root"))
    val verifier = MerkleProofVerifier(storageRoot)

    // Proof with no slots is valid for sparse tries (proof of absence)
    // With malformed proof, should fail gracefully
    val malformedProof = Seq(ByteString("bad-rlp"))
    val result = verifier.verifyStorageRange(
      slots = Seq.empty,
      proof = malformedProof,
      startHash = ByteString(Array.fill(32)(0.toByte)),
      endHash = ByteString(Array.fill(32)(0xff.toByte))
    )

    result shouldBe a[Left[_, _]]
  }

  // ========================================
  // Account Range Edge Cases
  // ========================================

  "MerkleProofVerifier account" should "handle proof with many accounts" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("state-root"))
    val verifier = MerkleProofVerifier(stateRoot)

    // Large batch of accounts with proof (simplified test)
    val accounts = (1 to 100).map { i =>
      import com.chipprbots.ethereum.domain.Account
      (kec256(ByteString(s"account-$i")), Account(nonce = i, balance = i * 1000))
    }

    // Without a valid proof, this should fail with a missing proof error
    val result = verifier.verifyAccountRange(
      accounts = accounts,
      proof = Seq.empty,
      startHash = ByteString.empty,
      endHash = ByteString.fromArray(Array.fill(32)(0xff.toByte))
    )

    result shouldBe a[Left[_, _]]
    result.left.get should include("Missing proof")
  }

  it should "distinguish empty trie from non-empty trie" taggedAs UnitTest in {
    // Empty trie: empty proof + empty accounts = OK
    val emptyVerifier = MerkleProofVerifier(ByteString(MerklePatriciaTrie.EmptyRootHash))
    emptyVerifier.verifyAccountRange(Seq.empty, Seq.empty, ByteString.empty, ByteString.empty) shouldBe Right(())

    // Non-empty trie: empty proof + empty accounts = error
    val nonEmptyVerifier = MerkleProofVerifier(kec256(ByteString("non-empty")))
    val result = nonEmptyVerifier.verifyAccountRange(Seq.empty, Seq.empty, ByteString.empty, ByteString.empty)
    result shouldBe a[Left[_, _]]
  }
}
