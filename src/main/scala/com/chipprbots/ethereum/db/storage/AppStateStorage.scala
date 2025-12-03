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
    BlockInfo( // TODO ETCM-1090 provide the genesis hash as default
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

  /** It is safe to return zero in case of not having any checkpoint block, because we assume that genesis block is a
    * kinda stable checkpoint block (without real checkpoint)
    *
    * @return
    *   Latest CheckpointBlock Number
    */
  def getLatestCheckpointBlockNumber(): BigInt =
    getBigInt(Keys.LatestCheckpointBlockNumber)

  def removeLatestCheckpointBlockNumber(): DataSourceBatchUpdate =
    update(toRemove = Seq(Keys.LatestCheckpointBlockNumber), toUpsert = Nil)

  def putLatestCheckpointBlockNumber(latestCheckpointBlockNumber: BigInt): DataSourceBatchUpdate =
    update(Nil, Seq(Keys.LatestCheckpointBlockNumber -> latestCheckpointBlockNumber.toString))

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
    * NOTE: This is infrastructure for future use. Currently not integrated into SNAPSyncController.
    * Integration is planned to enable resumable SNAP sync after process restarts.
    * 
    * This method retrieves a JSON representation of the SNAP sync progress. The JSON format
    * is flexible and not tied to any specific case class structure, allowing for evolution
    * of the progress format over time without breaking compatibility.
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
    * NOTE: This is infrastructure for future use. Currently not integrated into SNAPSyncController.
    * Integration is planned to enable resumable SNAP sync after process restarts.
    * 
    * This method stores a JSON representation of the SNAP sync progress. The JSON format
    * is flexible and allows the caller to determine what fields to include. This design
    * provides forward/backward compatibility as the progress tracking evolves.
    * 
    * @param progressJson
    *   JSON string representation of the sync progress
    * @return
    *   DataSourceBatchUpdate for committing
    */
  def putSnapSyncProgress(progressJson: String): DataSourceBatchUpdate =
    put(Keys.SnapSyncProgress, progressJson)
}

object AppStateStorage {
  type Key = String
  type Value = String

  object Keys {
    val BestBlockNumber = "BestBlockNumber"
    val BestBlockHash = "BestBlockHash"
    val FastSyncDone = "FastSyncDone"
    val EstimatedHighestBlock = "EstimatedHighestBlock"
    val SyncStartingBlock = "SyncStartingBlock"
    val LatestCheckpointBlockNumber = "LatestCheckpointBlockNumber"
    val BootstrapPivotBlock = "BootstrapPivotBlock"
    val BootstrapPivotBlockHash = "BootstrapPivotBlockHash"
    val SnapSyncDone = "SnapSyncDone"
    val SnapSyncPivotBlock = "SnapSyncPivotBlock"
    val SnapSyncStateRoot = "SnapSyncStateRoot"
    val SnapSyncProgress = "SnapSyncProgress"
  }

}
