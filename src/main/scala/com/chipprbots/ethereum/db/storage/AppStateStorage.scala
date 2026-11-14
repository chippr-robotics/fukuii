package com.chipprbots.ethereum.db.storage

import java.math.BigInteger

import org.apache.pekko.util.ByteString

import scala.collection.immutable.ArraySeq

import com.chipprbots.ethereum.db.dataSource.DataSource
import com.chipprbots.ethereum.db.dataSource.DataSourceBatchUpdate
import com.chipprbots.ethereum.db.storage.AppStateStorage.*
import com.chipprbots.ethereum.domain.appstate.BlockInfo
import com.chipprbots.ethereum.utils.Hex

/** This class is used to store app state variables Key: see AppStateStorage.Keys Value: stored string value
  */
class AppStateStorage(val dataSource: DataSource) extends TransactionalKeyValueStorage[Key, Value]:

  val namespace: IndexedSeq[Byte] = Namespaces.AppStateNamespace
  def keySerializer: Key => IndexedSeq[Byte] = k =>
    ArraySeq.unsafeWrapArray(k.getBytes(StorageStringCharset.UTF8Charset))

  def keyDeserializer: IndexedSeq[Byte] => Key = k => new String(k.toArray, StorageStringCharset.UTF8Charset)
  def valueSerializer: String => IndexedSeq[Byte] = k =>
    ArraySeq.unsafeWrapArray(k.getBytes(StorageStringCharset.UTF8Charset))
  def valueDeserializer: IndexedSeq[Byte] => String = (valueBytes: IndexedSeq[Byte]) =>
    new String(valueBytes.toArray, StorageStringCharset.UTF8Charset)

  def getBestBlockNumber(): BigInt =
    getBigInt(Keys.BestBlockNumber)

  def getBestBlockInfo(): BlockInfo =
    BlockInfo(
      get(Keys.BestBlockHash).map(v => ByteString(Hex.decode(v))).getOrElse(ByteString.empty),
      getBigInt(Keys.BestBlockNumber)
    )

  def putBestBlockInfo(b: BlockInfo): DataSourceBatchUpdate =
    put(Keys.BestBlockNumber, b.number.toString)
      .and(put(Keys.BestBlockHash, Hex.toHexString(b.hash.toArray)))

  def putBestBlockNumber(bestBlockNumber: BigInt): DataSourceBatchUpdate =
    put(Keys.BestBlockNumber, bestBlockNumber.toString)

  /** The block whose state the position-keyed trie store holds (spec 018 data-model 5.1), or `None` when the datadir
    * has no such anchor. A stored value this build cannot read throws: an unreadable anchor is not an absent one.
    */
  def getStateAnchor(): Option[pathstate.BlockRef] = get(Keys.StateAnchor).map(pathstate.StateAnchor.decode)

  /** The update that sets the state anchor. It belongs in the same batch as the rows it describes, and that batch is
    * written as a move of the state's clock (`pathstate.StateMoveLog.inFlight`), which is why only the path state
    * storage may build it. An anchor written past the clock would leave views of a block the disk no longer holds
    * looking current, and every row they then met would be called corrupt (`AppStateAnchorRatchetSpec`).
    */
  private[storage] def putStateAnchor(block: pathstate.BlockRef): DataSourceBatchUpdate =
    put(Keys.StateAnchor, pathstate.StateAnchor.encode(block))

  /** The layout stamp of this datadir (spec 018 data-model 5.2): present, with the one value this build knows, when its
    * state lives in the position-keyed rows under a state anchor. A datadir of the hash layout has none.
    */
  def getStateLayout(): Option[String] = get(Keys.StateLayout)

  /** The update that stamps the datadir as the unified layout's. It belongs in the batch that first gives the datadir a
    * state anchor, which only the path state storage builds.
    */
  private[storage] def putStateLayout(): DataSourceBatchUpdate =
    put(Keys.StateLayout, pathstate.StateLayout.PathLive1)

  /** The height the changeset records have been pruned up to (spec 018 data-model 4.5): every height below it has had
    * its records deleted, or never had one. `None` on a datadir no block has been committed to in place. The records
    * cannot be scanned for their lowest height (data-model 4.1), so this is the only thing that knows it.
    */
  def getChangesetPrunedTo(): Option[BigInt] = get(Keys.StateChangesetPrunedTo).map(BigInt(_))

  /** The removal of everything that says this datadir holds a state on the unified layout: the anchor, the layout stamp
    * and the count of pruned changeset records. It belongs in the batch that deletes every row and every record, and
    * nowhere else: [[pathstate.PathStateStorage.destroyStateForNewGenesis]], which only test mode may call.
    */
  private[storage] def removeStateMarks(): DataSourceBatchUpdate =
    update(
      Seq(Keys.StateAnchor, Keys.StateOrigin, Keys.StateLayout, Keys.StateChangesetPrunedTo, Keys.StateFinalized),
      Nil
    )

  /** The finalized block as the consensus client last named it with a fork choice this node followed (spec 018 C4d part
    * 1): what retention keeps above, and what a move of the head must not reverse, across a restart. `None` before one
    * was named, and on a datadir of the hash layout.
    */
  def getStateFinalized(): Option[pathstate.FinalizedBlock] =
    get(Keys.StateFinalized).map(pathstate.FinalizedBlock.decode)

  /** The update that sets [[getStateFinalized]]. It belongs in the batch of the move of the head that follows the fork
    * choice that named the block, which only the path state storage builds: a fork choice that was refused names
    * nothing.
    */
  private[storage] def putStateFinalized(block: pathstate.FinalizedBlock): DataSourceBatchUpdate =
    put(Keys.StateFinalized, pathstate.FinalizedBlock.encode(block))

  /** The block this datadir's state on the unified layout began at (spec 018 plan.md "C4b: the move core"): its
    * genesis, or the block a sync or an imported checkpoint left the state of. No state before it was ever here, so no
    * move of the head goes below it. `None` on a datadir of the hash layout, and on one whose anchor was made before
    * this was kept.
    */
  def getStateOrigin(): Option[pathstate.BlockRef] = get(Keys.StateOrigin).map(pathstate.StateAnchor.decode)

  /** The update that sets [[getStateOrigin]]. It belongs in the batch that gives the datadir a state anchor where it
    * had none, which only the path state storage builds.
    */
  private[storage] def putStateOrigin(block: pathstate.BlockRef): DataSourceBatchUpdate =
    put(Keys.StateOrigin, pathstate.StateAnchor.encode(block))

  /** The update that moves [[getChangesetPrunedTo]]. It belongs in the batch that deletes the records below it, which
    * is the canonical commit's.
    */
  private[storage] def putChangesetPrunedTo(height: BigInt): DataSourceBatchUpdate =
    put(Keys.StateChangesetPrunedTo, height.toString)

  /** Whether fast sync finished on this node. Fast sync was removed and nothing in the node sets this flag any more; a
    * database that fast sync completed earlier still carries it. `SyncController` reads it in two places: to tell a
    * stranded fast sync from a finished one, and, with SNAP on and not done, to send a node where fast sync finished
    * and SNAP has no stake (accounts not complete, saved pivot not the best block) to regular sync instead of SNAP. Its
    * recovery paths clear it.
    */
  def isFastSyncDone(): Boolean =
    get(Keys.FastSyncDone).exists(_.toBoolean)

  def fastSyncDone(): DataSourceBatchUpdate =
    put(Keys.FastSyncDone, true.toString)

  def clearFastSyncDone(): DataSourceBatchUpdate =
    remove(Keys.FastSyncDone)

  def getEstimatedHighestBlock(): BigInt =
    getBigInt(Keys.EstimatedHighestBlock)

  def putEstimatedHighestBlock(n: BigInt): DataSourceBatchUpdate =
    put(Keys.EstimatedHighestBlock, n.toString)

  def getSyncStartingBlock(): BigInt =
    getBigInt(Keys.SyncStartingBlock)

  def putSyncStartingBlock(n: BigInt): DataSourceBatchUpdate =
    put(Keys.SyncStartingBlock, n.toString)

  private def getBigInt(key: Key): BigInt =
    get(key).map(BigInt(_)).getOrElse(BigInt(BigInteger.ZERO))

  /** Get the bootstrap pivot block number (highest bootstrap checkpoint)
    * @return
    *   Bootstrap pivot block number, or 0 if not set
    */
  def getBootstrapPivotBlock(): BigInt =
    getBigInt(Keys.BootstrapPivotBlock)

  /** Get the bootstrap pivot block hash (highest bootstrap checkpoint)
    * @return
    *   Bootstrap pivot block hash, or empty ByteString if not set
    */
  def getBootstrapPivotBlockHash(): ByteString =
    get(Keys.BootstrapPivotBlockHash).map(v => ByteString(Hex.decode(v))).getOrElse(ByteString.empty)

  /** Store the bootstrap pivot block info (number and hash of highest bootstrap checkpoint)
    * @param number
    *   The block number of the highest bootstrap checkpoint
    * @param hash
    *   The block hash of the highest bootstrap checkpoint
    */
  def putBootstrapPivotBlock(number: BigInt, hash: ByteString): DataSourceBatchUpdate =
    put(Keys.BootstrapPivotBlock, number.toString)
      .and(put(Keys.BootstrapPivotBlockHash, Hex.toHexString(hash.toArray)))

  /** Check if SNAP sync has completed
    * @return
    *   true if SNAP sync is done, false otherwise
    */
  def isSnapSyncDone(): Boolean =
    get(Keys.SnapSyncDone).exists(_.toBoolean)

  /** Mark SNAP sync as completed
    * @return
    *   DataSourceBatchUpdate for committing
    */
  def snapSyncDone(): DataSourceBatchUpdate =
    put(Keys.SnapSyncDone, true.toString)

  /** Clear the SNAP-sync-done flag. Used by the regular-sync stuck escape valve to force a SNAP re-sync from a recent
    * pivot when post-SNAP regular sync hits permanently-unfetchable state nodes (e.g., stuck many blocks behind
    * canonical tip with no peer able to serve our parent stateRoot).
    *
    * The heal invariant marker goes in the same write (spec 022 D7): every caller re-enters SNAP on state that regular
    * sync has written to, or that was found wanting (a stuck import, a failed hand-off, `clearDoneOnStart`), so what is
    * present there is not vouched for any more. Here, so that no caller can forget it.
    *
    * The audit's clean anchor and its place go with it (spec 022 T053): a re-SNAP can land on the same pivot, and a
    * clean pass of the state it replaced says nothing of the state it writes.
    */
  def clearSnapSyncDone(): DataSourceBatchUpdate =
    update(toRemove = Seq(Keys.SnapSyncDone, Keys.SnapHealInvariant, Keys.SnapAuditCleanAnchor), toUpsert = Nil)
      .and(SnapSyncProgressStorage.forgetAudit(dataSource))

  // ========================================
  // Heal invariant marker (spec 022 D7, data-model.md "Invariant marker")
  // ========================================

  /** Whether the marker stands: "everything present here was written under present implies complete". It does NOT mean
    * complete. A value of another version does not stand.
    */
  def hasSnapHealInvariant(): Boolean =
    get(Keys.SnapHealInvariant).contains(SnapHealInvariantVersion)

  /** Whether a marker of any version is there. What decides a removal: nothing vouches for what this binary writes
    * under another version's rule, so that marker goes as this version's does. Only "it stands" looks at the version.
    */
  def isSnapHealInvariantPresent(): Boolean =
    get(Keys.SnapHealInvariant).isDefined

  /** Stamp the marker. One write with the removal of the checkpoint key, since the stamp means whatever an interrupted
    * import left has been repaired. Commit with `commitSync` (T042).
    */
  def stampSnapHealInvariant(): DataSourceBatchUpdate =
    update(
      toRemove = Seq(Keys.CheckpointImportInProgress),
      toUpsert = Seq(Keys.SnapHealInvariant -> SnapHealInvariantVersion)
    )

  /** Remove the marker. Commit with `commitSync` (T042). */
  def clearSnapHealInvariant(): DataSourceBatchUpdate =
    remove(Keys.SnapHealInvariant)

  /** Why this datadir's next finalisation may not claim that no account's bytecode is missing, if it may not (spec 022
    * T050a, data-model.md "Invariant marker"). The walk reads no code, so only the recovery scan can settle this.
    */
  def getSnapCodeScanOwed(): Option[String] =
    get(Keys.SnapCodeScanOwed)

  /** What is present is not vouched for and the code scan is owed: the marker goes and `reason` is recorded, in one
    * write. Commit with `commitSync`.
    */
  def markSnapCodeScanOwed(reason: String): DataSourceBatchUpdate =
    update(toRemove = Seq(Keys.SnapHealInvariant), toUpsert = Seq(Keys.SnapCodeScanOwed -> reason))

  /** The finalisation that withholds `bytecodeRecoveryDone` takes this with it: from there the flag carries the debt.
    */
  def clearSnapCodeScanOwed(): DataSourceBatchUpdate =
    remove(Keys.SnapCodeScanOwed)

  /** What the last clean background-audit pass covered (spec 022 decision 7): the SNAP pivot, or
    * [[AppStateStorage.AuditNoPivot]] on a node with no pivot. A pass is owed when this differs from the current one.
    */
  def getSnapAuditCleanAnchor(): Option[String] =
    get(Keys.SnapAuditCleanAnchor)

  def putSnapAuditCleanAnchor(anchor: String): DataSourceBatchUpdate =
    put(Keys.SnapAuditCleanAnchor, anchor)

  /** What a clean pass records now, and what [[isSnapAuditOwed]] compares with: this datadir's SNAP pivot. A
    * checkpoint-synced node sets the done flags and no pivot, so it has the fixed value.
    */
  def snapAuditAnchor(): String =
    getSnapSyncPivotBlock().fold(AppStateStorage.AuditNoPivot)(_.toString)

  /** #1692: owe this datadir one more full audit pass, once. Before the fix, pruning on a store SNAP or a checkpoint
    * filled could delete a node another position still used, and a clean pass the audit finished earlier says nothing
    * of a hole that appeared after it. So the clean anchor goes, with the audit's place and its record of bad entries
    * (a pass resumed from before the upgrade would skip what it had already walked), and the key that says this was
    * done goes in the same write: a crash leaves all of it or none, and a start that finds the key owes no more. True
    * when it forced a pass.
    */
  def forceAuditPassOnce(): Boolean =
    if get(Keys.SnapAuditForcedOnce).isDefined then false
    else
      update(
        toRemove = Seq(Keys.SnapAuditCleanAnchor),
        toUpsert = Seq(Keys.SnapAuditForcedOnce -> RefCountsMarkerValue)
      ).and(SnapSyncProgressStorage.forgetAudit(dataSource)).commitSync()
      true

  /** A node SNAP or a checkpoint finished owes one clean pass per anchor, and one pass of any kind while a recovery
    * flag is down: a flag says a pass ran, and the audit is what pays it (decision 14), so with the anchor matching and
    * a flag down, as after a bulk recovery that left it, nothing else would. A node that never finished either owes
    * none.
    */
  def isSnapAuditOwed(): Boolean =
    isSnapSyncDone() &&
      (!getSnapAuditCleanAnchor().contains(snapAuditAnchor()) || !isBytecodeRecoveryDone() || !isStorageRecoveryDone())

  /** The running or interrupted checkpoint import, `<state root hex>:<start ms>`, if any. While it is here the datadir
    * is pre-invariant, whatever else it holds.
    */
  def getCheckpointImportInProgress(): Option[String] =
    get(Keys.CheckpointImportInProgress)

  /** A checkpoint import is about to write: the importer writes parent-first, so a present node can sit over an absent
    * child until it ends. The marker goes in the same write, so no crash can leave it standing over that. Commit with
    * `commitSync`, before the first node (T042).
    *
    * The audit's clean anchor and its place go too: every checkpoint import has the same anchor, so nothing else would
    * say the state is new (spec 022 T053).
    */
  def beginCheckpointImport(stateRoot: ByteString, startMs: Long): DataSourceBatchUpdate =
    update(
      // #1692: the archive holds each node once, so a node two positions use is counted once. Basic pruning would then
      // delete nodes by a count that is too low, so the stamp goes in the same write as the first of them, on any scheme.
      // #1654: the importer stores the archive's weight for the checkpoint block, trusted and not derived from
      // genesis, so the chain-weight marker goes too; `ChainWeightReconcile` checks it once the headers below are in.
      toRemove = Seq(Keys.SnapHealInvariant, Keys.SnapAuditCleanAnchor, Keys.RefCountsExact, Keys.ChainWeightInvariant),
      toUpsert = Seq(Keys.CheckpointImportInProgress -> s"${Hex.toHexString(stateRoot.toArray)}:$startMs")
    ).and(SnapSyncProgressStorage.forgetAudit(dataSource))

  /** The import's CRC checked: it goes in the batch that writes the import's done flags. */
  def endCheckpointImport(): DataSourceBatchUpdate =
    remove(Keys.CheckpointImportInProgress)

  /** Whether CF `g` has had its one-time clear (spec 022 D6). */
  def isHealingFrontierRetired(): Boolean =
    get(Keys.SnapHealFrontierRetired).exists(_.toBoolean)

  /** The one-time clear of CF `g` is done. Commit with `commitSync`. */
  def markHealingFrontierRetired(): DataSourceBatchUpdate =
    put(Keys.SnapHealFrontierRetired, true.toString)

  /** Whether this datadir's genesis was first stored by a binary that writes under the invariant, so that no older
    * binary's state can be in it unless a sync's own keys say so (see `HealInvariant.isEmpty`).
    */
  def isDatadirCreatedUnderInvariant(): Boolean =
    get(Keys.SnapHealDatadirCreated).contains(SnapHealDatadirCreatedVersion)

  /** The genesis has just been stored for the first time. */
  def markDatadirCreatedUnderInvariant(): DataSourceBatchUpdate =
    put(Keys.SnapHealDatadirCreated, SnapHealDatadirCreatedVersion)

  // ========================================
  // Whether basic pruning may delete by reference count (#1692)
  // ========================================
  //
  // A node's count is the number of positions that emitted it. Block import emits once per position, so its counts are
  // exact. A writer that skips a node already present (SNAP heal and resume, the checkpoint importer, an old fast sync)
  // leaves counts too low, and pruning then deletes a node another position still uses. So prune deletes no trie node
  // unless this datadir has been exact since it was created, on either scheme. One key says so: the genesis write sets
  // it, when the genesis counts every position, and nothing sets it again. It is taken away, in a synced write before
  // the first write that could count a node short: a Hash SNAP start, a checkpoint import, and a Path publish of a
  // hand-off that is not clean. Counts can go negative, so a stamp set again after a stretch without pruning could meet
  // a live node at zero in a death row. A datadir an older binary made has no stamp, and keeps every trie node.

  /** Whether the stamp "every count here is exact" stands. Only the genesis write sets it (see [[markDatadirCreated]]).
    */
  def hasRefCountsExact(): Boolean =
    get(Keys.RefCountsExact).contains(RefCountsMarkerValue)

  /** The stamp, for the write that stores the genesis. */
  def markRefCountsExact(): DataSourceBatchUpdate =
    put(Keys.RefCountsExact, RefCountsMarkerValue)

  /** Take the stamp away. Commit with `commitSync`, before the first node a de-duplicating writer puts in. */
  def clearRefCountsExact(): DataSourceBatchUpdate =
    remove(Keys.RefCountsExact)

  /** The write that follows a datadir's genesis being stored for the first time: who created it, and, when
    * `countsExact`, that its counts are exact. One write, so a crash cannot leave one without the other. `countsExact`
    * is what the genesis load says (`GenesisDataLoader.countsExact`): a genesis that puts a shared subtree under two
    * accounts counts it once, and its datadir is left unstamped. A crash between the genesis and this write leaves a
    * datadir with no stamp, which only costs it the reclaiming of dead nodes.
    *
    * It also sets the chain-weight marker (#1654): the only stored weight is the genesis's own difficulty, and import
    * derives every later one from its parent. A crash before this write costs one `ChainWeightReconcile` pass.
    */
  def markDatadirCreated(countsExact: Boolean): DataSourceBatchUpdate =
    val created = markDatadirCreatedUnderInvariant().and(markChainWeightInvariant())
    if countsExact then created.and(markRefCountsExact()) else created

  // ========================================
  // Stored chain weights (#1654)
  // ========================================
  //
  // Fork choice compares stored weights (`ConsensusImpl.importToNewBranch`, `BlockQueue`), so one weight that does not
  // follow from its parent's is a jump that every fork branching below it is weighed across. The marker says there is
  // none: every stored weight on the canonical chain is the genesis weight plus the difficulties of the blocks after
  // genesis up to it, as import writes it (parent + difficulty). It is set by the genesis write, by a SNAP hand-off that
  // began on an exact chain and ends on an exact pivot weight (`ChainWeightReconcile.atSnapHandOff`), and by
  // `ChainWeightReconcile` once it has checked or repaired every
  // canonical weight. It is taken away by a SNAP start (the pivot carries an estimate until the headers reach it), a
  // checkpoint import (the archive's weight is trusted, not derived), an operator seed, and any estimate written over a
  // weight. A datadir an older binary made has no marker, and gets one reconcile pass.

  /** Whether every stored canonical weight is the genesis weight plus the difficulties up to its block. */
  def hasChainWeightInvariant(): Boolean =
    get(Keys.ChainWeightInvariant).contains(ChainWeightInvariantVersion)

  def markChainWeightInvariant(): DataSourceBatchUpdate =
    put(Keys.ChainWeightInvariant, ChainWeightInvariantVersion)

  def clearChainWeightInvariant(): DataSourceBatchUpdate =
    remove(Keys.ChainWeightInvariant)

  /** Set at a SNAP start that found the marker standing (or a datadir with no header above genesis), and kept across
    * restarts of that SNAP: only then may its hand-off set the marker again (`ChainWeightReconcile.atSnapHandOff`).
    * Removed at the hand-off and at any regular-sync start.
    */
  def isChainWeightSnapPending(): Boolean =
    get(Keys.ChainWeightSnapPending).contains(ChainWeightInvariantVersion)

  def markChainWeightSnapPending(): DataSourceBatchUpdate =
    put(Keys.ChainWeightSnapPending, ChainWeightInvariantVersion)

  def clearChainWeightSnapPending(): DataSourceBatchUpdate =
    remove(Keys.ChainWeightSnapPending)

  /** `ChainWeightReconcile`'s saved place, in its own encoding (`ChainWeightReconcile.Progress`). */
  def getChainWeightReconcileProgress(): Option[String] =
    get(Keys.ChainWeightReconcileProgress)

  def putChainWeightReconcileProgress(encoded: String): DataSourceBatchUpdate =
    put(Keys.ChainWeightReconcileProgress, encoded)

  def removeChainWeightReconcileProgress(): DataSourceBatchUpdate =
    remove(Keys.ChainWeightReconcileProgress)

  /** True when SNAP sync has started but has not yet completed — i.e., the node is mid-SNAP.
    *
    * In this state `AppStateStorage.bestBlock` is set to the pivot block number whose header is stored, but the full
    * block body is never persisted, and headers 0..pivot don't exist either. Phase-5 DB consistency checks must skip:
    * they would see "best block hash not in block storage" and falsely conclude the DB is corrupt.
    *
    * Signals "in progress" when any of these keys are set AND `SnapSyncDone` isn't:
    *   - `SnapSyncPivotBlock` / `SnapSyncStateRoot` — set as soon as the pivot is chosen (before the account-range
    *     actor starts persisting `SnapSyncProgress`). The early-window scenario between pivot selection and the first
    *     progress write is exactly the one Bug 28 hit.
    *   - `SnapSyncProgress` — set after the first AccountRangeProgress message is received.
    *
    * `isSnapSyncDone` wins over all of these; once SNAP completes we transition out of the partial state even if
    * pivot/root records remain from the last run.
    */
  def isSnapSyncInProgress(): Boolean =
    !isSnapSyncDone() && (
      getSnapSyncPivotBlock().isDefined ||
        getSnapSyncStateRoot().isDefined ||
        getSnapSyncProgress().isDefined ||
        getSnapAccountResumeCheckpoint().isDefined
    )

  /** Check if bytecode recovery scan has completed (Bug 20 hardening) */
  def isBytecodeRecoveryDone(): Boolean =
    get(Keys.BytecodeRecoveryDone).exists(_.toBoolean)

  /** Mark bytecode recovery as completed */
  def bytecodeRecoveryDone(): DataSourceBatchUpdate =
    put(Keys.BytecodeRecoveryDone, true.toString)

  /** Mark bytecode recovery as NOT done, so the next start's recovery scan runs (or resumes). Written when block import
    * finds contract code missing and escalates to a bulk bytecode recovery: whatever happens next, a node that stops
    * part-way must not come back believing its bytecode is complete.
    */
  def clearBytecodeRecoveryDone(): DataSourceBatchUpdate =
    put(Keys.BytecodeRecoveryDone, false.toString)

  /** How many bulk bytecode recoveries (triggered by block import) have finished with the triggering code still
    * missing. Persisted so that a code no peer serves stops costing a full trie scan on every restart.
    */
  def bulkBytecodeRecoveryFailures(): Int =
    get(Keys.BulkBytecodeRecoveryFailures).flatMap(v => scala.util.Try(v.toInt).toOption).getOrElse(0)

  def putBulkBytecodeRecoveryFailures(count: Int): DataSourceBatchUpdate =
    put(Keys.BulkBytecodeRecoveryFailures, count.toString)

  /** H3: SNAP re-syncs regular sync has asked for in a row, with no block imported in between. Read by SyncController's
    * `RegularSyncStuck` handler, which waits before a re-SNAP from the third on.
    */
  def regularSyncStuckResnaps(): Int =
    get(Keys.RegularSyncStuckResnaps).flatMap(v => scala.util.Try(v.toInt).toOption).getOrElse(0)

  /** H3: the best block an import is measured from: the best block at the last `RegularSyncStuck` that did not re-SNAP
    * at once, or where the first regular sync after a re-SNAP started. `None` while a re-SNAP runs (its pivot is not an
    * import), and when nothing was ever stuck.
    */
  def resnapBaseline(): Option[BigInt] =
    get(Keys.RegularSyncStuckBaseline).flatMap(v => scala.util.Try(BigInt(v)).toOption)

  /** The count and its baseline, in one batch, so a restart never finds one without the other. */
  def putRegularSyncStuck(count: Int, baseline: Option[BigInt]): DataSourceBatchUpdate =
    val counted = put(Keys.RegularSyncStuckResnaps, count.toString)
    baseline.fold(counted.and(remove(Keys.RegularSyncStuckBaseline)))(b =>
      counted.and(put(Keys.RegularSyncStuckBaseline, b.toString))
    )

  /** Check if storage recovery scan has completed (Bug 20 hardening) */
  def isStorageRecoveryDone(): Boolean =
    get(Keys.StorageRecoveryDone).exists(_.toBoolean)

  /** Mark storage recovery as completed */
  def storageRecoveryDone(): DataSourceBatchUpdate =
    put(Keys.StorageRecoveryDone, true.toString)

  /** Mark storage recovery as NOT done, so the next start's recovery scan runs. Written when a finalisation withholds
    * it (spec 022 T050a): a datadir SNAP has been through before can hold `true` from that cycle.
    */
  def clearStorageRecoveryDone(): DataSourceBatchUpdate =
    put(Keys.StorageRecoveryDone, false.toString)

  // ========================================
  // Resumable recovery scan progress
  // ========================================
  //
  // The post-SNAP recovery scan walks the full state trie to find missing bytecode/storage. On a
  // large chain (ETC ~86M accounts) that scan runs for hours, and an OOM/restart used to discard
  // ALL of it — the next startup re-scanned from account 0. These methods persist per-shard
  // progress (which shards are done) together with the gaps found so far, so a restart resumes from
  // the last completed shard. The whole progress is one value under one key, so a shard's
  // completion and its found-gaps always commit atomically — a crash can't desync them.

  /** Persist recovery-scan progress (completed shards + accumulated gaps). One atomic value. */
  def putRecoveryProgress(progress: RecoveryProgress): DataSourceBatchUpdate =
    put(Keys.RecoveryProgress, RecoveryProgress.serialize(progress))

  /** Read persisted recovery-scan progress, or None if absent or unparseable (a torn/corrupt write degrades to a fresh
    * scan rather than a silently-incomplete gap set).
    */
  def getRecoveryProgress(): Option[RecoveryProgress] =
    get(Keys.RecoveryProgress).flatMap(RecoveryProgress.deserialize)

  /** Tag-validated read: returns persisted progress ONLY if it was recorded for the same scan root and shard fanout. A
    * pivot/finalized-root change or a partition reconfig makes prior shard indices meaningless, so a tag mismatch is
    * treated as "no progress" — forcing a correct fresh scan instead of trusting stale, possibly-incomplete state.
    */
  def getRecoveryProgressFor(scanRoot: ByteString, shardCount: Int): Option[RecoveryProgress] =
    getRecoveryProgress().filter(p => p.scanRoot == scanRoot && p.shardCount == shardCount)

  /** Clear persisted recovery progress. Called once recovery completes so the next startup starts clean. */
  def clearRecoveryProgress(): DataSourceBatchUpdate =
    remove(Keys.RecoveryProgress)

  /** Atomically mark BOTH recovery scans done AND clear the resumable checkpoint, in a single batch.
    *
    * The combined recovery driver calls this once the single-pass scan + gap download finishes. Doing it as one atomic
    * write closes the crash window that separate commits would open: there is never a restart that sees a done-flag set
    * with a stale checkpoint still lingering, nor a cleared checkpoint with the done-flag unset (→ a needless full
    * re-scan). Either the whole "recovery is finished and the checkpoint is gone" fact is durable, or none of it is.
    */
  def recoveryDone(): DataSourceBatchUpdate =
    put(Keys.BytecodeRecoveryDone, true.toString)
      .and(put(Keys.StorageRecoveryDone, true.toString))
      .and(remove(Keys.RecoveryProgress))

  /** Get the SNAP sync pivot block number
    * @return
    *   SNAP sync pivot block number, or None if not set
    */
  def getSnapSyncPivotBlock(): Option[BigInt] =
    get(Keys.SnapSyncPivotBlock).map(BigInt(_))

  /** Store the SNAP sync pivot block number
    * @param pivotBlock
    *   The block number to use as the pivot for SNAP sync
    * @return
    *   DataSourceBatchUpdate for committing
    */
  def putSnapSyncPivotBlock(pivotBlock: BigInt): DataSourceBatchUpdate =
    put(Keys.SnapSyncPivotBlock, pivotBlock.toString)

  /** The lowest block SNAP may take as its pivot, set when the node was found stranded by an interrupted fast sync: the
    * best block that fast sync reached has no state behind it, so a pivot at or below it would pass for "already
    * synced". Persisted so a restart before SNAP commits a pivot keeps the floor; cleared once SNAP finalizes.
    */
  def getSnapSyncMinPivotBlock(): Option[BigInt] =
    get(Keys.SnapSyncMinPivotBlock).map(BigInt(_))

  def putSnapSyncMinPivotBlock(minPivotBlock: BigInt): DataSourceBatchUpdate =
    put(Keys.SnapSyncMinPivotBlock, minPivotBlock.toString)

  def clearSnapSyncMinPivotBlock(): DataSourceBatchUpdate =
    remove(Keys.SnapSyncMinPivotBlock)

  /** Get the SNAP sync state root hash
    * @return
    *   SNAP sync state root hash, or None if not set
    */
  def getSnapSyncStateRoot(): Option[ByteString] =
    get(Keys.SnapSyncStateRoot).map(v => ByteString(Hex.decode(v)))

  /** Store the SNAP sync state root hash
    * @param stateRoot
    *   The state root hash for SNAP sync
    * @return
    *   DataSourceBatchUpdate for committing
    */
  def putSnapSyncStateRoot(stateRoot: ByteString): DataSourceBatchUpdate =
    put(Keys.SnapSyncStateRoot, Hex.toHexString(stateRoot.toArray))

  /** The hash of the header the SNAP pivot was taken from: written in the same batch as `SnapSyncPivotBlock` and
    * `SnapSyncStateRoot` wherever a pivot is committed. Finalisation compares it with the header the validated header
    * download holds at the pivot's number. Absent on a datadir whose pivot was committed before this key existed, and
    * where the committed pivot's header could not be identified; finalisation then compares the state root only.
    */
  def getSnapSyncPivotHash(): Option[ByteString] =
    get(Keys.SnapSyncPivotHash).map(v => ByteString(Hex.decode(v)))

  def putSnapSyncPivotHash(hash: ByteString): DataSourceBatchUpdate =
    put(Keys.SnapSyncPivotHash, Hex.toHexString(hash.toArray))

  def removeSnapSyncPivotHash(): DataSourceBatchUpdate =
    remove(Keys.SnapSyncPivotHash)

  /** Get the SNAP sync progress (optional - for progress persistence across restarts)
    *
    * NOTE: This is infrastructure for future use. Currently not integrated into SNAPSyncController. Integration is
    * planned to enable resumable SNAP sync after process restarts.
    *
    * This method retrieves a JSON representation of the SNAP sync progress. The JSON format is flexible and not tied to
    * any specific case class structure, allowing for evolution of the progress format over time without breaking
    * compatibility.
    *
    * Example JSON format:
    * ```json
    * {
    *   "phase": "AccountRangeSync",
    *   "accountsSynced": 1000,
    *   "bytecodesDownloaded": 50,
    *   "storageSlotsSynced": 200,
    *   "nodesHealed": 10
    * }
    * ```
    *
    * @return
    *   Optional JSON string if progress has been saved, None otherwise
    */
  def getSnapSyncProgress(): Option[String] =
    get(Keys.SnapSyncProgress)

  /** Store the SNAP sync progress (optional - for progress persistence across restarts)
    *
    * NOTE: This is infrastructure for future use. Currently not integrated into SNAPSyncController. Integration is
    * planned to enable resumable SNAP sync after process restarts.
    *
    * This method stores a JSON representation of the SNAP sync progress. The JSON format is flexible and allows the
    * caller to determine what fields to include. This design provides forward/backward compatibility as the progress
    * tracking evolves.
    *
    * @param progressJson
    *   JSON string representation of the sync progress
    * @return
    *   DataSourceBatchUpdate for committing
    */
  def putSnapSyncProgress(progressJson: String): DataSourceBatchUpdate =
    put(Keys.SnapSyncProgress, progressJson)

  /** Versioned account-phase resume checkpoint (JSON, see `snap.AccountResumeCheckpoint`): per-range cursors, the root
    * and pivot they were downloaded against, and the contract task files (paths + entry counts) that hold the
    * storage/bytecode work derived from every account below those cursors. Stored under a fixed key — NOT keyed by the
    * state root — so a process restart that picks a fresh pivot still finds it.
    */
  def getSnapAccountResumeCheckpoint(): Option[String] =
    get(Keys.SnapAccountResumeCheckpoint)

  def putSnapAccountResumeCheckpoint(json: String): DataSourceBatchUpdate =
    put(Keys.SnapAccountResumeCheckpoint, json)

  def removeSnapAccountResumeCheckpoint(): DataSourceBatchUpdate =
    remove(Keys.SnapAccountResumeCheckpoint)

  /** Get the target block number for SNAP sync bootstrap via regular sync. This is used when SNAP sync requires a
    * minimum number of blocks to start.
    * @return
    *   Target block number for bootstrap, or None if not set
    */
  def getSnapSyncBootstrapTarget(): Option[BigInt] =
    get(Keys.SnapSyncBootstrapTarget).map(BigInt(_))

  /** Store the target block number for SNAP sync bootstrap. Regular sync will sync to this block number before
    * transitioning to SNAP sync.
    * @param targetBlock
    *   The block number to reach before starting SNAP sync
    * @return
    *   DataSourceBatchUpdate for chaining
    */
  def putSnapSyncBootstrapTarget(targetBlock: BigInt): DataSourceBatchUpdate =
    put(Keys.SnapSyncBootstrapTarget, targetBlock.toString)

  /** Clear the SNAP sync bootstrap target (called after transition to SNAP sync)
    * @return
    *   DataSourceBatchUpdate for chaining
    */
  def clearSnapSyncBootstrapTarget(): DataSourceBatchUpdate =
    update(toRemove = Seq(Keys.SnapSyncBootstrapTarget), toUpsert = Nil)

  /** Check if SNAP sync account download phase has completed. Used to skip account re-download on process restart
    * during bytecode/storage phase.
    */
  def isSnapSyncAccountsComplete(): Boolean =
    get(Keys.SnapSyncAccountsComplete).exists(_.toBoolean)

  /** Mark SNAP sync account download as complete (or incomplete on full restart). */
  def putSnapSyncAccountsComplete(complete: Boolean): DataSourceBatchUpdate =
    put(Keys.SnapSyncAccountsComplete, complete.toString)

  /** Check if SNAP sync storage download phase has completed. Used to skip storage re-download on restart. */
  def isSnapSyncStorageComplete(): Boolean =
    get(Keys.SnapSyncStorageComplete).exists(_.toBoolean)

  /** Mark SNAP sync storage download as complete (or incomplete on full restart). */
  def putSnapSyncStorageComplete(complete: Boolean): DataSourceBatchUpdate =
    put(Keys.SnapSyncStorageComplete, complete.toString)

  /** Check if SNAP sync bytecode download phase has completed. Used to skip bytecode re-download on restart. */
  def isSnapSyncBytecodeComplete(): Boolean =
    get(Keys.SnapSyncBytecodeComplete).exists(_.toBoolean)

  /** Mark SNAP sync bytecode download as complete (or incomplete on full restart). */
  def putSnapSyncBytecodeComplete(complete: Boolean): DataSourceBatchUpdate =
    put(Keys.SnapSyncBytecodeComplete, complete.toString)

  /** Get the persisted path to the unique codeHashes file for bytecode sync recovery. */
  def getSnapSyncCodeHashesPath(): Option[String] =
    get(Keys.SnapSyncCodeHashesPath)

  /** Persist the path to the unique codeHashes file so bytecode sync can resume after restart. */
  def putSnapSyncCodeHashesPath(path: String): DataSourceBatchUpdate =
    put(Keys.SnapSyncCodeHashesPath, path)

  /** Entry count the codeHashes file held when it was finalized. Lets recovery tell an empty-by-design file (count 0)
    * from a lost one. `None` for legacy data persisted before the count existed, or after a reset.
    */
  def getSnapSyncCodeHashesCount(): Option[Long] =
    get(Keys.SnapSyncCodeHashesCount).filter(_.nonEmpty).flatMap(_.toLongOption)

  /** Persist the codeHashes file entry count; an empty string clears it. */
  def putSnapSyncCodeHashesCount(count: Option[Long]): DataSourceBatchUpdate =
    put(Keys.SnapSyncCodeHashesCount, count.fold("")(_.toString))

  /** Entry count the contract storage-task file held when it was finalized (see [[getSnapSyncCodeHashesCount]]). */
  def getSnapSyncStorageFileCount(): Option[Long] =
    get(Keys.SnapSyncStorageFileCount).filter(_.nonEmpty).flatMap(_.toLongOption)

  /** Persist the storage-task file entry count; an empty string clears it. */
  def putSnapSyncStorageFileCount(count: Option[Long]): DataSourceBatchUpdate =
    put(Keys.SnapSyncStorageFileCount, count.fold("")(_.toString))

  /** Get the persisted path to the contract storage file for storage sync recovery. */
  def getSnapSyncStorageFilePath(): Option[String] =
    get(Keys.SnapSyncStorageFilePath)

  /** Persist the path to the contract storage file so storage sync can resume after restart. */
  def putSnapSyncStorageFilePath(path: String): DataSourceBatchUpdate =
    put(Keys.SnapSyncStorageFilePath, path)

  /** Get the finalized account trie root hash produced by finalizeTrie(). This may differ from the pivot block header's
    * stateRoot after pivot refreshes.
    */
  def getSnapSyncFinalizedRoot(): Option[ByteString] =
    get(Keys.SnapSyncFinalizedRoot).map(v => ByteString(Hex.decode(v)))

  /** Store the finalized account trie root hash from finalizeTrie(). */
  def putSnapSyncFinalizedRoot(root: ByteString): DataSourceBatchUpdate =
    put(Keys.SnapSyncFinalizedRoot, Hex.toHexString(root.toArray))

  // ========================================
  // Background chain backfill cursors (#1169)
  // ========================================
  //
  // After SNAP completes, `ChainDownloader` continues to backfill genesis→pivot in the background
  // (#1162). If the node is killed mid-backfill we want regular sync to come back up immediately
  // and *also* resume backfill from where it stopped. These cursors give us:
  //
  //   1. A fast skip-to-current path on resume (no full binary search across the chain).
  //   2. A predicate (`needsBackfillResume`) for `SyncController.start()` to decide whether to
  //      spawn a standalone `ChainDownloader` alongside regular sync.
  //
  // Three separate cursors because `ChainDownloader` writes headers, bodies, and receipts on
  // independent commit paths — they don't all advance in lockstep and must be tracked
  // separately. The header cursor is updated atomically with its `storeBlockHeader` commit (one
  // write batch, headers validated and stored strictly in order) so a crash mid-write never
  // leaves it ahead of the data on disk. Bodies and receipts are fetched by several peers
  // concurrently and can complete out of order, so their cursors instead mean "every block at or
  // below this number has its body/receipts stored" and are advanced by a separate
  // contiguous-prefix scan AFTER each store commit (`ChainDownloader.advanceBodyCursor` /
  // `advanceReceiptCursor`, #33) rather than bundled atomically with the store itself — but the
  // scan only ever advances over what it has just verified is on disk, so the same
  // never-ahead-of-data guarantee holds by construction, not by a single write batch.
  //
  // "The scan only ever advances over what it has just verified" is a claim about how FAR the scan trusts
  // itself to advance — it does NOT mean a header existing at some number N is by itself evidence that 1..N
  // are all present. `PivotHeaderBootstrap` (running(), Fetched branch) stores a SNAP pivot header directly,
  // with no cursor update and nothing underneath it yet, so `getBlockHeaderByNumber` can return `Some` for a
  // block far above a genuine gap. Neither the header cursor above nor a header existing at a high number
  // implies contiguity below it (forge's ETC review of 43d1c3eee, Defect 9) — `ChainDownloader.
  // findBestStoredHeader`'s own rebuild walk is what actually verifies the contiguous prefix (its own
  // sequential, header-by-header check, independent of the cursor or the binary search that seeds it) and
  // clamps `bestHeaderNumber` to the last confirmed-contiguous block rather than trusting an isolated header.

  /** Highest backfill target the node was working toward when it last saved progress. Set when `ChainDownloader`
    * starts; cleared after `Done` so future startups don't spuriously resume.
    */
  def getBackfillTarget(): BigInt =
    getBigInt(Keys.BackfillTarget)

  def putBackfillTarget(target: BigInt): DataSourceBatchUpdate =
    put(Keys.BackfillTarget, target.toString)

  /** Highest header number whose `storeBlockHeader` commit has succeeded via `ChainDownloader`'s OWN sequential fetch
    * path specifically — not merely "the highest block number with a header on disk from any source". A pivot header
    * stored by `PivotHeaderBootstrap` doesn't advance this cursor and can leave a genuine gap beneath it;
    * `ChainDownloader.findBestStoredHeader` accounts for that with its own contiguity walk rather than trusting this
    * value (or a higher header found by its binary search) blindly.
    */
  def getBackfillBestHeader(): BigInt =
    getBigInt(Keys.BackfillBestHeader)

  def putBackfillBestHeader(n: BigInt): DataSourceBatchUpdate =
    put(Keys.BackfillBestHeader, n.toString)

  /** Hash of the header at `BackfillBestHeader`, the tip of the header chain `ChainDownloader` stored: written in the
    * same batch as the cursor whenever the download stores headers. The download continues from this header, and SNAP
    * finalisation finds the downloaded chain's header at the pivot from it by parent hash, so neither depends on the
    * number→hash index, which other writers (the pivot bootstrap) also update. A value that does not name a stored
    * header at the cursor's height is stale; readers check that. Absent on a datadir whose headers were stored before
    * this key existed.
    */
  def getBackfillBestHeaderHash(): Option[ByteString] =
    get(Keys.BackfillBestHeaderHash).map(v => ByteString(Hex.decode(v)))

  def putBackfillBestHeaderHash(hash: ByteString): DataSourceBatchUpdate =
    put(Keys.BackfillBestHeaderHash, Hex.toHexString(hash.toArray))

  /** Hash of the header `ChainDownloader` stored at its target block (`BackfillTarget`, the SNAP pivot), written in the
    * batch that stores it. SNAP finalisation reads the downloaded chain's header at the pivot from it, without walking
    * back from the tip; readers check the header's number. Removed when the download starts again from genesis.
    */
  def getBackfillTargetHash(): Option[ByteString] =
    get(Keys.BackfillTargetHash).map(v => ByteString(Hex.decode(v)))

  def putBackfillTargetHash(hash: ByteString): DataSourceBatchUpdate =
    put(Keys.BackfillTargetHash, Hex.toHexString(hash.toArray))

  def removeBackfillTargetHash(): DataSourceBatchUpdate =
    remove(Keys.BackfillTargetHash)

  /** Highest block number N such that every block from 1 to N has a `storeBlockBody` commit on disk — a verified
    * contiguous prefix (`ChainDownloader.advanceBodyCursor`, #33), not merely the highest individual body that has ever
    * been stored (bodies land out of order across concurrent peers). May lag the header cursor.
    *
    * "Every block from 1 to N" is only as trustworthy as the header cursor it walks against: `advanceBodyCursor` checks
    * a block's body via its header, so a gap in the HEADERS themselves (see `getBackfillBestHeader`'s doc) bounds how
    * far this can validly advance too, not just how far bodies happen to be fetched.
    */
  def getBackfillBestBody(): BigInt =
    getBigInt(Keys.BackfillBestBody)

  def putBackfillBestBody(n: BigInt): DataSourceBatchUpdate =
    put(Keys.BackfillBestBody, n.toString)

  /** Highest block number N such that every block from 1 to N has a `storeReceipts` commit on disk — a verified
    * contiguous prefix (`ChainDownloader.advanceReceiptCursor`, #33), not merely the highest individual receipt set
    * that has ever been stored. May lag the body cursor.
    *
    * Same header-contiguity caveat as `getBackfillBestBody`'s doc: this cursor's own scan is only as trustworthy as the
    * headers it checks against.
    */
  def getBackfillBestReceipt(): BigInt =
    getBigInt(Keys.BackfillBestReceipt)

  def putBackfillBestReceipt(n: BigInt): DataSourceBatchUpdate =
    put(Keys.BackfillBestReceipt, n.toString)

  /** Clear all backfill cursors + target. A full reset — no code currently calls this on the "backfill just finished,
    * still-alive downloader may run again" path (see `removeBackfillTarget`); this remains for a genuine fresh-start
    * reset (e.g. a future admin/debug entry point).
    */
  def clearBackfillCursors(): DataSourceBatchUpdate =
    update(
      toRemove = Seq(
        Keys.BackfillTarget,
        Keys.BackfillBestHeader,
        Keys.BackfillBestHeaderHash,
        Keys.BackfillTargetHash,
        Keys.BackfillBestBody,
        Keys.BackfillBestReceipt
      ),
      toUpsert = Nil
    )

  /** Remove only the backfill target, leaving the header/body/receipt cursors in place. `needsBackfillResume()`
    * short-circuits to `false` as soon as `getBackfillTarget() <= 0`, so this alone is sufficient to tell a FRESH
    * startup "no backfill to resume" — the same signal `clearBackfillCursors()` gave.
    *
    * `ChainDownloader.checkCompletion` uses this instead of `clearBackfillCursors()` on `Done` (a bug Forge's ETC
    * review of the #33 follow-up found, ETC mainnet scale): `SNAPSyncController` keeps the downloader alive after
    * `Done` and can send it `UpdateTarget` on a later pivot refresh, landing in `idle()`'s handler, which calls
    * `findBestStoredHeader()` again. That rebuild trusts the header/body/receipt cursors as a floor to avoid re-walking
    * everything already confirmed on disk (#33) — deleting them on every completion meant that floor reset to 0 every
    * time, so the SAME already-backfilled range (potentially the whole chain) got walked from scratch on every single
    * pivot refresh. Leaving the three cursors at their completed values means the next `findBestStoredHeader()` call
    * only walks the NEW incremental range above them.
    */
  def removeBackfillTarget(): DataSourceBatchUpdate =
    remove(Keys.BackfillTarget)

  /** True iff SNAP is done AND a backfill target was previously persisted AND any of the three cursors is below the
    * target. `SyncController.start()` uses this to spawn a standalone `ChainDownloader` alongside regular sync.
    */
  def needsBackfillResume(): Boolean =
    if !isSnapSyncDone() then false
    else
      val target = getBackfillTarget()
      if target <= 0 then false
      else
        getBackfillBestHeader() < target ||
        getBackfillBestBody() < target ||
        getBackfillBestReceipt() < target

