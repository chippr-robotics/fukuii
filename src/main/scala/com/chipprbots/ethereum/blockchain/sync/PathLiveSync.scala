package com.chipprbots.ethereum.blockchain.sync

import org.apache.pekko.util.ByteString

import scala.concurrent.duration.*

import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.pathstate.BlockRef
import com.chipprbots.ethereum.db.storage.pathstate.PathStateStorage
import com.chipprbots.ethereum.db.storage.pathstate.SyncHandoff

/** A sync on the unified state layout (spec 018 C5a part 10): the hand-off each SNAP sync ends with, and the check
  * every route into SNAP asks first ([[SyncController.PathLiveSyncPort]]).
  *
  * @param genesis
  *   the block at height 0, as its state is anchored
  * @param resetRecoveries
  *   runs the clear with no recovery of the state executing or waiting, as the escalation's clear does; false when one
  *   was executing a block and the clear did not run
  * @param stateCleared
  *   what the node keeps of a state that is gone is dropped: the head the fork choice followed, and the escalation's
  *   count
  * @param handedOffNow
  *   a sync handed off: the escalation's count begins with it
  * @param snapPeers
  *   what the snap-peer precondition at SNAP's start counts against (spec 018 C6, T241/T242)
  */
final class PathLiveSync(
    state: PathStateStorage,
    appState: AppStateStorage,
    val handoff: SyncHandoff,
    genesis: () => BlockRef,
    resetRecoveries: (() => Unit) => Boolean,
    stateCleared: () => Unit,
    handedOffNow: () => Unit,
    val checkpointTarget: com.chipprbots.ethereum.blockchain.checkpoint.CheckpointImporter.UnifiedTarget,
    val snapPeers: PathLiveSync.SnapPeerPrecondition = PathLiveSync.SnapPeerPrecondition.Default,
    now: () => Long = () => System.currentTimeMillis()
) extends SyncController.PathLiveSyncPort:
  import SyncController.SnapStartOnGuard
  import SyncController.SnapStartOnGuard.*

  def beforeSnap(): SnapStartOnGuard =
    if appState.isStateResetPending() then
      Refused("the mark of a clear of the state that has not ended is set; a restart finishes the clear")
    else
      state.anchor match
        case None                            => Begin(clearedGenesis = false)
        case Some(held) if held == genesis() =>
          // after the clear, not before (beacon): a fork choice that lands in between would name again a head whose
          // state is then deleted; and the refusals counted so far are of the state that is gone
          def clearNow(): Unit =
            val _ = state.clearGenesisStateForSync(held, now())
            stateCleared()
          if resetRecoveries(() => clearNow()) then Begin(clearedGenesis = true)
          // asked again: regular sync from the genesis is what a guard node does not run (vault, beacon)
          else Wait("a recovery of the state was executing a block, so the genesis state was not cleared")
        case Some(held) =>
          // not a fault: the node followed the chain to this state and regular-syncs on it (B-386)
          HoldsState(held.show)

  def handedOff(): Unit = handedOffNow()

  def holdsOnlyGenesis(): Boolean =
    !appState.isStateResetPending() && state.anchor.contains(genesis()) && !appState.isSnapSyncDone() &&
      appState.getSnapSyncPivotBlock().isEmpty

