package com.chipprbots.ethereum.db.storage

import scala.util.control.NonFatal

import com.chipprbots.ethereum.utils.Logger

/** Whether basic pruning may delete a trie node by its reference count (#1692).
  *
  * A count is the number of positions that emitted a node. Block import emits once per position, so its counts are
  * exact. A writer that skips a node already present leaves a count too low where a subtree is shared, and a prune then
  * deletes a node another position still reads: SNAP heal and resume, the checkpoint importer, an old fast sync, and a
  * Path publish over a store that still has holes. Such a datadir keeps every trie node until it is re-synced, and
  * still prunes its death rows, snapshot rows, count rows and undo records.
  *
  * One stamp, `state.refcounts.exact`, on both schemes; the rule is one way, and it fails safe:
  *   - The genesis write sets it (`AppStateStorage.markDatadirCreated`) when the genesis counts every position
  *     (`GenesisDataLoader.countsExact`). Nothing sets it again: counts can go negative, so a stamp set after a stretch
  *     without pruning, plus a republish, could meet a live node at zero in a death row.
  *   - It is taken away in a synced write before the first write that could count a node short: a Hash-scheme SNAP
  *     start ([[withdrawAtSnapStart]]), a checkpoint import (`AppStateStorage.beginCheckpointImport`, any scheme), and
  *     the publish of a Path hand-off that is not clean ([[withdrawAtPathPublish]]).
  *   - A datadir an older binary made has none, whatever it holds, so every one keeps its trie nodes.
  *   - A read that fails answers no.
  */
object RefCountTrust extends Logger:

  /** Read at each prune, since SNAP can start in the same process. */
  def nodesMayBeDeleted(appState: AppStateStorage): Boolean =
    try appState.hasRefCountsExact()
    catch
      case NonFatal(e) =>
        log.error("Could not read whether reference counts can be trusted; trie nodes are kept (#1692)", e)
        false

  /** Run at every SNAP start, before anything of the run is written. On the Hash scheme SNAP's writers skip a node that
    * is already there, so the stamp goes in a synced write, and a node on disk from this run implies it is gone. True
    * when it was taken away. The Path scheme keeps it: SNAP writes the path-keyed store, and the hash store is written
    * by the publish, which decides ([[withdrawAtPathPublish]]).
    */
  def withdrawAtSnapStart(appState: AppStateStorage, hashScheme: Boolean): Boolean =
    if hashScheme && appState.hasRefCountsExact() then
      appState.clearRefCountsExact().commitSync()
      true
    else false

  /** Run on the Path scheme before the publish writes anything to the hash store. A hand-off that is clean, with a
    * complete walk or decision on this root and the invariant marker standing, publishes every position once. One that
    * is not, a lazy hand-off, publishes over a path store that has holes: a missing position P whose node also sits at
    * position Q is read through Q's write, so the node is counted once for two live positions. The stamp goes in a
    * synced write then. True when it was taken away.
    */
  def withdrawAtPathPublish(appState: AppStateStorage, handOffClean: Boolean): Boolean =
    if !handOffClean && appState.hasRefCountsExact() then
      appState.clearRefCountsExact().commitSync()
      true
    else false
