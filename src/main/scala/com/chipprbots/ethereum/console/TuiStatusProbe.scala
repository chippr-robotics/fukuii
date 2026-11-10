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
import com.chipprbots.ethereum.blockchain.sync.regular.RegularSyncMetrics
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.SyncPhase
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncMetrics
import com.chipprbots.ethereum.blockchain.sync.snap.SyncProgress
import com.chipprbots.ethereum.network.NetworkMetrics
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerManagerActor
import com.chipprbots.ethereum.network.PeerScoreSnapshot
import com.chipprbots.ethereum.transactions.PendingTransactionsManager
import com.chipprbots.ethereum.utils.Logger

/** Polls the running node for the TUI: handshaked peers from the peer manager, sync status from the sync controller
  * (the same query `net_peerCount` and `eth_syncing` answer), each peer's client id and best block from the network
  * peer manager, the txpool, the local best block, and the latest SNAP progress.
  *
  * All asks start together and share one bounded wait (`wait`, 500 ms), so a poll takes at most one wait however many
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
  *   what to read on the node itself, without an actor: disk, JVM, discovery table, head timestamp, SNAP counters, peer
  *   scores, the last import
  * @param networkPeerManager
  *   asked `GetHandshakedPeersCmd` for each peer's client id and best block; None asks nothing
  * @param txPool
  *   asked `GetPendingTransactionsReq` for the txpool's size and blob count; None asks nothing
  */
