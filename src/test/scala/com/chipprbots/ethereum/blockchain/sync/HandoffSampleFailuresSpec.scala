package com.chipprbots.ethereum.blockchain.sync

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.db.storage.PathHandoffSample.StopReason
import com.chipprbots.ethereum.testing.Tags.*

/** B-304: the hand-off's samples that stopped a sync since the last hand-off are counted, and the third is not
  * restarted.
  */
class HandoffSampleFailuresSpec extends AnyFlatSpec with Matchers:

  "The count of the hand-off's samples that stopped a sync" should "restart the first two and give the third up" taggedAs (
    UnitTest,
    SyncTest
  ) in {
    val failures = new HandoffSampleFailures
    failures.stopped(StopReason.AccountHole, BigInt(101)) shouldBe HandoffSampleFailures.Verdict.Restart(1)
    failures.stopped(StopReason.StorageHole, BigInt(102)) shouldBe HandoffSampleFailures.Verdict.Restart(2)
    failures.stopped(StopReason.RootAbsent, BigInt(103)) shouldBe HandoffSampleFailures.Verdict.GiveUp(3)
    failures.consecutive shouldBe 3
  }

  it should "count samples of any reason together: a systematic fault need not stop each sample the same way" taggedAs (
    UnitTest,
    SyncTest
  ) in {
    val failures = new HandoffSampleFailures
    StopReason.values.take(2).zipWithIndex.foreach((r, i) => failures.stopped(r, BigInt(i)))
    failures.stopped(StopReason.values.last, BigInt(7)) shouldBe HandoffSampleFailures.Verdict.GiveUp(3)
  }

  it should "begin again after a hand-off that was made" taggedAs (UnitTest, SyncTest) in {
    val failures = new HandoffSampleFailures
    failures.stopped(StopReason.AccountHole, BigInt(104))
    failures.stopped(StopReason.AccountHole, BigInt(105))
    failures.handedOff()
    failures.consecutive shouldBe 0
    failures.stopped(StopReason.AccountHole, BigInt(106)) shouldBe HandoffSampleFailures.Verdict.Restart(1)
    failures.stopped(StopReason.AccountHole, BigInt(107)) shouldBe HandoffSampleFailures.Verdict.Restart(2)
    failures.stopped(StopReason.AccountHole, BigInt(108)) shouldBe HandoffSampleFailures.Verdict.GiveUp(3)
  }

  it should "bound the restarts at three" taggedAs (UnitTest, SyncTest) in {
    HandoffSampleFailures.Bound shouldBe 3
  }

  // beacon: a frozen consensus client leaves the head on a root no peer serves (#1555); the rows are not at fault
  it should "not count a root absent again at the pivot of the last stop, and count one at a new pivot" taggedAs (
    UnitTest,
    SyncTest
  ) in {
    val failures = new HandoffSampleFailures
    failures.stopped(StopReason.RootAbsent, BigInt(500)) shouldBe HandoffSampleFailures.Verdict.Restart(1)
    failures.stopped(StopReason.RootAbsent, BigInt(500)) shouldBe HandoffSampleFailures.Verdict.NotCounted(1)
    failures.stopped(StopReason.RootAbsent, BigInt(500)) shouldBe HandoffSampleFailures.Verdict.NotCounted(1)
    failures.stopped(StopReason.RootAbsent, BigInt(564)) shouldBe HandoffSampleFailures.Verdict.Restart(2)
    // any other rule counts at the same pivot: the rows are not whole
    failures.stopped(StopReason.AccountHole, BigInt(564)) shouldBe HandoffSampleFailures.Verdict.GiveUp(3)
  }
