package com.chipprbots.ethereum.blockchain.sync

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.EscalationGuard.Decision
import com.chipprbots.ethereum.db.storage.pathstate.EscalationKind
import com.chipprbots.ethereum.db.storage.pathstate.GivenUpBranch
import com.chipprbots.ethereum.db.storage.pathstate.StateEscalation
import com.chipprbots.ethereum.testing.Tags.*

/** The guards of an escalation (spec 018 C5a part 7; T164): the cool-down between two, counted from a record that a
  * restart keeps, and the loop guard of a proof-of-work branch.
  */
class EscalationGuardSpec extends AnyFlatSpec with Matchers:

  private def hash(byte: Int): ByteString = ByteString(Array.fill[Byte](32)(byte.toByte))
  private val Hour = 3600000L
  private val Cooldown = 6 * Hour
  private val first = GivenUpBranch(4001, hash(0x21))

  /** A refused branch named `name`, whose blocks, tip first, are `blocks`. */
  private def refused(name: GivenUpBranch, blocks: GivenUpBranch*): EscalationWatch.Branch =
    EscalationWatch.Branch(name, () => (if blocks.isEmpty then Seq(name) else blocks).iterator)
  private val branch = refused(first)

  private class Rig:
    var last: Option[StateEscalation] = None
    var givenUp: Set[GivenUpBranch] = Set.empty
    var lastUnreadable = false
    var givenUpUnreadable = false
    val guard = new EscalationGuard(
      Cooldown,
      () => if lastUnreadable then throw new IllegalStateException("not a v1 escalation") else last,
      () => if givenUpUnreadable then throw new IllegalStateException("not a v1 list") else givenUp
    )

  "The escalation's guard" should "act on the first verdict, and record the branch on proof of work" taggedAs UnitTest in {
    val rig = new Rig
    rig.guard.decide(EscalationKind.Unreachable, Some(branch), 100 * Hour) shouldBe
      Decision.Act(EscalationKind.Unreachable, Some(first))
    rig.guard.decide(EscalationKind.LocalFault, None, 100 * Hour) shouldBe Decision.Act(EscalationKind.LocalFault, None)
  }

  it should "not act inside the cool-down of the last escalation, and act once it is over" taggedAs UnitTest in {
    val rig = new Rig
    val before = StateEscalation(100 * Hour, EscalationKind.LocalFault, hash(9), 5000)
    rig.last = Some(before)
    rig.guard.decide(EscalationKind.Unreachable, None, 100 * Hour + 1) shouldBe
      Decision.CoolingDown(before, 106 * Hour)
    rig.guard.decide(EscalationKind.Unreachable, None, 106 * Hour - 1) shouldBe
      Decision.CoolingDown(before, 106 * Hour)
    rig.guard.decide(EscalationKind.Unreachable, None, 106 * Hour) shouldBe
      Decision.Act(EscalationKind.Unreachable, None)
  }

  // forge: the end is the record's, not one that moves with the clock
  it should "hold a record dated after now until the clock reaches its end" taggedAs UnitTest in {
    val rig = new Rig
    val before = StateEscalation(100 * Hour, EscalationKind.LocalFault, hash(9), 5000)
    rig.last = Some(before)
    rig.guard.decide(EscalationKind.Unreachable, None, 50 * Hour) shouldBe Decision.CoolingDown(before, 106 * Hour)
    rig.guard.decide(EscalationKind.Unreachable, None, 51 * Hour) shouldBe Decision.CoolingDown(before, 106 * Hour)
  }

  it should "act at once with no cool-down, whatever the clock says" taggedAs UnitTest in {
    val before = StateEscalation(100 * Hour, EscalationKind.LocalFault, hash(9), 5000)
    val guard = new EscalationGuard(0L, () => Some(before), () => Set.empty)
    guard.decide(EscalationKind.Unreachable, None, 100 * Hour) shouldBe Decision.Act(EscalationKind.Unreachable, None)
    guard.decide(EscalationKind.Unreachable, None, 50 * Hour) shouldBe Decision.Act(EscalationKind.Unreachable, None)
  }

  // forge: the sync after an escalation rewrites the number index, and the same branch is then named by another block
  it should "know a branch again by any block of it, whatever it is named now" taggedAs UnitTest in {
    val rig = new Rig
    rig.givenUp = Set(first, GivenUpBranch(9000, hash(0x55)))
    val renamed = GivenUpBranch(4003, hash(0x23))
    // the cut that is refused now begins higher; under it, stored, are the blocks down to the one given up for
    val walked = scala.collection.mutable.ArrayBuffer.empty[BigInt]
    val blocks = Seq(
      GivenUpBranch(4005, hash(0x25)),
      GivenUpBranch(4004, hash(0x24)),
      renamed,
      GivenUpBranch(4002, hash(0x22)),
      first,
      GivenUpBranch(4000, hash(0x20)),
      GivenUpBranch(3999, hash(0x1f))
    )
    val again = EscalationWatch.Branch(
      renamed,
      () =>
        blocks.iterator.map { block =>
          walked += block.number; block
        }
    )
    rig.guard.decide(EscalationKind.Unreachable, Some(again), 9000 * Hour) shouldBe Decision.GivenUp
    walked.toList shouldBe List[BigInt](4005, 4004, 4003, 4002, 4001)
    // a branch that shares no block with one given up: walked no lower than the lowest height on the list
    walked.clear()
    val another = blocks.map(block => block.copy(hash = hash(0x70 + (block.number - 3999).toInt)))
    val unrelated =
      EscalationWatch.Branch(
        another(2),
        () =>
          another.iterator.map { block =>
            walked += block.number; block
          }
      )
    rig.guard.decide(EscalationKind.Unreachable, Some(unrelated), 9000 * Hour) shouldBe
      Decision.Act(EscalationKind.Unreachable, Some(another(2)))
    walked.toList shouldBe List[BigInt](4005, 4004, 4003, 4002, 4001, 4000)
  }

  it should "never act again for a branch the state was given up for, cool-down over or not" taggedAs UnitTest in {
    val rig = new Rig
    rig.givenUp = Set(first)
    rig.last = Some(StateEscalation(100 * Hour, EscalationKind.Unreachable, first.hash, 4001))
    rig.guard.decide(EscalationKind.Unreachable, Some(branch), 101 * Hour) shouldBe Decision.GivenUp
    rig.guard.decide(EscalationKind.LocalFault, Some(branch), 9000 * Hour) shouldBe Decision.GivenUp
    // another branch is another escalation: held by the cool-down, then acted on
    val otherName = GivenUpBranch(4001, hash(0x22))
    val other = refused(otherName)
    rig.guard.decide(EscalationKind.Unreachable, Some(other), 101 * Hour) shouldBe a[Decision.CoolingDown]
    rig.guard.decide(EscalationKind.Unreachable, Some(other), 106 * Hour) shouldBe
      Decision.Act(EscalationKind.Unreachable, Some(otherName))
    // and a verdict that names no branch (proof of stake) is not held by the list
    rig.guard.decide(EscalationKind.Unreachable, None, 106 * Hour) shouldBe
      Decision.Act(EscalationKind.Unreachable, None)
  }

  it should "say no while a record cannot be read" taggedAs UnitTest in {
    val rig = new Rig
    rig.lastUnreadable = true
    rig.guard.decide(EscalationKind.Unreachable, None, 100 * Hour) shouldBe a[Decision.Unreadable]
    rig.lastUnreadable = false
    rig.givenUpUnreadable = true
    rig.guard.decide(EscalationKind.Unreachable, Some(branch), 100 * Hour) shouldBe a[Decision.Unreadable]
    // the list is not read for a verdict that names no branch
    rig.guard.decide(EscalationKind.Unreachable, None, 100 * Hour) shouldBe
      Decision.Act(EscalationKind.Unreachable, None)
  }
