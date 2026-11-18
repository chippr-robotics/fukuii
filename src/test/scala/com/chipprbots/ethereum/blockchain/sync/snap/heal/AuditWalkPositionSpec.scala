package com.chipprbots.ethereum.blockchain.sync.snap.heal

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.db.storage.NodeFault
import com.chipprbots.ethereum.db.storage.pathstate.PathStateTestKit.*
import com.chipprbots.ethereum.db.storage.pathstate.PositionKey
import com.chipprbots.ethereum.db.storage.pathstate.PositionRepairFixture
import com.chipprbots.ethereum.testing.Tags.*

/** The audit's walk over the rows of the position store (spec 018 C5b, T210; data-model 6.5, 9): it reads each row by
  * position, directly, and classifies what it finds: absent, bad keccak, or moved. A moved position is counted and
  * never a hole, so never a delete.
  */
class AuditWalkPositionSpec extends AnyFlatSpec with Matchers:

  private def positionOf(key: PositionKey): AuditPosition =
    import com.chipprbots.ethereum.mpt.HexPrefix
    AuditPosition(key.owner, org.apache.pekko.util.ByteString(HexPrefix.decode(key.hp.toArray)._1))

  "The audit's walk of a position store" should "read every row of a clean state and find nothing" taggedAs UnitTest in {
    val f = new PositionRepairFixture
    val totals = new PositionAuditHarness(f).walkAll()

    totals.holes shouldBe empty
    totals.unfollowable shouldBe empty
    totals.moved shouldBe 0
    withClue("one read per row, so the twin storage tries are each read at their own positions: ") {
      totals.nodes shouldBe f.rowsOf().size
    }
  }

  it should "classify an absent row, naming its position, and no other row of the same node" taggedAs UnitTest in {
    val f = new PositionRepairFixture
    val (inAccounts, accountNode) = f.innerRow(None)
    val (inStorage, storageNode) = f.innerRow(Some(owner(bob)))
    f.remove(inAccounts)
    f.remove(inStorage)

    val holes = new PositionAuditHarness(f).walkAll().holes

    holes.map(h => (h.position, h.hash, h.fault)).toSet shouldBe Set(
      (positionOf(inAccounts), f.hashOf(accountNode), NodeFault.Absent),
      (positionOf(inStorage), f.hashOf(storageNode), NodeFault.Absent)
    )
    withClue("alice holds the same storage node at her own position, and it is read there: ") {
      holes.map(_.position).contains(positionOf(PositionKey(Some(owner(alice)), inStorage.hp))) shouldBe false
    }
  }

  it should "classify a row that fails its hash, whether it decodes or not" taggedAs UnitTest in {
    val f = new PositionRepairFixture
    val (garbled, garbledNode) = f.innerRow(None)
    val (swapped, swappedNode) = f.innerRow(Some(owner(carol)))
    val another =
      f.rowsOf().filter(_._1.owner == Some(owner(carol))).map(_._2).find(!java.util.Arrays.equals(_, swappedNode)).get
    f.put(garbled, f.garbage)
    f.put(swapped, another)

    val holes = new PositionAuditHarness(f).walkAll().holes

    holes.map(h => (h.position, h.hash, h.fault)).toSet shouldBe Set(
      (positionOf(garbled), f.hashOf(garbledNode), NodeFault.BadKeccak),
      (positionOf(swapped), f.hashOf(swappedNode), NodeFault.BadKeccak)
    )
  }

  it should "count a position the anchor moved under the walk as moved, and not as a hole" taggedAs UnitTest in {
    val f = new PositionRepairFixture
    val harness = new PositionAuditHarness(f)
    val before = f.rowsOf().toMap
    val oldHead = harness.headNow().get
    val next = f.commitNext()
    val after = f.rowsOf(next.stateRoot).toMap
    val (position, oldNode) = before.filter { case (p, node) =>
      p.owner.isEmpty && after.get(p).exists(!java.util.Arrays.equals(_, node)) && p.hp != PositionKey.AccountRoot.hp
    }.head

    // a frame taken under the old anchor, read after the move: the row there is the new node, which decodes
    val frame = AuditFrame(positionOf(position), Some(f.hashOf(oldNode)))
    oldHead.stateRoot should not be harness.headNow().get.stateRoot
    val step = AuditWalk.walkBatch(Seq(frame), harness.headNow().get, harness.reader, new AuditCodeFilter(10))

    step.holes shouldBe empty
    step.moved shouldBe 1
    step.pushes shouldBe Vector(AuditFrame(positionOf(position), Some(f.hashOf(after(position)))))
    f.rowAt(position).get shouldBe after(position) // the walk wrote nothing
  }

  it should "find the state clean again after a move, from the new anchor's root" taggedAs UnitTest in {
    val f = new PositionRepairFixture
    val harness = new PositionAuditHarness(f)
    f.commitNext()
    val totals = harness.walkAll()
    totals.holes shouldBe empty
    totals.nodes should be > 0L
  }
