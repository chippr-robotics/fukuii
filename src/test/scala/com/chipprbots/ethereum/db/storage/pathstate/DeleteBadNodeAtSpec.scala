package com.chipprbots.ethereum.db.storage.pathstate

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.db.storage.BadEntryDelete
import com.chipprbots.ethereum.db.storage.RawNodeRead
import com.chipprbots.ethereum.testing.Tags.*

/** The delete of a bad row by position (spec 018 C5b, T211; data-model 6.3, 7.5): a row is deleted only when it is
  * provably bad, the anchor's root still needs the node expected there, and a read of its own, under the state lock,
  * still finds it so. A stale read, a node that decodes and is another than expected, and a root that has moved on
  * delete nothing.
  */
class DeleteBadNodeAtSpec extends AnyFlatSpec with Matchers:
  import PathStateTestKit.*

  "deleteBadNodeAt" should "delete a row that does not decode, which the anchor's root still needs there" taggedAs UnitTest in {
    val f = new PositionRepairFixture
    val (position, node) = f.innerRow(None)
    f.put(position, f.garbage)

    f.state.deleteBadNodeAt(position, f.hashOf(node)) shouldBe BadEntryDelete.Deleted

    f.rowAt(position) shouldBe None
    f.state.anchor shouldBe Some(f.anchor)
  }

  it should "delete a row of a storage trie the same way, the owner's account found in the account trie" taggedAs UnitTest in {
    val f = new PositionRepairFixture
    val (position, node) = f.innerRow(Some(owner(bob)))
    f.put(position, f.garbage)

    f.state.deleteBadNodeAt(position, f.hashOf(node)) shouldBe BadEntryDelete.Deleted

    f.rowAt(position) shouldBe None
    withClue("the twin's identical storage trie is another trie's rows, and stays: ") {
      f.rowAt(PositionKey(Some(owner(alice)), position.hp)).map(_.toSeq) shouldBe Some(node.toSeq)
    }
  }

  it should "never delete a node that decodes and is another than the one expected there" taggedAs UnitTest in {
    val f = new PositionRepairFixture
    val (position, node) = f.innerRow(None)
    val (_, another) = f.rowsOf().filter(_._1.owner.isEmpty).find(row => !java.util.Arrays.equals(row._2, node)).get
    f.put(position, another)

    f.state.deleteBadNodeAt(position, f.hashOf(node)) shouldBe BadEntryDelete.DifferentNode

    f.rowAt(position).get shouldBe another
  }

  it should "delete nothing when the row hashes to what is expected, or is not there" taggedAs UnitTest in {
    val f = new PositionRepairFixture
    val (position, node) = f.innerRow(None)

    f.state.deleteBadNodeAt(position, f.hashOf(node)) shouldBe BadEntryDelete.NoLongerBad
    f.rowAt(position).get shouldBe node

    f.remove(position)
    f.state.deleteBadNodeAt(position, f.hashOf(node)) shouldBe BadEntryDelete.NoLongerBad
    f.rowAt(position) shouldBe None
  }

  it should "delete nothing for a verdict the root has moved past: a stale read never deletes" taggedAs UnitTest in {
    val f = new PositionRepairFixture
    val (before, next) = (f.rowsOf().toMap, f.commitNext())
    val after = f.rowsOf(next.stateRoot).toMap
    // a position whose node the block changed: the verdict was formed against the node that was there before
    val (position, oldNode) = before.filter { case (p, node) =>
      p.owner.isEmpty && after.get(p).exists(!java.util.Arrays.equals(_, node)) && p.hp != PositionKey.AccountRoot.hp
    }.head
    f.put(position, f.garbage)

    withClue("the verdict of the old root: ") {
      f.state.deleteBadNodeAt(position, f.hashOf(oldNode)) shouldBe BadEntryDelete.NotReached
    }
    f.rowAt(position).get shouldBe f.garbage

    withClue("the same row, judged against what the anchor's root names there: ") {
      f.state.deleteBadNodeAt(position, f.hashOf(after(position))) shouldBe BadEntryDelete.Deleted
    }
    f.rowAt(position) shouldBe None
  }

  it should "delete nothing when a row on the way down cannot be believed" taggedAs UnitTest in {
    val f = new PositionRepairFixture
    val (position, node) = f.innerRow(None)
    val parent = PositionKey.AccountRoot
    f.put(position, f.garbage)
    f.remove(parent)

    f.state.deleteBadNodeAt(position, f.hashOf(node)) shouldBe BadEntryDelete.NotReached
    f.rowAt(position).get shouldBe f.garbage
  }

  it should "delete nothing, and leave every row as it was, with no anchor" taggedAs UnitTest in {
    val f = new PositionRepairFixture(withAnchor = false)
    f.state.anchor shouldBe None
    val (position, node) = f.innerRow(None)
    f.put(position, f.garbage)
    val before = f.rowsOf().map(_._1).map(p => p -> f.rowAt(p).map(_.toSeq))

    f.state.deleteBadNodeAt(position, f.hashOf(node)) shouldBe BadEntryDelete.NotReached

    f.rowsOf().map(_._1).map(p => p -> f.rowAt(p).map(_.toSeq)) shouldBe before
  }

  it should "read rows for the audit by position, with nothing but what the row holds" taggedAs UnitTest in {
    val f = new PositionRepairFixture
    val (position, node) = f.innerRow(None)
    val gone = PositionKey(Some(owner(bob)), position.hp)
    f.state
      .rawRowsAt(Seq(position -> f.hashOf(node), gone -> f.hashOf(node)))
      .map {
        case RawNodeRead.Bytes(bytes) => Some(bytes.toSeq)
        case _                        => None
      } shouldBe Seq(Some(node.toSeq), f.rowAt(gone).map(_.toSeq))
  }
