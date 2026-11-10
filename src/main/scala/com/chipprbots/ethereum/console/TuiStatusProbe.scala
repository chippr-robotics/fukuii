package com.chipprbots.ethereum.console

import java.time.Clock
import java.time.Instant
import java.util.concurrent.TimeoutException

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Scheduler
import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
import org.apache.pekko.util.Timeout

import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import com.chipprbots.ethereum.blockchain.sync.SyncController
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.SyncPhase
import com.chipprbots.ethereum.blockchain.sync.snap.SyncProgress
import com.chipprbots.ethereum.network.PeerManagerActor

/** Polls the running node for the TUI: handshaked peers from the peer manager, sync status from the sync controller
  * (the same query `net_peerCount` and `eth_syncing` answer), the local best block, and the latest SNAP progress.
  *
  * Both asks start together and share one bounded wait (`wait`, 500 ms), so a poll takes at most one wait however many
  * actors are slow (FR-011). Each source gets its own freshness ([[Sourced]]): a source that misses the wait shows its
  * last answer as Stale, or Unresponsive if it never answered or failed.
  *
  * Runs on the [[TuiProbeThread]] — the blocking wait never touches an actor or dispatcher thread, nor the frame loop.
  * Not thread-safe: one thread polls it, and it keeps the previous snapshot to tell Stale from Unresponsive.
  *
  * @param snapSample
  *   the SNAP monitor's latest progress and when it was recorded, in epoch milliseconds
  *   (`SNAPSyncMetrics.latestSample`): shown while younger than [[TuiStatusProbe.SnapProgressMaxAgeMillis]]
  * @param clock
  *   the time of each snapshot. The TUI ages snapshots against its own clock (`Tui`'s `clock`), so the two must agree:
  *   both default to the system UTC clock
  */
class TuiStatusProbe(
    peerManager: ActorRef[PeerManagerActor.Command],
    syncController: ActorRef[SyncController.Command],
    bestBlockNumber: () => BigInt,
    maxPeers: Int,
    snapSample: () => Option[(SyncProgress, Long)],
    wait: FiniteDuration = TuiStatusProbe.DefaultAskTimeout,
    clock: Clock = Clock.systemUTC()
)(using scheduler: Scheduler)
    extends (() => NodeStatusSnapshot):

  import TuiStatusProbe.*

  // Longer than the wait, so an actor that is merely slow misses the wait (Stale) instead of failing it.
  private given Timeout = Timeout(wait * 2)

  private var previous: Option[NodeStatusSnapshot] = None

  def apply(): NodeStatusSnapshot =
    val peersAsk = settled(peerManager.ask[PeerManagerActor.Peers](PeerManagerActor.GetPeersCmd(_)))
    val syncAsk = settled(
      syncController.ask[SyncProtocol.Status](r => SyncController.WrappedSyncProtocol(SyncProtocol.GetStatus(r)))
    )
    // One wait for both: the asks run concurrently, and whatever has not answered by the deadline missed this poll.
    val deadline = wait.fromNow
    Seq(peersAsk, syncAsk).foreach(ask => Try(Await.ready(ask, deadline.timeLeft.max(Duration.Zero))))

    val now = clock.instant()
    val localBest = Try(bestBlockNumber().toLong).getOrElse(0L)
    val missed = s"no reply within ${wait.toMillis}ms"
    val peers = Sourced.next(previous.map(_.peers), poll(peersAsk)(_.handshaked.size), now, missed)
    val sync = Sourced.next(previous.map(_.sync), poll(syncAsk)(sample(_, localBest, now)), now, missed)
    val snapshot = NodeStatusSnapshot(now, maxPeers, localBest, peers, sync)
    previous = Some(snapshot)
    snapshot

  /** The sync status as the TUI reads it, with the SNAP progress while SNAP is running: a sample of an active phase,
    * young enough to show. "Syncing (SNAP)" needs a sample from the monitor's last two rounds.
    */
  private def sample(status: SyncProtocol.Status, localBest: Long, now: Instant): SyncSample =
    val snap = Option
      .when(status.syncing)(Try(snapSample()).toOption.flatten)
      .flatten
      .map((progress, at) => (progress, Instant.ofEpochMilli(at)))
      .filter((progress, at) =>
        ActiveSnapPhases.contains(progress.phase) && ageMillis(at, now) <= SnapProgressMaxAgeMillis
      )
    val running = snap.exists((_, at) => ageMillis(at, now) <= SnapStatusMaxAgeMillis)
    val (current, best, description) = describe(status, localBest, snapRunning = running)
    SyncSample(current, best, description, snap.map((p, _) => toSnapSyncState(p)), snap.map(_._2))

  private def ageMillis(at: Instant, now: Instant): Long = java.time.Duration.between(at, now).toMillis

