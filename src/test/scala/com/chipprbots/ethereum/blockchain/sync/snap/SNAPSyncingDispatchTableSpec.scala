package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.actor.testkit.typed.scaladsl.FishingOutcomes
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.util.ByteString

import scala.concurrent.duration.*
import scala.util.Using

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.*
import com.chipprbots.ethereum.blockchain.sync.snap.actors.AccountRangeStats
import com.chipprbots.ethereum.blockchain.sync.snap.actors.ByteCodeCoordinator
import com.chipprbots.ethereum.blockchain.sync.snap.actors.StorageRangeCoordinator
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.p2p.messages.SNAP
import com.chipprbots.ethereum.testing.Tags.*

/** Spec 016 T027: the arm-order oracle for P4a–e. For every `Command` (two samples for the generation-guarded
  * validation commands) and every `SyncPhase` the controller can be in while its behaviour is `syncing`, it records
  * what one message does today:
  *   - `Handled`: some arm of `syncing` (or of the behaviour the message switched to) took it;
  *   - `CatchAll`: it fell through to `syncing`'s "Unhandled message in syncing state" log (DEBUG, read through
  *     [[SnapLogCapture]]);
  *   - `Crashed`: handling it threw and stopped the controller.
  *
  * Each cell runs on a fresh controller, entered into the phase by the routes in `SnapControllerFixture`:
  *   - `AccountRangeSync`: genesis pivot, one snap peer (account, bytecode and storage children);
  *   - `ByteCodeAndStorageSync`: accounts-complete recovery with storage still to download;
  *   - `StateHealing`: accounts-complete recovery with every phase done;
  *   - `StateValidation`: from `StateHealing`, a clean walk (`TrieWalkComplete(0)`) whose finalisation waits for an
  *     outstanding healed account's bytecode, so the controller stays in `StateValidation`;
  *   - `Completed`: storage force-completed under deferred merkleization (#1371 handoff): `finalizeSnapSync` runs, but
  *     `checkAllDownloadsComplete` discards the behaviour it returns, so the controller stays in `syncing` with
  *     `currentPhase = Completed`.
  *
  * Not reachable inside `syncing` today, so not in the table: `ChainDownloadCompletion` (nothing assigns it), `Idle`
  * (`restartSnapSync`/`wakeFromDormant` set it and then return whatever `startSnapSync` returns, which sets a phase
  * before returning `syncing`), and `Dormant` (reachable only where `enterDormantMode`'s behaviour is discarded;
  * `SNAPResetCharacterizationSpec` pins that state, including its catch-all of `DormantWakeUp`).
  *
  * On a mismatch the test prints the whole observed table.
  */
