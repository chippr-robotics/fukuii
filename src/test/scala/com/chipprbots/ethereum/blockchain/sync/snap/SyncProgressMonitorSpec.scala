package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

class SyncProgressMonitorSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers:

  private val classicScheduler: org.apache.pekko.actor.Scheduler = system.classicSystem.scheduler

  // Regression for #1184 follow-up: SNAPSyncController never called
  // `updateEstimates(bytecodes = N)`, so `estimatedTotalBytecodes` stayed at 0 and the dashboard's
  // `100 * downloaded / clamp_min(estimated_total, 1)` formula divided by the clamp floor (1),
  // producing readings like `5,239,500%` for normal in-flight bytecode counts. The fix is in
  // `SNAPSyncController.IncrementalContractData` (accumulates `bytecodesEstimatedTotal` and
  // calls `progressMonitor.updateEstimates(bytecodes = total)`); this test locks the
  // `SyncProgressMonitor` side of that contract so a future refactor can't silently revert it.
  "SyncProgressMonitor.updateEstimates" should
    "set estimatedTotalBytecodes from the bytecodes argument" taggedAs UnitTest in {
      val monitor = new SyncProgressMonitor(classicScheduler)

      monitor.currentProgress.estimatedTotalBytecodes shouldBe 0L
      monitor.updateEstimates(bytecodes = 12_500L)
      monitor.currentProgress.estimatedTotalBytecodes shouldBe 12_500L
    }

  it should "honour the running-total contract: a larger update overrides the previous estimate" taggedAs UnitTest in {
    val monitor = new SyncProgressMonitor(classicScheduler)

    monitor.updateEstimates(bytecodes = 1_000L)
    monitor.currentProgress.estimatedTotalBytecodes shouldBe 1_000L

    // SNAPSyncController accumulates `bytecodesEstimatedTotal += codeHashes.size` per
    // `IncrementalContractData` and pushes the new total — so the monitor must accept
    // monotonically increasing replacements.
    monitor.updateEstimates(bytecodes = 5_000L)
    monitor.currentProgress.estimatedTotalBytecodes shouldBe 5_000L
  }

  it should "ignore a zero-or-negative update so callers can pass `accounts = 0` while updating bytecodes" taggedAs UnitTest in {
    val monitor = new SyncProgressMonitor(classicScheduler)

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
    val monitor = new SyncProgressMonitor(classicScheduler)
    monitor.updateEstimates(bytecodes = 100_000L)
    monitor.incrementBytecodesDownloaded(52_395L)

    val progress = monitor.currentProgress
    val percentDouble = 100.0 * progress.bytecodesDownloaded / math.max(progress.estimatedTotalBytecodes, 1L)

    percentDouble shouldBe 52.395 +- 0.01
  }

  // ===== B-044: SNAP progress must not go backwards within a phase =====
  //
  // The estimates arrive in pushes (`updateEstimates`) while the counters keep rising between them, so the ratio is
  // not monotonic on its own. The tests below drive the monitor the way `DownloadSupervisor` does (the call sites are
  // `DownloadSupervisor.scala` `ProgressAccountEstimate` and `ProgressStorageContracts`) and read what the log line
  // (`phaseProgress`, `wormBlock`) and the `--tui` Overall worm (`globalProgress`) read.

  import SNAPSyncController.SyncPhase.*

  /** The slot estimate `DownloadSupervisor` pushes with each storage-contract report: slots / completed * total. */
  private def reportStorageContracts(monitor: SyncProgressMonitor, completed: Int, total: Int): Unit =
    monitor.updateStorageContracts(completed, total)
    val slots = monitor.getStorageSlotsSynced
    if completed > 0 && total > 0 then monitor.updateEstimates(slots = (slots.toDouble / completed * total).toLong)

  private def phaseProgressSeries(samples: Seq[SyncProgress]): Seq[Int] = samples.map(_.phaseProgress)

  private def assertNonDecreasing(label: String, values: Seq[Double]): Unit =
    values.sliding(2).foreach {
      case Seq(before, after) => withClue(s"$label went backwards in $values: ")(after should be >= before)
      case _                  => ()
    }

  "SyncProgressMonitor progress within a phase" should
    "not drop when a refreshed accounts estimate is larger than the last one" taggedAs UnitTest in {
      val monitor = new SyncProgressMonitor(classicScheduler)
      monitor.startPhase(AccountRangeSync)

      monitor.incrementAccountsSynced(1_000_000L)
      monitor.updateEstimates(accounts = 10_000_000L) // 10%
      val a = monitor.currentProgress
      monitor.incrementAccountsSynced(100_000L) // 11% against the estimate in hand
      val b = monitor.currentProgress
      monitor.updateEstimates(accounts = 12_000_000L) // 1.1M / 12M = 9% on its own
      val c = monitor.currentProgress
      monitor.incrementAccountsSynced(500_000L)
      val d = monitor.currentProgress // 1.6M / 12M = 13%

      phaseProgressSeries(Seq(a, b, c, d)) shouldBe Seq(10, 11, 11, 13)
      assertNonDecreasing("globalProgress", Seq(a, b, c, d).map(_.globalProgress))
    }

  it should "show the held percentage in the log's Accounts worm, so the line and the worm agree" taggedAs UnitTest in {
    import com.chipprbots.ethereum.blockchain.sync.WormToBrainBar

    val monitor = new SyncProgressMonitor(classicScheduler)
    monitor.startPhase(AccountRangeSync)
    monitor.incrementAccountsSynced(1_100_000L)
    monitor.updateEstimates(accounts = 10_000_000L) // 11%
    monitor.currentProgress.phaseProgress shouldBe 11
    monitor.updateEstimates(accounts = 12_000_000L) // 9% on its own

    val progress = monitor.currentProgress
    progress.phaseProgress shouldBe 11
    val accountsRow = progress.wormBlock.linesIterator.find(_.trim.startsWith("Accounts")).get
    accountsRow should include(WormToBrainBar.renderKnown(0.11))
  }

  it should "never report more than 100%, and the Overall worm must not fall to the stage floor when it would" taggedAs
    UnitTest in {
      val monitor = new SyncProgressMonitor(classicScheduler)
      monitor.startPhase(AccountRangeSync)

      monitor.incrementAccountsSynced(950L)
      monitor.updateEstimates(accounts = 1_000L) // 95%
      val before = monitor.currentProgress
      // The last estimate was only ever above the count when it was computed; the count has since passed it.
      monitor.incrementAccountsSynced(100L)
      val after = monitor.currentProgress

      after.phaseProgress should be <= 100
      after.phaseProgress should be >= before.phaseProgress
      after.globalProgress should be >= before.globalProgress
    }

  it should "keep the storage percentage at completed / total while a large contract streams slots (no completions)" taggedAs
    UnitTest in {
      val monitor = new SyncProgressMonitor(classicScheduler)
      monitor.startPhase(AccountRangeSync)
      monitor.startPhase(ByteCodeAndStorageSync)

      // 1,000 of 100,000 contracts are done, with 100,000 slots between them: the estimate is 10M slots, 1%.
      monitor.incrementStorageSlotsSynced(100_000L)
      reportStorageContracts(monitor, completed = 1_000, total = 100_000)
      val a = monitor.currentProgress
      a.phaseProgress shouldBe 1

      // One giant contract streams 5M slots; no contract completes, so no report arrives. Against the estimate in
      // hand that is 5.1M / 10M = 51%, while 1% of the contracts are done.
      monitor.incrementStorageSlotsSynced(5_000_000L)
      val b = monitor.currentProgress

      // The next small contract completes and the report arrives: the estimate is now 510M slots.
      monitor.incrementStorageSlotsSynced(10L)
      reportStorageContracts(monitor, completed = 1_001, total = 100_000)
      val c = monitor.currentProgress

      withClue("the slots of an unfinished contract are not progress through the contracts: ")(
        b.phaseProgress shouldBe 1
      )
      phaseProgressSeries(Seq(a, b, c)) shouldBe Seq(1, 1, 1)
      assertNonDecreasing("globalProgress", Seq(a, b, c).map(_.globalProgress))
    }

  it should "start a new phase from what that phase's estimate says, and a reset from nothing" taggedAs UnitTest in {
    val monitor = new SyncProgressMonitor(classicScheduler)
    monitor.startPhase(AccountRangeSync)
    monitor.incrementAccountsSynced(800L)
    monitor.updateEstimates(accounts = 1_000L)
    monitor.currentProgress.phaseProgress shouldBe 80

    // The next phase is measured on its own: the accounts phase's 80% is not carried into it.
    monitor.startPhase(ByteCodeAndStorageSync)
    monitor.incrementStorageSlotsSynced(100L)
    reportStorageContracts(monitor, completed = 1, total = 10)
    monitor.currentProgress.phaseProgress shouldBe 10

    // A restart is a genuine restart: it starts from nothing, and does not hold the old percentage.
    monitor.reset()
    val restarted = monitor.currentProgress
    restarted.phaseProgress shouldBe 0
    restarted.estimatedTotalAccounts shouldBe 0L
    restarted.estimatedTotalSlots shouldBe 0L
    restarted.globalProgress shouldBe 0.0
    monitor.startPhase(AccountRangeSync)
    monitor.incrementAccountsSynced(10L)
    monitor.updateEstimates(accounts = 1_000L)
    monitor.currentProgress.phaseProgress shouldBe 1
  }

  it should "report the Overall worm without a fall when a SyncProgress carries an out-of-range phase percentage" taggedAs
    UnitTest in {
      def at(percent: Int): SyncProgress = SyncProgress(
        phase = AccountRangeSync,
        accountsSynced = 0L,
        bytecodesDownloaded = 0L,
        storageSlotsSynced = 0L,
        nodesHealed = 0L,
        elapsedSeconds = 0.0,
        phaseElapsedSeconds = 0.0,
        accountsPerSec = 0.0,
        bytecodesPerSec = 0.0,
        slotsPerSec = 0.0,
        nodesPerSec = 0.0,
        recentAccountsPerSec = 0.0,
        recentBytecodesPerSec = 0.0,
        recentSlotsPerSec = 0.0,
        recentNodesPerSec = 0.0,
        phaseProgress = percent,
        estimatedTotalAccounts = 0L,
        estimatedTotalBytecodes = 0L,
        estimatedTotalSlots = 0L,
        startTime = 0L,
        phaseStartTime = 0L
      )

      assertNonDecreasing("globalProgress", Seq(0, 50, 99, 100, 101, 250).map(at(_).globalProgress))
    }
