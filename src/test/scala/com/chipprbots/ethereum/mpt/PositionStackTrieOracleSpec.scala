package com.chipprbots.ethereum.mpt

import org.apache.pekko.util.ByteString

import scala.collection.mutable

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.mpt.PositionTestKit.*
import com.chipprbots.ethereum.testing.Tags.*

/** Spec 018 Track C, C1 (T020), oracle 2 of `baselines/oracle-tests.md`: after any sequence of changes, the position
  * store holds what [[StackTrie]] reports when it builds the same keys from nothing.
  *
  * `StackTrie` is the independent statement of what a position holds: it is how SNAP writes the position-keyed families
  * today, it never reads a store, and it shares no code with `MerklePatriciaTrie`'s put, remove or collapse.
  */
class PositionStackTrieOracleSpec extends AnyFlatSpec with Matchers:

  private val seeds = 1L to 12L

  /** What `StackTrie` reports for these keys: each stored node at its position. */
  private def stackTrieRows(contents: Seq[(Array[Byte], Array[Byte])]): (ByteString, Rows) =
    val rows = mutable.LinkedHashMap.empty[RowKey, ByteString]
    val stackTrie = new StackTrie((path, _, encoded) =>
      // StackTrie reuses the path array: the key is built from it before the callback returns
      rows.put(ByteString(HexPrefix.encode(path, isLeaf = false)), ByteString(encoded.clone()))
    )
    contents
      .sortWith { case ((a, _), (b, _)) => StackTrie.byteCompare(a, b) < 0 }
      .foreach { case (key, value) => stackTrie.update(key, value) }
    val root = stackTrie.hash()
    (root, rows.toMap)

  private def rebuildEquals(keys: Keys, changes: Int): Unit =
    seeds.foreach { seed =>
      val twin = new TwinTrie
      val source = new OpSource(seed, keys)
      (1 to changes).foreach { step =>
        applyOp(twin, source.next())
        // not after every change: the rebuild is the slow part, and TwinTrie already checks each one against a walk
        if step % 10 == 0 && source.contents.nonEmpty then
          withClue(s"$keys, seed $seed, after $step changes: ") {
            val (root, rows) = stackTrieRows(source.contents)
            root shouldBe ByteString(twin.root)
            twin.positions.rows shouldBe rows
          }
      }
    }

  "The position store after any changes" should "equal a StackTrie rebuild, for keys with long shared prefixes" taggedAs (
    UnitTest,
    MPTTest
  ) in {
    rebuildEquals(Keys.Dense, changes = 300)
  }

  it should "equal a StackTrie rebuild, for the keys of a secure trie" taggedAs (UnitTest, MPTTest) in {
    rebuildEquals(Keys.Hashed, changes = 300)
  }

  // Nodes shorter than 32 bytes have no row; the root has one at any size (data-model 2).
  it should "agree with StackTrie on embedded nodes and on a small root" taggedAs (UnitTest, MPTTest) in {
    // nibbles 0,0 / 0,7 / 7,0 with one-byte values: two tiny leaves under a branch at [0], a tiny leaf at [7]
    val small = Seq(
      Array[Byte](0x00) -> Array[Byte](1),
      Array[Byte](0x07) -> Array[Byte](2),
      Array[Byte](0x70) -> Array[Byte](3)
    )
    val twin = new TwinTrie
    small.foreach { case (key, value) => twin.put(key, value) }
    val (root, rows) = stackTrieRows(small)
    root shouldBe ByteString(twin.root)
    twin.positions.rows shouldBe rows
    // every node but the root is embedded in its parent
    rows.keySet shouldBe Set(rowKey())

    val single = new TwinTrie
    single.put(Array[Byte](0x12), Array[Byte](0x34))
    single.positions.rows.keySet shouldBe Set(rowKey())
    single.positions.rows(rowKey()).length should be < 32
  }
