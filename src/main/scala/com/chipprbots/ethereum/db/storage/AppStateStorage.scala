package com.chipprbots.ethereum.db.storage

import java.math.BigInteger

import org.apache.pekko.util.ByteString

import scala.collection.immutable.ArraySeq

import com.chipprbots.ethereum.db.dataSource.DataSource
import com.chipprbots.ethereum.db.dataSource.DataSourceBatchUpdate
import com.chipprbots.ethereum.db.storage.AppStateStorage._
import com.chipprbots.ethereum.domain.appstate.BlockInfo
import com.chipprbots.ethereum.utils.Hex

/** This class is used to store app state variables Key: see AppStateStorage.Keys Value: stored string value
  */
class AppStateStorage(val dataSource: DataSource) extends TransactionalKeyValueStorage[Key, Value] {

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

  def isFastSyncDone(): Boolean =
    get(Keys.FastSyncDone).exists(_.toBoolean)

  def fastSyncDone(): DataSourceBatchUpdate =
    put(Keys.FastSyncDone, true.toString)

  def clearFastSyncDone(): DataSourceBatchUpdate =
    remove(Keys.FastSyncDone)

  def getFastSyncCooldownUntilMillis(): Long =
    get(Keys.FastSyncCooldownUntilMillis).flatMap(v => scala.util.Try(v.toLong).toOption).getOrElse(0L)

  def putFastSyncCooldownUntilMillis(untilMillis: Long): DataSourceBatchUpdate =
    put(Keys.FastSyncCooldownUntilMillis, untilMillis.toString)

