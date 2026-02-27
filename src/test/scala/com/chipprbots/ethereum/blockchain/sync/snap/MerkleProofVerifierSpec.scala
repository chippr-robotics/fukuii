package com.chipprbots.ethereum.blockchain.sync.snap

import java.nio.ByteBuffer

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.mpt.{HexPrefix, MerklePatriciaTrie, byteStringSerializer}
import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.testing.TestMptStorage

/** Comprehensive tests for SNAP range proof verification.
  *
  * Exercises the RangeProofVerifier algorithm (used by MerkleProofVerifier) with real Merkle Patricia Tries and genuine
  * boundary proofs, validating correctness for: entire-trie delivery, partial range with proof, proof-of-absence,
  * tampered data, and F6 (storage root verification without proof).
  */
class MerkleProofVerifierSpec extends AnyFlatSpec with Matchers {

  // ---- helpers ----

  /** Create a deterministic 32-byte key from an integer. */
  private def key32(i: Int): Array[Byte] = {
    val buf = ByteBuffer.allocate(32)
    buf.position(28) // put the int at the end so keys are well-distributed
    buf.putInt(i)
    buf.array()
  }

  /** Short value for testing. */
  private def value(i: Int): Array[Byte] = s"val-$i".getBytes("UTF-8")

  /** Build a trie over sorted (key, value) pairs and return (rootHash, storage, trie). */
  private def buildTrie(
      kvs: Seq[(Array[Byte], Array[Byte])]
  ): (Array[Byte], TestMptStorage, MerklePatriciaTrie[ByteString, ByteString]) = {
    val storage = new TestMptStorage()
    var trie = MerklePatriciaTrie[ByteString, ByteString](storage)
    kvs.foreach { case (k, v) =>
      trie = trie.put(ByteString(k), ByteString(v))
    }
    (trie.getRootHash, storage, trie)
  }

  /** Collect boundary-proof nodes for firstKey and lastKey, de-duplicated by hash, RLP-encoded. */
  private def boundaryProof(
      trie: MerklePatriciaTrie[ByteString, ByteString],
      firstKey: Array[Byte],
      lastKey: Array[Byte]
  ): Seq[Array[Byte]] = {
    val proof1 = trie.getProof(ByteString(firstKey)).getOrElse(Vector.empty)
    val proof2 = trie.getProof(ByteString(lastKey)).getOrElse(Vector.empty)
    // Deduplicate by hash, encode each node to RLP
    val seen = scala.collection.mutable.Set.empty[ByteString]
    val combined = scala.collection.mutable.ArrayBuffer.empty[Array[Byte]]
    (proof1 ++ proof2).foreach { node =>
      val h = ByteString(node.hash)
      if (seen.add(h)) {
        combined += node.encode
      }
    }
    combined.toSeq
  }

  // ==================================================================
  // RangeProofVerifier — core algorithm tests
  // ==================================================================

  "RangeProofVerifier" should "accept empty trie (no data, no proof)" taggedAs UnitTest in {
    val emptyRoot = MerklePatriciaTrie.EmptyRootHash

    val result = RangeProofVerifier.verifyRangeProof(
      expectedRoot = emptyRoot,
      firstKey = key32(0),
      lastKey = key32(0),
      keys = Seq.empty,
      values = Seq.empty,
      proof = Seq.empty
    )

    result shouldBe Right(true)
  }

  it should "reject empty data + empty proof for non-empty trie" taggedAs UnitTest in {
    val fakeRoot = Array.fill(32)(0xab.toByte)

    val result = RangeProofVerifier.verifyRangeProof(
      expectedRoot = fakeRoot,
      firstKey = key32(0),
      lastKey = key32(0),
      keys = Seq.empty,
      values = Seq.empty,
      proof = Seq.empty
    )

    result shouldBe a[Left[_, _]]
  }

  it should "verify entire trie delivery (no proof needed)" taggedAs UnitTest in {
    val kvs = (1 to 10).map(i => (key32(i), value(i)))
    val (rootHash, _, _) = buildTrie(kvs)

    val result = RangeProofVerifier.verifyRangeProof(
      expectedRoot = rootHash,
      firstKey = kvs.head._1,
      lastKey = kvs.last._1,
      keys = kvs.map(_._1),
      values = kvs.map(_._2),
      proof = Seq.empty
    )

    result shouldBe Right(true) // entire trie delivered
  }

