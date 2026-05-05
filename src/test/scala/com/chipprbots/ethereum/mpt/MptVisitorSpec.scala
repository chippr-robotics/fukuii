package com.chipprbots.ethereum.mpt

import org.apache.pekko.util.ByteString

import scala.collection.mutable

import org.scalacheck.Arbitrary
import org.scalacheck.Gen
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage._
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.defaultByteArraySerializable
import com.chipprbots.ethereum.mpt.MptVisitors.LeafWalkVisitor
import com.chipprbots.ethereum.mpt.MptVisitors.PathTrackingLeafWalkVisitor
import com.chipprbots.ethereum.testing.Tags._

/** Unit tests for LeafWalkVisitor and PathTrackingLeafWalkVisitor.
  *
  * Both visitors are used by the SNAP healing layer (BytecodeRecoveryActor and StorageRecoveryActor) to walk the state
  * trie and find missing nodes. They have zero existing tests despite being on the critical path for SNAP sync
  * correctness.
  *
  * Test strategy: build tries with known key-value pairs, walk them with the visitor under test, and verify that every
  * leaf is visited exactly once with the correct data.
  */
class MptVisitorSpec extends AnyFunSuite with ScalaCheckPropertyChecks {

  // Each test gets its own isolated storage to prevent cross-test interference.
  private def freshStorage(): MptStorage = {
    val (stateStorage, _, _) = StateStorage.createTestStateStorage(EphemDataSource())
    stateStorage.getBackingStorage(0)
  }

  private def freshTrie(storage: MptStorage): MerklePatriciaTrie[Array[Byte], Array[Byte]] =
    MerklePatriciaTrie[Array[Byte], Array[Byte]](storage)

  // Walk a trie from its root hash using LeafWalkVisitor.
  // Returns the values from all leaves in visit order.
  private def walkLeaves(rootHash: Array[Byte], storage: MptStorage): Seq[ByteString] = {
    // EmptyRootHash is a sentinel for an empty trie — no node is ever stored under it.
    if (java.util.Arrays.equals(rootHash, MerklePatriciaTrie.EmptyRootHash)) return Seq.empty
    val collected = mutable.ArrayBuffer.empty[ByteString]
    val visitor = new LeafWalkVisitor(storage, leaf => collected += leaf.value)
    MptTraversals.dispatch(HashNode(rootHash), visitor)
    collected.toSeq
  }

  // Walk a trie from its root hash using PathTrackingLeafWalkVisitor.
  // Returns (reconstructedKeyBytes → valueBytes) pairs.
  private def walkWithPaths(rootHash: Array[Byte], storage: MptStorage): Seq[(ByteString, ByteString)] = {
    if (java.util.Arrays.equals(rootHash, MerklePatriciaTrie.EmptyRootHash)) return Seq.empty
    val collected = mutable.ArrayBuffer.empty[(ByteString, ByteString)]
    val visitor =
      new PathTrackingLeafWalkVisitor(storage, ByteString(), (path, leaf) => collected += ((path, leaf.value)))
    MptTraversals.dispatch(HashNode(rootHash), visitor)
    collected.toSeq
  }

  // ---------------------------------------------------------------------------
  // LeafWalkVisitor — leaf count and value correctness
  // ---------------------------------------------------------------------------

  test("LeafWalkVisitor visits no leaves in an empty trie", UnitTest, MPTTest) {
    val storage = freshStorage()
    val trie = freshTrie(storage)
    val leaves = walkLeaves(trie.getRootHash, storage)
    assert(leaves.isEmpty, s"Expected 0 leaves, got ${leaves.size}")
  }

  test("LeafWalkVisitor visits exactly one leaf for a single key-value", UnitTest, MPTTest) {
    val storage = freshStorage()
    val value = Array[Byte](0x42)
    val trie = freshTrie(storage).put(Array[Byte](0x10), value)
    val leaves = walkLeaves(trie.getRootHash, storage)
    assert(leaves.size == 1, s"Expected 1 leaf, got ${leaves.size}")
    assert(leaves.head.toArray.sameElements(value))
  }

