package com.chipprbots.ethereum.mpt

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.mpt.PositionTestKit.*
import com.chipprbots.ethereum.testing.Tags.*

/** Spec 018 Track C, C1 (T022): which nodes a change reports (data-model 3 rules 4 and 5, data-model 8 item 2).
  *
  * A node is reported when it is replaced (it lies on the path of the change) or absorbed (a removal merged it into its
  * parent). A branch that a removal only reads, to learn that it is a branch and may stay, is not.
  *
  * Keys are written as their nibbles. A 40-byte value makes every leaf a stored node.
  */
class PositionFixSpec extends AnyFlatSpec with Matchers:

  private def big(fill: Int): Array[Byte] = Array.fill[Byte](40)(fill.toByte)

  /** The key whose nibbles these are. */
  private def key(nibbles: Int*): Array[Byte] = HexPrefix.nibblesToBytes(nibbles.map(_.toByte).toArray)

  private def trieOf(keys: Seq[Int]*): TwinTrie =
    val twin = new TwinTrie
    keys.zipWithIndex.foreach { case (nibbles, index) => twin.put(key(nibbles*), big(index + 1)) }
    twin

  "A removal" should "not report the branch it reads and keeps under a new extension" taggedAs (UnitTest, MPTTest) in {
    // a branch at [] over a branch at [0] (two leaves) and a leaf at [1]
    val twin = trieOf(Seq(0, 0), Seq(0, 1), Seq(1, 0))
    val before = twin.positions.rows
    before.keySet shouldBe Set(rowKey(), rowKey(0), rowKey(0, 0), rowKey(0, 1), rowKey(1))

    twin.positions.clearReads()
    twin.remove(key(1, 0))

    val call = twin.positions.lastCall
    // the root branch and the removed leaf lay on the path
    call.replacedAt shouldBe Set(rowKey(), rowKey(1))
    // the branch at [0] was read, to see that an extension may point at it, and stays as it is
    twin.positions.reads should contain(rowKey(0))
    call.absorbed shouldBe empty
    call.oldStored.keySet should not contain rowKey(0)

    val after = twin.positions.rows
    after.keySet shouldBe Set(rowKey(), rowKey(0), rowKey(0, 0), rowKey(0, 1))
    after(rowKey(0)) shouldBe before(rowKey(0))
    // the root is now an extension over that branch
    MptTraversals.decodeNode(after(rowKey()).toArray) shouldBe a[ExtensionNode]
  }

  it should "report the leaf a collapsing branch absorbs" taggedAs (UnitTest, MPTTest) in {
    // a branch at [] over a leaf at [0] and a leaf at [1]
    val twin = trieOf(Seq(0, 0), Seq(1, 0))
    twin.positions.rows.keySet shouldBe Set(rowKey(), rowKey(0), rowKey(1))

    twin.remove(key(1, 0))

    val call = twin.positions.lastCall
    call.replacedAt shouldBe Set(rowKey(), rowKey(1))
    // the leaf at [0] was never on the path to [1, 0]; it is merged into a leaf at the root and is gone from [0]
    call.absorbedAt shouldBe Set(rowKey(0))
    call.absorbed.head._2 shouldBe a[LeafNode]
    twin.positions.rows.keySet shouldBe Set(rowKey())
    MptTraversals.decodeNode(twin.positions.rows(rowKey()).toArray) shouldBe a[LeafNode]
  }

  it should "report the extension a collapsing branch absorbs, and not the branch below it" taggedAs (
    UnitTest,
    MPTTest
  ) in {
    // a branch at [] over an extension at [0] (nibbles 5,5) to a branch at [0,5,5] with two leaves, and a leaf at [1]
    val twin = trieOf(Seq(0, 5, 5, 0), Seq(0, 5, 5, 1), Seq(1, 0, 0, 0))
    val before = twin.positions.rows
    before.keySet shouldBe Set(rowKey(), rowKey(0), rowKey(0, 5, 5), rowKey(0, 5, 5, 0), rowKey(0, 5, 5, 1), rowKey(1))

    twin.remove(key(1, 0, 0, 0))

    val call = twin.positions.lastCall
    call.replacedAt shouldBe Set(rowKey(), rowKey(1))
    call.absorbedAt shouldBe Set(rowKey(0))
    call.absorbed.head._2 shouldBe an[ExtensionNode]

    val after = twin.positions.rows
    after.keySet shouldBe Set(rowKey(), rowKey(0, 5, 5), rowKey(0, 5, 5, 0), rowKey(0, 5, 5, 1))
    after(rowKey(0, 5, 5)) shouldBe before(rowKey(0, 5, 5))
  }

  it should "report the leaf absorbed below an extension, once, and nothing for the node made on the way" taggedAs (
    UnitTest,
    MPTTest
  ) in {
    // an extension at [] (nibbles 5,5) to a branch at [5,5] over a leaf at [5,5,0] and a leaf at [5,5,1]
    val twin = trieOf(Seq(5, 5, 0, 0), Seq(5, 5, 1, 0))
    twin.positions.rows.keySet shouldBe Set(rowKey(), rowKey(5, 5), rowKey(5, 5, 0), rowKey(5, 5, 1))

    twin.remove(key(5, 5, 0, 0))

    val call = twin.positions.lastCall
    // on the path: the extension, the branch, the removed leaf
    call.replacedAt shouldBe Set(rowKey(), rowKey(5, 5), rowKey(5, 5, 0))
    // the branch collapses onto the leaf at [5,5,1]; the leaf that makes is then merged into the extension, and that
    // second merge takes in a node this removal built, which was never stored anywhere
    call.absorbedAt shouldBe Set(rowKey(5, 5, 1))
    call.absorbed should have size 1
    twin.positions.rows.keySet shouldBe Set(rowKey())
  }

  it should "report the leaf absorbed when a branch loses its own value" taggedAs (UnitTest, MPTTest) in {
    // keys [1,2] and [1,2,3,4]: an extension at [] to a branch at [1,2] that holds a value and a leaf at [1,2,3]
    val twin = new TwinTrie
    twin.put(Array[Byte](0x12), big(1))
    twin.put(Array[Byte](0x12, 0x34), big(2))
    twin.positions.rows.keySet shouldBe Set(rowKey(), rowKey(1, 2), rowKey(1, 2, 3))

    twin.remove(Array[Byte](0x12))

    val call = twin.positions.lastCall
    call.replacedAt shouldBe Set(rowKey(), rowKey(1, 2))
    call.absorbedAt shouldBe Set(rowKey(1, 2, 3))
    twin.positions.rows.keySet shouldBe Set(rowKey())
  }

  it should "report nothing absorbed when a branch is left with its value alone" taggedAs (UnitTest, MPTTest) in {
    val twin = new TwinTrie
    twin.put(Array[Byte](0x12), big(1))
    twin.put(Array[Byte](0x12, 0x34), big(2))

    twin.remove(Array[Byte](0x12, 0x34))

    val call = twin.positions.lastCall
    // the branch becomes a leaf with an empty key, which the extension above takes in: both are made by this removal
    call.replacedAt shouldBe Set(rowKey(), rowKey(1, 2), rowKey(1, 2, 3))
    call.absorbed shouldBe empty
    twin.positions.rows.keySet shouldBe Set(rowKey())
  }

  // An embedded sibling has no row of its own. Taking it into its parent removes no stored node, so it is not reported
  // (beacon, C1 pre-code review).
  it should "not report an embedded sibling a collapsing branch absorbs" taggedAs (UnitTest, MPTTest) in {
    val twin = new TwinTrie
    twin.put(key(0, 0), Array[Byte](1)) // a three-byte leaf, embedded in the branch
    twin.put(key(1, 0), big(2))
    twin.positions.rows.keySet shouldBe Set(rowKey(), rowKey(1))

    twin.remove(key(1, 0))

    val call = twin.positions.lastCall
    call.replacedAt shouldBe Set(rowKey(), rowKey(1))
    call.absorbed shouldBe empty
    twin.positions.rows.keySet shouldBe Set(rowKey())
  }

  // A hash-keyed store counts references by the removals it is told of, and has never been told of an absorbed node.
  // It must not be told now (forge and beacon, C1 pre-code review): the count of a live node would drop.
  it should "not tell a hash-keyed store of the node it absorbs" taggedAs (UnitTest, MPTTest) in {
    val twin = trieOf(Seq(0, 0), Seq(1, 0))
    val absorbedLeaf = twin.positions.rows(rowKey(0))
    val rootBranch = twin.positions.rows(rowKey())
    val removedLeaf = twin.positions.rows(rowKey(1))

    twin.remove(key(1, 0))

    twin.positions.lastCall.absorbed.map(_._2.encode.toSeq) shouldBe Seq(absorbedLeaf.toSeq)
    twin.reference.removed.last shouldBe List(rootBranch, removedLeaf)
    twin.reference.removed.last should not contain absorbedLeaf
  }

  "A change" should "drop the row of a node that shrinks below 32 bytes and make one when it grows back" taggedAs (
    UnitTest,
    MPTTest
  ) in {
    val twin = trieOf(Seq(0, 0), Seq(1, 0))
    val parent = twin.positions.rows
    parent.keySet shouldBe Set(rowKey(), rowKey(0), rowKey(1))

    // the leaf at [0] becomes three bytes: embedded in the root branch, no row (data-model 3 rule 4)
    val callsBefore = twin.positions.calls.size
    twin.put(key(0, 0), Array[Byte](1))
    val shrink = blockDiff(twin.positions.calls.drop(callsBefore))
    twin.positions.rows.keySet shouldBe Set(rowKey(), rowKey(1))
    shrink(rowKey(0)) shouldBe (parent.get(rowKey(0)), None)
    shrink.keySet shouldBe Set(rowKey(), rowKey(0))

    // and back to 40 bytes: a row again, with no prior, because the parent of this block has none there
    val shrunk = twin.positions.rows
    val callsBefore2 = twin.positions.calls.size
    twin.put(key(0, 0), big(1))
    val grow = blockDiff(twin.positions.calls.drop(callsBefore2))
    grow(rowKey(0)) shouldBe (None, parent.get(rowKey(0)))
    twin.positions.rows shouldBe parent
    applyReverse(twin.positions.rows, grow) shouldBe shrunk
  }

  it should "report the nodes on its path at their positions, and read each node where it sits" taggedAs (
    UnitTest,
    MPTTest
  ) in {
    val twin = trieOf(Seq(0, 5, 5, 0), Seq(0, 5, 5, 1), Seq(1, 0, 0, 0))

    twin.positions.clearReads()
    twin.put(key(0, 5, 5, 1), big(9))

    // root branch, extension at [0], branch at [0,5,5], leaf at [0,5,5,1]
    twin.positions.reads shouldBe Seq(rowKey(), rowKey(0), rowKey(0, 5, 5), rowKey(0, 5, 5, 1))
    val call = twin.positions.lastCall
    call.replaced.map(_._1.key) shouldBe Seq(rowKey(), rowKey(0), rowKey(0, 5, 5), rowKey(0, 5, 5, 1))
    call.newStored.keySet shouldBe Set(rowKey(), rowKey(0), rowKey(0, 5, 5), rowKey(0, 5, 5, 1))

    twin.positions.clearReads()
    twin.trie.get(key(0, 5, 5, 0)).map(_.toSeq) shouldBe Some(big(1).toSeq)
    twin.positions.reads shouldBe Seq(rowKey(), rowKey(0), rowKey(0, 5, 5), rowKey(0, 5, 5, 0))

    twin.positions.clearReads()
    twin.trie.getProof(key(1, 0, 0, 0)).map(_.size) shouldBe Some(2)
    twin.positions.reads shouldBe Seq(rowKey(), rowKey(1))
  }

  it should "report a split leaf and a split extension at the position they sat at" taggedAs (UnitTest, MPTTest) in {
    // one leaf at the root, then a key that shares two nibbles with it: an extension at [], a branch at [0,5]
    val twin = trieOf(Seq(0, 5, 5, 0))
    twin.put(key(0, 5, 6, 0), big(2))
    twin.positions.lastCall.replacedAt shouldBe Set(rowKey())
    twin.positions.rows.keySet shouldBe Set(rowKey(), rowKey(0, 5), rowKey(0, 5, 5), rowKey(0, 5, 6))

    // a key that leaves the extension after one nibble: a shorter extension at [], a branch at [0], and the rest of the
    // old extension becomes the branch's child slot (one nibble left, so the old branch hangs there directly)
    twin.put(key(0, 7, 0, 0), big(3))
    twin.positions.lastCall.replacedAt shouldBe Set(rowKey())
    twin.positions.rows.keySet shouldBe Set(
      rowKey(),
      rowKey(0),
      rowKey(0, 5),
      rowKey(0, 5, 5),
      rowKey(0, 5, 6),
      rowKey(0, 7)
    )

    // a key that shares nothing with the extension: a branch takes its place at the root
    twin.put(key(9, 0, 0, 0), big(4))
    twin.positions.lastCall.replacedAt shouldBe Set(rowKey())
    twin.positions.rows.keySet shouldBe Set(
      rowKey(),
      rowKey(0),
      rowKey(0, 5),
      rowKey(0, 5, 5),
      rowKey(0, 5, 6),
      rowKey(0, 7),
      rowKey(9)
    )
  }
