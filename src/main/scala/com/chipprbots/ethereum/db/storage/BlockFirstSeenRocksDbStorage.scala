package com.chipprbots.ethereum.db.storage

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.db.dataSource.DataSource
import com.chipprbots.ethereum.db.storage.Namespaces.BlockFirstSeenNamespace
import com.chipprbots.ethereum.utils.ByteStringUtils

/** RocksDB-backed implementation of BlockFirstSeenStorage.
  *
  * Stores block first-seen timestamps in a dedicated namespace to avoid conflicts
  * with other blockchain data.
  */
class BlockFirstSeenRocksDbStorage(val dataSource: DataSource) extends BlockFirstSeenStorage {

  /** Encodes a Long timestamp as bytes for storage. */
  private def encodeTimestamp(timestamp: Long): Array[Byte] =
    ByteStringUtils.longToByteString(timestamp).toArray

  /** Decodes bytes back to a Long timestamp. */
  private def decodeTimestamp(bytes: Array[Byte]): Long =
    ByteStringUtils.byteStringToLong(ByteString(bytes))

  override def put(blockHash: ByteString, timestamp: Long): Unit = {
    val key = blockHash.toArray
    val value = encodeTimestamp(timestamp)
    dataSource.update(BlockFirstSeenNamespace, Seq(), Seq(key -> value))
  }

  override def get(blockHash: ByteString): Option[Long] = {
    val key = blockHash.toArray
    dataSource.get(BlockFirstSeenNamespace, key).map(decodeTimestamp)
  }

  override def remove(blockHash: ByteString): Unit = {
    val key = blockHash.toArray
    dataSource.update(BlockFirstSeenNamespace, Seq(key), Seq())
  }
}