  def isFastSyncCoolingOff(nowMillis: Long): Boolean =
    getFastSyncCooldownUntilMillis() > nowMillis

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
    */
  def clearSnapSyncDone(): DataSourceBatchUpdate =
    remove(Keys.SnapSyncDone)

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
        getSnapSyncProgress().isDefined
    )

  /** Check if bytecode recovery scan has completed (Bug 20 hardening) */
  def isBytecodeRecoveryDone(): Boolean =
    get(Keys.BytecodeRecoveryDone).exists(_.toBoolean)

  /** Mark bytecode recovery as completed */
  def bytecodeRecoveryDone(): DataSourceBatchUpdate =
    put(Keys.BytecodeRecoveryDone, true.toString)

  /** Check if storage recovery scan has completed (Bug 20 hardening) */
  def isStorageRecoveryDone(): Boolean =
    get(Keys.StorageRecoveryDone).exists(_.toBoolean)

  /** Mark storage recovery as completed */
  def storageRecoveryDone(): DataSourceBatchUpdate =
    put(Keys.StorageRecoveryDone, true.toString)

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

  /** Total accounts downloaded during the completed AccountRangeSync phase.
    * Persisted at phase completion for accurate scan progress percentages
    * (rocksdb.estimate-num-keys is ~50% off on ETC mainnet flat storage).
    */
  def getSnapSyncTotalAccounts(): Option[Long] =
    get(Keys.SnapSyncTotalAccounts).flatMap(v => scala.util.Try(v.toLong).toOption)

  def putSnapSyncTotalAccounts(total: Long): DataSourceBatchUpdate =
    put(Keys.SnapSyncTotalAccounts, total.toString)

  /** Total bytecodes downloaded during the completed ByteCodeAndStorageSync phase. */
  def getSnapSyncTotalBytecodes(): Option[Long] =
    get(Keys.SnapSyncTotalBytecodes).flatMap(v => scala.util.Try(v.toLong).toOption)

  def putSnapSyncTotalBytecodes(total: Long): DataSourceBatchUpdate =
    put(Keys.SnapSyncTotalBytecodes, total.toString)

  /** Total contracts (unique storage tries) completed during the ByteCodeAndStorageSync phase. */
  def getSnapSyncTotalContracts(): Option[Long] =
    get(Keys.SnapSyncTotalContracts).flatMap(v => scala.util.Try(v.toLong).toOption)

  def putSnapSyncTotalContracts(total: Long): DataSourceBatchUpdate =
    put(Keys.SnapSyncTotalContracts, total.toString)

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

  /** Get the SNAP/Fast sync bounce cycle count. */
  def getSnapFastCycleCount(): Int =
    get(Keys.SnapFastCycleCount).flatMap(v => scala.util.Try(v.toInt).toOption).getOrElse(0)

  def putSnapFastCycleCount(count: Int): DataSourceBatchUpdate =
    put(Keys.SnapFastCycleCount, count.toString)

  def clearSnapFastCycleCount(): DataSourceBatchUpdate =
    remove(Keys.SnapFastCycleCount)

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
  // Healing frontier persistence (Phase 2 A1+B1)
  // ========================================
  //
  // On restart mid-healing, the old recovery path ran a full iterative DFS over all ~90M trie
  // nodes to rebuild the frontier of missing tasks — taking ~16 hours on ETC mainnet.
  //
  // These two keys save the frontier after each successful batch flush so a restart resumes
  // in O(1) from the last checkpoint rather than re-walking the entire healed trie.
  //
  //   FrontierRoot  — the pivot root at checkpoint time; used to detect pivot mismatch
  //   FrontierData  — serialised pending-task list (coordinator-owned encoding: one entry
  //                   per line, "hashHex:pathHex1,pathHex2,..." joined with "|")
  //
  // Write order: node batch commit → frontier checkpoint (never the reverse).
  // Cleared on pivot refresh, healing completion, and force-complete.

  def getSnapHealingFrontierRoot(): Option[ByteString] =
    get(Keys.SnapHealingFrontierRoot).map(v => ByteString(Hex.decode(v)))

  def putSnapHealingFrontierRoot(root: ByteString): DataSourceBatchUpdate =
    put(Keys.SnapHealingFrontierRoot, Hex.toHexString(root.toArray))

  def getSnapHealingFrontierData(): Option[String] =
    get(Keys.SnapHealingFrontierData)

  def putSnapHealingFrontierData(data: String): DataSourceBatchUpdate =
    put(Keys.SnapHealingFrontierData, data)

  def clearSnapHealingFrontier(): DataSourceBatchUpdate =
    update(toRemove = Seq(Keys.SnapHealingFrontierRoot, Keys.SnapHealingFrontierData), toUpsert = Nil)

  // ========================================
  // Recovery actor scan cursors (Phase 4 A3)
  // ========================================
  //
  // BytecodeRecoveryActor and StorageRecoveryActor scan all ~90M accounts from position 0.
  // Without cursors, a crash mid-scan restarts the full O(n) scan. These keys checkpoint
  // the last committed account hash so restart resumes from the saved position.
  //
  // Write order: send ScanBatch to coordinator → write cursor (never the reverse).
  // Cleared atomically with the done-flag on successful completion.

  def getSnapBytecodeRecoveryCursor(): Option[ByteString] =
    get(Keys.BytecodeRecoveryCursor).map(v => ByteString(Hex.decode(v)))

  def putSnapBytecodeRecoveryCursor(position: ByteString): DataSourceBatchUpdate =
    put(Keys.BytecodeRecoveryCursor, Hex.toHexString(position.toArray))

  def clearSnapBytecodeRecoveryCursor(): DataSourceBatchUpdate =
    update(toRemove = Seq(Keys.BytecodeRecoveryCursor), toUpsert = Nil)

  def getSnapStorageRecoveryCursor(): Option[ByteString] =
    get(Keys.StorageRecoveryCursor).map(v => ByteString(Hex.decode(v)))

  def putSnapStorageRecoveryCursor(position: ByteString): DataSourceBatchUpdate =
    put(Keys.StorageRecoveryCursor, Hex.toHexString(position.toArray))

  def clearSnapStorageRecoveryCursor(): DataSourceBatchUpdate =
    update(toRemove = Seq(Keys.StorageRecoveryCursor), toUpsert = Nil)

  // ========================================
  // Flat database healing cursor (Phase 7 A7)
  // ========================================
  //
  // After bytecode/storage recovery completes, FlatDatabaseHealingActor issues 256 GetAccountRange
  // probes (one per first-byte segment) to detect accounts that SNAP downloaded to the MPT but
  // missed writing to flat storage. Without this, a crash mid-SNAP-download leaves the flat DB
  // incomplete, causing EVM execution to miss accounts that exist in the trie.
  //
  // The cursor stores the current segment index (0-255 as an integer string). Cleared atomically
  // with the done-flag on successful completion. Also set atomically with snapSyncDone on a clean
  // (no-crash) sync path so the healing actor is skipped on normal restarts.

  def isFlatHealingDone(): Boolean =
    get(Keys.FlatHealingDone).exists(_.toBoolean)

  def flatHealingDone(): DataSourceBatchUpdate =
    put(Keys.FlatHealingDone, true.toString)

  def getFlatHealingCursor(): Option[Int] =
    get(Keys.FlatHealingCursor).flatMap(v => scala.util.Try(v.toInt).toOption)

  def putFlatHealingCursor(segment: Int): DataSourceBatchUpdate =
    put(Keys.FlatHealingCursor, segment.toString)

  def clearFlatHealingCursor(): DataSourceBatchUpdate =
    update(toRemove = Seq(Keys.FlatHealingCursor), toUpsert = Nil)

  // SnapValidationCheckpoint stores the hex-encoded account hash of the last
  // batch committed by StateValidator.findMissingNodesStreaming. On restart,
  // the walk resumes by skipping accounts whose hash is <= the cursor, avoiding
  // re-healing already-processed accounts. Cleared on walk completion or pivot change.
  def getSnapValidationCheckpoint(): Option[ByteString] =
    get(Keys.SnapValidationCheckpoint).map(hex => ByteString(Hex.decode(hex)))

  def putSnapValidationCheckpoint(accountHash: ByteString): DataSourceBatchUpdate =
    put(Keys.SnapValidationCheckpoint, Hex.toHexString(accountHash.toArray))

  def clearSnapValidationCheckpoint(): DataSourceBatchUpdate =
    update(toRemove = Seq(Keys.SnapValidationCheckpoint), toUpsert = Nil)

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
  // separately. Each cursor is updated atomically with its corresponding storage commit so a
  // crash mid-write never leaves the cursor ahead of the data on disk.

  /** Highest backfill target the node was working toward when it last saved progress. Set when `ChainDownloader`
    * starts; cleared after `Done` so future startups don't spuriously resume.
    */
  def getBackfillTarget(): BigInt =
    getBigInt(Keys.BackfillTarget)

  def putBackfillTarget(target: BigInt): DataSourceBatchUpdate =
    put(Keys.BackfillTarget, target.toString)

  /** Highest header number whose `storeBlockHeader` commit has succeeded. */
  def getBackfillBestHeader(): BigInt =
    getBigInt(Keys.BackfillBestHeader)

  def putBackfillBestHeader(n: BigInt): DataSourceBatchUpdate =
    put(Keys.BackfillBestHeader, n.toString)

  /** Highest block number whose `storeBlockBody` commit has succeeded. May lag the header cursor. */
  def getBackfillBestBody(): BigInt =
    getBigInt(Keys.BackfillBestBody)

  def putBackfillBestBody(n: BigInt): DataSourceBatchUpdate =
    put(Keys.BackfillBestBody, n.toString)

  /** Highest block number whose `storeReceipts` commit has succeeded. May lag the body cursor. */
  def getBackfillBestReceipt(): BigInt =
    getBigInt(Keys.BackfillBestReceipt)

  def putBackfillBestReceipt(n: BigInt): DataSourceBatchUpdate =
    put(Keys.BackfillBestReceipt, n.toString)

  /** Clear all backfill cursors + target. Called on `ChainDownloader.Done` so the next startup doesn't try to resume an
    * already-completed backfill.
    */
  def clearBackfillCursors(): DataSourceBatchUpdate =
    update(
      toRemove = Seq(
        Keys.BackfillTarget,
        Keys.BackfillBestHeader,
        Keys.BackfillBestBody,
        Keys.BackfillBestReceipt
      ),
      toUpsert = Nil
    )

  /** True iff SNAP is done AND a backfill target was previously persisted AND any of the three cursors is below the
    * target. `SyncController.start()` uses this to spawn a standalone `ChainDownloader` alongside regular sync.
    */
  def needsBackfillResume(): Boolean = {
    if (!isSnapSyncDone()) return false
    val target = getBackfillTarget()
    if (target <= 0) return false
    getBackfillBestHeader() < target ||
    getBackfillBestBody() < target ||
    getBackfillBestReceipt() < target
  }
}