  it should "reject entire trie delivery when a value is tampered" taggedAs UnitTest in {
    val kvs = (1 to 10).map(i => (key32(i), value(i)))
    val (rootHash, _, _) = buildTrie(kvs)

    // Tamper with one value
    val tamperedValues = kvs.map(_._2).updated(3, "TAMPERED".getBytes)

    val result = RangeProofVerifier.verifyRangeProof(
      expectedRoot = rootHash,
      firstKey = kvs.head._1,
      lastKey = kvs.last._1,
      keys = kvs.map(_._1),
      values = tamperedValues,
      proof = Seq.empty
    )

    result shouldBe a[Left[_, _]]
  }

  it should "reject entire trie delivery when a key is missing" taggedAs UnitTest in {
    val kvs = (1 to 10).map(i => (key32(i), value(i)))
    val (rootHash, _, _) = buildTrie(kvs)

    // Drop one key-value pair
    val withMissing = kvs.take(4) ++ kvs.drop(5)

    val result = RangeProofVerifier.verifyRangeProof(
      expectedRoot = rootHash,
      firstKey = withMissing.head._1,
      lastKey = withMissing.last._1,
      keys = withMissing.map(_._1),
      values = withMissing.map(_._2),
      proof = Seq.empty
    )

    result shouldBe a[Left[_, _]]
  }

  it should "verify a partial range with boundary proof" taggedAs UnitTest in {
    // Build trie with 20 entries
    val kvs = (1 to 20).map(i => (key32(i), value(i)))
    val (rootHash, _, trie) = buildTrie(kvs)

    // Deliver a sub-range: keys 5-15
    val subRange = kvs.slice(4, 15) // indices 4..14 = keys 5..15

    // Get boundary proofs
    val proof = boundaryProof(trie, subRange.head._1, subRange.last._1)

    val result = RangeProofVerifier.verifyRangeProof(
      expectedRoot = rootHash,
      firstKey = subRange.head._1,
      lastKey = subRange.last._1,
      keys = subRange.map(_._1),
      values = subRange.map(_._2),
      proof = proof
    )

    result shouldBe a[Right[_, _]]
  }

  it should "reject partial range with tampered value" taggedAs UnitTest in {
    val kvs = (1 to 20).map(i => (key32(i), value(i)))
    val (rootHash, _, trie) = buildTrie(kvs)

    val subRange = kvs.slice(4, 15)
    val proof = boundaryProof(trie, subRange.head._1, subRange.last._1)

    // Tamper
    val tamperedValues = subRange.map(_._2).updated(3, "BAD".getBytes)

    val result = RangeProofVerifier.verifyRangeProof(
      expectedRoot = rootHash,
      firstKey = subRange.head._1,
      lastKey = subRange.last._1,
      keys = subRange.map(_._1),
      values = tamperedValues,
      proof = proof
    )

    result shouldBe a[Left[_, _]]
  }

  it should "reject partial range with missing key in middle" taggedAs UnitTest in {
    val kvs = (1 to 20).map(i => (key32(i), value(i)))
    val (rootHash, _, trie) = buildTrie(kvs)

    val subRange = kvs.slice(4, 15)
    val proof = boundaryProof(trie, subRange.head._1, subRange.last._1)

    // Remove one entry from the middle
    val withHole = subRange.take(3) ++ subRange.drop(4)

    val result = RangeProofVerifier.verifyRangeProof(
      expectedRoot = rootHash,
      firstKey = withHole.head._1,
      lastKey = withHole.last._1,
      keys = withHole.map(_._1),
      values = withHole.map(_._2),
      proof = proof
    )

    result shouldBe a[Left[_, _]]
  }

  it should "verify single-element range with proof" taggedAs UnitTest in {
    val kvs = (1 to 10).map(i => (key32(i), value(i)))
    val (rootHash, _, trie) = buildTrie(kvs)

    // Deliver just one key
    val single = Seq(kvs(4))
    val proof = boundaryProof(trie, single.head._1, single.head._1)

    val result = RangeProofVerifier.verifyRangeProof(
      expectedRoot = rootHash,
      firstKey = single.head._1,
      lastKey = single.head._1,
      keys = single.map(_._1),
      values = single.map(_._2),
      proof = proof
    )

    result shouldBe a[Right[_, _]]
  }