  test("LeafWalkVisitor visits all leaves for two keys with different first nibble (branch root)", UnitTest, MPTTest) {
    val storage = freshStorage()
    val k1 = Array[Byte](0x10)
    val k2 = Array[Byte](0xf0.toByte)
    val v1 = Array[Byte](0x01)
    val v2 = Array[Byte](0x02)
    val trie = freshTrie(storage).put(k1, v1).put(k2, v2)
    val leaves = walkLeaves(trie.getRootHash, storage)
    assert(leaves.size == 2, s"Expected 2 leaves, got ${leaves.size}")
    val valueSet = leaves.map(_.toArray.toSeq).toSet
    assert(valueSet == Set(v1.toSeq, v2.toSeq))
  }

  test("LeafWalkVisitor visits all leaves for two keys with common prefix (extension root)", UnitTest, MPTTest) {
    val storage = freshStorage()
    val k1 = Array[Byte](0x11, 0x11)
    val k2 = Array[Byte](0x11, 0x00)
    val trie = freshTrie(storage).put(k1, k1).put(k2, k2)
    val leaves = walkLeaves(trie.getRootHash, storage)
    assert(leaves.size == 2, s"Expected 2 leaves, got ${leaves.size}")
  }

  test("LeafWalkVisitor visits all 16 leaves when keys span all branch nibbles", UnitTest, MPTTest) {
    val storage = freshStorage()
    val entries = (0 until 16).map(i => (Array[Byte]((i << 4).toByte), Array[Byte](i.toByte)))
    val trie = entries.foldLeft(freshTrie(storage)) { case (t, (k, v)) => t.put(k, v) }
    val leaves = walkLeaves(trie.getRootHash, storage)
    assert(leaves.size == 16, s"Expected 16 leaves, got ${leaves.size}")
  }

  test(
    "LeafWalkVisitor visits no leaf twice (all visited values are distinct when values are unique)",
    UnitTest,
    MPTTest
  ) {
    val storage = freshStorage()
    val entries = (0 until 8).map(i => (Array[Byte](i.toByte, 0x00.toByte), Array[Byte](i.toByte)))
    val trie = entries.foldLeft(freshTrie(storage)) { case (t, (k, v)) => t.put(k, v) }
    val leaves = walkLeaves(trie.getRootHash, storage)
    val unique = leaves.map(_.toArray.toSeq).toSet
    assert(unique.size == leaves.size, s"Duplicate leaves detected: ${leaves.size} visits, ${unique.size} unique")
  }

  test("LeafWalkVisitor count matches insert count for random key-value sets", UnitTest, MPTTest) {
    val pairsGen: Gen[Map[Seq[Byte], Seq[Byte]]] = Gen.mapOf(
      for {
        k <- Gen.listOfN(4, Arbitrary.arbitrary[Byte])
        v <- Gen.nonEmptyListOf(Arbitrary.arbitrary[Byte])
      } yield (k, v)
    )

    forAll(pairsGen) { (pairs: Map[Seq[Byte], Seq[Byte]]) =>
      val storage = freshStorage()
      val trie = pairs.foldLeft(freshTrie(storage)) { case (t, (k, v)) =>
        t.put(k.toArray, v.toArray)
      }
      val leaves = walkLeaves(trie.getRootHash, storage)
      // map keys are distinct so trie has exactly pairs.size entries
      assert(leaves.size == pairs.size, s"Expected ${pairs.size} leaves, got ${leaves.size}")
    }
  }

  // ---------------------------------------------------------------------------
  // PathTrackingLeafWalkVisitor — path reconstruction correctness
  // ---------------------------------------------------------------------------

  test("PathTrackingLeafWalkVisitor returns no entries for an empty trie", UnitTest, MPTTest) {
    val storage = freshStorage()
    val trie = freshTrie(storage)
    val paths = walkWithPaths(trie.getRootHash, storage)
    assert(paths.isEmpty)
  }