class SNAPSyncingDispatchTableSpec
    extends ScalaTestWithActorTestKit(SnapControllerFixture.config)
    with AnyFlatSpecLike
    with Matchers:

  enum Dispatch:
    case Handled, CatchAll, Crashed
  import Dispatch.*

  private val Phases = Seq("AccountRangeSync", "ByteCodeAndStorageSync", "StateHealing", "StateValidation", "Completed")

  private val statusProbe = testKit.createTestProbe[SyncProtocol.Status]()
  private val progressProbe = testKit.createTestProbe[SyncProgress]()

  private val hash = ByteString(Array.fill(32)(0x5a.toByte))
  private val root = SnapControllerFixture.Root

  /** One sample per Command; the generation-guarded validation commands get a current and a stale one. */
  private val samples: Seq[(String, Command)] = Seq(
    "AccountRangeResponse" -> AccountRangeResponse(SNAP.AccountRange(BigInt(9101), Seq.empty, Seq.empty)),
    "ByteCodesResponse" -> ByteCodesResponse(SNAP.ByteCodes(BigInt(9102), Seq.empty)),
    "StorageRangesResponse" -> StorageRangesResponse(SNAP.StorageRanges(BigInt(9103), Seq.empty, Seq.empty)),
    "TrieNodesResponse" -> TrieNodesResponse(SNAP.TrieNodes(BigInt(9104), Seq.empty)),
    "ChainDownloaderProgress" -> ChainDownloaderProgress(BigInt(1), BigInt(1), BigInt(1), BigInt(2)),
    "ChainDownloaderDone" -> ChainDownloaderDone,
    "HeaderHoldTick" -> HeaderHoldTick,
    "WrappedHandshakedPeers" -> WrappedHandshakedPeers(Map.empty),
    "WrappedPeerDisconnected" -> WrappedPeerDisconnected(PeerId("s0d-table")),
    "Start" -> Start,
    "CLPivotHint" -> CLPivotHint(hash, None),
    "MinPivotBlock" -> MinPivotBlock(BigInt(5)),
    "BootstrapComplete" -> BootstrapComplete(None),
    "PivotBootstrapFailed" -> PivotBootstrapFailed("s0d table"),
    "RetrySnapSyncStart" -> RetrySnapSyncStart,
    "FlushPeerDisconnects" -> FlushPeerDisconnects,
    "RetryPivotRefresh" -> RetryPivotRefresh,
    "RetryBootstrapAtBlock" -> RetryBootstrapAtBlock(BigInt(5)),
    "CheckSnapCapability" -> CheckSnapCapability,
    "TuneRateTracker" -> TuneRateTracker,
    "EvictNonSnapPeers" -> EvictNonSnapPeers,
    "PivotProbeTimeout" -> PivotProbeTimeout(BigInt(9105)),
    "DormantWakeUp" -> DormantWakeUp,
    "DelayedRestart" -> DelayedRestart("s0d table"),
    "AccountRangeProgressCmd" -> AccountRangeProgressCmd(Map.empty),
    "CheckDownloadStagnation" -> CheckDownloadStagnation,
    "AccountCoordinatorProgress" -> AccountCoordinatorProgress(AccountRangeStats(0L, 0L, 0, 0, 0, 0.0, 0L, 0L)),
    "StorageCoordinatorProgress" -> StorageCoordinatorProgress(
      StorageRangeCoordinator.SyncStatistics(0L, 0L, 0, 0, 0, 0L, 0.0)
    ),
    "ByteCodeCoordinatorProgress" -> ByteCodeCoordinatorProgress(ByteCodeCoordinator.ByteCodeProgress(0.0, 0L, 0L)),
    "RequestAccountRanges" -> RequestAccountRanges,
    "RequestByteCodes" -> RequestByteCodes,
    "RequestStorageRanges" -> RequestStorageRanges,
    "RequestTrieNodeHealing" -> RequestTrieNodeHealing,
    "EnsureSnapServerPeersConnected" -> EnsureSnapServerPeersConnected,
    "TrieWalkResult" -> TrieWalkResult(Seq.empty),
    "TrieWalkBatch" -> TrieWalkBatch(Seq.empty),
    "TrieWalkComplete" -> TrieWalkComplete(0),
    "TrieWalkFailed" -> TrieWalkFailed("s0d table"),
    "ValidateAccountTrieResult(current gen)" -> ValidateAccountTrieResult(0L, Right(Seq.empty), 0L),
    "ValidateAccountTrieResult(stale gen)" -> ValidateAccountTrieResult(99L, Right(Seq.empty), 0L),
    "ValidateStorageTriesResult(current gen)" -> ValidateStorageTriesResult(0L, Right(Seq.empty), 0L),
    "ValidateStorageTriesResult(stale gen)" -> ValidateStorageTriesResult(99L, Right(Seq.empty), 0L),
    "ValidationRetry(current gen)" -> ValidationRetry(0L),
    "ValidationRetry(stale gen)" -> ValidationRetry(99L),
    "ScheduledTrieWalk" -> ScheduledTrieWalk,
    "PollHandshakedPeers" -> PollHandshakedPeers,
    "AccountRangeSyncComplete" -> AccountRangeSyncComplete,
    "ByteCodeSyncComplete" -> ByteCodeSyncComplete,
    "StorageRangeSyncComplete" -> StorageRangeSyncComplete,
    "StorageRangeSyncForceCompleted" -> StorageRangeSyncForceCompleted,
    "IncrementalContractData" -> IncrementalContractData(Seq.empty, Seq.empty),
    "StateHealingComplete" -> StateHealingComplete,
    "StateHealingAbandoned" -> StateHealingAbandoned,
    "HealedCodeHashes" -> HealedCodeHashes(Seq(hash)),
    "HealedCodeWaitTimeout" -> HealedCodeWaitTimeout,
    "HealingAllPeersStateless" -> HealingAllPeersStateless,
    "HealingRootUnservable" -> HealingRootUnservable(root),
    "StateValidationComplete" -> StateValidationComplete,
    "GetStatus" -> GetStatus(statusProbe.ref),
    "GetProgress" -> GetProgress(progressProbe.ref),
    "PathPublishProgress" -> PathPublishProgress(1L, 1L),
    "PathPublishDone" -> PathPublishDone(PathToHashExporter.Result(1L, 1L), 1L),
    "PathPublishFailed" -> PathPublishFailed(new RuntimeException("s0d table")),
    "HealingServeRoot" -> HealingServeRoot(BigInt(5), None),
    "PivotStateUnservable" -> PivotStateUnservable(root, "s0d table", 1),
    "ProgressAccountsSynced" -> ProgressAccountsSynced(0L),
    "ProgressAccountsFinalizingTrie" -> ProgressAccountsFinalizingTrie,
    "ProgressAccountsTrieFinalized" -> ProgressAccountsTrieFinalized,
    "AccountTrieFinalized" -> AccountTrieFinalized(root),
    "AccountTrieFinalizationFailed" -> AccountTrieFinalizationFailed("s0d table"),
    "ProgressBytecodesDownloaded" -> ProgressBytecodesDownloaded(0L),
    "ProgressStorageSlotsSynced" -> ProgressStorageSlotsSynced(0L),
    "ProgressNodesHealed" -> ProgressNodesHealed(0L),
    "ProgressAccountEstimate" -> ProgressAccountEstimate(0L),
    "ProgressStorageContracts" -> ProgressStorageContracts(0, 0),
    "StorageBackpressureChanged" -> StorageBackpressureChanged(false),
    "ByteCodeBackpressureChanged" -> ByteCodeBackpressureChanged(false),
    "HealingStagnated" -> HealingStagnated(0L, 0L)
  )

  // ── Expected (today's) table ───────────────────────────────────────────────────────────────────

  private def everywhere(o: Dispatch): Map[String, Dispatch] = Phases.map(_ -> o).toMap
  private def handledOnlyIn(phases: String*): Map[String, Dispatch] =
    Phases.map(p => p -> (if phases.contains(p) then Handled else CatchAll)).toMap

  private val expected: Map[String, Map[String, Dispatch]] =
    samples.map(_._1).map(_ -> everywhere(Handled)).toMap ++ Map(
      // Handled only in `idle`/`bootstrapping`/`dormantRetry`: always the catch-all in `syncing`.
      "Start" -> everywhere(CatchAll),
      "MinPivotBlock" -> everywhere(CatchAll),
      "RetrySnapSyncStart" -> everywhere(CatchAll),
      "DormantWakeUp" -> everywhere(CatchAll),
      // Refresh-side bootstrap replies are guarded by `pendingPivotRefresh.isDefined` (none pending here).
      "BootstrapComplete" -> everywhere(CatchAll),
      "PivotBootstrapFailed" -> everywhere(CatchAll),
      // A second account launch reuses the account coordinator's name (same generation) and throws.
      "CheckSnapCapability" -> everywhere(Handled).updated("AccountRangeSync", Crashed),
      // Guarded on the phase-complete / healed-code flags.
      "ByteCodeSyncComplete" -> handledOnlyIn("AccountRangeSync", "StateValidation"),
      "HealedCodeWaitTimeout" -> handledOnlyIn("StateValidation"),
      "StorageRangeSyncComplete" -> handledOnlyIn("AccountRangeSync", "ByteCodeAndStorageSync"),
      "StorageRangeSyncForceCompleted" -> handledOnlyIn("AccountRangeSync", "ByteCodeAndStorageSync"),
      // Guarded on currentPhase == StateHealing.
      "HealingAllPeersStateless" -> handledOnlyIn("StateHealing"),
      "HealingStagnated" -> handledOnlyIn("StateHealing"),
      "StateHealingAbandoned" -> handledOnlyIn("StateHealing"),
      "HealingRootUnservable" -> handledOnlyIn("StateHealing"),
      "TrieWalkBatch" -> handledOnlyIn("StateHealing"),
      "TrieWalkComplete" -> handledOnlyIn("StateHealing"),
      "TrieWalkResult" -> handledOnlyIn("StateHealing"),
      "ScheduledTrieWalk" -> handledOnlyIn("StateHealing"),
      "TrieWalkFailed" -> handledOnlyIn("StateHealing"),
      // Current-generation validation results are guarded on currentPhase == StateValidation (stale ones are dropped
      // by an unguarded arm first, in every phase).
      "ValidateAccountTrieResult(current gen)" -> handledOnlyIn("StateValidation"),
      "ValidateStorageTriesResult(current gen)" -> handledOnlyIn("StateValidation"),
      "ValidationRetry(current gen)" -> handledOnlyIn("StateValidation"),
      // Progress replies are guarded on the phase that asked.
      "AccountCoordinatorProgress" -> handledOnlyIn("AccountRangeSync"),
      "StorageCoordinatorProgress" -> handledOnlyIn("ByteCodeAndStorageSync"),
      "ByteCodeCoordinatorProgress" -> handledOnlyIn("ByteCodeAndStorageSync")
    )

  // ── Running a cell ─────────────────────────────────────────────────────────────────────────────

  private def enter(f: SnapControllerFixture, phase: String): ActorRef[Command] = phase match
    case "AccountRangeSync"       => f.enterGenesisAccountSync()
    case "ByteCodeAndStorageSync" => f.enterStorageResume()
    case "StateHealing"           => f.enterStateHealing()
    case "StateValidation" =>
      val snap = f.enterStateHealing()
      Using.resource(new SnapLogCapture) { log =>
        snap ! HealedCodeHashes(Seq(ByteString(Array.fill(32)(0x44.toByte))))
        snap ! TrieWalkComplete(0)
        log.awaitLine("[HEAL-CODE] Holding SNAP finalisation")
      }
      f.awaitProcessed(snap)
      snap
    case "Completed" =>
      val snap = f.enterStorageResume(SNAPSyncConfig(deferredMerkleization = true))
      snap ! StorageRangeSyncForceCompleted
      f.parent.fishForMessage(10.seconds) {
        case SnapSyncFinalized(_) => FishingOutcomes.complete
        case _                    => FishingOutcomes.continueAndIgnore
      }
      f.awaitProcessed(snap)
      snap
    case other => fail(s"unknown phase $other")

  private def runCell(phase: String, command: Command): Dispatch =
    val f = new SnapControllerFixture(testKit)
    val snap = enter(f, phase)
    val outcome = Using.resource(new SnapLogCapture) { log =>
      val barrier = testKit.createTestProbe[SyncProgress]()
      snap ! command
      snap ! GetProgress(barrier.ref)
      try
        barrier.receiveMessage(5.seconds)
        if log.contains(s"Unhandled message in syncing state: $command") then CatchAll else Handled
      catch
        case _: AssertionError =>
          testKit.createTestProbe[Any]().expectTerminated(snap, 5.seconds)
          Crashed
    }
    testKit.stop(snap)
    outcome

  private def observe(phase: String): Map[String, Dispatch] =
    samples.map { case (label, command) => label -> runCell(phase, command) }.toMap

  private def render(phase: String, observed: Map[String, Dispatch]): String =
    samples.map(_._1).map(l => s"  $l -> ${observed(l)}").mkString(s"observed in $phase:\n", "\n", "")

  Phases.foreach { phase =>
    "SNAPSyncController `syncing` dispatch (Command × SyncPhase)" should
      s"route every Command as today in $phase" taggedAs UnitTest in {
        val observed = observe(phase)
        val want = samples.map(_._1).map(l => l -> expected(l)(phase)).toMap
        withClue(render(phase, observed) + "\n") {
          observed shouldBe want
        }
      }
  }
