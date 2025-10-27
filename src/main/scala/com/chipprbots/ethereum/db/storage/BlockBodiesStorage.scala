package com.chipprbots.ethereum.db.storage

import akka.util.ByteString

import boopickle.Default.Pickle
import boopickle.Default.Unpickle

import com.chipprbots.ethereum.db.dataSource.DataSource
import com.chipprbots.ethereum.db.storage.BlockBodiesStorage.BlockBodyHash
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.utils.ByteUtils.byteSequenceToBuffer
import com.chipprbots.ethereum.utils.ByteUtils.compactPickledBytes
import com.chipprbots.ethereum.utils.Picklers._

/** This class is used to store the BlockBody, by using: Key: hash of the block to which the BlockBody belong Value: the
  * block body
  */
class BlockBodiesStorage(val dataSource: DataSource) extends TransactionalKeyValueStorage[BlockBodyHash, BlockBody] {
  import BlockBodiesStorage._

  override val namespace: IndexedSeq[Byte] = Namespaces.BodyNamespace

  override def keySerializer: BlockBodyHash => IndexedSeq[Byte] = _.toIndexedSeq

  override def keyDeserializer: IndexedSeq[Byte] => BlockBodyHash = k => ByteString.fromArrayUnsafe(k.toArray)

  override def valueSerializer: BlockBody => IndexedSeq[Byte] = blockBody =>
    compactPickledBytes(Pickle.intoBytes(blockBody))

  override def valueDeserializer: IndexedSeq[Byte] => BlockBody =
    (byteSequenceToBuffer _).andThen(Unpickle[BlockBody].fromBytes)
}

object BlockBodiesStorage {
  type BlockBodyHash = ByteString
}