  test("PathTrackingLeafWalkVisitor reconstructs the original key bytes for a single-entry trie", UnitTest, MPTTest) {
    val storage = freshStorage()
    val key = Array[Byte](0xab.toByte, 0xcd.toByte)
    val value = Array[Byte](0x99.toByte)
    val trie = freshTrie(storage).put(key, value)
    val paths = walkWithPaths(trie.getRootHash, storage)
    assert(paths.size == 1, s"Expected 1 entry, got ${paths.size}")
    // The visitor converts nibble pairs back to bytes: 0xA||0xB → 0xAB, 0xC||0xD → 0xCD
    assert(
      paths.head._1.toArray.sameElements(key),
      s"Key mismatch: expected ${key.map(b => f"$b%02x").mkString}, " +
        s"got ${paths.head._1.toArray.map(b => f"$b%02x").mkString}"
    )
    assert(paths.head._2.toArray.sameElements(value))
  }

  test("PathTrackingLeafWalkVisitor reconstructs all original keys for a multi-entry trie", UnitTest, MPTTest) {
    val storage = freshStorage()
    val entries = Map(
      Array[Byte](0x00, 0x01) -> Array[Byte](0x01),
      Array[Byte](0x00, 0x02) -> Array[Byte](0x02),
      Array[Byte](0x0f.toByte, 0xff.toByte) -> Array[Byte](0x03),
      Array[Byte](0x10, 0x00) -> Array[Byte](0x04)
    )
    val trie = entries.foldLeft(freshTrie(storage)) { case (t, (k, v)) => t.put(k, v) }
    val paths = walkWithPaths(trie.getRootHash, storage)
    assert(paths.size == entries.size, s"Expected ${entries.size} paths, got ${paths.size}")

    val reconstructedKeys = paths.map(_._1.toArray.toSeq).toSet
    val originalKeys = entries.keys.map(_.toSeq).toSet
    assert(reconstructedKeys == originalKeys, "Reconstructed keys do not match originals")
  }

  test("PathTrackingLeafWalkVisitor handles all-zero and all-one byte keys", UnitTest, MPTTest) {
    val storage = freshStorage()
    val k1 = Array[Byte](0x00, 0x00)
    val k2 = Array[Byte](0xff.toByte, 0xff.toByte)
    val trie = freshTrie(storage).put(k1, Array[Byte](1)).put(k2, Array[Byte](2))
    val paths = walkWithPaths(trie.getRootHash, storage)
    val keys = paths.map(_._1.toArray.toSeq).toSet
    assert(keys.contains(k1.toSeq), "All-zero key not reconstructed")
    assert(keys.contains(k2.toSeq), "All-one key not reconstructed")
  }

  test("PathTrackingLeafWalkVisitor reconstructs keys for random inputs (property-based)", UnitTest, MPTTest) {
    val pairsGen: Gen[Map[Seq[Byte], Seq[Byte]]] = Gen
      .mapOf(
        for {
          // Use fixed-length 2-byte keys so nibble count is always even and reconstruction is unambiguous
          k <- Gen.listOfN(2, Arbitrary.arbitrary[Byte])
          v <- Gen.nonEmptyListOf(Arbitrary.arbitrary[Byte])
        } yield (k, v)
      )
      .suchThat(_.nonEmpty)

    forAll(pairsGen) { (pairs: Map[Seq[Byte], Seq[Byte]]) =>
      val storage = freshStorage()
      val trie = pairs.foldLeft(freshTrie(storage)) { case (t, (k, v)) =>
        t.put(k.toArray, v.toArray)
      }
      val paths = walkWithPaths(trie.getRootHash, storage)
      assert(paths.size == pairs.size, s"Expected ${pairs.size} paths, got ${paths.size}")

      val reconstructed = paths.map(_._1.toArray.toSeq).toSet
      val expected = pairs.keys.toSet
      assert(reconstructed == expected, s"Key reconstruction failed. Expected $expected, got $reconstructed")
    }
  }
}
