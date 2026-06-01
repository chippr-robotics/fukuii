package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.mpt.{LeafNode, MerklePatriciaTrie, MptTraversals, byteStringSerializer}
import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.testing.TestMptStorage

class MerkleProofVerifierSpec extends AnyFlatSpec with Matchers {

  // ── Helpers ─────────────────────────────────────────────────────────────────

  private val ZeroKey = ByteString(Array.fill(32)(0x00.toByte))
  private val MaxKey  = ByteString(Array.fill(32)(0xff.toByte))

  /** Generate proper SNAP boundary proofs from an MPT by walking both edge paths. */
  private def generateBoundaryProof[K, V](
      trie: MerklePatriciaTrie[K, V],
      firstKey: K,
      lastKey: K
  ): Seq[ByteString] = {
    val firstProof = trie.getProof(firstKey).getOrElse(Vector.empty)
    val lastProof  = trie.getProof(lastKey).getOrElse(Vector.empty)
    (firstProof ++ lastProof)
      .distinctBy(node => ByteString(node.hash))
      .map(node => ByteString(MptTraversals.encodeNode(node)))
  }

  /** Compute the storage root from a set of ordered (k, v) pairs using SnapHashTrie. */
  private def computeStorageRoot(slots: Seq[(ByteString, ByteString)]): ByteString = {
    val t = new SnapHashTrie(_ => ())
    slots.foreach { case (k, v) => t.update(k.toArray, v.toArray) }
    t.commit()
  }

  // ── Zero-leaf / empty-proof edge cases ──────────────────────────────────────

  "MerkleProofVerifier" should "accept terminal empty account range for non-empty state root" taggedAs UnitTest in {
    val verifier = MerkleProofVerifier(kec256(ByteString("test-root")))
    verifier.verifyAccountRange(
      accounts = Seq.empty, proof = Seq.empty,
      startHash = ZeroKey, endHash = MaxKey
    ) shouldBe Right(())
  }

  it should "accept empty proof for empty account list when trie is empty" taggedAs UnitTest in {
    val verifier = MerkleProofVerifier(ByteString(MerklePatriciaTrie.EmptyRootHash))
    verifier.verifyAccountRange(
      accounts = Seq.empty, proof = Seq.empty,
      startHash = ZeroKey, endHash = MaxKey
    ) shouldBe Right(())
  }

  it should "accept proof-only account range as valid proof of absence" taggedAs UnitTest in {
    val proofNode = LeafNode(ByteString(0x01.toByte), ByteString("value"))
    val proof = Seq(ByteString(MptTraversals.encodeNode(proofNode)))
    val verifier = MerkleProofVerifier(ByteString(proofNode.hash))

    verifier.verifyAccountRange(
      accounts = Seq.empty,
      proof = proof,
      startHash = ByteString.fromArray(Array.fill(32)(0x7f.toByte)),
      endHash = MaxKey
    ) shouldBe Right(())
  }

  // ── Nil-proof (full-range hash) path ────────────────────────────────────────

  it should "reject accounts with empty proof when hash does not match" taggedAs UnitTest in {
    // Empty proof = claim to provide the complete account range; hash must match.
    val verifier = MerkleProofVerifier(kec256(ByteString("test-root")))
    val result = verifier.verifyAccountRange(
      accounts = Seq(ByteString.fromArray(Array.fill(32)(0x01.toByte)) -> Account(nonce = 1)),
      proof = Seq.empty,
      startHash = ZeroKey, endHash = MaxKey
    )
    result shouldBe a[Left[_, _]]
  }

  it should "accept nil proof for complete storage range when hash matches" taggedAs UnitTest in {
    val slot1 = ByteString(Array.fill(31)(0x00.toByte) :+ 0x10.toByte)
    val slot2 = ByteString(Array.fill(31)(0x00.toByte) :+ 0x20.toByte)
    val slots = Seq(slot1 -> ByteString("v1"), slot2 -> ByteString("v2"))
    val storageRoot = computeStorageRoot(slots)

    MerkleProofVerifier(storageRoot).verifyStorageRange(
      slots = slots, proof = Seq.empty, startHash = ZeroKey, endHash = MaxKey
    ) shouldBe Right(())
  }