object TuiStatusProbe:
  val DefaultAskTimeout: FiniteDuration = 500.millis

  /** How long a SNAP progress sample stays displayable. SyncProgressMonitor samples every 30 s. */
  val SnapProgressMaxAgeMillis: Long = 90_000L

  /** How young a SNAP sample must be to call the sync "Syncing (SNAP)": the monitor's last two rounds of 30 s
    * (`SyncProgressMonitor.startPeriodicLogging`). Two poll intervals would be shorter than one round.
    */
  val SnapStatusMaxAgeMillis: Long = 60_000L

  private val ActiveSnapPhases: Set[SyncPhase] = Set(
    SyncPhase.AccountRangeSync,
    SyncPhase.ByteCodeAndStorageSync,
    SyncPhase.StateHealing,
    SyncPhase.StateValidation,
    SyncPhase.ChainDownloadCompletion
  )

  /** An ask as a future that never fails: its outcome is the value. */
  private def settled[A](ask: Future[A]): Future[Try[A]] = ask.transform(Success(_))(using ExecutionContext.parasitic)

  /** What one ask got by the end of the shared wait. An ask timeout is a miss, not a failure. */
  private[console] def poll[A, B](ask: Future[Try[A]])(f: A => B): Sourced.Poll[B] =
    ask.value.flatMap(_.toOption) match
      case Some(Success(a))                          => Sourced.Poll.Answered(f(a))
      case None | Some(Failure(_: TimeoutException)) => Sourced.Poll.Missed
      case Some(Failure(e)) => Sourced.Poll.Failed(Option(e.getMessage).getOrElse(e.getClass.getSimpleName))

  /** Map a sync status to (current block, best known block or 0 if unknown, description).
    *
    * While SNAP runs the sync controller reports no block target (`SyncController` answers `GetStatus` itself in
    * `runningSnapSync`, with a target of 0), so a 0 target with a SNAP sample in hand is SNAP at work, not a sync
    * waiting for its target (B-044).
    *
    * @param snapRunning
    *   the SNAP monitor has a recent sample of an active phase
    */
  private[console] def describe(
      status: SyncProtocol.Status,
      localBest: Long,
      snapRunning: Boolean
  ): (Long, Long, String) = status match
    case SyncProtocol.Status.Syncing(_, blocks, stateNodes) =>
      val target = blocks.target.toLong
      val current = if blocks.current > 0 then blocks.current.toLong else localBest
      val state = stateNodes.filter(_.nonEmpty).map(p => f" (state ${p.current}%,d / ${p.target}%,d)").getOrElse("")
      val description =
        if target > 0 then s"Syncing$state"
        else if snapRunning then "Syncing (SNAP)"
        else "Syncing (no target yet)"
      (current, target, description)
    case SyncProtocol.Status.SyncDone   => (localBest, localBest, "Synced")
    case SyncProtocol.Status.NotSyncing => (localBest, 0L, "Not syncing")

  private[console] def toSnapSyncState(p: SyncProgress): SnapSyncState =
    SnapSyncState(
      phase = p.phase.toString,
      accountsSynced = p.accountsSynced,
      bytecodesDownloaded = p.bytecodesDownloaded,
      storageSlotsSynced = p.storageSlotsSynced,
      nodesHealed = p.nodesHealed,
      elapsedSeconds = p.elapsedSeconds,
      accountsPerSec = p.accountsPerSec,
      bytecodesPerSec = p.bytecodesPerSec,
      slotsPerSec = p.slotsPerSec,
      nodesPerSec = p.nodesPerSec,
      recentAccountsPerSec = p.recentAccountsPerSec,
      recentBytecodesPerSec = p.recentBytecodesPerSec,
      recentSlotsPerSec = p.recentSlotsPerSec,
      recentNodesPerSec = p.recentNodesPerSec,
      phaseProgress = p.phaseProgress,
      overall = p.globalProgress,
      estimatedTotalAccounts = p.estimatedTotalAccounts,
      estimatedTotalBytecodes = p.estimatedTotalBytecodes,
      estimatedTotalSlots = p.estimatedTotalSlots,
      storageContractsCompleted = p.storageContractsCompleted,
      storageContractsTotal = p.storageContractsTotal,
      chainHeaders = p.chainHeaders,
      chainBodies = p.chainBodies,
      chainReceipts = p.chainReceipts,
      chainTarget = p.chainTarget,
      bytecodeComplete = p.bytecodeComplete,
      phaseElapsedSeconds = p.phaseElapsedSeconds
    )
