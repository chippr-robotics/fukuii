package com.chipprbots.ethereum.db.storage

import java.math.BigInteger

import org.apache.pekko.util.ByteString

import scala.collection.immutable.ArraySeq

import com.chipprbots.ethereum.db.dataSource.DataSource
import com.chipprbots.ethereum.db.storage.BlockHeadersStorage.BlockHeaderHash

class BlockNumberMappingStorage(val dataSource: DataSource)
    extends TransactionalKeyValueStorage[BigInt, BlockHeaderHash] {
  override val namespace: IndexedSeq[Byte] = Namespaces.HeightsNamespace

  override def keySerializer: (BigInt) => IndexedSeq[Byte] = index => ArraySeq.unsafeWrapArray(index.toByteArray)

  override def keyDeserializer: IndexedSeq[Byte] => BigInt = bytes =>
    if (bytes.isEmpty) BigInt(0)
    else new BigInt(new BigInteger(bytes.toArray))

  override def valueSerializer: (BlockHeaderHash) => IndexedSeq[Byte] = identity

  override def valueDeserializer: (IndexedSeq[Byte]) => BlockHeaderHash = arr => ByteString(arr.toArray[Byte])
}
