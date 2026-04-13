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

  def putBestBlockNumber(bestBlockNumber: BigInt): DataSourceBatchUpdate = {
    val batch = put(Keys.BestBlockNumber, bestBlockNumber.toString)
    // Write safe-block marker every 64 blocks for crash recovery
    if (bestBlockNumber > 0 && bestBlockNumber % SafeBlockInterval == 0)
      batch.and(put(Keys.LastSafeBlock, bestBlockNumber.toString))
    else batch
  }

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

  /** Clear the fast-sync cooldown circuit-breaker, allowing an immediate restart. */
  def clearFastSyncCooldown(): DataSourceBatchUpdate =
    put(Keys.FastSyncCooldownUntilMillis, "0")

  def getLastBlockImportTime(): Long =
    get(Keys.LastBlockImportTime).flatMap(v => scala.util.Try(v.toLong).toOption).getOrElse(0L)

  def putLastBlockImportTime(millis: Long): DataSourceBatchUpdate =
    put(Keys.LastBlockImportTime, millis.toString)

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

  /** Reset SNAP sync completion flag so a false-positive can be recovered by re-running SNAP */
  def resetSnapSyncDone(): DataSourceBatchUpdate =
    put(Keys.SnapSyncDone, false.toString)

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

  /** Get the persisted path to the unique codeHashes file for bytecode sync recovery. */
  def getSnapSyncCodeHashesPath(): Option[String] =
    get(Keys.SnapSyncCodeHashesPath).filter(_.nonEmpty)

  /** Persist the path to the unique codeHashes file so bytecode sync can resume after restart. */
  def putSnapSyncCodeHashesPath(path: String): DataSourceBatchUpdate =
    put(Keys.SnapSyncCodeHashesPath, path)

  /** Get the persisted path to the contract accounts file for bytecode re-feed recovery. */
  def getSnapSyncContractAccountsPath(): Option[String] =
    get(Keys.SnapSyncContractAccountsPath).filter(_.nonEmpty)

  /** Persist the path to the contract accounts file so bytecodes can be re-fed after mid-download restart. */
  def putSnapSyncContractAccountsPath(path: String): DataSourceBatchUpdate =
    put(Keys.SnapSyncContractAccountsPath, path)

  /** Get the persisted path to the contract storage file for storage sync recovery. */
  def getSnapSyncStorageFilePath(): Option[String] =
    get(Keys.SnapSyncStorageFilePath).filter(_.nonEmpty)

  /** Persist the path to the contract storage file so storage sync can resume after restart. */
  def putSnapSyncStorageFilePath(path: String): DataSourceBatchUpdate =
    put(Keys.SnapSyncStorageFilePath, path)

  /** Get the persisted path to the completed storage accounts file (R6 crash recovery). */
  def getSnapSyncCompletedStoragePath(): Option[String] =
    get(Keys.SnapSyncCompletedStoragePath).filter(_.nonEmpty)

  /** Persist the path to the completed storage accounts file for crash recovery. */
  def putSnapSyncCompletedStoragePath(path: String): DataSourceBatchUpdate =
    put(Keys.SnapSyncCompletedStoragePath, path)

  /** Get the finalized account trie root hash produced by finalizeTrie().
    * This may differ from the pivot block header's stateRoot after pivot refreshes.
    */
  def getSnapSyncFinalizedRoot(): Option[ByteString] =
    get(Keys.SnapSyncFinalizedRoot).map(v => ByteString(Hex.decode(v)))

  /** Store the finalized account trie root hash from finalizeTrie(). */
  def putSnapSyncFinalizedRoot(root: ByteString): DataSourceBatchUpdate =
    put(Keys.SnapSyncFinalizedRoot, Hex.toHexString(root.toArray))

  /** Get persisted missing trie node paths from the last healing walk result.
    * Non-empty means healing was in progress when the node last stopped.
    * Format: newline-delimited hex-encoded ByteString paths.
    */
  def getSnapSyncHealingPendingNodes(): Option[String] =
    get(Keys.SnapSyncHealingPendingNodes).filter(_.nonEmpty)

  /** Persist the trie walk result (missing node paths) so healing can resume without re-walking. */
  def putSnapSyncHealingPendingNodes(data: String): DataSourceBatchUpdate =
    put(Keys.SnapSyncHealingPendingNodes, data)

  /** Get the healing round counter at the time the node last stopped (used to restore stagnation counter). */
  def getSnapSyncHealingRound(): Int =
    get(Keys.SnapSyncHealingRound).flatMap(s => scala.util.Try(s.toInt).toOption).getOrElse(0)

  /** Persist the current healing round counter. */
  def putSnapSyncHealingRound(round: Int): DataSourceBatchUpdate =
    put(Keys.SnapSyncHealingRound, round.toString)

  /** Get the set of depth-2 subtree prefixes (hex) already completed in an in-progress trie walk.
    * Non-empty means a walk was interrupted mid-run and can be resumed by skipping these subtrees.
    */
  def getSnapSyncWalkCompletedPrefixes(): Set[String] =
    get(Keys.SnapSyncWalkCompletedPrefixes)
      .filter(_.nonEmpty)
      .map(_.split(',').toSet)
      .getOrElse(Set.empty)

  /** Persist the set of completed subtree prefixes (hex) for walk resume on crash. */
  def putSnapSyncWalkCompletedPrefixes(prefixes: Set[String]): DataSourceBatchUpdate =
    put(Keys.SnapSyncWalkCompletedPrefixes, prefixes.mkString(","))

  /** Get the state root hex for the walk-in-progress (to detect root changes on resume). */
  def getSnapSyncWalkRoot(): Option[String] =
    get(Keys.SnapSyncWalkRoot).filter(_.nonEmpty)

  /** Persist the state root hex for the walk-in-progress. */
  def putSnapSyncWalkRoot(rootHex: String): DataSourceBatchUpdate =
    put(Keys.SnapSyncWalkRoot, rootHex)

  /** Get the snapshot root hash that healing nodes were downloaded for.
    * Non-empty means a healing session was started for this specific root.
    * Used to detect stale persistence when the pivot has changed between JAR runs.
    */
  def getSnapSyncHealingSnapshotRoot(): String =
    get(Keys.SnapSyncHealingSnapshotRoot).getOrElse("")

  /** Persist the snapshot root hash for the current healing session.
    * Written at healing entry (fresh start and resume) and cleared when a walk finds 0 missing nodes.
    */
  def putSnapSyncHealingSnapshotRoot(root: String): DataSourceBatchUpdate =
    put(Keys.SnapSyncHealingSnapshotRoot, root)

  /** Get the pivot state root at which storage range sync completed.
    * L-032: if this matches the current pivot root on restart, skip StorageRangeCoordinator entirely.
    */
  def getSnapSyncStorageCompletionRoot(): Option[ByteString] =
    get(Keys.SnapSyncStorageCompletionRoot).filter(_.nonEmpty).map(v => ByteString(Hex.decode(v)))

  /** Persist the pivot state root when StorageRangeSyncComplete fires. */
  def putSnapSyncStorageCompletionRoot(root: ByteString): DataSourceBatchUpdate =
    put(Keys.SnapSyncStorageCompletionRoot, Hex.toHexString(root.toArray))

  /** Clear the storage completion root (called on restart or stale pivot). */
  def clearSnapSyncStorageCompletionRoot(): DataSourceBatchUpdate =
    put(Keys.SnapSyncStorageCompletionRoot, "")

  // --- Unclean shutdown recovery ---

  /** Check if the last shutdown was clean. Returns false on first run or after crash. */
  def isCleanShutdown(): Boolean =
    get(Keys.CleanShutdown).exists(_.toBoolean)

  /** Mark shutdown as clean (called during graceful shutdown). */
  def putCleanShutdown(clean: Boolean): DataSourceBatchUpdate =
    put(Keys.CleanShutdown, clean.toString)

  /** Get the last known-safe block number (written periodically during operation). */
  def getLastSafeBlock(): BigInt =
    getBigInt(Keys.LastSafeBlock)

  /** Store the last known-safe block number. Written every SafeBlockInterval blocks. */
  def putLastSafeBlock(blockNumber: BigInt): DataSourceBatchUpdate =
    put(Keys.LastSafeBlock, blockNumber.toString)
}