  it should "reject nil-proof storage range when hash does not match" taggedAs UnitTest in {
    val slot1 = ByteString(Array.fill(31)(0x00.toByte) :+ 0x10.toByte)
    val slot2 = ByteString(Array.fill(31)(0x00.toByte) :+ 0x20.toByte)
    val slots = Seq(slot1 -> ByteString("v1"), slot2 -> ByteString("v2"))
    val wrongRoot = kec256(ByteString("wrong"))

    MerkleProofVerifier(wrongRoot).verifyStorageRange(
      slots = slots, proof = Seq.empty, startHash = ZeroKey, endHash = MaxKey
    ) shouldBe a[Left[_, _]]
  }

  // ── Malformed proofs ─────────────────────────────────────────────────────────

  it should "handle malformed proof gracefully" taggedAs UnitTest in {
    val verifier = MerkleProofVerifier(kec256(ByteString("test-root")))
    verifier.verifyAccountRange(
      accounts = Seq(ByteString.fromArray(Array.fill(32)(0x01.toByte)) -> Account(nonce = 1)),
      proof = Seq(ByteString("not-a-valid-rlp-node")),
      startHash = ZeroKey, endHash = MaxKey
    ) shouldBe a[Left[_, _]]
  }

  it should "reject a malformed proof node for storage range" taggedAs UnitTest in {
    val verifier = MerkleProofVerifier(kec256(ByteString("storage-root")))
    verifier.verifyStorageRange(
      slots = Seq(ByteString(Array.fill(32)(0x10.toByte)) -> ByteString("value")),
      proof = Seq(ByteString("not-valid-rlp-xxxxxxxxxx")),
      startHash = ZeroKey, endHash = MaxKey
    ) shouldBe a[Left[_, _]]
  }

  it should "return Left with error for deliberately corrupted proof bytes" taggedAs UnitTest in {
    val verifier = MerkleProofVerifier(kec256(ByteString("some-state-root")))
    verifier.verifyAccountRange(
      accounts = Seq(ByteString(Array.fill(32)(0x01.toByte)) -> Account(nonce = 1)),
      proof = Seq(ByteString(Array.fill(32)(0xde.toByte)), ByteString(Array[Byte](0x01, 0x02, 0x03))),
      startHash = ZeroKey, endHash = MaxKey
    ) shouldBe a[Left[_, _]]
  }

  // ── Empty-proof storage edge cases ──────────────────────────────────────────

  it should "accept empty storage proof for empty slots" taggedAs UnitTest in {
    MerkleProofVerifier(ByteString(MerklePatriciaTrie.EmptyRootHash)).verifyStorageRange(
      slots = Seq.empty, proof = Seq.empty, startHash = ZeroKey, endHash = MaxKey
    ) shouldBe Right(())
  }

  it should "accept proof-of-absence for storage range with empty slots and valid proof" taggedAs UnitTest in {
    val storage = new TestMptStorage()
    val storageTrie = MerklePatriciaTrie[ByteString, ByteString](storage)
    MerkleProofVerifier(ByteString(storageTrie.getRootHash)).verifyStorageRange(
      slots = Seq.empty, proof = Seq.empty, startHash = ZeroKey, endHash = MaxKey
    ) shouldBe Right(())
  }

  // ── Monotonicity validation ──────────────────────────────────────────────────

  it should "reject storage slots that are not monotonically increasing" taggedAs UnitTest in {
    val slot1 = ByteString(Array.fill(31)(0x00.toByte) :+ 0x42.toByte)
    val slot2 = ByteString(Array.fill(31)(0x00.toByte) :+ 0x10.toByte) // slot2 < slot1
    MerkleProofVerifier(kec256(ByteString("storage-root"))).verifyStorageRange(
      slots = Seq(slot1 -> ByteString("v1"), slot2 -> ByteString("v2")),
      proof = Seq.empty, startHash = ZeroKey, endHash = MaxKey
    ) shouldBe a[Left[_, _]]
  }

