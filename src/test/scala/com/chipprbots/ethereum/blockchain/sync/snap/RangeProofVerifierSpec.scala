package com.chipprbots.ethereum.blockchain.sync.snap

import java.nio.ByteBuffer

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.mpt.{MerklePatriciaTrie, byteStringSerializer}
import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.testing.TestMptStorage

/** Low-level tests for [[RangeProofVerifier]].
  *
  * These tests exercise the core VerifyRangeProof algorithm (skeleton reconstruction, range unsetting, and root hash
  * verification) with real Merkle Patricia Tries and genuine boundary proofs.
  */
class RangeProofVerifierSpec extends AnyFlatSpec with Matchers {

  // ---- helpers ----

  private def key32(i: Int): Array[Byte] = {
    val buf = ByteBuffer.allocate(32)
    buf.position(28)
    buf.putInt(i)
    buf.array()
  }

  private def value(i: Int): Array[Byte] = s"v$i".getBytes("UTF-8")

  /** Build a ByteString-keyed trie and return (rootHash, trie). */
  private def buildTrie(
      kvs: Seq[(Array[Byte], Array[Byte])]
  ): (Array[Byte], MerklePatriciaTrie[ByteString, ByteString]) = {
    val storage = new TestMptStorage()
    var trie = MerklePatriciaTrie[ByteString, ByteString](storage)
    kvs.foreach { case (k, v) =>
      trie = trie.put(ByteString(k), ByteString(v))
    }
    (trie.getRootHash, trie)
  }