  it should "verify single-element trie with proof" taggedAs UnitTest in {
    val kvs = Seq((key32(42), value(42)))
    val (rootHash, _, trie) = buildTrie(kvs)

    val proof = boundaryProof(trie, kvs.head._1, kvs.head._1)

    val result = RangeProofVerifier.verifyRangeProof(
      expectedRoot = rootHash,
      firstKey = kvs.head._1,
      lastKey = kvs.head._1,
      keys = kvs.map(_._1),
      values = kvs.map(_._2),
      proof = proof
    )

    result shouldBe a[Right[_, _]]
  }

  it should "report 'entire trie' when all data is delivered with proof" taggedAs UnitTest in {
    val kvs = (1 to 5).map(i => (key32(i), value(i)))
    val (rootHash, _, trie) = buildTrie(kvs)

    // Provide proof even though all data is present
    val proof = boundaryProof(trie, kvs.head._1, kvs.last._1)

    val result = RangeProofVerifier.verifyRangeProof(
      expectedRoot = rootHash,
      firstKey = kvs.head._1,
      lastKey = kvs.last._1,
      keys = kvs.map(_._1),
      values = kvs.map(_._2),
      proof = proof
    )

    // Should succeed; the boolean indicates no more data beyond the range
    result shouldBe a[Right[_, _]]
  }

  it should "reject proof with wrong root hash" taggedAs UnitTest in {
    val kvs = (1 to 5).map(i => (key32(i), value(i)))
    val (_, _, trie) = buildTrie(kvs)

    val proof = boundaryProof(trie, kvs.head._1, kvs.last._1)
    val wrongRoot = Array.fill(32)(0xde.toByte)

    val result = RangeProofVerifier.verifyRangeProof(
      expectedRoot = wrongRoot,
      firstKey = kvs.head._1,
      lastKey = kvs.last._1,
      keys = kvs.map(_._1),
      values = kvs.map(_._2),
      proof = proof
    )

    result shouldBe a[Left[_, _]]
  }

  // ==================================================================
  // MerkleProofVerifier — facade tests (delegates to RangeProofVerifier)
  // ==================================================================

  "MerkleProofVerifier.verifyAccountRange" should "accept entire account range delivery (no proof)" taggedAs UnitTest in {
    // Build the state trie exactly as the verifier will reconstruct it:
    //   key   = raw accountHash bytes
    //   value = Account.accountSerializer.toBytes(account)
    val accounts = (1 to 5).map { i =>
      val hash = ByteString(key32(i))
      val acct = Account(nonce = i, balance = i * 100)
      (hash, acct)
    }

    val storage = new TestMptStorage()
    var trie = MerklePatriciaTrie[ByteString, ByteString](storage)
    accounts.foreach { case (hash, acct) =>
      trie = trie.put(hash, ByteString(Account.accountSerializer.toBytes(acct)))
    }
    val stateRoot = ByteString(trie.getRootHash)

    val verifier = MerkleProofVerifier(stateRoot)
    val result = verifier.verifyAccountRange(
      accounts = accounts,
      proof = Seq.empty,
      startHash = accounts.head._1,
      endHash = accounts.last._1
    )

    result shouldBe Right(())
  }

  it should "reject tampered account in entire delivery" taggedAs UnitTest in {
    val accounts = (1 to 5).map { i =>
      val hash = ByteString(key32(i))
      val acct = Account(nonce = i, balance = i * 100)
      (hash, acct)
    }

    val storage = new TestMptStorage()
    var trie = MerklePatriciaTrie[ByteString, ByteString](storage)
    accounts.foreach { case (hash, acct) =>
      trie = trie.put(hash, ByteString(Account.accountSerializer.toBytes(acct)))
    }
    val stateRoot = ByteString(trie.getRootHash)

    // Tamper: change one account's balance
    val tampered = accounts.updated(2, (accounts(2)._1, Account(nonce = 3, balance = 999999)))

    val verifier = MerkleProofVerifier(stateRoot)
    val result = verifier.verifyAccountRange(
      accounts = tampered,
      proof = Seq.empty,
      startHash = tampered.head._1,
      endHash = tampered.last._1
    )

    result shouldBe a[Left[_, _]]
  }