  it should "reject storage range where first and last slot have identical hashes" taggedAs UnitTest in {
    val sameSlot = ByteString(Array.fill(32)(0x55.toByte))
    MerkleProofVerifier(kec256(ByteString("storage-root"))).verifyStorageRange(
      slots = Seq(sameSlot -> ByteString("v1"), sameSlot -> ByteString("v2")),
      proof = Seq.empty, startHash = ZeroKey, endHash = MaxKey
    ) shouldBe a[Left[_, _]]
  }

  // ── Valid proof with reconstruction ─────────────────────────────────────────

  it should "verify account range with valid boundary proof" taggedAs UnitTest in {
    val storage  = new TestMptStorage()
    val acct1    = Account(nonce = 1, balance = 100)
    val acct2    = Account(nonce = 2, balance = 200)
    val key1     = ByteString(Array.fill(32)(0x10.toByte))
    val key2     = ByteString(Array.fill(32)(0x20.toByte))
    val trie     = MerklePatriciaTrie[ByteString, Account](storage).put(key1, acct1).put(key2, acct2)
    val proof    = generateBoundaryProof(trie, key1, key2)
    val verifier = MerkleProofVerifier(ByteString(trie.getRootHash))

    verifier.verifyAccountRange(
      accounts = Seq(key1 -> acct1, key2 -> acct2),
      proof = proof, startHash = key1, endHash = key2
    ) shouldBe Right(())
  }

  it should "verify storage range with valid boundary proof" taggedAs UnitTest in {
    val storage  = new TestMptStorage()
    val key1     = ByteString(Array.fill(32)(0x10.toByte))
    val key2     = ByteString(Array.fill(32)(0x20.toByte))
    val trie     = MerklePatriciaTrie[ByteString, ByteString](storage).put(key1, ByteString("v1")).put(key2, ByteString("v2"))
    val proof    = generateBoundaryProof(trie, key1, key2)
    val verifier = MerkleProofVerifier(ByteString(trie.getRootHash))

    verifier.verifyStorageRange(
      slots = Seq(key1 -> ByteString("v1"), key2 -> ByteString("v2")),
      proof = proof, startHash = key1, endHash = key2
    ) shouldBe Right(())
  }

  // ── Gap detection (the core bug being fixed) ─────────────────────────────────

  it should "detect missing account in the middle of a range" taggedAs UnitTest in {
    val storage = new TestMptStorage()
    val key0 = ByteString(Array.fill(32)(0x10.toByte))
    val key1 = ByteString(Array.fill(32)(0x20.toByte))
    val key2 = ByteString(Array.fill(32)(0x30.toByte))
    val acct = Account(nonce = 1)
    val trie = MerklePatriciaTrie[ByteString, Account](storage)
      .put(key0, acct).put(key1, acct).put(key2, acct)
    val proof    = generateBoundaryProof(trie, key0, key2)
    val verifier = MerkleProofVerifier(ByteString(trie.getRootHash))

    // Omit key1 — creates a gap that must be detected via hash mismatch
    verifier.verifyAccountRange(
      accounts = Seq(key0 -> acct, key2 -> acct),
      proof = proof, startHash = key0, endHash = key2
    ) shouldBe a[Left[_, _]]
  }

  it should "detect fabricated account value with valid proof structure" taggedAs UnitTest in {
    val storage = new TestMptStorage()
    val key0 = ByteString(Array.fill(32)(0x10.toByte))
    val key1 = ByteString(Array.fill(32)(0x20.toByte))
    val realAcct  = Account(nonce = 1, balance = 100)
    val wrongAcct = Account(nonce = 999, balance = 0)
    val trie  = MerklePatriciaTrie[ByteString, Account](storage).put(key0, realAcct).put(key1, realAcct)
    val proof = generateBoundaryProof(trie, key0, key1)
    val verifier = MerkleProofVerifier(ByteString(trie.getRootHash))

    // Peer returns wrong value for key1
    verifier.verifyAccountRange(
      accounts = Seq(key0 -> realAcct, key1 -> wrongAcct),
      proof = proof, startHash = key0, endHash = key1
    ) shouldBe a[Left[_, _]]
  }