class TuiStatusProbe(
    peerManager: ActorRef[PeerManagerActor.Command],
    syncController: ActorRef[SyncController.Command],
    bestBlockNumber: () => BigInt,
    maxPeers: Int,
    snapSample: () => Option[(SyncProgress, Long)],
    wait: FiniteDuration = TuiStatusProbe.DefaultAskTimeout,
    clock: Clock = Clock.systemUTC(),
    local: TuiStatusProbe.LocalSources = TuiStatusProbe.LocalSources(),
    networkPeerManager: Option[ActorRef[NetworkPeerManagerActor.Command]] = None,
    txPool: Option[ActorRef[PendingTransactionsManager.Command]] = None
)(using scheduler: Scheduler)
    extends (() => NodeStatusSnapshot)
    with Logger:

  import TuiStatusProbe.*

  // Longer than the wait, so an actor that is merely slow misses the wait (Stale) instead of failing it.
  private given Timeout = Timeout(wait * 2)

  private var previous: Option[NodeStatusSnapshot] = None

  /** The cumulative GC time at the previous poll, and when: the GC share is the difference over the interval. */
  private var previousGc: Option[(Long, Instant)] = None

  /** The local readings that have failed since they last succeeded: each failure is logged once, at DEBUG. */
  private var failingReads: Set[String] = Set.empty

  def apply(): NodeStatusSnapshot =
    val peersAsk = settled(peerManager.ask[PeerManagerActor.Peers](PeerManagerActor.GetPeersCmd(_)))
    val syncAsk = settled(
      syncController.ask[SyncProtocol.Status](r => SyncController.WrappedSyncProtocol(SyncProtocol.GetStatus(r)))
    )
    val detailsAsk = networkPeerManager.map(ref =>
      settled(ref.ask[NetworkPeerManagerActor.HandshakedPeers](NetworkPeerManagerActor.GetHandshakedPeersCmd(_)))
    )
    val txpoolAsk = txPool.map(ref =>
      settled(
        ref.ask[PendingTransactionsManager.PendingTransactionsResponse](
          PendingTransactionsManager.GetPendingTransactionsReq(_)
        )
      )
    )
    // One wait for all: the asks run concurrently, and whatever has not answered by the deadline missed this poll.
    // The node-local readings are taken meanwhile, so they add nothing to the poll unless they outlast the wait.
    val deadline = wait.fromNow
    val localSample = readLocal(clock.instant())
    (Seq(peersAsk, syncAsk) ++ detailsAsk ++ txpoolAsk).foreach(ask =>
      Try(Await.ready(ask, deadline.timeLeft.max(Duration.Zero)))
    )

    val now = clock.instant()
    val localBest = Try(bestBlockNumber().toLong).getOrElse(0L)
    val missed = s"no reply within ${wait.toMillis}ms"
    val peers = Sourced.next(previous.map(_.peers), poll(peersAsk)(_.handshaked.map(tuiPeer)), now, missed)
    val sync = Sourced.next(previous.map(_.sync), poll(syncAsk)(sample(_, localBest, now)), now, missed)
    val details =
      detailsAsk.map(ask => Sourced.next(previous.flatMap(_.details), poll(ask)(peerDetails), now, missed))
    val txpool = txpoolAsk.map(ask => Sourced.next(previous.flatMap(_.txpool), poll(ask)(txpoolSample), now, missed))
    val snapshot = NodeStatusSnapshot(now, maxPeers, localBest, peers, sync, localSample, details, txpool)
    previous = Some(snapshot)
    snapshot

  /** The node-local readings (research §6): each one that fails is left empty, and logged at DEBUG the first time; none
    * fails the poll.
    */
  private def readLocal(at: Instant): LocalSample =
    def read[A](name: String)(f: => Option[A]): Option[A] =
      Try(f) match
        case Success(value) =>
          failingReads -= name
          value
        case Failure(e) =>
          if !failingReads.contains(name) then
            log.debug("TUI could not read {}: {}", name, Option(e.getMessage).getOrElse(e.toString))
          failingReads += name
          None
    val gcMillis = read("the GC time")(local.gcMillis())
    val gcShare = for
      total <- gcMillis
      (before, previousAt) <- previousGc
      wall = java.time.Duration.between(previousAt, at).toMillis
      if wall > 0
    yield ((total - before).max(0L).toDouble / wall).min(1.0)
    previousGc = gcMillis.map(_ -> at)
    LocalSample(
      diskFree = read("the disk free")(local.dataDir.map(local.usableSpace)),
      jvm = read("the heap")(local.heap()).map((used, max) => JvmSample(used, max, gcShare)),
      discovery = read("the discovery counts")(local.discovery()),
      headTimestamp = read("the head timestamp")(local.headTimestamp()),
      snapCounters = read("the SNAP counters")(local.snapCounters()),
      snapPivot = read("the SNAP pivot")(local.snapPivot()),
      peerScores = read("the peer scores")(local.peerScores()),
      lastImport = read("the last import")(local.lastImport())
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
    val fresh = snap.exists((_, at) => ageMillis(at, now) <= SnapStatusMaxAgeMillis)
    val (current, best, description) = describe(status, localBest, snapSampleFresh = fresh)
    SyncSample(
      current,
      best,
      description,
      snap.map((p, _) => toSnapSyncState(p)),
      latest.map(_._2),
      snapRunning = latest.exists((_, at) => ageMillis(at, now) <= SnapRunningMaxAgeMillis)
    )

  private def ageMillis(at: Instant, now: Instant): Long = java.time.Duration.between(at, now).toMillis

object TuiStatusProbe:
  val DefaultAskTimeout: FiniteDuration = 500.millis

  /** How long a SNAP progress sample stays displayable. SyncProgressMonitor samples every 30 s. */
  val SnapProgressMaxAgeMillis: Long = 90_000L

  /** How long SNAP counts as running after its latest sample of a running phase: ten of the monitor's 30 s rounds.
    *
    * The monitor samples every 30 s while SNAP runs and publishes a final `Completed` sample when it finishes, so a
    * sample that stops arriving means SNAP stopped without one, as an in-process abort does before regular sync takes
    * over. Between 90 s and this age the SNAP rows stay on screen, marked with their age, and the alert row warns;
    * after it, both go. The sync controller's block target is not consulted.
    */
  val SnapRunningMaxAgeMillis: Long = 300_000L

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
      snapCounters: () => Option[SnapCounters] = () => None,
      snapPivot: () => Option[Long] = () => None,
      peerScores: () => Option[PeerScores] = () => None,
      lastImport: () => Option[ImportSample] = () => None
  )

  object LocalSources:

    /** The running node's readings: the datadir's disk, this JVM, `NetworkMetrics`, `SNAPSyncMetrics` (counters and
      * pivot), and the best header's timestamp through `headTimestamp`.
      */
    def node(dataDir: Option[File], headTimestamp: () => Option[Long]): LocalSources =
      LocalSources(
        dataDir = dataDir,
        heap = jvmHeap,
        gcMillis = jvmGcMillis,
        discovery = networkDiscovery,
        headTimestamp = headTimestamp,
        snapCounters = snapMetrics,
        snapPivot = snapPivotBlock,
        peerScores = networkScores,
        lastImport = regularSyncImport
      )

    /** The peer scores slice 4a last published to `NetworkMetrics` (Q5: the export, not the scorer). */
    def networkScores(): Option[PeerScores] = TuiStatusProbe.peerScores(NetworkMetrics.peerScores)

    /** The last import regular sync timed (`RegularSyncMetrics`, C-7). */
    def regularSyncImport(): Option[ImportSample] =
      RegularSyncMetrics.lastImport.map(last => ImportSample(last.blocks, java.time.Duration.ofNanos(last.nanos)))

    /** The SNAP pivot's block number, once SNAP has chosen one. */
    def snapPivotBlock(): Option[Long] = Some(SNAPSyncMetrics.pivotBlockNumber).filter(_ > 0)

    def jvmHeap(): Option[(Long, Long)] =
      val heap = ManagementFactory.getMemoryMXBean.getHeapMemoryUsage
      Some((heap.getUsed, if heap.getMax > 0 then heap.getMax else Runtime.getRuntime.maxMemory))

    /** The pausing collectors' total time; a collector that cannot tell (-1) adds nothing. */
    def jvmGcMillis(): Option[Long] =
      Some(
        pausingGcMillis(
          ManagementFactory.getGarbageCollectorMXBeans.asScala.toSeq.map(b => b.getName -> b.getCollectionTime)
        )
      )

    /** The collection time of the collectors that pause the application. G1's `G1 Concurrent GC` (and ZGC's and
      * Shenandoah's `… Cycles` beans) count the wall time of work that runs beside it, which is not a pause.
      */
    private[console] def pausingGcMillis(collectors: Seq[(String, Long)]): Long =
      collectors.collect {
        case (name, millis) if !name.contains("Concurrent") && !name.contains("Cycles") => millis.max(0L)
      }.sum

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

  /** A published score snapshot by peer id; None before slice 4a's scorer has published one. */
  def peerScores(snapshot: PeerScoreSnapshot): Option[PeerScores] =
    Option.when(snapshot.takenAt != PeerScoreSnapshot.empty.takenAt || snapshot.scores.nonEmpty)(
      PeerScores(snapshot.takenAt, snapshot.scores.map((id, score) => id.value -> score))
    )

  /** Each handshaked peer's client id, sanitised as it is read (C-16), and best block, by peer id. */
  private[console] def peerDetails(handshaked: NetworkPeerManagerActor.HandshakedPeers): Map[String, PeerDetails] =
    handshaked.peers.map { (peer, info) =>
      peer.id.value -> PeerDetails(
        Sanitize.line(info.remoteStatus.remoteClientId),
        info.maxBlockNumber.min(BigInt(Long.MaxValue)).toLong
      )
    }

  /** The pending transactions, and how many carry a blob sidecar: the sidecars the pool holds, one per blob transaction
    * (`blobTxNetworkBytes`, research §6).
    */
  private[console] def txpoolSample(response: PendingTransactionsManager.PendingTransactionsResponse): TxpoolSample =
    TxpoolSample(response.pendingTransactions.size, response.blobTxNetworkBytes.size)

  /** An ask as a future that never fails: its outcome is the value. */
  private def settled[A](ask: Future[A]): Future[Try[A]] = ask.transform(Success(_))(using ExecutionContext.parasitic)

  /** The longest failure reason the TUI shows. */
  val MaxReasonChars: Int = 120

  /** A failure as the alert row shows it: its message (or its class), through the one sanitiser for text from outside
    * the node ([[Sanitize.line]]: an exception message can carry anything, peer data included), at most
    * [[MaxReasonChars]] code points.
    */
  private[console] def reason(e: Throwable): String =
    Sanitize.line(Option(e.getMessage).filter(_.nonEmpty).getOrElse(e.getClass.getSimpleName), MaxReasonChars)

  /** What one ask got by the end of the shared wait. An ask timeout is a miss, not a failure. */
  private[console] def poll[A, B](ask: Future[Try[A]])(f: A => B): Sourced.Poll[B] =
    ask.value.flatMap(_.toOption) match
      case Some(Success(a))                          => Sourced.Poll.Answered(f(a))
      case None | Some(Failure(_: TimeoutException)) => Sourced.Poll.Missed
      case Some(Failure(e))                          => Sourced.Poll.Failed(reason(e))

  /** Map a sync status to (current block, best known block or 0 if unknown, description).
    *
    * While SNAP runs the sync controller reports no block target (`SyncController` answers `GetStatus` itself in
    * `runningSnapSync`, with a target of 0), so a 0 target with a SNAP sample in hand is SNAP at work, not a sync
    * waiting for its target (B-044).
    *
    * @param snapSampleFresh
    *   the SNAP monitor has a sample of an active phase from its last two rounds ([[SnapStatusMaxAgeMillis]]): enough
    *   to say "Syncing (SNAP)". Not [[SyncSample.snapRunning]], whose window is longer
    */
  private[console] def describe(
      status: SyncProtocol.Status,
      localBest: Long,
      snapSampleFresh: Boolean
  ): (Long, Long, String) = status match
    case SyncProtocol.Status.Syncing(_, blocks, stateNodes) =>
      val target = blocks.target.toLong
      val current = if blocks.current > 0 then blocks.current.toLong else localBest
      val state = stateNodes.filter(_.nonEmpty).map(p => f" (state ${p.current}%,d / ${p.target}%,d)").getOrElse("")
      val description =
        if target > 0 then s"Syncing$state"
        else if snapSampleFresh then "Syncing (SNAP)"
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
