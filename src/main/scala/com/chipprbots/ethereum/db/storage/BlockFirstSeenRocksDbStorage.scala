package com.chipprbots.ethereum.db.storage

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.db.dataSource.DataSource
import com.chipprbots.ethereum.db.dataSource.DataSourceUpdateOptimized
import com.chipprbots.ethereum.db.storage.Namespaces.BlockFirstSeenNamespace
import com.chipprbots.ethereum.utils.ByteStringUtils

/** RocksDB-backed implementation of BlockFirstSeenStorage.
  *
  * Stores block first-seen timestamps in a dedicated namespace to avoid conflicts
  * with other blockchain data.
  */
class BlockFirstSeenRocksDbStorage(val dataSource: DataSource) extends BlockFirstSeenStorage {

  /** Encodes a Long timestamp as bytes for storage. 
    * Note: This creates an intermediate ByteString that is immediately converted
    * to Array[Byte]. Could be optimized with direct Long-to-byte-array conversion
    * to reduce allocation overhead in the hot path.
    */
  private def encodeTimestamp(timestamp: Long): Array[Byte] =
    ByteStringUtils.longToByteString(timestamp).toArray

  /** Decodes bytes back to a Long timestamp. */
  private def decodeTimestamp(bytes: Array[Byte]): Long =
    ByteStringUtils.byteStringToLong(ByteString(bytes))

  override def put(blockHash: ByteString, timestamp: Long): Unit = {
    val key = blockHash.toArray
    val value = encodeTimestamp(timestamp)
    dataSource.update(
      Seq(
        DataSourceUpdateOptimized(
          namespace = BlockFirstSeenNamespace,
          toRemove = Seq(),
          toUpsert = Seq(key -> value)
        )
      )
    )
  }

  override def get(blockHash: ByteString): Option[Long] = {
    val key = blockHash.toArray
    dataSource.getOptimized(BlockFirstSeenNamespace, key).map(decodeTimestamp)
  }

  override def remove(blockHash: ByteString): Unit = {
    val key = blockHash.toArray
    dataSource.update(
      Seq(
        DataSourceUpdateOptimized(
          namespace = BlockFirstSeenNamespace,
          toRemove = Seq(key),
          toUpsert = Seq()
        )
      )
    )
  }
}
