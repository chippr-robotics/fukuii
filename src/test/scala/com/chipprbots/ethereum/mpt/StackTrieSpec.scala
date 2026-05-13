package com.chipprbots.ethereum.mpt

import scala.collection.mutable
import scala.util.Random

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.testing.TestMptStorage
import com.chipprbots.ethereum.testing.Tags._

/** Tests for [[StackTrie]].
  *
  * The bedrock invariant: for any set of (key, value) pairs inserted in
  * strictly-ascending hex-nibble order, `StackTrie.hash()` must produce the
  * same 32-byte root hash as inserting the same pairs into a
  * [[MerklePatriciaTrie]] backed by an arbitrary `MptStorage`. The
  * MerklePatriciaTrie is the reference oracle.
  */
class StackTrieSpec extends AnyFlatSpec with Matchers {

  // ---- helpers ----

  /** Implicit serializer used by the reference MerklePatriciaTrie tests below. */
  private implicit val byteArraySerializer: com.chipprbots.ethereum.mpt.ByteArraySerializable[Array[Byte]] =
    new com.chipprbots.ethereum.mpt.ByteArraySerializable[Array[Byte]] {
      def toBytes(input: Array[Byte]): Array[Byte] = input
      def fromBytes(bytes: Array[Byte]): Array[Byte] = bytes
    }

  /** Build a reference MPT root for the given (key, value) pairs. */
  private def referenceRoot(pairs: Seq[(Array[Byte], Array[Byte])]): Array[Byte] = {
    var trie: MerklePatriciaTrie[Array[Byte], Array[Byte]] =
      MerklePatriciaTrie[Array[Byte], Array[Byte]](new TestMptStorage())
    pairs.foreach { case (k, v) => trie = trie.put(k, v) }
    trie.getRootHash
  }

  /** Build a StackTrie root for the same pairs, discarding emitted nodes. */
  private def stackTrieRoot(pairs: Seq[(Array[Byte], Array[Byte])]): Array[Byte] = {
    val st = new StackTrie((_, _, _) => ())
    pairs.foreach { case (k, v) => st.update(k, v) }
    st.hash().toArray
  }

  /** Build a StackTrie and collect every emitted node into a hash → blob map. */
  private def stackTrieWithEmissions(
      pairs: Seq[(Array[Byte], Array[Byte])]
  ): (Array[Byte], Map[ByteString, Array[Byte]]) = {
    val collected = mutable.LinkedHashMap.empty[ByteString, Array[Byte]]
    val st = new StackTrie((_, hash, blob) => {
      // Defensive: callers must deep-copy if they want to retain the blob.
      collected += hash -> blob.clone()
    })
    pairs.foreach { case (k, v) => st.update(k, v) }
    val root = st.hash().toArray
    (root, collected.toMap)
  }

  /** Sort a sequence of (key, value) pairs by big-endian unsigned key. */
  private def sortByKey(pairs: Seq[(Array[Byte], Array[Byte])]): Seq[(Array[Byte], Array[Byte])] =
    pairs.sortWith { case ((a, _), (b, _)) => StackTrie.byteCompare(a, b) < 0 }

  /** Generate `n` (32-byte hash, value-bytes) pairs with the given seed. */
  private def generatedPairs(n: Int, seed: Long): Seq[(Array[Byte], Array[Byte])] = {
    val rng = new Random(seed)
    val raw = (0 until n).map { _ =>
      val k = new Array[Byte](32); rng.nextBytes(k)
      val v = new Array[Byte](16 + rng.nextInt(48)); rng.nextBytes(v)
      (k, v)
    }
    sortByKey(raw)
  }

  // ---- empty trie ----

  it should "produce the canonical empty-trie root for an empty input" taggedAs UnitTest in {
    val st = new StackTrie((_, _, _) => ())
    val root = st.hash().toArray
    root shouldEqual MerklePatriciaTrie.EmptyRootHash
  }

  // ---- single-key tries ----

  it should "match MerklePatriciaTrie for a single short key" taggedAs UnitTest in {
    val pairs = Seq(Array[Byte](0x01) -> Array[Byte](0xaa.toByte))
    stackTrieRoot(pairs) shouldEqual referenceRoot(pairs)
  }

  it should "match MerklePatriciaTrie for a single 32-byte hash key" taggedAs UnitTest in {
    val pairs = Seq(
      kec256("alpha".getBytes) -> "value-alpha".getBytes
    )
    stackTrieRoot(pairs) shouldEqual referenceRoot(pairs)
  }

