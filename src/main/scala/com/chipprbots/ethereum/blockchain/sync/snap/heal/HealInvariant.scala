package com.chipprbots.ethereum.blockchain.sync.snap.heal

import com.chipprbots.ethereum.blockchain.sync.snap.StorageScheme
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.HealingFrontierStorage
import com.chipprbots.ethereum.db.storage.PathNodeStorage

/** The rules of the invariant marker and the retirement of CF `g` (spec 022 D6, D7; data-model.md "Invariant marker").
  *
  * The marker (`snap.heal.invariant`, AppState `s`) says everything present here was written under "present implies
  * complete". It does not say complete. It is stamped on an empty datadir at a SNAP start and at a clean walk
  * completion; a datadir without it gets one repair pass, which is the verification walk healing already runs at every
  * completion, and the marker records how that went. Slice 5 reads it to take that walk off the completion path.
  *
  * Every write here is one synced batch, so no crash can leave half of one.
  */
object HealInvariant:

  /** What a SNAP start found. */
  enum AtSnapStart:

    /** The marker stands: this datadir needs no repair pass. */
    case Stands

    /** The datadir was empty, and is stamped now. */
    case StampedEmpty

    /** No marker, and not empty: this run's clean walk is the repair pass, and stamps. */
    case PreInvariant(reason: String)

    /** The marker cannot hold on this scheme and pruning mode (see [[canHold]]). `clearedOne`: one was found, and
      * removed.
      */
    case CannotHold(clearedOne: Boolean)

  /** Whether "present implies complete" can hold on disk at all. Not on the Hash scheme under `pruning.mode =
    * inmemory`: there the trie store is the node cache, which writes healed nodes to disk one by one as it evicts them,
    * least recently used first, so a crash can leave a present node over an absent child (spec 022 review vault V3, R1,
    * V-N2). The Path scheme is not affected: its heal commits, account phase and storage phase all write to the two
    * Path column families through `PathNodeStorage`, in atomic batches, and never touch the cache, which only the
    * hand-off's export goes through, behind its own barrier (#1581). Hence scheme-aware (V-N2 (4)). Where it cannot
    * hold, the verification walk stays at every start and every completion: that walk is the repair, and there is no
    * second mechanism (V-N2 (3)).
    */
  def canHold(scheme: StorageScheme, trieNodesCachedInMemory: Boolean): Boolean =
    scheme == StorageScheme.Path || !trieNodesCachedInMemory

  /** Run at every SNAP start, before anything of this run is written. The checkpoint key outranks the marker: while it
    * is set, the datadir is pre-invariant whatever else it holds.
    */
  def atSnapStart(appState: AppStateStorage, pathNodes: Option[PathNodeStorage], canHold: Boolean): AtSnapStart =
    val marked = appState.hasSnapHealInvariant()
    if !canHold then
      if marked then appState.clearSnapHealInvariant().commitSync()
      AtSnapStart.CannotHold(clearedOne = marked)
    else
      appState.getCheckpointImportInProgress() match
        case Some(importing) =>
          if marked then appState.clearSnapHealInvariant().commitSync()
          AtSnapStart.PreInvariant(s"a checkpoint import did not finish ($importing)")
        case None if marked => AtSnapStart.Stands
        case None if isEmpty(appState, pathNodes) =>
          appState.stampSnapHealInvariant().commitSync()
          AtSnapStart.StampedEmpty
        case None => AtSnapStart.PreInvariant("no marker, and not an empty datadir")

  /** Whether this datadir is empty for the marker's purposes, conservatively: when in doubt it is not, since a stamp
    * over state an older binary wrote is the one thing that must not happen, and the doubt costs one walk.
    *
    * The hash-keyed store cannot be asked: it holds the genesis trie on every datadir, and a partial checkpoint import
    * from an older binary leaves nodes there and no other trace. So the positive evidence is
    * `isDatadirCreatedUnderInvariant`, written when the genesis is first stored, which a datadir created by an older
    * binary never has. On top of that, nothing may say a sync has been here: each key below is written by a sync before
    * it writes a trie node, or says that it finished; none is written before the first SNAP start.
    * (`BootstrapPivotBlock` and `SyncStartingBlock` are, so they prove nothing.) The Path families are looked at
    * directly: one seek each.
    */
  def isEmpty(appState: AppStateStorage, pathNodes: Option[PathNodeStorage]): Boolean =
    appState.isDatadirCreatedUnderInvariant() &&
      appState.getCheckpointImportInProgress().isEmpty &&
      appState.getBestBlockNumber() == 0 &&
      !appState.isSnapSyncDone() &&
      !appState.isFastSyncDone() &&
      appState.getSnapSyncPivotBlock().isEmpty &&
      appState.getSnapSyncMinPivotBlock().isEmpty &&
      appState.getSnapSyncBootstrapTarget().isEmpty &&
      appState.getSnapSyncStateRoot().isEmpty &&
      appState.getSnapSyncFinalizedRoot().isEmpty &&
      appState.getSnapSyncProgress().isEmpty &&
      appState.getSnapAccountResumeCheckpoint().isEmpty &&
      !appState.isSnapSyncAccountsComplete() &&
      !appState.isSnapSyncStorageComplete() &&
      !appState.isSnapSyncBytecodeComplete() &&
      appState.getSnapSyncStorageFilePath().forall(_.isEmpty) &&
      appState.getSnapSyncCodeHashesPath().forall(_.isEmpty) &&
      pathNodes.forall(_.isEmpty)

  /** The one-time clear of CF `g` (spec 022 D6), at every node start until it has happened. True when it ran.
    *
    * A crash at any step is safe. Before the range delete: nothing has happened, and the next start does it. After it,
    * before the key: the next start does it again, one more tombstone on an empty family. After the key: done. The
    * key's synced write syncs the WAL up to and including itself, and the range delete is in that WAL before it, so a
    * key that survives a power loss implies the delete did. Nothing in the node reads `g` any more, so the worst a lost
    * or failed clear can cost is disk space.
    */
  def retireFrontierOnce(appState: AppStateStorage, frontier: HealingFrontierStorage): Boolean =
    if appState.isHealingFrontierRetired() then false
    else
      frontier.clearAll()
      appState.markHealingFrontierRetired().commitSync()
      true

  /** The operator's `fukuii.snap.clearDoneOnStart`: healing is to run again because what it finished is doubted, so
    * what is present is not vouched for, done or not (D7). One synced write. True when SNAP was marked done.
    */
  def clearDoneOnStart(appState: AppStateStorage): Boolean =
    val wasDone = appState.isSnapSyncDone()
    appState.clearSnapSyncDone().commitSync()
    wasDone