object AppStateStorage {
  type Key = String
  type Value = String

  /** Interval (in blocks) between safe-block marker writes for crash recovery.
    * Reduced from 64 to 16 to limit maximum data loss on crash to ~3 min (vs ~13 min at 64).
    */
  val SafeBlockInterval: Int = 16

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
    val SnapSyncBootstrapTarget = "SnapSyncBootstrapTarget"
    val SnapFastCycleCount = "SnapFastCycleCount"
    val BytecodeRecoveryDone = "BytecodeRecoveryDone"
    val StorageRecoveryDone = "StorageRecoveryDone"
    val SnapSyncAccountsComplete = "SnapSyncAccountsComplete"
    val SnapSyncCodeHashesPath = "SnapSyncCodeHashesPath"
    val SnapSyncContractAccountsPath = "SnapSyncContractAccountsPath"
    val SnapSyncStorageFilePath = "SnapSyncStorageFilePath"
    val SnapSyncCompletedStoragePath = "SnapSyncCompletedStoragePath"
    val SnapSyncFinalizedRoot = "SnapSyncFinalizedRoot"
    val CleanShutdown = "CleanShutdown"
    val LastSafeBlock = "LastSafeBlock"
    val SnapSyncHealingPendingNodes  = "SnapSyncHealingPendingNodes"
    val SnapSyncHealingRound         = "SnapSyncHealingRound"
    val SnapSyncWalkCompletedPrefixes = "SnapSyncWalkCompletedPrefixes"
    val SnapSyncWalkRoot              = "SnapSyncWalkRoot"
    val SnapSyncStorageCompletionRoot  = "SnapSyncStorageCompletionRoot"
    val SnapSyncHealingSnapshotRoot    = "SnapSyncHealingSnapshotRoot"
    val LastBlockImportTime            = "LastBlockImportTime"
  }

}
