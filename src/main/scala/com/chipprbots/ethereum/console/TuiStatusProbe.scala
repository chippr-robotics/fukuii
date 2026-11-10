package com.chipprbots.ethereum.console

import java.io.File
import java.lang.management.ManagementFactory
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
import scala.jdk.CollectionConverters.*
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import com.chipprbots.ethereum.blockchain.sync.SyncController
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.SyncPhase
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncMetrics
import com.chipprbots.ethereum.blockchain.sync.snap.SyncProgress
import com.chipprbots.ethereum.network.NetworkMetrics
import com.chipprbots.ethereum.network.Peer
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
  * @param local
  *   what to read on the node itself, without an actor: disk, JVM, discovery table, head timestamp, SNAP counters
  */
class TuiStatusProbe(
    peerManager: ActorRef[PeerManagerActor.Command],
    syncController: ActorRef[SyncController.Command],
    bestBlockNumber: () => BigInt,
    maxPeers: Int,
    snapSample: () => Option[(SyncProgress, Long)],
    wait: FiniteDuration = TuiStatusProbe.DefaultAskTimeout,
    clock: Clock = Clock.systemUTC(),
    local: TuiStatusProbe.LocalSources = TuiStatusProbe.LocalSources()
)(using scheduler: Scheduler)
    extends (() => NodeStatusSnapshot):

  import TuiStatusProbe.*

  // Longer than the wait, so an actor that is merely slow misses the wait (Stale) instead of failing it.
  private given Timeout = Timeout(wait * 2)

  private var previous: Option[NodeStatusSnapshot] = None

  /** The cumulative GC time at the previous poll, and when: the GC share is the difference over the interval. */
  private var previousGc: Option[(Long, Instant)] = None

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
    val peers = Sourced.next(previous.map(_.peers), poll(peersAsk)(_.handshaked.map(tuiPeer)), now, missed)
    val sync = Sourced.next(previous.map(_.sync), poll(syncAsk)(sample(_, localBest, now)), now, missed)
    val snapshot = NodeStatusSnapshot(now, maxPeers, localBest, peers, sync, readLocal(now))
    previous = Some(snapshot)
    snapshot

  /** The node-local readings (research §6): each one that fails is left empty; none fails the poll. */
  private def readLocal(now: Instant): LocalSample =
    def read[A](f: => Option[A]): Option[A] = Try(f).toOption.flatten
    val gcMillis = read(local.gcMillis())
    val gcShare = for
      total <- gcMillis
      (before, at) <- previousGc
      wall = java.time.Duration.between(at, now).toMillis
      if wall > 0
    yield ((total - before).max(0L).toDouble / wall).min(1.0)
    previousGc = gcMillis.map(_ -> now)
    LocalSample(
      diskFree = read(local.dataDir.map(local.usableSpace)),
      jvm = read(local.heap()).map((used, max) => JvmSample(used, max, gcShare)),
      discovery = read(local.discovery()),
      headTimestamp = read(local.headTimestamp()),
      snapCounters = read(local.snapCounters())
    )

  /** The sync status as the TUI reads it, with the SNAP progress while SNAP is running: a sample of an active phase,
    * young enough to show. "Syncing (SNAP)" needs a sample from the monitor's last two rounds. The sample's time is
    * kept at any age: the alert row warns when it is too old to show (§2.3), so the time outlives the sample. One read
    * of the sample, so its progress and its time agree.
    */
  private def sample(status: SyncProtocol.Status, localBest: Long, now: Instant): SyncSample =
    val latest = Option
      .when(status.syncing)(Try(snapSample()).toOption.flatten)
      .flatten
      .map((progress, at) => (progress, Instant.ofEpochMilli(at)))
      .filter((progress, _) => ActiveSnapPhases.contains(progress.phase))
    val snap = latest.filter((_, at) => ageMillis(at, now) <= SnapProgressMaxAgeMillis)
    val running = snap.exists((_, at) => ageMillis(at, now) <= SnapStatusMaxAgeMillis)
    val (current, best, description) = describe(status, localBest, snapRunning = running)
    SyncSample(current, best, description, snap.map((p, _) => toSnapSyncState(p)), latest.map(_._2))

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

  /** What the probe reads on the node itself, each read injectable for tests (spec 027 T065). The defaults read
    * nothing; [[LocalSources.node]] reads the running node.
    *
    * @param dataDir
    *   the datadir, whose file system's free space the disk reading is
    * @param usableSpace
    *   bytes usable on a directory's file system
    * @param heap
    *   the JVM's (used, max) heap bytes
    * @param gcMillis
    *   the JVM's cumulative GC time, all collectors
    * @param headTimestamp
    *   the best block header's timestamp, Unix seconds
    */
  final case class LocalSources(
      dataDir: Option[File] = None,
      usableSpace: File => Long = _.getUsableSpace,
      heap: () => Option[(Long, Long)] = () => None,
      gcMillis: () => Option[Long] = () => None,
      discovery: () => Option[DiscoverySample] = () => None,
      headTimestamp: () => Option[Long] = () => None,
      snapCounters: () => Option[SnapCounters] = () => None
  )

  object LocalSources:

    /** The running node's readings: the datadir's disk, this JVM, `NetworkMetrics`, `SNAPSyncMetrics`, and the best
      * header's timestamp through `headTimestamp`.
      */
    def node(dataDir: Option[File], headTimestamp: () => Option[Long]): LocalSources =
      LocalSources(
        dataDir = dataDir,
        heap = jvmHeap,
        gcMillis = jvmGcMillis,
        discovery = networkDiscovery,
        headTimestamp = headTimestamp,
        snapCounters = snapMetrics
      )

    def jvmHeap(): Option[(Long, Long)] =
      val heap = ManagementFactory.getMemoryMXBean.getHeapMemoryUsage
      Some((heap.getUsed, if heap.getMax > 0 then heap.getMax else Runtime.getRuntime.maxMemory))

    /** The collectors' total time; a collector that cannot tell (-1) adds nothing. */
    def jvmGcMillis(): Option[Long] =
      Some(ManagementFactory.getGarbageCollectorMXBeans.asScala.map(_.getCollectionTime.max(0L)).sum)

    def networkDiscovery(): Option[DiscoverySample] =
      Some(
        DiscoverySample(
          NetworkMetrics.DiscoveredPeersSize.get,
          NetworkMetrics.PendingPeersSize.get,
          NetworkMetrics.BlacklistedPeersSize.get
        )
      )

    def snapMetrics(): Option[SnapCounters] =
      Some(
        SnapCounters(
          accountRequests = SNAPSyncMetrics.accountRangeRequests,
          accountFailures = SNAPSyncMetrics.accountRangeFailures,
          storageRequests = SNAPSyncMetrics.storageRangeRequests,
          storageFailures = SNAPSyncMetrics.storageRangeFailures,
          bytecodeRequests = SNAPSyncMetrics.bytecodeRequests,
          bytecodeFailures = SNAPSyncMetrics.bytecodeFailures,
          healingRequests = SNAPSyncMetrics.healingRequests,
          healingFailures = SNAPSyncMetrics.healingFailures,
          timeouts = SNAPSyncMetrics.requestTimeouts,
          snapCapablePeers = SNAPSyncMetrics.snapCapablePeers,
          blacklisted = SNAPSyncMetrics.peersBlacklisted
        )
      )

  /** A handshaked peer as the TUI shows it: `GetPeersCmd`'s id, address, direction and connection time. */
  private[console] def tuiPeer(p: Peer): TuiPeer =
    val address = p.remoteAddress
    val host = Option(address.getAddress).fold(address.getHostString)(_.getHostAddress)
    TuiPeer(p.id.value, s"$host:${address.getPort}", p.incomingConnection, Instant.ofEpochMilli(p.createTimeMillis))

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
