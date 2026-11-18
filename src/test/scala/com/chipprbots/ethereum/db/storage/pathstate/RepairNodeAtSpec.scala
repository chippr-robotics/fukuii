package com.chipprbots.ethereum.db.storage.pathstate

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.db.storage.RepairOutcome
import com.chipprbots.ethereum.testing.Tags.*

/** The repair of a row by position (spec 018 C5b, T216; data-model 7.0, 7.5): one point write under the state lock,
  * made only when the bytes are the node expected and the anchor's root still names it at the position; a row that
  * decodes and is another node is overwritten (B-370). Declined when the anchor moved, or there is none.
  */
class RepairNodeAtSpec extends AnyFlatSpec with Matchers:
  import PathStateTestKit.*

  final private class Overwrites extends PathStateStorage.CommitObserver:
    val count = new java.util.concurrent.atomic.AtomicInteger(0)
    override def repairOverwrote(): Unit = count.incrementAndGet()

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

  it should "replace a row that is no node, without counting it as an overwrite" taggedAs UnitTest in {
    val f = new PositionRepairFixture
    val told = new Overwrites
    val state = new PathStateStorage(f.rows, f.appState, settings = PathStateStorage.CommitSettings(observer = told))
    val (position, node) = f.innerRow(None)
    f.put(position, f.garbage)
    state.repairNodeAt(position, f.hashOf(node), node) shouldBe RepairOutcome.Written
    f.rowAt(position).get shouldBe node
    told.count.get shouldBe 0
  }

  it should "overwrite a row that decodes and is another node, under the anchor, and count it (B-370)" taggedAs UnitTest in {
    val f = new PositionRepairFixture
    val told = new Overwrites
    val state = new PathStateStorage(f.rows, f.appState, settings = PathStateStorage.CommitSettings(observer = told))
    val (position, node) = f.innerRow(None)
    val another = f.rowsOf().filter(_._1.owner.isEmpty).map(_._2).find(!java.util.Arrays.equals(_, node)).get
    f.put(position, another)
    val epoch = state.clock.epoch

    state.repairNodeAt(position, f.hashOf(node), node) shouldBe RepairOutcome.Overwrote

    f.rowAt(position).get shouldBe node
    told.count.get shouldBe 1
    withClue("a move of the clock, anchor unchanged, lock free: ") {
      state.clock.epoch shouldBe epoch + 2
      state.anchor shouldBe Some(f.anchor)
      state.lock.holder shouldBe None
    }
    state.repairNodeAt(position, f.hashOf(node), node) shouldBe RepairOutcome.AlreadyValid
    told.count.get shouldBe 1
  }

  it should "leave a row that decodes and is another node when the anchor's root does not name the expected one there" taggedAs UnitTest in {
    val f = new PositionRepairFixture
    val before = f.rowsOf().toMap
    val next = f.commitNext()
    val after = f.rowsOf(next.stateRoot).toMap
    val (position, oldNode) = before.filter { case (p, node) =>
      p.owner.isEmpty && after.get(p).exists(!java.util.Arrays.equals(_, node)) && p.hp != PositionKey.AccountRoot.hp
    }.head
    val another = after(position)
    f.put(position, another)

    f.state.repairNodeAt(position, f.hashOf(oldNode), oldNode) shouldBe RepairOutcome.NotReached
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
