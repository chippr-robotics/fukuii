package com.chipprbots.ethereum.blockchain.sync

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
          Refused(
            s"this node holds the state of block ${held.show}, which it followed the chain to, and a SNAP sync does " +
              "not start over it"
          )

  def handedOff(): Unit = handedOffNow()

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

  /** Why a node on the unified layout does not start to run regular sync from its genesis (the user's decision of
    * 2026-11-17): with `do-snap-sync` off and no state but the genesis, no sync done and none in progress, the first
    * fork choice is a chain it cannot reach, and the escalation clears the genesis and starts SNAP anyway. Test mode
    * does not sync, and is left alone.
    *
    * @param anchor
    *   the block whose state is held, if any
    * @param syncStarted
    *   a SNAP sync is done here, or has begun (it has a pivot)
    */
  def regularSyncFromGenesisRefusal(
      doSnapSync: Boolean,
      testMode: Boolean,
      anchor: Option[BlockRef],
      syncStarted: Boolean
  ): Option[String] =
    Option.when(!testMode && !doSnapSync && anchor.forall(_.number == 0) && !syncStarted)(
      "fukuii.state.path-live = true with fukuii.sync.do-snap-sync = false, on a node that holds no state above its " +
        "genesis and has no sync done or begun: on the unified state layout a node syncs by SNAP, and regular sync " +
        "from there would be given up at its first fork choice. Set fukuii.sync.do-snap-sync = true, or import a " +
        "chain file first."
    )