  it should "accept empty account range for empty trie" taggedAs UnitTest in {
    val stateRoot = ByteString(MerklePatriciaTrie.EmptyRootHash)
    val verifier = MerkleProofVerifier(stateRoot)

    val result = verifier.verifyAccountRange(
      accounts = Seq.empty,
      proof = Seq.empty,
      startHash = ByteString(Array.fill(32)(0.toByte)),
      endHash = ByteString(Array.fill(32)(0xff.toByte))
    )

    result shouldBe Right(())
  }

  it should "reject empty account range for non-empty trie (missing proof)" taggedAs UnitTest in {
    val fakeRoot = ByteString(Array.fill(32)(0xab.toByte))
    val verifier = MerkleProofVerifier(fakeRoot)

    val result = verifier.verifyAccountRange(
      accounts = Seq.empty,
      proof = Seq.empty,
      startHash = ByteString(Array.fill(32)(0.toByte)),
      endHash = ByteString(Array.fill(32)(0xff.toByte))
    )

    result shouldBe a[Left[_, _]]
  }

  it should "reject malformed proof gracefully" taggedAs UnitTest in {
    val fakeRoot = ByteString(Array.fill(32)(0xab.toByte))
    val verifier = MerkleProofVerifier(fakeRoot)

    val account = (ByteString(key32(1)), Account(nonce = 1, balance = 100))
    val malformedProof = Seq(ByteString("not-a-valid-rlp-node"))

    val result = verifier.verifyAccountRange(
      accounts = Seq(account),
      proof = malformedProof,
      startHash = account._1,
      endHash = account._1
    )

    result shouldBe a[Left[_, _]]
  }

  // ==================================================================
  // MerkleProofVerifier.verifyStorageRange
  // ==================================================================

  "MerkleProofVerifier.verifyStorageRange" should "accept entire storage delivery (no proof) — F6 fix" taggedAs UnitTest in {
    // Build a storage trie with known slots
    val slots = (1 to 8).map(i => (ByteString(key32(i)), ByteString(value(i))))

    val storage = new TestMptStorage()
    var trie = MerklePatriciaTrie[ByteString, ByteString](storage)
    slots.foreach { case (k, v) => trie = trie.put(k, v) }
    val storageRoot = ByteString(trie.getRootHash)

    val verifier = MerkleProofVerifier(storageRoot)
    val result = verifier.verifyStorageRange(
      slots = slots,
      proof = Seq.empty,
      startHash = slots.head._1,
      endHash = slots.last._1
    )

    result shouldBe Right(())
  }

  it should "reject tampered slot value in entire delivery — F6 fix" taggedAs UnitTest in {
    val slots = (1 to 8).map(i => (ByteString(key32(i)), ByteString(value(i))))

    val storage = new TestMptStorage()
    var trie = MerklePatriciaTrie[ByteString, ByteString](storage)
    slots.foreach { case (k, v) => trie = trie.put(k, v) }
    val storageRoot = ByteString(trie.getRootHash)

    val tampered = slots.updated(3, (slots(3)._1, ByteString("EVIL")))

    val verifier = MerkleProofVerifier(storageRoot)
    val result = verifier.verifyStorageRange(
      slots = tampered,
      proof = Seq.empty,
      startHash = tampered.head._1,
      endHash = tampered.last._1
    )

    result shouldBe a[Left[_, _]]
  }

  it should "accept empty storage for empty trie" taggedAs UnitTest in {
    val emptyRoot = ByteString(MerklePatriciaTrie.EmptyRootHash)
    val verifier = MerkleProofVerifier(emptyRoot)

    val result = verifier.verifyStorageRange(
      slots = Seq.empty,
      proof = Seq.empty,
      startHash = ByteString(Array.fill(32)(0.toByte)),
      endHash = ByteString(Array.fill(32)(0xff.toByte))
    )

    result shouldBe Right(())
  }

  it should "reject empty storage for non-empty trie" taggedAs UnitTest in {
    val fakeRoot = ByteString(Array.fill(32)(0xcc.toByte))
    val verifier = MerkleProofVerifier(fakeRoot)

    val result = verifier.verifyStorageRange(
      slots = Seq.empty,
      proof = Seq.empty,
      startHash = ByteString(Array.fill(32)(0.toByte)),
      endHash = ByteString(Array.fill(32)(0xff.toByte))
    )

    result shouldBe a[Left[_, _]]
  }
}