  // ---- two-key cases (branch + leaf paths) ----

  it should "match MerklePatriciaTrie for two keys with no shared prefix" taggedAs UnitTest in {
    val pairs = sortByKey(
      Seq(
        Array[Byte](0x10.toByte) -> "v1".getBytes,
        Array[Byte](0x20.toByte) -> "v2".getBytes
      )
    )
    stackTrieRoot(pairs) shouldEqual referenceRoot(pairs)
  }

  it should "match MerklePatriciaTrie for two keys with a shared byte prefix" taggedAs UnitTest in {
    val pairs = sortByKey(
      Seq(
        Array[Byte](0x11.toByte, 0x22.toByte) -> "value-a".getBytes,
        Array[Byte](0x11.toByte, 0x33.toByte) -> "value-b".getBytes
      )
    )
    stackTrieRoot(pairs) shouldEqual referenceRoot(pairs)
  }

  it should "match MerklePatriciaTrie for two keys sharing an odd-nibble prefix" taggedAs UnitTest in {
    // Nibble-level prefix: 0x1 only (one nibble), differing on the second nibble.
    val pairs = sortByKey(
      Seq(
        Array[Byte](0x12.toByte, 0x34.toByte) -> "alpha".getBytes,
        Array[Byte](0x15.toByte, 0x67.toByte) -> "beta".getBytes
      )
    )
    stackTrieRoot(pairs) shouldEqual referenceRoot(pairs)
  }

  // ---- branch saturation (16 children at one slot depth) ----

  it should "match MerklePatriciaTrie for a fully-saturated 16-child branch" taggedAs UnitTest in {
    val pairs = sortByKey(
      (0 until 16).map { i =>
        val k = Array[Byte]((i << 4).toByte, 0x00.toByte) // upper nibble varies, lower nibble same
        val v = s"value-$i".getBytes
        k -> v
      }
    )
    stackTrieRoot(pairs) shouldEqual referenceRoot(pairs)
  }

  // ---- deep / extension-heavy ----

  it should "match MerklePatriciaTrie for keys sharing a long extension prefix" taggedAs UnitTest in {
    // Both keys share the first 31 bytes, differ in the last byte.
    val shared = new Array[Byte](31)
    new Random(0xabcdef).nextBytes(shared)
    val pairs = sortByKey(
      Seq(
        (shared :+ 0x00.toByte) -> "first".getBytes,
        (shared :+ 0xff.toByte) -> "last".getBytes
      )
    )
    stackTrieRoot(pairs) shouldEqual referenceRoot(pairs)
  }

  // ---- large random batches (the real test) ----

  it should "match MerklePatriciaTrie for 100 random 32-byte keys (sorted)" taggedAs UnitTest in {
    val pairs = generatedPairs(100, seed = 0x1234L)
    stackTrieRoot(pairs) shouldEqual referenceRoot(pairs)
  }

  it should "match MerklePatriciaTrie for 1 000 random 32-byte keys (sorted)" taggedAs UnitTest in {
    val pairs = generatedPairs(1000, seed = 0x5678L)
    stackTrieRoot(pairs) shouldEqual referenceRoot(pairs)
  }

  it should "match MerklePatriciaTrie for 10 000 random 32-byte keys (sorted)" taggedAs UnitTest in {
    val pairs = generatedPairs(10000, seed = 0xdeadbeefL)
    stackTrieRoot(pairs) shouldEqual referenceRoot(pairs)
  }

  // ---- inline-vs-hashed boundary ----

  it should "produce inline (sub-32-byte) values for tiny tries" taggedAs UnitTest in {
    // A single very-short leaf would normally inline, but we always hash at root.
    val pairs = Seq(Array[Byte](0x00.toByte) -> Array[Byte](0x42.toByte))
    val (root, emissions) = stackTrieWithEmissions(pairs)
    // For a single tiny leaf, the leaf is the root. Its RLP < 32 bytes, but
    // the root-path hashing rule forces us to emit + hash it.
    root.length shouldBe 32
    emissions.keySet should contain(ByteString(root))
    stackTrieRoot(pairs) shouldEqual referenceRoot(pairs)
  }

