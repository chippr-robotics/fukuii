package com.chipprbots.ethereum.console

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Scheduler
import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
import org.apache.pekko.util.Timeout

import scala.concurrent.Await
import scala.concurrent.duration.*
import scala.util.Try

import com.chipprbots.ethereum.blockchain.sync.SyncController
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.SyncPhase
import com.chipprbots.ethereum.blockchain.sync.snap.SyncProgress
import com.chipprbots.ethereum.network.PeerManagerActor

/** Polls the running node for the TUI: handshaked peers from the peer manager, sync status from the sync controller
  * (the same query `net_peerCount` and `eth_syncing` answer), the local best block, and the latest SNAP progress.
  *
  * Runs on the TUI updater thread — the blocking `Await`s never touch an actor or dispatcher thread. A slow or dead
  * actor degrades one field to an "unresponsive" marker instead of freezing the display.
  */
class TuiStatusProbe(
    peerManager: ActorRef[PeerManagerActor.Command],
    syncController: ActorRef[SyncController.Command],
    bestBlockNumber: () => BigInt,
    maxPeers: Int,
    snapProgress: () => Option[SyncProgress],
    askTimeout: FiniteDuration = TuiStatusProbe.DefaultAskTimeout
)(using scheduler: Scheduler)
    extends (() => NodeStatusSnapshot):

  private given Timeout = Timeout(askTimeout)

  def apply(): NodeStatusSnapshot =
    val peers = Try(
      Await.result(peerManager.ask[PeerManagerActor.Peers](PeerManagerActor.GetPeersCmd(_)), askTimeout).handshaked.size
    )
    val sync = Try(
      Await.result(
        syncController.ask[SyncProtocol.Status](r => SyncController.WrappedSyncProtocol(SyncProtocol.GetStatus(r))),
        askTimeout
      )
    )
    val localBest = Try(bestBlockNumber().toLong).getOrElse(0L)

    val (current, best, syncStatus) =
      sync.fold(_ => (localBest, 0L, "Unresponsive (sync controller)"), TuiStatusProbe.describe(_, localBest))

    val snap = sync.toOption
      .filter(_.syncing)
      .flatMap(_ => snapProgress())
      .filter(p => TuiStatusProbe.ActiveSnapPhases.contains(p.phase))
      .map(TuiStatusProbe.toSnapSyncState)

    NodeStatusSnapshot(
      connectionStatus = peers.fold(
        _ => "Unresponsive (peer manager)",
        n => if n > 0 then "Connected" else "Searching for peers"
      ),
      peerCount = peers.getOrElse(0),
      maxPeers = maxPeers,
      currentBlock = current,
      bestBlock = best,
      syncStatus = syncStatus,
      snapSync = snap
    )

object TuiStatusProbe:
  val DefaultAskTimeout: FiniteDuration = 500.millis

  /** How long a SNAP progress sample stays displayable. SyncProgressMonitor samples every 30 s. */
  val SnapProgressMaxAgeMillis: Long = 90_000L

  private val ActiveSnapPhases: Set[SyncPhase] = Set(
    SyncPhase.AccountRangeSync,
    SyncPhase.ByteCodeAndStorageSync,
    SyncPhase.StateHealing,
    SyncPhase.StateValidation,
    SyncPhase.ChainDownloadCompletion
  )

  /** Map a sync status to (current block, best known block or 0 if unknown, description). */
  private[console] def describe(status: SyncProtocol.Status, localBest: Long): (Long, Long, String) = status match
    case SyncProtocol.Status.Syncing(_, blocks, stateNodes) =>
      val target = blocks.target.toLong
      val current = if blocks.current > 0 then blocks.current.toLong else localBest
      val state = stateNodes.filter(_.nonEmpty).map(p => f" (state ${p.current}%,d / ${p.target}%,d)").getOrElse("")
      (current, target, if target > 0 then s"Syncing$state" else "Syncing (no target yet)")
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
      estimatedTotalAccounts = p.estimatedTotalAccounts,
      estimatedTotalBytecodes = p.estimatedTotalBytecodes,
      estimatedTotalSlots = p.estimatedTotalSlots
    )
