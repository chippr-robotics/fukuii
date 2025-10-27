package com.chipprbots.ethereum.db.storage

import akka.util.ByteString

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
  val valueDeserializer: IndexedSeq[Byte] => ChainWeight =
    (byteSequenceToBuffer _).andThen(Unpickle[ChainWeight].fromBytes)
}

object ChainWeightStorage {
  type BlockHash = ByteString
}