object AppStateStorage:
  type Key = String
  type Value = String

  /** The value of `snap.heal.invariant`. A later version of the rule writes another, and older marks do not stand. 3:
    * version 2 stamped an empty datadir and then downloaded with spec 022's H0 and H1 still open, so its mark says less
    * than this one's (research R-5d). 4: this version is the first reader that skips a walk on the marker's word, and a
    * run of an earlier jar can have lost track with its marker standing, so a version 3 mark costs one walk (research
    * R-5g).
    */
  val SnapHealInvariantVersion: Value = "4"

  /** The value of `snap.heal.datadirCreated`. Apart from the marker's: it says who created the datadir, and that did
    * not change with version 4. A datadir with an older value counts as not empty.
    */
  val SnapHealDatadirCreatedVersion: Value = "3"

  /** [[getSnapAuditCleanAnchor]]'s value on a node with no SNAP pivot. */
  val AuditNoPivot: Value = "no-pivot"

  /** The value of `state.refcounts.exact` and `snap.heal.auditForcedOnce`: the keys say it by being there. */
  val RefCountsMarkerValue: Value = "1"

  /** The value of `chain.weight.invariant` (#1654). A later rule writes another, and older marks do not stand. */
  val ChainWeightInvariantVersion: Value = "1"

  object Keys:
    val BestBlockNumber = "BestBlockNumber"
    val BestBlockHash = "BestBlockHash"
    val FastSyncDone = "FastSyncDone"
    val EstimatedHighestBlock = "EstimatedHighestBlock"
    val SyncStartingBlock = "SyncStartingBlock"
    val BootstrapPivotBlock = "BootstrapPivotBlock"
    val BootstrapPivotBlockHash = "BootstrapPivotBlockHash"
    val SnapSyncDone = "SnapSyncDone"
    val SnapSyncPivotBlock = "SnapSyncPivotBlock"
    val SnapSyncMinPivotBlock = "SnapSyncMinPivotBlock"
    val SnapSyncStateRoot = "SnapSyncStateRoot"
    val SnapSyncPivotHash = "SnapSyncPivotHash"
    val SnapSyncProgress = "SnapSyncProgress"
    val SnapAccountResumeCheckpoint = "SnapAccountResumeCheckpoint"
    val SnapSyncBootstrapTarget = "SnapSyncBootstrapTarget"
    val BytecodeRecoveryDone = "BytecodeRecoveryDone"
    val BulkBytecodeRecoveryFailures = "BulkBytecodeRecoveryFailures"
    val RegularSyncStuckResnaps = "RegularSyncStuckResnaps"
    val RegularSyncStuckBaseline = "RegularSyncStuckBaseline"
    val StorageRecoveryDone = "StorageRecoveryDone"
    val RecoveryProgress = "RecoveryProgress"
    val SnapSyncAccountsComplete = "SnapSyncAccountsComplete"
    val SnapSyncStorageComplete = "SnapSyncStorageComplete"
    val SnapSyncBytecodeComplete = "SnapSyncBytecodeComplete"
    val SnapSyncCodeHashesPath = "SnapSyncCodeHashesPath"
    val SnapSyncStorageFilePath = "SnapSyncStorageFilePath"
    val SnapSyncStorageFileCount = "SnapSyncStorageFileCount"
    val SnapSyncCodeHashesCount = "SnapSyncCodeHashesCount"
    val SnapSyncFinalizedRoot = "SnapSyncFinalizedRoot"
    val BackfillTarget = "BackfillTarget"
    val BackfillBestHeader = "BackfillBestHeader"
    val BackfillBestHeaderHash = "BackfillBestHeaderHash"
    val BackfillTargetHash = "BackfillTargetHash"
    val BackfillBestBody = "BackfillBestBody"
    val BackfillBestReceipt = "BackfillBestReceipt"
    val SnapHealInvariant = "snap.heal.invariant"
    val CheckpointImportInProgress = "checkpoint.import.inProgress"
    val SnapHealFrontierRetired = "snap.heal.frontierRetired"
    val SnapHealDatadirCreated = "snap.heal.datadirCreated"
    val SnapCodeScanOwed = "snap.heal.codeScanOwed"
    val SnapAuditCleanAnchor = "snap.heal.auditCleanAnchor"
    val SnapAuditForcedOnce = "snap.heal.auditForcedOnce"
    val RefCountsExact = "state.refcounts.exact"
    val StateAnchor = com.chipprbots.ethereum.db.storage.pathstate.StateAnchor.Key
    val StateOrigin = "state.origin"
    val StateFinalized = "state.finalized"
    val StateChangesetPrunedTo = "state.changeset.pruned-to"
    val StateLayout = com.chipprbots.ethereum.db.storage.pathstate.StateLayout.Key
    val ChainWeightInvariant = "chain.weight.invariant"
    val ChainWeightReconcileProgress = "chain.weight.reconcileProgress"
    val ChainWeightSnapPending = "chain.weight.snapPending"
