package com.chipprbots.ethereum.blockchain.sync

import org.apache.pekko.util.ByteString

import scala.collection.mutable

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.EscalationWatch.Signal
import com.chipprbots.ethereum.db.storage.pathstate.BlockRef
import com.chipprbots.ethereum.db.storage.pathstate.EscalationKind
import com.chipprbots.ethereum.ledger.StateRecovery.Outcome
import com.chipprbots.ethereum.testing.Tags.*

/** What each end of a recovery says to the escalation's trigger, and that the watch only says (spec 018 C5a part 6). */
class EscalationWatchSpec extends AnyFlatSpec with Matchers:

  private val block = BlockRef(ByteString(Array.fill[Byte](32)(1)), 7, ByteString(Array.fill[Byte](32)(2)))
  private val Slot = 12000L

  private class Rig(refusals: Int = 64):
    var clock = 0L
    val escalated: mutable.ArrayBuffer[EscalationKind] = mutable.ArrayBuffer.empty
    val said: mutable.ArrayBuffer[(EscalationKind, String)] = mutable.ArrayBuffer.empty
    val watch = new EscalationWatch(
      new EscalationTrigger(refusals, refusals * Slot),
      () => clock,
      escalated += _,
      (kind, why) => said += ((kind, why))
    )
    def refusedOnce(outcome: Outcome): Unit =
      clock += Slot
      watch.ended(outcome, "abcd", watch.currentRun)

  "The classification of a recovery's end" should "be the table of the data model" taggedAs UnitTest in {
    EscalationWatch.classify(Outcome.Recovered(3)) shouldBe Signal.HeadMoved
    EscalationWatch.classify(Outcome.Adopted(300)) shouldBe Signal.HeadMoved
    EscalationWatch.classify(Outcome.TooLong("9000 blocks")) shouldBe Signal.Unreachable("9000 blocks")
    // what may yet mend waits: a branch not adopted (the outcome does not say why), a block that may be on its way
    EscalationWatch.classify(Outcome.NotAdopted("a branch")) shouldBe a[Signal.Waits]
    EscalationWatch.classify(Outcome.MissingHeader(block.hash)) shouldBe a[Signal.Waits]
    EscalationWatch.classify(Outcome.MissingBody(block)) shouldBe a[Signal.Waits]
    EscalationWatch.classify(Outcome.LocalFault("a corrupt record")) shouldBe a[Signal.Waits]
    Seq(
      Outcome.AlreadyHeld,
      Outcome.NotKept(block),
      Outcome.Invalid(block, "a bad root"),
      Outcome.Superseded,
      Outcome.Closed,
      Outcome.Busy
    ).foreach(outcome => withClue(s"$outcome: ")(EscalationWatch.classify(outcome) shouldBe Signal.Nothing))
  }

  "The escalation's watch" should "say at once for a head that cannot be reached, and once for a run of them" taggedAs UnitTest in {
    val rig = new Rig
    rig.refusedOnce(Outcome.TooLong("9000 blocks"))
    rig.refusedOnce(Outcome.TooLong("9001 blocks"))
    rig.watch.refusedUnreachable("abcd", "below the block the state began at")

    rig.escalated.toSeq shouldBe Seq.fill(3)(EscalationKind.Unreachable)
    rig.said should have size 1
    rig.said.head._1 shouldBe EscalationKind.Unreachable
    rig.said.head._2 should (include("abcd").and(include("9000 blocks")))
  }

  it should "wait through 63 local faults and say on the 64th" taggedAs UnitTest in {
    val rig = new Rig
    (1 to 63).foreach(_ => rig.refusedOnce(Outcome.LocalFault("a corrupt record")))
    rig.escalated shouldBe empty
    rig.clock += Slot
    rig.refusedOnce(Outcome.LocalFault("a corrupt record"))
    rig.escalated.toSeq shouldBe Seq(EscalationKind.LocalFault)
    rig.said.map(_._1).toSeq shouldBe Seq(EscalationKind.LocalFault)
  }

  it should "begin again when a recovery moves the head, and say again for the next run" taggedAs UnitTest in {
    val rig = new Rig
    rig.refusedOnce(Outcome.TooLong("far"))
    rig.refusedOnce(Outcome.Adopted(300))
    rig.refusedOnce(Outcome.TooLong("far again"))
    rig.said.map(_._2).count(_.contains("far")) shouldBe 2

    val faults = new Rig
    (1 to 63).foreach(_ => faults.refusedOnce(Outcome.LocalFault("x")))
    faults.refusedOnce(Outcome.Recovered(1))
    (1 to 63).foreach(_ => faults.refusedOnce(Outcome.LocalFault("x")))
    faults.escalated shouldBe empty

    val other = new Rig
    (1 to 63).foreach(_ => other.refusedOnce(Outcome.LocalFault("x")))
    other.watch.headMoved()
    (1 to 63).foreach(_ => other.refusedOnce(Outcome.LocalFault("x")))
    other.escalated shouldBe empty
  }

  it should "count nothing for an end that says nothing of the chain" taggedAs UnitTest in {
    val rig = new Rig(refusals = 2)
    rig.refusedOnce(Outcome.LocalFault("x"))
    Seq(Outcome.Busy, Outcome.Superseded, Outcome.Closed, Outcome.AlreadyHeld, Outcome.Invalid(block, "bad")).foreach(
      rig.refusedOnce
    )
    rig.escalated shouldBe empty
    withClue("the run stands: the second fault, two slots after the first, ends it: ") {
      rig.refusedOnce(Outcome.LocalFault("x"))
      rig.escalated.toSeq shouldBe Seq(EscalationKind.LocalFault)
    }
  }

  it should "wait for a branch that was not adopted and for a block that is not stored, as for a fault" taggedAs UnitTest in {
    Seq[Outcome](Outcome.NotAdopted("a branch"), Outcome.MissingBody(block), Outcome.MissingHeader(block.hash))
      .foreach { outcome =>
        val rig = new Rig
        (1 to 63).foreach(_ => rig.refusedOnce(outcome))
        withClue(s"$outcome: ")(rig.escalated shouldBe empty)
        rig.clock += Slot
        rig.refusedOnce(outcome)
        rig.escalated.toSeq shouldBe Seq(EscalationKind.LocalFault)
      }
  }

  it should "count a fork choice refused for a fault where no recovery is asked for, and begin again on a head that is followed" taggedAs UnitTest in {
    val rig = new Rig(refusals = 2)
    rig.clock += Slot
    rig.watch.refusedForAFault("abcd", "the records are corrupt")
    rig.escalated shouldBe empty
    rig.watch.headMoved()
    rig.clock += 5 * Slot
    rig.watch.refusedForAFault("abcd", "the records are corrupt")
    withClue("the first of a new run: ")(rig.escalated shouldBe empty)
    rig.clock += 2 * Slot
    rig.watch.refusedForAFault("abcd", "the records are corrupt")
    rig.escalated.toSeq shouldBe Seq(EscalationKind.LocalFault)
    rig.said.head._2 should include("the records are corrupt")
  }

  // beacon: a recovery asked for before the head moved may end after it
  it should "drop the end of a recovery that was asked for before the head moved" taggedAs UnitTest in {
    val rig = new Rig(refusals = 1)
    val asked = rig.watch.currentRun
    rig.watch.headMoved()
    rig.watch.currentRun should not be asked
    rig.clock += Slot
    rig.watch.ended(Outcome.TooLong("far"), "abcd", asked)
    rig.watch.ended(Outcome.LocalFault("x"), "abcd", asked)
    rig.escalated shouldBe empty
    withClue("one asked for since counts: ") {
      rig.watch.ended(Outcome.TooLong("far"), "abcd", rig.watch.currentRun)
      rig.escalated.toSeq shouldBe Seq(EscalationKind.Unreachable)
    }
    withClue("and a move of the head is a move whenever it was asked for: ") {
      rig.watch.ended(Outcome.Adopted(3), "abcd", asked)
      rig.watch.ended(Outcome.TooLong("far"), "abcd", rig.watch.currentRun)
      rig.said should have size 2
    }
  }
