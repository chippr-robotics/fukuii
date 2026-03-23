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
    try
      // Try to deserialize as current format (totalDifficulty only)
      Unpickle[ChainWeight].fromBytes(buffer)
    catch {
      case _: BufferUnderflowException =>
        buffer.rewind()
        try {
          // Handle legacy format with (totalDifficulty, Option[messScore]) or
          // older format with (lastCheckpointNumber, totalDifficulty)
          val legacy = Unpickle[LegacyChainWeight].fromBytes(buffer)
          ChainWeight(legacy.totalDifficulty)
        } catch {
          case e: Exception =>
            throw new IllegalStateException(
              s"Failed to deserialize ChainWeight data in both current and legacy formats. Data may be corrupted.",
              e
            )
        }
      case e: Exception =>
        throw new IllegalStateException(
          s"Failed to deserialize ChainWeight data. Data may be corrupted.",
          e
        )
    }
  }
}

object ChainWeightStorage {
  type BlockHash = ByteString

  /** Legacy ChainWeight format that included lastCheckpointNumber (legacy checkpointing, now removed). Used for
    * backward-compatible deserialization of old database entries.
    */
  private[storage] case class LegacyChainWeight(
      lastCheckpointNumber: BigInt,
      totalDifficulty: BigInt
  )
}