  /** Collect deduplicated boundary proof nodes (RLP-encoded) for a key range. */
  private def boundaryProof(
      trie: MerklePatriciaTrie[ByteString, ByteString],
      firstKey: Array[Byte],
      lastKey: Array[Byte]
  ): Seq[Array[Byte]] = {
    val proof1 = trie.getProof(ByteString(firstKey)).getOrElse(Vector.empty)
    val proof2 = trie.getProof(ByteString(lastKey)).getOrElse(Vector.empty)
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
  // Empty / degenerate cases
  // ==================================================================

  "RangeProofVerifier" should "accept empty trie: no data, no proof" taggedAs UnitTest in {
    RangeProofVerifier.verifyRangeProof(
      MerklePatriciaTrie.EmptyRootHash,
      key32(0),
      key32(0),
      Seq.empty,
      Seq.empty,
      Seq.empty
    ) shouldBe Right(true)
  }

  it should "reject no-data no-proof for non-empty root" taggedAs UnitTest in {
    RangeProofVerifier.verifyRangeProof(
      Array.fill(32)(0x01.toByte),
      key32(0),
      key32(0),
      Seq.empty,
      Seq.empty,
      Seq.empty
    ) shouldBe a[Left[_, _]]
  }

  // ==================================================================
  // Entire trie delivery (proof = empty)
  // ==================================================================

  it should "verify 1-element entire trie" taggedAs UnitTest in {
    val kvs = Seq((key32(1), value(1)))
    val (root, _) = buildTrie(kvs)

    RangeProofVerifier.verifyRangeProof(
      root,
      kvs.head._1,
      kvs.head._1,
      kvs.map(_._1),
      kvs.map(_._2),
      Seq.empty
    ) shouldBe Right(true)
  }

  it should "verify 50-element entire trie" taggedAs UnitTest in {
    val kvs = (1 to 50).map(i => (key32(i), value(i)))
    val (root, _) = buildTrie(kvs)

    RangeProofVerifier.verifyRangeProof(
      root,
      kvs.head._1,
      kvs.last._1,
      kvs.map(_._1),
      kvs.map(_._2),
      Seq.empty
    ) shouldBe Right(true)
  }

  it should "reject entire trie with extra spurious key" taggedAs UnitTest in {
    val kvs = (1 to 10).map(i => (key32(i), value(i)))
    val (root, _) = buildTrie(kvs)

    // Add an extra key that wasn't in the original trie
    val extra = kvs :+ (key32(999), value(999))

    RangeProofVerifier.verifyRangeProof(
      root,
      extra.head._1,
      extra.last._1,
      extra.map(_._1),
      extra.map(_._2),
      Seq.empty
    ) shouldBe a[Left[_, _]]
  }

  // ==================================================================
  // Partial range with boundary proof
  // ==================================================================

  it should "verify first half of trie" taggedAs UnitTest in {
    val kvs = (1 to 20).map(i => (key32(i), value(i)))
    val (root, trie) = buildTrie(kvs)

    val firstHalf = kvs.take(10)
    val proof = boundaryProof(trie, firstHalf.head._1, firstHalf.last._1)

    val result = RangeProofVerifier.verifyRangeProof(
      root,
      firstHalf.head._1,
      firstHalf.last._1,
      firstHalf.map(_._1),
      firstHalf.map(_._2),
      proof
    )

    result shouldBe a[Right[_, _]]
    // Should indicate more data exists (right half of trie)
    result.toOption.get shouldBe false
  }

  it should "verify last half of trie" taggedAs UnitTest in {
    val kvs = (1 to 20).map(i => (key32(i), value(i)))
    val (root, trie) = buildTrie(kvs)

    val lastHalf = kvs.drop(10)
    val proof = boundaryProof(trie, lastHalf.head._1, lastHalf.last._1)

    RangeProofVerifier.verifyRangeProof(
      root,
      lastHalf.head._1,
      lastHalf.last._1,
      lastHalf.map(_._1),
      lastHalf.map(_._2),
      proof
    ) shouldBe a[Right[_, _]]
  }

  it should "verify middle slice of trie" taggedAs UnitTest in {
    val kvs = (1 to 30).map(i => (key32(i), value(i)))
    val (root, trie) = buildTrie(kvs)

    val middle = kvs.slice(10, 20)
    val proof = boundaryProof(trie, middle.head._1, middle.last._1)

    RangeProofVerifier.verifyRangeProof(
      root,
      middle.head._1,
      middle.last._1,
      middle.map(_._1),
      middle.map(_._2),
      proof
    ) shouldBe a[Right[_, _]]
  }

  it should "reject partial range with value tampered" taggedAs UnitTest in {
    val kvs = (1 to 20).map(i => (key32(i), value(i)))
    val (root, trie) = buildTrie(kvs)

    val middle = kvs.slice(5, 15)
    val proof = boundaryProof(trie, middle.head._1, middle.last._1)

    val tamperedValues = middle.map(_._2).updated(4, "NOPE".getBytes)

    RangeProofVerifier.verifyRangeProof(
      root,
      middle.head._1,
      middle.last._1,
      middle.map(_._1),
      tamperedValues,
      proof
    ) shouldBe a[Left[_, _]]
  }

  it should "reject partial range with key omitted (gap attack)" taggedAs UnitTest in {
    val kvs = (1 to 20).map(i => (key32(i), value(i)))
    val (root, trie) = buildTrie(kvs)

    val full = kvs.slice(5, 15)
    val proof = boundaryProof(trie, full.head._1, full.last._1)

    // Remove an interior key to simulate a gap attack
    val withGap = full.take(3) ++ full.drop(4)

    RangeProofVerifier.verifyRangeProof(
      root,
      withGap.head._1,
      withGap.last._1,
      withGap.map(_._1),
      withGap.map(_._2),
      proof
    ) shouldBe a[Left[_, _]]
  }

  it should "verify single-key range from multi-key trie" taggedAs UnitTest in {
    val kvs = (1 to 15).map(i => (key32(i), value(i)))
    val (root, trie) = buildTrie(kvs)

    val single = Seq(kvs(7))
    val proof = boundaryProof(trie, single.head._1, single.head._1)

    RangeProofVerifier.verifyRangeProof(
      root,
      single.head._1,
      single.head._1,
      single.map(_._1),
      single.map(_._2),
      proof
    ) shouldBe a[Right[_, _]]
  }

  // ==================================================================
  // InMemoryProofStorage
  // ==================================================================

  "InMemoryProofStorage" should "store and retrieve nodes by hash" taggedAs UnitTest in {
    import com.chipprbots.ethereum.mpt.{LeafNode, NullNode}

    val storage = new RangeProofVerifier.InMemoryProofStorage()
    val leaf = LeafNode(ByteString(Array[Byte](0x01, 0x02)), ByteString("data"))

    storage.storeNode(leaf)
    val retrieved = storage.get(leaf.hash)

    retrieved shouldBe leaf
  }

  it should "throw MissingNodeException for unknown hash" taggedAs UnitTest in {
    val storage = new RangeProofVerifier.InMemoryProofStorage()

    assertThrows[MerklePatriciaTrie.MissingNodeException] {
      storage.get(Array.fill(32)(0xff.toByte))
    }
  }
}