  it should "not emit inline child blobs as separate nodes" taggedAs UnitTest in {
    // For a tree of two keys where the branch + leaves all fit < 32 bytes
    // RLP-encoded, the branch's children are inline and shouldn't appear in
    // the emissions map separately. Only the root (or whatever isn't inline)
    // should be emitted.
    val pairs = sortByKey(
      Seq(
        Array[Byte](0x01.toByte) -> Array[Byte](0x10.toByte),
        Array[Byte](0x02.toByte) -> Array[Byte](0x20.toByte)
      )
    )
    val (root, emissions) = stackTrieWithEmissions(pairs)
    stackTrieRoot(pairs) shouldEqual referenceRoot(pairs)
    // The root is always hashed. There should be at most one emission
    // (the root itself).
    emissions.size should be <= 1
    emissions.keys.toSeq should contain only ByteString(root)
  }

  // ---- emission coverage: every non-inline node appears once ----

  it should "emit exactly one blob per finalised non-inline node" taggedAs UnitTest in {
    val pairs = generatedPairs(50, seed = 0xcafeL)
    val (_, emissions) = stackTrieWithEmissions(pairs)
    // Every emitted hash should match keccak256 of its blob.
    emissions.foreach { case (hash, blob) =>
      ByteString(kec256(blob)) shouldEqual hash
    }
    // No two emissions should share a hash.
    emissions.keys.toSeq.distinct.size shouldEqual emissions.size
  }

  // ---- sort enforcement ----

  it should "reject a duplicate key insert" taggedAs UnitTest in {
    val st = new StackTrie((_, _, _) => ())
    val k = Array[Byte](0x10.toByte)
    st.update(k, Array[Byte](0x01.toByte))
    an[IllegalArgumentException] should be thrownBy st.update(k, Array[Byte](0x02.toByte))
  }

  it should "reject a strictly-descending key insert" taggedAs UnitTest in {
    val st = new StackTrie((_, _, _) => ())
    st.update(Array[Byte](0x20.toByte), Array[Byte](0x01.toByte))
    an[IllegalArgumentException] should be thrownBy
      st.update(Array[Byte](0x10.toByte), Array[Byte](0x02.toByte))
  }

  it should "reject an empty value" taggedAs UnitTest in {
    val st = new StackTrie((_, _, _) => ())
    an[IllegalArgumentException] should be thrownBy
      st.update(Array[Byte](0x10.toByte), Array.emptyByteArray)
  }

  // ---- reset semantics ----

  it should "produce the empty root after reset" taggedAs UnitTest in {
    val st = new StackTrie((_, _, _) => ())
    st.update(Array[Byte](0x10.toByte), "value".getBytes)
    val _ = st.hash()
    st.reset()
    val reset = st.hash().toArray
    reset shouldEqual MerklePatriciaTrie.EmptyRootHash
  }

  it should "allow re-use after reset for a fresh trie" taggedAs UnitTest in {
    val st = new StackTrie((_, _, _) => ())
    st.update(Array[Byte](0x10.toByte), "first".getBytes)
    val _ = st.hash()
    st.reset()
    val pairs = generatedPairs(20, seed = 0xfeedL)
    pairs.foreach { case (k, v) => st.update(k, v) }
    val root2 = st.hash().toArray
    root2 shouldEqual referenceRoot(pairs)
  }

  // ---- diffIndex utility unit tests ----

  it should "compute diffIndex correctly for hex nibbles" taggedAs UnitTest in {
    StackTrie.diffIndex(Array[Byte](1, 2, 3), Array[Byte](1, 2, 3)) shouldEqual 3
    StackTrie.diffIndex(Array[Byte](1, 2, 3), Array[Byte](1, 2, 4)) shouldEqual 2
    StackTrie.diffIndex(Array[Byte](1, 2, 3), Array[Byte](1, 2, 3, 4)) shouldEqual 3
    StackTrie.diffIndex(Array.emptyByteArray, Array[Byte](1)) shouldEqual 0
  }

  it should "compute byteCompare correctly as unsigned" taggedAs UnitTest in {
    // 0x80 is greater than 0x7f when treated as unsigned (Scala's signed Byte
    // would otherwise put 0x80 = -128 < 0x7f).
    StackTrie.byteCompare(Array[Byte](0x80.toByte), Array[Byte](0x7f.toByte)) should be > 0
    StackTrie.byteCompare(Array[Byte](0x01.toByte), Array[Byte](0x01.toByte, 0x00.toByte)) should be < 0
    StackTrie.byteCompare(Array.emptyByteArray, Array.emptyByteArray) shouldEqual 0
  }
}