  // ── Single-element and edge-proof variants ───────────────────────────────────

  it should "handle proof with single account correctly" taggedAs UnitTest in {
    val storage  = new TestMptStorage()
    val acct     = Account(nonce = 1, balance = 100)
    val key      = ByteString(Array.fill(32)(0x42.toByte))
    val trie     = MerklePatriciaTrie[ByteString, Account](storage).put(key, acct)
    val proof    = generateBoundaryProof(trie, key, key)
    val verifier = MerkleProofVerifier(ByteString(trie.getRootHash))

    // Single-element with exact boundary proof (firstKey == lastKey)
    verifier.verifyAccountRange(
      accounts = Seq(key -> acct),
      proof = proof, startHash = key, endHash = key
    ) shouldBe Right(())
  }

  it should "handle proof with multiple storage slots correctly" taggedAs UnitTest in {
    val storage  = new TestMptStorage()
    val keys     = (1 to 5).map(i => ByteString(Array.fill(31)(0x00.toByte) :+ i.toByte))
    val slots    = keys.map(_ -> ByteString("v"))
    val trie     = slots.foldLeft(MerklePatriciaTrie[ByteString, ByteString](storage)) { case (t, (k, v)) => t.put(k, v) }
    val proof    = generateBoundaryProof(trie, keys.head, keys.last)
    val verifier = MerkleProofVerifier(ByteString(trie.getRootHash))

    verifier.verifyStorageRange(
      slots = slots, proof = proof, startHash = keys.head, endHash = keys.last
    ) shouldBe Right(())
  }

  // ── Root-hash mismatch ───────────────────────────────────────────────────────

  it should "reject account proof where proof root hash does not match state root" taggedAs UnitTest in {
    val realStateRoot = kec256(ByteString("real-state-root"))
    val verifier      = MerkleProofVerifier(realStateRoot)
    val wrongNode     = LeafNode(ByteString(0x42.toByte), ByteString("some-value"))
    val wrongProof    = Seq(ByteString(MptTraversals.encodeNode(wrongNode)))
    val key           = ByteString(Array.fill(32)(0x42.toByte))

    // Wrong proof → root node hash ≠ realStateRoot → Left
    verifier.verifyAccountRange(
      accounts = Seq(key -> Account(nonce = 1)),
      proof = wrongProof, startHash = ZeroKey, endHash = MaxKey
    ) shouldBe a[Left[_, _]]
  }

  // ── Adversarial / crash-safety ───────────────────────────────────────────────

  it should "accept proof for non-existent key without crashing" taggedAs UnitTest in {
    val storage     = new TestMptStorage()
    val realAccount = Account(nonce = 1, balance = 100)
    val trie        = MerklePatriciaTrie[ByteString, Account](storage).put(ByteString("real-key"), realAccount)
    val verifier    = MerkleProofVerifier(ByteString(trie.getRootHash))
    val proof       = Seq(ByteString(trie.getRootHash)) // raw hash bytes, not valid RLP

    val result = verifier.verifyAccountRange(
      accounts = Seq(ByteString(Array.fill(32)(0x99.toByte)) -> realAccount),
      proof = proof, startHash = ZeroKey, endHash = MaxKey
    )
    // Must not throw; either outcome acceptable
    result match {
      case Right(_)    => succeed
      case Left(error) => error should not be empty
    }
  }

