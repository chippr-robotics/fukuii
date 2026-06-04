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

/** ShardEnumerator must split the state trie into DISJOINT and EXHAUSTIVE shards: every account leaf belongs to exactly
  * one shard, and the union of all shard subtrees is the whole trie. This is the correctness foundation for the parallel
  * recovery scan — a shard that double-counts or skips a leaf = a silently wrong gap set = a corrupt checkpoint.
  *
  * Strategy: build tries with distinct leaf VALUES, walk every shard's subtree, and assert the multiset of leaves across
  * shards equals the full-trie leaf set. A separate test seeds PathTrackingLeafWalkVisitor with each shard's prefix and
  * asserts the reconstructed account keys (prefix + subtree path) exactly match the originals.
  */
class ShardEnumeratorSpec extends AnyFunSuite with ScalaCheckPropertyChecks {

  private def freshStorage(): MptStorage = {
    val (stateStorage, _, _) = StateStorage.createTestStateStorage(EphemDataSource())
    stateStorage.getBackingStorage(0)
  }
  private def freshTrie(s: MptStorage): MerklePatriciaTrie[Array[Byte], Array[Byte]] =
    MerklePatriciaTrie[Array[Byte], Array[Byte]](s)

  private def build(entries: Seq[(Array[Byte], Array[Byte])]): (ByteString, MptStorage) = {
    val storage = freshStorage()
    val trie = entries.foldLeft(freshTrie(storage)) { case (t, (k, v)) => t.put(k, v) }
    (ByteString(trie.getRootHash), storage)
  }

  /** All leaf values reachable from a (possibly inline) shard root, in any order. */
  private def shardLeafValues(root: MptNode, storage: MptStorage): Seq[String] = {
    val acc = mutable.ArrayBuffer.empty[String]
    MptTraversals.dispatch(root, new LeafWalkVisitor(storage, leaf => acc += leaf.value.toArray.mkString(",")))
    acc.toSeq
  }

  /** Full account keys (prefix + subtree path) reconstructed across all shards. */
  private def shardKeys(shards: Vector[ShardEnumerator.Shard], storage: MptStorage): Seq[Seq[Byte]] = {
    val acc = mutable.ArrayBuffer.empty[Seq[Byte]]
    shards.foreach { s =>
      MptTraversals.dispatch(
        s.root,
        new PathTrackingLeafWalkVisitor(storage, s.pathPrefix, (key, _) => acc += key.toArray.toSeq)
      )
    }
    acc.toSeq
  }

  private def assertPartition(entries: Seq[(Array[Byte], Array[Byte])], depth: Int = 1): Unit = {
    val (root, storage) = build(entries)
    val shards = ShardEnumerator.enumShards(root, storage, depth)
    val acrossShards = shards.flatMap(s => shardLeafValues(s.root, storage))
    val expected = entries.map(_._2.toArray.mkString(","))
    // multiset equality ⇒ exhaustive (all present) AND disjoint (none extra, none double-counted)
    assert(
      acrossShards.sorted == expected.sorted,
      s"partition mismatch (depth=$depth): ${acrossShards.size} shard leaves vs ${expected.size} expected"
    )
  }

  test("16-way branch root → up to 16 disjoint, exhaustive shards", UnitTest, MPTTest) {
    assertPartition((0 until 16).map(i => (Array[Byte]((i << 4).toByte), Array[Byte](i.toByte))))
  }

  test("branch root with two distinct first nibbles", UnitTest, MPTTest) {
    assertPartition(Seq(Array[Byte](0x10) -> Array[Byte](1), Array[Byte](0xf0.toByte) -> Array[Byte](2)))
  }

  test("EDGE: extension root (shared prefix) descends to the branch, still exhaustive", UnitTest, MPTTest) {
    // All keys share the first byte → the trie root is an ExtensionNode. Mishandling this skips/splits the subtree.
    assertPartition(
      Seq(
        Array[Byte](0x11, 0x11) -> Array[Byte](1),
        Array[Byte](0x11, 0x22) -> Array[Byte](2),
        Array[Byte](0x11, 0x33) -> Array[Byte](3)
      )
    )
  }

  test("single account → single shard", UnitTest, MPTTest) {
    assertPartition(Seq(Array[Byte](0xab.toByte) -> Array[Byte](0x42)))
  }

  test("empty trie → no shards", UnitTest, MPTTest) {
    val storage = freshStorage()
    val root = ByteString(freshTrie(storage).getRootHash)
    assert(ShardEnumerator.enumShards(root, storage).isEmpty)
  }

  test("depth=2 → finer shards, still disjoint+exhaustive", UnitTest, MPTTest) {
    assertPartition((0 until 80).map(i => (Array[Byte](i.toByte, (i * 7 % 256).toByte), Array[Byte](i.toByte))), depth = 2)
  }

  test("shard prefixes reconstruct the exact original account keys", UnitTest, MPTTest) {
    // 2-byte keys ⇒ even nibble count ⇒ unambiguous reconstruction.
    val entries = (0 until 24).map(i => (Array[Byte]((i * 11 % 256).toByte, (i * 7 % 256).toByte), Array[Byte](i.toByte)))
    val (root, storage) = build(entries)
    val shards = ShardEnumerator.enumShards(root, storage)
    val reconstructed = shardKeys(shards, storage).map(_.toSeq).toSet
    val original = entries.map(_._1.toSeq).toSet
    assert(reconstructed == original, "shard-prefix-seeded walk did not reconstruct the original account keys")
  }

  test("property: shard partition leaf-count equals insert count for random key sets", UnitTest, MPTTest) {
    val gen: Gen[Map[Seq[Byte], Seq[Byte]]] = Gen
      .mapOf(for {
        k <- Gen.listOfN(3, Arbitrary.arbitrary[Byte])
        v <- Gen.nonEmptyListOf(Arbitrary.arbitrary[Byte])
      } yield (k, v))
      .suchThat(_.nonEmpty)
    forAll(gen) { pairs =>
      val entries = pairs.toSeq.map { case (k, v) => (k.toArray, v.toArray) }
      val (root, storage) = build(entries)
      val shards = ShardEnumerator.enumShards(root, storage)
      val total = shards.map(s => shardLeafValues(s.root, storage).size).sum
      assert(total == pairs.size, s"expected ${pairs.size} leaves across shards, got $total")
    }
  }
}
