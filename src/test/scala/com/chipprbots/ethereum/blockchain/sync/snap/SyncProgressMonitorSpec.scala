package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestKit

import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags._

class SyncProgressMonitorSpec
    extends TestKit(ActorSystem("SyncProgressMonitorSpec"))
    with AnyFlatSpecLike
    with Matchers
    with BeforeAndAfterAll {

  override def afterAll(): Unit =
    TestKit.shutdownActorSystem(system)

  // Regression for #1184 follow-up: SNAPSyncController never called
  // `updateEstimates(bytecodes = N)`, so `estimatedTotalBytecodes` stayed at 0 and the dashboard's
  // `100 * downloaded / clamp_min(estimated_total, 1)` formula divided by the clamp floor (1),
  // producing readings like `5,239,500%` for normal in-flight bytecode counts. The fix is in
  // `SNAPSyncController.IncrementalContractData` (accumulates `bytecodesEstimatedTotal` and
  // calls `progressMonitor.updateEstimates(bytecodes = total)`); this test locks the
  // `SyncProgressMonitor` side of that contract so a future refactor can't silently revert it.
  "SyncProgressMonitor.updateEstimates" should
    "set estimatedTotalBytecodes from the bytecodes argument" taggedAs UnitTest in {
      val monitor = new SyncProgressMonitor(system.scheduler)

      monitor.currentProgress.estimatedTotalBytecodes shouldBe 0L
      monitor.updateEstimates(bytecodes = 12_500L)
      monitor.currentProgress.estimatedTotalBytecodes shouldBe 12_500L
    }

  it should "honour the running-total contract: a larger update overrides the previous estimate" taggedAs UnitTest in {
    val monitor = new SyncProgressMonitor(system.scheduler)

    monitor.updateEstimates(bytecodes = 1_000L)
    monitor.currentProgress.estimatedTotalBytecodes shouldBe 1_000L

    // SNAPSyncController accumulates `bytecodesEstimatedTotal += codeHashes.size` per
    // `IncrementalContractData` and pushes the new total — so the monitor must accept
    // monotonically increasing replacements.
    monitor.updateEstimates(bytecodes = 5_000L)
    monitor.currentProgress.estimatedTotalBytecodes shouldBe 5_000L
  }

  it should "ignore a zero-or-negative update so callers can pass `accounts = 0` while updating bytecodes" taggedAs UnitTest in {
    val monitor = new SyncProgressMonitor(system.scheduler)

    monitor.updateEstimates(accounts = 7_000L, bytecodes = 0L, slots = 0L)
    monitor.updateEstimates(bytecodes = 3_000L)

    monitor.currentProgress.estimatedTotalAccounts shouldBe 7_000L
    monitor.currentProgress.estimatedTotalBytecodes shouldBe 3_000L
    monitor.currentProgress.estimatedTotalSlots shouldBe 0L
  }

  it should "produce a non-divide-by-clamp ratio after a real bytecode estimate is set" taggedAs UnitTest in {
    // The dashboard formula is `100 * downloaded / clamp_min(estimated, 1)`. Before the fix,
    // `estimated` was 0 → clamp_min picks 1 → `100 * 52395 / 1 = 5,239,500`. After the fix the
    // estimate is real, so the percentage stays sane.
    val monitor = new SyncProgressMonitor(system.scheduler)
    monitor.updateEstimates(bytecodes = 100_000L)
    monitor.incrementBytecodesDownloaded(52_395L)

    val progress = monitor.currentProgress
    val percentDouble = 100.0 * progress.bytecodesDownloaded / math.max(progress.estimatedTotalBytecodes, 1L)

    percentDouble shouldBe 52.395 +- 0.01
  }
}
