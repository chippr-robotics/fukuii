package com.chipprbots.ethereum.db.storage.pathstate

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.db.storage.RepairOutcome
import com.chipprbots.ethereum.testing.Tags.*

/** The repair of a row by position (spec 018 C5b, T216; data-model 7.0, 7.5): one point write under the state lock,
  * made only when the bytes are the node expected, the anchor's root still names it at the position, and the row is
  * absent or no node. Declined when the anchor moved, or there is none.
  */
class RepairNodeAtSpec extends AnyFlatSpec with Matchers:
  import PathStateTestKit.*

  "repairNodeAt" should "write a node into an absent row, under the anchor, as a move that leaves the anchor where it is" taggedAs UnitTest in {
    val f = new PositionRepairFixture
    val (position, node) = f.innerRow(None)
    f.remove(position)
    val epoch = f.state.clock.epoch

    f.state.repairNodeAt(position, f.hashOf(node), node) shouldBe RepairOutcome.Written

    f.rowAt(position).get shouldBe node
    f.state.anchor shouldBe Some(f.anchor)
    withClue("a view made before sees the batch as a move of the clock, and the lock is free again: ") {
      f.state.clock.epoch shouldBe epoch + 2
      f.state.lock.holder shouldBe None
    }
  }

  it should "write into a storage trie, the row of its own owner and no other" taggedAs UnitTest in {
    val f = new PositionRepairFixture
    val (position, node) = f.innerRow(Some(owner(bob)))
    val twin = PositionKey(Some(owner(alice)), position.hp)
    f.remove(position)
    f.remove(twin)

    f.state.repairNodeAt(position, f.hashOf(node), node) shouldBe RepairOutcome.Written

    f.rowAt(position).get shouldBe node
    withClue("an identical node at another position is that position's hole, found by its own look: ") {
      f.rowAt(twin) shouldBe None
    }
  }

  it should "replace a row that is no node, and refuse one that decodes and is another" taggedAs UnitTest in {
    val f = new PositionRepairFixture
    val (position, node) = f.innerRow(None)
    f.put(position, f.garbage)
    f.state.repairNodeAt(position, f.hashOf(node), node) shouldBe RepairOutcome.Written
    f.rowAt(position).get shouldBe node

    val another = f.rowsOf().filter(_._1.owner.isEmpty).map(_._2).find(!java.util.Arrays.equals(_, node)).get
    f.put(position, another)
    f.state.repairNodeAt(position, f.hashOf(node), node) shouldBe RepairOutcome.DifferentNode
    f.rowAt(position).get shouldBe another
  }

  it should "leave a row that is already the node, and refuse bytes that are not the node expected" taggedAs UnitTest in {
    val f = new PositionRepairFixture
    val (position, node) = f.innerRow(None)
    f.state.repairNodeAt(position, f.hashOf(node), node) shouldBe RepairOutcome.AlreadyValid

    f.remove(position)
    f.state.repairNodeAt(position, f.hashOf(node), f.garbage) shouldBe RepairOutcome.Rejected
    f.rowAt(position) shouldBe None
  }

  it should "decline when the anchor's root no longer names that node there" taggedAs UnitTest in {
    val f = new PositionRepairFixture
    val before = f.rowsOf().toMap
    val next = f.commitNext()
    val after = f.rowsOf(next.stateRoot).toMap
    val (position, oldNode) = before.filter { case (p, node) =>
      p.owner.isEmpty && after.get(p).exists(!java.util.Arrays.equals(_, node)) && p.hp != PositionKey.AccountRoot.hp
    }.head
    f.remove(position)

    f.state.repairNodeAt(position, f.hashOf(oldNode), oldNode) shouldBe RepairOutcome.NotReached
    f.rowAt(position) shouldBe None

    f.state.repairNodeAt(position, f.hashOf(after(position)), after(position)) shouldBe RepairOutcome.Written
  }

  it should "write nothing with no anchor: the rows are byte-identical before and after" taggedAs UnitTest in {
    val f = new PositionRepairFixture(withAnchor = false)
    val (position, node) = f.innerRow(None)
    f.remove(position)
    val before = f.rowsOf().map(_._1).map(p => p -> f.rowAt(p).map(_.toSeq))

    f.state.repairNodeAt(position, f.hashOf(node), node) shouldBe RepairOutcome.NotReached

    f.rowsOf().map(_._1).map(p => p -> f.rowAt(p).map(_.toSeq)) shouldBe before
  }

  it should "write nothing when the row's parent is itself a hole: the position is not reached through it" taggedAs UnitTest in {
    val f = new PositionRepairFixture
    val (position, node) = f.innerRow(None)
    val parent = PositionKey.AccountRoot
    f.remove(position)
    f.remove(parent)

    f.state.repairNodeAt(position, f.hashOf(node), node) shouldBe RepairOutcome.NotReached
    f.rowAt(position) shouldBe None
  }