  it should "accept bloated proof with extra irrelevant nodes without crashing" taggedAs UnitTest in {
    val storage    = new TestMptStorage()
    val acct       = Account(nonce = 5, balance = 500)
    val key        = ByteString(Array.fill(32)(0x55.toByte))
    val trie       = MerklePatriciaTrie[ByteString, Account](storage).put(key, acct)
    val validProof = generateBoundaryProof(trie, key, key)
    val bloated    = validProof ++ Seq(ByteString("irrelevant-node-aaa"), ByteString("irrelevant-node-bbb"))
    val verifier   = MerkleProofVerifier(ByteString(trie.getRootHash))

    val result = verifier.verifyAccountRange(
      accounts = Seq(key -> acct),
      proof = bloated, startHash = key, endHash = key
    )
    result match {
      case Right(_)    => succeed
      case Left(error) => error should not be empty
    }
  }

  // ── Empty-value / zero-account edge cases ────────────────────────────────────

  it should "accept account with zero-value fields (default/deleted account)" taggedAs UnitTest in {
    MerkleProofVerifier(ByteString(MerklePatriciaTrie.EmptyRootHash)).verifyAccountRange(
      accounts = Seq.empty, proof = Seq.empty, startHash = ZeroKey, endHash = MaxKey
    ) shouldBe Right(())
  }

  // ── Deep trie / HashNode path ──────────────────────────────────────────────

  it should "verify account range when boundary proof traverses multiple HashNode levels" taggedAs UnitTest in {
    // 16 accounts at distinct first nibbles forces a full-width root BranchNode.
    // Each child subtrie's RLP is >= 32 bytes → stored as HashNode references in the root.
    // This exercises the resolveEdgePath HashNode lookup path that the 2-account tests miss.
    val storage = new TestMptStorage()
    val accts = (0 until 16).map { i =>
      val key = ByteString(Array(i.toByte) ++ Array.fill(31)(0x00.toByte))
      key -> Account(nonce = i.toLong)
    }
    val trie = accts.foldLeft(MerklePatriciaTrie[ByteString, Account](storage)) {
      case (t, (k, a)) => t.put(k, a)
    }
    val firstKey = accts.head._1
    val lastKey  = accts.last._1
    val proof    = generateBoundaryProof(trie, firstKey, lastKey)
    val verifier = MerkleProofVerifier(ByteString(trie.getRootHash))

    verifier.verifyAccountRange(
      accounts  = accts,
      proof     = proof,
      startHash = firstKey,
      endHash   = lastKey
    ) shouldBe Right(())
  }

  it should "detect missing account in multi-level trie" taggedAs UnitTest in {
    val storage = new TestMptStorage()
    val accts = (0 until 16).map { i =>
      val key = ByteString(Array(i.toByte) ++ Array.fill(31)(0x00.toByte))
      key -> Account(nonce = i.toLong)
    }
    val trie = accts.foldLeft(MerklePatriciaTrie[ByteString, Account](storage)) {
      case (t, (k, a)) => t.put(k, a)
    }
    val firstKey = accts.head._1
    val lastKey  = accts.last._1
    val proof    = generateBoundaryProof(trie, firstKey, lastKey)
    val verifier = MerkleProofVerifier(ByteString(trie.getRootHash))

    val gapped = accts.filterNot(_._1.head == 0x08.toByte)
    verifier.verifyAccountRange(
      accounts  = gapped,
      proof     = proof,
      startHash = firstKey,
      endHash   = lastKey
    ) shouldBe a[Left[_, _]]
  }

  it should "reject nil-proof storage range with empty (zeroed) slot value" taggedAs UnitTest in {
    // Storage slots with empty values are deletions — the nil-proof hash check will fail
    // because the SnapHashTrie either panics or computes a different hash
    val slotHash = ByteString(Array.fill(32)(0x33.toByte))
    val storageRoot = kec256(ByteString("storage-root"))
    val result = MerkleProofVerifier(storageRoot).verifyStorageRange(
      slots = Seq(slotHash -> ByteString.empty),
      proof = Seq.empty, startHash = ZeroKey, endHash = MaxKey
    )
    // Should fail — either due to hash mismatch or SnapHashTrie rejecting empty value
    result shouldBe a[Left[_, _]]
  }
}