object PathLiveSync:

  /** Why a node on the unified layout does not start with `-Dfukuii.snap.clearDoneOnStart` set: it re-runs SNAP's
    * healing over the state the node holds, on the hash-keyed store, which this layout never reads.
    */
  def snapRepairRefusal(clearDoneOnStart: Boolean): Option[String] =
    Option.when(clearDoneOnStart)(
      "-Dfukuii.snap.clearDoneOnStart=true cannot run with fukuii.state.path-live = true: it re-runs SNAP's healing " +
        "over the state this node holds, and on the unified state layout a sync never starts over a state. Remove the " +
        "property. A node whose state is at fault gives it up through the escalation."
    )

  /** Why a node on the unified layout does not start to run regular sync from its genesis (the user's decisions of
    * 2026-11-17 and 2026-11-18).
    *
    * On a chain with a terminal total difficulty: with `do-snap-sync` off and no state but the genesis, no sync done
    * and none in progress, the first fork choice is a chain it cannot reach, and the escalation clears the genesis and
    * starts SNAP anyway. So it is refused.
    *
    * On proof of work (ETC, Mordor, a private PoW net) a node follows the chain by its weight from the genesis, block
    * by block, and no fork choice names a head it cannot reach: regular sync from the genesis is taken. What is still
    * refused there is a node that holds no state at all (a SNAP sync cleared the genesis and none began): regular sync
    * has no state to execute the first block on.
    *
    * Test mode does not sync, and is left alone.
    *
    * @param anchor
    *   the block whose state is held, if any
    * @param syncStarted
    *   a SNAP sync is done here, or has begun (it has a pivot)
    * @param proofOfWork
    *   the chain has no terminal total difficulty
    */
  def regularSyncFromGenesisRefusal(
      doSnapSync: Boolean,
      testMode: Boolean,
      anchor: Option[BlockRef],
      syncStarted: Boolean,
      proofOfWork: Boolean
  ): Option[String] =
    if proofOfWork then
      Option.when(!testMode && !doSnapSync && anchor.isEmpty && !syncStarted)(
        "fukuii.state.path-live = true with fukuii.sync.do-snap-sync = false, on a node that holds no state, not even " +
          "its genesis's, and has no sync done or begun: a SNAP sync cleared the genesis state and did not go on, so " +
          "regular sync has no state to execute the first block on. Set fukuii.sync.do-snap-sync = true to sync by " +
          "SNAP, or start from a fresh datadir to regular-sync from the genesis."
      )
    else
      Option.when(!testMode && !doSnapSync && anchor.forall(_.number == 0) && !syncStarted)(
        "fukuii.state.path-live = true with fukuii.sync.do-snap-sync = false, on a node that holds no state above its " +
          "genesis and has no sync done or begun: on the unified state layout a node syncs by SNAP, and regular sync " +
          "from there would be given up at its first fork choice. Set fukuii.sync.do-snap-sync = true, or import a " +
          "chain file first."
      )

  /** What the snap-peer precondition at SNAP's start counts against, on the unified layout (spec 018 C6, T241/T242; the
    * user's decisions of 2026-11-18).
    *
    * @param minSnapPeers
    *   how many snap-capable peers a proof-of-work node at its genesis waits for (`sync.snap-sync.min-snap-peers`)
    * @param powWait
    *   how long it waits for them (`sync.snap-sync.pow-snap-peer-wait`): with none at the end it regular-syncs from its
    *   genesis instead, with some it begins SNAP with them
    * @param declared
    *   the node ids of the peers the operator declared (`static-nodes.json`, `sync.snap-sync.snap-server-peers`): a
    *   declared peer that advertises snap counts like any other, and the line says how many of the count were declared
    *   (EG-12, plan open item 3)
    */
  final case class SnapPeerPrecondition(minSnapPeers: Int, powWait: FiniteDuration, declared: Set[ByteString])

  object SnapPeerPrecondition:
    /** The shipped `min-snap-peers` and `pow-snap-peer-wait`, with nothing declared. */
    val Default: SnapPeerPrecondition = SnapPeerPrecondition(2, 10.minutes, Set.empty)

  /** The peers at SNAP's start: handshaked, those on this chain that advertise snap, and how many of those the operator
    * declared.
    */
  final case class SnapPeerCount(connected: Int, snapCapable: Int, declaredSnapCapable: Int):
    def describe: String =
      s"$snapCapable snap-capable peer(s) on this chain ($declaredSnapCapable of them declared by the operator, " +
        s"static-nodes.json or sync.snap-sync.snap-server-peers), of $connected connected"

  /** The handshaked peers counted: a peer whose fork was not accepted is of another chain, and is not counted. */
  def countSnapPeers(
      peers: Iterable[(Option[ByteString], com.chipprbots.ethereum.network.NetworkPeerManagerActor.PeerInfo)],
      declared: Set[ByteString]
  ): SnapPeerCount =
    val snap = peers.filter((_, info) => info.forkAccepted && info.remoteStatus.supportsSnap)
    SnapPeerCount(peers.size, snap.size, snap.count((nodeId, _) => nodeId.exists(declared.contains)))

  /** What the precondition says at SNAP's start. */
  enum SnapPeerVerdict:
    /** The check before SNAP is asked ([[SyncController.PathLiveSyncPort.beforeSnap]]). */
    case Begin

    /** The peers are counted again in a while. */
    case Wait

    /** Regular sync from the genesis on the unified layout, and no SNAP: the genesis state is left as it is. */
    case RegularSyncFromGenesis

  /** The precondition (the user's decision of 2026-11-18). On a chain with a terminal total difficulty it never waits
    * and never turns a SNAP start into regular sync: it is not asked there. On proof of work, a node that holds only
    * its genesis state, with no SNAP sync done or begun, waits for `minSnapPeers` snap-capable peers; at the end of the
    * wait it begins SNAP with the ones it has, and with none it regular-syncs from its genesis. Any other node (a SNAP
    * sync to resume, an escalation's clear) begins at once: its genesis state is gone, and SNAP's own wait for peers is
    * what follows.
    *
    * @param count
    *   the last count, if one was made
    * @param waitOver
    *   the wait of `powWait` is over
    */
  def snapPeerVerdict(
      count: Option[SnapPeerCount],
      proofOfWork: Boolean,
      holdsOnlyGenesis: Boolean,
      minSnapPeers: Int,
      waitOver: Boolean
  ): SnapPeerVerdict =
    import SnapPeerVerdict.*
    if !proofOfWork || !holdsOnlyGenesis then Begin
    else
      count match
        case Some(c) if c.snapCapable >= minSnapPeers.max(1) => Begin
        case _ if !waitOver                                  => Wait
        case Some(c) if c.snapCapable > 0                    => Begin
        case Some(_)                                         => RegularSyncFromGenesis
        // no count was ever made: what SNAP's own wait does is what is left, as before the precondition
        case None => Begin
