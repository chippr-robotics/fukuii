package com.chipprbots.ethereum.blockchain.sync

import org.apache.pekko.util.ByteString

import scala.collection.mutable

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.EscalationHandler.Outcome
import com.chipprbots.ethereum.db.storage.pathstate.EscalationKind
import com.chipprbots.ethereum.db.storage.pathstate.GivenUpBranch
import com.chipprbots.ethereum.db.storage.pathstate.StateEscalation
import com.chipprbots.ethereum.testing.Tags.*

/** What is done about each verdict to give the state up (spec 018 C5a part 7b; T164): what is answered, what is said
  * and counted, and that a verdict told again and again is answered once.
  */
class EscalationHandlerSpec extends AnyFlatSpec with Matchers:

  private def hash(byte: Int): ByteString = ByteString(Array.fill[Byte](32)(byte.toByte))
  private val Hour = 3600000L
  private val head = hash(5)
  private val first = GivenUpBranch(4001, hash(0x21))
  private val branch = EscalationWatch.Branch(first, () => Iterator(first))

  private def onStake(why: String = "head 0505 cannot be reached"): EscalationWatch.Request =
    EscalationWatch.Request(EscalationKind.Unreachable, Some(head), None, why)
  private def onWork(of: EscalationWatch.Branch = branch): EscalationWatch.Request =
    EscalationWatch.Request(
      EscalationKind.Unreachable,
      None,
      Some(of),
      "the heavier branch [4001-4050] cannot be reached"
    )

  private class Rig:
    var last: Option[StateEscalation] = None
    var givenUp: Set[GivenUpBranch] = Set.empty
    var unreadable = false
    var suppressed = 0
    val errors: mutable.ArrayBuffer[String] = mutable.ArrayBuffer.empty
    val warnings: mutable.ArrayBuffer[String] = mutable.ArrayBuffer.empty
    val handler = new EscalationHandler(
      new EscalationGuard(
        6 * Hour,
        () => if unreadable then throw new IllegalStateException("not a v1 escalation") else last,
        () => givenUp
      ),
      at => Option.when(at == head)(BigInt(7000)),
      () => suppressed += 1,
      errors += _,
      warnings += _
    )

  "The escalation's handler" should "answer the record of the clear for a head on proof of stake" taggedAs UnitTest in {
    val rig = new Rig
    rig.handler.onRequest(onStake(), 100 * Hour) shouldBe
      Outcome.Escalate(
        StateEscalation(100 * Hour, EscalationKind.Unreachable, head, 7000),
        None,
        "head 0505 cannot be reached"
      )
    rig.suppressed shouldBe 0
    rig.errors shouldBe empty
  }

  it should "answer the record and the branch to give up for on proof of work" taggedAs UnitTest in {
    val rig = new Rig
    rig.handler.onRequest(onWork(), 100 * Hour) match
      case Outcome.Escalate(record, givenUpFor, _) =>
        record shouldBe StateEscalation(100 * Hour, EscalationKind.Unreachable, first.hash, 4001)
        givenUpFor shouldBe Some(first)
      case other => fail(s"not an escalation: $other")
  }

  it should "record a verdict with no head named, or a head whose header is not stored, with what it has" taggedAs UnitTest in {
    val rig = new Rig
    val unknown = EscalationWatch.Request(EscalationKind.LocalFault, Some(hash(9)), None, "a fault")
    rig.handler.onRequest(unknown, 1L) shouldBe
      Outcome.Escalate(StateEscalation(1L, EscalationKind.LocalFault, hash(9), 0), None, "a fault")
    val unnamed = EscalationWatch.Request(EscalationKind.LocalFault, None, None, "a fault")
    new Rig().handler.onRequest(unnamed, 1L) shouldBe
      Outcome.Escalate(StateEscalation(1L, EscalationKind.LocalFault, hash(0), 0), None, "a fault")
  }

  it should "hold a verdict inside the cool-down, say so once, and act when it is over" taggedAs UnitTest in {
    val rig = new Rig
    rig.last = Some(StateEscalation(100 * Hour, EscalationKind.LocalFault, hash(9), 5000))
    rig.handler.onRequest(onStake(), 101 * Hour) shouldBe Outcome.Suppressed
    // told again each slot: answered once
    (1 to 50).foreach(i => rig.handler.onRequest(onStake(), 101 * Hour + i * 12000L) shouldBe Outcome.Repeated)
    rig.suppressed shouldBe 1
    rig.warnings should have size 1
    rig.warnings.head should (include("escalation-cooldown").and(include("head 0505 cannot be reached")))
    rig.errors shouldBe empty
    // on proof of stake the head changes with every slot: another head is the same verdict, of the same kind (beacon)
    val other = EscalationWatch.Request(EscalationKind.Unreachable, Some(hash(6)), None, "head 0606 cannot be reached")
    rig.handler.onRequest(other, 102 * Hour) shouldBe Outcome.Repeated
    rig.suppressed shouldBe 1
    // a verdict of the other kind is another
    val fault = EscalationWatch.Request(EscalationKind.LocalFault, Some(hash(6)), None, "head 0606 is not followed")
    rig.handler.onRequest(fault, 102 * Hour) shouldBe Outcome.Suppressed
    rig.suppressed shouldBe 2
    // the cool-down over
    rig.handler.onRequest(onStake(), 106 * Hour) shouldBe a[Outcome.Escalate]
  }

  it should "drop a branch that was given up, at ERROR, once, and say how an operator ends it" taggedAs UnitTest in {
    val rig = new Rig
    rig.givenUp = Set(first)
    rig.handler.onRequest(onWork(), 500 * Hour) shouldBe Outcome.Dropped
    (1 to 50).foreach(i => rig.handler.onRequest(onWork(), 500 * Hour + i) shouldBe Outcome.Repeated)
    rig.suppressed shouldBe 1
    rig.errors should have size 1
    rig.errors.head should (include("[STATE-ESCALATION-GIVEN-UP]")
      .and(include(EscalationHandler.ForgetSwitch))
      .and(include("[4001-4050]")))
    // it is said again after the answers are set back (the head moved): the list still says so
    rig.handler.reset()
    rig.handler.onRequest(onWork(), 600 * Hour) shouldBe Outcome.Dropped
    rig.errors should have size 2
  }

  it should "not act, and say so at ERROR, while the record of the escalations cannot be read" taggedAs UnitTest in {
    val rig = new Rig
    rig.unreadable = true
    rig.handler.onRequest(onStake(), 100 * Hour) shouldBe Outcome.Unreadable
    rig.handler.onRequest(onStake(), 100 * Hour + 1000) shouldBe Outcome.Repeated
    rig.errors should have size 1
    rig.errors.head should include("cannot be read")
    rig.suppressed shouldBe 1
    // it is looked at again after a while (forge): a read that failed once may not fail the next time
    rig.handler.onRequest(
      onStake(),
      100 * Hour + EscalationHandler.UnreadableStandsMillis - 1
    ) shouldBe Outcome.Repeated
    rig.handler.onRequest(onStake(), 100 * Hour + EscalationHandler.UnreadableStandsMillis) shouldBe Outcome.Unreadable
    rig.errors should have size 2
    rig.unreadable = false
    rig.handler.onRequest(onStake(), 100 * Hour + 2 * EscalationHandler.UnreadableStandsMillis) shouldBe
      a[Outcome.Escalate]
  }

  it should "forget what it answered once it has answered an escalation" taggedAs UnitTest in {
    val rig = new Rig
    val fault = EscalationWatch.Request(EscalationKind.LocalFault, Some(hash(6)), None, "head 0606 is not followed")
    // an answer that still stands when the escalation is answered: a record that could not be read a minute before
    rig.unreadable = true
    rig.handler.onRequest(fault, 106 * Hour - 60000) shouldBe Outcome.Unreadable
    rig.unreadable = false
    rig.handler.onRequest(onStake(), 106 * Hour) shouldBe a[Outcome.Escalate]
    // the owner cleared the state and recorded the escalation: the next verdict is decided anew, not repeated
    rig.last = Some(StateEscalation(106 * Hour, EscalationKind.Unreachable, head, 7000))
    rig.handler.onRequest(fault, 106 * Hour + 1) shouldBe Outcome.Suppressed
  }

  // beacon: a clear that could not begin says a recovery is stuck; tried again with every slot it holds a thread each time
  it should "ask about no verdict for a while after a clear that could not be begun" taggedAs UnitTest in {
    val rig = new Rig
    rig.handler.onRequest(onStake(), 100 * Hour) shouldBe a[Outcome.Escalate]
    rig.handler.clearRefused(100 * Hour)
    rig.suppressed shouldBe 1
    // nothing was recorded, so the guard would say "act": it is not asked
    rig.handler.onRequest(onStake(), 100 * Hour + 12000) shouldBe Outcome.Repeated
    rig.handler.onRequest(
      onWork(),
      100 * Hour + EscalationHandler.RefusedClearHoldsMillis - 1
    ) shouldBe Outcome.Repeated
    // regular sync beginning again does not end the hold
    rig.handler.reset()
    rig.handler.onRequest(onStake(), 100 * Hour + 24000) shouldBe Outcome.Repeated
    rig.handler
      .onRequest(onStake(), 100 * Hour + EscalationHandler.RefusedClearHoldsMillis) shouldBe a[Outcome.Escalate]
  }

  "The operator's switch" should "forget the branches at a start, synced, and say which" taggedAs UnitTest in {
    var listed: Set[GivenUpBranch] = Set(first, GivenUpBranch(9000, hash(0x55)))
    var removed = 0
    val said = mutable.ArrayBuffer.empty[String]
    def forget(asked: Boolean): Boolean =
      EscalationHandler.forgetGivenUpAtStart(
        asked,
        () => listed,
        () =>
          removed += 1; listed = Set.empty
        ,
        said += _
      )
    // not asked: nothing is read into a decision, nothing removed, nothing said
    forget(asked = false) shouldBe false
    (removed, said.size) shouldBe ((0, 0))
    forget(asked = true) shouldBe true
    removed shouldBe 1
    said should have size 1
    said.head should (include("block 4001 21212121").and(include("block 9000 55555555")).and(include("Unset")))
    // left set: said again at the next start, with nothing left to remove
    forget(asked = true) shouldBe false
    removed shouldBe 1
    said should have size 2
    said.last should include("no branch is on the list")
  }

  it should "remove a list that cannot be read: it is what the switch is for" taggedAs UnitTest in {
    var removed = 0
    val said = mutable.ArrayBuffer.empty[String]
    val unreadable = () => throw new IllegalStateException("not a v1 list of branches")
    EscalationHandler.forgetGivenUpAtStart(asked = true, unreadable, () => removed += 1, said += _) shouldBe true
    removed shouldBe 1
    said.head should include("could not be read")
    // and without the switch such a list is left as it is
    EscalationHandler.forgetGivenUpAtStart(asked = false, unreadable, () => removed += 1, said += _) shouldBe false
    removed shouldBe 1
  }

  // forge: the guard says no to every verdict while the last escalation cannot be read, and nothing else removes it
  it should "remove a record of the last escalation that cannot be read, and keep one that can" taggedAs UnitTest in {
    var removedLast = 0
    val said = mutable.ArrayBuffer.empty[String]
    def forget(asked: Boolean, last: () => Option[StateEscalation]): Boolean =
      EscalationHandler.forgetGivenUpAtStart(asked, () => Set.empty, () => (), said += _, last, () => removedLast += 1)
    val readable = () => Some(StateEscalation(1L, EscalationKind.LocalFault, hash(9), 5000))
    val unreadable = () => throw new IllegalStateException("not a v1 escalation")
    forget(asked = true, readable)
    removedLast shouldBe 0
    forget(asked = false, unreadable)
    removedLast shouldBe 0
    forget(asked = true, unreadable)
    removedLast shouldBe 1
    said.exists(_.contains("last escalation could not be read")) shouldBe true
  }
