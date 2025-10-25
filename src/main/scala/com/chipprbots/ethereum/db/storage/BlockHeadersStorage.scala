package com.chipprbots.ethereum.db.storage

import akka.util.ByteString

import boopickle.Default.Pickle
import boopickle.Default.Unpickle

import com.chipprbots.ethereum.db.dataSource.DataSource
import com.chipprbots.ethereum.db.storage.BlockHeadersStorage.BlockHeaderHash
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.utils.ByteUtils.byteSequenceToBuffer
import com.chipprbots.ethereum.utils.ByteUtils.compactPickledBytes
import com.chipprbots.ethereum.utils.Picklers._

/** This class is used to store the BlockHeader, by using:
  *   Key: hash of the block to which the BlockHeader belong
  *   Value: the block header
  */
class BlockHeadersStorage(val dataSource: DataSource)
    extends TransactionalKeyValueStorage[BlockHeaderHash, BlockHeader] {

  import BlockHeadersStorage._

  override val namespace: IndexedSeq[Byte] = Namespaces.HeaderNamespace

  override def keySerializer: BlockHeaderHash => IndexedSeq[Byte] = _.toIndexedSeq

  override def keyDeserializer: IndexedSeq[Byte] => BlockHeaderHash = k => ByteString.fromArrayUnsafe(k.toArray)

  override def valueSerializer: BlockHeader => IndexedSeq[Byte] =
    blockHeader => compactPickledBytes(Pickle.intoBytes(blockHeader))

  override def valueDeserializer: IndexedSeq[Byte] => BlockHeader =
    // TODO: consider reusing this formula in other storages: ETCM-322
    (byteSequenceToBuffer _).andThen(Unpickle[BlockHeader].fromBytes)
}

object BlockHeadersStorage {
  type BlockHeaderHash = ByteString
}
