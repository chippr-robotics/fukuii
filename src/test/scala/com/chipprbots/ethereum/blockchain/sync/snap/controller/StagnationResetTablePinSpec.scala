package com.chipprbots.ethereum.blockchain.sync.snap.controller

import org.apache.pekko.actor.testkit.typed.scaladsl.BehaviorTestKit
import org.apache.pekko.actor.typed.scaladsl.Behaviors

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncConfig
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.Command
import com.chipprbots.ethereum.testing.Tags.UnitTest

/** Spec 016 restructure slice (CQ-SNAP-016-20): pin of `StagnationWatchdog.resetStagnationClocks(reason)`, the
  * per-reason stagnation-clock reset table. For each reason it asserts exactly which watchdog fields are reset, and
  * that every other one keeps its value. The sets are today's inline resets, moved behind one call; the gaps #1551
  * reports are pinned here as current behaviour (the bytecode clocks on the accounts-complete recovery, and the two
  * stall counters on every reason). A #1551 fix changes one row of the table and the matching row below, on purpose.
  */
class StagnationResetTablePinSpec extends AnyFlatSpec with Matchers:

  import StagnationWatchdog.ResetReason
  import StagnationWatchdog.ResetReason.*

  private type Module = StubStagnationWatchdogState & StagnationWatchdog

  /** The module on its stub; the kit only provides a real `ctx` and timers, which the reset table never touches. */
  private def module(): Module =
    var captured: Module = null
    BehaviorTestKit(Behaviors.setup[Command] { ctx =>
      Behaviors.withTimers { timers =>
        captured = new StubStagnationWatchdogState(ctx, timers, SNAPSyncConfig()) with StagnationWatchdog
        Behaviors.ignore
      }
    })
    captured

  private val AllFields: Set[String] = Set(
    "lastStorageProgressMs",
    "lastBytecodeProgressMs",
    "lastBytecodeProgressCount",
    "lastAccountProgressMs",
    "lastAccountTasksCompleted",
    "lastAccountsDownloaded",
    "storageTailBaseline",
    "storageStagnationRefreshAttempted",
    "lastPivotRestartMs",
    "consecutiveAccountStallRefreshes"
  )

  /** The table as pinned today: reason -> the fields it resets. Every other field of `AllFields` is left alone. */
  private val ResetTable: Map[ResetReason, Set[String]] = Map(
    AccountLaunch -> Set("lastAccountProgressMs", "lastAccountTasksCompleted", "lastAccountsDownloaded"),
    AccountsCompleteRecovery -> Set("lastStorageProgressMs", "storageTailBaseline"),
    StoragePhaseEntry -> Set(
      "lastStorageProgressMs",
      "storageTailBaseline",
      "lastBytecodeProgressMs",
      "lastBytecodeProgressCount"
    ),
    PivotRefreshHeaderFetch -> Set("lastAccountProgressMs"),
    PivotRefreshCommit -> Set("lastAccountProgressMs"),
    BootstrapRetryAtBlock -> Set("lastAccountProgressMs")
  )

  private val OldBaseline = SNAPSyncController.StorageTailBaseline(lowWork = 7, sinceMs = 8L, staleFailuresAtLow = 9L)

  /** Every field set to a value no reset writes. */
  private def seed(m: Module): Unit =
    m.lastStorageProgressMs = 1L
    m.lastBytecodeProgressMs = 2L
    m.lastBytecodeProgressCount = 3L
    m.lastAccountProgressMs = 4L
    m.lastAccountTasksCompleted = 5
    m.lastAccountsDownloaded = 6L
    m.storageTailBaseline = OldBaseline
    m.storageStagnationRefreshAttempted = true
    m.lastPivotRestartMs = 10L
    m.consecutiveAccountStallRefreshes = 11

  private def snapshot(m: Module): Map[String, Matchable] = Map(
    "lastStorageProgressMs" -> m.lastStorageProgressMs,
    "lastBytecodeProgressMs" -> m.lastBytecodeProgressMs,
    "lastBytecodeProgressCount" -> m.lastBytecodeProgressCount,
    "lastAccountProgressMs" -> m.lastAccountProgressMs,
    "lastAccountTasksCompleted" -> m.lastAccountTasksCompleted,
    "lastAccountsDownloaded" -> m.lastAccountsDownloaded,
    "storageTailBaseline" -> m.storageTailBaseline,
    "storageStagnationRefreshAttempted" -> m.storageStagnationRefreshAttempted,
    "lastPivotRestartMs" -> m.lastPivotRestartMs,
    "consecutiveAccountStallRefreshes" -> m.consecutiveAccountStallRefreshes
  )

  /** Run `reason` on a seeded module: (fields before, fields after, wall clock before, wall clock after). */
  private def runReset(reason: ResetReason): (Map[String, Matchable], Map[String, Matchable], Long, Long) =
    val m = module()
    seed(m)
    val before = snapshot(m)
    val t0 = System.currentTimeMillis()
    m.resetStagnationClocks(reason)
    val t1 = System.currentTimeMillis()
    (before, snapshot(m), t0, t1)

  /** The value a reset writes into `field`: a fresh wall-clock time, a zero count or a fresh tail baseline. */
  private def resetValueOk(field: String, value: Matchable, t0: Long, t1: Long): Boolean =
    (field, value) match
      case ("lastAccountTasksCompleted", v: Int)  => v == 0
      case ("lastAccountsDownloaded", v: Long)    => v == 0L
      case ("lastBytecodeProgressCount", v: Long) => v == 0L
      case ("storageTailBaseline", v: SNAPSyncController.StorageTailBaseline) =>
        v.lowWork == Int.MaxValue && v.staleFailuresAtLow == 0L && v.sinceMs >= t0 && v.sinceMs <= t1
      case (_, v: Long) => v >= t0 && v <= t1
      case _            => false

  "StagnationWatchdog.resetStagnationClocks" should "have exactly the six reasons of today's six call sites" taggedAs UnitTest in {
    ResetReason.values.toSet shouldBe ResetTable.keySet
    ResetReason.values.toSet shouldBe Set(
      AccountLaunch,
      AccountsCompleteRecovery,
      StoragePhaseEntry,
      PivotRefreshHeaderFetch,
      PivotRefreshCommit,
      BootstrapRetryAtBlock
    )
  }

  ResetReason.values.foreach { reason =>
    val resets = ResetTable(reason)
    val kept = AllFields -- resets
    it should s"for $reason, reset exactly ${resets.toSeq.sorted.mkString(", ")} and keep " +
      s"${kept.toSeq.sorted.mkString(", ")}" taggedAs UnitTest in {
        val (before, after, t0, t1) = runReset(reason)
        val changed = AllFields.filter(f => before(f) != after(f))
        changed shouldBe resets
        resets.foreach(f => withClue(s"$f = ${after(f)}: ")(resetValueOk(f, after(f), t0, t1) shouldBe true))
        kept.foreach(f => withClue(s"$f: ")(after(f) shouldBe before(f)))
      }
  }

  it should "not reset the bytecode clocks on the accounts-complete recovery (#1551 item 1, pinned as today)" taggedAs UnitTest in {
    val (before, after, _, _) = runReset(AccountsCompleteRecovery)
    after("lastBytecodeProgressMs") shouldBe before("lastBytecodeProgressMs")
    after("lastBytecodeProgressCount") shouldBe before("lastBytecodeProgressCount")
  }

  it should "not clear storageStagnationRefreshAttempted for any reason (#1551 item 2, pinned as today)" taggedAs UnitTest in {
    ResetReason.values.foreach { reason =>
      val (_, after, _, _) = runReset(reason)
      withClue(s"$reason: ")(after("storageStagnationRefreshAttempted") shouldBe true)
    }
  }

  it should "not reset consecutiveAccountStallRefreshes for any reason (#1551 item 3, pinned as today)" taggedAs UnitTest in {
    ResetReason.values.foreach { reason =>
      val (_, after, _, _) = runReset(reason)
      withClue(s"$reason: ")(after("consecutiveAccountStallRefreshes") shouldBe 11)
    }
  }
