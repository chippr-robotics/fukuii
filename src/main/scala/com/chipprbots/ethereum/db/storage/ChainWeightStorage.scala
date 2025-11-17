package com.chipprbots.ethereum.db.storage

import java.nio.BufferUnderflowException

import org.apache.pekko.util.ByteString

import boopickle.Default._

import com.chipprbots.ethereum.db.dataSource.DataSource
import com.chipprbots.ethereum.db.storage.ChainWeightStorage._
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.utils.ByteUtils.byteSequenceToBuffer
import com.chipprbots.ethereum.utils.ByteUtils.compactPickledBytes

/** This class is used to store the ChainWeight of blocks, by using: Key: hash of the block Value: ChainWeight
  */
class ChainWeightStorage(val dataSource: DataSource) extends TransactionalKeyValueStorage[BlockHash, ChainWeight] {
  val namespace: IndexedSeq[Byte] = Namespaces.ChainWeightNamespace
  val keySerializer: BlockHash => ByteString = identity
  val keyDeserializer: IndexedSeq[Byte] => BlockHash = bytes => ByteString(bytes: _*)
  val valueSerializer: ChainWeight => IndexedSeq[Byte] = (Pickle.intoBytes[ChainWeight] _).andThen(compactPickledBytes)
  val valueDeserializer: IndexedSeq[Byte] => ChainWeight = { bytes =>
    val buffer = byteSequenceToBuffer(bytes)
    try {
      // Try to deserialize as current format (with messScore field)
      Unpickle[ChainWeight].fromBytes(buffer)
    } catch {
      case _: BufferUnderflowException =>
        // Handle legacy format (before messScore was added)
        // Rewind buffer to try deserializing as legacy format
        buffer.rewind()
        try {
          val legacy = Unpickle[LegacyChainWeight].fromBytes(buffer)
          // Convert legacy format to current format with messScore = None
          ChainWeight(legacy.lastCheckpointNumber, legacy.totalDifficulty, None)
        } catch {
          case e: Exception =>
            throw new IllegalStateException(
              s"Failed to deserialize ChainWeight data in both current and legacy formats. Data may be corrupted.",
              e
            )
        }
      case e: Exception =>
        // Handle other deserialization errors (corrupted data, etc.)
        throw new IllegalStateException(
          s"Failed to deserialize ChainWeight data. Data may be corrupted.",
          e
        )
    }
  }
}

object ChainWeightStorage {
  type BlockHash = ByteString

  /** Legacy ChainWeight format before messScore was added (MESS implementation).
    * Used for backward-compatible deserialization of old database entries.
    */
  private[storage] case class LegacyChainWeight(
      lastCheckpointNumber: BigInt,
      totalDifficulty: BigInt
  )
}
