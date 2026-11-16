package com.chipprbots.ethereum.blockchain.sync

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.EscalationTrigger.Verdict
import com.chipprbots.ethereum.db.storage.pathstate.EscalationKind
import com.chipprbots.ethereum.testing.Tags.*

/** The trigger of an escalation and its hysteresis (spec 018 T163; data-model Part 2 D, part 1): what waits, for how
  * long, and what sets the count back.
  */
class EscalationTriggerSpec extends AnyFlatSpec with Matchers:

  private val Slot = 12000L

  /** Proof of stake: 64 refused fork choices in a row, and no less than 64 slots. */
  private def proofOfStake = new EscalationTrigger(refusals = 64, minSpanMillis = 64 * Slot)

  "The escalation's trigger" should "wait through 63 refused fork choices of one fault, and escalate on the 64th" taggedAs UnitTest in {
    val trigger = proofOfStake
    (1 to 63).foreach(i => trigger.localFault(i * Slot) shouldBe Verdict.Waiting(i))
    trigger.localFault(65 * Slot) shouldBe Verdict.Escalate(EscalationKind.LocalFault)
  }

  it should "wait for the time as well as the count: 64 refusals inside a few seconds are a client that asks often" taggedAs UnitTest in {
    val trigger = proofOfStake
    (1 to 200).foreach(i => trigger.localFault(1000L + i) shouldBe Verdict.Waiting(i))
    withClue("one slot short of 64 from the first: ")(
      trigger.localFault(1001L + 64 * Slot - 1) shouldBe a[Verdict.Waiting]
    )
    trigger.localFault(1001L + 64 * Slot) shouldBe Verdict.Escalate(EscalationKind.LocalFault)
  }

  it should "begin again after any move of the head that succeeds" taggedAs UnitTest in {
    val trigger = proofOfStake
    (1 to 63).foreach(i => trigger.localFault(i * Slot))
    trigger.headMoved()
    trigger.refused shouldBe 0
    withClue("the 64th overall is the first of a new run, and its time starts with it: ") {
      trigger.localFault(100 * Slot) shouldBe Verdict.Waiting(1)
      (2 to 63).foreach(i => trigger.localFault((100 + i) * Slot) shouldBe Verdict.Waiting(i))
      trigger.localFault(164 * Slot) shouldBe Verdict.Escalate(EscalationKind.LocalFault)
    }
  }

  it should "cost nothing when a consensus client names a head the node cannot move to and one it can, in turn" taggedAs UnitTest in {
    val trigger = proofOfStake
    (1 to 1000).foreach { i =>
      trigger.localFault(i * Slot) shouldBe Verdict.Waiting(1)
      trigger.headMoved()
    }
  }

  it should "escalate at once for a chain that cannot be reached, and leave the run of faults as it was" taggedAs UnitTest in {
    val trigger = proofOfStake
    trigger.localFault(Slot)
    trigger.localFault(2 * Slot)

    trigger.unreachable() shouldBe Verdict.Escalate(EscalationKind.Unreachable)

    trigger.refused shouldBe 2
    trigger.localFault(3 * Slot) shouldBe Verdict.Waiting(3)
  }

  it should "keep escalating once it has, until the head moves: the handler's cool-down keeps two apart" taggedAs UnitTest in {
    val trigger = proofOfStake
    (1 to 64).foreach(i => trigger.localFault(i * 2 * Slot))
    trigger.localFault(200 * Slot) shouldBe Verdict.Escalate(EscalationKind.LocalFault)
    trigger.headMoved()
    trigger.localFault(201 * Slot) shouldBe Verdict.Waiting(1)
  }

  /** Proof of work: 64 blocks of growth of the branch that is refused. */
  private def proofOfWork = new EscalationTrigger(refusals = 64)

  it should "wait on proof of work for 64 blocks of growth of the refused branch, however often it is offered" taggedAs UnitTest in {
    val trigger = proofOfWork
    trigger.localFaultOnBranch(1000) shouldBe Verdict.Waiting(0)
    (1 to 500).foreach(_ => trigger.localFaultOnBranch(1000) shouldBe Verdict.Waiting(0))
    trigger.localFaultOnBranch(1063) shouldBe Verdict.Waiting(63)
    trigger.localFaultOnBranch(1064) shouldBe Verdict.Escalate(EscalationKind.LocalFault)
  }

  it should "begin again on proof of work when the head moves, and when a lower branch is the one refused" taggedAs UnitTest in {
    val trigger = proofOfWork
    trigger.localFaultOnBranch(1000)
    trigger.localFaultOnBranch(1050) shouldBe Verdict.Waiting(50)
    trigger.headMoved()
    trigger.localFaultOnBranch(1060) shouldBe Verdict.Waiting(0)
    trigger.localFaultOnBranch(1123) shouldBe Verdict.Waiting(63)

    withClue("a tip below where the run began is another branch: ") {
      trigger.localFaultOnBranch(900) shouldBe Verdict.Waiting(0)
      trigger.localFaultOnBranch(963) shouldBe Verdict.Waiting(63)
      trigger.localFaultOnBranch(964) shouldBe Verdict.Escalate(EscalationKind.LocalFault)
    }
  }

  it should "refuse a count of no refusals and a span of time that is negative" taggedAs UnitTest in {
    an[IllegalArgumentException] should be thrownBy new EscalationTrigger(0)
    an[IllegalArgumentException] should be thrownBy new EscalationTrigger(64, -1L)
  }