object AppStateStorage {
  type Key = String
  type Value = String

  object Keys {
    val BestBlockNumber = "BestBlockNumber"
    val BestBlockHash = "BestBlockHash"
    val FastSyncDone = "FastSyncDone"
    val FastSyncCooldownUntilMillis = "FastSyncCooldownUntilMillis"
    val EstimatedHighestBlock = "EstimatedHighestBlock"
    val SyncStartingBlock = "SyncStartingBlock"
    val BootstrapPivotBlock = "BootstrapPivotBlock"
    val BootstrapPivotBlockHash = "BootstrapPivotBlockHash"
    val SnapSyncDone = "SnapSyncDone"
    val SnapSyncPivotBlock = "SnapSyncPivotBlock"
    val SnapSyncStateRoot = "SnapSyncStateRoot"
    val SnapSyncProgress = "SnapSyncProgress"
    val SnapSyncTotalAccounts  = "SnapSyncTotalAccounts"
    val SnapSyncTotalBytecodes = "SnapSyncTotalBytecodes"
    val SnapSyncTotalContracts = "SnapSyncTotalContracts"
    val SnapSyncBootstrapTarget = "SnapSyncBootstrapTarget"
    val SnapFastCycleCount = "SnapFastCycleCount"
    val BytecodeRecoveryDone = "BytecodeRecoveryDone"
    val StorageRecoveryDone = "StorageRecoveryDone"
    val SnapSyncAccountsComplete = "SnapSyncAccountsComplete"
    val SnapSyncStorageComplete = "SnapSyncStorageComplete"
    val SnapSyncBytecodeComplete = "SnapSyncBytecodeComplete"
    val SnapSyncCodeHashesPath = "SnapSyncCodeHashesPath"
    val SnapSyncStorageFilePath = "SnapSyncStorageFilePath"
    val SnapSyncFinalizedRoot = "SnapSyncFinalizedRoot"
    val BackfillTarget = "BackfillTarget"
    val BackfillBestHeader = "BackfillBestHeader"
    val BackfillBestBody = "BackfillBestBody"
    val BackfillBestReceipt = "BackfillBestReceipt"
    val SnapHealingFrontierRoot = "SnapHealingFrontierRoot"
    val SnapHealingFrontierData = "SnapHealingFrontierData"
    val BytecodeRecoveryCursor = "BytecodeRecoveryCursor"
    val StorageRecoveryCursor = "StorageRecoveryCursor"
    val FlatHealingDone = "FlatHealingDone"
    val FlatHealingCursor = "FlatHealingCursor"
    val SnapValidationCheckpoint = "SnapValidationCheckpoint"
  }

}
