package com.chipprbots.ethereum.mpt

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.mpt.PositionTestKit.*
import com.chipprbots.ethereum.testing.Tags.*

/** Spec 018 Track C, C1 (T021), oracles 3 and 4 of `baselines/oracle-tests.md`: a block's change set, applied
  * backwards, gives back exactly the rows of the block before it, for one block and for several; and the prior of a
  * position is fixed by the first change that touches it in the block.
  */
class PositionReverseSpec extends AnyFlatSpec with Matchers:

  private val seeds = 1L to 12L

  /** Runs one block of `changes` changes and returns its change set. */
  private def block(twin: TwinTrie, source: OpSource, changes: Int): Diff =
    val callsBefore = twin.positions.calls.size
    (1 to changes).foreach(_ => applyOp(twin, source.next()))
    blockDiff(twin.positions.calls.drop(callsBefore))

  "A block's change set" should "give back the parent's rows when applied backwards" taggedAs (UnitTest, MPTTest) in {
    Seq(Keys.Dense, Keys.Hashed, Keys.Varying).foreach { keys =>
      seeds.foreach { seed =>
        val twin = new TwinTrie
        val source = new OpSource(seed, keys)
        (1 to 30).foreach { number =>
          val parent = twin.positions.rows
          val diff = block(twin, source, changes = 1 + (number % 9))
          withClue(s"$keys, seed $seed, block $number: ") {
            applyReverse(twin.positions.rows, diff) shouldBe parent
            applyForward(parent, diff) shouldBe twin.positions.rows
          }
        }
      }
    }
  }

  it should "give back the first block's parent when several blocks are applied backwards" taggedAs (
    UnitTest,
    MPTTest
  ) in {
    Seq(Keys.Dense, Keys.Hashed, Keys.Varying).foreach { keys =>
      seeds.foreach { seed =>
        val twin = new TwinTrie
        val source = new OpSource(seed, keys)
        block(twin, source, changes = 40) // some state to start from
        (1 to 6).foreach { round =>
          val start = twin.positions.rows
          val diffs = (1 to 8).map(number => block(twin, source, changes = 1 + ((round + number) % 7)))
          withClue(s"$keys, seed $seed, round $round: ") {
            diffs.reverse.foldLeft(twin.positions.rows)(applyReverse) shouldBe start
            diffs.foldLeft(start)(applyForward) shouldBe twin.positions.rows
          }
        }
      }
    }
  }

  // S0 item 1d. A position a block empties and a later block writes again: reversing one block cannot tell a change set
  // that lists the emptied position from one that misses it, because nothing touches the row. Reversing both can.
  it should "list a position a block empties, which only a deeper reverse can tell" taggedAs (UnitTest, MPTTest) in {
    val value = Array.fill[Byte](40)(7)
    val twin = new TwinTrie
    // nibbles 0,0 / 0,1 / 1,0: a branch at [0] over two leaves, and a leaf at [1]
    twin.put(Array[Byte](0x00), value)
    twin.put(Array[Byte](0x01), value)
    twin.put(Array[Byte](0x10), value)
    val start = twin.positions.rows
    start.keySet shouldBe Set(rowKey(), rowKey(0), rowKey(0, 0), rowKey(0, 1), rowKey(1))

    // block 1 removes the leaf at [1]: the root branch gives way to an extension, and [1] holds nothing
    val calls1 = twin.positions.calls.size
    twin.remove(Array[Byte](0x10))
    val diff1 = blockDiff(twin.positions.calls.drop(calls1))
    val afterBlock1 = twin.positions.rows
    diff1(rowKey(1)) shouldBe (start.get(rowKey(1)), None)

    // block 2 puts another value at the same key: [1] holds a node again, with nothing before it in this block
    val calls2 = twin.positions.calls.size
    twin.put(Array[Byte](0x10), Array.fill[Byte](40)(9))
    val diff2 = blockDiff(twin.positions.calls.drop(calls2))
    diff2(rowKey(1))._1 shouldBe None
    diff2(rowKey(1))._2 should not be empty

    applyReverse(applyReverse(twin.positions.rows, diff2), diff1) shouldBe start

    // The control: block 1's change set without the emptied position.
    val missed = diff1 - rowKey(1)
    // On a store that follows the same broken change set, block 1 leaves the old row behind and reversing block 1 alone
    // still looks exact:
    val leftBehind = applyForward(start, missed)
    leftBehind(rowKey(1)) shouldBe start(rowKey(1))
    applyReverse(leftBehind, missed) shouldBe start
    // but two blocks deep the row block 2 wrote there is deleted, where block 1's parent had a node:
    val twoDeep = applyReverse(applyReverse(applyForward(leftBehind, diff2), diff2), missed)
    twoDeep.get(rowKey(1)) shouldBe None
    twoDeep should not be start
    afterBlock1.get(rowKey(1)) shouldBe None
  }

  // Oracle 4, data-model 3 rule 1. Execution persists the trie after every transaction, so within one block a node made
  // by one transaction is reported as an old node by the next. The block's prior for that position is still "absent".
  it should "record no prior for a position the block itself created and then replaced" taggedAs (
    UnitTest,
    MPTTest
  ) in {
    val twin = new TwinTrie
    twin.put(Array[Byte](0x00), Array.fill[Byte](40)(1))
    val parent = twin.positions.rows
    parent.keySet shouldBe Set(rowKey())

    val callsBefore = twin.positions.calls.size
    // transaction 1: a second key. The root leaf gives way to a branch, with a new leaf at [1]
    twin.put(Array[Byte](0x10), Array.fill[Byte](40)(2))
    val created = twin.positions.rows(rowKey(1))
    // transaction 2: another value for that key. The leaf transaction 1 made at [1] is reported as replaced
    twin.put(Array[Byte](0x10), Array.fill[Byte](40)(3))
    val calls = twin.positions.calls.drop(callsBefore)
    calls should have size 2
    calls(1).oldStored.get(rowKey(1)) shouldBe Some(created)

    val diff = blockDiff(calls)
    diff(rowKey(1))._1 shouldBe None
    diff(rowKey(1))._2 shouldBe Some(twin.positions.rows(rowKey(1)))
    // the root was there before the block: its prior is the parent's root, not transaction 1's
    diff(rowKey())._1 shouldBe parent.get(rowKey())
    applyReverse(twin.positions.rows, diff) shouldBe parent

    // a block that creates a position and removes it again leaves no trace of it
    val callsBefore2 = twin.positions.calls.size
    val parent2 = twin.positions.rows
    twin.put(Array[Byte](0x20), Array.fill[Byte](40)(4))
    twin.remove(Array[Byte](0x20))
    val diff2 = blockDiff(twin.positions.calls.drop(callsBefore2))
    diff2 shouldBe empty
    twin.positions.rows shouldBe parent2
  }

  it should "keep the parent's node as prior through any number of changes in the block" taggedAs (
    UnitTest,
    MPTTest
  ) in {
    seeds.foreach { seed =>
      val twin = new TwinTrie
      val source = new OpSource(seed, Keys.Dense)
      (1 to 60).foreach(_ => applyOp(twin, source.next()))
      val parent = twin.positions.rows
      val callsBefore = twin.positions.calls.size
      // one long block: many transactions over the same few keys
      (1 to 120).foreach(_ => applyOp(twin, source.next()))
      val diff = blockDiff(twin.positions.calls.drop(callsBefore))
      withClue(s"seed $seed: ") {
        diff.foreach { case (key, (prior, _)) => prior shouldBe parent.get(key) }
        applyReverse(twin.positions.rows, diff) shouldBe parent
      }
    }
  }
