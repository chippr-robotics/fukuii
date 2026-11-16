package com.chipprbots.ethereum.blockchain.sync

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.db.storage.PathHandoffSample.StopReason
import com.chipprbots.ethereum.testing.Tags.*

/** B-304: the hand-off's samples that stopped a sync in a row are counted, and the third is not restarted. */
class HandoffSampleFailuresSpec extends AnyFlatSpec with Matchers:

  "The count of the hand-off's samples that stopped a sync" should "restart the first two and give the third up" taggedAs (
    UnitTest,
    SyncTest
  ) in {
    val failures = new HandoffSampleFailures
    failures.stopped(StopReason.AccountHole) shouldBe HandoffSampleFailures.Verdict.Restart(1)
    failures.stopped(StopReason.StorageHole) shouldBe HandoffSampleFailures.Verdict.Restart(2)
    failures.stopped(StopReason.RootAbsent) shouldBe HandoffSampleFailures.Verdict.GiveUp(3)
    failures.consecutive shouldBe 3
  }

  it should "count samples of any reason together: a systematic fault need not stop each sample the same way" taggedAs (
    UnitTest,
    SyncTest
  ) in {
    val failures = new HandoffSampleFailures
    StopReason.values.take(2).foreach(failures.stopped)
    failures.stopped(StopReason.values.last) shouldBe HandoffSampleFailures.Verdict.GiveUp(3)
  }

  it should "begin again after a hand-off that was made" taggedAs (UnitTest, SyncTest) in {
    val failures = new HandoffSampleFailures
    failures.stopped(StopReason.AccountHole)
    failures.stopped(StopReason.AccountHole)
    failures.handedOff()
    failures.consecutive shouldBe 0
    failures.stopped(StopReason.AccountHole) shouldBe HandoffSampleFailures.Verdict.Restart(1)
    failures.stopped(StopReason.AccountHole) shouldBe HandoffSampleFailures.Verdict.Restart(2)
    failures.stopped(StopReason.AccountHole) shouldBe HandoffSampleFailures.Verdict.GiveUp(3)
  }

  it should "bound the restarts at three" taggedAs (UnitTest, SyncTest) in {
    HandoffSampleFailures.Bound shouldBe 3
  }
